package com.bitchat.android.mesh

import android.content.Context
import android.util.Log
import com.bitchat.android.favorites.FavoriteControlMessage
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.noise.NoiseSession
import com.bitchat.android.wifiaware.WifiAwareController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.IdentityHashMap

/**
 * Feature-facing mesh service that hides local transport selection from the rest of the app.
 *
 * BLE remains the canonical origin for broadcast packets when it is enabled so existing BLE mesh
 * behavior and bridge semantics stay intact. Addressed Noise traffic is routed over whichever
 * local transport already has the peer/session, falling back to a connected transport handshake.
 */
class UnifiedMeshService internal constructor(
    private val bluetooth: MeshService,
    private val ports: UnifiedMeshPorts
) : MeshService, BluetoothMeshDelegate {

    constructor(context: Context, bluetooth: BluetoothMeshService) : this(BluetoothMeshPort(bluetooth), livePorts(context, bluetooth))

    companion object {
        private const val TAG = "UnifiedMeshService"

        private fun livePorts(context: Context, bluetooth: BluetoothMeshService): UnifiedMeshPorts {
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val powerManager = PowerManager.getInstance(context.applicationContext)
            return UnifiedMeshPorts(
                scope = scope,
                announcementProfile = powerManager.profile.map { it.meshAnnouncementIntervalMs to it.hasDirectPeers },
                hasAnnouncementRecipients = { powerManager.profile.value.hasDirectPeers },
                currentWifi = { WifiAwareController.getService() },
                startWifi = { WifiAwareController.startIfPossible() },
                stopWifi = { WifiAwareController.stop() },
                bleEnabled = {
                    try {
                        com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().bleEnabled.value
                    } catch (_: Exception) {
                        try { com.bitchat.android.ui.debug.DebugPreferenceManager.getBleEnabled(true) } catch (_: Exception) { true }
                    }
                },
                disableBle = { bluetooth.setBleTransportEnabled(false) },
                currentNpub = { com.bitchat.android.nostr.NostrIdentityBridge.getCurrentNostrIdentity(context)?.npub }
            )
        }
    }

    private val serviceScope = ports.scope
    private val ownerLock = Any()
    @Volatile private var retiredForPanic = false
    @Volatile private var currentDelegate: MeshDelegate? = null
    private var delegateGeneration = 0L
    private var retirementFailed = false
    private var announcementJob: Job? = null
    private var panicWifi: MeshService? = null
    private var activeCalls = 0
    private var idle = CompletableDeferred<Unit>().apply { complete(Unit) }
    private val bindingTargets = IdentityHashMap<MeshService, Unit>()
    private var retirement: Retirement? = null

    private class Retirement(
        val ownedJob: Job?,
        val idle: CompletableDeferred<Unit>,
        val targets: List<MeshService>,
        val completion: CompletableDeferred<Boolean> = CompletableDeferred()
    )

    private class Binding(val target: MeshService, val value: MeshDelegate?, val generation: Long)

    /**
     * Called by CVM's independent viewModelScope, never from this facade's admitted callback or
     * owned announcement scope. Controller, transport and Core task ownership are separate.
     */
    internal suspend fun retireForPanic(): Boolean {
        val (attempt, leader) = synchronized(ownerLock) {
            val existing = retirement
            if (existing != null) existing to false else {
                // currentWifi is a RAM identity snapshot, never a factory, binder or platform call.
                try { panicWifi = ports.currentWifi() } catch (_: Exception) { retirementFailed = true }
                retiredForPanic = true
                currentDelegate = null
                val targets = mutableListOf(bluetooth)
                fun retain(target: MeshService) {
                    if (targets.none { it === target }) targets.add(target)
                }
                panicWifi?.let(::retain)
                bindingTargets.keys.forEach(::retain)
                val ownedJob = serviceScope.coroutineContext[Job]
                if (ownedJob == null) retirementFailed = true
                Retirement(ownedJob, idle, targets).also { retirement = it } to true
            }
        }
        // No monitor is held across cancellation, completion, external setters or either await.
        return withContext(NonCancellable) {
            if (!leader) attempt.completion.await() else finishRetirement(attempt)
        }
    }

    private suspend fun finishRetirement(attempt: Retirement): Boolean {
        var result = false
        try {
            val cancelled = try {
                attempt.ownedJob?.cancel()
                true
            } catch (_: Exception) {
                failRetirement()
                false
            }
            attempt.idle.await()
            if (cancelled) {
                try { attempt.ownedJob?.join() } catch (_: Exception) { failRetirement() }
            }
            // A pre-fence refresh may have been inside an external setter. Detach only after it
            // finishes, using its recorded identity, and preserve a different facade's delegate.
            attempt.targets.forEach { target ->
                try {
                    if (target.delegate === this) target.delegate = null
                } catch (_: Exception) { failRetirement() }
            }
            result = synchronized(ownerLock) {
                attempt.ownedJob != null && attempt.ownedJob.isCompleted && !retirementFailed
            }
        } finally {
            attempt.completion.complete(result)
        }
        return result
    }

    private fun failRetirement() { synchronized(ownerLock) { retirementFailed = true } }

    override val myPeerID: String
        get() = bluetooth.myPeerID

    override var delegate: MeshDelegate?
        get() = currentDelegate
        set(value) {
            val accepted = synchronized(ownerLock) {
                if (retiredForPanic) false else {
                    currentDelegate = value
                    delegateGeneration++
                    true
                }
            }
            if (accepted) refreshDelegates()
        }

    private fun admitCallLocked() {
        if (activeCalls == 0) idle = CompletableDeferred()
        activeCalls++
    }

    private fun releaseCall() {
        val completed = synchronized(ownerLock) {
            activeCalls--
            check(activeCalls >= 0)
            if (activeCalls == 0) idle else null
        }
        // Completing a deferred may invoke continuations immediately; keep it outside the monitor.
        completed?.complete(Unit)
    }

    private inline fun <T> withActiveOwner(fallback: T, action: () -> T): T {
        val admitted = synchronized(ownerLock) {
            if (retiredForPanic) false else { admitCallLocked(); true }
        }
        if (!admitted) return fallback
        try { return action() } finally { releaseCall() }
    }

    private fun bindDelegate(target: MeshService, clearWhenAbsent: Boolean, force: Boolean) {
        var binding = synchronized(ownerLock) {
            if (retiredForPanic || (!clearWhenAbsent && currentDelegate == null)) null else {
                admitCallLocked()
                // Keep every identity, including a setter that throws or a formerly current WiFi.
                bindingTargets[target] = Unit
                Binding(target, if (currentDelegate != null) this else null, delegateGeneration)
            }
        } ?: return
        try {
            while (!retiredForPanic) {
                if (force || binding.target.delegate !== binding.value) {
                    binding.target.delegate = binding.value
                }
                // An older refresh finishing last must not restore a superseded null/non-null
                // binding. Reconcile the RAM generation without holding the lock in a setter.
                binding = synchronized(ownerLock) {
                    if (retiredForPanic || delegateGeneration == binding.generation) null else {
                        Binding(target, if (currentDelegate != null) this else null, delegateGeneration)
                    }
                } ?: break
            }
        } finally {
            releaseCall()
        }
    }

    fun refreshDelegates() {
        withActiveOwner(Unit) {
            try { bindDelegate(bluetooth, clearWhenAbsent = true, force = true) } catch (_: Exception) { }
            try { wifiService()?.let { bindDelegate(it, clearWhenAbsent = true, force = true) } } catch (_: Exception) { }
        }
    }

    override fun startServices() {
        withActiveOwner(Unit) {
            if (isBleEnabled()) {
                try { bluetooth.startServices() } catch (e: Exception) {
                    Log.w(TAG, "Failed to start BLE transport: ${e.message}")
                }
            } else {
                try { ports.disableBle() } catch (_: Exception) { }
            }
            if (retiredForPanic) return@withActiveOwner
            try { ports.startWifi() } catch (e: Exception) {
                Log.w(TAG, "Failed to start Wi-Fi Aware transport: ${e.message}")
            }
            if (retiredForPanic) return@withActiveOwner
            startAnnouncementScheduler()
            refreshDelegates()
        }
    }

    override fun stopServices() {
        withActiveOwner(Unit) {
            val stopped = synchronized(ownerLock) { announcementJob.also { announcementJob = null } }
            stopped?.cancel()
            if (retiredForPanic) return@withActiveOwner
            try { bluetooth.stopServices() } catch (_: Exception) { }
            if (retiredForPanic) return@withActiveOwner
            try { ports.stopWifi() } catch (_: Exception) { }
        }
    }

    private fun startAnnouncementScheduler() {
        val started = synchronized(ownerLock) {
            if (retiredForPanic || announcementJob?.isCompleted == false) null else {
                // Lazy construction registers the owned Job without executing callbacks in this lock.
                serviceScope.launch(start = CoroutineStart.LAZY) {
                    ports.announcementProfile
                        .distinctUntilChanged()
                        .collectLatest { (intervalMs, hasRecipients) ->
                            if (!hasRecipients) return@collectLatest
                            // Wait the full cadence interval; connection paths announce immediately.
                            while (isActive) {
                                delay(intervalMs)
                                if (ports.hasAnnouncementRecipients()) sendBroadcastAnnounce()
                            }
                        }
                }.also { announcementJob = it }
            }
        }
        started?.start()
    }

    override fun sendMessage(content: String, mentions: List<String>) {
        withActiveOwner(Unit) {
            when {
                isBleEnabled() -> bluetooth.sendMessage(content, mentions)
                else -> wifiService()?.sendMessage(content, mentions)
            }
        }
    }

    override fun sendPrivateMessage(
        content: String,
        recipientPeerID: String,
        recipientNickname: String,
        messageID: String?
    ) {
        withActiveOwner(Unit) {
            when {
                isBleReady(recipientPeerID) -> bluetooth.sendPrivateMessage(content, recipientPeerID, recipientNickname, messageID)
                isWifiReady(recipientPeerID) -> wifiService()?.sendPrivateMessage(content, recipientPeerID, recipientNickname, messageID)
                isBleConnected(recipientPeerID) || (isBleEnabled() && !isWifiConnected(recipientPeerID)) ->
                    bluetooth.sendPrivateMessage(content, recipientPeerID, recipientNickname, messageID)
                else -> wifiService()?.sendPrivateMessage(content, recipientPeerID, recipientNickname, messageID)
            }
        }
    }

    override suspend fun sendPrivateMessageBound(content: String, recipientPeerID: String, expectedKey: ByteArray,
        messageID: String, authorized: () -> Boolean): Boolean {
        if (retiredForPanic) return false
        return bluetooth.sendPrivateMessageBound(content, recipientPeerID, expectedKey, messageID) {
            // This callback may run under a transport lock: do not acquire ownerLock here.
            !retiredForPanic && authorized() && !retiredForPanic
        }
    }

    override fun sendReadReceipt(messageID: String, recipientPeerID: String, readerNickname: String) {
        withActiveOwner(Unit) {
            when {
                isBleReady(recipientPeerID) -> bluetooth.sendReadReceipt(messageID, recipientPeerID, readerNickname)
                isWifiReady(recipientPeerID) -> wifiService()?.sendReadReceipt(messageID, recipientPeerID, readerNickname)
            }
        }
    }

    override fun sendFavoriteNotification(peerID: String, isFavorite: Boolean) {
        withActiveOwner(Unit) {
            val myNpub = try {
                ports.currentNpub()
            } catch (_: Exception) {
                null
            }
            val content = FavoriteControlMessage.encode(isFavorite, myNpub)
            val nickname = getPeerNicknames()[peerID] ?: peerID
            if (hasEstablishedSession(peerID)) {
                sendPrivateMessage(content, peerID, nickname, java.util.UUID.randomUUID().toString())
            }
        }
    }

    override fun sendVerifyChallenge(peerID: String, noiseKeyHex: String, nonceA: ByteArray) {
        withActiveOwner(Unit) {
            when {
                isBleReady(peerID) -> bluetooth.sendVerifyChallenge(peerID, noiseKeyHex, nonceA)
                isWifiReady(peerID) -> wifiService()?.sendVerifyChallenge(peerID, noiseKeyHex, nonceA)
            }
        }
    }

    override fun sendVerifyResponse(peerID: String, noiseKeyHex: String, nonceA: ByteArray) {
        withActiveOwner(Unit) {
            when {
                isBleReady(peerID) -> bluetooth.sendVerifyResponse(peerID, noiseKeyHex, nonceA)
                isWifiReady(peerID) -> wifiService()?.sendVerifyResponse(peerID, noiseKeyHex, nonceA)
            }
        }
    }

    override fun sendBroadcastAnnounce() {
        withActiveOwner(Unit) {
            if (isBleEnabled()) {
                try { bluetooth.sendBroadcastAnnounce() } catch (_: Exception) { }
            }
            try { wifiService()?.sendBroadcastAnnounce() } catch (_: Exception) { }
        }
    }

    override fun sendAnnouncementToPeer(peerID: String) {
        withActiveOwner(Unit) {
            when {
                isBleConnected(peerID) || (isBleEnabled() && !isWifiConnected(peerID)) -> bluetooth.sendAnnouncementToPeer(peerID)
                else -> wifiService()?.sendAnnouncementToPeer(peerID)
            }
        }
    }

    override fun getPeerNicknames(): Map<String, String> {
        val merged = linkedMapOf<String, String>()
        try { merged.putAll(wifiService()?.getPeerNicknames().orEmpty()) } catch (_: Exception) { }
        try { merged.putAll(bluetooth.getPeerNicknames()) } catch (_: Exception) { }
        return merged
    }

    override fun getPeerRSSI(): Map<String, Int> {
        val merged = linkedMapOf<String, Int>()
        try { merged.putAll(wifiService()?.getPeerRSSI().orEmpty()) } catch (_: Exception) { }
        try { merged.putAll(bluetooth.getPeerRSSI()) } catch (_: Exception) { }
        return merged
    }

    override fun getActivePeerCount(): Int {
        return mergedPeerIDs().filter { it != myPeerID }.distinct().size
    }

    override fun hasEstablishedSession(peerID: String): Boolean {
        return isBleReady(peerID) || isWifiReady(peerID)
    }

    override fun getSessionState(peerID: String): NoiseSession.NoiseSessionState {
        val bleState = try { bluetooth.getSessionState(peerID) } catch (_: Exception) { NoiseSession.NoiseSessionState.Uninitialized }
        val wifiState = try { wifiService()?.getSessionState(peerID) } catch (_: Exception) { null }
        return when {
            bleState is NoiseSession.NoiseSessionState.Established -> bleState
            wifiState is NoiseSession.NoiseSessionState.Established -> wifiState
            bleState is NoiseSession.NoiseSessionState.Handshaking -> bleState
            wifiState is NoiseSession.NoiseSessionState.Handshaking -> wifiState
            bleState !is NoiseSession.NoiseSessionState.Uninitialized -> bleState
            wifiState != null -> wifiState
            else -> bleState
        }
    }

    override fun initiateNoiseHandshake(peerID: String) {
        withActiveOwner(Unit) {
            when {
                isBleConnected(peerID) -> bluetooth.initiateNoiseHandshake(peerID)
                isWifiConnected(peerID) -> wifiService()?.initiateNoiseHandshake(peerID)
                isBleEnabled() -> bluetooth.initiateNoiseHandshake(peerID)
                else -> wifiService()?.initiateNoiseHandshake(peerID)
            }
        }
    }

    override fun getPeerFingerprint(peerID: String): String? {
        return try { bluetooth.getPeerFingerprint(peerID) } catch (_: Exception) { null }
            ?: try { wifiService()?.getPeerFingerprint(peerID) } catch (_: Exception) { null }
    }

    override fun getPeerInfo(peerID: String): PeerInfo? {
        val ble = try { bluetooth.getPeerInfo(peerID) } catch (_: Exception) { null }
        val wifi = try { wifiService()?.getPeerInfo(peerID) } catch (_: Exception) { null }
        return when {
            ble?.isConnected == true && hasEstablishedSessionOnBluetooth(peerID) -> ble
            wifi?.isConnected == true && wifiService()?.hasEstablishedSession(peerID) == true -> wifi
            ble?.isConnected == true -> ble
            wifi?.isConnected == true -> wifi
            else -> ble ?: wifi
        }
    }

    override fun updatePeerInfo(
        peerID: String,
        nickname: String,
        noisePublicKey: ByteArray,
        signingPublicKey: ByteArray,
        isVerified: Boolean
    ): Boolean {
        return withActiveOwner(false) {
            val bleUpdated = try {
                bluetooth.updatePeerInfo(peerID, nickname, noisePublicKey, signingPublicKey, isVerified)
            } catch (_: Exception) {
                false
            }
            val wifiUpdated = try {
                wifiService()?.updatePeerInfo(peerID, nickname, noisePublicKey, signingPublicKey, isVerified) == true
            } catch (_: Exception) {
                false
            }
            bleUpdated || wifiUpdated
        }
    }

    override fun getIdentityFingerprint(): String = bluetooth.getIdentityFingerprint()

    override fun getAuthenticatedRemoteStaticKey(peerID: String): ByteArray? =
        bluetooth.getAuthenticatedRemoteStaticKey(peerID)

    override fun getStaticNoisePublicKey(): ByteArray? {
        return bluetooth.getStaticNoisePublicKey() ?: wifiService()?.getStaticNoisePublicKey()
    }

    override fun shouldShowEncryptionIcon(peerID: String): Boolean {
        return hasEstablishedSession(peerID)
    }

    override fun getEncryptedPeers(): List<String> {
        val encrypted = linkedSetOf<String>()
        try { encrypted.addAll(bluetooth.getEncryptedPeers()) } catch (_: Exception) { }
        try { encrypted.addAll(wifiService()?.getEncryptedPeers().orEmpty()) } catch (_: Exception) { }
        mergedPeerIDs().filterTo(encrypted) { hasEstablishedSession(it) }
        return encrypted.toList()
    }

    override fun getDeviceAddressForPeer(peerID: String): String? {
        return try { bluetooth.getDeviceAddressForPeer(peerID) } catch (_: Exception) { null }
            ?: try { wifiService()?.getDeviceAddressForPeer(peerID) } catch (_: Exception) { null }
    }

    override fun getDeviceAddressToPeerMapping(): Map<String, String> {
        val merged = linkedMapOf<String, String>()
        try { merged.putAll(wifiService()?.getDeviceAddressToPeerMapping().orEmpty()) } catch (_: Exception) { }
        try { merged.putAll(bluetooth.getDeviceAddressToPeerMapping()) } catch (_: Exception) { }
        return merged
    }

    override fun printDeviceAddressesForPeers(): String {
        return buildString {
            appendLine(bluetooth.printDeviceAddressesForPeers())
            wifiService()?.let {
                appendLine()
                appendLine(it.printDeviceAddressesForPeers())
            }
        }
    }

    override fun getDebugStatus(): String {
        return buildString {
            appendLine("=== Unified Mesh Service Debug Status ===")
            appendLine("My Peer ID: $myPeerID")
            appendLine("Merged Peers: ${mergedPeerIDs().joinToString(", ")}")
            appendLine()
            appendLine(bluetooth.getDebugStatus())
            wifiService()?.let {
                appendLine()
                appendLine(it.getDebugStatus())
            }
        }
    }

    override fun clearAllInternalData() {
        try { bluetooth.clearAllInternalData() } catch (_: Exception) { }
        try { wifiForErasure()?.clearAllInternalData() } catch (_: Exception) { }
    }

    override fun clearAllEncryptionData() {
        try { bluetooth.clearAllEncryptionData() } catch (_: Exception) { }
        try { wifiForErasure()?.clearAllEncryptionData() } catch (_: Exception) { }
    }

    override fun didReceiveMessage(message: BitchatMessage) {
        withActiveOwner(Unit) {
            delegate?.didReceiveMessage(message)
        }
    }

    override fun didUpdatePeerList(peers: List<String>) {
        withActiveOwner(Unit) {
            delegate?.didUpdatePeerList(mergedPeerIDs().ifEmpty { peers.distinct() })
        }
    }

    override fun didReceiveDeliveryAck(messageID: String, recipientPeerID: String) {
        withActiveOwner(Unit) {
            delegate?.didReceiveDeliveryAck(messageID, recipientPeerID)
        }
    }

    override fun didReceiveReadReceipt(messageID: String, recipientPeerID: String) {
        withActiveOwner(Unit) {
            delegate?.didReceiveReadReceipt(messageID, recipientPeerID)
        }
    }

    override fun didReceiveVerifyChallenge(peerID: String, payload: ByteArray, timestampMs: Long) {
        withActiveOwner(Unit) {
            delegate?.didReceiveVerifyChallenge(peerID, payload, timestampMs)
        }
    }

    override fun didReceiveVerifyResponse(peerID: String, payload: ByteArray, timestampMs: Long) {
        withActiveOwner(Unit) {
            delegate?.didReceiveVerifyResponse(peerID, payload, timestampMs)
        }
    }

    override fun getNickname(): String? = withActiveOwner(null) { currentDelegate?.getNickname() }

    override fun isFavorite(peerID: String): Boolean = withActiveOwner(false) { currentDelegate?.isFavorite(peerID) ?: false }

    private fun mergedPeerIDs(): List<String> {
        val ids = linkedSetOf<String>()
        try { ids.addAll(com.bitchat.android.services.AppStateStore.peers.value) } catch (_: Exception) { }
        try { ids.addAll(bluetooth.getPeerNicknames().keys) } catch (_: Exception) { }
        try { ids.addAll(wifiService()?.getPeerNicknames()?.keys.orEmpty()) } catch (_: Exception) { }
        return ids.toList()
    }

    private fun wifiForErasure(): MeshService? {
        val captured = synchronized(ownerLock) { retiredForPanic to panicWifi }
        return if (captured.first) captured.second else wifiService()
    }

    private fun wifiService(): MeshService? = withActiveOwner(null) {
        try {
            val service = synchronized(ownerLock) {
                if (retiredForPanic) null else ports.currentWifi()
            }
            if (service != null) bindDelegate(service, clearWhenAbsent = false, force = false)
            if (retiredForPanic) null else service
        } catch (_: Exception) {
            null
        }
    }

    private fun isBleEnabled(): Boolean = ports.bleEnabled()

    private fun isBleConnected(peerID: String): Boolean {
        return try { bluetooth.getPeerInfo(peerID)?.isConnected == true } catch (_: Exception) { false }
    }

    private fun isWifiConnected(peerID: String): Boolean {
        return try { wifiService()?.getPeerInfo(peerID)?.isConnected == true } catch (_: Exception) { false }
    }

    private fun isBleReady(peerID: String): Boolean {
        return isBleConnected(peerID) && hasEstablishedSessionOnBluetooth(peerID)
    }

    private fun isWifiReady(peerID: String): Boolean {
        return try {
            val wifi = wifiService()
            wifi?.getPeerInfo(peerID)?.isConnected == true && wifi.hasEstablishedSession(peerID)
        } catch (_: Exception) {
            false
        }
    }

    private fun hasEstablishedSessionOnBluetooth(peerID: String): Boolean {
        return try { bluetooth.hasEstablishedSession(peerID) } catch (_: Exception) { false }
    }
}

/** Platform ports for the facade; synthetic owners inject no Context or transport factories. */
internal data class UnifiedMeshPorts(
    val scope: CoroutineScope,
    val announcementProfile: Flow<Pair<Long, Boolean>>,
    val hasAnnouncementRecipients: () -> Boolean,
    /** Identity-only RAM read; must not create, bind, dispatch callbacks or acquire a holder lock. */
    val currentWifi: () -> MeshService?,
    val startWifi: () -> Unit,
    val stopWifi: () -> Unit,
    val bleEnabled: () -> Boolean,
    val disableBle: () -> Unit,
    val currentNpub: () -> String?
)

/** The BLE implementation predates MeshService; keep its existing calls behind a direct adapter. */
private class BluetoothMeshPort(private val bluetooth: BluetoothMeshService) : MeshService {
    override val myPeerID: String get() = bluetooth.myPeerID
    override var delegate: MeshDelegate?
        get() = bluetooth.delegate
        set(value) { bluetooth.delegate = value as? BluetoothMeshDelegate }
    override fun startServices() = bluetooth.startServices()
    override fun stopServices() = bluetooth.stopServices()
    override fun sendMessage(content: String, mentions: List<String>) = bluetooth.sendMessage(content, mentions)
    override fun sendPrivateMessage(content: String, recipientPeerID: String, recipientNickname: String, messageID: String?) =
        bluetooth.sendPrivateMessage(content, recipientPeerID, recipientNickname, messageID)
    override suspend fun sendPrivateMessageBound(content: String, recipientPeerID: String, expectedKey: ByteArray,
        messageID: String, authorized: () -> Boolean): Boolean =
        bluetooth.sendPrivateMessageBound(content, recipientPeerID, expectedKey, messageID, authorized)
    override fun sendReadReceipt(messageID: String, recipientPeerID: String, readerNickname: String) =
        bluetooth.sendReadReceipt(messageID, recipientPeerID, readerNickname)
    override fun sendVerifyChallenge(peerID: String, noiseKeyHex: String, nonceA: ByteArray) = bluetooth.sendVerifyChallenge(peerID, noiseKeyHex, nonceA)
    override fun sendVerifyResponse(peerID: String, noiseKeyHex: String, nonceA: ByteArray) = bluetooth.sendVerifyResponse(peerID, noiseKeyHex, nonceA)
    override fun sendBroadcastAnnounce() = bluetooth.sendBroadcastAnnounce()
    override fun sendAnnouncementToPeer(peerID: String) = bluetooth.sendAnnouncementToPeer(peerID)
    override fun getPeerNicknames(): Map<String, String> = bluetooth.getPeerNicknames()
    override fun getPeerRSSI(): Map<String, Int> = bluetooth.getPeerRSSI()
    override fun getActivePeerCount(): Int = bluetooth.getActivePeerCount()
    override fun hasEstablishedSession(peerID: String): Boolean = bluetooth.hasEstablishedSession(peerID)
    override fun getSessionState(peerID: String): NoiseSession.NoiseSessionState = bluetooth.getSessionState(peerID)
    override fun initiateNoiseHandshake(peerID: String) = bluetooth.initiateNoiseHandshake(peerID)
    override fun getPeerFingerprint(peerID: String): String? = bluetooth.getPeerFingerprint(peerID)
    override fun getPeerInfo(peerID: String): PeerInfo? = bluetooth.getPeerInfo(peerID)
    override fun updatePeerInfo(peerID: String, nickname: String, noisePublicKey: ByteArray,
        signingPublicKey: ByteArray, isVerified: Boolean): Boolean =
        bluetooth.updatePeerInfo(peerID, nickname, noisePublicKey, signingPublicKey, isVerified)
    override fun getIdentityFingerprint(): String = bluetooth.getIdentityFingerprint()
    override fun getAuthenticatedRemoteStaticKey(peerID: String): ByteArray? = bluetooth.getAuthenticatedRemoteStaticKey(peerID)
    override fun getStaticNoisePublicKey(): ByteArray? = bluetooth.getStaticNoisePublicKey()
    override fun shouldShowEncryptionIcon(peerID: String): Boolean = bluetooth.shouldShowEncryptionIcon(peerID)
    override fun getEncryptedPeers(): List<String> = bluetooth.getEncryptedPeers()
    override fun getDeviceAddressForPeer(peerID: String): String? = bluetooth.getDeviceAddressForPeer(peerID)
    override fun getDeviceAddressToPeerMapping(): Map<String, String> = bluetooth.getDeviceAddressToPeerMapping()
    override fun printDeviceAddressesForPeers(): String = bluetooth.printDeviceAddressesForPeers()
    override fun getDebugStatus(): String = bluetooth.getDebugStatus()
    override fun clearAllInternalData() = bluetooth.clearAllInternalData()
    override fun clearAllEncryptionData() = bluetooth.clearAllEncryptionData()
}
