package com.bitchat.android.services

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.model.BitchatMessage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Date
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
class PrivateHistoryPanicGenerationTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var repository: ConversationRepository
    private val dispatcher = PausedSubmissionDispatcher()
    private val callers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val keys = SyntheticHistoryKeys()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "synthetic-panic-${UUID.randomUUID()}.db"
        repository = newRepository()
        AppStateStore.setConversationRepositoryForTest(repository)
        AppStateStore.resumePrivateConversationsAfterPanic()
        AppStateStore.clear()
    }

    @After
    fun tearDown() {
        dispatcher.release()
        runBlocking { repository.awaitPendingWrites() }
        callers.cancel()
        AppStateStore.setConversationRepositoryForTest(null)
        AppStateStore.resumePrivateConversationsAfterPanic()
        AppStateStore.clear()
        repository.closeForTest()
        dispatcher.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun `admitted receipt submitted after panic cannot recreate history`() = runBlocking {
        assertLateReceiptIsRetired(resumeBeforeRelease = false)
    }

    @Test
    fun `admitted receipt stays retired after the new session resumes`() = runBlocking {
        assertLateReceiptIsRetired(resumeBeforeRelease = true)
    }

    @Test
    fun `pending undo submitted after panic cannot restore old plaintext`() = runBlocking {
        assertLateUndoIsRetired(resumeBeforeRelease = false)
    }

    @Test
    fun `pending undo stays retired after the new session resumes`() = runBlocking {
        assertLateUndoIsRetired(resumeBeforeRelease = true)
    }

    @Test
    fun `an undo token retained across panic cannot be used in a new session`() = runBlocking {
        assertTrue(AppStateStore.addPrivateMessageDurably(CONTACT, message("before-delete")))
        val deleted = requireNotNull(AppStateStore.deletePrivateConversationAndWait(CONTACT))
        assertTrue(AppStateStore.panicClearPrivateConversations())
        AppStateStore.resumePrivateConversationsAfterPanic()
        assertFalse(AppStateStore.restoreDeletedConversation(deleted))
        assertHistoryStaysErased()
    }

    @Test
    fun `normal durable receipt undo and fresh post-panic receipt remain usable`() = runBlocking {
        assertTrue(AppStateStore.addPrivateMessageDurably(CONTACT, message("normal")))
        assertEquals(1, keys.creates)
        val deleted = requireNotNull(AppStateStore.deletePrivateConversationAndWait(CONTACT))
        assertTrue(AppStateStore.restoreDeletedConversation(deleted))
        assertEquals(listOf("normal"), repository.loadConversationAndWait(CONTACT)?.chats?.get(CONTACT)?.map { it.id })
        repository.upsertMessage(CONTACT, setOf(CONTACT), "Synthetic sender", message("already-queued"), false)
        assertTrue(AppStateStore.panicClearPrivateConversations())
        assertHistoryStaysErased()
        AppStateStore.resumePrivateConversationsAfterPanic()
        assertTrue(AppStateStore.addPrivateMessageDurably(CONTACT, message("fresh")))
        assertEquals(2, keys.creates)
        assertEquals(listOf("fresh"), repository.loadConversationAndWait(CONTACT)?.chats?.get(CONTACT)?.map { it.id })
    }

    @Test
    fun `pending delete cannot remove a fresh sessions history`() = runBlocking {
        assertTrue(AppStateStore.addPrivateMessageDurably(CONTACT, message("before-delete")))
        // Deletion first loads retained history. Hold that continuation across
        // panic so it must not acquire permission to delete the new session.
        dispatcher.pauseNextSubmission()
        val deletion = callers.async { AppStateStore.deletePrivateConversationAndWait(CONTACT) }
        dispatcher.awaitSubmission()
        assertTrue(AppStateStore.panicClearPrivateConversations())
        AppStateStore.resumePrivateConversationsAfterPanic()
        assertTrue(AppStateStore.addPrivateMessageDurably(CONTACT, message("fresh")))
        dispatcher.release()
        assertNull(deletion.await())
        assertEquals(listOf("fresh"), repository.loadConversationAndWait(CONTACT)?.chats?.get(CONTACT)?.map { it.id })
    }

    @Test
    fun `a loaded snapshot delivered after panic cannot repopulate memory`() = runBlocking {
        assertTrue(AppStateStore.addPrivateMessageDurably(CONTACT, message("before-load")))
        val delivery = PausedSubmissionDispatcher()
        val loaderJob = SupervisorJob()
        val loader = CoroutineScope(loaderJob + delivery)
        var primaryFailure: Throwable? = null
        try {
            delivery.pauseNextSubmission()
            dispatcher.pauseNextSubmission()
            // Hold the database submission until the inline caller has suspended;
            // its completed read must then return through the delivery dispatcher.
            val load = loader.async(start = CoroutineStart.UNDISPATCHED) {
                AppStateStore.loadPrivateConversationHistory(CONTACT)
            }
            dispatcher.awaitSubmission()
            dispatcher.release()
            delivery.awaitSubmission()
            assertTrue(AppStateStore.panicClearPrivateConversations())
            AppStateStore.resumePrivateConversationsAfterPanic()
            delivery.release()
            val restored = load.await()
            assertHistoryStaysErased()
            assertFalse(restored)
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                dispatcher.release()
                delivery.release()
                try {
                    withTimeout(1_000) { loaderJob.cancelAndJoin() }
                } finally {
                    delivery.close()
                }
            } catch (cleanupFailure: Throwable) {
                val original = primaryFailure
                if (original != null) original.addSuppressed(cleanupFailure) else throw cleanupFailure
            }
        }
    }

    @Test
    fun `a failed key erase still retires pending history and permits a checked retry`() = runBlocking {
        assertTrue(AppStateStore.addPrivateMessageDurably(CONTACT, message("seed")))
        dispatcher.pauseNextSubmission()
        val receipt = callers.async { AppStateStore.addPrivateMessageDurably(CONTACT, message("late")) }
        dispatcher.awaitSubmission()
        keys.refuseDeletion = true
        assertFalse(AppStateStore.panicClearPrivateConversations())
        dispatcher.release()
        assertFalse(receipt.await())
        assertFalse(AppStateStore.addPrivateMessageDurably(CONTACT, message("blocked")))
        assertTrue(requireNotNull(repository.loadConversationAndWait(CONTACT)).chats.isEmpty())
        assertEquals(1, keys.creates)
        keys.refuseDeletion = false
        assertTrue(AppStateStore.panicClearPrivateConversations())
        assertHistoryStaysErased()
    }

    @Test
    fun `cancelled clear keeps admission closed until an explicit successful retry`() = runBlocking {
        assertTrue(AppStateStore.addPrivateMessageDurably(CONTACT, message("seed")))
        dispatcher.pauseNextSubmission()
        val clear = callers.async { AppStateStore.panicClearPrivateConversations() }
        dispatcher.awaitSubmission()
        clear.cancel()
        dispatcher.release()
        clear.cancelAndJoin()
        assertFalse(AppStateStore.addPrivateMessageDurably(CONTACT, message("blocked")))
        assertTrue(AppStateStore.panicClearPrivateConversations())
        assertHistoryStaysErased()
        AppStateStore.resumePrivateConversationsAfterPanic()
        assertTrue(AppStateStore.addPrivateMessageDurably(CONTACT, message("fresh")))
    }

    private suspend fun assertLateReceiptIsRetired(resumeBeforeRelease: Boolean) {
        assertTrue(AppStateStore.addPrivateMessageDurably(CONTACT, message("seed")))
        dispatcher.pauseNextSubmission()
        val receipt = callers.async { AppStateStore.addPrivateMessageDurably(CONTACT, message("late")) }
        dispatcher.awaitSubmission()
        assertTrue(AppStateStore.panicClearPrivateConversations())
        assertNull(keys.stored)
        if (resumeBeforeRelease) AppStateStore.resumePrivateConversationsAfterPanic()
        dispatcher.release()
        assertFalse(receipt.await()) // Existing UI fence alone is insufficient.
        assertHistoryStaysErased()
    }

    private suspend fun assertLateUndoIsRetired(resumeBeforeRelease: Boolean) {
        assertTrue(AppStateStore.addPrivateMessageDurably(CONTACT, message("before-delete")))
        val deleted = requireNotNull(AppStateStore.deletePrivateConversationAndWait(CONTACT))
        dispatcher.pauseNextSubmission()
        val undo = callers.async { AppStateStore.restoreDeletedConversation(deleted) }
        dispatcher.awaitSubmission()
        assertTrue(AppStateStore.panicClearPrivateConversations())
        if (resumeBeforeRelease) AppStateStore.resumePrivateConversationsAfterPanic()
        dispatcher.release()
        val restored = undo.await()
        // Inspect persistence even if the caller incorrectly reports success.
        assertHistoryStaysErased()
        assertFalse(restored)
    }

    private suspend fun assertHistoryStaysErased() {
        repository.awaitPendingWrites()
        val reopened = newRepository()
        try {
            val snapshot = requireNotNull(reopened.loadConversationAndWait(CONTACT))
            assertTrue("Old-generation rows must stay absent after reopening", snapshot.chats.isEmpty())
            assertEquals("Old work must not create a replacement key", 1, keys.creates)
            assertNull(keys.stored)
            assertTrue(AppStateStore.privateMessages.value.isEmpty())
        } finally {
            reopened.closeForTest()
        }
    }

    private fun newRepository() = ConversationRepository(
        context = context, dispatcher = dispatcher, databaseName = databaseName,
        storageCipher = AndroidConversationStorageCipher(keys)
    )

    private fun message(id: String) = BitchatMessage(
        id = id, sender = "Synthetic sender", content = "Synthetic private fixture",
        timestamp = Date(100), isPrivate = true, senderPeerID = CONTACT
    )

    private companion object { const val CONTACT = "synthetic-contact" }
}

/** Holds one submission before the serial writer receives it, without blocking that writer. */
private class PausedSubmissionDispatcher : CoroutineDispatcher() {
    private val delegate = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val armed = AtomicBoolean(false)
    private val held = AtomicReference<Pair<CoroutineContext, Runnable>?>(null)
    private val submitted = CountDownLatch(1)

    fun pauseNextSubmission() { check(armed.compareAndSet(false, true)) }
    fun awaitSubmission() { check(submitted.await(5, TimeUnit.SECONDS)) { "Expected pending database submission" } }
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (armed.compareAndSet(true, false)) {
            check(held.compareAndSet(null, context to block))
            submitted.countDown()
        } else {
            delegate.dispatch(context, block)
        }
    }
    fun release() { held.getAndSet(null)?.let { (context, block) -> delegate.dispatch(context, block) } }
    fun close() { delegate.close() }
}

private class SyntheticHistoryKeys : ConversationStorageKeyProvider {
    @Volatile var stored: SecretKey? = null
    @Volatile var refuseDeletion = false
    var creates = 0
        private set
    override fun loadExisting(): SecretKey? = stored
    override fun create(): SecretKey {
        creates += 1
        return SecretKeySpec(ByteArray(32) { creates.toByte() }, "AES").also { stored = it }
    }
    override fun delete() {
        check(!refuseDeletion) { "Synthetic key deletion refusal" }
        stored = null
    }
}
