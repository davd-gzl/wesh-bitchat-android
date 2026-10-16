package com.bitchat.android.ui.pave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PaveDisplayNameTest {
    private fun safe(raw: String?, reserved: List<String> = emptyList()) =
        PaveDisplayName.remote(raw, unnamed = "?", reserved = "!", reservedWords = reserved)

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
    fun `an invisible character inside the name hides the whole name`() {
        assertEquals("?", safe("Nora${rlo}gpj.exe"))
        assertEquals("?", safe("No${zwsp}ra$lri$pdi"))
        assertEquals("?", safe("Nora $zwsp Sami"))
    }

    @Test
    fun `invisible characters at either end are stripped`() {
        assertEquals("Nora", safe("$lri$zwsp Nora $pdi$rlo"))
    }

    @Test
    fun `stays on one line without control or private-use characters`() {
        assertEquals("?", safe("Nora\nVérifiée"))
        assertEquals("?", safe("Nora" + cp(0xE000) + "Sami"))
        val name = safe("\tNora Vérifiée\r\n" + cp(0x07, 0xE000))
        assertFalse(name.contains('\n') || name.contains('\r') || name.contains('\t'))
        assertEquals("Nora Vérifiée", name)
        assertEquals("Nora Sami", safe("Nora" + cp(0x2028) + "Sami"))
        assertEquals("Nora Sami", safe("  Nora " + cp(0x00A0, 0x3000) + " Sami  "))
    }

    @Test
    fun `lone surrogates are stripped at the ends and hide the name inside`() {
        assertEquals("Nora", safe("Nora" + 0xD800.toChar()))
        assertEquals("?", safe("No" + 0xD800.toChar() + "ra"))
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
        val result = safe(grapheme.repeat(40))
        assertEquals(grapheme.repeat(16) + "…" + grapheme.repeat(15), result)
        assertEquals("Nora la vraie qu…ongtemps ou pas", safe("Nora la vraie qui ecrit trop longtemps ou pas"))
        val exact = "a".repeat(PaveDisplayName.MAX_GRAPHEMES)
        assertEquals(exact, safe(exact))
    }

    @Test
    fun `blank but assigned code points are dropped`() {
        assertEquals("?", safe(cp(0x3164))) // Hangul filler
        assertEquals("?", safe(cp(0x2800))) // Braille blank
        assertEquals("?", safe(cp(0x1160))) // Hangul jungseong filler
        assertEquals("?", safe(cp(0x115F, 0xFFA0, 0x17B4, 0x17B5, 0x180B, 0x180E, 0xFE00, 0xFE0F)))
        assertEquals("Nora", safe(cp(0x3164) + "Nora" + cp(0x2800)))
        assertEquals("?", safe("No" + cp(0x3164) + "ra"))
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

    @Test
    fun `letters mixing confusable scripts hide the name`() {
        assertEquals("?", safe("N" + cp(0x043E) + "ra")) // Cyrillic o in a Latin name
        assertEquals("?", safe(cp(0x0391) + "lex")) // Greek Alpha
        assertEquals("Вера 42", safe("Вера 42"))
        assertEquals("小明 Lee", safe("小明 Lee"))
    }

    @Test
    fun `a name reading as a reserved word is masked`() {
        val reserved = listOf("Vous", "Sans surnom", "You")
        assertEquals("!", safe("v o u s", reserved))
        assertEquals("!", safe("Sans surnom Nora", reserved))
        assertEquals("You Nora", safe("You Nora", reserved)) // under 4 letters: only the whole name
        assertEquals("!", safe("Y0U", reserved))
        assertEquals("Nora", safe("Nora", reserved))
    }

    @Test
    fun `the elided name is checked too`() {
        // In full, "zz" splits it into "vo zz us"; once elided, only "vo…us" is left.
        val name = "Vo" + ".".repeat(15) + "zz" + ".".repeat(15) + "us"
        assertEquals("Vo" + ".".repeat(14) + "…" + ".".repeat(13) + "us", safe(name))
        assertEquals("!", safe(name, listOf("Vous")))
    }

    @Test
    fun `emoji keep their joiners and presentation selectors`() {
        val heart = cp(0x2764, 0xFE0F)
        val technologist = cp(0x1F469, 0x200D, 0x1F4BB)
        val keycapOne = cp(0x31, 0xFE0F, 0x20E3)
        assertEquals("$heart Nora", safe("$heart Nora"))
        assertEquals("Nora $heart", safe("Nora $heart"))
        assertEquals("$technologist Nora", safe("$technologist Nora"))
        assertEquals(keycapOne, safe(keycapOne))
        assertEquals(cp(0x2764, 0xFE0E), safe(cp(0x2764, 0xFE0E)))
    }

    @Test
    fun `joiners and selectors outside an emoji stay invisible`() {
        assertEquals("?", safe("No" + cp(0x200D) + "ra"))
        assertEquals("?", safe("No" + cp(0xFE0F) + "ra"))
        assertEquals("Nora", safe("Nora" + cp(0xFE0F)))
    }

    @Test
    fun `tag characters are refused even inside an emoji`() {
        // Black flag followed by tag letters and a cancel tag, then more text.
        assertEquals("?", safe(cp(0x1F3F4, 0xE0067, 0xE0062, 0xE007F) + " Nora"))
        assertEquals("?", safe("Nora" + cp(0xE0041, 0xE0042) + " Lee"))
    }

    @Test(timeout = 2_000)
    fun `a 100 000 character name is handled fast`() {
        val huge = "a ".repeat(50_000)
        assertEquals(100_000, huge.length)
        val elided = "a a a a a a a a…a a a a a a a a"
        assertEquals(elided, safe(huge, listOf("Vous", "Sans surnom")))
        assertEquals("!", safe("Vous $huge", listOf("Vous")))
    }

    @Test
    fun `only the first 256 code points of the raw name count`() {
        val filler = "a ".repeat(128)
        assertEquals("!", safe(filler.dropLast(6) + "Vous", listOf("Vous")))
        assertEquals("a a a a a a a a…a a a a a a a a", safe(filler + "Vous", listOf("Vous")))
        assertEquals("a a a a a a a a…a a a a a a a a", safe(filler + "No" + cp(0x200B) + "ra"))
    }

    @Test
    fun `default-ignorable code points are invisible, not marks`() {
        assertEquals("?", safe("Nora" + cp(0x034F) + "Lee")) // combining grapheme joiner
        assertEquals("?", safe("Nora" + cp(0xE0100) + "Lee")) // variation selector 17
        assertEquals("?", safe("Nora" + cp(0x180F) + "Lee")) // Mongolian free variation selector four
        assertEquals("?", safe("Nora" + cp(0xFFF0) + "Lee")) // reserved default-ignorable
        assertEquals("Nora", safe("Nora" + cp(0x034F, 0xE0100)))
    }

    @Test
    fun `capital I and small i read like l on both sides`() {
        val reserved = listOf("No nickname", "Not verified", "Failed")
        assertEquals("!", safe("NO NICKNAME", reserved))
        assertEquals("!", safe("NOT VERIFIED", reserved))
        assertEquals("!", safe("FAILED", reserved))
        assertEquals("!", safe("Fa1led", reserved))
        assertEquals("Lina", safe("Lina", reserved))
    }

    @Test
    fun `emoji joiners and selectors are kept only in their exact place`() {
        val heart = cp(0x2764)
        val fire = cp(0x1F525)
        assertEquals(heart + cp(0xFE0F, 0x200D) + fire, safe(heart + cp(0xFE0F, 0x200D) + fire))
        assertEquals(cp(0x23, 0xFE0F, 0x20E3), safe(cp(0x23, 0xFE0F, 0x20E3)))
        assertEquals("?", safe(heart + cp(0xFE0F, 0xFE0F) + " Nora")) // doubled selector
        assertEquals("?", safe(heart + cp(0x200D) + " Nora")) // joiner not followed by a symbol
        assertEquals("?", safe(cp(0x1F469, 0x200D, 0x200D, 0x1F4BB) + " Nora")) // doubled joiner
        assertEquals("?", safe("Nora " + cp(0x200D) + heart)) // joiner not preceded by a symbol
        assertEquals("?", safe("A" + cp(0xFE0F, 0x20E3) + " Nora")) // keycap needs 0-9, # or *
    }

    @Test
    fun `kept selectors do not reset the mark budget`() {
        val heart = cp(0x2764, 0xFE0F)
        assertEquals("$heart$acute$acute", safe(heart + acute.repeat(4)))
    }

    @Test
    fun `a skin tone may sit between a symbol and its joiner`() {
        val coder = cp(0x1F469, 0x1F3FD, 0x200D, 0x1F4BB)
        assertEquals("$coder Nora", safe("$coder Nora"))
        assertEquals("?", safe("Nora" + cp(0x1F3FD, 0x200D, 0x1F4BB))) // tone without a symbol before it
    }

    @Test
    fun `a name imitating the short fingerprint is masked`() {
        assertEquals("!", safe("Nora A1B2 C3D4"))
        assertEquals("!", safe("a1b2c3d4"))
        assertEquals("!", safe("A1B2-C3D4 Nora"))
        assertEquals("!", safe("Nora 1010 1010")) // checked before 1 and 0 are folded to l and o
        assertEquals("Nora 2024", safe("Nora 2024"))
        assertEquals("Café 2024", safe("Café 2024")) // each group needs a digit
        assertEquals("dead beef", safe("dead beef"))
        assertEquals("A1B2 Nora C3D4", safe("A1B2 Nora C3D4"))
    }
}
