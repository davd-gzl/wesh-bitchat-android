package com.bitchat.android.sync

import android.util.Log
import com.bitchat.android.mesh.BluetoothPacketBroadcaster
import com.bitchat.android.model.RequestSyncPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

internal sealed interface GossipManagerRootCloseOutcome {
    class Closed(val receipt: GossipRootCloseReceipt) : GossipManagerRootCloseOutcome
    data object ForeignBinding : GossipManagerRootCloseOutcome
}

/**
 * Gossip-based synchronization manager using on-demand GCS filters.
 * Tracks seen public packets (ANNOUNCE, broadcast MESSAGE) and periodically requests sync
 * from neighbors. Responds to REQUEST_SYNC by sending missing packets.
 */
class GossipSyncManager internal constructor(
    private val myPeerID: String,
    private val scope: CoroutineScope,
    private val configProvider: ConfigProvider,
    private val workOwner: GossipWorkOwner,
    private val jobCreator: GossipJobCreator
) {
    interface Delegate {
        fun sendPacket(packet: BitchatPacket)
        fun sendPacketToPeer(peerID: String, packet: BitchatPacket)
        fun signPacketForBroadcast(packet: BitchatPacket): BitchatPacket?
    }

    interface ConfigProvider {
        fun seenCapacity(): Int // max packets we sync per request (cap across types)
        fun gcsMaxBytes(): Int
        fun gcsTargetFpr(): Double // percent -> 0.0..1.0
    }

    companion object {
        private const val TAG = "GossipSyncManager"
    }

    private val originalJobBinding = workOwner.associateManager(this)

    internal fun hasOriginalWorkOwner(candidate: GossipWorkOwner): Boolean = workOwner === candidate
    internal fun captureJobPanicBinding(): GossipManagerJobBinding = originalJobBinding

    /** Restricts new roots only; accepted children, jobs and direct writers remain owned separately. */
    internal fun closeJobRootsForPanic(original: GossipManagerJobBinding): GossipManagerRootCloseOutcome =
        if (original !== originalJobBinding) GossipManagerRootCloseOutcome.ForeignBinding
        else GossipManagerRootCloseOutcome.Closed(workOwner.closeJobRoots(original))

    internal fun closeDirectIngressForPanic(
        original: GossipManagerJobBinding,
        receipt: GossipRootCloseReceipt
    ): GossipDirectCloseOutcome = if (original !== originalJobBinding) GossipDirectCloseOutcome.ForeignBinding
        else workOwner.closeDirectIngress(original, receipt)

    internal fun captureEndedDirectFrontier(
        original: GossipManagerJobBinding,
        rootReceipt: GossipRootCloseReceipt,
        receipt: GossipDirectCloseReceipt
    ): GossipDirectEndedCapture = if (original !== originalJobBinding) {
        GossipDirectEndedCapture.Unconfirmed(GossipDirectFrontierGap.ForeignBinding)
    } else workOwner.captureEndedDirectFrontier(original, rootReceipt, receipt)

    internal fun validateEndedDirectFrontier(
        original: GossipManagerJobBinding,
        rootReceipt: GossipRootCloseReceipt,
        receipt: GossipDirectCloseReceipt,
        frontier: GossipDirectEndedFrontier
    ): Boolean = original === originalJobBinding &&
        workOwner.validateEndedDirectFrontier(original, rootReceipt, receipt, frontier)

    internal fun captureReturnedJobsForCancellation(
        original: GossipManagerJobBinding,
        receipt: GossipRootCloseReceipt
    ): GossipReturnedJobsCapture = if (original !== originalJobBinding) GossipReturnedJobsCapture.ForeignBinding
        else workOwner.captureReturnedJobsForCancellation(original, receipt)

    internal suspend fun prepareRecordedFrontierJoin(
        original: GossipManagerJobBinding,
        receipt: GossipRootCloseReceipt,
        snapshot: GossipReturnedJobsSnapshot
    ): GossipRecordedFrontierPreparation = if (original !== originalJobBinding) {
        GossipRecordedFrontierPreparation.Unconfirmed(GossipRecordedJoinGap.ForeignBinding)
    } else workOwner.prepareRecordedFrontierJoin(original, receipt, snapshot)

    internal suspend fun joinPreparedRecordedFrontier(frontier: GossipRecordedFrontier): GossipRecordedFrontierJoinOutcome =
        workOwner.joinPreparedRecordedFrontier(frontier)

    @Volatile var delegate: Delegate? = null

    // Defaults (configurable constants)
    private val defaultMaxBytes = SyncDefaults.DEFAULT_FILTER_BYTES
    private val defaultFpr = SyncDefaults.DEFAULT_FPR_PERCENT

    // Stored packets for sync:
    // - broadcast messages: keep up to seenCapacity() most recent, keyed by packetId
    private val messages = LinkedHashMap<String, BitchatPacket>()
    // - announcements: only keep latest per sender peerID
    private val latestAnnouncementByPeer = ConcurrentHashMap<String, Pair<String, BitchatPacket>>()

    private var periodicJob: Job? = null
    private var cleanupJob: Job? = null
    fun start() {
        val root = when (val admission = workOwner.reserveRoot(originalJobBinding, GossipRootKind.START)) {
            is GossipRootReservation.Accepted -> admission.lease
            is GossipRootReservation.Refused -> return
        }
        try {
            periodicJob?.cancel()
            val periodicTicket = workOwner.reserveChild(root, GossipWorkKind.PERIODIC_SYNC)
            periodicJob = workOwner.dispatch(periodicTicket, scope, Dispatchers.IO, jobCreator) {
                while (isActive) {
                    try {
                        delay(30_000)
                        sendRequestSync()
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { Log.e(TAG, "Periodic sync error: ${e.message}") }
                }
            }

            // Start periodic cleanup of stale announcements and messages
            cleanupJob?.cancel()
            val cleanupTicket = workOwner.reserveChild(root, GossipWorkKind.CLEANUP)
            cleanupJob = workOwner.dispatch(cleanupTicket, scope, Dispatchers.IO, jobCreator) {
                while (isActive) {
                    try {
                        delay(com.bitchat.android.util.AppConstants.Sync.CLEANUP_INTERVAL_MS)
                        pruneStaleAnnouncements()
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { Log.e(TAG, "Periodic cleanup error: ${e.message}") }
                }
            }
        } finally {
            workOwner.finishRoot(root)
        }
    }

    fun stop() {
        periodicJob?.cancel(); periodicJob = null
        cleanupJob?.cancel(); cleanupJob = null
    }

    @Synchronized
    fun clear() {
        synchronized(messages) {
            messages.clear()
        }
        latestAnnouncementByPeer.clear()
        Log.d(TAG, "Cleared all gossip sync messages and announcements")
    }

    fun scheduleInitialSync(delayMs: Long = 5_000L) {
        val root = when (val admission = workOwner.reserveRoot(originalJobBinding, GossipRootKind.INITIAL_BROADCAST)) {
            is GossipRootReservation.Accepted -> admission.lease
            is GossipRootReservation.Refused -> return
        }
        try {
            val ticket = workOwner.reserveChild(root, GossipWorkKind.INITIAL_BROADCAST)
            workOwner.dispatch(ticket, scope, Dispatchers.IO, jobCreator) {
                delay(delayMs)
                sendRequestSync()
            }
        } finally {
            workOwner.finishRoot(root)
        }
    }

    fun scheduleInitialSyncToPeer(peerID: String, delayMs: Long = 5_000L) {
        val root = when (val admission = workOwner.reserveRoot(originalJobBinding, GossipRootKind.INITIAL_PEER)) {
            is GossipRootReservation.Accepted -> admission.lease
            is GossipRootReservation.Refused -> return
        }
        try {
            val ticket = workOwner.reserveChild(root, GossipWorkKind.INITIAL_PEER)
            workOwner.dispatch(ticket, scope, Dispatchers.IO, jobCreator) {
                delay(delayMs)
                sendRequestSyncToPeer(peerID)
            }
        } finally {
            workOwner.finishRoot(root)
        }
    }

    fun onPublicPacketSeen(packet: BitchatPacket) {
        val lease = when (val admission = workOwner.reserveDirect(originalJobBinding, GossipDirectOperationKind.PublicPacketSeen)) {
            is GossipDirectReservation.Accepted -> admission.lease
            is GossipDirectReservation.Refused, GossipDirectReservation.ForeignBinding -> return
        }
        var failureKind: GossipDirectFailureKind? = null
        try {
            // Only ANNOUNCE or broadcast MESSAGE
            val mt = MessageType.fromValue(packet.type)
            val isBroadcastMessage = (mt == MessageType.MESSAGE && (packet.recipientID == null || packet.recipientID.contentEquals(SpecialRecipients.BROADCAST)))
            val isAnnouncement = (mt == MessageType.ANNOUNCE)
            if (!isBroadcastMessage && !isAnnouncement) return

            val idBytes = PacketIdUtil.computeIdBytes(packet)
            val id = idBytes.joinToString("") { b -> "%02x".format(b) }

            if (isBroadcastMessage) {
                synchronized(messages) {
                    messages[id] = packet
                    // Enforce capacity (remove oldest when exceeded)
                    val cap = configProvider.seenCapacity().coerceAtLeast(1)
                    while (messages.size > cap) {
                        val it = messages.entries.iterator()
                        if (it.hasNext()) { it.next(); it.remove() } else break
                    }
                }
            } else if (isAnnouncement) {
                // Ignore stale announcements older than STALE_PEER_TIMEOUT
                val now = System.currentTimeMillis()
                val age = now - packet.timestamp.toLong()
                if (age > com.bitchat.android.util.AppConstants.Mesh.STALE_PEER_TIMEOUT_MS) {
                    Log.d(TAG, "Ignoring stale ANNOUNCE (age=${age}ms > ${com.bitchat.android.util.AppConstants.Mesh.STALE_PEER_TIMEOUT_MS}ms)")
                    return
                }
                // senderID is fixed-size 8 bytes; map to hex string for key
                val sender = packet.senderID.joinToString("") { b -> "%02x".format(b) }
                latestAnnouncementByPeer[sender] = id to packet
                // Enforce capacity (remove oldest when exceeded)
                val cap = configProvider.seenCapacity().coerceAtLeast(1)
                while (latestAnnouncementByPeer.size > cap) {
                    val it = latestAnnouncementByPeer.entries.iterator()
                    if (it.hasNext()) { it.next(); it.remove() } else break
                }
            }
        } catch (failure: Throwable) {
            failureKind = when (failure) {
                is CancellationException -> GossipDirectFailureKind.CancellationThrown
                is Exception -> GossipDirectFailureKind.OrdinaryThrow
                else -> GossipDirectFailureKind.OtherThrowable
            }
            throw failure
        } finally {
            workOwner.noteDirectBodyEnd(lease, failureKind)
        }
    }

    internal fun sendRequestSync() {
        val payload = buildGcsPayload()

        val packet = BitchatPacket(
            type = MessageType.REQUEST_SYNC.value,
            senderID = hexStringToByteArray(myPeerID),
            timestamp = System.currentTimeMillis().toULong(),
            payload = payload,
            ttl = com.bitchat.android.util.AppConstants.SYNC_TTL_HOPS // neighbors only
        )
        // Sign and broadcast
        val target = delegate ?: return
        val signed = try {
            target.signPacketForBroadcast(packet)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return
        } ?: return
        target.sendPacket(signed)
    }

    internal fun sendRequestSyncToPeer(peerID: String) {
        val payload = buildGcsPayload()

        val packet = BitchatPacket(
            type = MessageType.REQUEST_SYNC.value,
            senderID = hexStringToByteArray(myPeerID),
            recipientID = hexStringToByteArray(peerID),
            timestamp = System.currentTimeMillis().toULong(),
            payload = payload,
            ttl = com.bitchat.android.util.AppConstants.SYNC_TTL_HOPS // neighbor only
        )
        Log.d(TAG, "Sending sync request to $peerID (${payload.size} bytes)")
        // Sign and send directly to peer
        val target = delegate ?: return
        val signed = try {
            target.signPacketForBroadcast(packet)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return
        } ?: return
        target.sendPacketToPeer(peerID, signed)
    }

    fun handleRequestSync(fromPeerID: String, request: RequestSyncPacket) {
        val lease = when (val admission = workOwner.reserveDirect(originalJobBinding, GossipDirectOperationKind.RespondRequestSync)) {
            is GossipDirectReservation.Accepted -> admission.lease
            is GossipDirectReservation.Refused, GossipDirectReservation.ForeignBinding -> return
        }
        var failureKind: GossipDirectFailureKind? = null
        try {
            // Decode GCS into sorted set for membership checks
            val sorted = GCSFilter.decodeToSortedSet(request.p, request.m, request.data)
            fun mightContain(id: ByteArray): Boolean {
                val v = GCSFilter.h64(id) % request.m
                val nonZeroV = if (v == 0L) 1L else v
                return GCSFilter.contains(sorted, nonZeroV)
            }

            // 1) Announcements: send latest per peerID if remote doesn't have them
            for ((_, pair) in latestAnnouncementByPeer.entries) {
                val (id, pkt) = pair
                val idBytes = hexToBytes(id)
                if (!mightContain(idBytes)) {
                    // Send original packet unchanged to requester only (keep local TTL)
                    val toSend = pkt.copy(ttl = com.bitchat.android.util.AppConstants.SYNC_TTL_HOPS)
                    delegate?.sendPacketToPeer(fromPeerID, toSend)
                    Log.d(TAG, "Sent sync announce: Type ${toSend.type} from ${toSend.senderID.toHexString()} to $fromPeerID packet id ${idBytes.toHexString()}")
                }
            }

            // 2) Broadcast messages: send all they lack
            val toSendMsgs = synchronized(messages) { messages.values.toList() }
            for (pkt in toSendMsgs) {
                val idBytes = PacketIdUtil.computeIdBytes(pkt)
                if (!mightContain(idBytes)) {
                    val toSend = pkt.copy(ttl = com.bitchat.android.util.AppConstants.SYNC_TTL_HOPS)
                    delegate?.sendPacketToPeer(fromPeerID, toSend)
                    Log.d(TAG, "Sent sync message: Type ${toSend.type} to $fromPeerID packet id ${idBytes.toHexString()}")
                }
            }
        } catch (failure: Throwable) {
            failureKind = when (failure) {
                is CancellationException -> GossipDirectFailureKind.CancellationThrown
                is Exception -> GossipDirectFailureKind.OrdinaryThrow
                else -> GossipDirectFailureKind.OtherThrowable
            }
            throw failure
        } finally {
            workOwner.noteDirectBodyEnd(lease, failureKind)
        }
    }

    private fun hexStringToByteArray(hexString: String): ByteArray {
        val result = ByteArray(8) { 0 }
        var tempID = hexString
        var index = 0
        while (tempID.length >= 2 && index < 8) {
            val hexByte = tempID.substring(0, 2)
            val byte = hexByte.toIntOrNull(16)?.toByte()
            if (byte != null) result[index] = byte
            tempID = tempID.substring(2)
            index++
        }
        return result
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = if (hex.length % 2 == 0) hex else "0$hex"
        val out = ByteArray(clean.length / 2)
        var i = 0
        while (i < clean.length) {
            out[i/2] = clean.substring(i, i+2).toInt(16).toByte()
            i += 2
        }
        return out
    }

    private fun buildGcsPayload(): ByteArray {
        // Collect candidates: latest announcement per peer + recent broadcast messages
        val list = ArrayList<BitchatPacket>()
        // announcements
        for ((_, pair) in latestAnnouncementByPeer) {
            list.add(pair.second)
        }
        // messages
        synchronized(messages) {
            list.addAll(messages.values)
        }
        // sort by timestamp desc, then take up to min(seenCapacity, fit capacity)
        list.sortByDescending { it.timestamp.toLong() }

        val maxBytes = try { configProvider.gcsMaxBytes() } catch (_: Exception) { defaultMaxBytes }
        val fpr = try { configProvider.gcsTargetFpr() } catch (_: Exception) { defaultFpr }
        val p = GCSFilter.deriveP(fpr)
        val nMax = GCSFilter.estimateMaxElementsForSize(maxBytes, p)
        val cap = configProvider.seenCapacity().coerceAtLeast(1)
        val takeN = minOf(nMax, cap, list.size)
        if (takeN <= 0) {
            val p0 = GCSFilter.deriveP(fpr)
            return RequestSyncPacket(p = p0, m = 1, data = ByteArray(0)).encode()
        }
        val ids = list.take(takeN).map { pkt -> PacketIdUtil.computeIdBytes(pkt) }
        val params = GCSFilter.buildFilter(ids, maxBytes, fpr)
        val mVal = if (params.m <= 0L) 1 else params.m
        return RequestSyncPacket(p = params.p, m = mVal, data = params.data).encode()
    }

    // Periodically remove stale announcements and all their messages
    private fun pruneStaleAnnouncements() {
        val now = System.currentTimeMillis()
        val stalePeers = mutableListOf<String>()

        // Identify stale announcements by age
        for ((peerID, pair) in latestAnnouncementByPeer.entries) {
            val pkt = pair.second
            val age = now - pkt.timestamp.toLong()
            if (age > com.bitchat.android.util.AppConstants.Mesh.STALE_PEER_TIMEOUT_MS) {
                stalePeers.add(peerID)
            }
        }

        if (stalePeers.isEmpty()) return

        // Remove announcements and their messages
        var totalPrunedMsgs = 0
        for (peerID in stalePeers) {
            // Count messages to be pruned for logging
            val toRemove = mutableListOf<String>()
            synchronized(messages) {
                for ((id, message) in messages) {
                    val sender = message.senderID.joinToString("") { b -> "%02x".format(b) }
                    if (sender == peerID) toRemove.add(id)
                }
            }
            totalPrunedMsgs += toRemove.size

            // Reuse existing removal which also clears announcement entry
            removeAnnouncementForPeer(peerID)
        }

        Log.d(TAG, "Pruned ${stalePeers.size} stale announcements and $totalPrunedMsgs messages")
    }

    // Explicitly remove stored announcement for a given peer (hex ID)
    fun removeAnnouncementForPeer(peerID: String) {
        val key = peerID.lowercase()
        if (latestAnnouncementByPeer.remove(key) != null) {
            Log.d(TAG, "Removed stored announcement for peer $peerID")
        }

        // Collect IDs to remove first to avoid modifying collection while iterating
        val idsToRemove = mutableListOf<String>()
        synchronized(messages) {
            for ((id, message) in messages) {
                val sender = message.senderID.joinToString("") { b -> "%02x".format(b) }
                if (sender == key) {
                    idsToRemove.add(id)
                }
            }
        }
        
        // Now remove the collected IDs
        synchronized(messages) {
            for (id in idsToRemove) {
                messages.remove(id)
            }
        }
        
        if (idsToRemove.isNotEmpty()) {
            Log.d(TAG, "Pruned ${idsToRemove.size} messages with senders without announcements")
        }
    }
}
