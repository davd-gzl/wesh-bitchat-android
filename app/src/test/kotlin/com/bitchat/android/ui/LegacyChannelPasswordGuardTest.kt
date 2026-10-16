package com.bitchat.android.ui

import android.app.Application
import android.content.Context
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class LegacyChannelPasswordGuardTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun manager(state: ChatState, legacy: Boolean? = null): ChannelManager {
        val messages = MessageManager(state)
        return if (legacy == null) ChannelManager(state, messages, DataManager(context), TestScope())
        else ChannelManager(state, messages, DataManager(context), TestScope(), legacyChannelsEnabled = legacy)
    }

    @Test
    fun `Wesh build cannot join or protect a legacy channel`() {
        val state = ChatState(TestScope())
        val channels = manager(state)

        assertFalse(channels.joinChannel("#synthetic", "any-password", "0011223344556677"))
        channels.setChannelPassword("#synthetic", "any-password")
        assertTrue(state.getJoinedChannelsValue().isEmpty())
        assertFalse(channels.hasChannelKey("#synthetic"))
    }

    @Test
    fun `a password protected channel never accepts an unverifiable password`() {
        val state = ChatState(TestScope())
        state.setPasswordProtectedChannels(setOf("#synthetic"))
        val channels = manager(state, legacy = true)

        assertFalse(channels.joinChannel("#synthetic", "wrong-password", "0011223344556677"))
        assertFalse(state.getJoinedChannelsValue().contains("#synthetic"))
    }
}
