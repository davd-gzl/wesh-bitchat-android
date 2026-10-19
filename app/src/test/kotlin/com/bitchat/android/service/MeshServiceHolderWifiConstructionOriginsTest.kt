package com.bitchat.android.service

import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.sync.GossipSyncManager
import org.junit.Assert.*
import org.junit.Test
import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Actual holder construction/selection paths with inert products, ports and owned workers. */
class MeshServiceHolderWifiConstructionOriginsTest {
    @Test fun `Wi-Fi creator enters during held Bluetooth factory and waits only at original ensure`() = withFixture { f ->
        val firstEntered = CountDownLatch(1)
        val releaseFirst = f.hold()
        val creatorEntered = CountDownLatch(1)
        val first = Bluetooth().also { it.reusable = false }
        val second = Bluetooth()
        val manager = Manager()
        val controller = Any()
        val result = AtomicReference<Wifi>()
        val firstWorker = f.worker {
            f.owner.getOrCreate {
                firstEntered.countDown()
                await(releaseFirst, "first factory release")
                first
            }
        }
        await(firstEntered, "first factory entry")
        val wifiWorker = f.worker {
            result.set(f.owner.constructWifi(controller) { context ->
                creatorEntered.countDown()
                f.owner.ensureBluetoothForWifi(context) { nested ->
                    f.owner.setGossipManager(HolderManagerProvenance.Constructed(nested), manager) { it }
                    second
                }
                Wifi(f.owner.selectManagerForWifi(context))
            })
        }
        await(creatorEntered, "Wi-Fi creator must enter before Bluetooth release")
        val during = f.bounded { f.owner.provenanceSnapshot() }
        val wifiDuring = during.wifiConstructions.single()
        assertSame(controller, wifiDuring.controllerOrigin)
        assertSame(HolderConstructionOutcome.Dispatched, wifiDuring.outcome)
        assertSame(HolderWifiManagerSelection.NotSelected, wifiDuring.selection)
        assertEquals(2, during.activeEntryCount)
        assertBlockedBy(wifiWorker.thread, firstWorker.thread)
        assertEquals(1L, wifiWorker.done.count)
        releaseFirst.countDown()
        await(wifiWorker.done, "Wi-Fi completion after release")
        f.joinWorkers()
        val after = f.bounded { f.owner.provenanceSnapshot() }
        val wifi = after.wifiConstructions.single()
        val nested = after.constructions.single {
            it.kind == HolderConstructionKind.BLUETOOTH && returned(it.outcome) === second
        }
        assertSame(wifiDuring.entry, wifi.entry)
        assertSame(wifi.entry, nested.entry)
        assertNotSame(after.constructions.first().entry, wifi.entry)
        assertSame(controller, wifi.controllerOrigin)
        assertSame(result.get(), returned(wifi.outcome))
        assertSame(manager, result.get().registration?.manager)
        assertSame(nested.context, (result.get().registration?.provenance as HolderManagerProvenance.Constructed).context)
        assertEquals(0, after.activeEntryCount)
    }

    @Test fun `separate selection consumes replacement manager with its own registration origin`() = withFixture { f ->
        val first = Bluetooth()
        val second = Bluetooth()
        val firstManager = Manager()
        val secondManager = Manager()
        val controller = Any()
        val result = f.owner.constructWifi(controller) { context ->
            f.owner.ensureBluetoothForWifi(context) { nested ->
                f.owner.setGossipManager(HolderManagerProvenance.Constructed(nested), firstManager) { it }
                first
            }
            first.reusable = false
            f.owner.getOrCreate { nested ->
                f.owner.setGossipManager(HolderManagerProvenance.Constructed(nested), secondManager) { it }
                second
            }
            Wifi(f.owner.selectManagerForWifi(context))
        }
        val snapshot = f.owner.provenanceSnapshot()
        val wifi = snapshot.wifiConstructions.single()
        val selected = selected(wifi)
        val replacement = snapshot.constructions.single { returned(it.outcome) === second }
        assertSame(result.registration, selected)
        assertSame(secondManager, selected?.manager)
        assertSame(replacement.context, (selected?.provenance as HolderManagerProvenance.Constructed).context)
        assertSame(replacement.entry, selected?.entry)
        assertNotSame(wifi.entry, selected?.entry)
        assertSame(wifi.entry.epoch, selected?.entry?.epoch)
        assertSame(controller, wifi.controllerOrigin)
        assertEquals(1, first.stops)
    }

    @Test fun `known absence is selected null rather than an unreached selection`() = withFixture { f ->
        val controller = Any()
        val result = f.owner.constructWifi(controller) { context ->
            f.owner.ensureBluetoothForWifi(context) { Bluetooth() }
            assertNull(f.owner.selectManagerForWifi(context))
            Wifi(null)
        }
        val after = f.owner.provenanceSnapshot()
        val wifi = after.wifiConstructions.single()
        assertTrue(wifi.selection is HolderWifiManagerSelection.Selected<*>)
        assertNull(selected(wifi))
        assertSame(result, returned(wifi.outcome))
        assertSame(controller, wifi.controllerOrigin)
        assertEquals(0, after.activeEntryCount)
    }

    @Test fun `throw before selection retains original token and unreached selection`() = withFixture { f ->
        val controller = Any()
        val failure = IllegalStateException("Synthetic constructor failure")
        assertSame(failure, runCatching {
            f.owner.constructWifi<Wifi>(controller) { throw failure }
        }.exceptionOrNull())
        val after = f.owner.provenanceSnapshot()
        val wifi = after.wifiConstructions.single()
        assertSame(controller, wifi.controllerOrigin)
        assertSame(HolderWifiManagerSelection.NotSelected, wifi.selection)
        assertEquals(HolderConstructionKind.WIFI, (wifi.outcome as HolderConstructionOutcome.Unknown).kind)
        assertEquals(0, after.activeEntryCount)
    }

    @Test fun `throw after selection retains exact registration and original failure`() = withFixture { f ->
        val manager = Manager()
        val failure = IllegalStateException("Synthetic post-selection failure")
        val controller = Any()
        var registration: HolderManagerRegistration<Manager>? = null
        assertSame(failure, runCatching {
            f.owner.constructWifi<Wifi>(controller) { context ->
                f.owner.ensureBluetoothForWifi(context) { nested ->
                    f.owner.setGossipManager(HolderManagerProvenance.Constructed(nested), manager) { it }
                    Bluetooth()
                }
                registration = f.owner.selectManagerForWifi(context)
                throw failure
            }
        }.exceptionOrNull())
        val after = f.owner.provenanceSnapshot()
        val wifi = after.wifiConstructions.single()
        assertSame(registration, selected(wifi))
        assertSame(manager, selected(wifi)?.manager)
        assertSame(controller, wifi.controllerOrigin)
        assertEquals(HolderConstructionKind.WIFI, (wifi.outcome as HolderConstructionOutcome.Unknown).kind)
        assertEquals(0, after.activeEntryCount)
    }

    @Test fun `late returned Wi-Fi remains journaled after ordinary clear without republishing`() = withFixture { f ->
        lateCompletion(f, fail = false)
    }

    @Test fun `late throwing Wi-Fi remains journaled after ordinary clear without republishing`() = withFixture { f ->
        lateCompletion(f, fail = true)
    }

    private fun lateCompletion(f: Fixture, fail: Boolean) {
        val controller = Any()
        val manager = Manager()
        val selected = CountDownLatch(1)
        val release = f.hold()
        val failure = IllegalStateException("Synthetic late constructor failure")
        val outcome = AtomicReference<Result<Wifi>>()
        val worker = f.worker {
            outcome.set(runCatching {
                f.owner.constructWifi(controller) { context ->
                    f.owner.ensureBluetoothForWifi(context) { nested ->
                        f.owner.setGossipManager(HolderManagerProvenance.Constructed(nested), manager) { it }
                        Bluetooth()
                    }
                    val registration = f.owner.selectManagerForWifi(context)
                    selected.countDown()
                    await(release, "late creator release")
                    if (fail) throw failure
                    Wifi(registration)
                }
            })
        }
        await(selected, "actual constructor selection")
        val before = f.bounded { f.owner.provenanceSnapshot() }.wifiConstructions.single()
        f.bounded { f.owner.clear() }
        assertNull(f.owner.meshService)
        assertNull(f.owner.unifiedMeshService)
        assertNull(f.owner.sharedGossipSyncManager)
        assertEquals(1, manager.clears)
        assertEquals(1, manager.stops)
        release.countDown()
        await(worker.done, "late completion")
        f.joinWorkers()
        val after = f.bounded { f.owner.provenanceSnapshot() }
        val wifi = after.wifiConstructions.single()
        assertSame(before.context, wifi.context)
        assertSame(before.entry, wifi.entry)
        assertSame(controller, wifi.controllerOrigin)
        assertSame(selected(before), selected(wifi))
        assertSame(manager, selected(wifi)?.manager)
        if (fail) {
            assertSame(failure, outcome.get().exceptionOrNull())
            assertEquals(HolderConstructionKind.WIFI, (wifi.outcome as HolderConstructionOutcome.Unknown).kind)
        } else assertSame(outcome.get().getOrThrow(), returned(wifi.outcome))
        assertNull(f.owner.meshService)
        assertNull(f.owner.unifiedMeshService)
        assertNull(f.owner.sharedGossipSyncManager)
        assertEquals(0, after.activeEntryCount)
    }

    @Test fun `selected registration keeps original signer after same manager is registered again`() = withFixture { f ->
        val manager = Manager()
        val first = Bluetooth()
        val originalSigned = packet(1)
        val nextSigned = packet(2)
        var savedDelegate: GossipSyncManager.Delegate? = null
        val result = f.owner.constructWifi(Any()) { context ->
            f.owner.ensureBluetoothForWifi(context) { nested ->
                f.owner.setGossipManager(HolderManagerProvenance.Constructed(nested), manager) { originalSigned }
                first
            }
            val registration = f.owner.selectManagerForWifi(context)
            savedDelegate = manager.delegate
            first.reusable = false
            f.owner.getOrCreate { nested ->
                f.owner.setGossipManager(HolderManagerProvenance.Constructed(nested), manager) { nextSigned }
                Bluetooth()
            }
            Wifi(registration)
        }
        val after = f.owner.provenanceSnapshot()
        assertEquals(2, after.registrations.size)
        assertNotSame(after.registrations[0], after.registrations[1])
        assertSame(result.registration, selected(after.wifiConstructions.single()))
        assertSame(after.registrations[0], result.registration)
        assertSame(originalSigned, requireNotNull(savedDelegate).signPacketForBroadcast(packet(3)))
        assertSame(nextSigned, requireNotNull(manager.delegate).signPacketForBroadcast(packet(3)))
    }

    @Test fun `foreign Wi-Fi context is rejected before any nested factory or selection effect`() = withFixture { f ->
        val other = Fixture()
        lateinit var foreign: HolderWifiConstructionContext
        other.owner.constructWifi(Any()) { context -> foreign = context; Wifi(null) }
        var factories = 0
        assertTrue(runCatching { f.owner.ensureBluetoothForWifi(foreign) { factories++; Bluetooth() } }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { f.owner.selectManagerForWifi(foreign) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, factories)
        assertTrue(f.owner.provenanceSnapshot().constructions.isEmpty())
        assertTrue(f.owner.provenanceSnapshot().registrations.isEmpty())
    }

    @Test fun `second selection cannot replace the exact first constructor observation`() = withFixture { f ->
        val result = f.owner.constructWifi(Any()) { context ->
            assertNull(f.owner.selectManagerForWifi(context))
            assertTrue(runCatching { f.owner.selectManagerForWifi(context) }.exceptionOrNull() is IllegalStateException)
            Wifi(null)
        }
        val after = f.owner.provenanceSnapshot()
        assertNull(selected(after.wifiConstructions.single()))
        assertSame(result, returned(after.wifiConstructions.single().outcome))
    }

    private class Bluetooth { var reusable = true; var stops = 0 }
    private class Unified
    private class Wifi(val registration: HolderManagerRegistration<Manager>?)
    private class Manager {
        var delegate: GossipSyncManager.Delegate? = null
        var starts = 0
        var stops = 0
        var clears = 0
    }
    private class Worker(val thread: Thread, val done: CountDownLatch)
    private class Fixture {
        private val workers = mutableListOf<Worker>()
        private val errors = ConcurrentLinkedQueue<Throwable>()
        private val holds = mutableListOf<CountDownLatch>()
        val owner = MeshServiceHolderOwner(MeshServiceHolderPorts<Bluetooth, Unified, Manager>(
            isReusable = { it.reusable },
            stopBluetooth = { it.stops++ },
            refreshUnified = {},
            startGossip = { it.starts++ },
            stopGossip = { it.stops++ },
            clearGossip = { it.clears++ },
            installGossipDelegate = { manager, delegate -> manager.delegate = delegate },
            broadcast = {}, sendToPeer = { _, _ -> }, log = { _, _ -> }
        ))
        fun hold(): CountDownLatch = CountDownLatch(1).also { holds += it }
        fun releaseAll() { holds.forEach { it.countDown() } }
        fun worker(action: () -> Unit): Worker {
            val done = CountDownLatch(1)
            val thread = Thread({
                try { action() } catch (failure: Throwable) { errors += failure } finally { done.countDown() }
            }, "synthetic-wifi-construction-worker").apply { isDaemon = true }
            return Worker(thread, done).also { workers += it; thread.start() }
        }
        fun <T : Any> bounded(action: () -> T): T {
            val result = AtomicReference<T>()
            val worker = worker { result.set(action()) }
            await(worker.done, "owned holder observation")
            check(errors.isEmpty()) { "Owned worker failed: $errors" }
            return checkNotNull(result.get())
        }
        fun joinWorkers() {
            workers.forEach {
                it.thread.join(5_000)
                if (it.thread.isAlive) { it.thread.interrupt(); it.thread.join(1_000) }
            }
            assertTrue("all owned workers must finish", workers.none { it.thread.isAlive })
            assertTrue("owned worker errors are fixture failures: $errors", errors.isEmpty())
        }
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val f = Fixture()
        var primary: Throwable? = null
        try { block(f) } catch (failure: Throwable) { primary = failure; throw failure }
        finally {
            f.releaseAll()
            try { f.joinWorkers() } catch (cleanup: Throwable) {
                val original = primary
                if (original == null) throw cleanup else if (original !== cleanup) original.addSuppressed(cleanup)
            }
        }
    }

    companion object {
        private fun await(latch: CountDownLatch, reason: String) {
            check(latch.await(5, TimeUnit.SECONDS)) { "Timed out: $reason" }
        }
        private fun returned(outcome: HolderConstructionOutcome): Any? =
            (outcome as? HolderConstructionOutcome.Returned)?.product
        private fun selected(receipt: HolderWifiConstructionReceipt<Manager>): HolderManagerRegistration<Manager>? =
            (receipt.selection as HolderWifiManagerSelection.Selected<Manager>).registration
        private fun assertBlockedBy(worker: Thread, owner: Thread) {
            val threads = ManagementFactory.getThreadMXBean()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var blocked = false
            while (!blocked && System.nanoTime() < deadline) {
                val info = threads.getThreadInfo(worker.id)
                blocked = info != null && info.threadState == Thread.State.BLOCKED && info.lockOwnerId == owner.id
                if (!blocked) Thread.yield()
            }
            assertTrue("Wi-Fi must block at the original BLE ensure, owned by the held factory", blocked)
        }
        private fun packet(value: Int) = BitchatPacket(
            type = 1u, senderID = ByteArray(8) { value.toByte() }, timestamp = 1uL,
            payload = byteArrayOf(value.toByte()), ttl = 1u
        )
    }
}
