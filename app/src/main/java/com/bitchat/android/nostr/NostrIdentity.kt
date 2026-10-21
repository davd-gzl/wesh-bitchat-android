package com.bitchat.android.nostr

import android.content.Context
import android.util.Log
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.identity.SecureIdentityStateManager
import java.security.MessageDigest

/**
 * Manages Nostr identity (secp256k1 keypair) for NIP-17 private messaging
 * Compatible with iOS implementation
 */
data class NostrIdentity(
    val privateKeyHex: String,
    val publicKeyHex: String,
    val npub: String,
    val createdAt: Long
) {
    
    companion object {
        private const val TAG = "NostrIdentity"
        
        /**
         * Generate a new Nostr identity
         */
        fun generate(): NostrIdentity {
            val (privateKeyHex, publicKeyHex) = NostrCrypto.generateKeyPair()
            val npub = Bech32.encode("npub", publicKeyHex.hexToByteArrayLocal())
            
            Log.d(TAG, "Generated new Nostr identity: npub=$npub")
            
            return NostrIdentity(
                privateKeyHex = privateKeyHex,
                publicKeyHex = publicKeyHex,
                npub = npub,
                createdAt = System.currentTimeMillis()
            )
        }
        
        /**
         * Create from existing private key
         */
        fun fromPrivateKey(privateKeyHex: String): NostrIdentity {
            require(NostrCrypto.isValidPrivateKey(privateKeyHex)) { 
                "Invalid private key" 
            }
            
            val publicKeyHex = NostrCrypto.derivePublicKey(privateKeyHex)
            val npub = Bech32.encode("npub", publicKeyHex.hexToByteArrayLocal())
            
            return NostrIdentity(
                privateKeyHex = privateKeyHex,
                publicKeyHex = publicKeyHex,
                npub = npub,
                createdAt = System.currentTimeMillis()
            )
        }
        
        /**
         * Create from a deterministic seed (for demo purposes)
         */
        fun fromSeed(seed: String): NostrIdentity {
            // Hash the seed to create a private key
            val digest = MessageDigest.getInstance("SHA-256")
            val seedBytes = seed.toByteArray(Charsets.UTF_8)
            val privateKeyBytes = digest.digest(seedBytes)
            val privateKeyHex = privateKeyBytes.joinToString("") { "%02x".format(it) }
            
            return fromPrivateKey(privateKeyHex)
        }
    }
    
    /**
     * Sign a Nostr event
     */
    fun signEvent(event: NostrEvent): NostrEvent {
        return event.sign(privateKeyHex)
    }
    
    /**
     * Get short display format
     */
    fun getShortNpub(): String {
        return if (npub.length > 16) {
            "${npub.take(8)}...${npub.takeLast(8)}"
        } else {
            npub
        }
    }
}

/**
 * Bridge between Noise and Nostr identities
 * Manages persistent storage of the account identity
 */
object NostrIdentityBridge {
    private const val TAG = "NostrIdentityBridge"
    private const val NOSTR_PRIVATE_KEY = "nostr_private_key"
    // Seed of the removed per-location identities; still erased in case an older build stored it.
    private const val DEVICE_SEED_KEY = "nostr_device_seed"
    
    /**
     * Get or create the current Nostr identity
     */
    fun getCurrentNostrIdentity(context: Context): NostrIdentity? =
        getCurrentNostrIdentity(SecureIdentityStateManager(context))

    internal fun getCurrentNostrIdentity(stateManager: SecureIdentityStateManager): NostrIdentity? {
        // Try to load existing Nostr private key
        val existingKey = loadNostrPrivateKey(stateManager)
        if (existingKey != null) {
            return try {
                NostrIdentity.fromPrivateKey(existingKey)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create identity from stored key: ${e.message}")
                null
            }
        }
        
        // Generate new identity
        val newIdentity = NostrIdentity.generate()
        saveNostrPrivateKey(stateManager, newIdentity.privateKeyHex)
        
        Log.i(TAG, "Created new Nostr identity: ${newIdentity.getShortNpub()}")
        return newIdentity
    }
    
    /** Read-only: the stored identity, or null. Never generates or persists key material. */
    fun peekCurrentNostrIdentity(context: Context): NostrIdentity? =
        runCatching { peekCurrentNostrIdentity(SecureIdentityStateManager(context)) }.getOrNull()

    internal fun peekCurrentNostrIdentity(stateManager: SecureIdentityStateManager): NostrIdentity? = runCatching {
        loadNostrPrivateKey(stateManager)?.let(NostrIdentity::fromPrivateKey)
    }.getOrNull()

    /**
     * Every npub this device can sign as, read-only (never creates a key): the current identity.
     * Null when the key store cannot be read: callers fail closed.
     */
    fun ownedNpubs(context: Context): Set<String>? =
        runCatching { ownedNpubs(SecureIdentityStateManager(context)) }.getOrNull()

    internal fun ownedNpubs(stateManager: SecureIdentityStateManager): Set<String>? = runCatching {
        buildSet {
            stateManager.getSecureValue(NOSTR_PRIVATE_KEY)?.let { add(NostrIdentity.fromPrivateKey(it).npub) }
        }
    }.getOrNull()
    
    /**
     * Associate a Nostr identity with a Noise public key (for favorites)
     */
    fun associateNostrIdentity(nostrPubkey: String, noisePublicKey: ByteArray, context: Context) {
        FavoritesPersistenceService.initialize(context)
        FavoritesPersistenceService.shared.updateNostrPublicKey(noisePublicKey, nostrPubkey)
        Log.d(TAG, "Associated Nostr pubkey ${nostrPubkey.take(16)}... with Noise key")
    }
    
    /**
     * Get Nostr public key associated with a Noise public key
     */
    fun getNostrPublicKey(noisePublicKey: ByteArray, context: Context): String? {
        FavoritesPersistenceService.initialize(context)
        return FavoritesPersistenceService.shared.findNostrPubkey(noisePublicKey)
    }
    
    /**
     * Clear all Nostr identity data
     */
    fun clearAllAssociations(context: Context) {
        val stateManager = SecureIdentityStateManager(context)
        
        // Clear Nostr private key using public methods instead of reflection
        try {
            stateManager.clearSecureValues(NOSTR_PRIVATE_KEY, DEVICE_SEED_KEY)
                
            Log.i(TAG, "Cleared all Nostr identity data and cache")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear Nostr data: ${e.message}")
        }
    }

    /** Panic path: the Nostr key and any legacy device seed are gone from disk when this returns true. */
    fun panicClearKeys(context: Context): Boolean {
        val stateManager = SecureIdentityStateManager(context)
        return stateManager.clearSecureValuesSynchronously(NOSTR_PRIVATE_KEY, DEVICE_SEED_KEY) &&
            !stateManager.hasSecureValue(NOSTR_PRIVATE_KEY) && !stateManager.hasSecureValue(DEVICE_SEED_KEY)
    }

    // MARK: - Private Methods
    
    private fun loadNostrPrivateKey(stateManager: SecureIdentityStateManager): String? {
        return try {
            // Use public methods instead of reflection to access the encrypted preferences
            stateManager.getSecureValue(NOSTR_PRIVATE_KEY)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Nostr private key: ${e.message}")
            null
        }
    }
    
    private fun saveNostrPrivateKey(stateManager: SecureIdentityStateManager, privateKeyHex: String) {
        try {
            // Use public methods instead of reflection to access the encrypted preferences
            stateManager.storeSecureValue(NOSTR_PRIVATE_KEY, privateKeyHex)
                
            Log.d(TAG, "Saved Nostr private key to secure storage")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save Nostr private key: ${e.message}")
            throw e
        }
    }
}

// Extension functions for data conversion
private fun String.hexToByteArrayLocal(): ByteArray {
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
