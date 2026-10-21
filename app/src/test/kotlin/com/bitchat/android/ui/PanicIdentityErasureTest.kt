package com.bitchat.android.ui

import android.app.Application
import android.content.SharedPreferences
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.startup.WeshRecoveryGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual owned stores use only these memory preferences, never a Context/provider or Keystore. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35])
class PanicIdentityErasureTest {
    @Test fun `identity commit false cannot confirm panic erasure`() {
        val storage = Storage(identitySeed())
        storage.commitSucceeds = false
        val confirmed = storage.manager().clearIdentityDataForPanic()
        assertEquals(1, storage.commits)
        assertTrue(storage.disk.isNotEmpty())
        assertFalse(confirmed)
    }

    @Test fun `a sticky identity value cannot confirm panic erasure`() {
        val storage = Storage(identitySeed())
        storage.stickyKeys += "static_private_key"
        val confirmed = storage.manager().clearIdentityDataForPanic()
        assertTrue(storage.disk.containsKey("static_private_key"))
        assertFalse(confirmed)
    }

    @Test fun `unknown identity absence cannot confirm panic erasure`() {
        val storage = Storage(identitySeed())
        storage.probeFailure = IllegalStateException("Synthetic absence unavailable")
        assertFalse(storage.manager().clearIdentityDataForPanic())
    }

    @Test fun `identity cleanup cancellation propagates instead of confirming erase`() {
        val storage = Storage(identitySeed())
        storage.writeFailure = CancellationException("Synthetic cancellation")
        var cancelled = false
        try { storage.manager().clearIdentityDataForPanic() } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
    }

    @Test fun `confirmed identity cleanup erases the owned preference payload synchronously`() {
        val storage = Storage(identitySeed())
        assertTrue(storage.manager().clearIdentityDataForPanic())
        assertTrue(storage.memory.isEmpty())
        assertTrue(storage.disk.isEmpty())
        assertEquals(1, storage.commits)
        assertEquals(0, storage.applies)
    }

    @Test fun `favorite key commit false cannot confirm erasure`() {
        val storage = Storage(favoriteSeed())
        storage.commitSucceeds = false
        val confirmed = storage.manager().clearSecureValuesForPanic(FAVORITES, INDEX)
        assertTrue(storage.disk.containsKey(FAVORITES))
        assertFalse(confirmed)
    }

    @Test fun `a sticky favorite key cannot confirm erasure`() {
        val storage = Storage(favoriteSeed())
        storage.stickyKeys += INDEX
        val confirmed = storage.manager().clearSecureValuesForPanic(FAVORITES, INDEX)
        assertTrue(storage.memory.containsKey(INDEX))
        assertFalse(confirmed)
    }

    @Test fun `unknown favorite key absence cannot confirm erasure`() {
        val storage = Storage(favoriteSeed())
        storage.probeFailure = IllegalStateException("Synthetic absence unavailable")
        assertFalse(storage.manager().clearSecureValuesForPanic(FAVORITES, INDEX))
    }

    @Test fun `favorite key cancellation propagates from the actual storage port`() {
        val storage = Storage(favoriteSeed())
        storage.writeFailure = CancellationException("Synthetic cancellation")
        var cancelled = false
        try { storage.manager().clearSecureValuesForPanic(FAVORITES, INDEX) } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
    }

    @Test fun `favorite key success requires durable absence and preserves neighboring identity`() {
        val storage = Storage(favoriteSeed())
        assertTrue(storage.manager().clearSecureValuesForPanic(FAVORITES, INDEX))
        assertFalse(storage.memory.containsKey(FAVORITES))
        assertFalse(storage.memory.containsKey(INDEX))
        assertFalse(storage.disk.containsKey(FAVORITES))
        assertFalse(storage.disk.containsKey(INDEX))
        assertEquals(SENTINEL_VALUE, storage.disk[SENTINEL])
        assertEquals(0, storage.applies)
    }

    @Test fun `favorites cache cleanup cannot confirm a failed persistent commit`() {
        val storage = Storage(favoriteSeed())
        val favorites = storage.favorites()
        assertEquals(1, favorites.getAllRelationships().size)
        storage.commitSucceeds = false
        val confirmed = favorites.clearAllFavoritesForPanic()
        assertTrue(favorites.getAllRelationships().isEmpty())
        assertTrue(storage.disk.containsKey(FAVORITES))
        assertFalse(confirmed)
    }

    @Test fun `favorites cache cleanup cannot confirm sticky owned storage`() {
        val storage = Storage(favoriteSeed())
        val favorites = storage.favorites()
        storage.stickyKeys += setOf(FAVORITES, INDEX)
        val confirmed = favorites.clearAllFavoritesForPanic()
        assertTrue(storage.memory.containsKey(FAVORITES))
        assertTrue(storage.memory.containsKey(INDEX))
        assertFalse(confirmed)
    }

    @Test fun `favorites cache cleanup cannot confirm unknown owned key absence`() {
        val storage = Storage(favoriteSeed())
        val favorites = storage.favorites()
        storage.probeFailure = IllegalStateException("Synthetic absence unavailable")
        assertFalse(favorites.clearAllFavoritesForPanic())
    }

    @Test fun `favorites panic cancellation propagates from the actual persistent owner`() {
        val storage = Storage(favoriteSeed())
        val favorites = storage.favorites()
        storage.writeFailure = CancellationException("Synthetic cancellation")
        var cancelled = false
        try { favorites.clearAllFavoritesForPanic() } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
    }

    @Test fun `checked favorites success clears both caches and exact keys without replacing neighbors`() {
        val storage = Storage(favoriteSeed())
        val favorites = storage.favorites()
        assertEquals(SYNTHETIC_NPUB, favorites.findNostrPubkeyForPeerID(PEER))

        assertTrue(favorites.clearAllFavoritesForPanic())

        assertTrue(favorites.getAllRelationships().isEmpty())
        assertNull(favorites.findNostrPubkeyForPeerID(PEER))
        assertFalse(storage.disk.containsKey(FAVORITES))
        assertFalse(storage.disk.containsKey(INDEX))
        assertEquals(mapOf(SENTINEL to SENTINEL_VALUE), storage.memory)
        assertEquals(mapOf(SENTINEL to SENTINEL_VALUE), storage.disk)
        assertEquals(0, storage.applies)
    }

    @Test fun `ordinary favorites construction and clear retain their existing cache and apply behavior`() {
        val storage = Storage(favoriteSeed())
        val favorites = storage.favorites()
        assertEquals(1, favorites.getAllRelationships().size)
        assertEquals(SYNTHETIC_NPUB, favorites.findNostrPubkeyForPeerID(PEER))

        favorites.clearAllFavorites()

        assertTrue(favorites.getAllRelationships().isEmpty())
        assertNull(favorites.findNostrPubkeyForPeerID(PEER))
        assertEquals("{}", storage.memory[FAVORITES])
        assertEquals("{}", storage.memory[INDEX])
        assertEquals(2, storage.applies)
        assertEquals(0, storage.commits)
        assertEquals(SENTINEL_VALUE, storage.memory[SENTINEL])
    }

    @Test fun `any uncertain cryptographic outcome prevents the actual all store resume continuation`() = runTest {
        for (failed in listOf("mesh", "identity", "keys", "favorites")) {
            val attempts = mutableListOf<String>()
            fun result(name: String): Boolean { attempts += name; return name != failed }
            val erased = clearPanicCryptography(
                clearMesh = { attempts += "mesh"; if (failed == "mesh") error("Synthetic mesh failure") },
                clearIdentity = { result("identity") },
                clearFavoriteKeys = { result("keys") },
                clearFavorites = { result("favorites") }
            )
            var pending = true
            var resumes = 0
            val gate = WeshRecoveryGate().apply { requireRecovery() }
            val resumed = resumeAfterWeshPanicWipe(
                draftsCleared = true, conversationsCleared = true, mediaCleared = true, seenCleared = true,
                identityErasureConfirmed = erased.confirmed, clearPreferences = { true },
                resume = { resumes++; pending = false; gate.normalPanicCompleted(); true },
                meshOwnerRetired = true
            )
            assertFalse("Uncertain $failed cleanup must not enter the real continuation", resumed)
            assertEquals(0, resumes)
            assertTrue(pending)
            assertTrue(gate.recoveryRequired.value)
            assertEquals(listOf("mesh", "identity", "keys", "favorites"), attempts)
        }
    }

    @Test fun `actual failed identity persistence cannot restart through the production conjunction`() = runTest {
        val storage = Storage(identitySeed())
        storage.commitSucceeds = false
        val manager = storage.manager()
        val erased = clearPanicCryptography(
            clearMesh = {}, clearIdentity = manager::clearIdentityDataForPanic,
            clearFavoriteKeys = { manager.clearSecureValuesForPanic(FAVORITES, INDEX) }, clearFavorites = { true }
        )
        var restarted = false
        val resumed = resumeAfterWeshPanicWipe(
            draftsCleared = true, conversationsCleared = true, mediaCleared = true, seenCleared = true,
            identityErasureConfirmed = erased.confirmed, clearPreferences = { true },
            resume = { restarted = true; true },
            meshOwnerRetired = true
        )
        assertTrue(storage.disk.containsKey("static_private_key"))
        assertFalse(resumed)
        assertFalse(restarted)
    }

    @Test fun `cleanup exceptions do not skip independent owned erasure attempts`() {
        val attempts = mutableListOf<String>()
        val result = clearPanicCryptography(
            clearMesh = { attempts += "mesh"; error("Synthetic mesh failure") },
            clearIdentity = { attempts += "identity"; true },
            clearFavoriteKeys = { attempts += "keys"; true },
            clearFavorites = { attempts += "favorites"; true }
        )
        assertFalse(result.confirmed)
        assertEquals(listOf("mesh", "identity", "keys", "favorites"), attempts)
    }

    @Test fun `cleanup cancellation propagates before any successful resume decision`() {
        var cancelled = false
        try {
            clearPanicCryptography(clearMesh = {}, clearIdentity = { throw CancellationException("Synthetic cancellation") },
                clearFavoriteKeys = { true }, clearFavorites = { true })
        } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
    }

    @Test fun `an ordinary false result still attempts the remaining owned cleanup operations`() {
        val attempts = mutableListOf<String>()
        val result = clearPanicCryptography(clearMesh = { attempts += "mesh" },
            clearIdentity = { attempts += "identity"; false }, clearFavoriteKeys = { attempts += "keys"; true },
            clearFavorites = { attempts += "favorites"; true })
        assertFalse(result.confirmed)
        assertEquals(listOf("mesh", "identity", "keys", "favorites"), attempts)
    }

    @Test fun `fully confirmed operations preserve successful all store resume`() = runTest {
        val result = clearPanicCryptography(clearMesh = {}, clearIdentity = { true },
            clearFavoriteKeys = { true }, clearFavorites = { true })
        var resumed = 0
        assertTrue(result.confirmed)
        assertTrue(resumeAfterWeshPanicWipe(
            draftsCleared = true, conversationsCleared = true, mediaCleared = true, seenCleared = true,
            identityErasureConfirmed = result.confirmed, clearPreferences = { true }, resume = { resumed++; true },
            meshOwnerRetired = true
        ))
        assertEquals(1, resumed)
    }

    /** A failed commit may update memory while leaving the synthetic durable document intact. */
    private class Storage(initial: Map<String, Any>) {
        val memory = initial.toMutableMap()
        val disk = initial.toMutableMap()
        val prefs: SharedPreferences = mock()
        var commitSucceeds = true
        var stickyKeys = mutableSetOf<String>()
        var probeFailure: Exception? = null
        var writeFailure: Exception? = null
        var commits = 0
        var applies = 0

        init {
            whenever(prefs.all).thenAnswer { probeFailure?.let { throw it }; memory.toMap() }
            whenever(prefs.contains(any())).thenAnswer { probeFailure?.let { throw it }; memory.containsKey(it.getArgument<String>(0)) }
            whenever(prefs.getString(any(), anyOrNull())).thenAnswer { memory[it.getArgument<String>(0)] as String? ?: it.getArgument<String?>(1) }
            whenever(prefs.edit()).thenAnswer { editor() }
        }
        fun manager() = SecureIdentityStateManager(prefs, testOnly = true)
        fun favorites() = FavoritesPersistenceService(manager(), testOnly = true)

        private fun editor(): SharedPreferences.Editor {
            val editor: SharedPreferences.Editor = mock()
            val puts = linkedMapOf<String, String>()
            val removed = linkedSetOf<String>()
            var clear = false
            whenever(editor.clear()).thenAnswer { clear = true; editor }
            whenever(editor.remove(any())).thenAnswer { removed += it.getArgument<String>(0); editor }
            whenever(editor.putString(any(), anyOrNull())).thenAnswer {
                val key = it.getArgument<String>(0)
                val value = it.getArgument<String?>(1)
                if (value == null) removed += key else puts[key] = value
                editor
            }
            fun publishMemory() {
                if (clear) memory.keys.retainAll(stickyKeys)
                removed.filterNot(stickyKeys::contains).forEach(memory::remove)
                puts.filterKeys { it !in stickyKeys }.forEach { (key, value) -> memory[key] = value }
                writeFailure?.let { throw it }
            }
            whenever(editor.commit()).thenAnswer {
                commits++
                publishMemory()
                if (commitSucceeds) { disk.clear(); disk.putAll(memory) }
                commitSucceeds
            }
            doAnswer { applies++; publishMemory(); null }.whenever(editor).apply()
            return editor
        }
    }

    companion object {
        private const val FAVORITES = "favorite_relationships"
        private const val INDEX = "favorite_peerid_index"
        private const val SENTINEL = "synthetic_neighbor_identity"
        private const val SENTINEL_VALUE = "Synthetic retained identity"
        private const val PEER = "0000000000000001"
        private const val SYNTHETIC_NPUB = "synthetic-npub"
        private val noiseHex = "11".repeat(32)
        private fun identitySeed(): Map<String, Any> = mapOf(
            "static_private_key" to "Synthetic old private bytes", "static_public_key" to "Synthetic old public bytes"
        )
        private fun favoriteSeed(): Map<String, Any> = mapOf(
            FAVORITES to """{"$noiseHex":{"peerNoisePublicKeyHex":"$noiseHex","peerNostrPublicKey":"$SYNTHETIC_NPUB","peerNickname":"Synthetic contact","isFavorite":true,"theyFavoritedUs":false,"favoritedAt":100,"lastUpdated":100}}""",
            INDEX to """{"$PEER":"$SYNTHETIC_NPUB"}""", SENTINEL to SENTINEL_VALUE
        )
    }
}
