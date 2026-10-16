package com.bitchat.android.services

import android.content.Context
import android.content.SharedPreferences
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.util.dataFromHexString
import com.bitchat.android.util.hexEncodedString
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** QR enrollment has its own provenance; upstream manual verification cannot grant it. */
class WeshContactStore internal constructor(
    private val identity: SecureIdentityStateManager,
    private val blockPreferences: SharedPreferences
) {
    /** [npub] comes only from a signed QR proven over Noise; null means Bluetooth-only. */
    data class Contact(val noiseHex: String, val nickname: String, val npub: String? = null) {
        /** False: the UI asks to rescan the QR before Internet delivery is possible. */
        val internetReachable: Boolean get() = npub != null
        val key: ByteArray get() = noiseHex.dataFromHexString()!!
        val conversationID: String get() = ContactIdentityResolver.contactConversationIdForNoiseKey(key)
    }
    private val storageKey = "wesh_qr_contacts_v1"
    private val _contacts = MutableStateFlow(load())
    val contacts = _contacts.asStateFlow()

    private fun load(): List<Contact> = runCatching {
        val array = JSONArray(identity.getSecureValue(storageKey) ?: "[]")
        (0 until array.length()).mapNotNull { index ->
            val row = array.getJSONObject(index)
            val hex = row.getString("noise")
            val name = row.getString("nickname")
            val npub = row.optString("npub").takeIf(WeshNpubPolicy::isValid)
            if (!hex.matches(Regex("[0-9a-f]{64}")) || name.toByteArray().size > 255) null else Contact(hex, name, npub)
        }.distinctBy { it.noiseHex }.takeLast(256)
    }.getOrDefault(emptyList())

    @Synchronized fun record(key: ByteArray, nickname: String, npub: String? = null) {
        require(key.size == 32 && nickname.toByteArray().size <= 255 && (npub == null || WeshNpubPolicy.isValid(npub)))
        val hex = key.hexEncodedString()
        persist((_contacts.value.filterNot { it.noiseHex == hex } + Contact(hex, nickname, npub)).takeLast(256))
    }
    @Synchronized fun remove(conversationID: String): Boolean {
        val remaining = _contacts.value.filterNot { it.conversationID == conversationID }
        if (remaining.size == _contacts.value.size) return false
        persist(remaining)
        return true
    }
    @Synchronized fun clear() = persist(emptyList())
    fun forNpub(npub: String): Contact? = _contacts.value.firstOrNull { it.npub == npub }
    fun contact(conversationID: String): Contact? = _contacts.value.firstOrNull { it.conversationID == conversationID }
    fun forFingerprint(fingerprint: String): Contact? = _contacts.value.firstOrNull {
        ContactIdentityResolver.fingerprintHex(it.key) == fingerprint
    }
    fun isBlocked(key: ByteArray): Boolean = isFingerprintBlocked(ContactIdentityResolver.fingerprintHex(key))
    fun isFingerprintBlocked(fingerprint: String): Boolean = runCatching {
        blockPreferences.getStringSet("blocked_users", emptySet()).orEmpty().contains(fingerprint)
    }.getOrDefault(true)
    fun blockedFingerprints(): Set<String> = blockPreferences.getStringSet("blocked_users", emptySet()).orEmpty().toSet()
    private fun persist(contacts: List<Contact>) {
        val array = JSONArray()
        contacts.forEach { array.put(JSONObject().put("noise", it.noiseHex).put("nickname", it.nickname).putOpt("npub", it.npub)) }
        identity.storeSecureValue(storageKey, array.toString())
        _contacts.value = contacts
    }
    companion object {
        @Volatile private var instance: WeshContactStore? = null
        fun get(context: Context): WeshContactStore = instance ?: synchronized(this) {
            instance ?: WeshContactStore(SecureIdentityStateManager(context.applicationContext),
                context.applicationContext.getSharedPreferences("bitchat_prefs", Context.MODE_PRIVATE)).also { instance = it }
        }
        internal fun installForTesting(store: WeshContactStore) { instance = store }
        internal fun resetForTesting() { instance = null }
    }
}
