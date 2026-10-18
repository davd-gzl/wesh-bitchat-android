package com.bitchat.android.ui

import com.bitchat.android.services.SeenMessageStore
import com.bitchat.android.services.SeenReceiptAdmission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Checked deletion of only Seen bookkeeping; other encrypted-preference owners remain intact. */
internal suspend fun clearSeenForPanic(
    expectedPanic: SeenReceiptAdmission.Token,
    seenStore: () -> SeenMessageStore
): Boolean = withContext(NonCancellable + Dispatchers.IO) {
    // Construction and secure-store allocation happen outside the admission monitor.
    try {
        seenStore().clearForPanic(expectedPanic)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
}
