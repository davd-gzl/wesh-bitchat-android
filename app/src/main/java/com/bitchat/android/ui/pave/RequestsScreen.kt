package com.bitchat.android.ui.pave

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bitchat.android.R

/**
 * An unverified person who tried to write privately. [fingerprint] is [PaveFingerprint.short];
 * [verifiedNamesakeFingerprint] is set when a QR-verified contact already carries the same nickname,
 * so the two fingerprints can be compared side by side.
 */
@Immutable
data class PaveRequestItem(
    val id: String,
    val nickname: String,
    val fingerprint: String?,
    val pendingMessages: Int,
    val verifiedNamesakeFingerprint: String? = null,
)

/**
 * Demandes (Pavé Fluo). A nickname proves nothing: the only way to answer is to exchange QR codes
 * face to face. Message contents stay hidden; a request that copies a contact's nickname is framed in
 * `warning` with both fingerprints.
 */
@Composable
fun RequestsScreen(
    requests: List<PaveRequestItem>,
    onBack: () -> Unit,
    onExchange: (String) -> Unit,
    onDismiss: (String) -> Unit,
    onBlock: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = WeshPave.colors
    Column(modifier.fillMaxSize().background(c.bg).paveGrain(c.isNight).systemBarsPadding()) {
        PaveHeader(stringResource(R.string.wesh_pave_section_requests), Modifier.padding(horizontal = WeshSpace.s1), onBack = onBack)
        LazyColumn(
            contentPadding = PaddingValues(horizontal = WeshSpace.gutter, vertical = WeshSpace.s2),
            verticalArrangement = Arrangement.spacedBy(WeshSpace.s3),
        ) {
            item(key = "intro") {
                Text(stringResource(R.string.wesh_pave_requests_intro), style = WeshType.body, color = c.ink)
            }
            if (requests.isEmpty()) item(key = "empty") {
                Text(stringResource(R.string.wesh_pave_requests_empty), style = WeshType.body, color = c.inkMuted)
            }
            items(requests, key = { it.id }) { request ->
                RequestCard(request, onExchange = { onExchange(request.id) }, onDismiss = { onDismiss(request.id) }, onBlock = { onBlock(request.id) })
            }
        }
    }
}

@Composable
private fun RequestCard(request: PaveRequestItem, onExchange: () -> Unit, onDismiss: () -> Unit, onBlock: () -> Unit) {
    val c = WeshPave.colors
    val namesake = request.verifiedNamesakeFingerprint
    val shape = RoundedCornerShape(WeshRadius.control)
    Column(
        verticalArrangement = Arrangement.spacedBy(WeshSpace.s3),
        modifier = Modifier
            .fillMaxWidth()
            .background(c.surface, shape)
            .border(if (namesake != null) WeshStroke.focus else WeshStroke.s2, if (namesake != null) c.warning else c.line, shape)
            .padding(WeshSpace.s4),
    ) {
        if (namesake != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WeshSpace.s2)) {
                Icon(Icons.Rounded.Warning, contentDescription = null, tint = c.warning, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.wesh_pave_requests_namesake), style = WeshType.label, color = c.warning)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(WeshSpace.s1), modifier = Modifier.semantics(mergeDescendants = true) {}) {
            Text(remoteDisplayName(request.nickname), style = WeshType.headline, color = c.ink)
            FingerprintLine(request.fingerprint)
            Text(
                stringResource(R.string.wesh_pave_unverified) + " · " +
                    pluralStringResource(R.plurals.wesh_pave_request_preview, request.pendingMessages, request.pendingMessages),
                style = WeshType.caption,
                color = c.inkMuted,
            )
        }
        if (namesake != null) {
            Column(
                verticalArrangement = Arrangement.spacedBy(WeshSpace.s1),
                modifier = Modifier.fillMaxWidth().background(c.surfaceRaised, RoundedCornerShape(WeshRadius.tail)).padding(WeshSpace.s3),
            ) {
                Text(stringResource(R.string.wesh_pave_requests_namesake_yours), style = WeshType.caption, color = c.inkMuted)
                FingerprintLine(namesake)
                Text(stringResource(R.string.wesh_pave_requests_namesake_differs), style = WeshType.label, color = c.ink)
            }
        } else {
            PrimaryButton(stringResource(R.string.wesh_pave_requests_exchange), onExchange, icon = Icons.Rounded.QrCodeScanner)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(WeshSpace.s2), modifier = Modifier.fillMaxWidth()) {
            SecondaryButton(stringResource(R.string.wesh_pave_requests_dismiss), onDismiss, Modifier.weight(1f))
            DangerButton(
                stringResource(if (namesake != null) R.string.wesh_pave_requests_block_report else R.string.wesh_pave_requests_block),
                onBlock,
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun FingerprintLine(fingerprint: String?) {
    val c = WeshPave.colors
    if (fingerprint == null) {
        Text(stringResource(R.string.wesh_pave_fingerprint_unavailable), style = WeshType.caption, color = c.warning)
        return
    }
    val spoken = stringResource(R.string.wesh_pave_fingerprint_label) + " : " + PaveFingerprint.spoken(fingerprint)
    Text(fingerprint, style = WeshType.fingerprint, color = c.ink, maxLines = 1, modifier = Modifier.semantics { contentDescription = spoken })
}

@PaveScreenPreviews
@Composable
private fun RequestsScreenPreview() = PavePreview(padded = false) {
    RequestsScreen(
        listOf(
            PaveRequestItem("l", "Léo", "3C81 E0F2 9A47 15BD", 1),
            PaveRequestItem("n", "Nora", "D90E 5B21 77C3 A0F8", 2, verifiedNamesakeFingerprint = "A1B2 C3D4 E5F6 0718"),
        ),
        {}, {}, {}, {},
    )
}
