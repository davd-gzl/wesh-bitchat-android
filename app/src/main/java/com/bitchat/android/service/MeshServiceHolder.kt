package com.bitchat.android.service

import android.content.Context
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.mesh.UnifiedMeshService
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.sync.GossipSyncManager

/**
 * Process-wide holder to share a single BluetoothMeshService instance
 * between the foreground service and UI (MainActivity/ViewModels).
 */
object MeshServiceHolder {
    private const val TAG = "MeshServiceHolder"
    private val owner = MeshServiceHolderOwner(MeshServiceHolderPorts<BluetoothMeshService, UnifiedMeshService, GossipSyncManager>(
        isReusable = { it.isReusable() },
        stopBluetooth = { it.stopServices() },
        refreshUnified = { it.refreshDelegates() },
        startGossip = { it.start() },
        stopGossip = { it.stop() },
        clearGossip = { it.clear() },
        installGossipDelegate = { manager, delegate -> manager.delegate = delegate },
        broadcast = { TransportBridgeService.broadcastFromLocal(RoutedPacket(it)) },
        sendToPeer = { peerID, packet -> TransportBridgeService.sendToPeerFromLocal(peerID, packet) },
        log = { level, message ->
            when (level) {
                HolderLogLevel.DEBUG -> android.util.Log.d(TAG, message)
                HolderLogLevel.INFO -> android.util.Log.i(TAG, message)
                HolderLogLevel.WARN -> android.util.Log.w(TAG, message)
                HolderLogLevel.ERROR -> android.util.Log.e(TAG, message)
            }
        }
    ))

    val sharedGossipSyncManager: GossipSyncManager? get() = owner.sharedGossipSyncManager
    val meshService: BluetoothMeshService? get() = owner.meshService
    val unifiedMeshService: UnifiedMeshService? get() = owner.unifiedMeshService

    internal fun initialProcessPreparation(): HolderProcessPreparation = owner.initialProcessPreparation()

    internal fun captureUnifiedPanicBinding(exactUnified: Any): HolderPanicBinding =
        captureHolderPanicBindingWithGossip(owner, exactUnified)

    internal fun setGossipManager(
        provenance: HolderManagerProvenance,
        mgr: GossipSyncManager,
        signer: (BitchatPacket) -> BitchatPacket?
    ) = owner.setGossipManager(provenance, mgr, signer)

    internal fun constructBluetoothProcessor(
        construction: HolderConstructionContext, creator: () -> com.bitchat.android.mesh.PacketProcessor
    ): com.bitchat.android.mesh.PacketProcessor = owner.constructBluetoothProcessor(construction, creator)

    internal fun constructWifiProcessor(
        construction: HolderWifiConstructionContext, creator: () -> com.bitchat.android.mesh.PacketProcessor
    ): com.bitchat.android.mesh.PacketProcessor = owner.constructWifiProcessor(construction, creator)

    internal fun constructBluetoothFragment(
        construction: HolderConstructionContext, creator: () -> com.bitchat.android.mesh.FragmentManager
    ): com.bitchat.android.mesh.FragmentManager = owner.constructBluetoothFragment(construction, creator)

    internal fun constructWifiFragment(
        construction: HolderWifiConstructionContext, creator: () -> com.bitchat.android.mesh.FragmentManager
    ): com.bitchat.android.mesh.FragmentManager = owner.constructWifiFragment(construction, creator)

    internal fun constructBluetoothPeer(
        construction: HolderConstructionContext, creator: () -> com.bitchat.android.mesh.PeerManager
    ): com.bitchat.android.mesh.PeerManager = owner.constructBluetoothPeer(construction, creator)

    internal fun constructWifiPeer(
        construction: HolderWifiConstructionContext, creator: () -> com.bitchat.android.mesh.PeerManager
    ): com.bitchat.android.mesh.PeerManager = owner.constructWifiPeer(construction, creator)

    internal fun constructBluetoothStoreForward(
        construction: HolderConstructionContext, creator: () -> com.bitchat.android.mesh.StoreForwardManager
    ): com.bitchat.android.mesh.StoreForwardManager = owner.constructBluetoothStoreForward(construction, creator)

    internal fun constructWifiStoreForward(
        construction: HolderWifiConstructionContext, creator: () -> com.bitchat.android.mesh.StoreForwardManager
    ): com.bitchat.android.mesh.StoreForwardManager = owner.constructWifiStoreForward(construction, creator)

    internal fun attachOriginalWifiController(original: HolderOrigin,
        participant: com.bitchat.android.wifiaware.OriginalWifiControllerParticipant) = owner.attachOriginalWifiController(original, participant)

    internal fun finishWifiInitialization(ticket: HolderWifiInitializationTicket,
        outcome: com.bitchat.android.wifiaware.WifiInitializationOutcome) = owner.finishWifiInitialization(ticket, outcome)

    internal fun constructWifiHost(context: HolderWifiConstructionContext,
        creator: () -> com.bitchat.android.wifiaware.OriginalWifiHostOwner): com.bitchat.android.wifiaware.OriginalWifiHostOwner =
        owner.constructWifiHost(context, creator)

    internal fun constructGossipManager(
        parent: HolderGossipParent,
        source: HolderGossipSource,
        creator: () -> GossipSyncManager
    ): GossipSyncManager = owner.constructGossipManager(parent, source, creator)

    fun startSharedGossip(owner: String) = this.owner.startSharedGossip(owner)
    fun stopSharedGossip(owner: String) = this.owner.stopSharedGossip(owner)

    private fun createBluetooth(
        context: Context,
        preparedEncryption: com.bitchat.android.crypto.EncryptionService?,
        construction: HolderConstructionContext
    ): BluetoothMeshService = if (preparedEncryption != null) {
        BluetoothMeshService(context.applicationContext, preparedEncryption, construction)
    } else {
        BluetoothMeshService(context.applicationContext, holderConstruction = construction)
    }

    internal fun getOrCreate(
        context: Context,
        preparedEncryption: com.bitchat.android.crypto.EncryptionService? = null
    ): HolderAcquisitionResult<BluetoothMeshService> = owner.getOrCreate { createBluetooth(context, preparedEncryption, it) }

    internal fun getUnifiedOrCreate(
        context: Context,
        preparedEncryption: com.bitchat.android.crypto.EncryptionService? = null
    ): HolderAcquisitionResult<UnifiedMeshService> = owner.getUnifiedOrCreate(
        createBluetooth = { createBluetooth(context, preparedEncryption, it) },
        createUnified = { UnifiedMeshService(context.applicationContext, it) }
    )

    internal fun <W : Any> constructWifi(
        original: HolderOrigin,
        controllerOrigin: com.bitchat.android.wifiaware.WifiAwareOwnerToken,
        creator: (HolderWifiConstructionContext) -> W
    ): com.bitchat.android.wifiaware.WifiAwareFactoryResult<W> = owner.constructWifi(original, controllerOrigin, creator)

    internal fun ensureBluetoothForWifi(context: Context, construction: HolderWifiConstructionContext) =
        owner.ensureBluetoothForWifi(construction) { createBluetooth(context, null, it) }

    internal fun selectManagerForWifi(construction: HolderWifiConstructionContext): HolderManagerRegistration<GossipSyncManager>? =
        owner.selectManagerForWifi(construction)

    fun attach(service: BluetoothMeshService) = owner.attach(service)
    fun clear() = owner.clear()
}
