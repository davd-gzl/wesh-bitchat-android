package com.bitchat.android.services

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Peers hidden by peer ID because no Noise session proves their long-term key. Memory only: it
 * lasts until Wesh restarts, is part of the authoritative block gate (admission and UI delegate),
 * and is cleared by unblock or panic.
 */
object SessionHiddenPeers {
    private val _peers = MutableStateFlow<Set<String>>(emptySet())
    val peers: StateFlow<Set<String>> = _peers.asStateFlow()

    fun contains(peerID: String): Boolean = peerID in _peers.value
    fun hide(peerID: String) = _peers.update { it + peerID }
    fun unhide(peerID: String) = _peers.update { it - peerID }
    fun clear() { _peers.value = emptySet() }
}
