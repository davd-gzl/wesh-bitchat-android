package com.bitchat.android.ui

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

internal class WeshPreferences(
    private val prefs: SharedPreferences,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val commitEditor: suspend (SharedPreferences.Editor) -> Boolean = { it.commit() }
) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE))

    companion object {
        const val FILE = "wesh_ui"
        private const val NOTIFICATION_SENDER = "notification_sender"

        /** Notifications show no sender unless the person explicitly opted in on this phone. */
        fun notificationShowsSender(context: Context): Boolean =
            context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .getBoolean(NOTIFICATION_SENDER, false)
    }

    private val lock = Any()
    private val io = Mutex()
    private var fenced = false
    private var meetingRevision = 0L
    private data class MeetingSnapshot(val text: String, val epoch: Long, val revision: Long)
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
    var notificationShowsSender: Boolean
        get() = prefs.getBoolean(NOTIFICATION_SENDER, false)
        set(value) {
            synchronized(lock) { if (!fenced) prefs.edit { putBoolean(NOTIFICATION_SENDER, value) } }
        }
    /** Pavé appearance name ("Nuit", "Jour", "Automatique"); Nuit when unset or after an erase. */
    var appearance: String
        get() = prefs.getString("appearance", "Nuit") ?: "Nuit"
        set(value) {
            synchronized(lock) { if (!fenced) prefs.edit { putString("appearance", value) } }
        }
    val meetingNote: String
        get() = prefs.getString("meeting_note", "") ?: ""

    fun editMeetingDraft(value: String, expectedEpoch: Long): Boolean = synchronized(lock) {
        if (fenced || expectedEpoch != _resetEpoch.value) return@synchronized false
        _meetingDraft.value = value
        meetingRevision++
        true
    }

    suspend fun saveMeetingDraft(expectedEpoch: Long): Boolean {
        val snapshot = synchronized(lock) {
            if (fenced || expectedEpoch != _resetEpoch.value) return false
            MeetingSnapshot(_meetingDraft.value, expectedEpoch, meetingRevision)
        }
        val confirmed = withContext(ioDispatcher) {
            io.withLock {
                if (!isCurrent(snapshot)) return@withLock false
                try {
                    // Retry the actual commit even when a failed prior commit changed the RAM cache.
                    val committed = commitEditor(prefs.edit().putString("meeting_note", snapshot.text))
                    committed && prefs.getString("meeting_note", null) == snapshot.text && isCurrent(snapshot)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
            }
        }
        return confirmed && isCurrent(snapshot)
    }

    private fun isCurrent(snapshot: MeetingSnapshot): Boolean = synchronized(lock) {
        !fenced && snapshot.epoch == _resetEpoch.value && snapshot.revision == meetingRevision
    }

    /** Blocks stale UI callbacks before any suspending panic work. */
    fun fenceForPanic() {
        synchronized(lock) {
            fenced = true
            _meetingDraft.value = ""
            meetingRevision++
            _resetEpoch.value++
        }
    }

    /** Waits for any in-flight save, then erases it before the global panic can resume. */
    suspend fun clearForPanic(): Boolean {
        fenceForPanic()
        return withContext(NonCancellable + ioDispatcher) {
            io.withLock {
                try {
                    commitEditor(prefs.edit().clear()) && prefs.all.isEmpty()
                } catch (_: Exception) {
                    false
                }
            }
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
    mediaCleared: Boolean,
    clearPreferences: suspend () -> Boolean,
    resume: suspend () -> Boolean,
    /** Internet off, Tor state deleted and Nostr keys erased. */
    internetCleared: Boolean = true
): Boolean {
    val preferencesCleared = try {
        clearPreferences()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
    if (!draftsCleared || !conversationsCleared || !mediaCleared || !internetCleared || !preferencesCleared) return false
    return resume()
}
