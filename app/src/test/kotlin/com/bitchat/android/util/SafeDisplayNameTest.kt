package com.bitchat.android.util

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeDisplayNameTest {
    @Test
    fun `controls, bidi and zero width characters are removed`() {
        assertEquals("evilname", safeDisplayName("‮evil​\u0007name⁦", "fallback"))
    }

    @Test
    fun `compatibility forms are normalized and combining marks are capped`() {
        assertEquals("ABC", safeDisplayName("ＡＢＣ", "fallback"))
        assertEquals("á́́b", safeDisplayName("a" + "́".repeat(50) + "b", "fallback"))
        assertEquals("fallback", safeDisplayName("́́", "fallback"))
    }

    @Test
    fun `whitespace collapses, empty names fall back and long names are truncated`() {
        assertEquals("a b", safeDisplayName(" a \n\t  b ", "fallback"))
        assertEquals("fallback", safeDisplayName("‏\u0000", "fallback"))
        val long = safeDisplayName("x".repeat(200), "fallback")
        assertEquals(MAX_DISPLAY_NAME_CODE_POINTS + 1, long.length)
        assertTrue(long.endsWith("…"))
        assertFalse(safeDisplayName("private", "fallback").contains(''))
    }

    @Test
    fun `blank but assigned fillers never form a name`() {
        assertEquals("fallback", safeDisplayName("\u3164\u3164", "fallback"))
        assertEquals("fallback", safeDisplayName("\u2800", "fallback"))
        assertEquals("fallback", safeDisplayName("\u115F\u1160", "fallback"))
        assertEquals("fallback", safeDisplayName("\u17B4\uFFA0\u180E\uFE0F", "fallback"))
        assertEquals("ab", safeDisplayName("a\u3164\u2800\u1160b", "fallback"))
    }

    @Test
    fun `a name needs at least one visible character`() {
        assertEquals("fallback", safeDisplayName("\u0301 \u0301", "fallback"))
        assertEquals("-", safeDisplayName("-", "fallback"))
        assertEquals("★", safeDisplayName("★", "fallback"))
        assertEquals("7", safeDisplayName("7", "fallback"))
    }

    @Test
    fun `the Wesh screen shows remote nicknames only through the remote nickname rule`() {
        val pave = File("src/main/java/com/bitchat/android/ui/pave").listFiles().orEmpty()
            .filter { it.extension == "kt" && it.name !in setOf("PaveDisplayName.kt", "PaveIdentity.kt") }
        assertTrue(pave.any { it.name == "WeshPaveApp.kt" })
        pave.forEach { file ->
            val screen = file.readText()
            assertFalse(file.name, screen.contains("Text(message.sender") || screen.contains("Text(m.sender"))
            assertFalse(file.name, screen.contains("\${message.sender}") || screen.contains("\${m.sender}"))
            assertFalse(file.name, screen.contains("Text(request.nickname") || screen.contains("Text(contact.nickname") || screen.contains("Text(item.nickname"))
            // Storage sanitizing is not the display rule: no Pavé screen uses it to show a name.
            assertFalse(file.name, screen.contains("safeDisplayName("))
        }
        // Every component that draws a remote name applies the rule itself.
        listOf("MessageBubble.kt", "InboxRow.kt", "RequestsScreen.kt", "ConversationScreens.kt", "SettingsDetailScreens.kt").forEach { name ->
            assertTrue(name, File("src/main/java/com/bitchat/android/ui/pave/$name").readText().contains("remoteDisplayName("))
        }
        // States drawn next to a name come from resources, so PaveReservedWords can reserve them.
        val app = File("src/main/java/com/bitchat/android/ui/pave/WeshPaveApp.kt").readText()
        assertFalse(app.contains("\"Identité vérifiée sur ce téléphone\"") || app.contains("\"Bloqué sur ce téléphone\""))
    }
}
