package com.bitchat.android.ui

import android.app.Application
import android.content.Context
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.mesh.PeerInfo
import com.bitchat.android.noise.NoiseSession
import com.bitchat.android.services.ContactIdentityResolver
import com.bitchat.android.services.VerificationService
import com.bitchat.android.services.WeshContactStore
import com.bitchat.android.util.dataFromHexString
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class WeshVerificationHandlerTest {
    private lateinit var signer: EncryptionService
    private lateinit var mesh: MeshService
    private lateinit var handler: VerificationHandler
    private lateinit var store: WeshContactStore
    private lateinit var qr: VerificationService.VerificationQR
    private lateinit var peer: String
    private lateinit var scope: TestScope
    private var time = 1_000_000L
    private var nonce: ByteArray? = null
    @Before fun setup() {
        val application = RuntimeEnvironment.getApplication()
        val prefs = application.getSharedPreferences("synthetic-handler", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val blocks = application.getSharedPreferences("bitchat_prefs", Context.MODE_PRIVATE)
        blocks.edit().clear().commit()
        store = WeshContactStore(SecureIdentityStateManager(prefs, true), blocks)
        WeshContactStore.installForTesting(store)
        // Real Ed25519 signatures with a synthetic seed, without a device-keystore dependency.
        signer = mock()
        val signingKey = org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(ByteArray(32) { 11 }, 0)
        val publicKey = signingKey.generatePublicKey().encoded
        whenever(signer.getStaticPublicKey()).thenReturn(ByteArray(32) { 12 })
        whenever(signer.getSigningPublicKey()).thenReturn(publicKey)
        whenever(signer.signData(any())).thenAnswer { call ->
            val bytes = call.getArgument<ByteArray>(0)
            org.bouncycastle.crypto.signers.Ed25519Signer().apply {
                init(true, signingKey); update(bytes, 0, bytes.size)
            }.generateSignature()
        }
        whenever(signer.verifyEd25519Signature(any(), any(), any())).thenAnswer { call ->
            val signature = call.getArgument<ByteArray>(0)
            val bytes = call.getArgument<ByteArray>(1)
            val key = call.getArgument<ByteArray>(2)
            org.bouncycastle.crypto.signers.Ed25519Signer().apply {
                init(false, org.bouncycastle.crypto.params.Ed25519PublicKeyParameters(key, 0)); update(bytes, 0, bytes.size)
            }.verifySignature(signature)
        }
        VerificationService.configure(signer)
        qr = VerificationService.verifyScannedQR(VerificationService.buildMyQRString("Synthetic contact", null)!!)!!
        val key = qr.noiseKeyHex.dataFromHexString()!!
        peer = ContactIdentityResolver.peerIdForNoiseKey(key)
        mesh = mock()
        whenever(mesh.getPeerInfo(peer)).thenReturn(PeerInfo(id = peer, nickname = "Synthetic contact", noisePublicKey = key, isConnected = true, isDirectConnection = true, signingPublicKey = qr.signKeyHex.dataFromHexString(), isVerifiedNickname = true, lastSeen = 1L))
        whenever(mesh.getSessionState(peer)).thenReturn(NoiseSession.NoiseSessionState.Established)
        whenever(mesh.getAuthenticatedRemoteStaticKey(peer)).thenReturn(key)
        doAnswer { call -> nonce = call.getArgument<ByteArray>(2); null }.whenever(mesh).sendVerifyChallenge(eq(peer), any(), any())
        val state: ChatState = mock()
        whenever(state.getConnectedPeersValue()).thenReturn(listOf(peer))
        val context: Context = mock()
        whenever(context.getString(any())).thenReturn("Synthetic verification title")
        whenever(context.getString(any(), any())).thenReturn("Synthetic verification notice")
        scope = TestScope()
        time = 1_000_000L
        nonce = null
        handler = VerificationHandler(context, scope, { mesh }, mock(), state, mock(), mock(), { time })
    }
    @After fun cleanup() { WeshContactStore.resetForTesting() }
    private fun respond() = handler.didReceiveVerifyResponse(peer, VerificationService.buildVerifyResponse(qr.noiseKeyHex, nonce!!)!!)
    @Test fun `pending response completes local proof only within exact sixty second window`() = scope.runTest {
        assertTrue(handler.beginQRVerification(qr))
        assertTrue(store.contacts.value.isEmpty())
        assertEquals("pending", handler.weshProgress(qr.noiseKeyHex))
        time += 60_000
        respond(); runCurrent()
        assertEquals(1, store.contacts.value.size)
        assertEquals(qr.nickname, store.contacts.value.single().nickname)
        assertEquals("verified", handler.weshProgress(qr.noiseKeyHex))
    }
    @Test fun `response after deadline and late handshake cannot enroll`() = scope.runTest {
        assertTrue(handler.beginQRVerification(qr))
        time += 60_001
        assertEquals("expired", handler.weshProgress(qr.noiseKeyHex))
        respond(); runCurrent()
        assertTrue(store.contacts.value.isEmpty())
        clearInvocations(mesh)
        whenever(mesh.getSessionState(peer)).thenReturn(NoiseSession.NoiseSessionState.Uninitialized)
        assertTrue(handler.beginQRVerification(qr))
        time += 60_001
        handler.sendPendingVerificationIfNeeded(peer)
        verify(mesh, never()).sendVerifyChallenge(any(), any(), any())
    }
    @Test fun `revoking enrollment cancels a pending response without touching prior proof history`() = scope.runTest {
        store.record(qr.noiseKeyHex.dataFromHexString()!!, qr.nickname)
        assertTrue(handler.beginQRVerification(qr))
        val destination = store.contacts.value.single().conversationID
        handler.cancelWeshVerification(destination)
        store.remove(destination)
        respond(); runCurrent()
        assertTrue(store.contacts.value.isEmpty())
        assertEquals("unavailable", handler.weshProgress(qr.noiseKeyHex))
    }
    @Test fun `key replacement rejects signed QR response and requires a fresh attempt`() = scope.runTest {
        assertTrue(handler.beginQRVerification(qr))
        whenever(mesh.getAuthenticatedRemoteStaticKey(peer)).thenReturn(ByteArray(32) { 9 })
        respond(); runCurrent()
        assertTrue(store.contacts.value.isEmpty())
    }
}
