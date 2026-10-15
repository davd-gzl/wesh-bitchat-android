package com.bitchat.android.ui

import android.content.SharedPreferences
import com.bitchat.android.services.ConversationStorageCipher
import com.bitchat.android.startup.WeshRecoveryGate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class WeshPreferencesPanicTest {
    @get:Rule val directory = TemporaryFolder()

    private class Storage {
        val memory = mutableMapOf<String, Any>("meeting_note" to "synthetic saved note",
            "public_muted" to true, "public_disclosure" to true)
        val disk = memory.toMutableMap()
        val prefs: SharedPreferences = mock()
        private val editor: SharedPreferences.Editor = mock()
        private val puts = mutableMapOf<String, Any>()
        private var clearPending = false
        var commitSucceeds = true
        var throwOnClear = false
        var throwOnCommit = false
        var retainOnClear = false
        var commits = 0

        init {
            whenever(prefs.getString(eq("meeting_note"), anyOrNull())).thenAnswer { memory["meeting_note"] as String? ?: "" }
            whenever(prefs.getBoolean(any(), any())).thenAnswer { memory[it.getArgument<String>(0)] as Boolean? ?: false }
            whenever(prefs.all).thenAnswer { memory.toMap() }
            whenever(prefs.edit()).thenReturn(editor)
            whenever(editor.putString(any(), anyOrNull())).thenAnswer {
                puts[it.getArgument(0)] = it.getArgument<String>(1); editor
            }
            whenever(editor.putBoolean(any(), any())).thenAnswer {
                puts[it.getArgument(0)] = it.getArgument<Boolean>(1); editor
            }
            whenever(editor.clear()).thenAnswer {
                if (throwOnClear) error("synthetic clear failure")
                clearPending = true
                editor
            }
            whenever(editor.commit()).thenAnswer {
                commits++
                publishMemory()
                if (throwOnCommit) error("synthetic commit failure")
                if (commitSucceeds) persistMemory()
                commitSucceeds
            }
            doAnswer { publishMemory(); persistMemory(); null }.whenever(editor).apply()
        }

        private fun publishMemory() {
            if (clearPending && !retainOnClear) memory.clear()
            clearPending = false
            memory.putAll(puts)
            puts.clear()
        }

        private fun persistMemory() { disk.clear(); disk.putAll(memory) }
        fun simulateRestart() { memory.clear(); memory.putAll(disk) }
    }

    // Only the real durable marker lifecycle is under test; this store contains no draft data.
    private fun emptyDraftBackend() = EncryptedWeshDraftBackend(directory.root, object : ConversationStorageCipher {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray = error("Unexpected encryption")
        override fun decrypt(envelope: ByteArray, associatedData: ByteArray): ByteArray = error("Unexpected decryption")
        override fun destroyKey() = Unit
    })

    private suspend fun assertRefused(storage: Storage, operation: (suspend () -> Boolean)? = null) {
        val preferences = WeshPreferences(storage.prefs)
        val gate = WeshRecoveryGate().apply { requireRecovery() }
        val backend = emptyDraftBackend()
        backend.wipe()
        var resumes = 0
        val resumed = resumeAfterWeshPanicWipe(true, true,
            clearPreferences = operation ?: { preferences.clearForPanic() },
            resume = {
                resumes++
                backend.finishVerifiedWipe()
                gate.normalPanicCompleted()
                preferences.resumeAfterVerifiedPanic()
                true
            })
        assertFalse(resumed)
        assertEquals(0, resumes)
        assertTrue(File(directory.root, "wesh-drafts-wipe-v1").isFile)
        assertTrue(gate.recoveryRequired.value)
        assertFalse(gate.allowsTransport(prepared = true))
    }

    @Test fun `verified global panic clears note disclosure and mute before finishing marker and resuming`() = runTest {
        val storage = Storage()
        val preferences = WeshPreferences(storage.prefs)
        preferences.editMeetingDraft("synthetic unsaved note", preferences.resetEpoch.value)
        val gate = WeshRecoveryGate().apply { requireRecovery() }
        val backend = emptyDraftBackend()
        backend.wipe()
        preferences.fenceForPanic()
        assertEquals("", preferences.meetingDraft.value)
        var resumes = 0
        assertTrue(resumeAfterWeshPanicWipe(true, true,
            clearPreferences = { preferences.clearForPanic() },
            resume = {
                assertTrue(storage.disk.isEmpty())
                assertEquals(1, storage.commits)
                resumes++
                backend.finishVerifiedWipe()
                gate.normalPanicCompleted()
                preferences.resumeAfterVerifiedPanic()
                true
            }))
        assertEquals(1, resumes)
        assertFalse(File(directory.root, "wesh-drafts-wipe-v1").exists())
        assertTrue(gate.allowsTransport(prepared = true))
        storage.simulateRestart()
        val restored = WeshPreferences(storage.prefs)
        assertEquals("", restored.meetingNote)
        assertEquals("", restored.meetingDraft.value)
        assertFalse(restored.publicDisclosureAccepted)
        assertFalse(restored.publicMuted)
    }

    @Test fun `commit false after clear was published in RAM retains marker and recovery latch`() = runTest {
        val storage = Storage().apply { commitSucceeds = false }
        assertRefused(storage)
        assertTrue(storage.memory.isEmpty())
        storage.simulateRestart()
        assertEquals("synthetic saved note", WeshPreferences(storage.prefs).meetingNote)
        assertTrue(WeshPreferences(storage.prefs).publicDisclosureAccepted)
        assertTrue(WeshPreferences(storage.prefs).publicMuted)
    }

    @Test fun `commit exception retains marker and recovery latch even after RAM clear`() = runTest {
        val storage = Storage().apply { throwOnCommit = true }
        assertRefused(storage)
        assertTrue(storage.memory.isEmpty())
        assertEquals("synthetic saved note", storage.disk["meeting_note"])
    }

    @Test fun `clear exception leaves stale values unconfirmed and cannot resume`() = runTest {
        val storage = Storage().apply { throwOnClear = true }
        assertRefused(storage)
        assertEquals(0, storage.commits)
        assertEquals("synthetic saved note", storage.memory["meeting_note"])
    }

    @Test fun `commit reporting success without empty readback cannot resume`() = runTest {
        val storage = Storage().apply { retainOnClear = true }
        assertRefused(storage)
        assertEquals(1, storage.commits)
        assertEquals("synthetic saved note", storage.memory["meeting_note"])
    }

    @Test fun `injected preference wipe exception cannot finish the global panic`() = runTest {
        assertRefused(Storage(), operation = { error("synthetic storage unavailable") })
    }

    @Test fun `confirmed preferences cannot override unconfirmed drafts or conversation wipe`() = runTest {
        for ((drafts, conversations) in listOf(false to true, true to false)) {
            val storage = Storage()
            val preferences = WeshPreferences(storage.prefs)
            var resumes = 0
            assertFalse(resumeAfterWeshPanicWipe(drafts, conversations,
                clearPreferences = { preferences.clearForPanic() }, resume = { resumes++; true }))
            assertEquals(1, storage.commits)
            assertTrue(storage.disk.isEmpty())
            assertEquals(0, resumes)
        }
    }

    @Test fun `callbacks captured before panic cannot restore note after successful resume`() {
        val storage = Storage()
        val preferences = WeshPreferences(storage.prefs)
        val oldEpoch = preferences.resetEpoch.value
        val oldEdit = { preferences.editMeetingDraft("synthetic stale note", oldEpoch) }
        val oldSave = { preferences.saveMeetingDraft(oldEpoch) }
        preferences.fenceForPanic()
        assertTrue(preferences.clearForPanic())
        preferences.resumeAfterVerifiedPanic()
        assertFalse(oldEdit())
        assertFalse(oldSave())
        assertEquals("", preferences.meetingDraft.value)
        assertTrue(storage.memory.isEmpty())
        assertTrue(storage.disk.isEmpty())
        val freshEpoch = preferences.resetEpoch.value
        assertTrue(preferences.editMeetingDraft("synthetic fresh note", freshEpoch))
        assertTrue(preferences.saveMeetingDraft(freshEpoch))
        assertEquals("synthetic fresh note", preferences.meetingNote)
        assertFalse(oldEdit())
        assertFalse(oldSave())
        assertEquals("synthetic fresh note", preferences.meetingDraft.value)
        assertEquals("synthetic fresh note", storage.disk["meeting_note"])
    }

    @Test fun `stale UI callbacks cannot rewrite note disclosure or mute while panic remains fenced`() {
        val storage = Storage()
        val preferences = WeshPreferences(storage.prefs)
        preferences.editMeetingDraft("synthetic unsaved note", preferences.resetEpoch.value)
        preferences.fenceForPanic()
        assertEquals("", preferences.meetingDraft.value)
        assertTrue(preferences.clearForPanic())
        preferences.editMeetingDraft("synthetic stale note", preferences.resetEpoch.value)
        assertFalse(preferences.saveMeetingDraft(preferences.resetEpoch.value))
        preferences.publicMuted = true
        preferences.publicDisclosureAccepted = true
        assertEquals("", preferences.meetingDraft.value)
        assertTrue(storage.memory.isEmpty())
        assertTrue(storage.disk.isEmpty())
        preferences.resumeAfterVerifiedPanic()
        preferences.editMeetingDraft("synthetic fresh note", preferences.resetEpoch.value)
        assertTrue(preferences.saveMeetingDraft(preferences.resetEpoch.value))
        assertEquals("synthetic fresh note", preferences.meetingNote)
    }
}
