package com.bitchat.android.net

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class ArtiTorControlWipeTest {
    @Test fun `panic wipe deletes Arti cache and state and leaves Tor preference off`() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        TorPreferenceManager.set(app, TorMode.ON)
        val arti = ArtiTorControl.artiDirectory(app)
        File(arti, "state").apply { mkdirs() }.resolve("synthetic-guards.json").writeText("{}")
        File(arti, "cache").apply { mkdirs() }.resolve("synthetic-consensus").writeText("x")

        assertTrue(ArtiTorControl(app).stopAndWipe())

        assertFalse(arti.exists())
        assertEquals(TorMode.OFF, TorPreferenceManager.get(app))
        assertEquals(TorMode.OFF, TorPreferenceManager.modeFlow.value)
    }
}
