package com.bitchat.android.wifiaware

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Actual singleton-used owner, RAM ports and owned workers only; no platform singleton initialization. */
@OptIn(ExperimentalCoroutinesApi::class)
class WifiAwareControllerOwnerTest {
    @Test fun `retirement must wait for an admitted factory before acknowledging start drain`() = runTest {
        withFixture { f ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            f.onFactory = { _, _ -> entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            val worker = f.worker { f.owner.setEnabled(true) }
            try {
                assertTrue("synthetic factory must enter", entered.await(5, TimeUnit.SECONDS))
                val retirement = async { f.owner.retireForPanic() }
                try {
                    runCurrent()
                    assertFalse("an admitted factory prevents controller start-drain acknowledgment", retirement.isCompleted)
                } finally {
                    release.countDown()
                    f.reap(worker)
                    withTimeout(1_000) { retirement.cancelAndJoin() }
                }
            } finally { release.countDown(); f.reap(worker) }
        }
    }

    @Test fun `factory returning after panic cannot start publish or escape retirement`() = runTest {
        withFixture { f ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            f.onFactory = { _, _ -> entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            val worker = f.worker { f.owner.setEnabled(true) }
            try {
                assertTrue("synthetic factory must enter", entered.await(5, TimeUnit.SECONDS))
                val retirement = async { f.owner.retireForPanic() }
                runCurrent()
                release.countDown()
                f.reap(worker)
                withTimeout(1_000) { retirement.await() }
                assertNull("a factory admitted before panic must never publish afterwards", f.owner.getService())
                assertTrue(f.published.isEmpty())
                assertFalse("a late factory product must not start after panic", f.events.contains("start"))
                assertEquals("late product needs checked retirement", 1, f.acknowledgments.get())
                assertTrue(f.owner.enabled.value)
            } finally { release.countDown(); f.reap(worker) }
        }
    }

    @Test fun `retired controller owner refuses subsequent explicit starts`() = runTest {
        withFixture { f ->
            f.owner.setEnabled(true)
            f.owner.retireForPanic()
            val before = f.factories.get()
            f.owner.startIfPossible()
            assertEquals("retired owner cannot admit another explicit start", before, f.factories.get())
            assertNull(f.owner.getService())
            assertTrue(f.owner.enabled.value)
        }
    }

    @Test fun `old restart request cannot create a product in a new owner lifetime`() = runTest {
        withFixture { f ->
            f.owner.setEnabled(true)
            val old = f.owner.currentOwner()
            f.owner.retireForPanic()
            f.owner.beginOwner()
            val before = f.factories.get()
            f.owner.restartIfStillEnabled(old, 10)
            runCurrent()
            advanceTimeBy(10)
            runCurrent()
            assertEquals("a stale restart must not create a fresh owner", before, f.factories.get())
        }
    }

    @Test fun `old factory finally cannot clear a newer admitted start`() = runTest {
        withFixture { f ->
            val enteredA = CountDownLatch(1)
            val enteredB = CountDownLatch(1)
            val releaseA = CountDownLatch(1)
            val releaseB = CountDownLatch(1)
            f.onFactory = { number, _ ->
                when (number) {
                    1 -> { enteredA.countDown(); check(releaseA.await(5, TimeUnit.SECONDS)) }
                    2 -> { enteredB.countDown(); check(releaseB.await(5, TimeUnit.SECONDS)) }
                    else -> Unit
                }
            }
            f.shouldRun = { it.number != 1 }
            val first = f.worker { f.owner.setEnabled(true) }
            var second: Thread? = null
            try {
                assertTrue("first synthetic factory must enter", enteredA.await(5, TimeUnit.SECONDS))
                f.owner.stop()
                f.owner.beginOwner()
                val next = f.worker { f.owner.startIfPossible() }
                second = next
                assertTrue("second synthetic factory must enter", enteredB.await(5, TimeUnit.SECONDS))
                releaseA.countDown()
                f.reap(first)
                f.owner.startIfPossible()
                assertEquals("the old finally must not admit a third concurrent factory", 2, f.factories.get())
            } finally {
                releaseA.countDown(); releaseB.countDown()
                f.reap(first)
                second?.let(f::reap)
            }
        }
    }

    @Test fun `old debug callback cannot replace a new owner snapshot`() = runTest {
        withFixture { f ->
            val old = f.owner.currentOwner()
            val fresh = f.owner.beginOwner()
            f.owner.publishDebugSnapshot(fresh, mapOf("synthetic-new" to "synthetic-link"),
                mapOf("synthetic-new" to "Synthetic new"), setOf("synthetic-new"))
            f.owner.publishDebugSnapshot(old, mapOf("synthetic-old" to "synthetic-link"),
                mapOf("synthetic-old" to "Synthetic old"), setOf("synthetic-old"))
            assertEquals("late debug callbacks retain their captured lifetime", setOf("synthetic-new"), f.owner.discoveredPeers.value)
            assertEquals(setOf("synthetic-new"), f.owner.connectedPeers.value.keys)
            assertEquals(setOf("synthetic-new"), f.owner.knownPeers.value.keys)
        }
    }

    @Test fun `old support result cannot overwrite a new owner status`() = runTest {
        withFixture { f ->
            val old = f.owner.currentOwner()
            val fresh = f.owner.beginOwner()
            val current = WifiAwareSupport.Status(true, true)
            f.owner.publishSupportStatus(fresh, current)
            f.owner.publishSupportStatus(old, WifiAwareSupport.Status(false, false, "Synthetic old status"))
            assertEquals("late support result must not overwrite the new owner", current, f.owner.supportStatus.value)
            assertTrue(f.owner.supported.value)
            assertTrue(f.owner.available.value)
        }
    }

    @Test fun `old stopped product cannot clear the current product or its peers`() = runTest {
        withFixture { f ->
            f.owner.setEnabled(true)
            val old = requireNotNull(f.owner.getService())
            val origin = f.owner.currentOwner()
            f.owner.retireForPanic()
            f.owner.beginOwner()
            f.owner.startIfPossible()
            val fresh = requireNotNull(f.owner.getService())
            val clears = f.peerClears.get()
            f.owner.onServiceStopped(origin, old)
            assertSame(fresh, f.owner.getService())
            assertEquals(clears, f.peerClears.get())
            assertTrue(f.owner.running.value)
        }
    }

    @Test fun `current owner support and debug callbacks still publish`() = runTest {
        withFixture { f ->
            val origin = f.owner.beginOwner()
            val status = WifiAwareSupport.Status(true, false, "Synthetic temporary state")
            f.owner.publishSupportStatus(origin, status)
            f.owner.publishDebugSnapshot(origin, mapOf("synthetic" to "synthetic-link"),
                mapOf("synthetic" to "Synthetic peer"), setOf("synthetic"))
            assertEquals(status, f.owner.supportStatus.value)
            assertTrue(f.owner.supported.value)
            assertFalse(f.owner.available.value)
            assertEquals(setOf("synthetic"), f.owner.discoveredPeers.value)
            assertEquals("Synthetic peer", f.owner.knownPeers.value["synthetic"])
        }
    }

    @Test fun `ordinary enabled start reuse stop and identity lookup preserve behavior`() = runTest {
        withFixture { f ->
            f.owner.startIfPossible()
            assertEquals(0, f.factories.get())
            f.owner.setEnabled(true)
            val service = requireNotNull(f.owner.getService())
            val before = f.events.toList()
            repeat(5) { assertSame(service, f.owner.getService()) }
            assertEquals("identity lookup performs no callbacks", before, f.events.toList())
            f.owner.startIfPossible()
            assertEquals(1, f.factories.get())
            assertTrue(f.owner.running.value)
            f.owner.setEnabled(false)
            assertNull(f.owner.getService())
            assertFalse(f.owner.enabled.value)
            assertFalse(f.owner.running.value)
            assertEquals(1, f.stopRequests.get())
        }
    }

    @Test fun `ordinary overlapping hotspot leases release once without changing enabled choice`() = runTest {
        withFixture { f ->
            f.owner.setEnabled(true)
            val first = f.owner.acquireHotspotLease()
            val second = f.owner.acquireHotspotLease()
            first.close(); first.close()
            assertTrue(f.owner.isHeldForHotspot())
            assertTrue(f.owner.enabled.value)
            assertEquals(1, f.stopRequests.get())
            second.close()
            runCurrent()
            assertFalse(f.owner.isHeldForHotspot())
            assertEquals(2, f.factories.get())
            assertTrue(f.owner.running.value)
        }
    }

    @Test fun `ordinary restart coalesces and waits configured retry cadence`() = runTest {
        withFixture { f ->
            f.allowed = false
            f.owner.setEnabled(true)
            val initial = f.preparations.get()
            val origin = f.owner.currentOwner()
            f.owner.restartIfStillEnabled(origin, 10)
            f.owner.restartIfStillEnabled(origin, 10)
            runCurrent()
            advanceTimeBy(9); runCurrent()
            assertEquals(initial, f.preparations.get())
            advanceTimeBy(1); runCurrent()
            assertEquals(initial + 1, f.preparations.get())
            f.allowed = true
            advanceTimeBy(19); runCurrent()
            assertEquals(0, f.factories.get())
            advanceTimeBy(1); runCurrent()
            assertEquals(1, f.factories.get())
            assertTrue(f.owner.running.value)
        }
    }

    @Test fun `missing context or denied eligibility never constructs a product`() = runTest {
        withFixture { f ->
            f.contextReady = false
            f.owner.setEnabled(true)
            assertEquals(0, f.factories.get())
            f.contextReady = true
            f.allowed = false
            f.owner.startIfPossible()
            assertEquals(0, f.factories.get())
            assertNull(f.owner.getService())
        }
    }

    @Test fun `Unit stop request is not a checked product acknowledgment`() = runTest {
        withFixture { f ->
            f.acknowledge = { false }
            f.owner.setEnabled(true)
            val result = f.owner.retireForPanic()
            assertTrue(result.admittedStartsDrained)
            assertFalse(result.capturedProductsAcknowledged)
            assertEquals(1, f.stopRequests.get())
            assertEquals(1, f.acknowledgments.get())
        }
    }

    @Test fun `injected checked product completion is awaited independently`() = runTest {
        withFixture { f ->
            val release = CompletableDeferred<Unit>()
            f.acknowledge = { release.await(); true }
            f.owner.setEnabled(true)
            val retirement = async { f.owner.retireForPanic() }
            try {
                runCurrent()
                assertFalse(retirement.isCompleted)
                assertEquals(1, f.stopRequests.get())
                release.complete(Unit)
                val result = withTimeout(1_000) { retirement.await() }
                assertTrue(result.admittedStartsDrained)
                assertTrue(result.capturedProductsAcknowledged)
            } finally {
                release.complete(Unit)
                withTimeout(1_000) { retirement.cancelAndJoin() }
            }
        }
    }

    @Test fun `ordinary stop wins over a held reusable running probe`() = runTest {
        withFixture { f ->
            f.owner.setEnabled(true)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            f.probe = { product ->
                val running = product.running
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); running
            }
            val worker = f.worker { f.owner.startIfPossible() }
            try {
                assertTrue("reusable probe must enter", entered.await(5, TimeUnit.SECONDS))
                f.owner.stop()
                release.countDown(); f.reap(worker)
                assertNull(f.owner.getService())
                assertFalse(f.owner.running.value)
                assertEquals(1, f.factories.get())
                assertEquals(1, f.stopRequests.get())
            } finally { release.countDown(); f.reap(worker) }
        }
    }

    @Test fun `ordinary hotspot hold wins over a held reusable running probe`() = runTest {
        withFixture { f ->
            f.owner.setEnabled(true)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            f.probe = { product ->
                val running = product.running
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); running
            }
            val worker = f.worker { f.owner.startIfPossible() }
            var hold: AutoCloseable? = null
            try {
                assertTrue("reusable probe must enter", entered.await(5, TimeUnit.SECONDS))
                hold = f.owner.acquireHotspotLease()
                release.countDown(); f.reap(worker)
                assertNull(f.owner.getService())
                assertFalse(f.owner.running.value)
                assertTrue(f.owner.enabled.value)
                assertTrue(f.owner.isHeldForHotspot())
                assertEquals(1, f.factories.get())
            } finally { release.countDown(); f.reap(worker); hold?.close() }
        }
    }

    @Test fun `ordinary stop reconciles a delayed candidate running publication`() = runTest {
        withFixture { f ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            f.beforeRunningPublication = { running ->
                if (running) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            }
            val worker = f.worker { f.owner.setEnabled(true) }
            try {
                assertTrue("candidate publication must enter", entered.await(5, TimeUnit.SECONDS))
                assertNotNull(f.owner.getService())
                f.owner.stop()
                assertFalse(f.owner.running.value)
                release.countDown(); f.reap(worker)
                assertNull(f.owner.getService())
                assertFalse("late observable emission must reconcile to the stopped RAM state", f.owner.running.value)
                assertEquals(1, f.stopRequests.get())
            } finally { release.countDown(); f.reap(worker) }
        }
    }

    @Test fun `ordinary stopped cleanup completes before coalesced fresh publication`() = runTest {
        withFixture { f ->
            f.owner.setEnabled(true)
            val old = requireNotNull(f.owner.getService())
            val origin = f.owner.currentOwner()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            f.onClearPeers = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            f.onPublished = {
                f.owner.publishDebugSnapshot(origin, mapOf("fresh" to "link"),
                    mapOf("fresh" to "Synthetic fresh"), setOf("fresh"))
            }
            val worker = f.worker { f.owner.onServiceStopped(origin, old) }
            try {
                assertTrue("old peer cleanup must enter", entered.await(5, TimeUnit.SECONDS))
                f.owner.startIfPossible()
                f.owner.startIfPossible()
                assertNull(f.owner.getService())
                assertEquals("starts coalesce without blocking on cleanup", 1, f.factories.get())
                release.countDown(); f.reap(worker)
                assertNotSame(old, f.owner.getService())
                assertTrue(f.owner.running.value)
                assertEquals(2, f.factories.get())
                assertEquals(setOf("fresh"), f.owner.discoveredPeers.value)
                assertEquals("Synthetic fresh", f.owner.knownPeers.value["fresh"])
                assertEquals(1, f.peerClears.get())
            } finally { release.countDown(); f.reap(worker) }
        }
    }

    @Test fun `ordinary candidate completing during cleanup stays retained until effects finish`() = runTest {
        withFixture { f ->
            val factoryEntered = CountDownLatch(1)
            val factoryRelease = CountDownLatch(1)
            val cleanupEntered = CountDownLatch(1)
            val cleanupRelease = CountDownLatch(1)
            f.onFactory = { _, _ -> factoryEntered.countDown(); check(factoryRelease.await(5, TimeUnit.SECONDS)) }
            f.onClearPeers = { cleanupEntered.countDown(); check(cleanupRelease.await(5, TimeUnit.SECONDS)) }
            val start = f.worker { f.owner.setEnabled(true) }
            var stop: Thread? = null
            try {
                assertTrue("factory must enter", factoryEntered.await(5, TimeUnit.SECONDS))
                val stopping = f.worker { f.owner.stop() }
                stop = stopping
                assertTrue("cleanup must enter", cleanupEntered.await(5, TimeUnit.SECONDS))
                factoryRelease.countDown(); f.reap(start)
                assertNull(f.owner.getService())
                assertTrue(f.published.isEmpty())
                assertFalse(f.owner.running.value)
                cleanupRelease.countDown(); f.reap(stopping)
                assertNotNull("the ordinary baseline candidate remains owned and publishable", f.owner.getService())
                assertTrue(f.owner.running.value)
                assertEquals(1, f.published.size)
                assertEquals(1, f.factories.get())
            } finally {
                factoryRelease.countDown(); cleanupRelease.countDown()
                f.reap(start); stop?.let(f::reap)
            }
        }
    }

    @Test fun `throwing reusable running probe does not wedge later ordinary starts`() = runTest {
        withFixture { f ->
            f.owner.setEnabled(true)
            val product = requireNotNull(f.owner.getService())
            val expected = IllegalStateException("Synthetic probe failure")
            f.probe = { throw expected }
            try {
                f.owner.startIfPossible()
                fail("throwing probe must retain its original failure semantics")
            } catch (actual: IllegalStateException) { assertSame(expected, actual) }
            val probes = AtomicInteger()
            f.probe = { probes.incrementAndGet(); it.running }
            f.owner.startIfPossible()
            assertEquals("a failed reusable probe must release start admission", 1, probes.get())
            assertSame(product, f.owner.getService())
            assertTrue(f.owner.running.value)
            assertEquals(1, f.factories.get())
        }
    }

    @Test fun `ordinary cleanup failure still replays a reentrant coalesced start`() = runTest {
        withFixture { f ->
            f.owner.setEnabled(true)
            val old = requireNotNull(f.owner.getService())
            val origin = f.owner.currentOwner()
            f.onClearPeers = {
                f.owner.startIfPossible()
                f.owner.startIfPossible()
                throw IllegalStateException("Synthetic cleanup failure")
            }
            f.onPublished = {
                f.owner.publishDebugSnapshot(origin, emptyMap(), mapOf("fresh" to "Synthetic fresh"), setOf("fresh"))
            }
            f.owner.onServiceStopped(origin, old)
            assertNotSame(old, f.owner.getService())
            assertNotNull(f.owner.getService())
            assertTrue(f.owner.running.value)
            assertEquals("reentrant starts coalesce even when the cleanup port throws", 2, f.factories.get())
            assertEquals(setOf("fresh"), f.owner.discoveredPeers.value)
            assertEquals("Synthetic fresh", f.owner.knownPeers.value["fresh"])
            assertTrue(f.errors.isEmpty())
        }
    }

    @Test fun `ordinary disable rejects a product from an already admitted factory`() = runTest {
        withFixture { f ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            f.onFactory = { _, _ -> entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            val worker = f.worker { f.owner.setEnabled(true) }
            try {
                assertTrue("synthetic factory must enter", entered.await(5, TimeUnit.SECONDS))
                f.owner.setEnabled(false)
                release.countDown(); f.reap(worker)
                assertFalse("the explicit disabled choice remains authoritative", f.owner.enabled.value)
                assertFalse("a late factory product must not start after disable", f.events.contains("start"))
                assertTrue(f.published.isEmpty())
                assertNull(f.owner.getService())
                assertFalse(f.owner.running.value)
                assertEquals("the rejected candidate receives exactly one stop request", 1, f.stopRequests.get())
                assertEquals(1, f.factories.get())
            } finally { release.countDown(); f.reap(worker) }
        }
    }

    @Test fun `concurrent retirements share the captured product acknowledgment`() = runTest {
        withFixture { f ->
            val release = CompletableDeferred<Unit>()
            f.acknowledge = { release.await(); true }
            f.owner.setEnabled(true)
            val first = async { f.owner.retireForPanic() }
            val second = async { f.owner.retireForPanic() }
            try {
                runCurrent()
                assertFalse("the capturing retirement must await its product", first.isCompleted)
                assertFalse("a concurrent retirement cannot mistake an owned acknowledgment for absence", second.isCompleted)
                assertEquals(1, f.acknowledgments.get())
                assertEquals(1, f.stopRequests.get())
                release.complete(Unit)
                val firstResult = withTimeout(1_000) { first.await() }
                val secondResult = withTimeout(1_000) { second.await() }
                assertEquals(firstResult, secondResult)
                assertTrue(firstResult.admittedStartsDrained)
                assertTrue(firstResult.capturedProductsAcknowledged)
                assertEquals(1, f.acknowledgments.get())
                assertEquals(1, f.stopRequests.get())
            } finally {
                release.complete(Unit)
                withTimeout(1_000) { first.cancelAndJoin() }
                withTimeout(1_000) { second.cancelAndJoin() }
            }
        }
    }

    @Test fun `cancelling the retirement leader cannot cancel a shared product check`() = runTest {
        withFixture { f ->
            val release = CompletableDeferred<Unit>()
            f.acknowledge = { release.await(); true }
            f.owner.setEnabled(true)
            val first = async { f.owner.retireForPanic() }
            val second = async { f.owner.retireForPanic() }
            try {
                runCurrent()
                assertEquals(1, f.acknowledgments.get())
                first.cancel()
                runCurrent()
                assertFalse("cancelled caller still owns its noncancellable cleanup", first.isCompleted)
                assertFalse("follower still waits for the shared check", second.isCompleted)
                assertEquals(1, f.stopRequests.get())
                release.complete(Unit)
                withTimeout(1_000) { first.join() }
                val result = withTimeout(1_000) { second.await() }
                assertTrue(first.isCancelled)
                assertTrue(result.admittedStartsDrained)
                assertTrue(result.capturedProductsAcknowledged)
                assertEquals(1, f.acknowledgments.get())
                assertEquals(1, f.stopRequests.get())
            } finally {
                release.complete(Unit)
                withTimeout(1_000) { first.cancelAndJoin() }
                withTimeout(1_000) { second.cancelAndJoin() }
            }
        }
    }

    @Test fun `current environment availability survives reinitialization without reviving old service callbacks`() = runTest {
        withFixture { f ->
            f.owner.setEnabled(true)
            val old = f.owner.currentOwner()
            f.owner.retireForPanic()
            val fresh = f.owner.beginOwner()
            val available = WifiAwareSupport.Status(true, true)
            f.owner.environmentChanged { available }
            f.owner.publishSupportStatus(old, WifiAwareSupport.Status(false, false, "Synthetic stale service"))
            runCurrent()
            advanceTimeBy(499); runCurrent()
            assertNull(f.owner.getService())
            advanceTimeBy(1); runCurrent()
            assertSame(fresh, f.owner.currentOwner())
            assertEquals(available, f.owner.supportStatus.value)
            assertTrue(f.owner.supported.value)
            assertTrue(f.owner.available.value)
            assertTrue(f.owner.running.value)
            assertNotNull(f.owner.getService())
            assertEquals(2, f.factories.get())
        }
    }

    @Test fun `fresh factory may return while old factory stop effects still precede fresh start`() = runTest {
        withFixture { f ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val factories = AtomicInteger()
            val sharedPeers = AtomicReference<String?>("Synthetic before")
            val events = ConcurrentLinkedQueue<String>()
            val owner = WifiAwareControllerOwner(WifiAwareControllerPorts<Product>(
                scope = CoroutineScope(f.job + StandardTestDispatcher(testScheduler)),
                contextReady = { true }, prepareStart = { true }, restartSupported = { true },
                factory = {
                    val number = factories.incrementAndGet()
                    if (number == 1) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                    Product(number)
                },
                start = { it.running = true; sharedPeers.set("Synthetic peer ${it.number}"); events += "start${it.number}" },
                isRunning = { it.running },
                requestStop = { it.running = false; sharedPeers.set(null); events += "stop${it.number}" },
                awaitProductRetirement = { true },
                published = { events += "publish${it.number}" },
                clearPeers = { sharedPeers.set(null) }, debugMessage = {}, failure = { f.errors.add(it) }
            ))
            val worker = f.worker { owner.setEnabled(true) }
            try {
                assertTrue("old factory must enter", entered.await(5, TimeUnit.SECONDS))
                val fresh = owner.beginOwner()
                owner.startIfPossible()
                assertEquals("B factory is admitted without waiting for A", 2, factories.get())
                assertTrue("B stays inert while A's stop could clear shared peers", events.isEmpty())
                assertNull(owner.getService())
                assertFalse(owner.running.value)
                release.countDown(); f.reap(worker)
                assertEquals(listOf("stop1", "start2", "publish2"), events.toList())
                assertEquals("Synthetic peer 2", sharedPeers.get())
                assertSame(fresh, owner.currentOwner())
                assertEquals(2, requireNotNull(owner.getService()).number)
                assertTrue(owner.running.value)
            } finally { release.countDown(); f.reap(worker) }
        }
    }

    private suspend fun TestScope.withFixture(block: suspend (Fixture) -> Unit) {
        val f = Fixture(this)
        try { block(f) } finally { withTimeout(1_000) { f.job.cancelAndJoin() } }
    }

    private class Product(val number: Int) {
        @Volatile var running = false
    }

    private class Fixture(scope: TestScope) {
        val job = SupervisorJob()
        val factories = AtomicInteger()
        val preparations = AtomicInteger()
        val stopRequests = AtomicInteger()
        val acknowledgments = AtomicInteger()
        val peerClears = AtomicInteger()
        val events = ConcurrentLinkedQueue<String>()
        val published = ConcurrentLinkedQueue<Product>()
        val errors = ConcurrentLinkedQueue<Throwable>()
        @Volatile var allowed = true
        @Volatile var contextReady = true
        var onFactory: ((Int, WifiAwareOwnerToken) -> Unit)? = null
        var shouldRun: (Product) -> Boolean = { true }
        var acknowledge: suspend (Product) -> Boolean = { true }
        var probe: (Product) -> Boolean = { it.running }
        var onClearPeers: () -> Unit = {}
        var onPublished: (Product) -> Unit = {}
        var beforeRunningPublication: (Boolean) -> Unit = {}
        val owner: WifiAwareControllerOwner<Product> = WifiAwareControllerOwner(WifiAwareControllerPorts(
            scope = CoroutineScope(job + StandardTestDispatcher(scope.testScheduler)),
            contextReady = { contextReady },
            prepareStart = { preparations.incrementAndGet(); allowed },
            restartSupported = { true },
            factory = { origin ->
                val number = factories.incrementAndGet()
                onFactory?.invoke(number, origin)
                Product(number)
            },
            start = { it.running = shouldRun(it); events += "start" },
            isRunning = { probe(it) },
            requestStop = { it.running = false; stopRequests.incrementAndGet(); events += "stop" },
            awaitProductRetirement = { acknowledgments.incrementAndGet(); acknowledge(it) },
            published = { published.add(it); events += "publish"; onPublished(it) },
            clearPeers = { onClearPeers(); peerClears.incrementAndGet() },
            debugMessage = { events += "debug" },
            failure = { errors.add(it) },
            restartDelayMs = 20,
            maxRestartAttempts = 3,
            beforeRunningPublication = { beforeRunningPublication(it) }
        ))

        fun worker(action: () -> Unit): Thread = Thread({
            try { action() } catch (failure: Throwable) { errors.add(failure) }
        }, "synthetic-controller-start").apply { isDaemon = true; start() }

        fun reap(worker: Thread) {
            worker.join(5_000)
            if (worker.isAlive) { worker.interrupt(); worker.join(5_000) }
            assertFalse("owned synthetic worker must exit", worker.isAlive)
            assertTrue("synthetic worker failures are fixture errors: $errors", errors.isEmpty())
        }
    }
}
