package com.bitchat.android.startup

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class WeshDataWipeSystemTracesTest {
    @Test
    fun `the wipe removes notifications and conversation shortcuts kept by the system`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notifications = context.getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("synthetic", "Synthetic", NotificationManager.IMPORTANCE_DEFAULT))
        notifications.notify(1, Notification.Builder(context, "synthetic").setContentText("RDVQAMARKER1").build())
        ShortcutManagerCompat.pushDynamicShortcut(context, ShortcutInfoCompat.Builder(context, "synthetic-peer")
            .setShortLabel("Synthetic contact").setLongLived(true).setIntent(Intent(Intent.ACTION_VIEW)).build())

        // The test runtime has no Android Keystore, so the wipe fails after the system traces are cleared.
        runCatching { WeshDataWipe.wipe(context) }

        assertEquals(0, notifications.activeNotifications.size)
        assertEquals(0, ShortcutManagerCompat.getShortcuts(context,
            ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or ShortcutManagerCompat.FLAG_MATCH_CACHED).size)
    }
}
