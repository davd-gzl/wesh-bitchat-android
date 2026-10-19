package com.bitchat.android.ui

import com.bitchat.android.mesh.UnifiedMeshService
import com.bitchat.android.services.SeenReceiptAdmission
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

internal enum class KnownRecordedBatchGap { MissingOrFailedClose, MissingOrFailedCapture, CancellationNotAttempted, UnsafeCancellation }
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

internal sealed interface PanicUnconfirmedEvidence {
    data object NotCollected : PanicUnconfirmedEvidence
    class InvalidInstalledPair(val gap: PanicRetirementGap) : PanicUnconfirmedEvidence
    class InvalidOriginalGossip(val gap: HolderGossipCaptureGap) : PanicUnconfirmedEvidence
    class KnownOriginalGossip(
        val snapshot: HolderGossipSubsetSnapshot<GossipSyncManager>,
        val attempts: List<PanicGossipRootCloseAttempt>,
        val returnedJobsCancellation: KnownReturnedJobsCancellation = KnownReturnedJobsCancellation.NotAttempted,
        val recordedFrontierJoin: KnownRecordedFrontiersJoin = KnownRecordedFrontiersJoin.NotAttempted
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
                    val cancellation = cancelKnownOriginalReturnedJobs(closed, originalInvoker)
                    currentCoroutineContext().ensureActive()
                    PanicUnconfirmedEvidence.KnownOriginalGossip(closed.snapshot, closed.attempts, cancellation)
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
    val gossip = report.gossipJobsAndWriters as? PanicOwnerRetirement.Unconfirmed ?: return report
    val known = gossip.evidence as? PanicUnconfirmedEvidence.KnownOriginalGossip ?: return report
    val joined = joinKnownRecordedFrontiers(known)
    currentCoroutineContext().ensureActive()
    return report.copy(gossipJobsAndWriters = PanicOwnerRetirement.Unconfirmed(gossip.gap,
        PanicUnconfirmedEvidence.KnownOriginalGossip(known.snapshot, known.attempts,
            known.returnedJobsCancellation, joined)))
}

private suspend fun joinKnownRecordedFrontiers(
    known: PanicUnconfirmedEvidence.KnownOriginalGossip
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
    return PanicUnconfirmedEvidence.KnownOriginalGossip(snapshot, attempts)
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
