package com.bitchat.android.ui.pave

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import com.bitchat.android.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * "Effacer tout": hold three seconds while a `danger` ring fills, with rising haptic pulses;
 * releasing cancels. With TalkBack, a double tap opens a one-line confirmation instead, no timer.
 * Never wipes on a single tap.
 *
 * @param compact the Settings first row (72 dp ring beside the text) instead of the full screen layout.
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
    val ringSize: Dp = if (compact) 72.dp else 112.dp

    val ring = Modifier
        .size(ringSize.coerceAtLeast(WeshTouch.min))
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

    val ringContent: @Composable () -> Unit = {
        Box(ring, contentAlignment = Alignment.Center) {
            EraseRing(progress.value, ringSize)
            Icon(
                Icons.Rounded.LocalFireDepartment,
                contentDescription = null,
                tint = c.onDanger,
                modifier = Modifier.size(ringSize * 0.3f),
            )
        }
    }

    Column(modifier, horizontalAlignment = if (compact) Alignment.Start else Alignment.CenterHorizontally) {
        if (compact) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ringContent()
                Spacer(Modifier.width(WeshSpace.s4))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(WeshSpace.s1)) {
                    Text(holdLabel, style = WeshType.label, color = c.danger)
                    Text(scopeText, style = WeshType.caption, color = c.inkMuted)
                }
            }
        } else {
            ringContent()
            Spacer(Modifier.height(WeshSpace.s3))
            Text(holdLabel, style = WeshType.label, color = c.danger, textAlign = TextAlign.Center)
            Spacer(Modifier.height(WeshSpace.s1))
            Text(scopeText, style = WeshType.caption, color = c.inkMuted, textAlign = TextAlign.Center)
        }
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

@Composable
private fun EraseRing(progress: Float, size: Dp) {
    val c = WeshPave.colors
    Canvas(Modifier.size(size)) {
        val stroke = this.size.minDimension * 0.07f
        val radius = (this.size.minDimension - stroke) / 2f
        val topLeft = Offset(center.x - radius, center.y - radius)
        drawCircle(c.line, radius, style = Stroke(stroke))
        drawArc(
            color = c.danger,
            startAngle = -90f,
            sweepAngle = 360f * progress,
            useCenter = false,
            topLeft = topLeft,
            size = Size(radius * 2, radius * 2),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
        drawCircle(c.danger, radius * 0.7f)
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

/** After erasing: a neutral "Wesh est vide", or the failure with a retry. */
@Composable
fun EraseResultScreen(succeeded: Boolean, onRestart: () -> Unit, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val c = WeshPave.colors
    Column(
        modifier
            .fillMaxSize()
            .background(c.bg)
            .systemBarsPadding()
            .padding(WeshSpace.gutter),
        verticalArrangement = Arrangement.spacedBy(WeshSpace.s3),
    ) {
        Spacer(Modifier.weight(1f))
        Text(
            stringResource(if (succeeded) R.string.wesh_pave_erased_title else R.string.wesh_pave_erase_failed_title),
            style = WeshType.title,
            color = if (succeeded) c.ink else c.danger,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Text(
            stringResource(if (succeeded) R.string.wesh_pave_erased_body else R.string.wesh_pave_erase_failed_body),
            style = WeshType.body,
            color = c.ink,
        )
        Spacer(Modifier.weight(1f))
        if (succeeded) PrimaryButton(stringResource(R.string.wesh_pave_erased_restart), onRestart)
        else PrimaryButton(stringResource(R.string.wesh_pave_retry), onRetry)
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

@PaveScreenPreviews
@Composable
private fun EraseResultPreview() = PavePreview(padded = false) { EraseResultScreen(true, {}, {}) }
