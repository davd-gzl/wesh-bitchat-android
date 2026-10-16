package com.bitchat.android.services

import com.bitchat.android.model.BitchatMessage
import kotlinx.coroutines.runBlocking

/**
 * Reflects an incoming transport message into process-wide state before any downstream effects.
 *
 * Private-message admission is authoritative: a duplicate or a message rejected while panic mode
 * is wiping state must not continue to UI delegates, unread tracking, haptics, or notifications.
 * Public and channel messages retain their existing best-effort behavior if state reflection fails.
 */
internal object IncomingMessageAdmission {
    /** Wesh admits private messages only from identities enrolled by QR on this phone. */
    class QrEnrollment(
        val fingerprintOf: (peerID: String) -> String?,
        val isEnrolled: (fingerprint: String) -> Boolean,
        val onRequest: (peerID: String, nickname: String, fingerprint: String?) -> Unit = WeshMessageRequests::record
    ) {
        internal fun admits(message: BitchatMessage): Boolean {
            val peerID = message.senderPeerID?.takeIf(String::isNotBlank) ?: return false
            val fingerprint = runCatching { fingerprintOf(peerID) }.getOrNull()
            if (fingerprint != null && runCatching { isEnrolled(fingerprint) }.getOrDefault(false)) return true
            runCatching { onRequest(peerID, message.sender, fingerprint) }
            return false
        }
    }

    fun admitToAppState(
        message: BitchatMessage,
        qrEnrollment: QrEnrollment? = null,
        isSenderBlocked: (String) -> Boolean = { false }
    ): Boolean = if (message.senderPeerID?.let { peerID ->
        runCatching { isSenderBlocked(peerID) }.getOrDefault(true)
    } == true) false else if (message.isPrivate && qrEnrollment != null && !qrEnrollment.admits(message)) false else try {
        when {
            message.isPrivate -> {
                val peerID = message.senderPeerID?.takeIf(String::isNotBlank)
                    ?: return false
                // Mesh transport callbacks run on their background service workers. Wait for the
                // serialized SQLite transaction so a notification can never advertise a message
                // that an immediate process death would lose.
                runBlocking {
                    AppStateStore.addPrivateMessageDurably(peerID, message)
                }
            }

            message.channel != null -> {
                AppStateStore.addChannelMessage(message.channel, message)
                true
            }

            else -> {
                AppStateStore.addPublicMessage(message)
                true
            }
        }
    } catch (_: Exception) {
        // Preserve the pre-existing best-effort dispatch for public/channel messages, but never
        // bypass private-message admission when persistence or canonicalization fails.
        !message.isPrivate
    }
}
