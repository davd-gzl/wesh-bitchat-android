package com.bitchat.android.mesh

import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.util.AppConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Random

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class FragmentingPacketSenderTest {
    private val senderID = "1122334455667788"

    private fun packetWithPayload(bytes: Int): BitchatPacket {
        val payload = ByteArray(bytes)
        Random(42).nextBytes(payload)
        return BitchatPacket(
            version = 2u,
            type = MessageType.MESSAGE.value,
            senderID = MeshPacketUtils.hexStringToByteArray(senderID),
            recipientID = null,
            timestamp = 1uL,
            payload = payload,
            signature = null,
            ttl = 7u
        )
    }

    private fun <T> withManager(block: (FragmentManager) -> T): T {
        val manager = FragmentManager()
        return try { block(manager) } finally { manager.shutdown() }
    }

    @Test fun `oversized packet exceeding receiver fragment cap is rejected before any write`() = runTest {
        withManager { manager ->
            val sender = FragmentingPacketSender(this, manager, "test")
            var sent = false
            assertFalse(sender.send(RoutedPacket(packetWithPayload(1024 * 1024)), "test") { sent = true; true })
            assertFalse(sent)
        }
    }

    @Test fun `packet within fragment cap is accepted and sent as several fragments`() = runTest {
        withManager { manager ->
            val sender = FragmentingPacketSender(this, manager, "test")
            val writes = mutableListOf<UByte>()
            assertTrue(sender.send(RoutedPacket(packetWithPayload(10_000)), "test") { writes += it.packet.type; true })
            advanceUntilIdle()
            assertTrue(writes.size > 1)
            assertTrue(writes.all { it == MessageType.FRAGMENT.value })
        }
    }

    @Test fun `fragment count at cap boundary is not rejected`() {
        withManager { manager ->
            val fragments = manager.createFragments(packetWithPayload(AppConstants.Fragmentation.MAX_FRAGMENTS_PER_ID * 400),
                AppConstants.Fragmentation.MAX_FRAGMENTS_PER_ID)
            assertTrue(fragments.isNotEmpty())
            assertTrue(fragments.size <= AppConstants.Fragmentation.MAX_FRAGMENTS_PER_ID)
        }
    }

    @Test fun `second fragment refusal never sends the third`() = runTest {
        withManager { manager ->
            val sender = FragmentingPacketSender(this, manager, "test")
            var writes = 0
            assertTrue(sender.send(RoutedPacket(packetWithPayload(2_000)), "test") { writes++; writes == 1 })
            advanceUntilIdle()
            assertEquals(2, writes)
        }
    }

    @Test fun `single packet refusal returns false`() = runTest {
        val sender = FragmentingPacketSender(this, null, "test")
        var writes = 0
        assertFalse(sender.send(RoutedPacket(packetWithPayload(3)), "test") { writes++; false })
        assertEquals(1, writes)
    }

    @Test fun `already cancelled scope refuses a fragmented send with no writes`() = runTest {
        withManager { manager ->
            val cancelledScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job().apply { cancel() })
            val sender = FragmentingPacketSender(cancelledScope, manager, "test")
            var writes = 0
            assertFalse(sender.send(RoutedPacket(packetWithPayload(2_000)), "test") { writes++; true })
            advanceUntilIdle()
            assertEquals(0, writes)
        }
    }

    @Test fun `send callback cancellation remains cancellation rather than transport failure`() = runTest {
        withManager { manager ->
            val sender = FragmentingPacketSender(this, manager, "test")
            var writes = 0
            assertTrue(sender.send(RoutedPacket(packetWithPayload(2_000)), "test") {
                writes++; throw CancellationException("synthetic cancellation")
            })
            val fragmentedSend = coroutineContext[Job]!!.children.single()
            advanceUntilIdle()
            assertTrue(fragmentedSend.isCancelled)
            assertEquals(1, writes)
        }
    }
}
