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
    private val LINK_A = "link:a"
    private val LINK_B = "link:b"
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
            val result = manager.receiveFromLink(fragmentPacket(fragment(MessageType.NOISE_ENCRYPTED, index = 0, total = 2)))

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
            val result = manager.receiveFromLink(fragmentPacket(fragment(MessageType.NOISE_ENCRYPTED, index = 0, total = 2)))

            assertEquals(FragmentResult.Pending, result)
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun `file fragments are not buffered when media is refused`() {
        fragmentManager.acceptMediaFragments = false

        val result = fragmentManager.receiveFromLink(
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
            fragmentManager.receiveFromLink(fragmentPacket(fragment(MessageType.MESSAGE, index, total, chunk), recipient = broadcast))
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

        assertEquals(FragmentResult.Refused, fragmentManager.receiveFromLink(fragmentPacket(payload)))
    }

    @Test
    fun `only fragments accepted for reassembly are kept for sync`() {
        assertTrue(FragmentResult.Pending.keptForSync)
        assertTrue(FragmentResult.Complete(fragmentPacket(ByteArray(16))).keptForSync)
        assertFalse(FragmentResult.Refused.keptForSync)
        assertFalse(FragmentResult.Skipped.keptForSync)
    }

    @Test
    fun `same fragment ID from two senders assembles both messages`() {
        val senderA = "a1a2a3a4a5a6a7a8"
        val senderB = "b1b2b3b4b5b6b7b8"
        val sharedID = ByteArray(8) { 0x42 }
        val packetA = privatePacket(senderA, seed = 1)
        val packetB = privatePacket(senderB, seed = 2)
        val fragmentsA = fragmentsWithID(packetA, sharedID)
        val fragmentsB = fragmentsWithID(packetB, sharedID)
        assertEquals(fragmentsA.size, fragmentsB.size)
        assertTrue(fragmentsA.size > 1)

        // A third sender reusing the ID with different metadata only affects its own assembly.
        val intruder = FragmentPayload(sharedID, 0, fragmentsA.size + 1, MessageType.NOISE_ENCRYPTED.value, ByteArray(32) { 9 }).encode()

        val completed = mutableListOf<BitchatPacket>()
        for (i in fragmentsA.indices) {
            fragmentManager.handleFragment(fragmentsA[i])?.let(completed::add)
            if (i == 0) {
                assertEquals(FragmentResult.Pending, fragmentManager.receiveFromLink(fragmentPacket(intruder, sender = "c1c2c3c4c5c6c7c8")))
            }
            fragmentManager.handleFragment(fragmentsB[i])?.let(completed::add)
        }

        assertEquals(2, completed.size)
        val bySender = completed.associateBy { it.senderID.joinToString("") { b -> "%02x".format(b) } }
        assertArrayEquals(packetA.payload, bySender.getValue(senderA).payload)
        assertArrayEquals(packetB.payload, bySender.getValue(senderB).payload)
    }

    @Test
    fun `one sender opening many assemblies cannot evict another sender's assembly`() {
        val victim = "b1b2b3b4b5b6b7b8"
        val flooder = "f1f2f3f4f5f6f7f8"
        val packet = privatePacket(victim, seed = 3)
        val fragments = fragmentsWithID(packet, ByteArray(8) { 0x11 })
        assertTrue(fragments.size > 1)

        assertEquals(FragmentResult.Pending, fragmentManager.receiveFromLink(fragments.first()))

        val maxActive = com.bitchat.android.util.AppConstants.Fragmentation.MAX_ACTIVE_FRAGMENT_SETS
        val perSender = com.bitchat.android.util.AppConstants.Fragmentation.MAX_ACTIVE_FRAGMENT_SETS_PER_LINK_SENDER
        val results = (0 until maxActive + 16).map { n ->
            val id = ByteArray(8) { i -> if (i < 4) (n shr (8 * i)).toByte() else 0x7F }
            val flood = FragmentPayload(id, 0, 2, MessageType.NOISE_ENCRYPTED.value, ByteArray(32) { 1 }).encode()
            fragmentManager.receiveFromLink(fragmentPacket(flood, sender = flooder))
        }
        // The pair quota never evicts: assemblies beyond it are relayed but not kept.
        assertTrue(results.take(perSender).all { it == FragmentResult.Pending })
        assertTrue(results.drop(perSender).all { it == FragmentResult.Skipped })
        assertTrue(fragmentManager.getDebugInfo().contains("Active Fragment Sets: ${perSender + 1}"))

        val completed = fragments.drop(1).mapNotNull { fragmentManager.handleFragment(it) }
        assertEquals(1, completed.size)
        assertArrayEquals(packet.senderID, completed.single().senderID)
        assertArrayEquals(packet.payload, completed.single().payload)

        // A sender that starts after the flood still gets a slot.
        val late = privatePacket("c1c2c3c4c5c6c7c8", seed = 4)
        val lateCompleted = fragmentsWithID(late, ByteArray(8) { 0x22 }).mapNotNull { fragmentManager.handleFragment(it) }
        assertEquals(1, lateCompleted.size)
        assertArrayEquals(late.payload, lateCompleted.single().payload)
    }

    @Test
    fun `an attacker on another link cannot evict the victim's assembly or exceed the link quota`() {
        val victim = "b1b2b3b4b5b6b7b8"
        val packet = privatePacket(victim, seed = 5)
        val victimID = ByteArray(8) { 0x33 }
        val fragments = fragmentsWithID(packet, victimID)
        assertEquals(FragmentResult.Pending, fragmentManager.receiveFromLink(fragments.first(), LINK_A))

        // Reusing the victim's sender and fragment ID: conflicting metadata and a different
        // payload for a received index are both left out of the assembly.
        val conflicting = FragmentPayload(victimID, 1, fragments.size + 1, MessageType.NOISE_ENCRYPTED.value, ByteArray(32) { 9 }).encode()
        assertEquals(FragmentResult.Skipped, fragmentManager.receiveFromLink(fragmentPacket(conflicting, sender = victim), LINK_B))
        val first = requireNotNull(FragmentPayload.decode(fragments.first().payload))
        val overwrite = FragmentPayload(victimID, 0, first.total, first.originalType, ByteArray(first.data.size) { 9 }).encode()
        assertEquals(FragmentResult.Skipped, fragmentManager.receiveFromLink(fragmentPacket(overwrite, sender = victim), LINK_B))

        // Floods on link B with the victim's sender ID, then with invented sender IDs.
        val invented = (1..8).map { "0e0e0e0e0e0e0e0$it" }
        var n = 0
        (listOf(victim) + invented).forEach { sender ->
            repeat(80) {
                n++
                val id = ByteArray(8) { i -> if (i < 4) (n shr (8 * i)).toByte() else 0x6E }
                fragmentManager.receiveFromLink(
                    fragmentPacket(FragmentPayload(id, 0, 2, MessageType.NOISE_ENCRYPTED.value, ByteArray(32) { 1 }).encode(), sender = sender),
                    LINK_B
                )
            }
        }
        val perLink = com.bitchat.android.util.AppConstants.Fragmentation.MAX_ACTIVE_FRAGMENT_SETS_PER_LINK
        assertTrue(fragmentManager.getDebugInfo().contains("Active Fragment Sets: ${perLink + 1}"))

        val completed = fragments.drop(1).mapNotNull { fragmentManager.handleFragment(it, LINK_A) }
        assertEquals(1, completed.size)
        assertArrayEquals(packet.payload, completed.single().payload)
    }

    @Test
    fun `a relay link carrying many senders still assembles up to the link quota`() {
        val perLink = com.bitchat.android.util.AppConstants.Fragmentation.MAX_ACTIVE_FRAGMENT_SETS_PER_LINK
        val messages = (0 until perLink).map { i ->
            val packet = privatePacket("5e5e5e5e5e5e%04x".format(i), seed = 100L + i)
            packet to fragmentsWithID(packet, ByteArray(8) { 0x44 })
        }
        messages.forEach { (_, fragments) ->
            assertEquals(FragmentResult.Pending, fragmentManager.receiveFromLink(fragments.first(), LINK_A))
        }
        val extra = fragmentsWithID(privatePacket("5e5e5e5e5e5effff", seed = 99), ByteArray(8) { 0x44 })
        assertEquals(FragmentResult.Skipped, fragmentManager.receiveFromLink(extra.first(), LINK_A))

        messages.forEach { (packet, fragments) ->
            val completed = fragments.drop(1).mapNotNull { fragmentManager.handleFragment(it, LINK_A) }
            assertEquals(1, completed.size)
            assertArrayEquals(packet.payload, completed.single().payload)
        }
    }

    @Test
    fun `fragments of one assembly arriving over several links complete it`() {
        val packet = privatePacket(senderID, seed = 6)
        val fragments = fragmentsWithID(packet, ByteArray(8) { 0x55 })
        val completed = fragments.mapIndexedNotNull { i, fragment ->
            fragmentManager.handleFragment(fragment, if (i % 2 == 0) LINK_A else LINK_B)
        }
        assertEquals(1, completed.size)
        assertArrayEquals(packet.payload, completed.single().payload)
    }

    @Test
    fun `inconsistent metadata no longer clears the assembly`() {
        val packet = privatePacket(senderID, seed = 7)
        val id = ByteArray(8) { 0x66 }
        val fragments = fragmentsWithID(packet, id)
        assertEquals(FragmentResult.Pending, fragmentManager.receiveFromLink(fragments.first()))

        val conflicting = FragmentPayload(id, 1, fragments.size + 3, MessageType.NOISE_ENCRYPTED.value, ByteArray(32) { 9 }).encode()
        assertEquals(FragmentResult.Skipped, fragmentManager.receiveFromLink(fragmentPacket(conflicting)))
        assertTrue(fragmentManager.getDebugInfo().contains("Active Fragment Sets: 1"))

        val completed = fragments.drop(1).mapNotNull { fragmentManager.handleFragment(it) }
        assertEquals(1, completed.size)
        assertArrayEquals(packet.payload, completed.single().payload)
    }

    @Test
    fun `a received index is never overwritten`() {
        val packet = privatePacket(senderID, seed = 8)
        val fragments = fragmentsWithID(packet, ByteArray(8) { 0x77 })
        assertEquals(FragmentResult.Pending, fragmentManager.receiveFromLink(fragments.first()))

        val first = requireNotNull(FragmentPayload.decode(fragments.first().payload))
        val replaced = fragments.first().copy(
            payload = FragmentPayload(first.fragmentID, first.index, first.total, first.originalType, ByteArray(first.data.size) { 9 }).encode()
        )
        assertEquals(FragmentResult.Skipped, fragmentManager.receiveFromLink(replaced))
        // An identical duplicate changes nothing.
        assertEquals(FragmentResult.Pending, fragmentManager.receiveFromLink(fragments.first()))

        val completed = fragments.drop(1).mapNotNull { fragmentManager.handleFragment(it) }
        assertEquals(1, completed.size)
        assertArrayEquals(packet.payload, completed.single().payload)
    }

    @Test
    fun `a genuine fragment that loses the race to a forged copy is still relayed`() {
        val victim = "b1b2b3b4b5b6b7b8"
        val packet = privatePacket(victim, seed = 9)
        val id = ByteArray(8) { 0x0A }
        val fragments = fragmentsWithID(packet, id)
        assertTrue(fragments.size > 2)
        assertEquals(FragmentResult.Pending, fragmentManager.receiveFromLink(fragments[0], LINK_A))

        // A neighbor forges index 1 before the genuine copy: the genuine copy is not kept,
        // but it must stay relayable (Skipped, not Refused) so the forgery cannot censor it.
        val genuine = requireNotNull(FragmentPayload.decode(fragments[1].payload))
        val forged = fragments[1].copy(
            payload = FragmentPayload(id, 1, genuine.total, genuine.originalType, ByteArray(genuine.data.size) { 9 }).encode()
        )
        assertEquals(FragmentResult.Pending, fragmentManager.receiveFromLink(forged, LINK_B))
        val bytes = "Global Buffered Bytes: ${FragmentPayload.decode(fragments[0].payload)!!.data.size + genuine.data.size}"
        assertTrue(fragmentManager.getDebugInfo().contains(bytes))
        assertEquals(FragmentResult.Skipped, fragmentManager.receiveFromLink(fragments[1], LINK_A))
        // A relay forwarding both copies over a single link must not censor it either.
        assertEquals(FragmentResult.Skipped, fragmentManager.receiveFromLink(fragments[1], LINK_B))
        assertTrue(fragmentManager.getDebugInfo().contains(bytes))

        // The same holds when the forgery opened the assembly with other metadata.
        val otherID = ByteArray(8) { 0x0B }
        val other = fragmentsWithID(privatePacket(victim, seed = 10), otherID)
        val opener = FragmentPayload(otherID, 0, other.size + 1, MessageType.NOISE_ENCRYPTED.value, ByteArray(32) { 9 }).encode()
        assertEquals(FragmentResult.Pending, fragmentManager.receiveFromLink(fragmentPacket(opener, sender = victim), LINK_B))
        assertEquals(FragmentResult.Skipped, fragmentManager.receiveFromLink(other[1], LINK_A))
    }

    @Test
    fun `a fragment that overflows an assembly opened by another link is relayed and does not clear it`() {
        val broadcast = com.bitchat.android.protocol.SpecialRecipients.BROADCAST
        val chunk = 400
        val fill = com.bitchat.android.protocol.PacketPayloadLimits.PUBLIC_TEXT_FRAME_BYTES / chunk
        val total = fill + 2
        (0 until fill).forEach { index ->
            assertEquals(
                FragmentResult.Pending,
                fragmentManager.receiveFromLink(fragmentPacket(fragment(MessageType.MESSAGE, index, total, chunk), recipient = broadcast), LINK_A)
            )
        }
        val overflow = fragmentPacket(fragment(MessageType.MESSAGE, fill, total, chunk), recipient = broadcast)

        assertEquals(FragmentResult.Skipped, fragmentManager.receiveFromLink(overflow, LINK_B))
        assertTrue(fragmentManager.getDebugInfo().contains("Global Buffered Bytes: ${fill * chunk}"))
        // The opening link overflowing its own assembly still drops it.
        assertEquals(FragmentResult.Refused, fragmentManager.receiveFromLink(overflow, LINK_A))
        assertTrue(fragmentManager.getDebugInfo().contains("Global Buffered Bytes: 0"))
    }

    @Test
    fun `the genuine fragment completing an assembly a forgery spoiled is still relayed`() {
        val packet = privatePacket(senderID, seed = 11)
        val id = ByteArray(8) { 0x0C }
        val fragments = fragmentsWithID(packet, id)
        assertTrue(fragments.size > 2)
        // A forged first index (bad header) arrives before the genuine one.
        val genuine = requireNotNull(FragmentPayload.decode(fragments[0].payload))
        val forged = fragments[0].copy(
            payload = FragmentPayload(id, 0, genuine.total, genuine.originalType, ByteArray(genuine.data.size) { 9 }).encode()
        )
        assertEquals(FragmentResult.Pending, fragmentManager.receiveFromLink(forged, LINK_B))
        val results = fragments.map { fragmentManager.receiveFromLink(it, LINK_A) }

        // The set completes with the forged index and cannot decode: nothing is delivered,
        // and the genuine fragment that completed it is relayed rather than refused.
        assertTrue(results.none { it is FragmentResult.Complete || it == FragmentResult.Refused })
        assertEquals(FragmentResult.Skipped, results.last())
        assertTrue(fragmentManager.getDebugInfo().contains("Active Fragment Sets: 0"))
    }

    @Test
    fun `a sender ID claimed through an honest relay cannot evict the real sender's assembly`() {
        val victim = "b1b2b3b4b5b6b7b8"
        val packet = privatePacket(victim, seed = 12)
        val fragments = fragmentsWithID(packet, ByteArray(8) { 0x0D })
        assertEquals(FragmentResult.Pending, fragmentManager.receiveFromLink(fragments.first(), LINK_A))

        // Same relay link, same claimed sender, many new fragment IDs.
        val perSender = com.bitchat.android.util.AppConstants.Fragmentation.MAX_ACTIVE_FRAGMENT_SETS_PER_LINK_SENDER
        val results = (0 until perSender * 2).map { n ->
            val id = ByteArray(8) { i -> if (i < 4) (n shr (8 * i)).toByte() else 0x5D }
            fragmentManager.receiveFromLink(
                fragmentPacket(FragmentPayload(id, 0, 2, MessageType.NOISE_ENCRYPTED.value, ByteArray(32) { 1 }).encode(), sender = victim),
                LINK_A
            )
        }
        assertEquals(perSender - 1, results.count { it == FragmentResult.Pending })
        assertTrue(results.drop(perSender - 1).all { it == FragmentResult.Skipped })

        val completed = fragments.drop(1).mapNotNull { fragmentManager.handleFragment(it, LINK_A) }
        assertEquals(1, completed.size)
        assertArrayEquals(packet.payload, completed.single().payload)
    }

    @Test
    fun `closing a link frees the assemblies it opened and keeps it from opening more`() {
        val victim = privatePacket(senderID, seed = 13)
        val victimFragments = fragmentsWithID(victim, ByteArray(8) { 0x0E })
        assertEquals(FragmentResult.Pending, fragmentManager.receiveFromLink(victimFragments.first(), LINK_A))

        val perLink = com.bitchat.android.util.AppConstants.Fragmentation.MAX_ACTIVE_FRAGMENT_SETS_PER_LINK
        fun flood(link: String, tag: Int) = (0 until perLink).map { n ->
            val id = ByteArray(8) { i -> if (i < 4) (n shr (8 * i)).toByte() else tag.toByte() }
            fragmentManager.receiveFromLink(
                fragmentPacket(FragmentPayload(id, 0, 2, MessageType.NOISE_ENCRYPTED.value, ByteArray(32) { 1 }).encode(), sender = "0e0e0e0e0e0e%04x".format(n)),
                link
            )
        }
        // One radio reconnecting gets a fresh link each time: each closed link gives its slots back.
        repeat(5) { round ->
            val link = "link:reconnect-$round"
            assertTrue(flood(link, 0x40 + round).all { it == FragmentResult.Pending })
            fragmentManager.onIngressLinkClosed(link)
            assertTrue(fragmentManager.getDebugInfo().contains("Active Fragment Sets: 1"))
            // Fragments still queued from the closed link are relayed but never kept.
            assertTrue(flood(link, 0x60 + round).all { it == FragmentResult.Skipped })
        }
        assertTrue(fragmentManager.getDebugInfo().contains("Global Buffered Bytes: ${FragmentPayload.decode(victimFragments.first().payload)!!.data.size}"))

        val completed = victimFragments.drop(1).mapNotNull { fragmentManager.handleFragment(it, LINK_A) }
        assertEquals(1, completed.size)
        assertArrayEquals(victim.payload, completed.single().payload)
    }

    @Test
    fun `closing the link that opened a shared assembly hands it to another contributor`() {
        val packet = privatePacket(senderID, seed = 14)
        val fragments = fragmentsWithID(packet, ByteArray(8) { 0x0F })
        assertTrue(fragments.size > 2)
        val sizes = fragments.map { FragmentPayload.decode(it.payload)!!.data.size }

        // LINK_B opens the assembly with a genuine fragment, LINK_A contributes the next one.
        assertEquals(FragmentResult.Pending, fragmentManager.receiveFromLink(fragments[0], LINK_B))
        assertEquals(FragmentResult.Pending, fragmentManager.receiveFromLink(fragments[1], LINK_A))
        // An assembly only LINK_B fed goes away with it.
        val lone = FragmentPayload(ByteArray(8) { 0x1F }, 0, 2, MessageType.NOISE_ENCRYPTED.value, ByteArray(32) { 1 }).encode()
        assertEquals(FragmentResult.Pending, fragmentManager.receiveFromLink(fragmentPacket(lone), LINK_B))

        fragmentManager.onIngressLinkClosed(LINK_B)

        assertTrue(fragmentManager.getDebugInfo().contains("Active Fragment Sets: 1"))
        assertTrue(fragmentManager.getDebugInfo().contains("Global Buffered Bytes: ${sizes[0] + sizes[1]}"))
        // The assembly now counts against LINK_A: closing LINK_A drops it.
        val completed = fragments.drop(2).mapNotNull { fragmentManager.handleFragment(it, LINK_A) }
        assertEquals(1, completed.size)
        assertArrayEquals(packet.payload, completed.single().payload)
        assertTrue(fragmentManager.getDebugInfo().contains("Global Buffered Bytes: 0"))
    }

    @Test
    fun `a handed-over assembly counts against its new link`() {
        val fragments = fragmentsWithID(privatePacket(senderID, seed = 15), ByteArray(8) { 0x2E })
        assertEquals(FragmentResult.Pending, fragmentManager.receiveFromLink(fragments[0], LINK_B))
        assertEquals(FragmentResult.Pending, fragmentManager.receiveFromLink(fragments[1], LINK_A))
        fragmentManager.onIngressLinkClosed(LINK_B)

        fragmentManager.onIngressLinkClosed(LINK_A)
        assertTrue(fragmentManager.getDebugInfo().contains("Active Fragment Sets: 0"))
        assertTrue(fragmentManager.getDebugInfo().contains("Global Buffered Bytes: 0"))
    }

    @Test
    fun `one link cannot buffer more than its byte share but still fits one assembly of the largest size`() {
        val limits = com.bitchat.android.util.AppConstants.Fragmentation
        val type = MessageType.NOISE_ENCRYPTED.value
        val assemblyCap = minOf(limits.MAX_FRAGMENT_TOTAL_BYTES, com.bitchat.android.protocol.PacketPayloadLimits.maxFrameBytes(type))
        assertTrue(limits.MAX_FRAGMENT_BYTES_PER_LINK >= assemblyCap)
        assertTrue(limits.MAX_FRAGMENT_BYTES_PER_LINK < limits.MAX_GLOBAL_FRAGMENT_TOTAL_BYTES)
        val chunk = 64 * 1024
        val perAssembly = assemblyCap / chunk
        val total = perAssembly + 1
        fun send(link: String, idTag: Int, index: Int) = fragmentManager.receiveFromLink(
            fragmentPacket(FragmentPayload(ByteArray(8) { idTag.toByte() }, index, total, type, ByteArray(chunk) { 1 }).encode()),
            link
        )

        // One assembly of the largest size fits on a link...
        var buffered = 0L
        for (index in 0 until perAssembly) {
            assertEquals(FragmentResult.Pending, send(LINK_A, 0x10, index))
            buffered += chunk
        }
        // ...then LINK_A fills its byte share with assemblies kept just under their own bound.
        var tag = 0x11
        var index = 0
        while (buffered + chunk <= limits.MAX_FRAGMENT_BYTES_PER_LINK) {
            assertEquals(FragmentResult.Pending, send(LINK_A, tag, index))
            buffered += chunk
            index++
            if (index == perAssembly - 1) { tag++; index = 0 }
        }
        // Nothing more from LINK_A, whether it opens or grows an assembly...
        assertEquals(FragmentResult.Skipped, send(LINK_A, 0x7A, 0))
        if (index == 0) {
            assertEquals(FragmentResult.Skipped, send(LINK_A, tag - 1, perAssembly - 1))
        } else {
            assertEquals(FragmentResult.Skipped, send(LINK_A, tag, index))
        }
        assertTrue(fragmentManager.getDebugInfo().contains("Global Buffered Bytes: $buffered"))
        // ...while another link still has room.
        assertEquals(FragmentResult.Pending, send(LINK_B, 0x7B, 0))
    }

    @Test
    fun `an expired assembly gives its link quota back`() {
        var now = 1_000_000L
        val manager = FragmentManager(clock = { now })
        try {
            val perLink = com.bitchat.android.util.AppConstants.Fragmentation.MAX_ACTIVE_FRAGMENT_SETS_PER_LINK
            fun open(n: Int) = manager.receiveFromLink(
                fragmentPacket(
                    FragmentPayload(ByteArray(8) { 0x2F }, 0, 2, MessageType.NOISE_ENCRYPTED.value, ByteArray(32) { 1 }).encode(),
                    sender = "2f2f2f2f2f2f%04x".format(n)
                ),
                LINK_A
            )
            assertTrue((0 until perLink).all { open(it) == FragmentResult.Pending })
            assertEquals(FragmentResult.Skipped, open(perLink))

            now += com.bitchat.android.util.AppConstants.Fragmentation.FRAGMENT_TIMEOUT_MS - 1
            manager.cleanupOldFragments()
            assertEquals(FragmentResult.Skipped, open(perLink))

            now += 2
            manager.cleanupOldFragments()
            assertTrue(manager.getDebugInfo().contains("Active Fragment Sets: 0"))
            assertTrue(manager.getDebugInfo().contains("Global Buffered Bytes: 0"))
            assertEquals(FragmentResult.Pending, open(perLink))
        } finally {
            manager.shutdown()
        }
    }

    private fun FragmentManager.receiveFromLink(packet: BitchatPacket, link: String = LINK_A) =
        receiveFragment(packet, link)

    private fun privatePacket(sender: String, seed: Long) = BitchatPacket(
        version = 1u,
        type = MessageType.NOISE_ENCRYPTED.value,
        senderID = hexStringToByteArray(sender),
        recipientID = hexStringToByteArray(recipientID),
        timestamp = 1u,
        payload = ByteArray(1500).also { Random(seed).nextBytes(it) },
        ttl = 7u
    )

    /** Fragments [packet] and rewrites every fragment to carry [fragmentID]. */
    private fun fragmentsWithID(packet: BitchatPacket, fragmentID: ByteArray): List<BitchatPacket> =
        fragmentManager.createFragments(packet).map { fragment ->
            val decoded = requireNotNull(FragmentPayload.decode(fragment.payload))
            fragment.copy(payload = FragmentPayload(fragmentID, decoded.index, decoded.total, decoded.originalType, decoded.data).encode())
        }

    private fun fragment(type: MessageType, index: Int, total: Int, size: Int = 32) =
        FragmentPayload(ByteArray(8) { 5 }, index, total, type.value, ByteArray(size) { 1 }).encode()

    private fun fragmentPacket(
        payload: ByteArray,
        recipient: ByteArray = hexStringToByteArray(recipientID),
        sender: String = senderID
    ) = BitchatPacket(
        version = 1u,
        type = MessageType.FRAGMENT.value,
        senderID = hexStringToByteArray(sender),
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
