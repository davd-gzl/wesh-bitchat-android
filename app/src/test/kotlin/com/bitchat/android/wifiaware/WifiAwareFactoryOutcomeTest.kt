package com.bitchat.android.wifiaware

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Actual Controller owner with explicit factory outcomes and owned RAM products, not live admission. */
@OptIn(ExperimentalCoroutinesApi::class)
class WifiAwareFactoryOutcomeTest {
    @Test fun `created product retains original token and exact start publication identity`() = runTest {
        withFixture { f ->
            val origin = f.owner.currentOwner()
            val product = Product()
            f.factory = { observed ->
                assertSame(origin, observed)
                f.allocations++
                WifiAwareFactoryResult.Created(product)
            }
            f.owner.setEnabled(true)
            assertEquals(1, f.factoryOrigins.size)
            assertSame(origin, f.factoryOrigins.single())
            assertSame(origin, f.owner.currentOwner())
            assertSame(product, f.owner.getService())
            assertSame(product, f.started.single())
            assertSame(product, f.published.single())
            assertEquals(1, f.allocations)
            assertTrue(f.failures.isEmpty())
            val result = withTimeout(5_000) { f.owner.retireForPanic() }
            assertTrue(result.admittedStartsDrained)
            assertFalse("Unit-like product stop is not a checked acknowledgment", result.capturedProductsAcknowledged)
            assertSame(product, f.stopped.single())
            assertSame(product, f.awaited.single())
        }
    }

    @Test fun `explicit refusal has no product effects and releases actual start admission`() = runTest {
        withFixture { f ->
            val origin = f.owner.currentOwner()
            f.factory = { WifiAwareFactoryResult.RefusedBeforeAllocation }
            f.owner.setEnabled(true)
            assertEquals(1, f.factoryOrigins.size)
            assertSame(origin, f.factoryOrigins.single())
            assertSame(origin, f.owner.currentOwner())
            assertEquals("Preparation is still before the factory", 1, f.preparations)
            assertEquals(0, f.allocations)
            assertNull(f.owner.getService())
            assertTrue(f.started.isEmpty())
            assertTrue(f.published.isEmpty())
            assertTrue(f.stopped.isEmpty())
            assertTrue(f.awaited.isEmpty())
            assertTrue(f.failures.isEmpty())
            val result = withTimeout(5_000) { f.owner.retireForPanic() }
            assertTrue("Refused admission must not remain held", result.admittedStartsDrained)
            assertTrue("Known empty products have no unknown constructor debt", result.capturedProductsAcknowledged)
            assertTrue(f.stopped.isEmpty())
            assertTrue(f.awaited.isEmpty())
        }
    }

    @Test fun `later ordinary start after explicit refusal uses the same lifetime and can create`() = runTest {
        withFixture { f ->
            val origin = f.owner.currentOwner()
            f.factory = { WifiAwareFactoryResult.RefusedBeforeAllocation }
            f.owner.setEnabled(true)
            assertEquals(1, f.factoryOrigins.size)
            assertTrue(f.started.isEmpty())
            val product = Product()
            f.factory = { f.allocations++; WifiAwareFactoryResult.Created(product) }
            f.owner.startIfPossible()
            assertEquals(2, f.factoryOrigins.size)
            f.factoryOrigins.forEach { assertSame(origin, it) }
            assertSame(origin, f.owner.currentOwner())
            assertEquals(2, f.preparations)
            assertEquals(1, f.allocations)
            assertSame(product, f.owner.getService())
            assertSame(product, f.started.single())
            assertSame(product, f.published.single())
            assertTrue(f.failures.isEmpty())
        }
    }

    @Test fun `later refusal cannot erase an opaque constructor failure in the same lifetime`() = runTest {
        withFixture { f ->
            val origin = f.owner.currentOwner()
            val failure = IllegalStateException("Synthetic opaque constructor failure")
            f.factory = { throw failure }
            f.owner.setEnabled(true)
            assertSame(failure, f.failures.single())
            assertEquals(1, f.factoryOrigins.size)
            assertNull(f.owner.getService())
            f.factory = { WifiAwareFactoryResult.RefusedBeforeAllocation }
            f.owner.startIfPossible()
            assertEquals(2, f.factoryOrigins.size)
            f.factoryOrigins.forEach { assertSame(origin, it) }
            assertSame(origin, f.owner.currentOwner())
            assertSame(failure, f.failures.single())
            assertEquals(0, f.allocations)
            assertNull(f.owner.getService())
            assertTrue(f.started.isEmpty())
            assertTrue(f.published.isEmpty())
            val result = withTimeout(5_000) { f.owner.retireForPanic() }
            assertTrue(result.admittedStartsDrained)
            assertFalse("Later known refusal cannot erase unknown constructor debt", result.capturedProductsAcknowledged)
            assertTrue(f.stopped.isEmpty())
            assertTrue(f.awaited.isEmpty())
            assertSame(failure, f.failures.single())
        }
    }

    private class Product(var running: Boolean = false)

    private class Fixture(scope: TestScope) {
        val job = SupervisorJob()
        var factory: (WifiAwareOwnerToken) -> WifiAwareFactoryResult<Product> = {
            error("Each synthetic control must explicitly supply a factory outcome")
        }
        var preparations = 0
        var allocations = 0
        val factoryOrigins = mutableListOf<WifiAwareOwnerToken>()
        val started = mutableListOf<Product>()
        val published = mutableListOf<Product>()
        val stopped = mutableListOf<Product>()
        val awaited = mutableListOf<Product>()
        val failures = mutableListOf<Throwable>()
        val owner = WifiAwareControllerOwner(WifiAwareControllerPorts<Product>(
            scope = CoroutineScope(job + StandardTestDispatcher(scope.testScheduler)),
            contextReady = { true },
            prepareStart = { preparations++; true },
            restartSupported = { true },
            factory = { origin -> factoryOrigins += origin; factory(origin) },
            start = { product -> product.running = true; started += product },
            isRunning = { it.running },
            requestStop = { product -> product.running = false; stopped += product },
            awaitProductRetirement = { product -> awaited += product; false },
            published = { published += it },
            clearPeers = {}, debugMessage = {}, failure = { failures += it }
        ))
        suspend fun close() = withContext(NonCancellable) {
            var primary: Throwable? = null
            try { withTimeout(5_000) { owner.retireForPanic() } } catch (failure: Throwable) { primary = failure }
            try { withTimeout(5_000) { job.cancelAndJoin() } } catch (failure: Throwable) {
                val original = primary
                if (original == null) primary = failure else if (original !== failure) original.addSuppressed(failure)
            }
            primary?.let { throw it }
        }
    }

    private suspend fun TestScope.withFixture(block: suspend (Fixture) -> Unit) {
        val f = Fixture(this)
        var primary: Throwable? = null
        try { block(f) } catch (failure: Throwable) { primary = failure }
        finally {
            try { f.close() } catch (failure: Throwable) {
                val original = primary
                if (original == null) primary = failure else if (original !== failure) original.addSuppressed(failure)
            }
        }
        primary?.let { throw it }
    }
}
