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
    private val stopTimeoutMs: Long = STOP_TIMEOUT_MS,
    private val deleteArti: (File) -> Boolean = ::deleteDirectory
) : WeshTorControl {
    override val progress: Flow<Pair<Int, Boolean>> = status.map { it.bootstrapPercent to it.isReady() }

    override fun start() {
        // A wipe left over from a failed panic runs before Arti may touch its directory again.
        if (!completePendingWipe(app, deleteArti)) return
        TorPreferenceManager.set(app, TorMode.ON)
        ArtiTorManager.getInstance().init(app)
    }

    override fun stop() {
        TorPreferenceManager.set(app, TorMode.OFF)
    }

    /**
     * Always stops Arti, waits for it (hard timeout), and always deletes its directory. True only
     * when both are confirmed; otherwise a marker makes the next process start delete it again.
     */
    override suspend fun stopAndWipe(): Boolean {
        stop()
        val stopped = withTimeoutOrNull(stopTimeoutMs) {
            applyOff()
            status.first { it.state == ArtiTorManager.TorState.OFF && !it.running }
        } != null
        OkHttpProvider.reset()
        return withContext(Dispatchers.IO) {
            val marker = pendingMarker(app)
            runCatching { marker.exists() || marker.createNewFile() }
            val deleted = runCatching { deleteArti(artiDirectory(app)) }.getOrDefault(false)
            val done = stopped && deleted
            if (done) runCatching { marker.delete() }
            done && !marker.exists()
        }
    }

    private fun ArtiTorManager.TorStatus.isReady(): Boolean =
        mode == TorMode.ON && running && bootstrapPercent >= 100 && state == ArtiTorManager.TorState.RUNNING

    companion object {
        /** Above ArtiTorManager's own 7 s stop wait. */
        const val STOP_TIMEOUT_MS = 15_000L

        /** ArtiProxy keeps its `cache` and `state` directories here. */
        fun artiDirectory(context: Context): File = File(context.filesDir, "arti")

        /** Outside the Arti directory and outside the preference files panic clears. */
        fun pendingMarker(context: Context): File = File(context.filesDir, "arti-wipe-pending")

        private fun deleteDirectory(dir: File): Boolean = dir.deleteRecursively() && !dir.exists()

        /**
         * Runs at process start and before every Arti start: finishes a wipe a failed panic left
         * behind. False while the directory still cannot be deleted, so Arti must not start.
         */
        fun completePendingWipe(context: Context, delete: (File) -> Boolean = ::deleteDirectory): Boolean {
            val marker = pendingMarker(context)
            if (!marker.exists()) return true
            val deleted = runCatching { delete(artiDirectory(context)) }.getOrDefault(false)
            return deleted && marker.delete()
        }
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
