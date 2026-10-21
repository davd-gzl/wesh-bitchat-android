package com.bitchat.android.startup

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import com.bitchat.android.ui.EncryptedWeshDraftBackend
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.KeyStore

/**
 * The app's own full wipe: every file in both data directories, then every Keystore key.
 * The durable erase marker in no_backup survives it, so the next launch wipes again whatever
 * the old process wrote late, and only then lifts the marker.
 */
internal object WeshDataWipe {
    private const val MARKER = "wesh-drafts-wipe-v1"
    private val markerNames = listOf(MARKER, "$MARKER.bak", "$MARKER.new")

    fun wipe(context: Context) = wipe(roots(context), noBackup(context), ::deleteKeystoreEntries)

    /** Called before any provider or store opens storage. Never blocks the erase: Android clears what the wipe could not. */
    fun finishPendingErase(context: Context) = finishPendingErase(roots(context), noBackup(context), ::deleteKeystoreEntries) {
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).clearApplicationUserData()
    }

    /** Relaunches into a fresh task, then ends this process so no in-memory state survives the wipe. */
    fun restart(context: Context) {
        try {
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.component?.let {
                context.startActivity(Intent.makeRestartActivityTask(it))
            }
        } finally {
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    internal fun wipe(roots: List<File>, noBackup: File, deleteKeys: () -> Unit) {
        val kept = markerNames.map { File(noBackup, it).toPath() }.toSet()
        roots.forEach { deleteTree(it.toPath(), kept) }
        deleteKeys()
        // code_cache is still deleted, but the running renderer may write its shader cache back at any time.
        val renderCaches = roots.map { it.toPath().resolve("code_cache") }
        val left = roots.flatMap { root -> entries(root.toPath()) }
            .filter { it !in kept && it != noBackup.toPath() && renderCaches.none(it::startsWith) }
        check(left.isEmpty()) { "${left.size} app data entries remain after the wipe" }
    }

    internal fun finishPendingErase(roots: List<File>, noBackup: File, deleteKeys: () -> Unit, clearAppData: () -> Unit) {
        val pending = try { EncryptedWeshDraftBackend.hasPendingWipe(noBackup) } catch (_: Exception) { true }
        if (!pending) return
        try {
            wipe(roots, noBackup, deleteKeys)
            markerNames.forEach { Files.deleteIfExists(File(noBackup, it).toPath()) }
        } catch (_: Exception) {
            clearAppData()
        }
    }

    private fun roots(context: Context): List<File> = listOf(context.dataDir, context.createDeviceProtectedStorageContext().dataDir)

    private fun noBackup(context: Context) = File(context.dataDir, "no_backup")

    private fun deleteKeystoreEntries() {
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keystore.aliases().toList().forEach(keystore::deleteEntry)
        check(!keystore.aliases().hasMoreElements()) { "Keystore entries remain after the wipe" }
    }

    /** Links are removed, never followed. The root itself and directories holding the marker stay. */
    private fun deleteTree(root: Path, kept: Set<Path>) {
        if (!Files.exists(root)) return
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (file !in kept) Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (dir != root && kept.none { it.startsWith(dir) }) Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun entries(root: Path): List<Path> {
        if (!Files.exists(root)) return emptyList()
        return Files.walk(root).use { paths -> paths.filter { it != root }.toList() }
    }
}
