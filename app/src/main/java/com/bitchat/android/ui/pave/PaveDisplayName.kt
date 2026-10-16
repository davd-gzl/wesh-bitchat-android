package com.bitchat.android.ui.pave

import java.text.BreakIterator
import java.text.Normalizer

/**
 * Makes a remote nickname safe to draw and to read aloud. Pavé components apply it themselves,
 * so a caller cannot forget it: no control, format (bidi overrides, zero-width), surrogate,
 * private-use, unassigned or blank-glyph character (Hangul fillers, Braille blank, variation
 * selectors); NFKC; at most [MAX_COMBINING_MARKS] marks per character;
 * one line; at most [MAX_GRAPHEMES] graphemes, elided in the middle; at least one visible letter, digit, symbol or
 * punctuation, otherwise the fallback.
 * Same rules as the shared `safeDisplayName()` (inbound admission work), plus the grapheme clamp,
 * the blank-glyph and visibility checks and [remote]'s reserved-name masking;
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

    /** Sanitized [raw], with long names elided in the middle; [fallback] when nothing visible is left. */
    fun safe(raw: String?, fallback: String): String = clean(raw)?.let(::elideMiddle) ?: fallback

    /**
     * For a name that comes from someone else. Like [safe], but a name that reads as one of the
     * app's own words ([reserved]: "Vous", "Sans surnom", fallbacks, badges) or that contains the
     * masking marker shows [masked] applied to [fallback] instead of the remote text.
     * Comparison ignores case, accents, spacing and punctuation.
     */
    fun remote(raw: String?, fallback: String, reserved: Collection<String>, masked: (String) -> String): String {
        val name = clean(raw) ?: return fallback
        val key = comparisonKey(name)
        val markerKey = comparisonKey(masked(""))
        val impersonates = reserved.any { comparisonKey(it).let { r -> r.isNotEmpty() && r == key } } ||
            (markerKey.isNotEmpty() && key.contains(markerKey))
        return if (impersonates) masked(fallback) else elideMiddle(name)
    }

    /** Lowercase letters and digits only, without accents: "V.ó-us" and "vous" compare equal. */
    internal fun comparisonKey(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD)
        val out = StringBuilder()
        decomposed.codePoints().forEach { cp ->
            if (Character.isLetterOrDigit(cp)) out.appendCodePoint(Character.toLowerCase(cp))
        }
        return out.toString()
    }

    private fun clean(raw: String?): String? {
        if (raw.isNullOrEmpty()) return null
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
        return if (clean.codePoints().noneMatch(::isVisible)) null else clean
    }

    /**
     * At most [MAX_GRAPHEMES] graphemes, ellipsis included. A long name keeps its start and its end
     * ("Nora la vraie…ou pas"), so a suffix cannot be hidden behind a trusted-looking prefix.
     */
    internal fun elideMiddle(text: String): String {
        val iterator = BreakIterator.getCharacterInstance().apply { setText(text) }
        val bounds = mutableListOf(0)
        while (true) {
            val next = iterator.next()
            if (next == BreakIterator.DONE) break
            bounds += next
        }
        val count = bounds.size - 1
        if (count <= MAX_GRAPHEMES) return text
        val keep = MAX_GRAPHEMES - 1
        val head = (keep + 1) / 2
        val tail = keep - head
        return text.substring(0, bounds[head]).trimEnd() + ELLIPSIS + text.substring(bounds[count - tail]).trimStart()
    }
}
