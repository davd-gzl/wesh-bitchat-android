package com.bitchat.android.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicInboundBudgetTest {
    private var time = 1_000_000L
    private val budget = PublicInboundBudget(
        now = { time },
        global = PublicInboundBudget.Rate(perSecond = 10.0, burst = 20.0),
        perAuthenticatedSource = PublicInboundBudget.Rate(perSecond = 1.0, burst = 3.0),
        perLink = PublicInboundBudget.Rate(perSecond = 4.0, burst = 8.0),
        unauthenticated = PublicInboundBudget.Rate(perSecond = 1.0, burst = 4.0)
    )

    @Test
    fun `an authenticated source has its own budget and recovers over time`() {
        repeat(3) { assertTrue(budget.tryAdmit("noise:a", "link-1")) }
        assertFalse(budget.tryAdmit("noise:a", "link-1"))
        assertTrue("another authenticated source keeps its own budget", budget.tryAdmit("noise:b", "link-1"))
        assertEquals(1L, budget.dropped)

        time += 1_000
        assertTrue(budget.tryAdmit("noise:a", "link-1"))
    }

    @Test
    fun `rotating unauthenticated identities only drain one shared bucket`() {
        val admitted = (0 until 50).count { budget.tryAdmit(null, "link-$it") }
        assertEquals(4, admitted)
        assertTrue("authenticated senders are unaffected", budget.tryAdmit("noise:a", "link-1"))
    }

    @Test
    fun `traffic without a link identity pays into the shared unauthenticated bucket`() {
        val admitted = (0 until 10).count { budget.tryAdmit("noise:$it", null) }
        assertEquals(4, admitted)
        assertFalse(budget.tryAdmit(null, null))
    }

    @Test
    fun `one flooding link is bounded`() {
        val admitted = (0 until 50).count { budget.tryAdmit("noise:$it", "link-1") }
        assertEquals(8, admitted)
        assertTrue("other links keep working", budget.tryAdmit("noise:z", "link-2"))
    }

    @Test
    fun `global budget bounds the sum of all links`() {
        val admitted = (0 until 100).count { budget.tryAdmit("noise:$it", "link-$it") }
        assertEquals(20, admitted)
    }

    @Test
    fun `a full source table sends new identities to the shared bucket instead of a fresh burst`() {
        val small = PublicInboundBudget(
            now = { time },
            global = PublicInboundBudget.Rate(perSecond = 0.0, burst = 1_000.0),
            perAuthenticatedSource = PublicInboundBudget.Rate(perSecond = 0.0, burst = 2.0),
            perLink = PublicInboundBudget.Rate(perSecond = 0.0, burst = 1_000.0),
            unauthenticated = PublicInboundBudget.Rate(perSecond = 0.0, burst = 3.0),
            maxTrackedSources = 4
        )
        repeat(4) { assertTrue(small.tryAdmit("noise:$it", "link")) }
        val admitted = (10 until 30).count { small.tryAdmit("noise:$it", "link") }
        assertEquals(3, admitted)
    }
}
