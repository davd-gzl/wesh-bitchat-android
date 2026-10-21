package com.bitchat.android.noise

import com.bitchat.android.noise.southernstorm.protocol.Noise
import java.security.NoSuchAlgorithmException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Only Noise_XX_25519_ChaChaPoly_SHA256, the BitChat suite, is compiled in. */
class NoiseAlgorithmSuiteTest {
    @Test
    fun `the BitChat suite is available`() {
        assertEquals("25519", Noise.createDH("25519").getDHName())
        assertEquals("ChaChaPoly", Noise.createCipher("ChaChaPoly").getCipherName())
        assertEquals(32, Noise.createHash("SHA256").getDigestLength())
    }

    @Test
    fun `every other algorithm is refused`() {
        listOf("448", "NewHope").forEach { name -> assertThrows(NoSuchAlgorithmException::class.java) { Noise.createDH(name) } }
        assertThrows(NoSuchAlgorithmException::class.java) { Noise.createCipher("AESGCM") }
        listOf("SHA512", "BLAKE2b", "BLAKE2s").forEach { name -> assertThrows(NoSuchAlgorithmException::class.java) { Noise.createHash(name) } }
    }
}
