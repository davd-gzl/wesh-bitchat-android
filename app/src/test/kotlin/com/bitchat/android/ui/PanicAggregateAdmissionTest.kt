package com.bitchat.android.ui

import android.app.Application
import android.os.Build
import com.bitchat.android.mesh.MeshDelegate
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.mesh.PeerInfo
import com.bitchat.android.mesh.UnifiedMeshPorts
import com.bitchat.android.mesh.UnifiedMeshService
import com.bitchat.android.noise.NoiseSession
import com.bitchat.android.startup.WeshRecoveryGate
import com.bitchat.android.services.ConversationStorageCipher
import com.bitchat.android.ui.pave.PavePanicPresentation
import com.bitchat.android.ui.pave.pavePanicPresentation
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real facade/collector/coordinator with owned RAM ports; no platform service or storage graph. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class PanicAggregateAdmissionTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun `actual facade success cannot authorize destruction with missing required owners`() = runTest {
        withFixture { f ->
            f.facade.startServices()
            runCurrent()
            val recovery = WeshRecoveryGate().apply { requireRecovery() }
            var syntheticIntentPending = false
            var erases = 0
            var report: PanicRetirementReport? = null
            val admitted = PanicResetCoordinator.admitDestructivePanicOwners(
                establishIntent = { syntheticIntentPending = true; true },
                retireOwners = { collectPanicRetirement(f.facade).also { report = it } },
                eraseOwnedData = { erases++; true }
            )
            assertSame("actual facade retirement must succeed", PanicOwnerRetirement.Confirmed, checkNotNull(report).facade)
            assertTrue("actual owned facade job must complete", f.ownerJob.isCompleted)
            assertEquals("missing required owners must prevent the entire destructive thunk", 0, erases)
            assertFalse(admitted)
            assertMissingProducers(checkNotNull(report))
            assertTrue(syntheticIntentPending)
            assertTrue(recovery.recoveryRequired.value)
            assertFalse(recovery.allowsTransport(prepared = true))
            assertEquals(0, f.transport.erasures)
        }
    }

    @Test fun `failed intent never collects or destroys any owner`() = runTest {
        var collections = 0
        var erases = 0
        assertFalse(PanicResetCoordinator.admitDestructivePanicOwners(
            establishIntent = { false },
            retireOwners = { collections++; collectPanicRetirement(null) },
            eraseOwnedData = { erases++; true }
        ))
        assertEquals(0, collections)
        assertEquals(0, erases)
    }

    @Test fun `missing captured facade is explicitly unconfirmed without inventing a replacement`() = runTest {
        val report = collectPanicRetirement(null)
        assertEquals(PanicRetirementGap.MissingCapturedFacade, (report.facade as PanicOwnerRetirement.Unconfirmed).gap)
        assertMissingProducers(report)
        assertFalse(report.confirmed)
    }

    @Test fun `actual facade snapshot failure remains unconfirmed and cannot enter destruction`() = runTest {
        withFixture { f ->
            f.failWifiSnapshot = true
            var erases = 0
            var report: PanicRetirementReport? = null
            assertFalse(PanicResetCoordinator.admitDestructivePanicOwners(
                establishIntent = { true },
                retireOwners = { collectPanicRetirement(f.facade).also { report = it } },
                eraseOwnedData = { erases++; true }
            ))
            assertEquals(PanicRetirementGap.FacadeUnconfirmed, (checkNotNull(report).facade as PanicOwnerRetirement.Unconfirmed).gap)
            assertTrue(f.ownerJob.isCompleted)
            assertMissingProducers(checkNotNull(report))
            assertEquals(0, erases)
        }
    }

    @Test fun `retirement exception propagates without entering destruction`() = runTest {
        val failure = IllegalStateException("Synthetic retirement failure")
        var erases = 0
        val result = runCatching {
            PanicResetCoordinator.admitDestructivePanicOwners(
                establishIntent = { true },
                retireOwners = { throw failure },
                eraseOwnedData = { erases++; true }
            )
        }
        assertSame(failure, result.exceptionOrNull())
        assertEquals(0, erases)
    }

    @Test fun `cancellation after intent prevents any report or destructive continuation`() = runTest {
        var collections = 0
        var erases = 0
        val request = async {
            PanicResetCoordinator.admitDestructivePanicOwners(
                establishIntent = { currentCoroutineContext().cancel(); true },
                retireOwners = { collections++; collectPanicRetirement(null) },
                eraseOwnedData = { erases++; true }
            )
        }
        finishRequest(request)
        assertTrue(request.isCancelled)
        assertEquals(0, collections)
        assertEquals(0, erases)
    }

    @Test fun `cancellation after actual completed report cannot enter destruction`() = runTest {
        withFixture { f ->
            var erases = 0
            var reported = false
            val request = f.request(async {
                PanicResetCoordinator.admitDestructivePanicOwners(
                    establishIntent = { true },
                    retireOwners = {
                        collectPanicRetirement(f.facade).also {
                            assertSame(PanicOwnerRetirement.Confirmed, it.facade)
                            reported = true
                            currentCoroutineContext().cancel()
                        }
                    },
                    eraseOwnedData = { erases++; true }
                )
            })
            finishRequest(request)
            assertTrue(reported)
            assertTrue(f.ownerJob.isCompleted)
            assertTrue(request.isCancelled)
            assertEquals(0, erases)
        }
    }

    @Test fun `cancelled caller waits actual held facade cleanup then refuses destruction`() = runTest {
        val cleanupEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val profile = flow<Pair<Long, Boolean>> {
            try { emit(60_000L to false); awaitCancellation() }
            finally { withContext(NonCancellable) { cleanupEntered.complete(Unit); release.await() } }
        }
        withFixture(profile, beforeClose = { release.complete(Unit) }) { f ->
            f.facade.startServices()
            runCurrent()
            var erases = 0
            var reported = false
            val request = f.request(async {
                PanicResetCoordinator.admitDestructivePanicOwners(
                    establishIntent = { true },
                    retireOwners = { collectPanicRetirement(f.facade).also { reported = true } },
                    eraseOwnedData = { erases++; true }
                )
            })
            withTimeout(5_000) { cleanupEntered.await() }
            request.cancel()
            runCurrent()
            assertFalse("actual owned cleanup is still held", request.isCompleted)
            assertFalse(f.ownerJob.isCompleted)
            assertEquals(0, erases)
            release.complete(Unit)
            finishRequest(request)
            assertTrue(reported)
            assertTrue(request.isCancelled)
            assertTrue(f.ownerJob.isCompleted)
            assertEquals(0, erases)
        }
    }

    @Test fun `actual unconfirmed owners retain checked intent and recovery presentation without erasure`() = runTest {
        withFixture { f ->
            val root = directory.newFolder()
            val opaque = byteArrayOf(17, 31, 47, 61)
            val ciphertext = File(root, "wesh-drafts-v1.bin").apply { writeBytes(opaque) }
            var cipherCalls = 0
            val cipher = object : ConversationStorageCipher {
                override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
                    cipherCalls++
                    error("Unexpected synthetic encrypt")
                }
                override fun decrypt(envelope: ByteArray, associatedData: ByteArray): ByteArray {
                    cipherCalls++
                    error("Unexpected synthetic decrypt")
                }
                override fun destroyKey() { cipherCalls++ }
            }
            val backend = EncryptedWeshDraftBackend(root, cipher)
            val drafts = WeshDraftStore(backend, backgroundScope, StandardTestDispatcher(testScheduler))
            val recovery = WeshRecoveryGate().apply { requireRecovery() }
            drafts.fenceForPanic()
            val capturedEpoch = drafts.epoch
            f.facade.startServices()
            runCurrent()
            var report: PanicRetirementReport? = null
            var erases = 0
            val admitted = PanicResetCoordinator.admitDestructivePanicOwners(
                establishIntent = { drafts.establishPanicIntent(capturedEpoch) },
                retireOwners = { collectPanicRetirement(f.facade).also { report = it } },
                eraseOwnedData = { erases++; true }
            )
            assertSame("actual facade retirement must succeed", PanicOwnerRetirement.Confirmed, checkNotNull(report).facade)
            assertTrue("actual owned facade job must complete", f.ownerJob.isCompleted)
            assertEquals("missing required owners must prevent the entire destructive thunk", 0, erases)
            assertFalse(admitted)
            assertMissingProducers(checkNotNull(report))
            assertTrue(EncryptedWeshDraftBackend.hasPendingWipe(root))
            assertArrayEquals(opaque, ciphertext.readBytes())
            assertEquals(0, cipherCalls)
            assertEquals(0, f.transport.erasures)
            assertEquals(WeshDraftStore.Persistence.WipeRequired, drafts.persistence.value)
            assertFalse(drafts.save(capturedEpoch, "public", "Synthetic refused edit"))
            assertTrue(recovery.recoveryRequired.value)
            assertFalse(recovery.allowsTransport(prepared = true))
            val coldGate = WeshRecoveryGate()
            if (EncryptedWeshDraftBackend.hasPendingWipe(root)) coldGate.requireRecovery()
            assertTrue(coldGate.recoveryRequired.value)
            assertFalse(coldGate.allowsTransport(prepared = true))
            assertEquals(PavePanicPresentation.RecoveryRequired,
                pavePanicPresentation(false, recovery.recoveryRequired.value, true))
            assertEquals(PavePanicPresentation.RecoveryRequired,
                pavePanicPresentation(false, coldGate.recoveryRequired.value, true))
            assertEquals(PavePanicPresentation.Erasing,
                pavePanicPresentation(true, recovery.recoveryRequired.value, true))
        }
    }

    @Test fun `each mandatory unconfirmed owner refuses the entire destructive thunk`() = runTest {
        val confirmed = PanicRetirementReport(
            facade = PanicOwnerRetirement.Confirmed,
            wifiController = PanicOwnerRetirement.Confirmed,
            holderConstructionAndCallbacks = PanicOwnerRetirement.Confirmed,
            gossipJobsAndWriters = PanicOwnerRetirement.Confirmed,
            transportCoreRadioAndHandlers = PanicOwnerRetirement.Confirmed,
            startupAndRemainingSharedKeyUsers = PanicOwnerRetirement.Confirmed
        )
        val unavailable = PanicOwnerRetirement.Unconfirmed(PanicRetirementGap.ProducerNotImplemented)
        val cases = listOf(
            "facade" to confirmed.copy(facade = PanicOwnerRetirement.Unconfirmed(PanicRetirementGap.FacadeUnconfirmed)),
            "wifiController" to confirmed.copy(wifiController = unavailable),
            "holderConstructionAndCallbacks" to confirmed.copy(holderConstructionAndCallbacks = unavailable),
            "gossipJobsAndWriters" to confirmed.copy(gossipJobsAndWriters = unavailable),
            "transportCoreRadioAndHandlers" to confirmed.copy(transportCoreRadioAndHandlers = unavailable),
            "startupAndRemainingSharedKeyUsers" to confirmed.copy(startupAndRemainingSharedKeyUsers = unavailable)
        )
        data class Outcome(val label: String, val admitted: Boolean, val reports: Int, val erases: Int)
        val outcomes = cases.map { (label, report) ->
            var reports = 0
            var erases = 0
            val admitted = PanicResetCoordinator.admitDestructivePanicOwners(
                establishIntent = { true },
                retireOwners = { reports++; report },
                eraseOwnedData = { erases++; true }
            )
            Outcome(label, admitted, reports, erases)
        }
        outcomes.forEach {
            assertFalse(it.label, it.admitted)
            assertEquals(it.label, 1, it.reports)
            assertEquals(it.label, 0, it.erases)
        }
    }

    @Test fun `synthetic complete report reaches eraser once and preserves its false result`() = runTest {
        val report = PanicRetirementReport(
            facade = PanicOwnerRetirement.Confirmed,
            wifiController = PanicOwnerRetirement.Confirmed,
            holderConstructionAndCallbacks = PanicOwnerRetirement.Confirmed,
            gossipJobsAndWriters = PanicOwnerRetirement.Confirmed,
            transportCoreRadioAndHandlers = PanicOwnerRetirement.Confirmed,
            startupAndRemainingSharedKeyUsers = PanicOwnerRetirement.Confirmed
        )
        val order = mutableListOf<String>()
        var reports = 0
        var erases = 0
        val admitted = PanicResetCoordinator.admitDestructivePanicOwners(
            establishIntent = { order += "intent"; true },
            retireOwners = { order += "report"; reports++; report },
            eraseOwnedData = { actual ->
                order += "erase"
                erases++
                assertSame(report, actual)
                false
            }
        )
        assertFalse(admitted)
        assertEquals(listOf("intent", "report", "erase"), order)
        assertEquals(1, reports)
        assertEquals(1, erases)
    }

    private class Fixture(scope: TestScope, profile: Flow<Pair<Long, Boolean>>?) {
        val ownerJob = SupervisorJob()
        private val ownedScope = CoroutineScope(ownerJob + StandardTestDispatcher(scope.testScheduler))
        val transport = InertMesh()
        var failWifiSnapshot = false
        private val requests = mutableListOf<Job>()
        val facade = UnifiedMeshService(transport, UnifiedMeshPorts(
            scope = ownedScope,
            announcementProfile = profile ?: MutableStateFlow(60_000L to false),
            hasAnnouncementRecipients = { false },
            currentWifi = { if (failWifiSnapshot) error("Synthetic snapshot failure"); null },
            startWifi = {}, stopWifi = {}, bleEnabled = { true }, disableBle = {}, currentNpub = { null }
        ))
        fun <T : Job> request(job: T): T = job.also { requests += it }
        suspend fun close() {
            val jobs = requests + ownerJob
            jobs.forEach { it.cancel() }
            var primary: Throwable? = null
            jobs.forEach {
                try { withTimeout(5_000) { it.join() } } catch (failure: Throwable) {
                    val original = primary
                    if (original == null) primary = failure else if (original !== failure) original.addSuppressed(failure)
                }
            }
            primary?.let { throw it }
        }
    }

    private suspend fun TestScope.withFixture(
        profile: Flow<Pair<Long, Boolean>>? = null,
        beforeClose: () -> Unit = {},
        block: suspend (Fixture) -> Unit
    ) {
        val f = Fixture(this, profile)
        var primary: Throwable? = null
        try { block(f) } catch (failure: Throwable) { primary = failure }
        finally {
            withContext(NonCancellable) {
                try { beforeClose() } catch (failure: Throwable) {
                    val original = primary
                    if (original == null) primary = failure else if (original !== failure) original.addSuppressed(failure)
                }
                try { f.close() } catch (failure: Throwable) {
                    val original = primary
                    if (original == null) primary = failure else if (original !== failure) original.addSuppressed(failure)
                }
            }
        }
        primary?.let { throw it }
    }

    private class InertMesh : MeshService {
        var erasures = 0
        override val myPeerID = "1122334455667788"
        override var delegate: MeshDelegate? = null
        override fun startServices() {}
        override fun stopServices() {}
        override fun sendMessage(content: String, mentions: List<String>) {}
        override fun sendPrivateMessage(content: String, recipientPeerID: String, recipientNickname: String, messageID: String?) {}
        override fun sendReadReceipt(messageID: String, recipientPeerID: String, readerNickname: String) {}
        override fun sendVerifyChallenge(peerID: String, noiseKeyHex: String, nonceA: ByteArray) {}
        override fun sendVerifyResponse(peerID: String, noiseKeyHex: String, nonceA: ByteArray) {}
        override fun sendBroadcastAnnounce() {}
        override fun sendAnnouncementToPeer(peerID: String) {}
        override fun getPeerNicknames() = emptyMap<String, String>()
        override fun getPeerRSSI() = emptyMap<String, Int>()
        override fun getActivePeerCount() = 0
        override fun hasEstablishedSession(peerID: String) = false
        override fun getSessionState(peerID: String) = NoiseSession.NoiseSessionState.Uninitialized
        override fun initiateNoiseHandshake(peerID: String) {}
        override fun getPeerFingerprint(peerID: String): String? = null
        override fun getPeerInfo(peerID: String): PeerInfo? = null
        override fun updatePeerInfo(peerID: String, nickname: String, noisePublicKey: ByteArray, signingPublicKey: ByteArray, isVerified: Boolean) = false
        override fun getIdentityFingerprint() = "synthetic-fingerprint"
        override fun getStaticNoisePublicKey(): ByteArray? = null
        override fun shouldShowEncryptionIcon(peerID: String) = false
        override fun getEncryptedPeers() = emptyList<String>()
        override fun getDeviceAddressForPeer(peerID: String): String? = null
        override fun getDeviceAddressToPeerMapping() = emptyMap<String, String>()
        override fun printDeviceAddressesForPeers() = ""
        override fun getDebugStatus() = "Synthetic transport"
        override fun clearAllInternalData() { erasures++ }
        override fun clearAllEncryptionData() { erasures++ }
    }

    private suspend fun finishRequest(request: Job) {
        var primary: Throwable? = null
        try { withTimeout(5_000) { request.join() } } catch (failure: Throwable) { primary = failure }
        finally {
            withContext(NonCancellable) {
                try { withTimeout(5_000) { request.cancelAndJoin() } } catch (failure: Throwable) {
                    val original = primary
                    if (original == null) primary = failure else if (original !== failure) original.addSuppressed(failure)
                }
            }
        }
        primary?.let { throw it }
    }

    private fun assertMissingProducers(report: PanicRetirementReport) {
        listOf(report.wifiController, report.holderConstructionAndCallbacks, report.gossipJobsAndWriters,
            report.transportCoreRadioAndHandlers, report.startupAndRemainingSharedKeyUsers).forEach {
            assertEquals(PanicRetirementGap.ProducerNotImplemented, (it as PanicOwnerRetirement.Unconfirmed).gap)
        }
    }
}
