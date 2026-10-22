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
import kotlinx.coroutines.CoroutineDispatcher
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
    /** Durable record of DELIVERED acks already sent, so the 48 h lookback never re-acks after a restart. */
    private val alreadyAcked: (messageID: String) -> Boolean,
    private val markAcked: (messageID: String, expectedToken: SeenReceiptAdmission.Token?) -> Unit,
    /** The Bluetooth admission gate (session-hidden peers, block list, unloaded list fails closed). */
    private val senderBlocked: (WeshContactStore.Contact) -> Boolean,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 }
) {
    enum class Outcome { STORED, ACK_APPLIED, DROPPED }

    private val seen = LinkedHashSet<String>()

    suspend fun onGiftWrap(event: NostrEvent, expectedToken: SeenReceiptAdmission.Token?): Outcome {
        val identity = myIdentity() ?: return Outcome.DROPPED
        val (content, rawSender, rumorSeconds) = runCatching { NostrProtocol.decryptPrivateMessage(event, identity) }
            .getOrNull() ?: return Outcome.DROPPED
        if (!inWindow(rumorSeconds.toLong())) return Outcome.DROPPED
        val senderNpub = ContactIdentityResolver.npubFromHex(rawSender.lowercase()) ?: return Outcome.DROPPED
        val contact = runCatching { contacts.forNpub(senderNpub) }.getOrNull()
            ?.takeIf { it.deliveryNpub == senderNpub && !contacts.isBlocked(it.key) }
            ?: return Outcome.DROPPED
        if (runCatching { senderBlocked(contact) }.getOrDefault(true)) return Outcome.DROPPED
        // Recorded only once the sealed sender is a QR contact, so strangers cannot evict entries.
        // Message IDs are deduplicated durably by the store; this only saves repeated work and acks.
        if (!firstSight(event.id)) return Outcome.DROPPED
        val payload = decodePayload(content) ?: return Outcome.DROPPED
        val conversationID = contact.conversationID
        return when (payload.type) {
            NoisePayloadType.PRIVATE_MESSAGE -> {
                val pm = PrivateMessagePacket.decode(payload.data) ?: return Outcome.DROPPED
                if (!isPlausibleMessageID(pm.messageID)) return Outcome.DROPPED
                if (pm.content.isBlank() || pm.content.toByteArray().size > 2000) return Outcome.DROPPED
                if (FavoriteControlMessage.parse(pm.content) != null) return Outcome.DROPPED
                val message = BitchatMessage(
                    id = pm.messageID,
                    sender = contact.nickname,
                    content = pm.content,
                    timestamp = Date(rumorSeconds * 1000L),
                    isPrivate = true,
                    senderPeerID = conversationID,
                    senderNostrPubkey = rawSender.lowercase(),
                    transport = com.bitchat.android.model.MessageTransport.INTERNET
                )
                if (!runCatching { deliver(conversationID, message) }.getOrDefault(false)) return Outcome.DROPPED
                // The ack goes back over the transport the message came on (spec §3 rule 5), once.
                // Keyed by the raw (validated) ID, like the Bluetooth path, so deleting the
                // conversation also purges this record.
                if (!runCatching { alreadyAcked(pm.messageID) }.getOrDefault(false) &&
                    route.sendAck(NoisePayloadType.DELIVERED, pm.messageID, conversationID)) {
                    runCatching { markAcked(pm.messageID, expectedToken) }
                }
                Outcome.STORED
            }
            NoisePayloadType.DELIVERED, NoisePayloadType.READ_RECEIPT -> {
                val messageID = String(payload.data, Charsets.UTF_8).takeIf(::isPlausibleMessageID)
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

    /** Remote input: BitChat message IDs are UUID-like ASCII, so anything else is refused. */
    private fun isPlausibleMessageID(id: String): Boolean =
        id.length in 1..128 && id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }

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
 * Lifecycle of the gift-wrap subscription for my npub: subscribed only while Internet mode is
 * On and my key exists; on Off (toggle or panic) unsubscribed and the inbox forgotten.
 */
/** Calls [onOn] once per transition into [WeshInternetState.On]. */
internal class WeshInternetOnWatcher(private val onOn: () -> Unit) {
    private var wasOn = false
    @Synchronized fun apply(state: WeshInternetState) {
        val on = state == WeshInternetState.On
        if (on && !wasOn) runCatching(onOn)
        wasOn = on
    }
}

/** Calls [onReconnect] when a relay seen disconnected connects again. */
internal class WeshRelayReconnectWatcher(private val onReconnect: () -> Unit) {
    private val connected = HashMap<String, Boolean>()
    @Synchronized fun apply(relays: List<Pair<String, Boolean>>) {
        var reconnected = false
        for ((url, isConnected) in relays) {
            if (isConnected && connected[url] == false) reconnected = true
            connected[url] = isConnected
        }
        if (reconnected) runCatching(onReconnect)
    }
}

internal class WeshInternetSubscription(
    private val myIdentity: () -> NostrIdentity?,
    private val subscribe: (filter: NostrFilter, handler: (NostrEvent) -> Unit) -> Unit,
    private val unsubscribe: () -> Unit,
    private val onEvent: (NostrEvent) -> Unit,
    private val onOff: () -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    fun apply(state: WeshInternetState) {
        when (state) {
            WeshInternetState.On -> {
                val me = runCatching { myIdentity() }.getOrNull() ?: return
                val sinceMs = (nowMs() / 1000 - WeshInternetInbox.LOOKBACK_SECONDS) * 1000
                subscribe(NostrFilter.giftWrapsFor(me.publicKeyHex, sinceMs), onEvent)
            }
            WeshInternetState.Off -> {
                runCatching(unsubscribe)
                runCatching(onOff)
            }
            else -> Unit
        }
    }
}

/** Shows a new Internet message; [stillCurrent] turns false once an erase has started since it was stored. */
internal typealias WeshInternetNotifier = (conversationID: String, message: BitchatMessage, stillCurrent: () -> Boolean) -> Unit

/**
 * Keeps one gift-wrap subscription for my npub on the pinned relays while Internet mode is On.
 * It never opens a socket itself: NostrRelayManager does, behind Tor and the opt-in gate.
 */
internal object WeshInternetReceiver {
    internal const val SUBSCRIPTION_ID = "wesh-internet-dm"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    @Volatile private var inbox: WeshInternetInbox? = null

    /**
     * Stores an Internet-received message once. The same ID already received (over Bluetooth, or a
     * rule 4 resend) keeps its single bubble and still counts as delivered, so it is acked again.
     * Seen IDs outlive an unloaded history; IDs are random UUIDs, so a match is this message.
     */
    internal suspend fun deliverOnce(
        conversationID: String,
        message: BitchatMessage,
        notify: WeshInternetNotifier = ::notifyNew
    ): Boolean {
        // Captured before the store: an erase that starts after it turns stillCurrent false.
        val generation = AppStateStore.capturePrivateConversationGeneration()
        if (AppStateStore.addPrivateMessageDurably(conversationID, message)) {
            // A new message only: a copy already here was notified when it first arrived.
            if (generation != null) {
                runCatching { notify(conversationID, message) { AppStateStore.isCurrentPrivateConversationGeneration(generation) } }
            }
            return true
        }
        return AppStateStore.hasSeenMessage(message.id)
    }

    /** Set while a UI is attached: its notifier knows the open chat and whether the app is in front. */
    @Volatile internal var uiNotifier: WeshInternetNotifier? = null
    /** Without a UI (app closed, service running): shown as a background notification, like Bluetooth. */
    @Volatile private var backgroundNotifier: WeshInternetNotifier? = null

    /**
     * Same path and privacy rules as a Bluetooth private message: muted conversations stay silent and
     * Wesh notifications are redacted by [com.bitchat.android.ui.NotificationManager].
     */
    private fun notifyNew(conversationID: String, message: BitchatMessage, stillCurrent: () -> Boolean) {
        (uiNotifier ?: backgroundNotifier)?.invoke(conversationID, message, stillCurrent)
    }

    /**
     * The UI notifier: [show] runs on [main], where the UI's NotificationManager keeps its lists, and
     * only if no erase started since the message was stored. Delivery runs on the receiver's IO thread.
     */
    internal fun postOnMain(
        scope: CoroutineScope,
        main: CoroutineDispatcher = Dispatchers.Main,
        show: (String, BitchatMessage) -> Unit
    ): WeshInternetNotifier = { conversationID, message, stillCurrent ->
        scope.launch(main) { if (stillCurrent()) runCatching { show(conversationID, message) } }
    }

    fun start(app: Application): Unit = synchronized(this) {
        if (inbox != null) return
        val notifications = com.bitchat.android.ui.NotificationManager(
            app.applicationContext,
            androidx.core.app.NotificationManagerCompat.from(app.applicationContext)
        )
        // Called on the receiver's single IO thread, the only user of this manager.
        backgroundNotifier = { conversationID, message, stillCurrent ->
            if (stillCurrent()) {
                notifications.setAppBackgroundState(true)
                notifications.showPrivateMessageNotification(conversationID, message.sender.takeIf { it.isNotBlank() },
                    com.bitchat.android.ui.NotificationTextUtils.buildPrivateMessagePreview(message))
            }
        }
        val contacts = WeshContactStore.get(app)
        val seen = SeenMessageStore.getInstance(app)
        val created = WeshInternetInbox(
            contacts = contacts,
            myIdentity = { WeshQrIdentity.mine(app) },
            route = WeshInternetRoute(contacts, WeshInternetLink.Live(app)) {
                com.bitchat.android.nostr.NostrTransport.getInstance(app).senderPeerID
            },
            deliver = ::deliverOnce,
            updateStatus = { conversationID, messageID, status ->
                // Only that contact can reach here, only its own conversation, never a downgrade.
                AppStateStore.updatePrivateMessageStatus(conversationID, messageID, status,
                    com.bitchat.android.model.MessageTransport.INTERNET)
            },
            alreadyAcked = seen::hasDelivered,
            markAcked = seen::markDelivered,
            senderBlocked = { contact ->
                val fingerprint = ContactIdentityResolver.fingerprintHex(contact.key)
                IncomingMessageAdmission.isSenderBlocked(contact.conversationID) { fingerprint } ||
                    IncomingMessageAdmission.isSenderBlocked(ContactIdentityResolver.peerIdForNoiseKey(contact.key)) { fingerprint }
            }
        )
        inbox = created
        val relays = NostrRelayManager.getInstance(app)
        val subscription = WeshInternetSubscription(
            myIdentity = { WeshQrIdentity.mine(app) },
            subscribe = { filter, handler -> relays.subscribe(filter = filter, id = SUBSCRIPTION_ID, handler = handler) },
            unsubscribe = { relays.unsubscribe(SUBSCRIPTION_ID) },
            onEvent = { event ->
                val seenToken = SeenMessageStore.captureReceiptToken()
                scope.launch { created.onGiftWrap(event, seenToken) }
            },
            onOff = created::clear
        )
        val announcer = WeshInternetOnWatcher { MessageRouter.tryGetInstance()?.onWeshInternetOn() }
        val reconnects = WeshRelayReconnectWatcher { MessageRouter.tryGetInstance()?.onWeshInternetReconnected() }
        scope.launch { relays.relays.collect { list -> reconnects.apply(list.map { it.url to it.isConnected }) } }
        scope.launch {
            WeshInternetMode.get(app).state.collect { state ->
                subscription.apply(state)
                announcer.apply(state)
            }
        }
        Unit
    }
}
