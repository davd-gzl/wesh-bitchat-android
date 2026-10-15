package com.bitchat.android.startup

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Accepted means requested from Android; never confirms erasure or opens the runtime gate. */
internal class InterruptedPanicRecovery(private val scope: CoroutineScope) {
    enum class State { Idle, Running, Failed, Requested }
    private val _state = MutableStateFlow(State.Idle)
    val state = _state.asStateFlow()
    private var job: Job? = null

    @Synchronized fun request(clearApplicationData: suspend () -> Boolean) {
        if (job?.isActive == true || state.value == State.Requested) return
        _state.value = State.Running
        job = scope.launch {
            _state.value = try {
                if (clearApplicationData()) State.Requested else State.Failed
            } catch (_: Exception) {
                State.Failed
            }
        }
    }
}
