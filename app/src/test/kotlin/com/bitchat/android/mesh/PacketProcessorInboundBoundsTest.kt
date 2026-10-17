package com.bitchat.android.mesh

import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PacketProcessorInboundBoundsTest {
    private val processors = mutableListOf<PacketProcessor>()

    @After
    fun tearDown() {
        processors.forEach(PacketProcessor::shutdown)
    }

    @Test
    fun `public text above the V1 limit is neither delivered nor relayed`() = runBlocking {
        val delegate = Delegate()
        val processor = processor(delegate)

        processor.processPacket(publicMessage(PEER_A, PublicInboundBudget.MAX_PUBLIC_TEXT_BYTES + 1))
        processor.processPacket(publicMessage(PEER_A, PublicInboundBudget.MAX_PUBLIC_TEXT_BYTES))
        withTimeout(1_000) { while (delegate.relayed.get() == 0) delay(10) }
        delay(100)

        assertEquals(1, delegate.messages.get())
        assertEquals(1, delegate.relayed.get())
        assertEquals(1, delegate.lastSeen.get())
    }

    @Test
    fun `a reassembled fragment is never reassembled again nor relayed`() = runBlocking {
        val delegate = Delegate(fragmentResult = FragmentResult.Complete(packet(PEER_A, MessageType.FRAGMENT, ByteArray(32))))
        val processor = processor(delegate)

        processor.processPacket(RoutedPacket(packet(PEER_A, MessageType.FRAGMENT, ByteArray(32)), PEER_A, "link"))
        withTimeout(1_000) { while (delegate.fragments.get() == 0) delay(10) }
        delay(200)

        assertEquals(1, delegate.fragments.get())
        assertEquals(0, delegate.relayed.get())
    }

    @Test
    fun `a refused fragment is not relayed while a pending one is`() = runBlocking {
        val refused = Delegate(fragmentResult = FragmentResult.Refused)
        processor(refused).processPacket(RoutedPacket(packet(PEER_A, MessageType.FRAGMENT, ByteArray(32)), PEER_A, "link"))
        withTimeout(1_000) { while (refused.fragments.get() == 0) delay(10) }
        delay(200)
        assertEquals(0, refused.relayed.get())
        assertEquals(0, refused.lastSeen.get())

        val pending = Delegate(fragmentResult = FragmentResult.Pending)
        processor(pending).processPacket(RoutedPacket(packet(PEER_A, MessageType.FRAGMENT, ByteArray(32)), PEER_A, "link"))
        withTimeout(1_000) { while (pending.relayed.get() == 0) delay(10) }
        assertEquals(1, pending.relayed.get())
    }

    @Test
    fun `one link cannot queue more than its share`() = runBlocking {
        val delegate = Delegate(blockMessages = true)
        val processor = processor(delegate, perLink = 4)

        repeat(20) { index -> processor.processPacket(publicMessage(if (index % 2 == 0) PEER_A else PEER_B, 8)) }
        delegate.release()

        withTimeout(1_000) { while (delegate.messages.get() < 4) delay(10) }
        delay(100)
        assertEquals(4, delegate.messages.get())
    }

    @Test
    fun `a link claiming a contact's sender ID does not use up that contact's share`() = runBlocking {
        val delegate = Delegate(blockMessages = true)
        val processor = processor(delegate, perLink = 4)

        repeat(20) { processor.processPacket(publicMessage(PEER_A, 8, link = "other-link")) }
        processor.processPacket(publicMessage(PEER_A, 8, link = "contact-link"))
        delegate.release()

        withTimeout(1_000) { while (delegate.messages.get() < 5) delay(10) }
        delay(100)
        assertEquals(5, delegate.messages.get())
    }

    @Test
    fun `total queued packets stay bounded across links`() = runBlocking {
        val delegate = Delegate(blockMessages = true)
        val processor = processor(delegate, perLink = 4, total = 6)

        repeat(4) { processor.processPacket(publicMessage(PEER_A, 8, link = "link-a")) }
        repeat(4) { processor.processPacket(publicMessage(PEER_B, 8, link = "link-b")) }
        delegate.release()

        withTimeout(1_000) { while (delegate.messages.get() < 6) delay(10) }
        delay(100)
        assertEquals(6, delegate.messages.get())
    }

    @Test
    fun `new senders are refused while every tracked sender is busy, then accepted`() = runBlocking {
        val delegate = Delegate(blockMessages = true)
        val processor = processor(delegate, peers = 2)

        processor.processPacket(publicMessage(PEER_A, 8, link = "link-a"))
        processor.processPacket(publicMessage(PEER_B, 8, link = "link-b"))
        processor.processPacket(publicMessage(PEER_C, 8, link = "link-c"))
        delegate.release()
        withTimeout(1_000) { while (delegate.messages.get() < 2) delay(10) }
        delay(100)
        assertEquals(2, delegate.messages.get())

        processor.processPacket(publicMessage(PEER_C, 8))
        withTimeout(1_000) { while (delegate.messages.get() < 3) delay(10) }
        assertEquals(3, delegate.messages.get())
    }

    private fun processor(delegate: Delegate, perLink: Int = 64, total: Int = 1_024, peers: Int = 256) =
        PacketProcessor(
            MY_PEER_ID,
            perLinkQueueCapacity = perLink,
            maxQueuedPackets = total,
            maxPeerQueues = peers
        ).also {
            it.delegate = delegate
            processors += it
        }

    private fun publicMessage(peerID: String, size: Int, link: String = "link") = RoutedPacket(
        packet(peerID, MessageType.MESSAGE, ByteArray(size) { 'a'.code.toByte() }), peerID, null, ingressLinkID = link
    )

    private fun packet(peerID: String, type: MessageType, payload: ByteArray) = BitchatPacket(
        version = 1u, type = type.value, senderID = peerID.hexToBytes(),
        recipientID = SpecialRecipients.BROADCAST, timestamp = System.currentTimeMillis().toULong(),
        payload = payload, ttl = 7u
    )

    private class Delegate(
        private val fragmentResult: FragmentResult = FragmentResult.Pending,
        blockMessages: Boolean = false
    ) : PacketProcessorDelegate {
        private val gate = CountDownLatch(if (blockMessages) 1 else 0)
        val messages = AtomicInteger()
        val fragments = AtomicInteger()
        val relayed = AtomicInteger()
        val lastSeen = AtomicInteger()

        fun release() = gate.countDown()

        override fun validatePacketSecurity(packet: BitchatPacket, peerID: String) = true
        override fun updatePeerLastSeen(peerID: String) { lastSeen.incrementAndGet() }
        override fun getPeerNickname(peerID: String): String? = null
        override fun getNetworkSize() = 1
        override fun getBroadcastRecipient(): ByteArray = SpecialRecipients.BROADCAST
        override fun handleNoiseHandshake(routed: RoutedPacket) = true
        override fun handleNoiseEncrypted(routed: RoutedPacket) = true
        override suspend fun handleAnnounce(routed: RoutedPacket) = true
        override fun handleMessage(routed: RoutedPacket) {
            gate.await(5, TimeUnit.SECONDS)
            messages.incrementAndGet()
        }
        override fun handleLeave(routed: RoutedPacket) = Unit
        override fun handleFragment(packet: BitchatPacket): FragmentResult {
            fragments.incrementAndGet()
            return fragmentResult
        }
        override fun handleRequestSync(routed: RoutedPacket) = Unit
        override fun sendAnnouncementToPeer(peerID: String) = Unit
        override fun sendCachedMessages(peerID: String) = Unit
        override fun relayPacket(routed: RoutedPacket) { relayed.incrementAndGet() }
        override fun sendToPeer(peerID: String, routed: RoutedPacket) = false
    }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val MY_PEER_ID = "1111222233334444"
        const val PEER_A = "aaaabbbbccccdddd"
        const val PEER_B = "bbbbccccddddeeee"
        const val PEER_C = "ccccddddeeeeffff"
    }
}
