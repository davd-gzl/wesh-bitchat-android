package com.bitchat.android.ui.media

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import android.app.Activity
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.res.stringResource
import com.bitchat.android.ui.theme.BitchatFontFamily
import com.bitchat.android.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Fullscreen image viewer with swipe navigation between multiple images
 * @param imagePaths List of all image file paths in the current chat
 * @param initialIndex Starting index of the current image in the list
 * @param onClose Callback when the viewer should be dismissed
 */
// Backward compatibility for single image (can be removed after updating all callers)
@Composable
fun FullScreenImageViewer(path: String, onClose: () -> Unit) {
    FullScreenImageViewer(listOf(path), 0, onClose)
}

/**
 * Fullscreen image viewer with swipe navigation between multiple images
 * @param imagePaths List of all image file paths in the current chat
 * @param initialIndex Starting index of the current image in the list
 * @param onClose Callback when the viewer should be dismissed
 */
@Composable
fun FullScreenImageViewer(imagePaths: List<String>, initialIndex: Int = 0, onClose: () -> Unit) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(initialPage = initialIndex.coerceIn(0, (imagePaths.size - 1).coerceAtLeast(0)), pageCount = imagePaths::size)
    val scope = rememberCoroutineScope()
    var pendingDocument by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    fun showResult(success: Boolean) {
        Toast.makeText(context, if (success) R.string.toast_image_saved else R.string.toast_failed_to_save_image, Toast.LENGTH_SHORT).show()
    }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val source = pendingDocument
        pendingDocument = null
        if (result.resultCode == Activity.RESULT_OK && source != null) {
            val destination = result.data?.data
            scope.launch {
                saving = true
                try {
                    showResult(withContext(Dispatchers.IO) {
                        destination != null && ImageExport.toDocument(context.contentResolver, File(source), destination)
                    })
                } finally { saving = false }
            }
        }
    }

    if (imagePaths.isEmpty()) {
        onClose()
        return
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = Color.Black) {
            Box(modifier = Modifier.fillMaxSize()) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    val currentPath = imagePaths[page]
                    val bmp = remember(currentPath) { try { android.graphics.BitmapFactory.decodeFile(currentPath) } catch (_: Exception) { null } }

                    bmp?.let {
                        androidx.compose.foundation.Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = stringResource(R.string.cd_image_index_of, page + 1, imagePaths.size),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    } ?: run {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(text = stringResource(R.string.image_unavailable), color = Color.White)
                        }
                    }
                }

                // Image counter
                if (imagePaths.size > 1) {
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .align(Alignment.TopCenter)
                            .background(Color(0x66000000), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.image_counter, pagerState.currentPage + 1, imagePaths.size),
                            color = Color.White,
                            fontSize = 14.sp,
                            fontFamily = BitchatFontFamily
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                        .align(Alignment.TopEnd),
                    horizontalArrangement = Arrangement.End
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(Color(0x66000000), CircleShape)
                            .clickable(enabled = !saving && pendingDocument == null) {
                                val source = File(imagePaths[pagerState.currentPage])
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    scope.launch {
                                        saving = true
                                        try {
                                            showResult(withContext(Dispatchers.IO) {
                                                ImageExport.toDownloads(context.contentResolver, source)
                                            })
                                        } finally { saving = false }
                                    }
                                } else {
                                    // The system picker grants access to just the chosen destination on API 26–28.
                                    pendingDocument = source.path
                                    try { documentPicker.launch(ImageExport.documentIntent(source)) }
                                    catch (_: Exception) { pendingDocument = null; showResult(false) }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.material3.Icon(Icons.Filled.Download, stringResource(R.string.cd_save_current_image), tint = Color.White)
                    }
                    Spacer(Modifier.width(12.dp))
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(Color(0x66000000), CircleShape)
                            .clickable { onClose() },
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.material3.Icon(Icons.Filled.Close, stringResource(R.string.cd_close), tint = Color.White)
                    }
                }
            }
        }
    }
}
