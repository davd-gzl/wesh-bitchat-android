package com.bitchat.android.ui.pave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaveQrDecoderTest {
    private val payload = "bitchat://verify?v=1&n=Renard%2042&sample=1"
    private val stranger = "bitchat://verify?v=1&n=Inconnu%2099&sample=2"

    private class Frame(val bytes: ByteArray, val width: Int, val height: Int, val rowStride: Int) {
        val whole get() = QrCrop(0, 0, width, height)
    }

    /** A light frame of [width] x [height] (plus [padding] bytes per row) with [codes] drawn at their top-left corners. */
    private fun frame(
        width: Int,
        height: Int,
        vararg codes: Pair<String, Pair<Int, Int>>,
        scale: Int = 4,
        inverted: Boolean = false,
        padding: Int = 0,
    ): Frame {
        val rowStride = width + padding
        val dark: Byte = if (inverted) 0xF0.toByte() else 0x10
        val light: Byte = if (inverted) 0x10 else 0xF0.toByte()
        val bytes = ByteArray(rowStride * height) { light }
        codes.forEach { (text, origin) ->
            val modules = encodeQrModules(text)!!
            val (left, top) = origin
            for (my in 0 until modules.height) for (mx in 0 until modules.width) {
                if (!modules[mx, my]) continue
                for (dy in 0 until scale) for (dx in 0 until scale) {
                    bytes[(top + my * scale + dy) * rowStride + left + mx * scale + dx] = dark
                }
            }
        }
        return Frame(bytes, width, height, rowStride)
    }

    /** One code with a 4-module quiet zone, filling its own frame. */
    private fun single(text: String, inverted: Boolean = false, padding: Int = 0): Frame {
        val side = (encodeQrModules(text)!!.width + 8) * 4
        return frame(side, side, text to (16 to 16), inverted = inverted, padding = padding)
    }

    private fun decode(f: Frame, crop: QrCrop = f.whole) = ZxingQrFrameDecoder.decode(f.bytes, f.width, f.height, f.rowStride, crop)

    @Test
    fun `decodes a code rendered by the exchange screen`() {
        assertEquals(payload, decode(single(payload)))
    }

    @Test
    fun `honours the row stride of camera planes`() {
        assertEquals(payload, decode(single(payload, padding = 32)))
    }

    @Test
    fun `reads light on dark codes`() {
        assertEquals(payload, decode(single(payload, inverted = true)))
    }

    @Test
    fun `refuses short or empty frames`() {
        assertNull(ZxingQrFrameDecoder.decode(ByteArray(10), 100, 100, 100, QrCrop(0, 0, 100, 100)))
        assertNull(ZxingQrFrameDecoder.decode(ByteArray(100 * 100) { 0x7F }, 100, 100, 100, QrCrop(0, 0, 100, 100)))
        assertNull(ZxingQrFrameDecoder.decode(ByteArray(0), 0, 0, 0, QrCrop(0, 0, 0, 0)))
    }

    @Test
    fun `a frame with two different codes is ignored`() {
        val f = frame(640, 480, payload to (40 to 40), stranger to (400 to 260))
        assertNull(decode(f))
    }

    @Test
    fun `only the viewfinder region is read`() {
        // The contact's code sits in the middle; a stranger's code sits in a corner of the frame.
        val f = frame(640, 480, payload to (240 to 160), stranger to (8 to 8))
        val finder = QrCrop(200, 120, 240, 240)
        assertEquals(payload, decode(f, finder))
        // A code only outside the viewfinder is never read.
        assertNull(decode(frame(640, 480, stranger to (8 to 8)), finder))
    }

    @Test
    fun `a crop outside the frame is refused`() {
        val f = single(payload)
        assertNull(decode(f, QrCrop(-1, 0, f.width, f.height)))
        assertNull(decode(f, QrCrop(0, 0, f.width + 1, f.height)))
        assertNull(decode(f, QrCrop(0, 0, 0, 10)))
    }

    @Test
    fun `nothing to encode gives no code`() {
        assertNull(encodeQrModules("  "))
    }
}
