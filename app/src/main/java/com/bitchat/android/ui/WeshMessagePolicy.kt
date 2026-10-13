package com.bitchat.android.ui

import android.content.Context
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

    fun sendPublicText(
        content: String, nickname: String, myPeerID: String,
        store: (BitchatMessage) -> Unit,
        bluetoothSend: (String, List<String>, String?) -> Unit
    ): Boolean {
        if (content.isBlank() || content.toByteArray(Charsets.UTF_8).size > 2000) return false
        store(BitchatMessage(sender = nickname, content = content, timestamp = Date(),
            isRelay = false, senderPeerID = myPeerID))
        bluetoothSend(content, emptyList(), null)
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

internal class WeshPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("wesh_ui", Context.MODE_PRIVATE)
    var publicMuted: Boolean
        get() = prefs.getBoolean("public_muted", false)
        set(value) { prefs.edit { putBoolean("public_muted", value) } }
    var publicDisclosureAccepted: Boolean
        get() = prefs.getBoolean("public_disclosure", false)
        set(value) { prefs.edit { putBoolean("public_disclosure", value) } }
    var meetingNote: String
        get() = prefs.getString("meeting_note", "") ?: ""
        set(value) { prefs.edit { putString("meeting_note", value) } }
}
