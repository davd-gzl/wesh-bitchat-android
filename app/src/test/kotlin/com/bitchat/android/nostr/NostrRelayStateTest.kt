package com.bitchat.android.nostr

import com.bitchat.android.net.NostrRelayControl
import com.bitchat.android.net.WeshNetworkGate
import com.bitchat.android.net.WeshNetworkPolicy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.IOException
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NostrRelayStateTest {
    private val originalGate = WeshNetworkPolicy.gate
    private val managers = mutableListOf<NostrRelayManager>()

    @After fun restore() {
        WeshNetworkPolicy.gate = WeshNetworkGate { false }
        managers.forEach(NostrRelayManager::disconnect)
        WeshNetworkPolicy.gate = originalGate
    }

    @Test
    fun `a relay that connects is published to both connected counts`() = runTest {
        val manager = NostrRelayManager.shared
        manager.disconnect()
        managers += manager
        val fromRelays = mutableListOf<Int>()
        val fromControl = mutableListOf<Int>()
        val control = NostrRelayControl(RuntimeEnvironment.getApplication())
        val a = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.connectedCount.collect { fromRelays += it }
        }
        val b = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            control.connectedCount.collect { fromControl += it }
        }

        manager.updateRelayStatus(RELAY, isConnected = true)
        manager.updateRelayStatus(RELAY, isConnected = true)
        manager.updateRelayStatus(RELAY, isConnected = false)
        a.cancel()
        b.cancel()

        assertEquals(listOf(0, 1, 0), fromRelays)
        assertEquals(listOf(0, 1, 0), fromControl)
    }

    @Test
    fun `two connect requests open only one socket per relay`() = runTest {
        val (manager, attempts) = fixture()
        manager.connect()
        manager.connect()
        runCurrent()

        assertEquals(
            NostrRelayManager.WESH_PINNED_RELAYS.map { it.removePrefix("wss://") }.sorted(),
            attempts.map { it.request.url.host }.sorted()
        )
    }

    @Test
    fun `an open callback before factory return belongs to its attempt`() = runTest {
        val (manager, attempts) = fixture { it.open() }
        manager.connect()
        runCurrent()

        assertEquals(NostrRelayManager.WESH_PINNED_RELAYS.size, attempts.size)
        assertTrue(manager.relays.value.all { it.isConnected })
        assertTrue(attempts.none { it.socket.closed })
    }

    @Test
    fun `a failed callback before factory return cannot leave a dead registered socket`() = runTest {
        var failFirst = true
        val (manager, attempts) = fixture {
            if (it.request.url.host == "relay.damus.io" && failFirst) {
                failFirst = false
                it.fail()
            }
        }
        manager.connect()
        runCurrent()
        manager.connect()
        runCurrent()

        assertEquals(2, attempts.count { it.request.url.host == "relay.damus.io" })
        assertTrue(attempts.first { it.request.url.host == "relay.damus.io" }.socket.closed)
    }

    @Test
    fun `reset during socket creation replaces that generation without losing the next connect`() = runTest {
        lateinit var manager: NostrRelayManager
        var resetFirst = true
        val fixture = fixture {
            if (it.request.url.host == "relay.damus.io" && resetFirst) {
                resetFirst = false
                manager.resetAllConnections()
            }
        }
        manager = fixture.first
        val attempts = fixture.second
        manager.connect()
        runCurrent()
        val relayAttempts = attempts.filter { it.request.url.host == "relay.damus.io" }

        assertEquals(2, relayAttempts.size)
        assertTrue(relayAttempts.first().socket.closed)
        relayAttempts.last().open()
        relayAttempts.first().open()
        relayAttempts.first().fail()
        assertTrue(manager.relays.value.single { it.url == RELAY }.isConnected)
        assertFalse(relayAttempts.last().socket.closed)
    }

    @Test
    fun `a gate that disconnects during open validation cannot publish connected afterwards`() = runTest {
        val (manager, attempts) = fixture()
        manager.connect()
        runCurrent()
        var revoke = true
        WeshNetworkPolicy.gate = WeshNetworkGate {
            if (revoke) {
                revoke = false
                manager.disconnect()
            }
            true
        }
        attempts.first().open()

        assertFalse(manager.isConnected.value)
        assertTrue(manager.relays.value.none { it.isConnected })
        assertTrue(attempts.all { it.socket.closed })
    }

    @Test
    fun `off during creation rejects its socket and all late callbacks without dialing again`() = runTest {
        lateinit var manager: NostrRelayManager
        val fixture = fixture {
            WeshNetworkPolicy.gate = WeshNetworkGate { false }
            manager.disconnect()
        }
        manager = fixture.first
        val attempts = fixture.second
        manager.connect()
        runCurrent()
        attempts.single().open()
        attempts.single().fail()
        manager.connect()
        manager.resetAllConnections()
        runCurrent()

        assertEquals(1, attempts.size)
        assertTrue(attempts.single().socket.closed)
        assertFalse(manager.isConnectionDesired())
        assertTrue(manager.relays.value.none { it.isConnected })
    }

    @Test
    fun `gate revocation after an early open removes its published socket`() = runTest {
        var revokeFirst = true
        val (manager, attempts) = fixture {
            it.open()
            if (revokeFirst) {
                revokeFirst = false
                WeshNetworkPolicy.gate = WeshNetworkGate { false }
            }
        }
        manager.connect()
        runCurrent()
        val rejected = attempts.single()
        assertTrue(rejected.socket.closed)
        // A late terminal callback must not leave a socket or a published count behind.
        rejected.fail()
        val connectedAfterRevocation = manager.isConnected.value
        val publishedAfterRevocation = manager.relays.value.count { it.isConnected }

        WeshNetworkPolicy.gate = WeshNetworkGate { true }
        manager.connect()
        runCurrent()
        assertEquals(2, attempts.count { it.request.url.host == rejected.request.url.host })
        assertFalse(connectedAfterRevocation)
        assertEquals(0, publishedAfterRevocation)
        assertEquals(NostrRelayManager.WESH_PINNED_RELAYS.size, manager.relays.value.count { it.isConnected })
    }

    @Test
    fun `revocation publication cannot discard a replacement opened by its collector`() = runTest {
        // Run each manager launch immediately, including a retry from inside publication.
        // Unconfined dispatchers may queue that nested launch until the old cleanup finishes.
        val inlineDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()
        }
        var revokeFirst = true
        val (manager, attempts) = fixture(dispatcher = inlineDispatcher) {
            it.open()
            if (revokeFirst) {
                revokeFirst = false
                WeshNetworkPolicy.gate = WeshNetworkGate { false }
            }
        }
        var sawEarlyOpen = false
        var retried = false
        val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.relays.collect { relays ->
                if (relays.single { it.url == RELAY }.isConnected) {
                    sawEarlyOpen = true
                } else if (sawEarlyOpen && !retried) {
                    retried = true
                    WeshNetworkPolicy.gate = WeshNetworkGate { true }
                    manager.retryConnection(RELAY)
                }
            }
        }
        manager.connect()
        runCurrent()
        observer.cancel()

        val relayAttempts = attempts.filter { it.request.url.host == "relay.damus.io" }
        assertTrue(retried)
        assertEquals(2, relayAttempts.size)
        val rejected = relayAttempts.first()
        val replacement = relayAttempts.last()
        assertTrue(rejected.socket.closed)
        rejected.open()
        rejected.fail()
        replacement.open()

        assertFalse(replacement.socket.closed)
        assertTrue(manager.relays.value.single { it.url == RELAY }.isConnected)
    }

    @Test
    fun `retry with a closed gate cannot arm a later reset`() = runTest {
        val (manager, attempts) = fixture()
        WeshNetworkPolicy.gate = WeshNetworkGate { false }
        manager.retryConnection(RELAY)
        WeshNetworkPolicy.gate = WeshNetworkGate { true }
        manager.resetAllConnections()
        runCurrent()

        assertFalse(manager.isConnectionDesired())
        assertTrue(attempts.isEmpty())
    }

    @Test
    fun `panic invalidates all callbacks and reset stays off`() = runTest {
        val (manager, attempts) = fixture()
        manager.connect()
        runCurrent()
        attempts.forEach(Attempt::open)
        WeshNetworkPolicy.gate = WeshNetworkGate { false }
        manager.clearAllOnPanic()
        attempts.forEach { it.open(); it.fail() }
        manager.resetAllConnections()
        runCurrent()

        assertEquals(NostrRelayManager.WESH_PINNED_RELAYS.size, attempts.size)
        assertFalse(manager.isConnectionDesired())
        assertFalse(manager.isConnected.value)
        assertTrue(manager.relays.value.none { it.isConnected })
        assertTrue(attempts.all { it.socket.closed })
    }

    private fun TestScope.fixture(
        dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(testScheduler),
        onCreate: (Attempt) -> Unit = {}
    ): Pair<NostrRelayManager, MutableList<Attempt>> {
        WeshNetworkPolicy.gate = WeshNetworkGate { true }
        val manager = NostrRelayManager(CoroutineScope(backgroundScope.coroutineContext + dispatcher))
        managers += manager
        val attempts = mutableListOf<Attempt>()
        manager.openWebSocket = { request, listener ->
            Attempt(request, listener).also { attempts += it; onCreate(it) }.socket
        }
        return manager to attempts
    }

    private class Attempt(val request: Request, private val listener: WebSocketListener) {
        val socket = IdleWebSocket(request)
        fun open() = listener.onOpen(socket, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Synthetic upgrade").build())
        fun fail() = listener.onFailure(socket, IOException("Synthetic connection failure"), null)
    }

    private class IdleWebSocket(private val request: Request) : WebSocket {
        var closed = false
            private set
        override fun request(): Request = request
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean = !closed
        override fun send(bytes: ByteString): Boolean = !closed
        override fun close(code: Int, reason: String?): Boolean { closed = true; return true }
        override fun cancel() { closed = true }
    }

    companion object {
        private const val RELAY = "wss://relay.damus.io"
    }
}
