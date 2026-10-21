package com.bitchat.android.sync

import android.app.Application
import android.os.Build
import com.bitchat.android.model.RequestSyncPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A busy or hostile mesh must never grow gossip state past the configured capacity. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class GossipSyncMemoryBoundTest {
    private val capacity = 50

    @Test fun `a flood of unique public packets keeps at most capacity per kind`() {
        val scope = CoroutineScope(Job())
        val sent = mutableListOf<BitchatPacket>()
        val manager = GossipSyncManager("0102030405060708", scope, object : GossipSyncManager.ConfigProvider {
            override fun seenCapacity() = capacity
            override fun gcsMaxBytes() = SyncDefaults.DEFAULT_FILTER_BYTES
            override fun gcsTargetFpr() = SyncDefaults.DEFAULT_FPR_PERCENT / 100.0
        })
        manager.delegate = object : GossipSyncManager.Delegate {
            override fun sendPacket(packet: BitchatPacket) = Unit
            override fun sendPacketToPeer(peerID: String, packet: BitchatPacket) { sent += packet }
            override fun signPacketForBroadcast(packet: BitchatPacket): BitchatPacket? = packet
        }
        val now = System.currentTimeMillis().toULong()
        repeat(capacity * 10) { i ->
            val sender = ByteArray(8) { b -> (i shr (8 * (b % 4))).toByte() }.also { it[7] = 1 }
            manager.onPublicPacketSeen(packet(MessageType.MESSAGE, sender, now, "m$i", SpecialRecipients.BROADCAST))
            manager.onPublicPacketSeen(packet(MessageType.ANNOUNCE, sender, now, "a$i", null))
        }

        // An empty filter asks for everything that is still stored.
        manager.handleRequestSync("1112131415161718", RequestSyncPacket(p = 19, m = 1, data = ByteArray(0)))

        assertEquals(capacity, sent.count { it.type == MessageType.MESSAGE.value })
        assertEquals(capacity, sent.count { it.type == MessageType.ANNOUNCE.value })
        scope.cancel()
    }

    private fun packet(type: MessageType, sender: ByteArray, timestamp: ULong, body: String, recipient: ByteArray?) =
        BitchatPacket(
            type = type.value,
            senderID = sender,
            recipientID = recipient,
            timestamp = timestamp,
            payload = body.toByteArray(),
            ttl = 3u
        )
}
