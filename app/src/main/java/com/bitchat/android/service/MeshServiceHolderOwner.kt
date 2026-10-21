package com.bitchat.android.service

import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.sync.GossipSyncManager

internal enum class HolderLogLevel { DEBUG, INFO, WARN, ERROR }

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
    @Volatile var sharedGossipSyncManager: G? = null
        private set
    private val activeGossipOwners = mutableSetOf<String>()

    fun setGossipManager(manager: G, signer: (BitchatPacket) -> BitchatPacket?): Unit = synchronized(legacyMonitor) {
        val previous = sharedGossipSyncManager
        if (previous !== manager) {
            try { previous?.let(ports.stopGossip) } catch (_: Exception) { }
        }
        sharedGossipSyncManager = manager
        ports.installGossipDelegate(manager, TransportGossipDelegate(signer, ports.broadcast, ports.sendToPeer))
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

    private class TransportGossipDelegate(
        private val signer: (BitchatPacket) -> BitchatPacket?,
        private val broadcast: (BitchatPacket) -> Unit,
        private val sendToPeer: (String, BitchatPacket) -> Unit
    ) : GossipSyncManager.Delegate {
        override fun sendPacket(packet: BitchatPacket) = broadcast(packet)
        override fun sendPacketToPeer(peerID: String, packet: BitchatPacket) = sendToPeer(peerID, packet)
        override fun signPacketForBroadcast(packet: BitchatPacket): BitchatPacket? = signer(packet)
    }

    /** Factory closures are call-scoped so prepared dependencies cannot become singleton state. */
    fun getOrCreate(createBluetooth: () -> B): B = synchronized(legacyMonitor) {
        val existing = meshService
        if (existing != null) {
            return@synchronized try {
                if (ports.isReusable(existing)) {
                    log(HolderLogLevel.DEBUG, "Reusing existing BluetoothMeshService instance")
                    existing
                } else {
                    log(HolderLogLevel.WARN, "Existing BluetoothMeshService not reusable; replacing with a fresh instance")
                    try { ports.stopBluetooth(existing) } catch (e: Exception) {
                        log(HolderLogLevel.WARN, "Error while stopping non-reusable instance: ${e.message}")
                    }
                    val created = createBluetooth()
                    log(HolderLogLevel.INFO, "Created new BluetoothMeshService (replacement)")
                    meshService = created
                    unifiedMeshService = null
                    created
                }
            } catch (e: Exception) {
                log(HolderLogLevel.ERROR, "Error checking service reusability; creating new instance: ${e.message}")
                val created = createBluetooth()
                meshService = created
                unifiedMeshService = null
                created
            }
        }
        val created = createBluetooth()
        log(HolderLogLevel.INFO, "Created new BluetoothMeshService (no existing instance)")
        meshService = created
        unifiedMeshService = null
        created
    }

    fun getUnifiedOrCreate(createBluetooth: () -> B, createUnified: (B) -> U): U = synchronized(legacyMonitor) {
        val bluetooth = getOrCreate(createBluetooth)
        val existing = unifiedMeshService
        if (existing != null) {
            ports.refreshUnified(existing)
            return@synchronized existing
        }
        val created = createUnified(bluetooth)
        unifiedMeshService = created
        log(HolderLogLevel.INFO, "Created new UnifiedMeshService")
        created
    }

    fun attach(service: B): Unit = synchronized(legacyMonitor) {
        log(HolderLogLevel.DEBUG, "Attaching BluetoothMeshService to holder")
        meshService = service
        unifiedMeshService = null
    }

    fun clear(): Unit = synchronized(legacyMonitor) {
        log(HolderLogLevel.DEBUG, "Clearing BluetoothMeshService from holder")
        try { sharedGossipSyncManager?.let(ports.clearGossip) } catch (_: Exception) { }
        try { sharedGossipSyncManager?.let(ports.stopGossip) } catch (_: Exception) { }
        sharedGossipSyncManager = null
        activeGossipOwners.clear()
        meshService = null
        unifiedMeshService = null
    }

    private fun log(level: HolderLogLevel, message: String) = ports.log(level, message)
}
