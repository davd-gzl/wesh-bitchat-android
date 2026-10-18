package com.bitchat.android.service

import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.sync.GossipSyncManager

internal enum class HolderLogLevel { DEBUG, INFO, WARN, ERROR }

/** Opaque provenance, not admission or permission to open a new runtime. */
internal sealed interface HolderConstructionContext
internal class HolderEpoch internal constructor()
internal class HolderEntryOrigin internal constructor(val epoch: HolderEpoch)
internal enum class HolderConstructionKind { BLUETOOTH, UNIFIED }
internal sealed interface HolderConstructionOutcome {
    data object Dispatched : HolderConstructionOutcome
    class Returned(val product: Any) : HolderConstructionOutcome
    class Unknown(val kind: HolderConstructionKind) : HolderConstructionOutcome
}
internal data class HolderConstructionReceipt(
    val context: HolderConstructionContext,
    val entry: HolderEntryOrigin,
    val kind: HolderConstructionKind,
    val suppliedBluetooth: Any?,
    val outcome: HolderConstructionOutcome
)

/** External registration never inherits a constructor or its epoch. */
internal sealed interface HolderManagerProvenance {
    class Constructed(val context: HolderConstructionContext) : HolderManagerProvenance
    data object UnattributedExternal : HolderManagerProvenance
}

/** Its identity distinguishes even repeated registrations of the same manager. */
internal class HolderManagerRegistration<G : Any> internal constructor(
    val manager: G,
    val provenance: HolderManagerProvenance,
    val entry: HolderEntryOrigin?,
    internal val signer: (BitchatPacket) -> BitchatPacket
)

/** Retained records remain unconfirmed; these observations are not a drain acknowledgment. */
internal data class HolderProvenanceSnapshot<G : Any>(
    val activeEntryCount: Int,
    val constructions: List<HolderConstructionReceipt>,
    val registrations: List<HolderManagerRegistration<G>>,
    val externalProducts: List<Any>
)

/** Required actions; creating the owner does not open a runtime or a platform service. */
internal data class MeshServiceHolderPorts<B : Any, U : Any, G : Any>(
    val isReusable: (B) -> Boolean,
    val stopBluetooth: (B) -> Unit,
    val refreshUnified: (U) -> Unit,
    val startGossip: (G) -> Unit,
    val stopGossip: (G) -> Unit,
    val clearGossip: (G) -> Unit,
    val installGossipDelegate: (G, GossipSyncManager.Delegate) -> Unit,
    val broadcast: (BitchatPacket) -> Unit,
    val sendToPeer: (String, BitchatPacket) -> Unit,
    val log: (HolderLogLevel, String) -> Unit
)

/** Existing holder serialization and cache behavior, independent of platform construction. */
internal class MeshServiceHolderOwner<B : Any, U : Any, G : Any>(
    private val ports: MeshServiceHolderPorts<B, U, G>
) {
    // Preserve reentrant constructor -> manager registration under one legacy monitor.
    private val legacyMonitor = Any()
    @Volatile var meshService: B? = null
        private set
    @Volatile var unifiedMeshService: U? = null
        private set
    @Volatile private var currentRegistration: HolderManagerRegistration<G>? = null
    val sharedGossipSyncManager: G? get() = currentRegistration?.manager
    private val epoch = HolderEpoch()
    private val activeEntries = mutableSetOf<HolderEntryOrigin>()
    private class ConstructionContext : HolderConstructionContext
    private class ConstructionRecord(
        val context: HolderConstructionContext,
        val entry: HolderEntryOrigin,
        val kind: HolderConstructionKind,
        val suppliedBluetooth: Any?,
        var outcome: HolderConstructionOutcome = HolderConstructionOutcome.Dispatched
    )
    private val constructions = linkedMapOf<HolderConstructionContext, ConstructionRecord>()
    private val registrations = mutableListOf<HolderManagerRegistration<G>>()
    private val externalProducts = java.util.IdentityHashMap<B, Unit>()
    private val activeGossipOwners = mutableSetOf<String>()

    fun setGossipManager(
        provenance: HolderManagerProvenance,
        manager: G,
        signer: (BitchatPacket) -> BitchatPacket
    ): Unit = synchronized(legacyMonitor) {
        val entry = when (provenance) {
            is HolderManagerProvenance.Constructed -> requireNotNull(constructions[provenance.context]) {
                "Construction context does not belong to this holder"
            }.entry
            HolderManagerProvenance.UnattributedExternal -> null
        }
        val previous = sharedGossipSyncManager
        if (previous !== manager) {
            try { previous?.let(ports.stopGossip) } catch (_: Exception) { }
        }
        val registration = HolderManagerRegistration(manager, provenance, entry, signer)
        registrations += registration
        currentRegistration = registration
        ports.installGossipDelegate(manager, TransportGossipDelegate(registration, ports.broadcast, ports.sendToPeer))
        if (activeGossipOwners.isNotEmpty()) ports.startGossip(manager)
    }

    fun startSharedGossip(owner: String): Unit = synchronized(legacyMonitor) {
        val wasIdle = activeGossipOwners.isEmpty()
        activeGossipOwners.add(owner)
        if (wasIdle) sharedGossipSyncManager?.let(ports.startGossip)
    }

    fun stopSharedGossip(owner: String): Unit = synchronized(legacyMonitor) {
        activeGossipOwners.remove(owner)
        if (activeGossipOwners.isEmpty()) sharedGossipSyncManager?.let(ports.stopGossip)
    }

    private class TransportGossipDelegate<G : Any>(
        private val registration: HolderManagerRegistration<G>,
        private val broadcast: (BitchatPacket) -> Unit,
        private val sendToPeer: (String, BitchatPacket) -> Unit
    ) : GossipSyncManager.Delegate {
        override fun sendPacket(packet: BitchatPacket) = broadcast(packet)
        override fun sendPacketToPeer(peerID: String, packet: BitchatPacket) = sendToPeer(peerID, packet)
        override fun signPacketForBroadcast(packet: BitchatPacket): BitchatPacket = registration.signer(packet)
    }

    /** Factory closures are call-scoped so prepared dependencies cannot become singleton state. */
    fun getOrCreate(createBluetooth: (HolderConstructionContext) -> B): B = withEntry { entry ->
        getOrCreateLocked(entry, createBluetooth)
    }

    private fun getOrCreateLocked(entry: HolderEntryOrigin, createBluetooth: (HolderConstructionContext) -> B): B {
        val existing = meshService
        if (existing != null) {
            return try {
                if (ports.isReusable(existing)) {
                    log(HolderLogLevel.DEBUG, "Reusing existing BluetoothMeshService instance")
                    existing
                } else {
                    log(HolderLogLevel.WARN, "Existing BluetoothMeshService not reusable; replacing with a fresh instance")
                    try { ports.stopBluetooth(existing) } catch (e: Exception) {
                        log(HolderLogLevel.WARN, "Error while stopping non-reusable instance: ${e.message}")
                    }
                    val created = construct(entry, HolderConstructionKind.BLUETOOTH) { createBluetooth(it) }
                    log(HolderLogLevel.INFO, "Created new BluetoothMeshService (replacement)")
                    meshService = created
                    unifiedMeshService = null
                    created
                }
            } catch (e: Exception) {
                log(HolderLogLevel.ERROR, "Error checking service reusability; creating new instance: ${e.message}")
                val created = construct(entry, HolderConstructionKind.BLUETOOTH) { createBluetooth(it) }
                meshService = created
                unifiedMeshService = null
                created
            }
        }
        val created = construct(entry, HolderConstructionKind.BLUETOOTH) { createBluetooth(it) }
        log(HolderLogLevel.INFO, "Created new BluetoothMeshService (no existing instance)")
        meshService = created
        unifiedMeshService = null
        return created
    }

    fun getUnifiedOrCreate(createBluetooth: (HolderConstructionContext) -> B, createUnified: (B) -> U): U = withEntry { entry ->
        val bluetooth = getOrCreateLocked(entry, createBluetooth)
        val existing = unifiedMeshService
        if (existing != null) {
            ports.refreshUnified(existing)
            return@withEntry existing
        }
        val created = construct(entry, HolderConstructionKind.UNIFIED, bluetooth) { createUnified(bluetooth) }
        unifiedMeshService = created
        log(HolderLogLevel.INFO, "Created new UnifiedMeshService")
        created
    }

    fun attach(service: B): Unit = synchronized(legacyMonitor) {
        log(HolderLogLevel.DEBUG, "Attaching BluetoothMeshService to holder")
        externalProducts[service] = Unit
        meshService = service
        unifiedMeshService = null
    }

    fun clear(): Unit = synchronized(legacyMonitor) {
        log(HolderLogLevel.DEBUG, "Clearing BluetoothMeshService from holder")
        try { sharedGossipSyncManager?.let(ports.clearGossip) } catch (_: Exception) { }
        try { sharedGossipSyncManager?.let(ports.stopGossip) } catch (_: Exception) { }
        currentRegistration = null
        activeGossipOwners.clear()
        meshService = null
        unifiedMeshService = null
    }

    private fun <T> withEntry(action: (HolderEntryOrigin) -> T): T {
        val entry = HolderEntryOrigin(epoch)
        return synchronized(legacyMonitor) {
            activeEntries += entry
            try { action(entry) } finally { activeEntries -= entry }
        }
    }

    private fun <T : Any> construct(
        entry: HolderEntryOrigin,
        kind: HolderConstructionKind,
        suppliedBluetooth: Any? = null,
        factory: (HolderConstructionContext) -> T
    ): T {
        val context = ConstructionContext()
        val record = ConstructionRecord(context, entry, kind, suppliedBluetooth)
        constructions[context] = record
        val product = try {
            factory(context)
        } catch (failure: Throwable) {
            record.outcome = HolderConstructionOutcome.Unknown(kind)
            throw failure
        }
        // Record the return before caller logging can trigger the legacy retry.
        record.outcome = HolderConstructionOutcome.Returned(product)
        return product
    }

    fun provenanceSnapshot(): HolderProvenanceSnapshot<G> = synchronized(legacyMonitor) {
        HolderProvenanceSnapshot(
            activeEntries.size,
            constructions.values.map { HolderConstructionReceipt(it.context, it.entry, it.kind, it.suppliedBluetooth, it.outcome) },
            registrations.toList(),
            externalProducts.keys.toList()
        )
    }

    private fun log(level: HolderLogLevel, message: String) = ports.log(level, message)
}
