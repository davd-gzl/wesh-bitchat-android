package com.bitchat.android.mesh

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import com.bitchat.android.ui.debug.DebugSettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 35])
class BluetoothConnectionLimitPermissionTest {
    private inline fun <reified T> field(manager: BluetoothConnectionManager, name: String): T {
        val field = BluetoothConnectionManager::class.java.getDeclaredField(name)
        field.isAccessible = true
        return T::class.java.cast(field.get(manager))
    }

    @Test
    fun `denied eviction continues later disconnects and retains tracker ownership until callback`() {
        val manager = BluetoothConnectionManager(RuntimeEnvironment.getApplication(), "0011223344556677")
        // Prepare admission state without starting scanners, services or asynchronous limit observers.
        runBlocking { field<CoroutineScope>(manager, "connectionScope").coroutineContext[Job]!!.cancelAndJoin() }
        val tracker = field<BluetoothConnectionTracker>(manager, "connectionTracker")
        val debug = DebugSettingsManager.getInstance()
        val oldOverall = debug.maxConnectionsOverall.value
        val oldClient = debug.maxClientConnections.value
        val gatts = List(3) { mock<BluetoothGatt>() }
        val connections = gatts.mapIndexed { index, gatt ->
            BluetoothConnectionTracker.DeviceConnection(mock<BluetoothDevice>(), gatt = gatt, isClient = true, connectedAt = index.toLong())
        }
        try {
            debug.setMaxConnectionsOverall(1)
            debug.setMaxClientConnections(1)
            connections.forEachIndexed { index, connection -> tracker.addDeviceConnection("synthetic-$index", connection) }
            doThrow(SecurityException("synthetic revocation")).whenever(gatts[0]).disconnect()
            BluetoothConnectionManager::class.java.getDeclaredField("isActive").apply {
                isAccessible = true
                setBoolean(manager, true)
            }

            field<BluetoothConnectionManagerDelegate>(manager, "componentDelegate").onServerConnectionRegistered(mock())

            verify(gatts[0]).disconnect()
            verify(gatts[1]).disconnect()
            verify(gatts[2], never()).disconnect()
            gatts.forEach { verify(it, never()).close() }
            assertEquals(3, tracker.getConnectedDeviceCount())
            connections.forEachIndexed { index, connection -> assertSame(connection, tracker.getDeviceConnection("synthetic-$index")) }
        } finally {
            debug.setMaxConnectionsOverall(oldOverall)
            debug.setMaxClientConnections(oldClient)
            tracker.stop()
        }
    }
}
