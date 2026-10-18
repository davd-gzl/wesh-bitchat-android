package com.bitchat.android.service

import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.sync.GossipSyncManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Actual holder owner and installed callbacks, with synthetic objects and no platform runtime. */
class MeshServiceHolderOwnerTest {
    @Test fun `construction is inert and all identity caches start empty`() = withFixture { f ->
        assertNull(f.owner.meshService)
        assertNull(f.owner.unifiedMeshService)
        assertNull(f.owner.sharedGossipSyncManager)
        assertTrue(f.events.isEmpty())
    }

    @Test fun `healthy reuse ignores a later call scoped factory`() = withFixture { f ->
        val first = Bluetooth()
        assertSame(first, f.owner.getOrCreate { first })
        assertSame(first, f.owner.getOrCreate { error("A healthy product must be reused") })
        assertSame(first, f.owner.meshService)
        assertEquals(listOf("probe"), f.actions())
    }

    @Test fun `nonreusable replacement stops old product and invalidates its facade`() = withFixture { f ->
        val old = Bluetooth().also { it.reusable = false }
        val facade = Unified(old)
        f.owner.getUnifiedOrCreate({ old }, { facade })
        val fresh = Bluetooth()
        assertSame(fresh, f.owner.getOrCreate { fresh })
        assertEquals(1, old.stops)
        assertSame(fresh, f.owner.meshService)
        assertNull(f.owner.unifiedMeshService)
        val replacement = f.owner.getUnifiedOrCreate({ error("Must reuse fresh Bluetooth") }, { Unified(it) })
        assertSame(fresh, replacement.bluetooth)
        assertNotSame(facade, replacement)
    }

    @Test fun `probe failure creates replacement without stopping original`() = withFixture { f ->
        val old = Bluetooth()
        f.owner.getOrCreate { old }
        val failure = IllegalStateException("Synthetic probe failure")
        f.onProbe = { throw failure }
        val fresh = Bluetooth()
        assertSame(fresh, f.owner.getOrCreate { fresh })
        assertEquals(0, old.stops)
        assertSame(fresh, f.owner.meshService)
    }

    @Test fun `replacement creation failure preserves the original second factory attempt`() = withFixture { f ->
        val old = Bluetooth().also { it.reusable = false }
        f.owner.getOrCreate { old }
        val suppliedDependency = Any()
        val observations = mutableListOf<Any>()
        val fresh = Bluetooth()
        val calls = AtomicInteger()
        assertSame(fresh, f.owner.getOrCreate {
            observations += suppliedDependency
            if (calls.incrementAndGet() == 1) error("Synthetic first replacement failure")
            fresh
        })
        assertEquals(2, calls.get())
        assertEquals(2, observations.size)
        observations.forEach { assertSame(suppliedDependency, it) }
        assertEquals(1, old.stops)
        assertSame(fresh, f.owner.meshService)
    }

    @Test fun `initial creation failure is propagated without retry or cache mutation`() = withFixture { f ->
        val failure = IllegalStateException("Synthetic initial factory failure")
        val calls = AtomicInteger()
        assertSame(failure, runCatching { f.owner.getOrCreate { calls.incrementAndGet(); throw failure } }.exceptionOrNull())
        assertEquals(1, calls.get())
        assertNull(f.owner.meshService)
        assertNull(f.owner.unifiedMeshService)
    }

    @Test fun `failed fallback leaves both original cache identities intact`() = withFixture { f ->
        val old = Bluetooth().also { it.reusable = false }
        val originalFacade = f.owner.getUnifiedOrCreate({ old }, { Unified(it) })
        val failure = IllegalStateException("Synthetic replacement failure")
        val calls = AtomicInteger()
        assertSame(failure, runCatching { f.owner.getOrCreate { calls.incrementAndGet(); throw failure } }.exceptionOrNull())
        assertEquals(2, calls.get())
        assertSame(old, f.owner.meshService)
        assertSame(originalFacade, f.owner.unifiedMeshService)
    }

    @Test fun `best effort stop failure does not prevent ordinary replacement`() = withFixture { f ->
        val old = Bluetooth().also { it.reusable = false }
        f.owner.getOrCreate { old }
        f.onBluetoothStop = { error("Synthetic stop failure") }
        val fresh = Bluetooth()
        assertSame(fresh, f.owner.getOrCreate { fresh })
        assertEquals(1, old.stops)
        assertSame(fresh, f.owner.meshService)
    }

    @Test fun `unified reuse refreshes the same facade without constructing another`() = withFixture { f ->
        val bluetooth = Bluetooth()
        val facade = f.owner.getUnifiedOrCreate({ bluetooth }, { Unified(it) })
        assertSame(facade, f.owner.getUnifiedOrCreate({ error("Must reuse Bluetooth") }, { error("Must reuse facade") }))
        assertSame(bluetooth, facade.bluetooth)
        assertEquals(1, facade.refreshes)
        assertSame(facade, f.owner.unifiedMeshService)
    }

    @Test fun `unified factory failure retains Bluetooth for the next call`() = withFixture { f ->
        val bluetooth = Bluetooth()
        val failure = IllegalStateException("Synthetic facade factory failure")
        assertSame(failure, runCatching { f.owner.getUnifiedOrCreate({ bluetooth }, { throw failure }) }.exceptionOrNull())
        assertSame(bluetooth, f.owner.meshService)
        assertNull(f.owner.unifiedMeshService)
        val facade = f.owner.getUnifiedOrCreate({ error("Must reuse supplied Bluetooth") }, { Unified(it) })
        assertSame(bluetooth, facade.bluetooth)
    }

    @Test fun `unified refresh failure propagates without replacing cached products`() = withFixture { f ->
        val bluetooth = Bluetooth()
        val facade = f.owner.getUnifiedOrCreate({ bluetooth }, { Unified(it) })
        val failure = IllegalStateException("Synthetic refresh failure")
        f.onRefresh = { throw failure }
        assertSame(failure, runCatching { f.owner.getUnifiedOrCreate({ error("Unexpected factory") }, { error("Unexpected facade") }) }.exceptionOrNull())
        assertSame(bluetooth, f.owner.meshService)
        assertSame(facade, f.owner.unifiedMeshService)
    }

    @Test fun `attach replaces Bluetooth and invalidates only the facade cache`() = withFixture { f ->
        val old = Bluetooth()
        f.owner.getUnifiedOrCreate({ old }, { Unified(it) })
        val manager = Manager()
        f.owner.setGossipManager(HolderManagerProvenance.UnattributedExternal, manager) { it }
        val attached = Bluetooth()
        f.owner.attach(attached)
        assertSame(attached, f.owner.meshService)
        assertNull(f.owner.unifiedMeshService)
        assertSame(manager, f.owner.sharedGossipSyncManager)
        assertEquals(0, old.stops)
    }

    @Test fun `overlapping gossip owners start once and release independently`() = withFixture { f ->
        f.owner.startSharedGossip("Synthetic BLE")
        f.owner.startSharedGossip("Synthetic BLE")
        val manager = Manager()
        f.owner.setGossipManager(HolderManagerProvenance.UnattributedExternal, manager) { it }
        assertEquals(1, manager.starts)
        f.owner.startSharedGossip("Synthetic WIFI")
        f.owner.stopSharedGossip("Synthetic BLE")
        assertEquals(0, manager.stops)
        f.owner.stopSharedGossip("Synthetic WIFI")
        assertEquals(1, manager.stops)
        // Repeated idle stop requests retain the existing best-effort semantics.
        f.owner.stopSharedGossip("Synthetic absent")
        assertEquals(2, manager.stops)
        f.owner.startSharedGossip("Synthetic WIFI")
        assertEquals(2, manager.starts)
    }

    @Test fun `manager replacement stops previous identity and installs updated signer`() = withFixture { f ->
        val first = Manager()
        val second = Manager()
        f.owner.setGossipManager(HolderManagerProvenance.UnattributedExternal, first) { it }
        f.owner.startSharedGossip("Synthetic owner")
        val signed = packet(2)
        f.owner.setGossipManager(HolderManagerProvenance.UnattributedExternal, second) { signed }
        assertEquals(1, first.stops)
        assertEquals(1, second.starts)
        assertSame(second, f.owner.sharedGossipSyncManager)
        assertSame(signed, requireNotNull(second.delegate).signPacketForBroadcast(packet(1)))
        val updated = packet(3)
        f.owner.setGossipManager(HolderManagerProvenance.UnattributedExternal, second) { updated }
        assertEquals(0, second.stops)
        assertEquals(2, second.starts)
        assertSame(updated, requireNotNull(second.delegate).signPacketForBroadcast(packet(1)))
    }

    @Test fun `previous manager stop failure still installs and starts replacement`() = withFixture { f ->
        val first = Manager()
        val replacement = Manager()
        f.owner.setGossipManager(HolderManagerProvenance.UnattributedExternal, first) { it }
        f.owner.startSharedGossip("Synthetic owner")
        f.onManagerStop = { if (it === first) error("Synthetic previous manager stop failure") }
        f.owner.setGossipManager(HolderManagerProvenance.UnattributedExternal, replacement) { it }
        assertEquals(1, first.stops)
        assertSame(replacement, f.owner.sharedGossipSyncManager)
        assertTrue(replacement.delegate != null)
        assertEquals(1, replacement.starts)
    }

    @Test fun `constructor registration remains visible when initial factory throws`() = withFixture { f ->
        val partialManager = Manager()
        val failure = IllegalStateException("Synthetic partial construction failure")
        assertSame(failure, runCatching { f.owner.getOrCreate { construction ->
            f.owner.setGossipManager(HolderManagerProvenance.Constructed(construction), partialManager) { it }
            throw failure
        } }.exceptionOrNull())
        assertNull(f.owner.meshService)
        assertNull(f.owner.unifiedMeshService)
        assertSame(partialManager, f.owner.sharedGossipSyncManager)
        assertTrue(partialManager.delegate != null)
        assertEquals(0, partialManager.stops)
        assertEquals(0, partialManager.clears)
    }

    @Test fun `clear attempts manager clear and stop independently before forgetting caches`() {
        for (failClear in listOf(false, true)) for (failStop in listOf(false, true)) withFixture { f ->
            val bluetooth = Bluetooth()
            f.owner.getUnifiedOrCreate({ bluetooth }, { Unified(it) })
            val manager = Manager()
            f.owner.setGossipManager(HolderManagerProvenance.UnattributedExternal, manager) { it }
            f.owner.startSharedGossip("Synthetic owner")
            f.onManagerClear = { if (failClear) error("Synthetic clear failure") }
            f.onManagerStop = { if (failStop) error("Synthetic stop failure") }
            f.owner.clear()
            assertEquals(1, manager.clears)
            assertEquals(1, manager.stops)
            assertNull(f.owner.meshService)
            assertNull(f.owner.unifiedMeshService)
            assertNull(f.owner.sharedGossipSyncManager)
            assertEquals(0, bluetooth.stops)
            val fresh = Manager()
            f.owner.setGossipManager(HolderManagerProvenance.UnattributedExternal, fresh) { it }
            assertEquals("clear also forgets active gossip owners", 0, fresh.starts)
        }
    }

    @Test fun `clear reentry preserves the later lookup of the current manager for stop`() = withFixture { f ->
        val first = Manager()
        val replacement = Manager()
        f.owner.setGossipManager(HolderManagerProvenance.UnattributedExternal, first) { it }
        f.onManagerClear = { f.owner.setGossipManager(HolderManagerProvenance.UnattributedExternal, replacement) { it } }
        f.owner.clear()
        assertEquals(1, first.clears)
        assertEquals("replacement stops the old manager", 1, first.stops)
        assertEquals("clear then stops the manager installed during clear", 1, replacement.stops)
        assertNull(f.owner.sharedGossipSyncManager)
    }

    @Test fun `installed production delegate preserves signer and transport argument identity`() = withFixture { f ->
        val manager = Manager()
        val original = packet(1)
        val signed = packet(2)
        var signingInput: BitchatPacket? = null
        f.owner.setGossipManager(HolderManagerProvenance.UnattributedExternal, manager) { signingInput = it; signed }
        val delegate = requireNotNull(manager.delegate)
        assertSame(signed, delegate.signPacketForBroadcast(original))
        assertSame(original, signingInput)
        delegate.sendPacket(signed)
        delegate.sendPacketToPeer("Synthetic peer", original)
        assertSame(signed, f.broadcasts.single())
        assertEquals("Synthetic peer", f.peerSends.single().first)
        assertSame(original, f.peerSends.single().second)
        val failure = IllegalStateException("Synthetic signer failure")
        f.owner.setGossipManager(HolderManagerProvenance.UnattributedExternal, manager) { throw failure }
        assertSame(failure, runCatching { requireNotNull(manager.delegate).signPacketForBroadcast(original) }.exceptionOrNull())
        assertEquals(1, f.broadcasts.size)
    }

    @Test fun `delegate installation failure preserves manager assignment without starting it`() = withFixture { f ->
        f.owner.startSharedGossip("Synthetic owner")
        val manager = Manager()
        val failure = IllegalStateException("Synthetic delegate installation failure")
        f.onInstall = { throw failure }
        assertSame(failure, runCatching { f.owner.setGossipManager(HolderManagerProvenance.UnattributedExternal, manager) { it } }.exceptionOrNull())
        assertSame(manager, f.owner.sharedGossipSyncManager)
        assertEquals(0, manager.starts)
    }

    @Test fun `held factory serializes a second caller and supports nested manager registration`() = withFixture { f ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val secondFinished = CountDownLatch(1)
        val calls = AtomicInteger()
        val manager = Manager()
        val product = Bluetooth()
        val results = ConcurrentLinkedQueue<Bluetooth>()
        val first = f.worker {
            results += f.owner.getOrCreate { construction ->
                calls.incrementAndGet()
                f.owner.setGossipManager(HolderManagerProvenance.Constructed(construction), manager) { it }
                f.owner.startSharedGossip("Synthetic nested owner")
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "Held synthetic factory was not released" }
                product
            }
        }
        try {
            assertTrue("first factory entered after reentrant registration", entered.await(5, TimeUnit.SECONDS))
            assertSame(manager, f.owner.sharedGossipSyncManager)
            assertEquals(1, manager.starts)
            val second = f.worker {
                secondEntered.countDown()
                try { results += f.owner.getOrCreate { calls.incrementAndGet(); Bluetooth() } }
                finally { secondFinished.countDown() }
            }
            assertTrue("second owned caller started", secondEntered.await(5, TimeUnit.SECONDS))
            val threads = ManagementFactory.getThreadMXBean()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var blockedByFactory = false
            while (!blockedByFactory && System.nanoTime() < deadline) {
                val info = threads.getThreadInfo(second.id)
                blockedByFactory = info != null && info.threadState == Thread.State.BLOCKED && info.lockOwnerId == first.id
                if (!blockedByFactory) Thread.yield()
            }
            assertTrue("the second owned caller must block on the first factory's monitor", blockedByFactory)
            assertEquals("the blocked caller cannot have finished", 1L, secondFinished.count)
            assertEquals(1, calls.get())
            release.countDown()
            assertTrue("second caller completed after factory release", secondFinished.await(5, TimeUnit.SECONDS))
            f.joinWorkers()
            assertEquals(1, calls.get())
            assertEquals(2, results.size)
            results.forEach { assertSame(product, it) }
        } finally { release.countDown() }
    }

    private class Bluetooth {
        var reusable = true
        var stops = 0
    }
    private class Unified(val bluetooth: Bluetooth) { var refreshes = 0 }
    private class Manager {
        @Volatile var delegate: GossipSyncManager.Delegate? = null
        var starts = 0
        var stops = 0
        var clears = 0
    }

    private class Fixture {
        val events = CopyOnWriteArrayList<String>()
        val broadcasts = mutableListOf<BitchatPacket>()
        val peerSends = mutableListOf<Pair<String, BitchatPacket>>()
        private val workers = mutableListOf<Thread>()
        private val errors = ConcurrentLinkedQueue<Throwable>()
        var onProbe: (Bluetooth) -> Boolean = { it.reusable }
        var onBluetoothStop: (Bluetooth) -> Unit = {}
        var onRefresh: (Unified) -> Unit = {}
        var onManagerStop: (Manager) -> Unit = {}
        var onManagerClear: (Manager) -> Unit = {}
        var onInstall: (Manager) -> Unit = {}
        val owner = MeshServiceHolderOwner(MeshServiceHolderPorts<Bluetooth, Unified, Manager>(
            isReusable = { events += "probe"; onProbe(it) },
            stopBluetooth = { it.stops++; events += "stop-bluetooth"; onBluetoothStop(it) },
            refreshUnified = { it.refreshes++; events += "refresh"; onRefresh(it) },
            startGossip = { it.starts++; events += "start-gossip" },
            stopGossip = { it.stops++; events += "stop-gossip"; onManagerStop(it) },
            clearGossip = { it.clears++; events += "clear-gossip"; onManagerClear(it) },
            installGossipDelegate = { manager, delegate -> onInstall(manager); manager.delegate = delegate; events += "delegate" },
            broadcast = { broadcasts += it },
            sendToPeer = { peer, packet -> peerSends += peer to packet },
            log = { _, _ -> }
        ))
        fun actions() = events.toList()
        fun worker(action: () -> Unit): Thread {
            val worker = Thread({ try { action() } catch (failure: Throwable) { errors += failure } }, "synthetic-holder-worker")
                .apply { isDaemon = true }
            workers += worker
            worker.start()
            return worker
        }
        fun joinWorkers() {
            workers.forEach {
                it.join(5_000)
                if (it.isAlive) { it.interrupt(); it.join(1_000) }
            }
            assertTrue("all owned workers must finish", workers.none { it.isAlive })
            assertTrue("owned worker errors are fixture failures: $errors", errors.isEmpty())
        }
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val fixture = Fixture()
        var primaryFailure: Throwable? = null
        try { block(fixture) } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try { fixture.joinWorkers() } catch (cleanupFailure: Throwable) {
                val original = primaryFailure
                if (original == null) throw cleanupFailure else if (original !== cleanupFailure) original.addSuppressed(cleanupFailure)
            }
        }
    }

    private fun packet(value: Int) = BitchatPacket(
        type = 1u, senderID = ByteArray(8) { value.toByte() }, timestamp = 1uL,
        payload = byteArrayOf(value.toByte()), ttl = 1u
    )
}
