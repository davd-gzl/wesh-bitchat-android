package com.bitchat.android.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboundLinkRateLimiterTest {
    private var nowMs = 0L

    private fun limiter(
        maxLinks: Int = 64,
        globalPerSecond: Double = 1_000.0,
        globalBurst: Double = 1_000.0,
        minBurst: Double = 1.0
    ) = InboundLinkRateLimiter(
        perSecond = 10.0, burst = 5.0,
        globalPerSecond = globalPerSecond, globalBurst = globalBurst,
        minPerSecond = 1.0, minBurst = minBurst,
        maxTrackedLinks = maxLinks, now = { nowMs }
    )

    @Test
    fun `a device is cut once its burst is spent and recovers at its rate`() {
        val limiter = limiter()

        repeat(5) { assertTrue(limiter.tryAcquire("AA:01", "link-1")) }
        assertFalse(limiter.tryAcquire("AA:01", "link-1"))

        nowMs += 100
        assertTrue(limiter.tryAcquire("AA:01", "link-1"))
        assertFalse(limiter.tryAcquire("AA:01", "link-1"))
        assertEquals(2L, limiter.dropped)
    }

    @Test
    fun `reconnecting the same device does not reset its bucket`() {
        val limiter = limiter()
        repeat(5) { limiter.tryAcquire("AA:01", "link-1") }

        // Same device address, new connection ID.
        assertFalse(limiter.tryAcquire("AA:01", "link-2"))
    }

    @Test
    fun `the connection ID is the key when no address is known`() {
        val limiter = limiter()
        repeat(5) { limiter.tryAcquire(null, "link-1") }

        assertFalse(limiter.tryAcquire(null, "link-1"))
        assertTrue(limiter.tryAcquire(null, "link-2"))
    }

    @Test
    fun `one busy device does not starve another`() {
        val limiter = limiter()

        repeat(10) { limiter.tryAcquire("AA:01", "link-1") }

        assertTrue(limiter.tryAcquire("AA:02", "link-2"))
    }

    @Test
    fun `all links together stay under the shared ceiling plus each link's guaranteed minimum`() {
        val limiter = limiter(globalPerSecond = 10.0, globalBurst = 8.0, minBurst = 1.0)

        val perDevice = (1..4).map { device -> (1..5).count { limiter.tryAcquire("AA:0$device", "link-$device") } }

        assertTrue(perDevice.sum() <= 8 + 4 * 1)
        assertEquals(listOf(5, 4, 1, 1), perDevice)
    }

    @Test
    fun `busy devices exhausting the shared ceiling do not starve a quiet link`() {
        val limiter = limiter(globalPerSecond = 10.0, globalBurst = 8.0, minBurst = 3.0)
        (1..4).forEach { device -> repeat(50) { limiter.tryAcquire("AA:0$device", "link-$device") } }
        assertFalse("The shared ceiling is spent", limiter.tryAcquire("AA:01", "link-1"))

        val quiet = (1..3).count { limiter.tryAcquire("AA:09", "link-9") }

        assertEquals(3, quiet)
    }

    @Test
    fun `a full table evicts the least throttled bucket, never a throttled one`() {
        val limiter = limiter(maxLinks = 2)
        repeat(5) { limiter.tryAcquire("AA:01", "link-1") }
        limiter.tryAcquire("AA:02", "link-2")

        limiter.tryAcquire("AA:03", "link-3")

        assertEquals(2, limiter.trackedLinks)
        assertFalse("A throttled device keeps its state", limiter.tryAcquire("AA:01", "link-1"))
    }

    @Test
    fun `a bucket idle for a few refill windows is aged out first`() {
        val limiter = limiter(maxLinks = 2)
        repeat(5) { limiter.tryAcquire("AA:01", "link-1") }
        nowMs += 10_000
        repeat(5) { limiter.tryAcquire("AA:02", "link-2") }

        limiter.tryAcquire("AA:03", "link-3")

        assertFalse("The active, throttled device is kept", limiter.tryAcquire("AA:02", "link-2"))
        assertEquals(2, limiter.trackedLinks)
    }
}
