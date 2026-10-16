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
        val bootstrapping = CountDownLatch(1)
        override fun setLogCallback(callback: ArtiLogListener) { synchronized(calls) { calls += "log" } }
        override fun initialize(dataDir: String): Int {
            synchronized(calls) { calls += "initialize" }
            bootstrapping.countDown()
            // Like the native side: a stop() cancels the bootstrap, which then returns -4.
            blockBootstrap?.let { return if (it.await(30, TimeUnit.SECONDS)) -4 else 0 }
            return initResult
        }
        override fun startSocksProxyWithAuth(port: Int, username: String, password: String): Int {
            synchronized(calls) { calls += "socks:$port:$username:$password" }
            return 0
        }
        override fun stop(): Int {
            synchronized(calls) { calls += "stop" }
            blockBootstrap?.countDown()
            return 0
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
}
