package com.bitchat.android.services

import android.app.Application
import android.content.Context
import android.content.SharedPreferences

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
        assertTrue(WeshNpubProof.isValid(parsed))
        assertFalse(WeshNpubProof.isValid(qr))
        assertFalse(WeshNpubProof.isValid(parsed.copy(nickname = "Other")))
        assertFalse(WeshNpubProof.isValid(parsed.copy(npub = otherNpub)))
        assertFalse(WeshNpubProof.isValid(parsed.copy(npubSigHex = proof.uppercase())))
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
        assertEquals(WeshNpubPolicy.Enrollment(null, false, false, kept = true), enroll(npub, true, owned = null))
        // A proof that is present must verify; a failed one is flagged.
        assertEquals(WeshNpubPolicy.Enrollment(null, false, true), enroll(npub, false))
    }

    @Test fun `only a verified proof unbinds an incumbent and never a proven one`() {
        val unprovenB = listOf(contact(noiseB, npub, proven = false))
        val provenB = listOf(contact(noiseB, npub, proven = true))
        // Unproven conflicting claim: refused, incumbent kept, newcomer flagged.
        assertEquals(WeshNpubPolicy.Enrollment(null, false, true, kept = true), enroll(npub, null, unprovenB))
        assertEquals(WeshNpubPolicy.Enrollment(null, false, true, kept = true), enroll(npub, null, provenB))
        // Verified proof evicts an unproven incumbent, but not a proven one.
        assertEquals(WeshNpubPolicy.Enrollment(npub, true, true, setOf(noiseB)), enroll(npub, true, unprovenB))
        assertEquals(WeshNpubPolicy.Enrollment(null, false, true, kept = true), enroll(npub, true, provenB))
        // Re-enrolling the holder itself is not a conflict; no proof keeps its proven status.
        assertEquals(WeshNpubPolicy.Enrollment(npub, true, false), enroll(npub, true, provenB, noiseB))
        assertEquals(WeshNpubPolicy.Enrollment(npub, true, false, kept = true), enroll(npub, null, provenB, noiseB))
        // A proven contact showing a weaker QR keeps its binding, flagged: no silent downgrade.
        listOf(enroll(null, null, provenB, noiseB), enroll(npub, false, provenB, noiseB), enroll(Bech32.encode("npub", ByteArray(31) + byteArrayOf(3)), null, provenB, noiseB))
            .forEach { assertEquals(WeshNpubPolicy.Enrollment(npub, true, true, kept = true), it) }
    }

    private fun enrollIn(store: WeshContactStore, noise: String, candidate: String?, proof: Boolean?, owned: Set<String>? = mine) =
        store.enroll(noise.dataFromHexString()!!, "Synthetic", candidate, proof, owned)

    @Test fun `store enrollment evaluates and persists against current contacts`() {
        val c = "33".repeat(32)
        enrollIn(store, noiseB, npub, null)
        // The second enrollment sees B's claim inside the same lock and is refused.
        enrollIn(store, noiseA, npub, null)
        assertEquals(noiseB, store.forNpub(npub)?.noiseHex)
        assertTrue(store.contacts.value.single { it.noiseHex == noiseA }.npubWarning)
        enrollIn(store, c, npub, true)
        assertEquals(c, store.forNpub(npub)?.noiseHex)
        val b = store.contacts.value.single { it.noiseHex == noiseB }
        assertNull(b.npub)
        assertTrue(b.npubWarning)
        assertTrue(store.contacts.value.single { it.noiseHex == c }.npubProven)
    }

    @Test fun `holder invariant is re-checked before persisting`() {
        enrollIn(store, noiseB, npub, true)
        // A decision computed elsewhere cannot bind an npub someone else still holds.
        store.record(noiseA.dataFromHexString()!!, "A", WeshNpubPolicy.Enrollment(npub, true, false))
        val a = store.contacts.value.single { it.noiseHex == noiseA }
        assertNull(a.npub)
        assertTrue(a.npubWarning)
        assertEquals(noiseB, store.forNpub(npub)?.noiseHex)
    }

    @Test fun `a warning is sticky, blocks Internet delivery, and only a clean re-scan clears it`() {
        enrollIn(store, noiseB, npub, true)
        enrollIn(store, noiseB, null, null)  // proven contact shows a QR without npub
        var b = store.contacts.value.single()
        assertEquals(npub, b.npub)
        assertTrue(b.npubWarning)
        assertFalse(b.internetReachable)
        assertNull(b.deliveryNpub)
        // Re-scans that keep the binding (my Internet off, same weak QR) do not clear it.
        enrollIn(store, noiseB, npub, true, owned = null)
        enrollIn(store, noiseB, null, null)
        assertTrue(store.contacts.value.single().npubWarning)
        // Another contact's enrollment never clears it either.
        enrollIn(store, noiseA, otherNpub.let { Bech32.encode("npub", ByteArray(31) + byteArrayOf(3)) }, null)
        assertTrue(store.contacts.value.single { it.noiseHex == noiseB }.npubWarning)
        // A clean proven re-scan of that contact clears it.
        enrollIn(store, noiseB, npub, true)
        b = store.contacts.value.single { it.noiseHex == noiseB }
        assertFalse(b.npubWarning)
        assertEquals(npub, b.deliveryNpub)
        // Persisted across reload.
        enrollIn(store, noiseB, null, null)
        val reloaded = WeshContactStore(secure, RuntimeEnvironment.getApplication().getSharedPreferences("synthetic-npub-blocks", Context.MODE_PRIVATE))
        assertTrue(reloaded.contacts.value.single { it.noiseHex == noiseB }.npubWarning)
    }

    @Test fun `owned npubs are exactly my key and reading creates no key material`() {
        assertEquals(emptySet<String>(), com.bitchat.android.nostr.NostrIdentityBridge.ownedNpubs(secure))
        assertFalse("Reading never creates a key", secure.hasSecureValue("nostr_private_key"))

        val identity = com.bitchat.android.nostr.NostrIdentity.fromPrivateKey("00".repeat(31) + "07")
        secure.storeSecureValue("nostr_private_key", identity.privateKeyHex)
        // A seed left by an older build no longer yields extra identities.
        secure.storeSecureValue("nostr_device_seed", java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 9 }))
        val owned = com.bitchat.android.nostr.NostrIdentityBridge.ownedNpubs(secure)!!
        assertEquals(setOf(identity.npub), owned)
        owned.forEach { assertTrue(WeshNpubPolicy.isValid(it)) }

        val broken: SharedPreferences = org.mockito.kotlin.mock()
        org.mockito.kotlin.whenever(broken.getString(org.mockito.kotlin.any(), org.mockito.kotlin.anyOrNull())).thenThrow(SecurityException("synthetic"))
        assertNull(com.bitchat.android.nostr.NostrIdentityBridge.ownedNpubs(SecureIdentityStateManager(broken, true)))
    }

    @Test fun `npub is stored bound to the Noise key and survives reload`() {
        enrollIn(store, noiseA, npub, true)
        enrollIn(store, noiseB, null, null)
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
