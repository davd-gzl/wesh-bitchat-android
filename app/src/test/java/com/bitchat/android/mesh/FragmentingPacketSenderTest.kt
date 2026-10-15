package com.bitchat.android.mesh

import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.util.AppConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
            type = MessageType.FILE_TRANSFER.value,
            senderID = MeshPacketUtils.hexStringToByteArray(senderID),
            recipientID = null,
            timestamp = 1uL,
            payload = payload,
            signature = null,
            ttl = 7u
        )
    }

    private fun TestScope.eventsFor(id: String): Channel<TransferProgressEvent> {
        val events = Channel<TransferProgressEvent>(Channel.UNLIMITED)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            TransferProgressManager.events.collect { if (it.transferId == id) events.send(it) }
        }
        return events
    }

    // The production event bus publishes on IO; wait for its emissions, never for a guessed delay.
    private suspend fun Channel<TransferProgressEvent>.take(count: Int): List<TransferProgressEvent> =
        withContext(Dispatchers.Default) { withTimeout(5_000) { List(count) { receive() } } }

    private fun prepared(id: String, count: Int = 3): RoutedPacket {
        val packet = packetWithPayload(3)
        return RoutedPacket(packet, transferId = id, preparedPackets = List(count) { index ->
            packet.copy(type = MessageType.FRAGMENT.value, payload = byteArrayOf(index.toByte()))
        })
    }

    @Test fun `oversized packet exceeding receiver fragment cap is rejected with fail event`() = runTest {
        val manager = FragmentManager()
        try {
            val sender = FragmentingPacketSender(this, manager, "test")
            val events = eventsFor("oversize-test")
            var sent = false
            assertFalse(sender.send(RoutedPacket(packetWithPayload(1024 * 1024), transferId = "oversize-test"), "test") {
                sent = true; true
            })
            assertFalse(sent)
            assertTrue(events.take(1).single().failed)
        } finally { manager.shutdown() }
    }

    @Test fun `packet within fragment cap is accepted`() = runTest {
        val manager = FragmentManager()
        try {
            val sender = FragmentingPacketSender(this, manager, "test")
            var writes = 0
            assertTrue(sender.send(RoutedPacket(packetWithPayload(10_000), transferId = "fits-test"), "test") { writes++; true })
            advanceUntilIdle()
            assertTrue(writes > 1)
            assertFalse(sender.cancelTransfer("fits-test"))
        } finally { manager.shutdown() }
    }

    @Test fun `fragment count at cap boundary is not rejected`() {
        val manager = FragmentManager()
        try {
            val fragments = manager.createFragments(packetWithPayload(AppConstants.Fragmentation.MAX_FRAGMENTS_PER_ID * 400),
                AppConstants.Fragmentation.MAX_FRAGMENTS_PER_ID)
            assertTrue(fragments.isNotEmpty())
            assertTrue(fragments.size <= AppConstants.Fragmentation.MAX_FRAGMENTS_PER_ID)
        } finally { manager.shutdown() }
    }

    @Test fun `second fragment refusal emits exactly one failure and never sends the third`() = runTest {
        val terminals = mutableListOf<String>()
        val sender = FragmentingPacketSender(this, null, "test",
            onFailure = { terminals += "failed:$it" }, onComplete = { id, _ -> terminals += "completed:$id" })
        val writes = mutableListOf<Int>()
        assertTrue(sender.send(prepared("second-refused"), "test") { routed ->
            writes += routed.packet.payload.single().toInt()
            writes.size == 1
        })
        advanceUntilIdle()
        assertEquals(listOf(0, 1), writes)
        assertFalse(sender.cancelTransfer("second-refused"))
        assertEquals(listOf("failed:second-refused"), terminals)
    }

    @Test fun `initial single packet refusal still returns false and emits failure`() = runTest {
        val terminals = mutableListOf<String>()
        val sender = FragmentingPacketSender(this, null, "test",
            onFailure = { terminals += "failed:$it" }, onComplete = { id, _ -> terminals += "completed:$id" })
        var writes = 0
        assertFalse(sender.send(prepared("single-refused", 1), "test") { writes++; false })
        assertEquals(1, writes)
        assertEquals(listOf("failed:single-refused"), terminals)
    }

    @Test fun `already cancelled scope refuses admission with one failure and no writes`() = runTest {
        val terminals = mutableListOf<String>()
        val cancelledScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job().apply { cancel() })
        val sender = FragmentingPacketSender(cancelledScope, null, "test",
            onFailure = { terminals += "failed:$it" }, onComplete = { id, _ -> terminals += "completed:$id" })
        var writes = 0
        assertFalse(sender.send(prepared("inactive-scope"), "test") { writes++; true })
        advanceUntilIdle()
        assertEquals(0, writes)
        assertFalse(sender.cancelTransfer("inactive-scope"))
        assertEquals(listOf("failed:inactive-scope"), terminals)
    }

    @Test fun `normal cancellation stops after first fragment without reporting failure or completion`() = runTest {
        val terminals = mutableListOf<String>()
        val sender = FragmentingPacketSender(this, null, "test",
            onFailure = { terminals += "failed:$it" }, onComplete = { id, _ -> terminals += "completed:$id" })
        var writes = 0
        assertTrue(sender.send(prepared("cancelled"), "test") { writes++; true })
        runCurrent()
        assertEquals(1, writes)
        assertTrue(sender.cancelTransfer("cancelled"))
        advanceUntilIdle()
        assertEquals(1, writes)
        assertFalse(sender.cancelTransfer("cancelled"))
        assertTrue(terminals.isEmpty())
    }

    @Test fun `cancellation during a refused write remains cancellation without a failure terminal`() = runTest {
        val terminals = mutableListOf<String>()
        val sender = FragmentingPacketSender(this, null, "test",
            onFailure = { terminals += "failed:$it" }, onComplete = { id, _ -> terminals += "completed:$id" })
        var writes = 0
        assertTrue(sender.send(prepared("cancelled-write"), "test") {
            writes++
            assertTrue(sender.cancelTransfer("cancelled-write"))
            false
        })
        val transfer = coroutineContext[Job]!!.children.single()
        advanceUntilIdle()
        assertTrue(transfer.isCancelled)
        assertEquals(1, writes)
        assertTrue(terminals.isEmpty())
    }

    @Test fun `send callback cancellation remains cancellation rather than transport failure`() = runTest {
        val terminals = mutableListOf<String>()
        val sender = FragmentingPacketSender(this, null, "test",
            onFailure = { terminals += "failed:$it" }, onComplete = { id, _ -> terminals += "completed:$id" })
        var writes = 0
        assertTrue(sender.send(prepared("callback-cancelled"), "test") {
            writes++; throw CancellationException("synthetic cancellation")
        })
        val transfer = coroutineContext[Job]!!.children.single()
        advanceUntilIdle()
        assertTrue(transfer.isCancelled)
        assertEquals(1, writes)
        assertFalse(sender.cancelTransfer("callback-cancelled"))
        assertTrue(terminals.isEmpty())
    }
}
