package com.bitchat.android.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NostrPendingEventQueueTest {
    @Test
    fun `empty relay set is not queued`() {
        val queue = NostrPendingEventQueue(capacity = 2)

        assertNull(queue.enqueue(event("empty"), emptyList()))
        assertEquals(0, queue.size())
    }

    @Test
    fun `capacity evicts the oldest publish`() {
        val queue = NostrPendingEventQueue(capacity = 2)
        queue.enqueue(event("one"), listOf("relay"))
        queue.enqueue(event("two"), listOf("relay"))
        queue.enqueue(event("three"), listOf("relay"))

        assertEquals(
            listOf("two", "three"),
            queue.pendingForRelay("relay").map { it.event.content }
        )
    }

    @Test
    fun `duplicate event publishes retain independent delivery state`() {
        val queue = NostrPendingEventQueue(capacity = 4)
        val signedEvent = event("same")
        val firstId = requireNotNull(
            queue.enqueue(signedEvent, listOf("relay-a", "relay-b"))
        )
        val secondId = requireNotNull(
            queue.enqueue(signedEvent, listOf("relay-a"))
        )
        assertNotEquals(firstId, secondId)

        queue.markDelivered(firstId, "relay-a")

        assertEquals(
            listOf(secondId),
            queue.pendingForRelay("relay-a").map { it.queueId }
        )
        assertEquals(
            listOf(firstId),
            queue.pendingForRelay("relay-b").map { it.queueId }
        )

        queue.markDelivered(firstId, "relay-b")
        assertEquals(1, queue.size())
    }

    private fun event(content: String): NostrEvent {
        val privateKey = "0".repeat(63) + "1"
        return NostrEvent(
            pubkey = NostrCrypto.derivePublicKey(privateKey),
            createdAt = 1,
            kind = NostrKind.TEXT_NOTE,
            tags = emptyList(),
            content = content
        ).sign(privateKey)
    }
}
