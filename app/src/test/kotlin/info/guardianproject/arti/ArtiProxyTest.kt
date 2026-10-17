package info.guardianproject.arti

import android.app.Application
import com.bitchat.android.net.SocksCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** The contract ArtiTorManager relies on: stop always reaches the native side once a start began. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class ArtiProxyTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val credentials = SocksCredentials("synthetic-user", "synthetic-pass")

    private class FakeNative(var initResult: Int = 0) : ArtiNativeApi {
        val calls = mutableListOf<String>()
        var blockBootstrap: CountDownLatch? = null
        /** A bootstrap the stop does not reach (the stop came before the native start). */
        var uncancellable: CountDownLatch? = null
        val bootstrapping = CountDownLatch(1)
        var onLogCallback: (() -> Unit)? = null
        var onInitialize: (() -> Unit)? = null
        var stopGate: CountDownLatch? = null
        val stopping = CountDownLatch(1)
        var stopResult = 0
        override fun setLogCallback(callback: ArtiLogListener) {
            synchronized(calls) { calls += "log" }
            onLogCallback?.invoke()
        }
        override fun initialize(dataDir: String): Int {
            synchronized(calls) { calls += "initialize" }
            bootstrapping.countDown()
            onInitialize?.invoke()
            // Like the native side: a stop() cancels the bootstrap, which then returns -4.
            blockBootstrap?.let { return if (it.await(30, TimeUnit.SECONDS)) -4 else 0 }
            uncancellable?.await(30, TimeUnit.SECONDS)
            return initResult
        }
        override fun startSocksProxyWithAuth(port: Int, username: String, password: String): Int {
            synchronized(calls) { calls += "socks:$port:$username:$password" }
            return 0
        }
        override fun stop(): Int {
            synchronized(calls) { calls += "stop" }
            stopping.countDown()
            stopGate?.await(30, TimeUnit.SECONDS)
            blockBootstrap?.countDown()
            return stopResult
        }
    }

    @Test fun `a stop before any start never loads the native library`() {
        val native = FakeNative()
        ArtiProxy(app, null, native).stop()
        assertTrue(native.calls.isEmpty())
    }

    @Test fun `start hands the per-start port and credentials to the native proxy`() {
        val native = FakeNative()
        val proxy = ArtiProxy(app, null, native)
        proxy.start(23456, credentials)
        assertEquals(listOf("initialize", "socks:23456:synthetic-user:synthetic-pass"), native.calls)
        proxy.stop()
        assertEquals("stop", native.calls.last())
    }

    @Test fun `stop cancels a start still bootstrapping and the start fails`() {
        val native = FakeNative().apply { blockBootstrap = CountDownLatch(1) }
        val proxy = ArtiProxy(app, null, native)
        var failure: Throwable? = null
        val starter = thread { failure = runCatching { proxy.start(23456, credentials) }.exceptionOrNull() }
        assertTrue(native.bootstrapping.await(5, TimeUnit.SECONDS))

        proxy.stop()
        starter.join(5_000)
        assertFalse("start must return once stopped", starter.isAlive)
        assertTrue(failure is ArtiException)
        assertTrue("no SOCKS port after a cancelled start", native.calls.none { it.startsWith("socks") })
        assertEquals(1, native.calls.count { it == "stop" })
    }

    @Test fun `a failed start still releases whatever the native side holds`() {
        val native = FakeNative(initResult = -3)
        val proxy = ArtiProxy(app, null, native)
        assertTrue(runCatching { proxy.start(23456, credentials) }.exceptionOrNull() is ArtiException)
        proxy.stop()
        assertEquals(listOf("initialize", "stop"), native.calls)
    }

    @Test fun `every stop reaches the native side, even after a stop that interrupted a start`() {
        val native = FakeNative().apply { blockBootstrap = CountDownLatch(1) }
        val proxy = ArtiProxy(app, null, native)
        val starter = thread { runCatching { proxy.start(23456, credentials) } }
        assertTrue(native.bootstrapping.await(5, TimeUnit.SECONDS))
        proxy.stop()
        starter.join(5_000)
        proxy.stop()
        assertEquals(2, native.calls.count { it == "stop" })
    }

    @Test fun `a start that completes after a stop is stopped again and never marked running`() {
        val release = CountDownLatch(1)
        val native = FakeNative().apply { uncancellable = release }
        val proxy = ArtiProxy(app, null, native)
        var failure: Throwable? = null
        val starter = thread { failure = runCatching { proxy.start(23456, credentials) }.exceptionOrNull() }
        assertTrue(native.bootstrapping.await(5, TimeUnit.SECONDS))

        proxy.stop()
        release.countDown()
        starter.join(5_000)
        assertFalse(starter.isAlive)
        assertTrue(failure is ArtiException)
        assertEquals("the late start must be stopped again", "stop", native.calls.last())
        assertEquals(2, native.calls.count { it == "stop" })

        // Not left marked as running: the next start really starts.
        native.uncancellable = null
        proxy.start(23456, credentials)
        assertEquals(2, native.calls.count { it == "initialize" })
    }

    @Test fun `a stop before the bootstrap begins means no bootstrap at all`() {
        val native = FakeNative()
        val proxy = ArtiProxy(app, ArtiLogListener { }, native)
        native.onLogCallback = { native.onLogCallback = null; proxy.stop() }
        assertTrue(runCatching { proxy.start(23456, credentials) }.exceptionOrNull() is ArtiException)
        assertEquals(listOf("log", "stop", "stop"), native.calls)
    }

    @Test fun `a stop that reached the native side before the bootstrap stops it right after`() {
        val native = FakeNative()
        val proxy = ArtiProxy(app, null, native)
        // The bootstrap still returns 0: the stop came before it reached the native side.
        native.onInitialize = { native.onInitialize = null; proxy.stop() }
        assertTrue(runCatching { proxy.start(23456, credentials) }.exceptionOrNull() is ArtiException)
        assertEquals(listOf("initialize", "stop", "stop"), native.calls)
    }

    @Test fun `a start waits until a stop in progress has torn down`() {
        val gate = CountDownLatch(1)
        val native = FakeNative().apply { stopGate = gate }
        val proxy = ArtiProxy(app, null, native)
        native.stopGate = null
        proxy.start(23456, credentials)
        native.stopGate = gate
        val stopper = thread { proxy.stop() }
        assertTrue(native.stopping.await(5, TimeUnit.SECONDS))
        val starter = thread { proxy.start(23456, credentials) }
        starter.join(300)
        assertTrue("start must wait for the teardown", starter.isAlive)
        assertEquals(1, native.calls.count { it == "initialize" })

        gate.countDown()
        stopper.join(5_000)
        starter.join(5_000)
        assertFalse(starter.isAlive)
        assertEquals(2, native.calls.count { it == "initialize" })
    }

    @Test fun `a failed native stop leaves the proxy marked running`() {
        val native = FakeNative()
        val proxy = ArtiProxy(app, null, native)
        proxy.start(23456, credentials)
        native.stopResult = -1
        proxy.stop()
        proxy.start(23456, credentials)
        assertEquals("no second start over a client that may still run", 1, native.calls.count { it == "initialize" })
        native.stopResult = 0
        proxy.stop()
        proxy.start(23456, credentials)
        assertEquals(2, native.calls.count { it == "initialize" })
    }

    @Test fun `no start begins while the Arti directory is being wiped`() {
        val native = FakeNative()
        val proxy = ArtiProxy(app, ArtiLogListener { }, native)
        ArtiWipeLatch.begin()
        try {
            assertTrue(runCatching { proxy.start(23456, credentials) }.exceptionOrNull() is ArtiException)
            assertTrue("nothing native and no directory work while wiping", native.calls.isEmpty())
            assertFalse(proxy.isStartInFlight())
        } finally {
            ArtiWipeLatch.end()
        }
        proxy.start(23456, credentials)
        assertEquals(1, native.calls.count { it == "initialize" })
    }

    @Test fun `a start still waiting for a stop already counts as in flight`() {
        val gate = CountDownLatch(1)
        val native = FakeNative()
        val proxy = ArtiProxy(app, null, native)
        proxy.start(23456, credentials)
        native.stopGate = gate
        val stopper = thread { proxy.stop() }
        assertTrue(native.stopping.await(5, TimeUnit.SECONDS))
        assertFalse(proxy.isStartInFlight())
        val starter = thread { runCatching { proxy.start(23456, credentials) } }
        val deadline = System.currentTimeMillis() + 5_000
        while (!proxy.isStartInFlight() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue(proxy.isStartInFlight())
        gate.countDown()
        stopper.join(5_000)
        starter.join(5_000)
        assertFalse(proxy.isStartInFlight())
    }
}
