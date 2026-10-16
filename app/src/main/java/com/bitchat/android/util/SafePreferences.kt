package com.bitchat.android.util

import android.content.SharedPreferences

// A value stored with another type reads as absent instead of throwing ClassCastException,
// so a corrupted preference can neither crash the app nor stop an erase.

internal fun SharedPreferences.booleanOr(key: String, default: Boolean): Boolean =
    try { getBoolean(key, default) } catch (_: ClassCastException) { default }

internal fun SharedPreferences.stringOr(key: String, default: String?): String? =
    try { getString(key, default) } catch (_: ClassCastException) { default }
