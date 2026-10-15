package com.bitchat.android.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class WeshContrastTest {
    private fun contrast(foreground: Color, background: Color): Float {
        val first = foreground.luminance()
        val second = background.luminance()
        return (maxOf(first, second) + 0.05f) / (minOf(first, second) + 0.05f)
    }

    @Test
    fun normalTextAndDestructiveActionsMeetTextContrastOnTheirActualSurfaces() {
        val scheme = weshColorScheme()
        val pairs = mapOf(
            "body" to (scheme.onBackground to scheme.background),
            "surface" to (scheme.onSurface to scheme.surface),
            "secondary text" to (scheme.onSurfaceVariant to scheme.surfaceVariant),
            "filled action" to (scheme.onPrimary to scheme.primary),
            "outgoing message" to (scheme.onPrimaryContainer to scheme.primaryContainer),
            "selected tab" to (scheme.onSecondaryContainer to scheme.secondaryContainer),
            "outlined action" to (scheme.primary to scheme.surface),
            "destructive action" to (scheme.error to scheme.surface)
        )
        pairs.forEach { (name, pair) ->
            val ratio = contrast(pair.first, pair.second)
            assertTrue("$name contrast $ratio must be at least 4.5:1", ratio >= 4.5f)
        }
    }

    @Test
    fun blackButtonTextMeetsContrastAtEveryMetalGradientStop() {
        listOf(WeshColors.silverShadow, Color.White, WeshColors.silver).forEach { stop ->
            assertTrue(contrast(Color.Black, stop) >= 4.5f)
        }
    }

    @Test
    fun inputBoundaryRemainsVisibleAgainstBlack() {
        val scheme = weshColorScheme()
        assertTrue(contrast(scheme.outline, scheme.surface) >= 3f)
    }
}
