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
