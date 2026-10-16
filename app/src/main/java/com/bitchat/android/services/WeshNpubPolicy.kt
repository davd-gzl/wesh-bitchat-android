package com.bitchat.android.services

import com.bitchat.android.nostr.Bech32
import com.bitchat.android.nostr.NostrCrypto
import com.bitchat.android.util.hexEncodedString

/**
 * Enrollment checks for the npub carried by a signed QR. The parser stays permissive for
 * BitChat interop; a candidate that fails here leaves the contact Bluetooth-only.
 */
internal object WeshNpubPolicy {
    /** Canonical lowercase bech32 `npub` encoding a valid secp256k1 x-only key. */
    fun isValid(npub: String?): Boolean = runCatching {
        if (npub == null || npub.length != 63) return false
        val (hrp, data) = Bech32.decode(npub)
        hrp == "npub" && data.size == 32 && Bech32.encode(hrp, data) == npub &&
            NostrCrypto.isValidPublicKey(data.hexEncodedString())
    }.getOrDefault(false)

    /**
     * Returns [candidate] when it may be bound to [noiseHex]. It must differ from my own npub
     * and from any other contact's. An unknown own npub cannot be compared, so it fails closed.
     */
    fun acceptedNpub(
        candidate: String?,
        ownNpub: String?,
        contacts: List<WeshContactStore.Contact>,
        noiseHex: String
    ): String? {
        if (!isValid(candidate) || !isValid(ownNpub) || candidate == ownNpub) return null
        val taken = contacts.any { !it.noiseHex.equals(noiseHex, ignoreCase = true) && it.npub == candidate }
        return if (taken) null else candidate
    }
}
