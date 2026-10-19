package com.bitchat.android.wifiaware

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

/** Actual lifecycle owner with RAM products and virtual time; no platform controller initialization. */
@OptIn(ExperimentalCoroutinesApi::class)
class WifiAwareRecoveryRestartTest {
    @Test fun `current product restarts after recovery finally despite cached running`() = runTest {
        withFixture { f ->
            f.owner.setEnabled(true)
            val origin = f.owner.currentOwner()
            val product = requireNotNull(f.owner.getService())
            product.running = false
            f.owner.onServiceRecoveryCleanupFinished(origin, product)
            f.owner.restartIfStillEnabled(origin, 500)
            runCurrent()
            advanceTimeBy(499); runCurrent()
            assertEquals("the existing restart delay must remain intact", 1, f.starts)
            advanceTimeBy(1); runCurrent()
            assertEquals("cleanup finally must allow the current inactive product to restart", 2, f.starts)
            assertEquals(1, f.factories)
            assertSame(product, f.owner.getService())
            assertTrue(product.running)
            assertTrue(f.owner.running.value)
            assertEquals(2, f.publications)
            assertEquals(0, f.stops)
            assertEquals(0, f.acknowledgments)
        }
    }

    @Test fun `early restart cannot dispatch before recovery finally arrives`() = runTest {
        withFixture { f ->
            f.owner.setEnabled(true)
            val origin = f.owner.currentOwner()
            val product = requireNotNull(f.owner.getService())
            product.running = false
            product.recovering = true
            f.owner.restartIfStillEnabled(origin, 2_000)
            runCurrent()
            advanceTimeBy(2_000); runCurrent()
            assertEquals(1, f.starts)
            assertEquals(0, f.startsDuringRecovery)
            assertEquals(1, f.factories)
            assertEquals(1, f.publications)
            assertEquals(0, f.stops)
            assertSame(product, f.owner.getService())
            assertFalse(product.running)
        }
    }

    @Test fun `repeated recovery finally notifications do not restart a live current product`() = runTest {
        withFixture { f ->
            f.owner.setEnabled(true)
            val origin = f.owner.currentOwner()
            val product = requireNotNull(f.owner.getService())
            repeat(3) {
                f.owner.onServiceRecoveryCleanupFinished(origin, product)
                f.owner.restartIfStillEnabled(origin, 500)
            }
            runCurrent()
            advanceTimeBy(500); runCurrent()
            assertEquals(1, f.starts)
            assertEquals(1, f.factories)
            assertEquals(1, f.publications)
            assertEquals(0, f.stops)
            assertSame(product, f.owner.getService())
            assertTrue(f.owner.running.value)
        }
    }

    @Test fun `stale lifetime and foreign product recovery cannot disturb the current product`() = runTest {
        withFixture { f ->
            f.owner.setEnabled(true)
            val oldOrigin = f.owner.currentOwner()
            val old = requireNotNull(f.owner.getService())
            val currentOrigin = f.owner.beginOwner(f.original)
            f.owner.startIfPossible()
            val previous = requireNotNull(f.owner.getService())
            f.owner.onServiceRecoveryCleanupFinished(oldOrigin, old)
            f.owner.restartIfStillEnabled(oldOrigin, 500)
            runCurrent()
            advanceTimeBy(500); runCurrent()
            assertSame(previous, f.owner.getService())
            assertTrue(f.owner.running.value)
            assertEquals(2, f.starts)
            // A later ordinary stop and start reuse the lifetime, but not the product identity.
            f.owner.stop()
            f.owner.startIfPossible()
            val current = requireNotNull(f.owner.getService())
            f.owner.publishDebugSnapshot(currentOrigin, mapOf("synthetic-peer" to "synthetic-link"),
                mapOf("synthetic-peer" to "Synthetic peer"), setOf("synthetic-peer"))
            val starts = f.starts
            val publications = f.publications
            val clears = f.peerClears
            val stops = f.stops
            assertNotSame(previous, current)
            assertFalse(old.running)
            assertFalse(previous.running)
            f.owner.onServiceRecoveryCleanupFinished(currentOrigin, previous)
            f.owner.restartIfStillEnabled(currentOrigin, 500)
            runCurrent()
            advanceTimeBy(500); runCurrent()
            assertSame(current, f.owner.getService())
            assertTrue(current.running)
            assertTrue(f.owner.running.value)
            assertEquals(starts, f.starts)
            assertEquals(3, f.factories)
            assertEquals(publications, f.publications)
            assertEquals(clears, f.peerClears)
            assertEquals(stops, f.stops)
            assertEquals("synthetic-live", f.peerSentinel)
            assertEquals(setOf("synthetic-peer"), f.owner.connectedPeers.value.keys)
            assertEquals("Synthetic peer", f.owner.knownPeers.value["synthetic-peer"])
            assertEquals(setOf("synthetic-peer"), f.owner.discoveredPeers.value)
        }
    }

    @Test fun `disabled choice cannot be reopened by recovery finally`() = runTest {
        withFixture { f ->
            f.owner.setEnabled(true)
            val origin = f.owner.currentOwner()
            val old = requireNotNull(f.owner.getService())
            f.owner.setEnabled(false)
            f.owner.onServiceRecoveryCleanupFinished(origin, old)
            f.owner.restartIfStillEnabled(origin, 500)
            runCurrent()
            advanceTimeBy(500); runCurrent()
            assertFalse(f.owner.enabled.value)
            assertFalse(f.owner.running.value)
            assertNull(f.owner.getService())
            assertEquals(1, f.starts)
            assertEquals(1, f.factories)
            assertEquals(1, f.publications)
            assertEquals(1, f.stops)
            assertEquals(0, f.acknowledgments)
        }
    }

    @Test fun `hotspot hold rejects recovery until ordinary once release restores service`() = runTest {
        withFixture { f ->
            f.owner.setEnabled(true)
            val origin = f.owner.currentOwner()
            val old = requireNotNull(f.owner.getService())
            val hold = f.owner.acquireHotspotLease()
            try {
                f.owner.onServiceRecoveryCleanupFinished(origin, old)
                f.owner.restartIfStillEnabled(origin, 500)
                runCurrent()
                advanceTimeBy(500); runCurrent()
                assertTrue(f.owner.isHeldForHotspot())
                assertTrue(f.owner.enabled.value)
                assertNull(f.owner.getService())
                assertFalse(f.owner.running.value)
                assertEquals(1, f.starts)
                assertEquals(1, f.factories)
                hold.close(); hold.close()
                // The already coalesced restart retains its existing 2-second retry cadence.
                advanceTimeBy(2_000); runCurrent()
                val current = requireNotNull(f.owner.getService())
                assertNotSame(old, current)
                assertFalse(f.owner.isHeldForHotspot())
                assertTrue(f.owner.running.value)
                assertEquals(2, f.starts)
                assertEquals(2, f.factories)
                assertEquals(2, f.publications)
                assertEquals(1, f.stops)
                f.owner.onServiceRecoveryCleanupFinished(origin, old)
                f.owner.restartIfStillEnabled(origin, 500)
                advanceTimeBy(500); runCurrent()
                assertSame(current, f.owner.getService())
                assertEquals(2, f.starts)
                assertEquals("synthetic-live", f.peerSentinel)
            } finally { hold.close() }
        }
    }

    @Test fun `retired owner recovery grants no start or product acknowledgment`() = runTest {
        withFixture { f ->
            f.owner.setEnabled(true)
            val origin = f.owner.currentOwner()
            val old = requireNotNull(f.owner.getService())
            val result = f.owner.retireForPanic()
            assertTrue(result.admittedStartsDrained)
            assertFalse("a Unit stop is not checked product retirement", result.capturedProductsAcknowledged)
            f.owner.onServiceRecoveryCleanupFinished(origin, old)
            f.owner.restartIfStillEnabled(origin, 500)
            runCurrent()
            advanceTimeBy(500); runCurrent()
            assertNull(f.owner.getService())
            assertFalse(f.owner.running.value)
            assertTrue(f.owner.enabled.value)
            assertEquals(1, f.starts)
            assertEquals(1, f.factories)
            assertEquals(1, f.publications)
            assertEquals(1, f.stops)
            assertEquals(1, f.acknowledgments)
            assertEquals(result, f.owner.retireForPanic())
            assertEquals(1, f.acknowledgments)
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
        try {
            withContext(NonCancellable) {
                withTimeout(1_000) { fixture.job.cancelAndJoin() }
            }
        } catch (failure: Throwable) { record(failure) }
        try {
            assertTrue("port failures are fixture errors: ${fixture.errors}", fixture.errors.isEmpty())
        } catch (failure: Throwable) { record(failure) }
        primary?.let { throw it }
    }

    private class Product {
        var running = false
        var recovering = false
    }

    private class Fixture(scope: TestScope) {
        val job = SupervisorJob()
        val original = Any()
        val errors = mutableListOf<Throwable>()
        var factories = 0
        var starts = 0
        var startsDuringRecovery = 0
        var publications = 0
        var stops = 0
        var acknowledgments = 0
        var peerClears = 0
        var peerSentinel: String? = null
        val owner = WifiAwareControllerOwner(HolderInitialBinding.Bound(original), WifiAwareControllerPorts(
            scope = CoroutineScope(job + StandardTestDispatcher(scope.testScheduler)),
            contextReady = { true },
            prepareStart = { true },
            restartSupported = { true },
            factory = { _: WifiAwareOwnerToken, _: Any -> factories++; WifiAwareFactoryResult.Created(Product()) },
            start = { product ->
                starts++
                if (product.recovering) startsDuringRecovery++ else product.running = true
            },
            isRunning = { it.running },
            requestStop = { it.running = false; stops++ },
            awaitProductRetirement = { acknowledgments++; false },
            published = { _, _ -> publications++; peerSentinel = "synthetic-live" },
            clearPeers = { peerClears++; peerSentinel = null },
            debugMessage = {},
            failure = { errors.add(it) }
        ))
    }
}
