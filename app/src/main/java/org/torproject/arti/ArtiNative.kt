package org.torproject.arti

import info.guardianproject.arti.ArtiLogListener

/**
 * JNI wrapper for custom-built Arti (Tor implementation in Rust)
 *
 * This class provides native bindings to libarti_android.so compiled from
 * the latest Arti source with rustls (no OpenSSL dependency).
 *
 * Features:
 * - Latest Arti v1.7.0 code
 * - 16KB page size support (Google Play Nov 2025 ready)
 * - Onion service client support
 * - Pure Rust TLS (rustls)
 */
object ArtiNative {

    init {
        System.loadLibrary("arti_android")
    }

    /**
     * Get Arti version string
     * @return Version string from native library
     */
    external fun getVersion(): String

    /**
     * Set log callback for Arti logs
     * @param callback Callback object with onLogLine(String?) method
     */
    external fun setLogCallback(callback: ArtiLogListener)

    /**
     * Builds a fresh runtime and bootstraps a fresh Tor client; any earlier client is released.
     * @param dataDir Directory for Arti state/cache
     * @return 0 on success, -4 when [stop] cancelled the bootstrap, another error code otherwise
     */
    external fun initialize(dataDir: String): Int

    /**
     * Start SOCKS proxy on specified port
     * @param port Port number for SOCKS proxy (e.g., 9050)
     * @return 0 on success, error code otherwise
     */
    external fun startSocksProxy(port: Int): Int

    /**
     * Cancels a bootstrap in progress, drops the Tor client and shuts its runtime down. When it
     * returns no Tor traffic remains and the data directory can be deleted.
     * @return 0 on success, error code otherwise
     */
    external fun stop(): Int
}