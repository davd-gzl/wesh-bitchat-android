package com.bitchat.android.net

import android.app.Application
import info.guardianproject.arti.ArtiLogListener
import info.guardianproject.arti.ArtiNativeApi
import info.guardianproject.arti.ArtiProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The SOCKS address and credentials exist only while our own proxy holds the port. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class ArtiTorManagerSocksTest {
    private val app = RuntimeEnvironment.getApplication()

    private class FakeNative(private val manager: () -> ArtiTorManager) : ArtiNativeApi {
        var initResult = 0
        /** Results for successive SOCKS starts; the last one repeats. */
        var socksResults = listOf(0)
        val ports = mutableListOf<Int>()
        val usernames = mutableListOf<String>()
        /** Whether clients could see an address or credentials at each native call. */
        val publishedDuringStart = mutableListOf<Boolean>()

        private fun published() = manager().currentSocksAddress() != null || manager().currentSocksAuth() != null
        override fun setLogCallback(callback: ArtiLogListener) = Unit
        override fun generation() = 0L
        override fun initialize(dataDir: String, generation: Long): Int { publishedDuringStart += published(); return initResult }
        override fun startSocksProxyWithAuth(port: Int, username: String, password: String): Int {
            publishedDuringStart += published()
            ports += port; usernames += username
            return socksResults[minOf(ports.size - 1, socksResults.size - 1)]
        }
        override fun stop() = 0
    }

    private lateinit var native: FakeNative
    private lateinit var manager: ArtiTorManager
    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Before fun setUp() {
        TorPreferenceManager.set(app, TorMode.OFF, commit = true)
        native = FakeNative { manager }
        manager = ArtiTorManager(
            newProxy = { app, listener -> ArtiProxy(app, listener, native) },
            stopTimeoutMs = 50,
            appScope = managerScope
        )
        manager.init(app)
    }

    @After fun tearDown() {
        runBlocking {
            manager.requestOff()
            managerScope.cancel()
            TorPreferenceManager.set(app, TorMode.OFF, commit = true)
            manager.applyMode(app, TorMode.OFF)
            managerScope.coroutineContext[Job]?.join()
        }
    }

    @Test fun `a successful start publishes exactly the bound port with its credentials`() = runBlocking {
        manager.applyMode(app, TorMode.ON)
        val auth = requireNotNull(manager.currentSocksAuth()) { "nothing published after a successful start" }
        assertEquals(native.ports.single(), auth.address.port)
        assertEquals("127.0.0.1", auth.address.hostString)
        assertEquals(native.usernames.single(), auth.credentials.username)
        assertEquals(auth.address, manager.currentSocksAddress())
        assertTrue("nothing published before the bind", native.publishedDuringStart.none { it })

        manager.applyMode(app, TorMode.OFF)
        assertNull(manager.currentSocksAddress())
        assertNull(manager.currentSocksAuth())
    }

    @Test fun `a failed bind publishes nothing and the retry publishes only its own port`() = runBlocking {
        native.socksResults = listOf(-3, 0)
        manager.applyMode(app, TorMode.ON)
        assertEquals(2, native.ports.size)
        assertTrue("nothing published while binding or retrying", native.publishedDuringStart.none { it })
        val auth = manager.currentSocksAuth()!!
        assertEquals(native.ports[1], auth.address.port)
        assertEquals(native.usernames[1], auth.credentials.username)
    }

    @Test fun `giving up after bind failures leaves no address and no credentials`() = runBlocking {
        native.socksResults = listOf(-3)
        manager.applyMode(app, TorMode.ON)
        assertTrue(native.ports.size > 1)
        assertTrue(native.publishedDuringStart.none { it })
        assertNull(manager.currentSocksAddress())
        assertNull(manager.currentSocksAuth())
    }

    @Test fun `a failed bootstrap leaves no address and no credentials`() = runBlocking {
        native.initResult = -3
        manager.applyMode(app, TorMode.ON)
        assertTrue(native.ports.isEmpty())
        assertNull(manager.currentSocksAddress())
        assertNull(manager.currentSocksAuth())
    }
}
