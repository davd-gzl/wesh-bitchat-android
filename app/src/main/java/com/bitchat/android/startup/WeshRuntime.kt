package com.bitchat.android.startup

import android.content.Context
import com.bitchat.android.BitchatApplication
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.service.MeshServiceHolder
import com.bitchat.android.services.ConversationListPreferences
import com.bitchat.android.services.SeenMessageStore
import com.bitchat.android.services.WeshContactStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext

/** Only encrypted storage/crypto preparation runs on IO; transport construction keeps Main. */
internal object WeshRuntime {
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var coordinator: StartupCoordinator<Unit>? = null

    @Synchronized
    fun get(context: Context): StartupCoordinator<Unit> = coordinator ?: run {
        val app = context.applicationContext as BitchatApplication
        StartupCoordinator(processScope) {
            val encryption = withContext(Dispatchers.IO) {
                app.initializeRuntime()
                SecureIdentityStateManager(app) // Opens and caches the shared encrypted store.
                WeshContactStore.get(app)
                ConversationListPreferences.getInstance(app)
                SeenMessageStore.getInstance(app)
                app.getSharedPreferences("bitchat_prefs", Context.MODE_PRIVATE).all
                EncryptionService(app)
            }
            withContext(Dispatchers.Main.immediate) {
                MeshServiceHolder.getUnifiedOrCreate(app, encryption)
                Unit
            }
        }.also { coordinator = it }
    }

    fun start(context: Context) = get(context).start()
}
