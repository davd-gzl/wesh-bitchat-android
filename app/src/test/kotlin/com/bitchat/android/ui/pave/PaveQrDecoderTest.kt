package com.bitchat.android.ui.pave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaveQrDecoderTest {
    private val payload = "bitchat://verify?v=1&n=Renard%2042&sample=1"

    /** Renders modules as a luminance plane with a 4-module quiet zone and [stride] padding per row. */
    private fun luma(text: String, scale: Int = 4, inverted: Boolean = false, stride: Int = 0): Triple<ByteArray, Int, Int> {
        val modules = encodeQrModules(text)!!
        val quiet = 4
        val side = (modules.width + quiet * 2) * scale
        val rowStride = side + stride
        val dark: Byte = if (inverted) 0xF0.toByte() else 0x10
        val light: Byte = if (inverted) 0x10 else 0xF0.toByte()
        val bytes = ByteArray(rowStride * side) { light }
        for (y in 0 until side) for (x in 0 until side) {
            val mx = x / scale - quiet
            val my = y / scale - quiet
            if (mx in 0 until modules.width && my in 0 until modules.height && modules[mx, my]) bytes[y * rowStride + x] = dark
        }
        return Triple(bytes, side, rowStride)
    }

    @Test
    fun `decodes a code rendered by the exchange screen`() {
        val (bytes, side, stride) = luma(payload)
        assertEquals(payload, ZxingQrFrameDecoder.decode(bytes, side, side, stride))
    }

    @Test
    fun `honours the row stride of camera planes`() {
        val (bytes, side, stride) = luma(payload, stride = 32)
        assertEquals(payload, ZxingQrFrameDecoder.decode(bytes, side, side, stride))
    }

    @Test
    fun `reads light on dark codes`() {
        val (bytes, side, stride) = luma(payload, inverted = true)
        assertEquals(payload, ZxingQrFrameDecoder.decode(bytes, side, side, stride))
    }

    @Test
    fun `refuses short or empty frames`() {
        assertNull(ZxingQrFrameDecoder.decode(ByteArray(10), 100, 100, 100))
        assertNull(ZxingQrFrameDecoder.decode(ByteArray(100 * 100) { 0x7F }, 100, 100, 100))
        assertNull(ZxingQrFrameDecoder.decode(ByteArray(0), 0, 0, 0))
    }

    @Test
    fun `nothing to encode gives no code`() {
        assertNull(encodeQrModules("  "))
    }
}
