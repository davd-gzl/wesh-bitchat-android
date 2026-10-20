package com.bitchat.android.wifiaware

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.atomic.AtomicBoolean

/** Platform adapter for the shared Wi-Fi Aware lifecycle owner. */
object WifiAwareController {
    private const val TAG = "WifiAwareController"
    private var appContext: Context? = null
    private var awareReceiverRegistered = false
    private val owner: WifiAwareControllerOwner<WifiAwareMeshService, com.bitchat.android.service.HolderOrigin> = WifiAwareControllerOwner(
        HolderInitialBinding.Uninitialized, WifiAwareControllerPorts(
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        contextReady = { appContext != null },
        prepareStart = ::prepareStart,
        restartSupported = { origin -> appContext?.let { refreshSupportStatus(it, origin).supported } ?: true },
        factory = { origin, original ->
            com.bitchat.android.service.MeshServiceHolder.constructWifi(original, origin) { construction ->
                WifiAwareMeshService(checkNotNull(appContext), origin, construction)
            }
        },
        start = { it.startServices() },
        isRunning = { it.isRunning() },
        requestStop = { it.stopServices() },
        // stopServices returns Unit before its children/executors finish; it is not a checked acknowledgment.
        awaitProductRetirement = { false },
        published = { _, _ -> com.bitchat.android.service.MeshServiceHolder.unifiedMeshService?.refreshDelegates() },
        clearPeers = { com.bitchat.android.services.AppStateStore.clearTransportPeers("WIFI") },
        debugMessage = { message ->
            com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().addDebugMessage(
                com.bitchat.android.ui.debug.DebugMessage.SystemMessage(message))
        },
        failure = { Log.e(TAG, "Failed to start WifiAwareMeshService", it) }
    ))

    val enabled = owner.enabled
    val supported = owner.supported
    val available = owner.available
    val supportStatus = owner.supportStatus
    val running = owner.running
    val connectedPeers = owner.connectedPeers
    val knownPeers = owner.knownPeers
    val discoveredPeers = owner.discoveredPeers

    internal fun initialize(context: Context, enabledByDefault: Boolean, original: com.bitchat.android.service.HolderOrigin) {
        val participant = owner.originalParticipant(original) { it.originalHostBinding }
        val attachment = com.bitchat.android.service.MeshServiceHolder.attachOriginalWifiController(original, participant)
        if (attachment !is com.bitchat.android.service.HolderWifiInitializationAdmissionResult.Accepted) {
            if (attachment === com.bitchat.android.service.HolderWifiInitializationAdmissionResult.RefusedOriginalClosed) participant.fence()
            return
        }
        var ticket: ControllerInitializationTicket? = null
        var outcome = WifiInitializationOutcome.UnknownFailure
        try {
            when (val admission = owner.admitOriginalInitialization(participant)) {
                is ControllerInitializationAdmission.Accepted -> ticket = admission.ticket
                is ControllerInitializationAdmission.Refused -> { outcome = WifiInitializationOutcome.RefusedByController; return }
                ControllerInitializationAdmission.Invalid -> return
            }
            appContext = context.applicationContext
            val origin = when (val begun = owner.beginOriginalOwner(participant, checkNotNull(ticket))) {
                is WifiControllerBeginResult.Begun -> begun.token
                is WifiControllerBeginResult.Refused -> { outcome = WifiInitializationOutcome.RefusedByController; return }
                WifiControllerBeginResult.Invalid -> return
            }
            val status = refreshSupportStatus(appContext!!, origin)
            if (status.supported) registerAwareStateReceiver(appContext!!)
            else Log.i(TAG, "Wi-Fi Aware unsupported: ${status.reason}")
            setEnabled(enabledByDefault)
            outcome = WifiInitializationOutcome.Begun
        } finally {
            ticket?.let { owner.finishOriginalInitialization(it, outcome) }
            com.bitchat.android.service.MeshServiceHolder.finishWifiInitialization(attachment.ticket, outcome)
        }
    }

    internal fun publishDebugSnapshot(origin: WifiAwareOwnerToken, connected: Map<String, String>,
        known: Map<String, String>, discovered: Set<String>) {
        owner.publishDebugSnapshot(origin, connected, known, discovered)
    }

    fun setEnabled(value: Boolean) = owner.setEnabled(value)
    fun startIfPossible() = owner.startIfPossible()
    fun stop() = owner.stop()

    /** Partial controller result only, not permission to destroy shared cryptographic material. */
    internal suspend fun retireForPanic(): WifiAwareControllerRetirement = owner.retireForPanic()

    @VisibleForTesting
    internal fun isHeldForHotspot(): Boolean = owner.isHeldForHotspot()

    @VisibleForTesting
    internal fun resetHotspotLeasesForTest() = owner.resetHotspotLeasesForTest()

    /** A once-releasable claim; ordinary hotspot holds never change the user's enabled preference. */
    class HotspotLease internal constructor(private val releaseAction: () -> Unit) : AutoCloseable {
        private val released = AtomicBoolean(false)
        override fun close() {
            if (released.compareAndSet(false, true)) releaseAction()
        }
    }

    fun acquireHotspotLease(): HotspotLease {
        val lease = owner.acquireHotspotLease()
        return HotspotLease { lease.close() }
    }

    internal fun onServiceStopped(origin: WifiAwareOwnerToken, stoppedService: WifiAwareMeshService) =
        owner.onServiceStopped(origin, stoppedService)

    internal fun onServiceRecoveryCleanupFinished(origin: WifiAwareOwnerToken, product: WifiAwareMeshService) =
        owner.onServiceRecoveryCleanupFinished(origin, product)

    internal fun restartIfStillEnabled(delayMs: Long = 0L) = owner.restartIfStillEnabled(owner.currentOwner(), delayMs)

    internal fun restartIfStillEnabled(origin: WifiAwareOwnerToken, delayMs: Long) =
        owner.restartIfStillEnabled(origin, delayMs)

    private fun prepareStart(origin: WifiAwareOwnerToken): Boolean {
        val ctx = appContext ?: return false
        val status = refreshSupportStatus(ctx, origin)
        if (!status.supported) {
            val reason = status.reason ?: "not supported"
            Log.w(TAG, "Wi-Fi Aware unsupported; not starting ($reason)")
            owner.blocked(origin, "unsupported:$reason", "Wi-Fi Aware not supported on this device ($reason)")
            return false
        }
        val awareManager = WifiAwareSupport.getManager(ctx)
        if (awareManager == null || !status.available) {
            Log.w(TAG, "Wi-Fi Aware is not currently available; not starting")
            owner.blocked(origin, "unavailable", "Wi-Fi Aware is not available right now")
            return false
        }
        // These live eligibility checks are never invoked by the inert owner's synthetic fixtures.
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
        val locationEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm?.isLocationEnabled == true
        } else {
            @Suppress("DEPRECATION")
            lm?.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) == true ||
                lm?.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) == true
        }
        if (!locationEnabled) {
            Log.w(TAG, "Location services are disabled; Wi-Fi Aware cannot start.")
            owner.blocked(origin, "location-disabled", "Enable Location Services to start Wi-Fi Aware")
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.NEARBY_WIFI_DEVICES) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Log.w(TAG, "Missing NEARBY_WIFI_DEVICES permission; not starting")
                owner.blocked(origin, "missing-nearby-wifi", "Grant Nearby Wi-Fi Devices to start Wi-Fi Aware")
                return false
            }
        }
        return true
    }

    private fun registerAwareStateReceiver(ctx: Context) {
        if (awareReceiverRegistered) return
        if (!refreshSupportStatus(ctx, owner.currentOwner()).supported) return
        try {
            val filter = android.content.IntentFilter(android.net.wifi.aware.WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED)
            ctx.registerReceiver(object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: android.content.Intent?) {
                    // This persistent observer represents current system availability.
                    // Service callbacks still carry the token captured at construction.
                    owner.environmentChanged { WifiAwareSupport.evaluate(ctx) }
                    Log.i(TAG, "Wi-Fi Aware availability changed: supported=${supported.value} available=${available.value} enabled=${enabled.value} running=${running.value}")
                }
            }, filter)
            awareReceiverRegistered = true
        } catch (failure: Exception) {
            Log.w(TAG, "Failed to register Wi-Fi Aware state receiver: ${failure.message}")
        }
    }

    private fun refreshSupportStatus(ctx: Context, origin: WifiAwareOwnerToken): WifiAwareSupport.Status {
        val status = WifiAwareSupport.evaluate(ctx)
        owner.publishSupportStatus(origin, status)
        return status
    }

    /** Kept as a safely published RAM identity lookup for UnifiedMeshService. */
    fun getService(): WifiAwareMeshService? = owner.getService()
}
