package com.bitchat.android.ui

import com.bitchat.android.mesh.UnifiedMeshService
import com.bitchat.android.wifiaware.*
import com.bitchat.android.services.SeenReceiptAdmission
import com.bitchat.android.service.HolderAdmittedManagerFrontier
import com.bitchat.android.service.HolderGossipCensusCapture
import com.bitchat.android.service.HolderCensusGap
import com.bitchat.android.service.captureHolderAdmittedManagerFrontier
import com.bitchat.android.service.validateHolderAdmittedManagerFrontier
import com.bitchat.android.service.HolderGossipCaptureGap
import com.bitchat.android.service.HolderGossipChildOutcome
import com.bitchat.android.service.HolderGossipSubsetCapture
import com.bitchat.android.service.HolderGossipSubsetSnapshot
import com.bitchat.android.service.HolderPanicBinding
import com.bitchat.android.service.observeHolderOriginalGossipSubset
import com.bitchat.android.sync.GossipManagerJobBinding
import com.bitchat.android.sync.GossipManagerRootCloseOutcome
import com.bitchat.android.sync.GossipRootCloseReceipt
import com.bitchat.android.sync.GossipReturnedJobsCapture
import com.bitchat.android.sync.GossipWorkOutcome
import com.bitchat.android.sync.GossipWorkTicket
import com.bitchat.android.sync.GossipRecordedFrontier
import com.bitchat.android.sync.GossipRecordedFrontierPreparation
import com.bitchat.android.sync.GossipRecordedFrontierJoinOutcome
import com.bitchat.android.sync.GossipRecordedJoinGap
import com.bitchat.android.sync.GossipSyncManager
import com.bitchat.android.sync.GossipDirectCloseReceipt
import com.bitchat.android.sync.GossipDirectCloseOutcome
import com.bitchat.android.sync.GossipDirectIngressSnapshot
import com.bitchat.android.sync.GossipDirectEndedCapture
import com.bitchat.android.sync.GossipDirectEndedFrontier
import com.bitchat.android.sync.GossipDirectFrontierGap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal enum class PanicRetirementGap {
    MissingCapturedFacade, FacadeUnconfirmed, ProducerNotImplemented,
    InstalledFacadeMissing, InstalledFacadeMismatch, OriginalHolderInputUnconfirmed
}

internal sealed interface PanicOriginalGossipInput {
    data object MissingCapturedHolder : PanicOriginalGossipInput
    class Installed(val binding: HolderPanicBinding, val exactOriginalMesh: Any) : PanicOriginalGossipInput
}

internal enum class PanicGossipRootCloseGap { ForeignBinding, OperationFailed }
internal sealed interface PanicGossipRootCloseAttempt {
    val manager: GossipSyncManager
    class Closed(
        override val manager: GossipSyncManager,
        val binding: GossipManagerJobBinding,
        val receipt: GossipRootCloseReceipt
    ) : PanicGossipRootCloseAttempt
    class Unconfirmed(
        override val manager: GossipSyncManager,
        val binding: GossipManagerJobBinding?,
        val gap: PanicGossipRootCloseGap
    ) : PanicGossipRootCloseAttempt
}
internal sealed interface KnownDirectIngressClosure {
    data object NotAttempted : KnownDirectIngressClosure
    class Attempted(val managers: List<KnownManagerDirectClosure>) : KnownDirectIngressClosure
}
internal class KnownManagerDirectClosure(
    val originalClose: PanicGossipRootCloseAttempt,
    val outcome: KnownManagerDirectCloseOutcome
)
internal sealed interface KnownManagerDirectCloseOutcome {
    class Closed(
        val receipt: GossipDirectCloseReceipt,
        val snapshotAtClose: GossipDirectIngressSnapshot
    ) : KnownManagerDirectCloseOutcome
    data object RootNotClosed : KnownManagerDirectCloseOutcome
    data object ForeignBinding : KnownManagerDirectCloseOutcome
    data object ForeignRootReceipt : KnownManagerDirectCloseOutcome
    data object OperationFailed : KnownManagerDirectCloseOutcome
}

internal enum class KnownDirectEndBatchGap { MissingOrFailedClose, ClosureMismatch }
internal sealed interface KnownDirectEndRejection {
    class Batch(val gap: KnownDirectEndBatchGap) : KnownDirectEndRejection
    class Manager(val original: KnownManagerDirectClosure, val gap: GossipDirectFrontierGap) : KnownDirectEndRejection
}
internal class KnownManagerDirectEnd(
    val original: KnownManagerDirectClosure,
    val frontier: GossipDirectEndedFrontier
)
internal sealed interface KnownDirectBodiesEnd {
    data object NotAttempted : KnownDirectBodiesEnd
    class Ineligible(val reasons: List<KnownDirectEndRejection>) : KnownDirectBodiesEnd
    class Observed(val managers: List<KnownManagerDirectEnd>) : KnownDirectBodiesEnd
}

internal enum class KnownJobCaptureFailureKind { OrdinaryFailure, CancellationThrown }
internal sealed interface KnownManagerJobCaptureOutcome {
    class Captured(val result: GossipReturnedJobsCapture) : KnownManagerJobCaptureOutcome
    class OperationFailed(val kind: KnownJobCaptureFailureKind) : KnownManagerJobCaptureOutcome
}
internal class KnownManagerReturnedJobs(
    val originalClose: PanicGossipRootCloseAttempt.Closed,
    val outcome: KnownManagerJobCaptureOutcome
)
internal class KnownJobOrigin(
    val managerSnapshot: KnownManagerReturnedJobs,
    val tickets: List<GossipWorkTicket>
)
internal enum class JobCancellationRequestOutcome {
    RequestAttempted, OperationFailed, CancellationThrown,
    SkippedCallerRelated, SkippedCallerRelationshipUnknown, SkippedMissingCaller
}
internal class KnownJobCancellationRequest(
    val originalJob: Job,
    val origins: List<KnownJobOrigin>,
    val outcome: JobCancellationRequestOutcome
)
internal sealed interface KnownReturnedJobsCancellation {
    data object NotAttempted : KnownReturnedJobsCancellation
    class Attempted(
        val managers: List<KnownManagerReturnedJobs>,
        val requests: List<KnownJobCancellationRequest>
    ) : KnownReturnedJobsCancellation
}

internal enum class KnownRecordedBatchGap { MissingOrFailedClose, MissingOrFailedCapture, CancellationNotAttempted, UnsafeCancellation, HolderFrontierUnavailable, HolderFrontierChanged, NeedsExplicitCancellationRetry }
internal class KnownRecordedManagerJoin(
    val original: KnownManagerReturnedJobs,
    val outcome: GossipRecordedFrontierJoinOutcome
)
internal sealed interface KnownRecordedJoinRejection {
    class Batch(val gap: KnownRecordedBatchGap) : KnownRecordedJoinRejection
    class Manager(val original: KnownManagerReturnedJobs, val gap: GossipRecordedJoinGap) : KnownRecordedJoinRejection
}
internal sealed interface KnownRecordedFrontiersJoin {
    data object NotAttempted : KnownRecordedFrontiersJoin
    data object NoKnownRecordedJobs : KnownRecordedFrontiersJoin
    class Ineligible(val reasons: List<KnownRecordedJoinRejection>) : KnownRecordedFrontiersJoin
    class Attempted(val managers: List<KnownRecordedManagerJoin>) : KnownRecordedFrontiersJoin
}

internal enum class PanicProcessorRequestStatus { RequestReturned, UnsafeCaller }
internal data class PanicProcessorRequest(
    val root: com.bitchat.android.mesh.OriginalProcessorRootIdentity,
    val status: PanicProcessorRequestStatus
)
internal enum class PanicProcessorBatchStatus {
    MissingInput, InvalidInput, Ineligible, NeedsExplicitRetry, RequestedNotAwaited, AwaitReturned
}
internal data class PanicOriginalProcessorEvidence(
    val status: PanicProcessorBatchStatus,
    val rows: List<com.bitchat.android.service.HolderProcessorRow>,
    val requests: List<PanicProcessorRequest>,
    val holderGaps: List<com.bitchat.android.service.HolderProcessorGap>,
    val callerGaps: List<com.bitchat.android.mesh.OriginalProcessorGap>,
    val awaitReturnedRoots: List<com.bitchat.android.mesh.OriginalProcessorRootIdentity>
)

internal data class PanicStoreForwardRequest(
    val root: com.bitchat.android.mesh.OriginalStoreForwardRootIdentity,
    val status: PanicProcessorRequestStatus
)
internal enum class PanicStoreForwardBatchStatus { Ineligible, NeedsExplicitRetry, RequestedNotAwaited, AwaitReturned }
internal data class PanicOriginalStoreForwardEvidence(
    val status: PanicStoreForwardBatchStatus,
    val rows: List<com.bitchat.android.service.HolderStoreForwardRow>,
    val requests: List<PanicStoreForwardRequest>,
    val holderGaps: List<com.bitchat.android.service.HolderStoreForwardGap>,
    val callerGaps: List<com.bitchat.android.mesh.OriginalStoreForwardGap>,
    val awaitReturnedRoots: List<com.bitchat.android.mesh.OriginalStoreForwardRootIdentity>
)

internal sealed interface PanicUnconfirmedEvidence {
    class OriginalProcessorStoreForwardUnion(
        val processorObservation: PanicOriginalProcessorEvidence,
        val storeForwardObservation: PanicOriginalStoreForwardEvidence
    ) : PanicUnconfirmedEvidence
    class OriginalBluetoothProcessors(val observation: PanicOriginalProcessorEvidence) : PanicUnconfirmedEvidence
    class OriginalBluetoothAndCoreProcessors(val observation: PanicOriginalProcessorEvidence) : PanicUnconfirmedEvidence
    data object NotCollected : PanicUnconfirmedEvidence
    class InvalidInstalledPair(val gap: PanicRetirementGap) : PanicUnconfirmedEvidence
    class InvalidOriginalGossip(val gap: HolderGossipCaptureGap) : PanicUnconfirmedEvidence
    class KnownOriginalGossip(
        val snapshot: HolderGossipSubsetSnapshot<GossipSyncManager>,
        val attempts: List<PanicGossipRootCloseAttempt>,
        val directIngressClosure: KnownDirectIngressClosure,
        val returnedJobsCancellation: KnownReturnedJobsCancellation = KnownReturnedJobsCancellation.NotAttempted,
        val recordedFrontierJoin: KnownRecordedFrontiersJoin = KnownRecordedFrontiersJoin.NotAttempted,
        val holderFrontier: HolderGossipCensusCapture<GossipSyncManager>? = null,
        val directBodiesEnd: KnownDirectBodiesEnd = KnownDirectBodiesEnd.NotAttempted
    ) : PanicUnconfirmedEvidence
}
internal sealed interface PanicOwnerRetirement {
    data object Confirmed : PanicOwnerRetirement
    class Unconfirmed(val gap: PanicRetirementGap, val evidence: PanicUnconfirmedEvidence) : PanicOwnerRetirement {
        constructor(gap: PanicRetirementGap) : this(gap, PanicUnconfirmedEvidence.NotCollected)
    }
}

/** Destruction requires every reported ownership proof; unavailable producers keep it refused. */
internal data class PanicRetirementReport(
    val facade: PanicOwnerRetirement,
    val wifiController: PanicOwnerRetirement,
    val holderConstructionAndCallbacks: PanicOwnerRetirement,
    val gossipJobsAndWriters: PanicOwnerRetirement,
    val transportCoreRadioAndHandlers: PanicOwnerRetirement,
    val startupAndRemainingSharedKeyUsers: PanicOwnerRetirement,
    val originalWifiHosts: PanicOriginalWifiEvidence = PanicOriginalWifiEvidence()
) {
    val confirmed: Boolean get() = facade === PanicOwnerRetirement.Confirmed &&
        wifiController === PanicOwnerRetirement.Confirmed &&
        holderConstructionAndCallbacks === PanicOwnerRetirement.Confirmed &&
        gossipJobsAndWriters === PanicOwnerRetirement.Confirmed &&
        transportCoreRadioAndHandlers === PanicOwnerRetirement.Confirmed &&
        startupAndRemainingSharedKeyUsers === PanicOwnerRetirement.Confirmed
}

/** Known-manager root closure restricts admission; every missing aggregate proof stays unconfirmed. */
private class GossipRestriction(val early: PanicRetirementReport?, val evidence: PanicUnconfirmedEvidence)

private suspend fun restrictOriginalGossip(originalFacade: UnifiedMeshService?, originalGossip: PanicOriginalGossipInput,
    savedCaller: GossipCallerFrame? = null, failures: MutableList<Throwable>? = null): GossipRestriction {
    val evidence = when (originalGossip) {
        PanicOriginalGossipInput.MissingCapturedHolder -> PanicUnconfirmedEvidence.NotCollected
        is PanicOriginalGossipInput.Installed -> {
            val pairGap = when {
                originalFacade == null -> PanicRetirementGap.InstalledFacadeMissing
                originalGossip.exactOriginalMesh !== originalFacade -> PanicRetirementGap.InstalledFacadeMismatch
                else -> null
            }
            if (pairGap != null) return GossipRestriction(unconfirmedPanicReport(
                PanicOwnerRetirement.Unconfirmed(pairGap), PanicUnconfirmedEvidence.InvalidInstalledPair(pairGap)
            ), PanicUnconfirmedEvidence.NotCollected)
            when (val captured = observeHolderOriginalGossipSubset(originalGossip.binding, originalGossip.exactOriginalMesh)) {
                is HolderGossipSubsetCapture.Unconfirmed -> return GossipRestriction(unconfirmedPanicReport(
                    PanicOwnerRetirement.Unconfirmed(PanicRetirementGap.OriginalHolderInputUnconfirmed),
                    PanicUnconfirmedEvidence.InvalidOriginalGossip(captured.gap)
                ), PanicUnconfirmedEvidence.NotCollected)
                is HolderGossipSubsetCapture.Observed -> {
                    val originalInvoker = if (savedCaller == null) currentCoroutineContext()[Job] else savedCaller.invoking
                    val closed = closeKnownOriginalGossipRoots(captured.snapshot, failures)
                    val direct = closeKnownOriginalDirectIngress(closed.attempts, failures)
                    val cancellation = cancelKnownOriginalReturnedJobs(closed, originalInvoker, savedCaller)
                    currentCoroutineContext().ensureActive()
                    PanicUnconfirmedEvidence.KnownOriginalGossip(closed.snapshot, closed.attempts, direct, cancellation)
                }
            }
        }
    }
    return GossipRestriction(null, evidence)
}

internal suspend fun collectPanicRetirement(
    originalFacade: UnifiedMeshService?,
    originalGossip: PanicOriginalGossipInput
): PanicRetirementReport {
    val restricted = restrictOriginalGossip(originalFacade, originalGossip)
    restricted.early?.let { return it }
    val evidence = restricted.evidence
    // Restrict already-known managers before any facade await, never after waiting for Holder entries.
    val facade = when {
        originalFacade == null -> PanicOwnerRetirement.Unconfirmed(PanicRetirementGap.MissingCapturedFacade)
        originalFacade.retireForPanic() -> PanicOwnerRetirement.Confirmed
        else -> PanicOwnerRetirement.Unconfirmed(PanicRetirementGap.FacadeUnconfirmed)
    }
    return unconfirmedPanicReport(facade, evidence)
}

private class PreparedKnownManager(
    val original: KnownManagerReturnedJobs,
    val frontier: GossipRecordedFrontier
)

/** Joins only a wholly eligible recorded batch after the unchanged cancellation/facade phase. */
internal suspend fun collectPanicRetirementWithRecordedJobsJoin(
    originalFacade: UnifiedMeshService?,
    originalGossip: PanicOriginalGossipInput
): PanicRetirementReport {
    val report = collectPanicRetirement(originalFacade, originalGossip)
    return collectRecordedGossipPhase(report, originalGossip)
}

private suspend fun collectRecordedGossipPhase(
    report: PanicRetirementReport,
    originalGossip: PanicOriginalGossipInput, preflight: (() -> Boolean)? = null
): PanicRetirementReport {
    if (preflight?.invoke() == false) return report
    val gossip = report.gossipJobsAndWriters as? PanicOwnerRetirement.Unconfirmed ?: return report
    val known = gossip.evidence as? PanicUnconfirmedEvidence.KnownOriginalGossip ?: return report
    val installed = originalGossip as? PanicOriginalGossipInput.Installed ?: return report
    val captured = captureHolderAdmittedManagerFrontier(installed.binding, installed.exactOriginalMesh)
    val frontier = (captured as? HolderGossipCensusCapture.Sealed<GossipSyncManager>)?.frontier
    val joined = when {
        frontier == null -> KnownRecordedFrontiersJoin.Ineligible(listOf(
            KnownRecordedJoinRejection.Batch(KnownRecordedBatchGap.HolderFrontierUnavailable)))
        !sameCoveredManagerIdentities(known, frontier) -> KnownRecordedFrontiersJoin.Ineligible(listOf(
            KnownRecordedJoinRejection.Batch(KnownRecordedBatchGap.NeedsExplicitCancellationRetry)))
        else -> joinKnownRecordedFrontiers(known, installed, frontier, preflight)
    }
    currentCoroutineContext().ensureActive()
    val current = if (frontier != null && !validateHolderAdmittedManagerFrontier(installed.binding, installed.exactOriginalMesh, frontier)) {
        HolderGossipCensusCapture.Invalid(HolderCensusGap.WitnessChanged)
    } else captured
    return report.copy(gossipJobsAndWriters = PanicOwnerRetirement.Unconfirmed(gossip.gap,
        PanicUnconfirmedEvidence.KnownOriginalGossip(known.snapshot, known.attempts,
            known.directIngressClosure, known.returnedJobsCancellation, joined, current, known.directBodiesEnd)))
}

/** Original caller frames are call-scoped and captured before any new request or cancellation. */
private class WifiCollectionFrame(
    val installed: PanicOriginalGossipInput.Installed, val facade: UnifiedMeshService,
    val caller: WifiHostCaller, val ppCaller: com.bitchat.android.mesh.OriginalProcessorCaller,
    val storeCaller: com.bitchat.android.mesh.OriginalStoreForwardCaller,
    val gossipCaller: GossipCallerFrame
) {
    val failures = mutableListOf<Throwable>()
    private val requested = mutableListOf<OriginalWifiHostBinding>()
    private val operations = mutableListOf<Pair<OriginalWifiHostBinding, WifiStopOperation>>()
    private val requestGaps = mutableListOf<WifiHostGap>()
    private var current: com.bitchat.android.service.HolderWifiHostBatch? = null
    private var restricted: GossipRestriction? = null
    private var inheritedEntered = false
    private var inheritedPhase = WifiInheritedPhase.NotCollected
    private val awaitedStops = mutableListOf<WifiStopIdentity>()
    private val cancelled = mutableListOf<WifiHostIdentity>()
    private var status = WifiHostBatchStatus.Ineligible

    fun requestEarly() {
        com.bitchat.android.service.fenceHolderOriginalWifiHosts(installed.binding, facade)
        val captured = com.bitchat.android.service.captureHolderOriginalWifiHosts(installed.binding, facade)
        val batch = (captured as? com.bitchat.android.service.HolderWifiHostCapture.Observed)?.batch
        if (batch == null) { requestGaps += (captured as com.bitchat.android.service.HolderWifiHostCapture.Invalid).gap; return }
        batch.controllers.indices.forEach { index ->
            try { failures += batch.controllers[index].requestRestartCancellation(batch.controllerViews[index], caller) }
            catch (failure: Throwable) { failures += failure }
        }
        batch.hosts.forEach { binding ->
            val owner = wifiHostOwner(binding)
            if (owner == null) requestGaps += WifiHostGap.ForeignBinding
            else try {
                when (val request = owner.requestTerminalStop(binding, caller)) {
                    is WifiStopRequest.Published -> { requested += binding; operations += binding to request.operation }
                    is WifiStopRequest.Pending -> { requested += binding; requestGaps += WifiHostGap.PendingPublication }
                    is WifiStopRequest.Unconfirmed -> requestGaps += request.gap
                }
            } catch (failure: Throwable) { failures += failure }
        }
    }
    suspend fun restrictGossip() {
        try { restricted = restrictOriginalGossip(facade, installed, gossipCaller, failures) }
        catch (failure: Throwable) { failures += failure }
    }
    fun gate(): Boolean {
        val captured = com.bitchat.android.service.captureHolderOriginalWifiHosts(installed.binding, facade)
        val batch = (captured as? com.bitchat.android.service.HolderWifiHostCapture.Observed)?.batch
        current = batch
        if (batch == null || requestGaps.isNotEmpty() || batch.gaps.isNotEmpty()) return false
        if (batch.hosts.size != requested.size || batch.hosts.any { target -> requested.count { it === target } != 1 }) {
            status = WifiHostBatchStatus.NeedsExplicitRetry
            return false
        }
        return com.bitchat.android.service.validateHolderOriginalWifiHosts(installed.binding, facade, batch)
    }
    fun stillValid(): Boolean = current?.let {
        com.bitchat.android.service.validateHolderOriginalWifiHosts(installed.binding, facade, it)
    } ?: false
    fun scoped(report: PanicRetirementReport): PanicRetirementReport {
        val captured = current
        val rows = requested.mapNotNull { binding -> wifiHostOwner(binding)?.observation(binding) }
        return report.copy(originalWifiHosts = PanicOriginalWifiEvidence(status, rows,
            requested.map { it.identity }, cancelled.toList(), emptyList(),
            (requestGaps + (captured?.gaps ?: emptyList())).distinct(), inheritedPhase, awaitedStops.toList()))
    }
    fun notCollected(): PanicRetirementReport = scoped(unconfirmedPanicReport(
        PanicOwnerRetirement.Unconfirmed(PanicRetirementGap.OriginalHolderInputUnconfirmed),
        restricted?.evidence ?: PanicUnconfirmedEvidence.NotCollected))
    suspend fun inheritedReport(): PanicRetirementReport {
        if (!stillValid()) return notCollected()
        val stage = restricted ?: return notCollected()
        stage.early?.let { return scoped(it) }
        inheritedEntered = true
        inheritedPhase = WifiInheritedPhase.Entered
        val retired = facade.retireForPanic()
        if (!stillValid()) return notCollected()
        val report = unconfirmedPanicReport(if (retired) PanicOwnerRetirement.Confirmed else
            PanicOwnerRetirement.Unconfirmed(PanicRetirementGap.FacadeUnconfirmed), stage.evidence)
        val completed = collectEndedDirectPhase(report, installed, ::stillValid)
        inheritedPhase = WifiInheritedPhase.Returned
        return completed
    }
    suspend fun finish(report: PanicRetirementReport): PanicRetirementReport {
        if (!inheritedEntered || !stillValid() || report.facade !== PanicOwnerRetirement.Confirmed) return scoped(report)
        val inherited = (report.transportCoreRadioAndHandlers as? PanicOwnerRetirement.Unconfirmed)?.evidence
            as? PanicUnconfirmedEvidence.OriginalProcessorStoreForwardUnion ?: return scoped(report)
        if (inherited.processorObservation.status != PanicProcessorBatchStatus.AwaitReturned ||
            inherited.storeForwardObservation.status != PanicStoreForwardBatchStatus.AwaitReturned) return scoped(report)
        val batch = current ?: return scoped(report)
        for (index in batch.controllers.indices) {
            if (!stillValid() || !batch.controllers[index].awaitRestarts(batch.controllerViews[index], caller)) return scoped(report)
        }
        var allStopBodiesReturned = true
        for ((binding, operation) in operations) {
            try {
                if (!stillValid() || wifiHostOwner(binding)?.awaitStopBody(operation, caller) != true || !stillValid()) {
                    allStopBodiesReturned = false
                } else awaitedStops += operation.identity
            } catch (failure: Throwable) {
                failures += failure
                allStopBodiesReturned = false
            }
        }
        try { currentCoroutineContext().ensureActive() }
        catch (cancelled: CancellationException) { failures += cancelled }
        throwRequestFailures(failures)
        if (!allStopBodiesReturned) return scoped(report)
        val preparations = mutableListOf<Pair<OriginalWifiHostBinding, WifiHostAwaitPreparation>>()
        for ((binding, operation) in operations) {
            if (!stillValid()) return scoped(report)
            when (val prepared = wifiHostOwner(binding)?.prepareHostAwait(binding, operation, caller)) {
                is WifiHostPreparation.Ready -> preparations += binding to prepared.preparation
                is WifiHostPreparation.Unconfirmed -> { requestGaps += prepared.gap; return scoped(report) }
                null -> { requestGaps += WifiHostGap.ForeignBinding; return scoped(report) }
            }
        }
        if (!stillValid() || preparations.size != batch.hosts.size) return scoped(report)
        preparations.forEach { (binding, preparation) ->
            try {
                if (wifiHostOwner(binding)?.cancelPreparedHost(preparation) == true) cancelled += binding.identity
                else requestGaps += WifiHostGap.WitnessChanged
            } catch (failure: Throwable) { failures += failure }
        }
        try { currentCoroutineContext().ensureActive() }
        catch (cancelled: CancellationException) { failures += cancelled }
        throwRequestFailures(failures)
        currentCoroutineContext().ensureActive()
        if (!stillValid() || requestGaps.isNotEmpty() || cancelled.size != preparations.size) return scoped(report)
        preparations.forEach { (binding, preparation) ->
            if (wifiHostOwner(binding)?.validatePrepared(preparation) != true) return scoped(report)
        }
        // Baseline: all genuine preparation and cancellation above returned; original-host joins are deliberately absent.
        status = if (preparations.isEmpty()) WifiHostBatchStatus.EmptyValidated else WifiHostBatchStatus.RequestedNotAwaited
        return scoped(report)
    }
}

private fun throwRequestFailures(failures: List<Throwable>) {
    if (failures.isEmpty()) return
    val primary = failures.firstOrNull { it is CancellationException } ?: failures.first()
    val seen = java.util.IdentityHashMap<Throwable, Unit>()
    seen[primary] = Unit
    failures.forEach { if (seen.put(it, Unit) == null) primary.addSuppressed(it) }
    throw primary
}

internal suspend fun collectPanicRetirementWithOriginalWifiHosts(
    originalFacade: UnifiedMeshService?, originalGossip: PanicOriginalGossipInput
): PanicRetirementReport {
    val installed = originalGossip as? PanicOriginalGossipInput.Installed
    if (installed == null || originalFacade == null || installed.exactOriginalMesh !== originalFacade) {
        return unconfirmedPanicReport(PanicOwnerRetirement.Unconfirmed(PanicRetirementGap.OriginalHolderInputUnconfirmed),
            PanicUnconfirmedEvidence.NotCollected).copy(originalWifiHosts = PanicOriginalWifiEvidence(
                WifiHostBatchStatus.Ineligible, gaps = listOf(WifiHostGap.InvalidOrigin)))
    }
    val caller = captureOriginalWifiCaller()
    val ppCaller = com.bitchat.android.mesh.captureOriginalProcessorCaller()
    val storeCaller = com.bitchat.android.mesh.captureOriginalStoreForwardCaller()
    val gossipCaller = captureGossipCallerFrame(currentCoroutineContext()[Job])
    val frame = WifiCollectionFrame(installed, originalFacade, caller, ppCaller, storeCaller, gossipCaller)
    frame.requestEarly()
    val report = collectOriginalProcessors(originalFacade, originalGossip, PanicProcessorScope.BluetoothAndCore,
        StoreForwardCollection.RequiredOriginalUnion, frame)
    return frame.finish(report)
}

private enum class PanicProcessorScope { BluetoothOnly, BluetoothAndCore }
private enum class StoreForwardCollection { NotRequestedLegacy, RequiredOriginalUnion }

/** Compatibility entry point for the original Bluetooth-only scope. */
internal suspend fun collectPanicRetirementWithOriginalBluetoothProcessors(
    originalFacade: UnifiedMeshService?, originalGossip: PanicOriginalGossipInput
): PanicRetirementReport = collectOriginalProcessors(originalFacade, originalGossip, PanicProcessorScope.BluetoothOnly, StoreForwardCollection.NotRequestedLegacy)

/** All known safe original Bluetooth and Core roots are requested before any processor await. */
internal suspend fun collectPanicRetirementWithOriginalBluetoothAndCoreProcessors(
    originalFacade: UnifiedMeshService?, originalGossip: PanicOriginalGossipInput
): PanicRetirementReport = collectOriginalProcessors(originalFacade, originalGossip, PanicProcessorScope.BluetoothAndCore, StoreForwardCollection.NotRequestedLegacy)

/** Required original StoreForward authority is distinct from the two old processor scopes. */
internal suspend fun collectPanicRetirementWithOriginalProcessorStoreForwardUnion(
    originalFacade: UnifiedMeshService?, originalGossip: PanicOriginalGossipInput
): PanicRetirementReport = collectOriginalProcessors(originalFacade, originalGossip,
    PanicProcessorScope.BluetoothAndCore, StoreForwardCollection.RequiredOriginalUnion)

private fun captureOriginalProcessors(
    scope: PanicProcessorScope, binding: com.bitchat.android.service.HolderPanicBinding, product: Any
): com.bitchat.android.service.HolderProcessorCapture = when (scope) {
    PanicProcessorScope.BluetoothOnly -> com.bitchat.android.service.captureHolderOriginalBluetoothProcessors(binding, product)
    PanicProcessorScope.BluetoothAndCore -> com.bitchat.android.service.captureHolderOriginalBluetoothAndCoreProcessors(binding, product)
}

private fun validateOriginalProcessors(
    scope: PanicProcessorScope, binding: com.bitchat.android.service.HolderPanicBinding,
    product: Any, batch: com.bitchat.android.service.HolderProcessorBatch
): Boolean = when (scope) {
    PanicProcessorScope.BluetoothOnly -> com.bitchat.android.service.validateHolderOriginalBluetoothProcessors(binding, product, batch)
    PanicProcessorScope.BluetoothAndCore -> com.bitchat.android.service.validateHolderOriginalBluetoothAndCoreProcessors(binding, product, batch)
}

private suspend fun collectOriginalProcessors(
    originalFacade: UnifiedMeshService?, originalGossip: PanicOriginalGossipInput, scope: PanicProcessorScope,
    storeCollection: StoreForwardCollection, wifiFrame: WifiCollectionFrame? = null
): PanicRetirementReport {
    val installed = originalGossip as? PanicOriginalGossipInput.Installed
    val requireStore = storeCollection == StoreForwardCollection.RequiredOriginalUnion
    var storeEvidence = PanicOriginalStoreForwardEvidence(PanicStoreForwardBatchStatus.Ineligible,
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    fun withEvidence(report: PanicRetirementReport, evidence: PanicOriginalProcessorEvidence) = report.copy(
        transportCoreRadioAndHandlers = PanicOwnerRetirement.Unconfirmed(PanicRetirementGap.ProducerNotImplemented,
            if (requireStore) PanicUnconfirmedEvidence.OriginalProcessorStoreForwardUnion(evidence, storeEvidence)
            else when (scope) {
                PanicProcessorScope.BluetoothOnly -> PanicUnconfirmedEvidence.OriginalBluetoothProcessors(evidence)
                PanicProcessorScope.BluetoothAndCore -> PanicUnconfirmedEvidence.OriginalBluetoothAndCoreProcessors(evidence)
            }))
    fun empty(status: PanicProcessorBatchStatus) = PanicOriginalProcessorEvidence(status, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    if (installed == null || originalFacade == null || installed.exactOriginalMesh !== originalFacade) {
        val report = wifiFrame?.notCollected() ?: collectPanicRetirementWithEndedDirectFrontiers(originalFacade, originalGossip)
        return withEvidence(report, empty(if (installed == null) PanicProcessorBatchStatus.MissingInput else PanicProcessorBatchStatus.InvalidInput))
    }
    val firstUnion = if (requireStore) com.bitchat.android.service.captureHolderOriginalProcessorStoreForwardUnion(
        installed.binding, installed.exactOriginalMesh) else null
    val originalUnion = (firstUnion as? com.bitchat.android.service.HolderProcessorStoreForwardCapture.Observed)?.batch
    val first = if (!requireStore) captureOriginalProcessors(scope, installed.binding, installed.exactOriginalMesh)
        else when (firstUnion) {
            is com.bitchat.android.service.HolderProcessorStoreForwardCapture.Observed ->
                com.bitchat.android.service.HolderProcessorCapture.Observed(firstUnion.batch.processorBatch)
            is com.bitchat.android.service.HolderProcessorStoreForwardCapture.Invalid -> when (val failure = firstUnion.failure) {
                is com.bitchat.android.service.HolderProcessorStoreForwardFailure.Processor ->
                    com.bitchat.android.service.HolderProcessorCapture.Invalid(failure.gap)
                is com.bitchat.android.service.HolderProcessorStoreForwardFailure.StoreForward -> {
                    storeEvidence = storeEvidence.copy(holderGaps = listOf(failure.gap))
                    com.bitchat.android.service.HolderProcessorCapture.Invalid(com.bitchat.android.service.HolderProcessorGap.MissingCollector)
                }
            }
            null -> error("Required StoreForward capture must be performed")
        }
    val original = (first as? com.bitchat.android.service.HolderProcessorCapture.Observed)?.batch
    if (original == null) {
        val report = wifiFrame?.notCollected() ?: collectPanicRetirementWithEndedDirectFrontiers(originalFacade, originalGossip)
        return withEvidence(report, empty(PanicProcessorBatchStatus.InvalidInput).copy(holderGaps = listOf(
            (first as com.bitchat.android.service.HolderProcessorCapture.Invalid).gap)))
    }
    // Snapshot active caller ancestry before any cancellation can detach its original links.
    val caller = wifiFrame?.ppCaller ?: com.bitchat.android.mesh.captureOriginalProcessorCaller()
    val storeCaller = if (requireStore) wifiFrame?.storeCaller ?: com.bitchat.android.mesh.captureOriginalStoreForwardCaller() else null
    val storeRequests = mutableListOf<PanicStoreForwardRequest>()
    val storesRequested = mutableListOf<com.bitchat.android.mesh.OriginalStoreForwardBinding>()
    val storeCallerGaps = mutableListOf<com.bitchat.android.mesh.OriginalStoreForwardGap>()
    val requests = mutableListOf<PanicProcessorRequest>()
    val requested = mutableListOf<com.bitchat.android.mesh.OriginalProcessorBinding>()
    val awaitReturnedRoots = mutableListOf<com.bitchat.android.mesh.OriginalProcessorRootIdentity>()
    val storeAwaitReturnedRoots = mutableListOf<com.bitchat.android.mesh.OriginalStoreForwardRootIdentity>()
    val callerGaps = mutableListOf<com.bitchat.android.mesh.OriginalProcessorGap>()
    val failures = wifiFrame?.failures ?: mutableListOf<Throwable>()
    original.roots.forEach { binding ->
        val gap = com.bitchat.android.mesh.originalProcessorRelationshipGap(binding, caller)
        if (gap != null) {
            callerGaps += gap
            requests += PanicProcessorRequest(binding.identity, PanicProcessorRequestStatus.UnsafeCaller)
        } else try {
            if (com.bitchat.android.mesh.requestOriginalProcessorShutdown(binding)) {
                requested += binding
                requests += PanicProcessorRequest(binding.identity, PanicProcessorRequestStatus.RequestReturned)
            } else callerGaps += com.bitchat.android.mesh.OriginalProcessorGap.RequestUnavailable
        } catch (failure: Throwable) { failures += failure }
    }
    // Both original active caller chains were captured before the first cancellation.
    originalUnion?.storeForwardRoots?.forEach { binding ->
        val gap = com.bitchat.android.mesh.originalStoreForwardRelationshipGap(binding, checkNotNull(storeCaller))
        if (gap != null) {
            storeCallerGaps += gap
            storeRequests += PanicStoreForwardRequest(binding.identity, PanicProcessorRequestStatus.UnsafeCaller)
        } else try {
            if (com.bitchat.android.mesh.requestOriginalStoreForwardShutdown(binding)) {
                storesRequested += binding
                storeRequests += PanicStoreForwardRequest(binding.identity, PanicProcessorRequestStatus.RequestReturned)
            } else storeCallerGaps += com.bitchat.android.mesh.OriginalStoreForwardGap.RequestUnavailable
        } catch (failure: Throwable) { failures += failure }
    }
    wifiFrame?.restrictGossip()
    try { currentCoroutineContext().ensureActive() }
    catch (cancelled: CancellationException) { failures += cancelled }
    if (failures.isNotEmpty()) {
        val primary = failures.firstOrNull { it is CancellationException } ?: failures.first()
        val seen = java.util.IdentityHashMap<Throwable, Unit>()
        seen[primary] = Unit
        failures.forEach { if (seen.put(it, Unit) == null) primary.addSuppressed(it) }
        throw primary
    }
    currentCoroutineContext().ensureActive()
    if (wifiFrame != null && !wifiFrame.gate()) {
        storeEvidence = storeEvidence.copy(rows = originalUnion?.storeForwardRows ?: emptyList(),
            requests = storeRequests.toList(), holderGaps = originalUnion?.storeForwardGaps ?: emptyList(),
            callerGaps = storeCallerGaps.toList())
        return withEvidence(wifiFrame.notCollected(), empty(PanicProcessorBatchStatus.Ineligible).copy(
            rows = original.rows, requests = requests.toList(), callerGaps = callerGaps.toList()))
    }
    val report = wifiFrame?.inheritedReport() ?: collectPanicRetirementWithEndedDirectFrontiers(originalFacade, originalGossip)
    currentCoroutineContext().ensureActive()
    val secondUnion = if (requireStore) com.bitchat.android.service.captureHolderOriginalProcessorStoreForwardUnion(
        installed.binding, installed.exactOriginalMesh) else null
    val readyUnion = (secondUnion as? com.bitchat.android.service.HolderProcessorStoreForwardCapture.Observed)?.batch
    val second = if (!requireStore) captureOriginalProcessors(scope, installed.binding, installed.exactOriginalMesh)
        else when (secondUnion) {
            is com.bitchat.android.service.HolderProcessorStoreForwardCapture.Observed ->
                com.bitchat.android.service.HolderProcessorCapture.Observed(secondUnion.batch.processorBatch)
            is com.bitchat.android.service.HolderProcessorStoreForwardCapture.Invalid -> when (val failure = secondUnion.failure) {
                is com.bitchat.android.service.HolderProcessorStoreForwardFailure.Processor ->
                    com.bitchat.android.service.HolderProcessorCapture.Invalid(failure.gap)
                is com.bitchat.android.service.HolderProcessorStoreForwardFailure.StoreForward ->
                    com.bitchat.android.service.HolderProcessorCapture.Invalid(com.bitchat.android.service.HolderProcessorGap.MissingCollector)
            }
            null -> error("Required StoreForward recapture must be performed")
        }
    val ready = (second as? com.bitchat.android.service.HolderProcessorCapture.Observed)?.batch
    var status = PanicProcessorBatchStatus.Ineligible
    val gaps = (ready?.gaps ?: listOf((second as com.bitchat.android.service.HolderProcessorCapture.Invalid).gap)).toMutableList()
    var storeStatus = PanicStoreForwardBatchStatus.Ineligible
    val storeGaps = (readyUnion?.storeForwardGaps ?: originalUnion?.storeForwardGaps ?: emptyList()).toMutableList()
    val storePreparations = mutableListOf<com.bitchat.android.mesh.OriginalStoreForwardAwaitPreparation>()
    var storeEligible = !requireStore
    if (requireStore && readyUnion != null && gaps.isEmpty() && storeGaps.isEmpty() &&
        callerGaps.isEmpty() && storeCallerGaps.isEmpty()) {
        val roots = readyUnion.storeForwardRoots
        if (roots.size != storesRequested.size || roots.any { root -> storesRequested.count { it === root } != 1 }) {
            storeStatus = PanicStoreForwardBatchStatus.NeedsExplicitRetry
        } else {
            roots.forEach { root ->
                when (val prepared = com.bitchat.android.mesh.prepareOriginalStoreForwardAwait(root, checkNotNull(storeCaller))) {
                    is com.bitchat.android.mesh.OriginalStoreForwardPreparation.Ready -> storePreparations += prepared.preparation
                    is com.bitchat.android.mesh.OriginalStoreForwardPreparation.Unconfirmed -> storeCallerGaps += prepared.gap
                }
            }
            var valid = storeCallerGaps.isEmpty() && storePreparations.size == roots.size
            storePreparations.forEach { if (!com.bitchat.android.mesh.validateOriginalStoreForwardPreparation(it)) valid = false }
            storeEligible = valid && com.bitchat.android.service.validateHolderOriginalProcessorStoreForwardUnion(
                installed.binding, installed.exactOriginalMesh, readyUnion)
        }
    }
    suspend fun wholeUnionStillValid(): Boolean {
        if (!requireStore) return true
        if (!storeEligible || readyUnion == null || !com.bitchat.android.service.validateHolderOriginalProcessorStoreForwardUnion(
                installed.binding, installed.exactOriginalMesh, readyUnion)) return false
        storePreparations.forEach { if (!com.bitchat.android.mesh.validateOriginalStoreForwardPreparation(it)) return false }
        return true
    }
    // All Store and processor preparations must be valid before even the first processor wait.
    if (report.facade === PanicOwnerRetirement.Confirmed && ready != null && gaps.isEmpty() && callerGaps.isEmpty() && storeEligible) {
        val roots = ready.roots
        if (roots.size != requested.size || roots.any { root -> requested.count { it === root } != 1 }) {
            status = PanicProcessorBatchStatus.NeedsExplicitRetry
        } else {
            val preparations = mutableListOf<com.bitchat.android.mesh.OriginalProcessorAwaitPreparation>()
            roots.forEach { root ->
                when (val prepared = com.bitchat.android.mesh.prepareOriginalProcessorAwait(root, caller)) {
                    is com.bitchat.android.mesh.OriginalProcessorPreparation.Ready -> preparations += prepared.preparation
                    is com.bitchat.android.mesh.OriginalProcessorPreparation.Unconfirmed -> callerGaps += prepared.gap
                }
            }
            var valid = callerGaps.isEmpty() && preparations.size == roots.size
            preparations.forEach { if (!com.bitchat.android.mesh.validateOriginalProcessorPreparation(it)) valid = false }
            if (valid && validateOriginalProcessors(scope, installed.binding, installed.exactOriginalMesh, ready) && wholeUnionStillValid()) {
                var allReturned = true
                for ((index, preparation) in preparations.withIndex()) {
                    if (wifiFrame?.stillValid() == false || !wholeUnionStillValid() || !validateOriginalProcessors(scope, installed.binding, installed.exactOriginalMesh, ready) ||
                        !com.bitchat.android.mesh.awaitOriginalProcessor(preparation)) {
                        allReturned = false
                        break
                    }
                    awaitReturnedRoots += roots[index].identity
                }
                if (wifiFrame?.stillValid() != false && allReturned && validateOriginalProcessors(scope, installed.binding, installed.exactOriginalMesh, ready) && wholeUnionStillValid()) {
                    status = PanicProcessorBatchStatus.AwaitReturned
                    if (requireStore) {
                        val storeRoots = checkNotNull(readyUnion).storeForwardRoots
                        var allStoresReturned = true
                        for ((index, preparation) in storePreparations.withIndex()) {
                            if (wifiFrame?.stillValid() == false || !wholeUnionStillValid() || !validateOriginalProcessors(scope, installed.binding, installed.exactOriginalMesh, ready) ||
                                !com.bitchat.android.mesh.awaitOriginalStoreForward(preparation)) {
                                allStoresReturned = false
                                break
                            }
                            storeAwaitReturnedRoots += storeRoots[index].identity
                        }
                        if (wifiFrame?.stillValid() != false && allStoresReturned && wholeUnionStillValid() &&
                            validateOriginalProcessors(scope, installed.binding, installed.exactOriginalMesh, ready)) {
                            storeStatus = PanicStoreForwardBatchStatus.AwaitReturned
                        }
                    }
                }
            }
        }
    }
    currentCoroutineContext().ensureActive()
    if (ready != null && gaps.isEmpty() &&
        !validateOriginalProcessors(scope, installed.binding, installed.exactOriginalMesh, ready)) {
        gaps += com.bitchat.android.service.HolderProcessorGap.WitnessChanged
        status = PanicProcessorBatchStatus.Ineligible
    }
    if (readyUnion != null && gaps.isEmpty() && storeGaps.isEmpty() &&
        !com.bitchat.android.service.validateHolderOriginalProcessorStoreForwardUnion(installed.binding, installed.exactOriginalMesh, readyUnion)) {
        storeGaps += com.bitchat.android.service.HolderStoreForwardGap.WitnessChanged
        storeStatus = PanicStoreForwardBatchStatus.Ineligible
        status = PanicProcessorBatchStatus.Ineligible
    }
    storeEvidence = PanicOriginalStoreForwardEvidence(storeStatus,
        readyUnion?.storeForwardRows ?: originalUnion?.storeForwardRows ?: emptyList(), storeRequests.toList(),
        storeGaps.toList(), storeCallerGaps.toList(), storeAwaitReturnedRoots.toList())
    return withEvidence(report, PanicOriginalProcessorEvidence(status, ready?.rows ?: original.rows,
        requests.toList(), gaps.toList(), callerGaps.toList(), awaitReturnedRoots.toList()))
}

/** Observes actual direct body endpoints; every unavailable aggregate proof stays unconfirmed. */
internal suspend fun collectPanicRetirementWithEndedDirectFrontiers(
    originalFacade: UnifiedMeshService?,
    originalGossip: PanicOriginalGossipInput
): PanicRetirementReport {
    val report = collectPanicRetirement(originalFacade, originalGossip)
    return collectEndedDirectPhase(report, originalGossip)
}

private suspend fun collectEndedDirectPhase(report: PanicRetirementReport,
    originalGossip: PanicOriginalGossipInput, preflight: (() -> Boolean)? = null): PanicRetirementReport {
    if (preflight?.invoke() == false) return report
    val gossip = report.gossipJobsAndWriters as? PanicOwnerRetirement.Unconfirmed ?: return report
    val known = gossip.evidence as? PanicUnconfirmedEvidence.KnownOriginalGossip ?: return report
    if (originalGossip !is PanicOriginalGossipInput.Installed) return report
    val observed = validateKnownDirectEnds(captureKnownDirectEnds(known))
    val prepared = withDirectEnds(report, observed)
    if (observed is KnownDirectBodiesEnd.Ineligible) {
        currentCoroutineContext().ensureActive()
        return prepared
    }
    val joined = collectRecordedGossipPhase(prepared, originalGossip, preflight)
    currentCoroutineContext().ensureActive()
    return withDirectEnds(joined, validateKnownDirectEnds(observed))
}

private fun captureKnownDirectEnds(known: PanicUnconfirmedEvidence.KnownOriginalGossip): KnownDirectBodiesEnd {
    val direct = known.directIngressClosure as? KnownDirectIngressClosure.Attempted
        ?: return KnownDirectBodiesEnd.Ineligible(listOf(
            KnownDirectEndRejection.Batch(KnownDirectEndBatchGap.MissingOrFailedClose)))
    if (direct.managers.size != known.attempts.size || direct.managers.zip(known.attempts).any {
        (actual, original) -> actual.originalClose !== original
    }) return KnownDirectBodiesEnd.Ineligible(listOf(
        KnownDirectEndRejection.Batch(KnownDirectEndBatchGap.ClosureMismatch)))
    val ended = mutableListOf<KnownManagerDirectEnd>()
    val reasons = mutableListOf<KnownDirectEndRejection>()
    direct.managers.forEach { original ->
        val root = original.originalClose as? PanicGossipRootCloseAttempt.Closed
        val closed = original.outcome as? KnownManagerDirectCloseOutcome.Closed
        if (root == null || closed == null) {
            reasons += KnownDirectEndRejection.Batch(KnownDirectEndBatchGap.MissingOrFailedClose)
        } else {
            val result = try {
                root.manager.captureEndedDirectFrontier(root.binding, root.receipt, closed.receipt)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { GossipDirectEndedCapture.Unconfirmed(GossipDirectFrontierGap.OperationFailed) }
            when (result) {
                is GossipDirectEndedCapture.Observed -> ended += KnownManagerDirectEnd(original, result.frontier)
                is GossipDirectEndedCapture.EmptyValidated -> ended += KnownManagerDirectEnd(original, result.frontier)
                is GossipDirectEndedCapture.Unconfirmed -> reasons += KnownDirectEndRejection.Manager(original, result.gap)
            }
        }
    }
    return if (reasons.isEmpty()) KnownDirectBodiesEnd.Observed(ended.toList())
        else KnownDirectBodiesEnd.Ineligible(reasons.toList())
}

private fun validateKnownDirectEnds(observed: KnownDirectBodiesEnd): KnownDirectBodiesEnd {
    if (observed !is KnownDirectBodiesEnd.Observed) return observed
    val reasons = mutableListOf<KnownDirectEndRejection>()
    observed.managers.forEach { original ->
        val root = original.original.originalClose as PanicGossipRootCloseAttempt.Closed
        val closed = original.original.outcome as KnownManagerDirectCloseOutcome.Closed
        val valid = try {
            root.manager.validateEndedDirectFrontier(root.binding, root.receipt, closed.receipt, original.frontier)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            reasons += KnownDirectEndRejection.Manager(original.original, GossipDirectFrontierGap.OperationFailed)
            false
        }
        if (!valid && reasons.none { it is KnownDirectEndRejection.Manager && it.original === original.original }) {
            reasons += KnownDirectEndRejection.Manager(original.original, GossipDirectFrontierGap.FrontierChanged)
        }
    }
    return if (reasons.isEmpty()) observed else KnownDirectBodiesEnd.Ineligible(reasons.toList())
}

private fun withDirectEnds(report: PanicRetirementReport, observed: KnownDirectBodiesEnd): PanicRetirementReport {
    val gossip = report.gossipJobsAndWriters as PanicOwnerRetirement.Unconfirmed
    val known = gossip.evidence as PanicUnconfirmedEvidence.KnownOriginalGossip
    return report.copy(gossipJobsAndWriters = PanicOwnerRetirement.Unconfirmed(gossip.gap,
        PanicUnconfirmedEvidence.KnownOriginalGossip(known.snapshot, known.attempts, known.directIngressClosure,
            known.returnedJobsCancellation, known.recordedFrontierJoin, known.holderFrontier, observed)))
}

private fun sameCoveredManagerIdentities(
    known: PanicUnconfirmedEvidence.KnownOriginalGossip, frontier: HolderAdmittedManagerFrontier<GossipSyncManager>
): Boolean {
    val managers = frontier.managers
    if (known.attempts.size != managers.size) return false
    return managers.all { manager -> known.attempts.count { it.manager === manager } == 1 }
}

private suspend fun joinKnownRecordedFrontiers(
    known: PanicUnconfirmedEvidence.KnownOriginalGossip,
    installed: PanicOriginalGossipInput.Installed,
    holderFrontier: HolderAdmittedManagerFrontier<GossipSyncManager>, preflight: (() -> Boolean)? = null
): KnownRecordedFrontiersJoin {
    fun rejected(gap: KnownRecordedBatchGap) = KnownRecordedFrontiersJoin.Ineligible(
        listOf(KnownRecordedJoinRejection.Batch(gap))
    )
    if (known.attempts.any { it !is PanicGossipRootCloseAttempt.Closed }) {
        return rejected(KnownRecordedBatchGap.MissingOrFailedClose)
    }
    val cancellation = known.returnedJobsCancellation as? KnownReturnedJobsCancellation.Attempted
        ?: return rejected(KnownRecordedBatchGap.CancellationNotAttempted)
    if (cancellation.managers.size != known.attempts.size || cancellation.managers.zip(known.attempts).any {
        (observed, original) -> observed.originalClose !== original
    }) return rejected(KnownRecordedBatchGap.MissingOrFailedCapture)
    if (cancellation.requests.any { it.outcome != JobCancellationRequestOutcome.RequestAttempted }) {
        return rejected(KnownRecordedBatchGap.UnsafeCancellation)
    }
    val prepared = mutableListOf<PreparedKnownManager>()
    val reasons = mutableListOf<KnownRecordedJoinRejection>()
    // No join occurs until every original manager's old cancellation snapshot passes.
    cancellation.managers.forEach { original ->
        val captured = original.outcome as? KnownManagerJobCaptureOutcome.Captured
        val observed = captured?.result as? GossipReturnedJobsCapture.Observed
        if (observed == null || observed.snapshot.binding !== original.originalClose.binding ||
            observed.snapshot.receipt !== original.originalClose.receipt) {
            reasons += KnownRecordedJoinRejection.Batch(KnownRecordedBatchGap.MissingOrFailedCapture)
        } else {
            val result = try {
                original.originalClose.manager.prepareRecordedFrontierJoin(
                    original.originalClose.binding, original.originalClose.receipt, observed.snapshot)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { GossipRecordedFrontierPreparation.Unconfirmed(GossipRecordedJoinGap.OperationFailed) }
            when (result) {
                is GossipRecordedFrontierPreparation.Ready -> prepared += PreparedKnownManager(original, result.frontier)
                is GossipRecordedFrontierPreparation.Unconfirmed -> reasons += KnownRecordedJoinRejection.Manager(original, result.gap)
            }
        }
    }
    if (reasons.isNotEmpty()) return KnownRecordedFrontiersJoin.Ineligible(reasons.toList())
    if (preflight?.invoke() == false || !validateHolderAdmittedManagerFrontier(installed.binding, installed.exactOriginalMesh, holderFrontier)) {
        return rejected(KnownRecordedBatchGap.HolderFrontierChanged)
    }
    if (prepared.isEmpty()) return KnownRecordedFrontiersJoin.NoKnownRecordedJobs
    val outcomes = prepared.map { original ->
        if (preflight?.invoke() == false) return rejected(KnownRecordedBatchGap.HolderFrontierChanged)
        val result = try {
            original.original.originalClose.manager.joinPreparedRecordedFrontier(original.frontier)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { GossipRecordedFrontierJoinOutcome.Unconfirmed(GossipRecordedJoinGap.OperationFailed) }
        if (preflight?.invoke() == false) return rejected(KnownRecordedBatchGap.HolderFrontierChanged)
        KnownRecordedManagerJoin(original.original, result)
    }
    return KnownRecordedFrontiersJoin.Attempted(outcomes)
}

private fun closeKnownOriginalGossipRoots(
    snapshot: HolderGossipSubsetSnapshot<GossipSyncManager>, failures: MutableList<Throwable>? = null
): PanicUnconfirmedEvidence.KnownOriginalGossip {
    val known = mutableListOf<GossipSyncManager>()
    val seen = java.util.IdentityHashMap<GossipSyncManager, Unit>()
    fun retain(manager: GossipSyncManager) {
        if (!seen.containsKey(manager)) { seen[manager] = Unit; known += manager }
    }
    snapshot.children.forEach { child ->
        val outcome = child.outcome
        if (outcome is HolderGossipChildOutcome.Returned) retain(outcome.original.manager)
    }
    snapshot.holder.registrations.forEach { retain(it.manager) }
    val attempts = known.map { manager ->
        var binding: GossipManagerJobBinding? = null
        try {
            val original = manager.captureJobPanicBinding().also { binding = it }
            when (val closed = manager.closeJobRootsForPanic(original)) {
                is GossipManagerRootCloseOutcome.Closed -> PanicGossipRootCloseAttempt.Closed(manager, original, closed.receipt)
                GossipManagerRootCloseOutcome.ForeignBinding -> PanicGossipRootCloseAttempt.Unconfirmed(
                    manager, original, PanicGossipRootCloseGap.ForeignBinding
                )
            }
        } catch (cancelled: CancellationException) {
            if (failures == null) throw cancelled
            failures += cancelled
            PanicGossipRootCloseAttempt.Unconfirmed(manager, binding, PanicGossipRootCloseGap.OperationFailed)
        } catch (_: Exception) {
            PanicGossipRootCloseAttempt.Unconfirmed(manager, binding, PanicGossipRootCloseGap.OperationFailed)
        }
    }
    return PanicUnconfirmedEvidence.KnownOriginalGossip(snapshot, attempts, KnownDirectIngressClosure.NotAttempted)
}

/** Restricts every already-known direct ingress before cancellation or the original facade await. */
private fun closeKnownOriginalDirectIngress(
    attempts: List<PanicGossipRootCloseAttempt>, failures: MutableList<Throwable>? = null
): KnownDirectIngressClosure.Attempted = KnownDirectIngressClosure.Attempted(attempts.map { original ->
    val outcome = if (original !is PanicGossipRootCloseAttempt.Closed) KnownManagerDirectCloseOutcome.RootNotClosed
    else try { when (val closed = original.manager.closeDirectIngressForPanic(original.binding, original.receipt)) {
        is GossipDirectCloseOutcome.Closed -> KnownManagerDirectCloseOutcome.Closed(closed.receipt, closed.snapshotAtClose)
        GossipDirectCloseOutcome.ForeignBinding -> KnownManagerDirectCloseOutcome.ForeignBinding
        GossipDirectCloseOutcome.ForeignRootReceipt -> KnownManagerDirectCloseOutcome.ForeignRootReceipt
    }
    } catch (failure: Throwable) {
        if (failures == null) throw failure
        failures += failure
        KnownManagerDirectCloseOutcome.OperationFailed
    }
    KnownManagerDirectClosure(original, outcome)
})

private class GossipCallerFrame(val invoking: Job?, val chain: java.util.IdentityHashMap<Job, Unit>,
    val gap: JobCancellationRequestOutcome?, val cancellation: CancellationException?)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private fun captureGossipCallerFrame(originalInvoker: Job?): GossipCallerFrame {
    var pendingCancellation: CancellationException? = null

    val callerChain = java.util.IdentityHashMap<Job, Unit>()
    var relationshipGap: JobCancellationRequestOutcome? = null
    if (originalInvoker == null) relationshipGap = JobCancellationRequestOutcome.SkippedMissingCaller
    else try {
        if (!originalInvoker.isActive) relationshipGap = JobCancellationRequestOutcome.SkippedCallerRelationshipUnknown
        else {
            var current: Job? = originalInvoker
            while (current != null) {
                if (callerChain.containsKey(current)) {
                    relationshipGap = JobCancellationRequestOutcome.SkippedCallerRelationshipUnknown
                    break
                }
                callerChain[current] = Unit
                current = current.parent
            }
        }
    } catch (cancelled: CancellationException) {
        pendingCancellation = cancelled
        relationshipGap = JobCancellationRequestOutcome.SkippedCallerRelationshipUnknown
    } catch (_: Exception) {
        relationshipGap = JobCancellationRequestOutcome.SkippedCallerRelationshipUnknown
    }

    return GossipCallerFrame(originalInvoker, callerChain, relationshipGap, pendingCancellation)
}

private class CancellationOrigin(
    val managerSnapshot: KnownManagerReturnedJobs,
    val tickets: MutableList<GossipWorkTicket> = mutableListOf()
)
private class CancellationCandidate(val job: Job) {
    val origins = mutableListOf<CancellationOrigin>()
}

/** A finite request pass only. Completion handlers may run synchronously; no join or wait follows. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private fun cancelKnownOriginalReturnedJobs(
    closed: PanicUnconfirmedEvidence.KnownOriginalGossip,
    originalInvoker: Job?, savedCaller: GossipCallerFrame? = null
): KnownReturnedJobsCancellation.Attempted {
    var pendingCancellation: CancellationException? = null
    val managers = closed.attempts.filterIsInstance<PanicGossipRootCloseAttempt.Closed>().map { original ->
        val outcome = try {
            KnownManagerJobCaptureOutcome.Captured(
                original.manager.captureReturnedJobsForCancellation(original.binding, original.receipt)
            )
        } catch (cancelled: CancellationException) {
            if (pendingCancellation == null) pendingCancellation = cancelled
            KnownManagerJobCaptureOutcome.OperationFailed(KnownJobCaptureFailureKind.CancellationThrown)
        } catch (_: Exception) {
            KnownManagerJobCaptureOutcome.OperationFailed(KnownJobCaptureFailureKind.OrdinaryFailure)
        }
        KnownManagerReturnedJobs(original, outcome)
    }
    val candidates = mutableListOf<CancellationCandidate>()
    val byJob = java.util.IdentityHashMap<Job, CancellationCandidate>()
    managers.forEach { manager ->
        val captured = manager.outcome
        if (captured is KnownManagerJobCaptureOutcome.Captured) {
            val result = captured.result
            if (result is GossipReturnedJobsCapture.Observed) result.snapshot.records.forEach { row ->
                val returned = row.outcome
                if (returned is GossipWorkOutcome.Returned) {
                    val candidate = byJob[returned.job] ?: CancellationCandidate(returned.job).also {
                        byJob[returned.job] = it
                        candidates += it
                    }
                    val origin = candidate.origins.firstOrNull { it.managerSnapshot === manager }
                        ?: CancellationOrigin(manager).also { candidate.origins += it }
                    origin.tickets += row.ticket
                }
            }
        }
    }

    val capturedCaller = savedCaller ?: captureGossipCallerFrame(originalInvoker)
    val callerChain = capturedCaller.chain
    val relationshipGap = capturedCaller.gap
    if (pendingCancellation == null) pendingCancellation = capturedCaller.cancellation

    val requests = candidates.map { candidate ->
        val outcome = when {
            callerChain.containsKey(candidate.job) -> JobCancellationRequestOutcome.SkippedCallerRelated
            relationshipGap != null -> checkNotNull(relationshipGap)
            else -> try {
                candidate.job.cancel()
                JobCancellationRequestOutcome.RequestAttempted
            } catch (cancelled: CancellationException) {
                if (pendingCancellation == null) pendingCancellation = cancelled
                JobCancellationRequestOutcome.CancellationThrown
            } catch (_: Exception) {
                JobCancellationRequestOutcome.OperationFailed
            }
        }
        KnownJobCancellationRequest(
            candidate.job,
            candidate.origins.map { KnownJobOrigin(it.managerSnapshot, it.tickets.toList()) },
            outcome
        )
    }
    pendingCancellation?.let { throw it }
    return KnownReturnedJobsCancellation.Attempted(managers, requests)
}

private fun unconfirmedPanicReport(
    facade: PanicOwnerRetirement,
    gossipEvidence: PanicUnconfirmedEvidence
): PanicRetirementReport = PanicRetirementReport(
    facade = facade,
    wifiController = PanicOwnerRetirement.Unconfirmed(PanicRetirementGap.ProducerNotImplemented),
    holderConstructionAndCallbacks = PanicOwnerRetirement.Unconfirmed(PanicRetirementGap.ProducerNotImplemented),
    gossipJobsAndWriters = PanicOwnerRetirement.Unconfirmed(PanicRetirementGap.ProducerNotImplemented, gossipEvidence),
    transportCoreRadioAndHandlers = PanicOwnerRetirement.Unconfirmed(PanicRetirementGap.ProducerNotImplemented),
    startupAndRemainingSharedKeyUsers = PanicOwnerRetirement.Unconfirmed(PanicRetirementGap.ProducerNotImplemented)
)

/** Restart remains provisional until the durable marker and every admission gate commit. */
internal object PanicResetCoordinator {
    /** Synchronous restrictive fences; this partial closure never confirms the aggregate holder proof. */
    fun fenceCapturedPanicOwners(
        originalFacade: UnifiedMeshService?,
        holderBinding: com.bitchat.android.service.HolderPanicBinding,
        exactOriginalMesh: Any
    ): com.bitchat.android.service.HolderConstructionCloseOutcome {
        originalFacade?.fenceForPanic()
        val outcome = com.bitchat.android.service.closeHolderPanicBinding(holderBinding, exactOriginalMesh)
        if (outcome === com.bitchat.android.service.HolderConstructionCloseOutcome.ClosedForConstruction) {
            com.bitchat.android.service.fenceHolderOriginalWifiHosts(holderBinding, exactOriginalMesh)
        }
        return outcome
    }

    fun launchPanic(scope: CoroutineScope, action: suspend () -> Unit): Job =
        scope.launch(start = CoroutineStart.UNDISPATCHED) { action() }

    suspend fun admitDestructivePanic(
        establishIntent: suspend () -> Boolean,
        retireOwner: suspend () -> Boolean,
        eraseOwnedData: suspend (meshOwnerRetired: Boolean) -> Boolean
    ): Boolean {
        val intentConfirmed = establishIntent()
        currentCoroutineContext().ensureActive()
        if (!intentConfirmed) return false
        val retired = retireOwner()
        currentCoroutineContext().ensureActive()
        if (!retired) return false
        return eraseOwnedData(true)
    }

    suspend fun admitDestructivePanicOwners(
        establishIntent: suspend () -> Boolean,
        retireOwners: suspend () -> PanicRetirementReport,
        eraseOwnedData: suspend (PanicRetirementReport) -> Boolean
    ): Boolean {
        var report: PanicRetirementReport? = null
        return admitDestructivePanic(
            establishIntent = establishIntent,
            retireOwner = { retireOwners().also { report = it }.confirmed },
            eraseOwnedData = { eraseOwnedData(checkNotNull(report)) }
        )
    }

    suspend fun resume(
        prepareFreshDrafts: suspend () -> Boolean,
        seenReady: () -> Boolean,
        startTransport: suspend () -> Unit,
        announce: suspend () -> Unit,
        complete: suspend () -> Boolean,
        retainSeenFence: () -> Unit
    ): Boolean {
        var completed = false
        try {
            if (!prepareFreshDrafts() || !seenReady()) return false
            currentCoroutineContext().ensureActive()
            startTransport()
            announce()
            currentCoroutineContext().ensureActive()
            return withContext(NonCancellable) {
                // Includes IO return and synchronous gate reopening. Cancellation after
                // this boundary observes a completed transaction, never half a commit.
                complete().also { completed = it }
            }
        } finally {
            if (!completed) retainSeenFence()
        }
    }

    suspend fun complete(
        drafts: WeshDraftStore,
        prepared: WeshDraftStore.PanicPreparation,
        seenPanic: SeenReceiptAdmission.Token,
        reopenAdmission: () -> Unit
    ): Boolean = drafts.completePreparedPanic(prepared) { finishDrafts ->
        synchronized(seenPanic.owner.monitor) {
            if (!seenPanic.owner.canResumeAfterVerifiedPanic(seenPanic)) return@synchronized false
            finishDrafts()
            check(seenPanic.owner.resumeAfterVerifiedPanic(seenPanic))
            reopenAdmission()
            true
        }
    }
}
