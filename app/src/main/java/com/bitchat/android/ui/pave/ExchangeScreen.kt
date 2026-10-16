package com.bitchat.android.ui.pave

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.NoPhotography
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bitchat.android.R
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale

/**
 * Échanger: your code on top, the camera below, both live so two people verify each other in one go.
 * Two checks: "they scanned you" and "you scanned them". With both, the screen calls [onCompleted]
 * after a success haptic, and the caller opens the private chat.
 *
 * @param myQrPayload the `bitchat://verify` payload built by the app; null while it is not ready.
 * @param renewsInMinutes minutes before the code renews itself (it expires after five).
 * @param onScanned every newly decoded text, unparsed; the caller verifies it.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun ExchangeScreen(
    myQrPayload: String?,
    myNickname: String,
    renewsInMinutes: Int,
    progress: ExchangeProgress,
    contactNickname: String?,
    onScanned: (String) -> Unit,
    onCompleted: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    decoder: QrFrameDecoder = ZxingQrFrameDecoder,
) {
    val c = WeshPave.colors
    val haptics = rememberWeshHaptics()
    val currentOnCompleted by rememberUpdatedState(onCompleted)
    LaunchedEffect(progress.iScannedThem) { if (progress.iScannedThem) haptics.success() }
    LaunchedEffect(progress.complete) { if (progress.complete) currentOnCompleted() }

    val modules = remember(myQrPayload) { myQrPayload?.let(::encodeQrModules) }
    val contact = contactNickname ?: stringResource(R.string.wesh_pave_exchange_contact_fallback)
    val shape = RoundedCornerShape(WeshRadius.control)

    Column(
        modifier
            .fillMaxSize()
            .background(c.bg)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = WeshSpace.gutter, vertical = WeshSpace.s2),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(WeshSpace.s4),
    ) {
        PaveHeader(stringResource(R.string.wesh_pave_exchange_title), onClose = onClose)
        Text(stringResource(R.string.wesh_pave_exchange_intro), style = WeshType.body, color = c.ink, modifier = Modifier.fillMaxWidth())

        val codeLabel = stringResource(R.string.wesh_pave_exchange_my_code)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .clip(shape)
                .background(WeshTokens.QrBackground)
                .padding(WeshSpace.s4)
                .semantics { contentDescription = codeLabel },
        ) {
            if (modules != null) {
                QrModules(modules, Modifier.size(200.dp))
            } else {
                Text(
                    stringResource(R.string.wesh_pave_qr_unavailable),
                    style = WeshType.label,
                    color = WeshTokens.QrModule,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.size(200.dp).padding(top = 88.dp),
                )
            }
        }
        Text(
            stringResource(R.string.wesh_pave_exchange_renew, myNickname, renewsInMinutes),
            style = WeshType.caption,
            color = c.inkMuted,
        )

        CameraArea(onScanned, decoder)

        Row(horizontalArrangement = Arrangement.spacedBy(WeshSpace.s3), modifier = Modifier.fillMaxWidth()) {
            ExchangeStep(
                done = progress.theyScannedMe,
                text = if (progress.theyScannedMe) stringResource(R.string.wesh_pave_exchange_they_scanned, contact)
                else stringResource(R.string.wesh_pave_exchange_they_pending),
                modifier = Modifier.weight(1f),
            )
            ExchangeStep(
                done = progress.iScannedThem,
                text = if (progress.iScannedThem) stringResource(R.string.wesh_pave_exchange_i_scanned, contact)
                else stringResource(R.string.wesh_pave_exchange_i_pending),
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            stringResource(R.string.wesh_pave_exchange_footer),
            style = WeshType.caption,
            color = c.inkMuted,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun CameraArea(onScanned: (String) -> Unit, decoder: QrFrameDecoder) {
    val c = WeshPave.colors
    val shape = RoundedCornerShape(WeshRadius.control)
    val box = Modifier.fillMaxWidth().height(240.dp).clip(shape).background(c.surface)
    if (LocalInspectionMode.current) {
        Box(box, contentAlignment = Alignment.Center) { Viewfinder() }
        return
    }
    val context = LocalContext.current
    val camera = rememberPermissionState(Manifest.permission.CAMERA)
    var asked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!camera.status.isGranted && !asked) {
            asked = true
            camera.launchPermissionRequest()
        }
    }
    if (camera.status.isGranted) {
        Box(box, contentAlignment = Alignment.Center) {
            PaveQrScanner(onScanned, Modifier.fillMaxSize(), decoder)
            Viewfinder()
        }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp).clip(shape).background(c.surface).padding(WeshSpace.s4),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(WeshSpace.s3, Alignment.CenterVertically),
        ) {
            Icon(Icons.Rounded.NoPhotography, contentDescription = null, tint = c.warning, modifier = Modifier.size(32.dp))
            Text(stringResource(R.string.wesh_pave_camera_denied), style = WeshType.headline, color = c.ink, textAlign = TextAlign.Center)
            Text(stringResource(R.string.wesh_pave_camera_denied_body), style = WeshType.body, color = c.ink, textAlign = TextAlign.Center)
            if (asked && !camera.status.shouldShowRationale) {
                SecondaryButton(stringResource(R.string.wesh_pave_open_settings), {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                })
            } else {
                SecondaryButton(stringResource(R.string.wesh_pave_camera_allow), {
                    asked = true
                    camera.launchPermissionRequest()
                })
            }
        }
    }
}

@Composable
private fun Viewfinder() {
    val c = WeshPave.colors
    Box(contentAlignment = Alignment.BottomCenter, modifier = Modifier.fillMaxSize().padding(WeshSpace.s3)) {
        Box(
            Modifier
                .align(Alignment.Center)
                .size(160.dp)
                .border(WeshStroke.publicRule, c.publicColor, RoundedCornerShape(WeshRadius.control))
        )
        Text(
            stringResource(R.string.wesh_pave_exchange_aim),
            style = WeshType.label,
            color = c.ink,
            modifier = Modifier.clip(RoundedCornerShape(WeshRadius.control)).background(c.bg).padding(horizontal = WeshSpace.s3, vertical = WeshSpace.s1),
        )
    }
}

@Composable
private fun ExchangeStep(done: Boolean, text: String, modifier: Modifier = Modifier) {
    val c = WeshPave.colors
    val color = if (done) c.success else c.ink
    val state = stringResource(if (done) R.string.wesh_pave_step_done else R.string.wesh_pave_step_pending)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WeshSpace.s1),
        modifier = modifier
            .heightIn(min = WeshTouch.min)
            .border(WeshStroke.s2, if (done) c.success else c.lineStrong, RoundedCornerShape(WeshRadius.control))
            .padding(horizontal = WeshSpace.s3, vertical = WeshSpace.s2)
            .semantics(mergeDescendants = true) { stateDescription = state },
    ) {
        Icon(if (done) Icons.Rounded.Check else Icons.Rounded.RadioButtonUnchecked, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(text, style = WeshType.label, color = color)
    }
}

@PaveScreenPreviews
@Composable
private fun ExchangeScreenPreview() = PavePreview(padded = false) {
    ExchangeScreen(
        myQrPayload = "wesh-preview-code",
        myNickname = "Renard 42",
        renewsInMinutes = 4,
        progress = ExchangeProgress(theyScannedMe = true),
        contactNickname = "Nora",
        onScanned = {}, onCompleted = {}, onClose = {},
    )
}
