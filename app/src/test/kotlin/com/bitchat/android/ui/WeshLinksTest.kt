package com.bitchat.android.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeshLinksTest {
    @Test
    fun `terms URL is defined once and used by the settings screen`() {
        assertEquals("https://wesh.samourai.app/conditions", WeshLinks.TERMS_URL)
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
        val definitions = sources.filter { it.readText().contains("wesh.samourai.app") }.map { it.name }
        assertEquals(listOf("WeshLinks.kt"), definitions)
        // The Pavé About screen (reached from Settings) and the welcome both link the terms.
        assertTrue(File("src/main/java/com/bitchat/android/ui/pave/SettingsScreen.kt").readText().contains("WeshLinks.TERMS_URL"))
        assertTrue(File("src/main/java/com/bitchat/android/ui/pave/OnboardingFlow.kt").readText().contains("WeshLinks.TERMS_URL"))
    }
}
