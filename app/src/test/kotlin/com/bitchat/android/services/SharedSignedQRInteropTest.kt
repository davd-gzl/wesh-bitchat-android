package com.bitchat.android.services

import android.app.Application
import android.os.SystemClock
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.util.dataFromHexString
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.security.MessageDigest

/** Same immutable synthetic fixture as iOS SharedSignedQRInteropTests.
 * Signed QR API compatibility only: no live Noise session, camera, or device identity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28], manifest = Config.NONE, application = Application::class,
    // Instrument the real verifier's System.currentTimeMillis call, not the @RunWith test class.
    instrumentedPackages = ["com.bitchat.android.services.VerificationService"]
)
@LooperMode(LooperMode.Mode.PAUSED)
class SharedSignedQRInteropTest {
    private lateinit var fixture: JSONObject
    private lateinit var encryption: EncryptionService

    @Before fun setup() {
        fixture = javaClass.classLoader!!.getResourceAsStream("fixtures/wesh-signed-qr-v1.json")!!.use {
            JSONObject(it.bufferedReader(Charsets.UTF_8).readText())
        }
        // Hold a strong reference: VerificationService intentionally stores a weak reference.
        encryption = object : EncryptionService(RuntimeEnvironment.getApplication()) {
            // Verification uses the QR's public key. No local private identity or keystore is needed.
            override fun initialize() = Unit
        }
        VerificationService.configure(encryption)
        setClock(timestamp - 301)
    }

    private val timestamp: Long get() = fixture.getLong("timestamp")
    private val url: String get() = fixture.getString("url")
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private fun setClock(seconds: Long) {
        // @RunWith test classes are not instrumented: their System.currentTimeMillis stays real.
        // The explicit verifier instrumentation above intercepts its clock read. Successful
        // acceptance at the fixture date and rejection at +/-301 below prove that boundary.
        assertTrue(SystemClock.setCurrentTimeMillis(seconds * 1000L))
        assertEquals("Robolectric clock must be fixed", seconds, SystemClock.uptimeMillis() / 1000L)
    }

    @Test fun sharedSignedURLPassesRealVerificationAndCanonicalEncoding() {
        setClock(timestamp)
        val parsed = VerificationService.verifyScannedQR(url)
        assertNotNull(parsed)
        parsed!!
        assertEquals(fixture.getString("nickname"), parsed.nickname)
        assertEquals(fixture.getString("noiseKeyHex"), parsed.noiseKeyHex)
        assertEquals(fixture.getString("signKeyHex"), parsed.signKeyHex)
        assertEquals(fixture.getString("nonceB64"), parsed.nonceB64)
        assertEquals(timestamp, parsed.ts)
        assertEquals(fixture.getString("signatureHex"), parsed.sigHex)
        assertEquals(fixture.getString("canonicalHex"), parsed.canonicalBytes().hex())
        assertEquals(fixture.getString("canonicalSHA256"), MessageDigest.getInstance("SHA-256").digest(parsed.canonicalBytes()).hex())
    }

    @Test fun publicSyntheticSeedAuthenticatesFixtureAndFreshSignature() {
        val seed = fixture.getString("seedHex").dataFromHexString()!!
        val key = Ed25519PrivateKeyParameters(seed, 0)
        val canonical = fixture.getString("canonicalHex").dataFromHexString()!!
        val signature = Ed25519Signer().apply {
            init(true, key)
            update(canonical, 0, canonical.size)
        }.generateSignature()
        assertEquals(fixture.getString("signKeyHex"), key.generatePublicKey().encoded.hex())
        val publicKey = key.generatePublicKey().encoded
        assertTrue(encryption.verifyEd25519Signature(fixture.getString("signatureHex").dataFromHexString()!!, canonical, publicKey))
        assertTrue(encryption.verifyEd25519Signature(signature, canonical, publicKey))
    }

    @Test fun signatureNicknameNonceAndTimestampTamperingAreRejected() {
        setClock(timestamp)
        val signature = fixture.getString("signatureHex")
        val variants = listOf(
            url.replace(signature, "0" + signature.drop(1)),
            url.replace("nick=Nora%20caf%C3%A9", "nick=Nora%20cafe"),
            url.replace(fixture.getString("nonceB64"), "AAECAwQFBgcICQoLDA0ODg"),
            url.replace("ts=1700000000", "ts=1700000001")
        )
        variants.forEach { mutated ->
            assertNotEquals(url, mutated)
            // Parser-valid mutations isolate cryptographic rejection.
            assertNotNull(VerificationService.VerificationQR.fromUrlString(mutated))
            assertNull(VerificationService.verifyScannedQR(mutated))
        }
    }

    @Test fun sameSignedFixtureHasInclusiveSymmetricFreshnessBoundary() {
        // Monotone order also respects Robolectric's no-backwards clock rule.
        listOf(-301L, -300L, 0L, 300L, 301L).forEach { offset ->
            setClock(timestamp + offset)
            assertEquals("Clock offset: $offset", kotlin.math.abs(offset) <= 300,
                VerificationService.verifyScannedQR(url) != null)
        }
    }
}
