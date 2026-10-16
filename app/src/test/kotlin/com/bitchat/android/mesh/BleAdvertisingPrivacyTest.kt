package com.bitchat.android.mesh

import android.os.ParcelUuid
import com.bitchat.android.util.AppConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class BleAdvertisingPrivacyTest {
    @Test
    fun `scan response carries no stable identifier`() {
        val response = BluetoothGattServerManager.scanResponseData()

        assertTrue(response.serviceData.isEmpty())
        assertTrue(response.manufacturerSpecificData.size() == 0)
        assertTrue(response.serviceUuids.isNullOrEmpty())
        assertFalse(response.includeDeviceName)
        assertFalse(response.includeTxPowerLevel)
    }

    @Test
    fun `advertisement only exposes the shared mesh service UUID`() {
        val advertisement = BluetoothGattServerManager.advertiseData()

        assertEquals(listOf(ParcelUuid(AppConstants.Mesh.Gatt.SERVICE_UUID)), advertisement.serviceUuids)
        assertTrue(advertisement.serviceData.isEmpty())
        assertFalse(advertisement.includeDeviceName)
    }
}
