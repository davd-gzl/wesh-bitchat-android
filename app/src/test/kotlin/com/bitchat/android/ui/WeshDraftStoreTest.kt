package com.bitchat.android.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WeshDraftStoreTest {
    private val owner = "11".repeat(32)
    private val secondOwner = "22".repeat(32)
    private val privateA = WeshDraftStore.privateDestination("33".repeat(32))!!
    private val privateB = WeshDraftStore.privateDestination("44".repeat(32))!!

    @Test fun `public and full Noise destinations survive isolated navigation`() {
        val store = WeshDraftStore()
        store.save(store.epoch, "public", "public synthetic")
        store.save(store.epoch, privateA, "private A")
        store.save(store.epoch, privateB, "private B")
        val sent = store.capture(privateA)!!
        store.clearIfUnchanged(sent)
        assertEquals("public synthetic", store.drafts.value["public"])
        assertNull(store.drafts.value[privateA])
        assertEquals("private B", store.drafts.value[privateB])
        assertFalse(store.save(store.epoch, "contact_synthetic-short", "not an identity"))
    }

    @Test fun `late callback preserves newer text including ABA edits`() {
        val store = WeshDraftStore()
        store.save(store.epoch, "public", "old synthetic")
        val sent = store.capture("public")!!
        store.save(store.epoch, "public", "new synthetic")
        store.save(store.epoch, "public", "old synthetic")
        store.clearIfUnchanged(sent)
        assertEquals("old synthetic", store.drafts.value["public"])
    }

    @Test fun `editing another destination does not prevent clearing accepted draft`() {
        val store = WeshDraftStore()
        store.save(store.epoch, "public", "synthetic")
        val sent = store.capture("public")!!
        store.save(store.epoch, privateA, "new A")
        store.clearIfUnchanged(sent)
        assertNull(store.drafts.value["public"])
        assertEquals("new A", store.drafts.value[privateA])
    }

    @Test fun `UTF8 individual count and aggregate bounds reject or evict without truncation`() {
        val store = WeshDraftStore()
        store.save(store.epoch, "public", "kept")
        assertFalse(store.save(store.epoch, "public", "é".repeat(4097)))
        assertEquals("kept", store.drafts.value["public"])
        repeat(70) { store.save(store.epoch, "private:" + it.toString(16).padStart(64, '0'), "x") }
        assertEquals(64, store.drafts.value.size)
        repeat(64) { store.save(store.epoch, "private:" + it.toString(16).padStart(64, '0'), "x".repeat(8192)) }
        assertEquals(16, store.drafts.value.size)
        assertEquals(131072, store.drafts.value.values.sumOf { it.toByteArray().size })
    }

    @Test fun `edits before confirmed restore merge after disk and never overwrite unread data`() = runTest {
        val backend = FakeBackend().apply { stored[owner] = mapOf(privateA to "stored A", "public" to "stored public") }
        val store = WeshDraftStore(backend, backgroundScope, StandardTestDispatcher(testScheduler))
        store.save(store.epoch, "public", "new local")
        runCurrent()
        assertEquals(0, backend.writes)
        store.load(owner)
        runCurrent()
        assertEquals(mapOf(privateA to "stored A", "public" to "new local"), backend.stored[owner])
        assertEquals(WeshDraftStore.Persistence.Confirmed, store.persistence.value)
    }

    @Test fun `malformed or unreadable restore retains RAM and requires successful read before write`() = runTest {
        val backend = FakeBackend().apply { failRead = true }
        val store = WeshDraftStore(backend, backgroundScope, StandardTestDispatcher(testScheduler))
        store.load(owner)
        store.save(store.epoch, "public", "kept in RAM")
        runCurrent()
        assertEquals(WeshDraftStore.Persistence.Error, store.persistence.value)
        assertEquals(0, backend.writes)
        store.retry()
        runCurrent()
        assertEquals(0, backend.writes)
        backend.failRead = false
        backend.stored[owner] = mapOf(privateA to "stored A")
        store.retry()
        runCurrent()
        assertEquals("kept in RAM", backend.stored[owner]?.get("public"))
        assertEquals("stored A", store.drafts.value[privateA])
    }

    @Test fun `failed commit even after memory publication stays dirty until explicit rewrite`() = runTest {
        val backend = FakeBackend()
        val store = WeshDraftStore(backend, backgroundScope, StandardTestDispatcher(testScheduler))
        store.load(owner)
        backend.failWrite = true
        store.save(store.epoch, "public", "synthetic pending")
        runCurrent()
        assertEquals(WeshDraftStore.Persistence.Error, store.persistence.value)
        assertEquals("synthetic pending", store.drafts.value["public"])
        backend.failWrite = false
        store.retry()
        runCurrent()
        assertEquals(2, backend.writes)
        assertEquals(WeshDraftStore.Persistence.Confirmed, store.persistence.value)
    }

    @Test fun `readback mismatch retains RAM and retry persists again`() = runTest {
        val backend = FakeBackend()
        val store = WeshDraftStore(backend, backgroundScope, StandardTestDispatcher(testScheduler))
        store.load(owner)
        backend.dropWrite = true
        store.save(store.epoch, "public", "pending")
        runCurrent()
        assertEquals(WeshDraftStore.Persistence.Error, store.persistence.value)
        backend.dropWrite = false
        store.retry()
        runCurrent()
        assertEquals("pending", backend.stored[owner]?.get("public"))
        assertEquals(2, backend.writes)
    }

    @Test fun `delayed retry cannot reclaim previous identity or erase new owner edits`() = runTest {
        val backend = FakeBackend().apply { failRead = true }
        val store = WeshDraftStore(backend, backgroundScope, StandardTestDispatcher(testScheduler))
        store.load(owner)
        store.retry()
        backend.failRead = false
        store.load(secondOwner)
        store.save(store.epoch, "public", "owner B")
        runCurrent()
        assertEquals(listOf(owner, secondOwner, secondOwner), backend.readOwners)
        assertEquals("owner B", store.drafts.value["public"])
        assertFalse(backend.stored.containsKey(owner))
    }

    @Test fun `concurrent restore of same identity reads disk only once`() = runTest {
        val backend = FakeBackend().apply { stored[owner] = mapOf("public" to "stored") }
        val store = WeshDraftStore(backend, backgroundScope, StandardTestDispatcher(testScheduler))
        launch { store.load(owner) }
        launch { store.load(owner) }
        runCurrent()
        assertEquals(1, backend.readOwners.size)
        store.save(store.epoch, "public", "new text")
        runCurrent()
        assertEquals("new text", store.drafts.value["public"])
    }

    @Test fun `owner switch clears old RAM and old captures cannot erase identical new text`() = runTest {
        val backend = FakeBackend()
        val store = WeshDraftStore(backend, backgroundScope, StandardTestDispatcher(testScheduler))
        store.load(owner)
        store.save(store.epoch, "public", "same synthetic")
        val sent = store.capture("public")!!
        store.load(secondOwner)
        assertTrue(store.drafts.value.isEmpty())
        store.save(store.epoch, "public", "same synthetic")
        store.clearIfUnchanged(sent)
        assertEquals("same synthetic", store.drafts.value["public"])
    }

    @Test fun `panic fences queued writes clears RAM and stays visibly blocked`() = runTest {
        val backend = FakeBackend()
        val store = WeshDraftStore(backend, backgroundScope, StandardTestDispatcher(testScheduler))
        store.load(owner)
        store.save(store.epoch, "public", "never resurrect")
        store.fenceForPanic()
        runCurrent()
        assertEquals(0, backend.writes)
        assertTrue(store.drafts.value.isEmpty())
        assertFalse(store.save(store.epoch, "public", "blocked"))
        store.load(secondOwner)
        assertEquals(WeshDraftStore.Persistence.WipeRequired, store.persistence.value)
        assertEquals(1, backend.readOwners.size)
    }

    @Test fun `changing local owner retires an old edit token without requiring panic`() = runTest {
        val backend = FakeBackend()
        val store = WeshDraftStore(backend, backgroundScope, StandardTestDispatcher(testScheduler))
        store.load(owner)
        val oldEpoch = store.epoch
        store.load(secondOwner)

        assertFalse(store.save(oldEpoch, "public", "synthetic stale edit"))
        assertTrue(store.save(store.epoch, "public", "synthetic fresh edit"))
        runCurrent()

        assertEquals(mapOf("public" to "synthetic fresh edit"), store.drafts.value)
        assertEquals(mapOf("public" to "synthetic fresh edit"), backend.stored[secondOwner])
        assertEquals(WeshDraftStore.Persistence.Confirmed, store.persistence.value)
    }

    @Test fun `restart with pending marker never restores data or repairs without explicit wipe`() = runTest {
        val backend = FakeBackend().apply { marker = true; stored[owner] = mapOf("public" to "old") }
        val store = WeshDraftStore(backend, backgroundScope, StandardTestDispatcher(testScheduler))
        store.load(owner)
        store.retry()
        runCurrent()
        assertEquals(WeshDraftStore.Persistence.WipeRequired, store.persistence.value)
        assertEquals(mapOf(owner to mapOf("public" to "old")), backend.stored)
        assertFalse(store.save(store.epoch, "public", "blocked"))
    }

    @Test fun `empty edits to many other destinations cannot reset revision of a restored draft`() = runTest {
        val backend = FakeBackend().apply { stored[owner] = mapOf("public" to "A") }
        val store = WeshDraftStore(backend, backgroundScope, StandardTestDispatcher(testScheduler))
        store.load(owner)
        val sent = store.capture("public")!!
        store.save(store.epoch, "public", "B")
        store.save(store.epoch, "public", "A")
        repeat(70) { store.save(store.epoch, "private:" + it.toString(16).padStart(64, '0'), "") }
        store.clearIfUnchanged(sent)
        assertEquals("A", store.drafts.value["public"])
    }

    private class FakeBackend : WeshDraftBackend {
        val stored = mutableMapOf<String, Map<String, String>>()
        val readOwners = mutableListOf<String>()
        var writes = 0
        var failRead = false
        var failWrite = false
        var dropWrite = false
        var marker = false
        override fun load(owner: String): Map<String, String> {
            readOwners += owner
            if (marker) throw DraftWipePending()
            check(!failRead)
            return stored[owner].orEmpty()
        }
        override fun write(owner: String, drafts: Map<String, String>) {
            writes++
            check(!marker)
            if (!dropWrite) stored[owner] = drafts.toMap()
            check(!failWrite)
        }
        override fun markPanicPending() { marker = true }
    }
}
