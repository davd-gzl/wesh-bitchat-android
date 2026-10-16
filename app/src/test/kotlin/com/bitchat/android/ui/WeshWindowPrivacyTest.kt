package com.bitchat.android.ui

import android.app.Activity
import android.app.Application
import android.view.WindowManager
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class WeshWindowPrivacyTest {
    @Test
    @Config(sdk = [26])
    fun `window is secure on older Android versions`() = assertSecure()

    @Test
    @Config(sdk = [35])
    fun `window is secure on current Android versions`() = assertSecure()

    private fun assertSecure() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        WeshWindowPrivacy.apply(activity)
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }

    @Test
    @Config(sdk = [35])
    fun `main activity applies the secure window policy`() {
        val source = java.io.File("src/main/java/com/bitchat/android/MainActivity.kt").readText()
        assertTrue(source.contains("WeshWindowPrivacy.apply(this)"))
    }
}
