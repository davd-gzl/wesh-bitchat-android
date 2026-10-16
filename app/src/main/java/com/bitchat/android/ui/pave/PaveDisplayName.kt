package com.bitchat.android.ui.pave

import java.text.BreakIterator
import java.text.Normalizer

/**
 * Makes a remote nickname safe to draw and to read aloud. Pavé components apply it themselves,
 * so a caller cannot forget it: no control, format (bidi overrides, zero-width), surrogate,
 * private-use or unassigned character; NFKC; at most [MAX_COMBINING_MARKS] marks per character;
 * one line; at most [MAX_GRAPHEMES] graphemes.
 * Same rules as the shared `safeDisplayName()` (inbound admission work), plus the grapheme clamp;
 * it can delegate there once that lands.
 */
object PaveDisplayName {
    const val MAX_GRAPHEMES = 32
    const val MAX_COMBINING_MARKS = 2
    private const val ELLIPSIS = "…"

    fun safe(raw: String?, fallback: String): String {
        if (raw.isNullOrEmpty()) return fallback
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        val out = StringBuilder(normalized.length)
        var marks = 0
        var index = 0
        while (index < normalized.length) {
            val codePoint = normalized.codePointAt(index)
            index += Character.charCount(codePoint)
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
        if (clean.isEmpty()) return fallback
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
