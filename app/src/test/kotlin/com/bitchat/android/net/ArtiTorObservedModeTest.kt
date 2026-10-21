package com.bitchat.android.net

import android.app.Application
import info.guardianproject.arti.ArtiLogListener
import info.guardianproject.arti.ArtiNativeApi
import info.guardianproject.arti.ArtiProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Observer scheduling is controlled independently from each explicitly applied command. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class ArtiTorObservedModeTest {
    private val app = RuntimeEnvironment.getApplication()
    private val modes = MutableStateFlow(TorMode.OFF)
    private val observerScheduler = TestCoroutineScheduler()
    private val observerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(observerScheduler))
    private val native = FakeNative()

    @Before fun setUp() {
        TorPreferenceManager.set(app, TorMode.OFF, commit = true)
    }

    @After fun tearDown() {
        observerScope.cancel()
        TorPreferenceManager.set(app, TorMode.OFF, commit = true)
    }

    private fun manager() = ArtiTorManager(
        newProxy = { app, listener -> ArtiProxy(app, listener, native) },
        stopTimeoutMs = 0,
        appScope = observerScope,
        modeFlow = modes
    ).also { it.init(app) }

    @Test fun `init subscribes to preference changes before returning`() {
        manager()
        assertTrue("OFF must not be dropped as a delayed observer's first value", modes.subscriptionCount.value > 0)
    }

    @Test fun `a captured ON cannot supersede the latest OFF preference`() = runTest {
        val manager = manager()
        // A combined-flow emission can have captured ON before OFF was committed,
        // then reach the serialized application path after the stop completed.
        manager.applyObservedMode(app, TorMode.ON)
        val calls = native.snapshot()
        val wasRunning = manager.statusFlow.value.running
        manager.applyMode(app, TorMode.OFF)

        assertTrue("a stale observed ON must not reach native code", calls.isEmpty())
        assertFalse(wasRunning)
    }

    @Test fun `a captured OFF cannot cancel the latest ON preference`() = runTest {
        modes.value = TorMode.ON
        val manager = manager()
        manager.applyMode(app, TorMode.ON)
        manager.applyObservedMode(app, TorMode.OFF)
        val wasRunning = manager.statusFlow.value.running
        manager.applyMode(app, TorMode.OFF)

        assertTrue("a stale observed OFF must not stop the current start", wasRunning)
    }

    @Test fun `an observed ON queued behind bootstrap is rechecked after the mutex`() = runTest {
        modes.value = TorMode.ON
        val manager = manager()
        val release = CountDownLatch(1)
        native.blockInitialize = release
        val first = launch(Dispatchers.IO) { manager.applyMode(app, TorMode.ON) }
        var queued: Job? = null
        var off: Job? = null
        try {
            assertTrue("the first bootstrap holds the mutex", native.initializeEntered.await(5, TimeUnit.SECONDS))
            // UNDISPATCHED runs through entry validation and suspends at the held mutex
            // before launch returns, so this proves the second validation is necessary.
            queued = launch(start = CoroutineStart.UNDISPATCHED) {
                manager.applyObservedMode(app, TorMode.ON)
            }
            modes.value = TorMode.OFF
            off = launch(Dispatchers.IO) { manager.applyMode(app, TorMode.OFF) }
            assertTrue("OFF reaches native before bootstrap is released", native.stopped.await(5, TimeUnit.SECONDS))
            release.countDown()
            joinAll(first, requireNotNull(queued), requireNotNull(off))

            assertEquals("the queued stale ON never bootstraps", 1, native.snapshot().count { it == "initialize" })
            assertFalse(manager.statusFlow.value.running)
        } finally {
            release.countDown()
            manager.applyMode(app, TorMode.OFF)
            first.join()
            queued?.join()
            off?.join()
        }
    }

    @Test fun `an explicit ON remains independent of the preference observer`() = runTest {
        val manager = manager()
        manager.applyMode(app, TorMode.ON)
        val wasRunning = manager.statusFlow.value.running
        val starts = native.snapshot().count { it == "initialize" }
        manager.applyMode(app, TorMode.OFF)

        assertEquals(TorMode.OFF, modes.value)
        assertTrue(wasRunning)
        assertEquals(1, starts)
    }

    private class FakeNative : ArtiNativeApi {
        private val calls = mutableListOf<String>()
        @Volatile var blockInitialize: CountDownLatch? = null
        val initializeEntered = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        fun snapshot(): List<String> = synchronized(calls) { calls.toList() }
        override fun setLogCallback(callback: ArtiLogListener) = Unit
        override fun generation(): Long = 0
        override fun initialize(dataDir: String, generation: Long): Int {
            synchronized(calls) { calls += "initialize" }
            initializeEntered.countDown()
            check(blockInitialize?.await(10, TimeUnit.SECONDS) != false) { "synthetic bootstrap was not released" }
            return 0
        }
        override fun startSocksProxyWithAuth(port: Int, username: String, password: String): Int {
            synchronized(calls) { calls += "socks" }
            return 0
        }
        override fun stop(): Int {
            synchronized(calls) { calls += "stop" }
            stopped.countDown()
            return 0
        }
    }
}
