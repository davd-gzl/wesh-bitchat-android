package com.bitchat.android.ui.pave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaveReservedWordsTest {
    /**
     * Families of labels drawn next to a remote nickname. An entry matches its own key and every
     * key that continues it after an underscore. A new label in one of these families must join
     * [PaveReservedWords], or a peer could take it as a nickname.
     */
    private val families = listOf(
        "wesh_pave_message_from_you", // "Vous"
        "wesh_pave_unnamed", // "Sans surnom", "Sans surnom (réservé)" and their fallbacks
        "wesh_pave_verified", "wesh_pave_unverified", // badges
        "wesh_pave_delivery", "wesh_pave_failed_retry", "wesh_pave_retry", // delivery states
        "wesh_pave_via", // transport
        "wesh_pave_public_room", // public room
        "wesh_pave_action_exchange", // "Échanger"
        "wesh_pave_section_requests", // "Demandes"
        "wesh_pave_fingerprint", // "Empreinte de la clé", "Empreinte indisponible"
    )

    private fun inFamily(name: String) = families.any { name == it || name.startsWith(it + "_") }

    private val reservedNames = PaveReservedWords.keys.map(PaveStringResources::name).toSet()

    @Test
    fun `every label of the reserved families is in the central list, in every locale`() {
        assertTrue(PaveStringResources.localeDirectories.containsAll(listOf("values", "values-fr")))
        PaveStringResources.localeDirectories.forEach { directory ->
            val labels = PaveStringResources.strings(directory).filter { (name, text) -> inFamily(name) && '%' !in text }
            assertTrue("no reserved family found in $directory", labels.isNotEmpty())
            labels.keys.forEach { name ->
                assertTrue("$directory/$name is shown next to nicknames but missing from PaveReservedWords", name in reservedNames)
            }
        }
    }

    @Test
    fun `every reserved key exists in French and English`() {
        listOf("values", "values-fr").forEach { directory ->
            val strings = PaveStringResources.strings(directory)
            reservedNames.forEach { name -> assertTrue("$name is missing from $directory", name in strings) }
        }
    }

    @Test
    fun `the list carries the words the rule names`() {
        val words = PaveStringResources.reservedWords()
        listOf(
            "Vous", "You", "Sans surnom", "No nickname", "Sans surnom (réservé)", "No nickname (reserved)",
            "Code vérifié", "Non vérifié", "En attente", "Envoyé", "Reçu", "Échec", "via Internet", "Bluetooth",
            "Salon public", "Public room", "Échanger", "Exchange", "Demandes", "Requests",
            "Empreinte de la clé", "Key fingerprint", "Empreinte indisponible", "Fingerprint unavailable",
        ).forEach { word -> assertTrue("$word is not reserved", word in words) }
        assertEquals(PaveReservedWords.keys.size, PaveReservedWords.keys.toSet().size)
    }
}
