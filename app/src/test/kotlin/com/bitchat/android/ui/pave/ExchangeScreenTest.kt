package com.bitchat.android.ui.pave

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ExchangeScreenTest {
    private val myKey = ByteArray(32) { it.toByte() }
    private val contactKey = ByteArray(32) { (31 - it).toByte() }

    @Test
    fun `success card waits a moment before opening the chat`() {
        assertEquals(3000L, EXCHANGE_SUCCESS_HOLD_MS)
    }

    @Test
    fun `your own fingerprint is always shown, the contact's only once both checks are done`() {
        val pending = exchangeFingerprints(myKey, contactKey, ExchangeProgress(theyScannedMe = true))
        assertEquals(PaveFingerprint.short(myKey), pending.mine)
        assertNull(pending.contact)
        val done = exchangeFingerprints(myKey, contactKey, ExchangeProgress(theyScannedMe = true, iScannedThem = true))
        assertEquals(PaveFingerprint.short(myKey), done.mine)
        assertEquals(PaveFingerprint.short(contactKey), done.contact)
        assertNull(exchangeFingerprints(null, null, ExchangeProgress(true, true)).mine)
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
