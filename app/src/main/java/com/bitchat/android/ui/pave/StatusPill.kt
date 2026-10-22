package com.bitchat.android.ui.pave

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bitchat.android.R

/** Label of a status, in full ("7 téléphones à portée") or short ("7 à portée"). */
@Composable
fun PaveLinkStatus.label(short: Boolean = false): String = when (this) {
    is PaveLinkStatus.InRange ->
        if (short) stringResource(R.string.wesh_pave_status_in_range_short, phones)
        else pluralStringResource(R.plurals.wesh_pave_status_in_range, phones, phones)
    PaveLinkStatus.NobodyInRange -> stringResource(R.string.wesh_pave_status_nobody)
    PaveLinkStatus.BluetoothOff -> stringResource(R.string.wesh_pave_status_bluetooth_off)
    is PaveLinkStatus.ViaInternet -> stringResource(R.string.wesh_pave_status_internet, phones)
}

private fun PaveLinkStatus.icon(): ImageVector = when (this) {
    is PaveLinkStatus.InRange -> Icons.Rounded.Sensors
    PaveLinkStatus.NobodyInRange -> Icons.Rounded.Warning
    PaveLinkStatus.BluetoothOff -> Icons.Rounded.BluetoothDisabled
    is PaveLinkStatus.ViaInternet -> Icons.Rounded.Public
}

/**
 * The status pill at the top of each screen: phones in range and how messages travel.
 * Always an icon, a number or a word, and the colour. A tap opens the Connexion sheet.
 * It never says a message was received; that is the delivery state's job.
 */
@Composable
fun StatusPill(
    status: PaveLinkStatus,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    short: Boolean = false,
) {
    val c = WeshPave.colors
    val internet = status is PaveLinkStatus.ViaInternet
    val accent: Color = when (status) {
        is PaveLinkStatus.InRange -> c.success
        PaveLinkStatus.NobodyInRange -> c.warning
        PaveLinkStatus.BluetoothOff -> c.danger
        is PaveLinkStatus.ViaInternet -> c.onInternet
    }
    val text = status.label(short)
    val openLabel = stringResource(R.string.wesh_pave_status_open)
    val container = if (internet) c.internet else c.surface
    val clickable = if (onClick != null) Modifier.paveClickable(CircleShape, openLabel, onClick = onClick) else Modifier
    Row(
        modifier = modifier
            .heightIn(min = WeshTouch.min)
            .then(clickable)
            .clip(CircleShape)
            .background(container)
            .border(WeshStroke.s2, if (internet) c.internet else c.lineStrong, CircleShape)
            .padding(horizontal = WeshSpace.s4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(status.icon(), contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(WeshSpace.s2))
        Text(text, style = WeshType.label, color = if (internet) c.onInternet else c.ink)
    }
}

/**
 * The Internet pill on Home (spec §3 « Pill »), shown while Internet mode is on. It never says a
 * message was received. A touch retries after a failure and opens Connexion otherwise.
 */
@Composable
fun InternetPill(status: PaveInternetStatus, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = WeshPave.colors
    val on = status is PaveInternetStatus.On
    val failed = status.offersRetry
    val icon = when (status) {
        PaveInternetStatus.Off -> Icons.Rounded.Sensors
        is PaveInternetStatus.Connecting, is PaveInternetStatus.On -> Icons.Rounded.Public
        is PaveInternetStatus.Error -> Icons.Rounded.Warning
    }
    val text = stringResource(status.pillLabel())
    val hint = stringResource(if (failed) R.string.wesh_pave_pill_retry_hint else R.string.wesh_pave_pill_hint)
    val border = when {
        failed -> c.danger
        on || status is PaveInternetStatus.Connecting -> c.internet
        else -> c.lineStrong
    }
    Row(
        modifier = modifier
            .heightIn(min = WeshTouch.min)
            .paveClickable(CircleShape, hint, onClick = onClick)
            .clip(CircleShape)
            .background(if (on) c.internet else c.surface)
            .border(WeshStroke.s2, border, CircleShape)
            .padding(horizontal = WeshSpace.s4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = when {
            on -> c.onInternet
            failed -> c.danger
            status is PaveInternetStatus.Connecting -> c.internet
            else -> c.inkMuted
        }, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(WeshSpace.s2))
        Text(text, style = WeshType.label, color = when {
            on -> c.onInternet
            failed -> c.danger
            else -> c.ink
        })
    }
}

@PavePreviews
@Composable
private fun InternetPillPreview() = PavePreview {
    Column(verticalArrangement = Arrangement.spacedBy(WeshSpace.s3)) {
        InternetPill(PaveInternetStatus.Connecting(0), onClick = {})
        InternetPill(PaveInternetStatus.On(3), onClick = {})
        InternetPill(PaveInternetStatus.Error(PaveInternetError.TorBlocked), onClick = {})
    }
}

@PavePreviews
@Composable
private fun StatusPillPreview() = PavePreview {
    Column(verticalArrangement = Arrangement.spacedBy(WeshSpace.s3)) {
        StatusPill(PaveLinkStatus.InRange(7), onClick = {})
        StatusPill(PaveLinkStatus.InRange(7), onClick = {}, short = true)
        StatusPill(PaveLinkStatus.NobodyInRange, onClick = {})
        StatusPill(PaveLinkStatus.BluetoothOff, onClick = {})
        StatusPill(PaveLinkStatus.ViaInternet(3), onClick = {})
    }
}
