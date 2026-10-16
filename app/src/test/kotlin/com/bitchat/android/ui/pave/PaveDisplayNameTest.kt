package com.bitchat.android.ui.pave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaveDisplayNameTest {
    private fun safe(raw: String?) = PaveDisplayName.safe(raw, fallback = "?")

    /** Builds strings from code points so no invisible character sits in this source file. */
    private fun cp(vararg codePoints: Int) = String(codePoints, 0, codePoints.size)

    private val rlo = cp(0x202E) // right-to-left override
    private val zwsp = cp(0x200B) // zero-width space
    private val lri = cp(0x2066) // left-to-right isolate
    private val pdi = cp(0x2069) // pop directional isolate
    private val acute = cp(0x0301) // combining acute accent

    @Test
    fun `keeps an ordinary nickname`() {
        assertEquals("Renard 42", safe("Renard 42"))
        assertEquals("Léo 17", safe("Léo 17"))
    }

    @Test
    fun `strips bidi overrides and invisible format characters`() {
        assertEquals("Noragpj.exe", safe("Nora${rlo}gpj.exe"))
        assertEquals("Nora", safe("No${zwsp}ra$lri$pdi"))
    }

    @Test
    fun `stays on one line without control or private-use characters`() {
        val name = safe("Nora\nVérifiée\r\t" + cp(0x07, 0xE000))
        assertFalse(name.contains('\n') || name.contains('\r') || name.contains('\t'))
        assertEquals("NoraVérifiée", name)
        assertEquals("Nora Sami", safe("Nora" + cp(0x2028) + "Sami"))
        assertEquals("Nora Sami", safe("  Nora " + cp(0x00A0, 0x3000) + " Sami  "))
    }

    @Test
    fun `lone surrogates are removed`() {
        assertEquals("Nora", safe("No" + 0xD800.toChar() + "ra"))
    }

    @Test
    fun `NFKC folds look-alike compatibility forms`() {
        assertEquals("Nora", safe(cp(0xFF2E, 0xFF4F, 0xFF52, 0xFF41))) // fullwidth "Nora"
        assertEquals("fi", safe(cp(0xFB01))) // "fi" ligature
    }

    @Test
    fun `stacked combining marks are capped and a leading mark is dropped`() {
        // "x" has no precomposed accented form, so NFKC leaves the marks in place.
        assertEquals("x$acute${acute}ora", safe("x" + acute.repeat(6) + "ora"))
        assertEquals("Nora", safe("${acute}Nora"))
    }

    @Test
    fun `long names are elided in the middle on whole graphemes`() {
        val grapheme = "x$acute$acute"
        val max = PaveDisplayName.MAX_GRAPHEMES
        val result = safe(grapheme.repeat(40))
        val head = max / 2
        val tail = max - 1 - head
        assertEquals(grapheme.repeat(head) + "…" + grapheme.repeat(tail), result)
        val exact = "a".repeat(max)
        assertEquals(exact, safe(exact))
    }

    @Test
    fun `middle elision keeps the end of a long name visible`() {
        val name = "Nora officielle verifiee par Wesh mais en fait Mallory"
        val result = safe(name)
        assertTrue(result.startsWith("Nora officielle…"))
        assertTrue(result.endsWith("Mallory"))
        assertTrue(result.contains("…"))
    }

    private val reserved = listOf("Sans surnom", "Vous", "Votre contact", "Code vérifié", "Non vérifié")
    private fun remote(raw: String?, fallback: String = "Sans surnom") =
        PaveDisplayName.remote(raw, fallback, reserved) { "$it · surnom masqué" }

    @Test
    fun `remote names that imitate the app's own words are masked`() {
        assertEquals("Sans surnom · surnom masqué", remote("Vous"))
        assertEquals("Sans surnom · surnom masqué", remote("  v.O-u_s "))
        assertEquals("Sans surnom · surnom masqué", remote("SANS SURNOM"))
        assertEquals("Votre contact · surnom masqué", remote("Code verifie", fallback = "Votre contact"))
        assertEquals("Sans surnom · surnom masqué", remote("Vo${zwsp}us"))
    }

    @Test
    fun `a remote name cannot fake the masking marker`() {
        assertEquals("Sans surnom · surnom masqué", remote("Nora · surnom masqué"))
        assertEquals("Sans surnom · surnom masqué", remote("Nora (surnom masque)"))
    }

    @Test
    fun `ordinary remote names pass and empty ones get the plain fallback`() {
        assertEquals("Nora", remote("Nora"))
        assertEquals("Vousmann", remote("Vousmann"))
        assertEquals("Sans surnom", remote(cp(0x3164)))
        assertEquals("Sans surnom", remote(null))
    }

    @Test
    fun `blank but assigned code points are dropped`() {
        assertEquals("?", safe(cp(0x3164))) // Hangul filler
        assertEquals("?", safe(cp(0x2800))) // Braille blank
        assertEquals("?", safe(cp(0x1160))) // Hangul jungseong filler
        assertEquals("?", safe(cp(0x115F, 0xFFA0, 0x17B4, 0x17B5, 0x180B, 0x180E, 0xFE00, 0xFE0F)))
        assertEquals("Nora", safe("No" + cp(0x3164) + "ra" + cp(0x2800)))
    }

    @Test
    fun `a name needs at least one visible character`() {
        assertEquals("?", safe(acute + acute))
        assertEquals("-", safe("-"))
        assertEquals("42", safe(" 42 "))
    }

    @Test
    fun `empty or invisible names fall back`() {
        assertEquals("?", safe(null))
        assertEquals("?", safe("   "))
        assertEquals("?", safe(rlo + zwsp + cp(0)))
    }
}
