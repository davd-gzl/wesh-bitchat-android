package com.bitchat.android.ui.pave

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bitchat.android.R
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.drawWithContent
import java.util.Locale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * "Tout effacer": hold three seconds while hazard stripes fill the `danger` bar, with rising haptic pulses;
 * releasing cancels. With TalkBack, a double tap opens a one-line confirmation instead, no timer.
 * Never wipes on a single tap.
 *
 * @param compact the Settings block (bar, then the scope line) instead of the full screen layout.
 */
@Composable
fun HoldToErase(
    onErase: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    showShortcutHint: Boolean = false,
) {
    val c = WeshPave.colors
    val haptics = rememberWeshHaptics()
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    val resetMs = WeshPave.duration(WeshMotion.STATE_CHANGE_MS)
    val currentOnErase by rememberUpdatedState(onErase)
    var confirming by rememberSaveable { mutableStateOf(false) }

    val holdLabel = stringResource(R.string.wesh_pave_erase_hold)
    val a11yLabel = stringResource(R.string.wesh_pave_erase_hold_a11y)
    val actionLabel = stringResource(R.string.wesh_pave_erase_action)
    val holdingState = stringResource(R.string.wesh_pave_erase_holding, (progress.value * 100).toInt())
    val scopeText = stringResource(R.string.wesh_pave_erase_scope) +
        if (showShortcutHint) " " + stringResource(R.string.wesh_pave_erase_shortcut_hint) else ""
    val bar = Modifier
        .fillMaxWidth()
        .heightIn(min = if (compact) 64.dp else 72.dp)
        .semantics {
            role = Role.Button
            contentDescription = a11yLabel
            if (progress.value > 0f) stateDescription = holdingState
            onClick(label = actionLabel) { confirming = true; true }
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var done = false
                val hold: Job = scope.launch {
                    val start = withFrameMillis { it }
                    var fired = 0
                    while (true) {
                        val elapsed = withFrameMillis { it } - start
                        progress.snapTo(HoldToEraseTiming.progress(elapsed))
                        val due = HoldToEraseTiming.pulsesDue(elapsed)
                        while (fired < due) haptics.holdPulse(HoldToEraseTiming.isStrongPulse(fired++))
                        if (HoldToEraseTiming.isComplete(elapsed)) {
                            done = true
                            haptics.heavy()
                            currentOnErase()
                            break
                        }
                    }
                }
                waitForUpOrCancellation()
                if (!done) {
                    hold.cancel()
                    scope.launch { progress.animateTo(0f, tween(resetMs)) }
                }
            }
        }

    val barContent: @Composable () -> Unit = {
        EraseBar(progress.value, actionLabel, bar)
    }
    val align = if (compact) TextAlign.Start else TextAlign.Center

    Column(modifier.fillMaxWidth(), horizontalAlignment = if (compact) Alignment.Start else Alignment.CenterHorizontally) {
        barContent()
        Spacer(Modifier.height(WeshSpace.s2))
        Text(holdLabel, style = WeshType.label, color = c.ink, textAlign = align, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(WeshSpace.s1))
        Text(scopeText, style = WeshType.caption, color = c.inkMuted, textAlign = align, modifier = Modifier.fillMaxWidth())
        if (confirming) {
            Spacer(Modifier.height(WeshSpace.s4))
            Text(
                stringResource(R.string.wesh_pave_erase_confirm_question),
                style = WeshType.body,
                color = c.ink,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
            Spacer(Modifier.height(WeshSpace.s2))
            Row(horizontalArrangement = Arrangement.spacedBy(WeshSpace.s2)) {
                DangerButton(stringResource(R.string.wesh_pave_erase_confirm), {
                    confirming = false
                    haptics.heavy()
                    onErase()
                })
                SecondaryButton(stringResource(R.string.wesh_pave_cancel), { confirming = false })
            }
        }
    }
}

/**
 * The danger bar: `danger` fill with the label in poster capitals; while held, hazard stripes
 * cover it from the start edge in proportion to [progress].
 */
@Composable
private fun EraseBar(progress: Float, label: String, modifier: Modifier) {
    val c = WeshPave.colors
    val shape = RoundedCornerShape(WeshRadius.control)
    Box(
        modifier
            .clip(shape)
            .background(c.danger)
            .border(WeshStroke.publicRule, c.danger, shape),
        contentAlignment = Alignment.Center,
    ) {
        // Same size as the bar without taking part in its measure, so the bar keeps its own
        // height; the stripes cover it from the start edge in proportion to the hold.
        Box(
            Modifier
                .matchParentSize()
                .drawWithContent {
                    val end = size.width * progress.coerceIn(0f, 1f)
                    val left = if (layoutDirection == LayoutDirection.Rtl) size.width - end else 0f
                    clipRect(left = left, right = left + end) { this@drawWithContent.drawContent() }
                }
                .paveHazard(c.danger, c.bg),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WeshSpace.s2),
            modifier = Modifier
                // The bar itself carries the button semantics; its label is not a second stop.
                .clearAndSetSemantics {}
                .padding(vertical = WeshSpace.s2)
                .background(if (progress > 0f) c.bg else Color.Transparent, RoundedCornerShape(WeshRadius.tail))
                .padding(horizontal = WeshSpace.s2, vertical = WeshSpace.s1),
        ) {
            Icon(
                Icons.Rounded.LocalFireDepartment,
                contentDescription = null,
                tint = if (progress > 0f) c.danger else c.onDanger,
                modifier = Modifier.size(26.dp),
            )
            Text(
                label.uppercase(Locale.ROOT),
                style = WeshType.displaySmall,
                color = if (progress > 0f) c.ink else c.onDanger,
            )
        }
    }
}

/** Full screen reached from Settings or the "Tout effacer" app shortcut. Never wipes on its own. */
@Composable
fun HoldToEraseScreen(onErase: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .background(WeshPave.colors.bg)
            .systemBarsPadding()
            .padding(horizontal = WeshSpace.gutter),
    ) {
        PaveHeader(stringResource(R.string.wesh_pave_erase_action), onClose = onClose)
        Spacer(Modifier.weight(1f))
        HoldToErase(onErase, Modifier.fillMaxWidth().padding(vertical = WeshSpace.s12), showShortcutHint = false)
        Spacer(Modifier.weight(1f))
    }
}

@PavePreviews
@Composable
private fun HoldToErasePreview() = PavePreview {
    Column(verticalArrangement = Arrangement.spacedBy(WeshSpace.s6)) {
        HoldToErase({}, compact = true, showShortcutHint = true)
        HoldToErase({})
    }
}

@PaveScreenPreviews
@Composable
private fun HoldToEraseScreenPreview() = PavePreview(padded = false) { HoldToEraseScreen({}, {}) }
