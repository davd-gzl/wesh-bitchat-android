package com.bitchat.android.ui

import android.content.Context
import android.content.SharedPreferences
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.services.ConversationListPreferences
import com.bitchat.android.services.ConversationStorageCipher
import com.bitchat.android.startup.WeshRecoveryGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class PanicMetadataTest {
    @get:Rule val directory = TemporaryFolder()

    /** A failed disk write can still publish its edits to SharedPreferences' RAM cache. */
    private class Storage(initial: Map<String, Any>) {
        val memory = initial.toMutableMap()
        val disk = initial.toMutableMap()
        val prefs: SharedPreferences = mock()
        var commitSucceeds = true
        var writeFailure: Exception? = null
        var retainClearedValues = false
        var retainRemovedValues = false
        var nicknameReadback: String? = null
        var writes = 0
        var commits = 0

        init {
            whenever(prefs.all).thenAnswer { memory.toMap() }
            whenever(prefs.contains(any())).thenAnswer { memory.containsKey(it.getArgument<String>(0)) }
            whenever(prefs.getString(any(), anyOrNull())).thenAnswer {
                val key = it.getArgument<String>(0)
                if (key == "nickname" && nicknameReadback != null) nicknameReadback
                else memory[key] as String? ?: it.getArgument<String?>(1)
            }
            whenever(prefs.edit()).thenAnswer { editor() }
        }

        private fun editor(): SharedPreferences.Editor {
            val editor: SharedPreferences.Editor = mock()
            val puts = mutableMapOf<String, Any>()
            val removes = mutableSetOf<String>()
            var clear = false
            whenever(editor.clear()).thenAnswer { clear = true; editor }
            whenever(editor.remove(any())).thenAnswer { removes += it.getArgument<String>(0); editor }
            whenever(editor.putString(any(), anyOrNull())).thenAnswer {
                val key = it.getArgument<String>(0)
                val value = it.getArgument<String?>(1)
                if (value == null) removes += key else puts[key] = value
                editor
            }
            fun publish() {
                writes++
                if (clear && !retainClearedValues) memory.clear()
                if (!retainRemovedValues) removes.forEach(memory::remove)
                memory.putAll(puts)
                writeFailure?.let { throw it }
                if (commitSucceeds) {
                    disk.clear()
                    disk.putAll(memory)
                }
            }
            whenever(editor.commit()).thenAnswer { commits++; publish(); commitSucceeds }
            doAnswer { publish(); null }.whenever(editor).apply()
            return editor
        }

        fun restart() { memory.clear(); memory.putAll(disk) }
    }

    private class Stores {
        val plain = Storage(mapOf("nickname" to "synthetic old nickname", "legacy_metadata" to "synthetic value"))
        val secure = Storage(mapOf(
            "conversation_pinned_v1" to "[\"synthetic_contact\"]",
            "conversation_muted_v1" to "[\"synthetic_contact\"]",
            "conversation_drafts_v1" to "{\"synthetic_contact\":\"synthetic draft\"}",
            "unrelated_identity_value" to "synthetic retained value"
        ))
        private val context: Context = mock<Context>().also {
            whenever(it.getSharedPreferences(eq("bitchat_prefs"), eq(Context.MODE_PRIVATE))).thenReturn(plain.prefs)
        }
        val data = DataManager(context)
        val conversations = ConversationListPreferences(
            SecureIdentityStateManager(secure.prefs, testOnly = true), testOnly = true,
            canonicalize = { it }
        )
    }

    private suspend fun attemptClear(stores: Stores): Boolean = try {
        clearPanicMetadata(stores.data, stores.conversations)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // The outer panic handler already retains recovery on an escaping storage exception.
        false
    }

    private suspend fun attemptNickname(stores: Stores): Boolean = try {
        savePanicNickname(stores.data, "anon1234")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private fun markerBackend() = EncryptedWeshDraftBackend(directory.root, object : ConversationStorageCipher {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray = error("No draft data")
        override fun decrypt(envelope: ByteArray, associatedData: ByteArray): ByteArray = error("No draft data")
        override fun destroyKey() = Unit
    })

    private suspend fun assertRecoveryRetained(metadataConfirmed: Boolean) {
        val backend = markerBackend()
        backend.wipe()
        val gate = WeshRecoveryGate().apply { requireRecovery() }
        var preferenceClears = 0
        var resumes = 0
        val resumed = resumeAfterWeshPanicWipe(
            seenCleared = true,
            draftsCleared = true, conversationsCleared = true, mediaCleared = true,
            metadataCleared = metadataConfirmed,
            clearPreferences = { preferenceClears++; true },
            resume = {
                resumes++
                backend.finishVerifiedWipe()
                gate.normalPanicCompleted()
                true
            },
            identityErasureConfirmed = true,
            meshOwnerRetired = true
        )
        assertEquals("Independent preference cleanup must still run", 1, preferenceClears)
        assertFalse("Unconfirmed metadata must retain global recovery", resumed)
        assertEquals(0, resumes)
        assertTrue(File(directory.root, "wesh-drafts-wipe-v1").isFile)
        assertTrue(gate.recoveryRequired.value)
        assertFalse(gate.allowsTransport(prepared = true))
    }

    @Test fun `verified metadata and nickname allow durable marker completion`() = runTest {
        val stores = Stores()
        assertTrue(attemptClear(stores))
        assertTrue(attemptNickname(stores))
        assertEquals(mapOf("nickname" to "anon1234"), stores.plain.disk)
        assertEquals(mapOf("unrelated_identity_value" to "synthetic retained value"), stores.secure.disk)
        stores.plain.restart()
        stores.secure.restart()
        assertEquals("anon1234", stores.data.loadNickname())
        assertTrue(stores.conversations.pinned.value.isEmpty())
        assertTrue(stores.conversations.muted.value.isEmpty())
        assertTrue(stores.conversations.drafts.value.isEmpty())
        val backend = markerBackend()
        backend.wipe()
        val gate = WeshRecoveryGate().apply { requireRecovery() }
        assertTrue(resumeAfterWeshPanicWipe(true, true, true, seenCleared = true,
            metadataCleared = true, clearPreferences = { true }, resume = {
                backend.finishVerifiedWipe(); gate.normalPanicCompleted(); true
            }, identityErasureConfirmed = true, meshOwnerRetired = true))
        assertFalse(File(directory.root, "wesh-drafts-wipe-v1").exists())
        assertTrue(gate.allowsTransport(prepared = true))
    }

    @Test fun `plain clear with failed disk write cannot finish recovery despite empty RAM`() = runTest {
        val stores = Stores().apply { plain.commitSucceeds = false }
        val confirmed = attemptClear(stores)
        assertTrue(stores.plain.memory.isEmpty())
        assertEquals("synthetic old nickname", stores.plain.disk["nickname"])
        assertEquals(1, stores.secure.writes)
        assertRecoveryRetained(confirmed)
    }

    @Test fun `plain clear exception still attempts independent encrypted organization cleanup`() = runTest {
        val stores = Stores().apply { plain.writeFailure = IllegalStateException("synthetic write failure") }
        val confirmed = attemptClear(stores)
        assertRecoveryRetained(confirmed)
        assertEquals("Independent organization cleanup must not be skipped", 1, stores.secure.writes)
        assertFalse(stores.secure.disk.containsKey("conversation_pinned_v1"))
    }

    @Test fun `successful plain clear acknowledgement with retained readback cannot finish recovery`() = runTest {
        val stores = Stores().apply { plain.retainClearedValues = true }
        val confirmed = attemptClear(stores)
        assertEquals("synthetic old nickname", stores.plain.memory["nickname"])
        assertEquals(1, stores.secure.writes)
        assertRecoveryRetained(confirmed)
    }

    @Test fun `pin mute and draft removal returning false cannot finish recovery after RAM change`() = runTest {
        val stores = Stores().apply { secure.commitSucceeds = false }
        val confirmed = attemptClear(stores)
        assertFalse(stores.secure.memory.containsKey("conversation_pinned_v1"))
        assertTrue(stores.secure.disk.containsKey("conversation_pinned_v1"))
        assertTrue(stores.secure.disk.containsKey("conversation_muted_v1"))
        assertTrue(stores.secure.disk.containsKey("conversation_drafts_v1"))
        assertTrue(stores.plain.disk.isEmpty())
        assertRecoveryRetained(confirmed)
    }

    @Test fun `encrypted organization exception retains global recovery while plain cleanup remains attempted`() = runTest {
        val stores = Stores().apply { secure.writeFailure = IllegalStateException("synthetic encrypted write failure") }
        val confirmed = attemptClear(stores)
        assertTrue(stores.plain.disk.isEmpty())
        assertEquals(1, stores.secure.writes)
        assertRecoveryRetained(confirmed)
    }

    @Test fun `successful organization commit with retained pin mute and draft keys cannot finish recovery`() = runTest {
        val stores = Stores().apply { secure.retainRemovedValues = true }
        val confirmed = attemptClear(stores)
        assertTrue(stores.secure.memory.containsKey("conversation_pinned_v1"))
        assertTrue(stores.secure.memory.containsKey("conversation_muted_v1"))
        assertTrue(stores.secure.memory.containsKey("conversation_drafts_v1"))
        assertRecoveryRetained(confirmed)
    }

    @Test fun `fresh nickname failed disk write cannot finish recovery after RAM replacement`() = runTest {
        val stores = Stores()
        assertTrue(attemptClear(stores))
        stores.plain.commitSucceeds = false
        val confirmed = attemptNickname(stores)
        assertEquals("anon1234", stores.plain.memory["nickname"])
        assertFalse(stores.plain.disk.containsKey("nickname"))
        assertRecoveryRetained(confirmed)
    }

    @Test fun `fresh nickname exception retains global recovery`() = runTest {
        val stores = Stores()
        assertTrue(attemptClear(stores))
        stores.plain.writeFailure = IllegalStateException("synthetic nickname failure")
        assertRecoveryRetained(attemptNickname(stores))
    }

    @Test fun `fresh nickname success with mismatching readback cannot finish recovery`() = runTest {
        val stores = Stores()
        assertTrue(attemptClear(stores))
        stores.plain.nicknameReadback = "synthetic stale nickname"
        assertRecoveryRetained(attemptNickname(stores))
    }

    @Test fun `retry after failed clear must obtain a new durable acknowledgement even when RAM is empty`() = runTest {
        val stores = Stores().apply { plain.commitSucceeds = false }
        assertFalse(attemptClear(stores))
        assertTrue(stores.plain.memory.isEmpty())
        assertFalse(stores.plain.disk.isEmpty())
        val commitsBeforeRetry = stores.plain.commits
        stores.plain.commitSucceeds = true
        assertTrue(attemptClear(stores))
        assertTrue(stores.plain.commits > commitsBeforeRetry)
        assertTrue(stores.plain.disk.isEmpty())
        assertTrue(attemptNickname(stores))
        assertEquals("anon1234", stores.plain.disk["nickname"])
    }

    @Test fun `metadata cancellation propagates without being treated as verified cleanup`() = runTest {
        val stores = Stores()
        val cancellation = CancellationException("synthetic cancellation")
        stores.plain.writeFailure = cancellation
        try {
            clearPanicMetadata(stores.data, stores.conversations)
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) {
            assertEquals(cancellation.message, actual.message)
            assertTrue("The original cancellation must survive coroutine stack recovery",
                generateSequence(actual as Throwable?) { it.cause }.take(8).any { it === cancellation })
        }
        assertEquals(0, stores.secure.writes)
    }
}
