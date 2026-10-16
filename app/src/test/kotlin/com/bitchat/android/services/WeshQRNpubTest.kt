package com.bitchat.android.services

import android.app.Application
import android.content.Context
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.nostr.Bech32
import com.bitchat.android.util.dataFromHexString
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/** Synthetic public material only: the secp256k1 generator x and a public Ed25519 seed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class WeshQRNpubTest {
    private val generatorX = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
    private val npub = Bech32.encode("npub", generatorX.dataFromHexString()!!)
    // x = 2 is on secp256k1; x = 5 is not (x^3 + 7 is not a square modulo p).
    private val otherNpub = Bech32.encode("npub", ByteArray(31) + byteArrayOf(2))
    private val offCurveNpub = Bech32.encode("npub", ByteArray(31) + byteArrayOf(5))
    private val noiseA = "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"
    private val noiseB = "11".repeat(32)
    private val seed = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f".dataFromHexString()!!
    private lateinit var store: WeshContactStore
    private lateinit var secure: SecureIdentityStateManager

    @Before fun setup() {
        val application = RuntimeEnvironment.getApplication()
        val prefs = application.getSharedPreferences("synthetic-npub", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val blocks = application.getSharedPreferences("synthetic-npub-blocks", Context.MODE_PRIVATE)
        blocks.edit().clear().commit()
        secure = SecureIdentityStateManager(prefs, true)
        store = WeshContactStore(secure, blocks)
    }

    private fun field(out: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        out.write(bytes.size)
        out.write(bytes)
    }

    @Test fun `npub is the signed fifth canonical field and the last URL parameter`() {
        val signer = Ed25519PrivateKeyParameters(seed, 0)
        val signKey = signer.generatePublicKey().encoded.joinToString("") { "%02x".format(it) }
        val unsigned = VerificationService.VerificationQR(
            1, noiseA, signKey, npub, "Nora café", 1700000000, "AAECAwQFBgcICQoLDA0ODw", ""
        )
        val expected = ByteArrayOutputStream().also { out ->
            listOf("bitchat-verify-v1", "1", noiseA, signKey, npub, "Nora café", "1700000000", "AAECAwQFBgcICQoLDA0ODw")
                .forEach { field(out, it) }
        }.toByteArray()
        assertArrayEquals(expected, unsigned.canonicalBytes())
        assertEquals(63, npub.length)

        val canonical = unsigned.canonicalBytes()
        val signature = Ed25519Signer().apply { init(true, signer); update(canonical, 0, canonical.size) }.generateSignature()
        val signed = unsigned.copy(sigHex = signature.joinToString("") { "%02x".format(it) })
        val url = signed.toUrlString()
        assertTrue(url.endsWith("&sig=${signed.sigHex}&npub=$npub"))
        val parsed = VerificationService.VerificationQR.fromUrlString(url)!!
        assertEquals(npub, parsed.npub)
        assertArrayEquals(canonical, parsed.canonicalBytes())

        // Dropping the npub changes the signed bytes, so a stripped QR cannot reuse the signature.
        val stripped = VerificationService.VerificationQR.fromUrlString(url.substringBefore("&npub="))!!
        assertNull(stripped.npub)
        val verifier = Ed25519Signer().apply { init(false, Ed25519PublicKeyParameters(signer.generatePublicKey().encoded, 0)) }
        val strippedBytes = stripped.canonicalBytes()
        verifier.update(strippedBytes, 0, strippedBytes.size)
        assertFalse(verifier.verifySignature(signature))
    }

    @Test fun `enrollment accepts only a canonical on-curve npub distinct from mine and other contacts`() {
        assertEquals(npub, WeshNpubPolicy.acceptedNpub(npub, otherNpub, emptyList(), noiseA))
        listOf(
            null, "", npub.uppercase(), npub.dropLast(1) + if (npub.last() == 'q') "p" else "q",
            Bech32.encode("nsec", generatorX.dataFromHexString()!!),
            Bech32.encode("npub", ByteArray(31) { 1 }), offCurveNpub,
            Bech32.encode("npub", ByteArray(32) { -1 })
        ).forEach { assertNull("Rejected: $it", WeshNpubPolicy.acceptedNpub(it, otherNpub, emptyList(), noiseA)) }
        assertNull(WeshNpubPolicy.acceptedNpub(npub, npub, emptyList(), noiseA))
        assertNull(WeshNpubPolicy.acceptedNpub(npub, null, emptyList(), noiseA))
        val others = listOf(WeshContactStore.Contact(noiseB, "Synthetic B", npub))
        assertNull(WeshNpubPolicy.acceptedNpub(npub, otherNpub, others, noiseA))
        // Re-enrolling the same Noise key with its own npub is not a collision.
        assertEquals(npub, WeshNpubPolicy.acceptedNpub(npub, otherNpub, others, noiseB))
    }

    @Test fun `npub is stored bound to the Noise key and survives reload`() {
        store.record(noiseA.dataFromHexString()!!, "Synthetic A", npub)
        store.record(noiseB.dataFromHexString()!!, "Synthetic B")
        val reloaded = WeshContactStore(secure, RuntimeEnvironment.getApplication().getSharedPreferences("synthetic-npub-blocks", Context.MODE_PRIVATE))
        val a = reloaded.contacts.value.single { it.noiseHex == noiseA }
        val b = reloaded.contacts.value.single { it.noiseHex == noiseB }
        assertEquals(npub, a.npub)
        assertTrue(a.internetReachable)
        assertNull(b.npub)
        assertFalse(b.internetReachable)
        assertEquals(noiseA, reloaded.forNpub(npub)?.noiseHex)
        assertNull(reloaded.forNpub(otherNpub))

        // A newer signed QR without an npub replaces the binding: the contact becomes Bluetooth-only.
        reloaded.record(noiseA.dataFromHexString()!!, "Synthetic A")
        assertNull(reloaded.forNpub(npub))
    }

    @Test fun `legacy and tampered rows load as rescan required without losing the contact`() {
        val rows = JSONArray()
            .put(JSONObject().put("noise", noiseA).put("nickname", "Legacy"))
            .put(JSONObject().put("noise", noiseB).put("nickname", "Tampered").put("npub", offCurveNpub))
        secure.storeSecureValue("wesh_qr_contacts_v1", rows.toString())
        val loaded = WeshContactStore(secure, RuntimeEnvironment.getApplication().getSharedPreferences("synthetic-npub-blocks", Context.MODE_PRIVATE))
        assertEquals(listOf(noiseA, noiseB), loaded.contacts.value.map { it.noiseHex })
        assertTrue(loaded.contacts.value.none { it.internetReachable })
    }
}
