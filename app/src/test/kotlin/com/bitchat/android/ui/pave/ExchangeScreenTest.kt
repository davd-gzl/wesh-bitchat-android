package com.bitchat.android.ui.pave

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExchangeScreenTest {
    private val mine = "7F3A 91C2 0B5E 44D8"
    private val theirs = "A1B2 C3D4 E5F6 0718"

    @Test
    fun `success card waits a moment before opening the chat`() {
        assertEquals(3000L, EXCHANGE_SUCCESS_HOLD_MS)
    }

    @Test
    fun `your own fingerprint is always shown, the contact's only once both checks are done`() {
        val pending = exchangeFingerprints(mine, theirs, ExchangeProgress(theyScannedMe = true))
        assertEquals(mine, pending.mine)
        assertNull(pending.contact)
        val done = exchangeFingerprints(mine, theirs, ExchangeProgress(theyScannedMe = true, iScannedThem = true))
        assertEquals(mine, done.mine)
        assertEquals(theirs, done.contact)
        assertNull(exchangeFingerprints(null, null, ExchangeProgress(true, true)).mine)
    }

    @Test
    fun `completion runs once even if the button and the timer both fire`() {
        var calls = 0
        var done = false
        done = completeOnce(done) { calls++ }
        done = completeOnce(done) { calls++ }
        assertTrue(done)
        assertEquals(1, calls)
    }

    @Test
    fun `the camera is asked for only after the rationale, and settings only after a permanent refusal`() {
        assertEquals(CameraCard.Rationale, cameraCard(granted = false, asked = false, shouldShowRationale = false))
        assertEquals(CameraCard.Rationale, cameraCard(granted = false, asked = true, shouldShowRationale = true))
        assertEquals(CameraCard.Settings, cameraCard(granted = false, asked = true, shouldShowRationale = false))
        assertEquals(CameraCard.Scanner, cameraCard(granted = true, asked = false, shouldShowRationale = false))
    }

    @Test
    fun `the two fingerprints say whose key they show, without a name`() {
        val fr = PaveStringResources.strings("values-fr")
        val en = PaveStringResources.strings("values")
        assertEquals("Votre empreinte", fr.getValue("wesh_pave_fingerprint_mine"))
        assertEquals("Empreinte du contact", fr.getValue("wesh_pave_fingerprint_contact"))
        assertEquals("Your fingerprint", en.getValue("wesh_pave_fingerprint_mine"))
        assertEquals("Contact’s fingerprint", en.getValue("wesh_pave_fingerprint_contact"))
    }

    @Test
    fun `the camera rationale says what the camera is for`() {
        assertEquals(
            "La caméra sert uniquement à lire le code de votre contact. Aucune image n’est enregistrée.",
            PaveStringResources.strings("values-fr").getValue("wesh_pave_camera_rationale"),
        )
        assertTrue("wesh_pave_camera_rationale" in PaveStringResources.strings("values"))
    }

    @Test
    fun `check pastilles carry no remote name`() {
        listOf("values", "values-fr").forEach { directory ->
            val strings = PaveStringResources.strings(directory)
            listOf("wesh_pave_exchange_they_scanned", "wesh_pave_exchange_i_scanned").forEach { key ->
                assertFalse("$directory/$key must not take a name", '%' in strings.getValue(key))
            }
        }
    }

    @Test
    fun `screens show remote names only through remoteDisplayName`() {
        val screens = File("src/main/java/com/bitchat/android/ui/pave").listFiles().orEmpty()
            .filter { it.extension == "kt" && it.name !in setOf("PaveDisplayName.kt", "PaveIdentity.kt") }
        screens.forEach { file ->
            assertFalse("${file.name} bypasses remoteDisplayName", "PaveDisplayName." in file.readText())
        }
    }
}
