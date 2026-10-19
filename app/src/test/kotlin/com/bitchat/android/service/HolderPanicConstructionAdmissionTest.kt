package com.bitchat.android.service

import com.bitchat.android.mesh.MeshDelegate
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.mesh.PeerInfo
import com.bitchat.android.mesh.UnifiedMeshPorts
import com.bitchat.android.mesh.UnifiedMeshService
import com.bitchat.android.noise.NoiseSession
import com.bitchat.android.sync.GossipSyncManager
import com.bitchat.android.ui.PanicOwnerRetirement
import com.bitchat.android.ui.PanicResetCoordinator
import com.bitchat.android.ui.PanicRetirementReport
import com.bitchat.android.ui.collectPanicRetirement
import com.bitchat.android.wifiaware.HolderInitialBinding
import com.bitchat.android.wifiaware.WifiAwareControllerOwner
import com.bitchat.android.wifiaware.WifiAwareControllerPorts
import com.bitchat.android.wifiaware.WifiAwareFactoryResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Actual Holder/Controller/facade/panic helper with owned RAM ports, never a platform graph. */
@OptIn(ExperimentalCoroutinesApi::class)
class HolderPanicConstructionAdmissionTest {
    @Test fun closeViaProductionPanicHelperRefusesControllerWifiDispatch() = runTest {
        withFixture { f ->
            f.facade.startServices()
            runCurrent()
            val entered = f.hold()
            val release = f.hold()
            f.prepare = {
                entered.countDown()
                await(release, "owned Controller preparation release")
                true
            }
            val worker = f.worker { f.controller.setEnabled(true) }
            await(entered, "actual Controller preparation admission")
            assertTrue(f.holder.captureUnifiedPanicSubject(f.facade) is HolderPanicSubjectCapture.Captured)
            assertClosed(PanicResetCoordinator.fenceCapturedPanicOwners(f.facade, f.binding, f.facade))
            assertTrue("The same actual facade must finish its owned retirement", withTimeout(5_000) { f.facade.retireForPanic() })
            assertTrue("The actual owned facade job must complete", f.facadeJob.isCompleted)
            release.countDown()
            f.reap(worker)
            assertTrue("Caught Controller failures are fixture failures: ${f.failures}", f.failures.isEmpty())

            // The only intended baseline failure, after actual closure and all owned prerequisites.
            assertEquals("Closed original Holder admission must refuse the Wi-Fi creator", 0, f.creators.get())
            assertSame(WifiAwareFactoryResult.RefusedBeforeAllocation, f.factoryResults.single())
            assertNull(f.controller.getService())
            assertTrue(f.started.isEmpty())
            assertTrue(f.published.isEmpty())
            assertTrue(f.holder.provenanceSnapshot().wifiConstructions.isEmpty())
        }
    }

    @Test fun captureRequiresExactlyOneReturnedUnifiedRecord() = runTest {
        withFixture { f ->
            assertTrue(f.holder.captureUnifiedPanicSubject(f.facade) is HolderPanicSubjectCapture.Captured)
            val wifi = createdValue(f.holder.constructWifi(f.original, Any()) { Wifi(it) })
            val external = InertMesh()
            f.holder.attach(external)
            listOf(Any(), f.transport, wifi, external).forEach {
                assertEquals(HolderPanicCaptureGap.NoUniqueReturnedUnified, captureGap(f.holder.captureUnifiedPanicSubject(it)))
            }
            f.holder.clear()
            val unfinished = Any()
            val failure = IllegalStateException("Synthetic opaque Unified construction failure")
            assertSame(failure, runCatching {
                f.holder.getUnifiedOrCreate({ InertMesh() }) {
                    assertEquals(HolderPanicCaptureGap.NoUniqueReturnedUnified,
                        captureGap(f.holder.captureUnifiedPanicSubject(unfinished)))
                    throw failure
                }
            }.exceptionOrNull())
            assertTrue(f.holder.provenanceSnapshot().constructions.any {
                it.kind == HolderConstructionKind.UNIFIED && it.outcome is HolderConstructionOutcome.Unknown
            })
            assertEquals(HolderPanicCaptureGap.NoUniqueReturnedUnified, captureGap(f.holder.captureUnifiedPanicSubject(unfinished)))
            f.holder.clear()
            assertSame(f.facade, acquiredValue(f.holder.getUnifiedOrCreate({ InertMesh() }) { f.facade }))
            assertEquals(HolderPanicCaptureGap.AmbiguousReturnedUnified, captureGap(f.holder.captureUnifiedPanicSubject(f.facade)))
            val ambiguous = captureHolderPanicBinding(f.holder, f.facade)
            assertEquals(HolderPanicCaptureGap.AmbiguousReturnedUnified,
                closeGap(PanicResetCoordinator.fenceCapturedPanicOwners(f.facade, ambiguous, f.facade)))
        }
    }

    @Test fun originalUnifiedSubjectSurvivesCacheReplacementAndClear() = runTest {
        withFixture { f ->
            val original = f.holder.provenanceSnapshot().constructions.single { it.kind == HolderConstructionKind.UNIFIED }
            f.holder.clear()
            val replacement = Any()
            assertSame(replacement, acquiredValue(f.holder.getUnifiedOrCreate({ InertMesh() }) { replacement }))
            assertClosed(PanicResetCoordinator.fenceCapturedPanicOwners(f.facade, f.binding, f.facade))
            assertSame(replacement, f.holder.unifiedMeshService)
            val retained = f.holder.provenanceSnapshot().constructions.single { it.context === original.context }
            assertSame(original.outcome, retained.outcome)
            assertSame(f.facade, (retained.outcome as HolderConstructionOutcome.Returned).product)
            assertTrue(f.holder.captureUnifiedPanicSubject(replacement) is HolderPanicSubjectCapture.Captured)
        }
    }

    @Test fun foreignActorAndMismatchedProductCannotClose() = runTest {
        withFixture { first ->
            withFixture { second ->
                val captured = first.holder.captureUnifiedPanicSubject(first.facade) as HolderPanicSubjectCapture.Captured
                assertEquals(HolderPanicCaptureGap.ForeignSubject,
                    closeGap(second.holder.closeConstructionAdmission(captured.subject, first.facade)))
                assertEquals(HolderPanicCaptureGap.InvalidReturnedUnified,
                    closeGap(first.holder.closeConstructionAdmission(captured.subject, second.facade)))
                assertEquals(HolderPanicCaptureGap.ProductMismatch,
                    closeGap(PanicResetCoordinator.fenceCapturedPanicOwners(first.facade, first.binding, second.facade)))
                val missing = captureHolderPanicBinding(first.holder, Any())
                assertEquals(HolderPanicCaptureGap.NoUniqueReturnedUnified,
                    closeGap(PanicResetCoordinator.fenceCapturedPanicOwners(null, missing, first.facade)))
                assertClosed(PanicResetCoordinator.fenceCapturedPanicOwners(first.facade, first.binding, first.facade))
                assertClosed(PanicResetCoordinator.fenceCapturedPanicOwners(second.facade, second.binding, second.facade))
            }
        }
    }

    @Test fun closeCompletesWhileLegacyFactoryIsHeld() = runTest {
        withFixture { f ->
            f.holder.clear()
            val entered = f.hold()
            val release = f.hold()
            val constructor = f.worker {
                f.holder.getOrCreate {
                    entered.countDown()
                    await(release, "owned legacy constructor release")
                    InertMesh()
                }
            }
            await(entered, "actual legacy constructor admission")
            val result = AtomicReference<HolderConstructionCloseOutcome>()
            val closer = f.worker {
                result.set(PanicResetCoordinator.fenceCapturedPanicOwners(f.facade, f.binding, f.facade))
            }
            f.reap(closer)
            assertClosed(checkNotNull(result.get()))
            assertEquals("Closure cannot wait for the held legacy constructor to finish", 1L, constructor.done.count)
            release.countDown()
            f.reap(constructor)
        }
    }

    @Test fun acceptedLateReturnedConstructionKeepsItsReceiptAfterClose() = runTest {
        withFixture { lateCompletion(it, fail = false) }
    }

    @Test fun acceptedLateUnknownConstructionKeepsItsDebtAfterClose() = runTest {
        withFixture { lateCompletion(it, fail = true) }
    }

    @Test fun repeatedCloseAndOrdinaryClearDoNotCreateFreshAdmission() = runTest {
        withFixture { f ->
            val preparation = f.holder.initialProcessPreparation()
            val before = f.holder.provenanceSnapshot().constructions
            assertClosed(PanicResetCoordinator.fenceCapturedPanicOwners(f.facade, f.binding, f.facade))
            f.holder.clear()
            // Raw acquisition is deliberately still unqualified; it cannot mint a new H or drain proof.
            f.holder.getUnifiedOrCreate({ InertMesh() }) { Any() }
            assertSame(preparation, f.holder.initialProcessPreparation())
            assertSame(f.original, preparation.origin)
            val after = f.holder.provenanceSnapshot().constructions
            before.forEach { old ->
                val retained = after.single { it.context === old.context }
                assertSame(old.entry, retained.entry)
                assertSame(old.outcome, retained.outcome)
            }
            after.forEach { assertSame(before.first().entry.epoch, it.entry.epoch) }
            assertClosed(PanicResetCoordinator.fenceCapturedPanicOwners(f.facade, f.binding, f.facade))
        }
    }

    @Test fun partialClosureCannotConfirmC0OrAdmitDestruction() = runTest {
        withFixture { f ->
            f.facade.startServices()
            runCurrent()
            assertClosed(PanicResetCoordinator.fenceCapturedPanicOwners(f.facade, f.binding, f.facade))
            var report: PanicRetirementReport? = null
            var erases = 0
            val admitted = PanicResetCoordinator.admitDestructivePanicOwners(
                establishIntent = { true },
                retireOwners = { collectPanicRetirement(f.facade).also { report = it } },
                eraseOwnedData = { erases++; true }
            )
            assertSame(PanicOwnerRetirement.Confirmed, checkNotNull(report).facade)
            assertTrue(f.facadeJob.isCompleted)
            assertTrue(checkNotNull(report).holderConstructionAndCallbacks is PanicOwnerRetirement.Unconfirmed)
            assertFalse(checkNotNull(report).confirmed)
            assertFalse(admitted)
            assertEquals(0, erases)
            assertEquals(0, f.transport.erasures)
        }
    }

    private fun lateCompletion(f: Fixture, fail: Boolean) {
        f.transport.reusable = false
        val entered = f.hold()
        val release = f.hold()
        val manager = Manager()
        val failure = IllegalStateException("Synthetic admitted Wi-Fi constructor failure")
        val outcome = AtomicReference<Result<WifiAwareFactoryResult<Wifi>>>()
        val token = Any()
        val worker = f.worker {
            outcome.set(runCatching {
                f.holder.constructWifi(f.original, token) { context ->
                    f.holder.ensureBluetoothForWifi(context) { nested ->
                        f.holder.setGossipManager(HolderManagerProvenance.Constructed(nested), manager) { it }
                        InertMesh()
                    }
                    val selected = f.holder.selectManagerForWifi(context)
                    entered.countDown()
                    await(release, "admitted Wi-Fi completion release")
                    if (fail) throw failure
                    Wifi(context, selected)
                }
            })
        }
        await(entered, "actual admitted constructor selection")
        val before = f.holder.provenanceSnapshot().wifiConstructions.single()
        assertSame(HolderConstructionOutcome.Dispatched, before.outcome)
        assertClosed(PanicResetCoordinator.fenceCapturedPanicOwners(f.facade, f.binding, f.facade))
        f.holder.clear()
        release.countDown()
        f.reap(worker)
        val after = f.holder.provenanceSnapshot()
        val receipt = after.wifiConstructions.single()
        assertSame(before.context, receipt.context)
        assertSame(before.entry, receipt.entry)
        assertSame(token, receipt.controllerOrigin)
        assertSame(before.selection, receipt.selection)
        val selected = (receipt.selection as HolderWifiManagerSelection.Selected<Manager>).registration
        assertSame(manager, checkNotNull(selected).manager)
        assertSame(selected, after.registrations.single())
        if (fail) {
            assertSame(failure, checkNotNull(outcome.get()).exceptionOrNull())
            assertEquals(HolderConstructionKind.WIFI, (receipt.outcome as HolderConstructionOutcome.Unknown).kind)
        } else {
            val product = createdValue(checkNotNull(outcome.get()).getOrThrow())
            assertSame(product, (receipt.outcome as HolderConstructionOutcome.Returned).product)
            assertSame(selected, product.registration)
        }
        assertNull(f.holder.meshService)
        assertNull(f.holder.unifiedMeshService)
        assertNull(f.holder.sharedGossipSyncManager)
        assertEquals(1, manager.clears)
        assertEquals(1, manager.stops)
        assertEquals(0, after.activeEntryCount)
    }

    private class Wifi(val context: HolderWifiConstructionContext, val registration: HolderManagerRegistration<Manager>? = null) {
        @Volatile var running = false
    }
    private class Manager {
        var delegate: GossipSyncManager.Delegate? = null
        var clears = 0
        var stops = 0
    }
    private class Worker(val thread: Thread, val done: CountDownLatch)

    private class Fixture(scope: TestScope) {
        val facadeJob = SupervisorJob()
        private val controllerJob = SupervisorJob()
        val transport = InertMesh()
        val holder = MeshServiceHolderOwner(MeshServiceHolderPorts<Any, Any, Manager>(
            isReusable = { (it as? InertMesh)?.reusable != false }, stopBluetooth = {}, refreshUnified = {},
            startGossip = {}, stopGossip = { it.stops++ }, clearGossip = { it.clears++ },
            installGossipDelegate = { manager, delegate -> manager.delegate = delegate },
            broadcast = {}, sendToPeer = { _, _ -> }, log = { _, _ -> }
        ))
        val original = holder.initialProcessPreparation().origin
        val facade = acquiredValue(holder.getUnifiedOrCreate({ transport }) {
            UnifiedMeshService(transport, UnifiedMeshPorts(
                scope = CoroutineScope(facadeJob + StandardTestDispatcher(scope.testScheduler)),
                announcementProfile = MutableStateFlow(60_000L to false), hasAnnouncementRecipients = { false },
                currentWifi = { null }, startWifi = {}, stopWifi = {}, bleEnabled = { true }, disableBle = {}, currentNpub = { null }
            ))
        }) as UnifiedMeshService
        val binding = captureHolderPanicBinding(holder, facade)
        val creators = AtomicInteger()
        val factoryResults = CopyOnWriteArrayList<WifiAwareFactoryResult<Wifi>>()
        val started = CopyOnWriteArrayList<Wifi>()
        val published = CopyOnWriteArrayList<Pair<Wifi, HolderOrigin>>()
        val failures = ConcurrentLinkedQueue<Throwable>()
        private val workerErrors = ConcurrentLinkedQueue<Throwable>()
        private val workers = mutableListOf<Worker>()
        private val holds = mutableListOf<CountDownLatch>()
        @Volatile var prepare: () -> Boolean = { true }
        val controller = WifiAwareControllerOwner(HolderInitialBinding.Bound(original), WifiAwareControllerPorts<Wifi, HolderOrigin>(
            scope = CoroutineScope(controllerJob + StandardTestDispatcher(scope.testScheduler)),
            contextReady = { true }, prepareStart = { prepare() }, restartSupported = { true },
            factory = { token, captured ->
                holder.constructWifi(captured, token) { context -> creators.incrementAndGet(); Wifi(context) }
                    .also { factoryResults += it }
            },
            start = { it.running = true; started += it }, isRunning = { it.running }, requestStop = { it.running = false },
            awaitProductRetirement = { false }, published = { product, captured -> published += product to captured },
            clearPeers = {}, debugMessage = {}, failure = { failures += it }, restartDelayMs = 20, maxRestartAttempts = 3
        ))
        fun hold() = CountDownLatch(1).also { holds += it }
        fun worker(action: () -> Unit): Worker {
            val done = CountDownLatch(1)
            val thread = Thread({
                try { action() } catch (failure: Throwable) { workerErrors += failure }
                finally { done.countDown() }
            }, "synthetic-panic-construction-worker").apply { isDaemon = true }
            return Worker(thread, done).also { workers += it; thread.start() }
        }
        fun reap(worker: Worker) {
            worker.thread.join(5_000)
            if (worker.thread.isAlive) { worker.thread.interrupt(); worker.thread.join(5_000) }
            assertFalse("Owned worker must terminate", worker.thread.isAlive)
            assertEquals("Owned worker must complete its finally", 0L, worker.done.count)
            assertTrue("Owned worker failures are fixture errors: $workerErrors", workerErrors.isEmpty())
        }
        suspend fun close() = withContext(NonCancellable) {
            var primary: Throwable? = null
            fun remember(failure: Throwable) {
                val original = primary
                if (original == null) primary = failure else if (original !== failure) original.addSuppressed(failure)
            }
            holds.forEach { it.countDown() }
            workers.forEach { try { reap(it) } catch (failure: Throwable) { remember(failure) } }
            try { withTimeout(5_000) { controller.retireForPanic() } } catch (failure: Throwable) { remember(failure) }
            listOf(controllerJob, facadeJob).forEach {
                try { withTimeout(5_000) { it.cancelAndJoin() } } catch (failure: Throwable) { remember(failure) }
            }
            primary?.let { throw it }
        }
    }

    private suspend fun TestScope.withFixture(block: suspend (Fixture) -> Unit) {
        val f = Fixture(this)
        var primary: Throwable? = null
        try {
            block(f)
            assertTrue("Successful controls cannot hide caught Controller failures: ${f.failures}", f.failures.isEmpty())
        } catch (failure: Throwable) { primary = failure }
        finally {
            try { f.close() } catch (failure: Throwable) {
                val original = primary
                if (original == null) primary = failure else if (original !== failure) original.addSuppressed(failure)
            }
        }
        primary?.let { throw it }
    }

    private class InertMesh : MeshService {
        var reusable = true
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

    private fun await(latch: CountDownLatch, description: String) {
        check(latch.await(5, TimeUnit.SECONDS)) { "Missing $description" }
    }
    private fun assertClosed(outcome: HolderConstructionCloseOutcome) =
        assertSame(HolderConstructionCloseOutcome.ClosedForConstruction, outcome)
    private fun captureGap(captured: HolderPanicSubjectCapture) = (captured as HolderPanicSubjectCapture.Unconfirmed).gap
    private fun closeGap(outcome: HolderConstructionCloseOutcome) = (outcome as HolderConstructionCloseOutcome.Unconfirmed).gap
    private fun <T : Any> createdValue(result: WifiAwareFactoryResult<T>): T = when (result) {
        is WifiAwareFactoryResult.Created -> result.value
        WifiAwareFactoryResult.RefusedBeforeAllocation -> error("Expected a pre-closure admitted Created product")
    }
    private fun <T : Any> acquiredValue(result: HolderAcquisitionResult<T>): T = when (result) {
        is HolderAcquisitionResult.Available -> result.product
        HolderAcquisitionResult.RefusedBeforeAcquisition -> error("Expected an available synthetic acquisition")
    }

}
