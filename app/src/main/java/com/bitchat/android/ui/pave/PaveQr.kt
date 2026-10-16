package com.bitchat.android.ui.pave

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.viewfinder.core.ImplementationMode
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Decodes a QR code from a camera luminance plane. Kept behind an interface so the scanner
 * does not depend on one decoder; the default runs on the device, without Play services or network.
 */
fun interface QrFrameDecoder {
    /** [luma] holds [height] rows of [rowStride] bytes; only the first [width] bytes of a row are pixels. */
    fun decode(luma: ByteArray, width: Int, height: Int, rowStride: Int): String?
}

/** On-device ZXing decoder. Also reads light-on-dark codes. */
object ZxingQrFrameDecoder : QrFrameDecoder {
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.ALSO_INVERTED to true,
    )

    override fun decode(luma: ByteArray, width: Int, height: Int, rowStride: Int): String? {
        if (width <= 0 || height <= 0 || rowStride < width || luma.size < rowStride * (height - 1) + width) return null
        val source = PlanarYUVLuminanceSource(luma, rowStride, height, 0, 0, width, height, false)
        val reader = MultiFormatReader()
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

/** QR modules for [data], one cell per module, no quiet zone (drawn by the caller). Null if it cannot be encoded. */
fun encodeQrModules(data: String): BitMatrix? {
    if (data.isBlank()) return null
    return runCatching {
        QRCodeWriter().encode(
            data, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
        )
    }.getOrNull()
}

/** Draws [modules] dark on light. The quiet zone comes from the surrounding padding. */
@Composable
fun QrModules(modules: BitMatrix, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val cell = size.minDimension / modules.width
        drawRect(WeshTokens.QrBackground, size = Size(cell * modules.width, cell * modules.height))
        for (y in 0 until modules.height) {
            for (x in 0 until modules.width) {
                if (modules[x, y]) {
                    // Slight overlap avoids hairline gaps between modules.
                    drawRect(WeshTokens.QrModule, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
    }
}

/** Back camera preview that reports each new decoded text once. Needs the CAMERA permission. */
@Composable
fun PaveQrScanner(
    onCode: (String) -> Unit,
    modifier: Modifier = Modifier,
    decoder: QrFrameDecoder = ZxingQrFrameDecoder,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val surfaceRequests = remember { MutableStateFlow<SurfaceRequest?>(null) }
    val surfaceRequest by surfaceRequests.collectAsState()
    val onCodeState = rememberUpdatedState(onCode)

    DisposableEffect(lifecycleOwner, decoder) {
        val executor = Executors.newSingleThreadExecutor()
        val main = Handler(Looper.getMainLooper())
        var last: String? = null
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var buffer = ByteArray(0)
        val analyzer = ImageAnalysis.Analyzer { image: ImageProxy ->
            try {
                // Plane 0 of YUV_420_888 is luminance; QR finder patterns tolerate any rotation.
                val plane = image.planes.firstOrNull() ?: return@Analyzer
                val data = plane.buffer
                data.rewind()
                if (buffer.size != data.remaining()) buffer = ByteArray(data.remaining())
                data.get(buffer)
                val text = decoder.decode(buffer, image.width, image.height, plane.rowStride)
                if (!text.isNullOrBlank()) main.post {
                    if (text != last) {
                        last = text
                        onCodeState.value(text)
                    }
                }
            } finally {
                image.close()
            }
        }
        future.addListener({
            val p = runCatching { future.get() }.getOrNull() ?: return@addListener
            provider = p
            val preview = Preview.Builder().build().also { it.setSurfaceProvider { r -> surfaceRequests.value = r } }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(executor, analyzer) }
            runCatching {
                p.unbindAll()
                p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }.onFailure { Log.w("PaveQrScanner", "Camera unavailable") }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            surfaceRequests.value = null
            runCatching { provider?.unbindAll() }
            executor.shutdown()
        }
    }

    surfaceRequest?.let {
        CameraXViewfinder(surfaceRequest = it, implementationMode = ImplementationMode.EMBEDDED, modifier = modifier)
    }
}
