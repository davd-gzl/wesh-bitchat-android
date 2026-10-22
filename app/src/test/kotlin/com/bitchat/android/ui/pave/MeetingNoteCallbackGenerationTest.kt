package com.bitchat.android.ui.pave

import android.app.Application
import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.services.InMemoryConversationStorageCipher
import com.bitchat.android.ui.EncryptedWeshMeetingNoteBackend
import com.bitchat.android.ui.MeetingNoteState
import com.bitchat.android.ui.WeshPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/** Retained edit callbacks are an admission boundary, independently of navigation or IME behavior. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class MeetingNoteCallbackGenerationTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun `edit remains refused while fenced even when collected State has advanced`() = runTest {
        withStore { preferences, backend ->
            assertTrue(preferences.loadMeetingNote())
            val collectedEpoch = mutableStateOf(preferences.resetEpoch.value)
            var accepted = 0
            var feedback: String? = "Synthetic retained feedback"
            val edit = meetingNoteEditCallback(preferences, collectedEpoch.value) {
                accepted++
                feedback = null
            }
            preferences.fenceForPanic()
            collectedEpoch.value = preferences.resetEpoch.value

            edit("Synthetic refused edit")

            assertEquals(NoteEffects("", "", null, "Synthetic retained feedback", 0),
                NoteEffects(preferences.meetingDraft.value, preferences.meetingNote,
                    backend().load(), feedback, accepted))
            assertEquals(MeetingNoteState.Fenced, preferences.meetingNoteState.value)
        }
    }

    private suspend fun TestScope.withStore(
        test: suspend (WeshPreferences, () -> EncryptedWeshMeetingNoteBackend) -> Unit
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val raw = context.getSharedPreferences("synthetic-note-callback-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        val cipher = InMemoryConversationStorageCipher()
        val backend = { EncryptedWeshMeetingNoteBackend(directory.root, cipher) }
        val preferences = WeshPreferences(raw, StandardTestDispatcher(testScheduler), meetingBackendFactory = backend)
        try { test(preferences, backend) }
        finally { assertTrue(raw.edit().clear().commit()) }
    }

    private data class NoteEffects(val draft: String, val saved: String, val persisted: String?,
        val feedback: String?, val accepted: Int)
}
