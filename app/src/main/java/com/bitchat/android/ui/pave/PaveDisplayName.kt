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
 *    presentation selectors ([hiddenFlags]); tag characters are refused everywhere.
 * 3. At most [MAX_COMBINING_MARKS] marks per character; one line.
 * 4. Letters mixing confusable scripts (Latin, Greek, Cyrillic, Armenian, Cherokee) make it unnamed.
 * 5. At most [MAX_GRAPHEMES] graphemes, elided in the middle.
 * 6. A name whose skeleton reads as a reserved word, in full or elided, is masked as reserved.
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
     * Look-alikes folded before lowercasing when comparing with reserved words. Same table as the
     * shared vectors file; PaveDisplayNameVectorsTest keeps the two equal.
     */
    internal val CONFUSABLES: Map<Char, Char> = mapOf(
        'А' to 'a', // Cyrillic Capital Letter A
        'В' to 'b', // Cyrillic Capital Letter Ve
        'Е' to 'e', // Cyrillic Capital Letter Ie
        'К' to 'k', // Cyrillic Capital Letter Ka
        'М' to 'm', // Cyrillic Capital Letter Em
        'Н' to 'h', // Cyrillic Capital Letter En
        'О' to 'o', // Cyrillic Capital Letter O
        'Р' to 'p', // Cyrillic Capital Letter Er
        'С' to 'c', // Cyrillic Capital Letter Es
        'Т' to 't', // Cyrillic Capital Letter Te
        'Х' to 'x', // Cyrillic Capital Letter Ha
        'У' to 'y', // Cyrillic Capital Letter U
        'а' to 'a', // Cyrillic Small Letter A
        'е' to 'e', // Cyrillic Small Letter Ie
        'о' to 'o', // Cyrillic Small Letter O
        'р' to 'p', // Cyrillic Small Letter Er
        'с' to 'c', // Cyrillic Small Letter Es
        'у' to 'y', // Cyrillic Small Letter U
        'х' to 'x', // Cyrillic Small Letter Ha
        'і' to 'i', // Cyrillic Small Letter Byelorussian-Ukrainian I
        'ј' to 'j', // Cyrillic Small Letter Je
        'ѕ' to 's', // Cyrillic Small Letter Dze
        'ԁ' to 'd', // Cyrillic Small Letter Komi De
        'һ' to 'h', // Cyrillic Small Letter Shha
        'ӏ' to 'l', // Cyrillic Small Letter Palochka
        'Ӏ' to 'l', // Cyrillic Letter Palochka
        'І' to 'i', // Cyrillic Capital Letter Byelorussian-Ukrainian I
        'Ј' to 'j', // Cyrillic Capital Letter Je
        'Ѕ' to 's', // Cyrillic Capital Letter Dze
        'ԛ' to 'q', // Cyrillic Small Letter Qa
        'ԝ' to 'w', // Cyrillic Small Letter We
        'Α' to 'a', // Greek Capital Letter Alpha
        'Β' to 'b', // Greek Capital Letter Beta
        'Ε' to 'e', // Greek Capital Letter Epsilon
        'Ζ' to 'z', // Greek Capital Letter Zeta
        'Η' to 'h', // Greek Capital Letter Eta
        'Ι' to 'i', // Greek Capital Letter Iota
        'Κ' to 'k', // Greek Capital Letter Kappa
        'Μ' to 'm', // Greek Capital Letter Mu
        'Ν' to 'n', // Greek Capital Letter Nu
        'Ο' to 'o', // Greek Capital Letter Omicron
        'Ρ' to 'p', // Greek Capital Letter Rho
        'Τ' to 't', // Greek Capital Letter Tau
        'Υ' to 'y', // Greek Capital Letter Upsilon
        'Χ' to 'x', // Greek Capital Letter Chi
        'α' to 'a', // Greek Small Letter Alpha
        'ι' to 'i', // Greek Small Letter Iota
        'κ' to 'k', // Greek Small Letter Kappa
        'ν' to 'v', // Greek Small Letter Nu
        'ο' to 'o', // Greek Small Letter Omicron
        'ρ' to 'p', // Greek Small Letter Rho
        '0' to 'o',
        '1' to 'l',
        'I' to 'l',
        'υ' to 'u', // Greek Small Letter Upsilon
    )

    /**
     * Assigned code points that still draw nothing: Hangul fillers, Braille blank, Khmer inherent
     * vowels, Mongolian variation selectors and vowel separator, variation selectors.
     */
    private fun isBlankGlyph(codePoint: Int): Boolean =
        codePoint == 0x115F || codePoint == 0x1160 || codePoint == 0x3164 || codePoint == 0xFFA0 ||
            codePoint == 0x2800 || codePoint == 0x17B4 || codePoint == 0x17B5 ||
            codePoint in 0x180B..0x180E || codePoint in 0xFE00..0xFE0F

    private fun isInvisible(codePoint: Int): Boolean = isBlankGlyph(codePoint) ||
        when (Character.getType(codePoint).toByte()) {
            Character.CONTROL, Character.FORMAT, Character.SURROGATE,
            Character.PRIVATE_USE, Character.UNASSIGNED -> true
            else -> false
        }

    /**
     * For each code point of [text], whether it draws nothing. The zero-width joiner and the text
     * and emoji presentation selectors are kept inside a grapheme that holds a symbol (So) or the
     * keycap mark, so "❤️", "👩‍💻" and "1️⃣" survive; anywhere else they count as invisible.
     */
    private fun hiddenFlags(text: String): BooleanArray {
        val flags = BooleanArray(text.codePointCount(0, text.length))
        val graphemes = BreakIterator.getCharacterInstance().apply { setText(text) }
        var start = graphemes.first()
        var end = graphemes.next()
        var index = 0
        while (end != BreakIterator.DONE) {
            val grapheme = text.substring(start, end)
            val emoji = grapheme.codePoints().anyMatch {
                Character.getType(it).toByte() == Character.OTHER_SYMBOL || it == COMBINING_KEYCAP
            }
            grapheme.codePoints().forEach { codePoint ->
                val emojiJoiner = codePoint == ZERO_WIDTH_JOINER || codePoint == TEXT_PRESENTATION ||
                    codePoint == EMOJI_PRESENTATION
                flags[index++] = isInvisible(codePoint) && !(emoji && emojiJoiner)
            }
            start = end
            end = graphemes.next()
        }
        return flags
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
        val hidden = hiddenFlags(normalized)
        var start = 0
        var end = codePoints.size
        while (start < end && (hidden[start] || isSpace(codePoints[start]))) start++
        while (end > start && (hidden[end - 1] || isSpace(codePoints[end - 1]))) end--
        val out = StringBuilder(normalized.length)
        var marks = 0
        for (index in start until end) {
            val codePoint = codePoints[index]
            if (hidden[index]) return null
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
                startsWithAffix(words, affixes, longest) || startsWithAffix(words.asReversed(), affixes, longest, reversed = true)
        }
    }

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
     * Words of [text] as skeletons: NFKD, [CONFUSABLES], lowercase, marks dropped, letters and digits
     * only. Anything that is not a letter, digit or mark separates words: "V.ó-us" is v, o, us.
     */
    internal fun skeletonWords(text: String): List<String> {
        val words = mutableListOf<String>()
        val word = StringBuilder()
        Normalizer.normalize(text, Normalizer.Form.NFKD).codePoints().forEach { codePoint ->
            val folded = Character.toLowerCase(fold(codePoint))
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

    private fun fold(codePoint: Int): Int =
        if (Character.isBmpCodePoint(codePoint)) CONFUSABLES[codePoint.toChar()]?.code ?: codePoint else codePoint

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
