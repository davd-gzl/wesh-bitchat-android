package com.bitchat.android.services

import android.app.Application
import android.content.Context
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.mesh.PeerInfo
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.model.NoisePayloadType
import com.bitchat.android.nostr.NostrEvent
import com.bitchat.android.nostr.NostrIdentity
import com.bitchat.android.nostr.NostrProtocol
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/** Internet route to QR contacts: Tor-gated, npub from the QR only, BitChat envelope format. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class WeshInternetRouteTest {
    private class FakeLink(var on: Boolean, val identity: NostrIdentity?) : WeshInternetLink {
        val published = CopyOnWriteArrayList<NostrEvent>()
        override fun isOn() = on
        override fun myIdentity() = identity
        override fun publish(event: NostrEvent) { published += event }
    }

    /** One phone: its contact list, its Nostr key, its relay link, what it stored. */
    private inner class Phone(name: String, val nostr: NostrIdentity, val peerID: String) {
        val blocks: android.content.SharedPreferences =
            context.getSharedPreferences("synthetic-blocks-$name", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        val contacts = WeshContactStore(
            SecureIdentityStateManager(context.getSharedPreferences("synthetic-id-$name", Context.MODE_PRIVATE)
                .also { it.edit().clear().commit() }, true),
            blocks
        )
        val link = FakeLink(on = true, identity = nostr)
        val route = WeshInternetRoute(contacts, link) { peerID }
        val stored = mutableListOf<Pair<String, BitchatMessage>>()
        val statuses = mutableListOf<Triple<String, String, DeliveryStatus>>()
        var now: Long? = null
        val inbox = WeshInternetInbox(contacts, { nostr }, route,
            deliver = { conversationID, message -> stored += conversationID to message; true },
            updateStatus = { conversationID, id, status -> statuses += Triple(conversationID, id, status) },
            nowSeconds = { now ?: (System.currentTimeMillis() / 1000) })

        fun enroll(noiseKey: ByteArray, npub: String?, warning: Boolean = false) {
            contacts.record(noiseKey, "Synthetic", WeshNpubPolicy.Enrollment(npub, npub != null, warning))
        }
    }

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val aliceNoise = ByteArray(32) { 1 }
    private val bobNoise = ByteArray(32) { 2 }
    private val aliceConversation = ContactIdentityResolver.contactConversationIdForNoiseKey(aliceNoise)
    private val bobConversation = ContactIdentityResolver.contactConversationIdForNoiseKey(bobNoise)
    private lateinit var alice: Phone
    private lateinit var bob: Phone

    @Before fun setup() {
        alice = Phone("alice", NostrIdentity.generate(), "1111222233334444")
        bob = Phone("bob", NostrIdentity.generate(), "5555666677778888")
        alice.enroll(bobNoise, bob.nostr.npub)
        bob.enroll(aliceNoise, alice.nostr.npub)
    }

    @Test fun `a QR contact with a bound npub gets a BitChat private envelope Bob can open`() = runTest {
        assertTrue(alice.route.canSend(bobConversation))
        assertTrue(alice.route.sendPrivate("salut", bobConversation, "msg-1"))
        val wrap = alice.link.published.single()
        val (content, sender, _) = NostrProtocol.decryptPrivateMessage(wrap, bob.nostr)!!
        assertEquals(alice.nostr.publicKeyHex, sender)
        assertTrue(content.startsWith("bitchat1:"))
        assertEquals(WeshInternetInbox.Outcome.STORED, bob.inbox.onGiftWrap(wrap))
        val (conversation, message) = bob.stored.single()
        assertEquals(aliceConversation, conversation)
        assertEquals("msg-1", message.id)
        assertEquals("salut", message.content)
        assertTrue(message.isPrivate)
    }

    @Test fun `the delivery ack goes back over Internet and updates only Alice's conversation`() = runTest {
        alice.route.sendPrivate("salut", bobConversation, "msg-2")
        bob.inbox.onGiftWrap(alice.link.published.single())
        val ack = bob.link.published.single()
        assertEquals(WeshInternetInbox.Outcome.ACK_APPLIED, alice.inbox.onGiftWrap(ack))
        val (conversation, id, status) = alice.statuses.single()
        assertEquals(bobConversation, conversation)
        assertEquals("msg-2", id)
        assertTrue(status is DeliveryStatus.Delivered)
    }

    @Test fun `Tor or relays not ready fails closed and nothing is published`() = runTest {
        alice.link.on = false
        assertFalse(alice.route.canSend(bobConversation))
        assertFalse(alice.route.sendPrivate("salut", bobConversation, "msg-3"))
        assertFalse(alice.route.sendAck(NoisePayloadType.DELIVERED, "msg-3", bobConversation))
        assertTrue(alice.link.published.isEmpty())
    }

    @Test fun `no npub, a conflict flag, a block or no enrollment never reach Internet`() = runTest {
        val strangerConversation = ContactIdentityResolver.contactConversationIdForNoiseKey(ByteArray(32) { 9 })
        assertFalse(alice.route.sendPrivate("x", strangerConversation, "stranger"))
        alice.enroll(bobNoise, null)
        assertFalse(alice.route.sendPrivate("x", bobConversation, "bluetooth-only"))
        alice.enroll(bobNoise, bob.nostr.npub, warning = true)
        assertFalse(alice.route.sendPrivate("x", bobConversation, "flagged"))
        alice.enroll(bobNoise, bob.nostr.npub)
        alice.blocks.edit().putStringSet("blocked_users", setOf(ContactIdentityResolver.fingerprintHex(bobNoise))).commit()
        assertFalse(alice.route.sendPrivate("x", bobConversation, "blocked"))
        assertTrue(alice.link.published.isEmpty())
    }

    @Test fun `a sender whose npub is not bound to a QR contact is dropped without an ack`() = runTest {
        val stranger = Phone("stranger", NostrIdentity.generate(), "9999aaaabbbbcccc")
        stranger.enroll(bobNoise, bob.nostr.npub)
        stranger.route.sendPrivate("spam", bobConversation, "spam-1")
        assertEquals(WeshInternetInbox.Outcome.DROPPED, bob.inbox.onGiftWrap(stranger.link.published.single()))
        assertTrue(bob.stored.isEmpty())
        assertTrue(bob.link.published.isEmpty())
    }

    @Test fun `a blocked or flagged contact is dropped without an ack`() = runTest {
        alice.route.sendPrivate("one", bobConversation, "blocked-1")
        alice.route.sendPrivate("two", bobConversation, "flagged-1")
        val (first, second) = alice.link.published.toList()
        bob.blocks.edit().putStringSet("blocked_users", setOf(ContactIdentityResolver.fingerprintHex(aliceNoise))).commit()
        assertEquals(WeshInternetInbox.Outcome.DROPPED, bob.inbox.onGiftWrap(first))
        bob.blocks.edit().clear().commit()
        bob.enroll(aliceNoise, alice.nostr.npub, warning = true)
        assertEquals(WeshInternetInbox.Outcome.DROPPED, bob.inbox.onGiftWrap(second))
        assertTrue(bob.stored.isEmpty())
        assertTrue(bob.link.published.isEmpty())
    }

    @Test fun `a FAVORITED notice over Internet is never trusted`() = runTest {
        alice.route.sendPrivate("[FAVORITED]:${NostrIdentity.generate().npub}", bobConversation, "fav-1")
        assertEquals(WeshInternetInbox.Outcome.DROPPED, bob.inbox.onGiftWrap(alice.link.published.single()))
        assertTrue(bob.stored.isEmpty())
    }

    @Test fun `replays and out-of-window timestamps are dropped`() = runTest {
        alice.route.sendPrivate("one", bobConversation, "window-1")
        alice.route.sendPrivate("two", bobConversation, "window-2")
        val (first, second) = alice.link.published.toList()
        assertEquals(WeshInternetInbox.Outcome.STORED, bob.inbox.onGiftWrap(first))
        assertEquals(WeshInternetInbox.Outcome.DROPPED, bob.inbox.onGiftWrap(first))
        bob.now = System.currentTimeMillis() / 1000 + WeshInternetInbox.LOOKBACK_SECONDS + 3600
        assertEquals(WeshInternetInbox.Outcome.DROPPED, bob.inbox.onGiftWrap(second))
        assertEquals(1, bob.stored.size)
    }

    @Test fun `router prefers the live Bluetooth session, then Internet, else queues`() {
        val mesh: MeshService = mock()
        whenever(mesh.myPeerID).thenReturn(alice.peerID)
        ContactDirectory.initialize(context) { mesh }
        WeshContactStore.installForTesting(alice.contacts)
        MessageRouter.disableSchedulerForTesting = true
        AppStateStore.clear()
        val router = MessageRouter(context, mesh, mock())
        router.weshInternetLink = alice.link
        try {
            alice.link.on = false
            assertEquals(MessageRouter.RouteResult.QUEUED, router.sendPrivate("queued", bobConversation, "Bob", "q-1"))
            router.tickOutbox()
            Thread.sleep(200)
            assertTrue(alice.link.published.isEmpty())

            alice.link.on = true
            router.tickOutbox()
            waitFor { alice.link.published.size == 1 }
            assertEquals(MessageRouter.RouteResult.NOSTR, router.sendPrivate("direct", bobConversation, "Bob", "n-1"))
            waitFor { alice.link.published.size == 2 }
            verify(mesh, never()).sendPrivateMessage(any(), any(), any(), any())

            val stranger = ContactIdentityResolver.contactConversationIdForNoiseKey(ByteArray(32) { 9 })
            assertEquals(MessageRouter.RouteResult.DROPPED, router.sendPrivate("x", stranger, "Stranger", "d-1"))

            // Bob comes into Bluetooth range with the enrolled key: the mesh wins, Internet is untouched.
            val bobPeer = ContactIdentityResolver.peerIdForNoiseKey(bobNoise)
            whenever(mesh.getPeerNicknames()).thenReturn(mapOf(bobPeer to "Bob"))
            whenever(mesh.getPeerInfo(bobPeer)).thenReturn(PeerInfo(id = bobPeer, nickname = "Bob", isConnected = true,
                noisePublicKey = bobNoise, isDirectConnection = true, signingPublicKey = ByteArray(32),
                isVerifiedNickname = true, lastSeen = 1L))
            whenever(mesh.getAuthenticatedRemoteStaticKey(bobPeer)).thenReturn(bobNoise)
            assertEquals(MessageRouter.RouteResult.MESH, router.sendPrivate("near", bobConversation, "Bob", "m-1"))
            Thread.sleep(200)
            assertEquals(2, alice.link.published.size)
        } finally {
            router.stopOutboxScheduler()
            WeshContactStore.resetForTesting()
            MessageRouter.disableSchedulerForTesting = false
            AppStateStore.clear()
        }
    }

    @After fun cleanup() {
        WeshContactStore.resetForTesting()
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(condition())
    }
}
