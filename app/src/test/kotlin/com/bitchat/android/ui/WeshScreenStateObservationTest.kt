package com.bitchat.android.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeshScreenStateObservationTest {
    // WeshScreen only delegates to the Pavé app, which holds the conversation state.
    private val screen = File("src/main/java/com/bitchat/android/ui/pave/WeshPaveApp.kt").readText()

    @Test
    fun `the Wesh screen observes session and Bluetooth flows instead of a one second ticker`() {
        assertTrue(File("src/main/java/com/bitchat/android/ui/WeshScreen.kt").readText().contains("WeshPaveApp(viewModel)"))
        assertFalse(Regex("""while\s*\(true\)\s*\{\s*delay\(1000\)\s*;\s*revision\+\+""").containsMatchIn(screen))
        assertTrue(screen.contains("viewModel.peerSessionStates.collectAsState()"))
        assertTrue(screen.contains("viewModel.weshBluetoothReadyState.collectAsState()"))
    }
}
