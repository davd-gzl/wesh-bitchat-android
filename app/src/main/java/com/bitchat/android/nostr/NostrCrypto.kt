package com.bitchat.android.nostr

import fr.acinq.secp256k1.Secp256k1
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter
import com.google.crypto.tink.subtle.XChaCha20Poly1305
import java.security.SecureRandom

/**
 * Cryptographic utilities for Nostr protocol
 * secp256k1 keys, BIP-340 signatures and the NIP-44 shared point come from libsecp256k1,
 * the library iOS uses too.
 */
object NostrCrypto {

    internal const val NIP17_DEFAULT_MAX_PAST_SECONDS = 79_200

    private val secureRandom = SecureRandom()
    // NIP-44 v2 only

    /**
     * Generate secp256k1 key pair
     * Returns (privateKeyHex, publicKeyHex)
     */
    fun generateKeyPair(): Pair<String, String> {
        val privateKey = ByteArray(32)
        do { secureRandom.nextBytes(privateKey) } while (!Secp256k1.secKeyVerify(privateKey))
        return Pair(privateKey.toHexString(), xOnly(Secp256k1.pubkeyCreate(privateKey)).toHexString())
    }

    /**
     * Derive public key from private key
     * Returns x-only public key (32 bytes hex)
     */
    fun derivePublicKey(privateKeyHex: String): String =
        xOnly(Secp256k1.pubkeyCreate(privateKeyHex.hexToByteArray())).toHexString()

    /** [Secp256k1.pubkeyCreate] returns the 65-byte uncompressed key: 04 || x || y. */
    private fun xOnly(publicKey: ByteArray): ByteArray = publicKey.copyOfRange(1, 33)

    /**
     * Compressed ECDH shared point (33 bytes) with an explicit parity choice for the x-only public key
     */
    private fun sharedPointWithParity(privateKeyHex: String, publicKeyHex: String, preferOddY: Boolean): ByteArray {
        val xOnlyKey = publicKeyHex.hexToByteArray()
        require(xOnlyKey.size == 32) { "X-only public key must be 32 bytes" }
        val point = Secp256k1.pubkeyParse(byteArrayOf(if (preferOddY) 0x03 else 0x02) + xOnlyKey)
        val privateKey = privateKeyHex.hexToByteArray()
        // libsecp256k1's tweak multiply is variable-time, and its constant-time ECDH only returns a hash of
        // the point. The key is split into two fresh random shares, (k - b)P + bP, so the timing depends
        // only on values drawn anew for every call; the scalar arithmetic itself is constant-time.
        while (true) {
            val blind = ByteArray(32).also(secureRandom::nextBytes)
            if (!Secp256k1.secKeyVerify(blind)) continue
            val share = runCatching { Secp256k1.privKeyTweakAdd(privateKey, Secp256k1.privKeyNegate(blind)) }.getOrNull()
            if (share == null || !Secp256k1.secKeyVerify(share)) continue
            val shared = Secp256k1.pubKeyCombine(arrayOf(Secp256k1.pubKeyTweakMul(point, share), Secp256k1.pubKeyTweakMul(point, blind)))
            return Secp256k1.pubKeyCompress(shared)
        }
    }

    /**
     * NIP-44 v2 key derivation using HKDF-SHA256
     * salt = empty, info = "nip44-v2", length = 32 bytes
     */
    fun deriveNIP44Key(sharedSecret: ByteArray): ByteArray {
        val zeroSalt = ByteArray(0)
        val prk = hkdfExtract(zeroSalt, sharedSecret)
        return hkdfExpand(prk, info = "nip44-v2".toByteArray(Charsets.UTF_8), length = 32)
    }

    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val hmac = HMac(SHA256Digest())
        hmac.init(KeyParameter(salt))
        hmac.update(ikm, 0, ikm.size)
        val prk = ByteArray(hmac.macSize)
        hmac.doFinal(prk, 0)
        return prk
    }

    private fun hkdfExpand(prk: ByteArray, info: ByteArray?, length: Int): ByteArray {
        val hmac = HMac(SHA256Digest())
        hmac.init(KeyParameter(prk))
        if (info != null && info.isNotEmpty()) {
            hmac.update(info, 0, info.size)
        }
        hmac.update(byteArrayOf(0x01), 0, 1)
        val t = ByteArray(hmac.macSize)
        hmac.doFinal(t, 0)
        return t.copyOf(length)
    }

    /**
     * NIP-44 v2 encryption using XChaCha20-Poly1305
     * Output format: "v2:" + base64url(nonce24 || ciphertext || tag)
     */
    fun encryptNIP44(
        plaintext: String,
        recipientPublicKeyHex: String,
        senderPrivateKeyHex: String
    ): String {
        try {
            // Match iOS: derive HKDF input from the compressed shared point (33 bytes)
            val secretMaterial = sharedPointWithParity(senderPrivateKeyHex, recipientPublicKeyHex, preferOddY = false)
            val encryptionKey = deriveNIP44Key(secretMaterial)
            val aead = XChaCha20Poly1305(encryptionKey)
            val combined = aead.encrypt(plaintext.toByteArray(Charsets.UTF_8), null) // nonce||ct||tag
            val b64 = base64UrlNoPad(combined)
            android.util.Log.d("NostrCrypto", "NIP44 v2 encrypt: len=${b64.length}")
            return "v2:$b64"
        } catch (e: Exception) {
            throw RuntimeException("NIP-44 v2 encryption failed: ${e.message}", e)
        }
    }

    /**
     * NIP-44 v2 decryption using XChaCha20-Poly1305
     * Only accepts the exact "v2:" base64url format.
     * Tries both even/odd Y parities for x-only pubkeys.
     */
    fun decryptNIP44(ciphertext: String, senderPublicKeyHex: String, recipientPrivateKeyHex: String): String {
        try {
            require(ciphertext.startsWith("v2:")) { "Invalid NIP-44 version prefix" }
            val encoded = ciphertext.substring(3)
            val encryptedData = base64UrlDecode(encoded)
                ?: throw IllegalArgumentException("Invalid base64url payload")

            var lastError: Exception? = null
            // Try even-Y first, then odd-Y
            for (preferOdd in listOf(false, true)) {
                try {
                    // Match iOS: derive HKDF input from the compressed shared point (33 bytes)
                    val secretMaterial = sharedPointWithParity(recipientPrivateKeyHex, senderPublicKeyHex, preferOddY = preferOdd)
                    val key = deriveNIP44Key(secretMaterial)
                    val aead = XChaCha20Poly1305(key)
                    val pt = aead.decrypt(encryptedData, null) // expects nonce||ct||tag
                    return String(pt, Charsets.UTF_8)
                } catch (e: Exception) {
                    lastError = e
                }
            }
            throw lastError ?: RuntimeException("NIP-44 v2 decryption failed")
        } catch (e: Exception) {
            throw RuntimeException("NIP-44 v2 decryption failed: ${e.message}", e)
        }
    }

    private fun base64UrlNoPad(data: ByteArray): String {
        val b64 = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
        return b64.replace('+', '-').replace('/', '_').replace("=", "")
    }

    private fun base64UrlDecode(s: String): ByteArray? {
        var str = s.replace('-', '+').replace('_', '/')
        val pad = (4 - (str.length % 4)) % 4
        if (pad > 0) str += "=".repeat(pad)
        return try {
            android.util.Base64.decode(str, android.util.Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
    
    /**
     * Generate random timestamp offset for privacy (±15 minutes)
     */
    fun randomizeTimestamp(baseTimestamp: Long = System.currentTimeMillis() / 1000): Int {
        val offset = secureRandom.nextInt(1800) - 900 // ±15 minutes in seconds
        return (baseTimestamp + offset).toInt()
    }
    
    /**
     * Random timestamp in the past, defaulting to 22 hours to leave 2 hours of slack inside iOS's 24-hour lookback.
     */
    fun randomizeTimestampUpToPast(maxPastSeconds: Int = NIP17_DEFAULT_MAX_PAST_SECONDS): Int {
        val now = (System.currentTimeMillis() / 1000).toInt()
        val offset = if (maxPastSeconds > 0) secureRandom.nextInt(maxPastSeconds + 1) else 0
        return now - offset
    }
    
    /**
     * Validate secp256k1 private key
     */
    fun isValidPrivateKey(privateKeyHex: String): Boolean = try {
        val privateKeyBytes = privateKeyHex.hexToByteArray()
        privateKeyBytes.size == 32 && Secp256k1.secKeyVerify(privateKeyBytes)
    } catch (e: Exception) {
        false
    } catch (e: LinkageError) {
        // The native library failed to load: fail closed rather than crash on a relay event.
        false
    }

    /**
     * Validate x-only public key
     */
    fun isValidPublicKey(publicKeyHex: String): Boolean = try {
        val publicKeyBytes = publicKeyHex.hexToByteArray()
        publicKeyBytes.size == 32 && Secp256k1.pubkeyParse(byteArrayOf(0x02) + publicKeyBytes).isNotEmpty()
    } catch (e: Exception) {
        false
    } catch (e: LinkageError) {
        // The native library failed to load: fail closed rather than crash on a relay event.
        false
    }

    /**
     * BIP-340 Schnorr signature creation with fresh auxiliary randomness
     * Returns 64-byte signature (r || s) as hex string
     */
    fun schnorrSign(messageHash: ByteArray, privateKeyHex: String): String =
        schnorrSign(messageHash, privateKeyHex, ByteArray(32).also(secureRandom::nextBytes))

    internal fun schnorrSign(messageHash: ByteArray, privateKeyHex: String, auxRand: ByteArray): String {
        require(messageHash.size == 32) { "Message hash must be 32 bytes" }
        val privateKeyBytes = privateKeyHex.hexToByteArray()
        require(privateKeyBytes.size == 32 && Secp256k1.secKeyVerify(privateKeyBytes)) { "Invalid private key" }
        return Secp256k1.signSchnorr(messageHash, privateKeyBytes, auxRand).toHexString()
    }

    /**
     * BIP-340 Schnorr signature verification
     */
    fun schnorrVerify(messageHash: ByteArray, signatureHex: String, publicKeyHex: String): Boolean = try {
        val signatureBytes = signatureHex.hexToByteArray()
        val publicKeyBytes = publicKeyHex.hexToByteArray()
        messageHash.size == 32 && signatureBytes.size == 64 && publicKeyBytes.size == 32 &&
            Secp256k1.verifySchnorr(signatureBytes, messageHash, publicKeyBytes)
    } catch (e: Exception) {
        false
    } catch (e: LinkageError) {
        // The native library failed to load: fail closed rather than crash on a relay event.
        false
    }
}
