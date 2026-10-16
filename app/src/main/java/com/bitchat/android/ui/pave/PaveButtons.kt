package com.bitchat.android.ui.pave

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale

private enum class PaveButtonKind { Primary, Secondary, Danger }

/** Main action: filled `private`, 56 dp, full width. One per screen. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) = PaveButton(PaveButtonKind.Primary, text, onClick, modifier.fillMaxWidth(), enabled, icon)

/** Secondary action: transparent with a 2 dp `line-strong` border, 48 dp. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) = PaveButton(PaveButtonKind.Secondary, text, onClick, modifier, enabled, icon)

/** Danger action (block, delete one item). Erasing everything goes through [HoldToErase] instead. */
@Composable
fun DangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) = PaveButton(PaveButtonKind.Danger, text, onClick, modifier, enabled, icon)

@Composable
private fun PaveButton(
    kind: PaveButtonKind,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    icon: ImageVector?,
) {
    val c = WeshPave.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val (container, content) = when (kind) {
        PaveButtonKind.Primary -> c.privateColor to c.onPrivate
        PaveButtonKind.Secondary -> Color.Transparent to c.ink
        PaveButtonKind.Danger -> Color.Transparent to c.danger
    }
    val idleBorder = when (kind) {
        PaveButtonKind.Primary -> null
        PaveButtonKind.Secondary -> BorderStroke(WeshStroke.s2, c.lineStrong)
        PaveButtonKind.Danger -> BorderStroke(WeshStroke.s2, c.danger)
    }
    val minHeight: Dp = if (kind == PaveButtonKind.Primary) WeshTouch.primary else WeshTouch.min
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = minHeight),
        shape = RoundedCornerShape(WeshRadius.control),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = if (kind == PaveButtonKind.Primary) c.surfaceRaised else Color.Transparent,
            disabledContentColor = c.inkMuted,
        ),
        border = if (focused) BorderStroke(WeshStroke.focus, c.focus) else idleBorder,
        contentPadding = PaddingValues(horizontal = WeshSpace.s4, vertical = WeshSpace.s2),
        interactionSource = interaction,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(WeshSpace.s2))
        }
        if (kind == PaveButtonKind.Primary) {
            // Pavé Fluo: the main action reads like a poster line; TalkBack still hears it as typed.
            Text(
                text.uppercase(Locale.ROOT),
                style = WeshType.displaySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { contentDescription = text },
            )
        } else {
            Text(text, style = WeshType.label, textAlign = TextAlign.Center)
        }
    }
}

// region Previews

@PavePreviews
@Composable
private fun ButtonsPreview() = PavePreview {
    Column(verticalArrangement = Arrangement.spacedBy(WeshSpace.s3)) {
        PrimaryButton("Échanger", {}, icon = Icons.Rounded.QrCodeScanner)
        SecondaryButton("Montrer mon code", {})
        DangerButton("Bloquer Nora", {})
        PrimaryButton("Commencer", {}, enabled = false)
    }
}

// endregion
