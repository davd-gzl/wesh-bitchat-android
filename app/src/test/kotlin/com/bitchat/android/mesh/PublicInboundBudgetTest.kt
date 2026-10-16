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
        perPeer = PublicInboundBudget.Rate(perSecond = 1.0, burst = 3.0),
        perLink = PublicInboundBudget.Rate(perSecond = 4.0, burst = 8.0)
    )

    @Test
    fun `one peer cannot exceed its burst and recovers over time`() {
        repeat(3) { assertTrue(budget.tryAdmit("peer-a", "link-1")) }
        assertFalse(budget.tryAdmit("peer-a", "link-1"))
        assertTrue("another peer keeps its own budget", budget.tryAdmit("peer-b", "link-1"))
        assertEquals(1L, budget.dropped)

        time += 1_000
        assertTrue(budget.tryAdmit("peer-a", "link-1"))
    }

    @Test
    fun `one flooding link is bounded even with rotating peer identifiers`() {
        val admitted = (0 until 50).count { budget.tryAdmit("peer-$it", "link-1") }
        assertEquals(8, admitted)
        assertTrue("other links keep working", budget.tryAdmit("peer-z", "link-2"))
    }

    @Test
    fun `global budget bounds the sum of all links`() {
        val admitted = (0 until 100).count { budget.tryAdmit("peer-$it", "link-$it") }
        assertEquals(20, admitted)
        assertEquals(80L, budget.dropped)
    }
}
