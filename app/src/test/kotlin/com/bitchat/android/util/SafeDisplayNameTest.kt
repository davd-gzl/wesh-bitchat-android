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
    fun `the Wesh screen shows remote nicknames only through the sanitizer`() {
        val screen = File("src/main/java/com/bitchat/android/ui/WeshScreen.kt").readText()
        assertFalse(screen.contains("Text(message.sender"))
        assertFalse(screen.contains("\${message.sender}"))
        assertTrue(screen.contains("safeDisplayName(message.sender"))
    }
}
