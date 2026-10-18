package com.bitchat.android.ui

import com.bitchat.android.services.SeenReceiptAdmission
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Restart remains provisional until the durable marker and every admission gate commit. */
internal object PanicResetCoordinator {
    fun launchPanic(scope: CoroutineScope, action: suspend () -> Unit): Job = scope.launch { action() }

    suspend fun admitDestructivePanic(
        establishIntent: suspend () -> Boolean,
        retireOwner: suspend () -> Boolean,
        eraseOwnedData: suspend (meshOwnerRetired: Boolean) -> Boolean
    ): Boolean {
        val retired = retireOwner()
        return eraseOwnedData(retired)
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
