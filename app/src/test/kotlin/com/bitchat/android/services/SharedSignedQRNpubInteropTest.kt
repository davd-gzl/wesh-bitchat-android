package com.bitchat.android.services

import android.app.Application
import android.os.SystemClock
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.nostr.Bech32
import com.bitchat.android.nostr.NostrCrypto
import com.bitchat.android.util.dataFromHexString
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

/** Same immutable synthetic fixture as the iOS npub QR interop test: v1 payload with the npub filled. */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28], manifest = Config.NONE, application = Application::class,
    instrumentedPackages = ["com.bitchat.android.services.VerificationService"]
)
@LooperMode(LooperMode.Mode.PAUSED)
class SharedSignedQRNpubInteropTest {
    private lateinit var fixture: JSONObject
    private lateinit var encryption: EncryptionService
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Before fun setup() {
        fixture = javaClass.classLoader!!.getResourceAsStream("fixtures/wesh-signed-qr-v1-npub.json")!!.use {
            JSONObject(it.bufferedReader(Charsets.UTF_8).readText())
        }
        // Strong reference: VerificationService keeps a weak one. Verification needs no local identity.
        encryption = object : EncryptionService(RuntimeEnvironment.getApplication()) {
            override fun initialize() = Unit
        }
        VerificationService.configure(encryption)
        assertTrue(SystemClock.setCurrentTimeMillis(fixture.getLong("timestamp") * 1000L))
    }

    @Test fun sharedNpubURLVerifiesWithIdenticalCanonicalBytesAndURL() {
        val url = fixture.getString("url")
        val parsed = VerificationService.verifyScannedQR(url)!!
        assertEquals(fixture.getString("npub"), parsed.npub)
        assertEquals(fixture.getString("signatureHex"), parsed.sigHex)
        assertEquals(fixture.getString("canonicalHex"), parsed.canonicalBytes().hex())
        assertEquals(fixture.getString("canonicalSHA256"), MessageDigest.getInstance("SHA-256").digest(parsed.canonicalBytes()).hex())
        // Android serializes the same signed payload to the same URL, npub last.
        assertEquals(url, parsed.toUrlString())
        assertNull(VerificationService.verifyScannedQR(url.substringBefore("&npub=")))
    }

    @Test fun sharedNpubProofVerifiesAndLeavesTheSignedPayloadUnchanged() {
        val proof = javaClass.classLoader!!.getResourceAsStream("fixtures/wesh-signed-qr-v1-npub-proof.json")!!.use {
            JSONObject(it.bufferedReader(Charsets.UTF_8).readText())
        }
        val url = proof.getString("url")
        val parsed = VerificationService.verifyScannedQR(url)!!
        assertEquals(fixture.getString("canonicalHex"), parsed.canonicalBytes().hex())
        assertEquals(proof.getString("npubSigHex"), parsed.npubSigHex)
        assertEquals(proof.getString("npubProofMessageHex"), VerificationService.npubProofMessage(parsed).hex())
        assertTrue(WeshNpubProof.isValid(parsed))
        assertEquals(url, parsed.toUrlString())
        // The fixture URL without the proof is exactly the unproven npub fixture.
        assertEquals(fixture.getString("url"), url.substringBefore("&npubsig="))
        val fresh = NostrCrypto.schnorrSign(VerificationService.npubProofMessage(parsed), proof.getString("nostrSecretHex"))
        assertTrue(WeshNpubProof.isValid(parsed.copy(npubSigHex = fresh)))
    }

    @Test fun fixtureNpubIsTheEncodedPublicSyntheticNostrKeyAndPassesEnrollment() {
        val xOnly = NostrCrypto.derivePublicKey(fixture.getString("nostrSecretHex"))
        assertEquals(fixture.getString("nostrXOnlyPublicKeyHex"), xOnly)
        assertEquals(fixture.getString("npub"), Bech32.encode("npub", xOnly.dataFromHexString()!!))
        assertTrue(WeshNpubPolicy.isValid(fixture.getString("npub")))
    }
}
