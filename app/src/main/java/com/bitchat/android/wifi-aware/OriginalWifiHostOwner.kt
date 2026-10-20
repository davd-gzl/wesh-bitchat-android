package com.bitchat.android.wifiaware

import kotlinx.coroutines.*

/** These identities describe one original host; they cannot create an owner or restore a permit. */
internal sealed interface WifiHostIdentity
internal sealed interface WifiStopIdentity
internal sealed interface WifiTerminalReceipt
internal sealed interface OriginalWifiHostBinding { val identity: WifiHostIdentity }
internal sealed interface WifiStopOperation { val identity: WifiStopIdentity }
internal sealed interface WifiHostCaller
internal sealed interface WifiHostAwaitPreparation
internal enum class WifiHostGap {
    MissingCaller, UninspectableCaller, CallerRelated, ChangedCaller, ForeignBinding,
    MissingDriver, DuplicateDriver, PendingPublication, UnknownOperation, OrdinaryStopOverlap,
    BodyNotReturned, AwaitNotReturned, MissingHost, ControllerUnsettled, HolderUnsettled,
    InvalidOrigin, MissingParticipant, WitnessChanged, NeedsExplicitRetry
}
internal enum class WifiStopPhase { Reserved, Published, Entered, Returned, Threw }
internal enum class WifiInheritedPhase { NotCollected, Entered, Returned }
internal enum class WifiHostBatchStatus { NotCollected, Ineligible, NeedsExplicitRetry, EmptyValidated, RequestedNotAwaited, AwaitReturned }
internal data class WifiStopRow(
    val host: WifiHostIdentity, val operation: WifiStopIdentity?, val phase: WifiStopPhase?,
    val handlePublished: Boolean, val sameDeferredAwaitReturned: Boolean, val ordinaryUncertainty: Boolean
)
internal data class PanicOriginalWifiEvidence(
    val status: WifiHostBatchStatus = WifiHostBatchStatus.NotCollected,
    val rows: List<WifiStopRow> = emptyList(), val requests: List<WifiHostIdentity> = emptyList(),
    val cancelledHosts: List<WifiHostIdentity> = emptyList(), val joinedHosts: List<WifiHostIdentity> = emptyList(),
    val gaps: List<WifiHostGap> = emptyList(),
    val inheritedPhase: WifiInheritedPhase = WifiInheritedPhase.NotCollected,
    val stopAwaitReturned: List<WifiStopIdentity> = emptyList()
)
internal sealed interface WifiStopRequest {
    class Published(val operation: WifiStopOperation) : WifiStopRequest
    class Pending(val identity: WifiStopIdentity) : WifiStopRequest
    class Unconfirmed(val gap: WifiHostGap) : WifiStopRequest
}
internal sealed interface WifiHostPreparation {
    class Ready(val preparation: WifiHostAwaitPreparation) : WifiHostPreparation
    class Unconfirmed(val gap: WifiHostGap) : WifiHostPreparation
}
private class ActiveWifiCaller(val invoking: Job, val chain: List<Job>) : WifiHostCaller
private class UnknownWifiCaller(val gap: WifiHostGap) : WifiHostCaller

@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun captureOriginalWifiCaller(): WifiHostCaller {
    val context = currentCoroutineContext()
    context.ensureActive()
    val invoking = context[Job] ?: return UnknownWifiCaller(WifiHostGap.MissingCaller)
    val chain = mutableListOf<Job>()
    val seen = java.util.IdentityHashMap<Job, Unit>()
    try {
        var cursor: Job? = invoking
        while (cursor != null) {
            if (!cursor.isActive || seen.put(cursor, Unit) != null) return UnknownWifiCaller(WifiHostGap.UninspectableCaller)
            chain += cursor
            cursor = cursor.parent
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { return UnknownWifiCaller(WifiHostGap.UninspectableCaller) }
    context.ensureActive()
    return ActiveWifiCaller(invoking, chain.toList())
}
internal fun originalWifiCallerGap(caller: WifiHostCaller, target: Job): WifiHostGap? {
    val active = caller as? ActiveWifiCaller ?: return (caller as? UnknownWifiCaller)?.gap ?: WifiHostGap.UninspectableCaller
    return if (active.chain.any { it === target }) WifiHostGap.CallerRelated else null
}
internal suspend fun validateOriginalWifiCaller(caller: WifiHostCaller): Boolean {
    val context = currentCoroutineContext()
    context.ensureActive()
    return caller is ActiveWifiCaller && context[Job] === caller.invoking
}

/** Genuine root/driver custody. No method calls a Job, body, or continuation under the RAM monitor. */
internal class OriginalWifiHostOwner(dispatcher: CoroutineDispatcher) {
    companion object {
        internal fun ownerOf(binding: OriginalWifiHostBinding): OriginalWifiHostOwner? = (binding as? Binding)?.owner
    }
    private class HostIdentity : WifiHostIdentity
    private class StopIdentity : WifiStopIdentity
    private class Receipt : WifiTerminalReceipt
    private class Binding(val owner: OriginalWifiHostOwner) : OriginalWifiHostBinding {
        override val identity: WifiHostIdentity = HostIdentity()
    }
    private class Stop(val binding: Binding) : WifiStopOperation {
        override val identity: WifiStopIdentity = StopIdentity()
        var deferred: Deferred<Unit>? = null
        var phase = WifiStopPhase.Reserved
        var failed = false
    }
    private class Prepared(val owner: OriginalWifiHostOwner, val binding: Binding, val stop: Stop,
        val deferred: Deferred<Unit>, val caller: ActiveWifiCaller) : WifiHostAwaitPreparation
    private val monitor = Any()
    private val originalJob = SupervisorJob()
    val scope = CoroutineScope(dispatcher + originalJob)
    private val original = Binding(this)
    val binding: OriginalWifiHostBinding = original
    private var terminal: Receipt? = null
    private var driver: (suspend () -> Unit)? = null
    private var driverFault = false
    private var operation: Stop? = null
    private var ordinaryUncertainty = false
    private val ordinaryJobs = mutableListOf<Job>()
    private val awaitCallers = java.util.IdentityHashMap<ActiveWifiCaller, Stop>()

    fun installTerminalStop(binding: OriginalWifiHostBinding, body: suspend () -> Unit): Boolean = synchronized(monitor) {
        if (binding !== original || driver != null) { driverFault = true; false }
        else { driver = body; true }
    }
    fun fence(binding: OriginalWifiHostBinding): WifiTerminalReceipt? = synchronized(monitor) {
        if (binding !== original) null else terminal ?: Receipt().also { terminal = it }
    }
    fun startAllowed(): Boolean = synchronized(monitor) { terminal == null }
    /** Ordinary stop keeps its previous scheduling and self-cancel behavior, never terminal proof. */
    fun beginOrdinaryStop(): Boolean = synchronized(monitor) {
        if (terminal != null) false else { ordinaryUncertainty = true; true }
    }
    fun retainOrdinaryJob(job: Job) { synchronized(monitor) { ordinaryJobs += job } }
    fun noteOrdinaryFailure() { synchronized(monitor) { ordinaryUncertainty = true } }
    fun requestTerminalStop(binding: OriginalWifiHostBinding, caller: WifiHostCaller): WifiStopRequest {
        if (binding !== original) return WifiStopRequest.Unconfirmed(WifiHostGap.ForeignBinding)
        originalWifiCallerGap(caller, originalJob)?.let { return WifiStopRequest.Unconfirmed(it) }
        val selected = synchronized(monitor) {
            if (driverFault) return WifiStopRequest.Unconfirmed(WifiHostGap.DuplicateDriver)
            if (ordinaryUncertainty) return WifiStopRequest.Unconfirmed(WifiHostGap.OrdinaryStopOverlap)
            terminal = terminal ?: Receipt()
            operation?.let { old -> return old.deferred?.let { WifiStopRequest.Published(old) } ?: WifiStopRequest.Pending(old.identity) }
            val body = driver ?: return WifiStopRequest.Unconfirmed(WifiHostGap.MissingDriver)
            val stop = Stop(original)
            operation = stop
            stop to body
        }
        val (stop, body) = selected
        val deferred = try {
            scope.async(start = CoroutineStart.LAZY) {
                synchronized(monitor) { stop.phase = WifiStopPhase.Entered }
                try {
                    body()
                    synchronized(monitor) { stop.phase = WifiStopPhase.Returned }
                } catch (failure: Throwable) {
                    synchronized(monitor) { stop.phase = WifiStopPhase.Threw; stop.failed = true }
                    throw failure
                }
            }
        } catch (failure: Throwable) {
            synchronized(monitor) { stop.failed = true }
            throw failure
        }
        synchronized(monitor) { stop.deferred = deferred; if (stop.phase == WifiStopPhase.Reserved) stop.phase = WifiStopPhase.Published }
        deferred.start()
        return WifiStopRequest.Published(stop)
    }
    suspend fun awaitStopBody(operation: WifiStopOperation, caller: WifiHostCaller): Boolean {
        if (!validateOriginalWifiCaller(caller)) return false
        originalWifiCallerGap(caller, originalJob)?.let { return false }
        val pair = synchronized(monitor) {
            val stop = operation as? Stop ?: return false
            if (stop !== this.operation || stop.binding !== original || ordinaryUncertainty) return false
            stop to (stop.deferred ?: return false)
        }
        pair.second.await()
        if (!validateOriginalWifiCaller(caller)) return false
        return synchronized(monitor) {
            if (pair.first !== this.operation || pair.first.failed || pair.first.phase != WifiStopPhase.Returned || ordinaryUncertainty) false
            else { awaitCallers[caller as ActiveWifiCaller] = pair.first; true }
        }
    }
    suspend fun prepareHostAwait(binding: OriginalWifiHostBinding, operation: WifiStopOperation, caller: WifiHostCaller): WifiHostPreparation {
        if (!validateOriginalWifiCaller(caller)) return WifiHostPreparation.Unconfirmed(WifiHostGap.ChangedCaller)
        originalWifiCallerGap(caller, originalJob)?.let { return WifiHostPreparation.Unconfirmed(it) }
        return synchronized(monitor) {
            val stop = operation as? Stop
            when {
                binding !== original || stop == null || stop !== this.operation -> WifiHostPreparation.Unconfirmed(WifiHostGap.ForeignBinding)
                ordinaryUncertainty -> WifiHostPreparation.Unconfirmed(WifiHostGap.OrdinaryStopOverlap)
                driverFault || stop.failed || stop.phase != WifiStopPhase.Returned -> WifiHostPreparation.Unconfirmed(WifiHostGap.BodyNotReturned)
                awaitCallers[caller] !== stop -> WifiHostPreparation.Unconfirmed(WifiHostGap.AwaitNotReturned)
                stop.deferred == null -> WifiHostPreparation.Unconfirmed(WifiHostGap.PendingPublication)
                else -> WifiHostPreparation.Ready(Prepared(this, original, stop, checkNotNull(stop.deferred), caller as ActiveWifiCaller))
            }
        }
    }
    suspend fun validatePrepared(preparation: WifiHostAwaitPreparation): Boolean {
        val prepared = preparation as? Prepared ?: return false
        if (prepared.owner !== this || !validateOriginalWifiCaller(prepared.caller) ||
            originalWifiCallerGap(prepared.caller, originalJob) != null) return false
        return synchronized(monitor) {
            prepared.binding === original && prepared.stop === operation && prepared.stop.deferred === prepared.deferred &&
                prepared.stop.phase == WifiStopPhase.Returned && !prepared.stop.failed && !ordinaryUncertainty && !driverFault &&
                awaitCallers[prepared.caller] === prepared.stop
        }
    }
    suspend fun cancelPreparedHost(preparation: WifiHostAwaitPreparation): Boolean {
        val prepared = preparation as? Prepared ?: return false
        // A cancellation handler can cancel the invoking coroutine. Do not skip later safe requests.
        val sameCaller = currentCoroutineContext()[Job] === prepared.caller.invoking
        if (!sameCaller || prepared.owner !== this || originalWifiCallerGap(prepared.caller, originalJob) != null) return false
        val valid = synchronized(monitor) {
            prepared.binding === original && prepared.stop === operation && prepared.stop.deferred === prepared.deferred &&
                prepared.stop.phase == WifiStopPhase.Returned && !prepared.stop.failed && !ordinaryUncertainty && !driverFault &&
                awaitCallers[prepared.caller] === prepared.stop
        }
        if (!valid) return false
        originalJob.cancel()
        return true
    }
    suspend fun awaitPreparedHost(preparation: WifiHostAwaitPreparation): Boolean {
        if (!validatePrepared(preparation)) return false
        originalJob.join()
        return validatePrepared(preparation)
    }
    fun observation(binding: OriginalWifiHostBinding): WifiStopRow? = synchronized(monitor) {
        if (binding !== original) null else WifiStopRow(original.identity, operation?.identity, operation?.phase,
            operation?.deferred != null, awaitCallers.isNotEmpty(), ordinaryUncertainty)
    }
}

/** Only issuer-owned bindings can reach a host; public scalar rows never restore this reference. */
internal fun wifiHostOwner(binding: OriginalWifiHostBinding): OriginalWifiHostOwner? =
    OriginalWifiHostOwner.ownerOf(binding)
