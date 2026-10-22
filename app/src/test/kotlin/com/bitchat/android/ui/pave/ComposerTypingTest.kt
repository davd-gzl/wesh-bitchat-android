package com.bitchat.android.ui.pave

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Resources
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Types into the real composer footer, fed by the same state builder as the app: an empty field
 * takes the first character in both rooms, and the send button waits for something to send.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ComposerTypingTest {
    private val compose = createComposeRule()

    /** Without a merged manifest in JVM tests, the host activity is declared before the rule launches it. */
    private val hostActivity = object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(app.packageManager).addOrUpdateActivity(
                ActivityInfo().apply { name = ComponentActivity::class.java.name; packageName = app.packageName }
            )
        }
    }

    @get:Rule val rules: RuleChain = RuleChain.outerRule(hostActivity).around(compose)

    @Test fun `an empty composer in a private chat with a verified contact takes typing, and sends only text`() =
        typesThenSends(PaveRoom.Private, recipient = "Nora")

    @Test fun `an empty composer in the public room takes typing, and sends only text`() =
        typesThenSends(PaveRoom.Public, recipient = null)

    private fun typesThenSends(room: PaveRoom, recipient: String?) {
        var draft by mutableStateOf("")
        var sent = 0
        compose.setContent {
            val labels = LabelContext(LocalContext.current)
            CompositionLocalProvider(LocalContext provides labels, LocalResources provides labels.resources) {
                ComposerFooter(
                    // A verified contact, or Bluetooth ready in the public room: the conversation can send.
                    paveComposerState(draft, conversationCanSend = true, sending = false, editable = true,
                        blockedReason = null, feedback = null, onRetryPersistence = null),
                    room, recipient, onDraftChange = { draft = it }, onSend = { sent++ },
                )
            }
        }
        val field = compose.onNode(hasSetTextAction())
        val send = compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))

        field.assertIsEnabled()
        send.assertIsNotEnabled()

        field.performTextInput("   ")
        assertEquals("   ", draft)
        send.assertIsNotEnabled()

        field.performTextReplacement("Salut")
        assertEquals("Salut", draft)
        send.assertIsEnabled()
        send.performClick()
        assertEquals(1, sent)
    }

    /** App resources are not packaged for JVM tests; each string resolves to a stable label. */
    private class LabelContext(base: Context) : ContextWrapper(base) {
        @Suppress("DEPRECATION")
        private val labels = object : Resources(base.assets, base.resources.displayMetrics, base.resources.configuration) {
            override fun getText(id: Int): CharSequence = "label-$id"
            override fun getString(id: Int): String = "label-$id"
            override fun getString(id: Int, vararg formatArgs: Any?): String = (listOf("label-$id") + formatArgs).joinToString(" ")
        }
        override fun getResources(): Resources = labels
        // Reserved names are read in each language through configuration contexts: keep them labelled.
        override fun createConfigurationContext(overrideConfiguration: android.content.res.Configuration): Context =
            LabelContext(baseContext.createConfigurationContext(overrideConfiguration))
    }
}
