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

    @Test fun `npub proof is the last parameter and binds the key holder to these canonical bytes`() {
        val qr = VerificationService.VerificationQR(1, noiseA, "22".repeat(32), npub, "Nora", 1700000000, "AAECAwQFBgcICQoLDA0ODw", "ab".repeat(64))
        // generatorX is the public key of the synthetic secret 1.
        val proof = com.bitchat.android.nostr.NostrCrypto.schnorrSign(VerificationService.npubProofMessage(qr), "00".repeat(31) + "01")
        val proven = qr.copy(npubSigHex = proof)
        assertArrayEquals(qr.canonicalBytes(), proven.canonicalBytes())
        val url = proven.toUrlString()
        assertTrue(url.endsWith("&npub=$npub&npubsig=$proof"))
        val parsed = VerificationService.VerificationQR.fromUrlString(url)!!
        assertTrue(VerificationService.hasValidNpubProof(parsed))
        assertFalse(VerificationService.hasValidNpubProof(qr))
        assertFalse(VerificationService.hasValidNpubProof(parsed.copy(nickname = "Other")))
        assertFalse(VerificationService.hasValidNpubProof(parsed.copy(npub = otherNpub)))
        assertFalse(VerificationService.hasValidNpubProof(parsed.copy(npubSigHex = proof.uppercase())))
        // A malformed proof still parses, so enrollment can treat it as a failed proof.
        assertEquals("zz", VerificationService.VerificationQR.fromUrlString(url.replace(proof, "zz"))!!.npubSigHex)
        // npubsig without npub is refused by the parser.
        assertNull(VerificationService.VerificationQR.fromUrlString(url.replace("&npub=$npub", "")))
    }

    private fun contact(noise: String, npub: String?, proven: Boolean) = WeshContactStore.Contact(noise, "Synthetic", npub, proven)
    private val mine = setOf(otherNpub)
    private fun enroll(candidate: String?, proof: Boolean?, contacts: List<WeshContactStore.Contact> = emptyList(),
                       noise: String = noiseA, owned: Set<String>? = mine) = WeshNpubPolicy.enroll(candidate, proof, owned, contacts, noise)

    @Test fun `enrollment accepts only a canonical on-curve npub that is not mine`() {
        assertEquals(WeshNpubPolicy.Enrollment(npub, false, false), enroll(npub, null))
        assertEquals(WeshNpubPolicy.Enrollment(npub, true, false), enroll(npub, true))
        listOf(
            "", npub.uppercase(), npub.dropLast(1) + if (npub.last() == 'q') "p" else "q",
            Bech32.encode("nsec", generatorX.dataFromHexString()!!),
            Bech32.encode("npub", ByteArray(31) { 1 }), offCurveNpub,
            Bech32.encode("npub", ByteArray(32) { -1 })
        ).forEach { assertNull("Rejected: $it", enroll(it, null).npub) }
        assertEquals(WeshNpubPolicy.Enrollment(null, false, true), enroll(otherNpub, null))
        // My owned set unknown (Internet off, no key): nothing is bound.
        assertEquals(WeshNpubPolicy.Enrollment(null, false, false), enroll(npub, true, owned = null))
        // A proof that is present must verify; a failed one is flagged.
        assertEquals(WeshNpubPolicy.Enrollment(null, false, true), enroll(npub, false))
    }

    @Test fun `only a verified proof unbinds an incumbent and never a proven one`() {
        val unprovenB = listOf(contact(noiseB, npub, proven = false))
        val provenB = listOf(contact(noiseB, npub, proven = true))
        // Unproven conflicting claim: refused, incumbent kept, newcomer flagged.
        assertEquals(WeshNpubPolicy.Enrollment(null, false, true), enroll(npub, null, unprovenB))
        assertEquals(WeshNpubPolicy.Enrollment(null, false, true), enroll(npub, null, provenB))
        // Verified proof evicts an unproven incumbent, but not a proven one.
        assertEquals(WeshNpubPolicy.Enrollment(npub, true, true, setOf(noiseB)), enroll(npub, true, unprovenB))
        assertEquals(WeshNpubPolicy.Enrollment(null, false, true), enroll(npub, true, provenB))
        // Re-enrolling the holder itself is not a conflict; no proof keeps its proven status.
        assertEquals(WeshNpubPolicy.Enrollment(npub, true, false), enroll(npub, true, provenB, noiseB))
        assertEquals(WeshNpubPolicy.Enrollment(npub, true, false), enroll(npub, null, provenB, noiseB))
        // A proven contact showing a weaker QR keeps its binding, flagged: no silent downgrade.
        listOf(enroll(null, null, provenB, noiseB), enroll(npub, false, provenB, noiseB), enroll(Bech32.encode("npub", ByteArray(31) + byteArrayOf(3)), null, provenB, noiseB))
            .forEach { assertEquals(WeshNpubPolicy.Enrollment(npub, true, true), it) }
    }

    @Test fun `store applies evictions and flags`() {
        val c = "33".repeat(32)
        store.record(noiseB.dataFromHexString()!!, "B", enroll(npub, null, store.contacts.value, noiseB))
        store.record(noiseA.dataFromHexString()!!, "A", enroll(npub, null, store.contacts.value, noiseA))
        assertEquals(noiseB, store.forNpub(npub)?.noiseHex)
        assertTrue(store.contacts.value.single { it.noiseHex == noiseA }.npubWarning)
        store.record(c.dataFromHexString()!!, "C", enroll(npub, true, store.contacts.value, c))
        assertEquals(c, store.forNpub(npub)?.noiseHex)
        val b = store.contacts.value.single { it.noiseHex == noiseB }
        assertNull(b.npub)
        assertTrue(b.npubWarning)
        assertTrue(store.contacts.value.single { it.noiseHex == c }.npubProven)
    }

    @Test fun `npub is stored bound to the Noise key and survives reload`() {
        store.record(noiseA.dataFromHexString()!!, "Synthetic A", WeshNpubPolicy.Enrollment(npub, true, false))
        store.record(noiseB.dataFromHexString()!!, "Synthetic B")
        val reloaded = WeshContactStore(secure, RuntimeEnvironment.getApplication().getSharedPreferences("synthetic-npub-blocks", Context.MODE_PRIVATE))
        val a = reloaded.contacts.value.single { it.noiseHex == noiseA }
        val b = reloaded.contacts.value.single { it.noiseHex == noiseB }
        assertEquals(npub, a.npub)
        assertTrue(a.npubProven)
        assertTrue(a.internetReachable)
        assertNull(b.npub)
        assertFalse(b.npubProven)
        assertFalse(b.internetReachable)
        assertEquals(noiseA, reloaded.forNpub(npub)?.noiseHex)
        assertNull(reloaded.forNpub(otherNpub))

        assertFalse(a.npubWarning)
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
