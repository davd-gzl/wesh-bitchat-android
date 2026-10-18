package com.bitchat.android.service

import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.sync.GossipSyncManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real owner bookkeeping and installed delegates; every product and port is inert. */
class MeshServiceHolderConstructionOriginsTest {
    @Test fun `factory observes its dispatched record before registering the actual manager`() {
        val f = Fixture()
        val product = Bluetooth()
        val manager = Manager()
        lateinit var context: HolderConstructionContext
        assertSame(product, f.owner.getOrCreate { origin ->
            context = origin
            val during = f.owner.provenanceSnapshot()
            assertEquals(1, during.activeEntryCount)
            val receipt = during.constructions.single()
            assertSame(origin, receipt.context)
            assertSame(HolderConstructionOutcome.Dispatched, receipt.outcome)
            f.owner.setGossipManager(HolderManagerProvenance.Constructed(origin), manager) { it }
            product
        })
        val after = f.owner.provenanceSnapshot()
        assertEquals(0, after.activeEntryCount)
        val receipt = after.constructions.single()
        assertSame(product, returned(receipt))
        val registration = after.registrations.single()
        assertSame(manager, registration.manager)
        assertSame(context, (registration.provenance as HolderManagerProvenance.Constructed).context)
        assertSame(receipt.entry, registration.entry)
        assertSame(manager, f.owner.sharedGossipSyncManager)
    }

    @Test fun `unified factory is journaled with the actual supplied Bluetooth and shared outer entry`() {
        val f = Fixture()
        val bluetooth = Bluetooth()
        val facade = Unified(bluetooth)
        assertSame(facade, f.owner.getUnifiedOrCreate({ bluetooth }) { supplied ->
            assertSame(bluetooth, supplied)
            val during = f.owner.provenanceSnapshot()
            assertEquals(1, during.activeEntryCount)
            assertEquals(2, during.constructions.size)
            val ble = during.constructions[0]
            val unified = during.constructions[1]
            assertSame(bluetooth, returned(ble))
            assertEquals(HolderConstructionKind.UNIFIED, unified.kind)
            assertSame(bluetooth, unified.suppliedBluetooth)
            assertSame(ble.entry, unified.entry)
            assertNotSame(ble.context, unified.context)
            assertSame(HolderConstructionOutcome.Dispatched, unified.outcome)
            facade
        })
        val after = f.owner.provenanceSnapshot()
        assertSame(facade, returned(after.constructions[1]))
        assertEquals(0, after.activeEntryCount)
    }

    @Test fun `unified constructor throw retains its supplied Bluetooth and typed unknown record`() {
        val f = Fixture()
        val bluetooth = Bluetooth()
        val failure = IllegalStateException("Synthetic Unified constructor failure")
        assertSame(failure, runCatching {
            f.owner.getUnifiedOrCreate({ bluetooth }) { throw failure }
        }.exceptionOrNull())
        val after = f.owner.provenanceSnapshot()
        assertEquals(2, after.constructions.size)
        assertSame(bluetooth, returned(after.constructions[0]))
        val failed = after.constructions[1]
        assertSame(bluetooth, failed.suppliedBluetooth)
        assertEquals(HolderConstructionKind.UNIFIED, (failed.outcome as HolderConstructionOutcome.Unknown).kind)
        assertSame(after.constructions[0].entry, failed.entry)
        assertSame(bluetooth, f.owner.meshService)
        assertNull(f.owner.unifiedMeshService)
        assertEquals(0, after.activeEntryCount)
    }

    @Test fun `unified post-return log throw retains the already cached known facade`() {
        val f = Fixture()
        val bluetooth = Bluetooth()
        val facade = Unified(bluetooth)
        val failure = IllegalStateException("Synthetic Unified log failure")
        f.onLog = { if (it == "Created new UnifiedMeshService") throw failure }
        assertSame(failure, runCatching {
            f.owner.getUnifiedOrCreate({ bluetooth }) { facade }
        }.exceptionOrNull())
        val after = f.owner.provenanceSnapshot()
        assertSame(facade, returned(after.constructions[1]))
        assertSame(facade, f.owner.unifiedMeshService)
        assertSame(facade, f.owner.getUnifiedOrCreate({ error("Must reuse Bluetooth") }) { error("Must reuse facade") })
        assertEquals(2, f.owner.provenanceSnapshot().constructions.size)
        assertEquals(0, after.activeEntryCount)
    }

    @Test fun `throw after manager registration retains first unknown attempt when retry succeeds`() {
        val f = Fixture()
        val original = Bluetooth().also { it.reusable = false }
        f.owner.getOrCreate { original }
        val partial = Manager()
        val next = Manager()
        val replacement = Bluetooth()
        val failure = IllegalStateException("Synthetic partial constructor failure")
        var calls = 0
        assertSame(replacement, f.owner.getOrCreate { context ->
            calls++
            if (calls == 1) {
                f.owner.setGossipManager(HolderManagerProvenance.Constructed(context), partial) { it }
                throw failure
            }
            f.owner.setGossipManager(HolderManagerProvenance.Constructed(context), next) { it }
            replacement
        })
        assertEquals(2, calls)
        assertEquals(1, original.stops)
        val beforeClear = f.owner.provenanceSnapshot()
        assertEquals(3, beforeClear.constructions.size)
        val first = beforeClear.constructions[1]
        val second = beforeClear.constructions[2]
        assertEquals(HolderConstructionKind.BLUETOOTH, (first.outcome as HolderConstructionOutcome.Unknown).kind)
        assertSame(replacement, returned(second))
        assertNotSame(first.context, second.context)
        assertSame(first.entry, second.entry)
        assertNotSame(beforeClear.constructions[0].entry, first.entry)
        assertSame(beforeClear.constructions[0].entry.epoch, first.entry.epoch)
        assertSame(first.entry, beforeClear.registrations[0].entry)
        assertSame(first.context, (beforeClear.registrations[0].provenance as HolderManagerProvenance.Constructed).context)
        assertSame(second.context, (beforeClear.registrations[1].provenance as HolderManagerProvenance.Constructed).context)
        f.owner.clear()
        val afterClear = f.owner.provenanceSnapshot()
        assertNull(f.owner.meshService)
        assertNull(f.owner.sharedGossipSyncManager)
        assertEquals(0, afterClear.activeEntryCount)
        assertSame(first.outcome, afterClear.constructions[1].outcome)
        assertSame(beforeClear.registrations[0], afterClear.registrations[0])
        assertSame(beforeClear.registrations[1], afterClear.registrations[1])
    }

    @Test fun `replacement log throw retries without relabeling the already returned first product`() {
        val f = Fixture()
        val original = Bluetooth().also { it.reusable = false }
        f.owner.getOrCreate { original }
        val first = Bluetooth()
        val second = Bluetooth()
        val firstManager = Manager()
        val secondManager = Manager()
        var calls = 0
        var sawReturnedAtLog = false
        f.onLog = { message ->
            if (message == "Created new BluetoothMeshService (replacement)") {
                sawReturnedAtLog = true
                assertSame(first, returned(f.owner.provenanceSnapshot().constructions.last()))
                throw IllegalStateException("Synthetic post-return log failure")
            }
        }
        assertSame(second, f.owner.getOrCreate { context ->
            calls++
            val manager = if (calls == 1) firstManager else secondManager
            f.owner.setGossipManager(HolderManagerProvenance.Constructed(context), manager) { it }
            if (calls == 1) first else second
        })
        assertTrue(sawReturnedAtLog)
        assertEquals(2, calls)
        val after = f.owner.provenanceSnapshot()
        assertEquals(3, after.constructions.size)
        assertSame(first, returned(after.constructions[1]))
        assertSame(second, returned(after.constructions[2]))
        assertNotSame(after.constructions[1].context, after.constructions[2].context)
        assertEquals(2, after.registrations.size)
        assertSame(second, f.owner.meshService)
        assertEquals(0, after.activeEntryCount)
    }

    @Test fun `initial post-return log failure retains known product without inventing constructor uncertainty`() {
        val f = Fixture()
        val product = Bluetooth()
        val failure = IllegalStateException("Synthetic initial log failure")
        var calls = 0
        f.onLog = { if (it == "Created new BluetoothMeshService (no existing instance)") throw failure }
        assertSame(failure, runCatching { f.owner.getOrCreate { calls++; product } }.exceptionOrNull())
        val after = f.owner.provenanceSnapshot()
        assertEquals(1, calls)
        assertSame(product, returned(after.constructions.single()))
        assertNull(f.owner.meshService)
        assertEquals(0, after.activeEntryCount)
    }

    @Test fun `two failed replacement invocations remain separate unknown records`() {
        val f = Fixture()
        val original = Bluetooth().also { it.reusable = false }
        f.owner.getOrCreate { original }
        val failure = IllegalStateException("Synthetic constructor failure")
        var calls = 0
        assertSame(failure, runCatching { f.owner.getOrCreate { calls++; throw failure } }.exceptionOrNull())
        val after = f.owner.provenanceSnapshot()
        assertEquals(2, calls)
        assertEquals(3, after.constructions.size)
        assertTrue(after.constructions[1].outcome is HolderConstructionOutcome.Unknown)
        assertTrue(after.constructions[2].outcome is HolderConstructionOutcome.Unknown)
        assertNotSame(after.constructions[1].context, after.constructions[2].context)
        assertSame(original, f.owner.meshService)
        assertEquals(0, after.activeEntryCount)
    }

    @Test fun `saved delegate retains its original immutable registration and signer after replacement and clear`() {
        val f = Fixture()
        val manager = Manager()
        val first = Bluetooth().also { it.reusable = false }
        val second = Bluetooth()
        val signedFirst = packet(2)
        val signedSecond = packet(3)
        f.owner.getOrCreate { context ->
            f.owner.setGossipManager(HolderManagerProvenance.Constructed(context), manager) { signedFirst }
            first
        }
        val saved = requireNotNull(manager.delegate)
        val firstRegistration = f.owner.provenanceSnapshot().registrations.single()
        f.owner.getOrCreate { context ->
            f.owner.setGossipManager(HolderManagerProvenance.Constructed(context), manager) { signedSecond }
            second
        }
        val after = f.owner.provenanceSnapshot()
        assertNotSame(firstRegistration, after.registrations[1])
        assertNotSame((firstRegistration.provenance as HolderManagerProvenance.Constructed).context,
            (after.registrations[1].provenance as HolderManagerProvenance.Constructed).context)
        assertSame(signedFirst, saved.signPacketForBroadcast(packet(1)))
        assertSame(signedSecond, requireNotNull(manager.delegate).signPacketForBroadcast(packet(1)))
        assertEquals(0, manager.stops)
        f.owner.clear()
        assertSame(signedFirst, saved.signPacketForBroadcast(packet(1)))
        saved.sendPacket(signedFirst)
        saved.sendPacketToPeer("Synthetic peer", signedSecond)
        assertSame(signedFirst, f.broadcasts.single())
        assertSame(signedSecond, f.peerSends.single().second)
        assertSame(firstRegistration, f.owner.provenanceSnapshot().registrations[0])
    }

    @Test fun `delegate installation throw retains its assigned registration and unknown constructor`() {
        val f = Fixture()
        val manager = Manager()
        val failure = IllegalStateException("Synthetic install failure")
        f.onInstall = { throw failure }
        assertSame(failure, runCatching { f.owner.getOrCreate { context ->
            f.owner.setGossipManager(HolderManagerProvenance.Constructed(context), manager) { it }
            Bluetooth()
        } }.exceptionOrNull())
        val after = f.owner.provenanceSnapshot()
        assertSame(manager, f.owner.sharedGossipSyncManager)
        assertSame(manager, after.registrations.single().manager)
        assertTrue(after.constructions.single().outcome is HolderConstructionOutcome.Unknown)
        assertNull(f.owner.meshService)
        assertEquals(0, after.activeEntryCount)
    }

    @Test fun `explicit external registrations never borrow the active constructor epoch`() {
        val f = Fixture()
        val manager = Manager()
        f.owner.getOrCreate {
            f.owner.setGossipManager(HolderManagerProvenance.UnattributedExternal, manager) { it }
            Bluetooth()
        }
        val first = f.owner.provenanceSnapshot().registrations.single()
        assertSame(HolderManagerProvenance.UnattributedExternal, first.provenance)
        assertNull(first.entry)
        f.owner.setGossipManager(HolderManagerProvenance.UnattributedExternal, manager) { it }
        val after = f.owner.provenanceSnapshot()
        assertEquals(1, after.constructions.size)
        assertNotSame(first, after.registrations[1])
        assertNull(after.registrations[1].entry)
        f.owner.clear()
        assertEquals(2, f.owner.provenanceSnapshot().registrations.size)
    }

    @Test fun `foreign owner context is rejected before any manager side effect`() {
        val originOwner = Fixture()
        lateinit var foreign: HolderConstructionContext
        originOwner.owner.getOrCreate { foreign = it; Bluetooth() }
        val target = Fixture()
        val existing = Manager()
        target.owner.setGossipManager(HolderManagerProvenance.UnattributedExternal, existing) { it }
        target.owner.startSharedGossip("Synthetic owner")
        val before = target.owner.provenanceSnapshot()
        val delegate = existing.delegate
        val replacement = Manager()
        val failure = runCatching {
            target.owner.setGossipManager(HolderManagerProvenance.Constructed(foreign), replacement) { it }
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertSame(existing, target.owner.sharedGossipSyncManager)
        assertSame(delegate, existing.delegate)
        assertEquals(0, existing.stops)
        assertEquals(0, replacement.starts)
        assertNull(replacement.delegate)
        val after = target.owner.provenanceSnapshot()
        assertEquals(before.registrations.size, after.registrations.size)
        assertTrue(after.constructions.isEmpty())
    }

    @Test fun `cached acquisitions add no retained construction history or active entries`() {
        val f = Fixture()
        val product = Bluetooth()
        f.owner.getOrCreate { product }
        val first = f.owner.provenanceSnapshot().constructions.single()
        repeat(100) {
            assertSame(product, f.owner.getOrCreate { error("Cached reuse cannot dispatch a factory") })
            assertEquals(0, f.owner.provenanceSnapshot().activeEntryCount)
        }
        val after = f.owner.provenanceSnapshot()
        assertEquals(1, after.constructions.size)
        assertSame(first.context, after.constructions.single().context)
        assertSame(first.entry, after.constructions.single().entry)
        assertTrue(after.registrations.isEmpty())
    }

    @Test fun `attached external products remain unconfirmed without a fictitious constructor`() {
        val f = Fixture()
        val product = Bluetooth()
        f.owner.attach(product)
        f.owner.attach(product)
        val after = f.owner.provenanceSnapshot()
        assertTrue(after.constructions.isEmpty())
        assertSame(product, after.externalProducts.single())
        f.owner.clear()
        assertSame(product, f.owner.provenanceSnapshot().externalProducts.single())
        assertNull(f.owner.meshService)
    }

    private class Bluetooth { var reusable = true; var stops = 0 }
    private class Unified(val bluetooth: Bluetooth)
    private class Manager {
        var delegate: GossipSyncManager.Delegate? = null
        var starts = 0
        var stops = 0
    }
    private class Fixture {
        val broadcasts = mutableListOf<BitchatPacket>()
        val peerSends = mutableListOf<Pair<String, BitchatPacket>>()
        var onLog: (String) -> Unit = {}
        var onInstall: () -> Unit = {}
        val owner = MeshServiceHolderOwner(MeshServiceHolderPorts<Bluetooth, Unified, Manager>(
            isReusable = { it.reusable },
            stopBluetooth = { it.stops++ },
            refreshUnified = {},
            startGossip = { it.starts++ },
            stopGossip = { it.stops++ },
            clearGossip = {},
            installGossipDelegate = { manager, delegate -> onInstall(); manager.delegate = delegate },
            broadcast = { broadcasts += it },
            sendToPeer = { peer, packet -> peerSends += peer to packet },
            log = { _, message -> onLog(message) }
        ))
    }

    private fun returned(receipt: HolderConstructionReceipt): Any =
        (receipt.outcome as HolderConstructionOutcome.Returned).product

    private fun packet(value: Int) = BitchatPacket(
        type = 1u, senderID = ByteArray(8) { value.toByte() }, timestamp = 1uL,
        payload = byteArrayOf(value.toByte()), ttl = 1u
    )
}
