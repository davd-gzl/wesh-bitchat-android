package com.bitchat.android.ui.pave

import org.junit.Assert.assertEquals
import org.junit.Test

/** [remoteDisplayName] wiring without Compose: labels from one language, reserved words from both. */
class PaveIdentityTest {
    private val reservedWords = PaveStringResources.reservedWords()

    private fun shown(raw: String?, directory: String): String {
        val strings = PaveStringResources.strings(directory)
        return remoteDisplayName(raw, { strings.getValue(PaveStringResources.name(it)) }, reservedWords)
    }

    @Test
    fun `an English phone still masks the French words`() {
        assertEquals("No nickname (reserved)", shown("Vous", "values"))
        assertEquals("No nickname (reserved)", shown("Nora sans surnom", "values"))
        assertEquals("Sans surnom (réservé)", shown("You", "values-fr"))
    }

    @Test
    fun `labels come from the phone language`() {
        assertEquals("Nora", shown("Nora", "values"))
        assertEquals("No nickname", shown(null, "values"))
        assertEquals("Sans surnom", shown("No" + String(Character.toChars(0x200B)) + "ra", "values-fr"))
    }
}
