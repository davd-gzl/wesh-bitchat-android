package com.bitchat.android.ui

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.ConversationListPreferences
import com.bitchat.android.services.ConversationRepository
import com.bitchat.android.services.ConversationStorageCipher
import com.bitchat.android.services.InMemoryConversationStorageCipher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ChatViewModelPanicContinuationTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var preferencesName: String
    private lateinit var repository: ConversationRepository
    private lateinit var preferences: ConversationListPreferences
    private lateinit var state: ChatState
    private lateinit var viewModel: ChatViewModel
    private val writer = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val main = HeldMainReturnDispatcher()
    private val cipher = HeldRestoreCipher()
    private val stateScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val callers = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
        context = ApplicationProvider.getApplicationContext()
        databaseName = "synthetic-viewmodel-panic-${UUID.randomUUID()}.db"
        preferencesName = "synthetic-viewmodel-preferences-${UUID.randomUUID()}"
        ContactDirectory.initialize(context) { null }
        repository = ConversationRepository(context, writer, databaseName, cipher)
        AppStateStore.setConversationRepositoryForTest(repository)
        AppStateStore.resetPrivateConversationAdmissionForTest()
        AppStateStore.clear()
        val storage = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        preferences = ConversationListPreferences(
            SecureIdentityStateManager(storage, testOnly = true), testOnly = true,
            canonicalize = { it.lowercase() }
        )
        state = ChatState(stateScope)
        // Invoke the real continuation and real preference/state mutations without
        // constructing unrelated Bluetooth, native Tor, or live Keystore services.
        viewModel = Mockito.mock(ChatViewModel::class.java, Mockito.CALLS_REAL_METHODS)
        inject("conversationListPreferences", preferences)
        inject("state", state)
    }

    @After
    fun tearDown() {
        cipher.release()
        main.release()
        callers.cancel()
        stateScope.cancel()
        runBlocking { repository.awaitPendingWrites() }
        AppStateStore.setConversationRepositoryForTest(null)
        AppStateStore.resetPrivateConversationAdmissionForTest()
        AppStateStore.clear()
        repository.closeForTest()
        writer.close()
        main.close()
        Dispatchers.resetMain()
        context.deleteDatabase(databaseName)
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit().clear().commit()
        ContactDirectory.initialize(context) { null }
    }

    @Test
    fun `successful undo in the current session restores draft preferences and unread state`() = runBlocking {
        assertHeldUndo()
    }

    @Test
    fun `successful read in the current session clears unread state`() = runBlocking {
        assertHeldRead()
    }

    private suspend fun assertHeldRead() {
        val old = BitchatMessage(
            id = "synthetic-old-unread", sender = "Synthetic sender", content = "Synthetic private fixture",
            timestamp = Date(100), isPrivate = true, senderPeerID = CONTACT
        )
        assertTrue(AppStateStore.addPrivateMessageDurably(CONTACT, old))
        state.setPrivateChats(AppStateStore.privateMessages.value)
        state.setUnreadPrivateMessages(setOf(CONTACT))
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        writer.dispatch(EmptyCoroutineContext, Runnable {
            entered.countDown()
            check(released.await(5, TimeUnit.SECONDS)) { "Read fixture writer was not released" }
        })
        try {
            check(entered.await(5, TimeUnit.SECONDS)) { "Expected controlled writer admission" }
            main.holdNextSubmission()
            val reading = callers.async(start = CoroutineStart.UNDISPATCHED) {
                viewModel.setConversationRead(CONTACT, true)
            }
            // The serial writer is held until withContext(IO) has suspended the caller.
            released.countDown()
            main.awaitSubmission()
            assertFalse(reading.isCompleted)
            assertTrue(AppStateStore.isPrivateMessageRead(old.id))

            main.release()
            val updated = withTimeout(5_000) { reading.await() }
            assertTrue(updated)
            assertTrue(state.getUnreadPrivateMessagesValue().isEmpty())
            assertTrue(AppStateStore.isPrivateMessageRead(old.id))
        } finally {
            released.countDown()
        }
    }

    private suspend fun assertHeldUndo() {
        val message = BitchatMessage(
            id = "synthetic-before-delete", sender = "Synthetic sender", content = "Synthetic private fixture",
            timestamp = Date(100), isPrivate = true, senderPeerID = CONTACT
        )
        assertTrue(AppStateStore.addPrivateMessageDurably(CONTACT, message))
        val deletion = requireNotNull(AppStateStore.deletePrivateConversationAndWait(CONTACT)).copy(
            wasPinned = true, wasMuted = true, draft = "Synthetic unfinished private draft"
        )
        assertEquals(1, deletion.unreadMessageCount)

        cipher.holdNextEncryption()
        main.holdNextSubmission()
        val undo = callers.async(start = CoroutineStart.UNDISPATCHED) {
            viewModel.restoreDeletedConversation(deletion)
        }
        // Keep the writer inside the actual restore until the caller has suspended.
        // Its completed IO result must then dispatch back through our held Main.
        cipher.awaitEncryption()
        cipher.release()
        main.awaitSubmission()
        assertFalse(undo.isCompleted)
        assertEquals(listOf(message.id), repository.loadConversationAndWait(CONTACT)?.chats?.get(CONTACT)?.map { it.id })

        main.release()
        val restored = withTimeout(5_000) { undo.await() }
        assertTrue(restored)
        assertEquals(deletion.draft, preferences.draftFor(CONTACT))
        assertTrue(preferences.isPinned(CONTACT))
        assertTrue(preferences.isMuted(CONTACT))
        assertEquals(listOf(message.id), state.getPrivateChatsValue()[CONTACT]?.map { it.id })
        assertEquals(setOf(CONTACT), state.getUnreadPrivateMessagesValue())
    }

    private fun inject(name: String, value: Any) {
        ChatViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(viewModel, value)
    }

    private companion object { const val CONTACT = "synthetic-contact" }
}

private class HeldMainReturnDispatcher : CoroutineDispatcher() {
    private val delegate = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val armed = AtomicBoolean(false)
    private val held = AtomicReference<Pair<CoroutineContext, Runnable>?>(null)
    private val submitted = CountDownLatch(1)

    fun holdNextSubmission() { check(armed.compareAndSet(false, true)) }
    fun awaitSubmission() { check(submitted.await(5, TimeUnit.SECONDS)) { "Expected completed IO return to Main" } }
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (armed.compareAndSet(true, false)) {
            check(held.compareAndSet(null, context to block))
            submitted.countDown()
        } else delegate.dispatch(context, block)
    }
    fun release() { held.getAndSet(null)?.let { (context, block) -> delegate.dispatch(context, block) } }
    fun close() { delegate.close() }
}

private class HeldRestoreCipher : ConversationStorageCipher {
    private val delegate = InMemoryConversationStorageCipher()
    private val armed = AtomicBoolean(false)
    private val entered = CountDownLatch(1)
    private val released = CountDownLatch(1)

    fun holdNextEncryption() { check(armed.compareAndSet(false, true)) }
    fun awaitEncryption() { check(entered.await(5, TimeUnit.SECONDS)) { "Expected the real SQLite restore to encrypt" } }
    fun release() { released.countDown() }
    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        if (armed.compareAndSet(true, false)) {
            entered.countDown()
            check(released.await(5, TimeUnit.SECONDS)) { "Restore fixture was not released" }
        }
        return delegate.encrypt(plaintext, associatedData)
    }
    override fun decrypt(envelope: ByteArray, associatedData: ByteArray) = delegate.decrypt(envelope, associatedData)
    override fun destroyKey() = delegate.destroyKey()
}
