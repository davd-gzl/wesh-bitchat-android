package com.bitchat.android.ui.pave

import com.bitchat.android.model.DeliveryStatus
import java.util.Date
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaveLogicTest {

    @Test
    fun `byte counter counts UTF-8 bytes, not characters`() {
        assertEquals(5, ComposerLimits.byteCount("salut"))
        assertEquals(2, ComposerLimits.byteCount("é"))
        assertEquals(4, ComposerLimits.byteCount("😀"))
    }

    @Test
    fun `counter appears only past 1800 bytes`() {
        assertFalse(ComposerLimits.counterVisible(1800))
        assertTrue(ComposerLimits.counterVisible(1801))
    }

    @Test
    fun `send is refused when blank or over 2000 bytes`() {
        assertFalse(ComposerLimits.canSend("   "))
        assertTrue(ComposerLimits.canSend("a".repeat(2000)))
        assertFalse(ComposerLimits.canSend("a".repeat(2001)))
        // 1000 "é" = 2000 bytes; one more ASCII character crosses the limit.
        assertTrue(ComposerLimits.canSend("é".repeat(1000)))
        assertFalse(ComposerLimits.canSend("é".repeat(1000) + "a"))
    }

    @Test
    fun `delivery maps to the four Pavé words`() {
        assertEquals(PaveDelivery.Waiting, PaveDelivery.from(null, isPrivate = true))
        assertEquals(PaveDelivery.Waiting, PaveDelivery.from(DeliveryStatus.Sending, isPrivate = true))
        assertEquals(PaveDelivery.Sent, PaveDelivery.from(DeliveryStatus.Sent, isPrivate = true))
        assertEquals(PaveDelivery.Sent, PaveDelivery.from(DeliveryStatus.PartiallyDelivered(1, 3), isPrivate = false))
        assertEquals(PaveDelivery.Received, PaveDelivery.from(DeliveryStatus.Delivered("n", Date(0)), isPrivate = true))
        assertEquals(PaveDelivery.Failed, PaveDelivery.from(DeliveryStatus.Failed("x"), isPrivate = false))
    }

    @Test
    fun `read receipts are never shown and public never says received`() {
        assertEquals(PaveDelivery.Received, PaveDelivery.from(DeliveryStatus.Read("n", Date(0)), isPrivate = true))
        assertEquals(PaveDelivery.Sent, PaveDelivery.from(DeliveryStatus.Delivered("n", Date(0)), isPrivate = false))
        assertEquals(PaveDelivery.Sent, PaveDelivery.from(DeliveryStatus.Read("n", Date(0)), isPrivate = false))
    }

    @Test
    fun `hold completes at exactly three seconds`() {
        assertEquals(0f, HoldToEraseTiming.progress(0), 0f)
        assertEquals(0.5f, HoldToEraseTiming.progress(1500), 0.0001f)
        assertFalse(HoldToEraseTiming.isComplete(2999))
        assertTrue(HoldToEraseTiming.isComplete(3000))
        assertEquals(1f, HoldToEraseTiming.progress(9000), 0f)
        assertEquals(0f, HoldToEraseTiming.progress(-5), 0f)
    }

    @Test
    fun `hold pulses get closer together and all land before completion`() {
        val pulses = HoldToEraseTiming.PULSES_MS
        val gaps = pulses.toList().zipWithNext { a, b -> b - a }
        assertTrue(gaps.zipWithNext().all { (a, b) -> b < a })
        assertTrue(pulses.all { it < HoldToEraseTiming.HOLD_MS })
        assertEquals(1, HoldToEraseTiming.pulsesDue(0))
        assertEquals(pulses.size, HoldToEraseTiming.pulsesDue(HoldToEraseTiming.HOLD_MS))
        assertFalse(HoldToEraseTiming.isStrongPulse(0))
        assertTrue(HoldToEraseTiming.isStrongPulse(pulses.size - 1))
    }

    @Test
    fun `generated nicknames look like Renard 42 and fit the limit`() {
        val random = Random(7)
        repeat(500) {
            val nickname = NicknameGenerator.generate(random)
            val (name, number) = nickname.split(' ')
            assertTrue(name in NicknameGenerator.NAMES)
            assertTrue(number.toInt() in 2..99)
            assertTrue(NicknameGenerator.isValid(nickname))
        }
    }

    @Test
    fun `another nickname differs from the current one`() {
        val random = Random(1)
        repeat(100) {
            val current = NicknameGenerator.generate(random)
            assertNotEquals(current, NicknameGenerator.next(current, random))
        }
    }

    @Test
    fun `typed nickname is cleaned and bounded`() {
        assertEquals("Nora", NicknameGenerator.clean("  Nora"))
        assertEquals(NicknameGenerator.MAX_LENGTH, NicknameGenerator.clean("x".repeat(40)).length)
        assertFalse(NicknameGenerator.isValid("   "))
    }

    @Test
    fun `status pill picks one truth`() {
        assertEquals(PaveLinkStatus.InRange(7), PaveLinkStatus.from(true, 7, false))
        assertEquals(PaveLinkStatus.NobodyInRange, PaveLinkStatus.from(true, 0, false))
        assertEquals(PaveLinkStatus.BluetoothOff, PaveLinkStatus.from(false, 3, false))
        assertEquals(PaveLinkStatus.ViaInternet(3), PaveLinkStatus.from(true, 3, true))
        assertEquals(PaveLinkStatus.ViaInternet(0), PaveLinkStatus.from(false, 3, true))
    }

    @Test
    fun `exchange completes only with both checks`() {
        assertFalse(ExchangeProgress(theyScannedMe = true).complete)
        assertFalse(ExchangeProgress(iScannedThem = true).complete)
        assertTrue(ExchangeProgress(theyScannedMe = true, iScannedThem = true).complete)
    }

    @Test
    fun `no location permission is requested from API 31`() {
        val modern = paveBluetoothRuntimePermissions(31)
        assertTrue(modern.none { it.contains("LOCATION") })
        assertTrue(modern.contains(android.Manifest.permission.BLUETOOTH_SCAN))
        assertTrue(paveBluetoothRuntimePermissions(30).contains(android.Manifest.permission.ACCESS_FINE_LOCATION))
    }

    @Test
    fun `remove animations makes every duration instant`() {
        assertEquals(120, WeshMotion.duration(WeshMotion.STATE_CHANGE_MS, animationsEnabled = true))
        assertEquals(0, WeshMotion.duration(WeshMotion.SHEET_MS, animationsEnabled = false))
    }
}
