package com.bitchat.android.services

import com.bitchat.android.model.DeliveryStatus

/**
 * The UI side of a Wesh private send (spec §3 rules 1 to 3): any enrolled, unblocked contact is
 * writable, and the router picks Bluetooth, Internet or the queue. The message is already stored
 * as « En attente »; the router moves it on.
 */
internal class WeshPrivateCompose(
    private val sender: WeshPrivateSender,
    private val router: () -> MessageRouter
) {
    fun canCompose(conversationID: String): Boolean = sender.enrolled(conversationID)

    fun route(content: String, conversationID: String, nickname: String, messageID: String): MessageRouter.RouteResult {
        val result = runCatching { router().sendPrivate(content, conversationID, nickname, messageID) }
            .getOrDefault(MessageRouter.RouteResult.DROPPED)
        if (result == MessageRouter.RouteResult.DROPPED) {
            AppStateStore.updatePrivateMessageStatus(conversationID, messageID, DeliveryStatus.Failed("Contact retiré ou bloqué"))
        }
        return result
    }
}
