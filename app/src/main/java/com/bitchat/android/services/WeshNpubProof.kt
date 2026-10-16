package com.bitchat.android.services

import com.bitchat.android.nostr.Bech32
import com.bitchat.android.nostr.NostrCrypto
import com.bitchat.android.util.hexEncodedString

/** Check of the optional `npubsig` (NPUB-PROOF.md). */
internal object WeshNpubProof {
    /** True only for a well-formed BIP-340 proof made by the key the npub encodes. */
    fun isValid(qr: VerificationService.VerificationQR): Boolean = runCatching {
        val npub = qr.npub ?: return false
        val sig = qr.npubSigHex ?: return false
        if (!sig.matches(Regex("[0-9a-f]{128}")) || !WeshNpubPolicy.isValid(npub)) return false
        val key = Bech32.decode(npub).second.hexEncodedString()
        NostrCrypto.schnorrVerify(VerificationService.npubProofMessage(qr), sig, key)
    }.getOrDefault(false)
}
