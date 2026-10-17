package com.bitchat.android.mesh

import android.util.Log
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.PacketPayloadLimits
import com.bitchat.android.protocol.SpecialRecipients
import com.bitchat.android.model.FragmentPayload
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages message fragmentation and reassembly - 100% iOS Compatible
 * 
 * This implementation exactly matches iOS SimplifiedBluetoothService fragmentation:
 * - Same fragment payload structure (13-byte header + data)
 * - Same MTU thresholds and fragment sizes
 * - Same reassembly logic and timeout handling
 * - Uses new FragmentPayload model for type safety
 */
class FragmentManager(
    /** When set, directed fragments for other peers are relayed without being reassembled. */
    private val myPeerID: String? = null,
    private val clock: () -> Long = System::currentTimeMillis
) {
    
    companion object {
        private const val TAG = "FragmentManager"
        // iOS values: 512 MTU threshold, 469 max fragment size (512 MTU - headers)
        private const val FRAGMENT_SIZE_THRESHOLD = com.bitchat.android.util.AppConstants.Fragmentation.FRAGMENT_SIZE_THRESHOLD // Matches iOS: if data.count > 512
        private const val MAX_FRAGMENT_SIZE = com.bitchat.android.util.AppConstants.Fragmentation.MAX_FRAGMENT_SIZE        // Matches iOS: maxFragmentSize = 469 
        private const val FRAGMENT_TIMEOUT = com.bitchat.android.util.AppConstants.Fragmentation.FRAGMENT_TIMEOUT_MS     // Matches iOS: 30 seconds cleanup
        private const val CLEANUP_INTERVAL = com.bitchat.android.util.AppConstants.Fragmentation.CLEANUP_INTERVAL_MS     // 10 seconds cleanup check
        /** Ingress key for fragments not received from a link (local callers and tests). */
        const val LOCAL_INGRESS = "local"
        /** Closed links remembered so fragments still queued from them are never kept. */
        private const val MAX_CLOSED_LINKS = 256
    }
    
    /**
     * One assembly in progress. [ingressKey] is the link that opened it: slot quotas follow
     * that link, never the sender ID a fragment claims. [bytesByLink] counts the bytes each
     * link delivered into it, for the per-link byte share.
     */
    private class AssemblyMetadata(
        val originalType: UByte,
        val total: Int,
        val timestamp: Long,
        val senderKey: String,
        val ingressKey: String,
        val bytesByLink: MutableMap<String, Int> = mutableMapOf()
    )

    // Assemblies are keyed by "<sender hex>:<fragment ID hex>" (see assemblyKey): a fragment
    // ID is only unique per sender, so a fragment never touches another sender's assembly,
    // while fragments of one assembly arriving over several links still complete it.
    // Fragment storage - iOS equivalent: incomingFragments: [String: [Int: Data]]
    private val incomingFragments = ConcurrentHashMap<String, MutableMap<Int, ByteArray>>()
    // iOS equivalent: fragmentMetadata: [String: (type: UInt8, total: Int, timestamp: Date)]
    private val fragmentMetadata = ConcurrentHashMap<String, AssemblyMetadata>()
    private val fragmentCumulativeSize = ConcurrentHashMap<String, Int>()

    private val fragmentStateLock = Any()
    private var globalBufferedBytes: Long = 0L
    // Guarded by fragmentStateLock.
    private val linkBufferedBytes = HashMap<String, Long>()
    private val closedLinks = LinkedHashSet<String>()

    private val myPeerIDBytes: ByteArray? = myPeerID?.let { id ->
        ByteArray(id.length / 2) { i -> id.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    /** False when the inbound policy refuses media: file fragments are then never buffered. */
    @Volatile
    var acceptMediaFragments: Boolean = true

    // Delegate for callbacks
    var delegate: FragmentManagerDelegate? = null
    
    // Coroutines
    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    init {
        startPeriodicCleanup()
    }
    
    /**
     * Create fragments from a large packet - 100% iOS Compatible
     * Matches iOS sendFragmentedPacket() implementation exactly
     */
    /** Generic/public packets retain the full UInt16 fragment-count range. */
    fun createFragments(packet: BitchatPacket): List<BitchatPacket> =
        createFragments(packet, 0xFFFF)

    /**
     * Create a fragment plan with a caller-selected bound. Private media uses
     * 256 for cross-platform admission; generic/public traffic retains the
     * UInt16 wire limit.
     */
    fun createFragments(packet: BitchatPacket, maxFragments: Int): List<BitchatPacket> {
        try {
            if (maxFragments !in 1..0xFFFF) {
                Log.w(TAG, "Rejecting invalid outbound fragment limit: $maxFragments")
                return emptyList()
            }
            // Fragment core bytes directly: optional transport padding may have been
            // skipped, and a real payload suffix can look like PKCS#7 padding.
            // Each outer fragment is still encoded and padded independently.
            val fullData = packet.toBinaryData(padding = false)
            if (fullData == null) {
                Log.e(TAG, "Failed to encode packet to binary data")
                return emptyList()
            }

            // iOS logic: if data.count > 512 && packet.type != MessageType.fragment.rawValue
            if (fullData.size <= FRAGMENT_SIZE_THRESHOLD) {
                return listOf(packet) // No fragmentation needed
            }

            val fragments = mutableListOf<BitchatPacket>()

            // iOS: let fragmentID = Data((0..<8).map { _ in UInt8.random(in: 0...255) })
            val fragmentID = FragmentPayload.generateFragmentID()

            // iOS: stride(from: 0, to: fullData.count, by: maxFragmentSize)
            // Calculate dynamic fragment size to fit in MTU (512)
            // Packet = Header + Sender + Recipient + Route + FragmentHeader + Payload + PaddingBuffer
            val hasRoute = packet.route != null
            val version = if (hasRoute) 2 else 1
            val headerSize = if (version == 2) 15 else 13
            val senderSize = 8
            val recipientSize = if (packet.recipientID != null) 8 else 0
            // Route: 1 byte count + 8 bytes per hop
            val routeSize = if (hasRoute) (1 + (packet.route?.size ?: 0) * 8) else 0
            val fragmentHeaderSize = 13 // FragmentPayload header
            val paddingBuffer = 16 // MessagePadding.optimalBlockSize adds 16 bytes overhead

            // 512 - Overhead
            val packetOverhead = headerSize + senderSize + recipientSize + routeSize + fragmentHeaderSize + paddingBuffer
            val maxDataSize = (512 - packetOverhead).coerceAtMost(MAX_FRAGMENT_SIZE)

            if (maxDataSize <= 0) {
                Log.e(TAG, "Calculated maxDataSize is non-positive ($maxDataSize). Route too large?")
                return emptyList()
            }

            val requiredFragments = (
                (fullData.size.toLong() + maxDataSize.toLong() - 1L) / maxDataSize.toLong()
            ).toInt()
            if (requiredFragments > maxFragments) {
                Log.w(TAG, "Rejecting outbound packet requiring $requiredFragments fragments (caller cap: $maxFragments)")
                return emptyList()
            }

            // Do not allocate chunk copies until the plan passes the hard bound.
            val fragmentChunks = stride(0, fullData.size, maxDataSize) { offset ->
                val endOffset = minOf(offset + maxDataSize, fullData.size)
                fullData.sliceArray(offset..<endOffset)
            }

            // iOS: for (index, fragment) in fragments.enumerated()
            for (index in fragmentChunks.indices) {
                val fragmentData = fragmentChunks[index]

                // Create iOS-compatible fragment payload
                val fragmentPayload = FragmentPayload(
                    fragmentID = fragmentID,
                    index = index,
                    total = fragmentChunks.size,
                    originalType = packet.type,
                    data = fragmentData
                )

                // iOS: MessageType.fragment.rawValue (single fragment type)
                // Fix: Fragments must inherit source route and use v2 if routed
                val fragmentPacket = BitchatPacket(
                    version = if (packet.route != null) 2u else 1u,
                    type = MessageType.FRAGMENT.value,
                    ttl = packet.ttl,
                    senderID = packet.senderID,
                    recipientID = packet.recipientID,
                    timestamp = packet.timestamp,
                    payload = fragmentPayload.encode(),
                    route = packet.route,
                    signature = null // iOS: signature: nil
                )

                fragments.add(fragmentPacket)
            }

            return fragments
        } catch (e: Exception) {
            Log.e(TAG, "Fragment creation failed (type=${packet.type}, payload=${packet.payload.size} bytes): ${e.message}", e)
            return emptyList()
        }
    }
    
    /** Returns the reassembled packet once the last fragment arrives, null otherwise. */
    fun handleFragment(packet: BitchatPacket, ingressKey: String = LOCAL_INGRESS): BitchatPacket? =
        (receiveFragment(packet, ingressKey) as? FragmentResult.Complete)?.packet

    /**
     * Handle incoming fragment - iOS compatible reassembly.
     *
     * [FragmentResult.Refused] marks a fragment no BitChat client sends (malformed, nested, or
     * beyond what its claimed type can carry): it is neither kept, synced nor relayed.
     * [FragmentResult.Skipped] fragments are not buffered here (addressed to another peer, media
     * refused by policy, buffers full, or in conflict with what is already held) but may still
     * be relayed.
     *
     * [ingressKey] identifies the link the fragment arrived on (see PacketProcessor.quotaKey).
     * Assembly slots are bounded per link and per (link, sender), buffered bytes per link; a
     * full quota never evicts anything, the new fragment is just not kept. The first fragment
     * received for an index or an assembly's metadata wins over any later, different one. The
     * later one is Skipped rather than Refused, as is the fragment completing a set that then
     * fails to decode: whichever copy arrived first, a forged one cannot stop the genuine one
     * from being relayed to peers that never saw the forgery.
     */
    fun receiveFragment(packet: BitchatPacket, ingressKey: String): FragmentResult {
        // iOS: guard packet.payload.count > 13 else { return }
        if (packet.payload.size < FragmentPayload.HEADER_SIZE) {
            Log.d(TAG, "Fragment packet too small: ${packet.payload.size}")
            return FragmentResult.Refused
        }

        try {
            val fragmentPayload = FragmentPayload.decode(packet.payload)
            if (fragmentPayload == null || !fragmentPayload.isValid()) {
                Log.d(TAG, "Invalid fragment payload")
                return FragmentResult.Refused
            }

            val fragmentIDString = fragmentPayload.getFragmentIDString()
            val senderKey = senderKey(packet.senderID)
            val assemblyKey = assemblyKey(senderKey, fragmentIDString)

            // A fragment never carries another fragment: no BitChat client nests them.
            if (fragmentPayload.originalType == MessageType.FRAGMENT.value) {
                Log.w(TAG, "Rejecting nested fragment for $fragmentIDString")
                return FragmentResult.Refused
            }

            val maxFragments = com.bitchat.android.util.AppConstants.Fragmentation.MAX_FRAGMENTS_PER_ID
            if (fragmentPayload.total > maxFragments) {
                Log.w(TAG, "Rejecting fragment with excessive total count: ${fragmentPayload.total} > $maxFragments")
                return FragmentResult.Refused
            }

            val recipient = packet.recipientID
            val isBroadcast = recipient == null || recipient.contentEquals(SpecialRecipients.BROADCAST)
            // Only the addressee reassembles a directed packet; relays forward its fragments.
            if (!isBroadcast && recipient != null && myPeerIDBytes != null && !recipient.contentEquals(myPeerIDBytes)) {
                return FragmentResult.Skipped
            }
            if (!acceptMediaFragments && fragmentPayload.originalType == MessageType.FILE_TRANSFER.value) {
                return FragmentResult.Skipped
            }

            // Never buffer more than the reassembled frame could decode to for its claimed type;
            // public text is held to the public text limit.
            val typeBound = if (isBroadcast && fragmentPayload.originalType == MessageType.MESSAGE.value) {
                PacketPayloadLimits.PUBLIC_TEXT_FRAME_BYTES
            } else {
                PacketPayloadLimits.maxFrameBytes(fragmentPayload.originalType)
            }
            val maxTotalBytes = minOf(
                com.bitchat.android.util.AppConstants.Fragmentation.MAX_FRAGMENT_TOTAL_BYTES,
                typeBound
            )

            synchronized(fragmentStateLock) {
                // Still queued from a link that is gone: relay it, keep nothing for it.
                if (ingressKey in closedLinks) return FragmentResult.Skipped
                val existing = fragmentMetadata[assemblyKey]
                if (existing != null &&
                    (existing.total != fragmentPayload.total || existing.originalType != fragmentPayload.originalType)
                ) {
                    // First arrival wins: a conflicting fragment never clears the assembly, and
                    // is not kept, but stays relayable.
                    Log.w(TAG, "Not buffering fragment for $fragmentIDString: inconsistent metadata")
                    return FragmentResult.Skipped
                }

                val fragmentMap: MutableMap<Int, ByteArray>
                val newSize: Int
                if (existing == null) {
                    if (fragmentPayload.data.size > maxTotalBytes) {
                        Log.w(TAG, "Rejecting fragment for $fragmentIDString: size ${fragmentPayload.data.size} exceeds cap $maxTotalBytes")
                        return FragmentResult.Refused
                    }
                    if (!admitNewAssemblyLocked(fragmentIDString, senderKey, ingressKey, fragmentPayload.data.size)) {
                        return FragmentResult.Skipped
                    }
                    fragmentMap = mutableMapOf()
                    incomingFragments[assemblyKey] = fragmentMap
                    fragmentMetadata[assemblyKey] = AssemblyMetadata(
                        originalType = fragmentPayload.originalType,
                        total = fragmentPayload.total,
                        timestamp = clock(),
                        senderKey = senderKey,
                        ingressKey = ingressKey
                    )
                    newSize = fragmentPayload.data.size
                } else {
                    val map = incomingFragments[assemblyKey]
                    val currentSize = fragmentCumulativeSize[assemblyKey]
                    if (map == null || currentSize == null) {
                        Log.w(TAG, "Dropping fragment set $fragmentIDString due to missing state")
                        removeFragmentSetLocked(assemblyKey)
                        return FragmentResult.Skipped
                    }
                    map[fragmentPayload.index]?.let { stored ->
                        // First arrival wins: an identical duplicate changes nothing, a
                        // different payload for a received index is not kept but stays relayable.
                        if (stored.contentEquals(fragmentPayload.data)) return FragmentResult.Pending
                        Log.w(TAG, "Not buffering fragment for $fragmentIDString: index ${fragmentPayload.index} already received")
                        return FragmentResult.Skipped
                    }
                    val grown = currentSize + fragmentPayload.data.size
                    if (grown > maxTotalBytes) {
                        Log.w(TAG, "Rejecting fragment for $fragmentIDString: cumulative size $grown exceeds cap $maxTotalBytes")
                        // Only the link that opened the assembly can overflow it away. Another
                        // link may be overflowing it with the genuine copy: relay that one.
                        if (existing.ingressKey != ingressKey) return FragmentResult.Skipped
                        removeFragmentSetLocked(assemblyKey)
                        return FragmentResult.Refused
                    }
                    if (!hasByteRoomLocked(fragmentIDString, ingressKey, fragmentPayload.data.size)) {
                        return FragmentResult.Skipped
                    }
                    fragmentMap = map
                    newSize = grown
                }

                fragmentMap[fragmentPayload.index] = fragmentPayload.data
                fragmentCumulativeSize[assemblyKey] = newSize
                globalBufferedBytes += fragmentPayload.data.size
                fragmentMetadata[assemblyKey]?.bytesByLink?.merge(ingressKey, fragmentPayload.data.size) { a, b -> a + b }
                linkBufferedBytes.merge(ingressKey, fragmentPayload.data.size.toLong()) { a, b -> a + b }

                val expectedTotal = fragmentMetadata[assemblyKey]?.total ?: fragmentPayload.total
                if (fragmentMap.size < expectedTotal) return FragmentResult.Pending

                val reassembledData = java.io.ByteArrayOutputStream(newSize)
                for (i in 0 until expectedTotal) {
                    fragmentMap[i]?.let { data -> reassembledData.write(data) }
                }
                removeFragmentSetLocked(assemblyKey)

                // The reassembled packet must be the type its fragments announced.
                val originalPacket = BitchatPacket.fromBinaryData(reassembledData.toByteArray())
                    ?.takeIf { it.type == fragmentPayload.originalType }
                if (originalPacket == null) {
                    Log.e(TAG, "Failed to decode reassembled packet (type=${fragmentPayload.originalType}, total=$expectedTotal)")
                    // A lone fragment is malformed by itself. In a larger set, another copy may
                    // have spoiled an index first: this fragment may be genuine, relay it.
                    return if (expectedTotal == 1) FragmentResult.Refused else FragmentResult.Skipped
                }
                return FragmentResult.Complete(originalPacket.copy(ttl = 0u.toUByte()))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle fragment: ${e.message}")
        }

        return FragmentResult.Refused
    }

    /**
     * Admits a new assembly opened by [ingressKey] for [senderKey]. Nothing is ever evicted to
     * make room: the claimed sender is not authenticated, so evicting would let anyone push out
     * a real sender's assembly. A full (link, sender), link or global quota just refuses.
     */
    private fun admitNewAssemblyLocked(fragmentIDString: String, senderKey: String, ingressKey: String, bytes: Int): Boolean {
        val limits = com.bitchat.android.util.AppConstants.Fragmentation
        if (!hasByteRoomLocked(fragmentIDString, ingressKey, bytes)) return false
        val linkSets = fragmentMetadata.values.filter { it.ingressKey == ingressKey }
        if (linkSets.count { it.senderKey == senderKey } >= limits.MAX_ACTIVE_FRAGMENT_SETS_PER_LINK_SENDER) {
            Log.w(TAG, "Not buffering fragment set $fragmentIDString: sender at ${limits.MAX_ACTIVE_FRAGMENT_SETS_PER_LINK_SENDER} active fragment sets on this link")
            return false
        }
        if (linkSets.size >= limits.MAX_ACTIVE_FRAGMENT_SETS_PER_LINK) {
            Log.w(TAG, "Not buffering fragment set $fragmentIDString: link at ${limits.MAX_ACTIVE_FRAGMENT_SETS_PER_LINK} active fragment sets")
            return false
        }
        if (incomingFragments.size >= limits.MAX_ACTIVE_FRAGMENT_SETS) {
            Log.w(TAG, "Not buffering fragment set $fragmentIDString: active fragment sets at ${limits.MAX_ACTIVE_FRAGMENT_SETS}")
            return false
        }
        return true
    }

    /** True when [bytes] more from [ingressKey] fit both the global and the per-link byte caps. */
    private fun hasByteRoomLocked(fragmentIDString: String, ingressKey: String, bytes: Int): Boolean {
        val limits = com.bitchat.android.util.AppConstants.Fragmentation
        if (globalBufferedBytes + bytes > limits.MAX_GLOBAL_FRAGMENT_TOTAL_BYTES) {
            Log.w(TAG, "Not buffering fragment for $fragmentIDString: global buffered bytes exceed cap ${limits.MAX_GLOBAL_FRAGMENT_TOTAL_BYTES}")
            return false
        }
        if ((linkBufferedBytes[ingressKey] ?: 0L) + bytes > limits.MAX_FRAGMENT_BYTES_PER_LINK) {
            Log.w(TAG, "Not buffering fragment for $fragmentIDString: link buffered bytes exceed cap ${limits.MAX_FRAGMENT_BYTES_PER_LINK}")
            return false
        }
        return true
    }

    private fun senderKey(senderID: ByteArray): String =
        senderID.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun assemblyKey(senderKey: String, fragmentIDString: String): String =
        "$senderKey:$fragmentIDString"

    private fun removeFragmentSetLocked(assemblyKey: String) {
        incomingFragments.remove(assemblyKey)
        fragmentMetadata.remove(assemblyKey)?.bytesByLink?.forEach { (link, bytes) ->
            val left = (linkBufferedBytes[link] ?: 0L) - bytes
            if (left > 0L) linkBufferedBytes[link] = left else linkBufferedBytes.remove(link)
        }
        val bytes = fragmentCumulativeSize.remove(assemblyKey)?.toLong() ?: 0L
        if (bytes != 0L) {
            globalBufferedBytes = (globalBufferedBytes - bytes).coerceAtLeast(0L)
        }
    }
    
    /**
     * Helper function to match iOS stride functionality
     * stride(from: 0, to: fullData.count, by: maxFragmentSize)
     */
    private fun <T> stride(from: Int, to: Int, by: Int, transform: (Int) -> T): List<T> {
        val result = mutableListOf<T>()
        var current = from
        while (current < to) {
            result.add(transform(current))
            current += by
        }
        return result
    }
    
    /**
     * iOS cleanup - exactly matching performCleanup() implementation
     * Clean old fragments (> 30 seconds old)
     */
    @androidx.annotation.VisibleForTesting
    internal fun cleanupOldFragments() {
        synchronized(fragmentStateLock) {
            val now = clock()
            val cutoff = now - FRAGMENT_TIMEOUT

            // iOS: let oldFragments = fragmentMetadata.filter { $0.value.timestamp < cutoff }.map { $0.key }
            val oldFragments = fragmentMetadata.filter { it.value.timestamp < cutoff }.map { it.key }

            for (fragmentID in oldFragments) {
                removeFragmentSetLocked(fragmentID)
            }
        }
    }
    
    /**
     * Get debug information - matches iOS debugging
     */
    fun getDebugInfo(): String {
        synchronized(fragmentStateLock) {
            return buildString {
                appendLine("=== Fragment Manager Debug Info (iOS Compatible) ===")
                appendLine("Active Fragment Sets: ${incomingFragments.size}")
                appendLine("Fragment Size Threshold: $FRAGMENT_SIZE_THRESHOLD bytes")
                appendLine("Max Fragment Size: $MAX_FRAGMENT_SIZE bytes")
                appendLine("Global Buffered Bytes: $globalBufferedBytes")

                fragmentMetadata.forEach { (fragmentID, metadata) ->
                    val originalType = metadata.originalType
                    val totalFragments = metadata.total
                    val timestamp = metadata.timestamp
                    val received = incomingFragments[fragmentID]?.size ?: 0
                    val ageSeconds = (clock() - timestamp) / 1000
                    val bytes = fragmentCumulativeSize[fragmentID] ?: 0
                    appendLine("  - $fragmentID: $received/$totalFragments fragments, bytes=$bytes, type: $originalType, age: ${ageSeconds}s")
                }
            }
        }
    }
    
    /**
     * Start periodic cleanup of old fragments - matches iOS maintenance timer
     */
    private fun startPeriodicCleanup() {
        managerScope.launch {
            while (isActive) {
                delay(CLEANUP_INTERVAL)
                cleanupOldFragments()
            }
        }
    }
    
    /**
     * Drops the assemblies [ingressKey] opened. Each reconnection gets a new link ID, so without
     * this a radio reconnecting a few times would hold every global slot until they expire.
     * Fragments still queued from the closed link are relayed but not kept.
     */
    fun onIngressLinkClosed(ingressKey: String) {
        synchronized(fragmentStateLock) {
            closedLinks.remove(ingressKey)
            closedLinks.add(ingressKey)
            if (closedLinks.size > MAX_CLOSED_LINKS) closedLinks.remove(closedLinks.first())
            fragmentMetadata.filterValues { it.ingressKey == ingressKey }.keys.forEach { removeFragmentSetLocked(it) }
        }
    }

    /**
     * Clear all fragments
     */
    fun clearAllFragments() {
        synchronized(fragmentStateLock) {
            incomingFragments.clear()
            fragmentMetadata.clear()
            fragmentCumulativeSize.clear()
            linkBufferedBytes.clear()
            globalBufferedBytes = 0L
        }
    }
    
    /**
     * Shutdown the manager
     */
    fun shutdown() {
        managerScope.cancel()
        clearAllFragments()
    }
}

/** Outcome of receiving one fragment. */
sealed class FragmentResult {
    /** Out of bounds or malformed: not kept, synced or relayed. */
    object Refused : FragmentResult()
    /** Not buffered here; relay rules still apply. */
    object Skipped : FragmentResult()
    /** Buffered, waiting for the remaining fragments. */
    object Pending : FragmentResult()
    data class Complete(val packet: BitchatPacket) : FragmentResult()

    /** Only a fragment accepted for reassembly is kept for gossip sync. */
    val keptForSync: Boolean get() = this is Pending || this is Complete
}

/**
 * Delegate interface for fragment manager callbacks
 */
interface FragmentManagerDelegate {
    fun onPacketReassembled(packet: BitchatPacket)
}
