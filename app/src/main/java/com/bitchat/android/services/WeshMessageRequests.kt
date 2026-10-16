package com.bitchat.android.services

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Private messages from people not enrolled by QR are never stored, shown or notified. Only a
 * minimal, memory-only request (nickname, identity) is kept so the person can verify or block.
 *
 * The list is bounded against floods: one update per source (fingerprint, else peer ID) per
 * [SOURCE_INTERVAL_MS], at most [MAX_REQUESTS] entries, and when full the oldest
 * unauthenticated entry is dropped before any authenticated one.
 */
object WeshMessageRequests {
    data class Request(val peerID: String, val nickname: String, val fingerprint: String?, val lastSeen: Long)

    internal const val MAX_REQUESTS = 32
    internal const val MAX_NICKNAME_CHARS = 32
    private const val MAX_COMBINING_MARKS = 2
    internal const val SOURCE_INTERVAL_MS = 5_000L

    private val _requests = MutableStateFlow<List<Request>>(emptyList())
    val requests: StateFlow<List<Request>> = _requests.asStateFlow()

    @Synchronized
    fun record(peerID: String, nickname: String, fingerprint: String?, now: Long = System.currentTimeMillis()) {
        val key = fingerprint ?: peerID
        val current = _requests.value
        val existing = current.firstOrNull { (it.fingerprint ?: it.peerID) == key }
        if (existing != null && now - existing.lastSeen in 0 until SOURCE_INTERVAL_MS) return
        val entry = Request(peerID, displayName(nickname, peerID), fingerprint, now)
        var kept = listOf(entry) + current.filterNot { it === existing }
        while (kept.size > MAX_REQUESTS) {
            // Oldest unauthenticated entry goes first; an authenticated one only when none is left.
            val victim = kept.lastOrNull { it.fingerprint == null } ?: kept.last()
            kept = kept.filterNot { it === victim }
        }
        _requests.value = kept
    }

    internal fun displayName(nickname: String, peerID: String): String {
        val normalized = java.text.Normalizer.normalize(nickname, java.text.Normalizer.Form.NFKC)
        val out = StringBuilder()
        var marks = 0
        var index = 0
        while (index < normalized.length) {
            val codePoint = normalized.codePointAt(index)
            index += Character.charCount(codePoint)
            when (Character.getType(codePoint).toByte()) {
                // Control, format (incl. bidi overrides and zero-width), surrogate and private use.
                Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE,
                Character.UNASSIGNED -> continue
                Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> {
                    if (out.isEmpty() || ++marks > MAX_COMBINING_MARKS) continue
                }
                else -> marks = 0
            }
            out.appendCodePoint(if (Character.isWhitespace(codePoint)) ' '.code else codePoint)
        }
        val clean = out.toString().replace(Regex(" {2,}"), " ").trim()
        if (clean.isEmpty()) return peerID.take(8)
        val count = clean.codePointCount(0, clean.length)
        if (count <= MAX_NICKNAME_CHARS) return clean
        return clean.substring(0, clean.offsetByCodePoints(0, MAX_NICKNAME_CHARS)) + "…"
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
    }
}
