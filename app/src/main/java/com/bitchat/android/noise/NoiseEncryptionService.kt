package com.bitchat.android.noise

import android.content.Context
import android.util.Log
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.mesh.PeerFingerprintManager
import com.bitchat.android.noise.southernstorm.protocol.Noise
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Main Noise encryption service - 100% compatible with iOS implementation
 * 
 * This service manages:
 * - Static identity keys (persistent across sessions)
 * - Noise session management for each peer
 * - Peer fingerprint mapping and identity persistence
 */
class NoiseEncryptionService(private val context: Context) {
    
    companion object {
        private const val TAG = "NoiseEncryptionService"
        
        // Session limits for performance and security
        private const val REKEY_TIME_LIMIT = com.bitchat.android.util.AppConstants.Noise.REKEY_TIME_LIMIT_MS // 1 hour (same as iOS)
        private const val REKEY_MESSAGE_LIMIT = com.bitchat.android.util.AppConstants.Noise.REKEY_MESSAGE_LIMIT_ENCRYPTION // 1k messages (matches iOS) (same as iOS)
    }
    
    // Static identity key (persistent across app restarts) - loaded from secure storage
    private var staticIdentityPrivateKey: ByteArray
    private var staticIdentityPublicKey: ByteArray
    
    
    // Session management
    private lateinit var sessionManager: NoiseSessionManager
    
    // Identity management for peer ID rotation support
    private val identityStateManager: SecureIdentityStateManager
    
    // Centralized fingerprint management - NO LOCAL STORAGE
    private val fingerprintManager = PeerFingerprintManager.getInstance()
    
    // Callbacks
    var onPeerAuthenticated: ((String, String) -> Unit)? = null // (peerID, fingerprint)
    var onHandshakeRequired: ((String) -> Unit)? = null // peerID needs handshake
    
    init {
        // Initialize identity state manager for persistent storage
        identityStateManager = SecureIdentityStateManager(context)
        
        // Load or create keys - temporary placeholders
        staticIdentityPrivateKey = ByteArray(32)
        staticIdentityPublicKey = ByteArray(32)
        
        loadOrGenerateKeys()
        
        // Initialize session manager
        initializeSessionManager()
    }
    
    private fun initializeSessionManager() {
        // Create new session manager with current keys
        val localPeerID = calculateFingerprint(staticIdentityPublicKey).take(16)
        sessionManager = NoiseSessionManager(staticIdentityPrivateKey, staticIdentityPublicKey, localPeerID)
        
        // Set up session callbacks
        sessionManager.onSessionEstablished = { peerID, remoteStaticKey ->
            handleSessionEstablished(peerID, remoteStaticKey)
        }
        
        // Ensure any other callbacks are wired if needed
        // sessionManager.onSessionFailed could be wired if we exposed it
    }
    
    private fun loadOrGenerateKeys() {
        // Load or create static identity key (persistent across sessions)
        val loadedKeyPair = identityStateManager.loadStaticKey()
        if (loadedKeyPair != null) {
            staticIdentityPrivateKey = loadedKeyPair.first
            staticIdentityPublicKey = loadedKeyPair.second
            Log.d(TAG, "Identity loaded: ${calculateFingerprint(staticIdentityPublicKey).take(16)}")
        } else {
            // Generate new identity key pair
            val keyPair = generateKeyPair()
            staticIdentityPrivateKey = keyPair.first
            staticIdentityPublicKey = keyPair.second
            
            // Save to secure storage
            identityStateManager.saveStaticKey(staticIdentityPrivateKey, staticIdentityPublicKey)
        }
        
        // The identity signing key is EncryptionService's; drop the unused pair earlier builds stored here.
        identityStateManager.forgetRetiredSigningKey()
    }

    // MARK: - Public Interface
    
    /**
     * Get our static public key data for sharing (32 bytes)
     */
    fun getStaticPublicKeyData(): ByteArray {
        return staticIdentityPublicKey.clone()
    }

    /**
     * Get our identity fingerprint (SHA-256 hash of static public key)
     */
    fun getIdentityFingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(staticIdentityPublicKey)
        return hash.joinToString("") { "%02x".format(it) }
    }
    
    /**
     * Get peer's public key data (if we have a session)
     */
    fun getPeerPublicKeyData(peerID: String): ByteArray? {
        return sessionManager.getRemoteStaticKey(peerID)
    }

    fun getAuthenticatedSession(peerID: String): AuthenticatedNoiseSession? =
        sessionManager.getAuthenticatedSession(peerID)

    fun withAuthenticatedSession(
        peerID: String,
        expectedSession: AuthenticatedNoiseSession,
        action: () -> Boolean
    ): Boolean = sessionManager.withAuthenticatedSession(peerID, expectedSession, action)
    
    /**
     * Clear persistent identity (for panic mode)
     */
    fun clearPersistentIdentity() {
        // 1. Clear storage
        identityStateManager.clearIdentityData()
        
        // 2. Clear all sessions immediately
        if (::sessionManager.isInitialized) {
            sessionManager.shutdown()
        }
        
        // 3. Regenerate keys immediately (in-memory rotation)
        loadOrGenerateKeys()
        
        // 4. Re-initialize SessionManager with new keys
        initializeSessionManager()
        
    }
    
    // MARK: - Handshake Management
    
    /**
     * Initiate a Noise handshake with a peer
     * Returns the first handshake message to send
     */
    fun initiateHandshake(peerID: String, replaceEstablished: Boolean = false): ByteArray? {
        return try {
            sessionManager.initiateHandshake(peerID, replaceEstablished)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate handshake with $peerID: ${e.message}")
            null
        }
    }
    
    /**
     * Process an incoming handshake message
     * Returns response message if needed, null if handshake complete or failed
     */
    fun processHandshakeMessage(data: ByteArray, peerID: String): ByteArray? {
        return try {
            processHandshakeMessageWithResult(data, peerID).response
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process handshake from $peerID: ${e.message}")
            null
        }
    }

    /**
     * Typed handshake result for security-sensitive callers that must distinguish a null response
     * from a newly authenticated session or a rejected handshake. Identity mismatch exceptions are
     * intentionally propagated to the caller.
     */
    @Throws(Exception::class)
    fun processHandshakeMessageWithResult(
        data: ByteArray,
        peerID: String
    ): NoiseHandshakeProcessingResult {
        return sessionManager.processHandshakeMessageWithResult(peerID, data)
    }
    
    /**
     * Check if we have an established session with a peer
     */
    fun hasEstablishedSession(peerID: String): Boolean {
        return sessionManager.hasEstablishedSession(peerID)
    }
    
    /**
     * Get session state for a peer (for UI state display)
     */
    fun getSessionState(peerID: String): NoiseSession.NoiseSessionState {
        return sessionManager.getSessionState(peerID)
    }
    
    // MARK: - Encryption/Decryption
    
    /**
     * Encrypt data for a specific peer using established Noise session
     */
    fun encrypt(data: ByteArray, peerID: String): ByteArray? {
        if (!hasEstablishedSession(peerID)) {
            Log.w(TAG, "No established session with $peerID, handshake required. TODO: IMPLEMENT HANDSHAKE INIT")
            onHandshakeRequired?.invoke(peerID)
            return null
        }
        
        return try {
            sessionManager.encrypt(data, peerID)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt for $peerID: ${e.message}")
            null
        }
    }

    @Throws(Exception::class)
    fun encryptForSession(
        data: ByteArray,
        peerID: String,
        expectedSession: AuthenticatedNoiseSession
    ): ByteArray = sessionManager.encryptForSession(data, peerID, expectedSession)
    
    /**
     * Decrypt data from a specific peer using established Noise session
     */
    fun decrypt(encryptedData: ByteArray, peerID: String): ByteArray? {
        if (!hasEstablishedSession(peerID)) {
            Log.w(TAG, "No established session with $peerID")
            return null
        }
        
        return try {
            sessionManager.decrypt(encryptedData, peerID)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt from $peerID: ${e.message}")
            null
        }
    }

    fun decryptWithSession(encryptedData: ByteArray, peerID: String): NoiseDecryptionResult? =
        try {
            sessionManager.decryptWithSession(encryptedData, peerID)
        } catch (e: Exception) {
            Log.e(TAG, "Failed generation-bound decryption from $peerID: ${e.message}")
            null
        }
    
    // MARK: - Peer Management
    
    /**
     * Get fingerprint for a peer (returns null if peer unknown)
     */
    fun getPeerFingerprint(peerID: String): String? {
        return fingerprintManager.getFingerprintForPeer(peerID)
    }
    
    /**
     * Get current peer ID for a fingerprint (returns null if not currently online)
     */
    fun getPeerID(fingerprint: String): String? {
        return fingerprintManager.getPeerIDForFingerprint(fingerprint)
    }
    
    /**
     * Remove a peer session (called when peer disconnects)
     */
    fun removePeer(peerID: String) {
        sessionManager.removeSession(peerID)
        
        // Clean up fingerprint mappings via centralized manager
        fingerprintManager.removePeer(peerID)
    }
    
    /**
     * Update peer ID mapping (for peer ID rotation)
     * This allows favorites/blocking to persist across peer ID changes
     */
    fun updatePeerIDMapping(oldPeerID: String?, newPeerID: String, fingerprint: String) {
        // Use centralized fingerprint manager for peer ID rotation
        fingerprintManager.updatePeerIDMapping(oldPeerID, newPeerID, fingerprint)
    }
    
    // MARK: - Session Maintenance
    
    /**
     * Get sessions that need rekey based on time or message count
     */
    fun getSessionsNeedingRekey(): List<String> {
        return sessionManager.getSessionsNeedingRekey()
    }
    
    /**
     * Initiate rekey for a session (replaces old session with new handshake)
     */
    fun initiateRekey(peerID: String): ByteArray? {
        Log.d(TAG, "Rekeying session with $peerID")
        
        // Remove old session
        sessionManager.removeSession(peerID)
        
        // Start new handshake
        return initiateHandshake(peerID)
    }
    
    // MARK: - Private Helpers
    
    /**
     * Generate a new Curve25519 key pair using the real Noise library
     * Returns (privateKey, publicKey) as 32-byte arrays
     */
    private fun generateKeyPair(): Pair<ByteArray, ByteArray> {
        try {
            val dhState = com.bitchat.android.noise.southernstorm.protocol.Noise.createDH("25519")
            dhState.generateKeyPair()
            
            val privateKey = ByteArray(32)
            val publicKey = ByteArray(32)
            
            dhState.getPrivateKey(privateKey, 0)
            dhState.getPublicKey(publicKey, 0)
            
            dhState.destroy()
            
            return Pair(privateKey, publicKey)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate key pair: ${e.message}")
            throw e
        }
    }
    
    /**
     * Handle session establishment (called when Noise handshake completes)
     */
    private fun handleSessionEstablished(peerID: String, remoteStaticKey: ByteArray) {
        // Store fingerprint mapping via centralized manager
        // This is the ONLY place where fingerprints are stored - after successful Noise handshake
        fingerprintManager.storeFingerprintForPeer(peerID, remoteStaticKey)

        // Preserve the canonical peerID -> npub index, but only after Noise proves possession of
        // the static key. Announcement-time indexing was unsafe because a peer can copy another
        // party's public Noise key without possessing its private key.
        try {
            com.bitchat.android.favorites.FavoritesPersistenceService.shared
                .findNostrPubkey(remoteStaticKey)
                ?.let { npub ->
                    com.bitchat.android.favorites.FavoritesPersistenceService.shared
                        .updateNostrPublicKeyForPeerID(peerID, npub)
                }
        } catch (_: Exception) {
            // Favorites may not be initialized in isolated/background crypto tests.
        }
        
        // Calculate fingerprint for logging and callback
        val fingerprint = calculateFingerprint(remoteStaticKey)
        
        
        // Notify about authentication
        onPeerAuthenticated?.invoke(peerID, fingerprint)
    }
    
    /**
     * Calculate fingerprint from public key (SHA-256 hash)
     */
    private fun calculateFingerprint(publicKey: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(publicKey)
        return hash.joinToString("") { "%02x".format(it) }
    }
    
    /**
     * Clean shutdown
     */
    fun shutdown() {
        if (::sessionManager.isInitialized) {
            sessionManager.shutdown()
        }
        // No need to clear fingerprints here - they are managed centrally
    }
}

/**
 * Noise-specific errors
 */
sealed class NoiseEncryptionError(message: String) : Exception(message) {
    object HandshakeRequired : NoiseEncryptionError("Handshake required before encryption")
    object SessionNotEstablished : NoiseEncryptionError("No established Noise session")
    object InvalidMessage : NoiseEncryptionError("Invalid message format")
    class HandshakeFailed(cause: Throwable) : NoiseEncryptionError("Handshake failed: ${cause.message}")
}
