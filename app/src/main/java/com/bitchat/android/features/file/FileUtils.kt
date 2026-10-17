package com.bitchat.android.features.file

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

/**
 * Wesh sends and keeps no media. What remains erases media an older build may have written:
 * the panic wipe and the conversation store's cleanup of unreferenced attachment paths.
 */
object FileUtils {

    private const val TAG = "FileUtils"

    /** Caller runs this verified logical erasure on IO before completing global panic. */
    fun clearAllMedia(context: Context): Boolean = clearAllMedia(
        filesDir = { context.filesDir },
        cacheDir = { context.cacheDir }
    )

    internal fun clearAllMedia(
        filesDir: () -> File,
        cacheDir: () -> File,
        deleteTree: (File) -> Boolean = { it.deleteRecursively() },
        probe: (File) -> Boolean = ::mediaDirectoryExists
    ): Boolean {
        var confirmed = true
        val targets = mutableListOf<File>()
        try {
            val root = filesDir()
            listOf("files/incoming", "files/outgoing", "images/incoming", "images/outgoing", "voicenotes")
                .forEach { targets += File(root, it) }
        } catch (_: Exception) {
            confirmed = false
        }
        try {
            // Capture once: requesting context.cacheDir again can recreate a just-deleted directory.
            val root = cacheDir()
            targets += File(root, "files/incoming")
            targets += File(root, "images/incoming")
            targets += root
        } catch (_: Exception) {
            confirmed = false
        }
        for (target in targets) {
            try {
                if (probe(target)) {
                    val deleted = deleteTree(target)
                    val absent = !probe(target)
                    if (!deleted || !absent) confirmed = false
                }
            } catch (_: Exception) {
                confirmed = false
            }
        }
        if (!confirmed) Log.w(TAG, "Media erasure was not confirmed")
        return confirmed
    }

    private fun mediaDirectoryExists(file: File): Boolean = try {
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        check(attributes.isDirectory) { "Unexpected media storage entry" }
        true
    } catch (_: NoSuchFileException) {
        false
    }

    /**
     * Deletes paths proven by the conversation database to have no remaining message reference.
     * The same canonical-root boundary as explicit conversation deletion prevents arbitrary paths
     * from being removed even if persisted metadata is malformed.
     */
    fun deleteStoredMediaPaths(context: Context, paths: Collection<String>) {
        val roots = listOf(context.filesDir, context.cacheDir)
            .mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
        paths.asSequence()
            .mapNotNull { runCatching { File(it).canonicalFile }.getOrNull() }
            .distinctBy(File::getPath)
            .filter { file ->
                roots.any { root ->
                    file.path == root.path ||
                        file.path.startsWith(root.path + File.separator)
                }
            }
            .forEach { file ->
                runCatching {
                    if (file.isFile && !file.delete()) {
                        Log.w(TAG, "Unable to delete unreferenced conversation media")
                    }
                }
            }
    }
}
