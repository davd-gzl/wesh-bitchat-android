package com.bitchat.android.ui

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.core.content.edit
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.model.BitchatMessage
import java.util.Date
import com.bitchat.android.services.ContactIdentityResolver
import com.bitchat.android.util.hexEncodedString

/** UI decisions depend on authenticated identity, never a nickname or parsed QR alone. */
internal object WeshMessagePolicy {
    fun sessionMatches(sessionKey: ByteArray?, expectedNoiseHex: String): Boolean =
        sessionKey?.size == 32 && sessionKey.hexEncodedString().equals(expectedNoiseHex, true)

    fun canSendPrivate(sessionKey: ByteArray?, expectedKey: ByteArray?, verified: Set<String>): Boolean =
        sessionKey != null && expectedKey != null && sessionKey.contentEquals(expectedKey) &&
            verified.contains(ContactIdentityResolver.fingerprintHex(sessionKey))

    data class PublicSendRequest(val content: String) {
        fun canConfirm(destination: String, currentDraft: String, bluetoothReady: Boolean): Boolean =
            destination == "public" && currentDraft == content && bluetoothReady && content.isNotBlank() && content.toByteArray().size <= 2000
    }

    suspend fun sendPublicText(
        content: String, nickname: String, myPeerID: String,
        store: (BitchatMessage) -> Unit,
        bluetoothSend: suspend (String) -> Boolean,
        authorized: () -> Boolean = { true }
    ): Boolean {
        if (content.isBlank() || content.toByteArray(Charsets.UTF_8).size > 2000) return false
        if (!authorized() || !bluetoothSend(content) || !authorized()) return false
        store(BitchatMessage(sender = nickname, content = content, timestamp = Date(),
            isRelay = false, senderPeerID = myPeerID))
        return true
    }

    fun deliveryLabel(status: DeliveryStatus?): String = when (status) {
        null, DeliveryStatus.Sending -> "En attente d’envoi"
        DeliveryStatus.Sent -> "Transmis · réception non confirmée"
        is DeliveryStatus.Delivered -> "Réception confirmée"
        is DeliveryStatus.Read -> "Lu"
        is DeliveryStatus.Failed -> "Échec de l’envoi"
        is DeliveryStatus.PartiallyDelivered -> "Réception partielle"
    }
}

internal class WeshPreferences(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences("wesh_ui", Context.MODE_PRIVATE))

    private val lock = Any()
    private var fenced = false
    private val _resetEpoch = MutableStateFlow(0L)
    val resetEpoch = _resetEpoch.asStateFlow()
    private val _meetingDraft = MutableStateFlow(prefs.getString("meeting_note", "") ?: "")
    val meetingDraft = _meetingDraft.asStateFlow()

    var publicMuted: Boolean
        get() = prefs.getBoolean("public_muted", false)
        set(value) {
            synchronized(lock) { if (!fenced) prefs.edit { putBoolean("public_muted", value) } }
        }
    var publicDisclosureAccepted: Boolean
        get() = prefs.getBoolean("public_disclosure", false)
        set(value) {
            synchronized(lock) { if (!fenced) prefs.edit { putBoolean("public_disclosure", value) } }
        }
    val meetingNote: String
        get() = prefs.getString("meeting_note", "") ?: ""

    fun editMeetingDraft(value: String, expectedEpoch: Long): Boolean = synchronized(lock) {
        if (fenced || expectedEpoch != _resetEpoch.value) return@synchronized false
        _meetingDraft.value = value
        true
    }

    fun saveMeetingDraft(expectedEpoch: Long): Boolean = synchronized(lock) {
        if (fenced || expectedEpoch != _resetEpoch.value) return@synchronized false
        prefs.edit { putString("meeting_note", _meetingDraft.value) }
        true
    }

    /** Blocks stale UI callbacks before any suspending panic work. */
    fun fenceForPanic() {
        synchronized(lock) {
            fenced = true
            _meetingDraft.value = ""
            _resetEpoch.value++
        }
    }

    /** Must run on IO. A clear published only in memory is never a confirmed wipe. */
    fun clearForPanic(): Boolean {
        fenceForPanic()
        return try {
            prefs.edit().clear().commit() && prefs.all.isEmpty()
        } catch (_: Exception) {
            false
        }
    }

    fun resumeAfterVerifiedPanic() {
        synchronized(lock) {
            fenced = false
            _resetEpoch.value++
        }
    }
}

/** The draft marker must stay pending until every application wipe is confirmed. */
internal suspend fun resumeAfterWeshPanicWipe(
    draftsCleared: Boolean,
    conversationsCleared: Boolean,
    clearPreferences: suspend () -> Boolean,
    resume: suspend () -> Boolean
): Boolean {
    val preferencesCleared = try {
        clearPreferences()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
    if (!draftsCleared || !conversationsCleared || !preferencesCleared) return false
    return resume()
}
