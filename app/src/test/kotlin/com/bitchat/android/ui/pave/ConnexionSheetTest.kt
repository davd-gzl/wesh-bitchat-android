package com.bitchat.android.ui.pave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnexionSheetTest {
    /** Internet mode spec, section 4, word for word. */
    private val specDisclosure = "Le mode Internet passe par Tor et par des relais Nostr publics que Wesh n'exploite pas. " +
        "Les relais ne voient ni votre adresse IP ni le contenu, mais voient la clé Internet du destinataire, " +
        "l'heure et la taille des messages, et peuvent relier vos envois à votre clé. Ils peuvent conserver les " +
        "messages chiffrés. Quiconque a votre QR connaît votre clé Internet. Désactivé par défaut ; l'effacement " +
        "d'urgence le désactive."

    @Test
    fun `turning Internet on the first time asks for the disclosure and changes nothing`() {
        val internet = FakeWeshInternetModeState()
        assertEquals(InternetToggleOutcome.NeedsDisclosure, toggleInternet(internet, on = true))
        assertFalse(internet.enabled.value)
        assertFalse(internet.disclosureAccepted)
    }

    @Test
    fun `confirming the disclosure records it and turns Internet on`() {
        val internet = FakeWeshInternetModeState()
        assertEquals(InternetToggleOutcome.Changed, confirmInternetDisclosure(internet))
        assertTrue(internet.disclosureAccepted)
        assertTrue(internet.enabled.value)
        internet.setEnabled(false)
        assertEquals(InternetToggleOutcome.Changed, toggleInternet(internet, on = true))
    }

    @Test
    fun `a refused change is reported`() {
        val internet = FakeWeshInternetModeState(available = false, disclosureAccepted = true)
        assertEquals(InternetToggleOutcome.Refused, toggleInternet(internet, on = true))
        assertFalse(internet.enabled.value)
    }

    @Test
    fun `turning Internet off never needs the disclosure`() {
        val internet = FakeWeshInternetModeState(enabled = true, disclosureAccepted = true)
        assertEquals(InternetToggleOutcome.Changed, toggleInternet(internet, on = false))
        assertFalse(internet.enabled.value)
    }

    @Test
    fun `the confirmation shows the exact disclosure text, in French and English`() {
        assertEquals(specDisclosure, PaveStringResources.strings("values-fr").getValue("wesh_pave_internet_disclosure_full"))
        val english = PaveStringResources.strings("values").getValue("wesh_pave_internet_disclosure_full")
        assertTrue(english.startsWith("Internet mode goes through Tor"))
    }

    @Test
    fun `the inline caption makes no claim of its own`() {
        listOf("values", "values-fr").forEach { directory ->
            val caption = PaveStringResources.strings(directory).getValue("wesh_pave_internet_disclosure")
            assertTrue("$directory caption is too long to stay a summary", caption.length <= 80)
            listOf("vérifiés", "verified", "ne lisent pas", "cannot read").forEach { claim ->
                assertFalse("$directory caption claims « $claim »", claim in caption)
            }
        }
    }
}
