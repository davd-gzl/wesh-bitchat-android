package com.bitchat.android

import com.bitchat.android.ui.pave.BluetoothRefusal
import com.bitchat.android.ui.pave.OnboardingStart
import com.bitchat.android.ui.pave.bluetoothRefusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeshWelcomeTest {
    @Test
    fun `a simple refusal keeps Continuer, only a permanent one sends to Settings`() {
        assertEquals(BluetoothRefusal.None, bluetoothRefusal(granted = true, shouldShowRationale = false))
        assertEquals(BluetoothRefusal.Once, bluetoothRefusal(granted = false, shouldShowRationale = true))
        assertEquals(BluetoothRefusal.Permanent, bluetoothRefusal(granted = false, shouldShowRationale = false))
    }

    @Test
    fun `an existing nickname is kept, BitChat's default is not proposed`() {
        assertEquals("Renard 42", welcomeNickname(welcomed = false, stored = "Renard 42"))
        assertNull(welcomeNickname(welcomed = false, stored = "anon1234"))
        assertNull(welcomeNickname(welcomed = false, stored = ""))
        assertEquals("anon1234", welcomeNickname(welcomed = true, stored = "anon1234"))
    }

    @Test
    fun `a returning person goes straight to what is missing`() {
        assertEquals(OnboardingStart.Welcome, welcomeStart(welcomed = false, requiredGranted = false, notificationsPending = true))
        assertEquals(OnboardingStart.Bluetooth, welcomeStart(welcomed = true, requiredGranted = false, notificationsPending = true))
        assertEquals(OnboardingStart.Notifications, welcomeStart(welcomed = true, requiredGranted = true, notificationsPending = true))
        assertEquals(OnboardingStart.Notifications, welcomeStart(welcomed = false, requiredGranted = true, notificationsPending = true))
    }
}
