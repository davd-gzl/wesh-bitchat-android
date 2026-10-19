package com.bitchat.android.sync

import android.app.Application
import android.os.Build
import com.bitchat.android.model.RequestSyncPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.service.HolderManagerProvenance
import com.bitchat.android.service.MeshServiceHolderOwner
import com.bitchat.android.service.MeshServiceHolderPorts
import com.bitchat.android.util.AppConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual manager, owner-issued registrations and canonical codec; no platform transport or keys. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class GossipSigningRefusalTest {
    @Test fun `broadcast refuses a null signer result`() = runTest {
        withFixture { f ->
            var signs = 0
            f.bind(f.owner("A")) { signs++; null }
            f.manager.sendRequestSync()
            assertEquals(1, signs)
            assertTrue("Refusal must not become an unsigned broadcast", f.sent.isEmpty())
        }
    }

    @Test fun `targeted request refuses a null signer result`() = runTest {
        withFixture { f ->
            var signs = 0
            f.bind(f.owner("A")) { signs++; null }
            f.manager.sendRequestSyncToPeer(PEER)
            assertEquals(1, signs)
            assertTrue("Refusal must not become an unsigned targeted send", f.sent.isEmpty())
        }
    }

    @Test fun `actual codec helper refuses a null byte signature`() {
        val original = packet()
        val routed = original.copy(version = 2u, route = listOf(ByteArray(8) { 7 }))
        var routes = 0
        var signs = 0
        val result = signGossipPacket(original, { assertSame(original, it); routes++; routed }) {
            signs++
            assertArrayEquals(routed.toBinaryDataForSigning(), it)
            null
        }
        assertEquals(1, routes)
        assertEquals(1, signs)
        assertNull("Missing byte signature must not return the routed unsigned packet", result)
    }

    @Test fun `actual codec helper refuses an ordinary byte signer exception`() {
        val failure = IllegalStateException("Synthetic signing failure")
        var signs = 0
        val result = signGossipPacket(packet(), { it }) { signs++; throw failure }
        assertEquals(1, signs)
        assertNull("Ordinary signing failure must not return an unsigned packet", result)
    }

    @Test fun `actual codec rejection refuses without invoking the byte signer`() {
        val oversized = packet().copy(version = 1u, payload = ByteArray(65_536))
        assertNull("The real v1 codec rejects original size even when compression helps", oversized.toBinaryDataForSigning())
        var signs = 0
        val result = signGossipPacket(oversized, { it }) { signs++; SIGNATURE }
        assertEquals(0, signs)
        assertNull("Canonical encoding failure must refuse", result)
    }

    @Test fun `valid broadcast signs routed canonical bytes through its actual bound owner`() = runTest {
        withFixture { f -> assertValidRequest(f, targeted = false) }
    }

    @Test fun `valid targeted request signs routed canonical bytes through its actual bound owner`() = runTest {
        withFixture { f -> assertValidRequest(f, targeted = true) }
    }

    @Test fun `both request families with no delegate have no substitute dispatch`() = runTest {
        withFixture { f ->
            assertNull(f.manager.delegate)
            f.manager.sendRequestSync()
            f.manager.sendRequestSyncToPeer(PEER)
            assertTrue(f.sent.isEmpty())
        }
    }

    @Test fun `both manager boundaries refuse ordinary signer exceptions without dispatch`() = runTest {
        withFixture { f ->
            val failure = IllegalStateException("Synthetic bound signing failure")
            var signs = 0
            f.bind(f.owner("A")) { signs++; throw failure }
            // Catch the known baseline exception so the causal result is an assertion, not a fixture error.
            val broadcast = runCatching { f.manager.sendRequestSync() }.exceptionOrNull()
            val targeted = runCatching { f.manager.sendRequestSyncToPeer(PEER) }.exceptionOrNull()
            assertEquals(2, signs)
            assertTrue(f.sent.isEmpty())
            assertNull("Broadcast boundary must refuse an ordinary signer failure", broadcast)
            assertNull("Targeted boundary must refuse an ordinary signer failure", targeted)
        }
    }

    @Test fun `both manager boundaries propagate the original cancellation without dispatch`() = runTest {
        withFixture { f ->
            val failure = CancellationException("Synthetic signing cancellation")
            var signs = 0
            f.bind(f.owner("A")) { signs++; throw failure }
            val broadcast = runCatching { f.manager.sendRequestSync() }.exceptionOrNull()
            val targeted = runCatching { f.manager.sendRequestSyncToPeer(PEER) }.exceptionOrNull()
            assertEquals(2, signs)
            assertSame(failure, broadcast)
            assertSame(failure, targeted)
            assertTrue(f.sent.isEmpty())
        }
    }

    @Test fun `scoped byte signer cancellation is not converted into unsigned success`() {
        val failure = CancellationException("Synthetic byte signing cancellation")
        var signs = 0
        val result = runCatching { signGossipPacket(packet(), { it }) { signs++; throw failure } }
        assertEquals(1, signs)
        assertSame(failure, result.exceptionOrNull())
    }

    @Test fun `broadcast uses the same owner delegate that signed despite replacement during signing`() = runTest {
        withFixture { f -> assertBoundDispatch(f, targeted = false) }
    }

    @Test fun `targeted send uses the same owner delegate that signed despite replacement during signing`() = runTest {
        withFixture { f -> assertBoundDispatch(f, targeted = true) }
    }

    @Test fun `stored remote messages and announcements keep author signatures without local resigning`() = runTest {
        withFixture { f ->
            var signs = 0
            f.bind(f.owner("A")) { signs++; error("Remote history must not use the local signer") }
            val message = packet().copy(signature = ByteArray(64) { 3 }, ttl = 6u)
            val announce = packet().copy(type = MessageType.ANNOUNCE.value,
                senderID = ByteArray(8) { 9 }, timestamp = System.currentTimeMillis().toULong(),
                signature = ByteArray(64) { 5 }, ttl = 7u)
            f.manager.onPublicPacketSeen(message)
            f.manager.onPublicPacketSeen(announce)
            f.manager.handleRequestSync(PEER, RequestSyncPacket(p = 1, m = 1L, data = byteArrayOf()))
            assertEquals(0, signs)
            assertEquals(2, f.sent.size)
            listOf(message, announce).forEach { original ->
                val sent = f.sent.single { it.packet.type == original.type }
                assertEquals("A", sent.owner)
                assertEquals(PEER, sent.peer)
                assertSame(original.signature, sent.packet.signature)
                assertSame(original.senderID, sent.packet.senderID)
                assertSame(original.payload, sent.packet.payload)
                assertSame(original.recipientID, sent.packet.recipientID)
                assertEquals(original.timestamp, sent.packet.timestamp)
                assertEquals(AppConstants.SYNC_TTL_HOPS, sent.packet.ttl)
            }
        }
    }

    @Test fun `direct installed delegate preserves the exact ordinary signer exception`() = runTest {
        withFixture { f ->
            val failure = IllegalStateException("Synthetic direct signer failure")
            f.bind(f.owner("A")) { throw failure }
            assertSame(failure, runCatching { requireNotNull(f.manager.delegate).signPacketForBroadcast(packet()) }.exceptionOrNull())
            assertTrue(f.sent.isEmpty())
        }
    }

    @Test fun `send exceptions do not cause fallback retry or a different target`() = runTest {
        withFixture { f ->
            val failure = IllegalStateException("Synthetic dispatch failure")
            var signs = 0
            f.bind(f.owner("A")) { signs++; it.copy(signature = SIGNATURE) }
            f.onSend = { throw failure }
            val broadcast = runCatching { f.manager.sendRequestSync() }.exceptionOrNull()
            val targeted = runCatching { f.manager.sendRequestSyncToPeer(PEER) }.exceptionOrNull()
            assertSame(failure, broadcast)
            assertSame(failure, targeted)
            assertEquals(2, signs)
            assertEquals(2, f.sent.size)
            assertNull(f.sent[0].peer)
            assertEquals(PEER, f.sent[1].peer)
        }
    }

    private fun assertValidRequest(f: Fixture, targeted: Boolean) {
        var original: BitchatPacket? = null
        var routed: BitchatPacket? = null
        var preimage: ByteArray? = null
        var signs = 0
        f.bind(f.owner("A")) { packet ->
            original = packet
            signGossipPacket(packet, {
                it.copy(version = 2u, route = listOf(ByteArray(8) { 7 })).also { route -> routed = route }
            }) { canonical -> signs++; preimage = canonical; SIGNATURE }
        }
        if (targeted) f.manager.sendRequestSyncToPeer(PEER) else f.manager.sendRequestSync()
        assertEquals(1, signs)
        assertEquals(1, f.sent.size)
        val sent = f.sent.single()
        val request = requireNotNull(original)
        val route = requireNotNull(routed)
        assertEquals("A", sent.owner)
        assertEquals(if (targeted) PEER else null, sent.peer)
        assertEquals(MessageType.REQUEST_SYNC.value, request.type)
        assertNotNull(RequestSyncPacket.decode(request.payload))
        assertArrayEquals(route.toBinaryDataForSigning(), preimage)
        assertSame(SIGNATURE, sent.packet.signature)
        assertSame(request.payload, sent.packet.payload)
        assertSame(request.senderID, sent.packet.senderID)
        assertSame(request.recipientID, sent.packet.recipientID)
        assertSame(route.route, sent.packet.route)
        assertEquals(route.version, sent.packet.version)
        assertEquals(request.timestamp, sent.packet.timestamp)
        assertEquals(request.ttl, sent.packet.ttl)
    }

    private fun assertBoundDispatch(f: Fixture, targeted: Boolean) {
        val first = f.owner("A")
        val replacement = f.owner("B")
        var firstSigns = 0
        var secondSigns = 0
        f.bind(first) {
            firstSigns++
            f.bind(replacement) { packet -> secondSigns++; packet.copy(signature = ByteArray(64) { 8 }) }
            it.copy(signature = SIGNATURE)
        }
        val saved = requireNotNull(f.manager.delegate)
        if (targeted) f.manager.sendRequestSyncToPeer(PEER) else f.manager.sendRequestSync()
        assertEquals(1, firstSigns)
        assertEquals(0, secondSigns)
        assertNotSame(saved, f.manager.delegate)
        assertEquals(1, first.provenanceSnapshot().registrations.size)
        assertEquals(1, replacement.provenanceSnapshot().registrations.size)
        assertEquals(1, f.sent.size)
        assertSame(SIGNATURE, f.sent.single().packet.signature)
        assertEquals(if (targeted) PEER else null, f.sent.single().peer)
        assertEquals("Sign and send must use one captured registration", "A", f.sent.single().owner)
    }

    private data class Sent(val owner: String, val peer: String?, val packet: BitchatPacket)

    private class Fixture(scope: TestScope) {
        val job = SupervisorJob()
        private val ownedScope = CoroutineScope(job + StandardTestDispatcher(scope.testScheduler))
        val manager = GossipSyncManager("1122334455667788", ownedScope, object : GossipSyncManager.ConfigProvider {
            override fun seenCapacity() = 8
            override fun gcsMaxBytes() = 128
            override fun gcsTargetFpr() = 0.01
        })
        val sent = mutableListOf<Sent>()
        var onSend: (() -> Unit)? = null
        fun owner(label: String) = MeshServiceHolderOwner(MeshServiceHolderPorts<Any, Any, GossipSyncManager>(
            isReusable = { true }, stopBluetooth = {}, refreshUnified = {},
            startGossip = { error("Signing fixture must never start jobs") }, stopGossip = { it.stop() }, clearGossip = { it.clear() },
            installGossipDelegate = { actual, delegate -> actual.delegate = delegate },
            broadcast = { sent += Sent(label, null, it); onSend?.invoke() },
            sendToPeer = { peer, packet -> sent += Sent(label, peer, packet); onSend?.invoke() },
            log = { _, _ -> }
        ))
        fun bind(owner: MeshServiceHolderOwner<Any, Any, GossipSyncManager>, signer: (BitchatPacket) -> BitchatPacket?) {
            owner.getOrCreate { context ->
                owner.setGossipManager(HolderManagerProvenance.Constructed(context), manager, signer)
                Any()
            }
        }
        suspend fun close() = withContext(NonCancellable) {
            manager.stop()
            withTimeout(5_000) { job.cancelAndJoin() }
            manager.delegate = null
        }
    }

    private suspend fun TestScope.withFixture(block: suspend (Fixture) -> Unit) {
        val f = Fixture(this)
        var primary: Throwable? = null
        try { block(f) } catch (failure: Throwable) { primary = failure }
        finally {
            try { f.close() } catch (failure: Throwable) {
                val original = primary
                if (original == null) primary = failure else if (original !== failure) original.addSuppressed(failure)
            }
        }
        primary?.let { throw it }
    }

    private fun packet() = BitchatPacket(type = MessageType.MESSAGE.value,
        senderID = ByteArray(8) { 1 }, timestamp = 1234uL, payload = byteArrayOf(11, 17), ttl = 3u)

    private companion object {
        const val PEER = "8877665544332211"
        val SIGNATURE = ByteArray(64) { 13 }
    }
}
