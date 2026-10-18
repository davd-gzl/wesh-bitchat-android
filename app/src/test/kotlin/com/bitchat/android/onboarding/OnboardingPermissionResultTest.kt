package com.bitchat.android.onboarding

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.res.Resources
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.ui.pave.PaveStringResources
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class OnboardingPermissionResultTest {
    private lateinit var application: Application
    private lateinit var controller: ActivityController<ResourceActivity>
    private lateinit var permissions: PermissionManager
    private lateinit var coordinator: OnboardingCoordinator
    private val failures = mutableListOf<String>()
    private var completed = 0

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        application.getSharedPreferences("bitchat_permissions", Context.MODE_PRIVATE)
            .edit().clear().commit()
        controller = Robolectric.buildActivity(ResourceActivity::class.java).create()
        // JVM tests do not package Android resources. Keep the real callback/permission
        // policy while resolving its labels from the shipped English XML fixtures.
        val labels = PaveStringResources.strings("values")
        val resources = mock<Resources>()
        whenever(resources.getString(any())).thenAnswer { invocation ->
            labels.getValue(PaveStringResources.name(invocation.getArgument(0)))
        }
        controller.get().fixtureResources = resources
        permissions = PermissionManager(controller.get())
        shadowOf(application).grantPermissions(*permissions.getRequiredPermissions().toTypedArray())
        coordinator = OnboardingCoordinator(
            activity = controller.get(),
            permissionManager = permissions,
            onOnboardingComplete = { completed++ },
            onOnboardingFailed = failures::add
        )
    }

    @After
    fun tearDown() {
        controller.get().fixtureResources = null
        controller.destroy()
    }

    @Test
    fun `empty result cannot finish while a required permission is missing`() {
        shadowOf(application).denyPermissions(Manifest.permission.BLUETOOTH_SCAN)

        deliverResult(emptyMap())

        assertRemainsBlocked()
    }

    @Test
    fun `notification-only result cannot hide a required permission revoked during its prompt`() {
        shadowOf(application).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)

        deliverResult(mapOf(Manifest.permission.POST_NOTIFICATIONS to false))

        assertRemainsBlocked()
    }

    @Test
    fun `claimed granted result cannot override the actual revoked permission state`() {
        shadowOf(application).denyPermissions(Manifest.permission.BLUETOOTH_ADVERTISE)

        deliverResult(mapOf(Manifest.permission.BLUETOOTH_ADVERTISE to true))

        assertRemainsBlocked()
    }

    @Test
    fun `explicit required denial remains blocked`() {
        shadowOf(application).denyPermissions(Manifest.permission.BLUETOOTH_SCAN)

        deliverResult(mapOf(Manifest.permission.BLUETOOTH_SCAN to false))

        assertRemainsBlocked()
    }

    @Test
    fun `optional notification denial completes when all required permissions are still granted`() {
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        deliverResult(mapOf(Manifest.permission.POST_NOTIFICATIONS to false))

        assertFalse(permissions.isFirstTimeLaunch())
        assertEquals(1, completed)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `empty result completes when no required permission is missing`() {
        deliverResult(emptyMap())

        assertFalse(permissions.isFirstTimeLaunch())
        assertEquals(1, completed)
        assertTrue(failures.isEmpty())
    }

    private fun assertRemainsBlocked() {
        assertTrue("Missing required permission must not be persisted as completed onboarding", permissions.isFirstTimeLaunch())
        assertEquals("Missing required permission must not invoke completion", 0, completed)
        assertEquals("The existing failure callback must report the missing requirement", 1, failures.size)
    }

    private fun deliverResult(result: Map<String, Boolean>) {
        // Exercise the callback used by RequestMultiplePermissions without launching system UI.
        val callback = OnboardingCoordinator::class.java.getDeclaredMethod("handlePermissionResults", Map::class.java)
        callback.isAccessible = true
        callback.invoke(coordinator, result)
        // Advance the controller's existing delayed completion; never wait on wall time.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
    }

    class ResourceActivity : ComponentActivity() {
        var fixtureResources: Resources? = null

        override fun getResources(): Resources = fixtureResources ?: super.getResources()
    }
}
