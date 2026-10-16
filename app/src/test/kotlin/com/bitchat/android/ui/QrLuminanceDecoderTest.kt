package com.bitchat.android.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class QrLuminanceDecoderTest {
    private val payload = "bitchat://verify?v=1&n=synthetic&k=" + "ab".repeat(32)

    private fun luma(size: Int, rowStride: Int, rotate: Boolean = false): ByteArray {
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size)
        val pixels = ByteArray(rowStride * size) { 0x7F }
        for (y in 0 until size) for (x in 0 until size) {
            val dark = if (rotate) matrix[y, size - 1 - x] else matrix[x, y]
            pixels[y * rowStride + x] = if (dark) 0x10 else 0xF0.toByte()
        }
        return pixels
    }

    @Test
    fun `decodes a camera luminance plane with row padding`() {
        assertEquals(payload, QrLuminanceDecoder.decode(luma(320, 384), 320, 320, 384))
    }

    @Test
    fun `decodes a code rotated by the sensor orientation`() {
        assertEquals(payload, QrLuminanceDecoder.decode(luma(320, 320, rotate = true), 320, 320))
    }

    @Test
    fun `returns null for frames without a code or with inconsistent geometry`() {
        assertNull(QrLuminanceDecoder.decode(ByteArray(320 * 320) { 0x40 }, 320, 320))
        assertNull(QrLuminanceDecoder.decode(ByteArray(10), 320, 320))
    }

    @Test
    fun `release scanning does not depend on Play services barcode models`() {
        val gradle = File("build.gradle.kts").readText()
        assertFalse(gradle.contains("mlkit"))
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }
        assertFalse(sources.any { it.readText().contains("com.google.mlkit") })
    }
}
