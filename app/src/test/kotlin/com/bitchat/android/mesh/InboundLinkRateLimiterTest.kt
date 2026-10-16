package com.bitchat.android.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboundLinkRateLimiterTest {
    private var nowMs = 0L

    private fun limiter(maxLinks: Int = 64) = InboundLinkRateLimiter(
        perSecond = 10.0, burst = 5.0, maxTrackedLinks = maxLinks, now = { nowMs }
    )

    @Test
    fun `a link is cut once its burst is spent and recovers at its rate`() {
        val limiter = limiter()

        repeat(5) { assertTrue(limiter.tryAcquire("link-1")) }
        assertFalse(limiter.tryAcquire("link-1"))

        nowMs += 100
        assertTrue(limiter.tryAcquire("link-1"))
        assertFalse(limiter.tryAcquire("link-1"))
        assertEquals(2L, limiter.dropped)
    }

    @Test
    fun `one busy link does not starve another`() {
        val limiter = limiter()

        repeat(10) { limiter.tryAcquire("link-1") }

        assertTrue(limiter.tryAcquire("link-2"))
    }

    @Test
    fun `the link table stays bounded and forgets the least recent link`() {
        val limiter = limiter(maxLinks = 2)

        repeat(5) { limiter.tryAcquire("link-1") }
        limiter.tryAcquire("link-2")
        limiter.tryAcquire("link-3")

        assertEquals(2, limiter.trackedLinks)
        // link-1 was evicted, so it starts again from a full bucket.
        assertTrue(limiter.tryAcquire("link-1"))
    }

    @Test
    fun `forgetting a link drops its state`() {
        val limiter = limiter()
        limiter.tryAcquire("link-1")

        limiter.forget("link-1")

        assertEquals(0, limiter.trackedLinks)
    }
}
