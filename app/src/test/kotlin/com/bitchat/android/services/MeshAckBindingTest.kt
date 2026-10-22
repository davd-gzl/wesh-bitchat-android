package com.bitchat.android.services

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.model.MessageTransport
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Date

/** Bluetooth acks are bound to the acking peer's conversation, also when only SQLite holds the message. */
@RunWith(RobolectricTestRunner::class)
class MeshAckBindingTest {
    @Before
    fun setUp() {
        AppStateStore.resetPrivateConversationAdmissionForTest()
        AppStateStore.clear()
    }

    @After
    fun tearDown() {
        AppStateStore.resetPrivateConversationAdmissionForTest()
        AppStateStore.clear()
    }

    @Test
    fun `a Bluetooth ack from a third peer is refused when the history is only in the database`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val name = "ack-unloaded-${java.util.UUID.randomUUID()}.db"
        val repository = ConversationRepository(context = context,
            dispatcher = executor.asCoroutineDispatcher(), databaseName = name,
            storageCipher = InMemoryConversationStorageCipher())
        try {
            AppStateStore.setConversationRepositoryForTest(repository)
            // Only in SQLite: the history of Alice's conversation is not loaded in memory.
            repository.upsertMessage(conversationID = "contact_alice", aliases = setOf("contact_alice"),
                displayName = "alice", message = BitchatMessage(id = "unloaded-1", sender = "me", content = "x",
                    timestamp = Date(1), isPrivate = true, deliveryStatus = DeliveryStatus.Sent), isRead = true)
            kotlinx.coroutines.runBlocking { repository.awaitPendingWrites() }
            val conversations = mapOf("aaaa000000000001" to "contact_alice", "eeee000000000009" to "contact_mallory")
            val conversationOf = { peerID: String -> conversations.getValue(peerID) }
            fun stored() = kotlinx.coroutines.runBlocking { repository.loadConversationAndWait("contact_alice") }
                ?.chats?.get("contact_alice")?.single()

            assertFalse(AppStateStore.applyMeshAck("eeee000000000009", "unloaded-1",
                DeliveryStatus.Read("mallory", Date(2)), conversationOf))
            assertEquals(DeliveryStatus.Sent, stored()?.deliveryStatus)

            assertTrue(AppStateStore.applyMeshAck("aaaa000000000001", "unloaded-1",
                DeliveryStatus.Delivered("alice", Date(3)), conversationOf))
            assertEquals(DeliveryStatus.Delivered("alice", Date(3)), stored()?.deliveryStatus)
            assertEquals(MessageTransport.BLUETOOTH, stored()?.transport)
        } finally {
            AppStateStore.setConversationRepositoryForTest(null)
            repository.closeForTest()
            executor.shutdown()
            context.deleteDatabase(name)
        }
    }

}
