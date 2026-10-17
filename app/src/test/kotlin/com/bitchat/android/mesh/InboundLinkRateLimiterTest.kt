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
        minBurst: Double = 1.0,
        reserveBurst: Double = 100.0
    ) = InboundLinkRateLimiter(
        perSecond = 10.0, burst = 5.0,
        globalPerSecond = globalPerSecond, globalBurst = globalBurst,
        minPerSecond = 1.0, minBurst = minBurst,
        globalReservePerSecond = 1.0, globalReserveBurst = reserveBurst,
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
    fun `all links together stay under the shared ceiling`() {
        val limiter = limiter(globalPerSecond = 10.0, globalBurst = 8.0, minBurst = 1.0)

        val perDevice = (1..4).map { device -> (1..5).count { limiter.tryAcquire("AA:0$device", "link-$device") } }

        // New devices have earned no allowance yet.
        assertEquals(listOf(5, 3, 0, 0), perDevice)
    }

    @Test
    fun `busy devices exhausting the shared ceiling do not starve a quiet link`() {
        val limiter = limiter(globalPerSecond = 10.0, globalBurst = 8.0, minBurst = 3.0)
        assertTrue(limiter.tryAcquire("AA:09", "link-9"))
        nowMs += 3_000 // The quiet link stays idle and earns its allowance.
        (1..4).forEach { device -> repeat(50) { limiter.tryAcquire("AA:0$device", "link-$device") } }
        assertFalse("The shared ceiling is spent", limiter.tryAcquire("AA:01", "link-1"))

        val quiet = (1..3).count { limiter.tryAcquire("AA:09", "link-9") }

        assertEquals(3, quiet)
    }

    @Test
    fun `churned links cannot exceed the shared ceiling plus the shared reserve`() {
        val limiter = limiter(maxLinks = 1_000, globalPerSecond = 10.0, globalBurst = 8.0, minBurst = 3.0, reserveBurst = 4.0)

        val fresh = (1..100).sumOf { link -> (1..5).count { limiter.tryAcquire("BB:$link", "fresh-$link") } }
        assertEquals("Fresh links get no allowance of their own", 8, fresh)

        nowMs += 10_000 // Every link idles and earns its allowance; the shared buckets refill too.
        val idled = (1..100).sumOf { link -> (1..5).count { limiter.tryAcquire("BB:$link", "fresh-$link") } }
        assertEquals(8 + 4, idled)
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
