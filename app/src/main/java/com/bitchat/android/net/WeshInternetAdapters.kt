package com.bitchat.android.net

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.bitchat.android.nostr.NostrIdentityBridge
import com.bitchat.android.nostr.NostrRelayManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** Arti behind [WeshInternetMode]. TorPreferenceManager only ever mirrors the Wesh toggle. */
internal class ArtiTorControl(
    private val app: Application,
    private val status: StateFlow<ArtiTorManager.TorStatus> = ArtiTorManager.getInstance().statusFlow,
    private val applyOff: suspend () -> Unit = { ArtiTorManager.getInstance().applyMode(app, TorMode.OFF) },
    private val stopTimeoutMs: Long = STOP_TIMEOUT_MS
) : WeshTorControl {
    override val progress: Flow<Pair<Int, Boolean>> = status.map { it.bootstrapPercent to it.isReady() }

    override fun start() {
        TorPreferenceManager.set(app, TorMode.ON)
        ArtiTorManager.getInstance().init(app)
    }

    override fun stop() {
        TorPreferenceManager.set(app, TorMode.OFF)
    }

    /** Always stops Arti and waits for it; a timeout keeps the state on disk and reports false. */
    override suspend fun stopAndWipe(): Boolean {
        stop()
        val stopped = withTimeoutOrNull(stopTimeoutMs) {
            applyOff()
            status.first { it.state == ArtiTorManager.TorState.OFF && !it.running }
        } != null
        OkHttpProvider.reset()
        if (!stopped) return false
        return withContext(Dispatchers.IO) {
            val dir = artiDirectory(app)
            dir.deleteRecursively() && !dir.exists()
        }
    }

    private fun ArtiTorManager.TorStatus.isReady(): Boolean =
        mode == TorMode.ON && running && bootstrapPercent >= 100 && state == ArtiTorManager.TorState.RUNNING

    companion object {
        /** Above ArtiTorManager's own 7 s stop wait. */
        const val STOP_TIMEOUT_MS = 15_000L

        /** ArtiProxy keeps its `cache` and `state` directories here. */
        fun artiDirectory(context: Context): File = File(context.filesDir, "arti")
    }
}

internal class NostrRelayControl(private val context: Context) : WeshRelayControl {
    private val manager: NostrRelayManager get() = NostrRelayManager.getInstance(context)
    override val connectedCount: Flow<Int> = NostrRelayManager.shared.relays.map { relays -> relays.count { it.isConnected } }
    override fun connect() = manager.connect()
    override fun disconnect() = manager.disconnect()
    override fun clearOnPanic() = manager.clearAllOnPanic()
}

/** Emits on every default-network callback: true when a network with Internet capability is up. */
internal fun defaultNetworkFlow(context: Context): Flow<Boolean> = callbackFlow {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    if (connectivity == null) {
        trySend(false)
        awaitClose { }
        return@callbackFlow
    }
    fun hasInternet(): Boolean = runCatching {
        connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(false)
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { trySend(true) }
        override fun onLost(network: Network) { trySend(hasInternet()) }
    }
    trySend(hasInternet())
    connectivity.registerDefaultNetworkCallback(callback)
    awaitClose { runCatching { connectivity.unregisterNetworkCallback(callback) } }
}

internal fun wipeNostrKeys(context: Context): Boolean = NostrIdentityBridge.panicClearKeys(context)
