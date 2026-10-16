package com.bitchat.android.services

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Private messages from people not enrolled by QR are never stored, shown or notified. Only a
 * minimal, memory-only request (nickname, identity) is kept so the person can verify or block.
 *
 * The list is bounded against floods: one update per source per [SOURCE_INTERVAL_MS], at most
 * [MAX_NEW_SOURCES_PER_WINDOW] new sources per [NEW_SOURCE_WINDOW_MS], and unauthenticated
 * entries are evicted before authenticated ones.
 */
object WeshMessageRequests {
    data class Request(val peerID: String, val nickname: String, val fingerprint: String?, val lastSeen: Long)

    internal const val MAX_REQUESTS = 32
    internal const val MAX_NICKNAME_CHARS = 32
    internal const val SOURCE_INTERVAL_MS = 5_000L
    internal const val NEW_SOURCE_WINDOW_MS = 60_000L
    internal const val MAX_NEW_SOURCES_PER_WINDOW = 10

    private val _requests = MutableStateFlow<List<Request>>(emptyList())
    val requests: StateFlow<List<Request>> = _requests.asStateFlow()
    private val newSourceTimes = ArrayDeque<Long>()

    @Synchronized
    fun record(peerID: String, nickname: String, fingerprint: String?, now: Long = System.currentTimeMillis()) {
        val key = fingerprint ?: peerID
        val current = _requests.value
        val existing = current.firstOrNull { (it.fingerprint ?: it.peerID) == key }
        if (existing != null && now - existing.lastSeen in 0 until SOURCE_INTERVAL_MS) return
        if (existing == null) {
            while (newSourceTimes.isNotEmpty() && now - newSourceTimes.first() >= NEW_SOURCE_WINDOW_MS) newSourceTimes.removeFirst()
            if (newSourceTimes.size >= MAX_NEW_SOURCES_PER_WINDOW) return
            newSourceTimes.addLast(now)
        }
        val entry = Request(peerID, displayName(nickname, peerID), fingerprint, now)
        var kept = listOf(entry) + current.filterNot { it === existing }
        while (kept.size > MAX_REQUESTS) {
            // Oldest unauthenticated entry goes first; an authenticated one only when none is left.
            val victim = kept.drop(1).lastOrNull { it.fingerprint == null }
                ?: if (fingerprint == null) entry else kept.last()
            kept = kept.filterNot { it === victim }
        }
        _requests.value = kept
    }

    private fun displayName(nickname: String, peerID: String): String {
        val clean = nickname.filterNot { it.isISOControl() }.trim()
        if (clean.isEmpty()) return peerID.take(8)
        val end = clean.offsetByCodePoints(0, minOf(MAX_NICKNAME_CHARS, clean.codePointCount(0, clean.length)))
        return if (end < clean.length) clean.substring(0, end) + "…" else clean
    }

    @Synchronized
    fun remove(peerID: String) {
        _requests.value = _requests.value.filterNot { it.peerID == peerID }
    }

    @Synchronized
    fun removeFingerprint(fingerprint: String) {
        _requests.value = _requests.value.filterNot { it.fingerprint == fingerprint }
    }

    @Synchronized
    fun clear() {
        _requests.value = emptyList()
        newSourceTimes.clear()
    }
}
