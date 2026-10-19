package com.bitchat.android.sync

import android.app.Application
import android.os.Build
import com.bitchat.android.mesh.*
import com.bitchat.android.model.RequestSyncPacket
import com.bitchat.android.noise.NoiseSession
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.service.*
import com.bitchat.android.ui.*
import com.bitchat.android.util.AppConstants
import com.bitchat.android.wifiaware.WifiAwareFactoryResult
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual direct bodies, canonical GCS and original-owner collector; all transports and ports are inert. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class GossipDirectIngressAdmissionTest {
    @Test fun postClosePublicPacketInvokesNoProviderAndLeavesNoCachedMessage() = runTest {
        val post = withFixture { f ->
            val m = f.initial
            val announce = packet(MessageType.ANNOUNCE, 1)
            val message = packet(MessageType.MESSAGE, 2)
            m.manager.onPublicPacketSeen(announce)
            assertEquals("Actual announcement insertion reaches capacity provider", 1, m.capacityCalls.get())
            val request = emptyRequest()
            val entered = f.gate()
            val release = f.gate()
            m.sendHook = { _, sent -> if (sent.type == MessageType.ANNOUNCE.value) {
                entered.countDown(); await(release, "preaccepted response release")
            } }
            val response = f.worker { m.manager.handleRequestSync(PEER, request) }
            await(entered, "actual announcement response before close")
            val report = f.collect()
            val closed = direct(report, m)
            assertTrue("The actual response remains admitted at the close event",
                closed.snapshotAtClose.operations.any { it.kind == GossipDirectOperationKind.RespondRequestSync && it.state == GossipDirectBodyState.Admitted })
            val acceptedBefore = closed.snapshotAtClose.operations.size
            val before = m.capacityCalls.get()
            m.manager.onPublicPacketSeen(message)
            val providerDelta = m.capacityCalls.get() - before
            release.countDown()
            f.reap(response)
            val sent = m.sent.toList()
            val observed = m.snapshot()
            val signs = m.signCalls.get()
            val postconditions: () -> Unit = {
                assertEquals("Closed direct packet admission must precede the capacity provider", 0, providerDelta)
                assertFalse("The preaccepted response must not observe a newly refused write", sent.any { it.packet.payload === message.payload })
                assertEquals(acceptedBefore, observed.operations.size)
                assertEquals(GossipDirectDecision.Refused, observed.requests.last().decision)
                assertSame(closed.snapshotAtClose.close, observed.requests.last().close)
                assertEquals(0, signs)
            }
            postconditions
        }
        post()
    }

    @Test fun postCloseValidRequestSendsNoStoredPackets() = runTest {
        val post = withFixture { f ->
            val m = f.initial
            val original = packet(MessageType.MESSAGE, 3)
            m.manager.onPublicPacketSeen(original)
            val request = emptyRequest()
            m.manager.handleRequestSync(PEER, request)
            assertEquals("Nonempty actual cache and valid GCS must send before closure", 1, m.sent.size)
            assertPacketAlias(original, m.sent.single().packet)
            val report = f.collect()
            val closed = direct(report, m)
            val before = m.sent.size
            val acceptedBefore = m.snapshot().operations.size
            m.manager.handleRequestSync(PEER, request)
            val sendDelta = m.sent.size - before
            val observed = m.snapshot()
            val signs = m.signCalls.get()
            val postconditions: () -> Unit = {
                assertEquals("Closed direct request admission must precede remote sends", 0, sendDelta)
                assertEquals(acceptedBefore, observed.operations.size)
                assertEquals(GossipDirectOperationKind.RespondRequestSync, observed.requests.last().kind)
                assertEquals(GossipDirectDecision.Refused, observed.requests.last().decision)
                assertSame(closed.snapshotAtClose.close, observed.requests.last().close)
                assertEquals(0, signs)
            }
            postconditions
        }
        post()
    }

    @Test fun rootClosureAloneLeavesDirectIngressOpenAndDirectClosureIsStable() = runTest {
        val post = withFixture { f ->
            val m = f.initial
            val other = f.manager()
            val root = m.manager.closeJobRootsForPanic(m.binding) as GossipManagerRootCloseOutcome.Closed
            val otherRoot = other.manager.closeJobRootsForPanic(other.binding) as GossipManagerRootCloseOutcome.Closed
            m.manager.onPublicPacketSeen(packet(MessageType.MESSAGE, 4))
            m.manager.handleRequestSync(PEER, emptyRequest())
            val calls = m.capacityCalls.get()
            val sends = m.sent.size
            val before = m.snapshot()
            val foreignBinding = m.work.closeDirectIngress(other.binding, root.receipt)
            val foreignReceipt = m.work.closeDirectIngress(m.binding, otherRoot.receipt)
            val afterInvalid = m.snapshot()
            val first = m.manager.closeDirectIngressForPanic(m.binding, root.receipt) as GossipDirectCloseOutcome.Closed
            val repeated = m.manager.closeDirectIngressForPanic(m.binding, root.receipt) as GossipDirectCloseOutcome.Closed
            val otherSnapshot = other.snapshot()
            val postconditions: () -> Unit = {
                assertEquals(1, calls); assertEquals(1, sends)
                assertNull(before.close); assertNull(afterInvalid.close)
                assertSame(GossipDirectCloseOutcome.ForeignBinding, foreignBinding)
                assertSame(GossipDirectCloseOutcome.ForeignRootReceipt, foreignReceipt)
                assertSame(first.receipt, repeated.receipt)
                assertSame(first.snapshotAtClose.close, repeated.snapshotAtClose.close)
                assertNull(otherSnapshot.close)
            }
            postconditions
        }
        post()
    }

    @Test fun acceptedCacheInsertionFinishesAcrossDirectCloseWithoutMonitorInversion() = runTest {
        val post = withFixture { f ->
            val m = f.initial
            val announce = packet(MessageType.ANNOUNCE, 5)
            val message = packet(MessageType.MESSAGE, 6)
            m.manager.onPublicPacketSeen(announce)
            val responseEntered = f.gate(); val responseRelease = f.gate()
            m.sendHook = { _, sent -> if (sent.type == MessageType.ANNOUNCE.value) {
                responseEntered.countDown(); await(responseRelease, "accepted response release")
            } }
            val response = f.worker { m.manager.handleRequestSync(PEER, emptyRequest()) }
            await(responseEntered, "actual announcement before message snapshot")
            val providerEntered = f.gate(); val providerRelease = f.gate()
            m.capacityHook = { providerEntered.countDown(); await(providerRelease, "actual capacity release") }
            val insertion = f.worker { m.manager.onPublicPacketSeen(message) }
            await(providerEntered, "actual capacity provider under messages monitor")
            val report = f.collect()
            val closed = direct(report, m)
            val bothHeld = insertion.isAlive && response.isAlive
            val activeAtClose = closed.snapshotAtClose.operations.count { it.state == GossipDirectBodyState.Admitted }
            providerRelease.countDown(); f.reap(insertion)
            responseRelease.countDown(); f.reap(response)
            val sent = m.sent.toList()
            val observed = m.snapshot()
            val postconditions: () -> Unit = {
                assertTrue("Collector returned while both original operations were held", bothHeld)
                assertEquals(2, activeAtClose)
                assertTrue(sent.any { it.packet.payload === message.payload })
                assertTrue(observed.operations.all { it.state == GossipDirectBodyState.Returned })
                assertTrue(observed.faults.isEmpty())
                assertFalse(report.confirmed)
            }
            postconditions
        }
        post()
    }

    @Test fun acceptedResponsePreservesPerItemDelegateAndPacketAliasesAcrossDirectClose() = runTest {
        val post = withFixture { f ->
            val m = f.initial
            val announce = packet(MessageType.ANNOUNCE, 7)
            val message = packet(MessageType.MESSAGE, 8)
            m.manager.onPublicPacketSeen(announce); m.manager.onPublicPacketSeen(message)
            val entered = f.gate(); val release = f.gate()
            m.sendHook = { _, sent -> if (sent.type == MessageType.ANNOUNCE.value) {
                entered.countDown(); await(release, "delegate A release")
            } }
            val response = f.worker { m.manager.handleRequestSync(PEER, emptyRequest()) }
            await(entered, "actual first delegate send")
            f.collect()
            val second = CopyOnWriteArrayList<Sent>()
            m.manager.delegate = m.delegate(second, null)
            release.countDown(); f.reap(response)
            val firstSent = m.sent.toList(); val secondSent = second.toList()
            val signs = m.signCalls.get(); val observed = m.snapshot()
            val postconditions: () -> Unit = {
                assertEquals(1, firstSent.size); assertEquals(1, secondSent.size)
                assertPacketAlias(announce, firstSent.single().packet)
                assertPacketAlias(message, secondSent.single().packet)
                assertEquals(PEER, firstSent.single().peer); assertEquals(PEER, secondSent.single().peer)
                assertEquals(0, signs)
                assertTrue(observed.operations.all { it.state == GossipDirectBodyState.Returned })
            }
            postconditions
        }
        post()
    }

    @Test fun openAndFilteredEntriesPreserveBodyReturnAndActualEffects() = runTest {
        val post = withFixture { f ->
            val m = f.initial
            val announce = packet(MessageType.ANNOUNCE, 9)
            val message = packet(MessageType.MESSAGE, 10)
            m.manager.onPublicPacketSeen(announce); m.manager.onPublicPacketSeen(message)
            m.manager.onPublicPacketSeen(packet(MessageType.FRAGMENT, 11))
            m.manager.onPublicPacketSeen(packet(MessageType.MESSAGE, 12).copy(recipientID = ByteArray(8) { 3 }))
            m.manager.handleRequestSync(PEER, emptyRequest())
            val calls = m.capacityCalls.get(); val sent = m.sent.toList(); val snapshot = m.snapshot()
            val postconditions: () -> Unit = {
                assertEquals(2, calls); assertEquals(2, sent.size)
                assertPacketAlias(announce, sent.first().packet); assertPacketAlias(message, sent.last().packet)
                assertEquals(5, snapshot.operations.size)
                assertTrue(snapshot.operations.all { it.state == GossipDirectBodyState.Returned && it.failure == null })
                assertTrue(snapshot.faults.isEmpty()); assertNull(snapshot.close)
            }
            postconditions
        }
        post()
    }

    @Test fun providerAndSendFailuresSettleThrownAndRethrowOriginalInstances() = runTest {
        val failures = listOf(IllegalStateException("Synthetic direct failure"),
            CancellationException("Synthetic direct cancellation"), AssertionError("Synthetic direct throwable"))
        failures.forEach { sentinel ->
            val post = withFixture { f ->
                val m = f.initial
                val message = packet(MessageType.MESSAGE, 13)
                m.capacityHook = { throw sentinel }
                val providerFailure = runCatching { m.manager.onPublicPacketSeen(message) }.exceptionOrNull()
                assertSame("Expected synthetic provider failure is a prerequisite", sentinel, providerFailure)
                m.capacityHook = null
                m.manager.handleRequestSync(PEER, emptyRequest())
                val partialInsertionObserved = m.sent.single().packet.payload === message.payload
                m.sendHook = { _, _ -> throw sentinel }
                val sendFailure = runCatching { m.manager.handleRequestSync(PEER, emptyRequest()) }.exceptionOrNull()
                assertSame("Expected synthetic send failure is a prerequisite", sentinel, sendFailure)
                val snapshot = m.snapshot()
                val expectedKind = when (sentinel) {
                    is CancellationException -> GossipDirectFailureKind.CancellationThrown
                    is Exception -> GossipDirectFailureKind.OrdinaryThrow
                    else -> GossipDirectFailureKind.OtherThrowable
                }
                val postconditions: () -> Unit = {
                    assertSame(sentinel, providerFailure); assertSame(sentinel, sendFailure)
                    assertTrue("Provider throw occurs after actual insertion", partialInsertionObserved)
                    assertEquals(listOf(expectedKind, expectedKind), snapshot.operations.filter {
                        it.state == GossipDirectBodyState.Threw }.map { it.failure })
                    assertEquals(1, snapshot.operations.count { it.state == GossipDirectBodyState.Returned })
                    assertTrue(snapshot.faults.isEmpty())
                }
                postconditions
            }
            post()
        }
    }

    @Test fun foreignReplayAndSnapshotMetadataCannotLaunderAcceptedDirectLease() = runTest {
        val post = withFixture { f ->
            val a = f.initial; val b = f.manager()
            val first = f.lease(a, GossipDirectOperationKind.PublicPacketSeen)
            val second = f.lease(b, GossipDirectOperationKind.RespondRequestSync)
            val foreign = b.work.noteDirectBodyEnd(first, null)
            val issuingAfterForeign = a.snapshot()
            val valid = a.work.noteDirectBodyEnd(first, null)
            val replay = a.work.noteDirectBodyEnd(first, GossipDirectFailureKind.OrdinaryThrow)
            val otherValid = b.work.noteDirectBodyEnd(second, null)
            val observedA = a.snapshot(); val observedB = b.snapshot()
            val rowIdentity: Any = observedA.operations.single().identity
            val associationIdentity: Any = observedA.association
            val postconditions: () -> Unit = {
                assertSame(GossipDirectSettlement.ForeignLease, foreign)
                assertEquals(GossipDirectBodyState.Admitted, issuingAfterForeign.operations.single().state)
                assertSame(GossipDirectSettlement.Recorded, valid); assertSame(GossipDirectSettlement.Recorded, otherValid)
                assertSame(GossipDirectSettlement.RepeatedSettlement, replay)
                assertEquals(GossipDirectBodyState.Returned, observedA.operations.single().state)
                assertNull(observedA.operations.single().failure)
                assertFalse(rowIdentity is GossipDirectOperationLease)
                assertFalse(associationIdentity is GossipManagerJobBinding)
                assertNotSame(first, rowIdentity)
                assertEquals(listOf(GossipDirectSettlement.RepeatedSettlement), observedA.faults)
                assertEquals(listOf(GossipDirectSettlement.ForeignLease), observedB.faults)
            }
            postconditions
        }
        post()
    }

    @Test fun productionCollectorClosesEveryKnownManagerBeforeHeldFacadeAwait() = runTest {
        val post = withFixture { f ->
            val second = f.additionalOriginalManager()
            val enteredA = f.gate(); val releaseA = f.gate()
            val enteredB = f.gate(); val releaseB = f.gate()
            f.initial.capacityHook = { enteredA.countDown(); await(releaseA, "manager A provider release") }
            second.capacityHook = { enteredB.countDown(); await(releaseB, "manager B provider release") }
            val firstWorker = f.worker { f.initial.manager.onPublicPacketSeen(packet(MessageType.MESSAGE, 14)) }
            val secondWorker = f.worker { second.manager.onPublicPacketSeen(packet(MessageType.MESSAGE, 15)) }
            await(enteredA, "actual manager A body"); await(enteredB, "actual manager B body")
            val facadeEntered = CompletableDeferred<Unit>()
            val facadeRelease = f.suspendGate()
            val child = f.track(CoroutineScope(f.facadeJob + f.dispatcher).launch(start = CoroutineStart.UNDISPATCHED) {
                withContext(NonCancellable) { facadeEntered.complete(Unit); facadeRelease.await() }
            })
            assertTrue("Owned facade child entered its actual noncancellable body", facadeEntered.isCompleted)
            val report = AtomicReference<PanicRetirementReport>()
            val collector = f.launchOwned { report.set(f.collect()) }
            val snapshotA = f.initial.snapshot(); val snapshotB = second.snapshot()
            val held = collector.isActive && f.facadeJob.isCancelled && !f.facadeJob.isCompleted && !child.isCompleted
            releaseA.countDown(); releaseB.countDown()
            f.reap(firstWorker); f.reap(secondWorker)
            facadeRelease.complete(Unit)
            f.join(collector); f.join(child)
            val actual = checkNotNull(report.get())
            val known = known(actual)
            val direct = known.directIngressClosure as KnownDirectIngressClosure.Attempted
            val roots = known.attempts.toList(); val rows = direct.managers.toList()
            val firstManager = f.initial.manager
            val postconditions: () -> Unit = {
                assertTrue("All direct events precede actual held facade completion", held)
                assertNotNull(snapshotA.close); assertNotNull(snapshotB.close)
                assertEquals(GossipDirectBodyState.Admitted, snapshotA.operations.single().state)
                assertEquals(GossipDirectBodyState.Admitted, snapshotB.operations.single().state)
                assertEquals(2, rows.size)
                rows.zip(roots).forEach { (row, root) ->
                    assertSame(root, row.originalClose)
                    val outcome = row.outcome as KnownManagerDirectCloseOutcome.Closed
                    assertNotNull(outcome.snapshotAtClose.close)
                    assertEquals(GossipDirectBodyState.Admitted, outcome.snapshotAtClose.operations.single().state)
                    val original = root as PanicGossipRootCloseAttempt.Closed
                    val managed = if (original.manager === firstManager) snapshotA else snapshotB
                    assertSame(managed.close, outcome.snapshotAtClose.close)
                }
                assertFalse(actual.confirmed)
                assertMissingProofs(actual)
            }
            postconditions
        }
        post()
    }

    private data class Sent(val peer: String, val packet: BitchatPacket)

    private class Managed(scope: CoroutineScope) {
        val work = GossipWorkOwner()
        val capacityCalls = AtomicInteger()
        val signCalls = AtomicInteger()
        val sent = CopyOnWriteArrayList<Sent>()
        @Volatile var capacityHook: (() -> Unit)? = null
        @Volatile var sendHook: ((String, BitchatPacket) -> Unit)? = null
        val manager = GossipSyncManager("1122334455667788", scope, object : GossipSyncManager.ConfigProvider {
            override fun seenCapacity(): Int { capacityCalls.incrementAndGet(); capacityHook?.invoke(); return 16 }
            override fun gcsMaxBytes() = 128
            override fun gcsTargetFpr() = 0.01
        }, workOwner = work, jobCreator = { _, _, _ -> throw AssertionError("Direct fixtures must not launch Gossip jobs") })
        val binding = manager.captureJobPanicBinding()
        init { manager.delegate = delegate(sent) { peer, packet -> sendHook?.invoke(peer, packet) } }

        fun delegate(destination: MutableList<Sent>, hook: ((String, BitchatPacket) -> Unit)?) = object : GossipSyncManager.Delegate {
            override fun sendPacket(packet: BitchatPacket) { throw AssertionError("Unexpected broadcast in response fixture") }
            override fun sendPacketToPeer(peerID: String, packet: BitchatPacket) {
                destination += Sent(peerID, packet)
                hook?.invoke(peerID, packet)
            }
            override fun signPacketForBroadcast(packet: BitchatPacket): BitchatPacket {
                signCalls.incrementAndGet()
                return packet
            }
        }
        fun snapshot() = (work.directIngressSnapshot(binding) as GossipDirectSnapshotCapture.Observed).snapshot
    }

    private class Fixture(private val test: TestScope) {
        val facadeJob = SupervisorJob()
        private val gossipJob = SupervisorJob()
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        private val gossipScope = CoroutineScope(gossipJob + dispatcher)
        private val managers = CopyOnWriteArrayList<Managed>()
        private val workers = CopyOnWriteArrayList<Thread>()
        private val gates = CopyOnWriteArrayList<CountDownLatch>()
        private val suspendGates = CopyOnWriteArrayList<CompletableDeferred<Unit>>()
        private val jobs = CopyOnWriteArrayList<Job>()
        private val errors = CopyOnWriteArrayList<Throwable>()
        private class ManualLease(val managed: Managed, val lease: GossipDirectOperationLease, val identity: GossipDirectOperationIdentity)
        private val leases = CopyOnWriteArrayList<ManualLease>()
        private val mesh = InertMesh()
        val holder = MeshServiceHolderOwner(MeshServiceHolderPorts<InertMesh, Any, GossipSyncManager>(
            isReusable = { true }, stopBluetooth = {}, refreshUnified = {}, startGossip = {}, stopGossip = {}, clearGossip = {},
            installGossipDelegate = { manager, value -> manager.delegate = value }, broadcast = {}, sendToPeer = { _, _ -> }, log = { _, _ -> }
        ))
        private val original = holder.initialProcessPreparation().origin
        lateinit var initial: Managed
            private set
        val facade = acquired(holder.getUnifiedOrCreate({ context ->
            initial = child(context, HolderGossipSource.BLE_SERVICE)
            mesh
        }) { bluetooth ->
            UnifiedMeshService(bluetooth, UnifiedMeshPorts(
                scope = CoroutineScope(facadeJob + dispatcher), announcementProfile = MutableStateFlow(60_000L to false),
                hasAnnouncementRecipients = { false }, currentWifi = { null }, startWifi = {}, stopWifi = {},
                bleEnabled = { true }, disableBle = {}, currentNpub = { null }
            ))
        }) as UnifiedMeshService
        private val binding = captureHolderPanicBindingWithGossip(holder, facade)

        fun manager() = Managed(gossipScope).also { managers += it }
        private fun child(context: HolderConstructionContext, source: HolderGossipSource): Managed {
            lateinit var managed: Managed
            val result = holder.constructGossipManager(HolderGossipParent.Constructed(context), source) {
                manager().also { managed = it }.manager
            }
            check(result === managed.manager)
            return managed
        }
        fun additionalOriginalManager(): Managed {
            lateinit var managed: Managed
            val result = holder.constructWifi(original, Any()) { context ->
                managed = child(context, HolderGossipSource.CORE_FALLBACK)
                Any()
            }
            check(result is WifiAwareFactoryResult.Created)
            return managed
        }
        suspend fun collect(): PanicRetirementReport {
            check(PanicResetCoordinator.fenceCapturedPanicOwners(facade, binding, facade) === HolderConstructionCloseOutcome.ClosedForConstruction)
            val report = collectPanicRetirementWithRecordedJobsJoin(facade, PanicOriginalGossipInput.Installed(binding, facade))
            assertSame("Actual facade retirement must finish", PanicOwnerRetirement.Confirmed, report.facade)
            assertTrue("Actual owned facade parent completed", facadeJob.isCompleted)
            check(known(report).directIngressClosure is KnownDirectIngressClosure.Attempted)
            return report
        }
        fun lease(managed: Managed, kind: GossipDirectOperationKind): GossipDirectOperationLease {
            val result = managed.work.reserveDirect(managed.binding, kind) as GossipDirectReservation.Accepted
            leases += ManualLease(managed, result.lease, checkNotNull(managed.snapshot().requests.last().operation))
            return result.lease
        }
        fun gate() = CountDownLatch(1).also { gates += it }
        fun suspendGate() = CompletableDeferred<Unit>().also { suspendGates += it }
        fun worker(action: () -> Unit) = Thread {
            try { action() } catch (failure: Throwable) { errors += failure }
        }.apply { isDaemon = true; workers += this; start() }
        fun track(job: Job) = job.also { jobs += it }
        fun launchOwned(action: suspend () -> Unit) = track(test.launch(start = CoroutineStart.UNDISPATCHED) {
            try { action() } catch (failure: Throwable) { errors += failure }
        })
        fun reap(worker: Thread) {
            worker.join(WAIT_MS)
            assertFalse("Owned direct worker exceeded completion bound", worker.isAlive)
            assertHealthy()
        }
        suspend fun join(job: Job) { withTimeout(WAIT_MS) { job.join() }; assertHealthy() }
        fun assertHealthy() {
            val all = errors.toList()
            if (all.isNotEmpty()) {
                val error = AssertionError("Unexpected owned direct fixture failure")
                error.initCause(all.first())
                all.drop(1).forEach { if (it !== error && it !== error.cause) error.addSuppressed(it) }
                throw error
            }
        }
        suspend fun close() = withContext(NonCancellable) {
            var primary: Throwable? = null
            fun record(failure: Throwable) {
                val first = primary
                if (first == null) primary = failure else if (first !== failure) first.addSuppressed(failure)
            }
            fun attempt(action: () -> Unit) { try { action() } catch (failure: Throwable) { record(failure) } }
            // Release every original gate before any cancellation, bounded guard or actual reap.
            gates.forEach { gate -> attempt { gate.countDown() } }
            suspendGates.forEach { gate -> attempt { gate.complete(Unit) } }
            workers.forEach { worker ->
                attempt {
                    worker.join(WAIT_MS)
                    if (worker.isAlive) {
                        val guard = AssertionError("Owned worker exceeded its first completion bound")
                        try { worker.interrupt(); worker.join(WAIT_MS) } catch (failure: Throwable) {
                            if (failure !== guard) guard.addSuppressed(failure)
                        }
                        throw guard
                    }
                }
                while (true) {
                    try { worker.join(); break } catch (failure: Throwable) { record(failure) }
                }
            }
            // Manual accepted leases are settled only if still admitted; prior misuse evidence is retained.
            leases.forEach { original -> attempt {
                val observed = original.managed.snapshot().operations.single { it.identity === original.identity }
                if (observed.state == GossipDirectBodyState.Admitted) {
                    check(original.managed.work.noteDirectBodyEnd(original.lease, null) == GossipDirectSettlement.Recorded)
                }
            } }
            managers.forEach { managed -> attempt { managed.manager.stop(); managed.manager.delegate = null } }
            val allJobs = jobs.toList() + gossipJob + facadeJob
            allJobs.forEach { job -> attempt { job.cancel() } }
            allJobs.forEach { job ->
                try { withTimeout(WAIT_MS) { job.join() } } catch (failure: Throwable) { record(failure) }
                attempt { job.cancel() }
                while (true) {
                    try { job.join(); break } catch (failure: Throwable) { record(failure) }
                }
            }
            attempt { assertHealthy() }
            primary?.let { throw it }
        }
    }

    private suspend fun TestScope.withFixture(block: suspend (Fixture) -> (() -> Unit)): () -> Unit {
        val f = Fixture(this)
        var primary: Throwable? = null
        var postconditions: (() -> Unit)? = null
        try { postconditions = block(f); f.assertHealthy() } catch (failure: Throwable) { primary = failure }
        finally {
            try { f.close() } catch (failure: Throwable) {
                val original = primary
                if (original == null) primary = failure else if (original !== failure) original.addSuppressed(failure)
            }
        }
        primary?.let { throw it }
        return checkNotNull(postconditions)
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
        private const val PEER = "2233445566778899"
        private fun await(gate: CountDownLatch, reason: String) {
            if (!gate.await(WAIT_MS, TimeUnit.MILLISECONDS)) throw AssertionError("Timed out: $reason")
        }
        private fun <T : Any> acquired(result: HolderAcquisitionResult<T>): T = when (result) {
            is HolderAcquisitionResult.Available -> result.product
            HolderAcquisitionResult.RefusedBeforeAcquisition -> throw AssertionError("Expected ordinary original acquisition")
        }
        private fun packet(type: MessageType, marker: Int) = BitchatPacket(
            type = type.value, senderID = ByteArray(8) { marker.toByte() },
            timestamp = System.currentTimeMillis().toULong(), payload = byteArrayOf(marker.toByte(), 42),
            signature = ByteArray(64) { marker.toByte() }, ttl = 6u
        )
        private fun emptyRequest(): RequestSyncPacket {
            val filter = GCSFilter.buildFilter(emptyList(), 128, 0.01)
            val decoded = checkNotNull(RequestSyncPacket.decode(RequestSyncPacket(filter.p, filter.m, filter.data).encode()))
            check(decoded.p >= 1 && decoded.m > 0L)
            check(GCSFilter.decodeToSortedSet(decoded.p, decoded.m, decoded.data).isEmpty())
            return decoded
        }
        private fun known(report: PanicRetirementReport) =
            (report.gossipJobsAndWriters as PanicOwnerRetirement.Unconfirmed).evidence as PanicUnconfirmedEvidence.KnownOriginalGossip
        private fun direct(report: PanicRetirementReport, managed: Managed): KnownManagerDirectCloseOutcome.Closed {
            val evidence = known(report)
            val rows = (evidence.directIngressClosure as KnownDirectIngressClosure.Attempted).managers
            val row = rows.single { it.originalClose.manager === managed.manager }
            assertSame(evidence.attempts.single { it.manager === managed.manager }, row.originalClose)
            return row.outcome as KnownManagerDirectCloseOutcome.Closed
        }
        private fun assertPacketAlias(original: BitchatPacket, sent: BitchatPacket) {
            assertNotSame(original, sent)
            assertSame(original.payload, sent.payload)
            assertSame(original.signature, sent.signature)
            assertEquals(AppConstants.SYNC_TTL_HOPS, sent.ttl)
            assertArrayEquals(original.senderID, sent.senderID)
        }
        private fun assertMissingProofs(report: PanicRetirementReport) {
            assertFalse(report.confirmed)
            listOf(report.wifiController, report.holderConstructionAndCallbacks, report.gossipJobsAndWriters,
                report.transportCoreRadioAndHandlers, report.startupAndRemainingSharedKeyUsers).forEach {
                assertEquals(PanicRetirementGap.ProducerNotImplemented, (it as PanicOwnerRetirement.Unconfirmed).gap)
            }
        }
    }
}
