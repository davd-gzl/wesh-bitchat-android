package com.bitchat.android.nostr

import android.app.Application
import com.bitchat.android.model.NoisePayload
import com.bitchat.android.model.NoisePayloadType
import com.bitchat.android.model.PrivateMessagePacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.services.ContactIdentityResolver
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

/**
 * Same immutable synthetic fixture as the iOS test: `wesh-nostr-embedded-v1.json` holds the
 * « bitchat1: » content a Wesh contact puts inside a Nostr gift wrap, for a private message, a
 * DELIVERED ack and a READ receipt. Both platforms decode it to the same fields and encode the
 * same bytes; only the 8 timestamp bytes (the send time) may differ.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class NostrEmbeddedPayloadInteropTest {
    private lateinit var fixture: JSONObject
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private fun String.unhex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Before fun setup() {
        fixture = javaClass.classLoader!!.getResourceAsStream("fixtures/wesh-nostr-embedded-v1.json")!!.use {
            JSONObject(it.bufferedReader(Charsets.UTF_8).readText())
        }
    }

    private fun case(name: String): JSONObject {
        val cases = fixture.getJSONArray("cases")
        return (0 until cases.length()).map { cases.getJSONObject(it) }.single { it.getString("name") == name }
    }

    private fun packetData(embedded: String): ByteArray {
        assertTrue(embedded.startsWith("bitchat1:"))
        return Base64.getUrlDecoder().decode(embedded.removePrefix("bitchat1:"))
    }

    /** Version, type and TTL, then the 8-byte timestamp: the rest must match byte for byte. */
    private fun withoutTimestamp(data: ByteArray) = data.copyOf().also { it.fill(0, 3, 11) }

    @Test fun recipientIdIsDerivedFromTheNoiseKey() {
        assertEquals(
            fixture.getString("recipientPeerID"),
            ContactIdentityResolver.peerIdForNoiseKeyHex(fixture.getString("recipientNoiseKeyHex"))
        )
    }

    @Test fun everyCaseDecodesToTheFixtureFields() {
        val cases = fixture.getJSONArray("cases")
        assertEquals(listOf("privateMessage", "delivered", "readReceipt"),
            (0 until cases.length()).map { cases.getJSONObject(it).getString("name") })
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val name = c.getString("name")
            val data = packetData(c.getString("embedded"))
            assertEquals(name, c.getString("packetHex"), data.hex())
            val packet = BitchatPacket.fromBinaryData(data)
            assertNotNull(name, packet)
            packet!!
            assertEquals(1, packet.version.toInt())
            assertEquals(MessageType.NOISE_ENCRYPTED.value, packet.type)
            assertEquals(7, packet.ttl.toInt())
            assertEquals(fixture.getLong("timestampMs"), packet.timestamp.toLong())
            assertEquals(fixture.getString("senderPeerID"), packet.senderID.hex())
            assertEquals(fixture.getString("recipientPeerID"), packet.recipientID?.hex())
            assertNull(packet.signature)
            assertEquals(name, c.getString("payloadHex"), packet.payload.hex())

            val payload = NoisePayload.decode(packet.payload)
            assertNotNull(name, payload)
            assertEquals(c.getInt("noisePayloadType"), payload!!.type.value.toInt())
            when (payload.type) {
                NoisePayloadType.PRIVATE_MESSAGE -> {
                    val pm = PrivateMessagePacket.decode(payload.data)!!
                    assertEquals(fixture.getString("messageID"), pm.messageID)
                    assertEquals(fixture.getString("content"), pm.content)
                }
                NoisePayloadType.DELIVERED, NoisePayloadType.READ_RECEIPT ->
                    assertEquals(fixture.getString("messageID"), String(payload.data, Charsets.UTF_8))
                else -> throw AssertionError("Unexpected payload type in $name")
            }
        }
    }

    /** The recipient may be given by its Noise key or its peer ID: same bytes either way. */
    @Test fun encodersProduceTheFixtureBytes() {
        val sender = fixture.getString("senderPeerID")
        val messageID = fixture.getString("messageID")
        for (recipient in listOf(fixture.getString("recipientNoiseKeyHex"), fixture.getString("recipientPeerID"))) {
            val encoded = mapOf(
                "privateMessage" to NostrEmbeddedBitChat.encodePMForNostr(fixture.getString("content"), messageID, recipient, sender),
                "delivered" to NostrEmbeddedBitChat.encodeAckForNostr(NoisePayloadType.DELIVERED, messageID, recipient, sender),
                "readReceipt" to NostrEmbeddedBitChat.encodeAckForNostr(NoisePayloadType.READ_RECEIPT, messageID, recipient, sender)
            )
            for ((name, embedded) in encoded) {
                assertNotNull("$name for $recipient", embedded)
                val expected = case(name).getString("packetHex").unhex()
                assertArrayEquals("$name for $recipient", withoutTimestamp(expected), withoutTimestamp(packetData(embedded!!)))
            }
        }
    }
}
