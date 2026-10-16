package com.bitchat.android.identity

import android.content.Context
import com.bitchat.android.model.AuthenticatedPeerState
import com.bitchat.android.model.PeerCapabilities
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class AuthenticatedPeerStatePersistenceTest {
    private val fingerprint = "ab".repeat(32)
    private val legacyMediaPinsKey = "private_media_capability_pins_v1"
    private lateinit var manager: SecureIdentityStateManager
    private lateinit var prefs: android.content.SharedPreferences

    @Before
    fun setup() {
        prefs = RuntimeEnvironment.getApplication().getSharedPreferences(
            "authenticated-peer-state-${UUID.randomUUID()}",
            Context.MODE_PRIVATE
        )
        manager = SecureIdentityStateManager(prefs, testOnly = true)
        manager.clearIdentityData()
    }

    @After
    fun tearDown() {
        manager.clearIdentityData()
    }

    @Test
    fun `authenticated Ed key and capabilities persist rotate and clear atomically`() {
        val firstKey = ByteArray(32) { 0x11 }
        val rotatedKey = ByteArray(32) { 0x22 }
        // A peer may still prove BitChat's media bit; it is kept as data, never acted on.
        assertTrue(manager.storeAuthenticatedPeerState(
            fingerprint,
            AuthenticatedPeerState(PeerCapabilities.PRIVATE_MEDIA, firstKey)
        ))

        val reloaded = SecureIdentityStateManager(prefs, testOnly = true)
        assertArrayEquals(firstKey, reloaded.getAuthenticatedSigningKey(fingerprint))
        assertEquals(
            PeerCapabilities.PRIVATE_MEDIA,
            reloaded.getAuthenticatedPeerState(fingerprint)?.capabilities
        )

        assertTrue(reloaded.storeAuthenticatedPeerState(
            fingerprint,
            AuthenticatedPeerState(PeerCapabilities.NONE, rotatedKey)
        ))
        assertArrayEquals(rotatedKey, reloaded.getAuthenticatedSigningKey(fingerprint))

        reloaded.clearIdentityData()
        assertFalse(manager.storeAuthenticatedPeerState(
            fingerprint,
            AuthenticatedPeerState(PeerCapabilities.PRIVATE_MEDIA, firstKey)
        ))
        val afterPanic = SecureIdentityStateManager(prefs, testOnly = true)
        assertEquals(null, afterPanic.getAuthenticatedPeerState(fingerprint))
    }

    @Test
    fun `media capability pins written by an older build are erased`() {
        prefs.edit().putStringSet(legacyMediaPinsKey, setOf(fingerprint)).commit()

        assertTrue(manager.storeAuthenticatedPeerState(
            fingerprint,
            AuthenticatedPeerState(PeerCapabilities.NONE, ByteArray(32) { 0x33 })
        ))
        assertFalse(prefs.contains(legacyMediaPinsKey))

        prefs.edit().putStringSet(legacyMediaPinsKey, setOf(fingerprint)).commit()
        manager.clearIdentityData()
        assertFalse(prefs.contains(legacyMediaPinsKey))
    }
}
