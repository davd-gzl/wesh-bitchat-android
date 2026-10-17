package com.bitchat.android.ui

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.bitchat.android.model.BitchatMessage

/**
 * Ours by peer ID; a nickname match counts only for a non-blank nickname, since a remote sender
 * with no nickname has a blank sender.
 */
internal fun BitchatMessage.isFromSelf(
    currentUserNickname: String,
    myPeerId: String?,
): Boolean =
    (myPeerId != null && senderPeerID == myPeerId) || sentByNickname(currentUserNickname) ||
        (currentUserNickname.isNotBlank() && sender.startsWith("$currentUserNickname#"))

/** Legacy nickname-only check, for screens without our peer ID: never true for a blank nickname. */
internal fun BitchatMessage.sentByNickname(currentUserNickname: String?): Boolean =
    !currentUserNickname.isNullOrBlank() && sender == currentUserNickname

internal fun normalizeMessageUrl(rawUrl: String): String =
    if (
        rawUrl.startsWith("http://", ignoreCase = true) ||
        rawUrl.startsWith("https://", ignoreCase = true)
    ) {
        rawUrl
    } else {
        "https://$rawUrl"
    }

internal fun openMessageUrl(context: Context, rawUrl: String): Boolean =
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, normalizeMessageUrl(rawUrl).toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }.isSuccess
