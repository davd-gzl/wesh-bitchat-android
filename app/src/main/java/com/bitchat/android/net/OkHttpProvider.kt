package com.bitchat.android.net

import okhttp3.Dns
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Internet mode off or no SOCKS address: Wesh never falls back to a direct connection. */
class TorRequiredException : IOException("Internet mode is off or Tor is unavailable; direct connections are disabled")

/**
 * Centralized OkHttp provider. Every client goes through Tor's SOCKS proxy; without a SOCKS
 * address no client is built and callers get [TorRequiredException].
 */
object OkHttpProvider {
    enum class Route {
        DIRECT,
        TOR
    }

    data class RoutedClient(
        val client: OkHttpClient,
        val route: Route
    )

    private val httpClientRef = AtomicReference<RoutedClient?>(null)
    private val wsClientRef = AtomicReference<OkHttpClient?>(null)
    private val clientLock = Any()

    internal var socksAddressProvider: () -> InetSocketAddress? = { ArtiTorManager.getInstance().currentSocksAddress() }
    /** The Wesh Internet opt-in: closed means no client at all, even with a SOCKS port. */
    internal var networkAllowed: () -> Boolean = { WeshNetworkPolicy.allowsNetwork() }

    /** Host names go to Tor unresolved (SOCKS5 ATYP 0x03); a local lookup would leak them. */
    private val noLocalDns = Dns { hostname -> throw UnknownHostException("Local DNS disabled: $hostname") }

    fun reset() {
        synchronized(clientLock) {
            httpClientRef.set(null)
            wsClientRef.set(null)
        }
    }

    fun httpClient(): OkHttpClient = routedHttpClient().client

    /**
     * Returns the client and the route it was actually built with as one snapshot.
     *
     * The selected Tor mode can change while an existing client is still cached. Consumers that
     * key cooldowns by network identity must use this value rather than re-reading the preference.
     */
    fun routedHttpClient(): RoutedClient {
        // Checked on every access: a client cached while Internet was on is not handed out after.
        if (!networkAllowed()) throw TorRequiredException()
        httpClientRef.get()?.let { return it }
        return synchronized(clientLock) {
            httpClientRef.get() ?: run {
                val (builder, route) = baseBuilderForCurrentProxy()
                val client = builder
                    .callTimeout(15, TimeUnit.SECONDS)
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build()
                RoutedClient(client, route).also(httpClientRef::set)
            }
        }
    }

    fun webSocketClient(): OkHttpClient {
        if (!networkAllowed()) throw TorRequiredException()
        wsClientRef.get()?.let { return it }
        return synchronized(clientLock) {
            wsClientRef.get() ?: baseBuilderForCurrentProxy().first
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build()
                .also(wsClientRef::set)
        }
    }

    private fun baseBuilderForCurrentProxy(): Pair<OkHttpClient.Builder, Route> {
        // Tor publishes the SOCKS address as soon as Tor mode is ON, even before bootstrap, so
        // early calls fail on the local port instead of leaking. No address means no client.
        val socks: InetSocketAddress = socksAddressProvider() ?: throw TorRequiredException()
        val builder = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, socks))
            .dns(noLocalDns)
        return builder to Route.TOR
    }
}
