package com.bitchat.android.service

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.core.app.RemoteInput
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.ui.NotificationManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class WeshNotificationReceiverTest {
    @Test fun `stale remote input cannot persist a reply or start a Wesh transport`() {
        AppStateStore.clear()
        val intent = Intent(NotificationManager.ACTION_REPLY_TO_CONVERSATION)
            .putExtra(NotificationManager.EXTRA_PEER_ID, "aaaabbbbccccdddd")
        val input = RemoteInput.Builder(NotificationManager.KEY_TEXT_REPLY).build()
        RemoteInput.addResultsToIntent(arrayOf(input), intent, Bundle().apply {
            putCharSequence(NotificationManager.KEY_TEXT_REPLY, "Synthetic notification reply")
        })
        ConversationNotificationReceiver().onReceive(RuntimeEnvironment.getApplication(), intent)
        assertTrue(AppStateStore.privateMessages.value.isEmpty())
        assertNull(org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
    }
}
