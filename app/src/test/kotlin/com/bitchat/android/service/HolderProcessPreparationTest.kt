package com.bitchat.android.service

import com.bitchat.android.startup.StartupCoordinator
import com.bitchat.android.sync.GossipSyncManager
import com.bitchat.android.wifiaware.HolderInitialBinding
import com.bitchat.android.wifiaware.WifiAwareControllerOwner
import com.bitchat.android.wifiaware.WifiAwareControllerPorts
import com.bitchat.android.wifiaware.WifiAwareFactoryResult
import com.bitchat.android.wifiaware.WifiAwareOwnerToken
import com.bitchat.android.wifiaware.WifiAwareSupport
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual owners and preparation coordinator with pure anchors, RAM products and owned test jobs. */
@OptIn(ExperimentalCoroutinesApi::class)
class HolderProcessPreparationTest {
    @Test fun `initial preparation is pure and idempotently retains the same original identity`() {
        val f = HolderFixture()
        val first = f.owner.initialProcessPreparation()
        val second = f.owner.initialProcessPreparation()
        assertSame(first, second)
        assertSame(first.origin, second.origin)
        assertTrue(f.effects.isEmpty())
        val snapshot = f.owner.provenanceSnapshot()
        assertEquals(0, snapshot.activeEntryCount)
        assertTrue(snapshot.constructions.isEmpty())
        assertTrue(snapshot.registrations.isEmpty())
        val foreign = HolderFixture().owner.initialProcessPreparation().origin
        var creators = 0
        val result = runCatching { f.owner.constructWifi(foreign, Any()) { creators++; Wifi(it) } }
        assertTrue("Foreign identity is API validation, not a panic decision", result.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, creators)
        assertTrue(f.owner.provenanceSnapshot().constructions.isEmpty())
        assertSame(first, f.owner.initialProcessPreparation())
        assertTrue(f.effects.isEmpty())
    }

    @Test fun `wifi returned receipt preserves separate original H and existing entry epoch`() {
        val f = HolderFixture()
        val original = f.owner.initialProcessPreparation().origin
        val controller = Any()
        var dispatched: HolderWifiConstructionReceipt<Manager>? = null
        val product = f.owner.constructWifi(original, controller) { context ->
            dispatched = f.owner.provenanceSnapshot().wifiConstructions.single()
            assertSame(context, checkNotNull(dispatched).context)
            assertSame(HolderConstructionOutcome.Dispatched, checkNotNull(dispatched).outcome)
            assertSame(original, originalOf(checkNotNull(dispatched).entry))
            assertSame(HolderWifiManagerSelection.NotSelected, checkNotNull(dispatched).selection)
            Wifi(context)
        }
        val after = f.owner.provenanceSnapshot()
        val receipt = after.wifiConstructions.single()
        assertSame(controller, receipt.controllerOrigin)
        assertSame(checkNotNull(dispatched).entry, receipt.entry)
        assertSame(checkNotNull(dispatched).entry.epoch, receipt.entry.epoch)
        assertSame(original, originalOf(receipt.entry))
        assertSame(product, (receipt.outcome as HolderConstructionOutcome.Returned).product)
        assertEquals(0, after.activeEntryCount)
        assertSame(original, f.owner.initialProcessPreparation().origin)
    }

    @Test fun `nested reuse never upgrades a legacy cached product receipt`() {
        val f = HolderFixture()
        val original = f.owner.initialProcessPreparation().origin
        val manager = Manager()
        val bluetooth = f.owner.getOrCreate { context ->
            f.owner.setGossipManager(HolderManagerProvenance.Constructed(context), manager) { it }
            Bluetooth()
        }
        val before = f.owner.provenanceSnapshot()
        val legacy = before.constructions.single()
        val registration = before.registrations.single()
        assertSame(HolderEntryProvenance.LegacyUnattributed, legacy.entry.provenance)
        val product = f.owner.constructWifi(original, Any()) { context ->
            f.owner.ensureBluetoothForWifi(context) { error("Existing product must be reused") }
            assertSame(bluetooth, f.owner.meshService)
            assertSame(registration, f.owner.selectManagerForWifi(context))
            Wifi(context)
        }
        val after = f.owner.provenanceSnapshot()
        assertEquals(2, after.constructions.size)
        assertSame(legacy.entry, after.constructions.first().entry)
        assertSame(legacy.outcome, after.constructions.first().outcome)
        assertSame(HolderEntryProvenance.LegacyUnattributed, after.constructions.first().entry.provenance)
        assertSame(registration, after.registrations.single())
        assertSame(legacy.entry, registration.entry)
        assertSame(original, originalOf(after.wifiConstructions.single().entry))
        assertSame(product, (after.wifiConstructions.single().outcome as HolderConstructionOutcome.Returned).product)
    }

    @Test fun `new nested construction uses the original wifi entry and selected manager provenance`() {
        val f = HolderFixture()
        val original = f.owner.initialProcessPreparation().origin
        val previous = Manager()
        f.owner.getOrCreate { context ->
            f.owner.setGossipManager(HolderManagerProvenance.Constructed(context), previous) { it }
            Bluetooth(reusable = false)
        }
        val firstRegistration = f.owner.provenanceSnapshot().registrations.single()
        val nested = Manager()
        val selected = Manager()
        f.owner.constructWifi(original, Any()) { context ->
            f.owner.ensureBluetoothForWifi(context) { nestedContext ->
                f.owner.setGossipManager(HolderManagerProvenance.Constructed(nestedContext), nested) { it }
                // A separate same-lifetime manager registration is valid; no BLE/manager-pair rule.
                f.owner.setGossipManager(HolderManagerProvenance.Constructed(nestedContext), selected) { it }
                Bluetooth()
            }
            val registration = requireNotNull(f.owner.selectManagerForWifi(context))
            assertSame(selected, registration.manager)
            Wifi(context)
        }
        val after = f.owner.provenanceSnapshot()
        val wifi = after.wifiConstructions.single()
        val nestedReceipt = after.constructions.last { it.kind == HolderConstructionKind.BLUETOOTH }
        assertSame(wifi.entry, nestedReceipt.entry)
        assertSame(original, originalOf(nestedReceipt.entry))
        assertSame(firstRegistration, after.registrations.first())
        assertSame(HolderEntryProvenance.LegacyUnattributed, requireNotNull(firstRegistration.entry).provenance)
        val selectedRegistration = (wifi.selection as HolderWifiManagerSelection.Selected<Manager>).registration
        assertSame(after.registrations.last(), selectedRegistration)
        assertSame(nestedReceipt.entry, requireNotNull(selectedRegistration).entry)
        assertSame(selected, selectedRegistration.manager)
    }

    @Test fun `ordinary clear and retry retain H and all original construction receipts`() {
        val f = HolderFixture()
        val preparation = f.owner.initialProcessPreparation()
        val original = preparation.origin
        val first = f.owner.constructWifi(original, Any()) { Wifi(it) }
        val firstReceipt = f.owner.provenanceSnapshot().wifiConstructions.single()
        f.owner.clear()
        assertSame(preparation, f.owner.initialProcessPreparation())
        val second = f.owner.constructWifi(original, Any()) { Wifi(it) }
        val failure = IllegalStateException("Synthetic partial constructor failure")
        assertSame(failure, runCatching {
            f.owner.constructWifi<Wifi>(original, Any()) { throw failure }
        }.exceptionOrNull())
        val receipts = f.owner.provenanceSnapshot().wifiConstructions
        assertEquals(3, receipts.size)
        assertSame(firstReceipt.entry, receipts[0].entry)
        assertSame(firstReceipt.outcome, receipts[0].outcome)
        assertSame(first, (receipts[0].outcome as HolderConstructionOutcome.Returned).product)
        assertSame(second, (receipts[1].outcome as HolderConstructionOutcome.Returned).product)
        assertNotSame(receipts[0].entry, receipts[1].entry)
        assertSame("Current ordinary clear does not rotate the legacy epoch", receipts[0].entry.epoch, receipts[1].entry.epoch)
        receipts.forEach { assertSame(original, originalOf(it.entry)) }
        assertEquals(HolderConstructionKind.WIFI, (receipts[2].outcome as HolderConstructionOutcome.Unknown).kind)
        assertSame(preparation, f.owner.initialProcessPreparation())
        assertEquals(0, f.owner.provenanceSnapshot().activeEntryCount)
    }

    @Test fun `uninitialized current stop environment and hotspot paths never bind or mint H`() = runTest {
        withController(HolderInitialBinding.Uninitialized) { f ->
            val token = f.owner.currentOwner()
            f.owner.setEnabled(true)
            f.owner.stop()
            var evaluations = 0
            f.owner.environmentChanged { evaluations++; WifiAwareSupport.Status(true, true) }
            val lease = f.owner.acquireHotspotLease()
            assertTrue(f.owner.isHeldForHotspot())
            lease.close()
            runCurrent()
            assertSame(token, f.owner.currentOwner())
            assertTrue(f.owner.enabled.value)
            assertTrue(f.owner.available.value)
            assertTrue(f.owner.supported.value)
            assertFalse(f.owner.isHeldForHotspot())
            assertEquals(1, evaluations)
            assertEquals(0, f.preparations)
            assertTrue(f.factories.isEmpty())
            assertTrue(f.started.isEmpty())
            assertTrue(f.published.isEmpty())
            assertTrue(f.failures.isEmpty())
        }
    }

    @Test fun `explicit initial binding passes exact H to constructor and publication`() = runTest {
        val holder = HolderFixture()
        val original = holder.owner.initialProcessPreparation().origin
        withController(HolderInitialBinding.Uninitialized) { f ->
            f.factory = { token, captured ->
                WifiAwareFactoryResult.Created(holder.owner.constructWifi(captured, token) { Wifi(it) })
            }
            val token = f.owner.beginOwner(original)
            f.owner.setEnabled(true)
            val product = requireNotNull(f.owner.getService())
            assertSame(token, f.factories.single().first)
            assertSame(original, f.factories.single().second)
            assertSame(product, f.started.single())
            assertSame(product, f.published.single().first)
            assertSame(original, f.published.single().second)
            val receipt = holder.owner.provenanceSnapshot().wifiConstructions.single()
            assertSame(token, receipt.controllerOrigin)
            assertSame(original, originalOf(receipt.entry))
            assertSame(product, (receipt.outcome as HolderConstructionOutcome.Returned).product)
        }
    }

    @Test fun `retry and delayed candidate work retain their admission H`() = runTest {
        val holder = HolderFixture()
        val original = holder.owner.initialProcessPreparation().origin
        withController(HolderInitialBinding.Bound(original)) { f ->
            val firstToken = f.owner.currentOwner()
            val entered = f.hold()
            val release = f.hold()
            f.factory = { token, captured ->
                val product = holder.owner.constructWifi(captured, token) { Wifi(it) }
                if (token === firstToken) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS)) { "Owned factory was not released" }
                }
                WifiAwareFactoryResult.Created(product)
            }
            val worker = f.worker { f.owner.setEnabled(true) }
            assertTrue("Actual original factory must enter", entered.await(5, TimeUnit.SECONDS))
            val secondToken = f.owner.beginOwner(original)
            f.owner.startIfPossible()
            assertEquals(2, f.factories.size)
            assertTrue("New candidate must wait for admitted old call cleanup", f.started.isEmpty())
            assertTrue(f.published.isEmpty())
            release.countDown()
            f.reap(worker)
            assertTrue("Successful held factory must not report a caught failure: ${f.failures}", f.failures.isEmpty())
            val receipts = holder.owner.provenanceSnapshot().wifiConstructions
            assertEquals(2, receipts.size)
            receipts.forEach {
                assertTrue("Both actual constructors must have returned", it.outcome is HolderConstructionOutcome.Returned)
                assertSame(original, originalOf(it.entry))
            }
            assertNotSame(receipts[0].entry, receipts[1].entry)
            val product = requireNotNull(f.owner.getService())
            assertSame(secondToken, f.factories.last().first)
            f.factories.forEach { assertSame(original, it.second) }
            assertSame(product, f.started.single())
            assertSame(original, f.published.single().second)
            product.running = false
            f.owner.onServiceRecoveryCleanupFinished(secondToken, product)
            f.owner.restartIfStillEnabled(secondToken, 500)
            runCurrent()
            advanceTimeBy(499); runCurrent()
            assertEquals(1, f.started.size)
            advanceTimeBy(1); runCurrent()
            assertEquals(2, f.started.size)
            assertEquals(2, f.factories.size)
            f.published.forEach { assertSame(product, it.first); assertSame(original, it.second) }
        }
    }

    @Test fun `ordinary controller reinitialization changes token without creating Holder authority`() = runTest {
        val holder = HolderFixture()
        val preparation = holder.owner.initialProcessPreparation()
        val original = preparation.origin
        withController(HolderInitialBinding.Bound(original)) { f ->
            f.factory = { token, captured ->
                WifiAwareFactoryResult.Created(holder.owner.constructWifi(captured, token) { Wifi(it) })
            }
            val firstToken = f.owner.currentOwner()
            f.owner.setEnabled(true)
            val firstProduct = requireNotNull(f.owner.getService())
            val secondToken = f.owner.beginOwner(original)
            f.owner.setEnabled(true)
            val secondProduct = requireNotNull(f.owner.getService())
            assertNotSame(firstToken, secondToken)
            assertNotSame(firstProduct, secondProduct)
            assertSame(preparation, holder.owner.initialProcessPreparation())
            assertEquals(2, f.factories.size)
            assertSame(firstToken, f.factories[0].first)
            assertSame(secondToken, f.factories[1].first)
            f.factories.forEach { assertSame(original, it.second) }
            f.published.forEach { assertSame(original, it.second) }
            assertSame(firstProduct, f.stopped.single())
            holder.owner.provenanceSnapshot().wifiConstructions.forEach { assertSame(original, originalOf(it.entry)) }
        }
    }

    @Test fun `actual startup coordinator retries retain the already captured original H`() = runTest {
        val holder = HolderFixture()
        val preparation = holder.owner.initialProcessPreparation()
        val original = preparation.origin
        val observed = mutableListOf<HolderOrigin>()
        var calls = 0
        val coordinator = StartupCoordinator(backgroundScope) {
            observed += original
            if (++calls == 1) error("Synthetic preparation failure")
            original
        }
        coordinator.start()
        runCurrent()
        assertTrue(coordinator.state.value is StartupCoordinator.State.Failed)
        coordinator.start(retry = true)
        runCurrent()
        assertSame(original, coordinator.awaitReady())
        assertEquals(2, observed.size)
        observed.forEach { assertSame(original, it) }
        assertSame(preparation, holder.owner.initialProcessPreparation())
        assertTrue(holder.effects.isEmpty())
        assertTrue(holder.owner.provenanceSnapshot().constructions.isEmpty())
    }

    private class Bluetooth(val reusable: Boolean = true)
    private class Manager { var delegate: GossipSyncManager.Delegate? = null }
    private class Wifi(val construction: HolderWifiConstructionContext) { @Volatile var running = false }

    private class HolderFixture {
        val effects = ConcurrentLinkedQueue<String>()
        val owner = MeshServiceHolderOwner(MeshServiceHolderPorts<Bluetooth, Any, Manager>(
            isReusable = { it.reusable }, stopBluetooth = { effects += "stop Bluetooth" }, refreshUnified = {},
            startGossip = { effects += "start gossip" }, stopGossip = { effects += "stop gossip" },
            clearGossip = { effects += "clear gossip" }, installGossipDelegate = { manager, delegate -> manager.delegate = delegate },
            broadcast = {}, sendToPeer = { _, _ -> }, log = { _, _ -> effects += "log" }
        ))
    }

    private class ControllerFixture(scope: TestScope, binding: HolderInitialBinding<HolderOrigin>) {
        val job = SupervisorJob()
        var preparations = 0
        val factories = CopyOnWriteArrayList<Pair<WifiAwareOwnerToken, HolderOrigin>>()
        val started = CopyOnWriteArrayList<Wifi>()
        val stopped = CopyOnWriteArrayList<Wifi>()
        val published = CopyOnWriteArrayList<Pair<Wifi, HolderOrigin>>()
        val failures = ConcurrentLinkedQueue<Throwable>()
        private val workerErrors = ConcurrentLinkedQueue<Throwable>()
        private val workers = mutableListOf<Thread>()
        private val holds = mutableListOf<CountDownLatch>()
        @Volatile var factory: (WifiAwareOwnerToken, HolderOrigin) -> WifiAwareFactoryResult<Wifi> = { _, _ ->
            error("Each control must explicitly supply its factory")
        }
        val owner = WifiAwareControllerOwner(binding, WifiAwareControllerPorts<Wifi, HolderOrigin>(
            scope = CoroutineScope(job + StandardTestDispatcher(scope.testScheduler)),
            contextReady = { true }, prepareStart = { preparations++; true }, restartSupported = { true },
            factory = { token, original -> factories += token to original; factory(token, original) },
            start = { it.running = true; started += it }, isRunning = { it.running },
            requestStop = { it.running = false; stopped += it }, awaitProductRetirement = { false },
            published = { product, original -> published += product to original },
            clearPeers = {}, debugMessage = {}, failure = { failures += it }, restartDelayMs = 20, maxRestartAttempts = 3
        ))
        fun hold() = CountDownLatch(1).also { holds += it }
        fun worker(action: () -> Unit): Thread = Thread({
            try { action() } catch (failure: Throwable) { workerErrors += failure }
        }, "synthetic-process-preparation-worker").apply { isDaemon = true; workers += this; start() }
        fun reap(worker: Thread) {
            worker.join(5_000)
            if (worker.isAlive) { worker.interrupt(); worker.join(5_000) }
            assertFalse("Owned worker must terminate", worker.isAlive)
            assertTrue("Owned worker failures are fixture errors: $workerErrors", workerErrors.isEmpty())
        }
        suspend fun close() = withContext(NonCancellable) {
            var primary: Throwable? = null
            fun remember(failure: Throwable) {
                val original = primary
                if (original == null) primary = failure else if (original !== failure) original.addSuppressed(failure)
            }
            fun attempt(action: () -> Unit) {
                try { action() } catch (failure: Throwable) { remember(failure) }
            }
            holds.forEach { it.countDown() }
            workers.forEach { attempt { reap(it) } }
            try { withTimeout(5_000) { owner.retireForPanic() } } catch (failure: Throwable) { remember(failure) }
            try { withTimeout(5_000) { job.cancelAndJoin() } } catch (failure: Throwable) {
                val original = primary
                if (original == null) primary = failure else if (original !== failure) original.addSuppressed(failure)
            }
            primary?.let { throw it }
        }
    }

    private suspend fun TestScope.withController(binding: HolderInitialBinding<HolderOrigin>, block: suspend (ControllerFixture) -> Unit) {
        val f = ControllerFixture(this, binding)
        var primary: Throwable? = null
        try {
            block(f)
            assertTrue("Successful Controller control must have no caught failures: ${f.failures}", f.failures.isEmpty())
        } catch (failure: Throwable) { primary = failure }
        finally {
            try { f.close() } catch (failure: Throwable) {
                val original = primary
                if (original == null) primary = failure else if (original !== failure) original.addSuppressed(failure)
            }
        }
        primary?.let { throw it }
    }

    private fun originalOf(entry: HolderEntryOrigin) = (entry.provenance as HolderEntryProvenance.Original).origin
}
