package com.bitchat.android.startup

import com.bitchat.android.R
import com.bitchat.android.ui.pave.PaveStringResources
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The startup, recovery and verification copy follows the phone language: English by default,
 * French in `values-fr`. Read from the shipped XML, since unit tests run without merged resources.
 */
class WeshStartupStringsTest {
    private val english = PaveStringResources.strings("values")
    private val french = PaveStringResources.strings("values-fr")

    private fun names(directory: String): Set<String> =
        Regex("""<string name="([^"]+)"""")
            .findAll(File("src/main/res/$directory/strings_wesh_startup.xml").readText())
            .map { it.groupValues[1] }
            .toSet()

    private fun Map<String, String>.text(id: Int): String = getValue(PaveStringResources.name(id))

    @Test
    fun anEnglishPhoneReadsEnglish() {
        assertEquals("Getting Wesh ready…", english.text(R.string.wesh_startup_preparing))
        assertEquals("Try again", english.text(R.string.wesh_startup_retry))
        assertEquals("App settings", english.text(R.string.wesh_verify_app_settings))
    }

    @Test
    fun aFrenchPhoneReadsFrench() {
        assertEquals("Préparation de Wesh…", french.text(R.string.wesh_startup_preparing))
        assertEquals("Réessayer", french.text(R.string.wesh_startup_retry))
        assertEquals("Réglages de l’app", french.text(R.string.wesh_verify_app_settings))
    }

    @Test
    fun everyStartupStringHasBothLanguages() {
        assertEquals(names("values"), names("values-fr"))
    }
}
