package com.bitchat.android.ui.pave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec §3 « Pill », with an indeterminate Tor step: Arti gives no real progress figure. */
class InternetPillTest {
    private fun label(status: PaveInternetStatus, directory: String) =
        PaveStringResources.strings(directory).getValue(PaveStringResources.name(status.pillLabel()))

    @Test
    fun `the pill uses the spec words, in French`() {
        assertEquals("Bluetooth seulement", label(PaveInternetStatus.Off, "values-fr"))
        assertEquals("Connexion à Tor…", label(PaveInternetStatus.Connecting(42), "values-fr"))
        assertEquals("Internet actif", label(PaveInternetStatus.On(3), "values-fr"))
        assertEquals("Internet indisponible — Réessayer", label(PaveInternetStatus.Error(PaveInternetError.TorBlocked), "values-fr"))
    }

    @Test
    fun `the pill has English labels too`() {
        assertEquals("Bluetooth only", label(PaveInternetStatus.Off, "values"))
        assertEquals("Connecting to Tor…", label(PaveInternetStatus.Connecting(42), "values"))
        assertEquals("Internet on", label(PaveInternetStatus.On(3), "values"))
        assertEquals("Internet unavailable — Retry", label(PaveInternetStatus.Error(PaveInternetError.NoNetwork), "values"))
    }

    @Test
    fun `a touch retries only after a failure, and the spoken hints are actions`() {
        assertEquals(PaveInternetPillAction.Retry, PaveInternetStatus.Error(PaveInternetError.RelaysUnreachable).pillAction())
        for (status in listOf(PaveInternetStatus.Off, PaveInternetStatus.Connecting(0), PaveInternetStatus.On(1))) {
            assertEquals(PaveInternetPillAction.OpenConnexion, status.pillAction())
        }
        val fr = PaveStringResources.strings("values-fr")
        assertEquals("Relancer la connexion à Tor", fr.getValue("wesh_pave_pill_retry_hint"))
        assertEquals("Ouvrir Connexion", fr.getValue("wesh_pave_status_open"))
    }

    @Test
    fun `Tor bootstrap never shows a figure`() {
        for (directory in listOf("values", "values-fr")) {
            val strings = PaveStringResources.strings(directory)
            for (name in listOf(PaveStringResources.name(PaveInternetStatus.Connecting(0).pillLabel()), "wesh_pave_internet_connecting")) {
                val text = strings.getValue(name)
                assertFalse("$directory $name: $text", text.contains('%') || text.any(Char::isDigit))
            }
        }
    }

    @Test
    fun `the Home pill shows only while Internet mode is on, and only a failure offers a retry`() {
        assertNull(paveHomeInternetPill(available = true, enabled = false, status = PaveInternetStatus.Off))
        assertNull(paveHomeInternetPill(available = false, enabled = true, status = PaveInternetStatus.On(1)))
        assertEquals(PaveInternetStatus.Connecting(5), paveHomeInternetPill(true, true, PaveInternetStatus.Connecting(5)))
        val failed = PaveInternetStatus.Error(PaveInternetError.TorBlocked)
        assertEquals(failed, paveHomeInternetPill(true, true, failed))
        assertTrue(PaveInternetStatus.Error(PaveInternetError.NoNetwork).offersRetry)
        assertFalse(PaveInternetStatus.On(2).offersRetry)
        assertFalse(PaveInternetStatus.Connecting(0).offersRetry)
    }
}
