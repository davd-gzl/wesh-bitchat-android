package com.bitchat.android.service

import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.sync.GossipSyncManager
import com.bitchat.android.sync.GossipWorkOwner
import com.bitchat.android.wifiaware.WifiAwareFactoryResult
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Actual holder/helper and RAM managers only; no platform service is constructed. */
class HolderGossipConstructionOriginsTest {
    @Test fun returnedBleChildPrecedesOriginalDelegateInstallation() = withFixture { f ->
        val during = AtomicReference<List<HolderGossipChildSnapshot<GossipSyncManager>>>()
        val installed = AtomicReference<HolderGossipManagerReference<GossipSyncManager>>()
        lateinit var context: HolderConstructionContext
        lateinit var manager: GossipSyncManager
        val signed = packet(2)
        f.onInstall = { actual, delegate ->
            val reference = returned(f.owner.gossipConstructionSnapshot().single())
            assertSame(actual, reference.manager)
            assertSame(signed, delegate.signPacketForBroadcast(packet(1)))
            installed.set(reference)
        }
        val product = acquired(f.owner.getOrCreate { original ->
            context = original
            manager = f.owner.constructGossipManager(
                HolderGossipParent.Constructed(original), HolderGossipSource.BLE_SERVICE
            ) {
                // An owned different thread can read RAM while the real creator is entered.
                during.set(f.observe { f.owner.gossipConstructionSnapshot() })
                f.newManager()
            }
            f.owner.setGossipManager(HolderManagerProvenance.Constructed(original), manager) { signed }
            BluetoothProduct()
        })
        f.assertNoUnexpectedFailures()
        val first = during.get().single()
        assertSame(HolderGossipChildOutcome.Dispatched, first.outcome)
        val after = f.owner.gossipConstructionSnapshot().single()
        val reference = returned(after)
        assertSame(first.ticket, after.ticket)
        assertSame(HolderGossipChildOutcome.Dispatched, first.outcome)
        assertSame(manager, reference.manager)
        assertSame(reference, installed.get())
        assertSame(after.ticket, reference.ticket)
        assertEquals(after.source, reference.source)
        assertSame(after.provenance, reference.provenance)
        assertEquals(HolderGossipSource.BLE_SERVICE, reference.source)
        assertSame(context, constructed(reference).context)
        assertSame(f.owner.provenanceSnapshot().constructions.single().entry, constructed(reference).entry)
        assertSame(product, f.owner.meshService)
        assertSame(manager, f.owner.provenanceSnapshot().registrations.single().manager)
    }

    @Test fun replacementLoggingRetryRetainsBothReturnedManagerChildren() = withFixture { f ->
        val seed = BluetoothProduct(reusable = false)
        acquired(f.owner.getOrCreate { seed })
        val failure = IllegalStateException("Synthetic replacement log failure")
        f.expectedPortFailures += failure
        var failLog = true
        f.onLog = { _, message ->
            if (message == "Created new BluetoothMeshService (replacement)" && failLog) {
                failLog = false
                throw failure
            }
        }
        val products = mutableListOf<BluetoothProduct>()
        val managers = mutableListOf<GossipSyncManager>()
        val signed = listOf(packet(3), packet(4))
        val result = acquired(f.owner.getOrCreate { context ->
            val index = managers.size
            val manager = f.owner.constructGossipManager(
                HolderGossipParent.Constructed(context), HolderGossipSource.BLE_SERVICE
            ) { f.newManager() }
            managers += manager
            f.owner.setGossipManager(HolderManagerProvenance.Constructed(context), manager) { signed[index] }
            BluetoothProduct().also { products += it }
        })
        f.assertNoUnexpectedFailures()
        assertEquals(2, managers.size)
        assertSame(products[1], result)
        assertSame(result, f.owner.meshService)
        assertSame(failure, f.portFailures.single())
        assertEquals(listOf(seed), f.stoppedBluetooth.toList())
        val children = f.owner.gossipConstructionSnapshot()
        assertEquals(2, children.size)
        val first = returned(children[0])
        val second = returned(children[1])
        assertNotSame(first.ticket, second.ticket)
        assertNotSame(constructed(first).context, constructed(second).context)
        assertSame(managers[0], first.manager)
        assertSame(managers[1], second.manager)
        val parents = f.owner.provenanceSnapshot().constructions
        assertEquals(3, parents.size)
        assertSame(products[0], (parents[1].outcome as HolderConstructionOutcome.Returned).product)
        assertSame(products[1], (parents[2].outcome as HolderConstructionOutcome.Returned).product)
        assertEquals(2, f.owner.provenanceSnapshot().registrations.size)
        assertSame(signed[0], f.installed[0].second.signPacketForBroadcast(packet(1)))
        assertSame(signed[1], f.installed[1].second.signPacketForBroadcast(packet(1)))
    }

    @Test fun opaqueCreatorThrowRetainsUnknownAndRethrowsSameFailure() = withFixture { f ->
        val failure = IllegalStateException("Synthetic manager creator failure")
        lateinit var original: HolderConstructionContext
        lateinit var escaped: GossipSyncManager
        val observed = runCatching {
            f.owner.getOrCreate { context ->
                original = context
                f.owner.constructGossipManager(
                    HolderGossipParent.Constructed(context), HolderGossipSource.BLE_SERVICE
                ) {
                    escaped = f.newManager()
                    throw failure
                }
                BluetoothProduct()
            }
        }.exceptionOrNull()
        f.assertNoUnexpectedFailures()
        assertSame(failure, observed)
        assertTrue(f.managers.any { it === escaped })
        val failedSnapshot = f.owner.gossipConstructionSnapshot().single()
        assertSame(HolderGossipChildOutcome.Unknown, failedSnapshot.outcome)
        val parent = f.owner.provenanceSnapshot().constructions.single()
        assertSame(original, parent.context)
        assertEquals(HolderConstructionKind.BLUETOOTH, (parent.outcome as HolderConstructionOutcome.Unknown).kind)
        assertNull(f.owner.meshService)
        val later = f.owner.constructGossipManager(
            HolderGossipParent.Constructed(original), HolderGossipSource.BLE_SERVICE
        ) { f.newManager() }
        val after = f.owner.gossipConstructionSnapshot()
        assertEquals(2, after.size)
        assertSame(failedSnapshot.ticket, after[0].ticket)
        assertSame(HolderGossipChildOutcome.Unknown, after[0].outcome)
        assertSame(later, returned(after[1]).manager)
        assertSame(original, constructed(returned(after[1])).context)
        assertNotSame(after[0].ticket, after[1].ticket)
        assertSame(parent.outcome, f.owner.provenanceSnapshot().constructions.single().outcome)
    }

    @Test fun reentrantChildAndSelectedRegistrationKeepTheirOriginalReferences() = withFixture { f ->
        lateinit var context: HolderConstructionContext
        lateinit var nested: GossipSyncManager
        lateinit var outer: GossipSyncManager
        lateinit var nestedReference: HolderGossipManagerReference<GossipSyncManager>
        val signed = packet(5)
        acquired(f.owner.getOrCreate { original ->
            context = original
            outer = f.owner.constructGossipManager(
                HolderGossipParent.Constructed(original), HolderGossipSource.BLE_SERVICE
            ) {
                nested = f.owner.constructGossipManager(
                    HolderGossipParent.Constructed(original), HolderGossipSource.BLE_SERVICE
                ) { f.newManager() }
                val during = f.owner.gossipConstructionSnapshot()
                assertSame(HolderGossipChildOutcome.Dispatched, during[0].outcome)
                nestedReference = returned(during[1])
                f.owner.setGossipManager(HolderManagerProvenance.Constructed(original), nested) { signed }
                f.newManager()
            }
            BluetoothProduct()
        })
        val registration = f.owner.provenanceSnapshot().registrations.single()
        val wifi = created(f.owner.constructWifi(f.owner.initialProcessPreparation().origin, ControllerOrigin()) { original ->
            f.owner.ensureBluetoothForWifi(original) { throw AssertionError("Cached Bluetooth must be reused") }
            WifiProduct(original, f.owner.selectManagerForWifi(original))
        })
        f.assertNoUnexpectedFailures()
        val after = f.owner.gossipConstructionSnapshot()
        assertEquals(2, after.size)
        assertSame(outer, returned(after[0]).manager)
        assertSame(nestedReference, returned(after[1]))
        assertSame(nested, nestedReference.manager)
        assertNotSame(after[0].ticket, after[1].ticket)
        assertSame(context, constructed(nestedReference).context)
        assertSame(context, constructed(returned(after[0])).context)
        assertSame(registration, wifi.registration)
        assertSame(nestedReference.manager, wifi.registration!!.manager)
        val wifiReceipt = f.owner.provenanceSnapshot().wifiConstructions.single()
        assertNotSame(wifiReceipt.entry, constructed(nestedReference).entry)
        assertSame(constructed(nestedReference).entry, registration.entry)
        assertSame(signed, f.installed.single().second.signPacketForBroadcast(packet(1)))
    }

    @Test fun knownNullFallbackChildSurvivesUnknownWifiParent() = withFixture { f ->
        val failure = IllegalStateException("Synthetic later Wi-Fi initialization failure")
        lateinit var wifiContext: HolderWifiConstructionContext
        lateinit var shared: GossipSyncManager
        lateinit var fallback: GossipSyncManager
        val observed = runCatching {
            f.owner.constructWifi(f.owner.initialProcessPreparation().origin, ControllerOrigin()) { original ->
                wifiContext = original
                f.owner.ensureBluetoothForWifi(original) { bluetoothContext ->
                    shared = f.owner.constructGossipManager(
                        HolderGossipParent.Constructed(bluetoothContext), HolderGossipSource.BLE_SERVICE
                    ) { f.newManager() }
                    f.owner.setGossipManager(HolderManagerProvenance.Constructed(bluetoothContext), shared) { it }
                    BluetoothProduct()
                }
                assertSame(shared, f.owner.sharedGossipSyncManager)
                f.owner.clear()
                assertNull(f.owner.selectManagerForWifi(original))
                fallback = f.owner.constructGossipManager(
                    HolderGossipParent.Constructed(original), HolderGossipSource.CORE_FALLBACK
                ) { f.newManager() }
                throw failure
            }
        }.exceptionOrNull()
        f.assertNoUnexpectedFailures()
        assertSame(failure, observed)
        val wifi = f.owner.provenanceSnapshot().wifiConstructions.single()
        assertSame(wifiContext, wifi.context)
        assertEquals(HolderConstructionKind.WIFI, (wifi.outcome as HolderConstructionOutcome.Unknown).kind)
        assertNull((wifi.selection as HolderWifiManagerSelection.Selected<GossipSyncManager>).registration)
        assertNull(f.owner.sharedGossipSyncManager)
        val children = f.owner.gossipConstructionSnapshot()
        assertEquals(2, children.size)
        assertSame(shared, returned(children[0]).manager)
        val reference = returned(children[1])
        assertSame(fallback, reference.manager)
        assertSame(wifiContext, constructed(reference).context)
        assertSame(wifi.entry, constructed(reference).entry)
        assertEquals(HolderGossipSource.CORE_FALLBACK, reference.source)
        assertSame(shared, f.owner.provenanceSnapshot().registrations.single().manager)
        assertEquals(listOf(shared), f.clearedGossip.toList())
        assertEquals(listOf(shared), f.stoppedGossip.toList())
    }

    @Test fun acceptedLateContextStillRecordsChildAfterConstructionClose() = withFixture { f ->
        val facade = acquired(f.owner.getUnifiedOrCreate({ BluetoothProduct() }) { UnifiedProduct(it) })
        val binding = captureHolderPanicBinding(f.owner, facade)
        val originalH = f.owner.initialProcessPreparation().origin
        val entered = CountDownLatch(1)
        val release = f.releaseGate()
        val actualContext = AtomicReference<HolderWifiConstructionContext>()
        val result = AtomicReference<WifiAwareFactoryResult<WifiProduct>>()
        val creator = f.worker {
            result.set(f.owner.constructWifi(originalH, ControllerOrigin()) { context ->
                actualContext.set(context)
                entered.countDown()
                await(release, "late creator release")
                f.owner.constructGossipManager(
                    HolderGossipParent.Constructed(context), HolderGossipSource.CORE_FALLBACK
                ) { f.newManager() }
                WifiProduct(context)
            })
        }
        await(entered, "accepted Wi-Fi creator entry")
        val close = f.observe { closeHolderPanicBinding(binding, facade) }
        release.countDown()
        f.join(creator)
        f.assertNoUnexpectedFailures()
        assertSame(HolderConstructionCloseOutcome.ClosedForConstruction, close)
        val parent = f.owner.provenanceSnapshot()
        assertEquals(1, parent.constructions.count { it.kind == HolderConstructionKind.UNIFIED })
        val unifiedReceipt = parent.constructions.single { it.kind == HolderConstructionKind.UNIFIED }
        assertSame(facade, (unifiedReceipt.outcome as HolderConstructionOutcome.Returned).product)
        val wifi = created(result.get())
        val reference = returned(f.owner.gossipConstructionSnapshot().single())
        assertSame(actualContext.get(), wifi.context)
        assertSame(wifi.context, constructed(reference).context)
        assertSame(parent.wifiConstructions.single().entry, constructed(reference).entry)
        assertSame(originalH, (constructed(reference).entry.provenance as HolderEntryProvenance.Original).origin)
        assertEquals(0, parent.activeEntryCount)
        assertSame(HolderAcquisitionResult.RefusedBeforeAcquisition, f.owner.getOrCreate {
            throw AssertionError("Fresh acquisition must remain refused")
        })
    }

    @Test fun cachedReuseAndClearDoNotDuplicateOrForgetManagerChildren() = withFixture { f ->
        lateinit var firstContext: HolderConstructionContext
        lateinit var firstManager: GossipSyncManager
        val firstProduct = acquired(f.owner.getOrCreate { context ->
            firstContext = context
            firstManager = f.owner.constructGossipManager(
                HolderGossipParent.Constructed(context), HolderGossipSource.BLE_SERVICE
            ) { f.newManager() }
            f.owner.setGossipManager(HolderManagerProvenance.Constructed(context), firstManager) { it }
            BluetoothProduct()
        })
        val first = returned(f.owner.gossipConstructionSnapshot().single())
        repeat(3) {
            assertSame(firstProduct, acquired(f.owner.getOrCreate { throw AssertionError("Cached acquisition must not create") }))
        }
        assertEquals(1, f.owner.gossipConstructionSnapshot().size)
        f.owner.clear()
        lateinit var secondManager: GossipSyncManager
        acquired(f.owner.getOrCreate { context ->
            secondManager = f.owner.constructGossipManager(
                HolderGossipParent.Constructed(context), HolderGossipSource.BLE_SERVICE
            ) { f.newManager() }
            f.owner.setGossipManager(HolderManagerProvenance.Constructed(context), secondManager) { it }
            BluetoothProduct()
        })
        f.assertNoUnexpectedFailures()
        val after = f.owner.gossipConstructionSnapshot()
        assertEquals(2, after.size)
        assertSame(first, returned(after[0]))
        assertSame(firstManager, first.manager)
        assertSame(firstContext, constructed(first).context)
        assertSame(secondManager, returned(after[1]).manager)
        assertNotSame(first.ticket, after[1].ticket)
        assertNotSame(constructed(first).context, constructed(returned(after[1])).context)
        assertSame(constructed(first).entry.epoch, constructed(returned(after[1])).entry.epoch)
        assertEquals(2, f.owner.provenanceSnapshot().registrations.size)
        assertEquals(0, f.owner.provenanceSnapshot().activeEntryCount)
        assertEquals(listOf(firstManager), f.clearedGossip.toList())
        assertEquals(listOf(firstManager), f.stoppedGossip.toList())
    }

    @Test fun foreignContextRejectsCreatorAndExplicitUnattributedStaysUnknownOrigin() = withFixture { target ->
        withFixture { source ->
            lateinit var foreign: HolderConstructionContext
            acquired(source.owner.getOrCreate { context -> foreign = context; BluetoothProduct() })
            val sourceBefore = source.owner.provenanceSnapshot()
            var calls = 0
            val misuse = runCatching {
                target.owner.constructGossipManager(
                    HolderGossipParent.Constructed(foreign), HolderGossipSource.BLE_SERVICE
                ) { calls++; target.newManager() }
            }.exceptionOrNull()
            assertTrue(misuse is IllegalArgumentException)
            assertEquals(0, calls)
            assertTrue(target.owner.gossipConstructionSnapshot().isEmpty())
            assertTrue(target.owner.provenanceSnapshot().constructions.isEmpty())
            assertSame(sourceBefore.constructions.single().context, source.owner.provenanceSnapshot().constructions.single().context)
            assertSame(sourceBefore.constructions.single().outcome, source.owner.provenanceSnapshot().constructions.single().outcome)
            assertTrue(source.owner.gossipConstructionSnapshot().isEmpty())
            acquired(target.owner.getUnifiedOrCreate({ BluetoothProduct() }) { UnifiedProduct(it) })
            val parentBefore = target.owner.provenanceSnapshot()
            val wrongKind = parentBefore.constructions.single { it.kind == HolderConstructionKind.UNIFIED }.context
            assertTrue(runCatching {
                target.owner.constructGossipManager(
                    HolderGossipParent.Constructed(wrongKind), HolderGossipSource.BLE_SERVICE
                ) { calls++; target.newManager() }
            }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(0, calls)
            assertTrue(target.owner.gossipConstructionSnapshot().isEmpty())
            val manager = target.owner.constructGossipManager(
                HolderGossipParent.Unattributed, HolderGossipSource.CORE_FALLBACK
            ) { calls++; target.newManager() }
            target.assertNoUnexpectedFailures()
            source.assertNoUnexpectedFailures()
            val row = target.owner.gossipConstructionSnapshot().single()
            val reference = returned(row)
            assertEquals(1, calls)
            assertSame(manager, reference.manager)
            assertSame(HolderGossipProvenance.Unattributed, row.provenance)
            assertSame(HolderGossipProvenance.Unattributed, reference.provenance)
            assertEquals(HolderGossipSource.CORE_FALLBACK, reference.source)
            assertEquals(parentBefore, target.owner.provenanceSnapshot())
            assertTrue(target.owner.provenanceSnapshot().registrations.isEmpty())
        }
    }

    private class BluetoothProduct(var reusable: Boolean = true)
    private class UnifiedProduct(val bluetooth: BluetoothProduct)
    private class ControllerOrigin
    private class WifiProduct(
        val context: HolderWifiConstructionContext,
        val registration: HolderManagerRegistration<GossipSyncManager>? = null
    )

    private class Fixture {
        private val scopeJob = SupervisorJob()
        private val scope = CoroutineScope(scopeJob + Dispatchers.Unconfined)
        private val launches = AtomicInteger()
        private val workers = CopyOnWriteArrayList<Thread>()
        private val releases = CopyOnWriteArrayList<CountDownLatch>()
        private val workerFailures = CopyOnWriteArrayList<Throwable>()
        val portFailures = CopyOnWriteArrayList<Throwable>()
        val expectedPortFailures = CopyOnWriteArrayList<Throwable>()
        val managers = CopyOnWriteArrayList<GossipSyncManager>()
        val installed = CopyOnWriteArrayList<Pair<GossipSyncManager, GossipSyncManager.Delegate>>()
        val stoppedBluetooth = CopyOnWriteArrayList<BluetoothProduct>()
        val clearedGossip = CopyOnWriteArrayList<GossipSyncManager>()
        val stoppedGossip = CopyOnWriteArrayList<GossipSyncManager>()
        var onLog: ((HolderLogLevel, String) -> Unit)? = null
        var onInstall: ((GossipSyncManager, GossipSyncManager.Delegate) -> Unit)? = null
        val owner = MeshServiceHolderOwner(MeshServiceHolderPorts<BluetoothProduct, UnifiedProduct, GossipSyncManager>(
            isReusable = { it.reusable }, stopBluetooth = { stoppedBluetooth += it }, refreshUnified = {},
            startGossip = { throw AssertionError("Origin fixture must not start gossip") },
            stopGossip = { stoppedGossip += it }, clearGossip = { clearedGossip += it },
            installGossipDelegate = { manager, delegate ->
                manager.delegate = delegate
                installed += manager to delegate
                port { onInstall?.invoke(manager, delegate) }
            },
            broadcast = {}, sendToPeer = { _, _ -> },
            log = { level, message -> port { onLog?.invoke(level, message) } }
        ))

        fun newManager(): GossipSyncManager = GossipSyncManager(
            "1122334455667788", scope, object : GossipSyncManager.ConfigProvider {
                override fun seenCapacity() = 8
                override fun gcsMaxBytes() = 128
                override fun gcsTargetFpr() = 0.01
            }, workOwner = GossipWorkOwner(), jobCreator = { _, _, _ ->
                launches.incrementAndGet()
                throw AssertionError("Origin fixture must never dispatch jobs")
            }
        ).also { managers += it }

        private fun port(action: () -> Unit) {
            try { action() } catch (failure: Throwable) { portFailures += failure; throw failure }
        }

        fun releaseGate(): CountDownLatch = CountDownLatch(1).also { releases += it }

        fun worker(action: () -> Unit): Thread = Thread {
            try { action() } catch (failure: Throwable) { workerFailures += failure }
        }.apply {
            isDaemon = true
            workers += this
            start()
        }

        fun <T : Any> observe(action: () -> T): T {
            val value = AtomicReference<T>()
            val finished = CountDownLatch(1)
            val thread = worker {
                try { value.set(action()) } finally { finished.countDown() }
            }
            await(finished, "owned observation completion")
            join(thread)
            assertNoUnexpectedFailures()
            return value.get() ?: throw AssertionError("Owned observation returned no value")
        }

        fun join(thread: Thread) {
            thread.join(WAIT_MS)
            assertFalse("Owned worker must finish", thread.isAlive)
        }

        fun assertNoUnexpectedFailures() {
            val failures = workerFailures.toList() + portFailures.filter { actual ->
                expectedPortFailures.none { expected -> expected === actual }
            }
            if (failures.isNotEmpty()) {
                val failure = AssertionError("Unexpected owned fixture failure")
                failure.initCause(failures.first())
                failures.drop(1).forEach { if (it !== failure && it !== failure.cause) failure.addSuppressed(it) }
                throw failure
            }
            assertEquals("No real gossip job may be launched", 0, launches.get())
        }

        fun close() {
            var primary: Throwable? = null
            fun cleanup(action: () -> Unit) {
                try { action() } catch (failure: Throwable) {
                    val original = primary
                    if (original == null) primary = failure else if (original !== failure) original.addSuppressed(failure)
                }
            }
            releases.forEach { it.countDown() }
            workers.forEach { thread ->
                cleanup {
                    var boundFailure: Throwable? = null
                    try { thread.join(WAIT_MS) } catch (failure: Throwable) { boundFailure = failure }
                    if (thread.isAlive) {
                        val original = boundFailure ?: AssertionError("Owned worker exceeded its completion bound")
                        try {
                            thread.interrupt()
                            thread.join(WAIT_MS)
                            assertFalse("Owned worker must be reaped", thread.isAlive)
                        } catch (failure: Throwable) {
                            if (original !== failure) original.addSuppressed(failure)
                        }
                        throw original
                    }
                    boundFailure?.let { throw it }
                }
            }
            managers.forEach { it.delegate = null }
            cleanup { runBlocking { withTimeout(WAIT_MS) { scopeJob.cancelAndJoin() } } }
            cleanup { assertNoUnexpectedFailures() }
            primary?.let { throw it }
        }
    }

    private fun withFixture(action: (Fixture) -> Unit) {
        val fixture = Fixture()
        var primary: Throwable? = null
        try { action(fixture) } catch (failure: Throwable) { primary = failure }
        finally {
            try { fixture.close() } catch (failure: Throwable) {
                val original = primary
                if (original == null) primary = failure else if (original !== failure) original.addSuppressed(failure)
            }
        }
        primary?.let { throw it }
    }

    companion object {
        private const val WAIT_MS = 5_000L
        private fun await(latch: CountDownLatch, reason: String) {
            if (!latch.await(WAIT_MS, TimeUnit.MILLISECONDS)) throw AssertionError("Timed out: $reason")
        }
        private fun <T : Any> acquired(result: HolderAcquisitionResult<T>): T = when (result) {
            is HolderAcquisitionResult.Available -> result.product
            HolderAcquisitionResult.RefusedBeforeAcquisition -> throw AssertionError("Expected ordinary acquisition")
        }
        private fun <T : Any> created(result: WifiAwareFactoryResult<T>): T = when (result) {
            is WifiAwareFactoryResult.Created -> result.product
            WifiAwareFactoryResult.RefusedBeforeAllocation -> throw AssertionError("Expected accepted original construction")
        }
        private fun returned(row: HolderGossipChildSnapshot<GossipSyncManager>): HolderGossipManagerReference<GossipSyncManager> =
            when (val outcome = row.outcome) {
                is HolderGossipChildOutcome.Returned -> outcome.original
                else -> throw AssertionError("Expected returned manager")
            }
        private fun constructed(reference: HolderGossipManagerReference<GossipSyncManager>): HolderGossipProvenance.Constructed =
            reference.provenance as HolderGossipProvenance.Constructed
        private fun packet(value: Int) = BitchatPacket(
            type = 1u, senderID = ByteArray(8) { value.toByte() }, timestamp = 1uL,
            payload = byteArrayOf(value.toByte()), ttl = 1u
        )
    }
}
