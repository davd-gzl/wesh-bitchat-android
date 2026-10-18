package com.bitchat.android.nostr

import android.app.Application
import com.bitchat.android.net.WeshNetworkGate
import com.bitchat.android.net.WeshNetworkPolicy
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35])
class NostrRelayRegistrationTest {
    @Test fun `unsubscribing rejects an event already queued for its old handler`() = runTest {
        fixture { manager, socket ->
            val received = mutableListOf<String>()
            manager.subscribe(FILTER, ID, { received += it.id })
            socket.event("synthetic-unsubscribed")
            assertEquals(emptyList<String>(), received)
            manager.unsubscribe(ID)
            runCurrent()
            assertEquals(emptyList<String>(), received)
        }
    }

    @Test fun `same id and callback cannot upgrade a queued event to a replacement registration`() = runTest {
        fixture { manager, socket ->
            val received = mutableListOf<String>()
            val handler: (NostrEvent) -> Unit = { received += it.id }
            manager.subscribe(FILTER, ID, handler)
            val original = manager.getActiveSubscriptions().getValue(ID)
            socket.event("synthetic-old-registration")
            manager.unsubscribe(ID)
            manager.subscribe(FILTER, ID, handler)
            check(manager.getActiveSubscriptions().getValue(ID) !== original)
            socket.event("synthetic-fresh-registration")
            runCurrent()
            assertEquals(listOf("synthetic-fresh-registration"), received)
        }
    }

    @Test fun `panic and later opt in cannot admit an event queued before reset`() = runTest {
        fixture { manager, socket ->
            val received = mutableListOf<String>()
            val handler: (NostrEvent) -> Unit = { received += it.id }
            manager.subscribe(FILTER, ID, handler)
            socket.event("synthetic-pre-panic")
            WeshNetworkPolicy.gate = WeshNetworkGate { false }
            manager.clearAllOnPanic()
            WeshNetworkPolicy.gate = WeshNetworkGate { true }
            manager.subscribe(FILTER, ID, handler)
            runCurrent()
            assertEquals(emptyList<String>(), received)
        }
    }

    @Test fun `a current registration still receives its queued event`() = runTest {
        fixture { manager, socket ->
            val received = mutableListOf<String>()
            manager.subscribe(FILTER, ID, { received += it.id })
            socket.event("synthetic-current")
            assertEquals(emptyList<String>(), received)
            runCurrent()
            assertEquals(listOf("synthetic-current"), received)
        }
    }

    @Test fun `a closed network gate still refuses a queued current event`() = runTest {
        fixture { manager, socket ->
            val received = mutableListOf<String>()
            manager.subscribe(FILTER, ID, { received += it.id })
            socket.event("synthetic-off")
            WeshNetworkPolicy.gate = WeshNetworkGate { false }
            runCurrent()
            assertEquals(emptyList<String>(), received)
        }
    }

    @Test fun `subscription filter still refuses an unmatched event`() = runTest {
        fixture { manager, socket ->
            val received = mutableListOf<String>()
            manager.subscribe(FILTER, ID, { received += it.id })
            socket.event("synthetic-other-kind", NostrKind.GIFT_WRAP)
            runCurrent()
            assertEquals(emptyList<String>(), received)
        }
    }

    private suspend fun TestScope.fixture(block: suspend TestScope.(NostrRelayManager, Attempt) -> Unit) {
        val originalGate = WeshNetworkPolicy.gate
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val manager = NostrRelayManager(CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)))
        val attempts = mutableListOf<Attempt>()
        manager.openWebSocket = { request, listener ->
            Attempt(request, listener).also { attempts += it }.socket
        }
        try {
            WeshNetworkPolicy.gate = WeshNetworkGate { true }
            manager.connect()
            val attempt = attempts.first()
            attempt.open()
            block(manager, attempt)
        } finally {
            WeshNetworkPolicy.gate = WeshNetworkGate { false }
            manager.clearAllOnPanic()
            Dispatchers.resetMain()
            WeshNetworkPolicy.gate = originalGate
        }
    }

    private class Attempt(private val request: Request, private val listener: WebSocketListener) {
        val socket = object : WebSocket {
            private var closed = false
            override fun request(): Request = request
            override fun queueSize(): Long = 0
            override fun send(text: String): Boolean = !closed
            override fun send(bytes: ByteString): Boolean = !closed
            override fun close(code: Int, reason: String?): Boolean { closed = true; return true }
            override fun cancel() { closed = true }
        }
        fun open() = listener.onOpen(socket, Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(101).message("Synthetic upgrade").build())
        fun event(id: String, kind: Int = NostrKind.TEXT_NOTE) {
            val event = NostrEvent(id, "00".repeat(32), 1_700_000_000, kind, emptyList(), "synthetic", "00")
            listener.onMessage(socket, "[\"EVENT\",\"$ID\",${Gson().toJson(event)}]")
        }
    }

    companion object {
        private const val ID = "synthetic-registration"
        private val FILTER = NostrFilter(kinds = listOf(NostrKind.TEXT_NOTE))
    }
}
