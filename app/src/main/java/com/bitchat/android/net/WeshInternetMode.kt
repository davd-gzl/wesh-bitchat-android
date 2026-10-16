package com.bitchat.android.net

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the Wesh UI shows for Internet mode. */
sealed interface WeshInternetState {
    data object Off : WeshInternetState
    data class Connecting(val torPercent: Int) : WeshInternetState
    /** Tor is bootstrapped and at least one pinned relay is connected. */
    data object On : WeshInternetState
    data class Error(val reason: WeshInternetError) : WeshInternetState
}

enum class WeshInternetError { TOR_BLOCKED, NO_NETWORK, RELAYS_UNREACHABLE }

/** The single read point for "may Wesh open an Internet socket now". */
fun interface WeshNetworkGate {
    fun allowsNetwork(): Boolean
}

/**
 * Process-wide gate read by OkHttpProvider and NostrRelayManager before any socket, REQ or
 * EVENT. Closed until [WeshInternetMode.get] installs the runtime opt-in at startup.
 */
object WeshNetworkPolicy {
    @Volatile internal var gate: WeshNetworkGate = WeshNetworkGate { false }
    fun allowsNetwork(): Boolean = runCatching { gate.allowsNetwork() }.getOrDefault(false)
}

/** Inputs the state machine observes; pure data so transitions are testable. */
internal data class WeshInternetInputs(
    val network: Boolean = true,
    val torPercent: Int = 0,
    val torReady: Boolean = false,
    val relays: Int = 0
) {
    val ready: Boolean get() = network && torReady && relays > 0
}

/** Transitions of the Internet mode spec (section 1). */
internal object WeshInternetMachine {
    sealed interface Event {
        data object Enable : Event
        /** Toggle off and panic both land on Off. */
        data object Disable : Event
        data object Retry : Event
        data object Stalled : Event
        /** A connectivity callback fired; [available] is the new default-network state. */
        data class NetworkChanged(val available: Boolean) : Event
        data class Inputs(val inputs: WeshInternetInputs) : Event
    }

    fun reduce(state: WeshInternetState, event: Event, inputs: WeshInternetInputs): WeshInternetState = when (event) {
        Event.Disable -> WeshInternetState.Off
        Event.Enable -> if (state == WeshInternetState.Off) evaluate(WeshInternetState.Connecting(inputs.torPercent), inputs) else state
        Event.Retry -> if (state is WeshInternetState.Error) evaluate(WeshInternetState.Connecting(inputs.torPercent), inputs) else state
        Event.Stalled -> if (state is WeshInternetState.Connecting) WeshInternetState.Error(
            when {
                !inputs.network -> WeshInternetError.NO_NETWORK
                !inputs.torReady -> WeshInternetError.TOR_BLOCKED
                else -> WeshInternetError.RELAYS_UNREACHABLE
            }
        ) else state
        is Event.NetworkChanged -> when {
            state is WeshInternetState.Error && event.available -> evaluate(WeshInternetState.Connecting(inputs.torPercent), inputs)
            else -> evaluate(state, inputs)
        }
        is Event.Inputs -> evaluate(state, event.inputs)
    }

    private fun evaluate(state: WeshInternetState, inputs: WeshInternetInputs): WeshInternetState = when (state) {
        WeshInternetState.Off -> state
        // Tor kept retrying in the background and both legs came up: report it.
        is WeshInternetState.Error -> if (inputs.ready) WeshInternetState.On else state
        WeshInternetState.On -> if (inputs.ready) state else evaluate(WeshInternetState.Connecting(inputs.torPercent), inputs)
        is WeshInternetState.Connecting -> when {
            !inputs.network -> WeshInternetState.Error(WeshInternetError.NO_NETWORK)
            inputs.ready -> WeshInternetState.On
            else -> WeshInternetState.Connecting(inputs.torPercent)
        }
    }
}

/** Tor side effects. Every client is built through [OkHttpProvider], which refuses to go direct. */
internal interface WeshTorControl {
    /** Bootstrap percent and readiness. */
    val progress: Flow<Pair<Int, Boolean>>
    /** False when Tor must not start (an earlier Arti wipe is still incomplete). */
    fun start(): Boolean
    fun stop()
    /** Stops Tor, waits for it, then deletes its state; true only when nothing remains. */
    suspend fun stopAndWipe(): Boolean
}

internal interface WeshRelayControl {
    val connectedCount: Flow<Int>
    fun connect()
    fun disconnect()
    /** Drops subscriptions, queue and sockets; relays stay off afterwards. */
    fun clearOnPanic()
}

/**
 * Wesh Internet mode: persisted opt-in (default off), Tor mandatory, pinned relays only.
 *
 * UI API: [state], [enabled], [disclosureAccepted], [setEnabled], [retry].
 * Network call sites read [allowsNetwork]. Panic calls [fenceForPanic] then [panicReset].
 */
class WeshInternetMode internal constructor(
    private val prefs: SharedPreferences,
    private val tor: WeshTorControl,
    private val relays: WeshRelayControl,
    network: Flow<Boolean>,
    private val wipeNostrKeys: () -> Boolean,
    /** Product policy: false keeps Internet impossible whatever the toggle says. */
    private val internetAvailable: () -> Boolean,
    private val scope: CoroutineScope,
    /** Creates my Nostr key on opt-in; the QR path only ever reads it. */
    private val ensureNostrIdentity: () -> Unit = {},
    /** Startup found an Arti wipe it could not finish: Internet stays off and fenced. */
    startFenced: Boolean = false,
    private val stallMs: Long = STALL_MS
) : WeshNetworkGate {
    private val lock = Any()
    @Volatile private var fenced = false
    private var inputs = WeshInternetInputs()
    private var stallJob: Job? = null
    private var relaysRequested = false

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false) && internetAvailable())
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    private val _state = MutableStateFlow<WeshInternetState>(WeshInternetState.Off)
    val state: StateFlow<WeshInternetState> = _state.asStateFlow()

    var disclosureAccepted: Boolean
        get() = prefs.getBoolean(KEY_DISCLOSURE, false)
        set(value) = synchronized(lock) { if (!fenced) prefs.edit { putBoolean(KEY_DISCLOSURE, value) } }

    init {
        scope.launch { tor.progress.collect { (percent, ready) -> update { it.copy(torPercent = percent, torReady = ready) } } }
        scope.launch { relays.connectedCount.collect { count -> update { it.copy(relays = count) } } }
        scope.launch {
            network.collect { available ->
                synchronized(lock) {
                    inputs = inputs.copy(network = available)
                    apply(WeshInternetMachine.Event.NetworkChanged(available))
                }
            }
        }
        synchronized(lock) {
            if (startFenced) {
                refuseStart()
            } else if (_enabled.value) {
                if (tor.start()) apply(WeshInternetMachine.Event.Enable) else refuseStart()
            } else {
                // TorPreferenceManager is derived from the toggle: never ON while Internet is off.
                tor.stop()
            }
        }
    }

    override fun allowsNetwork(): Boolean = !fenced && internetAvailable() && _enabled.value

    /** Returns false when refused: product policy, panic in progress, or disclosure not accepted. */
    fun setEnabled(on: Boolean): Boolean = synchronized(lock) {
        if (!on) {
            persistEnabled(false)
            turnOff()
            return true
        }
        if (fenced || !internetAvailable() || !disclosureAccepted) return false
        if (_enabled.value) return true
        // Start Tor first: enabled=true is never persisted while an Arti wipe is incomplete.
        if (!tor.start()) {
            refuseStart()
            return false
        }
        persistEnabled(true)
        runCatching(ensureNostrIdentity)
        apply(WeshInternetMachine.Event.Enable)
        true
    }

    /** Caller holds [lock]. Tor refused to start: Internet off, persisted off, fence up. */
    private fun refuseStart() {
        fenced = true
        persistEnabled(false)
        turnOff()
    }

    fun retry() = synchronized(lock) {
        if (!allowsNetwork() || _state.value !is WeshInternetState.Error) return
        if (!tor.start()) return refuseStart()
        relaysRequested = false
        apply(WeshInternetMachine.Event.Retry)
    }

    /** Synchronous: blocks any re-enable before the suspending panic work starts. */
    fun fenceForPanic() = synchronized(lock) {
        fenced = true
        persistEnabled(false)
        turnOff()
    }

    /**
     * Panic: Internet off and persisted off, disclosure forgotten, relays cleared without
     * reconnecting, Tor stopped and its state deleted, Nostr keys erased. True only when each
     * step is confirmed; otherwise the fence stays up so nothing can re-enable Internet.
     */
    suspend fun panicReset(): Boolean {
        fenceForPanic()
        val relaysCleared = runCatching { relays.clearOnPanic(); relays.disconnect() }.isSuccess
        val torWiped = runCatching { tor.stopAndWipe() }.getOrDefault(false)
        val keysWiped = runCatching { wipeNostrKeys() }.getOrDefault(false)
        val persisted = runCatching {
            prefs.edit(commit = true) { putBoolean(KEY_ENABLED, false); remove(KEY_DISCLOSURE) }
            !prefs.getBoolean(KEY_ENABLED, false) && !prefs.contains(KEY_DISCLOSURE)
        }.getOrDefault(false)
        val cleared = relaysCleared && torWiped && keysWiped && persisted
        if (cleared) synchronized(lock) { fenced = false }
        return cleared
    }

    private fun persistEnabled(value: Boolean) {
        prefs.edit { putBoolean(KEY_ENABLED, value) }
        _enabled.value = value
    }

    private fun turnOff() {
        relaysRequested = false
        relays.disconnect()
        tor.stop()
        apply(WeshInternetMachine.Event.Disable)
    }

    private fun update(change: (WeshInternetInputs) -> WeshInternetInputs) = synchronized(lock) {
        val before = inputs
        inputs = change(inputs)
        if (inputs != before) apply(WeshInternetMachine.Event.Inputs(inputs))
    }

    /** Caller holds [lock]. */
    private fun apply(event: WeshInternetMachine.Event) {
        val previous = _state.value
        val next = WeshInternetMachine.reduce(previous, event, inputs)
        _state.value = next
        // No relay socket before Tor is ready, and none while Internet is not allowed.
        if (next != WeshInternetState.Off && inputs.torReady && allowsNetwork() && !relaysRequested) {
            relaysRequested = true
            relays.connect()
        }
        if (!inputs.torReady) relaysRequested = false
        val progressed = previous is WeshInternetState.Connecting && next is WeshInternetState.Connecting &&
            event is WeshInternetMachine.Event.Inputs && progressMade(event.inputs)
        when {
            next !is WeshInternetState.Connecting -> { stallJob?.cancel(); stallJob = null }
            previous !is WeshInternetState.Connecting || progressed || stallJob == null -> armStall()
        }
    }

    private var lastProgress = 0 to 0
    private fun progressMade(now: WeshInternetInputs): Boolean {
        val mark = now.torPercent to now.relays
        val moved = mark.first > lastProgress.first || mark.second > lastProgress.second
        lastProgress = mark
        return moved
    }

    private fun armStall() {
        stallJob?.cancel()
        lastProgress = inputs.torPercent to inputs.relays
        stallJob = scope.launch {
            delay(stallMs)
            synchronized(lock) { apply(WeshInternetMachine.Event.Stalled) }
        }
    }

    companion object {
        /** Same file as WeshPreferences, so its panic clear also erases these keys. */
        internal const val PREFS = "wesh_ui"
        internal const val KEY_ENABLED = "wesh_internet_enabled_v1"
        internal const val KEY_DISCLOSURE = "wesh_internet_disclosure_v1"
        internal const val STALL_MS = 75_000L

        @Volatile private var instance: WeshInternetMode? = null

        fun get(context: Context): WeshInternetMode = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext as android.app.Application
                // Startup: before anything can start Arti, finish a wipe a failed panic left behind.
                val wiped = runCatching { ArtiTorControl.completePendingWipe(app) }.getOrDefault(false)
                if (!wiped) TorPreferenceManager.set(app, TorMode.OFF, commit = true)
                WeshInternetMode(
                    prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
                    tor = ArtiTorControl(app),
                    relays = NostrRelayControl(app),
                    network = defaultNetworkFlow(app),
                    wipeNostrKeys = { wipeNostrKeys(app) },
                    // Task #1 replaces this with the product Internet flag; until then nothing changes.
                    internetAvailable = { !com.bitchat.android.BuildConfig.WESH_POLICY },
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                    ensureNostrIdentity = { com.bitchat.android.nostr.NostrIdentityBridge.getCurrentNostrIdentity(app) },
                    startFenced = !wiped
                ).also {
                    instance = it
                    WeshNetworkPolicy.gate = it
                }
            }
        }
    }
}
