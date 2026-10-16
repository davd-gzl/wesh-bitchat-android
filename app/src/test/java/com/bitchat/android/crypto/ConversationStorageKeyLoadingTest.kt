package com.bitchat.android.crypto

import com.bitchat.android.services.ConversationStorageKeyProvider
import com.bitchat.android.services.AndroidConversationStorageCipher

import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class ConversationStorageKeyLoadingTest {
    private class Keys(var stored: SecretKey? = null) : ConversationStorageKeyProvider {
        var creates = 0
        var fails = false
        override fun loadExisting(): SecretKey? { check(!fails); return stored }
        override fun create(): SecretKey { creates++; return SecretKeySpec(ByteArray(32) { 7 }, "AES").also { stored = it } }
        override fun delete() { stored = null }
    }
    private val envelope = byteArrayOf(1) + ByteArray(28)

    @Test fun `orphaned ciphertext cannot create a replacement storage key`() {
        val keys = Keys()
        assertTrue(runCatching { AndroidConversationStorageCipher(keys).decrypt(envelope, byteArrayOf(1)) }.isFailure)
        assertEquals(0, keys.creates)
        assertNull(keys.stored)
    }
    @Test fun `unavailable or invalid existing alias is never replaced on read or write`() {
        for (keys in listOf(Keys().apply { fails = true }, Keys(SecretKeySpec(ByteArray(32), "HmacSHA256")))) {
            val cipher = AndroidConversationStorageCipher(keys)
            assertTrue(runCatching { cipher.decrypt(envelope, byteArrayOf(1)) }.isFailure)
            assertTrue(runCatching { cipher.encrypt(byteArrayOf(2), byteArrayOf(1)) }.isFailure)
            assertEquals(0, keys.creates)
        }
    }
    @Test fun `encrypt creates once but decrypt only loads existing key and binds associated data`() {
        val keys = Keys()
        val encrypted = AndroidConversationStorageCipher(keys).encrypt("synthetic".toByteArray(), byteArrayOf(1))
        assertEquals(1, keys.creates)
        val reader = AndroidConversationStorageCipher(keys)
        assertEquals("synthetic", reader.decrypt(encrypted, byteArrayOf(1)).toString(Charsets.UTF_8))
        assertTrue(runCatching { reader.decrypt(encrypted, byteArrayOf(2)) }.isFailure)
        assertEquals(1, keys.creates)
    }
    @Test fun `destroy invalidates cipher cache and decrypt cannot regenerate key`() {
        val keys = Keys()
        val cipher = AndroidConversationStorageCipher(keys)
        val encrypted = cipher.encrypt(byteArrayOf(2), byteArrayOf(1))
        cipher.destroyKey()
        assertTrue(runCatching { cipher.decrypt(encrypted, byteArrayOf(1)) }.isFailure)
        assertEquals(1, keys.creates)
    }
}
