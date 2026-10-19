package com.bitchat.android.sync

import android.app.Application
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

/** Actual dispatch records and actual owned Jobs; no transport, provider or key construction. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class GossipWorkOriginsTest {
    @Test fun productionCreatorPreservesOriginalScopeChildReceiverAndIoContext() = runTest {
        val parent = SupervisorJob()
        val originalScope = CoroutineScope(parent + StandardTestDispatcher(testScheduler))
        val entered = CompletableDeferred<Pair<Job, ContinuationInterceptor?>>()
        val release = CompletableDeferred<Unit>()
        var actual: Job? = null
        var primary: Throwable? = null
        try {
            val launched = launchOriginalGossipJob(originalScope, Dispatchers.IO) {
                entered.complete(requireNotNull(coroutineContext[Job]) to coroutineContext[ContinuationInterceptor])
                release.await()
            }
            actual = launched
            val (receiver, dispatcher) = withContext(Dispatchers.IO) {
                withTimeout(5_000) { entered.await() }
            }
            assertSame(launched, receiver)
            assertSame(Dispatchers.IO, dispatcher)
            assertTrue(parent.children.any { it === launched })
            assertTrue(launched.isActive)
        } catch (failure: Throwable) { primary = failure }
        finally {
            release.complete(Unit)
            primary = preserveFailure(primary) {
                withContext(NonCancellable + Dispatchers.IO) {
                    withTimeout(5_000) { actual?.cancelAndJoin() }
                }
            }
            primary = preserveFailure(primary) {
                withContext(NonCancellable + Dispatchers.IO) {
                    withTimeout(5_000) { parent.cancelAndJoin() }
                }
            }
        }
        primary?.let { throw it }
    }

    @Test fun allFourManagerSitesRetainExactJobsAndRequestedContext() = runTest {
        withFixture { f ->
            f.manager.start()
            f.manager.scheduleInitialSync()
            f.manager.scheduleInitialSyncToPeer(PEER)
            runCurrent()
            val records = f.owner.snapshot()
            assertEquals(GossipWorkKind.entries.toList(), records.map { it.kind })
            assertEquals(4, f.jobs.size)
            records.zip(f.jobs).forEach { (record, job) ->
                assertSame(job, (record.outcome as GossipWorkOutcome.Returned).job)
                assertTrue(f.parent.children.any { it === job })
            }
            assertEquals(4, f.receivers.size)
            f.jobs.zip(f.receivers).forEach { (job, receiver) -> assertSame(job, receiver) }
            assertEquals(4, f.requests.size)
            f.requests.forEach { (scope, context) ->
                assertSame(f.scope, scope)
                assertSame(Dispatchers.IO, context)
            }
            assertEquals(4, records.map { it.ticket }.toSet().size)
        }
    }

    @Test fun repeatedStartAndStopKeepReplacedCancelledAndInitialDebt() = runTest {
        withFixture { f ->
            f.manager.start()
            runCurrent()
            val replaced = f.jobs.toList()
            f.manager.start()
            f.manager.scheduleInitialSync()
            f.manager.scheduleInitialSyncToPeer(PEER)
            runCurrent()
            f.manager.stop()
            runCurrent()
            f.manager.clear()
            val records = f.owner.snapshot()
            assertEquals(6, records.size)
            assertTrue(replaced.all { it.isCancelled })
            assertTrue(f.jobs.take(4).all { it.isCancelled })
            // These initial Jobs survive the legacy stop; retained handles are not a drain.
            assertTrue(f.jobs.drop(4).all { it.isActive })
            records.zip(f.jobs).forEach { (record, job) ->
                assertSame(job, (record.outcome as GossipWorkOutcome.Returned).job)
            }
        }
    }

    @Test fun lateCreatorReturnAfterStopRetainsOriginalDispatch() = runTest {
        withFixture { f ->
            val entered = f.latch()
            val release = f.latch()
            val calls = AtomicInteger()
            f.creator = { scope, context, body ->
                val actual = f.launchControlled(scope, context, body)
                if (calls.incrementAndGet() == 1) {
                    entered.countDown()
                    await(release)
                }
                actual
            }
            val starter = f.worker { f.manager.start() }
            await(entered)
            f.manager.stop()
            val captured = AtomicReference<List<GossipWorkSnapshot>>()
            val snapshotter = f.worker { captured.set(f.owner.snapshot()) }
            f.join(snapshotter)
            val pending = captured.get().single()
            assertEquals(GossipWorkKind.PERIODIC_SYNC, pending.kind)
            assertSame(GossipWorkOutcome.Dispatching, pending.outcome)
            release.countDown()
            f.join(starter)
            runCurrent()
            val returned = f.owner.snapshot()
            // The same legacy start continues to create cleanup AFTER stop and its late return.
            assertEquals(listOf(GossipWorkKind.PERIODIC_SYNC, GossipWorkKind.CLEANUP), returned.map { it.kind })
            assertSame(pending.ticket, returned.first().ticket)
            assertEquals(2, f.jobs.size)
            returned.zip(f.jobs).forEach { (record, job) ->
                assertSame(job, (record.outcome as GossipWorkOutcome.Returned).job)
                assertTrue(job.isActive)
            }
        }
    }

    @Test fun bodyCompletionBeforeCreatorReturnKeepsExactCompletedJob() = runTest {
        withFixture { f ->
            val entered = f.latch()
            val release = f.latch()
            f.creator = { scope, context, body ->
                val actual = f.launchControlled(scope, context, body)
                entered.countDown()
                await(release)
                actual
            }
            val worker = f.worker { f.manager.scheduleInitialSync(0) }
            await(entered)
            runCurrent()
            val actual = f.jobs.single()
            assertTrue(actual.isCompleted)
            val before = f.owner.snapshot().single()
            assertSame(GossipWorkOutcome.Dispatching, before.outcome)
            release.countDown()
            f.join(worker)
            val after = f.owner.snapshot().single()
            assertSame(before.ticket, after.ticket)
            assertSame(actual, (after.outcome as GossipWorkOutcome.Returned).job)
            assertTrue(actual.isCompleted)
        }
    }

    @Test fun creatorThrowAfterEscapedJobKeepsUnknownAndOwnedJobIsReaped() = runTest {
        withFixture { f ->
            val release = f.gate()
            val entered = CompletableDeferred<Unit>()
            val failure = IllegalStateException("Synthetic escaped dispatch failure")
            f.creator = { scope, context, body ->
                f.launchControlled(scope, context) {
                    entered.complete(Unit)
                    release.await()
                    body(this)
                }
                throw failure
            }
            val actual = runCatching { f.manager.scheduleInitialSync(0) }.exceptionOrNull()
            assertSame(failure, actual)
            runCurrent()
            assertTrue(entered.isCompleted)
            val escaped = f.jobs.single()
            assertTrue(escaped.isActive)
            assertTrue(f.parent.children.any { it === escaped })
            val record = f.owner.snapshot().single()
            assertEquals(GossipWorkKind.INITIAL_BROADCAST, record.kind)
            assertSame(GossipWorkOutcome.Unknown, record.outcome)
            release.complete(Unit)
            withTimeout(5_000) { escaped.join() }
            assertTrue(escaped.isCompleted)
            assertSame(GossipWorkOutcome.Unknown, f.owner.snapshot().single().outcome)
        }
    }

    @Test fun foreignAndRepeatedDispatchCannotMutateOriginalOutcomeOrEscapeAnotherJob() = runTest {
        withFixture { f ->
            val foreign = GossipWorkOwner()
            val ticket = f.reserveTicket(GossipWorkKind.INITIAL_BROADCAST)
            var creatorCalls = 0
            val forbidden: GossipJobCreator = { _, _, _ -> creatorCalls++; error("Unexpected dispatch") }
            assertTrue(runCatching {
                foreign.dispatch(ticket, f.scope, Dispatchers.IO, forbidden) {}
            }.exceptionOrNull() is IllegalStateException)
            assertTrue(foreign.snapshot().isEmpty())
            assertSame(GossipWorkOutcome.Reserved, f.owner.snapshot().single().outcome)
            val actual = f.owner.dispatch(ticket, f.scope, Dispatchers.IO, { scope, context, body ->
                creatorCalls++
                assertTrue(runCatching {
                    f.owner.dispatch(ticket, scope, context, forbidden) {}
                }.exceptionOrNull() is IllegalStateException)
                assertSame(GossipWorkOutcome.Dispatching, f.owner.snapshot().single().outcome)
                f.launchControlled(scope, context, body)
            }) {}
            runCurrent()
            assertSame(actual, (f.owner.snapshot().single().outcome as GossipWorkOutcome.Returned).job)
            assertTrue(runCatching {
                f.owner.dispatch(ticket, f.scope, Dispatchers.IO, forbidden) {}
            }.exceptionOrNull() is IllegalStateException)
            val unknown = f.reserveTicket(GossipWorkKind.INITIAL_PEER)
            val failure = IllegalStateException("Synthetic creator refusal")
            assertSame(failure, runCatching {
                f.owner.dispatch(unknown, f.scope, Dispatchers.IO, { _, _, _ -> throw failure }) {}
            }.exceptionOrNull())
            assertTrue(runCatching {
                f.owner.dispatch(unknown, f.scope, Dispatchers.IO, forbidden) {}
            }.exceptionOrNull() is IllegalStateException)
            assertEquals(1, creatorCalls)
            assertEquals(1, f.jobs.size)
            val outcomes = f.owner.snapshot().map { it.outcome }
            assertSame(actual, (outcomes[0] as GossipWorkOutcome.Returned).job)
            assertSame(GossipWorkOutcome.Unknown, outcomes[1])
        }
    }

    @Test fun bodyReturnAndCancellationDoNotStandInForActualJobCompletion() = runTest {
        withFixture { f ->
            val release = f.gate()
            val childEntered = CompletableDeferred<Unit>()
            val bodyReturned = CompletableDeferred<Unit>()
            f.creator = { scope, context, body ->
                f.launchControlled(scope, context) {
                    launch {
                        withContext(NonCancellable) {
                            childEntered.complete(Unit)
                            release.await()
                        }
                    }
                    body(this)
                    bodyReturned.complete(Unit)
                }
            }
            f.manager.scheduleInitialSyncToPeer(PEER, 0)
            runCurrent()
            assertTrue(childEntered.isCompleted)
            assertTrue(bodyReturned.isCompleted)
            val actual = f.jobs.single()
            val record = f.owner.snapshot().single()
            assertSame(actual, (record.outcome as GossipWorkOutcome.Returned).job)
            assertFalse(actual.isCompleted)
            actual.cancel()
            runCurrent()
            assertTrue(actual.isCancelled)
            assertFalse("The cancellation-ignoring child still owns actual completion", actual.isCompleted)
            assertSame(actual, (f.owner.snapshot().single().outcome as GossipWorkOutcome.Returned).job)
            release.complete(Unit)
            withTimeout(5_000) { actual.join() }
            assertTrue(actual.isCompleted)
            assertSame(actual, (f.owner.snapshot().single().outcome as GossipWorkOutcome.Returned).job)
        }
    }

    private class Fixture(test: TestScope) {
        val parent = SupervisorJob()
        private val dispatcher = StandardTestDispatcher(test.testScheduler)
        val scope = CoroutineScope(parent + dispatcher)
        val owner = GossipWorkOwner()
        val jobs = java.util.Collections.synchronizedList(mutableListOf<Job>())
        val requests = java.util.Collections.synchronizedList(mutableListOf<Pair<CoroutineScope, CoroutineContext>>())
        val receivers = java.util.Collections.synchronizedList(mutableListOf<Job>())
        private val latches = mutableListOf<CountDownLatch>()
        private val gates = mutableListOf<CompletableDeferred<Unit>>()
        private val workers = mutableListOf<Thread>()
        private val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        private val manualRoots = mutableListOf<GossipRootLease>()
        var creator: GossipJobCreator = ::launchControlled
        val manager = GossipSyncManager("1122334455667788", scope, object : GossipSyncManager.ConfigProvider {
            override fun seenCapacity() = 8
            override fun gcsMaxBytes() = 128
            override fun gcsTargetFpr() = 0.01
        }, owner, { originalScope, context, body -> creator(originalScope, context, body) })

        fun launchControlled(originalScope: CoroutineScope, context: CoroutineContext, body: suspend CoroutineScope.() -> Unit): Job {
            assertSame(GossipWorkOutcome.Dispatching, owner.snapshot().last().outcome)
            requests += originalScope to context
            // The fixture explicitly substitutes its owned scheduler; the live adapter is tested separately.
            val actual = originalScope.launch(dispatcher) {
                receivers += requireNotNull(coroutineContext[Job])
                body(this)
            }
            jobs += actual
            return actual
        }

        fun reserveTicket(kind: GossipWorkKind): GossipWorkTicket {
            val rootKind = when (kind) {
                GossipWorkKind.INITIAL_BROADCAST -> GossipRootKind.INITIAL_BROADCAST
                GossipWorkKind.INITIAL_PEER -> GossipRootKind.INITIAL_PEER
                else -> error("Direct fixture ticket needs an explicit initial root")
            }
            val reservation = owner.reserveRoot(manager.captureJobPanicBinding(), rootKind)
            check(reservation is GossipRootReservation.Accepted)
            manualRoots += reservation.lease
            return owner.reserveChild(reservation.lease, kind)
        }

        fun latch() = CountDownLatch(1).also { latches += it }
        fun gate() = CompletableDeferred<Unit>().also { gates += it }
        fun worker(action: () -> Unit): Thread = Thread {
            try { action() } catch (failure: Throwable) { errors += failure }
        }.apply { isDaemon = true; workers += this; start() }

        fun join(worker: Thread) {
            worker.join(5_000)
            assertFalse("Owned worker did not finish", worker.isAlive)
            checkErrors()
        }

        private fun checkErrors() {
            val first = errors.poll() ?: return
            while (true) {
                val next = errors.poll() ?: break
                if (next !== first) first.addSuppressed(next)
            }
            throw first
        }

        suspend fun close() = withContext(NonCancellable) {
            latches.forEach { it.countDown() }
            gates.forEach { it.complete(Unit) }
            var failure: Throwable? = null
            manualRoots.forEach { lease ->
                failure = preserveFailure(failure) { owner.finishRoot(lease) }
            }
            workers.forEach { worker ->
                failure = preserveFailure(failure) {
                    worker.join(5_000)
                    if (worker.isAlive) {
                        worker.interrupt()
                        worker.join(5_000)
                        error("Owned worker exceeded its original completion bound; alive=${worker.isAlive}")
                    }
                }
            }
            failure = preserveFailure(failure) { checkErrors() }
            failure = preserveFailure(failure) { manager.stop() }
            failure = preserveFailure(failure) { withTimeout(5_000) { parent.cancelAndJoin() } }
            failure?.let { throw it }
        }
    }

    private suspend fun TestScope.withFixture(block: suspend (Fixture) -> Unit) {
        val f = Fixture(this)
        var primary: Throwable? = null
        try { block(f) } catch (failure: Throwable) { primary = failure }
        finally { primary = preserveFailure(primary) { f.close() } }
        primary?.let { throw it }
    }

    private companion object {
        const val PEER = "8877665544332211"
        fun await(latch: CountDownLatch) {
            assertTrue("Owned latch did not reach its checkpoint", latch.await(5, TimeUnit.SECONDS))
        }

        suspend fun preserveFailure(primary: Throwable?, cleanup: suspend () -> Unit): Throwable? {
            return try { cleanup(); primary } catch (failure: Throwable) {
                if (primary == null) failure else primary.also { if (it !== failure) it.addSuppressed(failure) }
            }
        }
    }
}
