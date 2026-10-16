package com.bitchat.android.startup

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InterruptedPanicRecoveryTest {
    @Test fun `OS refusal and exception remain blocked and permit explicit retry`() = runTest {
        val recovery = InterruptedPanicRecovery(backgroundScope)
        recovery.request { false }
        runCurrent()
        assertEquals(InterruptedPanicRecovery.State.Failed, recovery.state.value)
        recovery.request { error("synthetic OS error") }
        runCurrent()
        assertEquals(InterruptedPanicRecovery.State.Failed, recovery.state.value)
    }
    @Test fun `OS acceptance is requested only once and never claims completed erasure`() = runTest {
        val recovery = InterruptedPanicRecovery(backgroundScope)
        var calls = 0
        recovery.request { calls++; true }
        recovery.request { calls++; true }
        runCurrent()
        assertEquals(InterruptedPanicRecovery.State.Requested, recovery.state.value)
        recovery.request { calls++; true }
        runCurrent()
        assertEquals(1, calls)
    }
}
