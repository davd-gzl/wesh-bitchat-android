package com.bitchat.android.ui

import android.content.Context
import android.util.AtomicFile
import com.bitchat.android.services.AndroidConversationStorageCipher
import com.bitchat.android.services.ConversationStorageCipher
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

/** Caller owns the process-wide IO mutex and dispatcher. */
internal interface WeshMeetingNoteBackend {
    /** Null means absent; an empty string is a saved empty note. */
    suspend fun load(): String?
    suspend fun write(text: String)
}

internal class EncryptedWeshMeetingNoteBackend private constructor(
    private val directory: () -> File,
    private val cipher: ConversationStorageCipher,
    private val probe: (File) -> Boolean,
    private val commitData: (() -> Unit) -> Unit
) : WeshMeetingNoteBackend {
    constructor(
        directory: File,
        cipher: ConversationStorageCipher,
        probe: (File) -> Boolean = ::pathExists,
        commitData: (() -> Unit) -> Unit = { it() }
    ) : this({ directory }, cipher, probe, commitData)

    constructor(context: Context) : this(
        { checkNotNull(context.applicationContext.noBackupFilesDir) },
        AndroidConversationStorageCipher(KEY_ALIAS),
        ::pathExists,
        { it() }
    )

    private val data by lazy { AtomicFile(File(directory(), FILE_NAME)) }
    private var initialized = false
    private var baseline: ByteArray? = null
    private var candidate: ByteArray? = null

    override suspend fun load(): String? {
        val record = readCurrent()
        if (!initialized) {
            initialized = true
            baseline = record.envelope
        } else {
            acceptCurrent(record.envelope)
        }
        return record.text
    }

    override suspend fun write(text: String) {
        val plaintext = encode(text)
        // Read and authenticate existing data before encryption is allowed to
        // create any key. An orphaned or unreadable note must never be replaced.
        val current = readCurrent()
        if (!initialized) {
            check(current.envelope == null) { "Meeting-note restore required before replacement" }
            initialized = true
        }
        acceptCurrent(current.envelope)
        val envelope = cipher.encrypt(plaintext, aad())
        check(envelope.size <= MAX_ENVELOPE_BYTES) { "Meeting-note envelope exceeds limit" }
        candidate = envelope
        commitData { writeAtomically(envelope) }
        val committed = readCurrent()
        check(same(committed.envelope, envelope) && committed.text == text) {
            "Meeting-note commit was not confirmed"
        }
        baseline = envelope
        candidate = null
    }

    private data class Record(val envelope: ByteArray?, val text: String?)

    private fun readCurrent(): Record {
        val paths = storagePaths()
        val present = paths.map(probe)
        // Do not use AtomicFile.openRead: it can rename/delete ambiguous crash
        // remnants before authentication. This bounded store preserves them and
        // refuses access until the emergency wipe removes all variants.
        check(!present[1] && !present[2]) { "Meeting-note atomic write requires recovery" }
        if (!present[0]) return Record(null, null)
        val envelope = paths[0].inputStream().use { input ->
            val bytes = ByteArray(MAX_ENVELOPE_BYTES + 1)
            var count = 0
            while (count < bytes.size) {
                val read = input.read(bytes, count, bytes.size - count)
                if (read < 0) break
                count += read
            }
            check(count <= MAX_ENVELOPE_BYTES) { "Meeting-note envelope exceeds limit" }
            bytes.copyOf(count)
        }
        val plaintext = cipher.decrypt(envelope, aad())
        check(plaintext.size <= MAX_PLAINTEXT_BYTES) { "Meeting-note payload exceeds limit" }
        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(plaintext)).toString()
        return Record(envelope, text)
    }

    private fun acceptCurrent(envelope: ByteArray?) {
        check(same(envelope, baseline) || candidate != null && same(envelope, candidate)) {
            "Meeting-note storage changed"
        }
        // A candidate whose acknowledgement was lost is a safe baseline only
        // after the exact stored envelope has been authenticated by readCurrent.
        if (candidate != null && same(envelope, candidate)) {
            baseline = candidate
            candidate = null
        }
    }

    private fun encode(text: String): ByteArray {
        check(text.length <= MAX_PLAINTEXT_BYTES) { "Meeting-note payload exceeds limit" }
        val encoded = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(text))
        check(encoded.remaining() <= MAX_PLAINTEXT_BYTES) { "Meeting-note payload exceeds limit" }
        return ByteArray(encoded.remaining()).also { encoded.get(it) }
    }

    private fun writeAtomically(envelope: ByteArray) {
        data.baseFile.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
        val output = data.startWrite()
        try {
            output.write(envelope)
            output.fd.sync()
            data.finishWrite(output)
        } catch (error: Exception) {
            data.failWrite(output)
            throw error
        }
    }

    private fun storagePaths(): List<File> = data.baseFile.let {
        listOf(it, File(it.path + ".bak"), File(it.path + ".new"))
    }

    private fun same(first: ByteArray?, second: ByteArray?): Boolean =
        if (first == null || second == null) first == null && second == null else first.contentEquals(second)

    private fun aad(): ByteArray = ASSOCIATED_DATA.toByteArray(Charsets.UTF_8)

    companion object {
        internal const val FILE_NAME = "wesh-meeting-note-v1.bin"
        internal const val KEY_ALIAS = "wesh_meeting_note_storage_v1"
        internal const val MAX_PLAINTEXT_BYTES = 1_048_576
        internal const val MAX_ENVELOPE_BYTES = MAX_PLAINTEXT_BYTES + 64
        internal const val ASSOCIATED_DATA = "WESH_MEETING_NOTE_V1"

        private fun pathExists(file: File): Boolean = try {
            val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            check(attributes.isRegularFile) { "Invalid meeting-note storage entry" }
            true
        } catch (_: NoSuchFileException) {
            false
        }
    }
}
