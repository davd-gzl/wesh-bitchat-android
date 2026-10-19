package com.bitchat.android.sync

import android.app.Application
import android.os.Build
import com.bitchat.android.protocol.BitchatPacket
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

/** Actual public Unit commands and original owner capabilities, with inert owned scheduling. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class GossipJobRootAdmissionTest {
    @Test fun closedStartRefusesBeforeCancelCreatorOrProvider() = runTest {
        withFixture { f ->
            f.manager.start()
            runCurrent()
            val oldJobs = f.jobs.toList()
            assertEquals(2, oldJobs.size)
            assertTrue(oldJobs.all { it.isActive })
            val before = f.creators.get()
            val receipt = f.owner.closeJobRoots(f.binding)
            f.manager.start()
            runCurrent()
            val cancellationsBeforeCleanup = oldJobs.map { it.isCancelled }
            val creatorDelta = f.creators.get() - before
            f.finishBeforePolicy()
            assertEquals(0, creatorDelta)
            assertEquals(listOf(false, false), cancellationsBeforeCleanup)
            assertEquals(0, f.providers.get())
            assertEquals(0, f.signs.get())
            assertEquals(0, f.sends.get())
            expectRefusal(f.owner, f.binding, GossipRootKind.START, receipt)
        }
    }

    @Test fun closedInitialBroadcastRefusesBeforeCreatorOrProvider() = runTest {
        withFixture { f ->
            val receipt = f.owner.closeJobRoots(f.binding)
            f.manager.scheduleInitialSync(0)
            runCurrent()
            val creatorDelta = f.creators.get()
            f.finishBeforePolicy()
            assertEquals(0, creatorDelta)
            assertEquals(0, f.providers.get())
            assertEquals(0, f.signs.get())
            assertEquals(0, f.sends.get())
            expectRefusal(f.owner, f.binding, GossipRootKind.INITIAL_BROADCAST, receipt)
        }
    }

    @Test fun closedInitialPeerRefusesBeforeCreatorOrProvider() = runTest {
        withFixture { f ->
            val receipt = f.owner.closeJobRoots(f.binding)
            f.manager.scheduleInitialSyncToPeer(PEER, 0)
            runCurrent()
            val creatorDelta = f.creators.get()
            f.finishBeforePolicy()
            assertEquals(0, creatorDelta)
            assertEquals(0, f.providers.get())
            assertEquals(0, f.signs.get())
            assertEquals(0, f.sends.get())
            expectRefusal(f.owner, f.binding, GossipRootKind.INITIAL_PEER, receipt)
        }
    }

    @Test fun openRootPreservesBothDispatchesAndOneOriginalBinding() = runTest {
        withFixture { f ->
            assertSame(f.binding, f.manager.captureJobPanicBinding())
            assertSame(f.binding, f.owner.associateManager(f.manager))
            f.manager.start()
            f.manager.scheduleInitialSync()
            f.manager.scheduleInitialSyncToPeer(PEER)
            runCurrent()
            val roots = f.owner.rootSnapshot()
            assertEquals(listOf(GossipRootKind.START, GossipRootKind.INITIAL_BROADCAST, GossipRootKind.INITIAL_PEER), roots.map { it.kind })
            assertTrue(roots.all { it.state == GossipRootState.FINISHED })
            assertEquals(3, roots.map { it.id }.toSet().size)
            roots.forEach { row ->
                val metadata: Any = row.id
                assertFalse(metadata is GossipRootLease)
                assertFalse(metadata is GossipManagerJobBinding)
            }
            val records = f.owner.snapshot()
            assertEquals(GossipWorkKind.entries.toList(), records.map { it.kind })
            assertEquals(4, f.creators.get())
            records.zip(f.jobs).forEach { (record, actual) ->
                assertSame(actual, (record.outcome as GossipWorkOutcome.Returned).job)
                assertTrue(actual.isActive)
            }
            assertEquals(0, f.providers.get())
            f.finishBeforePolicy()
            assertEquals(4, f.owner.snapshot().size)
            assertTrue(f.owner.rootSnapshot().all { it.state == GossipRootState.FINISHED })
        }
    }

    @Test fun acceptedStartStraddlingCloseRetainsBothOriginalJobs() = runTest {
        withFixture { f ->
            val entered = f.latch()
            val release = f.latch()
            f.creator = { scope, context, body ->
                val actual = f.launchControlled(scope, context, body)
                if (f.creators.get() == 1) {
                    entered.countDown()
                    awaitRootLatch(release)
                }
                actual
            }
            val starter = f.worker { f.manager.start() }
            awaitRootLatch(entered)
            val before = f.observe { f.owner.rootSnapshot().single() }
            assertEquals(GossipRootState.ACTIVE, before.state)
            val receipt = f.observe { f.owner.closeJobRoots(f.binding) }
            release.countDown()
            f.join(starter)
            runCurrent()
            val after = f.owner.rootSnapshot().single()
            assertSame(before.id, after.id)
            assertEquals(GossipRootState.FINISHED, after.state)
            assertEquals(2, f.creators.get())
            val records = f.owner.snapshot()
            assertEquals(listOf(GossipWorkKind.PERIODIC_SYNC, GossipWorkKind.CLEANUP), records.map { it.kind })
            records.zip(f.jobs).forEach { (record, actual) ->
                assertSame(actual, (record.outcome as GossipWorkOutcome.Returned).job)
            }
            assertSame(receipt, f.owner.closeJobRoots(f.binding))
            f.finishBeforePolicy()
            assertEquals(2, f.owner.snapshot().size)
        }
    }

    @Test fun reentrantCloseAndRepeatedOrForeignBindingCannotBorrowAuthority() = runTest {
        withFixture { f ->
            val otherOwner = GossipWorkOwner()
            val otherManager = f.newManager(otherOwner)
            val otherBinding = otherManager.captureJobPanicBinding()
            val broadRoot = f.manualRoot(f.owner, f.manager, GossipRootKind.INITIAL_BROADCAST)
            val lateTicket = f.owner.reserveChild(broadRoot, GossipWorkKind.INITIAL_BROADCAST)
            val startRoot = f.manualRoot(f.owner, f.manager, GossipRootKind.START)
            expectRootMisuse { f.owner.reserveChild(startRoot, GossipWorkKind.CLEANUP) }
            val heldTicket = f.owner.reserveChild(startRoot, GossipWorkKind.PERIODIC_SYNC)
            expectRootMisuse { f.owner.reserveChild(startRoot, GossipWorkKind.PERIODIC_SYNC) }
            val unknownTicket = f.owner.reserveChild(startRoot, GossipWorkKind.CLEANUP)
            expectRootMisuse { f.owner.reserveChild(startRoot, GossipWorkKind.CLEANUP) }
            val abandonedRoot = f.manualRoot(f.owner, f.manager, GossipRootKind.INITIAL_PEER)
            val abandoned = f.owner.reserveChild(abandonedRoot, GossipWorkKind.INITIAL_PEER)
            val foreignLease = f.manualRoot(otherOwner, otherManager, GossipRootKind.INITIAL_BROADCAST)
            val originalTicketCount = f.owner.snapshot().size
            expectRootMisuse { f.owner.reserveChild(foreignLease, GossipWorkKind.INITIAL_BROADCAST) }
            expectRootMisuse { f.owner.finishRoot(foreignLease) }
            expectRootMisuse { f.owner.closeJobRoots(otherBinding) }
            expectRootMisuse { f.owner.associateManager(otherManager) }
            assertEquals(originalTicketCount, f.owner.snapshot().size)
            assertEquals(GossipRootState.ACTIVE, otherOwner.rootSnapshot().single().state)

            val receipt = AtomicReference<GossipRootCloseReceipt>()
            f.creator = { scope, context, body ->
                val actual = f.launchControlled(scope, context, body)
                if (f.creators.get() == 1) receipt.set(f.owner.closeJobRoots(f.binding))
                actual
            }
            // No fresh public call or terminal-refusal expectation follows this reentrant close.
            f.manager.start()
            assertNotNull(receipt.get())
            assertSame(receipt.get(), f.owner.closeJobRoots(f.binding))
            assertEquals(2, f.creators.get())

            f.finishManualRoot(f.owner, broadRoot)
            expectRootMisuse { f.owner.finishRoot(broadRoot) }
            expectRootMisuse { f.owner.reserveChild(broadRoot, GossipWorkKind.INITIAL_BROADCAST) }
            val late = f.owner.dispatch(lateTicket, f.scope, Dispatchers.IO, f::launchControlled) {}
            assertSame(late, (f.owner.snapshot().first { it.ticket === lateTicket }.outcome as GossipWorkOutcome.Returned).job)

            val entered = f.latch()
            val release = f.latch()
            val returned = AtomicReference<Job>()
            val dispatch = f.worker {
                returned.set(f.owner.dispatch(heldTicket, f.scope, Dispatchers.IO, { scope, context, body ->
                    val actual = f.launchControlled(scope, context, body)
                    entered.countDown()
                    awaitRootLatch(release)
                    actual
                }) {})
            }
            awaitRootLatch(entered)
            f.observe { f.finishManualRoot(f.owner, startRoot) }
            f.observe { expectRootMisuse { f.owner.finishRoot(startRoot) } }
            val held = f.observe { f.owner.snapshot().first { it.ticket === heldTicket } }
            assertSame(GossipWorkOutcome.Dispatching, held.outcome)
            release.countDown()
            f.join(dispatch)
            assertSame(returned.get(), (f.owner.snapshot().first { it.ticket === heldTicket }.outcome as GossipWorkOutcome.Returned).job)

            val failure = IllegalStateException("Synthetic post-finish creator failure")
            val actualFailure = runCatching {
                f.owner.dispatch(unknownTicket, f.scope, Dispatchers.IO, { scope, context, body ->
                    f.launchControlled(scope, context, body)
                    throw failure
                }) {}
            }.exceptionOrNull()
            assertSame(failure, actualFailure)
            assertSame(GossipWorkOutcome.Unknown, f.owner.snapshot().first { it.ticket === unknownTicket }.outcome)
            f.finishManualRoot(f.owner, abandonedRoot)
            assertSame(GossipWorkOutcome.Reserved, f.owner.snapshot().first { it.ticket === abandoned }.outcome)
            expectRootMisuse { f.owner.reserveChild(abandonedRoot, GossipWorkKind.INITIAL_PEER) }
            f.finishManualRoot(otherOwner, foreignLease)
            runCurrent()
            f.finishBeforePolicy()
            val all = f.owner.snapshot()
            assertEquals(6, all.size)
            assertEquals(4, all.count { it.outcome is GossipWorkOutcome.Returned })
            assertEquals(1, all.count { it.outcome === GossipWorkOutcome.Unknown })
            assertEquals(1, all.count { it.outcome === GossipWorkOutcome.Reserved })
            assertEquals(4, f.owner.rootSnapshot().size)
            assertTrue(f.owner.rootSnapshot().all { it.state == GossipRootState.FINISHED })
            assertSame(receipt.get(), f.owner.closeJobRoots(f.binding))
            assertEquals(GossipRootState.FINISHED, otherOwner.rootSnapshot().single().state)
        }
    }

    @Test fun secondActualManagerCannotReuseOwnerBeforeWork() = runTest {
        withFixture { f ->
            val original = f.binding
            expectRootMisuse { f.newManager(f.owner) }
            assertSame(original, f.manager.captureJobPanicBinding())
            assertSame(original, f.owner.associateManager(f.manager))
            assertEquals(0, f.creators.get())
            assertEquals(0, f.providers.get())
            assertTrue(f.owner.snapshot().isEmpty())
            assertTrue(f.owner.rootSnapshot().isEmpty())
            val receipt = f.owner.closeJobRoots(original)
            assertSame(receipt, f.owner.closeJobRoots(original))
            f.finishBeforePolicy()
        }
    }

    private class Fixture(test: TestScope) {
        val parent = SupervisorJob()
        private val dispatcher = StandardTestDispatcher(test.testScheduler)
        val scope = CoroutineScope(parent + dispatcher)
        val owner = GossipWorkOwner()
        val creators = AtomicInteger()
        val providers = AtomicInteger()
        val signs = AtomicInteger()
        val sends = AtomicInteger()
        val jobs = Collections.synchronizedList(mutableListOf<Job>())
        private val latches = mutableListOf<CountDownLatch>()
        private val workers = mutableListOf<Thread>()
        private val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        private data class Manual(val owner: GossipWorkOwner, val lease: GossipRootLease)
        private val manualRoots = Collections.synchronizedList(mutableListOf<Manual>())
        var creator: GossipJobCreator = ::launchControlled
        val manager = newManager(owner).also { actual ->
            actual.delegate = object : GossipSyncManager.Delegate {
                override fun sendPacket(packet: BitchatPacket) { sends.incrementAndGet() }
                override fun sendPacketToPeer(peerID: String, packet: BitchatPacket) { sends.incrementAndGet() }
                override fun signPacketForBroadcast(packet: BitchatPacket): BitchatPacket? {
                    signs.incrementAndGet()
                    return null
                }
            }
        }
        val binding: GossipManagerJobBinding get() = manager.captureJobPanicBinding()

        fun newManager(originalOwner: GossipWorkOwner) = GossipSyncManager(
            "1122334455667788", scope, object : GossipSyncManager.ConfigProvider {
                override fun seenCapacity(): Int { providers.incrementAndGet(); return 8 }
                override fun gcsMaxBytes(): Int { providers.incrementAndGet(); return 128 }
                override fun gcsTargetFpr(): Double { providers.incrementAndGet(); return 0.01 }
            }, originalOwner, { originalScope, context, body -> creator(originalScope, context, body) }
        )

        fun launchControlled(originalScope: CoroutineScope, context: CoroutineContext, body: suspend CoroutineScope.() -> Unit): Job {
            assertSame(scope, originalScope)
            assertSame(Dispatchers.IO, context)
            creators.incrementAndGet()
            return originalScope.launch(dispatcher, block = body).also { jobs += it }
        }

        fun manualRoot(originalOwner: GossipWorkOwner, actualManager: GossipSyncManager, kind: GossipRootKind): GossipRootLease {
            val actual = originalOwner.reserveRoot(actualManager.captureJobPanicBinding(), kind)
            check(actual is GossipRootReservation.Accepted)
            manualRoots += Manual(originalOwner, actual.lease)
            return actual.lease
        }

        fun finishManualRoot(originalOwner: GossipWorkOwner, lease: GossipRootLease) {
            originalOwner.finishRoot(lease)
            manualRoots.removeAll { it.owner === originalOwner && it.lease === lease }
        }

        fun latch() = CountDownLatch(1).also { latches += it }
        fun worker(action: () -> Unit): Thread = Thread {
            try { action() } catch (failure: Throwable) { errors += failure }
        }.apply { isDaemon = true; workers += this; start() }

        fun join(worker: Thread) {
            worker.join(5_000)
            assertFalse("Owned worker did not finish", worker.isAlive)
            checkErrors()
        }

        fun <T> observe(action: () -> T): T {
            val result = AtomicReference<T>()
            val observer = worker { result.set(action()) }
            join(observer)
            return result.get()
        }

        private fun checkErrors() {
            val first = errors.poll() ?: return
            while (true) {
                val next = errors.poll() ?: break
                if (next !== first) first.addSuppressed(next)
            }
            throw first
        }

        suspend fun finishBeforePolicy() = withContext(NonCancellable) {
            latches.forEach { it.countDown() }
            var failure: Throwable? = null
            workers.forEach { worker ->
                failure = preserveRootFailure(failure) {
                    worker.join(5_000)
                    if (worker.isAlive) {
                        worker.interrupt()
                        worker.join(5_000)
                        error("Owned worker exceeded its original completion bound; alive=${worker.isAlive}")
                    }
                }
            }
            failure = preserveRootFailure(failure) { checkErrors() }
            manualRoots.toList().forEach { original ->
                failure = preserveRootFailure(failure) { finishManualRoot(original.owner, original.lease) }
            }
            failure = preserveRootFailure(failure) { manager.stop() }
            failure = preserveRootFailure(failure) { parent.cancel() }
            jobs.toList().forEach { actual ->
                failure = preserveRootFailure(failure) { withTimeout(5_000) { actual.cancelAndJoin() } }
            }
            failure = preserveRootFailure(failure) { withTimeout(5_000) { parent.cancelAndJoin() } }
            failure = preserveRootFailure(failure) {
                assertTrue("Actual fixture Jobs are not reaped", jobs.all { it.isCompleted })
                assertTrue("Owned worker remains alive", workers.none { it.isAlive })
                checkErrors()
            }
            failure?.let { throw it }
        }
    }

    private suspend fun TestScope.withFixture(block: suspend (Fixture) -> Unit) {
        val f = Fixture(this)
        var primary: Throwable? = null
        try { block(f) } catch (failure: Throwable) { primary = failure }
        finally { primary = preserveRootFailure(primary) { f.finishBeforePolicy() } }
        primary?.let { throw it }
    }

    private companion object { const val PEER = "8877665544332211" }
}

private fun awaitRootLatch(latch: CountDownLatch) {
    assertTrue("Owned latch did not reach its checkpoint", latch.await(5, TimeUnit.SECONDS))
}

private fun expectRootMisuse(action: () -> Unit) {
    assertTrue("Original capability misuse must fail before effects", runCatching(action).exceptionOrNull() is IllegalStateException)
}

private fun expectRefusal(owner: GossipWorkOwner, binding: GossipManagerJobBinding,
                          kind: GossipRootKind, receipt: GossipRootCloseReceipt) {
    when (val actual = owner.reserveRoot(binding, kind)) {
        is GossipRootReservation.Refused -> assertSame(receipt, actual.receipt)
        is GossipRootReservation.Accepted -> fail("Closed original root was accepted")
    }
}

private suspend fun preserveRootFailure(primary: Throwable?, cleanup: suspend () -> Unit): Throwable? {
    return try { cleanup(); primary } catch (failure: Throwable) {
        if (primary == null) failure else primary.also { if (it !== failure) it.addSuppressed(failure) }
    }
}
