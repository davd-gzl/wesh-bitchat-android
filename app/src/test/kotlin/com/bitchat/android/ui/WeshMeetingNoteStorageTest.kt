package com.bitchat.android.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import com.bitchat.android.services.InMemoryConversationStorageCipher
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class WeshMeetingNoteStorageTest {
    @get:Rule val directory = TemporaryFolder()
    @Test
    fun `saving a personal meeting note never persists its plaintext`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val raw = context.getSharedPreferences("synthetic-note-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        try {
            val preferences = WeshPreferences(raw, StandardTestDispatcher(testScheduler),
                meetingBackendFactory = { EncryptedWeshMeetingNoteBackend(directory.root, InMemoryConversationStorageCipher()) })
            assertTrue(preferences.loadMeetingNote())
            val note = "Synthetic personal meeting note fixture"
            val epoch = preferences.resetEpoch.value
            assertTrue(preferences.editMeetingDraft(note, epoch))
            assertTrue(preferences.saveMeetingDraft(epoch))
            val ciphertext = File(directory.root, "wesh-meeting-note-v1.bin")
            assertTrue(ciphertext.isFile)
            assertFalse(ciphertext.readBytes().toString(Charsets.ISO_8859_1).contains(note))
            assertFalse("A saved note must not appear in raw preference values",
                raw.all.values.filterIsInstance<String>().any { note in it })
        } finally {
            assertTrue(raw.edit().clear().commit())
        }
    }
}
