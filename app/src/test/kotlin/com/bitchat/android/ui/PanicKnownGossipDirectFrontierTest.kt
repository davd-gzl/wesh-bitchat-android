package com.bitchat.android.ui

import android.app.Application
import android.os.Build
import com.bitchat.android.mesh.*
import com.bitchat.android.model.RequestSyncPacket
import com.bitchat.android.noise.NoiseSession
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.service.*
import com.bitchat.android.sync.*
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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual original manager bodies and finite Jobs; no platform graph or manager IO loops. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class PanicKnownGossipDirectFrontierTest {
    @Test fun heldCacheBodyReturnsUnconfirmedBeforeRecordedJoin() = runTest {
        val post = withFixture { f ->
            val held = f.held(f.initial)
            runCurrent()
            check(held.entered.isCompleted) { "Actual noncancellable child must enter" }
            f.retireFacadeNow()
            val entered = f.gate(); val release = f.gate()
            f.initial.capacityHook = { entered.countDown(); await(release, "actual cache provider release") }
            val worker = f.worker { f.initial.manager.onPublicPacketSeen(packet(MessageType.MESSAGE, 1)) }
            await(entered, "actual cache provider")
            f.fence()
            f.requireRecordedReady(f.initial, held)
            val result = AtomicReference<PanicRetirementReport>()
            val collector = f.collectAsync(result)
            // UNDISTPATCHED reaches the first real suspension; no release or queue pumping here.
            val completedBeforeRelease = collector.isCompleted
            val actualPrerequisites = held.job.isCancelled && !held.job.isCompleted &&
                !held.release.isCompleted && worker.isAlive && release.count == 1L && f.facadeJob.isCompleted
            check(actualPrerequisites) { "Actual cancellation, held body and immediate facade prerequisites must succeed" }
            f.assertHealthy()
            release.countDown(); held.release.complete(Unit)
            f.reap(worker); f.join(collector); f.join(held.job)
            val report = checkNotNull(result.get())
            val cancelledAndCompleted = held.job.isCancelled && held.job.isCompleted
            val finalRows = f.initial.snapshot().operations.toList()
            val postconditions: () -> Unit = {
                assertTrue("An active direct body must return before any recorded Job join", completedBeforeRelease)
                assertTrue(actualPrerequisites)
                assertTrue(cancelledAndCompleted)
                assertEquals(GossipDirectBodyState.Returned, finalRows.single().state)
                assertActiveGap(report, f.initial)
                assertSame(KnownRecordedFrontiersJoin.NotAttempted, known(report).recordedFrontierJoin)
                assertMissingProofs(report)
            }
            postconditions
        }
        post()
    }

    @Test fun incompleteSecondManagerPreventsEveryRecordedJoin() = runTest {
        val post = withFixture { f ->
            val second = f.additionalOriginalManager()
            val held = f.held(f.initial)
            runCurrent()
            check(held.entered.isCompleted)
            f.retireFacadeNow()
            val entered = f.gate(); val release = f.gate()
            second.capacityHook = { entered.countDown(); await(release, "manager B body release") }
            val worker = f.worker { second.manager.onPublicPacketSeen(packet(MessageType.MESSAGE, 2)) }
            await(entered, "manager B actual body")
            f.fence()
            f.requireRecordedReady(f.initial, held)
            val result = AtomicReference<PanicRetirementReport>()
            val collector = f.collectAsync(result)
            val completedBeforeRelease = collector.isCompleted
            val actualPrerequisites = held.job.isCancelled && !held.job.isCompleted &&
                !held.release.isCompleted && worker.isAlive && release.count == 1L && f.facadeJob.isCompleted
            check(actualPrerequisites) { "Actual cancellation, held body and immediate facade prerequisites must succeed" }
            f.assertHealthy()
            release.countDown(); held.release.complete(Unit)
            f.reap(worker); f.join(collector); f.join(held.job)
            val report = checkNotNull(result.get())
            val knownManagers = known(report).attempts.map { it.manager }
            val postconditions: () -> Unit = {
                assertTrue("The whole direct batch must be ready before joining the earlier manager", completedBeforeRelease)
                assertTrue(actualPrerequisites)
                assertEquals(2, knownManagers.size)
                assertSame(f.initial.manager, knownManagers.first())
                assertSame(second.manager, knownManagers.last())
                assertActiveGap(report, second)
                assertSame(KnownRecordedFrontiersJoin.NotAttempted, known(report).recordedFrontierJoin)
                assertMissingProofs(report)
            }
            postconditions
        }
        post()
    }

    @Test fun preacceptedResponseEndsAfterCloseAndExplicitRetryObservesSameFrontier() = runTest {
        val post = withFixture { f ->
            val m = f.initial
            val original = packet(MessageType.MESSAGE, 3)
            m.manager.onPublicPacketSeen(original)
            val entered = f.gate(); val release = f.gate()
            m.sendHook = { _, _ -> entered.countDown(); await(release, "actual response release") }
            val worker = f.worker { m.manager.handleRequestSync(PEER, emptyRequest()) }
            await(entered, "actual response before direct close")
            val events = f.events(m)
            val before = m.capture(events)
            val held = worker.isAlive && release.count == 1L
            release.countDown(); f.reap(worker)
            val after = m.capture(events)
            f.fence()
            val retry = f.collect()
            val observed = known(retry).directBodiesEnd as KnownDirectBodiesEnd.Observed
            val actual = observed.managers.single()
            val valid = m.validate(events, actual.frontier)
            val rows = m.snapshot().operations.toList(); val sent = m.sent.toList()
            val postconditions: () -> Unit = {
                assertTrue(held)
                assertEquals(GossipDirectFrontierGap.ActiveOperation, (before as GossipDirectEndedCapture.Unconfirmed).gap)
                assertTrue(after is GossipDirectEndedCapture.Observed)
                assertTrue(valid)
                assertSame(m.manager, actual.original.originalClose.manager)
                assertSame(events.direct, (actual.original.outcome as KnownManagerDirectCloseOutcome.Closed).receipt)
                assertTrue(rows.all { it.state == GossipDirectBodyState.Returned })
                assertPacketAlias(original, sent.single().packet)
                assertMissingProofs(retry)
            }
            postconditions
        }
        post()
    }

    @Test fun emptyAndFilteredClosedFrontiersRequireAuthenticEvents() = runTest {
        val post = withFixture { f ->
            val a = f.initial; val b = f.manager(); val filtered = f.manager()
            val foreign = f.events(b)
            val open = a.capture(foreign)
            a.manager.closeJobRootsForPanic(a.binding)
            val rootOnly = a.capture(foreign)
            val actual = f.events(a)
            val empty = a.capture(actual) as GossipDirectEndedCapture.EmptyValidated
            val emptyValid = a.validate(actual, empty.frontier)
            filtered.manager.onPublicPacketSeen(packet(MessageType.FRAGMENT, 4))
            val filteredEvents = f.events(filtered)
            val returned = filtered.capture(filteredEvents) as GossipDirectEndedCapture.Observed
            val returnedValid = filtered.validate(filteredEvents, returned.frontier)
            val rows = filtered.snapshot().operations.toList()
            val emptyRows = a.snapshot().operations.toList()
            val filteredCalls = filtered.capacityCalls.get()
            val metadata: Any = filtered.snapshot().operations.single().identity
            val postconditions: () -> Unit = {
                assertEquals(GossipDirectFrontierGap.NotClosed, (open as GossipDirectEndedCapture.Unconfirmed).gap)
                assertEquals(GossipDirectFrontierGap.NotClosed, (rootOnly as GossipDirectEndedCapture.Unconfirmed).gap)
                assertTrue(emptyValid); assertTrue(returnedValid)
                assertTrue(emptyRows.isEmpty())
                assertEquals(GossipDirectBodyState.Returned, rows.single().state)
                assertEquals(0, filteredCalls)
                assertFalse(metadata is GossipDirectOperationLease)
                assertFalse((empty.frontier as Any) is GossipDirectOperationLease)
                assertFalse((returned.frontier as Any) is GossipManagerJobBinding)
            }
            postconditions
        }
        post()
    }

    @Test fun throwingBodiesEndWithExactFailureCategoriesAndPartialEffects() = runTest {
        listOf(IllegalStateException("Synthetic direct failure"), CancellationException("Synthetic direct cancellation"),
            AssertionError("Synthetic direct throwable")).forEach { sentinel ->
            val post = withFixture { f ->
                val m = f.initial; val original = packet(MessageType.MESSAGE, 5)
                m.capacityHook = { throw sentinel }
                val providerFailure = runCatching { m.manager.onPublicPacketSeen(original) }.exceptionOrNull()
                check(providerFailure === sentinel)
                m.capacityHook = null
                m.manager.handleRequestSync(PEER, emptyRequest())
                val partial = m.sent.single().packet.payload === original.payload
                m.sendHook = { _, _ -> throw sentinel }
                val sendFailure = runCatching { m.manager.handleRequestSync(PEER, emptyRequest()) }.exceptionOrNull()
                check(sendFailure === sentinel)
                val events = f.events(m)
                val captured = m.capture(events) as GossipDirectEndedCapture.Observed
                val valid = m.validate(events, captured.frontier)
                f.fence(); val report = f.collect()
                val rows = m.snapshot().operations.toList()
                val expected = when (sentinel) {
                    is CancellationException -> GossipDirectFailureKind.CancellationThrown
                    is Exception -> GossipDirectFailureKind.OrdinaryThrow
                    else -> GossipDirectFailureKind.OtherThrowable
                }
                val postconditions: () -> Unit = {
                    assertSame(sentinel, providerFailure); assertSame(sentinel, sendFailure)
                    assertTrue(partial); assertTrue(valid)
                    assertEquals(listOf(expected, expected), rows.filter { it.state == GossipDirectBodyState.Threw }.map { it.failure })
                    assertTrue(known(report).directBodiesEnd is KnownDirectBodiesEnd.Observed)
                    assertMissingProofs(report)
                }
                postconditions
            }
            post()
        }
    }

    @Test fun foreignReceiptsAndReplayedMetadataCannotMintFrontier() = runTest {
        val post = withFixture { f ->
            val a = f.initial; val b = f.manager(); val negative = f.manager()
            a.manager.onPublicPacketSeen(packet(MessageType.FRAGMENT, 8))
            // This lease is negative protocol input only, never a production-body completion witness.
            val negativeLease = f.lease(negative)
            val own = f.events(a); val other = f.events(b); val negativeEvents = f.events(negative)
            val captured = a.capture(own) as GossipDirectEndedCapture.Observed
            val foreignBinding = a.work.captureEndedDirectFrontier(b.binding, own.root, own.direct)
            val foreignRoot = a.work.captureEndedDirectFrontier(a.binding, other.root, own.direct)
            val foreignDirect = a.work.captureEndedDirectFrontier(a.binding, own.root, other.direct)
            val foreignWitness = b.validate(other, captured.frontier)
            val validBeforeFault = a.validate(own, captured.frontier)
            val foreignSettlement = a.work.noteDirectBodyEnd(negativeLease, null)
            val negativeSettlement = negative.work.noteDirectBodyEnd(negativeLease, null)
            val replay = negative.work.noteDirectBodyEnd(negativeLease, GossipDirectFailureKind.OrdinaryThrow)
            val validAfterFault = a.validate(own, captured.frontier)
            val fault = a.capture(own); val repeatedFault = negative.capture(negativeEvents)
            val rows = a.snapshot().operations.toList()
            val postconditions: () -> Unit = {
                assertEquals(GossipDirectFrontierGap.ForeignBinding, (foreignBinding as GossipDirectEndedCapture.Unconfirmed).gap)
                assertEquals(GossipDirectFrontierGap.ForeignRootReceipt, (foreignRoot as GossipDirectEndedCapture.Unconfirmed).gap)
                assertEquals(GossipDirectFrontierGap.ForeignDirectReceipt, (foreignDirect as GossipDirectEndedCapture.Unconfirmed).gap)
                assertFalse(foreignWitness); assertTrue(validBeforeFault); assertFalse(validAfterFault)
                assertSame(GossipDirectSettlement.ForeignLease, foreignSettlement)
                assertSame(GossipDirectSettlement.Recorded, negativeSettlement)
                assertSame(GossipDirectSettlement.RepeatedSettlement, replay)
                assertEquals(GossipDirectFrontierGap.ProtocolUncertainty, (fault as GossipDirectEndedCapture.Unconfirmed).gap)
                assertEquals(GossipDirectFrontierGap.ProtocolUncertainty, (repeatedFault as GossipDirectEndedCapture.Unconfirmed).gap)
                assertEquals(GossipDirectBodyState.Returned, rows.single().state)
                assertNull(rows.single().failure)
            }
            postconditions
        }
        post()
    }

    @Test fun reentrantProviderPreflightNeverWaitsForItsOwnBody() = runTest {
        val post = withFixture { f ->
            val m = f.initial
            val inside = AtomicReference<GossipDirectEndedCapture>()
            val events = AtomicReference<Events>()
            m.capacityHook = {
                val actual = f.events(m); events.set(actual)
                inside.set(m.capture(actual))
            }
            m.manager.onPublicPacketSeen(packet(MessageType.MESSAGE, 6))
            val actual = checkNotNull(events.get())
            val after = m.capture(actual) as GossipDirectEndedCapture.Observed
            val valid = m.validate(actual, after.frontier)
            val calls = m.capacityCalls.get(); val rows = m.snapshot().operations.toList()
            val postconditions: () -> Unit = {
                assertEquals(GossipDirectFrontierGap.ActiveOperation, (inside.get() as GossipDirectEndedCapture.Unconfirmed).gap)
                assertTrue(valid); assertEquals(1, calls)
                assertEquals(GossipDirectBodyState.Returned, rows.single().state)
            }
            postconditions
        }
        post()
    }

    @Test fun lateOriginalManagerNeedsFreshCancellationAndCollection() = runTest {
        val post = withFixture { f ->
            val entered = f.gate(); val release = f.gate()
            val late = AtomicReference<Managed>(); val lateJob = AtomicReference<Job>()
            val worker = f.lateOriginalManager(entered, release) { managed ->
                late.set(managed)
                val actual = f.track(Job())
                lateJob.set(actual); f.returned(managed, actual)
            }
            await(entered, "actual preaccepted original constructor")
            f.retireFacadeNow(); f.fence()
            val first = f.collect()
            val firstManagers = known(first).attempts.map { it.manager }
            val firstJoin = known(first).recordedFrontierJoin
            release.countDown(); f.reap(worker)
            val actualLate = checkNotNull(late.get()); val actualJob = checkNotNull(lateJob.get())
            val lateOpenBeforeRetry = actualJob.isActive && actualLate.snapshot().close == null
            val retry = f.collect()
            val cancelledByRetry = actualJob.isCancelled && actualJob.isCompleted
            f.join(actualJob)
            val managers = known(retry).attempts.map { it.manager }
            val postconditions: () -> Unit = {
                assertEquals(listOf(f.initial.manager), firstManagers)
                assertTrue(firstJoin is KnownRecordedFrontiersJoin.Ineligible)
                assertTrue(lateOpenBeforeRetry); assertTrue(cancelledByRetry)
                assertEquals(2, managers.size); assertSame(actualLate.manager, managers.last())
                assertTrue(known(retry).directBodiesEnd is KnownDirectBodiesEnd.Observed)
                assertMissingProofs(first); assertMissingProofs(retry)
            }
            postconditions
        }
        post()
    }

    @Test fun callerCancellationDuringActualRecordedJoinReturnsNoCompletedReport() = runTest {
        val post = withFixture { f ->
            f.initial.manager.onPublicPacketSeen(packet(MessageType.MESSAGE, 7))
            val held = f.held(f.initial)
            runCurrent(); check(held.entered.isCompleted)
            f.retireFacadeNow(); f.fence(); f.requireRecordedReady(f.initial, held)
            val result = AtomicReference<PanicRetirementReport>(); val failure = AtomicReference<Throwable>()
            val collector = f.collectAsync(result, failure)
            val heldAtJoin = !collector.isCompleted && held.job.isCancelled && !held.job.isCompleted
            collector.cancel(CancellationException("Synthetic collector cancellation"))
            f.join(collector)
            val cancelledBeforeRelease = collector.isCancelled && !held.job.isCompleted && !held.release.isCompleted
            val actualFailure = failure.get(); val returned = result.get()
            held.release.complete(Unit); f.join(held.job)
            val postconditions: () -> Unit = {
                assertTrue(heldAtJoin); assertTrue(cancelledBeforeRelease)
                assertTrue(actualFailure is CancellationException)
                assertNull(returned)
            }
            postconditions
        }
        post()
    }

    private data class Sent(val peer: String, val packet: BitchatPacket)
    private class Events(val root: GossipRootCloseReceipt, val direct: GossipDirectCloseReceipt)
    private class Held(val job: Job, val ticket: GossipWorkTicket,
        val entered: CompletableDeferred<Unit>, val release: CompletableDeferred<Unit>)

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
        }, workOwner = work, jobCreator = { _, _, _ -> throw AssertionError("Frontier fixtures must not start manager IO loops") })
        val binding = manager.captureJobPanicBinding()
        init { manager.delegate = object : GossipSyncManager.Delegate {
            override fun sendPacket(packet: BitchatPacket) { throw AssertionError("Unexpected fixture broadcast") }
            override fun sendPacketToPeer(peerID: String, packet: BitchatPacket) {
                sent += Sent(peerID, packet); sendHook?.invoke(peerID, packet)
            }
            override fun signPacketForBroadcast(packet: BitchatPacket): BitchatPacket {
                signCalls.incrementAndGet(); return packet
            }
        } }
        fun snapshot() = (work.directIngressSnapshot(binding) as GossipDirectSnapshotCapture.Observed).snapshot
        fun capture(events: Events) = manager.captureEndedDirectFrontier(binding, events.root, events.direct)
        fun validate(events: Events, frontier: GossipDirectEndedFrontier) =
            manager.validateEndedDirectFrontier(binding, events.root, events.direct, frontier)
    }

    private class Fixture(private val test: TestScope) {
        val facadeJob = SupervisorJob()
        private val gossipJob = SupervisorJob()
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        private val errors = CopyOnWriteArrayList<Throwable>()
        private val handler = CoroutineExceptionHandler { _, failure -> errors += failure }
        private val gossipScope = CoroutineScope(gossipJob + dispatcher + handler)
        private val managers = CopyOnWriteArrayList<Managed>()
        private val workers = CopyOnWriteArrayList<Thread>()
        private val gates = CopyOnWriteArrayList<CountDownLatch>()
        private val suspendGates = CopyOnWriteArrayList<CompletableDeferred<Unit>>()
        private val jobs = CopyOnWriteArrayList<Job>()
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
                scope = CoroutineScope(facadeJob + dispatcher + handler), announcementProfile = MutableStateFlow(60_000L to false),
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
        fun lateOriginalManager(entered: CountDownLatch, release: CountDownLatch, after: (Managed) -> Unit): Thread = worker {
            val result = holder.constructWifi(original, Any()) { context ->
                entered.countDown(); await(release, "original constructor release")
                after(child(context, HolderGossipSource.CORE_FALLBACK))
                Any()
            }
            check(result is WifiAwareFactoryResult.Created)
        }
        fun fence() {
            check(PanicResetCoordinator.fenceCapturedPanicOwners(facade, binding, facade) === HolderConstructionCloseOutcome.ClosedForConstruction)
        }
        suspend fun retireFacadeNow() {
            check(facade.retireForPanic()) { "Actual original facade retirement must finish before progress observation" }
            check(facadeJob.isCompleted) { "The SAME real facade parent must already be completed" }
            assertHealthy()
        }
        fun requireRecordedReady(managed: Managed, held: Held) {
            val holderFrontier = captureHolderAdmittedManagerFrontier(binding, facade)
            check(holderFrontier is HolderGossipCensusCapture.Sealed)
            val actualManagers = managers.toList()
            check(holderFrontier.frontier.managers.size == actualManagers.size &&
                actualManagers.all { expected -> holderFrontier.frontier.managers.count { it === expected.manager } == 1 })
            check(holderFrontier.frontier.managers.any { it === managed.manager })
            check(managed.work.rootSnapshot().all { it.state == GossipRootState.FINISHED })
            val rows = managed.work.snapshot()
            check(rows.size == 1 && rows.single().ticket === held.ticket &&
                (rows.single().outcome as? GossipWorkOutcome.Returned)?.job === held.job)
            check(held.entered.isCompleted && held.job.isActive && !held.release.isCompleted)
            check(facadeJob.isCompleted && gossipJob !== held.job && facadeJob !== held.job)
            assertHealthy()
        }
        suspend fun collect(): PanicRetirementReport {
            val report = collectPanicRetirementWithEndedDirectFrontiers(facade, PanicOriginalGossipInput.Installed(binding, facade))
            check(report.facade === PanicOwnerRetirement.Confirmed && facadeJob.isCompleted)
            assertHealthy()
            return report
        }
        fun collectAsync(result: AtomicReference<PanicRetirementReport>, expectedCancellation: AtomicReference<Throwable>? = null): Job {
            val entered = CompletableDeferred<Unit>()
            val collector = track(test.launch(start = CoroutineStart.UNDISPATCHED) {
                entered.complete(Unit)
                try {
                    val chain = java.util.IdentityHashMap<Job, Unit>()
                    var caller: Job? = currentCoroutineContext()[Job]
                    check(caller != null)
                    while (caller != null) {
                        check(chain.put(caller, Unit) == null)
                        caller = caller.parent
                    }
                    check(jobs.none { chain.containsKey(it) }) { "Actual collector must not join itself or an ancestor" }
                    check(!chain.containsKey(gossipJob) && !chain.containsKey(facadeJob))
                    result.set(collect())
                }
                catch (failure: Throwable) {
                    if (expectedCancellation != null && failure is CancellationException) expectedCancellation.set(failure)
                    else errors += failure
                }
            })
            check(entered.isCompleted) { "Actual undispatched collector must enter" }
            return collector
        }
        fun events(managed: Managed): Events {
            val root = managed.manager.closeJobRootsForPanic(managed.binding) as GossipManagerRootCloseOutcome.Closed
            val direct = managed.manager.closeDirectIngressForPanic(managed.binding, root.receipt) as GossipDirectCloseOutcome.Closed
            return Events(root.receipt, direct.receipt)
        }
        fun returned(managed: Managed, job: Job): GossipWorkTicket {
            track(job)
            val root = managed.work.reserveRoot(managed.binding, GossipRootKind.INITIAL_BROADCAST) as GossipRootReservation.Accepted
            val ticket = try { managed.work.reserveChild(root.lease, GossipWorkKind.INITIAL_BROADCAST) }
                finally { managed.work.finishRoot(root.lease) }
            check(managed.work.dispatch(ticket, gossipScope, dispatcher, { _, _, _ -> job }) {} === job)
            return ticket
        }
        fun held(managed: Managed): Held {
            val entered = CompletableDeferred<Unit>(); val release = suspendGate()
            val job = track(launchOriginalGossipJob(gossipScope, dispatcher) {
                track(launch { withContext(NonCancellable) { entered.complete(Unit); release.await() } })
                awaitCancellation()
            })
            return Held(job, returned(managed, job), entered, release)
        }
        fun lease(managed: Managed): GossipDirectOperationLease {
            val result = managed.work.reserveDirect(managed.binding, GossipDirectOperationKind.PublicPacketSeen) as GossipDirectReservation.Accepted
            leases += ManualLease(managed, result.lease, checkNotNull(managed.snapshot().requests.last().operation))
            return result.lease
        }
        fun gate() = CountDownLatch(1).also { gates += it }
        fun suspendGate() = CompletableDeferred<Unit>().also { suspendGates += it }
        fun worker(action: () -> Unit) = Thread {
            try { action() } catch (failure: Throwable) { errors += failure }
        }.apply { isDaemon = true; workers += this; start() }
        fun track(job: Job): Job = job.also { actual -> if (jobs.none { it === actual }) jobs += actual }
        fun reap(worker: Thread) {
            worker.join(WAIT_MS)
            check(!worker.isAlive) { "Owned worker exceeded its completion guard" }
            assertHealthy()
        }
        suspend fun join(job: Job) { withTimeout(WAIT_MS) { job.join() }; assertHealthy() }
        fun assertHealthy() {
            val all = errors.toList()
            if (all.isNotEmpty()) {
                val error = AssertionError("Unexpected owned frontier fixture failure")
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
            // All independent gates release before cancellation or any actual join.
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
            leases.forEach { original -> attempt {
                val row = original.managed.snapshot().operations.single { it.identity === original.identity }
                if (row.state == GossipDirectBodyState.Admitted) {
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
        val fixture = Fixture(this)
        var primary: Throwable? = null
        var postconditions: (() -> Unit)? = null
        try { postconditions = block(fixture); fixture.assertHealthy() } catch (failure: Throwable) { primary = failure }
        finally {
            try { fixture.close() } catch (failure: Throwable) {
                val first = primary
                if (first == null) primary = failure else if (first !== failure) first.addSuppressed(failure)
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
        private fun assertActiveGap(report: PanicRetirementReport, managed: Managed) {
            val ineligible = known(report).directBodiesEnd as KnownDirectBodiesEnd.Ineligible
            val row = ineligible.reasons.filterIsInstance<KnownDirectEndRejection.Manager>().single {
                it.original.originalClose.manager === managed.manager
            }
            assertEquals(GossipDirectFrontierGap.ActiveOperation, row.gap)
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
