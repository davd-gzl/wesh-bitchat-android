package com.bitchat.android.net

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Tor's preference only mirrors the Wesh Internet toggle: absent or unreadable means OFF. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class TorPreferenceManagerTest {
    private val app = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences("bitchat_settings", Context.MODE_PRIVATE)

    @Before fun freshInstall() {
        prefs.edit().clear().commit()
    }

    @Test fun `a fresh install reads OFF`() {
        assertEquals(TorMode.OFF, TorPreferenceManager.get(app))
        TorPreferenceManager.init(app)
        assertEquals(TorMode.OFF, TorPreferenceManager.modeFlow.value)
    }

    @Test fun `an unknown or wrongly typed value reads OFF without crashing`() {
        prefs.edit().putString("tor_mode", "SOMETHING").commit()
        assertEquals(TorMode.OFF, TorPreferenceManager.get(app))
        prefs.edit().putBoolean("tor_mode", true).commit()
        assertEquals(TorMode.OFF, TorPreferenceManager.get(app))
        TorPreferenceManager.init(app)
        assertEquals(TorMode.OFF, TorPreferenceManager.modeFlow.value)
    }

    @Test fun `ON is only read back after it was set`() {
        TorPreferenceManager.set(app, TorMode.ON, commit = true)
        assertEquals(TorMode.ON, TorPreferenceManager.get(app))
        TorPreferenceManager.set(app, TorMode.OFF, commit = true)
        assertEquals(TorMode.OFF, TorPreferenceManager.get(app))
    }
}
