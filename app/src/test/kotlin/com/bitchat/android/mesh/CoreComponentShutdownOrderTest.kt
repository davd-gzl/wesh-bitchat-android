package com.bitchat.android.mesh

import android.content.Context
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import com.bitchat.android.sync.GossipSyncManager
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.MockedConstruction
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** Actual Core/processor, with all six eager dependency constructors intercepted before construction. */
class CoreComponentShutdownOrderTest {
    @Test fun heldCoreActorDefersConcreteComponentShutdown() {
        val post = withFixture { f ->
            val held = f.holdValidation()
            f.offer(1)
            f.ready(held.entered)
            val caller = f.launchShutdown()
            val order = f.shutdownOrder.toList()
            val during = !caller.job.isCompleted && !caller.normalReturn.get() && !held.returned.get()
            check(during) { "The actual original processor must still await its entered callback" }
            return@withFixture {
                assertEquals(0, order.size)
                assertTrue(during)
            }
        }
        post()
    }

    @Test fun completedCoreActorReleasesAllFiveDependenciesInOriginalRelativeOrder() {
        val post = withFixture { f ->
            val visited = CompletableDeferred<Unit>()
            f.validation = { visited.complete(Unit); false }
            f.offer(1)
            f.ready(visited)
            val caller = f.launchShutdown()
            f.join(caller.job)
            val order = f.shutdownOrder.toList()
            val normal = caller.normalReturn.get()
            val failure = caller.failure.get()
            return@withFixture {
                assertEquals(COMPONENT_ORDER, order)
                assertTrue(normal)
                assertEquals(null, failure)
            }
        }
        post()
    }

    @Test fun nonCancellableCoreValidationKeepsDependenciesUntilReturn() {
        val post = withFixture { f ->
            val entered = CompletableDeferred<Unit>()
            val release = f.deferredGate()
            val actualChild = CompletableDeferred<Job>()
            val returned = AtomicBoolean(false)
            f.validation = {
                runBlocking {
                    f.remember(requireNotNull(currentCoroutineContext()[Job]))
                    val child = launch(start = CoroutineStart.UNDISPATCHED) {
                        withContext(NonCancellable) {
                            entered.complete(Unit)
                            f.waitDeferredGate(release)
                        }
                    }
                    f.remember(child)
                    actualChild.complete(child)
                    child.join()
                }
                returned.set(true)
                false
            }
            f.offer(1)
            val child = withTimeout(GUARD_MS) { actualChild.await() }
            f.ready(entered)
            check(child.isActive && !child.isCompleted) { "Actual callback-owned child must enter" }
            val caller = f.launchShutdown()
            val before = f.shutdownOrder.toList()
            val held = !caller.job.isCompleted && !returned.get() && !child.isCompleted
            check(held) { "The actual child must keep the original processor incomplete" }
            release.complete(Unit)
            f.join(caller.job)
            val after = f.shutdownOrder.toList()
            val ended = child.isCompleted && returned.get() && caller.normalReturn.get()
            return@withFixture {
                assertEquals(0, before.size)
                assertTrue(held)
                assertEquals(COMPONENT_ORDER, after)
                assertTrue(ended)
            }
        }
        post()
    }

    @Test fun cancelledCoreShutdownRetainsDependencies() {
        val post = withFixture { f ->
            val held = f.holdValidation()
            f.offer(1)
            f.ready(held.entered)
            val caller = f.launchShutdown()
            check(!caller.job.isCompleted) { "The actual external caller must suspend on the processor" }
            caller.job.cancel()
            f.join(caller.job)
            val order = f.shutdownOrder.toList()
            val cancelled = caller.job.isCancelled && caller.failure.get() is CancellationException
            val normal = caller.normalReturn.get()
            val callbackHeld = !held.returned.get() && held.release.count == 1L
            return@withFixture {
                assertEquals(0, order.size)
                assertTrue(cancelled)
                assertFalse(normal)
                assertTrue(callbackHeld)
            }
        }
        post()
    }

    @Test fun ordinaryValidationFailureStillAllowsCoreShutdown() {
        val post = withFixture { f ->
            val visited = CopyOnWriteArrayList<Int>()
            val second = CompletableDeferred<Unit>()
            val sentinel = IllegalStateException("synthetic validation failure")
            f.validation = { packet ->
                val n = packet.payload.single().toInt()
                visited += n
                if (n == 1) throw sentinel
                second.complete(Unit)
                false
            }
            f.offer(1); f.offer(2)
            f.ready(second)
            val caller = f.launchShutdown()
            f.join(caller.job)
            val actualVisited = visited.toList()
            val order = f.shutdownOrder.toList()
            val normal = caller.normalReturn.get()
            return@withFixture {
                assertEquals(listOf(1, 2), actualVisited)
                assertEquals(COMPONENT_ORDER, order)
                assertTrue(normal)
            }
        }
        post()
    }

    @Test fun dependencyShutdownFailurePreservesOriginalException() {
        val post = withFixture { f ->
            val visited = CompletableDeferred<Unit>()
            f.validation = { visited.complete(Unit); false }
            f.offer(1)
            f.ready(visited)
            // The policy observation follows completion of the SAME actual processor.
            f.processor.shutdown()
            withTimeout(GUARD_MS) { f.processor.awaitShutdown() }
            val sentinel = IllegalStateException("synthetic component failure")
            f.componentFailure.set("security" to sentinel)
            val caller = f.launchShutdown()
            f.join(caller.job)
            val actual = caller.failure.get()
            val order = f.shutdownOrder.toList()
            val normal = caller.normalReturn.get()
            return@withFixture {
                assertSame(sentinel, actual)
                assertEquals(listOf("peer", "fragment", "security"), order)
                assertFalse(normal)
            }
        }
        post()
    }

    @Test fun legacyCoreUnitShutdownRemainsNonAwaiting() {
        val post = withFixture { f ->
            val held = f.holdValidation()
            f.offer(1)
            f.ready(held.entered)
            f.core.shutdown()
            val order = f.shutdownOrder.toList()
            val awaiter = f.launchProcessorAwait()
            val heldAfterUnitReturn = !held.returned.get() && !awaiter.job.isCompleted && !awaiter.normalReturn.get()
            return@withFixture {
                assertEquals(COMPONENT_ORDER, order)
                assertTrue(heldAfterUnitReturn)
            }
        }
        post()
    }

    @Test fun partialCoreFixtureFailureStillClosesInterceptions() {
        val injected = PartialConstructionFailure()
        val observation = AtomicReference<CleanupObservation?>()
        try {
            withFixture(injected, observation) { throw AssertionError("Partial Core construction must not reach body") }
            throw AssertionError("The actual setter failure did not propagate")
        } catch (actual: PartialConstructionFailure) {
            check(actual === injected) { "Expected the exact original setter failure" }
        }
        val observed = requireNotNull(observation.get())
        // A real later registration on the same original thread detects a leaked constructor controller.
        val later = Mockito.mockConstruction(PeerManager::class.java)
        val installed = !later.isClosed
        val closeFailures = mutableListOf<Throwable>()
        closeControllers(listOf(later), Thread.currentThread(), closeFailures)
        throwFailures(null, closeFailures)
        val closed = later.isClosed
        assertTrue(observed.capturedProcessor)
        assertTrue(observed.originalProcessorJoined)
        assertTrue(observed.parentsCompleted)
        assertEquals(List(6) { 1 }, observed.constructionCounts)
        assertEquals(6, observed.closedControllers)
        assertTrue(observed.sameThreadClose)
        assertTrue(installed && closed)
    }

    private class PartialConstructionFailure : RuntimeException("synthetic partial construction failure")
    private class CleanupFailure(primary: Throwable, independent: List<Throwable>) : AssertionError("Independent fixture cleanup failed", primary) {
        init { independent.forEach { if (it !== this && it !== primary) addSuppressed(it) } }
    }
    private data class CleanupObservation(
        val capturedProcessor: Boolean,
        val originalProcessorJoined: Boolean,
        val parentsCompleted: Boolean,
        val constructionCounts: List<Int>,
        val closedControllers: Int,
        val sameThreadClose: Boolean
    )
    private data class Held(val entered: CompletableDeferred<Unit>, val release: CountDownLatch, val returned: AtomicBoolean)
    private data class ShutdownCall(val job: Job, val normalReturn: AtomicBoolean, val failure: AtomicReference<Throwable?>)

    private class Fixture {
        private val installationThread = Thread.currentThread()
        private val controllers = mutableListOf<MockedConstruction<*>>()
        private val hostParent = SupervisorJob()
        private val hostScope = CoroutineScope(Dispatchers.Default + hostParent)
        private val callerParent = SupervisorJob()
        private val callerScope = CoroutineScope(Dispatchers.Default + callerParent)
        private val capturedProcessor = AtomicReference<PacketProcessor?>()
        private val gates = ConcurrentLinkedQueue<CountDownLatch>()
        private val deferredGates = ConcurrentLinkedQueue<CompletableDeferred<Unit>>()
        private val knownJobs = ConcurrentLinkedQueue<Job>()
        private val callbackFailures = ConcurrentLinkedQueue<Throwable>()
        private var originalProcessorJoined = false
        lateinit var core: MeshCore
            private set
        val processor: PacketProcessor get() = requireNotNull(capturedProcessor.get())
        val shutdownOrder = CopyOnWriteArrayList<String>()
        val componentFailure = AtomicReference<Pair<String, Throwable>?>()
        @Volatile var validation: (BitchatPacket) -> Boolean = { false }

        /** No coroutine or dispatcher hop may precede the actual Core expression. */
        fun construct(setterFailure: Throwable?) {
            check(Thread.currentThread() === installationThread)
            controllers += Mockito.mockConstruction(PeerManager::class.java) { instance, _ ->
                doAnswer { recordShutdown("peer"); null }.whenever(instance).shutdown()
            }
            controllers += Mockito.mockConstruction(FragmentManager::class.java) { instance, _ ->
                doAnswer { recordShutdown("fragment"); null }.whenever(instance).shutdown()
            }
            controllers += Mockito.mockConstruction(SecureAuthenticatedPeerStateStore::class.java)
            controllers += Mockito.mockConstruction(SecurityManager::class.java) { instance, _ ->
                doAnswer { call -> validation(call.getArgument(0)) }.whenever(instance).validatePacket(any(), any())
                doAnswer { recordShutdown("security"); null }.whenever(instance).shutdown()
            }
            controllers += Mockito.mockConstruction(StoreForwardManager::class.java) { instance, _ ->
                doAnswer { recordShutdown("store"); null }.whenever(instance).shutdown()
            }
            controllers += Mockito.mockConstruction(MessageHandler::class.java) { instance, _ ->
                doAnswer { call ->
                    val actual = call.getArgument<PacketProcessor>(0)
                    check(capturedProcessor.compareAndSet(null, actual)) { "The actual original processor must be captured once" }
                    if (setterFailure != null) throw setterFailure
                    null
                }.whenever(instance).packetProcessor = any()
                doAnswer { recordShutdown("message"); null }.whenever(instance).shutdown()
            }
            check(controllers.size == 6)
            val context = mock<Context>()
            whenever(context.applicationContext).thenReturn(context)
            core = MeshCore(
                context = context, scope = hostScope, transport = mock<MeshTransport>(),
                encryptionService = mock<EncryptionService>(), myPeerID = MY_ID, maxTtl = 0u,
                sharedGossipManager = mock<GossipSyncManager>(),
                gossipConfigProvider = object : GossipSyncManager.ConfigProvider {
                    override fun seenCapacity() = 8
                    override fun gcsMaxBytes() = 64
                    override fun gcsTargetFpr() = 0.01
                },
                hooks = MeshCore.Hooks(), weshLocalOnly = true
            )
            check(controllers.all { it.constructed().size == 1 }) { "All six real dependency constructions must be intercepted once" }
            check(capturedProcessor.get() != null) { "Actual MessageHandler setter must capture the original processor" }
        }

        private fun recordShutdown(component: String) {
            shutdownOrder += component
            componentFailure.get()?.let { (target, failure) -> if (target == component) throw failure }
        }
        fun offer(sequence: Int) { core.processIncoming(packet(sequence), PEER, null, "synthetic-link") }
        fun remember(job: Job) { knownJobs.add(job) }
        fun deferredGate() = CompletableDeferred<Unit>().also { deferredGates.add(it) }
        fun holdValidation(): Held {
            val entered = CompletableDeferred<Unit>()
            val gate = CountDownLatch(1).also { gates.add(it) }
            val returned = AtomicBoolean(false)
            validation = { entered.complete(Unit); waitGate(gate); returned.set(true); false }
            return Held(entered, gate, returned)
        }
        private fun waitGate(gate: CountDownLatch) {
            try {
                if (gate.await(GUARD_MS, TimeUnit.MILLISECONDS)) return
                callbackFailures.add(AssertionError("Owned Core validation exceeded its guard"))
            } catch (failure: InterruptedException) { callbackFailures.add(failure) }
            while (gate.count != 0L) {
                try { gate.await() } catch (failure: InterruptedException) { callbackFailures.add(failure) }
            }
        }
        suspend fun waitDeferredGate(gate: CompletableDeferred<Unit>) {
            try { withTimeout(GUARD_MS) { gate.await() } }
            catch (failure: TimeoutCancellationException) {
                callbackFailures.add(AssertionError("Owned Core child exceeded its guard", failure))
                gate.await()
            }
        }
        suspend fun ready(signal: CompletableDeferred<Unit>) { withTimeout(GUARD_MS) { signal.await() } }
        suspend fun join(job: Job) { withTimeout(GUARD_MS) { job.join() } }
        fun launchShutdown(): ShutdownCall = launchCall { core.shutdownAndJoin() }
        fun launchProcessorAwait(): ShutdownCall = launchCall { processor.awaitShutdown() }
        private fun launchCall(action: suspend () -> Unit): ShutdownCall {
            val normal = AtomicBoolean(false)
            val failure = AtomicReference<Throwable?>()
            val job = callerScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try { action(); normal.set(true) }
                catch (actual: Throwable) {
                    failure.set(actual)
                    if (actual is CancellationException) throw actual
                }
            }
            remember(job)
            return ShutdownCall(job, normal, failure)
        }

        suspend fun reapOwned(failures: MutableList<Throwable>) {
            suspend fun attempt(action: suspend () -> Unit) {
                try { action() } catch (failure: Throwable) { failures.add(failure) }
            }
            suspend fun reap(job: Job) {
                attempt { withTimeout(GUARD_MS) { job.join() } }
                attempt { job.join() }
            }
            gates.forEach { it.countDown() }
            deferredGates.forEach { it.complete(Unit) }
            val original = capturedProcessor.get()
            if (original != null) {
                attempt { original.shutdown() }
                attempt { withTimeout(GUARD_MS) { original.awaitShutdown() } }
                attempt { original.awaitShutdown(); originalProcessorJoined = true }
            }
            knownJobs.toList().forEach { job -> attempt { job.cancel() }; reap(job) }
            attempt { callerParent.cancel() }; reap(callerParent)
            attempt { hostParent.cancel() }; reap(hostParent)
            failures.addAll(callbackFailures)
        }
        fun closeAndObserve(failures: MutableList<Throwable>): CleanupObservation {
            // Mockito disable clears constructed(); preserve its real census BEFORE controller close.
            val constructionCounts = controllers.map { it.constructed().size }
            closeControllers(controllers, installationThread, failures)
            return CleanupObservation(capturedProcessor.get() != null, originalProcessorJoined,
                hostParent.isCompleted && callerParent.isCompleted,
                constructionCounts, controllers.count { it.isClosed },
                Thread.currentThread() === installationThread)
        }
    }

    companion object {
        private const val GUARD_MS = 5_000L
        private const val MY_ID = "1111111111111111"
        private const val PEER = "2222222222222222"
        private val COMPONENT_ORDER = listOf("peer", "fragment", "security", "store", "message")
        private fun packet(sequence: Int) = BitchatPacket(
            version = 1u, type = MessageType.MESSAGE.value,
            senderID = PEER.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
            recipientID = SpecialRecipients.BROADCAST, timestamp = 1uL,
            payload = byteArrayOf(sequence.toByte()), ttl = 0u
        )
        private fun closeControllers(controllers: List<MockedConstruction<*>>, originalThread: Thread, failures: MutableList<Throwable>) {
            if (Thread.currentThread() !== originalThread) {
                failures.add(AssertionError("Construction controllers must close on their original thread"))
                return
            }
            controllers.asReversed().forEach { controller ->
                try { controller.closeOnDemand() } catch (failure: Throwable) { failures.add(failure) }
                try { if (!controller.isClosed) controller.closeOnDemand() } catch (failure: Throwable) { failures.add(failure) }
                try { if (!controller.isClosed) failures.add(AssertionError("Original construction controller remains installed")) }
                catch (failure: Throwable) { failures.add(failure) }
            }
        }
        private fun throwFailures(primary: Throwable?, independent: List<Throwable>) {
            if (primary != null) {
                if (independent.isNotEmpty()) throw CleanupFailure(primary, independent)
                throw primary
            }
            val first = independent.firstOrNull() ?: return
            independent.drop(1).forEach { if (it !== first) first.addSuppressed(it) }
            throw first
        }
        /** Controllers/Core stay on the original plain JUnit thread, outside runBlocking. */
        private fun withFixture(
            setterFailure: Throwable? = null,
            observation: AtomicReference<CleanupObservation?>? = null,
            block: suspend (Fixture) -> (() -> Unit)
        ): () -> Unit {
            val fixture = Fixture()
            var primary: Throwable? = null
            var saved: (() -> Unit)? = null
            val failures = mutableListOf<Throwable>()
            try {
                fixture.construct(setterFailure)
                saved = runBlocking { block(fixture) }
            } catch (failure: Throwable) { primary = failure }
            finally {
                var actualReapReturned = false
                while (!actualReapReturned) {
                    try {
                        runBlocking { withContext(NonCancellable) { fixture.reapOwned(failures) } }
                        actualReapReturned = true
                    } catch (failure: Throwable) { failures.add(failure) }
                }
                try {
                    val observed = fixture.closeAndObserve(failures)
                    observation?.set(observed)
                } catch (failure: Throwable) { failures.add(failure) }
            }
            throwFailures(primary, failures)
            return requireNotNull(saved) { "Saved feature assertions unavailable" }
        }
    }
}
