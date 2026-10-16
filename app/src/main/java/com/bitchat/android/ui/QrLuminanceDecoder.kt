package com.bitchat.android.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/** On-device QR decoding from a camera luminance plane; no Play services or network involved. */
internal object QrLuminanceDecoder {
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true
    )

    /** [luma] holds [height] rows of [rowStride] bytes; only the first [width] bytes of a row are pixels. */
    fun decode(luma: ByteArray, width: Int, height: Int, rowStride: Int = width): String? {
        if (width <= 0 || height <= 0 || rowStride < width || luma.size < rowStride * (height - 1) + width) return null
        val source = PlanarYUVLuminanceSource(luma, rowStride, height, 0, 0, width, height, false)
        val reader = QRCodeReader()
        return try {
            reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (_: ReaderException) {
            null
        } catch (_: RuntimeException) {
            null
        } finally {
            reader.reset()
        }
    }
}
