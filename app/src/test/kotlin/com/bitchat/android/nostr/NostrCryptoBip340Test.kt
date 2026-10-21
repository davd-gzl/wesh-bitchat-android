package com.bitchat.android.nostr

import android.app.Application
import com.bitchat.android.util.dataFromHexString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Official BIP-340 vectors (bitcoin/bips, bip-0340/test-vectors.csv) and keys shared with iOS's libsecp256k1. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class NostrCryptoBip340Test {
    private data class Vector(
        val index: Int, val secretKey: String, val publicKey: String, val auxRand: String,
        val message: String, val signature: String, val valid: Boolean
    )

    private val vectors: List<Vector> by lazy {
        javaClass.classLoader!!.getResourceAsStream("fixtures/bip340-test-vectors.csv")!!.bufferedReader().readLines()
            .drop(1).filter { it.isNotBlank() }.map { line ->
                val f = line.split(",")
                Vector(f[0].toInt(), f[1].lowercase(), f[2].lowercase(), f[3].lowercase(), f[4].lowercase(), f[5].lowercase(), f[6] == "TRUE")
            }
    }

    // Vectors 15 to 18 sign messages that are not 32 bytes; Nostr ids and the npub proof are always 32-byte hashes.
    private val hashVectors get() = vectors.filter { it.message.length == 64 }

    @Test
    fun `signing reproduces every official signature from its secret key and aux rand`() {
        val signing = hashVectors.filter { it.secretKey.isNotEmpty() }
        assertEquals(listOf(0, 1, 2, 3), signing.map { it.index })
        for (v in signing) {
            assertEquals("public key of vector ${v.index}", v.publicKey, NostrCrypto.derivePublicKey(v.secretKey))
            val signature = NostrCrypto.schnorrSign(v.message.dataFromHexString()!!, v.secretKey, v.auxRand.dataFromHexString()!!)
            assertEquals("signature of vector ${v.index}", v.signature, signature)
        }
    }

    @Test
    fun `verification gives the official result for every vector`() {
        assertEquals(15, hashVectors.size)
        for (v in hashVectors) {
            assertEquals("vector ${v.index}", v.valid, NostrCrypto.schnorrVerify(v.message.dataFromHexString()!!, v.signature, v.publicKey))
        }
    }

    @Test
    fun `a fresh signature verifies and changes with the aux rand`() {
        val (secret, public) = NostrCrypto.generateKeyPair()
        val message = ByteArray(32) { it.toByte() }
        val first = NostrCrypto.schnorrSign(message, secret)
        assertTrue(NostrCrypto.schnorrVerify(message, first, public))
        assertTrue(NostrCrypto.schnorrVerify(message, NostrCrypto.schnorrSign(message, secret), public))
        assertFalse(NostrCrypto.schnorrVerify(ByteArray(32), first, public))
        assertEquals(public, NostrCrypto.derivePublicKey(secret))
    }

    @Test
    fun `out of range secret keys and off-curve public keys are refused`() {
        assertFalse(NostrCrypto.isValidPrivateKey("00".repeat(32)))
        assertFalse(NostrCrypto.isValidPrivateKey("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141"))
        assertTrue(NostrCrypto.isValidPrivateKey("00".repeat(31) + "01"))
        // Vector 5: a public key that is not on the curve.
        assertFalse(NostrCrypto.isValidPublicKey(vectors.single { it.index == 5 }.publicKey))
        assertTrue(NostrCrypto.isValidPublicKey(vectors.single { it.index == 0 }.publicKey))
    }

    @Test
    fun `a ciphertext from the previous implementation still decrypts`() {
        // Encrypted by the BouncyCastle implementation this replaced, to the even-Y lift as iOS does.
        val ciphertext = "v2:fY8kIIGYUocB5ESrlYUaBFtLrF2cpYBuEZJSufz1XPAGzJZ55ELUNSbIH33PZ9TwQZoc5f2R8ggzMcnkH7NHiUA"
        val sender = NostrCrypto.derivePublicKey("00".repeat(31) + "03")
        val recipient = "b7e151628aed2a6abf7158809cf4f3c762e7160f38b4da56a784d9045190cfef"
        assertEquals("Synthetic NIP-44 sentinel", NostrCrypto.decryptNIP44(ciphertext, sender, recipient))
        val roundTrip = NostrCrypto.encryptNIP44("Synthetic NIP-44 sentinel", NostrCrypto.derivePublicKey(recipient), "00".repeat(31) + "03")
        assertEquals("Synthetic NIP-44 sentinel", NostrCrypto.decryptNIP44(roundTrip, sender, recipient))
    }
}
