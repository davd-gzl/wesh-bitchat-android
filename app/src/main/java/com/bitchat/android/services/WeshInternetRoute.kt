package com.bitchat.android.services

import android.content.Context
import com.bitchat.android.model.NoisePayloadType
import com.bitchat.android.net.WeshInternetMode
import com.bitchat.android.net.WeshInternetState
import com.bitchat.android.net.WeshNetworkPolicy
import com.bitchat.android.nostr.NostrEmbeddedBitChat
import com.bitchat.android.nostr.NostrEvent
import com.bitchat.android.nostr.NostrIdentity
import com.bitchat.android.nostr.NostrProtocol
import com.bitchat.android.nostr.NostrRelayManager

/** Nostr side of the Wesh Internet route; injectable so routing is testable without relays. */
internal interface WeshInternetLink {
    /** Tor bootstrapped and at least one pinned relay connected; anything else fails closed. */
    fun isOn(): Boolean
    /** Read-only: the Internet route never creates key material (Internet mode does, on opt-in). */
    fun myIdentity(): NostrIdentity?
    fun publish(event: NostrEvent)

    class Live(private val context: Context) : WeshInternetLink {
        override fun isOn(): Boolean = runCatching {
            WeshNetworkPolicy.allowsNetwork() && WeshInternetMode.current()?.state?.value == WeshInternetState.On
        }.getOrDefault(false)
        override fun myIdentity(): NostrIdentity? = WeshQrIdentity.mine(context)
        override fun publish(event: NostrEvent) {
            NostrRelayManager.registerPendingGiftWrap(event.id)
            NostrRelayManager.getInstance(context).sendEvent(event)
        }
    }
}

/**
 * Internet delivery to QR contacts only: the npub comes from the signed QR bound over Noise,
 * never from favorites or a `[FAVORITED]` notice. The envelope is BitChat's private format, so
 * official BitChat clients can read it.
 */
internal class WeshInternetRoute(
    private val contacts: WeshContactStore,
    private val link: WeshInternetLink,
    private val myPeerID: () -> String
) {
    /** An enrolled, unblocked contact with an npub Internet delivery may use. */
    fun recipient(conversationID: String): WeshContactStore.Contact? = runCatching {
        contacts.contact(conversationID)?.takeIf { it.deliveryNpub != null && !contacts.isBlocked(it.key) }
    }.getOrNull()

    /** Cheap enough for the UI thread: no key store read. */
    fun canSend(conversationID: String): Boolean = runCatching {
        link.isOn() && recipient(conversationID) != null
    }.getOrDefault(false)

    fun sendPrivate(content: String, conversationID: String, messageID: String): Boolean =
        publish(conversationID) { contact ->
            if (content.isBlank() || content.toByteArray().size > 2000) null
            else NostrEmbeddedBitChat.encodePMForNostr(content, messageID, contact.noiseHex, myPeerID())
        }

    /** DELIVERED or READ_RECEIPT for a message that arrived over Internet. */
    fun sendAck(type: NoisePayloadType, messageID: String, conversationID: String): Boolean =
        publish(conversationID) { contact ->
            NostrEmbeddedBitChat.encodeAckForNostr(type, messageID, contact.noiseHex, myPeerID())
        }

    private fun publish(conversationID: String, embed: (WeshContactStore.Contact) -> String?): Boolean = runCatching {
        val contact = recipient(conversationID) ?: return@runCatching false
        if (!link.isOn()) return@runCatching false
        val identity = link.myIdentity() ?: return@runCatching false
        val recipientHex = ContactIdentityResolver.nostrPubkeyHex(contact.deliveryNpub ?: return@runCatching false)
            ?: return@runCatching false
        val embedded = embed(contact) ?: return@runCatching false
        NostrProtocol.createPrivateMessage(embedded, recipientHex, identity).forEach(link::publish)
        true
    }.getOrDefault(false)
}
