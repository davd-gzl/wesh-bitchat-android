package com.bitchat.android.ui.pave

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the Connexion sheet shows about Internet mode. Mirrors the Internet mode state machine. */
sealed interface PaveInternetStatus {
    data object Off : PaveInternetStatus
    data class Connecting(val torPercent: Int) : PaveInternetStatus
    data class On(val relays: Int) : PaveInternetStatus
    data class Error(val reason: PaveInternetError) : PaveInternetStatus
}

enum class PaveInternetError { TorBlocked, NoNetwork, RelaysUnreachable }

/**
 * The UI's view of Internet mode. The Connexion sheet depends only on this,
 * so the real opt-in Tor-only implementation can be bound behind it.
 */
interface WeshInternetModeState {
    /** False when this build ships without Internet mode; the switch is then disabled. */
    val available: Boolean
    val enabled: StateFlow<Boolean>
    val status: StateFlow<PaveInternetStatus>

    /** Returns false when the change was refused (for example during an unfinished erase). */
    fun setEnabled(on: Boolean): Boolean
    fun retry()
}

/** In-memory stand-in for previews and tests. Never touches the network. */
class FakeWeshInternetModeState(
    override val available: Boolean = true,
    enabled: Boolean = false,
    status: PaveInternetStatus = if (enabled) PaveInternetStatus.On(3) else PaveInternetStatus.Off,
) : WeshInternetModeState {
    private val _enabled = MutableStateFlow(enabled)
    private val _status = MutableStateFlow(status)
    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    override val status: StateFlow<PaveInternetStatus> = _status.asStateFlow()

    override fun setEnabled(on: Boolean): Boolean {
        if (!available) return false
        _enabled.value = on
        _status.value = if (on) PaveInternetStatus.Connecting(0) else PaveInternetStatus.Off
        return true
    }

    override fun retry() {
        if (_enabled.value) _status.value = PaveInternetStatus.Connecting(0)
    }

    /** Lets a test or preview drive the status. */
    fun emit(status: PaveInternetStatus) {
        _status.value = status
    }
}
