package com.bitchat.android.wifiaware

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Captured before a factory or service registers asynchronous callbacks. */
class WifiAwareOwnerToken internal constructor()

/** Controller start admission and a product's separate acknowledgment; never global radio quiescence. */
internal data class WifiAwareControllerRetirement(
    val admittedStartsDrained: Boolean,
    val capturedProductsAcknowledged: Boolean
)

/** Constructing these ports performs no platform lookup or service initialization. */
internal data class WifiAwareControllerPorts<S : Any>(
    val scope: CoroutineScope,
    val contextReady: () -> Boolean,
    val prepareStart: (WifiAwareOwnerToken) -> Boolean,
    val restartSupported: (WifiAwareOwnerToken) -> Boolean,
    val factory: (WifiAwareOwnerToken) -> S,
    val start: (S) -> Unit,
    val isRunning: (S) -> Boolean,
    val requestStop: (S) -> Unit,
    val awaitProductRetirement: suspend (S) -> Boolean,
    val published: (S) -> Unit,
    val clearPeers: () -> Unit,
    val debugMessage: (String) -> Unit,
    val failure: (Throwable) -> Unit,
    val restartDelayMs: Long = 2_000L,
    val maxRestartAttempts: Int = 15,
    val beforeRunningPublication: (Boolean) -> Unit = {}
)

/** The singleton's actual lifecycle algorithm, with RAM state and injected platform actions. */
internal class WifiAwareControllerOwner<S : Any>(private val ports: WifiAwareControllerPorts<S>) {
    private val lifecycleLock = Any()
    @Volatile private var service: S? = null
    @Volatile private var ownerToken = WifiAwareOwnerToken()
    private var starting = false
    private var admissionSerial = 0L
    private var activeAdmission = 0L
    private var stateRevision = 0L
    private var runningState = false
    private var cleanupCount = 0
    private var pendingStart: WifiAwareOwnerToken? = null
    private data class Candidate<S>(val origin: WifiAwareOwnerToken, val product: S, val reusable: S?, val running: Boolean)
    private val pendingCandidates = mutableListOf<Candidate<S>>()
    private val restartInFlight = AtomicBoolean(false)
    private val restartRequested = AtomicBoolean(false)
    private val hotspotHolds = AtomicInteger(0)
    private var lastBlockedReason: String? = null

    private val _enabled = MutableStateFlow(false)
    val enabled = _enabled.asStateFlow()
    private val _supported = MutableStateFlow(false)
    val supported = _supported.asStateFlow()
    private val _available = MutableStateFlow(false)
    val available = _available.asStateFlow()
    private val _supportStatus = MutableStateFlow<WifiAwareSupport.Status?>(null)
    val supportStatus = _supportStatus.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running = _running.asStateFlow()
    private val _connectedPeers = MutableStateFlow<Map<String, String>>(emptyMap())
    val connectedPeers = _connectedPeers.asStateFlow()
    private val _knownPeers = MutableStateFlow<Map<String, String>>(emptyMap())
    val knownPeers = _knownPeers.asStateFlow()
    private val _discoveredPeers = MutableStateFlow<Set<String>>(emptySet())
    val discoveredPeers = _discoveredPeers.asStateFlow()

    fun beginOwner(): WifiAwareOwnerToken = WifiAwareOwnerToken().also { ownerToken = it }
    fun currentOwner(): WifiAwareOwnerToken = ownerToken

    /** Identity-only volatile RAM read: no locks, factories, callbacks or platform calls. */
    fun getService(): S? = service

    fun publishSupportStatus(origin: WifiAwareOwnerToken, status: WifiAwareSupport.Status) {
        _supported.value = status.supported
        _available.value = status.available
        _supportStatus.value = status
    }

    fun publishDebugSnapshot(origin: WifiAwareOwnerToken, connected: Map<String, String>,
        known: Map<String, String>, discovered: Set<String>) {
        _connectedPeers.value = connected
        _knownPeers.value = known
        _discoveredPeers.value = discovered
    }

    private fun clearDebugSnapshot() {
        publishDebugSnapshot(ownerToken, emptyMap(), emptyMap(), emptySet())
    }

    fun blocked(key: String, message: String) {
        if (lastBlockedReason == key) return
        lastBlockedReason = key
        try { ports.debugMessage(message) } catch (_: Exception) { }
    }

    fun setEnabled(value: Boolean) {
        _enabled.value = value
        if (value) startIfPossible() else stop()
    }

    fun isHeldForHotspot(): Boolean = hotspotHolds.get() > 0
    fun resetHotspotLeasesForTest() { hotspotHolds.set(0) }

    fun acquireHotspotLease(): AutoCloseable {
        if (hotspotHolds.getAndIncrement() == 0) stop()
        val released = AtomicBoolean(false)
        return AutoCloseable {
            if (released.compareAndSet(false, true)) releaseHotspotLease()
        }
    }

    private fun releaseHotspotLease() {
        var remaining: Int
        while (true) {
            val current = hotspotHolds.get()
            if (current == 0) return
            remaining = current - 1
            if (hotspotHolds.compareAndSet(current, remaining)) break
        }
        if (remaining == 0) restartIfStillEnabled(ownerToken)
    }

    fun startIfPossible() = startIfPossible(ownerToken)

    private fun startIfPossible(origin: WifiAwareOwnerToken) {
        val admission = synchronized(lifecycleLock) {
            if (!_enabled.value || isHeldForHotspot()) return
            if (cleanupCount != 0) { pendingStart = origin; return }
            if (starting) return
            starting = true
            activeAdmission = ++admissionSerial
            Triple(service, stateRevision, activeAdmission)
        }
        val reusable = admission.first
        // The probe used to run before marking a start in progress. Keep a throwing probe
        // from wedging admission, without calling service code under the RAM monitor.
        try {
            if (reusable != null && ports.isRunning(reusable)) {
                val stillCurrent = synchronized(lifecycleLock) {
                    val current = service === reusable && stateRevision == admission.second &&
                        activeAdmission == admission.third && cleanupCount == 0 &&
                        _enabled.value && !isHeldForHotspot()
                    if (current) { runningState = true; stateRevision++ }
                    current
                }
                if (stillCurrent) publishRunning()
                releaseProbeAdmission(admission.third)
                return
            }
            if (!ports.contextReady() || !ports.prepareStart(origin) || !_enabled.value || isHeldForHotspot()) {
                releaseProbeAdmission(admission.third)
                return
            }
        } catch (failure: Throwable) {
            releaseProbeAdmission(admission.third)
            throw failure
        }
        try {
            val candidate = reusable ?: ports.factory(origin)
            ports.start(candidate)
            finishCandidate(Candidate(origin, candidate, reusable, ports.isRunning(candidate)))
        } catch (failure: Throwable) {
            synchronized(lifecycleLock) { runningState = false; stateRevision++ }
            publishRunning()
            ports.failure(failure)
            try { ports.debugMessage("Wi‑Fi Aware failed to start: ${failure.message}") } catch (_: Exception) { }
        } finally {
            // Deliberately retains the old cross-owner finally behavior for the causal baseline.
            synchronized(lifecycleLock) { starting = false }
        }
    }

    private fun releaseProbeAdmission(admission: Long) {
        synchronized(lifecycleLock) { if (activeAdmission == admission) starting = false }
    }

    private fun finishCandidate(candidate: Candidate<S>) {
        val published = synchronized(lifecycleLock) {
            if (cleanupCount != 0) { pendingCandidates += candidate; return }
            // The pre-existing publication rule deliberately has no owner-generation fence.
            val accepted = !isHeldForHotspot() && candidate.running
            if (accepted) service = candidate.product else if (service === candidate.product) service = null
            runningState = accepted
            stateRevision++
            accepted
        }
        publishRunning()
        if (published) {
            try { ports.published(candidate.product) } catch (_: Exception) { }
            lastBlockedReason = null
            try { ports.debugMessage("Wi‑Fi Aware started") } catch (_: Exception) { }
        } else {
            val held = isHeldForHotspot()
            if (held || candidate.reusable == null) {
                try { ports.requestStop(candidate.product) } catch (_: Exception) { }
            }
            val detail = if (held) "held down for the hotspot" else "did not start"
            try { ports.debugMessage("Wi‑Fi Aware $detail") } catch (_: Exception) { }
        }
    }

    /** A delayed observable emission must reconcile to the current authoritative RAM state. */
    private fun publishRunning() {
        while (true) {
            val snapshot = synchronized(lifecycleLock) { stateRevision to runningState }
            ports.beforeRunningPublication(snapshot.second)
            _running.value = snapshot.second
            if (synchronized(lifecycleLock) { stateRevision == snapshot.first }) return
        }
    }

    private fun isRunning(): Boolean = synchronized(lifecycleLock) { runningState }

    fun stop() { takeStoppedService() }

    private fun takeStoppedService(): S? {
        val stopped = synchronized(lifecycleLock) {
            cleanupCount++
            service.also {
                service = null; starting = false; activeAdmission = ++admissionSerial
                runningState = false; stateRevision++
            }
        }
        try {
            publishRunning()
            try { stopped?.let(ports.requestStop) } catch (_: Exception) { }
            clearStoppedPeers()
            try { ports.debugMessage("Wi‑Fi Aware stopped") } catch (_: Exception) { }
        } finally { finishCleanup() }
        return stopped
    }

    private fun clearStoppedPeers() {
        try { ports.clearPeers() } catch (_: Exception) { }
        clearDebugSnapshot()
    }

    /** No waiting: a cleanup caller drains retained completions and coalesced starts after its effects. */
    private fun finishCleanup() {
        val pending = synchronized(lifecycleLock) {
            cleanupCount--
            if (cleanupCount != 0) return
            val candidates = pendingCandidates.toList()
            pendingCandidates.clear()
            val origin = pendingStart
            pendingStart = null
            candidates to origin
        }
        try {
            pending.first.forEach { finishCandidate(it) }
        } finally { pending.second?.let { startIfPossible(it) } }
    }

    /** Partial seam: the baseline still acknowledges starts before an admitted factory has returned. */
    suspend fun retireForPanic(): WifiAwareControllerRetirement {
        val stopped = takeStoppedService()
        val acknowledged = if (stopped == null) true else try {
            ports.awaitProductRetirement(stopped)
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
        return WifiAwareControllerRetirement(admittedStartsDrained = true, capturedProductsAcknowledged = acknowledged)
    }

    fun onServiceStopped(origin: WifiAwareOwnerToken, stopped: S) {
        synchronized(lifecycleLock) {
            if (service !== stopped) return
            cleanupCount++
            service = null
            runningState = false
            stateRevision++
        }
        try { publishRunning(); clearStoppedPeers() } finally { finishCleanup() }
    }

    fun restartIfStillEnabled(origin: WifiAwareOwnerToken, delayMs: Long = 0L) {
        restartRequested.set(true)
        if (!restartInFlight.compareAndSet(false, true)) return
        ports.scope.launch {
            try {
                if (delayMs > 0L) delay(delayMs)
                do {
                    restartRequested.set(false)
                    var attempt = 0
                    while (_enabled.value && !isRunning() && attempt < ports.maxRestartAttempts) {
                        if (!ports.restartSupported(origin)) break
                        startIfPossible()
                        if (isRunning()) break
                        attempt++
                        delay(ports.restartDelayMs)
                    }
                } while (restartRequested.get() && _enabled.value && !isRunning())
            } finally {
                restartInFlight.set(false)
            }
            if (restartRequested.get() && _enabled.value && !isRunning()) restartIfStillEnabled(origin)
        }
    }
}
