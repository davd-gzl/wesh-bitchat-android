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
import com.bitchat.android.ui.EncryptedWeshDraftBackend

/** Only encrypted storage/crypto preparation runs on IO; transport construction keeps Main. */
internal object WeshRuntime {
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gate = WeshRecoveryGate()
    val draftWipeRequired = gate.recoveryRequired
    val transportAllowed: Boolean get() = gate.allowsTransport(prepared = true)
    private val recovery = InterruptedPanicRecovery(processScope)
    val recoveryState = recovery.state
    @Volatile private var coordinator: StartupCoordinator<Unit>? = null

    @Synchronized
    fun get(context: Context): StartupCoordinator<Unit> = coordinator ?: run {
        val app = context.applicationContext as BitchatApplication
        val original = app.requireOriginalPreparation().origin
        StartupCoordinator(processScope) {
            val encryption = withContext(Dispatchers.IO) {
                val pendingWipe = try {
                    EncryptedWeshDraftBackend.hasPendingWipe(app)
                } catch (error: Exception) {
                    gate.requireRecovery()
                    throw error
                }
                if (pendingWipe) {
                    gate.requireRecovery()
                    error("Draft wipe requires explicit recovery")
                }
                app.initializeRuntime(original)
                SecureIdentityStateManager(app) // Opens and caches the shared encrypted store.
                WeshContactStore.get(app)
                ConversationListPreferences.getInstance(app)
                SeenMessageStore.getInstance(app)
                app.getSharedPreferences("bitchat_prefs", Context.MODE_PRIVATE).all
                EncryptionService(app)
            }
            withContext(Dispatchers.Main.immediate) {
                com.bitchat.android.service.dispatchHolderAcquisition(
                    acquire = { MeshServiceHolder.getUnifiedOrCreate(app, encryption) },
                    available = { Unit },
                    refused = {
                        gate.requireRecovery()
                        error("Secure startup unavailable")
                    }
                )
            }
        }.also { coordinator = it }
    }

    fun requirePanicRecovery() { gate.requireRecovery() }
    fun normalPanicCompleted() { gate.normalPanicCompleted() }

    fun clearInterruptedPanic(context: Context) {
        if (!draftWipeRequired.value) return
        requestApplicationDataWipe(context)
    }

    /**
     * After repeated identity load failures the person may reset: the same Android-verified wipe
     * as an interrupted panic, so no partial identity or contact list survives.
     */
    fun resetIdentityAfterFailures(context: Context) {
        val startup = coordinator ?: return
        if (!StartupRecoveryPolicy.offersIdentityReset(startup.failures.value, startup.state.value, draftWipeRequired.value)) return
        requestApplicationDataWipe(context)
    }

    private fun requestApplicationDataWipe(context: Context) {
        recovery.request {
            withContext(Dispatchers.IO) {
                val manager = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                manager.clearApplicationUserData()
            }
        }
    }

    suspend fun awaitTransportReady(context: Context) {
        get(context).awaitReady()
        check(transportAllowed) { "Panic recovery required" }
    }

    fun start(context: Context) = get(context).start()
}
