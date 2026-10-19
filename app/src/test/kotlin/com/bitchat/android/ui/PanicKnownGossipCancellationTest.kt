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

/** Actual captured owners, managers and finite Jobs; no platform graph or manager IO loop. */
@OptIn(ExperimentalCoroutinesApi::class)
class PanicKnownGossipCancellationTest {
    @Test fun actualCollectorClosesAllManagersBeforeAnyCancellation() = runTest {
        withFixture { f ->
            val second = f.child()
            val firstJob = f.own(Job())
            val secondJob = f.own(Job())
            val callbacks = mutableListOf<Pair<Boolean, Boolean>>()
            f.observeCompletion(firstJob) {
                callbacks += (f.closed(f.initial) && f.closed(second)) to f.facadeJob.isActive
            }
            f.observeCompletion(secondJob) {
                callbacks += (f.closed(f.initial) && f.closed(second)) to f.facadeJob.isActive
            }
            val firstTicket = f.returned(f.initial, firstJob)
            val secondTicket = f.returned(second, secondJob)
            f.fence()
            var erases = 0
            var report: PanicRetirementReport? = null
            val admitted = PanicResetCoordinator.admitDestructivePanicOwners(
                establishIntent = { true }, retireOwners = { f.collect().also { report = it } },
                eraseOwnedData = { erases++; true }
            )
            val cancellationObserved = firstJob.isCancelled to secondJob.isCancelled
            val facadeCompleted = f.facadeJob.isCompleted
            val actualCallbacks = callbacks.toList()
            f.join(firstJob)
            f.join(secondJob)
            f.assertHealthy()
            val actual = checkNotNull(report)
            val cancellation = attempted(actual)
            return@withFixture {
                assertEquals(listOf(true to true, true to true), actualCallbacks)
                assertEquals(true to true, cancellationObserved)
                assertTrue(facadeCompleted)
                assertEquals(2, cancellation.managers.size)
                assertEquals(2, cancellation.requests.size)
                assertTrue(cancellation.requests.all { it.outcome == JobCancellationRequestOutcome.RequestAttempted })
                cancellation.managers.forEach { manager ->
                    val snapshot = observed(manager)
                    assertSame(manager.originalClose.binding, snapshot.binding)
                    assertSame(manager.originalClose.receipt, snapshot.receipt)
                }
                assertSame(firstTicket, cancellation.requests[0].origins.single().tickets.single())
                assertSame(secondTicket, cancellation.requests[1].origins.single().tickets.single())
                assertFalse(admitted)
                assertEquals(0, erases)
                assertMissingProofs(actual)
            }
        }
    }

    @Test fun cancellationReturnsWithOriginalNonCancellableChildStillActive() = runTest {
        withFixture { f ->
            val entered = CompletableDeferred<Unit>()
            val release = f.suspendedGate()
            val original = f.launchOriginal {
                launch { withContext(NonCancellable) { entered.complete(Unit); release.await() } }
                awaitCancellation()
            }
            f.returned(f.initial, original)
            runCurrent()
            assertTrue("The actual NonCancellable child must be entered", entered.isCompleted)
            f.fence()
            val report = f.collect()
            val cancelledWhileChildHeld = original.isCancelled
            val completedWhileChildHeld = original.isCompleted
            val childStillHeld = !release.isCompleted
            release.complete(Unit)
            f.join(original)
            f.assertHealthy()
            return@withFixture {
                assertTrue(cancelledWhileChildHeld)
                assertFalse("Cancellation requests must not claim to join the held child", completedWhileChildHeld)
                assertTrue(childStillHeld)
                assertEquals(JobCancellationRequestOutcome.RequestAttempted, attempted(report).requests.single().outcome)
                assertMissingProofs(report)
            }
        }
    }

    @Test fun heldCreatorLateReturnedHandleRequiresExplicitSecondCollection() = runTest {
        withFixture { f ->
            val entered = f.gate()
            val release = f.gate()
            val bodyEntered = CompletableDeferred<Unit>()
            val escaped = AtomicReference<Job>()
            val root = f.root(f.initial)
            val ticket = f.initial.work.reserveChild(root, GossipWorkKind.INITIAL_BROADCAST)
            val worker = f.worker {
                try {
                    f.initial.work.dispatch(ticket, f.gossipScope, f.dispatcher, { scope, context, body ->
                        val actual = f.own(launchOriginalGossipJob(scope, context, body))
                        escaped.set(actual)
                        entered.countDown()
                        await(release, "owned original creator return")
                        actual
                    }) { bodyEntered.complete(Unit); awaitCancellation() }
                } finally { f.initial.work.finishRoot(root) }
            }
            await(entered, "actual creator handle before return")
            runCurrent()
            assertTrue("Original finite body must be entered", bodyEntered.isCompleted)
            f.fence()
            val firstReport = f.collect()
            val beforeReturnCancelled = checkNotNull(escaped.get()).isCancelled
            val first = attempted(firstReport)
            release.countDown()
            f.reap(worker)
            val secondReport = f.collect()
            val actualJob = checkNotNull(escaped.get())
            val cancelledAfterSecond = actualJob.isCancelled
            f.join(actualJob)
            f.assertHealthy()
            return@withFixture {
                assertFalse(beforeReturnCancelled)
                assertTrue(first.requests.isEmpty())
                assertSame(GossipWorkOutcome.Dispatching, observed(first.managers.single()).records.single().outcome)
                val second = attempted(secondReport)
                assertSame(actualJob, second.requests.single().originalJob)
                assertSame(ticket, second.requests.single().origins.single().tickets.single())
                assertTrue(cancelledAfterSecond)
                assertSame(GossipRootState.FINISHED, observed(second.managers.single()).roots.single().state)
                assertMissingProofs(firstReport)
                assertMissingProofs(secondReport)
            }
        }
    }

    @Test fun exactOriginalBindingAndReceiptRejectBeforeEffects() = runTest {
        withFixture { f ->
            val second = f.child()
            val job = f.own(Job())
            f.returned(f.initial, job)
            val firstBinding = f.initial.manager.captureJobPanicBinding()
            val secondBinding = second.manager.captureJobPanicBinding()
            val firstClose = close(f.initial)
            val secondClose = close(second)
            val foreignManagerBinding = f.initial.manager.captureReturnedJobsForCancellation(secondBinding, firstClose.receipt)
            val foreignReceipt = f.initial.manager.captureReturnedJobsForCancellation(firstBinding, secondClose.receipt)
            val foreignOwnerBinding = f.initial.work.captureReturnedJobsForCancellation(secondBinding, firstClose.receipt)
            val validCapture = f.initial.manager.captureReturnedJobsForCancellation(firstBinding, firstClose.receipt)
            val activeBeforeCollection = job.isActive
            f.fence()
            val report = f.collect()
            val cancelledByCollection = job.isCancelled
            f.join(job)
            f.assertHealthy()
            return@withFixture {
                assertSame(GossipReturnedJobsCapture.ForeignBinding, foreignManagerBinding)
                assertSame(GossipReturnedJobsCapture.ForeignCloseReceipt, foreignReceipt)
                assertSame(GossipReturnedJobsCapture.ForeignBinding, foreignOwnerBinding)
                val valid = validCapture as GossipReturnedJobsCapture.Observed
                assertSame(firstBinding, valid.snapshot.binding)
                assertSame(firstClose.receipt, valid.snapshot.receipt)
                assertSame(job, (valid.snapshot.records.single().outcome as GossipWorkOutcome.Returned).job)
                assertTrue(activeBeforeCollection)
                assertTrue(cancelledByCollection)
                assertSame(firstClose.receipt, attempted(report).managers.first().originalClose.receipt)
                assertMissingProofs(report)
            }
        }
    }

    @Test fun realCancellationHandlerFailureDoesNotSkipLaterHandle() = runTest {
        withFixture { f ->
            val first = f.own(Job())
            val second = f.own(Job())
            val failure = IllegalStateException("Synthetic completion handler failure")
            f.expectedCallbackErrors += failure
            f.observeCompletion(first) { throw failure }
            f.returned(f.initial, first)
            f.returned(f.initial, second)
            f.fence()
            val report = f.collect()
            val actualCancellation = first.isCancelled to second.isCancelled
            val actualFailureCount = f.callbackErrors.count { it === failure }
            f.join(first)
            f.join(second)
            f.assertHealthy()
            val requests = attempted(report).requests
            return@withFixture {
                assertEquals(1, actualFailureCount)
                assertEquals(2, requests.size)
                assertSame(first, requests[0].originalJob)
                assertEquals(JobCancellationRequestOutcome.OperationFailed, requests[0].outcome)
                assertSame(second, requests[1].originalJob)
                assertEquals(JobCancellationRequestOutcome.RequestAttempted, requests[1].outcome)
                assertEquals(true to true, actualCancellation)
                assertMissingProofs(report)
            }
        }
    }

    @Test fun aliasedReturnedHandlesKeepAllOriginsAndOneRequestRecord() = runTest {
        withFixture { f ->
            val second = f.child()
            val original = f.own(Job())
            val firstTicket = f.returned(f.initial, original)
            val secondTicket = f.returned(second, original)
            f.fence()
            val report = f.collect()
            val originalCancelled = original.isCancelled
            f.join(original)
            f.assertHealthy()
            val actual = attempted(report)
            val request = actual.requests.single()
            return@withFixture {
                assertSame(original, request.originalJob)
                assertEquals(JobCancellationRequestOutcome.RequestAttempted, request.outcome)
                assertEquals(2, request.origins.size)
                assertSame(actual.managers[0], request.origins[0].managerSnapshot)
                assertSame(actual.managers[1], request.origins[1].managerSnapshot)
                assertSame(firstTicket, request.origins[0].tickets.single())
                assertSame(secondTicket, request.origins[1].tickets.single())
                request.origins.forEach {
                    assertSame(original, (observed(it.managerSnapshot).records.single().outcome as GossipWorkOutcome.Returned).job)
                }
                assertTrue(originalCancelled)
                assertMissingProofs(report)
            }
        }
    }

    @Test fun actualCallerAndAncestorHandlesAreSkipped() = runTest {
        withFixture { f ->
            val callerParent = f.own(SupervisorJob())
            val callerScope = f.callerScope(callerParent)
            val unrelated = f.own(Job())
            val report = AtomicReference<PanicRetirementReport>()
            val stayedActive = AtomicReference<Boolean>()
            val caller = f.own(callerScope.launch {
                val originalInvoker = checkNotNull(currentCoroutineContext()[Job])
                check(originalInvoker !== f.facadeJob && callerParent !== f.facadeJob)
                f.returned(f.initial, originalInvoker)
                f.returned(f.initial, callerParent)
                f.returned(f.initial, unrelated)
                f.fence()
                report.set(f.collect())
                stayedActive.set(originalInvoker.isActive && callerParent.isActive)
            })
            f.join(caller)
            val parentStayedActive = callerParent.isActive
            val callerWasCancelled = caller.isCancelled
            val unrelatedCancelled = unrelated.isCancelled
            callerParent.cancel()
            f.join(callerParent)
            f.join(unrelated)
            f.assertHealthy()
            val actual = attempted(checkNotNull(report.get()))
            val callerStayedActive = stayedActive.get()
            return@withFixture {
                assertEquals(true, callerStayedActive)
                assertTrue(parentStayedActive)
                assertFalse(callerWasCancelled)
                assertTrue(unrelatedCancelled)
                assertEquals(listOf(JobCancellationRequestOutcome.SkippedCallerRelated,
                    JobCancellationRequestOutcome.SkippedCallerRelated, JobCancellationRequestOutcome.RequestAttempted),
                    actual.requests.map { it.outcome })
                assertMissingProofs(checkNotNull(report.get()))
            }
        }
    }

    @Test fun repeatedCollectionPreservesReservedUnknownAndLateDebt() = runTest {
        withFixture { f ->
            val late = f.own(Job())
            val alreadyReturned = f.own(Job())
            val reserved = f.reserved(f.initial)
            val unknown = f.reserved(f.initial)
            val failure = IllegalStateException("Synthetic opaque dispatch failure")
            val failed = runCatching {
                f.initial.work.dispatch(unknown, f.gossipScope, f.dispatcher, { _, _, _ -> throw failure }) {}
            }
            val returned = f.returned(f.initial, alreadyReturned)
            f.observeCompletion(alreadyReturned) {
                f.initial.work.dispatch(reserved, f.gossipScope, f.dispatcher, { _, _, _ -> late }) {}
            }
            f.fence()
            val firstReport = f.collect()
            val lateStillActiveAfterFirst = late.isActive
            val first = attempted(firstReport)
            val firstSnapshot = observed(first.managers.single())
            val secondReport = f.collect()
            val lateCancelledBySecond = late.isCancelled
            f.join(alreadyReturned)
            f.join(late)
            f.assertHealthy()
            val second = attempted(secondReport)
            val secondSnapshot = observed(second.managers.single())
            return@withFixture {
                assertSame(failure, failed.exceptionOrNull())
                assertTrue(lateStillActiveAfterFirst)
                assertEquals(1, first.requests.size)
                assertSame(returned, first.requests.single().origins.single().tickets.single())
                assertSame(GossipWorkOutcome.Reserved, firstSnapshot.records.single { it.ticket === reserved }.outcome)
                assertSame(GossipWorkOutcome.Unknown, firstSnapshot.records.single { it.ticket === unknown }.outcome)
                assertEquals(2, second.requests.size)
                assertSame(late, second.requests.first { it.originalJob === late }.originalJob)
                assertSame(GossipWorkOutcome.Unknown, secondSnapshot.records.single { it.ticket === unknown }.outcome)
                assertEquals(firstSnapshot.records.map { it.ticket }, secondSnapshot.records.map { it.ticket })
                assertSame(first.managers.single().originalClose.receipt, second.managers.single().originalClose.receipt)
                assertTrue(lateCancelledBySecond)
                assertMissingProofs(firstReport)
                assertMissingProofs(secondReport)
            }
        }
    }

    @Test fun handlerCancellationOfCallerStillAttemptsWholeFiniteBatch() = runTest {
        withFixture { f ->
            val callerParent = f.own(SupervisorJob())
            val callerScope = f.callerScope(callerParent)
            val first = f.own(Job())
            val second = f.own(Job())
            val observedCancellation = AtomicReference<Throwable>()
            val cause = CancellationException("Synthetic caller cancellation")
            val caller = f.own(callerScope.launch {
                val originalInvoker = checkNotNull(currentCoroutineContext()[Job])
                check(originalInvoker !== f.facadeJob && callerParent !== f.facadeJob)
                f.observeCompletion(first) { originalInvoker.cancel(cause) }
                f.returned(f.initial, first)
                f.returned(f.initial, second)
                f.fence()
                observedCancellation.set(runCatching { f.collect() }.exceptionOrNull())
            })
            f.join(caller)
            val callerWasCancelled = caller.isCancelled
            val requestedCancellation = first.isCancelled to second.isCancelled
            val actualCancellation = observedCancellation.get()
            f.join(first)
            f.join(second)
            val facadeNotRetired = f.facadeJob.isActive
            callerParent.cancel()
            f.join(callerParent)
            f.assertHealthy()
            return@withFixture {
                assertTrue(callerWasCancelled)
                assertEquals("The finite batch must reach the later Job before activity propagation", true to true, requestedCancellation)
                assertTrue(actualCancellation is CancellationException)
                assertTrue("Caller cancellation propagates before facade retirement", facadeNotRetired)
            }
        }
    }

    private class Managed(val manager: GossipSyncManager, val work: GossipWorkOwner)

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
        val expectedCallbackErrors = CopyOnWriteArrayList<Throwable>()
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
                    throw AssertionError("Cancellation fixture must not start manager IO loops")
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
        suspend fun collect() = collectPanicRetirement(facade, PanicOriginalGossipInput.Installed(binding, facade))
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
            val errors = workerErrors.toList() + callbackErrors.filter { actual -> expectedCallbackErrors.none { it === actual } }
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
        private fun close(managed: Managed) = managed.manager.closeJobRootsForPanic(managed.manager.captureJobPanicBinding())
            as GossipManagerRootCloseOutcome.Closed
        private fun observed(manager: KnownManagerReturnedJobs) =
            ((manager.outcome as KnownManagerJobCaptureOutcome.Captured).result as GossipReturnedJobsCapture.Observed).snapshot
        private fun attempted(report: PanicRetirementReport) =
            ((report.gossipJobsAndWriters as PanicOwnerRetirement.Unconfirmed).evidence as PanicUnconfirmedEvidence.KnownOriginalGossip)
                .returnedJobsCancellation as KnownReturnedJobsCancellation.Attempted
        private fun assertMissingProofs(report: PanicRetirementReport) {
            assertFalse(report.confirmed)
            listOf(report.wifiController, report.holderConstructionAndCallbacks, report.gossipJobsAndWriters,
                report.transportCoreRadioAndHandlers, report.startupAndRemainingSharedKeyUsers).forEach {
                assertEquals(PanicRetirementGap.ProducerNotImplemented, (it as PanicOwnerRetirement.Unconfirmed).gap)
            }
        }
    }
}
