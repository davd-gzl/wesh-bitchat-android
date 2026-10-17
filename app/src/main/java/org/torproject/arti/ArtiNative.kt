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

    /** The stop generation: read before a start, it lets any later [stop] cancel that start. */
    external fun generation(): Long

    /**
     * Builds a fresh runtime and bootstraps a fresh Tor client; any earlier client is released.
     * @param dataDir Directory for Arti state/cache
     * @param generation [generation] read when the start began
     * @return 0 on success, -4 when a [stop] came after [generation] was read or cancelled the
     *   bootstrap, another error code otherwise
     */
    external fun initialize(dataDir: String, generation: Long): Int

    /**
     * Start the SOCKS proxy on 127.0.0.1:[port]. Only RFC 1929 username/password with exactly
     * these credentials is accepted.
     * @return 0 on success, -3 when the port is taken, another error code otherwise
     */
    external fun startSocksProxyWithAuth(port: Int, username: String, password: String): Int

    /**
     * Cancels a bootstrap in progress, drops the Tor client and shuts its runtime down. When it
     * returns no Tor traffic remains and the data directory can be deleted.
     * @return 0 on success; -5 when a Tor runtime did not stop in time, now or earlier in this
     *   process, so Tor work may still run
     */
    external fun stop(): Int
}
