package com.bitchat.android.net

import android.app.Application
import info.guardianproject.arti.ArtiLogListener
import info.guardianproject.arti.ArtiNativeApi
import info.guardianproject.arti.ArtiProxy
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Internet off always ends fully OFF, whatever a start or a late Arti log line does meanwhile. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class ArtiTorManagerLifecycleTest {
    private val app = RuntimeEnvironment.getApplication()

    private class FakeNative : ArtiNativeApi {
        val calls = mutableListOf<String>()
        var listener: ArtiLogListener? = null
        var onInitialize: (() -> Unit)? = null
        val stopped = CountDownLatch(1)
        override fun setLogCallback(callback: ArtiLogListener) { listener = callback }
        override fun initialize(dataDir: String): Int {
            synchronized(calls) { calls += "initialize" }
            onInitialize?.invoke()
            return 0
        }
        override fun startSocksProxy(port: Int): Int { synchronized(calls) { calls += "socks" }; return 0 }
        override fun stop(): Int { synchronized(calls) { calls += "stop" }; stopped.countDown(); return 0 }
    }

    private lateinit var native: FakeNative
    private lateinit var manager: ArtiTorManager

    @Before fun setUp() {
        TorPreferenceManager.set(app, TorMode.OFF, commit = true)
        native = FakeNative()
        manager = ArtiTorManager(
            newProxy = { app, port, listener -> ArtiProxy(app, port, port + 1, listener, native) },
            stopTimeoutMs = 50
        )
        manager.init(app)
    }

    @After fun tearDown() = runBlocking { manager.applyMode(app, TorMode.OFF) }

    private fun assertFullyOff() {
        val status = manager.statusFlow.value
        assertEquals(ArtiTorManager.TorState.OFF, status.state)
        assertFalse(status.running)
        assertEquals(0, status.bootstrapPercent)
        assertNull(manager.currentSocksAddress())
        assertFalse(manager.isProxyEnabled())
    }

    @Test fun `late Arti log lines after Internet off cannot bring Tor back to running`() = runBlocking {
        manager.applyMode(app, TorMode.ON)
        assertTrue(manager.statusFlow.value.running)
        manager.applyMode(app, TorMode.OFF)
        assertFullyOff()

        native.listener!!.onLogLine("Sufficiently bootstrapped; system SOCKS now functional")
        native.listener!!.onLogLine("We have found that guard [scrubbed] is usable.")
        assertFullyOff()
    }

    @Test fun `Internet off during a start stops it before it ever runs`() = runBlocking {
        var off: Thread? = null
        native.onInitialize = {
            native.onInitialize = null
            // Internet off arrives while the bootstrap runs; its native stop lands first.
            off = thread { runBlocking { manager.applyMode(app, TorMode.OFF) } }
            assertTrue(native.stopped.await(5, TimeUnit.SECONDS))
        }
        manager.applyMode(app, TorMode.ON)
        off!!.join(5_000)
        assertFalse(off!!.isAlive)
        assertFalse("no SOCKS port for a start that was turned off", native.calls.contains("socks"))
        assertFullyOff()
    }

    @Test fun `Internet on is refused while the Arti directory is being wiped`() = runBlocking {
        info.guardianproject.arti.ArtiWipeLatch.begin()
        try {
            manager.applyMode(app, TorMode.ON)
        } finally {
            info.guardianproject.arti.ArtiWipeLatch.end()
        }
        assertTrue("no bootstrap during a wipe", native.calls.isEmpty())
        assertEquals(TorMode.OFF, TorPreferenceManager.get(app))
        assertFalse(manager.isStartInFlight())
    }
}
