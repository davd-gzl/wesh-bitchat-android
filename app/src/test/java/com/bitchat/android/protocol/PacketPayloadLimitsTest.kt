package com.bitchat.android.protocol

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PacketPayloadLimitsTest {
    private val sender = ByteArray(8) { 0x11 }
    private val recipient = ByteArray(8) { 0x22 }

    @After
    fun restorePolicy() {
        PacketPayloadLimits.acceptCompressedMedia = true
    }

    @Test
    fun `compressed directed message above its type cap never reaches the decompressor`() {
        val cap = PacketPayloadLimits.maxPayloadBytes(MessageType.MESSAGE.value)
        var calls = 0

        val result = BinaryProtocol.decodeForTesting(
            compressed(MessageType.MESSAGE.value, originalSize = cap + 1, compressedSize = 4_096, recipient = recipient)
        ) { _, _ -> calls += 1; ByteArray(cap + 1) }

        assertNull(result)
        assertEquals(0, calls)
    }

    @Test
    fun `compressed public text declaring more than the public text limit never reaches the decompressor`() {
        var calls = 0
        val decompress = { _: ByteArray, size: Int -> calls += 1; ByteArray(size) }

        assertNull(BinaryProtocol.decodeForTesting(
            compressed(MessageType.MESSAGE.value, PacketPayloadLimits.PUBLIC_TEXT_BYTES + 1, compressedSize = 64), decompress))
        assertEquals(0, calls)

        BinaryProtocol.decodeForTesting(
            compressed(MessageType.MESSAGE.value, PacketPayloadLimits.PUBLIC_TEXT_BYTES, compressedSize = 64), decompress)
        assertEquals(1, calls)
    }

    @Test
    fun `compressed encrypted payloads are refused before inflating`() {
        var calls = 0

        val result = BinaryProtocol.decodeForTesting(
            compressed(MessageType.NOISE_ENCRYPTED.value, originalSize = 1_024, compressedSize = 512, recipient = recipient)
        ) { _, size -> calls += 1; ByteArray(size) }

        assertNull(result)
        assertEquals(0, calls)
    }

    @Test
    fun `a compressed file inflates no further than a fragment set may buffer`() {
        val bound = com.bitchat.android.util.AppConstants.Fragmentation.MAX_FRAGMENT_TOTAL_BYTES
        var requested = -1
        val decompress = { _: ByteArray, size: Int -> requested = size; null }

        BinaryProtocol.decodeForTesting(compressed(MessageType.FILE_TRANSFER.value, bound + 1, compressedSize = 4_096), decompress)
        assertEquals(-1, requested)

        BinaryProtocol.decodeForTesting(compressed(MessageType.FILE_TRANSFER.value, bound, compressedSize = 4_096), decompress)
        assertEquals(bound, requested)
    }

    @Test
    fun `compressed files are not inflated at all when media is refused`() {
        PacketPayloadLimits.acceptCompressedMedia = false
        var calls = 0

        BinaryProtocol.decodeForTesting(compressed(MessageType.FILE_TRANSFER.value, 1_024, compressedSize = 512)) { _, size ->
            calls += 1; ByteArray(size)
        }

        assertEquals(0, calls)
    }

    @Test
    fun `declared size beyond the deflate expansion ceiling never reaches the decompressor`() {
        val compressedSize = 64
        val originalSize = compressedSize * PacketPayloadLimits.MAX_DEFLATE_RATIO + 1
        var calls = 0

        val result = BinaryProtocol.decodeForTesting(
            compressed(MessageType.MESSAGE.value, originalSize, compressedSize, recipient = recipient)
        ) { _, _ -> calls += 1; ByteArray(originalSize) }

        assertNull(result)
        assertEquals(0, calls)
    }

    @Test
    fun `declared size at the deflate expansion ceiling reaches the decompressor`() {
        val compressedSize = 64
        val originalSize = compressedSize * PacketPayloadLimits.MAX_DEFLATE_RATIO
        var requested = -1

        BinaryProtocol.decodeForTesting(
            compressed(MessageType.MESSAGE.value, originalSize, compressedSize, recipient = recipient)
        ) { _, size -> requested = size; null }

        assertEquals(originalSize, requested)
    }

    @Test
    fun `uncompressed control payload above its type cap is rejected`() {
        val cap = PacketPayloadLimits.maxPayloadBytes(MessageType.ANNOUNCE.value)

        assertNull(BinaryProtocol.decode(plain(MessageType.ANNOUNCE.value, cap + 1)))
        assertNotNull(BinaryProtocol.decode(plain(MessageType.ANNOUNCE.value, cap)))
    }

    @Test
    fun `fragment payload above its type cap is rejected`() {
        val cap = PacketPayloadLimits.maxPayloadBytes(MessageType.FRAGMENT.value)

        assertNull(BinaryProtocol.decode(plain(MessageType.FRAGMENT.value, cap + 1)))
        assertNotNull(BinaryProtocol.decode(plain(MessageType.FRAGMENT.value, cap)))
    }

    @Test
    fun `caps leave room for every legitimate BitChat payload`() {
        // Public text travels in v1 frames (16-bit length); files keep the framed-file ceiling.
        assertEquals(128 * 1024, PacketPayloadLimits.maxPayloadBytes(MessageType.MESSAGE.value))
        assertEquals(
            com.bitchat.android.util.AppConstants.Protocol.MAX_PAYLOAD_LENGTH,
            PacketPayloadLimits.maxPayloadBytes(MessageType.FILE_TRANSFER.value)
        )
        assertEquals(
            com.bitchat.android.util.AppConstants.Protocol.MAX_PAYLOAD_LENGTH,
            PacketPayloadLimits.maxPayloadBytes(MessageType.NOISE_ENCRYPTED.value)
        )
        assertEquals(8 * 1024, PacketPayloadLimits.maxPayloadBytes(MessageType.REQUEST_SYNC.value))
        // Types this client does not know (newer iOS types) stay relayable up to two v1 frames.
        assertEquals(128 * 1024, PacketPayloadLimits.maxPayloadBytes(0x7Fu))
        assertNotNull(BinaryProtocol.decode(plain(0x7Fu, 0xFFFF)))
    }

    private fun plain(type: UByte, payloadSize: Int): ByteArray =
        ByteBuffer.allocate(16 + 8 + payloadSize).order(ByteOrder.BIG_ENDIAN).apply {
            put(2)
            put(type.toByte())
            put(3)
            putLong(1_709_600_000_000L)
            put(0)
            putInt(payloadSize)
            put(sender)
            put(ByteArray(payloadSize) { 0x41 })
        }.array()

    private fun compressed(type: UByte, originalSize: Int, compressedSize: Int, recipient: ByteArray? = null): ByteArray =
        ByteBuffer.allocate(16 + 8 + (recipient?.size ?: 0) + 4 + compressedSize).order(ByteOrder.BIG_ENDIAN).apply {
            put(2)
            put(type.toByte())
            put(3)
            putLong(1_709_600_000_000L)
            val flags = BinaryProtocol.Flags.IS_COMPRESSED.toInt() or
                (if (recipient != null) BinaryProtocol.Flags.HAS_RECIPIENT.toInt() else 0)
            put(flags.toByte())
            putInt(4 + compressedSize)
            put(sender)
            recipient?.let { put(it) }
            putInt(originalSize)
            put(ByteArray(compressedSize) { 0x03 })
        }.array()
}
