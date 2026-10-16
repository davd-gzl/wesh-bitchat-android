package com.bitchat.android.ui.pave

import com.bitchat.android.net.WeshInternetError
import com.bitchat.android.net.WeshInternetMode
import com.bitchat.android.net.WeshInternetState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * The Connexion sheet's view of the real Internet mode. Every change goes through [WeshInternetMode]:
 * its product policy, panic fence and disclosure rule stay the only gate.
 */
class LiveWeshInternetModeState(
    private val mode: WeshInternetMode,
    connectedRelays: Flow<Int>,
    scope: CoroutineScope,
    override val available: Boolean = WeshInternetMode.productInternetAvailable(),
) : WeshInternetModeState {
    override val enabled: StateFlow<Boolean> = mode.enabled

    override val status: StateFlow<PaveInternetStatus> = combine(mode.state, connectedRelays) { state, relays ->
        paveInternetStatus(state, relays)
    }.stateIn(scope, SharingStarted.Eagerly, paveInternetStatus(mode.state.value, 0))

    override val disclosureAccepted: Boolean get() = mode.disclosureAccepted

    override fun acceptDisclosure() {
        mode.disclosureAccepted = true
    }

    override fun setEnabled(on: Boolean): Boolean = mode.setEnabled(on)

    override fun retry() = mode.retry()
}

/** One-to-one mapping of the state machine; the relay count only decorates the On state. */
internal fun paveInternetStatus(state: WeshInternetState, relays: Int): PaveInternetStatus = when (state) {
    WeshInternetState.Off -> PaveInternetStatus.Off
    is WeshInternetState.Connecting -> PaveInternetStatus.Connecting(state.torPercent)
    WeshInternetState.On -> PaveInternetStatus.On(relays.coerceAtLeast(1))
    is WeshInternetState.Error -> PaveInternetStatus.Error(
        when (state.reason) {
            WeshInternetError.TOR_BLOCKED -> PaveInternetError.TorBlocked
            WeshInternetError.NO_NETWORK -> PaveInternetError.NoNetwork
            WeshInternetError.RELAYS_UNREACHABLE -> PaveInternetError.RelaysUnreachable
        }
    )
}
