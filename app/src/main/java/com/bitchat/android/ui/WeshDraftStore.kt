package com.bitchat.android.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Bounded, memory-only drafts survive navigation and configuration; never auto-send. */
internal class WeshDraftStore {
    private val _drafts = MutableStateFlow<Map<String, String>>(emptyMap())
    val drafts = _drafts.asStateFlow()
    @Synchronized fun save(destination: String, value: String) {
        if (value.toByteArray().size > 8192) return
        val next = LinkedHashMap(_drafts.value)
        next.remove(destination)
        if (value.isNotEmpty()) next[destination] = value
        while (next.size > 64) next.remove(next.keys.first())
        _drafts.value = next
    }
    fun clearIfUnchanged(destination: String, sent: String) {
        synchronized(this) { if (_drafts.value[destination] == sent) save(destination, "") }
    }
    fun clear() { _drafts.value = emptyMap() }
}
