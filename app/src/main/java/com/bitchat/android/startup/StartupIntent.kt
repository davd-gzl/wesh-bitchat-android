package com.bitchat.android.startup

import android.content.Intent
import com.bitchat.android.ui.NotificationManager

/** Minimal pending route, safe to retain in Activity state. Quit is handled immediately. */
internal fun boundedWeshStartupIntent(source: Intent): Intent = Intent().apply {
    // The "Tout effacer" launcher shortcut only opens the hold-to-erase screen; it carries no data.
    if (source.action == com.bitchat.android.ui.pave.PaveShortcuts.ACTION_HOLD_TO_ERASE) action = source.action
    source.data?.takeIf {
        it.scheme == "bitchat" && it.host == "verify" && it.toString().toByteArray(Charsets.UTF_8).size <= 4096
    }?.let { data = it }
    if (runCatching { source.getBooleanExtra(NotificationManager.EXTRA_OPEN_PRIVATE_CHAT, false) }.getOrDefault(false)) {
        putExtra(NotificationManager.EXTRA_OPEN_PRIVATE_CHAT, true)
        runCatching { source.getStringExtra(NotificationManager.EXTRA_PEER_ID) }.getOrNull()
            ?.takeIf { it.toByteArray(Charsets.UTF_8).size <= 128 }
            ?.let { putExtra(NotificationManager.EXTRA_PEER_ID, it) }
        runCatching { source.getStringExtra(NotificationManager.EXTRA_SENDER_NICKNAME) }.getOrNull()
            ?.takeIf { it.toByteArray(Charsets.UTF_8).size <= 255 }
            ?.let { putExtra(NotificationManager.EXTRA_SENDER_NICKNAME, it) }
    }
}
