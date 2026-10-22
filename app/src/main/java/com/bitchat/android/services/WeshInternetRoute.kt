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
import com.bitchat.android.nostr.RelayPublishOutcome
import com.bitchat.android.util.AppConstants

/** Nostr side of the Wesh Internet route; injectable so routing is testable without relays. */
internal interface WeshInternetLink {
    /** Tor bootstrapped and at least one pinned relay connected; anything else fails closed. */
    fun isOn(): Boolean
    /** Read-only: the Internet route never creates key material (Internet mode does, on opt-in). */
    fun myIdentity(): NostrIdentity?
    /** Fire and forget, through the relay queue: acks and receipts. */
    fun publish(event: NostrEvent)
    /** Private messages: waits for a relay `OK`; never queued by the relay layer. */
    suspend fun publishConfirmed(event: NostrEvent): RelayPublishOutcome

    class Live(private val context: Context) : WeshInternetLink {
        override fun isOn(): Boolean = runCatching {
            WeshNetworkPolicy.allowsNetwork() && WeshInternetMode.current()?.state?.value == WeshInternetState.On
        }.getOrDefault(false)
        override fun myIdentity(): NostrIdentity? = WeshQrIdentity.mine(context)
        override fun publish(event: NostrEvent) {
            NostrRelayManager.registerPendingGiftWrap(event.id)
            NostrRelayManager.getInstance(context).sendEvent(event)
        }
        override suspend fun publishConfirmed(event: NostrEvent): RelayPublishOutcome {
            NostrRelayManager.registerPendingGiftWrap(event.id)
            return NostrRelayManager.getInstance(context).sendEventConfirmed(event)
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

    /** One attempt and the signed gift wrap it published, kept so a retry can send it again. */
    data class Send(val outcome: RelayPublishOutcome, val giftWrap: NostrEvent?)

    /**
     * « Envoyé » only on [RelayPublishOutcome.ACCEPTED]. [previousWrap], from an unconfirmed attempt,
     * is published again unchanged, so relays deduplicate it; it is wrapped afresh when it no longer
     * targets the contact's npub or is too old for the receivers' lookback.
     */
    suspend fun sendPrivate(
        content: String,
        conversationID: String,
        messageID: String,
        previousWrap: NostrEvent? = null,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
        /** Checked right before the publish: false (deleted, blocked, erased meanwhile) sends nothing. */
        allowed: () -> Boolean = { true }
    ): Send {
        val notSent = Send(RelayPublishOutcome.NOT_SENT, previousWrap)
        val contact = recipient(conversationID) ?: return notSent
        if (!link.isOn()) return notSent
        val recipientHex = runCatching { ContactIdentityResolver.nostrPubkeyHex(contact.deliveryNpub ?: return notSent) }
            .getOrNull() ?: return notSent
        val wrap = previousWrap?.takeIf {
            it.tags == listOf(listOf("p", recipientHex)) &&
                nowSeconds - it.createdAt < AppConstants.Router.WESH_INTERNET_WRAP_REUSE_MAX_AGE_S
        } ?: envelope(conversationID) { c ->
            if (content.isBlank() || content.toByteArray().size > 2000) null
            else NostrEmbeddedBitChat.encodePMForNostr(content, messageID, c.noiseHex, myPeerID())
        }?.singleOrNull() ?: return notSent
        if (!runCatching(allowed).getOrDefault(false)) return Send(RelayPublishOutcome.NOT_SENT, previousWrap)
        val outcome = try {
            link.publishConfirmed(wrap)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            RelayPublishOutcome.UNCONFIRMED
        }
        return Send(outcome, wrap)
    }

    /** DELIVERED or READ_RECEIPT for a message that arrived over Internet; [allowed] is read right before it leaves. */
    fun sendAck(type: NoisePayloadType, messageID: String, conversationID: String, allowed: () -> Boolean = { true }): Boolean =
        publish(conversationID, allowed) { contact ->
            NostrEmbeddedBitChat.encodeAckForNostr(type, messageID, contact.noiseHex, myPeerID())
        }

    private fun publish(conversationID: String, allowed: () -> Boolean, embed: (WeshContactStore.Contact) -> String?): Boolean = runCatching {
        val events = envelope(conversationID, embed) ?: return@runCatching false
        if (!runCatching(allowed).getOrDefault(false)) return@runCatching false
        events.forEach(link::publish)
        true
    }.getOrDefault(false)

    /** The gift wraps for an enrolled contact while Internet is on; null when nothing may leave. */
    private fun envelope(conversationID: String, embed: (WeshContactStore.Contact) -> String?): List<NostrEvent>? = runCatching {
        val contact = recipient(conversationID) ?: return@runCatching null
        if (!link.isOn()) return@runCatching null
        val identity = link.myIdentity() ?: return@runCatching null
        val recipientHex = ContactIdentityResolver.nostrPubkeyHex(contact.deliveryNpub ?: return@runCatching null)
            ?: return@runCatching null
        val embedded = embed(contact) ?: return@runCatching null
        NostrProtocol.createPrivateMessage(embedded, recipientHex, identity).takeIf { it.isNotEmpty() }
    }.getOrNull()
}
