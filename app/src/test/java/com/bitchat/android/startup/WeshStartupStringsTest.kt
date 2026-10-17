package com.bitchat.android.startup

import android.content.Context
import com.bitchat.android.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The startup, recovery and verification copy follows the phone language: English by default. */
@RunWith(RobolectricTestRunner::class)
class WeshStartupStringsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    @Config(qualifiers = "en")
    fun anEnglishPhoneReadsEnglish() {
        assertEquals("Getting Wesh ready…", context.getString(R.string.wesh_startup_preparing))
        assertEquals("Try again", context.getString(R.string.wesh_startup_retry))
        assertEquals("App settings", context.getString(R.string.wesh_verify_app_settings))
    }

    @Test
    @Config(qualifiers = "fr")
    fun aFrenchPhoneReadsFrench() {
        assertEquals("Préparation de Wesh…", context.getString(R.string.wesh_startup_preparing))
        assertEquals("Réessayer", context.getString(R.string.wesh_startup_retry))
        assertEquals("Réglages de l’app", context.getString(R.string.wesh_verify_app_settings))
    }
}
