package com.bitchat.android.services

import android.content.Context
import android.os.Build
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.model.ReadReceipt
import com.bitchat.android.net.WeshNetworkGate
import com.bitchat.android.net.WeshNetworkPolicy
import com.bitchat.android.nostr.NostrTransport
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Internet mode can now be switched on, but the Wesh route to QR contacts is not wired yet:
 * the inherited favorite-based Nostr fallbacks must stay closed whatever the toggle says.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class WeshRouterInternetClosedTest {
    private val peerID = "aaaabbbbccccdddd"
    private val originalGate = WeshNetworkPolicy.gate
    private lateinit var mesh: MeshService
    private lateinit var nostr: NostrTransport
    private lateinit var router: MessageRouter

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("wesh-router-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        val identityManager = SecureIdentityStateManager(prefs, testOnly = true)
        ContactDirectory.identityManagerProvider = { identityManager }
        mesh = mock()
        whenever(mesh.myPeerID).thenReturn("1111222233334444")
        whenever(mesh.getPeerNicknames()).thenReturn(mapOf(peerID to "peer"))
        ContactDirectory.initialize(context) { mesh }
        MessageRouter.disableSchedulerForTesting = true
        nostr = mock()
        router = MessageRouter(context, mesh, nostr)
        WeshNetworkPolicy.gate = WeshNetworkGate { true }
    }

    @After fun tearDown() {
        WeshNetworkPolicy.gate = originalGate
        MessageRouter.disableSchedulerForTesting = false
        ContactDirectory.identityManagerProvider = { SecureIdentityStateManager(it) }
    }

    @Test fun `the QR router policy is on by default`() {
        assertTrue(router.enforceWeshPolicy)
    }

    @Test fun `with Internet on, receipts, acks and favorites never fall back to Nostr`() {
        router.sendReadReceipt(ReadReceipt("synthetic-message", peerID), peerID)
        router.sendDeliveryAck("synthetic-message", peerID)
        router.sendFavoriteNotification(peerID, true)
        router.tickOutbox()
        verifyNoInteractions(nostr)
    }
}
