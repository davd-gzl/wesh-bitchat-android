package com.bitchat.android.ui

import com.bitchat.android.services.ContactIdentityResolver

/**
 * A public sender is blocked by long-term identity only when this phone holds a Noise session
 * that proves possession of that key. An announce alone can name any key, so blocking on it
 * could silence someone else; without proof the sender is hidden by peer ID for this session.
 */
internal object WeshPublicBlockPolicy {
    sealed interface Target {
        data class Identity(val fingerprint: String) : Target
        data class Session(val peerID: String) : Target
    }

    fun target(peerID: String, authenticatedStaticKey: ByteArray?): Target =
        authenticatedStaticKey?.takeIf { it.size == 32 }
            ?.let { Target.Identity(ContactIdentityResolver.fingerprintHex(it)) }
            ?: Target.Session(peerID)
}
