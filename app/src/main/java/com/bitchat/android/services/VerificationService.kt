package com.bitchat.android.services

import android.net.Uri
import android.util.Base64
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.util.AppConstants
import com.bitchat.android.util.dataFromHexString
import com.bitchat.android.util.hexEncodedString
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import androidx.core.net.toUri
import java.lang.ref.WeakReference

/**
 * QR verification helpers: schema, signing, and basic challenge/response helpers.
 */
object VerificationService {
    private const val CONTEXT = "bitchat-verify-v1"
    private const val RESPONSE_CONTEXT = "bitchat-verify-resp-v1"
    private const val NPUB_PROOF_DOMAIN = "wesh-npub-proof-v1"

    private var encryptionServiceRef: WeakReference<EncryptionService>? = null

    fun configure(encryptionService: EncryptionService) {
        this.encryptionServiceRef = WeakReference(encryptionService)
    }

    data class VerificationQR(
        val v: Int,
        val noiseKeyHex: String,
        val signKeyHex: String,
        val npub: String?,
        val nickname: String,
        val ts: Long,
        val nonceB64: String,
        val sigHex: String,
        /** Optional Wesh proof that the npub key endorses this QR; outside the canonical bytes. */
        val npubSigHex: String? = null
    ) {
        fun canonicalBytes(): ByteArray {
            val out = ByteArrayOutputStream()

            fun appendField(value: String) {
                val data = value.toByteArray(Charsets.UTF_8)
                val len = minOf(data.size, 255)
                out.write(len)
                out.write(data, 0, len)
            }

            appendField(CONTEXT)
            appendField(v.toString())
            appendField(noiseKeyHex.lowercase())
            appendField(signKeyHex.lowercase())
            appendField(npub ?: "")
            appendField(nickname)
            appendField(ts.toString())
            appendField(nonceB64)
            return out.toByteArray()
        }

        fun toUrlString(): String {
            val builder = Uri.Builder()
                .scheme("bitchat")
                .authority("verify")
                .appendQueryParameter("v", v.toString())
                .appendQueryParameter("noise", noiseKeyHex)
                .appendQueryParameter("sign", signKeyHex)
                .appendQueryParameter("nick", nickname)
                .appendQueryParameter("ts", ts.toString())
                .appendQueryParameter("nonce", nonceB64)
                .appendQueryParameter("sig", sigHex)
            if (npub != null) {
                builder.appendQueryParameter("npub", npub)
                npubSigHex?.let { builder.appendQueryParameter("npubsig", it) }
            }
            return builder.build().toString()
        }

        companion object {
            fun fromUrlString(urlString: String): VerificationQR? = runCatching parser@ {
                if (urlString.toByteArray(Charsets.UTF_8).size > 2048) return@parser null
                val uri = runCatching { urlString.toUri() }.getOrNull() ?: return@parser null
                if (uri.scheme != "bitchat" || uri.encodedAuthority != "verify" || uri.host != "verify" || !uri.isHierarchical ||
                    uri.userInfo != null || uri.port != -1 || uri.fragment != null ||
                    !uri.path.isNullOrEmpty()) return@parser null
                val allowed = setOf("v", "noise", "sign", "nick", "ts", "nonce", "sig", "npub", "npubsig")
                if (uri.queryParameterNames.any { it !in allowed || uri.getQueryParameters(it).size != 1 }) return@parser null

                val vStr = uri.getQueryParameter("v") ?: return@parser null
                val v = vStr.toIntOrNull() ?: return@parser null
                val noise = uri.getQueryParameter("noise") ?: return@parser null
                val sign = uri.getQueryParameter("sign") ?: return@parser null
                val nick = uri.getQueryParameter("nick") ?: return@parser null
                val tsStr = uri.getQueryParameter("ts") ?: return@parser null
                val ts = tsStr.toLongOrNull() ?: return@parser null
                val nonce = uri.getQueryParameter("nonce") ?: return@parser null
                val sig = uri.getQueryParameter("sig") ?: return@parser null
                val npub = uri.getQueryParameter("npub")
                // A malformed proof is kept so enrollment treats it as a failed proof.
                val npubSig = uri.getQueryParameter("npubsig")

                if (v != 1 || !noise.matches(Regex("[0-9a-fA-F]{64}")) ||
                    !sign.matches(Regex("[0-9a-fA-F]{64}")) || !sig.matches(Regex("[0-9a-fA-F]{128}")) ||
                    nick.toByteArray(Charsets.UTF_8).size > 255 ||
                    (npub?.toByteArray(Charsets.UTF_8)?.size ?: 0) > 255 ||
                    (npubSig != null && npub == null) ||
                    (npubSig?.toByteArray(Charsets.UTF_8)?.size ?: 0) > 255 || ts <= 0 ||
                    !nonce.matches(Regex("[A-Za-z0-9_-]{22}"))) return@parser null
                val decoded = runCatching { Base64.decode(nonce, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }.getOrNull() ?: return@parser null
                if (decoded.size != 16 || Base64.encodeToString(decoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) != nonce) return@parser null

                VerificationQR(
                    v = v,
                    noiseKeyHex = noise,
                    signKeyHex = sign,
                    npub = npub,
                    nickname = nick,
                    ts = ts,
                    nonceB64 = nonce,
                    sigHex = sig,
                    npubSigHex = npubSig
                )
            }.getOrNull()
        }
    }

    /** BIP-340 message for the npub proof: SHA-256(domain || canonical bytes). */
    fun npubProofMessage(qr: VerificationQR): ByteArray = java.security.MessageDigest.getInstance("SHA-256").run {
        update(NPUB_PROOF_DOMAIN.toByteArray(Charsets.UTF_8))
        digest(qr.canonicalBytes())
    }

    /** True only for a well-formed proof made by the key the npub encodes. */
    fun hasValidNpubProof(qr: VerificationQR): Boolean = runCatching {
        val npub = qr.npub ?: return false
        val sig = qr.npubSigHex ?: return false
        if (!sig.matches(Regex("[0-9a-f]{128}")) || !WeshNpubPolicy.isValid(npub)) return false
        val key = com.bitchat.android.nostr.Bech32.decode(npub).second.hexEncodedString()
        com.bitchat.android.nostr.NostrCrypto.schnorrVerify(npubProofMessage(qr), sig, key)
    }.getOrDefault(false)

    /**
     * [npubSigner] signs the npub proof message with the npub's secret key. Without a signer the
     * npub is unproven (BitChat behavior). A failed signature builds no QR; callers drop the npub.
     */
    fun buildMyQRString(nickname: String, npub: String?, npubSigner: ((ByteArray) -> String?)? = null): String? {
        val service = encryptionServiceRef?.get() ?: return null
        val noiseKey = service.getStaticPublicKey()?.hexEncodedString() ?: return null
        val signKey = service.getSigningPublicKey()?.hexEncodedString() ?: return null
        val cache = Cache.last
        if (cache != null && cache.nickname == nickname && cache.npub == npub && cache.proven == (npubSigner != null) && VerificationQR.fromUrlString(cache.value)?.let { it.noiseKeyHex == noiseKey && it.signKeyHex == signKey } == true) {
            if (System.currentTimeMillis() - cache.builtAtMs < 60_000L) {
                return cache.value
            }
        }

        val ts = System.currentTimeMillis() / 1000L
        val nonce = ByteArray(16)
        SecureRandom().nextBytes(nonce)
        val nonceB64 = Base64.encodeToString(
            nonce,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )

        val payload = VerificationQR(
            v = 1,
            noiseKeyHex = noiseKey,
            signKeyHex = signKey,
            npub = npub,
            nickname = nickname,
            ts = ts,
            nonceB64 = nonceB64,
            sigHex = ""
        )

        val signature = service.signData(payload.canonicalBytes()) ?: return null
        val signed = payload.copy(sigHex = signature.hexEncodedString()).let { qr ->
            if (npub == null || npubSigner == null) qr
            else qr.copy(npubSigHex = npubSigner(npubProofMessage(qr)) ?: return null)
        }
        val out = signed.toUrlString()
        Cache.last = CacheEntry(nickname, npub, npubSigner != null, System.currentTimeMillis(), out)
        return out
    }

    fun verifyScannedQR(
        urlString: String,
        maxAgeSeconds: Long = 300
    ): VerificationQR? {
        val service = encryptionServiceRef?.get() ?: return null
        val qr = VerificationQR.fromUrlString(urlString) ?: return null
        val now = System.currentTimeMillis() / 1000L
        if (!isFresh(qr.ts, now, maxAgeSeconds)) return null

        val sig = qr.sigHex.dataFromHexString() ?: return null
        val signKey = qr.signKeyHex.dataFromHexString() ?: return null
        val ok = service.verifyEd25519Signature(sig, qr.canonicalBytes(), signKey)
        return if (ok) qr else null
    }

    internal fun isFresh(timestamp: Long, now: Long, maxAgeSeconds: Long = 300): Boolean =
        maxAgeSeconds in 0..300 && timestamp > 0 && timestamp >= now - maxAgeSeconds && timestamp <= now + maxAgeSeconds

    fun buildVerifyChallenge(noiseKeyHex: String, nonceA: ByteArray): ByteArray {
        val noiseData = noiseKeyHex.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        out.write(0x01)
        out.write(minOf(noiseData.size, 255))
        out.write(noiseData, 0, minOf(noiseData.size, 255))
        out.write(0x02)
        out.write(minOf(nonceA.size, 255))
        out.write(nonceA, 0, minOf(nonceA.size, 255))
        return out.toByteArray()
    }

    fun buildVerifyResponse(noiseKeyHex: String, nonceA: ByteArray): ByteArray? {
        val service = encryptionServiceRef?.get() ?: return null
        val noiseData = noiseKeyHex.toByteArray(Charsets.UTF_8)
        val msg = ByteArrayOutputStream()
        msg.write(RESPONSE_CONTEXT.toByteArray(Charsets.UTF_8))
        msg.write(minOf(noiseData.size, 255))
        msg.write(noiseData, 0, minOf(noiseData.size, 255))
        msg.write(nonceA)
        val sig = service.signData(msg.toByteArray()) ?: return null

        val out = ByteArrayOutputStream()
        out.write(0x01)
        out.write(minOf(noiseData.size, 255))
        out.write(noiseData, 0, minOf(noiseData.size, 255))
        out.write(0x02)
        out.write(minOf(nonceA.size, 255))
        out.write(nonceA, 0, minOf(nonceA.size, 255))
        out.write(0x03)
        out.write(minOf(sig.size, 255))
        out.write(sig, 0, minOf(sig.size, 255))
        return out.toByteArray()
    }

    fun parseVerifyChallenge(data: ByteArray): Pair<String, ByteArray>? {
        val reader = VerificationTlvReader(data)
        val noise = reader.noiseKey() ?: return null
        val nonce = reader.field(0x02, 16) ?: return null
        if (!reader.isExhausted()) return null
        return noise to nonce
    }

    data class VerifyResponse(val noiseKeyHex: String, val nonceA: ByteArray, val signature: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as VerifyResponse

            if (noiseKeyHex != other.noiseKeyHex) return false
            if (!nonceA.contentEquals(other.nonceA)) return false
            if (!signature.contentEquals(other.signature)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = noiseKeyHex.hashCode()
            result = 31 * result + nonceA.contentHashCode()
            result = 31 * result + signature.contentHashCode()
            return result
        }
    }

    fun parseVerifyResponse(data: ByteArray): VerifyResponse? {
        val reader = VerificationTlvReader(data)
        val noise = reader.noiseKey() ?: return null
        val nonce = reader.field(0x02, 16) ?: return null
        val sig = reader.field(0x03, 64) ?: return null
        if (!reader.isExhausted()) return null
        return VerifyResponse(noise, nonce, sig)
    }

    /** Fixed verification schema; malformed fields never reach range arithmetic. */
    private class VerificationTlvReader(private val data: ByteArray) {
        private var offset = 0

        fun field(expectedTag: Int, expectedSize: Int): ByteArray? {
            if (data.size - offset < 2) return null
            val tag = data[offset++].toInt() and 0xff
            val size = data[offset++].toInt() and 0xff
            // offset is within data; subtraction avoids an unchecked offset+size.
            if (tag != expectedTag || size != expectedSize || size > data.size - offset) return null
            val end = offset + size
            val value = data.copyOfRange(offset, end)
            offset = end
            return value
        }

        fun noiseKey(): String? {
            val bytes = field(0x01, 64) ?: return null
            val isHex = bytes.all {
                val value = it.toInt()
                value in 48..57 || value in 65..70 || value in 97..102
            }
            if (!isHex) return null
            return bytes.toString(Charsets.US_ASCII)
        }

        fun isExhausted(): Boolean = offset == data.size
    }

    fun verifyResponseSignature(
        noiseKeyHex: String,
        nonceA: ByteArray,
        signature: ByteArray,
        signerPublicKeyHex: String
    ): Boolean {
        val service = encryptionServiceRef?.get() ?: return false
        val noiseData = noiseKeyHex.toByteArray(Charsets.UTF_8)
        val msg = ByteArrayOutputStream()
        msg.write(RESPONSE_CONTEXT.toByteArray(Charsets.UTF_8))
        msg.write(minOf(noiseData.size, 255))
        msg.write(noiseData, 0, minOf(noiseData.size, 255))
        msg.write(nonceA)
        val signerKey = signerPublicKeyHex.dataFromHexString() ?: return false
        return service.verifyEd25519Signature(signature, msg.toByteArray(), signerKey)
    }

    private data class CacheEntry(
        val nickname: String,
        val npub: String?,
        val proven: Boolean,
        val builtAtMs: Long,
        val value: String
    )

    private object Cache {
        var last: CacheEntry? = null
    }
}
