package com.bitchat.android.services

import android.app.Application
import android.content.Context
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.mesh.PeerInfo
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class WeshPrivateSenderTest {
    private val key = ByteArray(32) { 7 }
    private val replacement = ByteArray(32) { 8 }
    private val peer = ContactIdentityResolver.peerIdForNoiseKey(key)
    private val destination = ContactIdentityResolver.contactConversationIdForNoiseKey(key)
    private lateinit var mesh: MeshService
    private lateinit var store: WeshContactStore
    private lateinit var sender: WeshPrivateSender
    private lateinit var blocks: android.content.SharedPreferences
    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        val identityPrefs = context.getSharedPreferences("synthetic-wesh-identity", Context.MODE_PRIVATE)
        identityPrefs.edit().clear().commit()
        blocks = context.getSharedPreferences("bitchat_prefs", Context.MODE_PRIVATE)
        blocks.edit().clear().commit()
        store = WeshContactStore(SecureIdentityStateManager(identityPrefs, true), blocks)
        WeshContactStore.installForTesting(store)
        mesh = mock()
        whenever(mesh.myPeerID).thenReturn("1111222233334444")
        whenever(mesh.getPeerNicknames()).thenReturn(mapOf(peer to "Synthetic"))
        whenever(mesh.getPeerInfo(peer)).thenReturn(PeerInfo(id = peer, nickname = "Synthetic", isConnected = true, noisePublicKey = key, isDirectConnection = true, signingPublicKey = ByteArray(32), isVerifiedNickname = true, lastSeen = 1L))
        whenever(mesh.getAuthenticatedRemoteStaticKey(peer)).thenReturn(key)
        ContactDirectory.initialize(context) { mesh }
        sender = WeshPrivateSender(store)
        AppStateStore.clear()
    }
    @After fun cleanup() { WeshContactStore.resetForTesting(); MessageRouter.resetForTesting(); MessageRouter.disableSchedulerForTesting = false; AppStateStore.clear() }
    @Test fun `generic verification and parsed contact key cannot grant QR enrollment`() = runTest {
        assertFalse(sender.canSend(destination, mesh))
        assertFalse(sender.dispatch("synthetic", destination, "not-enrolled", mesh))
        verify(mesh, never()).sendPrivateMessageBound(any(), any(), any(), any(), any())
    }
    @Test fun `changed session blocked identity and revoked contact all prevent effects`() = runTest {
        store.record(key, "Synthetic")
        assertTrue(sender.canSend(destination, mesh))
        whenever(mesh.getAuthenticatedRemoteStaticKey(peer)).thenReturn(replacement)
        assertFalse(sender.dispatch("synthetic", destination, "changed", mesh))
        whenever(mesh.getAuthenticatedRemoteStaticKey(peer)).thenReturn(key)
        blocks.edit().putStringSet("blocked_users", setOf(ContactIdentityResolver.fingerprintHex(key))).commit()
        assertFalse(sender.dispatch("synthetic", destination, "blocked", mesh))
        blocks.edit().clear().commit()
        store.remove(destination)
        assertFalse(sender.dispatch("synthetic", destination, "removed", mesh))
        verify(mesh, never()).sendPrivateMessageBound(any(), any(), any(), any(), any())
    }
    @Test fun `revocation between admission and encryption is rechecked by effect callback`() = runTest {
        store.record(key, "Synthetic")
        whenever(mesh.sendPrivateMessageBound(any(), any(), any(), any(), any())).thenAnswer { call ->
            store.remove(destination)
            call.getArgument<() -> Boolean>(4).invoke()
        }
        assertFalse(sender.dispatch("synthetic", destination, "revocation", mesh))
    }
    @Test fun `handoff failure retains message with retryable state and accepted handoff is not delivery`() = runTest {
        store.record(key, "Synthetic")
        val message = BitchatMessage(id = "retry", sender = "Synthetic self", content = "synthetic", timestamp = Date(1),
            senderPeerID = mesh.myPeerID, isPrivate = true, deliveryStatus = DeliveryStatus.Sending)
        AppStateStore.addPrivateMessage(destination, message)
        whenever(mesh.sendPrivateMessageBound(any(), any(), any(), any(), any())).thenReturn(false)
        assertFalse(sender.dispatch(message.content, destination, message.id, mesh))
        assertTrue(AppStateStore.privateMessages.value[destination]!!.single().deliveryStatus is DeliveryStatus.Failed)
        whenever(mesh.sendPrivateMessageBound(any(), any(), any(), any(), any())).thenReturn(true)
        assertTrue(sender.dispatch(message.content, destination, message.id, mesh))
        assertEquals(DeliveryStatus.Sent, AppStateStore.privateMessages.value[destination]!!.single().deliveryStatus)
        assertEquals(1, AppStateStore.privateMessages.value[destination]!!.size)
    }
    @Test fun `router cannot enqueue or dispatch an unverified or blocked notification destination`() {
        MessageRouter.disableSchedulerForTesting = true
        val router = MessageRouter.getInstance(RuntimeEnvironment.getApplication(), mesh)
        assertEquals(MessageRouter.RouteResult.DROPPED, router.sendPrivate("synthetic", peer, "Synthetic", "unknown"))
        store.record(key, "Synthetic")
        blocks.edit().putStringSet("blocked_users", setOf(ContactIdentityResolver.fingerprintHex(key))).commit()
        assertEquals(MessageRouter.RouteResult.DROPPED, router.sendPrivate("synthetic", destination, "Synthetic", "blocked"))
        MessageRouter.disableSchedulerForTesting = false
    }
    @Test fun `queued enrollment revoked before reconnect cannot reach a transport`() = runTest {
        store.record(key, "Synthetic")
        whenever(mesh.getAuthenticatedRemoteStaticKey(peer)).thenReturn(null)
        MessageRouter.disableSchedulerForTesting = true
        val router = MessageRouter.getInstance(RuntimeEnvironment.getApplication(), mesh)
        assertEquals(MessageRouter.RouteResult.QUEUED, router.sendPrivate("synthetic", destination, "Synthetic", "queued"))
        store.remove(destination)
        whenever(mesh.getAuthenticatedRemoteStaticKey(peer)).thenReturn(key)
        router.flushOutboxFor(destination)
        verify(mesh, never()).sendPrivateMessageBound(any(), any(), any(), any(), any())
        verify(mesh, never()).sendPrivateMessage(any(), any(), any(), anyOrNull())
    }
    @Test fun `failed session inspection refuses outgoing effects`() = runTest {
        store.record(key, "Synthetic")
        whenever(mesh.getAuthenticatedRemoteStaticKey(peer)).thenThrow(IllegalStateException("Synthetic unavailable session"))
        assertFalse(sender.canSend(destination, mesh))
        assertFalse(sender.dispatch("synthetic", destination, "unavailable", mesh))
        verify(mesh, never()).sendPrivateMessageBound(any(), any(), any(), any(), any())
    }
    @Test fun `offline QR enrollment restores key and nickname separately from conversation history`() {
        store.record(key, "Synthetic")
        val restored = WeshContactStore(SecureIdentityStateManager(RuntimeEnvironment.getApplication().getSharedPreferences("synthetic-wesh-identity", Context.MODE_PRIVATE), true), blocks)
        WeshContactStore.installForTesting(restored)
        ContactDirectory.initialize(RuntimeEnvironment.getApplication()) { null }
        val resolution = ContactDirectory.resolve(destination)
        assertArrayEquals(key, resolution.noisePublicKey)
        assertEquals("Synthetic", resolution.displayName)
        assertNull(resolution.meshPeerID)
    }
}
