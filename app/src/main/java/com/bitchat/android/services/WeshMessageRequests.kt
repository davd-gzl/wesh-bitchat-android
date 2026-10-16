package com.bitchat.android.services

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Private messages from people not enrolled by QR are never stored, shown or notified. Only a
 * minimal, memory-only request (nickname, identity) is kept so the person can verify or block.
 */
object WeshMessageRequests {
    data class Request(val peerID: String, val nickname: String, val fingerprint: String?, val lastSeen: Long)

    private const val MAX_REQUESTS = 32
    private const val MAX_NICKNAME_BYTES = 255
    private val _requests = MutableStateFlow<List<Request>>(emptyList())
    val requests: StateFlow<List<Request>> = _requests.asStateFlow()

    @Synchronized
    fun record(peerID: String, nickname: String, fingerprint: String?, now: Long = System.currentTimeMillis()) {
        val name = nickname.takeIf { it.isNotBlank() && it.toByteArray().size <= MAX_NICKNAME_BYTES } ?: peerID.take(8)
        val key = fingerprint ?: peerID
        val others = _requests.value.filterNot { (it.fingerprint ?: it.peerID) == key }
        _requests.value = (listOf(Request(peerID, name, fingerprint, now)) + others).take(MAX_REQUESTS)
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
