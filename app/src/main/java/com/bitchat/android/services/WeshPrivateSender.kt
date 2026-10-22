package com.bitchat.android.services

import com.bitchat.android.mesh.MeshService
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.model.MessageTransport

/** Shared by the UI/router; every effect must recheck enrollment and the authenticated key. */
internal class WeshPrivateSender(private val contacts: WeshContactStore) {
    fun enrolled(conversationID: String): Boolean = contacts.contact(conversationID)?.let { !contacts.isBlocked(it.key) } == true
    fun canSend(conversationID: String, mesh: MeshService): Boolean = runCatching {
        val contact = contacts.contact(conversationID) ?: return@runCatching false
        val live = ContactDirectory.resolve(conversationID).meshPeerID ?: return@runCatching false
        !contacts.isBlocked(contact.key) && mesh.getAuthenticatedRemoteStaticKey(live)?.contentEquals(contact.key) == true
    }.getOrDefault(false)
    suspend fun dispatch(content: String, conversationID: String, messageID: String, mesh: MeshService): Boolean {
        val accepted = runCatching {
            val contact = contacts.contact(conversationID) ?: return@runCatching false
            val live = ContactDirectory.resolve(conversationID).meshPeerID ?: return@runCatching false
            if (content.isBlank() || content.toByteArray().size > 2000 || !canSend(conversationID, mesh)) return@runCatching false
            mesh.sendPrivateMessageBound(content, live, contact.key, messageID) { canSend(conversationID, mesh) }
        }.getOrDefault(false)
        if (accepted) {
            AppStateStore.updatePrivateMessageStatus(conversationID, messageID, DeliveryStatus.Sent, MessageTransport.BLUETOOTH)
        } else {
            AppStateStore.updatePrivateMessageStatus(conversationID, messageID,
                DeliveryStatus.Failed("Connexion ou identité indisponible. Réessayez après vérification."))
        }
        return accepted
    }
}
