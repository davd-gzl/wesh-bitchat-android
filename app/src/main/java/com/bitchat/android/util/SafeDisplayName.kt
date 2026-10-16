package com.bitchat.android.util

import java.text.Normalizer

/** Longest remote nickname shown, in code points, before an ellipsis. */
const val MAX_DISPLAY_NAME_CODE_POINTS = 32
private const val MAX_COMBINING_MARKS = 2

/** Assigned code points that render blank (Hangul fillers, braille blank, variation selectors…). */
private fun isBlankFiller(codePoint: Int): Boolean = when (codePoint) {
    0x115F, 0x1160, 0x3164, 0xFFA0, 0x2800, 0x17B4, 0x17B5 -> true
    in 0x180B..0x180E, in 0xFE00..0xFE0F -> true
    else -> false
}

/** A letter, digit, symbol or punctuation: something a reader can actually see. */
private fun isVisible(codePoint: Int): Boolean = when (Character.getType(codePoint).toByte()) {
    Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER,
    Character.MODIFIER_LETTER, Character.OTHER_LETTER,
    Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER,
    Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL, Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL,
    Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
    Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
    Character.OTHER_PUNCTUATION -> !isBlankFiller(codePoint)
    else -> false
}

/**
 * The one sanitizer for every remote nickname shown in Wesh. It NFKC-normalizes, strips control,
 * format (bidi overrides, zero-width), surrogate, private-use and unassigned code points, caps
 * blank fillers, caps stacked combining marks, collapses whitespace and truncates. A name with no
 * visible letter, digit, symbol or punctuation uses [fallback].
 */
fun safeDisplayName(raw: String, fallback: String): String {
    val normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
    val out = StringBuilder()
    var marks = 0
    var index = 0
    while (index < normalized.length) {
        val codePoint = normalized.codePointAt(index)
        index += Character.charCount(codePoint)
        if (isBlankFiller(codePoint)) continue
        when (Character.getType(codePoint).toByte()) {
            Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE,
            Character.UNASSIGNED -> continue
            Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> {
                if (out.isEmpty() || ++marks > MAX_COMBINING_MARKS) continue
            }
            else -> marks = 0
        }
        out.appendCodePoint(if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) ' '.code else codePoint)
    }
    val clean = out.toString().replace(Regex(" {2,}"), " ").trim()
    var visible = false
    var i = 0
    while (i < clean.length && !visible) {
        val codePoint = clean.codePointAt(i)
        visible = isVisible(codePoint)
        i += Character.charCount(codePoint)
    }
    if (!visible) return fallback
    if (clean.codePointCount(0, clean.length) <= MAX_DISPLAY_NAME_CODE_POINTS) return clean
    return clean.substring(0, clean.offsetByCodePoints(0, MAX_DISPLAY_NAME_CODE_POINTS)) + "…"
}
