package com.bitchat.android.ui.pave

import java.text.BreakIterator
import java.text.Normalizer

/**
 * Makes a remote nickname safe to draw and to read aloud. Pavé components apply it themselves,
 * so a caller cannot forget it: no control, format (bidi overrides, zero-width), surrogate,
 * private-use, unassigned or blank-glyph character (Hangul fillers, Braille blank, variation
 * selectors); NFKC; at most [MAX_COMBINING_MARKS] marks per character;
 * one line; at most [MAX_GRAPHEMES] graphemes; at least one visible letter, digit, symbol or
 * punctuation, otherwise the fallback.
 * Same rules as the shared `safeDisplayName()` (inbound admission work), plus the grapheme clamp
 * and the blank-glyph and visibility checks;
 * it can delegate there once that lands.
 */
object PaveDisplayName {
    const val MAX_GRAPHEMES = 32
    const val MAX_COMBINING_MARKS = 2
    private const val ELLIPSIS = "…"

    /**
     * Assigned code points that still draw nothing: Hangul fillers, Braille blank, Khmer inherent
     * vowels, Mongolian variation selectors and vowel separator, variation selectors.
     */
    private fun isBlankGlyph(codePoint: Int): Boolean =
        codePoint == 0x115F || codePoint == 0x1160 || codePoint == 0x3164 || codePoint == 0xFFA0 ||
            codePoint == 0x2800 || codePoint == 0x17B4 || codePoint == 0x17B5 ||
            codePoint in 0x180B..0x180E || codePoint in 0xFE00..0xFE0F

    private fun isVisible(codePoint: Int): Boolean = when (Character.getType(codePoint).toByte()) {
        Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER,
        Character.MODIFIER_LETTER, Character.OTHER_LETTER,
        Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER,
        Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL, Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL,
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
        Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
        Character.OTHER_PUNCTUATION -> true
        else -> false
    }

    fun safe(raw: String?, fallback: String): String {
        if (raw.isNullOrEmpty()) return fallback
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        val out = StringBuilder(normalized.length)
        var marks = 0
        var index = 0
        while (index < normalized.length) {
            val codePoint = normalized.codePointAt(index)
            index += Character.charCount(codePoint)
            if (isBlankGlyph(codePoint)) continue
            when (Character.getType(codePoint).toByte()) {
                Character.CONTROL, Character.FORMAT, Character.SURROGATE,
                Character.PRIVATE_USE, Character.UNASSIGNED -> continue
                Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> {
                    if (out.isEmpty() || out.last() == ' ' || ++marks > MAX_COMBINING_MARKS) continue
                }
                else -> marks = 0
            }
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                if (out.isNotEmpty() && out.last() != ' ') out.append(' ')
            } else {
                out.appendCodePoint(codePoint)
            }
        }
        val clean = out.toString().trim()
        if (clean.codePoints().noneMatch(::isVisible)) return fallback
        return clampGraphemes(clean)
    }

    private fun clampGraphemes(text: String): String {
        val graphemes = BreakIterator.getCharacterInstance().apply { setText(text) }
        var end = 0
        repeat(MAX_GRAPHEMES) {
            val next = graphemes.next()
            if (next == BreakIterator.DONE) return text
            end = next
        }
        return if (graphemes.next() == BreakIterator.DONE) text else text.substring(0, end).trimEnd() + ELLIPSIS
    }
}
