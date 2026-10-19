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
internal sealed interface PanicUnconfirmedEvidence {
    data object NotCollected : PanicUnconfirmedEvidence
    class InvalidInstalledPair(val gap: PanicRetirementGap) : PanicUnconfirmedEvidence
    class InvalidOriginalGossip(val gap: HolderGossipCaptureGap) : PanicUnconfirmedEvidence
    class KnownOriginalGossip(
        val snapshot: HolderGossipSubsetSnapshot<GossipSyncManager>,
        val attempts: List<PanicGossipRootCloseAttempt>
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
                is HolderGossipSubsetCapture.Observed -> closeKnownOriginalGossipRoots(captured.snapshot)
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
