package com.bitchat.android.ui

import android.app.Application
import android.content.SharedPreferences
import com.bitchat.android.services.InMemoryConversationStorageCipher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class WeshMeetingNoteMigrationTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun `constructing preferences and reading cached note getters never opens the backend`() = runTest {
        val fixture = fixture()
        val storage = LegacyPreferences()
        var factories = 0
        val preferences = preferences(fixture, storage, factory = { factories++; fixture.backend() })
        assertEquals("", preferences.meetingNote)
        assertEquals("", preferences.savedMeetingNote.value)
        assertEquals("", preferences.meetingDraft.value)
        assertEquals(MeetingNoteState.Unloaded, preferences.meetingNoteState.value)
        assertEquals(0, factories)
        assertFalse(fixture.file.exists())
        assertEquals(NOTE, storage.disk[LEGACY])
        assertTrue(preferences.loadMeetingNote())
        assertEquals(1, factories)
    }

    @Test
    fun `migration authenticates ciphertext before committing plaintext removal`() = runTest {
        val fixture = fixture()
        val storage = LegacyPreferences()
        var removalObserved = false
        val preferences = preferences(fixture, storage, commit = { editor ->
            assertEquals(NOTE, storage.memory[LEGACY])
            assertEquals(NOTE, storage.disk[LEGACY])
            assertEquals(NOTE, fixture.backend().load())
            assertFalse(fixture.file.readBytes().toString(Charsets.UTF_8).contains(NOTE))
            removalObserved = true
            editor.commit()
        })
        assertTrue(preferences.loadMeetingNote())
        assertTrue(removalObserved)
        assertFalse(storage.memory.containsKey(LEGACY))
        assertFalse(storage.disk.containsKey(LEGACY))
        assertEquals(true, storage.disk[UNRELATED])
        assertEquals(NOTE, preferences.meetingNote)
        assertEquals(NOTE, preferences.meetingDraft.value)
        assertEquals(MeetingNoteState.Ready, preferences.meetingNoteState.value)
    }

    @Test
    fun `migration write failure retains legacy bytes and an explicit retry recovers`() = runTest {
        val fixture = fixture()
        val storage = LegacyPreferences()
        var failWrite = true
        val preferences = preferences(fixture, storage, factory = {
            fixture.backend { commit -> if (failWrite) throw IOException("Synthetic write failure") else commit() }
        })
        assertFalse(preferences.loadMeetingNote())
        assertEquals(NOTE, storage.memory[LEGACY])
        assertEquals(NOTE, storage.disk[LEGACY])
        assertEquals(0, storage.commits)
        assertFalse(fixture.file.exists())
        assertEquals(MeetingNoteState.Unavailable, preferences.meetingNoteState.value)
        assertFalse(preferences.editMeetingDraft("Synthetic replacement", preferences.resetEpoch.value))
        failWrite = false
        assertTrue(preferences.loadMeetingNote())
        assertEquals(NOTE, preferences.meetingNote)
        assertFalse(storage.disk.containsKey(LEGACY))
    }

    @Test
    fun `matching copies survive restart including a lost encrypted commit acknowledgement`() = runTest {
        for (lostAcknowledgement in listOf(false, true)) {
            val fixture = fixture()
            val storage = LegacyPreferences()
            if (lostAcknowledgement) {
                val first = preferences(fixture, storage, factory = {
                    fixture.backend { commit -> commit(); throw IOException("Synthetic lost acknowledgement") }
                })
                assertFalse(first.loadMeetingNote())
                assertEquals(0, storage.commits)
            } else {
                fixture.backend().write(NOTE)
            }
            val encrypted = fixture.file.readBytes()
            assertEquals(NOTE, storage.disk[LEGACY])
            storage.restart()
            val restarted = preferences(fixture, storage)
            assertTrue(restarted.loadMeetingNote())
            assertEquals(NOTE, restarted.meetingNote)
            assertFalse(storage.disk.containsKey(LEGACY))
            assertArrayEquals(encrypted, fixture.file.readBytes())
        }
    }

    @Test
    fun `divergent authenticated and plaintext copies are both preserved and saving stays blocked`() = runTest {
        val fixture = fixture()
        val storage = LegacyPreferences()
        val other = "Synthetic different encrypted note"
        fixture.backend().write(other)
        val encrypted = fixture.file.readBytes()
        val preferences = preferences(fixture, storage)
        assertFalse(preferences.loadMeetingNote())
        assertEquals(MeetingNoteState.Unavailable, preferences.meetingNoteState.value)
        assertEquals(NOTE, storage.disk[LEGACY])
        assertEquals(NOTE, storage.memory[LEGACY])
        assertEquals(0, storage.commits)
        assertArrayEquals(encrypted, fixture.file.readBytes())
        assertEquals(other, fixture.backend().load())
        assertFalse(preferences.editMeetingDraft("Synthetic replacement", preferences.resetEpoch.value))
        assertFalse(preferences.saveMeetingDraft(preferences.resetEpoch.value))
    }

    @Test
    fun `failed remove commit changes RAM but retry performs another real disk commit`() = runTest {
        val fixture = fixture()
        val storage = LegacyPreferences().apply { commitSucceeds = false }
        val preferences = preferences(fixture, storage)
        assertFalse(preferences.loadMeetingNote())
        assertFalse(storage.memory.containsKey(LEGACY))
        assertEquals(NOTE, storage.disk[LEGACY])
        assertEquals(NOTE, fixture.backend().load())
        assertEquals(1, storage.commits)
        storage.commitSucceeds = true
        assertTrue(preferences.loadMeetingNote())
        assertEquals(2, storage.commits)
        assertEquals(2, storage.removeCommits)
        assertFalse(storage.disk.containsKey(LEGACY))
        storage.restart()
        assertTrue(preferences(fixture, storage).loadMeetingNote())
        assertFalse(storage.memory.containsKey(LEGACY))
    }

    @Test
    fun `corrupted migration readback never authorizes removing the legacy note`() = runTest {
        val fixture = fixture()
        val storage = LegacyPreferences()
        val preferences = preferences(fixture, storage, factory = {
            val real = fixture.backend()
            object : WeshMeetingNoteBackend by real {
                override suspend fun write(text: String) {
                    real.write(text)
                    val bytes = fixture.file.readBytes()
                    bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
                    fixture.file.writeBytes(bytes)
                }
            }
        })
        assertFalse(preferences.loadMeetingNote())
        val damaged = fixture.file.readBytes()
        assertEquals(NOTE, storage.disk[LEGACY])
        assertEquals(0, storage.commits)
        assertFalse(preferences.loadMeetingNote())
        assertArrayEquals(damaged, fixture.file.readBytes())
        assertEquals(NOTE, storage.memory[LEGACY])
    }

    @Test
    fun `a real load held across the panic fence cannot publish old note text`() = runTest {
        val fixture = fixture()
        fixture.backend().write(NOTE)
        val storage = LegacyPreferences()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var holdFirst = true
        val preferences = preferences(fixture, storage, factory = {
            val real = fixture.backend()
            object : WeshMeetingNoteBackend by real {
                override suspend fun load(): String? {
                    val loaded = real.load()
                    if (holdFirst) { holdFirst = false; entered.complete(Unit); release.await() }
                    return loaded
                }
            }
        })
        val loading = async { preferences.loadMeetingNote() }
        try {
            entered.await()
            preferences.fenceForPanic()
            release.complete(Unit)
            assertFalse(loading.await())
            assertEquals("", preferences.meetingDraft.value)
            assertEquals("", preferences.meetingNote)
            assertEquals(MeetingNoteState.Fenced, preferences.meetingNoteState.value)
            assertFalse(preferences.loadMeetingNote())
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun `a cancelled startup load returns to Unloaded and a second load can recover`() = runTest {
        val fixture = fixture()
        val storage = LegacyPreferences()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var first = true
        var factories = 0
        val preferences = preferences(fixture, storage, factory = { factories++; fixture.backend() }, before = {
            if (first) { first = false; entered.complete(Unit); release.await() }
        })
        val loading = async { preferences.loadMeetingNote() }
        try {
            entered.await()
            loading.cancelAndJoin()
            assertTrue(loading.isCancelled)
            assertEquals(MeetingNoteState.Unloaded, preferences.meetingNoteState.value)
            assertEquals(0, factories)
            assertEquals(NOTE, storage.disk[LEGACY])
            assertTrue(preferences.loadMeetingNote())
            assertEquals(MeetingNoteState.Ready, preferences.meetingNoteState.value)
            assertEquals(NOTE, preferences.meetingNote)
            assertFalse(storage.disk.containsKey(LEGACY))
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun `cancelling an older same epoch load cannot demote a newer completed Ready state`() = runTest {
        val fixture = fixture()
        val storage = LegacyPreferences()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val preferences = preferences(fixture, storage, before = {
            if (++calls == 1) { entered.complete(Unit); release.await() }
        })
        val older = async { preferences.loadMeetingNote() }
        try {
            entered.await()
            assertTrue(preferences.loadMeetingNote())
            assertEquals(MeetingNoteState.Ready, preferences.meetingNoteState.value)
            older.cancelAndJoin()
            assertTrue(older.isCancelled)
            assertEquals(MeetingNoteState.Ready, preferences.meetingNoteState.value)
            assertEquals(NOTE, preferences.meetingNote)
            assertEquals(NOTE, preferences.meetingDraft.value)
            assertFalse(storage.disk.containsKey(LEGACY))
        } finally {
            release.complete(Unit)
        }
    }

    private fun TestScope.preferences(
        fixture: Fixture,
        storage: LegacyPreferences,
        factory: () -> WeshMeetingNoteBackend = { fixture.backend() },
        commit: suspend (SharedPreferences.Editor) -> Boolean = { it.commit() },
        before: suspend () -> Unit = {}
    ) = WeshPreferences(
        storage.prefs, StandardTestDispatcher(testScheduler), commitEditor = commit,
        meetingBackendFactory = factory, beforeNoteLoad = before
    )

    private fun fixture() = Fixture(File(directory.root, UUID.randomUUID().toString()).apply { check(mkdir()) })

    private class Fixture(val root: File) {
        val cipher = InMemoryConversationStorageCipher()
        val file = File(root, EncryptedWeshMeetingNoteBackend.FILE_NAME)
        fun backend(commit: (() -> Unit) -> Unit = { it() }) =
            EncryptedWeshMeetingNoteBackend(root, cipher, commitData = commit)
    }

    /** Models commit(false) publishing editor mutations to RAM without changing disk. */
    private class LegacyPreferences {
        val memory = mutableMapOf<String, Any>(LEGACY to NOTE, UNRELATED to true)
        val disk = memory.toMutableMap()
        val prefs: SharedPreferences = mock()
        var commitSucceeds = true
        var commits = 0
            private set
        var removeCommits = 0
            private set

        init {
            whenever(prefs.all).thenAnswer { memory.toMap() }
            whenever(prefs.contains(any())).thenAnswer { memory.containsKey(it.getArgument<String>(0)) }
            whenever(prefs.getString(any(), anyOrNull())).thenAnswer {
                memory[it.getArgument<String>(0)] as? String ?: it.getArgument<String?>(1)
            }
            whenever(prefs.getBoolean(any(), any())).thenAnswer {
                memory[it.getArgument<String>(0)] as? Boolean ?: it.getArgument<Boolean>(1)
            }
            whenever(prefs.edit()).thenAnswer { editor() }
        }

        fun restart() { memory.clear(); memory.putAll(disk) }

        private fun editor(): SharedPreferences.Editor {
            val editor: SharedPreferences.Editor = mock()
            val removed = mutableSetOf<String>()
            whenever(editor.remove(any())).thenAnswer { removed += it.getArgument<String>(0); editor }
            fun publish() {
                removed.forEach(memory::remove)
            }
            whenever(editor.commit()).thenAnswer {
                commits++
                if (LEGACY in removed) removeCommits++
                publish()
                if (commitSucceeds) { disk.clear(); disk.putAll(memory) }
                commitSucceeds
            }
            doAnswer { publish(); disk.clear(); disk.putAll(memory); null }.whenever(editor).apply()
            return editor
        }
    }

    private companion object {
        const val LEGACY = "meeting_note"
        const val UNRELATED = "synthetic_unrelated_preference"
        const val NOTE = "Synthetic personal rendez-vous — café at 18 h"
    }
}
