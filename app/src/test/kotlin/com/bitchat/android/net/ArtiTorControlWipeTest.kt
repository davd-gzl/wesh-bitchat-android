package com.bitchat.android.net

import android.app.Application
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class ArtiTorControlWipeTest {
    private val app = RuntimeEnvironment.getApplication()
    private val arti get() = ArtiTorControl.artiDirectory(app)
    private val running = ArtiTorManager.TorStatus(TorMode.ON, true, 100, "", ArtiTorManager.TorState.RUNNING)

    @Before fun seed() {
        TorPreferenceManager.set(app, TorMode.ON)
        File(arti, "state").apply { mkdirs() }.resolve("synthetic-guards.json").writeText("{}")
        File(arti, "cache").apply { mkdirs() }.resolve("synthetic-consensus").writeText("x")
    }

    @Test fun `panic wipe always stops Arti, waits for it, then deletes cache and state`() = runBlocking {
        val status = MutableStateFlow(ArtiTorManager.TorStatus())
        var stops = 0
        // Even a status that already reads OFF goes through the stop.
        val control = ArtiTorControl(app, status, applyOff = { stops++; status.value = ArtiTorManager.TorStatus() })
        assertTrue(control.stopAndWipe())
        assertEquals(1, stops)
        assertFalse(arti.exists())
        assertEquals(TorMode.OFF, TorPreferenceManager.get(app))
        assertEquals(TorMode.OFF, TorPreferenceManager.modeFlow.value)
    }

    @Test fun `Arti that does not stop in time fails the wipe and is not deleted under it`() = runBlocking {
        val status = MutableStateFlow(running)
        val control = ArtiTorControl(app, status, applyOff = { }, stopTimeoutMs = 200)
        assertFalse(control.stopAndWipe())
        assertTrue(File(arti, "state/synthetic-guards.json").exists())
        status.value = running.copy(state = ArtiTorManager.TorState.OFF)
        assertFalse("Still running", ArtiTorControl(app, status, applyOff = { }, stopTimeoutMs = 200).stopAndWipe())
    }
}
