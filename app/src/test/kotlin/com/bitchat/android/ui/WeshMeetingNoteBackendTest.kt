package com.bitchat.android.ui

import android.app.Application
import android.content.Context
import com.bitchat.android.services.AndroidConversationStorageCipher
import com.bitchat.android.services.ConversationStorageCipher
import com.bitchat.android.services.ConversationStorageKeyProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.AccessDeniedException
import java.util.UUID
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35])
class WeshMeetingNoteBackendTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun `constructors do not consult context storage probes or keys`() {
        val context = Mockito.mock(Context::class.java)
        EncryptedWeshMeetingNoteBackend(context)
        Mockito.verifyNoInteractions(context)
        val fixture = fixture()
        EncryptedWeshMeetingNoteBackend(fixture.root, fixture.cipher(), probe = { error("Unexpected probe") })
        assertEquals(0, fixture.keys.creates)
        assertEquals(0, fixture.keys.deletes)
        assertFalse(fixture.file.exists())
    }

    @Test
    fun `an absent note does not create a key or directory`() = runBlocking {
        val fixture = fixture(createDirectory = false)
        assertNull(fixture.backend().load())
        assertEquals(0, fixture.keys.creates)
        assertFalse(fixture.root.exists())
    }

    @Test
    fun `saved empty note remains distinct from an absent note`() = runBlocking {
        val fixture = fixture()
        val backend = fixture.backend()
        assertNull(backend.load())
        backend.write("")
        assertEquals("", backend.load())
        assertEquals("", fixture.backend().load())
        assertTrue(fixture.file.length() > 0)
    }

    @Test
    fun `a Unicode note survives a fresh backend without plaintext on disk`() = runBlocking {
        val fixture = fixture()
        fixture.backend().write(NOTE)
        assertEquals(NOTE, fixture.backend().load())
        assertEquals(1, fixture.keys.creates)
        assertFalse(fixture.file.readBytes().toString(Charsets.UTF_8).contains(NOTE))
        assertEquals(listOf(fixture.file.name), fixture.root.listFiles()!!.map { it.name })
    }

    @Test
    fun `invalid surrogate and oversized text cannot replace a valid note`() = runBlocking {
        for (invalid in listOf("\uD800", "x".repeat(EncryptedWeshMeetingNoteBackend.MAX_PLAINTEXT_BYTES + 1))) {
            val fixture = fixture()
            val original = fixture.seed(NOTE)
            val backend = fixture.backend()
            backend.load()
            refuses { backend.write(invalid) }
            assertArrayEquals(original, fixture.file.readBytes())
            assertEquals(1, fixture.keys.creates)
        }
    }

    @Test
    fun `tampered truncated foreign malformed and oversized records cannot be read or overwritten`() = runBlocking {
        for (variant in listOf("tampered", "truncated", "foreign", "malformed", "oversized")) {
            val fixture = fixture()
            val valid = fixture.seed(NOTE)
            val bytes = when (variant) {
                "tampered" -> valid.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
                "truncated" -> valid.copyOf(5)
                "foreign" -> fixture.cipher().encrypt(NOTE.toByteArray(), "FOREIGN_NOTE_FORMAT".toByteArray())
                "malformed" -> fixture.cipher().encrypt(byteArrayOf(0xc3.toByte(), 0x28), AAD)
                else -> ByteArray(EncryptedWeshMeetingNoteBackend.MAX_ENVELOPE_BYTES + 1)
            }
            fixture.file.writeBytes(bytes)
            val backend = fixture.backend()
            refuses { backend.load() }
            refuses { backend.write("Synthetic replacement") }
            assertArrayEquals(bytes, fixture.file.readBytes())
            assertEquals(1, fixture.keys.creates)
        }
    }

    @Test
    fun `orphaned ciphertext cannot create a replacement key`() = runBlocking {
        val fixture = fixture()
        val original = fixture.seed(NOTE)
        fixture.keys.delete()
        val backend = fixture.backend()
        refuses { backend.load() }
        refuses { backend.write("Synthetic replacement") }
        assertEquals(1, fixture.keys.creates)
        assertNull(fixture.keys.stored)
        assertArrayEquals(original, fixture.file.readBytes())
    }

    @Test
    fun `authenticated bytes changed outside this owner cannot become its replacement baseline`() = runBlocking {
        val fixture = fixture()
        fixture.seed(NOTE)
        val backend = fixture.backend()
        backend.load()
        val changed = fixture.seed("Synthetic foreign but authenticated note")
        refuses { backend.load() }
        refuses { backend.write("Synthetic replacement") }
        assertArrayEquals(changed, fixture.file.readBytes())
    }

    @Test
    fun `an existing note must be loaded before it can be replaced`() = runBlocking {
        val fixture = fixture()
        val original = fixture.seed(NOTE)
        val backend = fixture.backend()
        refuses { backend.write("Synthetic unreviewed replacement") }
        assertArrayEquals(original, fixture.file.readBytes())
        assertEquals(NOTE, backend.load())
        backend.write("Synthetic reviewed replacement")
        assertEquals("Synthetic reviewed replacement", backend.load())
    }

    @Test
    fun `skipped commit stays unconfirmed until an actual retry writes the note`() = runBlocking {
        val fixture = fixture()
        var skip = true
        var attempts = 0
        val backend = EncryptedWeshMeetingNoteBackend(fixture.root, fixture.cipher(), commitData = { commit ->
            attempts++
            if (!skip) commit()
        })
        refuses { backend.write(NOTE) }
        assertFalse(fixture.file.exists())
        skip = false
        backend.write(NOTE)
        assertEquals(2, attempts)
        assertEquals(NOTE, backend.load())
    }

    @Test
    fun `a committed candidate is authenticated before another uncertain replacement`() = runBlocking {
        val fixture = fixture()
        fixture.seed(NOTE)
        var attempt = 0
        val backend = EncryptedWeshMeetingNoteBackend(fixture.root, fixture.cipher(), commitData = { commit ->
            when (++attempt) {
                1 -> { commit(); error("Synthetic lost commit acknowledgement") }
                2 -> error("Synthetic failure before replacement")
                else -> commit()
            }
        })
        backend.load()
        refuses { backend.write("Synthetic committed candidate") }
        refuses { backend.write("Synthetic later candidate") }
        assertEquals("Synthetic committed candidate", backend.load())
        backend.write("Synthetic later candidate")
        assertEquals("Synthetic later candidate", backend.load())
        assertEquals(1, fixture.keys.creates)
    }

    @Test
    fun `unreadable readback remains preserved and cannot authorize later overwrite`() = runBlocking {
        val fixture = fixture()
        val corrupted = byteArrayOf(1, 2, 3, 4)
        var attempts = 0
        val backend = EncryptedWeshMeetingNoteBackend(fixture.root, fixture.cipher(), commitData = { commit ->
            attempts++
            commit()
            fixture.file.writeBytes(corrupted)
        })
        refuses { backend.write(NOTE) }
        refuses { backend.load() }
        refuses { backend.write("Synthetic replacement") }
        assertEquals(1, attempts)
        assertArrayEquals(corrupted, fixture.file.readBytes())
    }

    @Test
    fun `ambiguous atomic companions are preserved without implicit recovery`() = runBlocking {
        for (suffix in listOf(".bak", ".new")) {
            val fixture = fixture()
            val original = fixture.seed(NOTE)
            val companion = File(fixture.file.path + suffix)
            val pending = "Synthetic ambiguous pending bytes".toByteArray()
            companion.writeBytes(pending)
            val backend = fixture.backend()
            refuses { backend.load() }
            refuses { backend.write("Synthetic replacement") }
            assertArrayEquals(original, fixture.file.readBytes())
            assertArrayEquals(pending, companion.readBytes())
        }
    }

    @Test
    fun `storage probe failure never becomes absence or permission to create a key`() = runBlocking {
        val fixture = fixture()
        val backend = EncryptedWeshMeetingNoteBackend(fixture.root, fixture.cipher(), probe = {
            throw AccessDeniedException("synthetic-note")
        })
        refuses { backend.load() }
        refuses { backend.write(NOTE) }
        assertEquals(0, fixture.keys.creates)
        assertFalse(fixture.file.exists())
    }

    @Test
    fun `erase destroys the key before removing every atomic variant and retires this instance`() = runBlocking {
        val fixture = fixture()
        fixture.seed(NOTE)
        for (suffix in listOf(".bak", ".new")) File(fixture.file.path + suffix).writeText("Synthetic pending bytes")
        val delegate = fixture.cipher()
        val events = mutableListOf<String>()
        lateinit var backend: EncryptedWeshMeetingNoteBackend
        val cipher = object : ConversationStorageCipher by delegate {
            override fun destroyKey() {
                events += "key"
                // Retirement is observable before even the first destructive effect.
                runBlocking {
                    refuses { backend.load() }
                    refuses { backend.write("Synthetic stale writer") }
                }
                delegate.destroyKey()
            }
        }
        backend = EncryptedWeshMeetingNoteBackend(fixture.root, cipher, probe = { path ->
            events += "probe"
            assertNull("Storage must only be inspected for removal after key deletion", fixture.keys.stored)
            path.exists()
        })
        backend.erase()
        assertEquals(listOf("key"), events.take(1))
        assertEquals(1, fixture.keys.deletes)
        assertNull(fixture.keys.stored)
        for (suffix in listOf("", ".bak", ".new")) assertFalse(File(fixture.file.path + suffix).exists())
        refuses { backend.load() }
        refuses { backend.write("Synthetic stale writer") }
        assertNull(fixture.backend().load())
        assertEquals(1, fixture.keys.creates)
    }

    @Test
    fun `failed key deletion retires the instance but explicit erase retry remains possible`() = runBlocking {
        val fixture = fixture()
        val original = fixture.seed(NOTE)
        val delegate = fixture.cipher()
        var failDelete = true
        val cipher = object : ConversationStorageCipher by delegate {
            override fun destroyKey() {
                if (failDelete) error("Synthetic unavailable key deletion")
                delegate.destroyKey()
            }
        }
        val backend = EncryptedWeshMeetingNoteBackend(fixture.root, cipher)
        refuses { backend.erase() }
        refuses { backend.load() }
        refuses { backend.write("Synthetic stale writer") }
        assertArrayEquals(original, fixture.file.readBytes())
        failDelete = false
        backend.erase()
        assertFalse(fixture.file.exists())
        assertNull(fixture.keys.stored)
        assertNull(fixture.backend().load())
    }

    @Test
    fun `failed erase verification remains an error until an explicit retry confirms absence`() = runBlocking {
        val fixture = fixture()
        fixture.seed(NOTE)
        var failProbe = true
        val backend = EncryptedWeshMeetingNoteBackend(fixture.root, fixture.cipher(), probe = { path ->
            if (failProbe) throw AccessDeniedException("synthetic-note")
            path.exists()
        })
        refuses { backend.erase() }
        assertNull(fixture.keys.stored)
        refuses { backend.load() }
        refuses { backend.write("Synthetic stale writer") }
        failProbe = false
        backend.erase()
        assertFalse(fixture.file.exists())
    }

    @Test
    fun `unexpected directory entry cannot be read overwritten or silently removed`() = runBlocking {
        val fixture = fixture()
        assertTrue(fixture.file.mkdir())
        val backend = fixture.backend()
        refuses { backend.load() }
        refuses { backend.write(NOTE) }
        refuses { backend.erase() }
        assertTrue(fixture.file.isDirectory)
    }

    private fun fixture(createDirectory: Boolean = true): Fixture {
        val root = File(directory.root, UUID.randomUUID().toString())
        if (createDirectory) check(root.mkdir())
        return Fixture(root, SyntheticNoteKeys())
    }

    private class Fixture(val root: File, val keys: SyntheticNoteKeys) {
        val file = File(root, EncryptedWeshMeetingNoteBackend.FILE_NAME)
        fun cipher() = AndroidConversationStorageCipher(keys)
        fun backend() = EncryptedWeshMeetingNoteBackend(root, cipher())
        fun seed(text: String): ByteArray = cipher().encrypt(text.toByteArray(Charsets.UTF_8), AAD)
            .also(file::writeBytes)
    }

    private suspend fun refuses(operation: suspend () -> Unit) {
        try {
            operation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return
        }
        fail("The backend must refuse this storage operation")
    }

    private companion object {
        const val NOTE = "Synthetic rendez-vous — café, 18 h 🟨\nLocal note only"
        val AAD = "WESH_MEETING_NOTE_V1".toByteArray(Charsets.UTF_8)
    }
}

private class SyntheticNoteKeys : ConversationStorageKeyProvider {
    var stored: SecretKey? = null
    var creates = 0
        private set
    var deletes = 0
        private set
    override fun loadExisting() = stored
    override fun create(): SecretKey {
        creates++
        return SecretKeySpec(ByteArray(32) { creates.toByte() }, "AES").also { stored = it }
    }
    override fun delete() { deletes++; stored = null }
}
