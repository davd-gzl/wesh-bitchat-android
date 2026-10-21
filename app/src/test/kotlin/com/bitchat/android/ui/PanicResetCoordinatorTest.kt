package com.bitchat.android.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PanicResetCoordinatorTest {
    @Test
    fun `a confirmed erase wipes the app itself then restarts`() = runTest {
        val order = mutableListOf<String>()
        PanicResetCoordinator.eraseAndRestart(
            establishIntent = { order += "intent"; true },
            wipe = { order += "wipe" },
            restart = { order += "restart" },
            clearAppData = { order += "android" }
        )
        assertEquals(listOf("intent", "wipe", "restart"), order)
    }

    @Test
    fun `an unconfirmed intent never reaches the own wipe and Android clears all app data`() = runTest {
        val order = mutableListOf<String>()
        PanicResetCoordinator.eraseAndRestart(
            establishIntent = { order += "intent"; false },
            wipe = { order += "wipe" },
            restart = { order += "restart" },
            clearAppData = { order += "android" }
        )
        assertEquals(listOf("intent", "android"), order)
    }

    @Test
    fun `a failed own wipe hands the erase to Android without restarting`() = runTest {
        val order = mutableListOf<String>()
        PanicResetCoordinator.eraseAndRestart(
            establishIntent = { true },
            wipe = { order += "wipe"; error("Synthetic wipe failure") },
            restart = { order += "restart" },
            clearAppData = { order += "android" }
        )
        assertEquals(listOf("wipe", "android"), order)
    }

    @Test
    fun `a failed in-app erase or intent still clears all app data`() = runTest {
        var cleared = 0
        PanicResetCoordinator.eraseOrClearAppData(erase = { throw RuntimeException() }, clearAppData = { cleared++ })
        PanicResetCoordinator.eraseOrClearAppData(erase = { false }, clearAppData = { cleared++ })
        assertEquals(2, cleared)
    }

    @Test
    fun `every transport is stopped even when an earlier stop throws`() {
        val stopped = mutableListOf<String>()
        PanicResetCoordinator.stopTransports(
            { stopped += "ble"; error("radio busy") },
            { stopped += "wifi" }
        )
        assertEquals(listOf("ble", "wifi"), stopped)
    }

    @Test
    fun `a cancelled in-app erase still clears all app data`() = runTest {
        var cleared = 0
        val erasing = async {
            PanicResetCoordinator.eraseOrClearAppData(erase = { awaitCancellation() }, clearAppData = { cleared++ })
        }
        runCurrent()
        erasing.cancelAndJoin()
        assertEquals(1, cleared)
    }

    @Test
    fun `a completed in-app erase does not clear app data`() = runTest {
        var cleared = 0
        PanicResetCoordinator.eraseOrClearAppData(erase = { true }, clearAppData = { cleared++ })
        assertEquals(0, cleared)
    }
}
