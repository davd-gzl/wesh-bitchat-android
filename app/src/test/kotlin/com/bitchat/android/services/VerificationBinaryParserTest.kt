package com.bitchat.android.services

import android.app.Application
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.model.NoisePayload
import com.bitchat.android.model.NoisePayloadType
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class VerificationBinaryParserTest {
    private val noiseHex = "0123456789abcdef".repeat(4)
    private val nonce = ByteArray(16) { it.toByte() }
    // Structural parser bytes only, not a valid Ed25519 signature or identity.
    private val signature = ByteArray(64) { (it + 32).toByte() }

    private fun field(tag: Int, value: ByteArray): ByteArray =
        byteArrayOf(tag.toByte(), value.size.toByte()) + value

    private fun challenge(
        noise: ByteArray = noiseHex.toByteArray(Charsets.US_ASCII),
        challengeNonce: ByteArray = nonce
    ): ByteArray = field(1, noise) + field(2, challengeNonce)

    private fun response(
        noise: ByteArray = noiseHex.toByteArray(Charsets.US_ASCII),
        challengeNonce: ByteArray = nonce,
        signedBytes: ByteArray = signature
    ): ByteArray = challenge(noise, challengeNonce) + field(3, signedBytes)

    @Test fun `challenge length with high bit set is rejected without throwing`() {
        // The Noise payload type 0x10 has already been stripped by the transport.
        assertNull(VerificationService.parseVerifyChallenge(byteArrayOf(0x01, 0x80.toByte())))
    }

    @Test fun `response length with high bit set is rejected without throwing`() {
        // The Noise payload type 0x11 has already been stripped by the transport.
        assertNull(VerificationService.parseVerifyResponse(byteArrayOf(0x01, 0x80.toByte())))
    }

    @Test fun `normal challenge retains its golden wire and round trips after type stripping`() {
        // Fixed v1 TLV: 01 40 ASCII64 02 10 nonce16; no change to emitted bytes.
        val golden = byteArrayOf(0x01, 0x40) +
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x02, 0x10, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15)
        val built = VerificationService.buildVerifyChallenge(noiseHex, nonce)
        assertArrayEquals(golden, built)
        val wire = NoisePayload(NoisePayloadType.VERIFY_CHALLENGE, built).encode()
        assertArrayEquals(byteArrayOf(0x10) + golden, wire)
        val payload = requireNotNull(NoisePayload.decode(wire))
        val parsed = requireNotNull(VerificationService.parseVerifyChallenge(payload.data))
        assertEquals(noiseHex, parsed.first)
        assertArrayEquals(nonce, parsed.second)
    }

    @Test fun `normal response retains signing preimage and round trips without a device identity`() {
        val signer = mock<EncryptionService>()
        whenever(signer.signData(any())).thenReturn(signature)
        // Restore the singleton's prior injection even if an assertion fails.
        // Reflection is test-only so production needs no state-reset API.
        val injection = VerificationService::class.java.getDeclaredField("encryptionServiceRef").apply { isAccessible = true }
        val previous = injection.get(null)
        try {
            VerificationService.configure(signer)
            val built = requireNotNull(VerificationService.buildVerifyResponse(noiseHex, nonce))
            val signedInput = argumentCaptor<ByteArray>()
            verify(signer).signData(signedInput.capture())
            assertArrayEquals(
                "bitchat-verify-resp-v1".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x40) +
                    noiseHex.toByteArray(Charsets.US_ASCII) + nonce,
                signedInput.firstValue
            )
            assertEquals(103, signedInput.firstValue.size)
            assertArrayEquals(challenge() + byteArrayOf(0x03, 0x40) + signature, built)
            val wire = NoisePayload(NoisePayloadType.VERIFY_RESPONSE, built).encode()
            assertEquals(151, wire.size)
            val parsed = requireNotNull(VerificationService.parseVerifyResponse(requireNotNull(NoisePayload.decode(wire)).data))
            assertEquals(noiseHex, parsed.noiseKeyHex)
            assertArrayEquals(nonce, parsed.nonceA)
            assertArrayEquals(signature, parsed.signature)
        } finally {
            injection.set(null, previous)
        }
    }

    @Test fun `all truncated prefixes including empty messages fail closed`() {
        val validChallenge = challenge()
        for (size in validChallenge.indices) {
            assertNull("challenge prefix $size", VerificationService.parseVerifyChallenge(validChallenge.copyOf(size)))
        }
        val validResponse = response()
        for (size in validResponse.indices) {
            assertNull("response prefix $size", VerificationService.parseVerifyResponse(validResponse.copyOf(size)))
        }
    }

    @Test fun `all unsigned length headers on an empty field are rejected without range exceptions`() {
        for (length in 0..255) {
            val truncated = byteArrayOf(0x01, length.toByte())
            assertNull("challenge length $length", VerificationService.parseVerifyChallenge(truncated))
            assertNull("response length $length", VerificationService.parseVerifyResponse(truncated))
        }
    }

    @Test fun `fully present high bit length fields are rejected at every field position`() {
        for (size in listOf(128, 255)) {
            val oversized = ByteArray(size) { 'a'.code.toByte() }
            assertNull(VerificationService.parseVerifyChallenge(challenge(noise = oversized)))
            assertNull(VerificationService.parseVerifyChallenge(challenge(challengeNonce = oversized)))
            assertNull(VerificationService.parseVerifyResponse(response(noise = oversized)))
            assertNull(VerificationService.parseVerifyResponse(response(challengeNonce = oversized)))
            assertNull(VerificationService.parseVerifyResponse(response(signedBytes = oversized)))
        }
    }

    @Test fun `nonce and signature sizes must match the existing emitter contract`() {
        for (size in listOf(0, 1, 15, 17, 63, 64, 65, 127)) {
            val invalidNonce = ByteArray(size)
            assertNull(VerificationService.parseVerifyChallenge(challenge(challengeNonce = invalidNonce)))
            assertNull(VerificationService.parseVerifyResponse(response(challengeNonce = invalidNonce)))
        }
        for (size in listOf(0, 1, 16, 63, 65, 127)) {
            assertNull(VerificationService.parseVerifyResponse(response(signedBytes = ByteArray(size))))
        }
    }

    @Test fun `Noise identity must be exactly sixty four ASCII hexadecimal bytes`() {
        for (noise in listOf(
            ByteArray(0), ByteArray(63) { 'a'.code.toByte() }, ByteArray(65) { 'a'.code.toByte() },
            ByteArray(64) { 'g'.code.toByte() }, ByteArray(64) { 0x80.toByte() }
        )) {
            assertNull(VerificationService.parseVerifyChallenge(challenge(noise = noise)))
            assertNull(VerificationService.parseVerifyResponse(response(noise = noise)))
        }
        val uppercase = noiseHex.uppercase().toByteArray(Charsets.US_ASCII)
        assertEquals(noiseHex.uppercase(), VerificationService.parseVerifyChallenge(challenge(noise = uppercase))?.first)
    }

    @Test fun `wrong tags duplicate fields reordered fields and suffixes are not accepted`() {
        val validChallenge = challenge()
        val validResponse = response()
        val nonceField = field(2, nonce)
        val noiseField = field(1, noiseHex.toByteArray(Charsets.US_ASCII))
        listOf(
            validChallenge.copyOf().apply { this[0] = 2 },
            validChallenge.copyOf().apply { this[66] = 1 },
            nonceField + noiseField, noiseField + noiseField + nonceField,
            validChallenge + byteArrayOf(0), byteArrayOf(0x10) + validChallenge
        ).forEach { assertNull(VerificationService.parseVerifyChallenge(it)) }
        listOf(
            validResponse.copyOf().apply { this[84] = 2 },
            validResponse + byteArrayOf(0), validResponse + field(3, signature),
            byteArrayOf(0x11) + validResponse
        ).forEach { assertNull(VerificationService.parseVerifyResponse(it)) }
    }
}
