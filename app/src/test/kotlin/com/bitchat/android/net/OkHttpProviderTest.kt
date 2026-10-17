package com.bitchat.android.net

import okhttp3.Request
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class OkHttpProviderTest {
    private val original = OkHttpProvider.socksAddressProvider
    private val originalGate = OkHttpProvider.networkAllowed

    @Before fun openGate() {
        OkHttpProvider.networkAllowed = { true }
    }

    @After fun restore() {
        OkHttpProvider.socksAddressProvider = original
        OkHttpProvider.networkAllowed = originalGate
        OkHttpProvider.reset()
    }

    @Test
    fun `without a SOCKS address no client is built and nothing goes direct`() {
        OkHttpProvider.socksAddressProvider = { null }
        OkHttpProvider.reset()
        assertThrows(TorRequiredException::class.java) { OkHttpProvider.routedHttpClient() }
        assertThrows(TorRequiredException::class.java) { OkHttpProvider.httpClient() }
        assertThrows(TorRequiredException::class.java) { OkHttpProvider.webSocketClient() }
        // Callers that only catch IOException still fail closed.
        assertTrue(IOException::class.java.isAssignableFrom(TorRequiredException::class.java))

        // The failure cached nothing: once Tor publishes its port, every client is proxied.
        val socks = InetSocketAddress(InetAddress.getLoopbackAddress(), 9060)
        OkHttpProvider.socksAddressProvider = { socks }
        val routed = OkHttpProvider.routedHttpClient()
        assertEquals(OkHttpProvider.Route.TOR, routed.route)
        assertEquals(Proxy(Proxy.Type.SOCKS, socks), routed.client.proxy)
        assertEquals(Proxy(Proxy.Type.SOCKS, socks), OkHttpProvider.webSocketClient().proxy)
    }

    @Test
    fun `Internet mode off refuses every client, including one cached while it was on`() {
        OkHttpProvider.socksAddressProvider = { InetSocketAddress(InetAddress.getLoopbackAddress(), 9060) }
        OkHttpProvider.reset()
        OkHttpProvider.routedHttpClient()
        OkHttpProvider.webSocketClient()
        OkHttpProvider.networkAllowed = { false }
        assertThrows(TorRequiredException::class.java) { OkHttpProvider.routedHttpClient() }
        assertThrows(TorRequiredException::class.java) { OkHttpProvider.webSocketClient() }
        // The production default reads the process gate, which is closed until startup installs it.
        OkHttpProvider.networkAllowed = originalGate
        WeshNetworkPolicy.gate = WeshNetworkGate { false }
        assertThrows(TorRequiredException::class.java) { OkHttpProvider.httpClient() }
    }

    @Test
    fun `relay sockets send pings so an idle stream through Tor stays open`() {
        OkHttpProvider.socksAddressProvider = { InetSocketAddress(InetAddress.getLoopbackAddress(), 9060) }
        OkHttpProvider.reset()
        assertEquals(20_000, OkHttpProvider.webSocketClient().pingIntervalMillis)
    }

    @Test
    fun `reset clears cached clients without changing the route`() {
        OkHttpProvider.socksAddressProvider = { InetSocketAddress(InetAddress.getLoopbackAddress(), 9060) }
        OkHttpProvider.reset()
        val cachedHttp = OkHttpProvider.routedHttpClient()
        val cachedWebSocket = OkHttpProvider.webSocketClient()

        OkHttpProvider.reset()

        val rebuiltHttp = OkHttpProvider.routedHttpClient()
        val rebuiltWebSocket = OkHttpProvider.webSocketClient()
        assertEquals(cachedHttp.route, rebuiltHttp.route)
        assertNotSame(cachedHttp.client, rebuiltHttp.client)
        assertNotSame(cachedWebSocket, rebuiltWebSocket)
    }

    @Test
    fun `relay host name reaches the SOCKS proxy unresolved`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            // Minimal SOCKS5 peer: accept no-auth, record the CONNECT request, then hang up.
            val request = CompletableFuture.supplyAsync {
                server.accept().use { socket ->
                    val input = DataInputStream(socket.getInputStream())
                    check(input.readUnsignedByte() == 5)
                    input.readFully(ByteArray(input.readUnsignedByte()))
                    socket.getOutputStream().apply { write(byteArrayOf(5, 0)); flush() }
                    val header = ByteArray(4).also(input::readFully)
                    val host = if (header[3].toInt() == 3) String(ByteArray(input.readUnsignedByte()).also(input::readFully)) else ""
                    header[3].toInt() to host
                }
            }
            OkHttpProvider.socksAddressProvider = { InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort) }
            OkHttpProvider.reset()
            runCatching {
                OkHttpProvider.httpClient().newCall(Request.Builder().url("http://relay.example.invalid/").build()).execute().close()
            }
            val (addressType, host) = request.get(10, TimeUnit.SECONDS)
            assertEquals("SOCKS5 ATYP must be a domain name", 3, addressType)
            assertEquals("relay.example.invalid", host)
        }
    }
}
