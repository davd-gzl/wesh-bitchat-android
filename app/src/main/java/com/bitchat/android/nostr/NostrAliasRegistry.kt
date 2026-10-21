package com.bitchat.android.nostr

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * Global, thread-safe registry for alias -> Nostr pubkey mappings (e.g., nostr_<pub16> -> pubkeyHex),
 * persisted to SharedPreferences to survive app restarts.
 */
object NostrAliasRegistry {
    private val map: MutableMap<String, String> = ConcurrentHashMap()
    private const val PREFS_NAME = "nostr_alias_registry"
    // The same registry was stored under this name by the inherited code.
    private const val LEGACY_PREFS_NAME = "geohash_alias_registry"
    private var prefs: SharedPreferences? = null

    fun initialize(context: Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            migrateLegacy(context)
            loadFromPrefs()
        }
    }

    private fun migrateLegacy(context: Context) {
        val legacy = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE).all
        if (legacy.isNotEmpty()) {
            val editor = prefs?.edit() ?: return
            for ((key, value) in legacy) if (value is String) editor.putString(key, value)
            if (!editor.commit()) return
        }
        context.deleteSharedPreferences(LEGACY_PREFS_NAME)
    }

    private fun loadFromPrefs() {
        prefs?.let { p ->
            val allEntries = p.all
            for ((key, value) in allEntries) {
                if (key is String && value is String) {
                    map[key] = value
                }
            }
        }
    }

    fun put(alias: String, pubkeyHex: String) {
        map[alias] = pubkeyHex
        prefs?.edit()?.putString(alias, pubkeyHex)?.apply()
    }

    fun get(alias: String): String? = map[alias]

    fun contains(alias: String): Boolean = map.containsKey(alias)

    fun snapshot(): Map<String, String> = HashMap(map)

    fun clear() {
        map.clear()
        prefs?.edit()?.clear()?.apply()
    }
}
