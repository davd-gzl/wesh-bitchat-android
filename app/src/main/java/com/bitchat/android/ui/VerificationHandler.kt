package com.bitchat.android.ui

import android.content.Context
import com.bitchat.android.R
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.noise.NoiseSession
import com.bitchat.android.nostr.GeohashAliasRegistry
import com.bitchat.android.services.ContactIdentityResolver
import com.bitchat.android.services.VerificationService
import com.bitchat.android.ui.pave.RemoteNameRule
import com.bitchat.android.util.dataFromHexString
import com.bitchat.android.util.hexEncodedString
import com.bitchat.android.util.safeDisplayName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles QR verification logic and state, extracted from ChatViewModel.
 */
class VerificationHandler(
    private val context: Context,
    private val scope: CoroutineScope,
    private val getMeshService: () -> MeshService,
    private val identityManager: SecureIdentityStateManager,
    private val state: ChatState,
    private val notificationManager: NotificationManager,
    private val messageManager: MessageManager,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    /** Read-only; null while an erase is unconfirmed or the key store is unreadable, which fails closed. Never creates keys. */
    private val ownedNpubs: suspend () -> Set<String>? = { com.bitchat.android.services.WeshQrIdentity.ownedNpubs(context) },
    private val remoteName: RemoteNameRule = RemoteNameRule.of(context)
) {
    // Helper to get current mesh service (may change after panic clear)
    private val meshService: MeshService
        get() = getMeshService()

    private val _verifiedFingerprints = MutableStateFlow<Set<String>>(emptySet())
    val verifiedFingerprints: StateFlow<Set<String>> = _verifiedFingerprints.asStateFlow()

    private val contacts = com.bitchat.android.services.WeshContactStore.get(context)
    private val completedAttempts = ConcurrentHashMap<String, Long>()
    private val pendingQRVerifications = ConcurrentHashMap<String, PendingVerification>()
    private val lastVerifyNonceByPeer = ConcurrentHashMap<String, ByteArray>()
    private val lastInboundVerifyChallengeAt = ConcurrentHashMap<String, Long>()
    private val inboundChallengeAt = ConcurrentHashMap<String, Long>()
    private val lastMutualToastAt = ConcurrentHashMap<String, Long>()

    fun loadVerifiedFingerprints() {
        _verifiedFingerprints.value = identityManager.getVerifiedFingerprints()
    }

    fun isPeerVerified(peerID: String): Boolean {
        if (peerID.startsWith("nostr_") || peerID.startsWith("nostr:")) return false
        val fingerprint = getPeerFingerprintForDisplay(peerID)
        return fingerprint != null && _verifiedFingerprints.value.contains(fingerprint)
    }

    fun isNoisePublicKeyVerified(noisePublicKey: ByteArray): Boolean {
        val fingerprint = fingerprintFromNoiseBytes(noisePublicKey)
        return _verifiedFingerprints.value.contains(fingerprint)
    }

    fun unverifyFingerprint(peerID: String) {
        val fingerprint = meshService.getPeerFingerprint(peerID) ?: return
        identityManager.setVerifiedFingerprint(fingerprint, false)
        val current = _verifiedFingerprints.value.toMutableSet()
        current.remove(fingerprint)
        _verifiedFingerprints.value = current
    }

    fun beginQRVerification(qr: VerificationService.VerificationQR): Boolean {
        if (contacts.isBlocked(qr.noiseKeyHex.dataFromHexString() ?: return false)) return false
        completedAttempts.remove(qr.noiseKeyHex.lowercase())
        val targetNoise = qr.noiseKeyHex.lowercase()
        val peerID = state.getConnectedPeersValue().firstOrNull { pid ->
            val noiseKeyHex = meshService.getPeerInfo(pid)?.noisePublicKey?.hexEncodedString()?.lowercase()
            noiseKeyHex == targetNoise
        } ?: return false

        pendingQRVerifications[peerID]?.let { existing ->
            if (isFreshAttempt(existing) &&
                existing.noiseKeyHex.equals(qr.noiseKeyHex, true) && existing.signKeyHex.equals(qr.signKeyHex, true)) return true
            pendingQRVerifications.remove(peerID)
        }
        val nonce = ByteArray(16)
        java.security.SecureRandom().nextBytes(nonce)
        val npubProof = qr.npubSigHex?.let { com.bitchat.android.services.WeshNpubProof.isValid(qr) }
        val pending = PendingVerification(qr.noiseKeyHex, qr.signKeyHex, nonce, nowMs(), false, qr.nickname, qr.npub, npubProof)
        pendingQRVerifications[peerID] = pending

        if (meshService.getSessionState(peerID) is NoiseSession.NoiseSessionState.Established) {
            if (!sessionMatches(peerID, qr.noiseKeyHex)) {
                pendingQRVerifications.remove(peerID)
                return false
            }
            meshService.sendVerifyChallenge(peerID, qr.noiseKeyHex, nonce)
            pendingQRVerifications[peerID] = pending.copy(sent = true)
        } else {
            meshService.initiateNoiseHandshake(peerID)
        }
        fingerprintFromNoiseHex(qr.noiseKeyHex)?.let { fp ->
            safeDisplayName(qr.nickname, "").takeIf { it.isNotEmpty() }?.let { identityManager.cacheFingerprintNickname(fp, it) }
            identityManager.cacheNoiseFingerprint(qr.noiseKeyHex, fp)
            identityManager.cachePeerNoiseKey(peerID, qr.noiseKeyHex)
        }
        return true
    }

    fun sendPendingVerificationIfNeeded(peerID: String) {
        val pending = pendingQRVerifications[peerID] ?: return
        if (!isFreshAttempt(pending)) { pendingQRVerifications.remove(peerID); return }
        if (pending.sent) return
        if (!sessionMatches(peerID, pending.noiseKeyHex)) {
            pendingQRVerifications.remove(peerID)
            return
        }
        meshService.sendVerifyChallenge(peerID, pending.noiseKeyHex, pending.nonceA)
        pendingQRVerifications[peerID] = pending.copy(sent = true)
    }

    fun didReceiveVerifyChallenge(peerID: String, payload: ByteArray) {
        scope.launch {
            val parsed = VerificationService.parseVerifyChallenge(payload) ?: return@launch
            val myNoiseHex = meshService.getStaticNoisePublicKey()?.hexEncodedString()?.lowercase() ?: return@launch
            if (parsed.first.lowercase() != myNoiseHex) return@launch

            val lastNonce = lastVerifyNonceByPeer[peerID]
            if (lastNonce != null && lastNonce.contentEquals(parsed.second)) return@launch
            lastVerifyNonceByPeer[peerID] = parsed.second

            meshService.sendVerifyResponse(peerID, parsed.first, parsed.second)
            inboundChallengeAt[peerID] = nowMs()
        }
    }

    /**
     * Échanger's "they scanned you" check: a challenge for our key arrived since [sinceMs] from the
     * peer whose session key is [noiseHex], the contact we are scanning. Nothing lights before our
     * own scan names that key, so a neighbour's unrelated challenge never shows. Display only.
     */
    fun weshScannedMeSince(sinceMs: Long, noiseHex: String?): Boolean {
        if (noiseHex == null) return false
        return inboundChallengeAt.any { (peerID, at) -> at >= sinceMs && sessionMatches(peerID, noiseHex) }
    }

    fun didReceiveVerifyResponse(peerID: String, payload: ByteArray) {
        scope.launch {
            val resp = VerificationService.parseVerifyResponse(payload) ?: return@launch
            val pending = pendingQRVerifications[peerID] ?: return@launch
            if (!isFreshAttempt(pending) || contacts.isBlocked(pending.noiseKeyHex.dataFromHexString() ?: return@launch) || !sessionMatches(peerID, pending.noiseKeyHex)) {
                pendingQRVerifications.remove(peerID)
                return@launch
            }
            if (!resp.noiseKeyHex.equals(pending.noiseKeyHex, ignoreCase = true)) return@launch
            if (!resp.nonceA.contentEquals(pending.nonceA)) return@launch

            val ok = VerificationService.verifyResponseSignature(
                noiseKeyHex = resp.noiseKeyHex,
                nonceA = resp.nonceA,
                signature = resp.signature,
                signerPublicKeyHex = pending.signKeyHex
            )
            if (!ok) return@launch

            // Claim the attempt first so duplicate responses bail before any key store work.
            if (!pendingQRVerifications.remove(peerID, pending)) return@launch
            // Read my npubs off the Main thread. A panic, a cancel, a contact removal or a block during
            // that read is caught below, and nothing suspends between those checks and the writes.
            val generation = weshGeneration
            val removals = contacts.removalEpoch
            val owned = ownedNpubs()
            if (generation != weshGeneration || removals != contacts.removalEpoch || !isFreshAttempt(pending)) return@launch
            if (contacts.isBlocked(pending.noiseKeyHex.dataFromHexString() ?: return@launch)) return@launch
            val fp = fingerprintFromNoiseHex(pending.noiseKeyHex) ?: return@launch
            // The QR signature covers the npub and this response proves the signer holds the Noise session.
            // A contact list that could not be read must never be replaced: abort the verification.
            contacts.enroll(pending.noiseKeyHex.dataFromHexString() ?: return@launch, pending.nickname, pending.npub, pending.npubProof, owned)
                ?: return@launch
            completedAttempts[pending.noiseKeyHex.lowercase()] = nowMs()
            identityManager.setVerifiedFingerprint(fp, true)
            val current = _verifiedFingerprints.value.toMutableSet()
            current.add(fp)
            _verifiedFingerprints.value = current

            // The announced nickname is remote input: stripped for the cache, the full rule where shown.
            val announced = try { meshService.getPeerInfo(peerID)?.nickname } catch (_: Exception) { null }
            safeDisplayName(announced.orEmpty(), "").takeIf { it.isNotEmpty() }?.let { identityManager.cacheFingerprintNickname(fp, it) }
            val noiseKeyHex = try {
                meshService.getPeerInfo(peerID)?.noisePublicKey?.hexEncodedString()
            } catch (_: Exception) {
                null
            }
            if (noiseKeyHex != null) {
                identityManager.cachePeerNoiseKey(peerID, noiseKeyHex)
                identityManager.cacheNoiseFingerprint(noiseKeyHex, fp)
            }
            addVerificationSystemMessage(peerID, context.getString(R.string.verify_success_system_message, remoteName.show(announced)))
            notificationManager.showVerificationSuccessNotification(announced, peerID)
            // BitChat peers do not read the npub in my QR: they learn it from this notice, sent only while Internet mode is active.
            com.bitchat.android.services.MessageRouter.tryGetInstance()?.announceWeshInternetKey(peerID)


        }
    }

    internal fun isFreshAttemptTime(startedAt: Long): Boolean = (nowMs() - startedAt) in 0..60_000L
    private fun isFreshAttempt(pending: PendingVerification): Boolean = isFreshAttemptTime(pending.startedAtMs)

    /** Bumped by panic and cancel so a verification resumed after either never writes. */
    @Volatile private var weshGeneration = 0L

    fun clearWeshVerifications() { weshGeneration++; pendingQRVerifications.clear(); completedAttempts.clear(); inboundChallengeAt.clear() }

    fun cancelWeshVerification(conversationID: String) {
        weshGeneration++
        pendingQRVerifications.entries.removeIf { (_, pending) ->
            pending.noiseKeyHex.dataFromHexString()?.let { ContactIdentityResolver.contactConversationIdForNoiseKey(it) } == conversationID
        }
        contacts.contact(conversationID)?.let { completedAttempts.remove(it.noiseHex) }
    }
    fun weshProgress(noiseHex: String): String {
        val pending = pendingQRVerifications.values.firstOrNull { it.noiseKeyHex.equals(noiseHex, true) }
        if (pending != null) return if (isFreshAttempt(pending)) "pending" else "expired"
        return if (completedAttempts.containsKey(noiseHex.lowercase()) && contacts.contacts.value.any { it.noiseHex.equals(noiseHex, true) }) "verified" else "unavailable"
    }

    private fun sessionMatches(peerID: String, noiseHex: String): Boolean =
        WeshMessagePolicy.sessionMatches(meshService.getAuthenticatedRemoteStaticKey(peerID), noiseHex)

    fun getPeerFingerprintForDisplay(peerID: String): String? {
        val fromMap = state.getPeerFingerprintsValue()[peerID]
        if (fromMap != null) return fromMap
        val hexRegex = Regex("^[0-9a-fA-F]+$")
        return try {
            when {
                ContactIdentityResolver.isContactConversationId(peerID) -> {
                    ContactIdentityResolver.fingerprintFromContactConversationId(peerID)
                }
                peerID.length == 64 && peerID.matches(hexRegex) -> {
                    identityManager.getCachedNoiseFingerprint(peerID)?.let { return it }
                    fingerprintFromNoiseHex(peerID)?.also { identityManager.cacheNoiseFingerprint(peerID, it) }
                }
                peerID.length == 16 && peerID.matches(hexRegex) -> {
                    val meshFp = meshService.getPeerFingerprint(peerID)
                    if (meshFp != null) return meshFp
                    identityManager.getCachedPeerFingerprint(peerID)?.let { return it }
                    identityManager.getCachedNoiseKey(peerID)?.let { noiseHex ->
                        identityManager.getCachedNoiseFingerprint(noiseHex)?.let { return it }
                        return fingerprintFromNoiseHex(noiseHex)?.also { identityManager.cacheNoiseFingerprint(noiseHex, it) }
                    }
                    val favorite = try {
                        FavoritesPersistenceService.shared.getFavoriteStatus(peerID)
                    } catch (_: Exception) {
                        null
                    }
                    favorite?.peerNoisePublicKey?.let { fingerprintFromNoiseBytes(it) }
                }
                peerID.startsWith("nostr_") -> {
                    val pubHex = GeohashAliasRegistry.get(peerID)
                    val noiseKey = pubHex?.let {
                        FavoritesPersistenceService.shared.findNoiseKey(it)
                    }
                    noiseKey?.let {
                        val noiseHex = it.hexEncodedString()
                        identityManager.getCachedNoiseFingerprint(noiseHex) ?: fingerprintFromNoiseBytes(it)
                    }
                }
                peerID.startsWith("nostr:") -> {
                    val prefix = peerID.removePrefix("nostr:").lowercase()
                    val pubHex = GeohashAliasRegistry
                        .snapshot()
                        .values
                        .firstOrNull { it.lowercase().startsWith(prefix) }
                    val noiseKey = pubHex?.let {
                        FavoritesPersistenceService.shared.findNoiseKey(it)
                    }
                    noiseKey?.let {
                        val noiseHex = it.hexEncodedString()
                        identityManager.getCachedNoiseFingerprint(noiseHex) ?: fingerprintFromNoiseBytes(it)
                    }
                }
                else -> {
                    val meshFp = meshService.getPeerFingerprint(peerID)
                    if (meshFp != null) return meshFp
                    identityManager.getCachedPeerFingerprint(peerID)?.let { return it }
                    identityManager.getCachedNoiseKey(peerID)?.let { noiseHex ->
                        identityManager.getCachedNoiseFingerprint(noiseHex)?.let { return it }
                        return fingerprintFromNoiseHex(noiseHex)?.also { identityManager.cacheNoiseFingerprint(noiseHex, it) }
                    }
                    val favorite = try {
                        FavoritesPersistenceService.shared.getFavoriteStatus(peerID)
                    } catch (_: Exception) {
                        null
                    }
                    favorite?.peerNoisePublicKey?.let { fingerprintFromNoiseBytes(it) }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Every source below can be remote input (announce, QR, favorite): the remote rule decides what is shown. */
    fun resolvePeerDisplayNameForFingerprint(peerID: String): String =
        remoteName.show(rawPeerDisplayNameForFingerprint(peerID))

    private fun rawPeerDisplayNameForFingerprint(peerID: String): String? {
        val nicknameMap = state.peerNicknames.value
        nicknameMap[peerID]?.let { return it }
        try {
            meshService.getPeerInfo(peerID)?.nickname?.let { return it }
        } catch (_: Exception) { }

        val fingerprint = getPeerFingerprintForDisplay(peerID)
        fingerprint?.let { fp ->
            identityManager.getCachedFingerprintNickname(fp)?.let { cached ->
                if (cached.isNotBlank()) return cached
            }
        }

        val hexRegex = Regex("^[0-9a-fA-F]+$")
        if (peerID.length == 64 && peerID.matches(hexRegex)) {
            val noiseKeyBytes = try {
                peerID.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            } catch (_: Exception) { null }
            val favorite = noiseKeyBytes?.let {
                FavoritesPersistenceService.shared.getFavoriteStatus(it)
            }
            favorite?.peerNickname?.takeIf { it.isNotBlank() }?.let { return it }
        }

        if (peerID.length == 16 && peerID.matches(hexRegex)) {
            val favorite = try {
                FavoritesPersistenceService.shared.getFavoriteStatus(peerID)
            } catch (_: Exception) {
                null
            }
            favorite?.peerNickname?.takeIf { it.isNotBlank() }?.let { return it }
        }

        // No name: shown as unnamed, never as a hex prefix that could pass for a fingerprint.
        return null
    }

    fun getMyFingerprint(): String {
        return meshService.getIdentityFingerprint()
    }

    fun verifyFingerprintValue(fingerprint: String) {
        if (fingerprint.isBlank()) return
        identityManager.setVerifiedFingerprint(fingerprint, true)
        val current = _verifiedFingerprints.value.toMutableSet()
        current.add(fingerprint)
        _verifiedFingerprints.value = current
    }

    fun unverifyFingerprintValue(fingerprint: String) {
        if (fingerprint.isBlank()) return
        identityManager.setVerifiedFingerprint(fingerprint, false)
        val current = _verifiedFingerprints.value.toMutableSet()
        current.remove(fingerprint)
        _verifiedFingerprints.value = current
    }

    private fun addVerificationSystemMessage(peerID: String, text: String) {
        val msg = BitchatMessage(
            sender = "system",
            content = text,
            timestamp = Date(),
            isRelay = false,
            isPrivate = true,
            senderPeerID = peerID
        )
        messageManager.addPrivateMessageNoUnread(peerID, msg)
    }


    private fun fingerprintFromNoiseHex(noiseHex: String): String? {
        val bytes = noiseHex.dataFromHexString() ?: return null
        return fingerprintFromNoiseBytes(bytes)
    }

    fun fingerprintFromNoiseBytes(bytes: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
        return hash.hexEncodedString()
    }

    private data class PendingVerification(
        val noiseKeyHex: String,
        val signKeyHex: String,
        val nonceA: ByteArray,
        val startedAtMs: Long,
        val sent: Boolean,
        val nickname: String,
        val npub: String?,
        /** null: no proof in the QR; else whether it verified. */
        val npubProof: Boolean?
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as PendingVerification

            if (startedAtMs != other.startedAtMs) return false
            if (sent != other.sent) return false
            if (noiseKeyHex != other.noiseKeyHex) return false
            if (signKeyHex != other.signKeyHex) return false
            if (!nonceA.contentEquals(other.nonceA)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = startedAtMs.hashCode()
            result = 31 * result + sent.hashCode()
            result = 31 * result + noiseKeyHex.hashCode()
            result = 31 * result + signKeyHex.hashCode()
            result = 31 * result + nonceA.contentHashCode()
            return result
        }
    }
}
