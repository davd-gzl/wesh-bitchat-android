package com.bitchat.android.net

import android.content.Context
import com.bitchat.android.util.stringOr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Derived from the Wesh Internet toggle only: absent or unreadable means OFF. */
object TorPreferenceManager {
    private const val PREFS_NAME = "bitchat_settings"
    private const val KEY_TOR_MODE = "tor_mode"

    private val _modeFlow = MutableStateFlow(TorMode.OFF)
    val modeFlow: StateFlow<TorMode> = _modeFlow

    fun init(context: Context) {
        _modeFlow.value = get(context)
    }

    /** [commit]: write synchronously (panic and fail-closed paths). */
    fun set(context: Context, mode: TorMode, commit: Boolean = false) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit().putString(KEY_TOR_MODE, mode.name)
        if (commit) editor.commit() else editor.apply()
        _modeFlow.value = mode
    }

    fun get(context: Context): TorMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.stringOr(KEY_TOR_MODE, null) ?: return TorMode.OFF
        return runCatching { TorMode.valueOf(saved) }.getOrDefault(TorMode.OFF)
    }
}
