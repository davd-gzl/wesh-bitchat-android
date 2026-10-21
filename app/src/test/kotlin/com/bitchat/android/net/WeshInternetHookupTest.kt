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

    private val keys by lazy {
        val prefs = app.getSharedPreferences("synthetic-nostr-key", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        com.bitchat.android.identity.SecureIdentityStateManager(prefs, true)
    }
    private fun stored() = com.bitchat.android.nostr.NostrIdentityBridge.peekCurrentNostrIdentity(keys)

    @Before fun closed() {
        WeshInternetMode.resetForTesting()
        WeshQrIdentity.resetForTesting()
        WeshQrIdentity.keyStore = { keys }
        NostrRelayManager.shared.disconnect()
    }

    @After fun restore() {
        WeshInternetMode.resetForTesting()
        OkHttpProvider.socksAddressProvider = originalSocks
        OkHttpProvider.reset()
        NostrRelayManager.shared.disconnect()
        NostrRelayManager.shared.clearAllSubscriptions()
    }

    @Test fun `product policy stays on and Internet stays unavailable until Arti can stop Tor`() {
        assertTrue(BuildConfig.WESH_POLICY)
        assertFalse(BuildConfig.WESH_INTERNET_AVAILABLE)
        assertFalse(WeshInternetMode.productInternetAvailable())
    }

    @Test fun `this build refuses to enable Internet even with the disclosure accepted`() {
        val mode = WeshInternetMode.get(app)
        mode.disclosureAccepted = true
        assertFalse(mode.setEnabled(true))
        assertFalse(mode.enabled.value)
        assertFalse(mode.allowsNetwork())
        assertFalse(WeshNetworkPolicy.allowsNetwork())
        assertEquals(TorMode.OFF, TorPreferenceManager.get(app))
    }

    @Test fun `an opt-in persisted by an earlier build stays off in this build`() {
        app.getSharedPreferences(WeshInternetMode.PREFS, android.content.Context.MODE_PRIVATE).edit()
            .putBoolean(WeshInternetMode.KEY_ENABLED, true)
            .putBoolean(WeshInternetMode.KEY_DISCLOSURE, true)
            .commit()
        val mode = WeshInternetMode.get(app)
        assertFalse(mode.enabled.value)
        assertFalse(mode.allowsNetwork())
        assertFalse(WeshNetworkPolicy.allowsNetwork())
        assertEquals(TorMode.OFF, TorPreferenceManager.get(app))
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

        // Internet routes stay closed; my QR never creates a key, so without one it shows no npub.
        assertFalse(WeshQrIdentity.isActive())
        val qr = WeshQrIdentity.myQR(app, "Synthetic contact")
        assertNull(qr?.let { VerificationService.VerificationQR.fromUrlString(it)?.npub })
        assertEquals(emptySet<String>(), WeshQrIdentity.ownedNpubs(app))
        assertNull(stored())
    }

    @Test fun `my key is made locally with Internet off and owned for enrollment, the routes still closed`() = runBlocking {
        assertFalse(WeshNetworkPolicy.allowsNetwork())
        assertTrue(WeshQrIdentity.ensureKey(app))
        val mine = stored()!!
        assertTrue(WeshQrIdentity.showsNpub())
        assertEquals(setOf(mine.npub), WeshQrIdentity.ownedNpubs(app))
        // Making the key again keeps the same one; the Internet routes still see nothing.
        assertTrue(WeshQrIdentity.ensureKey(app))
        assertEquals(mine.npub, stored()!!.npub)
        assertNull(WeshQrIdentity.mine(app))
    }

    @Test fun `concurrent creators all end up with the one stored key`() {
        val start = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            val made = (1..8).map {
                pool.submit<String?> { start.await(); com.bitchat.android.nostr.NostrIdentityBridge.getCurrentNostrIdentity(keys)?.npub }
            }
            start.countDown()
            val npubs = made.map { it.get(10, java.util.concurrent.TimeUnit.SECONDS) }.toSet()
            assertEquals(setOf(stored()!!.npub), npubs)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun `a stored key that cannot be read is never replaced by a new one`() = runBlocking {
        keys.storeSecureValue("nostr_private_key", "unparseable synthetic key")
        assertFalse(WeshQrIdentity.ensureKey(app))
        assertEquals("unparseable synthetic key", keys.getSecureValue("nostr_private_key"))
        // The entry exists but reading it fails, as a broken store's decrypt would: still no new key.
        val prefs = app.getSharedPreferences("synthetic-nostr-key", android.content.Context.MODE_PRIVATE)
        prefs.edit().putInt("nostr_private_key", 7).commit()
        assertFalse(WeshQrIdentity.ensureKey(app))
        assertEquals(7, prefs.getInt("nostr_private_key", 0))
    }

    @Test fun `while an erase is unconfirmed no key is made and no npub is shown or owned`() = runBlocking {
        assertTrue(WeshQrIdentity.ensureKey(app))
        WeshQrIdentity.eraseUnconfirmed = { true }
        assertFalse(WeshQrIdentity.showsNpub())
        assertNull(WeshQrIdentity.ownedNpubs(app))
        WeshQrIdentity.eraseUnconfirmed = { error("unreadable gate") }
        assertNull(WeshQrIdentity.ownedNpubs(app))
        keys.clearIdentityDataForPanic()
        WeshQrIdentity.eraseUnconfirmed = { true }
        assertFalse(WeshQrIdentity.ensureKey(app))
        assertNull(stored())
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
        try {
            WeshQrIdentity.eraseUnconfirmed = { synchronized(seen) { seen += Thread.currentThread() }; true }
            WeshQrIdentity.myQR(app, "Synthetic contact")
            assertNull(WeshQrIdentity.ownedNpubs(app))
            WeshQrIdentity.ensureKey(app)
        } finally {
            WeshQrIdentity.eraseUnconfirmed = { false }
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
