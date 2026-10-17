package com.bitchat.android.ui.pave

import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WeshTextFieldPrivacyTest {
    @Test
    fun `the wrapper asks the keyboard not to learn and keeps the other options`() {
        val options = withNoPersonalizedLearning(EditorInfo.IME_ACTION_DONE)
        assertTrue(options and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
        assertEquals(EditorInfo.IME_ACTION_DONE, options and EditorInfo.IME_MASK_ACTION)
    }

    @Test
    fun `every text field in the app is wrapped so the keyboard does not learn from it`() {
        val field = Regex("""\b(BasicTextField|OutlinedTextField|TextField|BasicSecureTextField|SecureTextField)\(""")
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty())
        var fields = 0
        val unwrapped = sources.flatMap { file ->
            val lines = file.readLines()
            lines.indices.filter { i -> field.containsMatchIn(lines[i]) && !lines[i].trimStart().startsWith("import ") }
                .onEach { fields++ }
                .filter { i ->
                    val previous = lines.subList(0, i).lastOrNull { it.isNotBlank() }?.trim()
                    previous != "NoPersonalizedLearning {"
                }
                .map { i -> "${file.name}:${i + 1}" }
        }
        assertTrue("no text field found: the scan is broken", fields > 0)
        assertTrue("text fields outside NoPersonalizedLearning: $unwrapped", unwrapped.isEmpty())
    }
}
