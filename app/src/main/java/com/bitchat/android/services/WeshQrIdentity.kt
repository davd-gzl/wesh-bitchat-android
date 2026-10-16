package com.bitchat.android.services

import android.content.Context
import com.bitchat.android.nostr.NostrCrypto
import com.bitchat.android.nostr.NostrIdentity
import com.bitchat.android.nostr.NostrIdentityBridge
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Nostr identity my Wesh QR may reveal. Nothing here creates key material: the Internet
 * mode creates the key when the user opts in, and panic erases it.
 */
internal object WeshQrIdentity {
    /**
     * True only while Internet mode is on and no panic is fenced: the process network gate, which
     * stays closed until the user opts in. While closed, my QR carries no npub and scanned npubs
     * are not bound.
     */
    @Volatile var internetActive: () -> Boolean = { com.bitchat.android.net.WeshNetworkPolicy.allowsNetwork() }

    /** Key store and registry reads never run on the caller's (often Main) thread. */
    @Volatile internal var io: CoroutineDispatcher = Dispatchers.IO

    fun isActive(): Boolean = runCatching { internetActive() }.getOrDefault(false)

    fun mine(context: Context): NostrIdentity? =
        if (isActive()) NostrIdentityBridge.peekCurrentNostrIdentity(context) else null

    /**
     * Every npub I own, for the enrollment self-check: my current identity plus every cached,
     * derivable or persisted geohash identity (bookmarked or with a stored conversation). Null
     * while Internet mode is off or fenced, without a current key, or when any source cannot be
     * read, so the check fails closed.
     */
    suspend fun ownedNpubs(context: Context): Set<String>? = withContext(io) {
        val current = mine(context)?.npub ?: return@withContext null
        val geohashes = runCatching {
            com.bitchat.android.nostr.GeohashConversationRegistry.initialize(context.applicationContext)
            com.bitchat.android.geohash.GeohashBookmarksStore.getInstance(context).bookmarks.value +
                com.bitchat.android.nostr.GeohashConversationRegistry.snapshot().values
        }.getOrNull() ?: return@withContext null
        NostrIdentityBridge.ownedNpubs(context, geohashes.toSet())?.takeIf { current in it }
    }

    /** My signed QR, with a proven npub only while Internet mode is active. */
    suspend fun myQR(context: Context, nickname: String): String? = withContext(io) {
        val identity = mine(context)
            ?: return@withContext VerificationService.buildMyQRString(nickname, null)
        VerificationService.buildMyQRString(nickname, identity.npub) { message ->
            runCatching { NostrCrypto.schnorrSign(message, identity.privateKeyHex) }.getOrNull()
        } ?: VerificationService.buildMyQRString(nickname, null)
    }
}
