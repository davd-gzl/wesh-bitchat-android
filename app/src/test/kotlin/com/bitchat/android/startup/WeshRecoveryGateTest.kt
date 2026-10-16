package com.bitchat.android.startup

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WeshRecoveryGateTest {
    @Test fun `ready coordinator cannot authorize sticky or delayed start during interrupted panic`() {
        val gate = WeshRecoveryGate()
        assertFalse(gate.allowsTransport(prepared = false))
        assertTrue(gate.allowsTransport(prepared = true))
        gate.requireRecovery()
        assertFalse(gate.allowsTransport(prepared = true))
        assertTrue(gate.recoveryRequired.value)
        gate.normalPanicCompleted()
        assertFalse(gate.allowsTransport(prepared = false))
        assertTrue(gate.allowsTransport(prepared = true))
    }

    @Test fun `OS accepted recovery does not open the gate or claim completed global panic`() = runTest {
        val gate = WeshRecoveryGate().apply { requireRecovery() }
        val recovery = InterruptedPanicRecovery(backgroundScope)
        recovery.request { true }
        runCurrent()
        assertEquals(InterruptedPanicRecovery.State.Requested, recovery.state.value)
        assertFalse(gate.allowsTransport(prepared = true))
    }
}
