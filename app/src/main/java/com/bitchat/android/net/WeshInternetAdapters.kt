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
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

/** Arti behind [WeshInternetMode]. TorPreferenceManager only ever mirrors the Wesh toggle. */
internal class ArtiTorControl(private val app: Application) : WeshTorControl {
    private val manager: ArtiTorManager get() = ArtiTorManager.getInstance()

    override val progress: Flow<Pair<Int, Boolean>> =
        ArtiTorManager.getInstance().statusFlow.map { it.bootstrapPercent to manager.isProxyEnabled() }

    override fun start() {
        TorPreferenceManager.set(app, TorMode.ON)
        manager.init(app)
    }

    override fun stop() {
        TorPreferenceManager.set(app, TorMode.OFF)
    }

    override suspend fun stopAndWipe(): Boolean {
        stop()
        val status = manager.statusFlow.value
        if (status.mode != TorMode.OFF || status.running || status.state != ArtiTorManager.TorState.OFF) {
            manager.applyMode(app, TorMode.OFF)
        }
        OkHttpProvider.reset()
        return withContext(Dispatchers.IO) { artiDirectory(app).let { it.deleteRecursively() && !it.exists() } }
    }

    companion object {
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
