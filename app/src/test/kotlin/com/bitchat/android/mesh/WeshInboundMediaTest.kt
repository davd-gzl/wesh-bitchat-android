package com.bitchat.android.mesh

import android.os.Build
import com.bitchat.android.model.BitchatFilePacket
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class WeshInboundMediaTest {
    private val myPeerID = "1111222233334444"
    private val peerID = "aaaabbbbccccdddd"
    private lateinit var handler: MessageHandler
    private lateinit var delegate: MessageHandlerDelegate
    private val cache get() = RuntimeEnvironment.getApplication().cacheDir

    @Before
    fun setup() {
        File(cache, "images").deleteRecursively()
        File(cache, "files").deleteRecursively()
        handler = MessageHandler(myPeerID, RuntimeEnvironment.getApplication(),
            PublicInboundBudget(global = PublicInboundBudget.Rate(100.0, 100.0),
                perAuthenticatedSource = PublicInboundBudget.Rate(0.0, 5.0), perLink = PublicInboundBudget.Rate(100.0, 100.0),
                unauthenticated = PublicInboundBudget.Rate(0.0, 5.0)))
        handler.acceptIncomingMedia = false
        delegate = mock()
        handler.delegate = delegate
        whenever(delegate.getBroadcastRecipient()).thenReturn(SpecialRecipients.BROADCAST)
        whenever(delegate.getPeerNickname(peerID)).thenReturn("Synthetic")
        whenever(delegate.verifySignature(any(), eq(peerID))).thenReturn(true)
        whenever(delegate.getPeerInfo(peerID)).thenReturn(PeerInfo(
            id = peerID, nickname = "Synthetic", isConnected = true, isDirectConnection = true,
            noisePublicKey = ByteArray(32) { 3 }, signingPublicKey = ByteArray(32) { 4 },
            isVerifiedNickname = true, lastSeen = System.currentTimeMillis()))
    }

    private fun file() = BitchatFilePacket(fileName = "synthetic.jpg", fileSize = 3, mimeType = "image/jpeg", content = byteArrayOf(1, 2, 3))

    private fun packet(type: MessageType, recipient: ByteArray, payload: ByteArray) = BitchatPacket(
        version = 2u, type = type.value, senderID = peerID.hexToBytes(), recipientID = recipient,
        timestamp = System.currentTimeMillis().toULong(), payload = payload, signature = ByteArray(64) { 1 }, ttl = 7u)

    private fun incomingFiles(): List<File> = listOf("images/incoming", "files/incoming")
        .flatMap { File(cache, it).listFiles()?.toList().orEmpty() }

    @Test
    fun `private media is not written to storage and arrives as a placeholder`() = runBlocking {
        handler.handleMessage(RoutedPacket(packet(MessageType.FILE_TRANSFER, myPeerID.hexToBytes(), file().encode()!!), peerID, "link"))

        val captor = argumentCaptor<BitchatMessage>()
        verify(delegate).onMessageReceived(captor.capture())
        assertEquals("", captor.firstValue.content)
        assertEquals(BitchatMessageType.Image, captor.firstValue.type)
        assertTrue(incomingFiles().isEmpty())
    }

    @Test
    fun `public media is not written to storage`() = runBlocking {
        handler.handleMessage(RoutedPacket(packet(MessageType.FILE_TRANSFER, SpecialRecipients.BROADCAST, file().encode()!!), peerID, "link"))

        val captor = argumentCaptor<BitchatMessage>()
        verify(delegate).onMessageReceived(captor.capture())
        assertEquals("", captor.firstValue.content)
        assertTrue(incomingFiles().isEmpty())
    }

    @Test
    fun `public text above the V1 limit is dropped silently`() = runBlocking {
        handler.handleMessage(RoutedPacket(packet(MessageType.MESSAGE, SpecialRecipients.BROADCAST, ByteArray(2_001) { 'a'.code.toByte() }), peerID, "link"))
        verify(delegate, never()).onMessageReceived(any())
        assertEquals(1L, handler.publicBudget.dropped)

        handler.handleMessage(RoutedPacket(packet(MessageType.MESSAGE, SpecialRecipients.BROADCAST, ByteArray(2_000) { 'a'.code.toByte() }), peerID, "link"))
        verify(delegate).onMessageReceived(any())
    }

    @Test
    fun `public flood from one peer is cut at its budget`() = runBlocking {
        repeat(20) { index ->
            handler.handleMessage(RoutedPacket(packet(MessageType.MESSAGE, SpecialRecipients.BROADCAST, "hello $index".toByteArray()), peerID, "link"))
        }
        verify(delegate, times(5)).onMessageReceived(any())
        assertEquals(15L, handler.publicBudget.dropped)
    }

    @Test
    fun `Wesh drops unencrypted directed text entirely`() = runBlocking {
        handler.acceptDirectedPlaintext = false
        handler.handleMessage(RoutedPacket(packet(MessageType.MESSAGE, myPeerID.hexToBytes(), "directed".toByteArray()), peerID, "link"))
        verify(delegate, never()).onMessageReceived(any())
        assertEquals(1L, handler.publicBudget.dropped)
    }

    @Test
    fun `unauthenticated senders share one budget whatever key they announce`() = runBlocking {
        val otherPeer = "1111aaaa2222bbbb"
        val otherInfo = delegate.getPeerInfo(peerID)!!.copy(id = otherPeer)
        whenever(delegate.getPeerInfo(otherPeer)).thenReturn(otherInfo)
        whenever(delegate.getPeerNickname(otherPeer)).thenReturn("Synthetic")
        // No Noise session: an announced key earns no bucket; both peer IDs share one (5 total).
        repeat(10) { handler.handleMessage(RoutedPacket(packet(MessageType.MESSAGE, SpecialRecipients.BROADCAST, "a$it".toByteArray()), peerID, "link")) }
        repeat(10) { handler.handleMessage(RoutedPacket(packet(MessageType.MESSAGE, SpecialRecipients.BROADCAST, "b$it".toByteArray()).copy(senderID = otherPeer.hexToBytes()), otherPeer, "link")) }
        verify(delegate, times(5)).onMessageReceived(any())
    }

    @Test
    fun `distinct authenticated keys get separate budgets`() = runBlocking {
        val otherPeer = "1111aaaa2222bbbb"
        val otherInfo = delegate.getPeerInfo(peerID)!!.copy(id = otherPeer)
        whenever(delegate.getPeerInfo(otherPeer)).thenReturn(otherInfo)
        whenever(delegate.getAuthenticatedRemoteStaticKey(peerID)).thenReturn(ByteArray(32) { 7 })
        whenever(delegate.getAuthenticatedRemoteStaticKey(otherPeer)).thenReturn(ByteArray(32) { 8 })
        repeat(10) { handler.handleMessage(RoutedPacket(packet(MessageType.MESSAGE, SpecialRecipients.BROADCAST, "a$it".toByteArray()), peerID, "link")) }
        repeat(10) { handler.handleMessage(RoutedPacket(packet(MessageType.MESSAGE, SpecialRecipients.BROADCAST, "b$it".toByteArray()).copy(senderID = otherPeer.hexToBytes()), otherPeer, "link")) }
        verify(delegate, times(10)).onMessageReceived(any())
    }

    @Test
    fun `peers proven to hold one key share one budget`() = runBlocking {
        val otherPeer = "1111aaaa2222bbbb"
        val key = ByteArray(32) { 7 }
        val otherInfo = delegate.getPeerInfo(peerID)!!.copy(id = otherPeer)
        whenever(delegate.getPeerInfo(otherPeer)).thenReturn(otherInfo)
        whenever(delegate.getAuthenticatedRemoteStaticKey(peerID)).thenReturn(key)
        whenever(delegate.getAuthenticatedRemoteStaticKey(otherPeer)).thenReturn(key)
        repeat(10) { handler.handleMessage(RoutedPacket(packet(MessageType.MESSAGE, SpecialRecipients.BROADCAST, "a$it".toByteArray()), peerID, "link")) }
        repeat(10) { handler.handleMessage(RoutedPacket(packet(MessageType.MESSAGE, SpecialRecipients.BROADCAST, "b$it".toByteArray()).copy(senderID = otherPeer.hexToBytes()), otherPeer, "link")) }
        verify(delegate, times(5)).onMessageReceived(any())
    }

    @Test
    fun `signed directed plain text is delivered as private so QR admission applies`() = runBlocking {
        handler.acceptDirectedPlaintext = true
        handler.handleMessage(RoutedPacket(packet(MessageType.MESSAGE, myPeerID.hexToBytes(), "directed".toByteArray()), peerID, "link"))

        val captor = argumentCaptor<BitchatMessage>()
        verify(delegate).onMessageReceived(captor.capture())
        assertTrue(captor.firstValue.isPrivate)
        assertEquals(peerID, captor.firstValue.senderPeerID)
    }

    @Test
    fun `unsigned or oversized directed plain text is dropped`() = runBlocking {
        handler.acceptDirectedPlaintext = true
        val unsigned = packet(MessageType.MESSAGE, myPeerID.hexToBytes(), "directed".toByteArray()).copy(signature = null)
        handler.handleMessage(RoutedPacket(unsigned, peerID, "link"))
        whenever(delegate.verifySignature(any(), eq(peerID))).thenReturn(false)
        handler.handleMessage(RoutedPacket(packet(MessageType.MESSAGE, myPeerID.hexToBytes(), "forged".toByteArray()), peerID, "link"))
        whenever(delegate.verifySignature(any(), eq(peerID))).thenReturn(true)
        handler.handleMessage(RoutedPacket(packet(MessageType.MESSAGE, myPeerID.hexToBytes(), ByteArray(2_001) { 'a'.code.toByte() }), peerID, "link"))

        verify(delegate, never()).onMessageReceived(any())
    }

    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
