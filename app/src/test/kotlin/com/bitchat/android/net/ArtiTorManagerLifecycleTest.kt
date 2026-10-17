package com.bitchat.android.net

import android.app.Application
import info.guardianproject.arti.ArtiLogListener
import info.guardianproject.arti.ArtiNativeApi
import info.guardianproject.arti.ArtiProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
        var onStop: (() -> Unit)? = null
        /** When set, initialize blocks until it is released, as a real bootstrap does. */
        @Volatile var blockInitialize: CountDownLatch? = null
        val initializeEntered = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val stops = AtomicInteger(0)
        fun snapshot() = synchronized(calls) { calls.toList() }
        override fun setLogCallback(callback: ArtiLogListener) { listener = callback }
        override fun initialize(dataDir: String): Int {
            synchronized(calls) { calls += "initialize" }
            initializeEntered.countDown()
            blockInitialize?.await(10, TimeUnit.SECONDS)
            onInitialize?.invoke()
            return 0
        }
        override fun startSocksProxy(port: Int): Int { synchronized(calls) { calls += "socks" }; return 0 }
        override fun stop(): Int {
            onStop?.invoke()
            synchronized(calls) { calls += "stop" }
            stops.incrementAndGet()
            stopped.countDown()
            return 0
        }
    }

    private lateinit var native: FakeNative
    private lateinit var manager: ArtiTorManager
    private val recorders = CoroutineScope(Dispatchers.Unconfined)

    private fun newManager(native: FakeNative, stopTimeoutMs: Long) = ArtiTorManager(
        newProxy = { app, port, listener -> ArtiProxy(app, port, port + 1, listener, native) },
        stopTimeoutMs = stopTimeoutMs
    ).also { it.init(app) }

    @Before fun setUp() {
        TorPreferenceManager.set(app, TorMode.OFF, commit = true)
        native = FakeNative()
        manager = newManager(native, stopTimeoutMs = 50)
    }

    @After fun tearDown() = runBlocking {
        recorders.cancel()
        TorPreferenceManager.set(app, TorMode.OFF, commit = true)
        manager.applyMode(app, TorMode.OFF)
    }

    private fun assertFullyOff(manager: ArtiTorManager = this.manager) {
        val status = manager.statusFlow.value
        assertEquals(ArtiTorManager.TorState.OFF, status.state)
        assertFalse(status.running)
        assertEquals(0, status.bootstrapPercent)
        assertNull(manager.currentSocksAddress())
        assertFalse(manager.isProxyEnabled())
    }

    private fun isFullyOff(manager: ArtiTorManager): Boolean {
        val status = manager.statusFlow.value
        return status.state == ArtiTorManager.TorState.OFF && !status.running &&
            manager.currentSocksAddress() == null && !manager.isStartInFlight()
    }

    private fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    /** Every status the manager publishes, to prove it never reported Tor as running. */
    private fun recordStatuses(manager: ArtiTorManager): List<ArtiTorManager.TorStatus> {
        val seen = Collections.synchronizedList(mutableListOf<ArtiTorManager.TorStatus>())
        recorders.launch { manager.statusFlow.collect { seen += it } }
        return seen
    }

    private fun assertNeverRan(statuses: List<ArtiTorManager.TorStatus>) {
        synchronized(statuses) {
            assertTrue(statuses.none { it.running || it.state == ArtiTorManager.TorState.RUNNING })
        }
    }

    /** Internet off arrives through [turnOff] while the bootstrap is blocked in native code. */
    private fun assertOffStopsBlockedBootstrap(turnOff: () -> Unit) {
        val statuses = recordStatuses(manager)
        val release = CountDownLatch(1)
        native.blockInitialize = release
        try {
            TorPreferenceManager.set(app, TorMode.ON)
            assertTrue("the bootstrap began", native.initializeEntered.await(5, TimeUnit.SECONDS))
            val stopsBefore = native.stops.get()
            turnOff()
            assertTrue(
                "a native stop within 1 s, without waiting for the bootstrap",
                waitUntil(1_000) { native.stops.get() > stopsBefore }
            )
            assertEquals("the bootstrap was still blocked", 1L, release.count)
        } finally {
            release.countDown()
        }
        assertTrue(waitUntil { isFullyOff(manager) })
        assertFalse("no SOCKS port for a start that was turned off", native.snapshot().contains("socks"))
        assertNeverRan(statuses)
        assertFullyOff()
    }

    @Test fun `Internet off on the mode flow stops a blocked bootstrap at once`() =
        assertOffStopsBlockedBootstrap { TorPreferenceManager.set(app, TorMode.OFF, commit = true) }

    @Test fun `the Wesh Internet toggle stops a blocked bootstrap at once`() =
        assertOffStopsBlockedBootstrap {
            ArtiTorControl(app, manager.statusFlow, requestOff = { manager.requestOff() }).stop()
        }

    @Test fun `Internet off during the wait before a start never reaches native code`() {
        // A long stop timeout: the start waits for it before its first native call.
        val slowNative = FakeNative()
        val slow = newManager(slowNative, stopTimeoutMs = 800)
        val statuses = recordStatuses(slow)
        TorPreferenceManager.set(app, TorMode.ON)
        assertTrue(waitUntil { slow.statusFlow.value.state == ArtiTorManager.TorState.STARTING })
        Thread.sleep(100)
        assertTrue("still in the wait", slowNative.snapshot().isEmpty())
        TorPreferenceManager.set(app, TorMode.OFF, commit = true)
        assertTrue(waitUntil { isFullyOff(slow) })
        Thread.sleep(1_200) // past the end of the wait
        assertFalse(slowNative.snapshot().contains("initialize"))
        assertNeverRan(statuses)
        assertFullyOff(slow)
    }

    @Test fun `a wipe during the wait before a start leaves no client and no Arti files`() {
        val arti = ArtiTorControl.artiDirectory(app)
        ArtiTorControl.pendingMarker(app).delete()
        File(arti, "state").apply { mkdirs() }.resolve("guards").writeText("{}")
        val slowNative = FakeNative()
        val slow = newManager(slowNative, stopTimeoutMs = 800)
        val statuses = recordStatuses(slow)
        val on = thread { runBlocking { slow.applyMode(app, TorMode.ON) } }
        assertTrue(waitUntil { slow.statusFlow.value.state == ArtiTorManager.TorState.STARTING })
        Thread.sleep(100)
        assertTrue("still in the wait", slowNative.snapshot().isEmpty())
        val control = ArtiTorControl(
            app, slow.statusFlow,
            applyOff = { slow.applyMode(app, TorMode.OFF) },
            stopTimeoutMs = 5_000,
            startInFlight = { slow.isStartInFlight() },
            requestOff = { slow.requestOff() }
        )
        assertTrue(runBlocking { control.stopAndWipe() })
        on.join(5_000)
        assertFalse(on.isAlive)
        val calls = slowNative.snapshot()
        val init = calls.indexOf("initialize")
        assertTrue("no bootstrap, or one stopped after", init < 0 || calls.subList(init, calls.size).contains("stop"))
        assertFalse(arti.exists())
        assertNeverRan(statuses)
        assertFullyOff(slow)
    }

    @Test fun `the SOCKS address is withdrawn before the native stop, off the caller thread`() = runBlocking {
        val caller = Thread.currentThread()
        val atStop = Collections.synchronizedList(mutableListOf<Pair<InetSocketAddress?, Thread>>())
        native.onStop = { atStop += manager.currentSocksAddress() to Thread.currentThread() }
        manager.applyMode(app, TorMode.ON)
        assertNotNull(manager.currentSocksAddress())
        manager.applyMode(app, TorMode.OFF)
        assertTrue(atStop.isNotEmpty())
        assertTrue("no client may reach a port being torn down", atStop.all { it.first == null })
        assertTrue("the blocking native stop never runs on the caller thread", atStop.none { it.second == caller })
        assertFullyOff()
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
        assertFalse("no SOCKS port for a start that was turned off", native.snapshot().contains("socks"))
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
