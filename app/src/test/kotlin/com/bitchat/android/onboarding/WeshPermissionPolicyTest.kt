package com.bitchat.android.onboarding

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class WeshPermissionPolicyTest {
    @Test @Config(sdk = [35])
    fun `modern Wesh onboarding needs Bluetooth but no foreground background or WiFi location permissions`() {
        val manager = PermissionManager(ApplicationProvider.getApplicationContext<Application>())
        val permissions = manager.getRequiredPermissions()
        assertTrue(permissions.contains(Manifest.permission.BLUETOOTH_SCAN))
        assertTrue(permissions.contains(Manifest.permission.BLUETOOTH_CONNECT))
        assertTrue(permissions.contains(Manifest.permission.BLUETOOTH_ADVERTISE))
        assertFalse(permissions.contains(Manifest.permission.ACCESS_FINE_LOCATION))
        assertFalse(permissions.contains(Manifest.permission.ACCESS_COARSE_LOCATION))
        assertFalse(permissions.contains(Manifest.permission.NEARBY_WIFI_DEVICES))
        assertFalse(manager.needsBackgroundLocationPermission())
    }

    @Test @Config(sdk = [30])
    fun `legacy Android keeps its actual Bluetooth scanning permission requirement`() {
        val manager = PermissionManager(ApplicationProvider.getApplicationContext<Application>())
        assertTrue(manager.getRequiredPermissions().contains(Manifest.permission.ACCESS_FINE_LOCATION))
        assertTrue(manager.needsBackgroundLocationPermission())
    }
}
