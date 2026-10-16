package com.bitchat.android.net

import android.app.Application
import com.bitchat.android.BuildConfig
import com.bitchat.android.nostr.NostrBackgroundRuntime
import com.bitchat.android.nostr.NostrRelayManager
import com.bitchat.android.services.VerificationService
import com.bitchat.android.services.WeshQrIdentity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress

/** The build flags only set product policy; every socket follows the runtime Internet toggle. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class WeshInternetHookupTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val originalSocks = OkHttpProvider.socksAddressProvider
    private val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()

    @Before fun closed() {
        WeshInternetMode.resetForTesting()
        WeshQrIdentity.resetForTesting()
        NostrRelayManager.shared.disconnect()
    }

    @After fun restore() {
        WeshInternetMode.resetForTesting()
        OkHttpProvider.socksAddressProvider = originalSocks
        OkHttpProvider.reset()
        NostrRelayManager.shared.disconnect()
        NostrRelayManager.shared.clearAllSubscriptions()
    }

    @Test fun `product policy stays on and Internet is available as an opt-in`() {
        assertTrue(BuildConfig.WESH_POLICY)
        assertTrue(BuildConfig.WESH_INTERNET_AVAILABLE)
        assertTrue(WeshInternetMode.productInternetAvailable())
    }

    @Test fun `with the toggle off nothing network-related starts`() = runBlocking {
        TorPreferenceManager.set(app, TorMode.ON)
        val mode = WeshInternetMode.get(app)
        // Fresh install: off, and startup forces Tor's preference off.
        assertFalse(mode.enabled.value)
        assertEquals(WeshInternetState.Off, mode.state.value)
        assertFalse(WeshNetworkPolicy.allowsNetwork())
        assertEquals(TorMode.OFF, TorPreferenceManager.get(app))

        // Even with a SOCKS port published, no client is handed out.
        OkHttpProvider.socksAddressProvider = { InetSocketAddress(InetAddress.getLoopbackAddress(), 9060) }
        OkHttpProvider.reset()
        assertThrows(TorRequiredException::class.java) { OkHttpProvider.httpClient() }
        assertThrows(TorRequiredException::class.java) { OkHttpProvider.webSocketClient() }

        val relays = NostrRelayManager.shared
        relays.connect()
        assertFalse(relays.isConnectionDesired())
        assertTrue(relays.getRelayStatuses().none { it.isConnected })

        // My QR reveals no npub while Internet mode is off.
        assertFalse(WeshQrIdentity.isActive())
        val qr = WeshQrIdentity.myQR(app, "Synthetic contact")
        assertNull(qr?.let { VerificationService.VerificationQR.fromUrlString(it)?.npub })
        assertNull(WeshQrIdentity.ownedNpubs(app))
    }

    @Test fun `the QR identity follows the runtime Internet gate`() {
        WeshNetworkPolicy.gate = WeshNetworkGate { true }
        assertTrue(WeshQrIdentity.isActive())
        WeshNetworkPolicy.gate = WeshNetworkGate { false }
        assertFalse(WeshQrIdentity.isActive())
        WeshNetworkPolicy.gate = WeshNetworkGate { error("unreadable gate") }
        assertFalse(WeshQrIdentity.isActive())
    }

    @Test fun `with the toggle on the product-safety gates stay on`() {
        WeshNetworkPolicy.gate = WeshNetworkGate { true }
        // No unfiltered account-DM subscription in Wesh.
        NostrBackgroundRuntime.initialize(app)
        assertTrue(NostrRelayManager.shared.getActiveSubscriptions().isEmpty())
    }

    @Test fun `my QR and owned npubs are read off the calling thread`() = runBlocking {
        val caller = Thread.currentThread()
        val seen = mutableListOf<Thread>()
        val original = WeshQrIdentity.internetActive
        try {
            WeshQrIdentity.internetActive = { synchronized(seen) { seen += Thread.currentThread() }; false }
            WeshQrIdentity.myQR(app, "Synthetic contact")
            assertNull(WeshQrIdentity.ownedNpubs(app))
        } finally {
            WeshQrIdentity.internetActive = original
        }
        assertTrue(seen.isNotEmpty())
        seen.forEach { assertNotEquals(caller, it) }
    }

    @Test fun `network code reads the runtime toggle, never a build flag`() {
        val gated = listOf("OkHttpProvider.kt", "NostrRelayManager.kt", "WeshInternetMode.kt", "WeshInternetAdapters.kt")
        sources.filter { it.name in gated }.also { assertEquals(gated.size, it.size) }.forEach { file ->
            assertFalse("${file.name} reads the product flag", file.readText().contains("BuildConfig.WESH_POLICY"))
        }
    }

    @Test fun `no HTTP client is ever built outside the Tor-only provider`() {
        val needles = listOf("OkHttpClient.Builder(", "OkHttpClient()", "HttpURLConnection", ".openConnection(", "newBuilder()")
        val offenders = sources.filter { it.name != "OkHttpProvider.kt" }
            .filter { file -> file.readText().let { text -> needles.any { it in text } } }
            .map { it.name }
        assertTrue("direct HTTP clients in $offenders", offenders.isEmpty())
    }
}
