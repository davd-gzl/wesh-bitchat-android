package com.bitchat.android.wifiaware

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Only the actual generic owner, RAM products, owned workers and virtual restart time. */
@OptIn(ExperimentalCoroutinesApi::class)
class WifiAwareRecoveryCompletionRaceTest {
    @Test fun `unrelated status and debug updates cannot consume the only inactive notification`() = runTest {
        withFixture { f ->
            f.owner.setEnabled(true)
            val origin = f.owner.currentOwner()
            val product = requireNotNull(f.owner.getService())
            product.running = false
            val gate = f.holdProbe()
            val worker = f.worker { f.owner.onServiceRecoveryCleanupFinished(origin, product) }
            gate.awaitEntered()
            val status = WifiAwareSupport.Status(true, true)
            f.owner.publishSupportStatus(origin, status)
            f.owner.publishDebugSnapshot(origin, mapOf("synthetic-peer" to "synthetic-link"),
                mapOf("synthetic-peer" to "Synthetic peer"), setOf("synthetic-peer"))
            gate.release.countDown()
            f.reap(worker)
            assertFalse("unrelated publications must not discard the inactive liveness sample", f.owner.running.value)
            assertEquals(status, f.owner.supportStatus.value)
            assertEquals(setOf("synthetic-peer"), f.owner.discoveredPeers.value)
            assertEquals("Synthetic peer", f.owner.knownPeers.value["synthetic-peer"])
            f.owner.restartIfStillEnabled(origin, 500)
            runCurrent(); advanceTimeBy(500); runCurrent()
            assertEquals(2, f.starts.get())
            assertEquals(1, f.factories.get())
            assertSame(product, f.owner.getService())
            assertTrue(f.owner.running.value)
            assertEquals(0, f.stops.get())
        }
    }

    @Test fun `older inactive probe cannot downgrade a newer actual start of the same product`() = runTest {
        withFixture { f ->
            f.owner.setEnabled(true)
            val origin = f.owner.currentOwner()
            val product = requireNotNull(f.owner.getService())
            product.running = false
            val gate = f.holdProbe()
            val worker = f.worker { f.owner.onServiceRecoveryCleanupFinished(origin, product) }
            gate.awaitEntered()
            f.owner.startIfPossible()
            assertEquals(2, f.starts.get())
            assertTrue(product.running)
            assertTrue(f.owner.running.value)
            gate.release.countDown()
            f.reap(worker)
            assertTrue("the older false sample must not overwrite the later live start", f.owner.running.value)
            assertSame(product, f.owner.getService())
            assertEquals(1, f.factories.get())
            f.owner.restartIfStillEnabled(origin, 500)
            runCurrent(); advanceTimeBy(500); runCurrent()
            assertEquals(2, f.starts.get())
            assertEquals(0, f.stops.get())
        }
    }

    @Test fun `failed preparation during a held probe does not discard inactive reconciliation`() = runTest {
        withFixture { f ->
            f.owner.setEnabled(true)
            val origin = f.owner.currentOwner()
            val product = requireNotNull(f.owner.getService())
            product.running = false
            val gate = f.holdProbe()
            val worker = f.worker { f.owner.onServiceRecoveryCleanupFinished(origin, product) }
            gate.awaitEntered()
            f.allowed = false
            f.owner.startIfPossible()
            assertEquals(2, f.preparations.get())
            assertEquals("failed preparation cannot dispatch an actual start", 1, f.starts.get())
            gate.release.countDown()
            f.reap(worker)
            assertFalse("a start admission without dispatch cannot consume the only notification", f.owner.running.value)
            f.allowed = true
            f.owner.restartIfStillEnabled(origin, 500)
            runCurrent(); advanceTimeBy(500); runCurrent()
            assertEquals(2, f.starts.get())
            assertEquals(1, f.factories.get())
            assertSame(product, f.owner.getService())
            assertTrue(f.owner.running.value)
            assertEquals(0, f.stops.get())
        }
    }

    @Test fun `panic fences immediately and drains the held recovery callback without inventing acknowledgment`() = runTest {
        withFixture { f ->
            f.owner.setEnabled(true)
            val origin = f.owner.currentOwner()
            val product = requireNotNull(f.owner.getService())
            product.running = false
            val gate = f.holdProbe()
            val worker = f.worker { f.owner.onServiceRecoveryCleanupFinished(origin, product) }
            gate.awaitEntered()
            val retirement = async { f.owner.retireForPanic() }
            var primary: Throwable? = null
            try {
                runCurrent()
                assertFalse("the counted callback must prevent early drain acknowledgment", retirement.isCompleted)
                assertNull(f.owner.getService())
                assertFalse(f.owner.running.value)
                f.owner.startIfPossible()
                f.owner.restartIfStillEnabled(origin, 500)
                advanceTimeBy(500); runCurrent()
                assertEquals("the synchronous fence refuses new starts while the probe is held", 1, f.starts.get())
                assertEquals(1, f.factories.get())
                assertEquals(1, f.stops.get())
                assertEquals("product acknowledgment must wait for the counted callback", 0, f.acknowledgments.get())
                gate.release.countDown()
                f.reap(worker)
                val result = withTimeout(1_000) { retirement.await() }
                assertTrue(result.admittedStartsDrained)
                assertFalse("Unit stop and recovery finally are not checked retirement", result.capturedProductsAcknowledged)
                assertEquals(1, f.acknowledgments.get())
                assertNull(f.owner.getService())
                assertFalse(f.owner.running.value)
                assertEquals(1, f.starts.get())
            } catch (failure: Throwable) { primary = failure }
            gate.release.countDown()
            try {
                f.reap(worker)
            } catch (failure: Throwable) {
                val original = primary
                if (original == null) primary = failure
                else if (original !== failure) original.addSuppressed(failure)
            }
            try {
                withContext(NonCancellable) { withTimeout(1_000) { retirement.cancelAndJoin() } }
            } catch (failure: Throwable) {
                val original = primary
                if (original == null) primary = failure
                else if (original !== failure) original.addSuppressed(failure)
            }
            primary?.let { throw it }
        }
    }

    private class Product { @Volatile var running = false }

    private class ProbeGate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val claimed = AtomicBoolean(false)
        fun awaitEntered() = assertTrue("owned inactive probe must enter", entered.await(5, TimeUnit.SECONDS))
    }

    private class Fixture(scope: TestScope) {
        val job = SupervisorJob()
        val starts = AtomicInteger()
        val factories = AtomicInteger()
        val preparations = AtomicInteger()
        val stops = AtomicInteger()
        val acknowledgments = AtomicInteger()
        val errors = ConcurrentLinkedQueue<Throwable>()
        private val workers = mutableListOf<Thread>()
        private val gates = mutableListOf<ProbeGate>()
        @Volatile var allowed = true
        @Volatile private var nextProbe: ProbeGate? = null
        val owner = WifiAwareControllerOwner(WifiAwareControllerPorts(
            scope = CoroutineScope(job + StandardTestDispatcher(scope.testScheduler)),
            contextReady = { true },
            prepareStart = { preparations.incrementAndGet(); allowed },
            restartSupported = { true },
            factory = { _: WifiAwareOwnerToken -> factories.incrementAndGet(); WifiAwareFactoryResult.Created(Product()) },
            start = { starts.incrementAndGet(); it.running = true },
            isRunning = { product ->
                val sample = product.running
                val gate = nextProbe
                if (gate != null && gate.claimed.compareAndSet(false, true)) {
                    gate.entered.countDown()
                    check(gate.release.await(5, TimeUnit.SECONDS)) { "Owned probe was not released" }
                }
                sample
            },
            requestStop = { stops.incrementAndGet(); it.running = false },
            awaitProductRetirement = { acknowledgments.incrementAndGet(); false },
            published = {},
            clearPeers = {},
            debugMessage = {},
            failure = { errors.add(it) }
        ))
        fun holdProbe() = ProbeGate().also { gates += it; nextProbe = it }
        fun worker(action: () -> Unit): Thread = Thread({
            try { action() } catch (failure: Throwable) { errors.add(failure) }
        }, "synthetic-recovery-probe").apply { isDaemon = true; workers += this; start() }
        fun reap(worker: Thread) {
            worker.join(5_000)
            if (worker.isAlive) { worker.interrupt(); worker.join(1_000) }
            assertFalse("owned probe worker must finish", worker.isAlive)
            assertTrue("owned worker or port failures are fixture errors: $errors", errors.isEmpty())
        }
        fun releaseAndReap() {
            gates.forEach { it.release.countDown() }
            workers.forEach(::reap)
        }
    }

    private suspend fun TestScope.withFixture(block: suspend (Fixture) -> Unit) {
        val fixture = Fixture(this)
        var primary: Throwable? = null
        fun record(failure: Throwable) {
            val original = primary
            if (original == null) primary = failure
            else if (original !== failure) original.addSuppressed(failure)
        }
        try { block(fixture) } catch (failure: Throwable) { record(failure) }
        try { fixture.releaseAndReap() } catch (failure: Throwable) { record(failure) }
        try {
            withContext(NonCancellable) { withTimeout(1_000) { fixture.job.cancelAndJoin() } }
        } catch (failure: Throwable) { record(failure) }
        primary?.let { throw it }
    }
}
