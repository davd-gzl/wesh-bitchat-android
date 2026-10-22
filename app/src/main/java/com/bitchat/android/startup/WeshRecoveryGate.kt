package com.bitchat.android.startup

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Secure preparation being Ready never overrides an interrupted-panic fence. */
internal class WeshRecoveryGate {
    private val blocked = MutableStateFlow(false)
    val recoveryRequired = blocked.asStateFlow()
    fun requireRecovery() { blocked.value = true }
    fun allowsTransport(prepared: Boolean): Boolean = prepared && !blocked.value
}
