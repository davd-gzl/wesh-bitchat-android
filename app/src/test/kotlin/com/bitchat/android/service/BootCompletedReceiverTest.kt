package com.bitchat.android.service

import android.app.Application
import android.content.Context
import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class BootCompletedReceiverTest {
    @Test fun `missing action does not read preferences or touch services`() {
        val context = mock(Context::class.java)
        BootCompletedReceiver().onReceive(context, Intent())
        verifyNoInteractions(context)
    }

    @Test fun `unrelated action does not read preferences or touch services`() {
        val context = mock(Context::class.java)
        BootCompletedReceiver().onReceive(context, Intent("synthetic.unrelated.action"))
        verifyNoInteractions(context)
    }

    @Test fun `both boot actions respect disabled automatic startup`() {
        val context = RuntimeEnvironment.getApplication()
        MeshServicePreferences.init(context)
        MeshServicePreferences.setAutoStartEnabled(false)
        for (action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED)) {
            BootCompletedReceiver().onReceive(context, Intent(action))
            assertFalse(MeshServicePreferences.isAutoStartEnabled())
            assertNull(shadowOf(context).nextStartedService)
        }
    }
}
