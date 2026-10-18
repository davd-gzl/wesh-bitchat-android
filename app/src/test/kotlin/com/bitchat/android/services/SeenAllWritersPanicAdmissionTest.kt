package com.bitchat.android.services

import android.app.Application
import android.content.SharedPreferences
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.ui.clearSeenForPanic
import com.google.gson.Gson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual Seen mutations over synthetic preferences, without a Context, transport or key provider. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35])
class SeenAllWritersPanicAdmissionTest {
    @Before fun setUp() {
        AppStateStore.setConversationRepositoryForTest(null)
        AppStateStore.clear()
        AppStateStore.resumePrivateConversationsAfterPanic()
    }

    @After fun tearDown() {
        AppStateStore.setConversationRepositoryForTest(null)
        AppStateStore.clear()
        AppStateStore.resumePrivateConversationsAfterPanic()
    }

    @Test fun `held delivered write cannot recreate state after checked clear and resume`() = runTest {
        heldOldWrite(Mutation.DELIVERED)
    }

    @Test fun `held local read cannot recreate state or mark AppState read after checked clear and resume`() = runTest {
        heldOldWrite(Mutation.LOCAL_READ)
    }

    @Test fun `held remove cannot delete a fresh epoch record with the same message ID`() = runTest {
        heldOldWrite(Mutation.REMOVE)
    }

    @Test fun `fenced delivered write leaves RAM and persistence unchanged`() {
        refusedWrite(Mutation.DELIVERED, TokenKind.FENCED)
    }

    @Test fun `fenced local read leaves RAM persistence and read side effects unchanged`() {
        refusedWrite(Mutation.LOCAL_READ, TokenKind.FENCED)
    }

    @Test fun `fenced remove leaves every set and persistence unchanged`() {
        refusedWrite(Mutation.REMOVE, TokenKind.FENCED)
    }

    @Test fun `null delivered token cannot write`() { refusedWrite(Mutation.DELIVERED, TokenKind.NULL) }
    @Test fun `null local read token cannot write`() { refusedWrite(Mutation.LOCAL_READ, TokenKind.NULL) }
    @Test fun `null remove token cannot write`() { refusedWrite(Mutation.REMOVE, TokenKind.NULL) }
    @Test fun `foreign delivered token cannot write`() { refusedWrite(Mutation.DELIVERED, TokenKind.FOREIGN) }
    @Test fun `foreign local read token cannot write`() { refusedWrite(Mutation.LOCAL_READ, TokenKind.FOREIGN) }
    @Test fun `foreign remove token cannot write`() { refusedWrite(Mutation.REMOVE, TokenKind.FOREIGN) }

    @Test fun `fresh delivered token preserves ordinary persistence`() { freshWrite(Mutation.DELIVERED) }
    @Test fun `fresh local read token preserves ordinary persistence and AppState read`() { freshWrite(Mutation.LOCAL_READ) }
    @Test fun `fresh remove token removes all Seen sets for that ID`() { freshWrite(Mutation.REMOVE) }

    @Test fun `old receipt completion stays refused while a fresh receipt completion persists`() = runTest {
        val fixture = Fixture()
        val old = fixture.admission.capture()
        fixture.clearAndResume()
        val before = fixture.snapshot()
        assertFalse(fixture.seen.markReadReceiptSent(OLD_ID, old))
        assertEquals(before, fixture.snapshot())
        assertTrue(fixture.seen.markReadReceiptSent(FRESH_ID, fixture.admission.capture()))
        assertTrue(fixture.seen.hasReadReceiptBeenSent(FRESH_ID))
        assertEquals(expectedMap(receipts = listOf(FRESH_ID)), fixture.storage.disk)
        assertEquals(before.writes + 1, fixture.storage.writes)
    }

    private suspend fun TestScope.heldOldWrite(mutation: Mutation) {
        val fixture = Fixture()
        fixture.seedAll(OLD_ID)
        val old = fixture.admission.capture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val pending = launch {
            entered.complete(Unit)
            release.await()
            mutation.apply(fixture, OLD_ID, old)
        }
        try {
            runCurrent()
            assertTrue("the old write is held at its explicit completion barrier", entered.isCompleted)
            fixture.clearAndResume()
            // Reusing OLD_ID is intentional: an old remove must not erase the new owner's record.
            fixture.seedAll(if (mutation == Mutation.REMOVE) OLD_ID else FRESH_ID)
            val before = fixture.snapshot()
            release.complete(Unit)
            withTimeout(1_000) { pending.join() }
            assertEquals("old completion must not mutate RAM, JSON, write count or AppState read state", before, fixture.snapshot())
        } finally {
            release.complete(Unit)
            pending.cancelAndJoin()
        }
    }

    private fun refusedWrite(mutation: Mutation, tokenKind: TokenKind) {
        val fixture = Fixture()
        fixture.seedAll(FRESH_ID)
        // REMOVE needs an existing target; the other two must not introduce an unseen ID.
        val target = if (mutation == Mutation.REMOVE) FRESH_ID else OLD_ID
        val token = when (tokenKind) {
            TokenKind.FENCED -> fixture.admission.capture().also { fixture.admission.fenceForPanic() }
            TokenKind.NULL -> null
            TokenKind.FOREIGN -> SeenReceiptAdmission().capture()
        }
        val before = fixture.snapshot()
        mutation.apply(fixture, target, token)
        assertEquals("refused mutation must have no persistence or read side effects", before, fixture.snapshot())
    }

    private fun freshWrite(mutation: Mutation) {
        val fixture = Fixture()
        if (mutation == Mutation.REMOVE) fixture.seedAll(FRESH_ID)
        val beforeWrites = fixture.storage.writes
        mutation.apply(fixture, FRESH_ID, fixture.admission.capture())
        val delivered = if (mutation == Mutation.DELIVERED) listOf(FRESH_ID) else emptyList()
        val locallyRead = if (mutation == Mutation.LOCAL_READ) listOf(FRESH_ID) else emptyList()
        assertEquals(mutation == Mutation.DELIVERED, fixture.seen.hasDelivered(FRESH_ID))
        assertEquals(mutation == Mutation.LOCAL_READ, fixture.seen.hasBeenReadLocally(FRESH_ID))
        assertFalse(fixture.seen.hasReadReceiptBeenSent(FRESH_ID))
        // Seen removal has never removed AppState's separate read projection.
        assertEquals(mutation != Mutation.DELIVERED, AppStateStore.isPrivateMessageRead(FRESH_ID))
        assertEquals(expectedMap(delivered, locallyRead), fixture.storage.memory)
        assertEquals(expectedMap(delivered, locallyRead), fixture.storage.disk)
        assertEquals(beforeWrites + 1, fixture.storage.writes)
    }

    private enum class TokenKind { FENCED, NULL, FOREIGN }

    private enum class Mutation {
        DELIVERED, LOCAL_READ, REMOVE;

        fun apply(fixture: Fixture, id: String, token: SeenReceiptAdmission.Token?) {
            when (this) {
                DELIVERED -> fixture.seen.markDelivered(id, token)
                LOCAL_READ -> fixture.seen.markReadLocally(id, token)
                REMOVE -> fixture.seen.remove(setOf(id), token)
            }
        }
    }

    private class Fixture {
        val storage = Storage()
        val admission = SeenReceiptAdmission()
        val seen = SeenMessageStore(SecureIdentityStateManager(storage.prefs, testOnly = true), admission, testOnly = true)

        fun seedAll(id: String) {
            val token = admission.capture()
            seen.markDelivered(id, token)
            seen.markReadLocally(id, token)
            assertTrue(seen.markReadReceiptSent(id, token))
        }

        suspend fun clearAndResume() {
            val panic = admission.fenceForPanic()
            assertTrue(clearSeenForPanic(panic) { seen })
            assertTrue(AppStateStore.panicClearPrivateConversations())
            assertTrue(admission.resumeAfterVerifiedPanic(panic))
            AppStateStore.resumePrivateConversationsAfterPanic()
        }

        fun snapshot() = Snapshot(
            listOf(OLD_ID, FRESH_ID).associateWith {
                listOf(seen.hasDelivered(it), seen.hasBeenReadLocally(it), seen.hasReadReceiptBeenSent(it))
            },
            storage.memory.toMap(), storage.disk.toMap(), storage.writes,
            AppStateStore.readPrivateMessageIDs.value.toSet()
        )
    }

    private data class Snapshot(
        val sets: Map<String, List<Boolean>>,
        val memory: Map<String, String>,
        val disk: Map<String, String>,
        val writes: Int,
        val appStateReadIDs: Set<String>
    )

    /** Only in-memory maps exist; apply/commit publish to both maps synchronously for assertions. */
    private class Storage {
        val memory = mutableMapOf(NEIGHBOR to NEIGHBOR_VALUE)
        val disk = memory.toMutableMap()
        var writes = 0
        val prefs: SharedPreferences = mock()

        init {
            whenever(prefs.getString(any(), anyOrNull())).thenAnswer {
                memory[it.getArgument<String>(0)] ?: it.getArgument<String?>(1)
            }
            whenever(prefs.contains(any())).thenAnswer { memory.containsKey(it.getArgument<String>(0)) }
            whenever(prefs.all).thenAnswer { memory.toMap() }
            whenever(prefs.edit()).thenAnswer {
                val editor: SharedPreferences.Editor = mock()
                val puts = mutableMapOf<String, String>()
                val removes = mutableSetOf<String>()
                whenever(editor.putString(any(), anyOrNull())).thenAnswer {
                    val key = it.getArgument<String>(0)
                    val value = it.getArgument<String?>(1)
                    if (value == null) removes += key else puts[key] = value
                    editor
                }
                whenever(editor.remove(any())).thenAnswer { removes += it.getArgument<String>(0); editor }
                fun publish() {
                    writes++
                    removes.forEach(memory::remove)
                    memory.putAll(puts)
                    disk.clear()
                    disk.putAll(memory)
                }
                whenever(editor.commit()).thenAnswer { publish(); true }
                doAnswer { publish(); null }.whenever(editor).apply()
                editor
            }
        }
    }

    private companion object {
        const val OLD_ID = "synthetic-old-message"
        const val FRESH_ID = "synthetic-fresh-message"
        const val SEEN_KEY = "seen_message_store_v1"
        const val NEIGHBOR = "synthetic_unrelated_identity_value"
        const val NEIGHBOR_VALUE = "Synthetic retained preference"

        fun expectedMap(delivered: List<String> = emptyList(), read: List<String> = emptyList(),
            receipts: List<String> = emptyList()): Map<String, String> = mapOf(
            NEIGHBOR to NEIGHBOR_VALUE,
            SEEN_KEY to Gson().toJson(linkedMapOf(
                "delivered" to delivered, "read" to read, "read_receipts_sent" to receipts
            ))
        )
    }
}
