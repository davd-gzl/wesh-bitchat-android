package com.bitchat.android.service

import com.bitchat.android.mesh.MeshDelegate
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.mesh.PeerInfo
import com.bitchat.android.mesh.UnifiedMeshPorts
import com.bitchat.android.mesh.UnifiedMeshService
import com.bitchat.android.noise.NoiseSession
import com.bitchat.android.startup.StartupCoordinator
import com.bitchat.android.ui.PanicResetCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Actual raw owner and production action boundaries with owned RAM dependencies only. */
@OptIn(ExperimentalCoroutinesApi::class)
class HolderRawAcquisitionAdmissionTest {
    @Test fun newBluetoothAcquisitionAfterCloseInvokesNoLegacyPortsOrCreator() = runTest {
        withFixture { newAcquisitionAfterClose(it, unified = false) }
    }

    @Test fun newUnifiedAcquisitionAfterCloseInvokesNoLegacyPortsOrCreators() = runTest {
        withFixture { newAcquisitionAfterClose(it, unified = true) }
    }

    @Test fun cachedBluetoothAcquisitionAfterCloseInvokesNoReuseOrLogPorts() = runTest {
        withFixture { cachedAcquisitionAfterClose(it, unified = false) }
    }

    @Test fun cachedUnifiedAcquisitionAfterCloseInvokesNoReuseLogOrRefreshPorts() = runTest {
        withFixture { cachedAcquisitionAfterClose(it, unified = true) }
    }

    @Test fun acceptedRawWaiterReservationIsVisibleBeforeLegacyMonitorAcquisition() = runTest {
        withFixture { f ->
            f.holder.clear()
            val entered = f.hold()
            val release = f.hold()
            val product = Transport()
            val firstResult = AtomicReference<HolderAcquisitionResult<Transport>>()
            val secondResult = AtomicReference<HolderAcquisitionResult<Transport>>()
            val first = f.worker {
                firstResult.set(f.holder.getOrCreate { entered.countDown(); await(release); product })
            }
            await(entered)
            val secondAttempted = f.hold()
            val second = f.worker {
                secondAttempted.countDown()
                secondResult.set(f.holder.getOrCreate { error("Accepted waiter must reuse the first product") })
            }
            await(secondAttempted)
            assertBlockedBy(second.thread, first.thread)
            assertEquals(2, f.observe { f.holder.provenanceSnapshot().activeEntryCount })
            f.closeAdmission()
            f.settleFacade()
            release.countDown()
            f.reap(first)
            f.reap(second)
            assertSame(product, acquired(firstResult.get()))
            assertSame(product, acquired(secondResult.get()))
            assertEquals(0, f.holder.provenanceSnapshot().activeEntryCount)
        }
    }

    @Test fun acceptedRawLateReturnRetainsOriginalEntryAfterClose() = runTest {
        withFixture { lateCompletion(it, fail = false) }
    }

    @Test fun acceptedRawLateUnknownRetainsOriginalEntryAfterClose() = runTest {
        withFixture { lateCompletion(it, fail = true) }
    }

    @Test fun acceptedLogFailureRetryRetainsBothConstructionDebtsAfterClose() = runTest {
        withFixture { f ->
            f.transport.reusable = false
            val first = Transport()
            val second = Transport()
            val calls = AtomicInteger()
            val once = AtomicBoolean()
            f.logHook = { _, message ->
                if (message == "Created new BluetoothMeshService (replacement)" && once.compareAndSet(false, true)) {
                    f.closeAdmission()
                    throw IllegalStateException("Synthetic accepted log failure")
                }
            }
            val result = f.holder.getOrCreate { if (calls.incrementAndGet() == 1) first else second }
            f.settleFacade()
            assertSame(second, acquired(result))
            assertEquals(2, calls.get())
            val records = f.holder.provenanceSnapshot().constructions.filter {
                it.kind == HolderConstructionKind.BLUETOOTH
            }.takeLast(2)
            assertSame(first, (records[0].outcome as HolderConstructionOutcome.Returned).product)
            assertSame(second, (records[1].outcome as HolderConstructionOutcome.Returned).product)
            assertSame(records[0].entry, records[1].entry)
            assertSame(HolderEntryProvenance.LegacyUnattributed, records[0].entry.provenance)
            assertEquals(0, f.holder.provenanceSnapshot().activeEntryCount)
        }
    }

    @Test fun repeatedCloseAndClearRetainOriginalPreparationAndReceipts() = runTest {
        withFixture { f ->
            val preparation = f.holder.initialProcessPreparation()
            val before = f.holder.provenanceSnapshot().constructions
            f.closeAdmission()
            f.holder.clear()
            f.closeAdmission()
            assertSame(preparation, f.holder.initialProcessPreparation())
            val after = f.holder.provenanceSnapshot().constructions
            assertEquals(before.size, after.size)
            before.zip(after).forEach { (old, retained) ->
                assertSame(old.context, retained.context)
                assertSame(old.entry, retained.entry)
                assertSame(old.outcome, retained.outcome)
            }
        }
    }

    @Test fun pairFirstRefusalInvokesOnlyRefusalAction() = runTest {
        withFixture { f ->
            var seconds = 0
            var publications = 0
            val refusals = mutableListOf<HolderPairRefusal<Transport>>()
            val result = makeHolderRuntimePairAction<Transport, UnifiedMeshService>(
                acquireBluetooth = { HolderAcquisitionResult.RefusedBeforeAcquisition },
                acquireUnified = { seconds++; f.holder.getUnifiedOrCreate({ error("Must not allocate") }) { f.facade } },
                available = { _, _ -> publications++ },
                refused = { refusals += it }
            )()
            assertFalse(result)
            assertEquals(0, seconds)
            assertEquals(0, publications)
            assertSame(HolderPairRefusal.BeforeFirst, refusals.single())
        }
    }

    @Test fun pairSecondRefusalRetainsExactFirstDebtWithoutPublishing() = runTest {
        withFixture { f ->
            var publications = 0
            val refusals = mutableListOf<HolderPairRefusal<Transport>>()
            val result = makeHolderRuntimePairAction<Transport, UnifiedMeshService>(
                acquireBluetooth = { f.holder.getOrCreate { error("Must reuse the actual first product") } },
                // Caller handling uses an explicit injected refusal; the raw baseline ignores closure.
                acquireUnified = { HolderAcquisitionResult.RefusedBeforeAcquisition },
                available = { _, _ -> publications++ },
                refused = { refusals += it }
            )()
            assertFalse(result)
            assertEquals(0, publications)
            assertSame(f.transport, (refusals.single() as HolderPairRefusal.AfterFirst<Transport>).product)
            assertSame(f.transport, f.holder.meshService)
            assertTrue(f.holder.provenanceSnapshot().constructions.any {
                (it.outcome as? HolderConstructionOutcome.Returned)?.product === f.transport
            })
        }
    }

    @Test fun pairSuccessPublishesBothProductsExactlyOnce() = runTest {
        withFixture { f ->
            val published = mutableListOf<Pair<Transport, UnifiedMeshService>>()
            val result = makeHolderRuntimePairAction(
                acquireBluetooth = { f.holder.getOrCreate { error("Must reuse Bluetooth") } },
                acquireUnified = { f.holder.getUnifiedOrCreate({ error("Must reuse Bluetooth") }) { error("Must reuse Unified") } },
                available = { bluetooth, unified -> published += bluetooth to unified },
                refused = { error("Open actual owner must not refuse") }
            )()
            assertTrue(result)
            assertSame(f.transport, published.single().first)
            assertSame(f.facade, published.single().second)
        }
    }

    @Test fun startupRefusalFailsActualCoordinatorInsteadOfReturningUnitReady() = runTest {
        withFixture { f ->
            var recoveries = 0
            val coordinator = StartupCoordinator<Unit>(f.auxScope) {
                dispatchHolderAcquisition<Transport, Unit>(
                    acquire = { HolderAcquisitionResult.RefusedBeforeAcquisition },
                    available = { error("Refused startup cannot return Unit success") },
                    refused = { recoveries++; error("Synthetic runtime unavailable") }
                )
            }
            coordinator.start()
            runCurrent()
            assertSame(StartupCoordinator.State.Failed, coordinator.state.value)
            assertEquals(1, coordinator.failures.value)
            assertEquals(1, recoveries)
        }
    }

    @Test fun foregroundRefusalInvokesRequiredStopActionWithoutStartingProduct() = runTest {
        withFixture {
            var starts = 0
            val stop = mutableListOf<String>()
            dispatchHolderAcquisition<Transport, Unit>(
                acquire = { HolderAcquisitionResult.RefusedBeforeAcquisition },
                available = { starts++ },
                refused = { stop += listOf("stopForeground", "foregroundFalse", "clearNotifications", "stopSelf") }
            )
            assertEquals(0, starts)
            assertEquals(listOf("stopForeground", "foregroundFalse", "clearNotifications", "stopSelf"), stop)
        }
    }

    @Test fun notificationRefusalPreservesActualCompletionFinallyWithoutReplyWrites() = runTest {
        withFixture {
            var replyActions = 0
            var finishes = 0
            completeHolderReceiverWork(
                body = {
                    dispatchHolderAcquisition<Transport, Unit>(
                        acquire = { HolderAcquisitionResult.RefusedBeforeAcquisition },
                        available = { replyActions += 4 },
                        refused = { }
                    )
                },
                finish = { finishes++ }
            )
            assertEquals(0, replyActions)
            assertEquals(1, finishes)
            val cancellation = CancellationException("Synthetic receiver cancellation")
            val actual = runCatching {
                completeHolderReceiverWork(body = { throw cancellation }, finish = { finishes++ })
            }.exceptionOrNull()
            assertSame(cancellation, actual)
            assertEquals(2, finishes)
        }
    }

    @Test fun attachedInitializerRechecksAfterHeldDelayAndAllowsOneCurrentContinuation() = runTest {
        withFixture { f ->
            var attached = true
            var entered = 0
            var finished = 0
            val initializer = makeAttachedHolderInitializer(
                isAttached = { attached },
                initialize = { entered++; delay(500); finished++ }
            )
            var firstResult: Boolean? = null
            val first = f.auxScope.launch { delay(1000); firstResult = initializer() }
            runCurrent()
            attached = false
            advanceTimeBy(1000)
            runCurrent()
            first.join()
            assertEquals(false, firstResult)
            assertEquals(0, entered)
            attached = true
            var secondResult: Boolean? = null
            val second = f.auxScope.launch { secondResult = initializer() }
            runCurrent()
            assertEquals(1, entered)
            assertEquals(0, finished)
            advanceTimeBy(500)
            runCurrent()
            second.join()
            assertEquals(true, secondResult)
            assertEquals(1, entered)
            assertEquals(1, finished)
        }
    }

    private suspend fun newAcquisitionAfterClose(f: Fixture, unified: Boolean) {
        f.holder.clear()
        val entered = f.hold()
        val release = f.hold()
        val accepted = Transport().also { it.reusable = false }
        val firstResult = AtomicReference<HolderAcquisitionResult<Transport>>()
        val first = f.worker {
            firstResult.set(f.holder.getOrCreate { entered.countDown(); await(release); accepted })
        }
        await(entered)
        f.closeAdmission()
        f.settleFacade()
        val forbiddenCreators = AtomicInteger()
        val attempted = f.hold()
        val secondResult = AtomicReference<HolderAcquisitionResult<*>>()
        val second = f.worker {
            attempted.countDown()
            secondResult.set(if (unified) {
                f.holder.getUnifiedOrCreate(
                    { forbiddenCreators.incrementAndGet(); Transport() },
                    { forbiddenCreators.incrementAndGet(); f.facade }
                )
            } else f.holder.getOrCreate { forbiddenCreators.incrementAndGet(); Transport() })
        }
        await(attempted)
        release.countDown()
        f.reap(first)
        f.reap(second)
        assertSame(accepted, acquired(firstResult.get()))
        assertTrue("All owned worker failures must be checked before policy", f.errors.isEmpty())
        assertEquals("Closed raw acquisition must not invoke a new creator", 0, forbiddenCreators.get())
        assertSame(HolderAcquisitionResult.RefusedBeforeAcquisition, secondResult.get())
        assertSame(accepted, f.holder.meshService)
        assertEquals(0, f.holder.provenanceSnapshot().activeEntryCount)
    }

    private suspend fun cachedAcquisitionAfterClose(f: Fixture, unified: Boolean) {
        f.closeAdmission()
        f.settleFacade()
        val mark = f.events.size
        val outcome = AtomicReference<HolderAcquisitionResult<*>>()
        val worker = f.worker {
            outcome.set(if (unified) {
                f.holder.getUnifiedOrCreate({ error("Cached Bluetooth cannot allocate") }) { error("Cached Unified cannot allocate") }
            } else f.holder.getOrCreate { error("Cached Bluetooth cannot allocate") })
        }
        f.reap(worker)
        assertTrue("All owned worker failures must be checked before policy", f.errors.isEmpty())
        assertEquals("Closed cached acquisition must invoke no reuse, log or refresh port", 0, f.events.size - mark)
        assertSame(HolderAcquisitionResult.RefusedBeforeAcquisition, outcome.get())
        assertSame(f.transport, f.holder.meshService)
        assertSame(f.facade, f.holder.unifiedMeshService)
    }

    private suspend fun lateCompletion(f: Fixture, fail: Boolean) {
        f.holder.clear()
        val entered = f.hold()
        val release = f.hold()
        val product = Transport()
        val failure = IllegalStateException("Synthetic accepted construction failure")
        val outcome = AtomicReference<Result<HolderAcquisitionResult<Transport>>>()
        val worker = f.worker {
            outcome.set(runCatching {
                f.holder.getOrCreate { entered.countDown(); await(release); if (fail) throw failure; product }
            })
        }
        await(entered)
        val before = f.holder.provenanceSnapshot().constructions.last()
        assertSame(HolderConstructionOutcome.Dispatched, before.outcome)
        f.closeAdmission()
        f.settleFacade()
        release.countDown()
        f.reap(worker)
        f.holder.clear()
        val after = f.holder.provenanceSnapshot().constructions.single { it.context === before.context }
        assertSame(before.entry, after.entry)
        if (fail) {
            assertSame(failure, checkNotNull(outcome.get()).exceptionOrNull())
            assertEquals(HolderConstructionKind.BLUETOOTH, (after.outcome as HolderConstructionOutcome.Unknown).kind)
        } else {
            assertSame(product, acquired(checkNotNull(outcome.get()).getOrThrow()))
            assertSame(product, (after.outcome as HolderConstructionOutcome.Returned).product)
        }
        assertEquals(0, f.holder.provenanceSnapshot().activeEntryCount)
    }

    private class Worker(val thread: Thread, val done: CountDownLatch)

    private class Fixture(scope: TestScope) {
        val facadeJob = SupervisorJob()
        private val auxiliaryJob = SupervisorJob()
        val auxScope = CoroutineScope(auxiliaryJob + StandardTestDispatcher(scope.testScheduler))
        val events = CopyOnWriteArrayList<String>()
        val errors = ConcurrentLinkedQueue<Throwable>()
        @Volatile var logHook: ((HolderLogLevel, String) -> Unit)? = null
        val transport = Transport()
        val holder = MeshServiceHolderOwner(MeshServiceHolderPorts<Transport, UnifiedMeshService, Any>(
            isReusable = { events += "reuse"; it.reusable },
            stopBluetooth = { events += "stop" }, refreshUnified = { events += "refresh" },
            startGossip = {}, stopGossip = {}, clearGossip = {}, installGossipDelegate = { _, _ -> },
            broadcast = {}, sendToPeer = { _, _ -> },
            log = { level, message -> events += "log"; logHook?.invoke(level, message) }
        ))
        val facade = when (val result = holder.getUnifiedOrCreate({ transport }) {
            UnifiedMeshService(it, UnifiedMeshPorts(
                scope = CoroutineScope(facadeJob + StandardTestDispatcher(scope.testScheduler)),
                announcementProfile = MutableStateFlow(60_000L to false), hasAnnouncementRecipients = { false },
                currentWifi = { null }, startWifi = {}, stopWifi = {}, bleEnabled = { true }, disableBle = {}, currentNpub = { null }
            ))
        }) {
            is HolderAcquisitionResult.Available -> result.product
            HolderAcquisitionResult.RefusedBeforeAcquisition -> error("Initial synthetic fixture must be available")
        }
        private val binding = captureHolderPanicBinding(holder, facade)
        private val holds = mutableListOf<CountDownLatch>()
        private val workers = mutableListOf<Worker>()
        fun hold() = CountDownLatch(1).also { holds += it }
        fun worker(action: () -> Unit): Worker {
            val done = CountDownLatch(1)
            val thread = Thread({
                try { action() } catch (failure: Throwable) { errors += failure }
                finally { done.countDown() }
            }, "synthetic-raw-acquisition-worker").apply { isDaemon = true }
            return Worker(thread, done).also { workers += it; thread.start() }
        }
        fun reap(worker: Worker) {
            worker.thread.join(5_000)
            if (worker.thread.isAlive) { worker.thread.interrupt(); worker.thread.join(5_000) }
            assertFalse("Owned worker must terminate", worker.thread.isAlive)
            assertEquals("Owned worker must finish its finally", 0L, worker.done.count)
            assertTrue("Unexpected owned worker errors are fixture failures: $errors", errors.isEmpty())
        }
        fun <T : Any> observe(action: () -> T): T {
            val result = AtomicReference<T>()
            val worker = worker { result.set(action()) }
            reap(worker)
            return checkNotNull(result.get())
        }
        fun closeAdmission() {
            assertSame(HolderConstructionCloseOutcome.ClosedForConstruction,
                PanicResetCoordinator.fenceCapturedPanicOwners(facade, binding, facade))
        }
        suspend fun settleFacade() {
            assertTrue("Same actual facade must finish its owned retirement", withTimeout(5_000) { facade.retireForPanic() })
            assertTrue("Actual owned facade job must complete", facadeJob.isCompleted)
        }
        suspend fun close() = withContext(NonCancellable) {
            var primary: Throwable? = null
            fun remember(failure: Throwable) {
                val original = primary
                if (original == null) primary = failure else if (original !== failure) original.addSuppressed(failure)
            }
            holds.forEach { it.countDown() }
            workers.forEach { try { reap(it) } catch (failure: Throwable) { remember(failure) } }
            try { settleFacade() } catch (failure: Throwable) { remember(failure) }
            listOf(auxiliaryJob, facadeJob).forEach {
                try { withTimeout(5_000) { it.cancelAndJoin() } } catch (failure: Throwable) { remember(failure) }
            }
            primary?.let { throw it }
        }
    }

    private suspend fun TestScope.withFixture(block: suspend (Fixture) -> Unit) {
        val f = Fixture(this)
        var primary: Throwable? = null
        try {
            f.facade.startServices()
            runCurrent()
            block(f)
            assertTrue("Unexpected worker errors cannot be passing controls: ${f.errors}", f.errors.isEmpty())
        } catch (failure: Throwable) { primary = failure }
        finally {
            try { f.close() } catch (failure: Throwable) {
                val original = primary
                if (original == null) primary = failure else if (original !== failure) original.addSuppressed(failure)
            }
        }
        primary?.let { throw it }
    }

    private class Transport : MeshService {
        @Volatile var reusable = true
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

    private fun await(latch: CountDownLatch) {
        check(latch.await(5, TimeUnit.SECONDS)) { "Owned synthetic barrier was not released" }
    }

    private fun assertBlockedBy(worker: Thread, owner: Thread) {
        val threads = ManagementFactory.getThreadMXBean()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var blocked = false
        while (!blocked && System.nanoTime() < deadline) {
            val info = threads.getThreadInfo(worker.id)
            blocked = info != null && info.threadState == Thread.State.BLOCKED && info.lockOwnerId == owner.id
            if (!blocked) Thread.yield()
        }
        assertTrue("Owned waiter must block on the owned legacy factory monitor", blocked)
    }

    private fun <T : Any> acquired(result: HolderAcquisitionResult<T>): T = when (result) {
        is HolderAcquisitionResult.Available -> result.product
        HolderAcquisitionResult.RefusedBeforeAcquisition -> error("Expected an accepted synthetic call")
    }
}
