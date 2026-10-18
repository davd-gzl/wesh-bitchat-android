package com.bitchat.android.ui

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.ConversationListPreferences
import com.bitchat.android.services.ConversationRepository
import com.bitchat.android.services.ConversationStorageCipher
import com.bitchat.android.services.InMemoryConversationStorageCipher
import com.bitchat.android.startup.WeshRecoveryGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Date
import java.util.UUID
import java.util.concurrent.Executors

/** Exercises the composed storage boundaries without constructing transport or live Keystore services. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35])
class PanicStorageCompositionTest {
    @get:Rule val directory = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var notePreferencesName: String
    private lateinit var securePreferencesName: String
    private lateinit var repository: ConversationRepository
    private lateinit var preferences: WeshPreferences
    private lateinit var organization: ConversationListPreferences
    private lateinit var dataManager: DataManager
    private lateinit var noteRoot: File
    private lateinit var draftRoot: File
    private lateinit var marker: EncryptedWeshDraftBackend
    private val writer = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val noteCipher = InMemoryConversationStorageCipher()
    private val metadata = MetadataStorage()
    private val recovery = WeshRecoveryGate()
    private var resumes = 0

    @Before fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "synthetic-composed-history-${UUID.randomUUID()}.db"
        notePreferencesName = "synthetic-composed-note-${UUID.randomUUID()}"
        securePreferencesName = "synthetic-composed-organization-${UUID.randomUUID()}"
        ContactDirectory.initialize(context) { null }
        repository = ConversationRepository(context, writer, databaseName, InMemoryConversationStorageCipher())
        AppStateStore.setConversationRepositoryForTest(repository)
        AppStateStore.resumePrivateConversationsAfterPanic()
        AppStateStore.clear()
        noteRoot = directory.newFolder("note")
        draftRoot = directory.newFolder("draft")
        val notePrefs = context.getSharedPreferences(notePreferencesName, Context.MODE_PRIVATE)
        assertTrue(notePrefs.edit().putString("meeting_note", OLD_NOTE).commit())
        preferences = WeshPreferences(notePrefs, Dispatchers.IO,
            meetingBackendFactory = { EncryptedWeshMeetingNoteBackend(noteRoot, noteCipher) })
        assertTrue(preferences.loadMeetingNote())
        organization = ConversationListPreferences(
            SecureIdentityStateManager(context.getSharedPreferences(securePreferencesName, Context.MODE_PRIVATE),
                testOnly = true), testOnly = true, canonicalize = { it }
        )
        organization.togglePinned(CONTACT)
        organization.toggleMuted(CONTACT)
        organization.setDraft(CONTACT, "Synthetic old composer draft")
        val dataContext: Context = mock()
        whenever(dataContext.getSharedPreferences(eq("bitchat_prefs"), eq(Context.MODE_PRIVATE)))
            .thenReturn(metadata.prefs)
        dataManager = DataManager(dataContext)
        marker = EncryptedWeshDraftBackend(draftRoot, object : ConversationStorageCipher {
            override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray = error("No composer data")
            override fun decrypt(envelope: ByteArray, associatedData: ByteArray): ByteArray = error("No composer data")
            override fun destroyKey() = Unit
        })
    }

    @After fun tearDown() {
        runBlocking { repository.awaitPendingWrites() }
        AppStateStore.setConversationRepositoryForTest(null)
        AppStateStore.resumePrivateConversationsAfterPanic()
        AppStateStore.clear()
        repository.closeForTest()
        writer.close()
        context.deleteDatabase(databaseName)
        context.getSharedPreferences(notePreferencesName, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(securePreferencesName, Context.MODE_PRIVATE).edit().clear().commit()
        ContactDirectory.initialize(context) { null }
    }

    @Test fun `successful note and history erase cannot release recovery after uncertain metadata clear`() = runBlocking {
        assertTrue(AppStateStore.addPrivateMessageDurably(CONTACT, message("old-history")))
        metadata.commitSucceeds = false

        assertFalse(panic())

        assertFalse(File(noteRoot, EncryptedWeshMeetingNoteBackend.FILE_NAME).exists())
        assertTrue(context.getSharedPreferences(notePreferencesName, Context.MODE_PRIVATE).all.isEmpty())
        assertTrue(requireNotNull(repository.loadConversationAndWait(CONTACT)).chats.isEmpty())
        assertEquals("Synthetic old nickname", metadata.disk["nickname"])
        assertTrue(organization.pinned.value.isEmpty())
        assertTrue(organization.muted.value.isEmpty())
        assertTrue(organization.drafts.value.isEmpty())
        assertEquals(0, resumes)
        assertTrue(File(draftRoot, "wesh-drafts-wipe-v1").isFile)
        assertTrue(recovery.recoveryRequired.value)
        assertFalse(recovery.allowsTransport(prepared = true))
        assertEquals(MeetingNoteState.Fenced, preferences.meetingNoteState.value)
        assertFalse(AppStateStore.addPrivateMessageDurably(CONTACT, message("blocked-history")))
    }

    @Test fun `verified global retry permits fresh storage but rejects old undo and note callbacks`() = runBlocking {
        assertTrue(AppStateStore.addPrivateMessageDurably(CONTACT, message("deleted-history")))
        val undo = requireNotNull(AppStateStore.deletePrivateConversationAndWait(CONTACT))
        assertTrue(AppStateStore.addPrivateMessageDurably(CONTACT, message("retained-history")))
        val oldEpoch = preferences.resetEpoch.value
        val staleEdit = { preferences.editMeetingDraft(OLD_NOTE, oldEpoch) }
        val staleSave: suspend () -> Boolean = { preferences.saveMeetingDraft(oldEpoch) }
        metadata.commitSucceeds = false
        assertFalse(panic())
        assertFalse(AppStateStore.restoreDeletedConversation(undo))

        metadata.commitSucceeds = true
        assertTrue(panic())
        assertEquals(1, resumes)
        assertFalse(File(draftRoot, "wesh-drafts-wipe-v1").exists())
        assertTrue(recovery.allowsTransport(prepared = true))
        assertEquals(mapOf("nickname" to "anon1234"), metadata.disk)
        assertTrue(preferences.loadMeetingNote())
        assertEquals("", preferences.meetingNote)
        val freshEpoch = preferences.resetEpoch.value
        assertTrue(preferences.editMeetingDraft(FRESH_NOTE, freshEpoch))
        assertTrue(preferences.saveMeetingDraft(freshEpoch))
        assertTrue(AppStateStore.addPrivateMessageDurably(CONTACT, message("fresh-history")))

        assertFalse(AppStateStore.restoreDeletedConversation(undo))
        assertFalse(staleEdit())
        assertFalse(staleSave())
        assertEquals(listOf("fresh-history"),
            repository.loadConversationAndWait(CONTACT)?.chats?.get(CONTACT)?.map { it.id })
        assertEquals(FRESH_NOTE, EncryptedWeshMeetingNoteBackend(noteRoot, noteCipher).load())
        assertEquals(FRESH_NOTE, preferences.meetingNote)
        assertEquals(FRESH_NOTE, preferences.meetingDraft.value)
        assertFalse(organization.isPinned(CONTACT))
        assertFalse(organization.isMuted(CONTACT))
        assertNull(organization.draftFor(CONTACT))
    }

    private suspend fun panic(): Boolean {
        recovery.requireRecovery()
        preferences.fenceForPanic()
        marker.wipe()
        val historyCleared = AppStateStore.panicClearPrivateConversations()
        val metadataCleared = clearPanicMetadata(dataManager, organization)
        val nicknameSaved = savePanicNickname(dataManager, "anon1234")
        val resumed = resumeAfterWeshPanicWipe(
            seenCleared = true,
            draftsCleared = true, conversationsCleared = historyCleared, mediaCleared = true,
            metadataCleared = metadataCleared && nicknameSaved,
            clearPreferences = { preferences.clearForPanic() },
            resume = {
                resumes++
                marker.finishVerifiedWipe()
                recovery.normalPanicCompleted()
                AppStateStore.resumePrivateConversationsAfterPanic()
                true
            },
            identityErasureConfirmed = true,
            meshOwnerRetired = true
        )
        if (resumed) preferences.resumeAfterVerifiedPanic()
        return resumed
    }

    private fun message(id: String) = BitchatMessage(id = id, sender = "Synthetic sender",
        content = "Synthetic history content", timestamp = Date(100), isPrivate = true, senderPeerID = CONTACT)

    /** Models a disk failure that nevertheless publishes the editor transaction in RAM. */
    private class MetadataStorage {
        private val memory = mutableMapOf<String, Any>("nickname" to "Synthetic old nickname")
        val disk = memory.toMutableMap()
        val prefs: SharedPreferences = mock()
        var commitSucceeds = true

        init {
            whenever(prefs.all).thenAnswer { memory.toMap() }
            whenever(prefs.getString(any(), anyOrNull())).thenAnswer {
                memory[it.getArgument<String>(0)] as? String ?: it.getArgument<String?>(1)
            }
            whenever(prefs.edit()).thenAnswer {
                val editor: SharedPreferences.Editor = mock()
                val puts = mutableMapOf<String, String>()
                var clear = false
                whenever(editor.clear()).thenAnswer { clear = true; editor }
                whenever(editor.putString(any(), anyOrNull())).thenAnswer {
                    puts[it.getArgument(0)] = it.getArgument(1); editor
                }
                whenever(editor.commit()).thenAnswer {
                    if (clear) memory.clear()
                    memory.putAll(puts)
                    if (commitSucceeds) { disk.clear(); disk.putAll(memory) }
                    commitSucceeds
                }
                editor
            }
        }
    }

    private companion object {
        const val CONTACT = "synthetic-composed-contact"
        const val OLD_NOTE = "Synthetic previous personal note"
        const val FRESH_NOTE = "Synthetic fresh personal note"
    }
}
