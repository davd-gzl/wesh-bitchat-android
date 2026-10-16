package com.bitchat.android.services

import com.bitchat.android.util.safeDisplayName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Private messages from people not enrolled by QR are never stored, shown or notified. Only a
 * minimal, memory-only request (nickname, identity) is kept so the person can verify or block.
 *
 * Flood bounds: one update per source (fingerprint, else peer ID) per [SOURCE_INTERVAL_MS]; at
 * most [MAX_NEW_SOURCES_PER_WINDOW] new sources, added or replacing one, per [NEW_SOURCE_WINDOW_MS];
 * at most [MAX_REQUESTS] entries. When full, the entry first seen longest ago, if shown for at
 * least [MIN_DISPLAY_AGE_MS], is evicted whatever its type: Noise keys are cheap, so authenticated
 * entries must not lock the inbox, and refreshing an entry cannot protect it.
 */
object WeshMessageRequests {
    data class Request(
        val peerID: String,
        val nickname: String,
        val fingerprint: String?,
        val lastSeen: Long,
        val firstSeen: Long = lastSeen
    )

    internal const val MAX_REQUESTS = 32
    internal const val SOURCE_INTERVAL_MS = 5_000L
    internal const val NEW_SOURCE_WINDOW_MS = 60_000L
    internal const val MAX_NEW_SOURCES_PER_WINDOW = 10
    internal const val MIN_DISPLAY_AGE_MS = 30_000L

    private val _requests = MutableStateFlow<List<Request>>(emptyList())
    val requests: StateFlow<List<Request>> = _requests.asStateFlow()
    private val newSourceTimes = ArrayDeque<Long>()

    @Synchronized
    fun record(peerID: String, nickname: String, fingerprint: String?, now: Long = System.currentTimeMillis()) {
        val key = fingerprint ?: peerID
        val current = _requests.value
        val existing = current.firstOrNull { (it.fingerprint ?: it.peerID) == key }
        // Empty when nothing visible is left: the display rule shows it unnamed, not as hex.
        val name = safeDisplayName(nickname, "")
        if (existing != null) {
            if (now - existing.lastSeen in 0 until SOURCE_INTERVAL_MS) return
            _requests.value = listOf(existing.copy(peerID = peerID, nickname = name, lastSeen = now)) +
                current.filterNot { it === existing }
            return
        }
        while (newSourceTimes.isNotEmpty() && now - newSourceTimes.first() >= NEW_SOURCE_WINDOW_MS) newSourceTimes.removeFirst()
        if (newSourceTimes.size >= MAX_NEW_SOURCES_PER_WINDOW) return
        var kept = current
        if (kept.size >= MAX_REQUESTS) {
            val victim = kept.filter { now - it.firstSeen >= MIN_DISPLAY_AGE_MS }.minByOrNull { it.firstSeen } ?: return
            kept = kept.filterNot { it === victim }
        }
        newSourceTimes.addLast(now)
        _requests.value = listOf(Request(peerID, name, fingerprint, now)) + kept
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
