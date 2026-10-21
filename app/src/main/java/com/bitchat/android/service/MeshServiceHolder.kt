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

    fun setGossipManager(mgr: GossipSyncManager, signer: (BitchatPacket) -> BitchatPacket?) =
        owner.setGossipManager(mgr, signer)

    fun startSharedGossip(owner: String) = this.owner.startSharedGossip(owner)
    fun stopSharedGossip(owner: String) = this.owner.stopSharedGossip(owner)

    private fun createBluetooth(
        context: Context,
        preparedEncryption: com.bitchat.android.crypto.EncryptionService?
    ): BluetoothMeshService = if (preparedEncryption != null) {
        BluetoothMeshService(context.applicationContext, preparedEncryption)
    } else {
        BluetoothMeshService(context.applicationContext)
    }

    fun getOrCreate(
        context: Context,
        preparedEncryption: com.bitchat.android.crypto.EncryptionService? = null
    ): BluetoothMeshService = owner.getOrCreate { createBluetooth(context, preparedEncryption) }

    fun getUnifiedOrCreate(
        context: Context,
        preparedEncryption: com.bitchat.android.crypto.EncryptionService? = null
    ): UnifiedMeshService = owner.getUnifiedOrCreate(
        createBluetooth = { createBluetooth(context, preparedEncryption) },
        createUnified = { UnifiedMeshService(context.applicationContext, it) }
    )

    fun attach(service: BluetoothMeshService) = owner.attach(service)
    fun clear() = owner.clear()
}
