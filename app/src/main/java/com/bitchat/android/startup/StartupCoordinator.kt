package com.bitchat.android.startup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Process-owned preparation: Activity cancellation never creates a second identity/runtime. */
internal class StartupCoordinator<T>(
    private val scope: CoroutineScope,
    private val prepare: suspend () -> T
) {
    sealed interface State<out T> {
        data object Initializing : State<Nothing>
        data class Ready<T>(val value: T) : State<T>
        data object Failed : State<Nothing>
    }

    private val mutableState = MutableStateFlow<State<T>>(State.Initializing)
    val state = mutableState.asStateFlow()
    private var job: Job? = null

    @Synchronized
    fun start(retry: Boolean = false) {
        if (job?.isActive == true || state.value is State.Ready) return
        if (state.value is State.Failed && !retry) return
        mutableState.value = State.Initializing
        job = scope.launch {
            try {
                mutableState.value = State.Ready(prepare())
            } catch (cancelled: CancellationException) {
                mutableState.value = State.Failed
                throw cancelled
            } catch (_: Exception) {
                // Do not put key aliases, paths or decrypted values into UI/error logs.
                mutableState.value = State.Failed
            }
        }
    }

    suspend fun awaitReady(): T {
        start()
        return when (val result = state.first { it !is State.Initializing }) {
            is State.Ready -> result.value
            else -> error("Secure startup unavailable")
        }
    }
}
