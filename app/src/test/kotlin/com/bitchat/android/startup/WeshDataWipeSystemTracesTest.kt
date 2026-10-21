package com.bitchat.android.startup

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.kotlin.argumentCaptor
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

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

        // The test runtime has no Android Keystore: the first step fails, the later ones must still run.
        runCatching { WeshDataWipe.wipe(context) }

        assertEquals(0, notifications.activeNotifications.size)
        assertEquals(0, ShortcutManagerCompat.getShortcuts(context,
            ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or ShortcutManagerCompat.FLAG_MATCH_CACHED).size)
    }

    @Test
    fun `a conversation shortcut pinned to the home screen is renamed to the app then disabled, the erase shortcut is not`() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        fun info(id: String, label: String) = ShortcutInfo.Builder(app, id).setShortLabel(label).setIntent(Intent(Intent.ACTION_VIEW)).build()
        val manager = Mockito.mock(ShortcutManager::class.java)
        Mockito.`when`(manager.manifestShortcuts).thenReturn(listOf(info("hold_to_erase", "Tout effacer")))
        Mockito.`when`(manager.pinnedShortcuts).thenReturn(listOf(info("hold_to_erase", "Tout effacer"), info("dm_synthetic", "Synthetic contact")))
        Mockito.`when`(manager.updateShortcuts(Mockito.anyList())).thenReturn(true)
        val context = object : ContextWrapper(app) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.SHORTCUT_SERVICE) manager else super.getSystemService(name)
        }

        val name = app.packageManager.getApplicationLabel(app.applicationInfo).toString()

        WeshDataWipe.neutralizePinnedShortcuts(context)

        val order = Mockito.inOrder(manager)
        val updated = argumentCaptor<List<ShortcutInfo>>()
        order.verify(manager).updateShortcuts(updated.capture())
        order.verify(manager).disableShortcuts(listOf("dm_synthetic"), name)
        assertEquals(listOf("dm_synthetic"), updated.firstValue.map { it.id })
        assertEquals(name, updated.firstValue.single().shortLabel.toString())
        val people = ReflectionHelpers.callInstanceMethod<Array<android.app.Person>?>(updated.firstValue.single(), "getPersons")
        assertEquals("the stored contact must be overwritten, not left unset", listOf(name), people?.map { it.name.toString() })
    }

    @Test
    fun `a rate-limited shortcut update fails the wipe so Android clears the data`() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val manager = Mockito.mock(ShortcutManager::class.java)
        Mockito.`when`(manager.pinnedShortcuts).thenReturn(listOf(
            ShortcutInfo.Builder(app, "dm_synthetic").setShortLabel("Synthetic contact").setIntent(Intent(Intent.ACTION_VIEW)).build()
        ))
        val context = object : ContextWrapper(app) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.SHORTCUT_SERVICE) manager else super.getSystemService(name)
        }

        assertTrue(runCatching { WeshDataWipe.neutralizePinnedShortcuts(context) }.isFailure)
    }
}
