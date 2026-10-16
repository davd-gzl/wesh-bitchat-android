package com.bitchat.android.startup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StartupCoordinatorTest {
    @Test fun suspendedStorageDoesNotPublishReadyOrDuplicatePreparation() = runTest {
        val release = CompletableDeferred<String>()
        var calls = 0
        val startup = StartupCoordinator(backgroundScope) { calls++; release.await() }
        repeat(20) { startup.start() }
        runCurrent()
        assertEquals(1, calls)
        assertTrue(startup.state.value is StartupCoordinator.State.Initializing)
        release.complete("stable-identity")
        runCurrent()
        assertEquals("stable-identity", startup.awaitReady())
        repeat(20) { startup.start(retry = true) }
        runCurrent()
        assertEquals(1, calls)
    }

    @Test fun failedStorageRequiresExplicitRetryAndKeepsIdentitySource() = runTest {
        val identity = Any()
        var calls = 0
        val startup = StartupCoordinator(backgroundScope) {
            if (++calls == 1) error("Storage unavailable")
            identity
        }
        startup.start(); runCurrent()
        assertTrue(startup.state.value is StartupCoordinator.State.Failed)
        startup.start(); runCurrent()
        assertEquals(1, calls)
        startup.start(retry = true); runCurrent()
        assertSame(identity, startup.awaitReady())
        assertEquals(2, calls)
    }

    @Test fun destroyingOneUiWaiterDoesNotCancelProcessPreparation() = runTest {
        val release = CompletableDeferred<String>()
        var calls = 0
        val startup = StartupCoordinator(backgroundScope) { calls++; release.await() }
        var cancelledOwnerActions = 0
        val firstActivity = async { startup.awaitReady().also { cancelledOwnerActions++ } }
        runCurrent()
        firstActivity.cancelAndJoin()
        val recreatedActivity = async { startup.awaitReady() }
        runCurrent()
        assertEquals(1, calls)
        release.complete("same-identity")
        runCurrent()
        assertEquals("same-identity", recreatedActivity.await())
        assertEquals(0, cancelledOwnerActions)
    }

    @Test fun routesWaitForBothGatesAndDispatchOnlyLatestIntentOnce() {
        val gate = StartupRouteGate()
        var latest = "first-qr"
        val routed = mutableListOf<String>()
        val dispatch = { routed.add(latest); Unit }
        gate.dispatchIfReady(false, true, dispatch)
        gate.dispatchIfReady(true, false, dispatch)
        latest = "latest-qr"
        assertTrue(routed.isEmpty())
        gate.dispatchIfReady(true, true, dispatch)
        gate.dispatchIfReady(true, true, dispatch)
        assertEquals(listOf("latest-qr"), routed)
        // A recreated Activity preserves the consumed flag.
        StartupRouteGate(gate.pending).dispatchIfReady(true, true, dispatch)
        assertEquals(1, routed.size)
        latest = "new-qr"
        gate.pending = true
        gate.dispatchIfReady(true, true, dispatch)
        assertEquals(listOf("latest-qr", "new-qr"), routed)
    }
}
