package com.bitchat.android.ui.pave

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.SensorsOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bitchat.android.R
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Pavé Fluo signatures: the asphalt grain, the hazard stripes reserved for erasing,
 * and the row of pavés that light up, one per phone in reach.
 */
object PaveFluo {
    /** Pavés in the reach gauge. More phones than this keep the row full. */
    const val GAUGE_PAVES = 12

    /** Night grain on asphalt, day grain on poster paper: 1 dp dots every 4 dp. */
    val GrainNight = Color(0x0FFFFFFF)
    val GrainDay = Color(0x12000000)

    fun litPaves(phonesInReach: Int): Int = phonesInReach.coerceIn(0, GAUGE_PAVES)

    /** Time between two pavés lighting, so a full wave never exceeds [WeshMotion.MESH_MAX_MS]. */
    fun stepMs(from: Int, to: Int): Int {
        val steps = abs(litPaves(to) - litPaves(from))
        if (steps == 0) return 0
        return minOf(WeshMotion.MESH_RING_MS, WeshMotion.MESH_MAX_MS / steps)
    }

    /** Whole wave length; zero when animations are off. */
    fun waveMs(from: Int, to: Int, animationsEnabled: Boolean): Int =
        if (!animationsEnabled) 0 else stepMs(from, to) * abs(litPaves(to) - litPaves(from))

    /** The giant numeral follows the system font size up to 1.3×, then stops growing. */
    fun numeralScale(fontScale: Float): Float = if (fontScale <= MAX_NUMERAL_SCALE) 1f else MAX_NUMERAL_SCALE / fontScale

    const val MAX_NUMERAL_SCALE = 1.3f
}

/** Asphalt or poster-paper grain, painted once as a repeated tile behind the content. */
fun Modifier.paveGrain(night: Boolean): Modifier = drawWithCache {
    val step = 4.dp.toPx().coerceAtLeast(2f)
    val tileSize = ceil(step).toInt()
    val tile = ImageBitmap(tileSize, tileSize)
    val dot = if (night) PaveFluo.GrainNight else PaveFluo.GrainDay
    CanvasDrawScope().draw(this, layoutDirection, Canvas(tile), Size(tileSize.toFloat(), tileSize.toFloat())) {
        drawCircle(dot, radius = 0.5.dp.toPx().coerceAtLeast(0.5f), center = Offset(tileSize / 2f, tileSize / 2f))
    }
    val brush = ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
    onDrawBehind { drawRect(brush) }
}

/** 45° stripes, [color] over [base]. Only for what cannot be undone (Tout effacer). */
fun Modifier.paveHazard(color: Color, base: Color, band: Dp = 12.dp): Modifier = drawWithCache {
    val b = band.toPx()
    val stripes = Path().apply {
        var x = -size.height
        while (x < size.width + size.height) {
            moveTo(x, size.height)
            lineTo(x + size.height, 0f)
            lineTo(x + size.height + b, 0f)
            lineTo(x + b, size.height)
            close()
            x += 2 * b
        }
    }
    onDrawBehind {
        drawRect(base)
        drawPath(stripes, color)
    }
}

/**
 * The reach gauge: twelve pavés, one lit in `public` yellow per phone in reach.
 * When the count changes the pavés light (or go out) one after another, in under 900 ms;
 * with animations off they change at once. Decorative: callers state the count in text.
 */
@Composable
fun PaveReachGauge(phonesInReach: Int, modifier: Modifier = Modifier) {
    val c = WeshPave.colors
    val animations = WeshPave.animationsEnabled
    val target = PaveFluo.litPaves(phonesInReach)
    val lit = remember { Animatable(target.toFloat()) }
    LaunchedEffect(target, animations) {
        val from = lit.value.toInt()
        val ms = PaveFluo.waveMs(from, target, animations)
        if (ms == 0) lit.snapTo(target.toFloat())
        else lit.animateTo(target.toFloat(), tween(ms, easing = LinearEasing))
    }
    val shape = RoundedCornerShape(2.dp)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(PaveFluo.GAUGE_PAVES) { i ->
            val on = i < lit.value
            Box(
                Modifier
                    .weight(1f)
                    .height(8.dp)
                    .background(if (on) c.publicColor else c.line, shape),
            )
        }
    }
}

/** [WeshType.numeral], capped at 1.3× the system font size so one figure never fills the screen. */
@Composable
fun paveNumeralStyle(): TextStyle {
    val scale = PaveFluo.numeralScale(LocalDensity.current.fontScale)
    val base = WeshType.numeral
    return if (scale == 1f) base else base.copy(
        fontSize = base.fontSize * scale,
        lineHeight = base.lineHeight * scale,
    )
}

/**
 * Home reach card: the giant count of phones in reach, its label, the Bluetooth state and the
 * pavé gauge. TalkBack reads one sentence ("7 téléphones à portée, Bluetooth actif").
 */
@Composable
fun PaveReachCard(phonesInReach: Int, bluetoothOn: Boolean, modifier: Modifier = Modifier) {
    val c = WeshPave.colors
    val count = phonesInReach.coerceAtLeast(0)
    val spoken = pluralStringResource(R.plurals.wesh_pave_status_in_range, count, count) + ", " +
        stringResource(if (bluetoothOn) R.string.wesh_pave_fluo_bluetooth_on else R.string.wesh_pave_fluo_bluetooth_off)
    val shape = RoundedCornerShape(WeshRadius.control)
    Row(
        horizontalArrangement = Arrangement.spacedBy(WeshSpace.s3),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(c.surface, shape)
            .border(WeshStroke.s2, c.line, shape)
            .padding(horizontal = WeshSpace.s4, vertical = WeshSpace.s3)
            .clearAndSetSemantics { contentDescription = spoken },
    ) {
        Text(count.toString(), style = paveNumeralStyle(), color = c.ink)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(WeshSpace.s1)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WeshSpace.s1)) {
                Icon(
                    if (bluetoothOn) Icons.Rounded.Sensors else Icons.Rounded.SensorsOff,
                    contentDescription = null,
                    tint = if (bluetoothOn) c.success else c.warning,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    stringResource(if (bluetoothOn) R.string.wesh_pave_fluo_bluetooth_on else R.string.wesh_pave_fluo_bluetooth_off),
                    style = WeshType.caption,
                    color = if (bluetoothOn) c.success else c.warning,
                )
            }
            Text(pluralStringResource(R.plurals.wesh_pave_fluo_reach_label, count), style = WeshType.headline, color = c.ink)
            PaveReachGauge(count)
        }
    }
}

@PavePreviews
@Composable
private fun PaveReachCardPreview() = PavePreview {
    Column(verticalArrangement = Arrangement.spacedBy(WeshSpace.s3)) {
        PaveReachCard(7, bluetoothOn = true)
        PaveReachCard(0, bluetoothOn = false)
        Box(Modifier.fillMaxWidth().height(48.dp).paveHazard(WeshPave.colors.danger, WeshPave.colors.bg))
    }
}
