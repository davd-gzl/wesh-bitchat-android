package com.bitchat.android.ui.pave

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Same shared vectors as iOS: docs/plan/fixtures/wesh-display-name-v1.json. */
class PaveDisplayNameVectorsTest {
    private val fixture: JsonObject = javaClass.classLoader!!
        .getResourceAsStream("fixtures/wesh-display-name-v1.json")!!
        .use { JsonParser.parseString(it.bufferedReader(Charsets.UTF_8).readText()).asJsonObject }

    @Test
    fun `confusables table matches the shared fixture`() {
        val shared = fixture.getAsJsonObject("confusables").entrySet().associate { (key, value) -> key to value.asString }
        val local = PaveDisplayName.CONFUSABLES.entries.associate { (key, value) -> key.toString() to value.toString() }
        assertEquals(shared, local)
    }

    @Test
    fun `after-lowercase table matches the shared fixture`() {
        val shared = fixture.getAsJsonObject("afterLowercase").entrySet().associate { (key, value) -> key to value.asString }
        val local = PaveDisplayName.AFTER_LOWERCASE.entries.associate { (key, value) -> key.toString() to value.toString() }
        assertEquals(shared, local)
    }

    @Test
    fun `fingerprint canonical table matches the shared fixture`() {
        val shared = fixture.getAsJsonObject("fingerprintCanonical").entrySet().associate { (key, value) -> key to value.asString }
        val local = PaveDisplayName.FINGERPRINT_CANONICAL.entries.associate { (key, value) -> key.toString() to value.toString() }
        assertEquals(shared, local)
    }

    @Test
    fun `script groups match the shared fixture`() {
        val scripts = fixture.getAsJsonObject("scripts")
        fun list(key: String) = scripts.getAsJsonArray(key).map { it.asString }
        assertEquals(setOf("latinMayMixWith", "neverMixTogether", "alwaysUnnamed"), scripts.keySet())
        assertEquals(list("latinMayMixWith"), PaveDisplayName.LATIN_MAY_MIX_WITH)
        assertEquals(list("neverMixTogether"), PaveDisplayName.NEVER_MIX_TOGETHER)
        assertEquals(list("alwaysUnnamed"), PaveDisplayName.ALWAYS_UNNAMED)
        val known = Character.UnicodeScript.values().map { it.name }.toSet()
        (list("latinMayMixWith") + list("neverMixTogether") + list("alwaysUnnamed")).forEach { name ->
            assertTrue("$name is not a Java script name", name.uppercase() in known)
        }
    }

    @Test
    fun `every shared vector gives the expected outcome in French and English`() {
        val reservedWords = fixture.getAsJsonArray("reservedForTest").map { it.asString } +
            PaveStringResources.reservedWords()
        val vectors = fixture.getAsJsonArray("vectors").map { it.asJsonObject }
        assertTrue(vectors.isNotEmpty())
        listOf("values", "values-fr").forEach { directory ->
            val strings = PaveStringResources.strings(directory)
            val unnamed = strings.getValue("wesh_pave_unnamed")
            val reserved = strings.getValue("wesh_pave_unnamed_reserved")
            vectors.forEach { vector ->
                val input = vector.get("input").asString
                val expected = when (val outcome = vector.get("expect").asString) {
                    "display" -> vector.get("display").asString
                    "unnamed" -> unnamed
                    "reserved" -> reserved
                    else -> error("unknown outcome $outcome")
                }
                val label = input.codePoints().toArray().joinToString(" ") { "U+%04X".format(it) }
                assertEquals("$directory: $label", expected, PaveDisplayName.remote(input, unnamed, reserved, reservedWords))
            }
        }
    }
}
