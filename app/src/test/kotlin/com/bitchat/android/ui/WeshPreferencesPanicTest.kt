package com.bitchat.android.ui

import android.content.SharedPreferences
import com.bitchat.android.features.file.FileUtils
import com.bitchat.android.services.ConversationStorageCipher
import com.bitchat.android.startup.WeshRecoveryGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
@OptIn(ExperimentalCoroutinesApi::class)
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
        var readbackOverride: String? = null
        val committedNotes = mutableListOf<String?>()

        init {
            whenever(prefs.getString(eq("meeting_note"), anyOrNull())).thenAnswer { readbackOverride ?: memory["meeting_note"] as String? ?: "" }
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
                committedNotes += memory["meeting_note"] as String?
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

    private fun TestScope.newPreferences(storage: Storage) =
        WeshPreferences(storage.prefs, StandardTestDispatcher(testScheduler))

    private suspend fun TestScope.assertRefused(storage: Storage, operation: (suspend () -> Boolean)? = null) {
        val preferences = newPreferences(storage)
        val gate = WeshRecoveryGate().apply { requireRecovery() }
        val backend = emptyDraftBackend()
        backend.wipe()
        var resumes = 0
        val resumed = resumeAfterWeshPanicWipe(true, true, mediaCleared = true,
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
        val preferences = newPreferences(storage)
        preferences.editMeetingDraft("synthetic unsaved note", preferences.resetEpoch.value)
        val gate = WeshRecoveryGate().apply { requireRecovery() }
        val backend = emptyDraftBackend()
        backend.wipe()
        preferences.fenceForPanic()
        assertEquals("", preferences.meetingDraft.value)
        var resumes = 0
        assertTrue(resumeAfterWeshPanicWipe(true, true, mediaCleared = true,
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
        val restored = newPreferences(storage)
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
        assertEquals("synthetic saved note", newPreferences(storage).meetingNote)
        assertTrue(newPreferences(storage).publicDisclosureAccepted)
        assertTrue(newPreferences(storage).publicMuted)
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
            val preferences = newPreferences(storage)
            var resumes = 0
            assertFalse(resumeAfterWeshPanicWipe(drafts, conversations, mediaCleared = true,
                clearPreferences = { preferences.clearForPanic() }, resume = { resumes++; true }))
            assertEquals(1, storage.commits)
            assertTrue(storage.disk.isEmpty())
            assertEquals(0, resumes)
        }
    }

    @Test fun `unconfirmed plaintext media keeps marker and recovery gate until verified cleanup succeeds`() = runTest {
        val storage = Storage()
        val preferences = newPreferences(storage)
        val gate = WeshRecoveryGate().apply { requireRecovery() }
        val backend = emptyDraftBackend()
        backend.wipe()
        val files = directory.newFolder("synthetic-media-files")
        val cache = directory.newFolder("synthetic-media-cache")
        val blocked = File(files, "files/incoming").apply { mkdirs() }
        val attachment = File(blocked, "synthetic.bin").apply { writeText("synthetic attachment bytes") }
        val firstCleanup = FileUtils.clearAllMedia({ files }, { cache }, deleteTree = { target ->
            if (target == blocked) false else target.deleteRecursively()
        })
        var resumes = 0
        val resume: suspend () -> Boolean = {
            resumes++
            backend.finishVerifiedWipe()
            gate.normalPanicCompleted()
            preferences.resumeAfterVerifiedPanic()
            true
        }
        assertFalse(firstCleanup)
        assertFalse(resumeAfterWeshPanicWipe(true, true, mediaCleared = firstCleanup,
            clearPreferences = { preferences.clearForPanic() }, resume = resume))
        assertEquals(0, resumes)
        assertTrue(File(directory.root, "wesh-drafts-wipe-v1").isFile)
        assertFalse(gate.allowsTransport(prepared = true))
        assertEquals("synthetic attachment bytes", attachment.readText())
        assertTrue(storage.disk.isEmpty())
        val verifiedCleanup = FileUtils.clearAllMedia({ files }, { cache })
        assertTrue(verifiedCleanup)
        assertTrue(resumeAfterWeshPanicWipe(true, true, mediaCleared = verifiedCleanup,
            clearPreferences = { preferences.clearForPanic() }, resume = resume))
        assertEquals(1, resumes)
        assertFalse(File(directory.root, "wesh-drafts-wipe-v1").exists())
        assertTrue(gate.allowsTransport(prepared = true))
        assertFalse(attachment.exists())
    }

    @Test fun `callbacks captured before panic cannot restore note after successful resume`() = runTest {
        val storage = Storage()
        val preferences = newPreferences(storage)
        val oldEpoch = preferences.resetEpoch.value
        val oldEdit = { preferences.editMeetingDraft("synthetic stale note", oldEpoch) }
        val oldSave: suspend () -> Boolean = { preferences.saveMeetingDraft(oldEpoch) }
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

    @Test fun `failed note commit changed in RAM still requires a new successful commit on retry`() = runTest {
        val storage = Storage().apply { commitSucceeds = false }
        val preferences = newPreferences(storage)
        val epoch = preferences.resetEpoch.value
        assertTrue(preferences.editMeetingDraft("synthetic edited note", epoch))
        assertFalse(preferences.saveMeetingDraft(epoch))
        assertEquals("synthetic edited note", preferences.meetingDraft.value)
        assertEquals("synthetic edited note", storage.memory["meeting_note"])
        assertEquals("synthetic saved note", storage.disk["meeting_note"])
        assertFalse(preferences.saveMeetingDraft(epoch))
        assertEquals(2, storage.commits)
        storage.commitSucceeds = true
        assertTrue(preferences.saveMeetingDraft(epoch))
        assertEquals(3, storage.commits)
        storage.simulateRestart()
        assertEquals("synthetic edited note", newPreferences(storage).meetingNote)
    }

    @Test fun `note commit exception retains the edited draft and permits an explicit retry`() = runTest {
        val storage = Storage().apply { throwOnCommit = true }
        val preferences = newPreferences(storage)
        val epoch = preferences.resetEpoch.value
        preferences.editMeetingDraft("synthetic edited note", epoch)
        assertFalse(preferences.saveMeetingDraft(epoch))
        assertEquals("synthetic edited note", preferences.meetingDraft.value)
        assertEquals("synthetic saved note", storage.disk["meeting_note"])
        storage.throwOnCommit = false
        assertTrue(preferences.saveMeetingDraft(epoch))
        assertEquals(2, storage.commits)
        assertEquals("synthetic edited note", storage.disk["meeting_note"])
    }

    @Test fun `note commit success without exact readback remains unconfirmed until retry`() = runTest {
        val storage = Storage()
        val preferences = newPreferences(storage)
        val epoch = preferences.resetEpoch.value
        preferences.editMeetingDraft("synthetic edited note", epoch)
        storage.readbackOverride = "synthetic divergent note"
        assertFalse(preferences.saveMeetingDraft(epoch))
        assertEquals("synthetic edited note", preferences.meetingDraft.value)
        storage.readbackOverride = null
        assertTrue(preferences.saveMeetingDraft(epoch))
        assertEquals(2, storage.commits)
    }

    @Test fun `serialized saves preserve newer edits and do not confirm an older draft`() = runTest {
        val storage = Storage()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var active = 0
        var peakActive = 0
        var attempts = 0
        val preferences = WeshPreferences(storage.prefs, StandardTestDispatcher(testScheduler), commitEditor = { editor ->
            active++
            peakActive = maxOf(active, peakActive)
            try {
                if (attempts++ == 0) { entered.complete(Unit); release.await() }
                editor.commit()
            } finally { active-- }
        })
        val epoch = preferences.resetEpoch.value
        preferences.editMeetingDraft("synthetic first note", epoch)
        val first = async { preferences.saveMeetingDraft(epoch) }
        entered.await()
        preferences.editMeetingDraft("synthetic newer note", epoch)
        val second = async(start = CoroutineStart.UNDISPATCHED) { preferences.saveMeetingDraft(epoch) }
        runCurrent()
        assertFalse(second.isCompleted)
        release.complete(Unit)
        assertFalse(first.await())
        assertTrue(second.await())
        assertEquals(1, peakActive)
        assertEquals(listOf("synthetic first note", "synthetic newer note"), storage.committedNotes)
        assertEquals("synthetic newer note", preferences.meetingDraft.value)
        assertEquals("synthetic newer note", storage.disk["meeting_note"])
    }

    @Test fun `panic fences immediately while commit is suspended then clears before queued old writes`() = runTest {
        val storage = Storage()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var attempts = 0
        val preferences = WeshPreferences(storage.prefs, StandardTestDispatcher(testScheduler), commitEditor = { editor ->
            if (attempts++ == 0) { entered.complete(Unit); release.await() }
            editor.commit()
        })
        val epoch = preferences.resetEpoch.value
        preferences.editMeetingDraft("synthetic in flight", epoch)
        val first = async { preferences.saveMeetingDraft(epoch) }
        entered.await()
        preferences.editMeetingDraft("synthetic queued", epoch)
        val queued = async(start = CoroutineStart.UNDISPATCHED) { preferences.saveMeetingDraft(epoch) }
        runCurrent()
        preferences.fenceForPanic()
        assertEquals("", preferences.meetingDraft.value)
        assertFalse(preferences.editMeetingDraft("synthetic stale callback", epoch))
        val clear = async { preferences.clearForPanic() }
        runCurrent()
        assertFalse(clear.isCompleted)
        assertEquals(0, storage.commits)
        release.complete(Unit)
        assertFalse(first.await())
        assertFalse(queued.await())
        assertTrue(clear.await())
        assertEquals(listOf("synthetic in flight", null), storage.committedNotes)
        assertTrue(storage.memory.isEmpty())
        assertTrue(storage.disk.isEmpty())
        preferences.resumeAfterVerifiedPanic()
        assertFalse(preferences.saveMeetingDraft(epoch))
        assertEquals(2, storage.commits)
    }

    @Test fun `panic before IO dispatch refuses a captured save without writing its text`() = runTest {
        val storage = Storage()
        val preferences = newPreferences(storage)
        val epoch = preferences.resetEpoch.value
        preferences.editMeetingDraft("synthetic queued", epoch)
        val save = async(start = CoroutineStart.UNDISPATCHED) { preferences.saveMeetingDraft(epoch) }
        preferences.fenceForPanic()
        assertFalse(save.await())
        assertEquals(0, storage.commits)
        assertTrue(preferences.clearForPanic())
        assertEquals(listOf<String?>(null), storage.committedNotes)
        assertTrue(storage.disk.isEmpty())
    }

    @Test fun `invalidation after IO commit but before caller resumes cannot report note saved`() = runTest {
        val storage = Storage()
        val caller = StandardTestDispatcher(testScheduler, name = "synthetic-caller")
        val io = StandardTestDispatcher(testScheduler, name = "synthetic-io")
        lateinit var preferences: WeshPreferences
        preferences = WeshPreferences(storage.prefs, io, commitEditor = { editor ->
            val committed = editor.commit()
            launch(caller) { preferences.fenceForPanic() }
            committed
        })
        val epoch = preferences.resetEpoch.value
        preferences.editMeetingDraft("synthetic edited note", epoch)
        val save = async(caller) { preferences.saveMeetingDraft(epoch) }
        advanceUntilIdle()
        assertFalse(save.await())
        assertEquals(1, storage.commits)
        assertEquals("synthetic edited note", storage.disk["meeting_note"])
        assertEquals("", preferences.meetingDraft.value)
    }

    @Test fun `stale UI callbacks cannot rewrite note disclosure or mute while panic remains fenced`() = runTest {
        val storage = Storage()
        val preferences = newPreferences(storage)
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
