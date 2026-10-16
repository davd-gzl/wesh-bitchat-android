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
        unauthenticated = PublicInboundBudget.Rate(perSecond = 1.0, burst = 4.0),
        perUnauthenticatedPeer = PublicInboundBudget.Rate(perSecond = 1.0, burst = 2.0)
    )

    @Test
    fun `one unauthenticated peer ID is capped below the shared bucket`() {
        val admitted = (0 until 10).count { budget.tryAdmit(null, "link-$it", "peer-a") }
        assertEquals(2, admitted)
        assertTrue("another peer still uses the shared bucket", budget.tryAdmit(null, "link-x", "peer-b"))
    }

    @Test
    fun `default shared bucket is sized for a crowd`() {
        val crowd = PublicInboundBudget(now = { time })
        val admitted = (0 until 100).count { crowd.tryAdmit(null, "link-$it", "peer-$it") }
        assertEquals(30, admitted)
        assertTrue("unauthenticated traffic leaves global room", crowd.tryAdmit("noise:a", "link-0"))
    }

    @Test
    fun `an authenticated sender keeps getting through an unauthenticated flood on its own link`() {
        val defaults = PublicInboundBudget(now = { time })
        var next = 0
        var authenticatedAdmitted = 0
        repeat(100) { step ->
            // Four neighbours relay a flood from rotating peer IDs, well above every unauthenticated cap.
            repeat(10) { defaults.tryAdmit(null, "link-${next % 4}", "peer-${next++}") }
            if (step % 10 == 0 && defaults.tryAdmit("noise:a", "link-0")) authenticatedAdmitted++
            time += 100
        }
        assertEquals(10, authenticatedAdmitted)
    }

    @Test
    fun `an enrolled contact is not charged to the relaying link`() {
        val roomy = roomyBudget()
        val admitted = (0 until 50).count { roomy.tryAdmit(null, "link-1", "peer-$it") }
        assertEquals("the flood exhausts the link", 8, admitted)
        assertTrue(roomy.tryAdmit("noise:a", "link-1", enrolledContact = true))
        assertTrue(roomy.tryAdmit("noise:b", "link-1", enrolledContact = true))
        assertFalse("a Noise key alone is not a contact", roomy.tryAdmit("noise:c", "link-1"))
    }

    @Test
    fun `minted Noise identities on one link cannot starve a contact or an unrelated sender`() {
        val defaults = PublicInboundBudget(now = { time })
        var next = 0
        var contact = 0
        var stranger = 0
        var anonymous = 0
        repeat(130) { step ->
            // One device answers with 30 fresh Noise identities, all relayed over link-1.
            repeat(10) { defaults.tryAdmit("noise:minted-${next++ % 30}", "link-1") }
            if (step % 10 == 0) {
                val measured = step >= 30
                if (defaults.tryAdmit("noise:contact", "link-1", enrolledContact = true)) contact++
                if (defaults.tryAdmit("noise:other", "link-2") && measured) stranger++
                if (defaults.tryAdmit(null, "link-3", "peer-anon") && measured) anonymous++
            }
            time += 100
        }
        assertEquals(13, contact)
        assertEquals(10, stranger)
        assertEquals(10, anonymous)
    }

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
        val admitted = (0 until 50).count { budget.tryAdmit(null, "link-$it", "peer-$it") }
        assertEquals(4, admitted)
        assertTrue("authenticated senders are unaffected", budget.tryAdmit("noise:a", "link-1"))
    }

    @Test
    fun `traffic without a link identity pays into the shared unauthenticated bucket`() {
        val admitted = (0 until 10).count { budget.tryAdmit(null, null, "peer-$it") }
        assertEquals(4, admitted)
        assertFalse(budget.tryAdmit(null, null))
    }

    @Test
    fun `one flooding link is bounded`() {
        val roomy = roomyBudget()
        val admitted = (0 until 50).count { roomy.tryAdmit(null, "link-1", "peer-$it") }
        assertEquals(8, admitted)
        assertTrue("other links keep working", roomy.tryAdmit(null, "link-2", "peer-z"))
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

    /** Unauthenticated caps wide enough that only the link bucket binds. */
    private fun roomyBudget() = PublicInboundBudget(
        now = { time },
        global = PublicInboundBudget.Rate(perSecond = 10.0, burst = 100.0),
        perLink = PublicInboundBudget.Rate(perSecond = 4.0, burst = 8.0),
        unauthenticated = PublicInboundBudget.Rate(perSecond = 1.0, burst = 50.0)
    )
}
