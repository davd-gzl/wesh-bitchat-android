package com.bitchat.android.ui

import android.content.Context
import android.util.AtomicFile
import com.bitchat.android.services.AndroidConversationStorageCipher
import com.bitchat.android.services.ConversationStorageCipher
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.io.File
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

internal class DraftWipePending : IllegalStateException()

/** Only ciphertext and a data-free wipe marker reach disk. All calls belong on IO. */
internal interface WeshDraftBackend {
    fun load(owner: String): Map<String, String>
    fun write(owner: String, drafts: Map<String, String>)
    fun markPanicPending()
}

internal class EncryptedWeshDraftBackend(
    directory: File,
    private val cipher: ConversationStorageCipher,
    private val probe: (File) -> Boolean = ::pathExists,
    private val commitData: (() -> Unit) -> Unit = { it() },
    private val commitMarker: (() -> Unit) -> Unit = { it() }
) : WeshDraftBackend {
    private val data = AtomicFile(File(directory, "wesh-drafts-v1.bin"))
    private val marker = AtomicFile(File(directory, "wesh-drafts-wipe-v1"))

    constructor(context: Context) : this(
        context.applicationContext.noBackupFilesDir,
        AndroidConversationStorageCipher("wesh_draft_storage_v1")
    )

    private var initializedOwner: String? = null
    private var baseline: ByteArray? = null
    private var candidate: ByteArray? = null

    override fun load(owner: String): Map<String, String> {
        val (bytes, drafts) = read(owner)
        if (initializedOwner == owner) {
            check(same(bytes, baseline) || candidate != null && same(bytes, candidate)) { "Draft storage changed" }
            if (candidate != null && same(bytes, candidate)) { baseline = candidate; candidate = null }
        } else {
            initializedOwner = owner
            baseline = bytes
            candidate = null
        }
        return drafts
    }

    private fun read(owner: String): Pair<ByteArray?, Map<String, String>> {
        require(WeshDraftStore.validOwner(owner))
        if (exists(marker)) throw DraftWipePending()
        if (!exists(data)) return null to emptyMap()
        val envelope = data.openRead().use { input ->
            val bytes = ByteArray(MAX_ENVELOPE_BYTES + 1)
            var count = 0
            while (count < bytes.size) {
                val read = input.read(bytes, count, bytes.size - count)
                if (read < 0) break
                count += read
            }
            require(count <= MAX_ENVELOPE_BYTES) { "Draft storage exceeds limit" }
            bytes.copyOf(count)
        }
        val plaintext = cipher.decrypt(envelope, aad(owner))
        require(plaintext.size <= MAX_PLAINTEXT_BYTES) { "Draft payload exceeds limit" }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(plaintext)).toString()
        val parser = JSONTokener(text)
        val record = parser.nextValue() as? JSONObject ?: error("Invalid draft document")
        require(parser.nextClean() == '\u0000')
        require(record.toString() == text) { "Noncanonical draft document" }
        require(record.keys().asSequence().toSet() == setOf("version", "owner", "drafts"))
        require(record.get("version") == 1 && record.getString("owner") == owner)
        val rows = record.getJSONArray("drafts")
        require(rows.length() <= WeshDraftStore.MAX_DRAFTS)
        val result = linkedMapOf<String, String>()
        repeat(rows.length()) { index ->
            val row = rows.getJSONArray(index)
            require(row.length() == 2)
            val destination = row.get(0)
            val text = row.get(1)
            require(destination is String && text is String)
            require(WeshDraftStore.validDestination(destination) && text.isNotEmpty())
            require(text.toByteArray(Charsets.UTF_8).size <= WeshDraftStore.MAX_DRAFT_BYTES)
            require(destination !in result)
            result[destination] = text
        }
        require(result.values.sumOf { it.toByteArray(Charsets.UTF_8).size } <= WeshDraftStore.MAX_TOTAL_BYTES)
        return envelope to result
    }

    override fun write(owner: String, drafts: Map<String, String>) {
        if (exists(marker)) throw DraftWipePending()
        require(WeshDraftStore.validOwner(owner))
        require(drafts.size <= WeshDraftStore.MAX_DRAFTS)
        require(drafts.all { (key, text) -> WeshDraftStore.validDestination(key) && text.isNotEmpty() && text.toByteArray(Charsets.UTF_8).size <= WeshDraftStore.MAX_DRAFT_BYTES })
        require(drafts.values.sumOf { it.toByteArray(Charsets.UTF_8).size } <= WeshDraftStore.MAX_TOTAL_BYTES)
        val rows = JSONArray()
        drafts.forEach { (destination, text) -> rows.put(JSONArray().put(destination).put(text)) }
        // Revalidate existing ciphertext before every replacement; an unreadable or foreign
        // document must never authorize key creation or overwrite, even after a failed readback.
        val (existing, _) = read(owner)
        if (initializedOwner == null) {
            check(existing == null) { "Draft restore required before replacement" }
            initializedOwner = owner
        }
        check(initializedOwner == owner && (same(existing, baseline) || candidate != null && same(existing, candidate))) { "Draft storage changed" }
        // A prior uncertain commit has now been read and authenticated. Preserve that exact
        // document as the rollback baseline before replacing the single pending candidate.
        if (candidate != null && same(existing, candidate)) { baseline = candidate; candidate = null }
        val plaintext = JSONObject().put("version", 1).put("owner", owner).put("drafts", rows)
            .toString().toByteArray(Charsets.UTF_8)
        require(plaintext.size <= MAX_PLAINTEXT_BYTES)
        val envelope = cipher.encrypt(plaintext, aad(owner))
        candidate = envelope
        commitData { writeAtomically(data, envelope) }
    }

    override fun markPanicPending() {
        commitMarker { writeAtomically(marker, byteArrayOf(1)) }
        check(marker.openRead().use { it.read() == 1 && it.read() == -1 })
    }

    private fun writeAtomically(file: AtomicFile, bytes: ByteArray) {
        file.baseFile.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
        val output = file.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    private fun exists(file: AtomicFile): Boolean {
        val base = probe(file.baseFile)
        val backup = probe(File(file.baseFile.path + ".bak"))
        val pending = probe(File(file.baseFile.path + ".new"))
        return base || backup || pending
    }

    private fun same(first: ByteArray?, second: ByteArray?): Boolean =
        if (first == null || second == null) first == null && second == null else first.contentEquals(second)

    private fun aad(owner: String): ByteArray = "WESH_DRAFTS_V1:$owner".toByteArray(Charsets.UTF_8)

    companion object {
        private fun pathExists(file: File): Boolean = try {
            val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            check(attributes.isRegularFile) { "Invalid draft storage entry" }
            true
        } catch (_: NoSuchFileException) {
            false
        }

        fun hasPendingWipe(context: Context): Boolean {
            val directory = checkNotNull(context.applicationContext.noBackupFilesDir)
            return hasPendingWipe(directory)
        }

        internal fun hasPendingWipe(directory: File): Boolean {
            val marker = File(directory, "wesh-drafts-wipe-v1")
            val base = pathExists(marker)
            val backup = pathExists(File(marker.path + ".bak"))
            val pending = pathExists(File(marker.path + ".new"))
            return base || backup || pending
        }
        const val MAX_PLAINTEXT_BYTES = 1_100_000
        const val MAX_ENVELOPE_BYTES = MAX_PLAINTEXT_BYTES + 64
    }
}
