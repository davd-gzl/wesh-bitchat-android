package com.bitchat.android.ui

import com.bitchat.android.mesh.MeshDelegate
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.mesh.PeerInfo
import com.bitchat.android.mesh.UnifiedMeshPorts
import com.bitchat.android.mesh.UnifiedMeshService
import com.bitchat.android.noise.NoiseSession
import com.bitchat.android.service.*
import com.bitchat.android.sync.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Real recorded-frontier joins with finite owned Jobs; no platform graph or manager IO loops. */
@OptIn(ExperimentalCoroutinesApi::class)
class PanicKnownGossipRecordedJoinTest {
    @Test fun stableRecordedBatchJoinsActualHeldChildAfterFacadePhase() = runTest {
        withFixture { f ->
            val held = f.held()
            val second = f.child()
            val other = f.own(Job())
            f.returned(second, other)
            val observedOrder = CopyOnWriteArrayList<Boolean>()
            f.observeCompletion(other) { observedOrder += f.closed(f.initial) && f.closed(second) && f.facadeJob.isActive }
            runCurrent()
            check(held.entered.isCompleted) { "Actual noncancellable child must enter" }
            f.fence()
            val result = AtomicReference<PanicRetirementReport>()
            val collector = f.collectAsync(result)
            runCurrent()
            val requestedBeforeRelease = held.job.isCancelled && other.isCancelled
            val waitingBeforeRelease = !collector.isCompleted && !held.job.isCompleted && !held.release.isCompleted
            val facadeAlreadyCompleted = f.facadeJob.isCompleted
            val actualOrder = observedOrder.toList()
            held.release.complete(Unit)
            f.join(collector)
            f.join(held.job)
            f.join(other)
            f.assertHealthy()
            val report = checkNotNull(result.get())
            val evidence = joins(report)
            val jobsCompleted = held.job.isCompleted && other.isCompleted
            return@withFixture {
                assertTrue(requestedBeforeRelease)
                assertTrue(waitingBeforeRelease)
                assertTrue(facadeAlreadyCompleted)
                assertEquals(listOf(true), actualOrder)
                assertTrue(jobsCompleted)
                assertEquals(2, evidence.managers.size)
                evidence.managers.zip(cancellations(report).managers).forEach { (actual, original) ->
                    assertSame(original, actual.original)
                    assertSame(observed(original), joined(actual).originalSnapshot)
                }
                assertSame(held.job, joined(evidence.managers[0]).joinedJobs.single())
                assertSame(other, joined(evidence.managers[1]).joinedJobs.single())
                assertSame(held.ticket, joined(evidence.managers[0]).originalSnapshot.records.single().ticket)
                assertMissingProofs(report)
            }
        }
    }

    @Test fun activeManagerFrontierPreventsJoiningEveryOtherManager() = runTest {
        withFixture { f ->
            val held = f.held()
            val second = f.child()
            val active = f.root(second)
            runCurrent()
            check(held.entered.isCompleted)
            f.fence()
            val report = f.collect()
            val incompleteAtReturn = held.job.isCancelled && !held.job.isCompleted && !held.release.isCompleted
            second.work.finishRoot(active)
            held.release.complete(Unit)
            f.join(held.job)
            f.assertHealthy()
            return@withFixture {
                assertTrue("The entire batch must return before joining the held earlier job", incompleteAtReturn)
                val reason = ineligible(report).reasons.single() as KnownRecordedJoinRejection.Manager
                assertSame(second.manager, reason.original.originalClose.manager)
                assertEquals(GossipRecordedJoinGap.ActiveRoot, reason.gap)
                assertMissingProofs(report)
            }
        }
    }

    @Test fun finishedReservedTicketRequiresExplicitRetry() = runTest {
        withFixture { f ->
            val held = f.held()
            val second = f.child()
            val reserved = f.reserved(second)
            runCurrent()
            check(held.entered.isCompleted)
            f.fence()
            val first = f.collect()
            val incompleteAtFirst = held.job.isCancelled && !held.job.isCompleted
            val entered = CompletableDeferred<Unit>()
            val late = f.launchOriginal { entered.complete(Unit); awaitCancellation() }
            second.work.dispatch(reserved, f.gossipScope, f.dispatcher, { _, _, _ -> late }) {}
            runCurrent()
            check(entered.isCompleted) { "Actual late reserved body must enter" }
            val result = AtomicReference<PanicRetirementReport>()
            val collector = f.collectAsync(result)
            runCurrent()
            val lateCancelledBeforeRelease = late.isCancelled
            val collectorWaiting = !collector.isCompleted && !held.job.isCompleted
            held.release.complete(Unit)
            f.join(collector)
            f.join(held.job)
            f.join(late)
            f.assertHealthy()
            val secondReport = checkNotNull(result.get())
            return@withFixture {
                assertTrue(incompleteAtFirst)
                assertEquals(GossipRecordedJoinGap.ReservedTicket,
                    (ineligible(first).reasons.single() as KnownRecordedJoinRejection.Manager).gap)
                assertTrue(lateCancelledBeforeRelease)
                assertTrue(collectorWaiting)
                val witness = joined(joins(secondReport).managers[1])
                assertSame(late, witness.joinedJobs.single())
                assertSame(reserved, witness.originalSnapshot.records.single().ticket)
                assertSame(GossipWorkOutcome.Reserved,
                    observed(cancellations(first).managers[1]).records.single().outcome)
                assertMissingProofs(first)
                assertMissingProofs(secondReport)
            }
        }
    }

    @Test fun dispatchingCreatorRequiresExplicitRetryBeforeJoin() = runTest {
        withFixture { f ->
            val held = f.held()
            val second = f.child()
            val entered = f.gate()
            val release = f.gate()
            val bodyEntered = CompletableDeferred<Unit>()
            val escaped = AtomicReference<Job>()
            val root = f.root(second)
            val ticket = second.work.reserveChild(root, GossipWorkKind.INITIAL_BROADCAST)
            val worker = f.worker {
                try {
                    second.work.dispatch(ticket, f.gossipScope, f.dispatcher, { scope, context, body ->
                        f.own(launchOriginalGossipJob(scope, context, body)).also {
                            escaped.set(it); entered.countDown(); await(release, "actual creator return")
                        }
                    }) { bodyEntered.complete(Unit); awaitCancellation() }
                } finally { second.work.finishRoot(root) }
            }
            await(entered, "actual escaped handle")
            runCurrent()
            check(bodyEntered.isCompleted && held.entered.isCompleted)
            f.fence()
            val first = f.collect()
            val originalStillHeld = held.job.isCancelled && !held.job.isCompleted
            val escapedNotCancelled = !checkNotNull(escaped.get()).isCancelled
            release.countDown()
            f.reap(worker)
            val result = AtomicReference<PanicRetirementReport>()
            val collector = f.collectAsync(result)
            runCurrent()
            val actualLateJob = checkNotNull(escaped.get())
            val lateCancellation = actualLateJob.isCancelled
            val collectorWaiting = !collector.isCompleted
            held.release.complete(Unit)
            f.join(collector)
            f.join(held.job)
            f.join(actualLateJob)
            f.assertHealthy()
            val secondReport = checkNotNull(result.get())
            return@withFixture {
                assertTrue(originalStillHeld)
                assertTrue(escapedNotCancelled)
                assertTrue(ineligible(first).reasons.isNotEmpty())
                assertSame(GossipWorkOutcome.Dispatching,
                    observed(cancellations(first).managers[1]).records.single().outcome)
                assertTrue(lateCancellation)
                assertTrue(collectorWaiting)
                val witness = joined(joins(secondReport).managers[1])
                assertSame(actualLateJob, witness.joinedJobs.single())
                assertSame(ticket, witness.originalSnapshot.records.single().ticket)
                assertMissingProofs(first)
                assertMissingProofs(secondReport)
            }
        }
    }

    @Test fun unknownDispatchDebtNeverProducesJoinedBatch() = runTest {
        withFixture { f ->
            val held = f.held()
            val second = f.child()
            val unknown = f.reserved(second)
            val failure = IllegalStateException("Synthetic opaque creator failure")
            val actualFailure = runCatching {
                second.work.dispatch(unknown, f.gossipScope, f.dispatcher, { _, _, _ -> throw failure }) {}
            }.exceptionOrNull()
            runCurrent()
            check(held.entered.isCompleted)
            f.fence()
            val report = f.collect()
            val heldAtReturn = held.job.isCancelled && !held.job.isCompleted
            held.release.complete(Unit)
            f.join(held.job)
            f.assertHealthy()
            return@withFixture {
                assertSame(failure, actualFailure)
                assertTrue(heldAtReturn)
                assertEquals(GossipRecordedJoinGap.UnknownTicket,
                    (ineligible(report).reasons.single() as KnownRecordedJoinRejection.Manager).gap)
                assertSame(GossipWorkOutcome.Unknown,
                    observed(cancellations(report).managers[1]).records.single().outcome)
                assertSame(unknown, observed(cancellations(report).managers[1]).records.single().ticket)
                assertMissingProofs(report)
            }
        }
    }

    @Test fun foreignBindingReceiptSnapshotAndCallerPreparationsReject() = runTest {
        withFixture { f ->
            val held = f.held()
            val second = f.child()
            val other = f.own(Job())
            f.returned(second, other)
            runCurrent()
            check(held.entered.isCompleted)
            f.fence()
            val oldPhase = f.cancelOnly()
            val originals = cancellations(oldPhase).managers
            val a = originals[0].originalClose
            val b = originals[1].originalClose
            val snapshotA = observed(originals[0])
            val snapshotB = observed(originals[1])
            val foreignBinding = a.manager.prepareRecordedFrontierJoin(b.binding, a.receipt, snapshotA)
            val foreignReceipt = a.manager.prepareRecordedFrontierJoin(a.binding, b.receipt, snapshotA)
            val foreignSnapshot = a.manager.prepareRecordedFrontierJoin(a.binding, a.receipt, snapshotB)
            val ready = a.manager.prepareRecordedFrontierJoin(a.binding, a.receipt, snapshotA)
                as GossipRecordedFrontierPreparation.Ready
            val foreignPreparation = b.manager.joinPreparedRecordedFrontier(ready.frontier)
            val wrongCaller = AtomicReference<GossipRecordedFrontierJoinOutcome>()
            val worker = f.own(f.callerScope(f.own(SupervisorJob())).launch {
                wrongCaller.set(a.manager.joinPreparedRecordedFrontier(ready.frontier))
            })
            f.join(worker)
            val heldAfterRejections = held.job.isCancelled && !held.job.isCompleted && !held.release.isCompleted
            held.release.complete(Unit)
            f.join(held.job)
            f.join(other)
            f.assertHealthy()
            val actualWrongCaller = checkNotNull(wrongCaller.get())
            return@withFixture {
                assertEquals(GossipRecordedJoinGap.ForeignBinding, (foreignBinding as GossipRecordedFrontierPreparation.Unconfirmed).gap)
                assertEquals(GossipRecordedJoinGap.ForeignReceipt, (foreignReceipt as GossipRecordedFrontierPreparation.Unconfirmed).gap)
                assertEquals(GossipRecordedJoinGap.ForeignSnapshot, (foreignSnapshot as GossipRecordedFrontierPreparation.Unconfirmed).gap)
                assertEquals(GossipRecordedJoinGap.ForeignPreparation, (foreignPreparation as GossipRecordedFrontierJoinOutcome.Unconfirmed).gap)
                assertEquals(GossipRecordedJoinGap.WrongJoinCaller, (actualWrongCaller as GossipRecordedFrontierJoinOutcome.Unconfirmed).gap)
                assertTrue(heldAfterRejections)
                assertMissingProofs(oldPhase)
            }
        }
    }

    @Test fun selfAndAncestorBatchSkipsPreventAllJoins() = runTest {
        withFixture { f ->
            val held = f.held()
            runCurrent()
            check(held.entered.isCompleted)
            val parent = f.own(SupervisorJob())
            val result = AtomicReference<PanicRetirementReport>()
            val activity = AtomicReference<Boolean>()
            val caller = f.own(f.callerScope(parent).launch {
                val self = checkNotNull(currentCoroutineContext()[Job])
                check(self !== f.facadeJob && parent !== f.facadeJob)
                f.returned(f.initial, self)
                f.returned(f.initial, parent)
                f.fence()
                result.set(f.collect())
                activity.set(self.isActive && parent.isActive)
            })
            f.join(caller)
            val callerCancelled = caller.isCancelled
            val heldAtReturn = held.job.isCancelled && !held.job.isCompleted
            val parentActive = parent.isActive
            val actualActivity = activity.get()
            val report = checkNotNull(result.get())
            held.release.complete(Unit)
            parent.cancel()
            f.join(parent)
            f.join(held.job)
            f.assertHealthy()
            return@withFixture {
                assertFalse(callerCancelled)
                assertTrue(parentActive)
                assertEquals(true, actualActivity)
                assertTrue(heldAtReturn)
                assertEquals(KnownRecordedBatchGap.UnsafeCancellation,
                    (ineligible(report).reasons.single() as KnownRecordedJoinRejection.Batch).gap)
                assertMissingProofs(report)
            }
        }
    }

    @Test fun callerCancellationDuringInlineJoinKeepsRetryableOriginalRecords() = runTest {
        withFixture { f ->
            val held = f.held()
            runCurrent()
            check(held.entered.isCompleted)
            f.fence()
            val result = AtomicReference<PanicRetirementReport>()
            val collector = f.collectAsync(result)
            runCurrent()
            val waiting = !collector.isCompleted && held.job.isCancelled && !held.job.isCompleted
            val facadeCompleted = f.facadeJob.isCompleted
            val binding = f.initial.manager.captureJobPanicBinding()
            val receipt = (f.initial.manager.closeJobRootsForPanic(binding) as GossipManagerRootCloseOutcome.Closed).receipt
            collector.cancel(CancellationException("Synthetic joining caller cancellation"))
            f.join(collector)
            val cancelled = collector.isCancelled
            val resultAbsent = result.get() == null
            val stillHeld = !held.job.isCompleted && !held.release.isCompleted
            held.release.complete(Unit)
            f.join(held.job)
            val retry = f.collect()
            f.assertHealthy()
            return@withFixture {
                assertTrue(waiting)
                assertTrue(facadeCompleted)
                assertTrue(cancelled)
                assertTrue(resultAbsent)
                assertTrue(stillHeld)
                val witness = joined(joins(retry).managers.single())
                assertSame(held.job, witness.joinedJobs.single())
                assertSame(held.ticket, witness.originalSnapshot.records.single().ticket)
                assertSame(binding, witness.originalSnapshot.binding)
                assertSame(receipt, witness.originalSnapshot.receipt)
                assertMissingProofs(retry)
            }
        }
    }

    @Test fun aliasedHandlesPreserveAllOwnerWitnessOrigins() = runTest {
        withFixture { f ->
            val second = f.child()
            val shared = f.own(Job())
            val firstTicket = f.returned(f.initial, shared)
            val secondTicket = f.returned(second, shared)
            f.fence()
            val report = f.collect()
            val completeBeforeCleanup = shared.isCancelled && shared.isCompleted
            f.join(shared)
            f.assertHealthy()
            return@withFixture {
                assertTrue(completeBeforeCleanup)
                val actual = joins(report)
                assertEquals(2, actual.managers.size)
                actual.managers.zip(cancellations(report).managers).forEach { (result, original) ->
                    assertSame(original, result.original)
                    assertSame(observed(original), joined(result).originalSnapshot)
                    assertSame(shared, joined(result).joinedJobs.single())
                }
                assertSame(firstTicket, joined(actual.managers[0]).originalSnapshot.records.single().ticket)
                assertSame(secondTicket, joined(actual.managers[1]).originalSnapshot.records.single().ticket)
                assertMissingProofs(report)
            }
        }
    }

    @Test fun emptyAndMissingInputsDoNotManufactureJoinedEvidence() = runTest {
        withFixture { f ->
            f.fence()
            val report = f.collect()
            val facadeCompleted = f.facadeJob.isCompleted
            f.assertHealthy()
            return@withFixture {
                assertTrue(facadeCompleted)
                val result = joins(report).managers.single()
                val empty = (result.outcome as GossipRecordedFrontierJoinOutcome.EmptyFrontierValidated).witness
                assertSame(observed(result.original), empty.originalSnapshot)
                assertSame(result.original.originalClose.binding, empty.binding)
                assertSame(result.original.originalClose.receipt, empty.receipt)
                assertTrue(empty.originalSnapshot.records.isEmpty())
                assertMissingProofs(report)
            }
        }
        withFixture { f ->
            val invalid = f.invalid()
            val activeAfterInvalid = f.facadeJob.isActive
            val rootOpenAfterInvalid = !f.closed(f.initial)
            val missing = f.missing()
            val completedAfterMissing = f.facadeJob.isCompleted
            f.assertHealthy()
            return@withFixture {
                assertEquals(PanicRetirementGap.InstalledFacadeMismatch, (invalid.facade as PanicOwnerRetirement.Unconfirmed).gap)
                assertTrue(activeAfterInvalid)
                assertTrue(rootOpenAfterInvalid)
                assertTrue(completedAfterMissing)
                assertSame(PanicUnconfirmedEvidence.NotCollected,
                    (missing.gossipJobsAndWriters as PanicOwnerRetirement.Unconfirmed).evidence)
                assertMissingProofs(invalid)
                assertMissingProofs(missing)
            }
        }
    }

    private class Managed(val manager: GossipSyncManager, val work: GossipWorkOwner)

    private class Held(val job: Job, val ticket: GossipWorkTicket,
        val entered: CompletableDeferred<Unit>, val release: CompletableDeferred<Unit>)

    private class Fixture(scope: TestScope) {
        val facadeJob = SupervisorJob()
        private val gossipJob = SupervisorJob()
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        private val jobs = CopyOnWriteArrayList<Job>()
        private val workers = CopyOnWriteArrayList<Thread>()
        private val gates = CopyOnWriteArrayList<CountDownLatch>()
        private val suspendedGates = CopyOnWriteArrayList<CompletableDeferred<Unit>>()
        private val workerErrors = CopyOnWriteArrayList<Throwable>()
        val callbackErrors = CopyOnWriteArrayList<Throwable>()
        private val managers = CopyOnWriteArrayList<Managed>()
        private val coroutineErrors = CoroutineExceptionHandler { _, failure -> workerErrors += failure }
        val gossipScope = CoroutineScope(gossipJob + dispatcher + coroutineErrors)
        private val transport = InertMesh()
        val holder = MeshServiceHolderOwner(MeshServiceHolderPorts<InertMesh, Any, GossipSyncManager>(
            isReusable = { it.reusable }, stopBluetooth = {}, refreshUnified = {}, startGossip = {},
            stopGossip = { it.stop() }, clearGossip = {},
            installGossipDelegate = { manager, delegate -> manager.delegate = delegate },
            broadcast = {}, sendToPeer = { _, _ -> }, log = { _, _ -> }
        ))
        private lateinit var context: HolderConstructionContext
        lateinit var initial: Managed
            private set
        val facade = acquired(holder.getUnifiedOrCreate({ original ->
            context = original
            initial = child()
            holder.setGossipManager(HolderManagerProvenance.Constructed(original), initial.manager) { it }
            transport
        }) { bluetooth ->
            UnifiedMeshService(bluetooth, UnifiedMeshPorts(
                scope = CoroutineScope(facadeJob + dispatcher + coroutineErrors), announcementProfile = MutableStateFlow(60_000L to false),
                hasAnnouncementRecipients = { false }, currentWifi = { null }, startWifi = {}, stopWifi = {},
                bleEnabled = { true }, disableBle = {}, currentNpub = { null }
            ))
        }) as UnifiedMeshService
        private val binding = captureHolderPanicBindingWithGossip(holder, facade)

        fun child(): Managed {
            val work = GossipWorkOwner()
            lateinit var actual: Managed
            val manager = holder.constructGossipManager(HolderGossipParent.Constructed(context), HolderGossipSource.BLE_SERVICE) {
                GossipSyncManager("1122334455667788", gossipScope, object : GossipSyncManager.ConfigProvider {
                    override fun seenCapacity() = 8
                    override fun gcsMaxBytes() = 128
                    override fun gcsTargetFpr() = 0.01
                }, workOwner = work, jobCreator = { _, _, _ ->
                    throw AssertionError("Recorded-join fixture must not start manager IO loops")
                }).also { actual = Managed(it, work) }
            }
            check(manager === actual.manager)
            return actual.also { managers += it }
        }

        fun callerScope(parent: Job) = CoroutineScope(parent + dispatcher + coroutineErrors)
        fun <T : Job> own(job: T): T = job.also { actual -> if (jobs.none { it === actual }) jobs += actual }
        fun launchOriginal(body: suspend CoroutineScope.() -> Unit) = own(launchOriginalGossipJob(gossipScope, dispatcher, body))
        fun root(managed: Managed): GossipRootLease = when (val root = managed.work.reserveRoot(
            managed.manager.captureJobPanicBinding(), GossipRootKind.INITIAL_BROADCAST
        )) {
            is GossipRootReservation.Accepted -> root.lease
            is GossipRootReservation.Refused -> throw AssertionError("Expected original accepted fixture root")
        }
        fun reserved(managed: Managed): GossipWorkTicket {
            val root = root(managed)
            return try { managed.work.reserveChild(root, GossipWorkKind.INITIAL_BROADCAST) }
            finally { managed.work.finishRoot(root) }
        }
        fun returned(managed: Managed, job: Job): GossipWorkTicket {
            own(job)
            val ticket = reserved(managed)
            assertSame(job, managed.work.dispatch(ticket, gossipScope, dispatcher, { _, _, _ -> job }) {})
            return ticket
        }
        fun closed(managed: Managed): Boolean = when (val root = managed.work.reserveRoot(
            managed.manager.captureJobPanicBinding(), GossipRootKind.INITIAL_BROADCAST
        )) {
            is GossipRootReservation.Refused -> true
            is GossipRootReservation.Accepted -> { managed.work.finishRoot(root.lease); false }
        }
        fun observeCompletion(job: Job, action: () -> Unit) {
            job.invokeOnCompletion {
                try { action() } catch (failure: Throwable) { callbackErrors += failure; throw failure }
            }
        }
        fun fence() {
            check(PanicResetCoordinator.fenceCapturedPanicOwners(facade, binding, facade) === HolderConstructionCloseOutcome.ClosedForConstruction)
        }
        suspend fun collect() = withTimeout(WAIT_MS) {
            collectPanicRetirementWithRecordedJobsJoin(facade, PanicOriginalGossipInput.Installed(binding, facade))
        }
        suspend fun cancelOnly() = withTimeout(WAIT_MS) {
            collectPanicRetirement(facade, PanicOriginalGossipInput.Installed(binding, facade))
        }
        suspend fun missing() = withTimeout(WAIT_MS) {
            collectPanicRetirementWithRecordedJobsJoin(facade, PanicOriginalGossipInput.MissingCapturedHolder)
        }
        suspend fun invalid() = withTimeout(WAIT_MS) {
            collectPanicRetirementWithRecordedJobsJoin(facade, PanicOriginalGossipInput.Installed(binding, Any()))
        }
        fun collectAsync(result: AtomicReference<PanicRetirementReport>): Job =
            own(callerScope(own(SupervisorJob())).launch { result.set(collect()) })
        fun held(managed: Managed = initial): Held {
            val entered = CompletableDeferred<Unit>()
            val release = suspendedGate()
            val job = launchOriginal {
                launch { withContext(NonCancellable) { entered.complete(Unit); release.await() } }
                awaitCancellation()
            }
            return Held(job, returned(managed, job), entered, release)
        }
        suspend fun join(job: Job) { withTimeout(WAIT_MS) { job.join() } }
        fun gate() = CountDownLatch(1).also { gates += it }
        fun suspendedGate() = CompletableDeferred<Unit>().also { suspendedGates += it }
        fun worker(action: () -> Unit): Thread = Thread {
            try { action() } catch (failure: Throwable) { workerErrors += failure }
        }.apply { isDaemon = true; workers += this; start() }
        fun reap(worker: Thread) {
            worker.join(WAIT_MS)
            assertFalse("Owned creator exceeded its completion bound", worker.isAlive)
            assertHealthy()
        }
        fun assertHealthy() {
            val errors = workerErrors.toList() + callbackErrors.toList()
            if (errors.isNotEmpty()) {
                val failure = AssertionError("Unexpected owned fixture failure")
                failure.initCause(errors.first())
                errors.drop(1).forEach { if (it !== failure && it !== failure.cause) failure.addSuppressed(it) }
                throw failure
            }
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
            suspendedGates.forEach { it.complete(Unit) }
            workers.forEach { worker ->
                cleanup {
                    var boundedFailure: Throwable? = null
                    try { worker.join(WAIT_MS) } catch (failure: Throwable) { boundedFailure = failure }
                    if (worker.isAlive) {
                        val original = boundedFailure ?: AssertionError("Owned creator exceeded its first completion bound")
                        try { worker.interrupt(); worker.join(WAIT_MS); assertFalse("Owned creator must be reaped", worker.isAlive) }
                        catch (failure: Throwable) { if (original !== failure) original.addSuppressed(failure) }
                        throw original
                    }
                    boundedFailure?.let { throw it }
                }
                // A guard failure is retained above; it never substitutes for the actual reap.
                while (true) {
                    try { worker.join(); break }
                    catch (failure: Throwable) { cleanup { throw failure } }
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
                cleanup { job.cancel() }
                // No timeout result or cancellation return claims that the same Job has joined.
                while (true) {
                    try { job.join(); break }
                    catch (failure: Throwable) { cleanup { throw failure } }
                }
            }
            cleanup { assertHealthy() }
            primary?.let { throw it }
        }
    }

    private suspend fun TestScope.withFixture(block: suspend (Fixture) -> (() -> Unit)) {
        val fixture = Fixture(this)
        var primary: Throwable? = null
        var postconditions: (() -> Unit)? = null
        try { postconditions = block(fixture); fixture.assertHealthy() } catch (failure: Throwable) { primary = failure }
        finally {
            try { fixture.close() } catch (failure: Throwable) {
                val original = primary
                if (original == null) primary = failure else if (original !== failure) original.addSuppressed(failure)
            }
        }
        primary?.let { throw it }
        checkNotNull(postconditions).invoke()
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
        private fun await(gate: CountDownLatch, reason: String) {
            if (!gate.await(WAIT_MS, TimeUnit.MILLISECONDS)) throw AssertionError("Timed out: $reason")
        }
        private fun <T : Any> acquired(result: HolderAcquisitionResult<T>): T = when (result) {
            is HolderAcquisitionResult.Available -> result.product
            HolderAcquisitionResult.RefusedBeforeAcquisition -> throw AssertionError("Expected original acquisition")
        }
        private fun known(report: PanicRetirementReport) =
            (report.gossipJobsAndWriters as PanicOwnerRetirement.Unconfirmed).evidence as PanicUnconfirmedEvidence.KnownOriginalGossip
        private fun cancellations(report: PanicRetirementReport) =
            known(report).returnedJobsCancellation as KnownReturnedJobsCancellation.Attempted
        private fun observed(manager: KnownManagerReturnedJobs) =
            ((manager.outcome as KnownManagerJobCaptureOutcome.Captured).result as GossipReturnedJobsCapture.Observed).snapshot
        private fun joins(report: PanicRetirementReport) =
            known(report).recordedFrontierJoin as KnownRecordedFrontiersJoin.Attempted
        private fun ineligible(report: PanicRetirementReport) =
            known(report).recordedFrontierJoin as KnownRecordedFrontiersJoin.Ineligible
        private fun joined(manager: KnownRecordedManagerJoin) =
            (manager.outcome as GossipRecordedFrontierJoinOutcome.Joined).witness
        private fun assertMissingProofs(report: PanicRetirementReport) {
            assertFalse(report.confirmed)
            listOf(report.wifiController, report.holderConstructionAndCallbacks, report.gossipJobsAndWriters,
                report.transportCoreRadioAndHandlers, report.startupAndRemainingSharedKeyUsers).forEach {
                assertEquals(PanicRetirementGap.ProducerNotImplemented, (it as PanicOwnerRetirement.Unconfirmed).gap)
            }
        }
    }
}
