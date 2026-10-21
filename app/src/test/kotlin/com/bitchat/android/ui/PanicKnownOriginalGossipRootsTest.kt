package com.bitchat.android.ui

import com.bitchat.android.mesh.MeshDelegate
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.mesh.PeerInfo
import com.bitchat.android.mesh.UnifiedMeshPorts
import com.bitchat.android.mesh.UnifiedMeshService
import com.bitchat.android.noise.NoiseSession
import com.bitchat.android.service.*
import com.bitchat.android.sync.*
import com.bitchat.android.wifiaware.WifiAwareFactoryResult
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Actual original-owner capture, manager admission and panic collector using owned RAM fixtures. */
@OptIn(ExperimentalCoroutinesApi::class)
class PanicKnownOriginalGossipRootsTest {
    @Test fun knownOriginalManagersRefuseAllThreeNewRoots() = runTest {
        withFixture { f ->
            val closedAtFacadeCompletion = AtomicReference<Boolean>()
            f.facadeJob.invokeOnCompletion { closedAtFacadeCompletion.set(f.isClosed(f.initial)) }
            f.fence()
            var erases = 0
            var report: PanicRetirementReport? = null
            val admitted = PanicResetCoordinator.admitDestructivePanicOwners(
                establishIntent = { true },
                retireOwners = { f.collect().also { report = it } },
                eraseOwnedData = { erases++; true }
            )
            f.joinJobs()
            f.assertHealthy()
            val actual = checkNotNull(report)
            assertSame(PanicOwnerRetirement.Confirmed, actual.facade)
            assertTrue(f.facadeJob.isCompleted)
            assertEquals(true, closedAtFacadeCompletion.get())
            val before = f.initial.launches.get()
            f.initial.manager.start()
            f.initial.manager.scheduleInitialSync(0)
            f.initial.manager.scheduleInitialSyncToPeer("2233445566778899", 0)
            assertEquals("All three new root families must refuse before creator effects", before, f.initial.launches.get())
            val evidence = known(actual)
            val close = evidence.attempts.single() as PanicGossipRootCloseAttempt.Closed
            assertSame(f.initial.manager, close.manager)
            assertSame(f.initial.manager.captureJobPanicBinding(), close.binding)
            val repeated = f.initial.manager.closeJobRootsForPanic(close.binding) as GossipManagerRootCloseOutcome.Closed
            assertSame(close.receipt, repeated.receipt)
            assertFalse(admitted)
            assertEquals(0, erases)
            assertMissingProofs(actual)
        }
    }

    @Test fun activeConstructorDoesNotDelayKnownManagerClosure() = runTest {
        withFixture { f ->
            val entered = f.gate()
            val release = f.gate()
            val late = AtomicReference<Managed>()
            val constructor = f.worker {
                created(f.holder.constructWifi(f.original, Any()) { context ->
                    late.set(f.child(context, HolderGossipSource.CORE_FALLBACK))
                    entered.countDown()
                    await(release, "owned original constructor release")
                    Any()
                })
            }
            await(entered, "actual manager return within held constructor")
            f.fence()
            val report = f.collect()
            val closedWhileHeld = f.isClosed(checkNotNull(late.get()))
            val unfinished = constructor.isAlive
            val observed = known(report)
            release.countDown()
            f.reap(constructor)
            f.joinJobs()
            f.assertHealthy()
            assertTrue("Known root closure cannot wait on the original constructor", closedWhileHeld)
            assertTrue(unfinished)
            assertEquals(1, observed.snapshot.holder.activeEntryCount)
            assertTrue(observed.snapshot.holder.wifiConstructions.single().outcome === HolderConstructionOutcome.Dispatched)
            assertTrue(f.holder.provenanceSnapshot().wifiConstructions.single().outcome is HolderConstructionOutcome.Returned)
            assertEquals(2, observed.attempts.size)
            assertMissingProofs(report)
        }
    }

    @Test fun unknownParentStillClosesKnownReturnedChild() = runTest {
        withFixture { f ->
            f.transport.reusable = false
            val failure = IllegalStateException("Synthetic post-manager constructor failure")
            val children = mutableListOf<Managed>()
            val result = runCatching {
                f.holder.getOrCreate { context ->
                    children += f.child(context, HolderGossipSource.BLE_SERVICE)
                    throw failure
                }
            }
            f.fence()
            val report = f.collect()
            f.joinJobs()
            f.assertHealthy()
            assertSame(failure, result.exceptionOrNull())
            assertEquals("The actual replacement catch retries its opaque constructor", 2, children.size)
            assertTrue(children.all { f.isClosed(it) })
            val evidence = known(report)
            assertEquals(2, evidence.snapshot.holder.constructions.count {
                it.kind == HolderConstructionKind.BLUETOOTH && it.outcome is HolderConstructionOutcome.Unknown
            })
            assertEquals(3, evidence.snapshot.children.size)
            assertEquals(3, evidence.attempts.size)
            assertMissingProofs(report)
        }
    }

    @Test fun registrationsAndCreatorUnknownRetainIndependentEvidence() = runTest {
        withFixture { f ->
            val external = f.manager()
            f.holder.setGossipManager(HolderManagerProvenance.UnattributedExternal, external.manager) { it }
            val failure = IllegalStateException("Synthetic unknown manager creator")
            val result = runCatching {
                f.holder.constructGossipManager(HolderGossipParent.Unattributed, HolderGossipSource.CORE_FALLBACK) {
                    throw failure
                }
            }
            val externalProduct = InertMesh()
            f.holder.attach(externalProduct)
            f.fence()
            val report = f.collect()
            f.joinJobs()
            f.assertHealthy()
            assertSame(failure, result.exceptionOrNull())
            assertTrue(f.isClosed(external))
            val evidence = known(report)
            assertSame(HolderManagerProvenance.UnattributedExternal, evidence.snapshot.holder.registrations.last().provenance)
            assertSame(externalProduct, evidence.snapshot.holder.externalProducts.single())
            assertTrue(evidence.snapshot.children.any { it.outcome === HolderGossipChildOutcome.Unknown })
            assertEquals(2, evidence.attempts.size)
            assertMissingProofs(report)
        }
    }

    @Test fun replacementsAndClearRetainEveryOriginalManager() = runTest {
        withFixture { f ->
            withFixture { unrelated ->
                f.transport.reusable = false
                val failure = IllegalStateException("Synthetic replacement log failure")
                f.expectedPortErrors += failure
                var rejectReplacementLog = true
                f.onLog = { _, message ->
                    if (message == "Created new BluetoothMeshService (replacement)" && rejectReplacementLog) {
                        rejectReplacementLog = false
                        throw failure
                    }
                }
                val replacementManagers = mutableListOf<Managed>()
                acquired(f.holder.getOrCreate { context ->
                    val manager = f.child(context, HolderGossipSource.BLE_SERVICE)
                    replacementManagers += manager
                    f.holder.setGossipManager(HolderManagerProvenance.Constructed(context), manager.manager) { it }
                    InertMesh()
                })
                f.holder.setGossipManager(HolderManagerProvenance.UnattributedExternal, replacementManagers.first().manager) { it }
                val registrations = f.holder.provenanceSnapshot().registrations
                f.holder.clear()
                f.fence()
                val report = f.collect()
                f.joinJobs()
                f.assertHealthy()
                unrelated.assertHealthy()
                assertEquals(2, replacementManagers.size)
                assertEquals(1, f.portErrors.count { it === failure })
                assertTrue(replacementManagers.all { f.isClosed(it) })
                assertTrue(f.isClosed(f.initial))
                assertFalse(unrelated.isClosed(unrelated.initial))
                val evidence = known(report)
                assertEquals(4, evidence.snapshot.holder.registrations.size)
                registrations.zip(evidence.snapshot.holder.registrations).forEach { (before, after) -> assertSame(before, after) }
                assertEquals("Repeated registration preserves its row but closes one exact manager identity", 3, evidence.attempts.size)
                assertNull(f.holder.sharedGossipSyncManager)
                assertMissingProofs(report)
            }
        }
    }

    @Test fun selectedManagerAndKnownNullFallbackUseExactReferences() = runTest {
        withFixture { f ->
            lateinit var replacement: Managed
            lateinit var fallback: Managed
            lateinit var selected: HolderManagerRegistration<GossipSyncManager>
            created(f.holder.constructWifi(f.original, Any()) { context ->
                f.holder.ensureBluetoothForWifi(context) { throw AssertionError("Existing Bluetooth is reusable") }
                f.holder.clear()
                acquired(f.holder.getOrCreate { nested ->
                    replacement = f.child(nested, HolderGossipSource.BLE_SERVICE)
                    f.holder.setGossipManager(HolderManagerProvenance.Constructed(nested), replacement.manager) { it }
                    InertMesh()
                })
                selected = checkNotNull(f.holder.selectManagerForWifi(context))
                Any()
            })
            created(f.holder.constructWifi(f.original, Any()) { context ->
                f.holder.ensureBluetoothForWifi(context) { throw AssertionError("Replacement Bluetooth is reusable") }
                f.holder.clear()
                check(f.holder.selectManagerForWifi(context) == null)
                fallback = f.child(context, HolderGossipSource.CORE_FALLBACK)
                Any()
            })
            f.fence()
            val report = f.collect()
            f.joinJobs()
            f.assertHealthy()
            val evidence = known(report)
            val selections = evidence.snapshot.holder.wifiConstructions.map { it.selection as HolderWifiManagerSelection.Selected<GossipSyncManager> }
            assertSame(selected, selections[0].registration)
            assertSame(replacement.manager, selected.manager)
            assertNull(selections[1].registration)
            assertTrue(listOf(f.initial, replacement, fallback).all { f.isClosed(it) })
            assertEquals(3, evidence.attempts.size)
            assertEquals(HolderGossipSource.CORE_FALLBACK, evidence.snapshot.children.last().source)
            assertMissingProofs(report)
        }
    }

    @Test fun missingForeignAndMismatchedBindingsStayUnconfirmed() = runTest {
        withFixture { a ->
            withFixture { b ->
                a.fence()
                val mismatch = collectPanicRetirement(b.facade, PanicOriginalGossipInput.Installed(a.binding, a.facade))
                assertEquals(PanicRetirementGap.InstalledFacadeMismatch, (mismatch.facade as PanicOwnerRetirement.Unconfirmed).gap)
                assertTrue(a.facadeJob.isActive)
                assertTrue(b.facadeJob.isActive)
                assertFalse(a.isClosed(a.initial))
                assertFalse(b.isClosed(b.initial))
                val missing = collectPanicRetirement(null, a.input())
                assertEquals(PanicRetirementGap.InstalledFacadeMissing, (missing.facade as PanicOwnerRetirement.Unconfirmed).gap)
                assertFalse(a.isClosed(a.initial))
                val innerMismatch = collectPanicRetirement(b.facade, PanicOriginalGossipInput.Installed(a.binding, b.facade))
                assertEquals(HolderPanicCaptureGap.ProductMismatch,
                    (invalid(innerMismatch) as HolderGossipCaptureGap.InvalidSubject).gap)
                assertTrue(b.facadeJob.isActive)
                assertFalse(a.isClosed(a.initial))
                assertFalse(b.isClosed(b.initial))
                val subject = (a.holder.captureUnifiedPanicSubject(a.facade) as HolderPanicSubjectCapture.Captured).subject
                val foreign = b.holder.observeOriginalGossipSubset(subject, a.facade) as HolderGossipSubsetCapture.Unconfirmed
                assertEquals(HolderPanicCaptureGap.ForeignSubject, (foreign.gap as HolderGossipCaptureGap.InvalidSubject).gap)
                val foreignBinding = a.initial.manager.captureJobPanicBinding()
                assertSame(GossipManagerRootCloseOutcome.ForeignBinding, b.initial.manager.closeJobRootsForPanic(foreignBinding))
                assertFalse(b.isClosed(b.initial))
                val ownClose = b.initial.manager.closeJobRootsForPanic(b.initial.manager.captureJobPanicBinding())
                assertTrue(ownClose is GossipManagerRootCloseOutcome.Closed)
                assertTrue(b.isClosed(b.initial))
                a.assertHealthy()
                b.assertHealthy()
            }
        }
        withFixture { f ->
            val report = f.collect()
            assertSame(HolderGossipCaptureGap.NotClosed, invalid(report))
            assertTrue(f.facadeJob.isActive)
            assertFalse(f.isClosed(f.initial))
        }
        withFixture { f ->
            val missingCollector = captureHolderPanicBinding(f.holder, f.facade)
            f.fence()
            val report = collectPanicRetirement(f.facade, PanicOriginalGossipInput.Installed(missingCollector, f.facade))
            assertSame(HolderGossipCaptureGap.MissingCollector, invalid(report))
            assertTrue(f.facadeJob.isActive)
            assertFalse(f.isClosed(f.initial))
            val old = collectPanicRetirement(f.facade, PanicOriginalGossipInput.MissingCapturedHolder)
            assertSame(PanicOwnerRetirement.Confirmed, old.facade)
            assertTrue(f.facadeJob.isCompleted)
            assertFalse(f.isClosed(f.initial))
        }
        withFixture { f ->
            val absent = captureHolderPanicBindingWithGossip(f.holder, Any())
            val report = collectPanicRetirement(f.facade, PanicOriginalGossipInput.Installed(absent, f.facade))
            assertEquals(HolderPanicCaptureGap.NoUniqueReturnedUnified,
                (invalid(report) as HolderGossipCaptureGap.InvalidSubject).gap)
            assertTrue(f.facadeJob.isActive)
            assertFalse(f.isClosed(f.initial))
            f.holder.clear()
            assertSame(f.facade, acquired(f.holder.getUnifiedOrCreate({ InertMesh() }) { f.facade }))
            val ambiguous = captureHolderPanicBindingWithGossip(f.holder, f.facade)
            val ambiguousReport = collectPanicRetirement(f.facade, PanicOriginalGossipInput.Installed(ambiguous, f.facade))
            assertEquals(HolderPanicCaptureGap.AmbiguousReturnedUnified,
                (invalid(ambiguousReport) as HolderGossipCaptureGap.InvalidSubject).gap)
            assertTrue(f.facadeJob.isActive)
            assertFalse(f.isClosed(f.initial))
        }
    }

    @Test fun completedContextCannotPublishNewManagerAfterPanicClose() = runTest {
        var retained: Fixture? = null
        var primary: Throwable? = null
        var postconditions: (() -> Unit)? = null
        try {
            withFixture { f ->
                retained = f
                check(f.holder.provenanceSnapshot().constructions.any {
                    it.context === f.bluetoothContext && it.outcome is HolderConstructionOutcome.Returned
                }) { "Actual original BLE constructor must have returned" }
                f.fence()
                val firstReport = f.collect()
                f.joinJobs()
                check(f.facadeJob.isCompleted) { "Actual original facade retirement must finish" }
                val before = f.holder.gossipConstructionSnapshot()
                var creatorCalls = 0
                val requested = runCatching {
                    f.holder.constructGossipManager(HolderGossipParent.Constructed(f.bluetoothContext), HolderGossipSource.BLE_SERVICE) {
                        creatorCalls++
                        f.manager().manager
                    }
                }
                val count = creatorCalls
                val after = f.holder.gossipConstructionSnapshot()
                f.assertHealthy()
                postconditions = {
                    assertEquals("Ended original context must refuse before creator effects", 0, count)
                    val refused = requested.exceptionOrNull() as HolderContextOperationRefusal
                    assertEquals(HolderContextOperationKind.ManagerCreator, refused.operation)
                    assertEquals(HolderContextOperationGap.EndedContextAfterClose, refused.gap)
                    assertEquals(before.size, after.size)
                    assertSame(before.single().ticket, after.single().ticket)
                    assertMissingProofs(firstReport)
                }
            }
        } catch (failure: Throwable) { primary = failure }
        finally {
            try { retained?.reapOwnedForCompletedContextContract() } catch (failure: Throwable) {
                val original = primary
                if (original == null) primary = failure else if (original !== failure) original.addSuppressed(failure)
            }
        }
        primary?.let { throw it }
        checkNotNull(postconditions).invoke()
    }

    private class Managed(val manager: GossipSyncManager, val work: GossipWorkOwner, val launches: AtomicInteger)

    private class Fixture(scope: TestScope) {
        val facadeJob = SupervisorJob()
        private val gossipJob = SupervisorJob()
        private val dispatcher = StandardTestDispatcher(scope.testScheduler)
        private val gossipScope = CoroutineScope(gossipJob + dispatcher)
        private val jobs = CopyOnWriteArrayList<Job>()
        private val managers = CopyOnWriteArrayList<Managed>()
        private val workers = CopyOnWriteArrayList<Thread>()
        private val gates = CopyOnWriteArrayList<CountDownLatch>()
        private val workerErrors = CopyOnWriteArrayList<Throwable>()
        val portErrors = CopyOnWriteArrayList<Throwable>()
        val expectedPortErrors = CopyOnWriteArrayList<Throwable>()
        @Volatile var onLog: ((HolderLogLevel, String) -> Unit)? = null
        val transport = InertMesh()
        val holder = MeshServiceHolderOwner(MeshServiceHolderPorts<InertMesh, Any, GossipSyncManager>(
            isReusable = { it.reusable }, stopBluetooth = {}, refreshUnified = {}, startGossip = {},
            stopGossip = { it.stop() }, clearGossip = {},
            installGossipDelegate = { manager, delegate -> manager.delegate = delegate },
            broadcast = {}, sendToPeer = { _, _ -> },
            log = { level, message ->
                try { onLog?.invoke(level, message) } catch (failure: Throwable) { portErrors += failure; throw failure }
            }
        ))
        val original = holder.initialProcessPreparation().origin
        lateinit var bluetoothContext: HolderConstructionContext
            private set
        lateinit var initial: Managed
            private set
        val facade = acquired(holder.getUnifiedOrCreate({ context ->
            bluetoothContext = context
            initial = child(context, HolderGossipSource.BLE_SERVICE)
            holder.setGossipManager(HolderManagerProvenance.Constructed(context), initial.manager) { it }
            transport
        }) { bluetooth ->
            UnifiedMeshService(bluetooth, UnifiedMeshPorts(
                scope = CoroutineScope(facadeJob + dispatcher), announcementProfile = MutableStateFlow(60_000L to false),
                hasAnnouncementRecipients = { false }, currentWifi = { null }, startWifi = {}, stopWifi = {},
                bleEnabled = { true }, disableBle = {}, currentNpub = { null }
            ))
        }) as UnifiedMeshService
        val binding = captureHolderPanicBindingWithGossip(holder, facade)

        fun manager(): Managed {
            val work = GossipWorkOwner()
            val launches = AtomicInteger()
            val actual = GossipSyncManager("1122334455667788", gossipScope, object : GossipSyncManager.ConfigProvider {
                override fun seenCapacity() = 8
                override fun gcsMaxBytes() = 128
                override fun gcsTargetFpr() = 0.01
            }, workOwner = work, jobCreator = { ownerScope, _, _ ->
                launches.incrementAndGet()
                Job(ownerScope.coroutineContext[Job]).apply { complete(); jobs += this }
            })
            return Managed(actual, work, launches).also { managers += it }
        }

        fun child(context: HolderConstructionContext, source: HolderGossipSource): Managed {
            lateinit var returned: Managed
            val actual = holder.constructGossipManager(HolderGossipParent.Constructed(context), source) {
                manager().also { returned = it }.manager
            }
            check(actual === returned.manager)
            return returned
        }

        fun input() = PanicOriginalGossipInput.Installed(binding, facade)
        suspend fun collect() = collectPanicRetirement(facade, input())
        fun fence() {
            check(PanicResetCoordinator.fenceCapturedPanicOwners(facade, binding, facade) === HolderConstructionCloseOutcome.ClosedForConstruction)
        }

        fun isClosed(managed: Managed): Boolean = when (val admission = managed.work.reserveRoot(
            managed.manager.captureJobPanicBinding(), GossipRootKind.INITIAL_BROADCAST
        )) {
            is GossipRootReservation.Refused -> true
            is GossipRootReservation.Accepted -> { managed.work.finishRoot(admission.lease); false }
        }

        fun gate() = CountDownLatch(1).also { gates += it }
        fun worker(action: () -> Unit): Thread = Thread {
            try { action() } catch (failure: Throwable) { workerErrors += failure }
        }.apply { isDaemon = true; workers += this; start() }

        fun reap(thread: Thread) {
            thread.join(WAIT_MS)
            assertFalse("Owned worker exceeded its completion bound", thread.isAlive)
            assertHealthy()
        }

        suspend fun joinJobs() { jobs.forEach { withTimeout(WAIT_MS) { it.join() } } }

        fun assertHealthy() {
            val errors = workerErrors.toList() + portErrors.filter { actual -> expectedPortErrors.none { it === actual } }
            if (errors.isNotEmpty()) {
                val failure = AssertionError("Unexpected owned fixture failure")
                failure.initCause(errors.first())
                errors.drop(1).forEach { if (it !== failure && it !== failure.cause) failure.addSuppressed(it) }
                throw failure
            }
        }

        suspend fun reapOwnedForCompletedContextContract() = withContext(NonCancellable) {
            var primary: Throwable? = null
            fun cleanup(action: () -> Unit) {
                try { action() } catch (failure: Throwable) {
                    val original = primary
                    if (original == null) primary = failure else if (original !== failure) original.addSuppressed(failure)
                }
            }
            gates.forEach { gate -> cleanup { gate.countDown() } }
            workers.forEach { worker ->
                cleanup {
                    var boundedFailure: Throwable? = null
                    try { worker.join(WAIT_MS) } catch (failure: Throwable) { boundedFailure = failure }
                    if (worker.isAlive) {
                        val guard = boundedFailure ?: AssertionError("Owned worker exceeded its first completion bound")
                        try { worker.interrupt(); worker.join(WAIT_MS); assertFalse("Owned worker must be reaped", worker.isAlive) }
                        catch (failure: Throwable) { if (guard !== failure) guard.addSuppressed(failure) }
                        boundedFailure = guard
                    }
                    boundedFailure?.let { throw it }
                }
                while (true) {
                    try { worker.join(); break } catch (failure: Throwable) { cleanup { throw failure } }
                }
            }
            val allJobs = jobs.toList() + gossipJob + facadeJob
            allJobs.forEach { job -> cleanup { job.cancel() } }
            allJobs.forEach { job ->
                try { withTimeout(WAIT_MS) { job.join() } } catch (failure: Throwable) {
                    val original = primary
                    if (original == null) primary = failure else if (original !== failure) original.addSuppressed(failure)
                }
                cleanup { job.cancel() }
                while (true) {
                    try { job.join(); break } catch (failure: Throwable) { cleanup { throw failure } }
                }
            }
            cleanup { assertHealthy() }
            primary?.let { throw it }
        }

        suspend fun close() = withContext(NonCancellable) {
            var primary: Throwable? = null
            fun cleanup(action: () -> Unit) {
                try { action() } catch (failure: Throwable) {
                    val original = primary
                    if (original == null) primary = failure else if (original !== failure) original.addSuppressed(failure)
                }
            }
            gates.forEach { it.countDown() }
            workers.forEach { thread ->
                cleanup {
                    var boundedFailure: Throwable? = null
                    try { thread.join(WAIT_MS) } catch (failure: Throwable) { boundedFailure = failure }
                    if (thread.isAlive) {
                        val original = boundedFailure ?: AssertionError("Owned worker exceeded its first completion bound")
                        try { thread.interrupt(); thread.join(WAIT_MS); assertFalse("Owned worker must be reaped", thread.isAlive) }
                        catch (failure: Throwable) { if (original !== failure) original.addSuppressed(failure) }
                        throw original
                    }
                    boundedFailure?.let { throw it }
                }
            }
            managers.forEach { cleanup { it.manager.stop(); it.manager.delegate = null } }
            val allJobs = jobs.toList() + gossipJob + facadeJob
            allJobs.forEach { cleanup { it.cancel() } }
            allJobs.forEach { job ->
                try { withTimeout(WAIT_MS) { job.join() } } catch (failure: Throwable) {
                    val original = primary
                    if (original == null) primary = failure else if (original !== failure) original.addSuppressed(failure)
                }
            }
            cleanup { assertHealthy() }
            primary?.let { throw it }
        }
    }

    private suspend fun TestScope.withFixture(block: suspend (Fixture) -> Unit) {
        val f = Fixture(this)
        var primary: Throwable? = null
        try { block(f); f.assertHealthy() } catch (failure: Throwable) { primary = failure }
        finally {
            try { f.close() } catch (failure: Throwable) {
                val original = primary
                if (original == null) primary = failure else if (original !== failure) original.addSuppressed(failure)
            }
        }
        primary?.let { throw it }
    }

    private class InertMesh(var reusable: Boolean = true) : MeshService {
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


    companion object {
        private const val WAIT_MS = 5_000L
        private fun await(latch: CountDownLatch, reason: String) {
            if (!latch.await(WAIT_MS, TimeUnit.MILLISECONDS)) throw AssertionError("Timed out: $reason")
        }
        private fun <T : Any> acquired(result: HolderAcquisitionResult<T>): T = when (result) {
            is HolderAcquisitionResult.Available -> result.product
            HolderAcquisitionResult.RefusedBeforeAcquisition -> throw AssertionError("Expected ordinary acquisition")
        }
        private fun <T : Any> created(result: WifiAwareFactoryResult<T>): T = when (result) {
            is WifiAwareFactoryResult.Created -> result.value
            WifiAwareFactoryResult.RefusedBeforeAllocation -> throw AssertionError("Expected accepted original construction")
        }
        private fun known(report: PanicRetirementReport) =
            (report.gossipJobsAndWriters as PanicOwnerRetirement.Unconfirmed).evidence as PanicUnconfirmedEvidence.KnownOriginalGossip
        private fun invalid(report: PanicRetirementReport) =
            ((report.gossipJobsAndWriters as PanicOwnerRetirement.Unconfirmed).evidence as PanicUnconfirmedEvidence.InvalidOriginalGossip).gap
        private fun assertMissingProofs(report: PanicRetirementReport) {
            assertFalse(report.confirmed)
            listOf(report.wifiController, report.holderConstructionAndCallbacks, report.gossipJobsAndWriters,
                report.transportCoreRadioAndHandlers, report.startupAndRemainingSharedKeyUsers).forEach {
                assertEquals(PanicRetirementGap.ProducerNotImplemented, (it as PanicOwnerRetirement.Unconfirmed).gap)
            }
        }
    }
}
