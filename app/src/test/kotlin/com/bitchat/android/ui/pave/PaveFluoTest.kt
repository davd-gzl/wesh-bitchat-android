package com.bitchat.android.ui.pave

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class PaveFluoTest {
    private fun channel(v: Float): Double = if (v <= 0.03928f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    private fun luminance(c: Color) = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun `the gauge lights one pave per phone and stays full beyond twelve`() {
        assertEquals(0, PaveFluo.litPaves(-3))
        assertEquals(7, PaveFluo.litPaves(7))
        assertEquals(12, PaveFluo.litPaves(40))
    }

    @Test
    fun `a lighting wave never exceeds 900 ms and stops when animations are off`() {
        assertEquals(WeshMotion.MESH_RING_MS * 7, PaveFluo.waveMs(0, 7, animationsEnabled = true))
        assertTrue(PaveFluo.waveMs(0, 12, animationsEnabled = true) <= WeshMotion.MESH_MAX_MS)
        assertEquals(WeshMotion.MESH_RING_MS * 2, PaveFluo.waveMs(7, 5, animationsEnabled = true))
        assertEquals(0, PaveFluo.waveMs(0, 12, animationsEnabled = false))
        assertEquals(0, PaveFluo.waveMs(4, 4, animationsEnabled = true))
    }

    @Test
    fun `the giant numeral follows the font size up to 1_3x only`() {
        assertEquals(1f, PaveFluo.numeralScale(1f))
        assertEquals(1f, PaveFluo.numeralScale(1.3f))
        assertEquals(1.3f, 2f * PaveFluo.numeralScale(2f), 0.0001f)
    }

    @Test
    fun `text and controls keep their contrast in both themes`() {
        listOf(WeshTokens.Nuit, WeshTokens.Jour).forEach { c ->
            val theme = if (c.isNight) "Nuit" else "Jour"
            listOf(c.bg, c.surface).forEach { ground ->
                assertTrue("$theme ink", contrast(c.ink, ground) >= 15.0)
                assertTrue("$theme ink-muted", contrast(c.inkMuted, ground) >= 4.5)
                assertTrue("$theme line-strong", contrast(c.lineStrong, ground) >= 3.0)
                assertTrue("$theme danger", contrast(c.danger, ground) >= 4.5)
                assertTrue("$theme success", contrast(c.success, ground) >= 4.5)
            }
            assertTrue("$theme ink-muted on raised", contrast(c.inkMuted, c.surfaceRaised) >= 4.5)
            assertTrue("$theme on-public", contrast(c.onPublic, c.publicColor) >= 15.0)
            assertTrue("$theme on-private", contrast(c.onPrivate, c.privateColor) >= 15.0)
            assertTrue("$theme on-internet", contrast(c.onInternet, c.internet) >= 4.5)
            assertTrue("$theme on-danger", contrast(c.onDanger, c.danger) >= 4.5)
        }
    }

    @Test
    fun `the fluo yellow is the Pave Fluo one and never text on the day ground`() {
        assertEquals(Color(0xFFE4FF1A), WeshTokens.Nuit.publicColor)
        assertEquals(Color(0xFFE4FF1A), WeshTokens.Jour.publicColor)
        assertTrue(contrast(WeshTokens.Jour.publicColor, WeshTokens.Jour.bg) < 1.5)
    }
}
