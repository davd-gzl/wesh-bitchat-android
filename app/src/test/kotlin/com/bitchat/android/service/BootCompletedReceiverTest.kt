package com.bitchat.android.service

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class BootCompletedReceiverTest {
    private fun freshBootPreferences(): Application {
        val context = RuntimeEnvironment.getApplication()
        assertTrue(context.getSharedPreferences("bitchat_mesh_service_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit())
        MeshServicePreferences.init(context)
        MeshServicePreferences.setBackgroundEnabled(true)
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        return context
    }

    @Test fun `fresh preferences require opting in to automatic boot startup`() {
        freshBootPreferences()

        assertFalse(MeshServicePreferences.isAutoStartEnabled())
    }

    @Test fun `boot completed cannot start a fresh installation without opt in`() {
        val context = freshBootPreferences()

        BootCompletedReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertNull(shadowOf(context).nextStartedService)
    }

    @Test fun `locked boot completed cannot start a fresh installation without opt in`() {
        val context = freshBootPreferences()

        BootCompletedReceiver().onReceive(context, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))

        assertNull(shadowOf(context).nextStartedService)
    }

    @Test fun `missing action does not read preferences or touch services`() {
        val context = mock(Context::class.java)
        BootCompletedReceiver().onReceive(context, Intent())
        verifyNoInteractions(context)
    }

    @Test fun `unrelated action does not read preferences or touch services`() {
        val context = mock(Context::class.java)
        BootCompletedReceiver().onReceive(context, Intent("synthetic.unrelated.action"))
        verifyNoInteractions(context)
    }

    @Test fun `both boot actions respect disabled automatic startup`() {
        val context = RuntimeEnvironment.getApplication()
        MeshServicePreferences.init(context)
        MeshServicePreferences.setAutoStartEnabled(false)
        for (action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED)) {
            BootCompletedReceiver().onReceive(context, Intent(action))
            assertFalse(MeshServicePreferences.isAutoStartEnabled())
            assertNull(shadowOf(context).nextStartedService)
        }
    }

    @Test fun `saved boot opt in survives preference reinitialization and starts both boot actions`() {
        val context = RuntimeEnvironment.getApplication()
        MeshServicePreferences.init(context)
        MeshServicePreferences.setBackgroundEnabled(true)
        MeshServicePreferences.setAutoStartEnabled(true)
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

        for (action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED)) {
            MeshServicePreferences.init(context)
            BootCompletedReceiver().onReceive(context, Intent(action))
            assertTrue(MeshServicePreferences.isAutoStartEnabled())
            val started = shadowOf(context).nextStartedService
            assertEquals(MeshForegroundService.ACTION_START, started?.action)
            assertEquals(MeshForegroundService::class.java.name, started?.component?.className)
        }
    }

    @Test fun `saved boot opt out survives reinitialization after previously opting in`() {
        val context = RuntimeEnvironment.getApplication()
        MeshServicePreferences.init(context)
        MeshServicePreferences.setBackgroundEnabled(true)
        MeshServicePreferences.setAutoStartEnabled(true)
        MeshServicePreferences.setAutoStartEnabled(false)
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

        for (action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED)) {
            MeshServicePreferences.init(context)
            BootCompletedReceiver().onReceive(context, Intent(action))
            assertFalse(MeshServicePreferences.isAutoStartEnabled())
            assertNull(shadowOf(context).nextStartedService)
        }
    }

    @Test fun `boot opt in cannot override disabled background operation`() {
        val context = RuntimeEnvironment.getApplication()
        MeshServicePreferences.init(context)
        MeshServicePreferences.setAutoStartEnabled(true)
        MeshServicePreferences.setBackgroundEnabled(false)
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

        BootCompletedReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertTrue(MeshServicePreferences.isAutoStartEnabled())
        assertFalse(MeshServicePreferences.isBackgroundEnabled())
        assertNull(shadowOf(context).nextStartedService)
    }

    @Test fun `stopping the current service preserves the separate future boot choice`() {
        val context = RuntimeEnvironment.getApplication()
        MeshServicePreferences.init(context)
        MeshServicePreferences.setBackgroundEnabled(true)
        MeshServicePreferences.setAutoStartEnabled(true)
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

        MeshForegroundService.stop(context)
        assertEquals(MeshForegroundService.ACTION_STOP, shadowOf(context).nextStartedService?.action)
        MeshServicePreferences.init(context)
        assertTrue(MeshServicePreferences.isAutoStartEnabled())

        BootCompletedReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(MeshForegroundService.ACTION_START, shadowOf(context).nextStartedService?.action)
    }
}
