package com.bitchat.android.services

import com.bitchat.android.nostr.Bech32
import org.bouncycastle.crypto.ec.CustomNamedCurves

/**
 * Enrollment checks for the npub carried by a signed QR. The parser stays permissive for
 * BitChat interop; a candidate that fails here leaves the contact Bluetooth-only.
 */
object WeshNpubPolicy {
    /**
     * What one successful QR enrollment stores. [warning]: flag the contact for user review
     * (refused conflicting claim, failed proof, or a proven binding kept against a weaker QR).
     * [evict]: Noise keys whose unproven claim to [npub] this proven claim removes.
     * [kept]: the contact's previous binding is kept, so its earlier warning stays too.
     */
    data class Enrollment(
        val npub: String?,
        val proven: Boolean,
        val warning: Boolean,
        val evict: Set<String> = emptySet(),
        val kept: Boolean = false
    )

    /** Canonical lowercase bech32 `npub` encoding a valid secp256k1 x-only key. */
    fun isValid(npub: String?): Boolean = runCatching {
        if (npub == null || npub.length != 63) return false
        val (hrp, data) = Bech32.decode(npub)
        hrp == "npub" && data.size == 32 && Bech32.encode(hrp, data) == npub && isXOnlyPoint(data)
    }.getOrDefault(false)

    /** BIP-340 lift_x: x below the field prime and x^3 + 7 a square. */
    private fun isXOnlyPoint(x: ByteArray): Boolean = runCatching {
        CustomNamedCurves.getByName("secp256k1").curve.decodePoint(byteArrayOf(0x02) + x).isValid
    }.getOrDefault(false)

    /**
     * Rules in NPUB-PROOF.md. [proof]: null when the QR had no `npubsig` (official BitChat),
     * else whether it verified. [ownedNpubs]: every npub I own, or null when that set cannot be
     * computed (Internet mode off, no key, storage error), which fails closed. An unproven claim
     * never unbinds an incumbent; only a verified proof may, and never a proven incumbent.
     */
    fun enroll(
        candidate: String?,
        proof: Boolean?,
        ownedNpubs: Set<String>?,
        contacts: List<WeshContactStore.Contact>,
        noiseHex: String
    ): Enrollment {
        val self = contacts.firstOrNull { it.noiseHex.equals(noiseHex, ignoreCase = true) }
        val selfProven = self?.npubProven == true
        fun keep(warning: Boolean) = Enrollment(self?.npub, selfProven, warning, kept = true)
        // My own npubs are unknown: no new binding, and an existing one is left as is.
        if (ownedNpubs == null || !ownedNpubs.all(::isValid)) return keep(warning = false)
        if (candidate == null) return if (selfProven) keep(warning = true) else Enrollment(null, false, false)
        if (!isValid(candidate) || candidate in ownedNpubs || proof == false) {
            val suspicious = proof == false || candidate in ownedNpubs
            return if (selfProven) keep(warning = true) else Enrollment(null, false, suspicious)
        }
        val holders = contacts.filter { !it.noiseHex.equals(noiseHex, ignoreCase = true) && it.npub == candidate }
        if (proof == true) {
            if (holders.any { it.npubProven }) return keep(warning = true)
            return Enrollment(candidate, true, holders.isNotEmpty(), holders.map { it.noiseHex }.toSet())
        }
        // Unproven from here on: no silent downgrade, never unbind an incumbent.
        if (selfProven) return keep(warning = self?.npub != candidate)
        if (holders.isNotEmpty()) return keep(warning = true)
        return Enrollment(candidate, false, false)
    }
}
