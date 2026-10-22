package com.bitchat.android.ui

import com.bitchat.android.services.InMemoryConversationStorageCipher
import java.io.File
import java.nio.file.AccessDeniedException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class EncryptedWeshDraftBackendTest {
    @get:Rule val directory = TemporaryFolder()
    private val owner = "11".repeat(32)
    private val otherOwner = "22".repeat(32)
    private val cipher = InMemoryConversationStorageCipher()
    private fun backend() = EncryptedWeshDraftBackend(directory.root, cipher)
    private fun data() = File(directory.root, "wesh-drafts-v1.bin")
    private fun marker() = File(directory.root, "wesh-drafts-wipe-v1")
    private fun aad() = "WESH_DRAFTS_V1:$owner".toByteArray()

    @Test fun `fresh encrypted backend restores isolated public and full Noise drafts without plaintext`() {
        val drafts = mapOf("public" to "public synthetic text", "private:" + "33".repeat(32) to "private synthetic text")
        assertTrue(backend().load(owner).isEmpty())
        backend().write(owner, drafts)
        assertEquals(drafts, backend().load(owner))
        val raw = data().readBytes().toString(Charsets.UTF_8)
        assertFalse(raw.contains("synthetic text"))
        assertFalse(raw.contains(owner))
    }

    @Test fun `wrong owner tampering truncated and orphaned ciphertext never become empty defaults`() {
        backend().write(owner, mapOf("public" to "synthetic"))
        val encrypted = data().readBytes()
        assertTrue(runCatching { backend().load(otherOwner) }.isFailure)
        data().writeBytes(encrypted.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
        assertTrue(runCatching { backend().load(owner) }.isFailure)
        data().writeBytes(encrypted.copyOf(5))
        assertTrue(runCatching { backend().load(owner) }.isFailure)
        data().writeBytes(encrypted)
        cipher.destroyKey()
        assertTrue(runCatching { backend().load(owner) }.isFailure)
        assertArrayEquals(encrypted, data().readBytes())
    }

    @Test fun `pending marker and interrupted atomic marker block read and write without deletion`() {
        backend().write(owner, mapOf("public" to "synthetic"))
        val encrypted = data().readBytes()
        for (suffix in listOf("", ".bak", ".new")) {
            val pending = File(marker().path + suffix)
            pending.writeBytes(byteArrayOf(1))
            assertTrue(runCatching { backend().load(owner) }.exceptionOrNull() is DraftWipePending)
            assertTrue(runCatching { backend().write(owner, emptyMap()) }.exceptionOrNull() is DraftWipePending)
            assertArrayEquals(encrypted, data().readBytes())
            pending.delete()
        }
    }

    @Test fun `panic intent marker is durable and blocks load without deleting ciphertext`() {
        backend().write(owner, mapOf("public" to "synthetic"))
        val encrypted = data().readBytes()
        backend().markPanicPending()
        assertTrue(marker().exists())
        assertTrue(runCatching { backend().load(owner) }.exceptionOrNull() is DraftWipePending)
        assertArrayEquals(encrypted, data().readBytes())
    }

    @Test fun `access probe errors cannot masquerade as absent files or authorize encryption`() {
        var encrypts = 0
        val recording = object : com.bitchat.android.services.ConversationStorageCipher by cipher {
            override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
                encrypts++
                return cipher.encrypt(plaintext, associatedData)
            }
        }
        val denied = EncryptedWeshDraftBackend(directory.root, recording, probe = { throw AccessDeniedException("synthetic") })
        assertTrue(runCatching { denied.load(owner) }.exceptionOrNull() is AccessDeniedException)
        assertTrue(runCatching { denied.write(owner, mapOf("public" to "synthetic")) }.exceptionOrNull() is AccessDeniedException)
        assertEquals(0, encrypts)
    }

    @Test fun `noncanonical duplicate unknown or mistyped schema never becomes a valid empty draft`() {
        backend().write(owner, mapOf("public" to "synthetic"))
        val canonical = cipher.decrypt(data().readBytes(), aad()).toString(Charsets.UTF_8)
        val malformed = listOf(
            canonical.replace("\"version\":1", "\"version\":\"1\""),
            canonical.replace("\"version\":1", "\"version\":1,\"version\":1"),
            canonical.dropLast(1) + ",\"unexpected\":1}",
            canonical + " trailing",
            canonical.replace("\"public\"", "\"short-alias\"")
        )
        malformed.forEach { text ->
            assertNotEquals(canonical, text)
            data().writeBytes(cipher.encrypt(text.toByteArray(), aad()))
            assertTrue(runCatching { backend().load(owner) }.isFailure)
        }
    }

    @Test fun `valid divergent ciphertext and corrupted readback cannot be overwritten by later edits`() {
        val active = backend()
        active.load(owner)
        active.write(owner, mapOf("public" to "initial"))
        active.load(owner)
        val confirmed = data().readBytes()
        val document = cipher.decrypt(confirmed, aad()).toString(Charsets.UTF_8)
        val divergent = cipher.encrypt(document.replace("initial", "external").toByteArray(), aad())
        data().writeBytes(divergent)
        assertTrue(runCatching { active.write(owner, mapOf("public" to "new RAM")) }.isFailure)
        assertArrayEquals(divergent, data().readBytes())
        assertTrue(runCatching { active.load(owner) }.isFailure)
        data().writeBytes(confirmed)
        active.write(owner, mapOf("public" to "unconfirmed write"))
        val corrupt = data().readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        data().writeBytes(corrupt)
        assertTrue(runCatching { active.load(owner) }.isFailure)
        assertTrue(runCatching { active.write(owner, mapOf("public" to "later edit")) }.isFailure)
        assertArrayEquals(corrupt, data().readBytes())
    }

    @Test fun `uncertain candidate becomes rollback baseline before another commit can fail`() {
        var failCommit = false
        val active = EncryptedWeshDraftBackend(directory.root, cipher, commitData = { commit ->
            check(!failCommit) { "synthetic disk unavailable" }
            commit()
        })
        active.load(owner)
        active.write(owner, mapOf("public" to "A"))
        active.load(owner)
        active.write(owner, mapOf("public" to "B"))
        // The readback was not observed by the caller; B remains the uncertain candidate.
        failCommit = true
        assertTrue(runCatching { active.write(owner, mapOf("public" to "C")) }.isFailure)
        failCommit = false
        assertEquals(mapOf("public" to "B"), active.load(owner))
        active.write(owner, mapOf("public" to "C"))
        assertEquals(mapOf("public" to "C"), active.load(owner))
    }

    @Test fun `oversized file and bounded draft schema are rejected before replacement`() {
        data().writeBytes(ByteArray(1_100_065))
        assertTrue(runCatching { backend().load(owner) }.isFailure)
        assertTrue(runCatching { backend().write(owner, mapOf("public" to "x".repeat(8193))) }.isFailure)
        assertTrue(runCatching { backend().write(owner, mapOf("private:short" to "synthetic")) }.isFailure)
    }
}
