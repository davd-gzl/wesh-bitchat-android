package com.bitchat.android.ui.pave

import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.net.WeshInternetError
import com.bitchat.android.net.WeshInternetState
import com.bitchat.android.ui.weshNamesakes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class WeshPaveAppLogicTest {
    @Test
    fun `a request fingerprint is shown as the same four groups as a contact's`() {
        assertEquals("A1B2 C3D4 E5F6 0718", paveShortFingerprintFromHex("a1b2c3d4e5f60718" + "9".repeat(48)))
        assertNull(paveShortFingerprintFromHex(null))
        assertNull(paveShortFingerprintFromHex("a1b2"))
        assertNull(paveShortFingerprintFromHex("zz" + "0".repeat(62)))
    }

    @Test
    fun `a remote fingerprint comes from the proven session key, never the announced one`() {
        val announced = ByteArray(32) { 7 } // what an impostor can copy from Nora's announces
        val proven = ByteArray(32) { 9 }
        assertEquals(PaveFingerprint.short(proven), paveSenderFingerprint("synthetic-peer") { proven })
        assertNull(paveSenderFingerprint("synthetic-peer") { null })
        assertNull(paveSenderFingerprint(null) { announced })
        val app = java.io.File("src/main/java/com/bitchat/android/ui/pave/WeshPaveApp.kt").readText()
        assertFalse("WeshPaveApp reads an announced key", "noisePublicKey" in app || "getMeshPeerInfo" in app)
    }

    @Test
    fun `the public room never claims a receipt`() {
        val delivered = DeliveryStatus.Delivered("synthetic", Date(0))
        assertEquals(PaveDelivery.Sent, paveDelivery(delivered, public = true))
        assertEquals(PaveDelivery.Received, paveDelivery(delivered, public = false))
        assertEquals(PaveDelivery.Waiting, paveDelivery(null, public = false))
        assertEquals(PaveDelivery.Waiting, paveDelivery(DeliveryStatus.Sending, public = false))
        assertEquals(PaveDelivery.Failed, paveDelivery(DeliveryStatus.Failed("synthetic"), public = true))
    }

    @Test
    fun `look-alike nicknames are namesakes`() {
        assertTrue(weshNamesakes("Nora", "nora"))
        assertTrue(weshNamesakes("Nora", "Nоra")) // Cyrillic o
        assertFalse(weshNamesakes("Nora", "Léo"))
        assertFalse(weshNamesakes("", ""))
    }

    @Test
    fun `back always leads home through the settings tree`() {
        PaveRoute.entries.forEach { route ->
            var current: PaveRoute? = route
            var steps = 0
            while (current != null && current != PaveRoute.Home && steps < 5) { current = current.parent; steps++ }
            assertEquals("$route", PaveRoute.Home, current)
        }
        assertEquals(PaveRoute.Settings, paveRouteFor(PaveSettingsEntry.Connexion))
        assertEquals(PaveRoute.Erase.parent, PaveRoute.Settings)
    }

    @Test
    fun `an unknown appearance falls back to Nuit`() {
        assertEquals(WeshAppearance.Jour, parseAppearance("Jour"))
        assertEquals(WeshAppearance.Nuit, parseAppearance("synthetic"))
    }

    @Test
    fun `the Internet sheet mirrors the real state machine`() {
        assertEquals(PaveInternetStatus.Off, paveInternetStatus(WeshInternetState.Off, 3))
        assertEquals(PaveInternetStatus.Connecting(40), paveInternetStatus(WeshInternetState.Connecting(40), 0))
        assertEquals(PaveInternetStatus.On(2), paveInternetStatus(WeshInternetState.On, 2))
        assertEquals(PaveInternetStatus.Error(PaveInternetError.TorBlocked), paveInternetStatus(WeshInternetState.Error(WeshInternetError.TOR_BLOCKED), 0))
    }
}
