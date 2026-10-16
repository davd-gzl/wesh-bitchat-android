package info.guardianproject.arti

import android.app.Application
import android.util.Log
import com.bitchat.android.net.SocksCredentials
import org.torproject.arti.ArtiNative
import java.io.File

/** The native calls ArtiProxy makes; tests replace it. */
internal interface ArtiNativeApi {
    fun setLogCallback(callback: ArtiLogListener)
    fun initialize(dataDir: String): Int
    fun startSocksProxyWithAuth(port: Int, username: String, password: String): Int
    fun stop(): Int
}

/** Loads the native library only on first use. */
internal object DefaultArtiNative : ArtiNativeApi {
    override fun setLogCallback(callback: ArtiLogListener) = ArtiNative.setLogCallback(callback)
    override fun initialize(dataDir: String) = ArtiNative.initialize(dataDir)
    override fun startSocksProxyWithAuth(port: Int, username: String, password: String) =
        ArtiNative.startSocksProxyWithAuth(port, username, password)
    override fun stop() = ArtiNative.stop()
}

/**
 * Arti Tor proxy implementation compatible with Guardian Project API.
 *
 * Usage:
 * ```
 * val proxy = ArtiProxy.Builder(application)
 *     .setLogListener { logLine -> Log.i("Arti", logLine ?: "") }
 *     .build()
 *
 * proxy.start(socksPort, credentials)
 * // ... use proxy ...
 * proxy.stop()
 * ```
 */
class ArtiProxy internal constructor(
    private val application: Application,
    private val logListener: ArtiLogListener?,
    private val native: ArtiNativeApi = DefaultArtiNative
) {
    companion object {
        private const val TAG = "ArtiProxy"
    }

    @Volatile
    private var isRunning = false

    /** Set from the first native call of a start until a stop: the native side may hold a client. */
    @Volatile
    private var nativeTouched = false

    /**
     * Start the Arti Tor proxy.
     *
     * This method:
     * 1. Registers log callback
     * 2. Bootstraps a fresh Tor client (blocks; [stop] from another thread cancels it)
     * 3. Starts the authenticated SOCKS proxy on [socksPort]
     *
     * @throws ArtiException if initialization or startup fails
     */
    fun start(socksPort: Int, credentials: SocksCredentials) {
        if (isRunning) {
            Log.w(TAG, "Arti already running")
            return
        }

        try {
            nativeTouched = true
            logListener?.let { listener ->
                Log.d(TAG, "Registering log callback")
                native.setLogCallback(listener)
            }

            val dataDir = getDataDirectory()
            Log.i(TAG, "Initializing Arti")

            when (val initResult = native.initialize(dataDir.absolutePath)) {
                0 -> Unit
                -4 -> throw ArtiException("Arti stopped during bootstrap")
                else -> throw ArtiException("Failed to initialize Arti: error code $initResult")
            }

            Log.i(TAG, "Starting SOCKS proxy on port $socksPort")
            val startResult = native.startSocksProxyWithAuth(socksPort, credentials.username, credentials.password)
            when (startResult) {
                0 -> {
                    isRunning = true
                    Log.i(TAG, "Arti started successfully")
                }
                -1 -> throw ArtiException("Arti client not initialized")
                -2 -> throw ArtiException("Tokio runtime not initialized")
                -3 -> throw ArtiException("Failed to bind SOCKS proxy to port $socksPort (port already in use)")
                else -> throw ArtiException("Failed to start SOCKS proxy: error code $startResult")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Arti", e)
            if (e is ArtiException) {
                throw e
            } else {
                throw ArtiException("Failed to start Arti: ${e.message}", e)
            }
        }
    }

    /**
     * Stop the Arti Tor proxy: drops the Tor client and its runtime, and cancels a start still
     * bootstrapping. Also runs after a failed start, which can leave a client behind.
     */
    fun stop() {
        if (!nativeTouched) return

        try {
            Log.i(TAG, "Stopping Arti...")
            val stopResult = native.stop()
            if (stopResult != 0) {
                Log.w(TAG, "Stop returned error code: $stopResult")
            }

            isRunning = false
            nativeTouched = false
            Log.i(TAG, "Arti stopped successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Arti", e)
        }
    }

    /**
     * Get or create Arti data directory.
     *
     * Directory structure:
     * - {app_data}/arti/cache - Tor directory cache
     * - {app_data}/arti/state - Tor persistent state
     */
    private fun getDataDirectory(): File {
        val artiDir = File(application.filesDir, "arti")
        if (!artiDir.exists()) {
            artiDir.mkdirs()
        }

        File(artiDir, "cache").apply { if (!exists()) mkdirs() }
        File(artiDir, "state").apply { if (!exists()) mkdirs() }

        return artiDir
    }

    /**
     * Builder for ArtiProxy instances. The SOCKS port and credentials are chosen per start.
     */
    class Builder(private val application: Application) {
        private var logListener: ArtiLogListener? = null

        /**
         * Set log listener for Arti log messages.
         * @param listener Callback for log lines
         * @return this Builder for chaining
         */
        fun setLogListener(listener: ArtiLogListener) = apply {
            this.logListener = listener
        }

        /**
         * Build and return the configured ArtiProxy instance.
         * @return Configured ArtiProxy (not yet started)
         */
        fun build(): ArtiProxy {
            return ArtiProxy(application, logListener)
        }
    }
}
