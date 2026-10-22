package com.bitchat.android.ui.pave

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import com.bitchat.android.R

/** Where a message lives: the public room (yellow) or a private chat (chalk / ink). */
enum class PaveRoom { Public, Private }

@Composable
fun PaveDelivery.label(): String = stringResource(
    when (this) {
        PaveDelivery.Waiting -> R.string.wesh_pave_delivery_waiting
        PaveDelivery.Sent -> R.string.wesh_pave_delivery_sent
        PaveDelivery.Delivered -> R.string.wesh_pave_delivery_delivered
        PaveDelivery.Read -> R.string.wesh_pave_delivery_read
        PaveDelivery.Failed -> R.string.wesh_pave_delivery_failed
    }
)

@Composable
private fun PaveDelivery.color(): Color {
    val c = WeshPave.colors
    return when (this) {
        PaveDelivery.Waiting -> c.deliveryWaiting
        PaveDelivery.Sent -> c.deliverySent
        PaveDelivery.Delivered, PaveDelivery.Read -> c.deliveryReceived
        PaveDelivery.Failed -> c.deliveryFailed
    }
}

/**
 * A message. Yours are filled (`private`, or `public` in the public room), others' are bordered,
 * so they read apart in full sun without relying on alignment. No warning inside the bubble.
 *
 * @param sender received only: nickname above the first message of a series (sanitized here).
 * @param senderFingerprint received only: [PaveFingerprint.short] of the sender, shown under the
 * nickname so two identical nicknames read apart. Null when the key is unknown.
 * @param delivery your messages only.
 * @param onOpenDetail a tap opens the message status detail.
 */
@Composable
fun MessageBubble(
    text: String,
    own: Boolean,
    room: PaveRoom,
    time: String?,
    modifier: Modifier = Modifier,
    sender: String? = null,
    senderFingerprint: String? = null,
    delivery: PaveDelivery? = null,
    transport: PaveTransport? = null,
    onRetry: (() -> Unit)? = null,
    onOpenDetail: (() -> Unit)? = null,
) {
    val c = WeshPave.colors
    // Remote nicknames are sanitized here so no caller can forget it.
    val safeSender = sender?.let { remoteDisplayName(it) }
    val failed = own && delivery == PaveDelivery.Failed
    val (fill, ink) = when {
        !own -> c.surface to c.ink
        room == PaveRoom.Public -> c.publicColor to c.onPublic
        else -> c.privateColor to c.onPrivate
    }
    val r = WeshRadius.bubble
    val shape = if (own) RoundedCornerShape(r, r, WeshRadius.tail, r) else RoundedCornerShape(r, r, r, WeshRadius.tail)

    val you = stringResource(R.string.wesh_pave_message_from_you)
    val viaInternet = stringResource(R.string.wesh_pave_via_internet)
    val viaBluetooth = stringResource(R.string.wesh_pave_via_bluetooth)
    val detailLabel = stringResource(R.string.wesh_pave_message_detail)
    val deliveryText = delivery?.takeIf { own }?.label()
    val transportText = when (transport) {
        PaveTransport.Internet -> viaInternet
        PaveTransport.Bluetooth -> viaBluetooth
        null -> null
    }
    // Received in the public room: "Coquelicot · 17:40" above the bubble.
    val headerWithTime = !own && room == PaveRoom.Public && safeSender != null
    val header = when {
        own || safeSender == null -> null
        headerWithTime && time != null -> "$safeSender · $time"
        else -> safeSender
    }
    val metaParts = buildList {
        if (!headerWithTime) time?.let(::add)
        if (!failed) deliveryText?.let(::add)
        transportText?.let(::add)
    }
    val fingerprintLabel = stringResource(R.string.wesh_pave_fingerprint_label)
    // Under a remote nickname, always a fingerprint line: the key, or "Empreinte indisponible".
    val fingerprintUnavailable = stringResource(R.string.wesh_pave_fingerprint_unavailable)
    val showFingerprintLine = header != null && room == PaveRoom.Public
    val shownFingerprint = (senderFingerprint ?: fingerprintUnavailable).takeIf { showFingerprintLine }
    val spokenFingerprint = if (!showFingerprintLine) null
        else senderFingerprint?.let { "$fingerprintLabel : " + PaveFingerprint.spoken(it) } ?: fingerprintUnavailable
    val description = listOfNotNull(if (own) you else safeSender, spokenFingerprint, text, if (headerWithTime) time else null)
        .plus(metaParts)
        .plus(if (failed) listOfNotNull(deliveryText) else emptyList())
        .joinToString(", ")

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val maxBubble = maxWidth * 0.78f
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = if (own) Alignment.End else Alignment.Start,
        ) {
            val clickable = if (onOpenDetail != null) Modifier.paveClickable(shape, detailLabel, onClick = onOpenDetail) else Modifier
            Column(
                horizontalAlignment = if (own) Alignment.End else Alignment.Start,
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = description
                    if (onOpenDetail != null) onClick(label = detailLabel) { onOpenDetail(); true }
                },
            ) {
                if (header != null) {
                    Text(
                        header,
                        style = WeshType.caption,
                        color = c.inkMuted,
                        modifier = Modifier.padding(start = WeshSpace.s1, top = WeshSpace.s2, bottom = if (shownFingerprint != null) 0.dp else WeshSpace.s1),
                    )
                    if (shownFingerprint != null) {
                        Text(
                            shownFingerprint,
                            style = if (senderFingerprint != null) WeshType.fingerprint.copy(fontSize = WeshType.caption.fontSize, lineHeight = WeshType.caption.lineHeight) else WeshType.caption,
                            color = if (senderFingerprint != null) c.inkMuted else c.warning,
                            maxLines = 1,
                            modifier = Modifier.padding(start = WeshSpace.s1, bottom = WeshSpace.s1),
                        )
                    }
                }
                val bordered = if (own) Modifier else Modifier.border(WeshStroke.s2, c.lineStrong, shape)
                Text(
                    text,
                    style = WeshType.body,
                    color = ink,
                    modifier = Modifier
                        .widthIn(max = maxBubble)
                        .then(clickable)
                        .background(fill, shape)
                        .then(bordered)
                        .padding(WeshSpace.s3),
                )
                if (metaParts.isNotEmpty()) {
                    MetaLine(metaParts, deliveryText.takeIf { !failed }, delivery, viaInternet)
                }
            }
            if (failed) {
                val retry = stringResource(R.string.wesh_pave_failed_retry)
                val retryShape = RoundedCornerShape(WeshRadius.control)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .heightIn(min = WeshTouch.min)
                        .then(if (onRetry != null) Modifier.paveClickable(retryShape, retry, onClick = onRetry) else Modifier)
                        .padding(horizontal = WeshSpace.s2),
                ) {
                    Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = c.danger, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(WeshSpace.s1))
                    Text(retry, style = WeshType.caption, color = c.danger)
                }
            }
        }
    }
}

@Composable
private fun MetaLine(parts: List<String>, deliveryText: String?, delivery: PaveDelivery?, viaInternet: String) {
    val c = WeshPave.colors
    val line = buildAnnotatedString {
        parts.forEachIndexed { i, part ->
            if (i > 0) append(" · ")
            val color = when {
                part == deliveryText && delivery != null -> delivery.color()
                part == viaInternet -> c.internet
                else -> c.inkMuted
            }
            withStyle(SpanStyle(color = color)) { append(part) }
        }
    }
    Text(line, style = WeshType.caption, modifier = Modifier.padding(horizontal = WeshSpace.s1, vertical = WeshSpace.s1))
}

@PavePreviews
@Composable
private fun MessageBubblePreview() = PavePreview {
    Column(verticalArrangement = Arrangement.spacedBy(WeshSpace.s2)) {
        MessageBubble("On se retrouve devant la fontaine à 18 h ?", own = false, room = PaveRoom.Private, time = "17:52", transport = PaveTransport.Bluetooth)
        MessageBubble("Oui, j’arrive par la rue de la Paix.", own = true, room = PaveRoom.Private, time = "17:53", delivery = PaveDelivery.Delivered, transport = PaveTransport.Bluetooth)
        MessageBubble("Je suis là.", own = true, room = PaveRoom.Private, time = "17:59", delivery = PaveDelivery.Sent, transport = PaveTransport.Internet)
        MessageBubble("Vous me voyez ?", own = true, room = PaveRoom.Private, time = null, delivery = PaveDelivery.Failed, onRetry = {})
        MessageBubble("Cortège bloqué au pont, on contourne par le quai.", own = false, room = PaveRoom.Public, time = "17:40", sender = "Coquelicot", senderFingerprint = "0E7A 4C19 B2D8 66F1")
        MessageBubble("Point d’eau ouvert au coin de la place.", own = true, room = PaveRoom.Public, time = "18:01", delivery = PaveDelivery.Waiting)
    }
}
