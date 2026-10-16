package com.bitchat.android.startup

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StartupIdentityResetTest {
    @Test fun identityResetIsOfferedOnlyAfterThreeFailedLoads() = runTest {
        var calls = 0
        val startup = StartupCoordinator(backgroundScope) { if (++calls <= 3) error("Synthetic unreadable identity"); Unit }
        startup.start(); runCurrent()
        repeat(1) { startup.start(retry = true); runCurrent() }
        assertEquals(2, startup.failures.value)
        assertFalse(StartupRecoveryPolicy.offersIdentityReset(startup.failures.value, startup.state.value, draftWipeRequired = false))

        startup.start(retry = true); runCurrent()
        assertEquals(3, startup.failures.value)
        assertTrue(StartupRecoveryPolicy.offersIdentityReset(startup.failures.value, startup.state.value, draftWipeRequired = false))
        assertFalse("an unfinished panic keeps its own erase path",
            StartupRecoveryPolicy.offersIdentityReset(startup.failures.value, startup.state.value, draftWipeRequired = true))

        startup.start(retry = true); runCurrent()
        assertTrue(startup.state.value is StartupCoordinator.State.Ready)
        assertEquals(0, startup.failures.value)
        assertFalse(StartupRecoveryPolicy.offersIdentityReset(3, startup.state.value, draftWipeRequired = false))
    }
}
