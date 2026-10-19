package com.bitchat.android.ui

import com.bitchat.android.mesh.UnifiedMeshService
import com.bitchat.android.services.SeenReceiptAdmission
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal enum class PanicRetirementGap {
    MissingCapturedFacade, FacadeUnconfirmed, ProducerNotImplemented
}

internal sealed interface PanicOwnerRetirement {
    data object Confirmed : PanicOwnerRetirement
    class Unconfirmed(val gap: PanicRetirementGap) : PanicOwnerRetirement
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

/** Only the captured facade has a checked producer; the remaining ownership proofs are unavailable. */
internal suspend fun collectPanicRetirement(originalFacade: UnifiedMeshService?): PanicRetirementReport {
    val facade = when {
        originalFacade == null -> PanicOwnerRetirement.Unconfirmed(PanicRetirementGap.MissingCapturedFacade)
        originalFacade.retireForPanic() -> PanicOwnerRetirement.Confirmed
        else -> PanicOwnerRetirement.Unconfirmed(PanicRetirementGap.FacadeUnconfirmed)
    }
    return PanicRetirementReport(
        facade = facade,
        wifiController = PanicOwnerRetirement.Unconfirmed(PanicRetirementGap.ProducerNotImplemented),
        holderConstructionAndCallbacks = PanicOwnerRetirement.Unconfirmed(PanicRetirementGap.ProducerNotImplemented),
        gossipJobsAndWriters = PanicOwnerRetirement.Unconfirmed(PanicRetirementGap.ProducerNotImplemented),
        transportCoreRadioAndHandlers = PanicOwnerRetirement.Unconfirmed(PanicRetirementGap.ProducerNotImplemented),
        startupAndRemainingSharedKeyUsers = PanicOwnerRetirement.Unconfirmed(PanicRetirementGap.ProducerNotImplemented)
    )
}

/** Restart remains provisional until the durable marker and every admission gate commit. */
internal object PanicResetCoordinator {
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
