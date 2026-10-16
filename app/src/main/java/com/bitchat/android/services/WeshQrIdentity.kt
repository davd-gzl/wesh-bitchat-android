package com.bitchat.android.services

import android.content.Context
import com.bitchat.android.nostr.NostrCrypto
import com.bitchat.android.nostr.NostrIdentity
import com.bitchat.android.nostr.NostrIdentityBridge

/**
 * The Nostr identity my Wesh QR may reveal. Nothing here creates key material: the Internet
 * mode creates the key when the user opts in, and panic erases it.
 */
internal object WeshQrIdentity {
    /**
     * True only while Internet mode is on and no panic is fenced. Closed by default; the Internet
     * mode wires it. While closed, my QR carries no npub and scanned npubs are not bound.
     */
    @Volatile var internetActive: () -> Boolean = { false }

    fun isActive(): Boolean = runCatching { internetActive() }.getOrDefault(false)

    fun mine(context: Context): NostrIdentity? =
        if (isActive()) NostrIdentityBridge.peekCurrentNostrIdentity(context) else null

    /**
     * Every npub I own, for the enrollment self-check: my current identity plus every cached,
     * derivable or persisted geohash identity (bookmarked or with a stored conversation). Null
     * while Internet mode is off or fenced, without a current key, or when any source cannot be
     * read, so the check fails closed.
     */
    fun ownedNpubs(context: Context): Set<String>? {
        val current = mine(context)?.npub ?: return null
        val geohashes = runCatching {
            com.bitchat.android.nostr.GeohashConversationRegistry.initialize(context.applicationContext)
            com.bitchat.android.geohash.GeohashBookmarksStore.getInstance(context).bookmarks.value +
                com.bitchat.android.nostr.GeohashConversationRegistry.snapshot().values
        }.getOrNull() ?: return null
        return NostrIdentityBridge.ownedNpubs(context, geohashes.toSet())?.takeIf { current in it }
    }

    /** My signed QR, with a proven npub only while Internet mode is active. */
    fun myQR(context: Context, nickname: String): String? {
        val identity = mine(context)
            ?: return VerificationService.buildMyQRString(nickname, null)
        return VerificationService.buildMyQRString(nickname, identity.npub) { message ->
            runCatching { NostrCrypto.schnorrSign(message, identity.privateKeyHex) }.getOrNull()
        } ?: VerificationService.buildMyQRString(nickname, null)
    }
}
