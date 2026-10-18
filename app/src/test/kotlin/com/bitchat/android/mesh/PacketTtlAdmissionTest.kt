package com.bitchat.android.mesh

import android.os.Build
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class PacketTtlAdmissionTest {
    private val processors = mutableListOf<PacketProcessor>()
    private val relays = mutableListOf<PacketRelayManager>()
    private val securityManagers = mutableListOf<SecurityManager>()

    @After
    fun tearDown() {
        processors.forEach(PacketProcessor::shutdown)
        relays.forEach(PacketRelayManager::shutdown)
        securityManagers.forEach(SecurityManager::shutdown)
    }

    @Test
    fun `direct relay refuses excessive TTL for known unknown and fragment packets`() = runBlocking {
        val sent = CopyOnWriteArrayList<RoutedPacket>()
        val relay = relay(sent)
        for (type in listOf(MessageType.MESSAGE.value, MessageType.FRAGMENT.value, 0x7fu.toUByte())) {
            for (ttl in listOf(8u.toUByte(), 255u.toUByte())) {
                relay.handlePacketRelay(routed(packet(type, ttl)))
            }
        }
        assertTrue("An excessive hop count must never reach broadcast", sent.isEmpty())
    }

    @Test
    fun `source routing cannot bypass excessive TTL refusal`() = runBlocking {
        val sent = CopyOnWriteArrayList<RoutedPacket>()
        val relay = relay(sent)
        for (ttl in listOf(8u.toUByte(), 255u.toUByte())) {
            val packet = packet(MessageType.NOISE_ENCRYPTED.value, ttl).copy(
                version = 2u, route = listOf(bytes(MY_ID)), recipientID = bytes(OTHER_ID)
            )
            relay.handlePacketRelay(routed(packet))
        }
        assertTrue("An excessive hop count must never reach targeted forwarding", sent.isEmpty())
    }

    @Test
    fun `ordinary relay decrements seven and one without underflowing zero`() = runBlocking {
        val sent = CopyOnWriteArrayList<RoutedPacket>()
        val relay = relay(sent)
        for (ttl in listOf(7u.toUByte(), 1u.toUByte(), 0u.toUByte())) {
            relay.handlePacketRelay(routed(packet(MessageType.MESSAGE.value, ttl)))
        }
        assertEquals(listOf(6u.toUByte(), 0u.toUByte()), sent.map { it.packet.ttl })
    }

    @Test
    fun `excessive TTL is refused before security delivery presence or relay`() = runBlocking {
        val delegate = RecordingDelegate()
        val processor = processor(delegate)
        for (type in listOf(MessageType.MESSAGE.value, MessageType.FRAGMENT.value, MessageType.REQUEST_SYNC.value, 0x7fu.toUByte())) {
            for (ttl in listOf(8u.toUByte(), 255u.toUByte())) {
                processor.processPacket(routed(packet(type, ttl)))
            }
        }
        drain(processor, delegate)
        assertTrue("Invalid TTL must not consume security/dedup state", delegate.validated.isEmpty())
        assertTrue(delegate.handled.isEmpty())
        assertTrue(delegate.relayed.isEmpty())
        assertEquals(0, delegate.seen)
    }

    @Test
    fun `local delivery keeps zero TTL and neighbor sync while relays remain bounded`() = runBlocking {
        val delegate = RecordingDelegate()
        val processor = processor(delegate)
        for (ttl in listOf(7u.toUByte(), 1u.toUByte(), 0u.toUByte())) {
            processor.processPacket(routed(packet(MessageType.MESSAGE.value, ttl)))
        }
        processor.processPacket(routed(packet(MessageType.REQUEST_SYNC.value, 0u)))
        drain(processor, delegate)
        assertEquals(listOf(7u.toUByte(), 1u.toUByte(), 0u.toUByte(), 0u.toUByte()), delegate.handled.map { it.ttl })
        assertEquals(listOf(6u.toUByte(), 0u.toUByte()), delegate.relayed.map { it.ttl })
        assertEquals(4, delegate.seen)
    }

    @Test
    fun `an excessive TTL inside a complete fragment is not delivered or relayed`() = runBlocking {
        val inner = packet(MessageType.MESSAGE.value, 255u)
        val delegate = RecordingDelegate(fragmentResult = FragmentResult.Complete(inner))
        val processor = processor(delegate)
        processor.processPacket(routed(packet(MessageType.FRAGMENT.value, 7u)))
        drain(processor, delegate)
        assertEquals(1, delegate.fragments)
        assertTrue(delegate.handled.isEmpty())
        assertTrue(delegate.relayed.isEmpty())
        assertEquals(0, delegate.seen)
    }

    @Test
    fun `a complete fragment may still deliver a zero TTL local message`() = runBlocking {
        val inner = packet(MessageType.MESSAGE.value, 0u)
        val delegate = RecordingDelegate(fragmentResult = FragmentResult.Complete(inner))
        val processor = processor(delegate)
        processor.processPacket(routed(packet(MessageType.FRAGMENT.value, 7u)))
        drain(processor, delegate)
        assertEquals(listOf(0u.toUByte()), delegate.handled.map { it.ttl })
        assertEquals(listOf(6u.toUByte()), delegate.relayed.map { it.ttl })
    }

    @Test
    fun `wire TTL tampering preserves a real signature but cannot poison later legitimate delivery`() = runBlocking {
        val privateKey = Ed25519PrivateKeyParameters(ByteArray(32) { 0x31 }, 0)
        val publicKey = privateKey.generatePublicKey().encoded
        val encryption = object : EncryptionService(RuntimeEnvironment.getApplication()) {
            override fun initialize() = Unit
        }
        for (ttl in listOf(8u.toUByte(), 255u.toUByte())) {
            val original = packet(MessageType.MESSAGE.value, 7u)
            val preimage = original.toBinaryDataForSigning()!!
            val signer = Ed25519Signer().apply { init(true, privateKey); update(preimage, 0, preimage.size) }
            original.signature = signer.generateSignature()
            val wire = original.toBinaryData(padding = false)!!
            wire[2] = ttl.toByte()
            val tampered = BitchatPacket.fromBinaryData(wire)!!
            assertEquals(ttl, tampered.ttl)
            assertArrayEquals(preimage, tampered.toBinaryDataForSigning())
            assertTrue(encryption.verifyEd25519Signature(tampered.signature!!, tampered.toBinaryDataForSigning()!!, publicKey))

            val security = SecurityManager(encryption, MY_ID).also { securityManagers += it }
            val securityDelegate = mock<SecurityManagerDelegate>()
            whenever(securityDelegate.getPeerInfo(PEER_ID)).thenReturn(PeerInfo(
                PEER_ID, "Synthetic peer", true, true, null, publicKey, true, 0L
            ))
            security.delegate = securityDelegate
            val delegate = RecordingDelegate(validate = { security.validatePacket(it, PEER_ID) })
            val processor = processor(delegate)
            processor.processPacket(routed(tampered))
            processor.processPacket(routed(BitchatPacket.fromBinaryData(original.toBinaryData(padding = false)!!)!!))
            drain(processor, delegate)
            assertEquals(listOf(7u.toUByte()), delegate.handled.map { it.ttl })
            assertEquals(listOf(6u.toUByte()), delegate.relayed.map { it.ttl })
        }
    }

    private fun processor(delegate: RecordingDelegate) = PacketProcessor(MY_ID).also {
        it.delegate = delegate
        processors += it
    }

    private suspend fun drain(processor: PacketProcessor, delegate: RecordingDelegate) {
        processor.processPacket(routed(packet(MessageType.REQUEST_SYNC.value, 0u).copy(
            recipientID = bytes(MY_ID), payload = MARKER
        )))
        withTimeout(3_000) { delegate.barrier.await() }
    }

    private fun relay(sent: MutableList<RoutedPacket>) = PacketRelayManager(MY_ID).also {
        relays += it
        it.delegate = object : PacketRelayManagerDelegate {
            override fun getNetworkSize() = 1
            override fun getBroadcastRecipient() = SpecialRecipients.BROADCAST
            override fun broadcastPacket(routed: RoutedPacket) { sent += routed }
            override fun sendToPeer(peerID: String, routed: RoutedPacket): Boolean { sent += routed; return true }
        }
    }

    private class RecordingDelegate(
        private val fragmentResult: FragmentResult = FragmentResult.Pending,
        private val validate: (BitchatPacket) -> Boolean = { true }
    ) : PacketProcessorDelegate {
        val barrier = CompletableDeferred<Unit>()
        val validated = CopyOnWriteArrayList<BitchatPacket>()
        val handled = CopyOnWriteArrayList<BitchatPacket>()
        val relayed = CopyOnWriteArrayList<BitchatPacket>()
        var fragments = 0
        var seen = 0
        private var atBarrier = false
        override fun validatePacketSecurity(packet: BitchatPacket, peerID: String): Boolean {
            if (!packet.payload.contentEquals(MARKER)) validated += packet
            return validate(packet)
        }
        override fun updatePeerLastSeen(peerID: String) {
            if (atBarrier) barrier.complete(Unit) else seen++
        }
        override fun handleRequestSync(routed: RoutedPacket) {
            if (routed.packet.payload.contentEquals(MARKER)) atBarrier = true else handled += routed.packet
        }
        override fun getPeerNickname(peerID: String): String? = null
        override fun getNetworkSize() = 1
        override fun getBroadcastRecipient() = SpecialRecipients.BROADCAST
        override fun handleNoiseHandshake(routed: RoutedPacket) = true
        override fun handleNoiseEncrypted(routed: RoutedPacket) = true
        override suspend fun handleAnnounce(routed: RoutedPacket) = true
        override fun handleMessage(routed: RoutedPacket) { handled += routed.packet }
        override fun handleLeave(routed: RoutedPacket) = Unit
        override fun handleFragment(packet: BitchatPacket, ingressKey: String): FragmentResult { fragments++; return fragmentResult }
        override fun sendAnnouncementToPeer(peerID: String) = Unit
        override fun sendCachedMessages(peerID: String) = Unit
        override fun relayPacket(routed: RoutedPacket) { relayed += routed.packet }
        override fun sendToPeer(peerID: String, routed: RoutedPacket) = false
    }

    private fun packet(type: UByte, ttl: UByte) = BitchatPacket(
        type = type, senderID = bytes(PEER_ID), recipientID = SpecialRecipients.BROADCAST,
        timestamp = System.currentTimeMillis().toULong(), payload = "Synthetic TTL fixture".toByteArray(), ttl = ttl
    )
    private fun routed(packet: BitchatPacket) = RoutedPacket(packet, PEER_ID, ingressLinkID = "synthetic-ingress")
    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val MY_ID = "1111222233334444"
        const val PEER_ID = "aaaabbbbccccdddd"
        const val OTHER_ID = "bbbbccccddddeeee"
        val MARKER = "Synthetic queue barrier".toByteArray()
    }
}
