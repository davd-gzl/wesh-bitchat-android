package com.bitchat.android.wifiaware

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.IdentityHashMap
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
    private class Lifetime<S : Any>(val token: WifiAwareOwnerToken = WifiAwareOwnerToken()) {
        var retired = false
        var starting: Admission<S>? = null
        var calls = 0
        var idle: CompletableDeferred<Unit>? = null
        var accountingFailed = false
        val products = IdentityHashMap<S, Product<S>>()
        var restart: Restart<S>? = null
        val restarts = mutableSetOf<Restart<S>>()
        var retirement: CompletableDeferred<WifiAwareControllerRetirement>? = null
        var confirmed = false
    }
    private class Admission<S : Any>(val owner: Lifetime<S>, val reusable: Product<S>?, val revision: Long) {
        var released = false
    }
    private class Product<S : Any>(val value: S) {
        var stop: CompletableDeferred<Boolean>? = null
        // Failed preparation and unrelated publications do not invalidate liveness samples.
        var startDispatch = 0L
    }
    private class Restart<S : Any>(val owner: Lifetime<S>) {
        val ready = CompletableDeferred<Job?>()
        var requested = true
    }
    private data class Cleanup<S : Any>(val owner: Lifetime<S>, val product: Product<S>?,
        val requestProductStop: Boolean, val observedStop: CompletableDeferred<Boolean>?)
    private data class Candidate<S : Any>(val admission: Admission<S>, val product: Product<S>, val running: Boolean? = null)
    private data class Retirement<S : Any>(val owner: Lifetime<S>,
        val result: CompletableDeferred<WifiAwareControllerRetirement>, val leader: Boolean)
    private data class DebugSnapshot(val connected: Map<String, String> = emptyMap(),
        val known: Map<String, String> = emptyMap(), val discovered: Set<String> = emptySet())

    private val lifecycleLock = Any()
    @Volatile private var service: S? = null
    @Volatile private var current = Lifetime<S>()
    private val lifetimes = mutableListOf(current)
    private var serviceOwner: Lifetime<S>? = null
    private var stateRevision = 0L
    private var enabledState = false
    private var runningState = false
    private var statusState: WifiAwareSupport.Status? = null
    private var debugState = DebugSnapshot()
    private var cleanupCount = 0
    private var pendingStart: WifiAwareOwnerToken? = null
    private val pendingCandidates = mutableListOf<Candidate<S>>()
    private var replayingCandidates = false
    private var replayRequested = false
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

    /** A RAM lifetime change is not authorization that other transport/key owners have drained. */
    fun beginOwner(): WifiAwareOwnerToken {
        val fresh = Lifetime<S>()
        val cleanup = synchronized(lifecycleLock) {
            val old = current
            // Reserve cleanup before publishing B, so synchronous A stop effects
            // cannot race a B start. A's still-held factory remains separate debt.
            val reserved = reserveCleanup(old, clearWhenEmpty = false)
            old.retired = true
            old.starting = null
            pendingStart = null
            debugState = DebugSnapshot()
            lastBlockedReason = null
            stateRevision++
            current = fresh
            lifetimes += fresh
            lifetimes.removeAll { it !== fresh && it.calls == 0 && it.restarts.isEmpty() &&
                (it.confirmed || (it.products.isEmpty() && !it.accountingFailed && it.retirement == null)) }
            reserved
        }
        if (cleanup != null) runCleanup(cleanup) else publishDebug()
        return fresh.token
    }

    fun currentOwner(): WifiAwareOwnerToken = current.token

    /** Identity-only volatile RAM read: no locks, factories, callbacks or platform calls. */
    fun getService(): S? = service

    private fun active(owner: Lifetime<S>): Boolean = current === owner && !owner.retired
    private fun addCall(owner: Lifetime<S>) {
        if (owner.calls++ == 0) owner.idle = CompletableDeferred()
    }
    private fun releaseCall(owner: Lifetime<S>) {
        val idle = synchronized(lifecycleLock) {
            check(owner.calls > 0)
            if (--owner.calls == 0) owner.idle.also { owner.idle = null } else null
        }
        idle?.complete(Unit)
        if (idle != null) replayCandidates()
    }
    private fun releaseAdmission(admission: Admission<S>) {
        val released = synchronized(lifecycleLock) {
            if (admission.released) false else {
                admission.released = true
                if (admission.owner.starting === admission) admission.owner.starting = null
                true
            }
        }
        if (released) releaseCall(admission.owner)
    }

    fun publishSupportStatus(origin: WifiAwareOwnerToken, status: WifiAwareSupport.Status) {
        val owner = synchronized(lifecycleLock) {
            current.takeIf { active(it) && it.token === origin }?.also {
                addCall(it); statusState = status; stateRevision++
            }
        } ?: return
        try { publishStatus() } finally { releaseCall(owner) }
    }

    fun publishDebugSnapshot(origin: WifiAwareOwnerToken, connected: Map<String, String>,
        known: Map<String, String>, discovered: Set<String>) {
        val owner = synchronized(lifecycleLock) {
            current.takeIf { active(it) && it.token === origin }?.also {
                addCall(it); debugState = DebugSnapshot(connected, known, discovered); stateRevision++
            }
        } ?: return
        try { publishDebug() } finally { releaseCall(owner) }
    }

    /** Platform availability is an environment event, not a callback owned by an old service. */
    fun environmentChanged(evaluate: () -> WifiAwareSupport.Status) {
        val owner = synchronized(lifecycleLock) {
            current.takeIf { active(it) }?.also { addCall(it) }
        } ?: return
        try {
            val status = evaluate()
            publishSupportStatus(owner.token, status)
            if (!synchronized(lifecycleLock) { active(owner) }) return
            if (status.available) restartIfStillEnabled(owner.token, 500)
            else if (synchronized(lifecycleLock) { active(owner) && runningState }) stopLifetime(owner)
        } finally { releaseCall(owner) }
    }

    fun blocked(origin: WifiAwareOwnerToken, key: String, message: String) {
        val accepted = synchronized(lifecycleLock) {
            if (current.token !== origin || !active(current) || lastBlockedReason == key) false
            else { lastBlockedReason = key; true }
        }
        if (accepted) try { ports.debugMessage(message) } catch (_: Exception) { }
    }

    private fun debugMessage(owner: Lifetime<S>, message: String) {
        if (synchronized(lifecycleLock) { active(owner) }) {
            try { ports.debugMessage(message) } catch (_: Exception) { }
        }
    }

    fun setEnabled(value: Boolean) {
        val owner = synchronized(lifecycleLock) {
            enabledState = value; stateRevision++; current
        }
        publishEnabled()
        if (value) startIfPossible(owner.token) else stopLifetime(owner)
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
            val count = hotspotHolds.get()
            if (count == 0) return
            remaining = count - 1
            if (hotspotHolds.compareAndSet(count, remaining)) break
        }
        if (remaining == 0) restartIfStillEnabled(current.token)
    }

    fun startIfPossible() = startIfPossible(current.token)

    private fun mayStart(admission: Admission<S>): Boolean = active(admission.owner) && enabledState && !isHeldForHotspot()

    private fun startIfPossible(origin: WifiAwareOwnerToken) {
        val admission = synchronized(lifecycleLock) {
            val owner = current
            if (!active(owner) || owner.token !== origin || !enabledState || isHeldForHotspot()) return
            if (cleanupCount != 0) { pendingStart = origin; return }
            if (owner.starting != null) return
            val reusable = service?.let { owner.products[it] }
            Admission(owner, reusable, stateRevision).also { owner.starting = it; addCall(owner) }
        }
        var retained = false
        val reusable = admission.reusable
        try {
            if (reusable != null && ports.isRunning(reusable.value)) {
                synchronized(lifecycleLock) {
                    if (mayStart(admission) && service === reusable.value && stateRevision == admission.revision && cleanupCount == 0) {
                        runningState = true; stateRevision++
                    }
                }
                publishRunning()
                return
            }
            if (!ports.contextReady() || !ports.prepareStart(origin) ||
                !synchronized(lifecycleLock) { mayStart(admission) }) return
            try {
                val product = reusable ?: Product(ports.factory(origin)).also {
                    synchronized(lifecycleLock) { admission.owner.products[it.value] = it }
                }
                retained = !finishCandidate(Candidate(admission, product))
            } catch (failure: Throwable) {
                synchronized(lifecycleLock) {
                    admission.owner.accountingFailed = true
                    if (active(admission.owner)) { runningState = false; stateRevision++ }
                }
                publishRunning()
                ports.failure(failure)
                debugMessage(admission.owner, "Wi‑Fi Aware failed to start: ${failure.message}")
            }
        } finally { if (!retained) releaseAdmission(admission) }
    }

    private fun hasOlderCalls(owner: Lifetime<S>): Boolean =
        lifetimes.takeWhile { it !== owner }.any { it.calls != 0 }

    /** Returns false only when the product and admission remain retained for nonblocking replay. */
    private fun finishCandidate(candidate: Candidate<S>): Boolean {
        val proceed = synchronized(lifecycleLock) {
            if (!mayStart(candidate.admission)) false
            else {
                if (cleanupCount != 0 || hasOlderCalls(candidate.admission.owner)) {
                    pendingCandidates += candidate
                    return false
                }
                if (candidate.running == null) candidate.product.startDispatch++
                true
            }
        }
        if (!proceed) {
            discardCandidate(candidate)
            return true
        }
        if (candidate.running == null) {
            // The dispatch is admitted under the monitor before the external call.
            // A later fence awaits this admission and its candidate's stop/check.
            ports.start(candidate.product.value)
            return finishCandidate(candidate.copy(running = ports.isRunning(candidate.product.value)))
        }
        val published = synchronized(lifecycleLock) {
            if (mayStart(candidate.admission) &&
                (cleanupCount != 0 || hasOlderCalls(candidate.admission.owner))) {
                pendingCandidates += candidate
                return false
            }
            val accepted = mayStart(candidate.admission) && candidate.running
            if (accepted) {
                service = candidate.product.value
                serviceOwner = candidate.admission.owner
                runningState = true
                stateRevision++
            }
            accepted
        }
        publishRunning()
        if (published) {
            // A stop may have won after publication. Do not announce a detached product.
            val currentProduct = synchronized(lifecycleLock) {
                mayStart(candidate.admission) && service === candidate.product.value
            }
            if (currentProduct) {
                try { ports.published(candidate.product.value) } catch (_: Exception) { }
                synchronized(lifecycleLock) { if (active(candidate.admission.owner)) lastBlockedReason = null }
                debugMessage(candidate.admission.owner, "Wi‑Fi Aware started")
            }
        } else discardCandidate(candidate)
        return true
    }

    private fun discardCandidate(candidate: Candidate<S>) {
        synchronized(lifecycleLock) {
            if (service === candidate.product.value && serviceOwner === candidate.admission.owner) {
                service = null; serviceOwner = null; runningState = false; stateRevision++
            }
        }
        publishRunning()
        requestStop(candidate.product)
    }

    /** Observable publications reconcile outside the monitor; dispatch uses authoritative RAM. */
    private fun publishRunning() = publishState(
        snapshot = { runningState }, emit = { ports.beforeRunningPublication(it); _running.value = it })
    private fun publishEnabled() = publishState(snapshot = { enabledState }, emit = { _enabled.value = it })
    private fun publishStatus() = publishState(snapshot = { statusState }, emit = { status ->
        _supported.value = status?.supported ?: false
        _available.value = status?.available ?: false
        _supportStatus.value = status
    })
    private fun publishDebug() = publishState(snapshot = { debugState }, emit = {
        _connectedPeers.value = it.connected; _knownPeers.value = it.known; _discoveredPeers.value = it.discovered
    })
    private fun <T> publishState(snapshot: () -> T, emit: (T) -> Unit) {
        while (true) {
            val value = synchronized(lifecycleLock) { stateRevision to snapshot() }
            emit(value.second)
            if (synchronized(lifecycleLock) { stateRevision == value.first }) return
        }
    }

    fun stop() { stopLifetime(current) }

    private fun requestStop(product: Product<S>): CompletableDeferred<Boolean> {
        val stop = synchronized(lifecycleLock) {
            product.stop?.let { return it }
            CompletableDeferred<Boolean>().also { product.stop = it }
        }
        val confirmedRequest = try { ports.requestStop(product.value); true } catch (_: Throwable) { false }
        stop.complete(confirmedRequest)
        return stop
    }

    /** Called only under the RAM monitor; all effects happen in runCleanup. */
    private fun reserveCleanup(owner: Lifetime<S>, clearWhenEmpty: Boolean = true,
        requestProductStop: Boolean = true): Cleanup<S>? {
        if (serviceOwner !== owner && (!clearWhenEmpty || current !== owner)) return null
        cleanupCount++
        addCall(owner)
        val old = service?.let { owner.products[it] }
        if (serviceOwner === owner) { service = null; serviceOwner = null }
        owner.starting = null
        if (current === owner || service == null) { runningState = false; stateRevision++ }
        val observedStop = if (!requestProductStop && old != null && old.stop == null) {
            CompletableDeferred<Boolean>().also { old.stop = it }
        } else null
        return Cleanup(owner, old, requestProductStop, observedStop)
    }

    private fun stopLifetime(owner: Lifetime<S>, allowRetired: Boolean = false) {
        val cleanup = synchronized(lifecycleLock) {
            if (owner.retired && !allowRetired) return
            reserveCleanup(owner)
        } ?: return
        runCleanup(cleanup)
    }

    private fun runCleanup(cleanup: Cleanup<S>) {
        try {
            cleanup.observedStop?.complete(true)
            publishRunning()
            if (cleanup.requestProductStop) cleanup.product?.let { requestStop(it) }
            try { ports.clearPeers() } catch (_: Exception) { }
            synchronized(lifecycleLock) {
                if (current === cleanup.owner) { debugState = DebugSnapshot(); stateRevision++ }
            }
            publishDebug()
            debugMessage(cleanup.owner, "Wi‑Fi Aware stopped")
        } finally { try { finishCleanup() } finally { releaseCall(cleanup.owner) } }
    }

    private fun finishCleanup() {
        val origin = synchronized(lifecycleLock) {
            cleanupCount--
            if (cleanupCount != 0) return
            pendingStart.also { pendingStart = null }
        }
        try { replayCandidates() } finally { origin?.let { startIfPossible(it) } }
    }

    /** One caller replays retained products; no waiting, additional scope or background job. */
    private fun replayCandidates() {
        synchronized(lifecycleLock) {
            replayRequested = true
            if (replayingCandidates || cleanupCount != 0) return
            replayingCandidates = true
        }
        try {
            do {
                val pending = synchronized(lifecycleLock) {
                    replayRequested = false
                    pendingCandidates.toList().also { pendingCandidates.clear() }
                }
                pending.forEach { candidate ->
                    try {
                        if (finishCandidate(candidate)) releaseAdmission(candidate.admission)
                    } catch (failure: Throwable) {
                        synchronized(lifecycleLock) { candidate.admission.owner.accountingFailed = true }
                        requestStop(candidate.product)
                        releaseAdmission(candidate.admission)
                        try { ports.failure(failure) } catch (_: Exception) { }
                    }
                }
            } while (synchronized(lifecycleLock) { replayRequested && cleanupCount == 0 })
        } finally {
            val retry = synchronized(lifecycleLock) {
                replayingCandidates = false
                replayRequested && cleanupCount == 0
            }
            if (retry) replayCandidates()
        }
    }

    /** Fences synchronously, then shares cancellation-safe controller drain and separate product checks. */
    suspend fun retireForPanic(): WifiAwareControllerRetirement {
        val retirements = synchronized(lifecycleLock) {
            pendingStart = null
            lifetimes.map { owner ->
                owner.retired = true
                owner.starting = null
                val existing = owner.retirement
                if (existing != null) Retirement(owner, existing, false)
                else Retirement(owner, CompletableDeferred<WifiAwareControllerRetirement>().also {
                    owner.retirement = it
                }, true)
            }
        }
        return withContext(ports.scope.coroutineContext + NonCancellable) {
            val results = retirements.map { retirement ->
                if (retirement.leader) {
                    val result = try { drainLifetime(retirement.owner) } catch (_: Throwable) {
                        WifiAwareControllerRetirement(false, false)
                    }
                    synchronized(lifecycleLock) { retirement.owner.confirmed = result.admittedStartsDrained && result.capturedProductsAcknowledged }
                    retirement.result.complete(result)
                }
                retirement.result.await()
            }
            synchronized(lifecycleLock) {
                lifetimes.removeAll { it !== current && it.confirmed && it.calls == 0 && it.restarts.isEmpty() }
            }
            WifiAwareControllerRetirement(results.all { it.admittedStartsDrained }, results.all { it.capturedProductsAcknowledged })
        }
    }

    private suspend fun drainLifetime(owner: Lifetime<S>): WifiAwareControllerRetirement {
        val restarts = synchronized(lifecycleLock) { owner.restarts.toList() }
        val jobs = restarts.map { it.ready.await() }
        jobs.forEach { it?.cancel() }
        stopLifetime(owner, allowRetired = true)
        synchronized(lifecycleLock) { owner.idle }?.await()
        jobs.forEach { it?.join() }
        val products = synchronized(lifecycleLock) { owner.products.values.toList() }
        var acknowledged = jobs.none { it == null } && !synchronized(lifecycleLock) { owner.accountingFailed }
        for (product in products) {
            val stopped = requestStop(product).await()
            val checked = try { ports.awaitProductRetirement(product.value) } catch (_: Throwable) { false }
            if (!stopped || !checked) acknowledged = false
        }
        return WifiAwareControllerRetirement(true, acknowledged)
    }

    fun onServiceStopped(origin: WifiAwareOwnerToken, stopped: S) {
        val cleanup = synchronized(lifecycleLock) {
            val owner = current
            if (!active(owner) || owner.token !== origin || service !== stopped) return
            reserveCleanup(owner, requestProductStop = false)
        } ?: return
        runCleanup(cleanup)
    }

    /** Recovery reached its cleanup finally; this is not a checked stop acknowledgment. */
    fun onServiceRecoveryCleanupFinished(origin: WifiAwareOwnerToken, product: S) {
        val (owner, record, dispatch) = synchronized(lifecycleLock) {
            val owner = current
            if (!active(owner) || owner.token !== origin || !enabledState || isHeldForHotspot() ||
                serviceOwner !== owner || service !== product) return
            val record = owner.products[product] ?: return
            addCall(owner)
            Triple(owner, record, record.startDispatch)
        }
        try {
            if (ports.isRunning(product)) return
            val reconciled = synchronized(lifecycleLock) {
                if (!active(owner) || owner.token !== origin || !enabledState || isHeldForHotspot() ||
                    serviceOwner !== owner || service !== product || owner.products[product] !== record ||
                    record.startDispatch != dispatch) false
                else { runningState = false; stateRevision++; true }
            }
            if (reconciled) publishRunning()
        } finally { releaseCall(owner) }
    }

    fun restartIfStillEnabled(origin: WifiAwareOwnerToken, delayMs: Long = 0L) {
        val restart = synchronized(lifecycleLock) {
            val owner = current
            if (!active(owner) || owner.token !== origin || !enabledState) return
            owner.restart?.let { it.requested = true; return }
            Restart(owner).also { owner.restart = it; owner.restarts += it }
        }
        try {
            val job = ports.scope.launch(start = CoroutineStart.LAZY) {
                if (delayMs > 0L) delay(delayMs)
                do {
                    synchronized(lifecycleLock) { restart.requested = false }
                    var attempt = 0
                    while (restartNeeded(restart) && attempt < ports.maxRestartAttempts) {
                        if (!ports.restartSupported(origin) || !restartNeeded(restart)) break
                        startIfPossible(origin)
                        if (!restartNeeded(restart)) break
                        attempt++
                        delay(ports.restartDelayMs)
                    }
                } while (synchronized(lifecycleLock) { restart.requested } && restartNeeded(restart))
            }
            job.invokeOnCompletion { cause ->
                val retry = synchronized(lifecycleLock) {
                    restart.owner.restarts.remove(restart)
                    if (restart.owner.restart === restart) restart.owner.restart = null
                    cause == null && restart.requested && active(restart.owner) && enabledState && !runningState
                }
                if (retry) restartIfStillEnabled(origin)
            }
            restart.ready.complete(job)
            if (!synchronized(lifecycleLock) { active(restart.owner) }) job.cancel()
            job.start()
        } catch (failure: Throwable) {
            synchronized(lifecycleLock) {
                restart.owner.accountingFailed = true
                restart.owner.restarts.remove(restart)
                if (restart.owner.restart === restart) restart.owner.restart = null
            }
            restart.ready.complete(null)
            ports.failure(failure)
        }
    }

    private fun restartNeeded(restart: Restart<S>): Boolean = synchronized(lifecycleLock) {
        active(restart.owner) && enabledState && !runningState
    }
}
