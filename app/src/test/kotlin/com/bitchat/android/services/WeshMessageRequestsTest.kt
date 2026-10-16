package com.bitchat.android.services

import org.junit.After
import org.junit.Assert.assertEquals
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
    fun `new sources are rate limited per window`() {
        repeat(25) { WeshMessageRequests.record("peer-$it", "Synthetic", fp(it), time) }
        assertEquals(WeshMessageRequests.MAX_NEW_SOURCES_PER_WINDOW, WeshMessageRequests.requests.value.size)

        time += WeshMessageRequests.NEW_SOURCE_WINDOW_MS
        WeshMessageRequests.record("peer-late", "Synthetic", fp(99), time)
        assertEquals(WeshMessageRequests.MAX_NEW_SOURCES_PER_WINDOW + 1, WeshMessageRequests.requests.value.size)
    }

    @Test
    fun `displayed nickname is bounded and stripped of control characters`() {
        WeshMessageRequests.record("peer-a", "‮abc" + "x".repeat(200) + "\n", fp(1), time)
        val name = WeshMessageRequests.requests.value.single().nickname
        assertTrue(name.length <= WeshMessageRequests.MAX_NICKNAME_CHARS + 1)
        assertTrue(name.none { it == '\n' })
    }

    @Test
    fun `unauthenticated entries are evicted before authenticated ones`() {
        var index = 0
        fun fill(count: Int, authenticated: Boolean) = repeat(count) {
            index++
            WeshMessageRequests.record("peer-$index", "Synthetic", if (authenticated) fp(index) else null, time)
            time += WeshMessageRequests.NEW_SOURCE_WINDOW_MS / WeshMessageRequests.MAX_NEW_SOURCES_PER_WINDOW
        }
        fill(WeshMessageRequests.MAX_REQUESTS - 2, authenticated = true)
        fill(2, authenticated = false)
        fill(10, authenticated = false)

        val requests = WeshMessageRequests.requests.value
        assertEquals(WeshMessageRequests.MAX_REQUESTS, requests.size)
        assertEquals(WeshMessageRequests.MAX_REQUESTS - 2, requests.count { it.fingerprint != null })
    }
}
