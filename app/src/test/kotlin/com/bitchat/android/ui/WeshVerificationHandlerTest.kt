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
import com.bitchat.android.ui.pave.PaveStringResources
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
    private val names = PaveStringResources.remoteNameRule()
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
        // Enrollment reads owned npubs off the caller thread; keep that hop on the test clock.
        com.bitchat.android.services.WeshQrIdentity.io = kotlinx.coroutines.test.StandardTestDispatcher(scope.testScheduler)
        time = 1_000_000L
        nonce = null
        handler = VerificationHandler(context, scope, { mesh }, mock(), state, mock(), mock(), { time }, remoteName = names)
    }
    @After fun cleanup() {
        WeshContactStore.resetForTesting()
        com.bitchat.android.services.WeshQrIdentity.resetForTesting()
    }
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
    @Test fun `announced nickname follows the remote rule where shown and is only stripped in the cache`() = scope.runTest {
        val key = qr.noiseKeyHex.dataFromHexString()!!
        whenever(mesh.getPeerInfo(peer)).thenReturn(PeerInfo(id = peer, nickname = "\u202Eevil\u200Bname", noisePublicKey = key, isConnected = true, isDirectConnection = true, signingPublicKey = qr.signKeyHex.dataFromHexString(), isVerifiedNickname = true, lastSeen = 1L))
        val identity: SecureIdentityStateManager = mock()
        val notifications: NotificationManager = mock()
        val context: Context = mock()
        whenever(context.getString(any(), any())).thenReturn("Synthetic verification notice")
        val state: ChatState = mock()
        whenever(state.getConnectedPeersValue()).thenReturn(listOf(peer))
        handler = VerificationHandler(context, scope, { mesh }, identity, state, notifications, mock(), { time }, remoteName = names)

        assertTrue(handler.beginQRVerification(qr))
        respond(); runCurrent()

        val fingerprint = handler.fingerprintFromNoiseBytes(key)
        verify(identity).cacheFingerprintNickname(fingerprint, "evilname")
        // The notification manager applies the rule itself (see WeshNotificationPrivacyTest).
        verify(notifications).showVerificationSuccessNotification("\u202Eevil\u200Bname", peer)
        verify(context).getString(any(), eq("Sans surnom"))
    }
    @Test fun `a peer named like an app label or a fingerprint is masked in the verification system message`() = scope.runTest {
        val key = qr.noiseKeyHex.dataFromHexString()!!
        listOf("Vous", "NO NICKNAME", "Nora A1B2 C3D4").forEach { impostor ->
            store.clear()
            whenever(mesh.getPeerInfo(peer)).thenReturn(PeerInfo(id = peer, nickname = impostor, noisePublicKey = key, isConnected = true, isDirectConnection = true, signingPublicKey = qr.signKeyHex.dataFromHexString(), isVerifiedNickname = true, lastSeen = 1L))
            val context: Context = mock()
            whenever(context.getString(any(), any())).thenReturn("Synthetic verification notice")
            val state: ChatState = mock()
            whenever(state.getConnectedPeersValue()).thenReturn(listOf(peer))
            handler = VerificationHandler(context, scope, { mesh }, mock(), state, mock(), mock(), { time }, remoteName = names)

            assertTrue(handler.beginQRVerification(qr))
            respond(); runCurrent()

            assertEquals(impostor, 1, store.contacts.value.size)
            verify(context).getString(eq(com.bitchat.android.R.string.verify_success_system_message), eq("Sans surnom (réservé)"))
            whenever(state.peerNicknames).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(mapOf(peer to impostor)))
            assertEquals(impostor, "Sans surnom (réservé)", handler.resolvePeerDisplayNameForFingerprint(peer))
        }
    }
    @Test fun `a scanned QR without a visible nickname caches no fingerprint look-alike`() {
        val identity: SecureIdentityStateManager = mock()
        handler = VerificationHandler(mock(), scope, { mesh }, identity, state(), mock(), mock(), { time }, remoteName = names)
        val unnamed = npubQR(syntheticNpub, "\u3164\u200B")
        assertTrue(handler.beginQRVerification(unnamed))
        verify(identity, never()).cacheFingerprintNickname(any(), any())
    }
    @Test fun `fingerprint display name applies the remote rule to live and announced nicknames`() {
        val state: ChatState = mock()
        whenever(state.peerNicknames).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(mapOf(peer to "\u202Eevil\u200Bname")))
        handler = VerificationHandler(mock(), scope, { mesh }, mock(), state, mock(), mock(), { time }, remoteName = names)
        assertEquals("Sans surnom", handler.resolvePeerDisplayNameForFingerprint(peer))

        whenever(state.peerNicknames).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(emptyMap()))
        whenever(mesh.getPeerInfo(peer)).thenReturn(PeerInfo(id = peer, nickname = "\u3164\u200B", noisePublicKey = qr.noiseKeyHex.dataFromHexString(), isConnected = true, isDirectConnection = true, signingPublicKey = qr.signKeyHex.dataFromHexString(), isVerifiedNickname = true, lastSeen = 1L))
        assertEquals("Sans surnom", handler.resolvePeerDisplayNameForFingerprint(peer))
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
    // Distinct nicknames keep the 60 s QR cache from reusing another case's code.
    private fun npubQR(npub: String, nickname: String = "Synthetic contact", signer: ((ByteArray) -> String?)? = null): VerificationService.VerificationQR =
        VerificationService.verifyScannedQR(VerificationService.buildMyQRString(nickname, npub, signer)!!)!!
    // syntheticNpub encodes the generator x, the public key of the synthetic secret 1.
    private val syntheticSigner: (ByteArray) -> String? = { com.bitchat.android.nostr.NostrCrypto.schnorrSign(it, "00".repeat(31) + "01") }
    private fun enroll(scanned: VerificationService.VerificationQR, mine: String?) {
        val owned = mine?.let(::setOf)
        qr = scanned
        val context: Context = mock()
        whenever(context.getString(any())).thenReturn("Synthetic verification title")
        whenever(context.getString(any(), any())).thenReturn("Synthetic verification notice")
        handler = VerificationHandler(context, scope, { mesh }, mock(), state(), mock(), mock(), { time }, { owned }, remoteName = names)
        assertTrue(handler.beginQRVerification(qr))
        respond(); scope.runCurrent()
    }
    private fun state(): ChatState = mock<ChatState>().also { whenever(it.getConnectedPeersValue()).thenReturn(listOf(peer)) }
    @Test fun `verification aborts without writing when stored contacts are unreadable`() = scope.runTest {
        val application = RuntimeEnvironment.getApplication()
        val prefs = application.getSharedPreferences("synthetic-handler-unreadable", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("wesh_qr_contacts_v1", "{unreadable").commit()
        val unreadable = WeshContactStore(SecureIdentityStateManager(prefs, true),
            application.getSharedPreferences("bitchat_prefs", Context.MODE_PRIVATE))
        WeshContactStore.installForTesting(unreadable)
        enroll(npubQR(syntheticNpub, nickname = "Unreadable store contact"), mine = mineNpub)
        assertTrue(unreadable.loadFailed.value)
        assertTrue(unreadable.contacts.value.isEmpty())
        assertEquals("{unreadable", prefs.getString("wesh_qr_contacts_v1", null))
        assertTrue(handler.weshProgress(qr.noiseKeyHex) != "verified")
    }
    @Test fun `signed QR npub is bound to the proven Noise key`() = scope.runTest {
        enroll(npubQR(syntheticNpub), mine = mineNpub)
        assertEquals(syntheticNpub, store.contacts.value.single().npub)
        assertTrue(store.contacts.value.single().internetReachable)
    }
    @Test fun `a valid npub proof marks the claim proven and a bad one drops the npub`() = scope.runTest {
        enroll(npubQR(syntheticNpub, "Synthetic proven", syntheticSigner), mine = mineNpub)
        assertTrue(store.contacts.value.single().npubProven)
        store.clear()
        enroll(npubQR(syntheticNpub, "Synthetic bad proof") { "ab".repeat(64) }, mine = mineNpub)
        assertTrue(store.contacts.value.single().npubWarning)
        assertNull(store.contacts.value.single().npub)
        store.clear()
        enroll(npubQR(syntheticNpub), mine = mineNpub)
        assertEquals(syntheticNpub, store.contacts.value.single().npub)
        assertFalse(store.contacts.value.single().npubProven)
    }
    @Test fun `with Internet mode closed my QR has no npub and enrollment binds none`() = scope.runTest {
        com.bitchat.android.services.WeshQrIdentity.internetActive = { false }
        val mine = com.bitchat.android.services.WeshQrIdentity.myQR(RuntimeEnvironment.getApplication(), "Synthetic contact")!!
        assertNull(VerificationService.VerificationQR.fromUrlString(mine)!!.npub)
        handler = VerificationHandler(mock<Context>().also {
            whenever(it.getString(any())).thenReturn("Synthetic verification title")
            whenever(it.getString(any(), any())).thenReturn("Synthetic verification notice")
        }, scope, { mesh }, mock(), state(), mock(), mock(), { time }, remoteName = names)
        qr = npubQR(syntheticNpub, "Synthetic closed", syntheticSigner)
        assertTrue(handler.beginQRVerification(qr))
        respond(); runCurrent()
        assertNull(store.contacts.value.single().npub)
    }
    /**
     * Starts a verification, lets the response pass its checks, and holds the off-Main npub read;
     * [during] runs while it is held, then the read is released. Nothing may be written after.
     */
    private fun TestScope.interruptedDuringNpubRead(nickname: String, during: () -> Unit) {
        val held = ArrayDeque<Runnable>()
        com.bitchat.android.services.WeshQrIdentity.io = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { held.addLast(block) }
        }
        val identity: SecureIdentityStateManager = mock()
        handler = VerificationHandler(mock<Context>().also {
            whenever(it.getString(any())).thenReturn("Synthetic verification title")
            whenever(it.getString(any(), any())).thenReturn("Synthetic verification notice")
        }, scope, { mesh }, identity, state(), mock(), mock(), { time }, remoteName = names)
        qr = npubQR(syntheticNpub, nickname, syntheticSigner)
        assertTrue(handler.beginQRVerification(qr))
        respond(); runCurrent()
        assertTrue(held.isNotEmpty())
        during()
        while (held.isNotEmpty()) held.removeFirst().run()
        runCurrent()
        assertTrue(store.contacts.value.isEmpty())
        verify(identity, never()).setVerifiedFingerprint(any(), any())
        assertEquals("unavailable", handler.weshProgress(qr.noiseKeyHex))
    }
    private val conversation get() = ContactIdentityResolver.contactConversationIdForNoiseKey(qr.noiseKeyHex.dataFromHexString()!!)
    @Test fun `a panic while owned npubs are read off the Main thread enrolls nothing`() = scope.runTest {
        interruptedDuringNpubRead("Synthetic panic race") { handler.clearWeshVerifications(); store.clear() }
    }
    @Test fun `cancelling the verification during the npub read enrolls nothing`() = scope.runTest {
        interruptedDuringNpubRead("Synthetic cancel race") { handler.cancelWeshVerification(conversation) }
    }
    @Test fun `removing the contact during a re-verification enrolls nothing`() = scope.runTest {
        interruptedDuringNpubRead("Synthetic remove race") {
            // The contact was enrolled earlier; the user removes it while this re-scan is checked.
            assertNotNull(store.enroll(qr.noiseKeyHex.dataFromHexString()!!, "Synthetic remove race", null, null, null))
            assertTrue(store.remove(conversation))
        }
    }
    @Test fun `a duplicate response for a claimed attempt never reaches the npub reader`() = scope.runTest {
        var reads = 0
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        handler = VerificationHandler(mock<Context>().also {
            whenever(it.getString(any())).thenReturn("Synthetic verification title")
            whenever(it.getString(any(), any())).thenReturn("Synthetic verification notice")
        }, scope, { mesh }, mock(), state(), mock(), mock(), { time }, { reads++; release.await(); null }, remoteName = names)
        qr = npubQR(syntheticNpub, "Synthetic duplicate", syntheticSigner)
        assertTrue(handler.beginQRVerification(qr))
        // The first response waits on the key store; the peer repeats it meanwhile.
        respond(); runCurrent()
        repeat(3) { respond() }; runCurrent()
        assertEquals(1, reads)
        release.complete(Unit); runCurrent()
        assertEquals(1, reads)
        assertEquals(1, store.contacts.value.size)
    }
    @Test fun `my own or unknown own npub enrolls the contact Bluetooth only`() = scope.runTest {
        enroll(npubQR(syntheticNpub), mine = syntheticNpub)
        assertNull(store.contacts.value.single().npub)
        store.clear()
        enroll(npubQR(syntheticNpub), mine = null)
        assertNull(store.contacts.value.single().npub)
        assertFalse(store.contacts.value.single().internetReachable)
    }
    private val syntheticNpub = com.bitchat.android.nostr.Bech32.encode("npub",
        "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798".dataFromHexString()!!)
    private val mineNpub = com.bitchat.android.nostr.Bech32.encode("npub", ByteArray(31) + byteArrayOf(2))
}
