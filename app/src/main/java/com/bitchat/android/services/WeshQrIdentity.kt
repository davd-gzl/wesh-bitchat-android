package com.bitchat.android.services

import android.content.Context
import com.bitchat.android.nostr.NostrCrypto
import com.bitchat.android.nostr.NostrIdentity
import com.bitchat.android.nostr.NostrIdentityBridge
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The Nostr identity my Wesh QR reveals. My QR always carries my npub, Internet on or off, except
 * while an erase is unconfirmed. The key is made locally once onboarding is done ([ensureKey]) and
 * is not used on the network until Internet mode is on; showing a QR never creates it.
 */
internal object WeshQrIdentity {
    /**
     * True only while Internet mode is on and no panic is fenced: the process network gate, which
     * stays closed until the user opts in. Internet routes ([mine]) use my key only while it is open.
     */
    @Volatile var internetActive: () -> Boolean = ::networkGateOpen

    /** True while an erase is unconfirmed: my QR then shows no npub and scanned npubs are not bound. */
    @Volatile var eraseUnconfirmed: () -> Boolean = { com.bitchat.android.startup.WeshRuntime.draftWipeRequired.value }

    @Volatile internal var keyStore: (Context) -> com.bitchat.android.identity.SecureIdentityStateManager =
        { com.bitchat.android.identity.SecureIdentityStateManager(it) }

    /** Key store and registry reads never run on the caller's (often Main) thread. */
    @Volatile internal var io: CoroutineDispatcher = Dispatchers.IO

    private fun networkGateOpen(): Boolean = com.bitchat.android.net.WeshNetworkPolicy.allowsNetwork()

    internal fun resetForTesting() {
        internetActive = ::networkGateOpen
        eraseUnconfirmed = { com.bitchat.android.startup.WeshRuntime.draftWipeRequired.value }
        keyStore = { com.bitchat.android.identity.SecureIdentityStateManager(it) }
        io = Dispatchers.IO
    }

    fun isActive(): Boolean = runCatching { internetActive() }.getOrDefault(false)

    fun mine(context: Context): NostrIdentity? = if (isActive()) stored(context) else null

    /** Whether my QR may show my npub now. An unreadable gate counts as an unconfirmed erase. */
    fun showsNpub(): Boolean = runCatching { !eraseUnconfirmed() }.getOrDefault(false)

    private fun stored(context: Context): NostrIdentity? =
        runCatching { NostrIdentityBridge.peekCurrentNostrIdentity(keyStore(context)) }.getOrNull()

    /**
     * Every npub I own, for the enrollment self-check: my current identity, or empty before the
     * key exists. Null while an erase is unconfirmed or when the key store cannot be read, so the
     * check fails closed.
     */
    suspend fun ownedNpubs(context: Context): Set<String>? = withContext(io) {
        if (!showsNpub()) return@withContext null
        runCatching { NostrIdentityBridge.ownedNpubs(keyStore(context)) }.getOrNull()
    }

    /**
     * Makes my Nostr key locally once onboarding is done; nothing is sent. Nothing while an erase is
     * unconfirmed. Returns whether a key exists afterwards.
     */
    suspend fun ensureKey(context: Context): Boolean = withContext(io) {
        // One creation at a time: two callers must not each make a key, the second overwriting the first.
        keyCreation.withLock {
            showsNpub() && runCatching { NostrIdentityBridge.getCurrentNostrIdentity(keyStore(context)) }.getOrNull() != null
        }
    }

    private val keyCreation = Mutex()

    /** My signed QR, with my proven npub whenever the key exists and no erase is unconfirmed. */
    suspend fun myQR(context: Context, nickname: String): String? = withContext(io) {
        val identity = (if (showsNpub()) stored(context) else null)
            ?: return@withContext VerificationService.buildMyQRString(nickname, null)
        VerificationService.buildMyQRString(nickname, identity.npub) { message ->
            runCatching { NostrCrypto.schnorrSign(message, identity.privateKeyHex) }.getOrNull()
        } ?: VerificationService.buildMyQRString(nickname, null)
    }
}
