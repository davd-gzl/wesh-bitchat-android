package com.bitchat.android.mesh

import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.MessagePadding
import com.bitchat.android.model.FragmentPayload
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Random

@RunWith(RobolectricTestRunner::class)
class FragmentManagerTest {

    private lateinit var fragmentManager: FragmentManager
    private val senderID = "1122334455667788"
    private val recipientID = "8877665544332211"

    @Before
    fun setup() {
        fragmentManager = FragmentManager()
    }

    @After
    fun tearDown() {
        fragmentManager.shutdown()
    }

    @Test
    fun `test fragmentation without route`() {
        // Create a large payload (e.g., 1000 bytes)
        val payload = ByteArray(1000)
        Random(0xB17C4A7).nextBytes(payload)

        val packet = BitchatPacket(
            version = 1u,
            type = MessageType.MESSAGE.value,
            senderID = hexStringToByteArray(senderID),
            recipientID = hexStringToByteArray(recipientID),
            timestamp = System.currentTimeMillis().toULong(),
            payload = payload,
            ttl = 7u,
            route = null
        )

        val fragments = fragmentManager.createFragments(packet)

        assertTrue("Should create multiple fragments", fragments.size > 1)
        
        // Verify each fragment fits in MTU (512)
        for (fragment in fragments) {
            val encodedSize = fragment.toBinaryData()?.size ?: 0
            assertTrue("Fragment encoded size should be <= 512, was $encodedSize", encodedSize <= 512)
            
            // Inspect the payload data size
            val fragmentPayload = FragmentPayload.decode(fragment.payload)
            assertNotNull(fragmentPayload)
        }
    }

    @Test
    fun `test fragmentation with route`() {
        // Create a large payload
        val payload = ByteArray(1000)
        Random(0xB17C4A7).nextBytes(payload)
        
        // Create a fake route (3 hops)
        val route = listOf(
            hexStringToByteArray("AABBCCDDEEFF0011"),
            hexStringToByteArray("1100FFEEDDCCBBAA"),
            hexStringToByteArray("1234567890ABCDEF")
        )

        val packet = BitchatPacket(
            version = 2u,
            type = MessageType.MESSAGE.value,
            senderID = hexStringToByteArray(senderID),
            recipientID = hexStringToByteArray(recipientID),
            timestamp = System.currentTimeMillis().toULong(),
            payload = payload,
            ttl = 7u,
            route = route
        )

        val fragments = fragmentManager.createFragments(packet)

        assertTrue("Should create multiple fragments", fragments.size > 1)

        // Verify fragments retain the route and version 2
        for (fragment in fragments) {
            assertEquals("Fragment version should be 2", 2u.toUByte(), fragment.version)
            assertEquals("Fragment should have the route", route.size, fragment.route?.size)
            
            val encodedSize = fragment.toBinaryData()?.size ?: 0
            assertTrue("Fragment encoded size should be <= 512, was $encodedSize", encodedSize <= 512)
        }
    }
    
    @Test
    fun `test fragmentation size difference with and without route`() {
        // This test specifically checks if the dynamic calculation logic works 
        // by observing that fragments with routes carry less data payload per fragment
        
        val payload = ByteArray(2000) // Large enough to ensure full fragments
        Random(0xB17C4A7).nextBytes(payload)
        
        // 1. Without route
        val packetNoRoute = BitchatPacket(
            version = 1u,
            type = MessageType.MESSAGE.value,
            senderID = hexStringToByteArray(senderID),
            recipientID = hexStringToByteArray(recipientID),
            timestamp = System.currentTimeMillis().toULong(),
            payload = payload,
            ttl = 7u,
            route = null
        )
        val fragmentsNoRoute = fragmentManager.createFragments(packetNoRoute)
        val firstFragPayloadNoRoute = FragmentPayload.decode(fragmentsNoRoute[0].payload)
        val dataSizeNoRoute = firstFragPayloadNoRoute?.data?.size ?: 0
        
        // 2. With large route (e.g., 5 hops)
        val route = List(5) { hexStringToByteArray("000000000000000$it") }
        val packetWithRoute = BitchatPacket(
            version = 2u,
            type = MessageType.MESSAGE.value,
            senderID = hexStringToByteArray(senderID),
            recipientID = hexStringToByteArray(recipientID),
            timestamp = System.currentTimeMillis().toULong(),
            payload = payload,
            ttl = 7u,
            route = route
        )
        val fragmentsWithRoute = fragmentManager.createFragments(packetWithRoute)
        val firstFragPayloadWithRoute = FragmentPayload.decode(fragmentsWithRoute[0].payload)
        val dataSizeWithRoute = firstFragPayloadWithRoute?.data?.size ?: 0
        
        println("Data size without route: $dataSizeNoRoute")
        println("Data size with route: $dataSizeWithRoute")
        
        assertTrue("Data payload should be smaller with route", dataSizeWithRoute < dataSizeNoRoute)
        
        // Rough verification of the math:
        // 5 hops * 8 bytes = 40 bytes extra.
        // Plus v2 header overhead differences.
        // The difference should be roughly 40+ bytes.
        assertTrue("Difference should be significant", (dataSizeNoRoute - dataSizeWithRoute) >= 40)
    }

    @Test
    fun `test reassembly`() {
        val originalPayload = ByteArray(1500)
        Random(0xB17C4A7).nextBytes(originalPayload)
        
        val originalPacket = BitchatPacket(
            version = 1u,
            type = MessageType.FILE_TRANSFER.value,
            senderID = hexStringToByteArray(senderID),
            recipientID = hexStringToByteArray(recipientID),
            timestamp = System.currentTimeMillis().toULong(),
            payload = originalPayload,
            ttl = 7u
        )
        
        val fragments = fragmentManager.createFragments(originalPacket)
        
        var reassembledPacket: BitchatPacket? = null
        
        // Feed fragments back into FragmentManager
        // Note: FragmentManager stores state in incomingFragments
        
        for (fragment in fragments) {
            val result = fragmentManager.handleFragment(fragment)
            if (result != null) {
                reassembledPacket = result
            }
        }
        
        assertNotNull("Should have reassembled packet", reassembledPacket)
        assertEquals("Type should match", originalPacket.type, reassembledPacket!!.type)
        assertEquals("Payload size should match", originalPacket.payload.size, reassembledPacket.payload.size)
        assertTrue("Payload content should match", originalPacket.payload.contentEquals(reassembledPacket.payload))
    }

    @Test
    fun `fragmentation preserves unpadded frame ending in a single padding-like byte`() {
        assertUnpaddedSuffixRoundTrip(byteArrayOf(0x01))
    }

    @Test
    fun `fragmentation preserves unpadded frame ending in repeated padding-like bytes`() {
        assertUnpaddedSuffixRoundTrip(byteArrayOf(0x02, 0x02))
    }

    @Test
    fun `fragmentation retains core bytes when transport padding is actually applied`() {
        val packet = deterministicPacket(900, byteArrayOf(0x01))
        val expectedCore = literalHeader("0384") + packet.payload
        assertArrayEquals(expectedCore, packet.toBinaryData(padding = false))
        val padded = packet.toBinaryData(padding = true)!!
        assertEquals(930, expectedCore.size)
        assertEquals(1024, padded.size)
        assertArrayEquals(expectedCore, MessagePadding.unpad(padded))

        assertFragmentRoundTrip(packet, expectedCore)
    }

    private fun assertUnpaddedSuffixRoundTrip(suffix: ByteArray) {
        val packet = deterministicPacket(1500, suffix)
        val expectedCore = literalHeader("05dc") + packet.payload
        assertEquals(1530, expectedCore.size)
        assertArrayEquals(expectedCore, packet.toBinaryData(padding = false))
        assertEquals(2048, MessagePadding.optimalBlockSize(expectedCore.size))
        assertEquals(518, MessagePadding.optimalBlockSize(expectedCore.size) - expectedCore.size)
        // The required padding exceeds its one-byte marker; the transport encoder leaves the frame intact.
        assertArrayEquals(expectedCore, packet.toBinaryData(padding = true))

        assertFragmentRoundTrip(packet, expectedCore)
    }

    private fun deterministicPacket(payloadSize: Int, suffix: ByteArray): BitchatPacket {
        // All 256 byte values occur, bypassing the encoder's low-diversity compression heuristic.
        val payload = ByteArray(payloadSize) { it.toByte() }
        suffix.copyInto(payload, payload.size - suffix.size)
        return BitchatPacket(
            version = 1u,
            type = MessageType.FILE_TRANSFER.value,
            senderID = hexStringToByteArray(senderID),
            recipientID = hexStringToByteArray(recipientID),
            timestamp = 1u,
            payload = payload,
            ttl = 7u
        )
    }

    private fun literalHeader(payloadLengthHex: String): ByteArray =
        // v1, FILE_TRANSFER, TTL 7, timestamp 1, recipient flag, UInt16 length, sender, recipient.
        hexStringToByteArray("012207000000000000000101" + payloadLengthHex + senderID + recipientID)

    private fun assertFragmentRoundTrip(packet: BitchatPacket, expectedCore: ByteArray) {
        val fragments = fragmentManager.createFragments(packet)
        assertTrue("Should create multiple fragments", fragments.size > 1)
        val chunks = fragments.map { requireNotNull(FragmentPayload.decode(it.payload)).data }
        assertArrayEquals(expectedCore, chunks.flatMap { it.asIterable() }.toByteArray())

        val completed = fragments.mapNotNull { fragmentManager.handleFragment(it) }
        assertEquals("Exactly one reassembled packet", 1, completed.size)
        val reassembled = completed.single()
        assertEquals(packet.version, reassembled.version)
        assertEquals(packet.type, reassembled.type)
        assertEquals(packet.timestamp, reassembled.timestamp)
        assertArrayEquals(packet.senderID, reassembled.senderID)
        assertArrayEquals(packet.recipientID, reassembled.recipientID)
        assertArrayEquals(packet.payload, reassembled.payload)
        assertEquals(0u.toUByte(), reassembled.ttl)
    }

    @Test
    fun `inbound fragment set above 256 is rejected`() {
        val payload = FragmentPayload(
            fragmentID = ByteArray(8) { 1 },
            index = 0,
            total = 257,
            originalType = MessageType.NOISE_ENCRYPTED.value,
            data = byteArrayOf(1)
        ).encode()
        val packet = BitchatPacket(
            version = 1u,
            type = MessageType.FRAGMENT.value,
            senderID = hexStringToByteArray(senderID),
            recipientID = hexStringToByteArray(recipientID),
            timestamp = 1u,
            payload = payload,
            ttl = 7u
        )

        assertNull(fragmentManager.handleFragment(packet))
    }

    @Test
    fun `fragment wrapping another fragment is refused`() {
        val payload = FragmentPayload(
            fragmentID = ByteArray(8) { 3 },
            index = 0,
            total = 1,
            originalType = MessageType.FRAGMENT.value,
            data = ByteArray(32) { 1 }
        ).encode()

        assertNull(fragmentManager.handleFragment(fragmentPacket(payload)))
        assertTrue(fragmentManager.getDebugInfo().contains("Active Fragment Sets: 0"))
    }

    @Test
    fun `assembly is bounded by the frame its claimed type can fill`() {
        val limit = com.bitchat.android.protocol.PacketPayloadLimits.maxFrameBytes(MessageType.ANNOUNCE.value)
        val chunk = 400
        val total = limit / chunk + 2
        var last: BitchatPacket? = null
        // Stop at the first fragment that crosses the bound: the set must be gone by then.
        for (index in 0..limit / chunk) {
            last = fragmentManager.handleFragment(fragmentPacket(FragmentPayload(
                fragmentID = ByteArray(8) { 4 },
                index = index,
                total = total,
                originalType = MessageType.ANNOUNCE.value,
                data = ByteArray(chunk) { 1 }
            ).encode()))
        }

        assertNull(last)
        assertTrue(fragmentManager.getDebugInfo().contains("Global Buffered Bytes: 0"))
    }

    @Test
    fun `directed fragments for another peer are relayed without being buffered`() {
        val manager = FragmentManager(myPeerID = "0102030405060708")
        try {
            val result = manager.receiveFragment(fragmentPacket(fragment(MessageType.NOISE_ENCRYPTED, index = 0, total = 2)))

            assertEquals(FragmentResult.Skipped, result)
            assertTrue(manager.getDebugInfo().contains("Active Fragment Sets: 0"))
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun `directed fragments for this peer are buffered`() {
        val manager = FragmentManager(myPeerID = recipientID)
        try {
            val result = manager.receiveFragment(fragmentPacket(fragment(MessageType.NOISE_ENCRYPTED, index = 0, total = 2)))

            assertEquals(FragmentResult.Pending, result)
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun `file fragments are not buffered when media is refused`() {
        fragmentManager.acceptMediaFragments = false

        val result = fragmentManager.receiveFragment(
            fragmentPacket(fragment(MessageType.FILE_TRANSFER, index = 0, total = 2), recipient = com.bitchat.android.protocol.SpecialRecipients.BROADCAST)
        )

        assertEquals(FragmentResult.Skipped, result)
        assertTrue(fragmentManager.getDebugInfo().contains("Active Fragment Sets: 0"))
    }

    @Test
    fun `public text fragment set is refused once it outgrows the public text frame`() {
        val broadcast = com.bitchat.android.protocol.SpecialRecipients.BROADCAST
        val chunk = 400
        val total = com.bitchat.android.protocol.PacketPayloadLimits.PUBLIC_TEXT_FRAME_BYTES / chunk + 2
        val results = (0..com.bitchat.android.protocol.PacketPayloadLimits.PUBLIC_TEXT_FRAME_BYTES / chunk).map { index ->
            fragmentManager.receiveFragment(fragmentPacket(fragment(MessageType.MESSAGE, index, total, chunk), recipient = broadcast))
        }

        assertTrue(results.dropLast(1).all { it == FragmentResult.Pending })
        assertEquals(FragmentResult.Refused, results.last())
        assertTrue(fragmentManager.getDebugInfo().contains("Global Buffered Bytes: 0"))
    }

    @Test
    fun `a complete set whose content is another type is refused`() {
        val inner = BitchatPacket(
            version = 1u, type = MessageType.LEAVE.value, senderID = hexStringToByteArray(senderID),
            recipientID = hexStringToByteArray(recipientID), timestamp = 1u, payload = ByteArray(8), ttl = 7u
        ).toBinaryData(padding = false)!!
        val payload = FragmentPayload(ByteArray(8) { 6 }, 0, 1, MessageType.ANNOUNCE.value, inner).encode()

        assertEquals(FragmentResult.Refused, fragmentManager.receiveFragment(fragmentPacket(payload)))
    }

    @Test
    fun `only fragments accepted for reassembly are kept for sync`() {
        assertTrue(FragmentResult.Pending.keptForSync)
        assertTrue(FragmentResult.Complete(fragmentPacket(ByteArray(16))).keptForSync)
        assertFalse(FragmentResult.Refused.keptForSync)
        assertFalse(FragmentResult.Skipped.keptForSync)
    }

    private fun fragment(type: MessageType, index: Int, total: Int, size: Int = 32) =
        FragmentPayload(ByteArray(8) { 5 }, index, total, type.value, ByteArray(size) { 1 }).encode()

    private fun fragmentPacket(payload: ByteArray, recipient: ByteArray = hexStringToByteArray(recipientID)) = BitchatPacket(
        version = 1u,
        type = MessageType.FRAGMENT.value,
        senderID = hexStringToByteArray(senderID),
        recipientID = recipient,
        timestamp = 1u,
        payload = payload,
        ttl = 7u
    )

    @Test
    fun `fragment payload refuses UInt16 truncation`() {
        assertThrows(IllegalArgumentException::class.java) {
            FragmentPayload(
                fragmentID = ByteArray(8) { 2 },
                index = 0,
                total = 65_536,
                originalType = MessageType.NOISE_ENCRYPTED.value,
                data = byteArrayOf(1)
            ).encode()
        }
    }

    @Test
    fun `generic public packet retains a 257 fragment outbound plan`() {
        val randomPayload = ByteArray(180 * 1024).also { Random(0xB17C4A7).nextBytes(it) }

        fun plan(contentSize: Int): List<BitchatPacket> = fragmentManager.createFragments(
            BitchatPacket(
                version = 2u,
                type = MessageType.FILE_TRANSFER.value,
                senderID = hexStringToByteArray(senderID),
                recipientID = com.bitchat.android.protocol.SpecialRecipients.BROADCAST,
                timestamp = 1u,
                payload = randomPayload.copyOf(contentSize),
                signature = ByteArray(64) { 7 },
                ttl = 7u
            )
        )

        var low = 1
        var high = randomPayload.size
        while (low < high) {
            val mid = low + (high - low) / 2
            if (plan(mid).size >= 257) high = mid else low = mid + 1
        }

        assertEquals(257, plan(low).size)
    }

    private fun hexStringToByteArray(hexString: String): ByteArray {
        val result = ByteArray(hexString.length / 2)
        for (i in result.indices) {
            val byteStr = hexString.substring(i * 2, i * 2 + 2)
            result[i] = byteStr.toInt(16).toByte()
        }
        return result
    }
}
