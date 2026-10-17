package com.bitchat.android.net

import android.app.Application
import android.util.Log
import com.bitchat.android.util.AppConstants
import info.guardianproject.arti.ArtiLogListener
import info.guardianproject.arti.ArtiProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

/**
 * Tor provider implementation using custom-built Arti (Tor-in-Rust).
 *
 * This singleton provides Tor anonymity features using a custom Arti build
 * compiled with 16KB page size support for Google Play compliance.
 *
 * Based on the original TorManager implementation.
 */
class ArtiTorManager internal constructor(
    /** Tests pass a proxy over a fake native library. */
    private val newProxy: (Application, ArtiLogListener) -> ArtiProxy = { app, listener ->
        ArtiProxy.Builder(app).setLogListener(listener).build()
    },
    private val stopTimeoutMs: Long = STOP_TIMEOUT_MS
) {
    enum class TorState {
        OFF,
        STARTING,
        BOOTSTRAPPING,
        RUNNING,
        STOPPING,
        ERROR
    }

    data class TorStatus(
        val mode: TorMode = TorMode.OFF,
        val running: Boolean = false,
        val bootstrapPercent: Int = 0,
        val lastLogLine: String = "",
        val state: TorState = TorState.OFF
    )

    companion object {
        private const val TAG = "ArtiTorManager"
        private const val RESTART_DELAY_MS = AppConstants.Tor.RESTART_DELAY_MS
        private const val INACTIVITY_TIMEOUT_MS = AppConstants.Tor.INACTIVITY_TIMEOUT_MS
        private const val MAX_RETRY_ATTEMPTS = AppConstants.Tor.MAX_RETRY_ATTEMPTS
        private const val STOP_TIMEOUT_MS = AppConstants.Tor.STOP_TIMEOUT_MS

        @Volatile
        private var INSTANCE: ArtiTorManager? = null

        fun getInstance(): ArtiTorManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ArtiTorManager().also { INSTANCE = it }
            }
        }
    }

    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var initialized = false
    @Volatile
    private var socksAddr: InetSocketAddress? = null
    @Volatile
    private var artiProxy: ArtiProxy? = null
    @Volatile
    private var lastMode: TorMode = TorMode.OFF
    private val applyMutex = Mutex()
    @Volatile
    private var desiredMode: TorMode = TorMode.OFF
    /** Random per start, so the port is not a fixed, well-known target on the phone. */
    @Volatile
    private var currentSocksPort: Int = SocksCredentials.randomPort()
    /** The bound port and its credentials, published together once our proxy holds the port. */
    @Volatile
    private var socksAuth: SocksAuth? = null
    @Volatile
    private var lastLogTime = AtomicLong(0L)
    @Volatile
    private var retryAttempts = 0
    @Volatile
    private var bindRetryAttempts = 0
    private var inactivityJob: Job? = null
    private var retryJob: Job? = null
    private var currentApplication: Application? = null

    private enum class LifecycleState { STOPPED, STARTING, RUNNING, STOPPING }

    @Volatile
    private var lifecycleState: LifecycleState = LifecycleState.STOPPED

    private val _statusFlow = MutableStateFlow(
        TorStatus(
            mode = TorMode.OFF,
            running = false,
            bootstrapPercent = 0,
            lastLogLine = "",
            state = TorState.OFF
        )
    )

    val statusFlow: StateFlow<TorStatus> = _statusFlow.asStateFlow()

    private val stateChangeDeferred = AtomicReference<CompletableDeferred<TorState>?>(null)

    fun isProxyEnabled(): Boolean {
        val s = _statusFlow.value
        return s.mode != TorMode.OFF && s.running && s.bootstrapPercent >= 100 &&
                socksAddr != null && s.state == TorState.RUNNING
    }

    fun init(application: Application) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
            currentApplication = application
            TorPreferenceManager.init(application)

            val logListener = ArtiLogListener { logLine ->
                val text = logLine ?: return@ArtiLogListener
                val s = text
                lastLogTime.set(System.currentTimeMillis())
                _statusFlow.update { it.copy(lastLogLine = s) }
                handleArtiLogLine(s)
                if (_statusFlow.value.bootstrapPercent < 100) {
                    armBootstrapInactivityWatchdog()
                }
            }

            artiProxy = newProxy(application, logListener)

            val savedMode = TorPreferenceManager.get(application)
            if (savedMode == TorMode.ON) {
                desiredMode = savedMode
            }
            appScope.launch {
                applyMode(application, savedMode)
            }

            appScope.launch {
                // Not behind applyMutex, which a start holds for its whole bootstrap. The initial
                // value is skipped: nothing runs yet, and init applies it below.
                TorPreferenceManager.modeFlow.drop(1).collect { mode ->
                    if (mode == TorMode.OFF) requestOff()
                }
            }

            appScope.launch {
                // An immediate stop re-runs the serialized path, which then settles on the current mode.
                combine(TorPreferenceManager.modeFlow, immediateStops) { mode, _ -> mode }.collect { mode ->
                    applyMode(application, mode)
                }
            }
        }
    }

    fun currentSocksAddress(): InetSocketAddress? = socksAddr

    fun currentSocksAuth(): SocksAuth? = socksAuth

    /** Only after our proxy bound the port: a process squatting it never gets credentials or traffic. */
    private fun publishSocks(address: InetSocketAddress, credentials: SocksCredentials) {
        socksAuth = SocksAuth(address, credentials)
        socksAddr = address
        resetNetworkConnections()
    }

    /** Clients fail closed (no address, no credentials) until the next successful start. */
    private fun unpublishSocks() {
        socksAddr = null
        socksAuth = null
        try {
            OkHttpProvider.reset()
        } catch (_: Throwable) {
        }
    }

    /** Bumped after each stop made outside applyMutex; the serialized path re-applies the mode then. */
    private val immediateStops = MutableStateFlow(0L)

    /** [immediateStops] when the current ON was applied: a later immediate stop makes ON start again. */
    @Volatile
    private var immediateStopsAtStart = -1L

    /**
     * Internet off, effective at once and without waiting for applyMutex: the SOCKS route is
     * withdrawn now, then the native client and any bootstrap in progress are stopped on IO.
     */
    fun requestOff() {
        desiredMode = TorMode.OFF
        unpublishSocks()
        appScope.launch {
            // An ON applied since then wants Tor again; its own start already replaced the client.
            if (desiredMode != TorMode.OFF) return@launch
            stopNative()
            immediateStops.update { it + 1 }
        }
    }

    /** No client may connect to the SOCKS port once its teardown begins. */
    private fun withdrawSocks() {
        unpublishSocks()
        resetNetworkConnections()
    }

    /** The native stop blocks until the client is gone: never on the caller's (possibly main) thread. */
    private suspend fun stopNative() = withContext(Dispatchers.IO) { stopArtiInternal() }

    /** A start is between its first native call and its outcome; it may still create Arti files. */
    fun isStartInFlight(): Boolean = pendingStarts.get() > 0 || artiProxy?.isStartInFlight() == true

    /** startArti() calls not yet finished, counted before they stop the previous client. */
    private val pendingStarts = java.util.concurrent.atomic.AtomicInteger(0)

    /** Bumped by every ON request; a bootstrap watcher from an older start never acts. */
    private var startGeneration = 0L

    /** Fully OFF: late Arti log lines are ignored once lifecycleState is STOPPED. */
    private fun resetToOff() {
        lifecycleState = LifecycleState.STOPPED
        unpublishSocks()
        _statusFlow.update { it.copy(running = false, bootstrapPercent = 0, state = TorState.OFF) }
        resetNetworkConnections()
    }

    /**
     * Wait until the currently selected HTTP route can be used.
     *
     * [socksAddr] is published only once our proxy holds its port; until then
     * OkHttpProvider builds no client at all, so nothing leaks directly.
     */
    suspend fun awaitSelectedRoute(timeoutMs: Long): Boolean {
        if (currentSocksAddress() == null || isProxyEnabled()) {
            return true
        }

        return withTimeoutOrNull(timeoutMs) {
            statusFlow.first {
                currentSocksAddress() == null || isProxyEnabled()
            }
            true
        } ?: false
    }

    suspend fun applyMode(application: Application, mode: TorMode) {
        if (mode == TorMode.OFF) {
            // A start holds applyMutex for its whole bootstrap: cancel it now, not after.
            desiredMode = TorMode.OFF
            withdrawSocks()
            stopNative()
        }
        applyMutex.withLock {
            try {
                desiredMode = mode
                lastMode = mode
                val s = _statusFlow.value
                if (mode == s.mode && mode != TorMode.OFF &&
                    (lifecycleState == LifecycleState.STARTING || lifecycleState == LifecycleState.RUNNING) &&
                    immediateStopsAtStart == immediateStops.value
                ) {
                    return
                }
                when (mode) {
                    TorMode.OFF -> {
                        if (lifecycleState == LifecycleState.STOPPED && s.mode == TorMode.OFF &&
                            s.state == TorState.OFF && !s.running
                        ) {
                            // Already fully off; the native stop above has returned. No STOPPING flicker.
                            return
                        }
                        Log.i(TAG, "applyMode: OFF -> stopping tor")
                        lifecycleState = LifecycleState.STOPPING
                        _statusFlow.value = _statusFlow.value.copy(
                            mode = TorMode.OFF,
                            running = false,
                            bootstrapPercent = 0,
                            state = TorState.STOPPING
                        )
                        stopArti()
                        waitForStateTransition(target = TorState.OFF, timeoutMs = stopTimeoutMs)
                        socksAddr = null
                        _statusFlow.value = _statusFlow.value.copy(
                            mode = TorMode.OFF,
                            running = false,
                            bootstrapPercent = 0,
                            state = TorState.OFF
                        )
                        currentSocksPort = SocksCredentials.randomPort()
                        bindRetryAttempts = 0
                        lifecycleState = LifecycleState.STOPPED
                        resetNetworkConnections()
                    }

                    TorMode.ON -> {
                        // Single chokepoint: Arti never starts over state a failed panic left behind,
                        // nor while a wipe is deleting it.
                        if (info.guardianproject.arti.ArtiWipeLatch.isWiping() ||
                            !ArtiTorControl.completePendingWipe(application)
                        ) {
                            Log.w(TAG, "applyMode: ON refused, Arti state wipe pending")
                            desiredMode = TorMode.OFF
                            lastMode = TorMode.OFF
                            TorPreferenceManager.set(application, TorMode.OFF, commit = true)
                            return
                        }
                        Log.i(TAG, "applyMode: ON -> starting arti")
                        currentSocksPort = SocksCredentials.randomPort()
                        bindRetryAttempts = 0
                        lifecycleState = LifecycleState.STARTING
                        _statusFlow.value = _statusFlow.value.copy(
                            mode = TorMode.ON,
                            running = false,
                            bootstrapPercent = 0,
                            state = TorState.STARTING
                        )
                        unpublishSocks()
                        val generation = ++startGeneration
                        immediateStopsAtStart = immediateStops.value
                        startArti(application, useDelay = false)
                        appScope.launch {
                            waitUntilBootstrapped()
                            // Decided under applyMutex, and only for this start: a newer start is
                            // never stopped by an older watcher.
                            applyMutex.withLock {
                                if (generation != startGeneration) return@withLock
                                if (desiredMode != TorMode.ON) {
                                    withdrawSocks()
                                    stopNative()
                                    resetToOff()
                                } else if (_statusFlow.value.running && socksAddr != null) {
                                    Log.i(TAG, "Tor ON: proxy ready")
                                    resetNetworkConnections()
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply Arti mode: ${e.message}")
            }
        }
    }

    private suspend fun startArti(application: Application, useDelay: Boolean = false) {
        pendingStarts.incrementAndGet()
        try {
            startArtiCounted(application, useDelay)
        } finally {
            pendingStarts.decrementAndGet()
        }
    }

    private suspend fun startArtiCounted(application: Application, useDelay: Boolean) {
        if (info.guardianproject.arti.ArtiWipeLatch.isWiping() || !ArtiTorControl.completePendingWipe(application)) {
            _statusFlow.update { it.copy(running = false, state = TorState.ERROR) }
            TorPreferenceManager.set(application, TorMode.OFF, commit = true)
            return
        }
        try {
            stopArtiAndWait()
            if (useDelay) {
                delay(RESTART_DELAY_MS)
            }

            val proxy = artiProxy ?: run {
                Log.e(TAG, "ArtiProxy not initialized! This should not happen.")
                _statusFlow.update { it.copy(state = TorState.ERROR) }
                return
            }

            // Internet turned off during the wait above: never reach the native side. Checked again
            // inside start() under its lock, so a stop that lands in between still wins.
            if (desiredMode != TorMode.ON) {
                resetToOff()
                return
            }
            val credentials = SocksCredentials.random()
            proxy.start(currentSocksPort, credentials) { desiredMode == TorMode.ON }
            if (desiredMode != TorMode.ON) {
                // Internet was turned off while this start ran: stop now, never mark RUNNING.
                withdrawSocks()
                stopNative()
                resetToOff()
                return
            }
            // Published only once our own proxy holds the port: a process squatting it never
            // gets the credentials or the traffic.
            publishSocks(InetSocketAddress("127.0.0.1", currentSocksPort), credentials)
            lastLogTime.set(System.currentTimeMillis())

            _statusFlow.update {
                it.copy(
                    running = true,
                    bootstrapPercent = 0,
                    state = TorState.STARTING
                )
            }
            lifecycleState = LifecycleState.RUNNING
            startInactivityMonitoring()

        } catch (e: Exception) {
            Log.e(TAG, "Error starting Arti on port $currentSocksPort: ${e.message}")
            unpublishSocks()
            _statusFlow.update { it.copy(state = TorState.ERROR) }
            if (desiredMode != TorMode.ON) {
                // A stop cancelled this start: no retry.
                lifecycleState = LifecycleState.STOPPED
                return
            }

            val isBindError = isBindError(e)
            if (isBindError && bindRetryAttempts < MAX_RETRY_ATTEMPTS) {
                bindRetryAttempts++
                currentSocksPort = SocksCredentials.randomPort()
                Log.w(TAG, "Port bind failed (attempt $bindRetryAttempts/$MAX_RETRY_ATTEMPTS), retrying with port $currentSocksPort")
                startArti(application, useDelay = false)
            } else if (isBindError) {
                Log.e(TAG, "Max bind retry attempts reached ($MAX_RETRY_ATTEMPTS), giving up")
                lifecycleState = LifecycleState.STOPPED
                _statusFlow.update {
                    it.copy(
                        running = false,
                        bootstrapPercent = 0,
                        state = TorState.ERROR
                    )
                }
            } else {
                scheduleRetry(application)
            }
        }
    }

    private fun isBindError(exception: Exception): Boolean {
        val message = exception.message?.lowercase() ?: ""
        return message.contains("bind") ||
                message.contains("address already in use") ||
                message.contains("port") && message.contains("use") ||
                message.contains("permission denied") && message.contains("port") ||
                message.contains("could not bind")
    }

    /**
     * Reset network connections after Tor state changes.
     * Rebuilds OkHttp clients and reconnects Nostr relays.
     */
    private fun resetNetworkConnections() {
        try {
            OkHttpProvider.reset()
        } catch (_: Throwable) {
        }
        try {
            com.bitchat.android.nostr.NostrRelayManager.shared.resetAllConnections()
        } catch (_: Throwable) {
        }
    }

    private fun stopArtiInternal() {
        try {
            val proxy = artiProxy
            if (proxy != null) {
                try {
                    proxy.stop()
                } catch (_: Throwable) {
                }
            }
            stopInactivityMonitoring()
            stopRetryMonitoring()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping Arti: ${e.message}")
        }
        unpublishSocks()
    }

    private suspend fun stopArti() {
        withdrawSocks()
        stopNative()
        _statusFlow.value = _statusFlow.value.copy(
            running = false,
            bootstrapPercent = 0,
            state = TorState.STOPPING
        )
    }

    private suspend fun stopArtiAndWait(timeoutMs: Long = stopTimeoutMs) {
        stopNative()
        waitForStateTransition(target = TorState.OFF, timeoutMs = timeoutMs)
        delay(200)
    }

    private suspend fun restartArti(application: Application) {
        Log.i(TAG, "Restarting Arti (keeping SOCKS proxy enabled)...")
        stopArtiAndWait()
        delay(RESTART_DELAY_MS)
        startArti(application, useDelay = false)
    }

    private fun startInactivityMonitoring() {
        armBootstrapInactivityWatchdog()
    }

    /**
     * One-shot startup watchdog. Arti log activity re-arms it and successful bootstrap cancels it,
     * so a healthy background Tor process has no permanent five-second polling coroutine.
     */
    private fun armBootstrapInactivityWatchdog() {
        inactivityJob?.cancel()
        if (lifecycleState != LifecycleState.RUNNING || _statusFlow.value.bootstrapPercent >= 100) {
            inactivityJob = null
            return
        }
        inactivityJob = appScope.launch {
            delay(INACTIVITY_TIMEOUT_MS)
            val timeSinceLastActivity = System.currentTimeMillis() - lastLogTime.get()
            if (
                timeSinceLastActivity >= INACTIVITY_TIMEOUT_MS &&
                _statusFlow.value.mode == TorMode.ON &&
                _statusFlow.value.bootstrapPercent < 100 &&
                lifecycleState == LifecycleState.RUNNING
            ) {
                Log.w(TAG, "Bootstrap inactivity detected (${timeSinceLastActivity}ms), restarting Arti")
                currentApplication?.let { restartArti(it) }
            }
        }
    }

    private fun stopInactivityMonitoring() {
        inactivityJob?.cancel()
        inactivityJob = null
    }

    private fun scheduleRetry(application: Application) {
        retryJob?.cancel()
        if (retryAttempts < MAX_RETRY_ATTEMPTS) {
            retryAttempts++
            val delayMs = (1000L * (1 shl retryAttempts)).coerceAtMost(30000L)
            Log.w(TAG, "Scheduling Arti retry attempt $retryAttempts in ${delayMs}ms")
            retryJob = appScope.launch {
                delay(delayMs)
                val currentMode = _statusFlow.value.mode
                if (currentMode == TorMode.ON) {
                    restartArti(application)
                }
            }
        } else {
            Log.e(TAG, "Max retry attempts reached, giving up on Arti connection")
        }
    }

    private fun stopRetryMonitoring() {
        retryJob?.cancel()
        retryJob = null
    }

    private suspend fun waitUntilBootstrapped() {
        val current = _statusFlow.value
        if (!current.running) return
        if (current.bootstrapPercent >= 100 && current.state == TorState.RUNNING) return
        while (true) {
            val s = statusFlow.first {
                (it.bootstrapPercent >= 100 && it.state == TorState.RUNNING) ||
                        !it.running ||
                        it.state == TorState.ERROR
            }
            if (!s.running || s.state == TorState.ERROR) return
            if (s.bootstrapPercent >= 100 && s.state == TorState.RUNNING) return
        }
    }

    private fun handleArtiLogLine(s: String) {
        val currentState = _statusFlow.value.state
        val currentLifecycle = lifecycleState

        when {
            s.contains("AMEx: state changed to Initialized", ignoreCase = true) -> {
                if (currentLifecycle != LifecycleState.STARTING && currentLifecycle != LifecycleState.RUNNING) {
                    return
                }
                _statusFlow.update { it.copy(state = TorState.STARTING) }
                completeWaitersIf(TorState.STARTING)
            }

            s.contains("AMEx: state changed to Starting", ignoreCase = true) -> {
                if (currentLifecycle != LifecycleState.STARTING && currentLifecycle != LifecycleState.RUNNING) {
                    return
                }
                _statusFlow.update { it.copy(state = TorState.STARTING) }
                completeWaitersIf(TorState.STARTING)
            }

            s.contains(
                "Sufficiently bootstrapped; system SOCKS now functional",
                ignoreCase = true
            ) -> {
                if (currentLifecycle != LifecycleState.RUNNING) {
                    return
                }
                _statusFlow.update {
                    it.copy(
                        bootstrapPercent = 75,
                        state = TorState.BOOTSTRAPPING
                    )
                }
                retryAttempts = 0
                bindRetryAttempts = 0
                startInactivityMonitoring()
            }

            s.contains("We have found that guard [scrubbed] is usable.", ignoreCase = true) -> {
                if (currentLifecycle != LifecycleState.RUNNING) {
                    return
                }
                _statusFlow.update {
                    it.copy(
                        state = TorState.RUNNING,
                        bootstrapPercent = 100,
                        running = true
                    )
                }
                stopInactivityMonitoring()
                completeWaitersIf(TorState.RUNNING)
            }

            s.contains("AMEx: state changed to Stopping", ignoreCase = true) -> {
                if (currentLifecycle != LifecycleState.STOPPING) {
                    return
                }
                _statusFlow.update {
                    it.copy(
                        state = TorState.STOPPING,
                        running = false
                    )
                }
            }

            s.contains("AMEx: state changed to Stopped", ignoreCase = true) -> {
                if (currentLifecycle != LifecycleState.STOPPING && currentLifecycle != LifecycleState.STOPPED) {
                    return
                }
                _statusFlow.update {
                    it.copy(
                        state = TorState.OFF,
                        running = false,
                        bootstrapPercent = 0
                    )
                }
                completeWaitersIf(TorState.OFF)
            }

            s.contains("Another process has the lock on our state files", ignoreCase = true) -> {
                _statusFlow.update { it.copy(state = TorState.ERROR) }
            }
        }
    }

    private fun completeWaitersIf(state: TorState) {
        stateChangeDeferred.getAndSet(null)?.let { def ->
            def.complete(state)
        }
    }

    private suspend fun waitForStateTransition(target: TorState, timeoutMs: Long): TorState? {
        val def = CompletableDeferred<TorState>()
        stateChangeDeferred.getAndSet(def)?.cancel()
        return withTimeoutOrNull(timeoutMs) {
            val cur = _statusFlow.value.state
            if (cur == target) return@withTimeoutOrNull cur
            def.await()
        }
    }

    fun isTorAvailable(): Boolean = true
}
