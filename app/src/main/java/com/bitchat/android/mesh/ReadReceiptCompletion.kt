package com.bitchat.android.mesh

import com.bitchat.android.services.SeenMessageStore
import com.bitchat.android.services.SeenReceiptAdmission

/** A completed retry window records acceptance before the transport's existing callback. */
internal fun readReceiptCompletion(
    messageID: String,
    expectedToken: SeenReceiptAdmission.Token?,
    seenStore: () -> SeenMessageStore,
    onAccepted: () -> Unit = {}
): (Boolean) -> Unit = { accepted ->
    if (accepted) {
        if (expectedToken != null) {
            try { seenStore().markReadReceiptSent(messageID, expectedToken) } catch (_: Exception) { }
        }
        onAccepted()
    }
}
