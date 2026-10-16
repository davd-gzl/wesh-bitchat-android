package info.guardianproject.arti

import android.app.Application
import android.util.Log
import org.torproject.arti.ArtiNative
import java.io.File

/** The native calls ArtiProxy makes; tests replace it. */
internal interface ArtiNativeApi {
    fun setLogCallback(callback: ArtiLogListener)
    fun initialize(dataDir: String): Int
    fun startSocksProxy(port: Int): Int
    fun stop(): Int
}

/** Loads the native library only on first use. */
internal object DefaultArtiNative : ArtiNativeApi {
    override fun setLogCallback(callback: ArtiLogListener) = ArtiNative.setLogCallback(callback)
    override fun initialize(dataDir: String) = ArtiNative.initialize(dataDir)
    override fun startSocksProxy(port: Int) = ArtiNative.startSocksProxy(port)
    override fun stop() = ArtiNative.stop()
}

/**
 * Arti Tor proxy implementation compatible with Guardian Project API.
 *
 * Usage:
 * ```
 * val proxy = ArtiProxy.Builder(application)
 *     .setSocksPort(9050)
 *     .setDnsPort(9051)
 *     .setLogListener { logLine -> Log.i("Arti", logLine ?: "") }
 *     .build()
 *
 * proxy.start()
 * // ... use proxy ...
 * proxy.stop()
 * ```
 */
class ArtiProxy internal constructor(
    private val application: Application,
    private val socksPort: Int,
    private val dnsPort: Int,
    private val logListener: ArtiLogListener?,
    private val native: ArtiNativeApi = DefaultArtiNative
) {
    companion object {
        private const val TAG = "ArtiProxy"
    }

    private val lock = Any()

    /** Guarded by [lock]. */
    private var isRunning = false

    /** Guarded by [lock]. Once a start began, every stop reaches the native side (it is idempotent). */
    private var startAttempted = false

    /** Guarded by [lock]. Bumped by every stop, so a start can tell a stop happened while it ran. */
    private var stops = 0L

    /**
     * Start the Arti Tor proxy.
     *
     * This method:
     * 1. Registers log callback
     * 2. Bootstraps a fresh Tor client (blocks; [stop] from another thread cancels it)
     * 3. Starts SOCKS proxy on configured port
     *
     * @throws ArtiException if initialization or startup fails
     */
    fun start() {
        val stopsAtStart = synchronized(lock) {
            if (isRunning) {
                Log.w(TAG, "Arti already running")
                return
            }
            startAttempted = true
            stops
        }

        try {
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

            Log.i(TAG, "Starting SOCKS proxy on port $socksPort (DNS port: $dnsPort)")
            val startResult = native.startSocksProxy(socksPort)
            when (startResult) {
                0 -> {
                    val stoppedMeanwhile = synchronized(lock) {
                        (stops != stopsAtStart).also { if (!it) isRunning = true }
                    }
                    if (stoppedMeanwhile) {
                        // A stop landed between our native calls: whatever started must not run.
                        native.stop()
                        throw ArtiException("Arti stopped during start")
                    }
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
        synchronized(lock) {
            if (!startAttempted) return
            stops++
            isRunning = false
        }

        try {
            Log.i(TAG, "Stopping Arti...")
            val stopResult = native.stop()
            if (stopResult != 0) {
                Log.w(TAG, "Stop returned error code: $stopResult")
            }
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
     * Builder for ArtiProxy instances.
     *
     * Provides fluent API for configuring proxy settings before creation.
     */
    class Builder(private val application: Application) {
        private var socksPort: Int = 9050
        private var dnsPort: Int = 9051
        private var logListener: ArtiLogListener? = null

        /**
         * Set SOCKS proxy port.
         * @param port Port number (default: 9050)
         * @return this Builder for chaining
         */
        fun setSocksPort(port: Int) = apply {
            this.socksPort = port
        }

        /**
         * Set DNS port.
         * @param port Port number (default: 9051)
         * @return this Builder for chaining
         */
        fun setDnsPort(port: Int) = apply {
            this.dnsPort = port
        }

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
            return ArtiProxy(application, socksPort, dnsPort, logListener)
        }
    }
}
