package com.bitchat.android.ui

import com.bitchat.android.mesh.admitWeshPublicMessage

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class WeshPublicAdmissionTest {
    @Test fun `unavailable transport refuses before preparing a packet`() = runTest {
        var effects = 0
        assertFalse(admitWeshPublicMessage("synthetic public",
            ready = { false }, generation = { 1L },
            prepare = { effects++; "packet" },
            handoff = { _, _ -> effects++; true }, recordAccepted = { effects++ }))
        assertEquals(0, effects)
    }

    @Test fun `stop during preparation prevents any transport handoff`() = runTest {
        var active = true
        var handoffs = 0
        var recorded = 0
        assertFalse(admitWeshPublicMessage("synthetic public", ready = { active }, generation = { 1L },
            prepare = { active = false; "packet" },
            handoff = { _, _ -> handoffs++; true }, recordAccepted = { recorded++ }))
        assertEquals(0, handoffs)
        assertEquals(0, recorded)
    }

    @Test fun `stop and restart between preflight and concrete handoff cannot reuse admission`() = runTest {
        var generation = 1L
        var concreteWrites = 0
        var recorded = 0
        val awaitingHandoff = CompletableDeferred<Unit>()
        val continueHandoff = CompletableDeferred<Unit>()
        val send = async {
            admitWeshPublicMessage("synthetic public", ready = { true }, generation = { generation },
                prepare = { "packet" },
                handoff = { _, authorized ->
                    awaitingHandoff.complete(Unit)
                    continueHandoff.await()
                    if (authorized()) { concreteWrites++; true } else false
                }, recordAccepted = { recorded++ })
        }
        awaitingHandoff.await()
        generation++
        continueHandoff.complete(Unit)
        assertFalse(send.await())
        assertEquals(0, concreteWrites)
        assertEquals(0, recorded)
    }

    @Test fun `scope cancellation or BLE pause while awaiting handoff creates no accepted sync entry`() = runTest {
        var ready = true
        var recorded = 0
        assertFalse(admitWeshPublicMessage("synthetic public", ready = { ready }, generation = { 1L },
            prepare = { "packet" }, handoff = { _, authorized -> ready = false; authorized() },
            recordAccepted = { recorded++ }))
        assertEquals(0, recorded)
    }

    @Test fun `refused or failed handoff never records an accepted public packet`() = runTest {
        var recorded = 0
        for (throws in listOf(false, true)) {
            assertFalse(admitWeshPublicMessage("synthetic public", ready = { true }, generation = { 1L },
                prepare = { "packet" }, handoff = { _, _ ->
                    if (throws) throw IllegalStateException("synthetic refusal")
                    false
                }, recordAccepted = { recorded++ }))
        }
        assertEquals(0, recorded)
    }

    @Test fun `accepted local handoff records the same packet only after transport acceptance`() = runTest {
        val effects = mutableListOf<String>()
        val packet = Any()
        assertTrue(admitWeshPublicMessage("synthetic public", ready = { true }, generation = { 1L },
            prepare = { packet }, handoff = { candidate, authorized ->
                assertSame(packet, candidate)
                assertTrue(authorized())
                effects += "handoff"
                true
            }, recordAccepted = { candidate -> assertSame(packet, candidate); effects += "recorded" }))
        assertEquals(listOf("handoff", "recorded"), effects)
    }

    @Test fun `lifecycle invalidated after concrete acceptance does not record public success`() = runTest {
        var generation = 1L
        var recorded = 0
        var handoffs = 0
        assertFalse(admitWeshPublicMessage("synthetic public", ready = { true }, generation = { generation },
            prepare = { "packet" }, handoff = { _, authorized ->
                assertTrue(authorized())
                handoffs++
                generation++
                true
            }, recordAccepted = { recorded++ }))
        assertEquals(1, handoffs)
        assertEquals(0, recorded)
    }

    @Test fun `caller cancellation is propagated without recording success`() = runTest {
        var recorded = 0
        try {
            admitWeshPublicMessage("synthetic public", ready = { true }, generation = { 1L },
                prepare = { "packet" }, handoff = { _, _ -> throw CancellationException("synthetic cancellation") },
                recordAccepted = { recorded++ })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(0, recorded)
        }
    }
}
