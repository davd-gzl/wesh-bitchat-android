package com.bitchat.android.mesh

import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
import org.junit.Assert.assertTrue
import org.junit.Test

/** Original private-channel offers and processor Jobs; no platform service or provider graph. */
class PacketProcessorShutdownBoundaryTest {
    @Test fun shutdownRefusesNewPeerQueueAndCountsActualDrop() = runBlocking {
        val post = withFixture { f ->
            val held = f.holdValidation()
            f.processor.processPacket(packet(1, PEER_A))
            f.ready(held.entered)
            check(f.peerQueues() == 1) { "The original actor must own its real queue" }
            f.processor.shutdown()
            val awaiter = f.awaiter()
            check(!awaiter.job.isCompleted && !held.returned.get()) { "The original callback must keep its parent incomplete" }
            val before = f.processor.dropped
            f.processor.processPacket(packet(2, PEER_B))
            val queues = f.peerQueues()
            val dropDelta = f.processor.dropped - before
            val callbackStillHeld = !held.returned.get() && held.release.count == 1L
            val awaitStillPending = !awaiter.job.isCompleted && !awaiter.returned.get()
            check(callbackStillHeld && awaitStillPending && !f.scopeActive()) {
                "The canceled original parent must stay incomplete through the real post-close offer"
            }
            return@withFixture {
                assertEquals(0, queues)
                assertEquals(1L, dropDelta)
                assertTrue(callbackStillHeld)
                assertTrue(awaitStillPending)
            }
        }
        post()
    }

    @Test fun heldValidationCallbackDelaysOriginalProcessorJoin() = runBlocking {
        val post = withFixture { f ->
            val held = f.holdValidation()
            f.processor.processPacket(packet(1))
            f.ready(held.entered)
            f.processor.shutdown()
            val awaiter = f.awaiter()
            val during = listOf(f.scopeActive(), awaiter.job.isCompleted, awaiter.returned.get(), held.returned.get())
            held.release.countDown()
            f.join(awaiter.job)
            val returnedAfterRelease = awaiter.returned.get() && held.returned.get()
            return@withFixture {
                assertEquals(listOf(false, false, false, false), during)
                assertTrue(returnedAfterRelease)
            }
        }
        post()
    }

    @Test fun nonCancellableValidationWorkPreventsEarlyJoin() = runBlocking {
        val post = withFixture { f ->
            val entered = CompletableDeferred<Unit>()
            val release = f.deferredGate()
            val callbackReturned = AtomicBoolean(false)
            val actualChild = CompletableDeferred<Job>()
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
                callbackReturned.set(true)
                false
            }
            f.processor.processPacket(packet(1))
            f.ready(entered)
            val child = withTimeout(GUARD_MS) { actualChild.await() }
            check(child.isActive && !child.isCompleted) { "The actual callback-owned child must enter" }
            f.processor.shutdown()
            val awaiter = f.awaiter()
            val childHeld = child.isActive && !child.isCompleted
            val scopeCanceled = !f.scopeActive()
            val during = awaiter.job.isCompleted || awaiter.returned.get() || callbackReturned.get()
            release.complete(Unit)
            f.join(awaiter.job)
            val after = child.isCompleted && callbackReturned.get() && awaiter.returned.get()
            return@withFixture {
                assertTrue(childHeld)
                assertTrue(scopeCanceled)
                assertFalse(during)
                assertTrue(after)
            }
        }
        post()
    }

    @Test fun offerAcceptedBeforeCloseSettlesWithoutFreshQueueAuthority() = runBlocking {
        val post = withFixture { f ->
            val held = f.holdValidation()
            f.processor.processPacket(packet(1, PEER_A))
            f.ready(held.entered)
            check(f.calls.get() == 1 && f.peerQueues() == 1) { "The preaccepted original offer must enter" }
            f.processor.shutdown()
            val awaiter = f.awaiter()
            check(!awaiter.job.isCompleted && !held.returned.get()) { "The original callback must stay held across close" }
            val before = f.processor.dropped
            f.processor.processPacket(packet(2, PEER_A))
            val queues = f.peerQueues()
            val dropDelta = f.processor.dropped - before
            val originalStillHeld = !held.returned.get() && held.release.count == 1L
            val awaitStillPending = !awaiter.job.isCompleted && !awaiter.returned.get()
            val actualCallbacks = f.calls.get()
            check(originalStillHeld && awaitStillPending && !f.scopeActive() && actualCallbacks == 1) {
                "The canceled original callback must stay held through the real repeated-peer offer"
            }
            return@withFixture {
                assertEquals(0, queues)
                assertEquals(1L, dropDelta)
                assertTrue(originalStillHeld)
                assertTrue(awaitStillPending)
                assertEquals(1, actualCallbacks)
            }
        }
        post()
    }

    @Test fun realSamePeerOffersStaySerialized() = runBlocking {
        val post = withFixture { f ->
            val firstEntered = CompletableDeferred<Unit>()
            val secondFinished = CompletableDeferred<Unit>()
            val release = f.gate()
            val order = CopyOnWriteArrayList<Int>()
            f.validation = { value ->
                if (value.payload.single().toInt() == 1) {
                    order += 1
                    firstEntered.complete(Unit)
                    f.waitGate(release)
                    order += 2
                } else {
                    order += 3
                    order += 4
                    secondFinished.complete(Unit)
                }
                false
            }
            f.processor.processPacket(packet(1))
            f.ready(firstEntered)
            f.processor.processPacket(packet(2))
            release.countDown()
            f.ready(secondFinished)
            f.processor.shutdown()
            f.processor.awaitShutdown()
            val actualOrder = order.toList()
            return@withFixture { assertEquals(listOf(1, 2, 3, 4), actualOrder) }
        }
        post()
    }

    @Test fun legacyUnitShutdownFromValidationReturnsWithoutSelfJoin() = runBlocking {
        val post = withFixture { f ->
            val returned = CompletableDeferred<Unit>()
            f.validation = {
                f.processor.shutdown()
                returned.complete(Unit)
                false
            }
            f.processor.processPacket(packet(1))
            f.ready(returned)
            f.processor.awaitShutdown()
            val actualCalls = f.calls.get()
            val actualScopeInactive = !f.scopeActive()
            return@withFixture {
                assertEquals(1, actualCalls)
                assertTrue(actualScopeInactive)
            }
        }
        post()
    }

    @Test fun cancelingExternalAwaiterDoesNotInventProcessorCompletion() = runBlocking {
        val post = withFixture { f ->
            val held = f.holdValidation()
            f.processor.processPacket(packet(1))
            f.ready(held.entered)
            f.processor.shutdown()
            val first = f.awaiter()
            check(!first.job.isCompleted) { "The actual first awaiter must suspend on the held processor" }
            first.job.cancel()
            f.join(first.job)
            val firstCanceled = first.job.isCancelled
            val firstReturned = first.returned.get()
            val callbackStillHeld = !held.returned.get()
            val retry = f.awaiter()
            val retryPending = !retry.job.isCompleted && !retry.returned.get()
            held.release.countDown()
            f.join(retry.job)
            val retryReturned = retry.returned.get() && held.returned.get()
            return@withFixture {
                assertTrue(firstCanceled)
                assertFalse(firstReturned)
                assertTrue(callbackStillHeld)
                assertTrue(retryPending)
                assertTrue(retryReturned)
            }
        }
        post()
    }

    @Test fun concurrentExternalAwaitersFinishAfterSameCallbackReturns() = runBlocking {
        val post = withFixture { f ->
            val held = f.holdValidation()
            f.processor.processPacket(packet(1))
            f.ready(held.entered)
            f.processor.shutdown()
            val first = f.awaiter()
            val second = f.awaiter()
            val bothPending = !first.job.isCompleted && !second.job.isCompleted &&
                !first.returned.get() && !second.returned.get() && !held.returned.get()
            held.release.countDown()
            f.join(first.job)
            f.join(second.job)
            val bothReturned = first.returned.get() && second.returned.get() && held.returned.get()
            return@withFixture {
                assertTrue(bothPending)
                assertTrue(bothReturned)
            }
        }
        post()
    }

    @Test fun ordinaryValidationThrowKeepsOriginalActorPolicy() = runBlocking {
        val post = withFixture { f ->
            val next = CompletableDeferred<Unit>()
            val sentinel = IllegalStateException("synthetic validation failure")
            val visited = CopyOnWriteArrayList<Int>()
            f.validation = { value ->
                val sequence = value.payload.single().toInt()
                visited += sequence
                if (sequence == 1) throw sentinel
                next.complete(Unit)
                false
            }
            f.processor.processPacket(packet(1))
            f.processor.processPacket(packet(2))
            f.ready(next)
            f.processor.shutdown()
            f.processor.awaitShutdown()
            val actualVisited = visited.toList()
            val actualCalls = f.calls.get()
            return@withFixture {
                assertEquals(listOf(1, 2), actualVisited)
                assertEquals(2, actualCalls)
            }
        }
        post()
    }

    private class Fixture {
        val processor = PacketProcessor(MY_ID)
        val calls = AtomicInteger()
        @Volatile var validation: (BitchatPacket) -> Boolean = { false }
        private val gates = ConcurrentLinkedQueue<CountDownLatch>()
        private val deferredGates = ConcurrentLinkedQueue<CompletableDeferred<Unit>>()
        private val knownJobs = ConcurrentLinkedQueue<Job>()
        private val callbackFailures = ConcurrentLinkedQueue<Throwable>()
        private val awaitParent = SupervisorJob()
        private val awaitScope = CoroutineScope(Dispatchers.Default + awaitParent)

        init {
            processor.delegate = object : PacketProcessorDelegate {
                override fun validatePacketSecurity(packet: BitchatPacket, peerID: String): Boolean {
                    calls.incrementAndGet()
                    return validation(packet)
                }
                override fun updatePeerLastSeen(peerID: String) = Unit
                override fun getPeerNickname(peerID: String): String? = null
                override fun getNetworkSize() = 1
                override fun getBroadcastRecipient() = SpecialRecipients.BROADCAST
                override fun handleNoiseHandshake(routed: RoutedPacket) = false
                override fun handleNoiseEncrypted(routed: RoutedPacket) = false
                override suspend fun handleAnnounce(routed: RoutedPacket) = false
                override fun handleMessage(routed: RoutedPacket) = Unit
                override fun handleLeave(routed: RoutedPacket) = Unit
                override fun handleFragment(packet: BitchatPacket, ingressKey: String) = FragmentResult.Pending
                override fun handleRequestSync(routed: RoutedPacket) = Unit
                override fun sendAnnouncementToPeer(peerID: String) = Unit
                override fun sendCachedMessages(peerID: String) = Unit
                override fun relayPacket(routed: RoutedPacket) = Unit
                override fun sendToPeer(peerID: String, routed: RoutedPacket) = false
            }
        }

        fun gate() = CountDownLatch(1).also { gates.add(it) }
        fun deferredGate() = CompletableDeferred<Unit>().also { deferredGates.add(it) }
        fun remember(job: Job) { knownJobs.add(job) }

        fun holdValidation(): Held {
            val entered = CompletableDeferred<Unit>()
            val release = gate()
            val returned = AtomicBoolean(false)
            validation = {
                entered.complete(Unit)
                waitGate(release)
                returned.set(true)
                false
            }
            return Held(entered, release, returned)
        }

        fun waitGate(gate: CountDownLatch) {
            try {
                if (gate.await(GUARD_MS, TimeUnit.MILLISECONDS)) return
                callbackFailures.add(AssertionError("The owned callback gate exceeded its guard"))
            } catch (failure: InterruptedException) {
                callbackFailures.add(failure)
            }
            while (gate.count != 0L) {
                try { gate.await() } catch (failure: InterruptedException) { callbackFailures.add(failure) }
            }
        }

        suspend fun waitDeferredGate(gate: CompletableDeferred<Unit>) {
            try {
                withTimeout(GUARD_MS) { gate.await() }
            } catch (failure: TimeoutCancellationException) {
                callbackFailures.add(AssertionError("The owned noncancellable gate exceeded its guard", failure))
                gate.await()
            }
        }

        suspend fun ready(signal: CompletableDeferred<Unit>) { withTimeout(GUARD_MS) { signal.await() } }
        suspend fun join(job: Job) { withTimeout(GUARD_MS) { job.join() } }
        fun peerQueues(): Int = requireNotNull(Regex("Active Peer Queues: ([0-9]+)").find(processor.getDebugInfo()))
            .groupValues[1].toInt()
        fun scopeActive(): Boolean = processor.getDebugInfo().contains("Processor Scope Active: true")

        fun awaiter(): AwaitCall {
            val returned = AtomicBoolean(false)
            val job = awaitScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    processor.awaitShutdown()
                    returned.set(true)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    callbackFailures.add(failure)
                }
            }
            remember(job)
            return AwaitCall(job, returned)
        }

        suspend fun close() {
            val failures = mutableListOf<Throwable>()
            suspend fun attempt(action: suspend () -> Unit) {
                try { action() } catch (failure: Throwable) { failures.add(failure) }
            }
            suspend fun reap(job: Job) {
                attempt { withTimeout(GUARD_MS) { job.join() } }
                attempt { job.join() }
            }
            gates.forEach { it.countDown() }
            deferredGates.forEach { it.complete(Unit) }
            attempt { awaitParent.cancel() }
            attempt { processor.shutdown() }
            attempt { withTimeout(GUARD_MS) { processor.awaitShutdown() } }
            attempt { processor.awaitShutdown() }
            knownJobs.toList().forEach { job ->
                attempt { job.cancel() }
                reap(job)
            }
            reap(awaitParent)
            failures.addAll(callbackFailures)
            val first = failures.firstOrNull()
            if (first != null) {
                failures.drop(1).forEach { if (it !== first) first.addSuppressed(it) }
                throw first
            }
        }
    }

    private data class Held(
        val entered: CompletableDeferred<Unit>,
        val release: CountDownLatch,
        val returned: AtomicBoolean
    )
    private data class AwaitCall(val job: Job, val returned: AtomicBoolean)

    companion object {
        private const val GUARD_MS = 5_000L
        private const val MY_ID = "1111111111111111"
        private const val PEER_A = "2222222222222222"
        private const val PEER_B = "3333333333333333"

        private fun packet(sequence: Int, peer: String = PEER_A) = RoutedPacket(
            BitchatPacket(
                version = 1u, type = MessageType.MESSAGE.value,
                senderID = peer.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
                recipientID = SpecialRecipients.BROADCAST, timestamp = 1uL,
                payload = byteArrayOf(sequence.toByte()), ttl = 0u
            ), peer, ingressLinkID = "synthetic-link"
        )

        private suspend fun withFixture(block: suspend (Fixture) -> (() -> Unit)): () -> Unit {
            val fixture = Fixture()
            var primary: Throwable? = null
            try {
                return block(fixture)
            } catch (failure: Throwable) {
                primary = failure
                throw failure
            } finally {
                try {
                    withContext(NonCancellable) { fixture.close() }
                } catch (cleanupFailure: Throwable) {
                    val original = primary
                    if (original == null) throw cleanupFailure
                    if (cleanupFailure !== original) original.addSuppressed(cleanupFailure)
                }
            }
        }
    }
}
