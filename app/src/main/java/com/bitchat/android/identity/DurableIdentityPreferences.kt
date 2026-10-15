package com.bitchat.android.identity

import android.content.SharedPreferences
import java.util.WeakHashMap

/**
 * commit(false) can still publish values in SharedPreferences memory. Remember that uncertainty
 * process-wide and require a successful explicit rewrite before returning those identity values.
 * No alternate key is generated on persistence failure. Call the startup path on IO.
 */
internal object DurableIdentityPreferences {
    private val pending = WeakHashMap<SharedPreferences, MutableMap<String, String?>>()
    private val pendingClear = WeakHashMap<SharedPreferences, Unit>()

    private fun repair(prefs: SharedPreferences) {
        if (pendingClear.containsKey(prefs)) {
            check(prefs.edit().clear().commit()) { "Identity wipe unavailable" }
            pendingClear.remove(prefs)
        }
        pending[prefs]?.let { values ->
            val editor = prefs.edit()
            values.forEach { (key, value) ->
                if (value == null) editor.remove(key) else editor.putString(key, value)
            }
            check(editor.commit()) { "Identity persistence unavailable" }
            pending.remove(prefs)
        }
    }

    @Synchronized
    fun read(prefs: SharedPreferences, vararg keys: String): List<String?> {
        repair(prefs)
        return keys.map { prefs.getString(it, null) }
    }

    @Synchronized
    fun write(prefs: SharedPreferences, values: Map<String, String>) {
        val all = pending.getOrPut(prefs) { mutableMapOf() }
        all.putAll(values)
        repair(prefs)
    }

    @Synchronized
    fun remove(prefs: SharedPreferences, key: String) {
        pending.getOrPut(prefs) { mutableMapOf() }[key] = null
        repair(prefs)
    }

    @Synchronized
    fun clear(prefs: SharedPreferences) {
        // A panic request must never resurrect a previous failed identity write.
        pending.remove(prefs)
        pendingClear[prefs] = Unit
        repair(prefs)
    }
}
