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
import com.bitchat.android.nostr.RelayPublishOutcome
import com.bitchat.android.util.AppConstants
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
        /** What the relays answer to a confirmed publish. */
        @Volatile var answer = RelayPublishOutcome.ACCEPTED
        override fun isOn() = on
        override fun myIdentity() = identity
        override fun publish(event: NostrEvent) { published += event }
        /** Set to hold a confirmed publish in flight until completed. */
        @Volatile var hold: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        override suspend fun publishConfirmed(event: NostrEvent): RelayPublishOutcome {
            if (answer != RelayPublishOutcome.NOT_SENT) published += event
            hold?.await()
            return answer
        }
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
        val acked = mutableSetOf<String>()
        val hidden = mutableSetOf<String>()
        var now: Long? = null
        fun newInbox() = WeshInternetInbox(contacts, { nostr }, route,
            deliver = { conversationID, message -> stored += conversationID to message; true },
            updateStatus = { conversationID, id, status -> statuses += Triple(conversationID, id, status) },
            alreadyAcked = { it in acked }, markAcked = { id, _ -> acked += id },
            senderBlocked = { it.noiseHex in hidden },
            nowSeconds = { now ?: (System.currentTimeMillis() / 1000) })
        var inbox = newInbox()

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
        assertEquals(RelayPublishOutcome.ACCEPTED, alice.route.sendPrivate("salut", bobConversation, "msg-1").outcome)
        val wrap = alice.link.published.single()
        val (content, sender, _) = NostrProtocol.decryptPrivateMessage(wrap, bob.nostr)!!
        assertEquals(alice.nostr.publicKeyHex, sender)
        assertTrue(content.startsWith("bitchat1:"))
        assertEquals(WeshInternetInbox.Outcome.STORED, bob.inbox.onGiftWrap(wrap, expectedToken = null))
        val (conversation, message) = bob.stored.single()
        assertEquals(aliceConversation, conversation)
        assertEquals("msg-1", message.id)
        assertEquals("salut", message.content)
        assertTrue(message.isPrivate)
        assertEquals(com.bitchat.android.model.MessageTransport.INTERNET, message.transport)
    }

    @Test fun `the delivery ack goes back over Internet and updates only Alice's conversation`() = runTest {
        alice.route.sendPrivate("salut", bobConversation, "msg-2")
        bob.inbox.onGiftWrap(alice.link.published.single(), expectedToken = null)
        val ack = bob.link.published.single()
        assertEquals(WeshInternetInbox.Outcome.ACK_APPLIED, alice.inbox.onGiftWrap(ack, expectedToken = null))
        val (conversation, id, status) = alice.statuses.single()
        assertEquals(bobConversation, conversation)
        assertEquals("msg-2", id)
        assertTrue(status is DeliveryStatus.Delivered)
    }

    @Test fun `Tor or relays not ready fails closed and nothing is published`() = runTest {
        alice.link.on = false
        assertFalse(alice.route.canSend(bobConversation))
        assertEquals(RelayPublishOutcome.NOT_SENT, alice.route.sendPrivate("salut", bobConversation, "msg-3").outcome)
        assertFalse(alice.route.sendAck(NoisePayloadType.DELIVERED, "msg-3", bobConversation))
        assertTrue(alice.link.published.isEmpty())
    }

    @Test fun `no npub, a conflict flag, a block or no enrollment never reach Internet`() = runTest {
        val strangerConversation = ContactIdentityResolver.contactConversationIdForNoiseKey(ByteArray(32) { 9 })
        assertEquals(RelayPublishOutcome.NOT_SENT, alice.route.sendPrivate("x", strangerConversation, "stranger").outcome)
        alice.enroll(bobNoise, null)
        assertEquals(RelayPublishOutcome.NOT_SENT, alice.route.sendPrivate("x", bobConversation, "bluetooth-only").outcome)
        alice.enroll(bobNoise, bob.nostr.npub, warning = true)
        assertEquals(RelayPublishOutcome.NOT_SENT, alice.route.sendPrivate("x", bobConversation, "flagged").outcome)
        alice.enroll(bobNoise, bob.nostr.npub)
        alice.blocks.edit().putStringSet("blocked_users", setOf(ContactIdentityResolver.fingerprintHex(bobNoise))).commit()
        assertEquals(RelayPublishOutcome.NOT_SENT, alice.route.sendPrivate("x", bobConversation, "blocked").outcome)
        assertTrue(alice.link.published.isEmpty())
    }

    @Test fun `a sender whose npub is not bound to a QR contact is dropped without an ack`() = runTest {
        val stranger = Phone("stranger", NostrIdentity.generate(), "9999aaaabbbbcccc")
        stranger.enroll(bobNoise, bob.nostr.npub)
        stranger.route.sendPrivate("spam", bobConversation, "spam-1")
        assertEquals(WeshInternetInbox.Outcome.DROPPED, bob.inbox.onGiftWrap(stranger.link.published.single(), expectedToken = null))
        assertTrue(bob.stored.isEmpty())
        assertTrue(bob.link.published.isEmpty())
    }

    @Test fun `a blocked or flagged contact is dropped without an ack`() = runTest {
        alice.route.sendPrivate("one", bobConversation, "blocked-1")
        alice.route.sendPrivate("two", bobConversation, "flagged-1")
        val (first, second) = alice.link.published.toList()
        bob.blocks.edit().putStringSet("blocked_users", setOf(ContactIdentityResolver.fingerprintHex(aliceNoise))).commit()
        assertEquals(WeshInternetInbox.Outcome.DROPPED, bob.inbox.onGiftWrap(first, expectedToken = null))
        bob.blocks.edit().clear().commit()
        bob.enroll(aliceNoise, alice.nostr.npub, warning = true)
        assertEquals(WeshInternetInbox.Outcome.DROPPED, bob.inbox.onGiftWrap(second, expectedToken = null))
        assertTrue(bob.stored.isEmpty())
        assertTrue(bob.link.published.isEmpty())
    }

    @Test fun `the Bluetooth admission gate applies to Internet senders too, acks included`() = runTest {
        alice.route.sendPrivate("one", bobConversation, "hidden-1")
        alice.route.sendPrivate("two", bobConversation, "hidden-2")
        val (first, second) = alice.link.published.toList()
        assertEquals(WeshInternetInbox.Outcome.STORED, bob.inbox.onGiftWrap(first, expectedToken = null))
        val ack = bob.link.published.single()
        // Bob hides Alice: her next message is dropped with no store and no ack.
        bob.hidden += aliceNoise.joinToString("") { "%02x".format(it) }
        assertEquals(WeshInternetInbox.Outcome.DROPPED, bob.inbox.onGiftWrap(second, expectedToken = null))
        assertEquals(1, bob.stored.size)
        assertEquals(1, bob.link.published.size)
        // Alice hides Bob: his ack no longer updates anything.
        alice.hidden += bobNoise.joinToString("") { "%02x".format(it) }
        assertEquals(WeshInternetInbox.Outcome.DROPPED, alice.inbox.onGiftWrap(ack, expectedToken = null))
        assertTrue(alice.statuses.isEmpty())
    }

    @Test fun `a FAVORITED notice over Internet is never trusted`() = runTest {
        alice.route.sendPrivate("[FAVORITED]:${NostrIdentity.generate().npub}", bobConversation, "fav-1")
        assertEquals(WeshInternetInbox.Outcome.DROPPED, bob.inbox.onGiftWrap(alice.link.published.single(), expectedToken = null))
        assertTrue(bob.stored.isEmpty())
    }

    @Test fun `replays and out-of-window timestamps are dropped`() = runTest {
        alice.route.sendPrivate("one", bobConversation, "window-1")
        alice.route.sendPrivate("two", bobConversation, "window-2")
        val (first, second) = alice.link.published.toList()
        assertEquals(WeshInternetInbox.Outcome.STORED, bob.inbox.onGiftWrap(first, expectedToken = null))
        assertEquals(WeshInternetInbox.Outcome.DROPPED, bob.inbox.onGiftWrap(first, expectedToken = null))
        bob.now = System.currentTimeMillis() / 1000 + WeshInternetInbox.LOOKBACK_SECONDS + 3600
        assertEquals(WeshInternetInbox.Outcome.DROPPED, bob.inbox.onGiftWrap(second, expectedToken = null))
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
            kotlinx.coroutines.runBlocking {
                whenever(mesh.sendPrivateMessageBound(any(), any(), any(), any(), any())).thenReturn(true)
            }
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

    @Test fun `an unconfirmed send is republished as the same signed gift wrap`() = runTest {
        alice.link.answer = RelayPublishOutcome.UNCONFIRMED
        val first = alice.route.sendPrivate("salut", bobConversation, "same-1")
        assertEquals(RelayPublishOutcome.UNCONFIRMED, first.outcome)
        alice.link.answer = RelayPublishOutcome.ACCEPTED
        val second = alice.route.sendPrivate("salut", bobConversation, "same-1", previousWrap = first.giftWrap)

        assertEquals(RelayPublishOutcome.ACCEPTED, second.outcome)
        assertEquals(2, alice.link.published.size)
        assertEquals(alice.link.published[0].id, alice.link.published[1].id)
    }

    @Test fun `a wrap too old for the receivers' lookback, or for an old npub, is wrapped again`() = runTest {
        alice.link.answer = RelayPublishOutcome.UNCONFIRMED
        val wrap = alice.route.sendPrivate("salut", bobConversation, "old-1").giftWrap!!
        val stale = wrap.createdAt + AppConstants.Router.WESH_INTERNET_WRAP_REUSE_MAX_AGE_S
        alice.route.sendPrivate("salut", bobConversation, "old-1", previousWrap = wrap, nowSeconds = stale.toLong())
        val newNpub = NostrIdentity.generate()
        alice.enroll(bobNoise, newNpub.npub)
        alice.route.sendPrivate("salut", bobConversation, "old-1", previousWrap = wrap)

        assertEquals(3, alice.link.published.map { it.id }.distinct().size)
        assertNotNull(NostrProtocol.decryptPrivateMessage(alice.link.published[2], newNpub))
    }

    @Test fun `the Internet retry backs off from 30 s and is capped at 30 min`() {
        val delays = (1..10).map(AppConstants.Router::weshInternetRetryDelayMs)
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 240_000L, 480_000L, 960_000L,
            1_800_000L, 1_800_000L, 1_800_000L, 1_800_000L), delays)
    }

    @Test fun `Envoyé only after a relay OK, and failed publishes stay pending with a backoff`() {
        val mesh: MeshService = mock()
        whenever(mesh.myPeerID).thenReturn(alice.peerID)
        ContactDirectory.initialize(context) { mesh }
        WeshContactStore.installForTesting(alice.contacts)
        MessageRouter.disableSchedulerForTesting = true
        AppStateStore.clear()
        val router = MessageRouter(context, mesh, mock())
        router.weshInternetLink = alice.link
        var now = 1_000_000L
        router.clock = { now }
        fun status() = AppStateStore.privateMessages.value.values.flatten().single { it.id == "ok-1" }.deliveryStatus
        fun tickUntil(count: Int) {
            router.tickOutbox(now)
            waitFor { alice.link.published.size == count }
            Thread.sleep(100)
        }
        try {
            AppStateStore.addPrivateMessage(bobConversation, BitchatMessage(id = "ok-1", sender = "me", content = "salut",
                timestamp = java.util.Date(), isRelay = false, isPrivate = true, recipientNickname = "Bob",
                senderPeerID = alice.peerID, deliveryStatus = DeliveryStatus.Sending))
            // Explicit rejection: pending, then a fresh wrap after 30 s.
            alice.link.answer = RelayPublishOutcome.REJECTED
            assertEquals(MessageRouter.RouteResult.NOSTR, router.sendPrivate("salut", bobConversation, "Bob", "ok-1"))
            waitFor { alice.link.published.size == 1 }
            Thread.sleep(100)
            assertEquals(DeliveryStatus.Sending, status())
            now += 29_999
            router.tickOutbox(now)
            Thread.sleep(200)
            assertEquals(1, alice.link.published.size)

            // Timeout: the same signed wrap goes out again, 60 s later.
            alice.link.answer = RelayPublishOutcome.UNCONFIRMED
            now += 1
            tickUntil(2)
            assertNotEquals(alice.link.published[0].id, alice.link.published[1].id)
            now += 59_999
            router.tickOutbox(now)
            Thread.sleep(200)
            assertEquals(2, alice.link.published.size)

            // Nothing could leave (no relay connected): no backoff step is spent.
            alice.link.answer = RelayPublishOutcome.NOT_SENT
            now += 1
            router.tickOutbox(now)
            Thread.sleep(200)
            alice.link.answer = RelayPublishOutcome.ACCEPTED
            tickUntil(3)
            assertEquals(alice.link.published[1].id, alice.link.published[2].id)
            waitFor { status() == DeliveryStatus.Sent }
        } finally {
            WeshContactStore.resetForTesting()
            MessageRouter.disableSchedulerForTesting = false
            AppStateStore.clear()
        }
    }

    @Test fun `the composer sends to any enrolled contact, queued without a route, over Internet once it is on`() {
        val mesh: MeshService = mock()
        whenever(mesh.myPeerID).thenReturn(alice.peerID)
        ContactDirectory.initialize(context) { mesh }
        WeshContactStore.installForTesting(alice.contacts)
        MessageRouter.disableSchedulerForTesting = true
        AppStateStore.clear()
        val router = MessageRouter(context, mesh, mock())
        router.weshInternetLink = alice.link
        val compose = WeshPrivateCompose(WeshPrivateSender(alice.contacts)) { router }
        fun status(id: String) = AppStateStore.privateMessages.value.values.flatten().single { it.id == id }.deliveryStatus
        fun own(id: String) = AppStateStore.addPrivateMessage(bobConversation, BitchatMessage(id = id, sender = "me",
            content = "salut", timestamp = java.util.Date(), isPrivate = true, recipientNickname = "Bob",
            senderPeerID = alice.peerID, deliveryStatus = DeliveryStatus.Sending))
        try {
            // No Bluetooth session, Internet off: the contact is still writable and the message waits.
            alice.link.on = false
            assertTrue(compose.canCompose(bobConversation))
            own("c-1")
            assertEquals(MessageRouter.RouteResult.QUEUED, compose.route("salut", bobConversation, "Bob", "c-1"))
            // Réessayer on a waiting message replaces it in the queue instead of adding a copy.
            assertEquals(MessageRouter.RouteResult.QUEUED, compose.route("salut", bobConversation, "Bob", "c-1"))
            assertEquals(DeliveryStatus.Sending, status("c-1"))

            alice.link.on = true
            router.tickOutbox()
            waitFor { status("c-1") == DeliveryStatus.Sent }
            Thread.sleep(200)
            assertEquals(1, alice.link.published.size)

            // A stranger is never writable; a forced send fails visibly.
            val stranger = ContactIdentityResolver.contactConversationIdForNoiseKey(ByteArray(32) { 9 })
            assertFalse(compose.canCompose(stranger))
            own("c-2")
            alice.contacts.remove(bobConversation)
            assertEquals(MessageRouter.RouteResult.DROPPED, compose.route("salut", bobConversation, "Bob", "c-2"))
            assertTrue(status("c-2") is DeliveryStatus.Failed)
        } finally {
            WeshContactStore.resetForTesting()
            MessageRouter.disableSchedulerForTesting = false
            AppStateStore.clear()
        }
    }

    @Test fun `a conversation deleted while its Internet send is in flight never sends it again`() {
        val mesh: MeshService = mock()
        whenever(mesh.myPeerID).thenReturn(alice.peerID)
        ContactDirectory.initialize(context) { mesh }
        WeshContactStore.installForTesting(alice.contacts)
        MessageRouter.disableSchedulerForTesting = true
        AppStateStore.clear()
        val router = MessageRouter(context, mesh, mock())
        router.weshInternetLink = alice.link
        var now = 1_000_000L
        router.clock = { now }
        try {
            val hold = kotlinx.coroutines.CompletableDeferred<Unit>()
            alice.link.hold = hold
            alice.link.answer = RelayPublishOutcome.UNCONFIRMED
            AppStateStore.addPrivateMessage(bobConversation, BitchatMessage(id = "del-1", sender = "me", content = "salut",
                timestamp = java.util.Date(), isPrivate = true, recipientNickname = "Bob",
                senderPeerID = alice.peerID, deliveryStatus = DeliveryStatus.Sending))
            assertEquals(MessageRouter.RouteResult.NOSTR, router.sendPrivate("salut", bobConversation, "Bob", "del-1"))
            waitFor { alice.link.published.size == 1 }
            router.cancelWeshConversation(bobConversation)
            hold.complete(Unit)
            Thread.sleep(200)
            alice.link.hold = null
            now += AppConstants.Router.WESH_INTERNET_RETRY_MAX_MS
            router.tickOutbox(now)
            Thread.sleep(200)
            assertEquals(1, alice.link.published.size)
            // Never a false « En attente »: the cancelled attempt ends « Échec ».
            assertTrue(AppStateStore.privateMessages.value.values.flatten().single { it.id == "del-1" }.deliveryStatus
                is DeliveryStatus.Failed)
        } finally {
            WeshContactStore.resetForTesting()
            MessageRouter.disableSchedulerForTesting = false
            AppStateStore.clear()
        }
    }

    @Test fun `Réessayer on a waiting message keeps its 24 h deadline`() {
        val mesh: MeshService = mock()
        whenever(mesh.myPeerID).thenReturn(alice.peerID)
        ContactDirectory.initialize(context) { mesh }
        WeshContactStore.installForTesting(alice.contacts)
        MessageRouter.disableSchedulerForTesting = true
        val router = MessageRouter(context, mesh, mock())
        router.weshInternetLink = alice.link
        val expired = CopyOnWriteArrayList<String>()
        router.onMessageExpired = { expired += it }
        var now = 1_000_000L
        router.clock = { now }
        try {
            alice.link.on = false
            assertEquals(MessageRouter.RouteResult.QUEUED, router.sendPrivate("salut", bobConversation, "Bob", "ttl-1"))
            now += 23L * 3600_000L
            assertEquals(MessageRouter.RouteResult.QUEUED, router.sendPrivate("salut", bobConversation, "Bob", "ttl-1"))
            now += 3600_000L + 1
            router.tickOutbox(now)
            assertEquals(listOf("ttl-1"), expired)
        } finally {
            WeshContactStore.resetForTesting()
            MessageRouter.disableSchedulerForTesting = false
        }
    }

    /** Bob in Bluetooth range over a bound session whose write fails. */
    private fun failingBluetoothRouter(): MessageRouter {
        val mesh: MeshService = mock()
        whenever(mesh.myPeerID).thenReturn(alice.peerID)
        val bobPeer = ContactIdentityResolver.peerIdForNoiseKey(bobNoise)
        whenever(mesh.getPeerNicknames()).thenReturn(mapOf(bobPeer to "Bob"))
        whenever(mesh.getPeerInfo(bobPeer)).thenReturn(PeerInfo(id = bobPeer, nickname = "Bob", isConnected = true,
            noisePublicKey = bobNoise, isDirectConnection = true, signingPublicKey = ByteArray(32),
            isVerifiedNickname = true, lastSeen = 1L))
        whenever(mesh.getAuthenticatedRemoteStaticKey(bobPeer)).thenReturn(bobNoise)
        kotlinx.coroutines.runBlocking {
            whenever(mesh.sendPrivateMessageBound(any(), any(), any(), any(), any())).thenReturn(false)
        }
        ContactDirectory.initialize(context) { mesh }
        WeshContactStore.installForTesting(alice.contacts)
        MessageRouter.disableSchedulerForTesting = true
        AppStateStore.clear()
        AppStateStore.addPrivateMessage(bobConversation, BitchatMessage(id = "bw-1", sender = "me", content = "salut",
            timestamp = java.util.Date(), isPrivate = true, recipientNickname = "Bob",
            senderPeerID = alice.peerID, deliveryStatus = DeliveryStatus.Sending))
        return MessageRouter(context, mesh, mock()).also { it.weshInternetLink = alice.link }
    }

    private fun bw1() = AppStateStore.privateMessages.value.values.flatten().single { it.id == "bw-1" }.deliveryStatus

    @Test fun `a failed Bluetooth write goes over Internet when that route exists`() {
        val router = failingBluetoothRouter()
        try {
            assertEquals(MessageRouter.RouteResult.MESH, router.sendPrivate("salut", bobConversation, "Bob", "bw-1"))
            waitFor { bw1() == DeliveryStatus.Sent }
            assertEquals(1, alice.link.published.size)
        } finally {
            WeshContactStore.resetForTesting()
            MessageRouter.disableSchedulerForTesting = false
            AppStateStore.clear()
        }
    }

    @Test fun `a failed Bluetooth write without an Internet route ends Échec`() {
        val router = failingBluetoothRouter()
        alice.link.on = false
        try {
            assertEquals(MessageRouter.RouteResult.MESH, router.sendPrivate("salut", bobConversation, "Bob", "bw-1"))
            waitFor { bw1() is DeliveryStatus.Failed }
            assertTrue(alice.link.published.isEmpty())
        } finally {
            WeshContactStore.resetForTesting()
            MessageRouter.disableSchedulerForTesting = false
            AppStateStore.clear()
        }
    }

    @Test fun `a message replayed by the lookback after a restart is not acked again`() = runTest {
        alice.route.sendPrivate("salut", bobConversation, "ack-once")
        val wrap = alice.link.published.single()
        assertEquals(WeshInternetInbox.Outcome.STORED, bob.inbox.onGiftWrap(wrap, expectedToken = null))
        assertEquals(1, bob.link.published.size)
        bob.inbox = bob.newInbox() // process restart: in-memory event IDs are gone, the ack record is durable
        assertEquals(WeshInternetInbox.Outcome.STORED, bob.inbox.onGiftWrap(wrap, expectedToken = null))
        assertEquals(1, bob.link.published.size)
    }

    @Test fun `a message ID that is not UUID-like is refused before any store or ack`() = runTest {
        alice.route.sendPrivate("x", bobConversation, "evil:id")
        alice.route.sendPrivate("y", bobConversation, "a".repeat(129))
        alice.route.sendPrivate("z", bobConversation, "id-\u0430\u0431")      // Cyrillic letters
        alice.route.sendPrivate("w", bobConversation, "id-\uFF11\uFF12")      // full-width digits
        for (wrap in alice.link.published) assertEquals(WeshInternetInbox.Outcome.DROPPED, bob.inbox.onGiftWrap(wrap, expectedToken = null))
        assertTrue(bob.stored.isEmpty())
        assertTrue(bob.link.published.isEmpty())
        assertTrue(bob.acked.isEmpty())
    }

    @Test fun `files, verification, voice and peer state never arrive over Internet`() = runTest {
        for (type in listOf(NoisePayloadType.FILE_TRANSFER, NoisePayloadType.VERIFY_CHALLENGE,
                NoisePayloadType.VERIFY_RESPONSE, NoisePayloadType.VOICE_FRAME, NoisePayloadType.PEER_STATE)) {
            val wrap = NostrProtocol.createPrivateMessage(embed(type, ByteArray(48) { 7 }), bob.nostr.publicKeyHex, alice.nostr).single()
            assertEquals(type.name, WeshInternetInbox.Outcome.DROPPED, bob.inbox.onGiftWrap(wrap, expectedToken = null))
        }
        assertTrue(bob.stored.isEmpty())
        assertTrue(bob.link.published.isEmpty())
    }

    @Test fun `a seal forged in Alice's name is dropped`() = runTest {
        val attacker = NostrIdentity.generate()
        val embedded = com.bitchat.android.nostr.NostrEmbeddedBitChat.encodePMForNostr("forged", "forged-1", bobNoise.hex(), alice.peerID)!!
        val rumor = NostrEvent(pubkey = alice.nostr.publicKeyHex, createdAt = (System.currentTimeMillis() / 1000).toInt(),
            kind = com.bitchat.android.nostr.NostrKind.DIRECT_MESSAGE, tags = listOf(listOf("p", bob.nostr.publicKeyHex)), content = embedded)
            .let { it.copy(id = it.computeEventIdHex()) }
        val gson = com.google.gson.Gson()
        val sealContent = com.bitchat.android.nostr.NostrCrypto.encryptNIP44(gson.toJson(rumor), bob.nostr.publicKeyHex, attacker.privateKeyHex)
        // Claims Alice as the seal author but is signed with the attacker's key.
        val seal = NostrEvent(pubkey = alice.nostr.publicKeyHex, createdAt = rumor.createdAt,
            kind = com.bitchat.android.nostr.NostrKind.SEAL, tags = emptyList(), content = sealContent)
            .sign(attacker.privateKeyHex).copy(pubkey = alice.nostr.publicKeyHex)
        val (wrapKey, wrapPub) = com.bitchat.android.nostr.NostrCrypto.generateKeyPair()
        val wrap = NostrEvent(pubkey = wrapPub, createdAt = rumor.createdAt, kind = com.bitchat.android.nostr.NostrKind.GIFT_WRAP,
            tags = listOf(listOf("p", bob.nostr.publicKeyHex)),
            content = com.bitchat.android.nostr.NostrCrypto.encryptNIP44(gson.toJson(seal), bob.nostr.publicKeyHex, wrapKey))
            .sign(wrapKey)
        assertEquals(WeshInternetInbox.Outcome.DROPPED, bob.inbox.onGiftWrap(wrap, expectedToken = null))
        assertTrue(bob.stored.isEmpty())
        assertTrue(bob.link.published.isEmpty())
    }

    @Test fun `the subscription exists only while On with my key, and Off unsubscribes and forgets`() {
        val events = mutableListOf<String>()
        var identity: NostrIdentity? = bob.nostr
        val subscription = WeshInternetSubscription(
            myIdentity = { identity },
            subscribe = { filter, _ ->
                events += if (com.google.gson.Gson().toJson(filter).contains(bob.nostr.publicKeyHex)) "subscribe:me" else "subscribe:other"
            },
            unsubscribe = { events += "unsubscribe" },
            onEvent = {},
            onOff = { events += "forget" },
            nowMs = { 1_000_000_000L }
        )
        subscription.apply(com.bitchat.android.net.WeshInternetState.Connecting(40))
        subscription.apply(com.bitchat.android.net.WeshInternetState.Error(com.bitchat.android.net.WeshInternetError.TOR_BLOCKED))
        assertTrue(events.isEmpty())
        subscription.apply(com.bitchat.android.net.WeshInternetState.On)
        assertEquals(listOf("subscribe:me"), events)
        subscription.apply(com.bitchat.android.net.WeshInternetState.Off)
        assertEquals(listOf("subscribe:me", "unsubscribe", "forget"), events)
        // After a panic wipe there is no key: On again subscribes nothing.
        identity = null
        subscription.apply(com.bitchat.android.net.WeshInternetState.On)
        assertEquals(3, events.size)
    }

    @Test fun `my npub goes only to an enrolled contact over its bound session, and never with a favorite star`() {
        val mesh: MeshService = mock()
        whenever(mesh.myPeerID).thenReturn(alice.peerID)
        val bobPeer = ContactIdentityResolver.peerIdForNoiseKey(bobNoise)
        val strangerKey = ByteArray(32) { 9 }
        val strangerPeer = ContactIdentityResolver.peerIdForNoiseKey(strangerKey)
        whenever(mesh.getPeerNicknames()).thenReturn(mapOf(bobPeer to "Bob", strangerPeer to "Stranger"))
        for ((peer, key) in listOf(bobPeer to bobNoise, strangerPeer to strangerKey)) {
            whenever(mesh.getPeerInfo(peer)).thenReturn(PeerInfo(id = peer, nickname = "x", isConnected = true,
                noisePublicKey = key, isDirectConnection = true, signingPublicKey = ByteArray(32),
                isVerifiedNickname = true, lastSeen = 1L))
            whenever(mesh.getAuthenticatedRemoteStaticKey(peer)).thenReturn(key)
            whenever(mesh.hasEstablishedSession(peer)).thenReturn(true)
        }
        val sent = CopyOnWriteArrayList<Pair<String, ByteArray>>()
        kotlinx.coroutines.runBlocking {
            whenever(mesh.sendPrivateMessageBound(any(), any(), any(), any(), any())).thenAnswer { call ->
                sent += call.getArgument<String>(0) to call.getArgument<ByteArray>(2); true
            }
        }
        ContactDirectory.initialize(context) { mesh }
        WeshContactStore.installForTesting(alice.contacts)
        MessageRouter.disableSchedulerForTesting = true
        val router = MessageRouter(context, mesh, mock())
        var myNpub: String? = null
        router.weshMyNpub = { myNpub }
        try {
            router.announceWeshInternetKey(bobPeer) // Internet off: no npub, nothing sent
            Thread.sleep(200)
            assertTrue(sent.isEmpty())
            myNpub = alice.nostr.npub
            router.announceWeshInternetKey(strangerPeer)
            router.announceWeshInternetKey(bobPeer)
            waitFor { sent.size == 1 }
            assertEquals("[FAVORITED]:${alice.nostr.npub}", sent.single().first)
            assertTrue(sent.single().second.contentEquals(bobNoise))
            router.announceWeshInternetKey(bobPeer) // once per contact and npub
            Thread.sleep(200)
            assertEquals(1, sent.size)

            router.sendFavoriteNotification(strangerPeer, true)
            verify(mesh).sendPrivateMessage(org.mockito.kotlin.eq("[FAVORITED]:"), org.mockito.kotlin.eq(strangerPeer), any(), org.mockito.kotlin.isNull())
        } finally {
            WeshContactStore.resetForTesting()
            MessageRouter.disableSchedulerForTesting = false
        }
    }

    @Test fun `turning Internet on tells every enrolled contact in Bluetooth range my npub, once`() {
        val mesh: MeshService = mock()
        whenever(mesh.myPeerID).thenReturn(alice.peerID)
        val bobPeer = ContactIdentityResolver.peerIdForNoiseKey(bobNoise)
        val strangerKey = ByteArray(32) { 9 }
        val strangerPeer = ContactIdentityResolver.peerIdForNoiseKey(strangerKey)
        whenever(mesh.getPeerNicknames()).thenReturn(mapOf(bobPeer to "Bob", strangerPeer to "Stranger"))
        for ((peer, key) in listOf(bobPeer to bobNoise, strangerPeer to strangerKey)) {
            whenever(mesh.getPeerInfo(peer)).thenReturn(PeerInfo(id = peer, nickname = "x", isConnected = true,
                noisePublicKey = key, isDirectConnection = true, signingPublicKey = ByteArray(32),
                isVerifiedNickname = true, lastSeen = 1L))
            whenever(mesh.getAuthenticatedRemoteStaticKey(peer)).thenReturn(key)
            whenever(mesh.hasEstablishedSession(peer)).thenReturn(true)
        }
        val sent = CopyOnWriteArrayList<Pair<String, ByteArray>>()
        kotlinx.coroutines.runBlocking {
            whenever(mesh.sendPrivateMessageBound(any(), any(), any(), any(), any())).thenAnswer { call ->
                sent += call.getArgument<String>(0) to call.getArgument<ByteArray>(2); true
            }
        }
        ContactDirectory.initialize(context) { mesh }
        WeshContactStore.installForTesting(alice.contacts)
        MessageRouter.disableSchedulerForTesting = true
        val router = MessageRouter(context, mesh, mock())
        router.weshMyNpub = { alice.nostr.npub }
        try {
            router.onWeshInternetOn()
            waitFor { sent.size == 1 }
            assertEquals("[FAVORITED]:${alice.nostr.npub}", sent.single().first)
            assertTrue(sent.single().second.contentEquals(bobNoise))
            router.onWeshInternetOn()
            Thread.sleep(200)
            assertEquals(1, sent.size)
        } finally {
            WeshContactStore.resetForTesting()
            MessageRouter.disableSchedulerForTesting = false
        }
    }

    @Test fun `the receiver calls the router once per transition to On`() {
        var calls = 0
        val watcher = WeshInternetOnWatcher { calls++ }
        watcher.apply(com.bitchat.android.net.WeshInternetState.Connecting(40))
        watcher.apply(com.bitchat.android.net.WeshInternetState.On)
        watcher.apply(com.bitchat.android.net.WeshInternetState.On)
        watcher.apply(com.bitchat.android.net.WeshInternetState.Connecting(90))
        watcher.apply(com.bitchat.android.net.WeshInternetState.On)
        assertEquals(2, calls)
    }

    private fun embed(type: NoisePayloadType, data: ByteArray): String {
        val packet = com.bitchat.android.protocol.BitchatPacket(
            version = 1u, type = com.bitchat.android.protocol.MessageType.NOISE_ENCRYPTED.value,
            senderID = com.bitchat.android.mesh.MeshPacketUtils.hexStringToByteArray(alice.peerID),
            recipientID = com.bitchat.android.mesh.MeshPacketUtils.hexStringToByteArray(ContactIdentityResolver.peerIdForNoiseKey(bobNoise)),
            timestamp = System.currentTimeMillis().toULong(), payload = byteArrayOf(type.value.toByte()) + data,
            signature = null, ttl = 7u)
        return "bitchat1:" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(packet.toBinaryData()!!)
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test fun `a panic during a send keeps nothing queued`() {
        val mesh: MeshService = mock()
        whenever(mesh.myPeerID).thenReturn(alice.peerID)
        ContactDirectory.initialize(context) { mesh }
        WeshContactStore.installForTesting(alice.contacts)
        MessageRouter.disableSchedulerForTesting = true
        val router = MessageRouter(context, mesh, mock())
        router.weshInternetLink = alice.link
        alice.link.on = false
        // The clock is read after the epoch capture: a panic lands there, mid-send.
        router.clock = { router.clearAll(); 1_000L }
        try {
            assertEquals(MessageRouter.RouteResult.DROPPED, router.sendPrivate("x", bobConversation, "Bob", "panic-1"))
            router.clock = { 2_000L }
            alice.link.on = true
            router.tickOutbox(2_000L)
            Thread.sleep(200)
            assertTrue(alice.link.published.isEmpty())
        } finally {
            WeshContactStore.resetForTesting()
            MessageRouter.disableSchedulerForTesting = false
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
