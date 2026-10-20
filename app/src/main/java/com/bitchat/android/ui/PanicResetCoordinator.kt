package com.bitchat.android.ui

import com.bitchat.android.mesh.UnifiedMeshService
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

internal sealed interface PanicUnconfirmedEvidence {
    class OriginalBluetoothProcessors(val observation: PanicOriginalProcessorEvidence) : PanicUnconfirmedEvidence
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
    val startupAndRemainingSharedKeyUsers: PanicOwnerRetirement
) {
    val confirmed: Boolean get() = facade === PanicOwnerRetirement.Confirmed &&
        wifiController === PanicOwnerRetirement.Confirmed &&
        holderConstructionAndCallbacks === PanicOwnerRetirement.Confirmed &&
        gossipJobsAndWriters === PanicOwnerRetirement.Confirmed &&
        transportCoreRadioAndHandlers === PanicOwnerRetirement.Confirmed &&
        startupAndRemainingSharedKeyUsers === PanicOwnerRetirement.Confirmed
}

/** Known-manager root closure restricts admission; every missing aggregate proof stays unconfirmed. */
internal suspend fun collectPanicRetirement(
    originalFacade: UnifiedMeshService?,
    originalGossip: PanicOriginalGossipInput
): PanicRetirementReport {
    val evidence = when (originalGossip) {
        PanicOriginalGossipInput.MissingCapturedHolder -> PanicUnconfirmedEvidence.NotCollected
        is PanicOriginalGossipInput.Installed -> {
            val pairGap = when {
                originalFacade == null -> PanicRetirementGap.InstalledFacadeMissing
                originalGossip.exactOriginalMesh !== originalFacade -> PanicRetirementGap.InstalledFacadeMismatch
                else -> null
            }
            if (pairGap != null) return unconfirmedPanicReport(
                PanicOwnerRetirement.Unconfirmed(pairGap), PanicUnconfirmedEvidence.InvalidInstalledPair(pairGap)
            )
            when (val captured = observeHolderOriginalGossipSubset(originalGossip.binding, originalGossip.exactOriginalMesh)) {
                is HolderGossipSubsetCapture.Unconfirmed -> return unconfirmedPanicReport(
                    PanicOwnerRetirement.Unconfirmed(PanicRetirementGap.OriginalHolderInputUnconfirmed),
                    PanicUnconfirmedEvidence.InvalidOriginalGossip(captured.gap)
                )
                is HolderGossipSubsetCapture.Observed -> {
                    val originalInvoker = currentCoroutineContext()[Job]
                    val closed = closeKnownOriginalGossipRoots(captured.snapshot)
                    val direct = closeKnownOriginalDirectIngress(closed.attempts)
                    val cancellation = cancelKnownOriginalReturnedJobs(closed, originalInvoker)
                    currentCoroutineContext().ensureActive()
                    PanicUnconfirmedEvidence.KnownOriginalGossip(closed.snapshot, closed.attempts, direct, cancellation)
                }
            }
        }
    }
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
    originalGossip: PanicOriginalGossipInput
): PanicRetirementReport {
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
        else -> joinKnownRecordedFrontiers(known, installed, frontier)
    }
    currentCoroutineContext().ensureActive()
    val current = if (frontier != null && !validateHolderAdmittedManagerFrontier(installed.binding, installed.exactOriginalMesh, frontier)) {
        HolderGossipCensusCapture.Invalid(HolderCensusGap.WitnessChanged)
    } else captured
    return report.copy(gossipJobsAndWriters = PanicOwnerRetirement.Unconfirmed(gossip.gap,
        PanicUnconfirmedEvidence.KnownOriginalGossip(known.snapshot, known.attempts,
            known.directIngressClosure, known.returnedJobsCancellation, joined, current, known.directBodiesEnd)))
}

/** Requests known original BLE roots before the unchanged old phase; no aggregate proof is promoted. */
internal suspend fun collectPanicRetirementWithOriginalBluetoothProcessors(
    originalFacade: UnifiedMeshService?, originalGossip: PanicOriginalGossipInput
): PanicRetirementReport {
    val installed = originalGossip as? PanicOriginalGossipInput.Installed
    fun withEvidence(report: PanicRetirementReport, evidence: PanicOriginalProcessorEvidence) = report.copy(
        transportCoreRadioAndHandlers = PanicOwnerRetirement.Unconfirmed(PanicRetirementGap.ProducerNotImplemented,
            PanicUnconfirmedEvidence.OriginalBluetoothProcessors(evidence)))
    fun empty(status: PanicProcessorBatchStatus) = PanicOriginalProcessorEvidence(status, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    if (installed == null || originalFacade == null || installed.exactOriginalMesh !== originalFacade) {
        val report = collectPanicRetirementWithEndedDirectFrontiers(originalFacade, originalGossip)
        return withEvidence(report, empty(if (installed == null) PanicProcessorBatchStatus.MissingInput else PanicProcessorBatchStatus.InvalidInput))
    }
    val first = com.bitchat.android.service.captureHolderOriginalBluetoothProcessors(installed.binding, installed.exactOriginalMesh)
    val original = (first as? com.bitchat.android.service.HolderProcessorCapture.Observed)?.batch
    if (original == null) {
        val report = collectPanicRetirementWithEndedDirectFrontiers(originalFacade, originalGossip)
        return withEvidence(report, empty(PanicProcessorBatchStatus.InvalidInput).copy(holderGaps = listOf(
            (first as com.bitchat.android.service.HolderProcessorCapture.Invalid).gap)))
    }
    // Snapshot active caller ancestry before any cancellation can detach its original links.
    val caller = com.bitchat.android.mesh.captureOriginalProcessorCaller()
    val requests = mutableListOf<PanicProcessorRequest>()
    val requested = mutableListOf<com.bitchat.android.mesh.OriginalProcessorBinding>()
    val awaitReturnedRoots = mutableListOf<com.bitchat.android.mesh.OriginalProcessorRootIdentity>()
    val callerGaps = mutableListOf<com.bitchat.android.mesh.OriginalProcessorGap>()
    val failures = mutableListOf<Throwable>()
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
    val report = collectPanicRetirementWithEndedDirectFrontiers(originalFacade, originalGossip)
    currentCoroutineContext().ensureActive()
    val second = com.bitchat.android.service.captureHolderOriginalBluetoothProcessors(installed.binding, installed.exactOriginalMesh)
    val ready = (second as? com.bitchat.android.service.HolderProcessorCapture.Observed)?.batch
    var status = PanicProcessorBatchStatus.Ineligible
    val gaps = (ready?.gaps ?: listOf((second as com.bitchat.android.service.HolderProcessorCapture.Invalid).gap)).toMutableList()
    if (report.facade === PanicOwnerRetirement.Confirmed && ready != null && gaps.isEmpty() && callerGaps.isEmpty()) {
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
            if (valid && com.bitchat.android.service.validateHolderOriginalBluetoothProcessors(installed.binding, installed.exactOriginalMesh, ready)) {
                var allReturned = true
                for ((index, preparation) in preparations.withIndex()) {
                    if (!com.bitchat.android.service.validateHolderOriginalBluetoothProcessors(installed.binding, installed.exactOriginalMesh, ready) ||
                        !com.bitchat.android.mesh.awaitOriginalProcessor(preparation)) {
                        allReturned = false
                        break
                    }
                    awaitReturnedRoots += roots[index].identity
                }
                if (allReturned && com.bitchat.android.service.validateHolderOriginalBluetoothProcessors(installed.binding, installed.exactOriginalMesh, ready)) {
                    status = PanicProcessorBatchStatus.AwaitReturned
                }
            }
        }
    }
    currentCoroutineContext().ensureActive()
    if (ready != null && gaps.isEmpty() &&
        !com.bitchat.android.service.validateHolderOriginalBluetoothProcessors(installed.binding, installed.exactOriginalMesh, ready)) {
        gaps += com.bitchat.android.service.HolderProcessorGap.WitnessChanged
        status = PanicProcessorBatchStatus.Ineligible
    }
    return withEvidence(report, PanicOriginalProcessorEvidence(status, ready?.rows ?: original.rows,
        requests.toList(), gaps.toList(), callerGaps.toList(), awaitReturnedRoots.toList()))
}

/** Observes actual direct body endpoints; every unavailable aggregate proof stays unconfirmed. */
internal suspend fun collectPanicRetirementWithEndedDirectFrontiers(
    originalFacade: UnifiedMeshService?,
    originalGossip: PanicOriginalGossipInput
): PanicRetirementReport {
    val report = collectPanicRetirement(originalFacade, originalGossip)
    val gossip = report.gossipJobsAndWriters as? PanicOwnerRetirement.Unconfirmed ?: return report
    val known = gossip.evidence as? PanicUnconfirmedEvidence.KnownOriginalGossip ?: return report
    if (originalGossip !is PanicOriginalGossipInput.Installed) return report
    val observed = validateKnownDirectEnds(captureKnownDirectEnds(known))
    val prepared = withDirectEnds(report, observed)
    if (observed is KnownDirectBodiesEnd.Ineligible) {
        currentCoroutineContext().ensureActive()
        return prepared
    }
    val joined = collectRecordedGossipPhase(prepared, originalGossip)
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
    holderFrontier: HolderAdmittedManagerFrontier<GossipSyncManager>
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
    if (!validateHolderAdmittedManagerFrontier(installed.binding, installed.exactOriginalMesh, holderFrontier)) {
        return rejected(KnownRecordedBatchGap.HolderFrontierChanged)
    }
    if (prepared.isEmpty()) return KnownRecordedFrontiersJoin.NoKnownRecordedJobs
    val outcomes = prepared.map { original ->
        val result = try {
            original.original.originalClose.manager.joinPreparedRecordedFrontier(original.frontier)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { GossipRecordedFrontierJoinOutcome.Unconfirmed(GossipRecordedJoinGap.OperationFailed) }
        KnownRecordedManagerJoin(original.original, result)
    }
    return KnownRecordedFrontiersJoin.Attempted(outcomes)
}

private fun closeKnownOriginalGossipRoots(
    snapshot: HolderGossipSubsetSnapshot<GossipSyncManager>
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
            throw cancelled
        } catch (_: Exception) {
            PanicGossipRootCloseAttempt.Unconfirmed(manager, binding, PanicGossipRootCloseGap.OperationFailed)
        }
    }
    return PanicUnconfirmedEvidence.KnownOriginalGossip(snapshot, attempts, KnownDirectIngressClosure.NotAttempted)
}

/** Restricts every already-known direct ingress before cancellation or the original facade await. */
private fun closeKnownOriginalDirectIngress(
    attempts: List<PanicGossipRootCloseAttempt>
): KnownDirectIngressClosure.Attempted = KnownDirectIngressClosure.Attempted(attempts.map { original ->
    val outcome = if (original !is PanicGossipRootCloseAttempt.Closed) KnownManagerDirectCloseOutcome.RootNotClosed
    else when (val closed = original.manager.closeDirectIngressForPanic(original.binding, original.receipt)) {
        is GossipDirectCloseOutcome.Closed -> KnownManagerDirectCloseOutcome.Closed(closed.receipt, closed.snapshotAtClose)
        GossipDirectCloseOutcome.ForeignBinding -> KnownManagerDirectCloseOutcome.ForeignBinding
        GossipDirectCloseOutcome.ForeignRootReceipt -> KnownManagerDirectCloseOutcome.ForeignRootReceipt
    }
    KnownManagerDirectClosure(original, outcome)
})

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
    originalInvoker: Job?
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

    // Save the caller chain once, before any cancellation handler can cancel or detach it.
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
        if (pendingCancellation == null) pendingCancellation = cancelled
        relationshipGap = JobCancellationRequestOutcome.SkippedCallerRelationshipUnknown
    } catch (_: Exception) {
        relationshipGap = JobCancellationRequestOutcome.SkippedCallerRelationshipUnknown
    }

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
        return com.bitchat.android.service.closeHolderPanicBinding(holderBinding, exactOriginalMesh)
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
