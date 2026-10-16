package com.bitchat.android.nostr

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Process-owned Nostr connectivity and low-volume background subscriptions.
 *
 * Stable subscriptions dispatch directly to a process-owned event processor. The UI hydrates from
 * the process state store, so relay events remain useful without retaining a cleared ViewModel.
 */
object NostrBackgroundRuntime {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lock = Any()

    @Volatile private var initialized = false
    private lateinit var application: Application
    private lateinit var subscriptions: NostrSubscriptionManager
    private lateinit var eventProcessor: NostrBackgroundEventProcessor

    fun initialize(app: Application) {
        // Product policy, not the Internet toggle: Wesh account DMs need the QR-contact inbound
        // filter before any relay event may reach a conversation. Relay sockets themselves follow WeshNetworkPolicy in NostrRelayManager.
        if (com.bitchat.android.BuildConfig.WESH_POLICY) return
        synchronized(lock) {
            if (initialized) return
            application = app
            eventProcessor = NostrBackgroundEventProcessor(app, scope)
            subscriptions = NostrSubscriptionManager(
                app,
                owner = NostrRelayManager.OWNER_BACKGROUND
            )
            // Publish readiness only after every process-owned dependency is available.
            initialized = true
        }

        subscriptions.connect()
        subscribeAccountDm()
    }

    fun resetSubscriptions() {
        if (!initialized) return
        subscriptions.unsubscribeAllOwned()
        scope.launch {
            // Let CLOSE frames be queued before replacing the deterministic IDs.
            delay(100)
            subscribeAccountDm()
        }
    }

    private fun subscribeAccountDm() {
        val identity = NostrIdentityBridge.getCurrentNostrIdentity(application) ?: return
        subscriptions.subscribeGiftWraps(
            pubkey = identity.publicKeyHex,
            sinceMs = System.currentTimeMillis() - 172_800_000L,
            id = "chat-messages",
            handler = { event ->
                eventProcessor.onAccountDm(event, identity)
            }
        )
    }
}
