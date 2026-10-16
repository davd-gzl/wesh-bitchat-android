package com.bitchat.android.ui.pave

import java.text.BreakIterator
import java.text.Normalizer

/**
 * The remote nickname rule shared with iOS (design system README, « Surnom distant », v1; vectors
 * in `wesh-display-name-v1.json`). Pavé components apply it themselves, so a caller cannot forget it:
 * 1. The raw input is cut to [MAX_RAW_CODE_POINTS] code points, then NFKC. Nothing past that cap
 *    is looked at, so an oversized name costs no more than a long one.
 * 2. Invisible characters (control, format such as bidi and zero-width, surrogate, private-use,
 *    unassigned, blank glyphs) are stripped at both ends; one between visible characters makes
 *    the whole name unnamed rather than being removed silently. Emoji keep their joiner and
 *    presentation selectors, strictly in place ([isKeptEmojiJoiner]); tags are refused everywhere.
 * 3. At most [MAX_COMBINING_MARKS] marks per character; one line.
 * 4. Letters mixing confusable scripts (Latin, Greek, Cyrillic, Armenian, Cherokee) make it unnamed.
 * 5. At most [MAX_GRAPHEMES] graphemes, elided in the middle.
 * 6. A name whose skeleton reads as a reserved word, in full or elided, is masked as reserved;
 *    so is one that imitates the short key fingerprint ([FINGERPRINT_LIKE]).
 */
object PaveDisplayName {
    const val MAX_GRAPHEMES = 32
    const val MAX_COMBINING_MARKS = 2
    const val MAX_RAW_CODE_POINTS = 256

    /** A reserved word this long or longer also masks names that start or end with it. */
    const val MIN_AFFIX_LETTERS = 4
    private const val ELLIPSIS = "…"
    private const val ZERO_WIDTH_JOINER = 0x200D
    private const val TEXT_PRESENTATION = 0xFE0E
    private const val EMOJI_PRESENTATION = 0xFE0F
    private const val COMBINING_KEYCAP = 0x20E3

    private val CONFUSABLE_SCRIPTS = setOf(
        Character.UnicodeScript.LATIN, Character.UnicodeScript.GREEK, Character.UnicodeScript.CYRILLIC,
        Character.UnicodeScript.ARMENIAN, Character.UnicodeScript.CHEROKEE,
    )

    /**
     * Look-alikes (Cyrillic, Greek, Latin small capitals, dotless i) folded before lowercasing when
     * comparing with reserved words. Same table as the shared vectors file;
     * PaveDisplayNameVectorsTest keeps the two equal.
     */
    internal val CONFUSABLES: Map<Char, Char> = mapOf(
        '\u0410' to 'a', // Cyrillic Capital Letter A
        '\u0412' to 'b', // Cyrillic Capital Letter Ve
        '\u0415' to 'e', // Cyrillic Capital Letter Ie
        '\u041A' to 'k', // Cyrillic Capital Letter Ka
        '\u041C' to 'm', // Cyrillic Capital Letter Em
        '\u041D' to 'h', // Cyrillic Capital Letter En
        '\u041E' to 'o', // Cyrillic Capital Letter O
        '\u0420' to 'p', // Cyrillic Capital Letter Er
        '\u0421' to 'c', // Cyrillic Capital Letter Es
        '\u0422' to 't', // Cyrillic Capital Letter Te
        '\u0425' to 'x', // Cyrillic Capital Letter Ha
        '\u0423' to 'y', // Cyrillic Capital Letter U
        '\u0430' to 'a', // Cyrillic Small Letter A
        '\u0435' to 'e', // Cyrillic Small Letter Ie
        '\u043E' to 'o', // Cyrillic Small Letter O
        '\u0440' to 'p', // Cyrillic Small Letter Er
        '\u0441' to 'c', // Cyrillic Small Letter Es
        '\u0443' to 'y', // Cyrillic Small Letter U
        '\u0445' to 'x', // Cyrillic Small Letter Ha
        '\u0456' to 'i', // Cyrillic Small Letter Byelorussian-Ukrainian I
        '\u0458' to 'j', // Cyrillic Small Letter Je
        '\u0455' to 's', // Cyrillic Small Letter Dze
        '\u0501' to 'd', // Cyrillic Small Letter Komi De
        '\u04BB' to 'h', // Cyrillic Small Letter Shha
        '\u04CF' to 'l', // Cyrillic Small Letter Palochka
        '\u04C0' to 'l', // Cyrillic Letter Palochka
        '\u0406' to 'i', // Cyrillic Capital Letter Byelorussian-Ukrainian I
        '\u0408' to 'j', // Cyrillic Capital Letter Je
        '\u0405' to 's', // Cyrillic Capital Letter Dze
        '\u051B' to 'q', // Cyrillic Small Letter Qa
        '\u051D' to 'w', // Cyrillic Small Letter We
        '\u0391' to 'a', // Greek Capital Letter Alpha
        '\u0392' to 'b', // Greek Capital Letter Beta
        '\u0395' to 'e', // Greek Capital Letter Epsilon
        '\u0396' to 'z', // Greek Capital Letter Zeta
        '\u0397' to 'h', // Greek Capital Letter Eta
        '\u0399' to 'i', // Greek Capital Letter Iota
        '\u039A' to 'k', // Greek Capital Letter Kappa
        '\u039C' to 'm', // Greek Capital Letter Mu
        '\u039D' to 'n', // Greek Capital Letter Nu
        '\u039F' to 'o', // Greek Capital Letter Omicron
        '\u03A1' to 'p', // Greek Capital Letter Rho
        '\u03A4' to 't', // Greek Capital Letter Tau
        '\u03A5' to 'y', // Greek Capital Letter Upsilon
        '\u03A7' to 'x', // Greek Capital Letter Chi
        '\u03B1' to 'a', // Greek Small Letter Alpha
        '\u03B9' to 'i', // Greek Small Letter Iota
        '\u03BA' to 'k', // Greek Small Letter Kappa
        '\u03BD' to 'v', // Greek Small Letter Nu
        '\u03BF' to 'o', // Greek Small Letter Omicron
        '\u03C1' to 'p', // Greek Small Letter Rho
        '\u03C5' to 'u', // Greek Small Letter Upsilon
        '\u1D00' to 'a', // Latin Letter Small Capital A
        '\u0299' to 'b', // Latin Letter Small Capital B
        '\u1D04' to 'c', // Latin Letter Small Capital C
        '\u1D05' to 'd', // Latin Letter Small Capital D
        '\u1D07' to 'e', // Latin Letter Small Capital E
        '\uA730' to 'f', // Latin Letter Small Capital F
        '\u0262' to 'g', // Latin Letter Small Capital G
        '\u029C' to 'h', // Latin Letter Small Capital H
        '\u026A' to 'i', // Latin Letter Small Capital I
        '\u1D0A' to 'j', // Latin Letter Small Capital J
        '\u1D0B' to 'k', // Latin Letter Small Capital K
        '\u029F' to 'l', // Latin Letter Small Capital L
        '\u1D0D' to 'm', // Latin Letter Small Capital M
        '\u0274' to 'n', // Latin Letter Small Capital N
        '\u1D0F' to 'o', // Latin Letter Small Capital O
        '\u1D18' to 'p', // Latin Letter Small Capital P
        '\u0280' to 'r', // Latin Letter Small Capital R
        '\uA731' to 's', // Latin Letter Small Capital S
        '\u1D1B' to 't', // Latin Letter Small Capital T
        '\u1D1C' to 'u', // Latin Letter Small Capital U
        '\u1D20' to 'v', // Latin Letter Small Capital V
        '\u1D21' to 'w', // Latin Letter Small Capital W
        '\u028F' to 'y', // Latin Letter Small Capital Y
        '\u1D22' to 'z', // Latin Letter Small Capital Z
        '\u0131' to 'i', // Latin Small Letter Dotless I
    )

    /**
     * Applied after lowercasing, to names and reserved words alike, so "NO NICKNAME", "N0 nickname"
     * and "no nickname" share one skeleton. Same table as the shared vectors file.
     */
    internal val AFTER_LOWERCASE: Map<Char, Char> = mapOf('i' to 'l', 'l' to 'l', '1' to 'l', '0' to 'o')

    /**
     * Assigned code points that still draw nothing: Hangul fillers, Braille blank, Khmer inherent
     * vowels, Mongolian variation selectors and vowel separator, variation selectors.
     */
    private fun isBlankGlyph(codePoint: Int): Boolean =
        codePoint == 0x115F || codePoint == 0x1160 || codePoint == 0x3164 || codePoint == 0xFFA0 ||
            codePoint == 0x2800 || codePoint == 0x17B4 || codePoint == 0x17B5 ||
            codePoint in 0x180B..0x180E || codePoint in 0xFE00..0xFE0F

    /** Unicode Default_Ignorable_Code_Point (DerivedCoreProperties): never drawn, never a mark. */
    private fun isDefaultIgnorable(codePoint: Int): Boolean =
        codePoint == 0x00AD || codePoint == 0x034F || codePoint == 0x061C || codePoint == 0xFEFF ||
            codePoint in 0x115F..0x1160 || codePoint in 0x17B4..0x17B5 || codePoint in 0x180B..0x180F ||
            codePoint in 0x200B..0x200F || codePoint in 0x202A..0x202E || codePoint in 0x2060..0x206F ||
            codePoint == 0x3164 || codePoint in 0xFE00..0xFE0F || codePoint == 0xFFA0 ||
            codePoint in 0xFFF0..0xFFF8 || codePoint in 0x1BCA0..0x1BCA3 || codePoint in 0x1D173..0x1D17A ||
            codePoint in 0xE0000..0xE0FFF

    private fun isInvisible(codePoint: Int): Boolean = isBlankGlyph(codePoint) || isDefaultIgnorable(codePoint) ||
        when (Character.getType(codePoint).toByte()) {
            Character.CONTROL, Character.FORMAT, Character.SURROGATE,
            Character.PRIVATE_USE, Character.UNASSIGNED -> true
            else -> false
        }

    /** For each code point, whether it draws nothing (see [isKeptEmojiJoiner] for the exception). */
    private fun hiddenFlags(codePoints: IntArray): BooleanArray =
        BooleanArray(codePoints.size) { index -> isInvisible(codePoints[index]) && !isKeptEmojiJoiner(codePoints, index) }

    private fun isSymbol(codePoint: Int?): Boolean =
        codePoint != null && Character.getType(codePoint).toByte() == Character.OTHER_SYMBOL

    /** A presentation selector or a skin tone (U+1F3FB–U+1F3FF), allowed between a symbol and its joiner. */
    private fun isEmojiModifier(codePoint: Int?): Boolean =
        codePoint == TEXT_PRESENTATION || codePoint == EMOJI_PRESENTATION || (codePoint != null && codePoint in 0x1F3FB..0x1F3FF)

    private fun isKeycapBase(codePoint: Int?): Boolean =
        codePoint != null && (codePoint in '0'.code..'9'.code || codePoint == '#'.code || codePoint == '*'.code)

    /**
     * The only invisible code points kept, so "❤️", "👩‍💻" and "1️⃣" survive: a presentation selector
     * once, right after a symbol (So) or after 0-9, # or * followed by the keycap mark; a zero-width
     * joiner between two symbols, the first one possibly with its selector or skin tone. A doubled,
     * leading or trailing joiner or selector stays invisible.
     */
    private fun isKeptEmojiJoiner(codePoints: IntArray, index: Int): Boolean {
        val before = codePoints.getOrNull(index - 1)
        val after = codePoints.getOrNull(index + 1)
        return when (codePoints[index]) {
            TEXT_PRESENTATION, EMOJI_PRESENTATION ->
                isSymbol(before) || (isKeycapBase(before) && after == COMBINING_KEYCAP)
            ZERO_WIDTH_JOINER -> isSymbol(after) && (
                isSymbol(before) || (isEmojiModifier(before) && isSymbol(codePoints.getOrNull(index - 2)))
                )
            else -> false
        }
    }

    private fun isSpace(codePoint: Int): Boolean = Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)

    private fun isMark(codePoint: Int): Boolean = when (Character.getType(codePoint).toByte()) {
        Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> true
        else -> false
    }

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

    /**
     * For a name that comes from someone else: the name to draw, [unnamed] when nothing safe is
     * left, or [reserved] when it reads as one of [reservedWords] (see PaveReservedWords).
     */
    fun remote(raw: String?, unnamed: String, reserved: String, reservedWords: Collection<String>): String {
        val name = clean(raw) ?: return unnamed
        val elided = elideMiddle(name)
        return if (readsAsReserved(name, elided, reservedWords)) reserved else elided
    }

    /** Rules 1 to 4; null when the name must show as unnamed. */
    private fun clean(raw: String?): String? {
        if (raw.isNullOrEmpty()) return null
        val normalized = Normalizer.normalize(clampRaw(raw), Normalizer.Form.NFKC)
        val codePoints = normalized.codePoints().toArray()
        val hidden = hiddenFlags(codePoints)
        var start = 0
        var end = codePoints.size
        while (start < end && (hidden[start] || isSpace(codePoints[start]))) start++
        while (end > start && (hidden[end - 1] || isSpace(codePoints[end - 1]))) end--
        val out = StringBuilder(normalized.length)
        var marks = 0
        for (index in start until end) {
            val codePoint = codePoints[index]
            if (hidden[index]) return null
            if (isInvisible(codePoint)) {
                // A kept emoji joiner or selector: neither a mark nor a reset of the mark count.
                out.appendCodePoint(codePoint)
                continue
            }
            if (isMark(codePoint)) {
                if (out.isEmpty() || out.last() == ' ' || ++marks > MAX_COMBINING_MARKS) continue
            } else {
                marks = 0
            }
            if (isSpace(codePoint)) {
                if (out.isNotEmpty() && out.last() != ' ') out.append(' ')
            } else {
                out.appendCodePoint(codePoint)
            }
        }
        val clean = out.toString().trim()
        if (clean.codePoints().noneMatch(::isVisible) || mixesConfusableScripts(clean)) return null
        return clean
    }

    private fun mixesConfusableScripts(text: String): Boolean = text.codePoints()
        .filter { Character.isLetter(it) }
        .mapToObj { Character.UnicodeScript.of(it) }
        .filter { it in CONFUSABLE_SCRIPTS }
        .distinct()
        .count() > 1

    /** The first [MAX_RAW_CODE_POINTS] code points of [raw], without reading the rest. */
    private fun clampRaw(raw: String): String {
        var end = 0
        var count = 0
        while (end < raw.length && count < MAX_RAW_CODE_POINTS) {
            end += Character.charCount(raw.codePointAt(end))
            count++
        }
        return raw.substring(0, end)
    }

    private fun readsAsReserved(name: String, elided: String, reservedWords: Collection<String>): Boolean {
        val whole = HashSet<String>()
        val affixes = HashSet<String>()
        reservedWords.forEach { word ->
            val skeleton = skeletonWords(word).joinToString("")
            if (skeleton.isEmpty()) return@forEach
            whole += skeleton
            if (skeleton.codePointCount(0, skeleton.length) >= MIN_AFFIX_LETTERS) affixes += skeleton
        }
        val longest = affixes.maxOfOrNull { it.length } ?: 0
        return setOf(name, elided).any { candidate ->
            val words = skeletonWords(candidate)
            words.joinToString("") in whole ||
                startsWithAffix(words, affixes, longest) || startsWithAffix(words.asReversed(), affixes, longest, reversed = true) ||
                FINGERPRINT_LIKE.containsMatchIn(skeletonWords(candidate, afterLowercase = false).joinToString(" "))
        }
    }

    /**
     * Two groups of 4 hex characters, joined or one space apart ("A1B2 C3D4", "a1b2c3d4"): a name
     * like that would pass for the short key fingerprint. Matched before [AFTER_LOWERCASE], which
     * would turn 1 and 0 into letters.
     */
    private val FINGERPRINT_LIKE = Regex("[0-9a-f]{4} ?[0-9a-f]{4}")

    /**
     * Whether whole leading [words] (trailing ones when [reversed]) join into one of [affixes].
     * One pass that stops once the joined text is longer than the [longest] affix.
     */
    private fun startsWithAffix(words: List<String>, affixes: Set<String>, longest: Int, reversed: Boolean = false): Boolean {
        val joined = StringBuilder()
        for (word in words) {
            if (reversed) joined.insert(0, word) else joined.append(word)
            if (joined.length > longest) return false
            if (joined.toString() in affixes) return true
        }
        return false
    }

    /**
     * Words of [text] as skeletons: NFKD, [CONFUSABLES], lowercase, [AFTER_LOWERCASE] unless
     * [afterLowercase] is false, marks dropped, letters and digits only. Anything that is not a
     * letter, digit or mark separates words: "V.ó-us" is v, o, us.
     */
    internal fun skeletonWords(text: String, afterLowercase: Boolean = true): List<String> {
        val words = mutableListOf<String>()
        val word = StringBuilder()
        Normalizer.normalize(text, Normalizer.Form.NFKD).codePoints().forEach { codePoint ->
            val folded = fold(codePoint, afterLowercase)
            when {
                Character.isLetterOrDigit(folded) -> word.appendCodePoint(folded)
                isMark(folded) -> Unit
                word.isNotEmpty() -> {
                    words += word.toString()
                    word.setLength(0)
                }
            }
        }
        if (word.isNotEmpty()) words += word.toString()
        return words
    }

    private fun fold(codePoint: Int, afterLowercase: Boolean): Int {
        if (!Character.isBmpCodePoint(codePoint)) return Character.toLowerCase(codePoint)
        val lower = Character.toLowerCase(CONFUSABLES[codePoint.toChar()]?.code ?: codePoint)
        if (!afterLowercase || !Character.isBmpCodePoint(lower)) return lower
        return AFTER_LOWERCASE[lower.toChar()]?.code ?: lower
    }

    /**
     * At most [MAX_GRAPHEMES] graphemes, ellipsis included. A long name keeps its start and its end
     * ("Nora la vraie qu…ongtemps ou pas"), so a suffix cannot hide behind a trusted-looking prefix.
     */
    internal fun elideMiddle(text: String): String {
        val tail = (MAX_GRAPHEMES - 1) / 2
        val head = MAX_GRAPHEMES - 1 - tail
        val iterator = BreakIterator.getCharacterInstance().apply { setText(text) }
        var headEnd = 0
        // Walk forward only until the name is known to be too long.
        for (count in 1..MAX_GRAPHEMES + 1) {
            val next = iterator.next()
            if (next == BreakIterator.DONE) return text
            if (count == head) headEnd = next
        }
        var tailStart = iterator.last()
        repeat(tail) { tailStart = iterator.previous() }
        return text.substring(0, headEnd).trimEnd() + ELLIPSIS + text.substring(tailStart).trimStart()
    }
}
