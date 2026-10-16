package com.bitchat.android.services

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WeshMessageRequestsTest {
    private var time = 1_000_000L

    @Before fun setUp() = WeshMessageRequests.clear()
    @After fun tearDown() = WeshMessageRequests.clear()

    private fun fp(index: Int) = index.toString().padStart(64, '0')

    @Test
    fun `one source refreshes its entry at most once per interval`() {
        WeshMessageRequests.record("peer-a", "First", fp(1), time)
        WeshMessageRequests.record("peer-a", "Renamed", fp(1), time + 1_000)
        assertEquals("First", WeshMessageRequests.requests.value.single().nickname)

        WeshMessageRequests.record("peer-a", "Renamed", fp(1), time + WeshMessageRequests.SOURCE_INTERVAL_MS)
        assertEquals("Renamed", WeshMessageRequests.requests.value.single().nickname)
    }

    @Test
    fun `a flood of new sources stays bounded without refusing new authenticated requests`() {
        repeat(100) { WeshMessageRequests.record("peer-$it", "Synthetic", fp(it), time) }
        val requests = WeshMessageRequests.requests.value
        assertEquals(WeshMessageRequests.MAX_REQUESTS, requests.size)
        assertEquals(fp(99), requests.first().fingerprint)
    }

    @Test
    fun `the oldest unauthenticated request is dropped before any authenticated one`() {
        repeat(WeshMessageRequests.MAX_REQUESTS - 1) { WeshMessageRequests.record("peer-$it", "Synthetic", fp(it), time) }
        repeat(10) { WeshMessageRequests.record("anon-$it", "Synthetic", null, time) }

        val requests = WeshMessageRequests.requests.value
        assertEquals(WeshMessageRequests.MAX_REQUESTS, requests.size)
        assertEquals(WeshMessageRequests.MAX_REQUESTS - 1, requests.count { it.fingerprint != null })
        assertEquals(listOf("anon-9"), requests.filter { it.fingerprint == null }.map { it.peerID })
    }

    @Test
    fun `display names drop controls and bidi overrides, normalize and bound combining marks`() {
        val spoof = WeshMessageRequests.displayName("‮evil​\u0007name", "peer-a")
        assertEquals("evilname", spoof)
        assertEquals("ABC", WeshMessageRequests.displayName("ＡＢＣ", "peer-a"))
        val zalgo = WeshMessageRequests.displayName("a" + "́".repeat(50) + "b", "peer-a")
        assertEquals("\u00E1\u0301\u0301b", zalgo)
        assertEquals("peer-abc", WeshMessageRequests.displayName("‏\u0000", "peer-abcdef"))
        val long = WeshMessageRequests.displayName("x".repeat(200), "peer-a")
        assertEquals(WeshMessageRequests.MAX_NICKNAME_CHARS + 1, long.length)
        assertFalse(long.contains('\n'))
        assertTrue(long.endsWith("…"))
    }
}
