package com.bitchat.android.startup

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import com.bitchat.android.ui.EncryptedWeshDraftBackend
import java.io.File
import java.io.IOException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.KeyStore

/**
 * The app's own full wipe: every file in both data directories, then every Keystore key,
 * notification and conversation shortcut (a pinned one is renamed and disabled).
 * The durable erase marker in no_backup survives it, so the next launch wipes again whatever
 * the old process wrote late, and only then lifts the marker.
 */
internal object WeshDataWipe {
    private const val MARKER = "wesh-drafts-wipe-v1"
    private val markerNames = listOf(MARKER, "$MARKER.bak", "$MARKER.new")

    fun wipe(context: Context) = wipe(roots(context), noBackup(context)) { clearOutsideAppFiles(context) }

    /** Called before any provider or store opens storage. Never blocks the erase: Android clears what the wipe could not. */
    fun finishPendingErase(context: Context) = finishPendingErase(roots(context), noBackup(context), { clearOutsideAppFiles(context) }) {
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

    internal fun wipe(roots: List<File>, noBackup: File, clearOutsideFiles: () -> Unit) {
        val kept = markerNames.map { File(noBackup, it).toPath() }.toSet() +
            // Android owns this link for 32-bit apps, and its own clear keeps it too.
            roots.map { it.toPath().resolve("lib") }.filter { Files.isSymbolicLink(it) }
        roots.forEach { deleteTree(it.toPath(), kept) }
        clearOutsideFiles()
        // code_cache is still deleted, but the running renderer may write its shader cache back at any time.
        val renderCaches = roots.map { it.toPath().resolve("code_cache") }
        val left = roots.flatMap { root -> entries(root.toPath()) }
            .filter { it !in kept && it != noBackup.toPath() && renderCaches.none(it::startsWith) }
        check(left.isEmpty()) { "${left.size} app data entries remain after the wipe" }
    }

    internal fun finishPendingErase(roots: List<File>, noBackup: File, clearOutsideFiles: () -> Unit, clearAppData: () -> Unit) {
        val pending = try { EncryptedWeshDraftBackend.hasPendingWipe(noBackup) } catch (_: Exception) { true }
        if (!pending) return
        try {
            wipe(roots, noBackup, clearOutsideFiles)
            markerNames.forEach { Files.deleteIfExists(File(noBackup, it).toPath()) }
        } catch (_: Exception) {
            clearAppData()
        }
    }

    private fun roots(context: Context): List<File> = listOf(context.dataDir, context.createDeviceProtectedStorageContext().dataDir)

    private fun noBackup(context: Context) = File(context.dataDir, "no_backup")

    /** Keys, notifications and conversation shortcuts are kept by the system, outside the data directories. */
    private fun clearOutsideAppFiles(context: Context) {
        // Every step runs even when an earlier one fails; the first failure then reaches the fallback.
        val failures = listOf(
            ::deleteKeystoreEntries,
            { checkNotNull(context.getSystemService(android.app.NotificationManager::class.java)).cancelAll() },
            { neutralizePinnedShortcuts(context) },
            {
                val conversations = ShortcutManagerCompat.getShortcuts(
                    context, ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or ShortcutManagerCompat.FLAG_MATCH_CACHED
                ).map { it.id }
                ShortcutManagerCompat.removeAllDynamicShortcuts(context)
                if (conversations.isNotEmpty()) ShortcutManagerCompat.removeLongLivedShortcuts(context, conversations)
            }
        ).mapNotNull { step -> runCatching { step() }.exceptionOrNull() }
        failures.firstOrNull()?.let { throw it }
    }

    /** Android cannot unpin a home-screen shortcut, so a pinned conversation loses its name and is disabled. */
    internal fun neutralizePinnedShortcuts(context: Context) {
        val shortcuts = checkNotNull(context.getSystemService(android.content.pm.ShortcutManager::class.java))
        val static = shortcuts.manifestShortcuts.map { it.id }.toSet()
        val pinned = shortcuts.pinnedShortcuts.map { it.id } - static
        if (pinned.isEmpty()) return
        val name = context.packageManager.getApplicationLabel(context.applicationInfo).toString()
        val open = Intent(context, com.bitchat.android.MainActivity::class.java).setAction(Intent.ACTION_MAIN)
        // An update keeps every field it leaves unset, so the stored contact is overwritten too.
        val nobody = androidx.core.app.Person.Builder().setName(name).build()
        val renamed = ShortcutManagerCompat.updateShortcuts(context, pinned.map {
            ShortcutInfoCompat.Builder(context, it).setShortLabel(name).setLongLabel(name).setPerson(nobody).setIntent(open).build()
        })
        // Android rate-limits updates from the background; a refused update must reach the fallback.
        check(renamed) { "Pinned shortcuts could not be renamed" }
        ShortcutManagerCompat.disableShortcuts(context, pinned, name)
    }

    private fun deleteKeystoreEntries() {
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keystore.aliases().toList().forEach(keystore::deleteEntry)
        check(!keystore.aliases().hasMoreElements()) { "Keystore entries remain after the wipe" }
    }

    /** Links are removed, never followed. The root itself and directories holding a kept entry stay. */
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
                if (dir != root && kept.none { it.startsWith(dir) }) {
                    // A file written back meanwhile is left to the final check and the launch wipe.
                    try { Files.deleteIfExists(dir) } catch (_: DirectoryNotEmptyException) { }
                }
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun entries(root: Path): List<Path> {
        if (!Files.exists(root)) return emptyList()
        // Stream.toList is API 34; the iterator works from API 26.
        return Files.walk(root).use { paths -> paths.iterator().asSequence().filter { it != root }.toList() }
    }
}
