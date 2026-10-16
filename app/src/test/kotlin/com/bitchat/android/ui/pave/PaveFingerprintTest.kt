package com.bitchat.android.ui.pave

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaveFingerprintTest {
    private val key = ByteArray(32) { (it * 7).toByte() }

    @Test
    fun `short fingerprint is the first four groups of the key's SHA-256, uppercase`() {
        val digest = MessageDigest.getInstance("SHA-256").digest(key)
            .joinToString("") { "%02X".format(it) }
        val expected = digest.take(16).chunked(4).joinToString(" ")
        assertEquals(expected, PaveFingerprint.short(key))
        assertTrue(Regex("[0-9A-F]{4}( [0-9A-F]{4}){3}").matches(PaveFingerprint.short(key)!!))
    }

    @Test
    fun `different keys give different fingerprints`() {
        assertNotEquals(PaveFingerprint.short(key), PaveFingerprint.short(ByteArray(32)))
    }

    @Test
    fun `no fingerprint without a valid Noise key`() {
        assertNull(PaveFingerprint.short(null))
        assertNull(PaveFingerprint.short(ByteArray(31)))
        assertNull(PaveFingerprint.short(ByteArray(33)))
    }

    @Test
    fun `TalkBack reads the fingerprint one character at a time`() {
        assertEquals("7 F 3 A, 9 1 C 2", PaveFingerprint.spoken("7F3A 91C2"))
    }
}
