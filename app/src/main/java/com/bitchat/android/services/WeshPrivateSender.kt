package com.bitchat.android.services

import com.bitchat.android.mesh.BoundSendOutcome
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
    /** Boolean form for callers that only need « Envoyé » or « Échec ». */
    suspend fun dispatch(content: String, conversationID: String, messageID: String, mesh: MeshService): Boolean {
        val outcome = dispatchOutcome(content, conversationID, messageID, mesh)
        if (outcome != BoundSendOutcome.SENT) {
            AppStateStore.updatePrivateMessageStatus(conversationID, messageID, DeliveryStatus.Failed(FAILED_WRITE))
        }
        return outcome == BoundSendOutcome.SENT
    }

    /**
     * Writes over the live session bound to the enrolled key; « Envoyé » only on [BoundSendOutcome.SENT].
     * A changed or unproven key, a block or a removal is [BoundSendOutcome.AUTH_FAILED]: the message
     * must not move to another route.
     */
    suspend fun dispatchOutcome(content: String, conversationID: String, messageID: String, mesh: MeshService): BoundSendOutcome {
        val outcome = try {
            val contact = contacts.contact(conversationID)
            val live = ContactDirectory.resolve(conversationID).meshPeerID
            when {
                contact == null || contacts.isBlocked(contact.key) -> BoundSendOutcome.AUTH_FAILED
                content.isBlank() || content.toByteArray().size > 2000 || live == null -> BoundSendOutcome.NOT_SENT
                else -> when (val proven = mesh.getAuthenticatedRemoteStaticKey(live)) {
                    null -> BoundSendOutcome.NOT_SENT
                    else -> if (!proven.contentEquals(contact.key)) BoundSendOutcome.AUTH_FAILED
                        else mesh.sendPrivateMessageBoundOutcome(content, live, contact.key, messageID) { canSend(conversationID, mesh) }
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            BoundSendOutcome.UNCONFIRMED
        } ?: BoundSendOutcome.UNCONFIRMED
        if (outcome == BoundSendOutcome.SENT) AppStateStore.updatePrivateMessageStatus(conversationID, messageID, DeliveryStatus.Sent, MessageTransport.BLUETOOTH)
        return outcome
    }

    companion object {
        const val FAILED_WRITE = "Connexion ou identité indisponible. Réessayez après vérification."
    }
}
