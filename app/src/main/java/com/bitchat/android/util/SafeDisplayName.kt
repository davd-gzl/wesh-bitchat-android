package com.bitchat.android.util

import java.text.Normalizer

/** Longest remote nickname shown, in code points, before an ellipsis. */
const val MAX_DISPLAY_NAME_CODE_POINTS = 32
private const val MAX_COMBINING_MARKS = 2

/**
 * The one sanitizer for every remote nickname shown in Wesh. It NFKC-normalizes, strips control,
 * format (bidi overrides, zero-width), surrogate, private-use and unassigned code points, caps
 * stacked combining marks, collapses whitespace and truncates; an empty result uses [fallback].
 */
fun safeDisplayName(raw: String, fallback: String): String {
    val normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
    val out = StringBuilder()
    var marks = 0
    var index = 0
    while (index < normalized.length) {
        val codePoint = normalized.codePointAt(index)
        index += Character.charCount(codePoint)
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
    if (clean.isEmpty()) return fallback
    if (clean.codePointCount(0, clean.length) <= MAX_DISPLAY_NAME_CODE_POINTS) return clean
    return clean.substring(0, clean.offsetByCodePoints(0, MAX_DISPLAY_NAME_CODE_POINTS)) + "…"
}
