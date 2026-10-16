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
    /**
     * [npub] comes only from a signed QR proven over Noise; null means Bluetooth-only.
     * [npubProven]: the QR also carried a valid proof by the npub key (NPUB-PROOF rules).
     * [npubWarning]: an npub conflict or downgrade the user should review.
     */
    data class Contact(
        val noiseHex: String,
        val nickname: String,
        val npub: String? = null,
        val npubProven: Boolean = false,
        val npubWarning: Boolean = false
    ) {
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
            val proven = npub != null && row.optBoolean("npubProven", false)
            val warning = row.optBoolean("npubWarning", false)
            if (!hex.matches(Regex("[0-9a-f]{64}")) || name.toByteArray().size > 255) null else Contact(hex, name, npub, proven, warning)
        }.distinctBy { it.noiseHex }.takeLast(256)
    }.getOrDefault(emptyList())

    /** Applies a [WeshNpubPolicy.enroll] decision; evicted contacts lose the npub and are flagged. */
    @Synchronized fun record(key: ByteArray, nickname: String, enrollment: WeshNpubPolicy.Enrollment? = null) {
        val npub = enrollment?.npub
        require(key.size == 32 && nickname.toByteArray().size <= 255 && (npub == null || WeshNpubPolicy.isValid(npub)))
        val hex = key.hexEncodedString()
        val others = _contacts.value.filterNot { it.noiseHex == hex }.map {
            if (enrollment != null && it.noiseHex in enrollment.evict && it.npub == npub && !it.npubProven) {
                it.copy(npub = null, npubWarning = true)
            } else it
        }
        val self = Contact(hex, nickname, npub, npub != null && enrollment?.proven == true, enrollment?.warning == true)
        persist((others + self).takeLast(256))
    }
    @Synchronized fun remove(conversationID: String): Boolean {
        val remaining = _contacts.value.filterNot { it.conversationID == conversationID }
        if (remaining.size == _contacts.value.size) return false
        persist(remaining)
        return true
    }
    @Synchronized fun clear() = persist(emptyList())
    /** Inbound resolution: the proven holder, else a single unproven holder; ambiguous is null. */
    fun forNpub(npub: String): Contact? {
        val holders = _contacts.value.filter { it.npub == npub }
        return holders.firstOrNull { it.npubProven } ?: holders.singleOrNull()
    }
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
        contacts.forEach { array.put(JSONObject().put("noise", it.noiseHex).put("nickname", it.nickname).putOpt("npub", it.npub)
            .putOpt("npubProven", if (it.npubProven) true else null)
            .putOpt("npubWarning", if (it.npubWarning) true else null)) }
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
