package com.bitchat.android.services

import android.app.Application
import com.bitchat.android.favorites.FavoriteControlMessage
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.model.NoisePayload
import com.bitchat.android.model.NoisePayloadType
import com.bitchat.android.model.PrivateMessagePacket
import com.bitchat.android.net.WeshInternetMode
import com.bitchat.android.net.WeshInternetState
import com.bitchat.android.nostr.NostrEvent
import com.bitchat.android.nostr.NostrFilter
import com.bitchat.android.nostr.NostrIdentity
import com.bitchat.android.nostr.NostrProtocol
import com.bitchat.android.nostr.NostrRelayManager
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Date

/**
 * Inbound Internet messages. A sender counts only when its key is the npub bound to a QR contact
 * (proven holder, no conflict flag) and that contact is not blocked; everything else is dropped
 * without an ack, so a stranger never learns this phone exists. `[FAVORITED]` notices are never
 * trusted over Internet.
 */
internal class WeshInternetInbox(
    private val contacts: WeshContactStore,
    private val myIdentity: () -> NostrIdentity?,
    private val route: WeshInternetRoute,
    /** Stores the message; true when it is stored now or was already (duplicate over Bluetooth). */
    private val deliver: suspend (conversationID: String, message: BitchatMessage) -> Boolean,
    /** Applies an ack only to a message of that contact's conversation. */
    private val updateStatus: (conversationID: String, messageID: String, status: DeliveryStatus) -> Unit,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 }
) {
    enum class Outcome { STORED, ACK_APPLIED, DROPPED }

    private val seen = LinkedHashSet<String>()

    suspend fun onGiftWrap(event: NostrEvent): Outcome {
        if (!firstSight(event.id)) return Outcome.DROPPED
        val identity = myIdentity() ?: return Outcome.DROPPED
        val (content, rawSender, rumorSeconds) = runCatching { NostrProtocol.decryptPrivateMessage(event, identity) }
            .getOrNull() ?: return Outcome.DROPPED
        if (!inWindow(rumorSeconds.toLong())) return Outcome.DROPPED
        val senderNpub = ContactIdentityResolver.npubFromHex(rawSender.lowercase()) ?: return Outcome.DROPPED
        val contact = runCatching { contacts.forNpub(senderNpub) }.getOrNull()
            ?.takeIf { it.deliveryNpub == senderNpub && !contacts.isBlocked(it.key) }
            ?: return Outcome.DROPPED
        val payload = decodePayload(content) ?: return Outcome.DROPPED
        val conversationID = contact.conversationID
        return when (payload.type) {
            NoisePayloadType.PRIVATE_MESSAGE -> {
                val pm = PrivateMessagePacket.decode(payload.data) ?: return Outcome.DROPPED
                if (pm.content.isBlank() || pm.content.toByteArray().size > 2000) return Outcome.DROPPED
                if (FavoriteControlMessage.parse(pm.content) != null) return Outcome.DROPPED
                val message = BitchatMessage(
                    id = pm.messageID,
                    sender = contact.nickname,
                    content = pm.content,
                    timestamp = Date(rumorSeconds * 1000L),
                    isPrivate = true,
                    senderPeerID = conversationID,
                    senderNostrPubkey = rawSender.lowercase()
                )
                if (!runCatching { deliver(conversationID, message) }.getOrDefault(false)) return Outcome.DROPPED
                // The ack goes back over the transport the message came on (spec §3 rule 5).
                route.sendAck(NoisePayloadType.DELIVERED, pm.messageID, conversationID)
                Outcome.STORED
            }
            NoisePayloadType.DELIVERED, NoisePayloadType.READ_RECEIPT -> {
                val messageID = String(payload.data, Charsets.UTF_8).takeIf { it.isNotBlank() && it.length <= 128 }
                    ?: return Outcome.DROPPED
                val status = if (payload.type == NoisePayloadType.DELIVERED) DeliveryStatus.Delivered(contact.nickname, Date())
                    else DeliveryStatus.Read(contact.nickname, Date())
                runCatching { updateStatus(conversationID, messageID, status) }
                Outcome.ACK_APPLIED
            }
            // Verification, files, voice and peer state are Bluetooth-only.
            else -> Outcome.DROPPED
        }
    }

    /** Panic: forget which events were seen, with the keys they were sealed to. */
    @Synchronized fun clear() = seen.clear()

    @Synchronized private fun firstSight(id: String): Boolean {
        if (!seen.add(id)) return false
        if (seen.size > MAX_SEEN) seen.remove(seen.first())
        return true
    }

    private fun inWindow(rumorSeconds: Long): Boolean {
        val now = nowSeconds()
        return rumorSeconds in (now - LOOKBACK_SECONDS)..(now + FUTURE_SKEW_SECONDS)
    }

    private fun decodePayload(content: String): NoisePayload? = runCatching {
        if (!content.startsWith(PREFIX)) return@runCatching null
        val data = java.util.Base64.getUrlDecoder().decode(content.removePrefix(PREFIX))
        val packet = BitchatPacket.fromBinaryData(data) ?: return@runCatching null
        if (packet.type != MessageType.NOISE_ENCRYPTED.value) return@runCatching null
        NoisePayload.decode(packet.payload)
    }.getOrNull()

    companion object {
        private const val PREFIX = "bitchat1:"
        private const val MAX_SEEN = 2000
        /** 48 h, plus the 15 min BitChat tolerates for clock skew. */
        internal const val LOOKBACK_SECONDS = 48L * 3600 + 15 * 60
        internal const val FUTURE_SKEW_SECONDS = 15L * 60
    }
}

/**
 * Keeps one gift-wrap subscription for my npub on the pinned relays while Internet mode is On.
 * It never opens a socket itself: NostrRelayManager does, behind Tor and the opt-in gate.
 */
internal object WeshInternetReceiver {
    internal const val SUBSCRIPTION_ID = "wesh-internet-dm"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    @Volatile private var inbox: WeshInternetInbox? = null

    fun start(app: Application): Unit = synchronized(this) {
        if (inbox != null) return
        val contacts = WeshContactStore.get(app)
        val created = WeshInternetInbox(
            contacts = contacts,
            myIdentity = { WeshQrIdentity.mine(app) },
            route = WeshInternetRoute(contacts, WeshInternetLink.Live(app)) {
                com.bitchat.android.nostr.NostrTransport.getInstance(app).senderPeerID
            },
            deliver = { conversationID, message ->
                AppStateStore.addPrivateMessageDurably(conversationID, message) ||
                    AppStateStore.privateMessages.value[conversationID].orEmpty().any { it.id == message.id }
            },
            updateStatus = { conversationID, messageID, status ->
                // Only that contact can reach here, and statuses never downgrade.
                val known = AppStateStore.privateMessages.value[conversationID].orEmpty().any { it.id == messageID }
                if (known) AppStateStore.updatePrivateMessageStatus(messageID, status)
            }
        )
        inbox = created
        scope.launch {
            WeshInternetMode.get(app).state.collect { state ->
                val relays = NostrRelayManager.getInstance(app)
                when (state) {
                    WeshInternetState.On -> {
                        val me = WeshQrIdentity.mine(app) ?: return@collect
                        val sinceMs = (System.currentTimeMillis() / 1000 - WeshInternetInbox.LOOKBACK_SECONDS) * 1000
                        relays.subscribe(
                            filter = NostrFilter.giftWrapsFor(me.publicKeyHex, sinceMs),
                            id = SUBSCRIPTION_ID,
                            handler = { event -> scope.launch { created.onGiftWrap(event) } }
                        )
                    }
                    WeshInternetState.Off -> {
                        runCatching { relays.unsubscribe(SUBSCRIPTION_ID) }
                        created.clear()
                    }
                    else -> Unit
                }
            }
        }
        Unit
    }
}
