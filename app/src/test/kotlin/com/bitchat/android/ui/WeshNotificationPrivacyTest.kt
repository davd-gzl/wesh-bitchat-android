package com.bitchat.android.ui

import android.app.Application
import android.app.Notification
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.os.Bundle
import android.os.Parcelable
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.pm.ShortcutManagerCompat
import com.bitchat.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.app.NotificationManager as AndroidNotificationManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE, application = Application::class)
class WeshNotificationPrivacyTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val context = LabelContext(app)
    private val nickname = "Synthetic Sender"
    private val secret = "Synthetic private text"

    @Before
    fun setUp() {
        app.getSharedPreferences(WeshPreferences.FILE, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `private message notification hides sender and content by default`() {
        val manager = backgroundManager()
        manager.showPrivateMessageNotification("aaaabbbbccccdddd", nickname, secret)
        manager.showPrivateMessageNotification("1111222233334444", nickname, secret)

        val posted = postedNotifications()
        assertTrue(posted.isNotEmpty())
        posted.forEach { notification ->
            val text = flatten(notification.extras)
            assertFalse("sender leaked: $text", text.contains(nickname))
            assertFalse("content leaked: $text", text.contains(secret))
            assertNull(notification.shortcutId)
            assertNull(notification.locusId)
            assertTrue("notification may be bridged to a watch", notification.flags and Notification.FLAG_LOCAL_ONLY != 0)
            assertNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification))
        }
        assertEquals(label(R.string.wesh_notification_new_message), posted.first().extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertTrue(ShortcutManagerCompat.getDynamicShortcuts(context).isEmpty())
    }

    @Test
    fun `sender name appears only after opt in and content stays hidden`() {
        WeshPreferences(app).notificationShowsSender = true
        backgroundManager().showPrivateMessageNotification("aaaabbbbccccdddd", nickname, secret)

        val notification = postedNotifications().single()
        assertEquals(nickname, notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertFalse(flatten(notification.extras).contains(secret))
        assertNull(notification.shortcutId)
        assertTrue(ShortcutManagerCompat.getDynamicShortcuts(context).isEmpty())
    }

    @Test
    fun `public mention notification hides sender and content`() {
        backgroundManager().showMeshMentionNotification(nickname, secret, "aaaabbbbccccdddd")

        val notification = postedNotifications().single()
        val text = flatten(notification.extras)
        assertFalse(text.contains(nickname))
        assertFalse(text.contains(secret))
        assertTrue(notification.flags and Notification.FLAG_LOCAL_ONLY != 0)
    }

    @Test
    fun `verification notification hides the contact nickname unless opted in`() {
        backgroundManager().showVerificationSuccessNotification(nickname, "aaaabbbbccccdddd")

        val notification = postedNotifications().single()
        assertFalse(flatten(notification.extras).contains(nickname))
        assertEquals(label(R.string.wesh_notification_verified_body), notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertTrue(notification.flags and Notification.FLAG_LOCAL_ONLY != 0)
        val intent = shadowOf(notification.contentIntent).savedIntent
        assertFalse(intent.hasExtra(NotificationManager.EXTRA_SENDER_NICKNAME))
        assertEquals("aaaabbbbccccdddd", intent.getStringExtra(NotificationManager.EXTRA_PEER_ID))
    }

    @Test
    fun `verification notification names the contact after opt in`() {
        WeshPreferences(app).notificationShowsSender = true
        backgroundManager().showVerificationSuccessNotification(nickname, "aaaabbbbccccdddd")
        assertEquals(label(R.string.verify_success_body), postedNotifications().single().extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    private fun backgroundManager() = NotificationManager(context, NotificationManagerCompat.from(context), isConversationMuted = { false })
        .apply { setAppBackgroundState(true) }

    private fun postedNotifications(): List<Notification> =
        shadowOf(app.getSystemService(Context.NOTIFICATION_SERVICE) as AndroidNotificationManager).allNotifications

    private fun label(id: Int) = "label-$id"

    private fun flatten(value: Any?): String = when (value) {
        is Bundle -> value.keySet().joinToString(" ") { flatten(value.get(it)) }
        is Array<*> -> value.joinToString(" ") { flatten(it) }
        is Parcelable -> value.toString()
        else -> value?.toString().orEmpty()
    }

    /** App resources are not packaged for JVM tests; each string resolves to a stable label. */
    private class LabelContext(base: Context) : ContextWrapper(base) {
        @Suppress("DEPRECATION")
        private val labels = object : Resources(base.assets, base.resources.displayMetrics, base.resources.configuration) {
            override fun getText(id: Int): CharSequence = "label-$id"
            override fun getString(id: Int): String = "label-$id"
            override fun getString(id: Int, vararg formatArgs: Any?): String = "label-$id"
            override fun getQuantityString(id: Int, quantity: Int, vararg formatArgs: Any?): String = "label-$id"
        }
        override fun getResources(): Resources = labels
        override fun getApplicationContext(): Context = this
    }
}
