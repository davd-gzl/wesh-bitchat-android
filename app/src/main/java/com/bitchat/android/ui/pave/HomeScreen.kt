package com.bitchat.android.ui.pave

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.bitchat.android.R

/** One verified contact on the home screen. [fingerprint] is [PaveFingerprint.short], null when unknown. */
@Immutable
data class PaveContactItem(
    val id: String,
    val nickname: String,
    val fingerprint: String?,
    val verified: Boolean,
    val lastMessage: String?,
    val time: String?,
)

@Immutable
data class PaveHomeState(
    val phonesInReach: Int,
    val bluetoothOn: Boolean,
    /** Raw nickname of the last public sender; sanitized and drawn apart from the body by [PublicRoomCard]. */
    val publicPreviewSender: String?,
    val publicPreviewText: String?,
    val publicTime: String?,
    val contacts: List<PaveContactItem>,
    val requestCount: Int,
    val contactStoreFailed: Boolean = false,
    /** Shown on Home only while Internet mode is on (spec §3 « Pill »); null hides it. */
    val internetPill: PaveInternetStatus? = null,
)

/**
 * Accueil (Pavé Fluo): the giant count of phones in reach over the pavé gauge, the public room as a
 * yellow card, verified contacts with their fingerprint, the requests entry, and Échanger at the
 * bottom, within thumb reach.
 */
@Composable
fun HomeScreen(
    state: PaveHomeState,
    onOpenPublicRoom: () -> Unit,
    onOpenContact: (String) -> Unit,
    onOpenRequests: () -> Unit,
    onExchange: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetryContacts: () -> Unit,
    modifier: Modifier = Modifier,
    onInternetPill: () -> Unit = {},
) {
    val c = WeshPave.colors
    Column(
        modifier
            .fillMaxSize()
            .background(c.bg)
            .paveGrain(c.isNight)
            .systemBarsPadding(),
    ) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = WeshSpace.gutter, vertical = WeshSpace.s2),
            verticalArrangement = Arrangement.spacedBy(WeshSpace.s3),
        ) {
            item(key = "top") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "WESH",
                        style = WeshType.wordmark.copy(fontSize = WeshType.displaySmall.fontSize * 1.3f, lineHeight = WeshType.displaySmall.lineHeight * 1.3f),
                        color = c.ink,
                        modifier = Modifier.weight(1f).semantics { heading(); contentDescription = "Wesh" },
                    )
                    PaveIconButton(Icons.Rounded.Settings, stringResource(R.string.wesh_pave_home_settings), onOpenSettings)
                }
            }
            item(key = "reach") { PaveReachCard(state.phonesInReach, state.bluetoothOn) }
            state.internetPill?.let { pill -> item(key = "internet") { InternetPill(pill, onInternetPill) } }
            item(key = "public") {
                PublicRoomCard(
                    phonesInRange = state.phonesInReach,
                    lastMessage = state.publicPreviewText,
                    time = state.publicTime,
                    onClick = onOpenPublicRoom,
                    lastSender = state.publicPreviewSender,
                )
            }
            item(key = "private-title") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    InboxSectionTitle(stringResource(R.string.wesh_pave_home_private), Modifier.weight(1f))
                    if (state.requestCount > 0) RequestsLink(state.requestCount, onOpenRequests)
                }
            }
            if (state.contactStoreFailed) item(key = "store-failed") {
                Column(verticalArrangement = Arrangement.spacedBy(WeshSpace.s2)) {
                    Text(
                        stringResource(R.string.wesh_pave_home_contacts_unreadable),
                        style = WeshType.body,
                        color = c.danger,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    SecondaryButton(stringResource(R.string.wesh_pave_retry), onRetryContacts)
                }
            }
            if (state.contacts.isEmpty() && !state.contactStoreFailed) item(key = "empty") {
                Text(stringResource(R.string.wesh_pave_home_no_contact), style = WeshType.body, color = c.inkMuted)
            }
            items(state.contacts, key = { it.id }) { contact ->
                ContactRow(
                    nickname = contact.nickname,
                    fingerprint = contact.fingerprint,
                    verified = contact.verified,
                    lastMessage = contact.lastMessage,
                    time = contact.time,
                    onClick = { onOpenContact(contact.id) },
                )
            }
        }
        Box(Modifier.padding(horizontal = WeshSpace.gutter, vertical = WeshSpace.s3)) {
            PrimaryButton(stringResource(R.string.wesh_pave_action_exchange), onExchange, icon = Icons.Rounded.QrCodeScanner)
        }
    }
}

@Composable
private fun RequestsLink(count: Int, onClick: () -> Unit) {
    val c = WeshPave.colors
    val label = stringResource(R.string.wesh_pave_section_requests)
    val shape = RoundedCornerShape(WeshRadius.control)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WeshSpace.s2),
        modifier = Modifier
            .heightIn(min = WeshTouch.min)
            .paveClickable(shape, label, onClick = onClick)
            .padding(horizontal = WeshSpace.s2),
    ) {
        Text(label, style = WeshType.label, color = c.ink)
        Text(
            count.toString(),
            style = WeshType.caption,
            color = c.bg,
            textAlign = TextAlign.Center,
            modifier = Modifier.background(c.ink, CircleShape).padding(horizontal = WeshSpace.s2, vertical = WeshSpace.s1 / 2),
        )
    }
}

@PaveScreenPreviews
@Composable
private fun HomeScreenPreview() = PavePreview(padded = false) {
    HomeScreen(
        PaveHomeState(
            phonesInReach = 7,
            bluetoothOn = true,
            publicPreviewSender = "Mehdi",
            publicPreviewText = "Ça bouge vers République, restez groupés.",
            publicTime = "18:04",
            contacts = listOf(
                PaveContactItem("n", "Nora", "A1B2 C3D4 E5F6 0718", true, "Je suis à l’angle, côté pharmacie.", "18:06"),
                PaveContactItem("s", "Sami", "5E90 77AC 1B3F D402", true, "Bien arrivé, merci.", "17:52"),
            ),
            requestCount = 2,
        ),
        {}, {}, {}, {}, {}, {},
    )
}
