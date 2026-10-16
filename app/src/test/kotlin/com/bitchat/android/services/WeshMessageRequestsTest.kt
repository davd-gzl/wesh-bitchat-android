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
    private fun nextWindowSlot() { time += WeshMessageRequests.NEW_SOURCE_WINDOW_MS / WeshMessageRequests.MAX_NEW_SOURCES_PER_WINDOW }

    @Test
    fun `one source refreshes its entry at most once per interval`() {
        WeshMessageRequests.record("peer-a", "First", fp(1), time)
        WeshMessageRequests.record("peer-a", "Renamed", fp(1), time + 1_000)
        assertEquals("First", WeshMessageRequests.requests.value.single().nickname)

        WeshMessageRequests.record("peer-a", "Renamed", fp(1), time + WeshMessageRequests.SOURCE_INTERVAL_MS)
        assertEquals("Renamed", WeshMessageRequests.requests.value.single().nickname)
    }

    @Test
    fun `new distinct sources are capped per window`() {
        repeat(25) { WeshMessageRequests.record("peer-$it", "Synthetic", fp(it), time) }
        assertEquals(WeshMessageRequests.MAX_NEW_SOURCES_PER_WINDOW, WeshMessageRequests.requests.value.size)

        time += WeshMessageRequests.NEW_SOURCE_WINDOW_MS
        WeshMessageRequests.record("peer-late", "Synthetic", fp(99), time)
        assertEquals(WeshMessageRequests.MAX_NEW_SOURCES_PER_WINDOW + 1, WeshMessageRequests.requests.value.size)
    }

    @Test
    fun `a full inbox of authenticated requests still admits a new one by evicting the oldest`() {
        repeat(WeshMessageRequests.MAX_REQUESTS) { WeshMessageRequests.record("peer-$it", "Synthetic", fp(it), time); nextWindowSlot() }
        WeshMessageRequests.record("peer-new", "Synthetic", fp(999), time)

        val requests = WeshMessageRequests.requests.value
        assertEquals(WeshMessageRequests.MAX_REQUESTS, requests.size)
        assertEquals("peer-new", requests.first().peerID)
        assertTrue(requests.none { it.peerID == "peer-0" })
    }

    @Test
    fun `when full an entry shown too briefly is skipped for an older one`() {
        repeat(WeshMessageRequests.MAX_REQUESTS - 1) { WeshMessageRequests.record("peer-$it", "Synthetic", fp(it), time); nextWindowSlot() }
        WeshMessageRequests.record("anon-recent", "Synthetic", null, time)
        nextWindowSlot()
        // Refresh every older entry so the recent one becomes the least recently updated.
        repeat(WeshMessageRequests.MAX_REQUESTS - 1) { WeshMessageRequests.record("peer-$it", "Synthetic", fp(it), time) }

        WeshMessageRequests.record("peer-late", "Synthetic", fp(501), time)
        val requests = WeshMessageRequests.requests.value
        assertEquals(WeshMessageRequests.MAX_REQUESTS, requests.size)
        assertTrue("too recent to evict", requests.any { it.peerID == "anon-recent" })
        assertTrue(requests.none { it.peerID == "peer-0" })
        assertEquals("peer-late", requests.first().peerID)
    }

    @Test
    fun `a newcomer after a full window replaces the oldest request shown long enough`() {
        repeat(WeshMessageRequests.MAX_NEW_SOURCES_PER_WINDOW) { WeshMessageRequests.record("flood-$it", "Synthetic", fp(it), time) }
        time += WeshMessageRequests.MIN_DISPLAY_AGE_MS

        WeshMessageRequests.record("peer-legit", "Synthetic", fp(500), time)
        val requests = WeshMessageRequests.requests.value
        assertEquals("the window still bounds growth", WeshMessageRequests.MAX_NEW_SOURCES_PER_WINDOW, requests.size)
        assertEquals("peer-legit", requests.first().peerID)
    }

    @Test
    fun `a full window refuses a newcomer while every request is still recent`() {
        repeat(WeshMessageRequests.MAX_NEW_SOURCES_PER_WINDOW) { WeshMessageRequests.record("flood-$it", "Synthetic", fp(it), time) }
        WeshMessageRequests.record("peer-early", "Synthetic", fp(500), time + 1_000)
        assertTrue(WeshMessageRequests.requests.value.none { it.peerID == "peer-early" })
    }

    @Test
    fun `request nicknames go through the shared display name sanitizer`() {
        WeshMessageRequests.record("peer-a", "‮evil​name", fp(1), time)
        assertEquals("evilname", WeshMessageRequests.requests.value.single().nickname)
    }
}
