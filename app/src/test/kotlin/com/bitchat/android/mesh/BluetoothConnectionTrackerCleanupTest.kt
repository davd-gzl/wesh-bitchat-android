package com.bitchat.android.mesh

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class BluetoothConnectionTrackerCleanupTest {
    private val scope = TestScope()
    private val tracker = BluetoothConnectionTracker(scope, mock())

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `stop closes every GATT before scope cancellation despite denied disconnect and close`() {
        val deniedGatt = mock<BluetoothGatt>()
        val remainingGatt = mock<BluetoothGatt>()
        val device = mock<BluetoothDevice>()
        doThrow(SecurityException("synthetic revocation")).whenever(deniedGatt).disconnect()
        doThrow(SecurityException("synthetic revocation")).whenever(deniedGatt).close()
        tracker.addDeviceConnection(FIRST_ADDRESS, BluetoothConnectionTracker.DeviceConnection(device, deniedGatt))
        tracker.addDeviceConnection(SECOND_ADDRESS, BluetoothConnectionTracker.DeviceConnection(mock(), remainingGatt))
        tracker.addSubscribedDevice(device)
        tracker.addPendingConnection("synthetic-pending")
        tracker.observePeerIfCurrent(FIRST_ADDRESS, tracker.getCurrentLinkID(FIRST_ADDRESS)!!, "0011223344556677")

        tracker.stop()
        scope.cancel()
        tracker.stop()

        verify(deniedGatt).disconnect()
        verify(deniedGatt).close()
        verify(remainingGatt).disconnect()
        verify(remainingGatt).close()
        assertEquals(0, tracker.getConnectedDeviceCount())
        assertTrue(tracker.getSubscribedDevices().isEmpty())
        assertTrue(tracker.addressPeerMap.isEmpty())
        assertTrue(tracker.isConnectionAttemptAllowed("synthetic-pending"))
    }

    @Test
    fun `denied individual disconnect still removes local link and peer observation`() {
        val gatt = mock<BluetoothGatt>()
        doThrow(SecurityException("synthetic revocation")).whenever(gatt).disconnect()
        doThrow(SecurityException("synthetic revocation")).whenever(gatt).close()
        tracker.addDeviceConnection(FIRST_ADDRESS, BluetoothConnectionTracker.DeviceConnection(mock(), gatt, linkID = "link-a"))
        tracker.observePeerIfCurrent(FIRST_ADDRESS, "link-a", "0011223344556677")

        tracker.disconnectDevice(FIRST_ADDRESS)

        verify(gatt).disconnect()
        verify(gatt).close()
        assertFalse(tracker.isDeviceConnected(FIRST_ADDRESS))
        assertFalse(tracker.addressPeerMap.containsKey(FIRST_ADDRESS))
    }

    @Test
    fun `accepted individual disconnect leaves GATT close to its normal callback`() {
        val gatt = mock<BluetoothGatt>()
        tracker.addDeviceConnection(FIRST_ADDRESS, BluetoothConnectionTracker.DeviceConnection(mock(), gatt))

        tracker.disconnectDevice(FIRST_ADDRESS)

        verify(gatt).disconnect()
        verify(gatt, never()).close()
        assertFalse(tracker.isDeviceConnected(FIRST_ADDRESS))
    }

    private companion object {
        const val FIRST_ADDRESS = "AA:BB:CC:DD:EE:01"
        const val SECOND_ADDRESS = "AA:BB:CC:DD:EE:02"
    }
}
