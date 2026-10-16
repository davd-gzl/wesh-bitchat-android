package com.bitchat.android.ui.pave

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.bitchat.android.R

/**
 * The message field at the bottom of a conversation. It carries the colour of its room so nobody
 * posts to the public room by mistake: public has a yellow top rule and a megaphone, private a lock.
 * The send button stays disabled while the field is empty or over 2,000 bytes.
 */
@Composable
fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    room: PaveRoom,
    modifier: Modifier = Modifier,
    recipient: String? = null,
    enabled: Boolean = true,
) {
    val c = WeshPave.colors
    val haptics = rememberWeshHaptics()
    val bytes = ComposerLimits.byteCount(value)
    val over = ComposerLimits.isOverLimit(bytes)
    val canSend = enabled && ComposerLimits.canSend(value)
    val isPublic = room == PaveRoom.Public
    val hint = if (isPublic) stringResource(R.string.wesh_pave_composer_public_hint)
    else stringResource(R.string.wesh_pave_composer_private_hint, recipient.orEmpty())
    val sendLabel = if (isPublic) stringResource(R.string.wesh_pave_send_public)
    else stringResource(R.string.wesh_pave_send_private, recipient.orEmpty())
    val shape = RoundedCornerShape(WeshRadius.control)
    val rule = c.publicColor

    Column(modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WeshSpace.s2),
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(c.surface)
                .then(
                    if (isPublic) Modifier.drawBehind {
                        drawRect(rule, size = Size(size.width, WeshStroke.publicRule.toPx()))
                    } else Modifier
                )
                .border(WeshStroke.s2, c.lineStrong, shape)
                .padding(WeshSpace.s2),
        ) {
            Icon(
                if (isPublic) Icons.Rounded.Campaign else Icons.Rounded.Lock,
                contentDescription = null,
                tint = c.inkMuted,
                modifier = Modifier.padding(start = WeshSpace.s1).size(22.dp),
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                textStyle = WeshType.body.copy(color = c.ink),
                cursorBrush = SolidColor(c.ink),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                maxLines = 6,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = WeshTouch.min)
                    .semantics { contentDescription = hint },
                decorationBox = { inner ->
                    Box(Modifier.heightIn(min = WeshTouch.min), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) Text(hint, style = WeshType.body, color = c.inkMuted, maxLines = 2)
                        inner()
                    }
                },
            )
            val sendFill = when {
                !canSend -> c.surfaceRaised
                isPublic -> c.publicColor
                else -> c.privateColor
            }
            val sendInk = when {
                !canSend -> c.inkMuted
                isPublic -> c.onPublic
                else -> c.onPrivate
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(WeshTouch.primary)
                    .paveClickable(shape, sendLabel, enabled = canSend) {
                        haptics.send()
                        onSend()
                    }
                    .background(sendFill, shape)
                    .semantics { contentDescription = sendLabel },
            ) {
                Icon(Icons.Rounded.ArrowUpward, contentDescription = null, tint = sendInk, modifier = Modifier.size(26.dp))
            }
        }
        if (ComposerLimits.counterVisible(bytes)) {
            val counter = stringResource(R.string.wesh_pave_composer_counter, bytes, ComposerLimits.MAX_BYTES)
            val text = if (over) "${stringResource(R.string.wesh_pave_composer_too_long)} · $counter" else counter
            Text(
                text,
                style = WeshType.caption,
                color = if (over) c.danger else c.inkMuted,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = WeshSpace.s1, end = WeshSpace.s1)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@PavePreviews
@Composable
private fun ComposerPreview() = PavePreview {
    Column(verticalArrangement = Arrangement.spacedBy(WeshSpace.s4)) {
        Composer("", {}, {}, PaveRoom.Public)
        Composer("", {}, {}, PaveRoom.Private, recipient = "Nora")
        Composer("On arrive dans cinq minutes.", {}, {}, PaveRoom.Private, recipient = "Nora")
        Composer("a".repeat(1850), {}, {}, PaveRoom.Public)
        Composer("a".repeat(2010), {}, {}, PaveRoom.Private, recipient = "Nora")
    }
}
