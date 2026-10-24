package com.bitchat.android.ui.pave

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.content.res.Resources
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.R
import com.bitchat.android.ui.WeshLinks
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AboutPrivacyLinkTest {
    private val compose = createComposeRule()

    private val hostActivity = object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(app.packageManager).addOrUpdateActivity(
                ActivityInfo().apply { name = ComponentActivity::class.java.name; packageName = app.packageName },
            )
        }
    }

    @get:Rule val rules: RuleChain = RuleChain.outerRule(hostActivity).around(compose)

    @Test
    fun `privacy row is visibly labelled in both shipped languages`() {
        assertEquals("Confidentialité", PaveStringResources.strings("values-fr").getValue("wesh_pave_about_privacy"))
        assertEquals("Privacy policy", PaveStringResources.strings("values").getValue("wesh_pave_about_privacy"))
    }

    @Test
    fun `French privacy row opens the French policy`() {
        clickPrivacy(Locale.FRENCH, WeshLinks.PRIVACY_URL_FR)
    }

    @Test
    fun `English privacy row opens the English policy`() {
        clickPrivacy(Locale.ENGLISH, WeshLinks.PRIVACY_URL_EN)
    }

    private fun clickPrivacy(locale: Locale, expectedUrl: String) {
        val opened = mutableListOf<String>()
        val configuration = Configuration().apply { setLocale(locale) }
        compose.setContent {
            val labels = LabelContext(LocalContext.current)
            CompositionLocalProvider(
                LocalContext provides labels,
                LocalResources provides labels.resources,
                LocalConfiguration provides configuration,
                LocalUriHandler provides object : UriHandler {
                    override fun openUri(uri: String) {
                        opened += uri
                    }
                },
            ) {
                WeshPaveTheme {
                    AboutScreen(versionName = "1.0.0", onBack = {}, onOpenLicenses = {})
                }
            }
        }

        compose.onNodeWithText("label-${R.string.wesh_pave_about_privacy}").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(expectedUrl), opened)
        }
    }

    private class LabelContext(base: Context) : ContextWrapper(base) {
        @Suppress("DEPRECATION")
        private val labels = object : Resources(base.assets, base.resources.displayMetrics, base.resources.configuration) {
            override fun getText(id: Int): CharSequence = "label-$id"
            override fun getString(id: Int): String = "label-$id"
            override fun getString(id: Int, vararg formatArgs: Any?): String =
                (listOf("label-$id") + formatArgs).joinToString(" ")
        }

        override fun getResources(): Resources = labels

        override fun createConfigurationContext(overrideConfiguration: Configuration): Context =
            LabelContext(baseContext.createConfigurationContext(overrideConfiguration))
    }
}
