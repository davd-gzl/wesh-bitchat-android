package com.bitchat.android

import android.app.Application
import com.bitchat.android.nostr.RelayDirectory
import com.bitchat.android.ui.theme.ThemePreferenceManager
import com.bitchat.android.net.ArtiTorManager

/**
 * Main application class for bitchat Android
 */
class BitchatApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.WESH_POLICY) {
            com.bitchat.android.features.voice.LiveVoicePreferences.forceDisabled = true
            com.bitchat.android.startup.WeshRuntime.start(this)
        } else {
            initializeRuntime()
        }
    }

    internal fun initializeRuntime() {
        // Start the single process-wide power policy before transport components are constructed.
        com.bitchat.android.mesh.PowerManager.getInstance(this).start()

        // Installs the Internet opt-in gate (default off) and keeps Tor's preference OFF while it is off.
        try { com.bitchat.android.net.WeshInternetMode.get(this) } catch (_: Exception) { }

        // Wesh's native validation product has no Internet or location route.
        if (!BuildConfig.WESH_POLICY) {
            try { ArtiTorManager.getInstance().init(this) } catch (_: Exception) { }
            RelayDirectory.initialize(this)
            try { com.bitchat.android.nostr.LocationNotesInitializer.initialize(this) } catch (_: Exception) { }
        }

        // Initialize favorites persistence early so MessageRouter/NostrTransport can use it on startup
        com.bitchat.android.favorites.FavoritesPersistenceService.initialize(this)

        // Restore private conversations before background transports can deliver new messages.
        // AppStateStore merges any in-flight arrivals by message ID, so startup cannot replace
        // newer transport state with an older database snapshot.
        try {
            com.bitchat.android.services.AppStateStore.initializeConversationPersistence(this)
        } catch (_: Exception) { }

        // Warm up Nostr identity to ensure npub is available for favorite notifications
        if (!BuildConfig.WESH_POLICY) try {
            com.bitchat.android.nostr.NostrIdentityBridge.getCurrentNostrIdentity(this)
        } catch (_: Exception) { }

        // Initialize theme preference
        ThemePreferenceManager.init(this)

        // Initialize chat UI mode (matrix transcript vs bubbles)
        com.bitchat.android.ui.theme.ChatUiModeManager.init(this)

        // Initialize debug preference manager (persists debug toggles)
        try { com.bitchat.android.ui.debug.DebugPreferenceManager.init(this) } catch (_: Exception) { }

        // Initialize Wi‑Fi Aware controller with persisted default
        try {
            val enabled = !BuildConfig.WESH_POLICY && com.bitchat.android.ui.debug.DebugPreferenceManager.getWifiAwareEnabled(false)
            com.bitchat.android.wifiaware.WifiAwareController.initialize(this, enabled)
        } catch (_: Exception) { }

        // Initialize Geohash Registries for persistence
        try {
            com.bitchat.android.nostr.GeohashAliasRegistry.initialize(this)
            com.bitchat.android.nostr.GeohashConversationRegistry.initialize(this)
        } catch (_: Exception) { }

        // Own relay connectivity, selected-channel subscriptions, and presence scheduling at the
        // process level so closing the Activity does not disconnect Nostr.
        if (!BuildConfig.WESH_POLICY) try { com.bitchat.android.nostr.NostrBackgroundRuntime.initialize(this) } catch (_: Exception) { }

        // Initialize mesh service preferences
        try { com.bitchat.android.service.MeshServicePreferences.init(this) } catch (_: Exception) { }

        // Proactively start the foreground service to keep mesh alive
        if (!BuildConfig.WESH_POLICY) try { com.bitchat.android.service.MeshForegroundService.start(this) } catch (_: Exception) { }

        // TorManager already initialized above
    }
}
