package com.bitchat.android.ui.pave

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bitchat.android.R

private val RowShape = RoundedCornerShape(WeshRadius.control)

/** First row of Discussions: the public room, as a full yellow card. */
@Composable
fun PublicRoomCard(
    phonesInRange: Int,
    lastMessage: String?,
    time: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = WeshPave.colors
    val title = stringResource(R.string.wesh_pave_public_room)
    Column(
        verticalArrangement = Arrangement.spacedBy(WeshSpace.s1),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = WeshTouch.row)
            .paveClickable(RowShape, title, onClick = onClick)
            .background(c.publicColor, RowShape)
            .padding(WeshSpace.s4)
            .semantics(mergeDescendants = true) {},
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Campaign, contentDescription = null, tint = c.onPublic, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(WeshSpace.s2))
            Text(title, style = WeshType.headline, color = c.onPublic, modifier = Modifier.weight(1f))
            Text(
                stringResource(R.string.wesh_pave_status_in_range_short, phonesInRange.coerceAtLeast(0)),
                style = WeshType.label,
                color = c.onPublic,
            )
        }
        Text(
            lastMessage ?: stringResource(R.string.wesh_pave_no_message_yet),
            style = WeshType.body,
            color = c.onPublic,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (time != null) stringResource(R.string.wesh_pave_public_room_footer, time)
            else stringResource(R.string.wesh_pave_public_room_readable),
            style = WeshType.caption,
            color = c.onPublic,
        )
    }
}

/**
 * A contact: nickname, verification badge, last message preview (never a status in its place).
 *
 * @param verified the real QR verification state of this contact. Deliberately has no default,
 * so no call site can show the verified badge without stating it.
 */
@Composable
fun ContactRow(
    nickname: String,
    verified: Boolean,
    lastMessage: String?,
    time: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = WeshPave.colors
    InboxRowFrame(
        title = remoteDisplayName(nickname),
        badge = if (verified) stringResource(R.string.wesh_pave_verified) else stringResource(R.string.wesh_pave_unverified),
        badgeIcon = if (verified) Icons.Rounded.Verified else Icons.AutoMirrored.Rounded.HelpOutline,
        badgeColor = if (verified) c.success else c.warning,
        time = time,
        preview = lastMessage ?: stringResource(R.string.wesh_pave_no_message_yet),
        onClick = onClick,
        modifier = modifier,
    )
}

/** An unverified message request (nickname sanitized here, like every remote name): preview hidden; a tap opens it read-only with Exchange, Block, Delete. */
@Composable
fun RequestRow(
    nickname: String,
    messageCount: Int,
    time: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = WeshPave.colors
    InboxRowFrame(
        title = remoteDisplayName(nickname),
        badge = stringResource(R.string.wesh_pave_unverified),
        badgeIcon = Icons.AutoMirrored.Rounded.HelpOutline,
        badgeColor = c.warning,
        time = time,
        preview = pluralStringResource(R.plurals.wesh_pave_request_preview, messageCount, messageCount),
        onClick = onClick,
        modifier = modifier,
    )
}

/** Section title above contacts or requests. */
@Composable
fun InboxSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = WeshType.label,
        color = WeshPave.colors.inkMuted,
        modifier = modifier.padding(top = WeshSpace.s4, bottom = WeshSpace.s1).semantics { heading() },
    )
}

@Composable
private fun InboxRowFrame(
    title: String,
    badge: String,
    badgeIcon: ImageVector,
    badgeColor: Color,
    time: String?,
    preview: String,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val c = WeshPave.colors
    Column(
        verticalArrangement = Arrangement.spacedBy(WeshSpace.s1),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = WeshTouch.row)
            .paveClickable(RowShape, title, onClick = onClick)
            .background(c.surface, RowShape)
            .padding(horizontal = WeshSpace.s4, vertical = WeshSpace.s3)
            .semantics(mergeDescendants = true) {},
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = WeshType.headline, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(WeshSpace.s2))
            Icon(badgeIcon, contentDescription = null, tint = badgeColor, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(WeshSpace.s1))
            Text(badge, style = WeshType.caption, color = badgeColor, modifier = Modifier.weight(1f))
            if (time != null) Text(time, style = WeshType.caption, color = c.inkMuted)
        }
        Text(preview, style = WeshType.body, color = c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@PavePreviews
@Composable
private fun InboxRowPreview() = PavePreview {
    Column(verticalArrangement = Arrangement.spacedBy(WeshSpace.s2)) {
        PublicRoomCard(7, "Point d’eau ouvert au coin de la place.", "18:01", {})
        InboxSectionTitle("Contacts")
        ContactRow("Nora", verified = true, lastMessage = "Oui, j’arrive par la rue de la Paix.", time = "17:53", onClick = {})
        ContactRow("Sami", verified = true, lastMessage = null, time = "hier", onClick = {})
        ContactRow("Inès", verified = false, lastMessage = "À tout à l’heure.", time = "16:20", onClick = {})
        InboxSectionTitle("Demandes")
        RequestRow("Léo 17", 1, "18:02", {})
    }
}
