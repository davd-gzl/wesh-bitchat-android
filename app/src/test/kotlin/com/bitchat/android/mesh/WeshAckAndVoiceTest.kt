package com.bitchat.android.mesh

import android.os.Build
import com.bitchat.android.features.voice.LiveVoiceManager
import com.bitchat.android.features.voice.LiveVoicePreferences
import com.bitchat.android.features.voice.VoiceBurstCodec
import com.bitchat.android.features.voice.VoiceBurstPacket
import com.bitchat.android.model.BitchatFilePacket
import com.bitchat.android.model.NoisePayload
import com.bitchat.android.model.NoisePayloadType
import com.bitchat.android.model.PrivateMessagePacket
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.noise.AuthenticatedNoiseSession
import com.bitchat.android.noise.NoiseDecryptionResult
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class WeshAckAndVoiceTest {
    private val myPeerID = "1111222233334444"
    private val peerID = "aaaabbbbccccdddd"
    private val session = AuthenticatedNoiseSession(ByteArray(32) { 0x0B }, ByteArray(32) { 0x5D })
    private lateinit var handler: MessageHandler
    private lateinit var delegate: MessageHandlerDelegate

    @Before
    fun setup() {
        handler = MessageHandler(myPeerID, RuntimeEnvironment.getApplication())
        handler.acceptIncomingMedia = false
        delegate = mock()
        handler.delegate = delegate
        whenever(delegate.getBroadcastRecipient()).thenReturn(SpecialRecipients.BROADCAST)
        whenever(delegate.getPeerNickname(peerID)).thenReturn("Synthetic")
        whenever(delegate.getMyNickname()).thenReturn("Me")
        whenever(delegate.encryptForPeer(any(), eq(peerID))).thenReturn(byteArrayOf(0x55))
    }

    @After
    fun tearDown() {
        LiveVoicePreferences.forceDisabled = false
        LiveVoiceManager.getInstance(RuntimeEnvironment.getApplication()).reset()
    }

    private fun encrypted(plaintext: ByteArray): RoutedPacket {
        val ciphertext = byteArrayOf(0x41, 0x42)
        whenever(delegate.decryptFromPeer(any(), eq(peerID))).thenReturn(NoiseDecryptionResult(plaintext, session))
        return RoutedPacket(BitchatPacket(
            version = 2u, type = MessageType.NOISE_ENCRYPTED.value, senderID = peerID.hexToBytes(),
            recipientID = myPeerID.hexToBytes(), timestamp = System.currentTimeMillis().toULong(),
            payload = ciphertext, signature = ByteArray(64) { 1 }, ttl = 7u), peerID, "link")
    }

    private fun privateText() = NoisePayload(NoisePayloadType.PRIVATE_MESSAGE,
        PrivateMessagePacket("SYNTHETIC-ID", "Synthetic secret").encode()!!).encode()

    @Test
    fun `no delivery ACK is sent for a message admission refused`() = runBlocking<Unit> {
        whenever(delegate.onPrivateMessageReceived(any())).thenReturn(false)
        assertTrue(handler.handleNoiseEncrypted(encrypted(privateText())))
        verify(delegate).onPrivateMessageReceived(any())
        verify(delegate, never()).encryptForPeer(any(), any())
        verify(delegate, never()).sendPacket(any())
    }

    @Test
    fun `a sender without a known nickname is left blank, never a placeholder word`() = runBlocking<Unit> {
        whenever(delegate.getPeerNickname(peerID)).thenReturn(null)
        whenever(delegate.onPrivateMessageReceived(any())).thenReturn(false)
        handler.handleNoiseEncrypted(encrypted(privateText()))
        val captured = org.mockito.kotlin.argumentCaptor<com.bitchat.android.model.BitchatMessage>()
        verify(delegate).onPrivateMessageReceived(captured.capture())
        assertTrue(captured.firstValue.sender.isEmpty())
    }

    @Test
    fun `a delegate that does not opt in never acknowledges`() = runBlocking<Unit> {
        // Real interface default, as for any transport that does not run admission itself.
        org.mockito.kotlin.doCallRealMethod().whenever(delegate).onPrivateMessageReceived(any())
        handler.handleNoiseEncrypted(encrypted(privateText()))
        verify(delegate).onMessageReceived(any())
        verify(delegate, never()).encryptForPeer(any(), any())
        verify(delegate, never()).sendPacket(any())
    }

    @Test
    fun `a delivery ACK follows an admitted message`() = runBlocking<Unit> {
        whenever(delegate.onPrivateMessageReceived(any())).thenReturn(true)
        handler.handleNoiseEncrypted(encrypted(privateText()))
        verify(delegate).encryptForPeer(any(), eq(peerID))
        verify(delegate).sendPacket(any())
    }

    private fun voiceFrame(burstID: ByteArray, sequence: Int, kind: VoiceBurstPacket.Kind) =
        NoisePayload(NoisePayloadType.VOICE_FRAME, VoiceBurstPacket.create(burstID, sequence, kind)!!.encode()).encode()

    /** Streams a one-packet live burst, then sends its finalized note, which the live row absorbs. */
    private suspend fun streamThenSendFinalizedVoiceNote() {
        handler.acceptIncomingMedia = true
        LiveVoicePreferences.setEnabled(RuntimeEnvironment.getApplication(), true)
        val burstID = ByteArray(VoiceBurstPacket.BURST_ID_SIZE) { 0x2A }
        handler.handleNoiseEncrypted(encrypted(voiceFrame(burstID, 0, VoiceBurstPacket.Kind.Start(VoiceBurstCodec.AAC_LC_16K_MONO))))
        handler.handleNoiseEncrypted(encrypted(voiceFrame(burstID, 1, VoiceBurstPacket.Kind.Frames(listOf(ByteArray(20) { 5 })))))
        handler.handleNoiseEncrypted(encrypted(voiceFrame(burstID, 2, VoiceBurstPacket.Kind.End(1, 500))))
        val note = BitchatFilePacket("voice_${VoiceBurstPacket.burstIDHex(burstID)}.m4a", 3, "audio/mp4", byteArrayOf(1, 2, 3))
        handler.handleNoiseEncrypted(encrypted(NoisePayload(NoisePayloadType.FILE_TRANSFER, note.encode()!!).encode()))
    }

    @Test
    fun `an absorbed voice note from a sender admission refuses is never acknowledged`() = runBlocking<Unit> {
        whenever(delegate.onPrivateMessageReceived(any())).thenReturn(false)
        streamThenSendFinalizedVoiceNote()
        // Absorbed into its live row: admission was never asked, so nothing may vouch for delivery.
        verify(delegate, never()).onPrivateMessageReceived(any())
        verify(delegate, never()).encryptForPeer(any(), any())
        verify(delegate, never()).sendPacket(any())
    }

    @Test
    fun `an absorbed voice note from an admitted sender is not acknowledged either`() = runBlocking<Unit> {
        whenever(delegate.onPrivateMessageReceived(any())).thenReturn(true)
        streamThenSendFinalizedVoiceNote()
        verify(delegate, never()).onPrivateMessageReceived(any())
        verify(delegate, never()).encryptForPeer(any(), any())
        verify(delegate, never()).sendPacket(any())
    }

    @Test
    fun `favorite notices are ignored without ACK when disabled`() = runBlocking<Unit> {
        handler.acceptFavoriteNotices = false
        val notice = NoisePayload(NoisePayloadType.PRIVATE_MESSAGE,
            PrivateMessagePacket("FAV-ID", "[FAVORITED]:" + "ab".repeat(32)).encode()!!).encode()
        handler.handleNoiseEncrypted(encrypted(notice))
        verify(delegate, never()).encryptForPeer(any(), any())
    }

    @Test
    fun `private and public voice frames are dropped before any assembly`() = runBlocking<Unit> {
        val cacheBefore = RuntimeEnvironment.getApplication().cacheDir.walkTopDown().count()
        assertTrue(handler.handleNoiseEncrypted(encrypted(NoisePayload(NoisePayloadType.VOICE_FRAME, ByteArray(40) { 7 }).encode())))
        val publicFrame = BitchatPacket(version = 2u, type = MessageType.VOICE_FRAME.value, senderID = peerID.hexToBytes(),
            recipientID = SpecialRecipients.BROADCAST, timestamp = System.currentTimeMillis().toULong(),
            payload = ByteArray(40) { 7 }, signature = ByteArray(64) { 1 }, ttl = 7u)
        assertFalse(handler.handlePublicVoiceFrame(RoutedPacket(publicFrame, peerID, "link")))
        verify(delegate, never()).getPeerInfo(any())
        verify(delegate, never()).onMessageReceived(any())
        assertTrue(cacheBefore == RuntimeEnvironment.getApplication().cacheDir.walkTopDown().count())
    }

    @Test
    fun `live voice preference is off when forced by the Wesh build`() {
        LiveVoicePreferences.setEnabled(RuntimeEnvironment.getApplication(), true)
        LiveVoicePreferences.forceDisabled = true
        assertFalse(LiveVoicePreferences.isEnabled(RuntimeEnvironment.getApplication()))
    }

    @Test
    fun `a budget drop is reported so the packet is not marked seen for gossip`() = runBlocking<Unit> {
        val tight = MessageHandler(myPeerID, RuntimeEnvironment.getApplication(), PublicInboundBudget(
            global = PublicInboundBudget.Rate(0.0, 1.0), perLink = PublicInboundBudget.Rate(0.0, 100.0)))
        tight.delegate = delegate
        whenever(delegate.getPeerInfo(peerID)).thenReturn(PeerInfo(id = peerID, nickname = "Synthetic", isConnected = true,
            isDirectConnection = true, noisePublicKey = ByteArray(32) { 3 }, signingPublicKey = ByteArray(32) { 4 },
            isVerifiedNickname = true, lastSeen = System.currentTimeMillis()))
        fun message(text: String) = RoutedPacket(BitchatPacket(version = 2u, type = MessageType.MESSAGE.value,
            senderID = peerID.hexToBytes(), recipientID = SpecialRecipients.BROADCAST, timestamp = System.currentTimeMillis().toULong(),
            payload = text.toByteArray(), signature = ByteArray(64) { 1 }, ttl = 7u), peerID, "link")
        assertTrue(tight.handleMessage(message("first")))
        assertFalse(tight.handleMessage(message("second")))
    }

    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
