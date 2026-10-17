package com.bitchat.android.net

import android.app.Application
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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
        ArtiTorControl.pendingMarker(app).delete()
        app.getSharedPreferences("wesh_arti_wipe", android.content.Context.MODE_PRIVATE).edit().clear().commit()
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
        assertFalse(ArtiTorControl.pendingMarker(app).exists())
    }

    @Test fun `the state directory is deleted only after Arti confirms it stopped`() = runBlocking {
        val status = MutableStateFlow(running)
        val events = mutableListOf<String>()
        val control = ArtiTorControl(
            app, status,
            applyOff = {
                events += "stop requested"
                // The native stop returns later, once the client and its runtime are gone.
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                    kotlinx.coroutines.delay(100)
                    events += "stopped"
                    status.value = ArtiTorManager.TorStatus()
                }
            },
            deleteArti = { dir ->
                events += "delete while ${status.value.state}"
                dir.deleteRecursively() && !dir.exists()
            }
        )
        assertTrue(control.stopAndWipe())
        assertEquals(listOf("stop requested", "stopped", "delete while OFF"), events)
        assertFalse(arti.exists())
    }

    @Test fun `a start still in flight cannot leave the wiped directory behind`() = runBlocking {
        val status = MutableStateFlow(ArtiTorManager.TorStatus())
        var stops = 0
        var inFlightChecks = 0
        val control = ArtiTorControl(
            app, status,
            applyOff = { stops++; status.value = ArtiTorManager.TorStatus() },
            startInFlight = {
                // A late bootstrap recreates the directory after the first delete, then ends.
                if (++inFlightChecks == 1) File(arti, "state").apply { mkdirs() }.resolve("late").writeText("x")
                inFlightChecks < 3
            }
        )
        assertTrue(control.stopAndWipe())
        assertTrue(inFlightChecks >= 3)
        assertEquals("stopped again once the start settled", 2, stops)
        assertFalse(arti.exists())
        assertFalse(ArtiTorControl.pendingMarker(app).exists())
    }

    @Test fun `a stop timeout still deletes the state and leaves a wipe for the next start`() = runBlocking {
        val status = MutableStateFlow(running)
        val control = ArtiTorControl(app, status, applyOff = { }, stopTimeoutMs = 200)
        assertFalse(control.stopAndWipe())
        assertFalse("Deletion is attempted even when the stop is unconfirmed", arti.exists())
        assertTrue(ArtiTorControl.pendingMarker(app).exists())

        // The still-running Arti wrote again before the process died; next start finishes the wipe.
        File(arti, "state").apply { mkdirs() }.resolve("late-write").writeText("x")
        assertTrue(ArtiTorControl.completePendingWipe(app))
        assertFalse(arti.exists())
        assertFalse(ArtiTorControl.pendingMarker(app).exists())
        assertTrue("Nothing pending: a normal start is a no-op", ArtiTorControl.completePendingWipe(app))
    }

    @Test fun `a failed deletion fails the wipe and blocks Arti start until it succeeds`() = runBlocking {
        val status = MutableStateFlow(ArtiTorManager.TorStatus())
        var deletable = false
        val delete: (File) -> Boolean = { dir -> deletable && dir.deleteRecursively() && !dir.exists() }
        val control = ArtiTorControl(app, status, applyOff = { }, deleteArti = delete)
        assertFalse(control.stopAndWipe())
        assertTrue(File(arti, "state/synthetic-guards.json").exists())
        assertTrue(ArtiTorControl.pendingMarker(app).exists())

        TorPreferenceManager.set(app, TorMode.ON)
        assertFalse(control.start())
        assertEquals("Arti must not start over undeleted state", TorMode.OFF, TorPreferenceManager.get(app))
        assertFalse(ArtiTorControl.completePendingWipe(app, delete))

        deletable = true
        assertTrue(ArtiTorControl.completePendingWipe(app, delete))
        assertFalse(arti.exists())
        assertFalse(ArtiTorControl.pendingMarker(app).exists())
    }

    @Test fun `without a marker a committed preference keeps the wipe pending`() = runBlocking {
        val status = MutableStateFlow(ArtiTorManager.TorStatus())
        var deletable = false
        val delete: (File) -> Boolean = { dir -> deletable && dir.deleteRecursively() && !dir.exists() }
        val control = ArtiTorControl(app, status, applyOff = { }, deleteArti = delete, createMarker = { false })
        assertFalse(control.stopAndWipe())
        assertFalse(ArtiTorControl.pendingMarker(app).exists())
        assertTrue(ArtiTorControl.isWipePending(app))
        assertFalse(control.start())
        deletable = true
        assertTrue(ArtiTorControl.completePendingWipe(app, delete))
        assertFalse(ArtiTorControl.isWipePending(app))
    }

    @Test fun `the manager refuses to start Arti while the wipe cannot complete`() = runBlocking {
        File(arti, "state/synthetic-guards.json").writeText("{}")
        ArtiTorControl.pendingMarker(app).createNewFile()
        val locked = File(arti, "state")
        assertTrue(locked.setWritable(false))
        try {
            TorPreferenceManager.set(app, TorMode.ON)
            ArtiTorManager.getInstance().applyMode(app, TorMode.ON)
            assertEquals(TorMode.OFF, TorPreferenceManager.get(app))
            assertEquals(TorMode.OFF, ArtiTorManager.getInstance().statusFlow.value.mode)
            assertTrue(File(arti, "state/synthetic-guards.json").exists())
        } finally {
            locked.setWritable(true)
        }
        assertTrue(ArtiTorControl.completePendingWipe(app))
    }
}
