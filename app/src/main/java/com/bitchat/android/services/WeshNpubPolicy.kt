package com.bitchat.android.services

import com.bitchat.android.nostr.Bech32
import com.bitchat.android.nostr.NostrCrypto
import com.bitchat.android.util.hexEncodedString

/**
 * Enrollment checks for the npub carried by a signed QR. The parser stays permissive for
 * BitChat interop; a candidate that fails here leaves the contact Bluetooth-only.
 */
object WeshNpubPolicy {
    /** [proven]: the QR carried a valid BIP-340 proof by the npub key (see NPUB-PROOF.md). */
    data class Claim(val npub: String, val proven: Boolean)

    /** Canonical lowercase bech32 `npub` encoding a valid secp256k1 x-only key. */
    fun isValid(npub: String?): Boolean = runCatching {
        if (npub == null || npub.length != 63) return false
        val (hrp, data) = Bech32.decode(npub)
        hrp == "npub" && data.size == 32 && Bech32.encode(hrp, data) == npub &&
            NostrCrypto.isValidPublicKey(data.hexEncodedString())
    }.getOrDefault(false)

    /**
     * The claim [noiseHex] may hold, or null. [proof]: null when the QR had no proof (official
     * BitChat), else whether it verified. A failed proof, my own npub, or an unknown own npub
     * (Internet mode off) gives no claim. Another contact's proven claim blocks both kinds; an
     * unproven claim never blocks a proven one, and unproven claims may coexist.
     */
    fun accept(
        candidate: String?,
        proof: Boolean?,
        ownNpub: String?,
        contacts: List<WeshContactStore.Contact>,
        noiseHex: String
    ): Claim? {
        if (!isValid(candidate) || !isValid(ownNpub) || candidate == ownNpub || proof == false) return null
        val provenElsewhere = contacts.any {
            !it.noiseHex.equals(noiseHex, ignoreCase = true) && it.npub == candidate && it.npubProven
        }
        return if (provenElsewhere) null else Claim(candidate!!, proof == true)
    }
}
