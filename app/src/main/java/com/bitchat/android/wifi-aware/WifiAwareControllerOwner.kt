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

/** A known refusal has no product; an opaque constructor failure still throws. */
internal sealed interface WifiAwareFactoryResult<out S : Any> {
    data class Created<S : Any>(val value: S) : WifiAwareFactoryResult<S>
    data object RefusedBeforeAllocation : WifiAwareFactoryResult<Nothing>
}

/** Only explicit initialization supplies original Holder provenance. */
internal sealed interface HolderInitialBinding<out H : Any> {
    data object Uninitialized : HolderInitialBinding<Nothing>
    class Bound<H : Any>(val original: H) : HolderInitialBinding<H>
}

/** Constructing these ports performs no platform lookup or service initialization. */
internal data class WifiAwareControllerPorts<S : Any, H : Any>(
    val scope: CoroutineScope,
    val contextReady: () -> Boolean,
    val prepareStart: (WifiAwareOwnerToken) -> Boolean,
    val restartSupported: (WifiAwareOwnerToken) -> Boolean,
    val factory: (WifiAwareOwnerToken, H) -> WifiAwareFactoryResult<S>,
    val start: (S) -> Unit,
    val isRunning: (S) -> Boolean,
    val requestStop: (S) -> Unit,
    val awaitProductRetirement: suspend (S) -> Boolean,
    val published: (S, H) -> Unit,
    val clearPeers: () -> Unit,
    val debugMessage: (String) -> Unit,
    val failure: (Throwable) -> Unit,
    val restartDelayMs: Long = 2_000L,
    val maxRestartAttempts: Int = 15,
    val beforeRunningPublication: (Boolean) -> Unit = {}
)

internal sealed interface OriginalWifiControllerParticipant {
    fun fence(): WifiTerminalReceipt
    fun observe(): WifiControllerOriginalView
    fun validate(view: WifiControllerOriginalView): Boolean
    fun requestRestartCancellation(view: WifiControllerOriginalView, caller: WifiHostCaller): List<Throwable>
    suspend fun awaitRestarts(view: WifiControllerOriginalView, caller: WifiHostCaller): Boolean
}
internal class WifiControllerProduct(val product: Any, val token: WifiAwareOwnerToken, val host: OriginalWifiHostBinding?)
internal sealed interface WifiControllerOriginalView {
    val products: List<WifiControllerProduct>
    val gaps: List<WifiHostGap>
}
internal sealed interface ControllerInitializationTicket
internal sealed interface ControllerInitializationAdmission {
    class Accepted(val ticket: ControllerInitializationTicket) : ControllerInitializationAdmission
    class Refused(val receipt: WifiTerminalReceipt) : ControllerInitializationAdmission
    data object Invalid : ControllerInitializationAdmission
}
internal sealed interface WifiControllerBeginResult {
    class Begun(val token: WifiAwareOwnerToken) : WifiControllerBeginResult
    class Refused(val receipt: WifiTerminalReceipt) : WifiControllerBeginResult
    data object Invalid : WifiControllerBeginResult
}
internal enum class WifiInitializationOutcome { Begun, RefusedByController, UnknownFailure }
internal class OriginalWifiControllerClosed : IllegalStateException("Original Wi-Fi controller is closed")
internal fun originalWifiParticipantOrigin(participant: OriginalWifiControllerParticipant): Any? =
    WifiAwareControllerOwner.participantOrigin(participant)

/** The singleton's actual lifecycle algorithm, with RAM state and injected platform actions. */
internal class WifiAwareControllerOwner<S : Any, H : Any>(
    initialBinding: HolderInitialBinding<H>,
    private val ports: WifiAwareControllerPorts<S, H>
) {
    private class Lifetime<S : Any, H : Any>(val binding: HolderInitialBinding<H>, val token: WifiAwareOwnerToken = WifiAwareOwnerToken()) {
        var retired = false
        var starting: Admission<S, H>? = null
        var calls = 0
        var idle: CompletableDeferred<Unit>? = null
        var accountingFailed = false
        val products = IdentityHashMap<S, Product<S>>()
        var restart: Restart<S, H>? = null
        val restarts = mutableSetOf<Restart<S, H>>()
        val originalRestarts = mutableListOf<Restart<S, H>>()
        var retirement: CompletableDeferred<WifiAwareControllerRetirement>? = null
        var confirmed = false
    }
    private class Admission<S : Any, H : Any>(val owner: Lifetime<S, H>, val original: H, val reusable: Product<S>?, val revision: Long) {
        var released = false
    }
    private class Product<S : Any>(val value: S) {
        var stop: CompletableDeferred<Boolean>? = null
        // Failed preparation and unrelated publications do not invalidate liveness samples.
        var startDispatch = 0L
    }
    private class Restart<S : Any, H : Any>(val owner: Lifetime<S, H>) {
        val ready = CompletableDeferred<Job?>()
        var requested = true
        @Volatile var published: Job? = null
        var publicationFailed = false
    }
    private data class Cleanup<S : Any, H : Any>(val owner: Lifetime<S, H>, val product: Product<S>?,
        val requestProductStop: Boolean, val observedStop: CompletableDeferred<Boolean>?)
    private data class Candidate<S : Any, H : Any>(val admission: Admission<S, H>, val product: Product<S>, val running: Boolean? = null)
    private data class Retirement<S : Any, H : Any>(val owner: Lifetime<S, H>,
        val result: CompletableDeferred<WifiAwareControllerRetirement>, val leader: Boolean)
    private data class DebugSnapshot(val connected: Map<String, String> = emptyMap(),
        val known: Map<String, String> = emptyMap(), val discovered: Set<String> = emptySet())

    private val lifecycleLock = Any()
    companion object {
        internal fun participantOrigin(participant: OriginalWifiControllerParticipant): Any? =
            (participant as? Participant<*, *>)?.original
    }
    private class ControllerReceipt : WifiTerminalReceipt
    private class Initialization<S : Any, H : Any>(val participant: Participant<S, H>) : ControllerInitializationTicket {
        var token: WifiAwareOwnerToken? = null
        var ended = false
        var outcome: WifiInitializationOutcome? = null
    }
    private class ControllerView<S : Any, H : Any>(
        val participant: Participant<S, H>, val revision: Long,
        val lifetimes: List<Lifetime<S, H>>, val restarts: List<Restart<S, H>>,
        val stopRecords: List<Pair<Product<S>, CompletableDeferred<Boolean>?>>,
        products: List<WifiControllerProduct>, gaps: List<WifiHostGap>
    ) : WifiControllerOriginalView {
        private val originalProducts = products.toList()
        private val originalGaps = gaps.toList()
        override val products: List<WifiControllerProduct> get() = originalProducts.toList()
        override val gaps: List<WifiHostGap> get() = originalGaps.toList()
    }
    private class Participant<S : Any, H : Any>(
        val owner: WifiAwareControllerOwner<S, H>, val original: H,
        val hostOf: (S) -> OriginalWifiHostBinding?
    ) : OriginalWifiControllerParticipant {
        var terminal: ControllerReceipt? = null
        override fun fence(): WifiTerminalReceipt = owner.fenceOriginal(this)
        override fun observe(): WifiControllerOriginalView = owner.observeOriginal(this)
        override fun validate(view: WifiControllerOriginalView): Boolean = owner.validateOriginal(this, view)
        override fun requestRestartCancellation(view: WifiControllerOriginalView, caller: WifiHostCaller): List<Throwable> =
            owner.requestRestartCancellation(this, view, caller)
        override suspend fun awaitRestarts(view: WifiControllerOriginalView, caller: WifiHostCaller): Boolean =
            owner.awaitRestarts(this, view, caller)
    }
    private val originalParticipants = mutableListOf<Participant<S, H>>()
    private val originalInitializations = mutableListOf<Initialization<S, H>>()
    private val lostOriginalHistory = IdentityHashMap<H, Unit>()
    private fun originalOf(owner: Lifetime<S, H>): H? = when (val binding = owner.binding) {
        is HolderInitialBinding.Bound -> binding.original
        HolderInitialBinding.Uninitialized -> null
    }
    private fun pinned(owner: Lifetime<S, H>): Boolean = originalParticipants.any { it.original === originalOf(owner) }
    private fun recordPruned(owner: Lifetime<S, H>): Boolean {
        originalOf(owner)?.let { lostOriginalHistory[it] = Unit }
        return true
    }
    fun originalParticipant(original: H, hostOf: (S) -> OriginalWifiHostBinding?): OriginalWifiControllerParticipant = synchronized(lifecycleLock) {
        originalParticipants.firstOrNull { it.original === original }
            ?: Participant(this, original, hostOf).also { originalParticipants += it; stateRevision++ }
    }
    private fun matching(participant: Participant<S, H>): List<Lifetime<S, H>> =
        lifetimes.filter { originalOf(it) === participant.original }
    private fun fenceOriginal(participant: Participant<S, H>): WifiTerminalReceipt = synchronized(lifecycleLock) {
        participant.terminal?.let { return it }
        ControllerReceipt().also { receipt ->
            participant.terminal = receipt
            matching(participant).forEach { owner ->
                owner.retired = true
                owner.starting = null
                if (pendingStart === owner.token) pendingStart = null
            }
            stateRevision++
        }
    }
    fun admitOriginalInitialization(participant: OriginalWifiControllerParticipant): ControllerInitializationAdmission = synchronized(lifecycleLock) {
        val original = originalParticipants.firstOrNull { it === participant } ?: return ControllerInitializationAdmission.Invalid
        original.terminal?.let { return ControllerInitializationAdmission.Refused(it) }
        ControllerInitializationAdmission.Accepted(Initialization(original).also { originalInitializations += it; stateRevision++ })
    }
    fun beginOriginalOwner(participant: OriginalWifiControllerParticipant, ticket: ControllerInitializationTicket): WifiControllerBeginResult {
        val original = synchronized(lifecycleLock) { originalParticipants.firstOrNull { it === participant } }
            ?: return WifiControllerBeginResult.Invalid
        val token = beginOwnerChecked(original.original, ticket) ?: return synchronized(lifecycleLock) {
            original.terminal?.let { WifiControllerBeginResult.Refused(it) } ?: WifiControllerBeginResult.Invalid
        }
        return WifiControllerBeginResult.Begun(token)
    }
    fun finishOriginalInitialization(ticket: ControllerInitializationTicket, outcome: WifiInitializationOutcome) = synchronized(lifecycleLock) {
        val admitted = originalInitializations.firstOrNull { it === ticket } ?: return@synchronized
        if (admitted.ended || (outcome == WifiInitializationOutcome.Begun && admitted.token == null)) {
            admitted.outcome = WifiInitializationOutcome.UnknownFailure
        } else admitted.outcome = outcome
        admitted.ended = true
        stateRevision++
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun observeOriginal(participant: Participant<S, H>): WifiControllerOriginalView {
        val captured = synchronized(lifecycleLock) {
            val owners = matching(participant)
            val gaps = mutableListOf<WifiHostGap>()
            if (participant.terminal == null || lostOriginalHistory.containsKey(participant.original) ||
                originalInitializations.any { it.participant === participant && (!it.ended || it.outcome == WifiInitializationOutcome.UnknownFailure) } ||
                owners.any { it.calls != 0 || it.accountingFailed || it.starting != null } ||
                pendingCandidates.any { candidate -> owners.any { it === candidate.admission.owner } } || cleanupCount != 0) gaps += WifiHostGap.ControllerUnsettled
            val restarts = owners.flatMap { it.originalRestarts }
            if (restarts.any { it.published == null || it.publicationFailed }) gaps += WifiHostGap.PendingPublication
            Triple(stateRevision, owners.toList(), Pair(restarts.toList(), gaps.toList()))
        }
        // Real product mapping and Job state reads are deliberately outside lifecycle RAM.
        val jobs = captured.third.first
        val gaps = captured.third.second.toMutableList()
        if (jobs.any { it.published?.isCompleted != true }) gaps += WifiHostGap.ControllerUnsettled
        val values = synchronized(lifecycleLock) { captured.second.flatMap { owner ->
            owner.products.values.map { Triple(it, owner.token, it.stop) }
        } }
        val stops = values.map { it.first to it.third }
        // These old stop acknowledgments remain request-return metadata, not host-body proof.
        // Their unknown/false outcomes cannot disappear when a late product is no longer current.
        stops.forEach { (_, stop) ->
            if (stop != null && (!stop.isCompleted || runCatching { stop.getCompleted() }.getOrDefault(false) != true)) {
                gaps += WifiHostGap.ControllerUnsettled
            }
        }
        val products = values.map { (product, token, _) -> WifiControllerProduct(product.value, token, participant.hostOf(product.value)) }
        if (products.any { it.host == null }) gaps += WifiHostGap.MissingHost
        return ControllerView(participant, captured.first, captured.second, jobs, stops, products, gaps.distinct())
    }
    private fun validateOriginal(participant: Participant<S, H>, view: WifiControllerOriginalView): Boolean {
        val issued = view as? ControllerView<*, *> ?: return false
        return synchronized(lifecycleLock) {
            issued.participant === participant && issued.revision == stateRevision && participant.terminal != null &&
                issued.gaps.isEmpty() && matching(participant).size == issued.lifetimes.size &&
                matching(participant).all { owner -> issued.lifetimes.any { it === owner } } &&
                !lostOriginalHistory.containsKey(participant.original) &&
                matching(participant).all { it.calls == 0 && !it.accountingFailed && it.starting == null } &&
                originalInitializations.none { it.participant === participant && (!it.ended || it.outcome == WifiInitializationOutcome.UnknownFailure) } &&
                pendingCandidates.none { candidate -> matching(participant).any { it === candidate.admission.owner } } &&
                issued.stopRecords.all { (product, stop) -> product.stop === stop } &&
                matching(participant).sumOf { it.products.size } == issued.products.size &&
                matching(participant).all { owner -> owner.products.values.all { product ->
                    issued.products.any { it.product === product.value && it.token === owner.token }
                } } && cleanupCount == 0
        }
    }
    private fun requestRestartCancellation(participant: Participant<S, H>, view: WifiControllerOriginalView,
        caller: WifiHostCaller): List<Throwable> {
        val issued = view as? ControllerView<*, *> ?: return emptyList()
        if (issued.participant !== participant) return emptyList()
        val failures = mutableListOf<Throwable>()
        issued.restarts.forEach { record ->
            val job = record.published
            if (job != null && originalWifiCallerGap(caller, job) == null) {
                try { job.cancel() } catch (failure: Throwable) { failures += failure }
            }
        }
        return failures
    }
    private suspend fun awaitRestarts(participant: Participant<S, H>, view: WifiControllerOriginalView,
        caller: WifiHostCaller): Boolean {
        val issued = view as? ControllerView<*, *> ?: return false
        if (issued.participant !== participant || !validateOriginalWifiCaller(caller)) return false
        for (record in issued.restarts) {
            val job = record.published ?: return false
            if (originalWifiCallerGap(caller, job) != null) return false
            job.join()
        }
        return validateOriginal(participant, view) && validateOriginalWifiCaller(caller)
    }

    @Volatile private var service: S? = null
    @Volatile private var current = Lifetime<S, H>(initialBinding)
    private val lifetimes = mutableListOf(current)
    private var serviceOwner: Lifetime<S, H>? = null
    private var stateRevision = 0L
    private var enabledState = false
    private var runningState = false
    private var statusState: WifiAwareSupport.Status? = null
    private var debugState = DebugSnapshot()
    private var cleanupCount = 0
    private var pendingStart: WifiAwareOwnerToken? = null
    private val pendingCandidates = mutableListOf<Candidate<S, H>>()
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
    fun beginOwner(original: H): WifiAwareOwnerToken =
        beginOwnerChecked(original, null) ?: throw OriginalWifiControllerClosed()

    private fun beginOwnerChecked(original: H, ticket: ControllerInitializationTicket?): WifiAwareOwnerToken? {
        val fresh = Lifetime<S, H>(HolderInitialBinding.Bound(original))
        val cleanup = synchronized(lifecycleLock) {
            val participant = originalParticipants.firstOrNull { it.original === original }
            if (participant?.terminal != null) return null
            if (ticket != null) {
                val admission = originalInitializations.firstOrNull { it === ticket } ?: return null
                if (admission.participant !== participant || admission.ended || admission.token != null) return null
                admission.token = fresh.token
            }
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
            lifetimes.removeAll { !pinned(it) && it !== fresh && it.calls == 0 && it.restarts.isEmpty() &&
                (it.confirmed || (it.products.isEmpty() && !it.accountingFailed && it.retirement == null)) && recordPruned(it) }
            reserved
        }
        if (cleanup != null) runCleanup(cleanup) else publishDebug()
        return fresh.token
    }

    fun currentOwner(): WifiAwareOwnerToken = current.token

    /** Identity-only volatile RAM read: no locks, factories, callbacks or platform calls. */
    fun getService(): S? = service

    private fun active(owner: Lifetime<S, H>): Boolean = current === owner && !owner.retired
    private fun addCall(owner: Lifetime<S, H>) {
        if (owner.calls++ == 0) owner.idle = CompletableDeferred()
    }
    private fun releaseCall(owner: Lifetime<S, H>) {
        val idle = synchronized(lifecycleLock) {
            check(owner.calls > 0)
            if (--owner.calls == 0) owner.idle.also { owner.idle = null } else null
        }
        idle?.complete(Unit)
        if (idle != null) replayCandidates()
    }
    private fun releaseAdmission(admission: Admission<S, H>) {
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

    private fun debugMessage(owner: Lifetime<S, H>, message: String) {
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

    private fun mayStart(admission: Admission<S, H>): Boolean = active(admission.owner) && enabledState && !isHeldForHotspot()

    private fun startIfPossible(origin: WifiAwareOwnerToken) {
        val admission = synchronized(lifecycleLock) {
            val owner = current
            if (!active(owner) || owner.token !== origin || !enabledState || isHeldForHotspot()) return
            if (cleanupCount != 0) { pendingStart = origin; return }
            if (owner.starting != null) return
            val original = when (val binding = owner.binding) {
                is HolderInitialBinding.Bound -> binding.original
                HolderInitialBinding.Uninitialized -> return
            }
            val reusable = service?.let { owner.products[it] }
            Admission(owner, original, reusable, stateRevision).also { owner.starting = it; addCall(owner) }
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
                val product = reusable ?: when (val result = ports.factory(origin, admission.original)) {
                    is WifiAwareFactoryResult.Created -> Product(result.value).also {
                        synchronized(lifecycleLock) { admission.owner.products[it.value] = it }
                    }
                    WifiAwareFactoryResult.RefusedBeforeAllocation -> return
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

    private fun hasOlderCalls(owner: Lifetime<S, H>): Boolean =
        lifetimes.takeWhile { it !== owner }.any { it.calls != 0 }

    /** Returns false only when the product and admission remain retained for nonblocking replay. */
    private fun finishCandidate(candidate: Candidate<S, H>): Boolean {
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
                try { ports.published(candidate.product.value, candidate.admission.original) } catch (_: Exception) { }
                synchronized(lifecycleLock) { if (active(candidate.admission.owner)) lastBlockedReason = null }
                debugMessage(candidate.admission.owner, "Wi‑Fi Aware started")
            }
        } else discardCandidate(candidate)
        return true
    }

    private fun discardCandidate(candidate: Candidate<S, H>) {
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
    private fun reserveCleanup(owner: Lifetime<S, H>, clearWhenEmpty: Boolean = true,
        requestProductStop: Boolean = true): Cleanup<S, H>? {
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

    private fun stopLifetime(owner: Lifetime<S, H>, allowRetired: Boolean = false) {
        val cleanup = synchronized(lifecycleLock) {
            if (owner.retired && !allowRetired) return
            reserveCleanup(owner)
        } ?: return
        runCleanup(cleanup)
    }

    private fun runCleanup(cleanup: Cleanup<S, H>) {
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
                lifetimes.removeAll { !pinned(it) && it !== current && it.confirmed && it.calls == 0 && it.restarts.isEmpty() && recordPruned(it) }
            }
            WifiAwareControllerRetirement(results.all { it.admittedStartsDrained }, results.all { it.capturedProductsAcknowledged })
        }
    }

    private suspend fun drainLifetime(owner: Lifetime<S, H>): WifiAwareControllerRetirement {
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
            Restart(owner).also { owner.restart = it; owner.restarts += it; owner.originalRestarts += it }
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
            synchronized(lifecycleLock) { restart.published = job }
            job.invokeOnCompletion { cause ->
                val retry = synchronized(lifecycleLock) {
                    if (!pinned(restart.owner)) { originalOf(restart.owner)?.let { lostOriginalHistory[it] = Unit }; restart.owner.originalRestarts.remove(restart) }
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
                restart.publicationFailed = true
                if (!pinned(restart.owner)) { originalOf(restart.owner)?.let { lostOriginalHistory[it] = Unit }; restart.owner.originalRestarts.remove(restart) }
                restart.owner.restarts.remove(restart)
                if (restart.owner.restart === restart) restart.owner.restart = null
            }
            restart.ready.complete(null)
            ports.failure(failure)
        }
    }

    private fun restartNeeded(restart: Restart<S, H>): Boolean = synchronized(lifecycleLock) {
        active(restart.owner) && enabledState && !runningState
    }
}
