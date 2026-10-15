package com.bitchat.android.features.file

import android.content.Context
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.LinkOption

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class FileUtilsPanicCleanupTest {
    @get:Rule val temporary = TemporaryFolder()
    private val mediaDirectories = listOf("files/incoming", "files/outgoing", "images/incoming", "images/outgoing", "voicenotes")
    private val syntheticContent = "synthetic attachment bytes"
    private data class Roots(val files: File, val cache: File)

    private fun roots(withMedia: Boolean = true): Roots {
        val roots = Roots(temporary.newFolder("files"), temporary.newFolder("cache"))
        if (withMedia) {
            mediaDirectories.forEach { populate(roots.files, it) }
            populate(roots.cache, "files/incoming")
            populate(roots.cache, "images/incoming")
            File(roots.cache, "synthetic-fallback.bin").writeText(syntheticContent)
        }
        return roots
    }

    private fun populate(root: File, relative: String): File = File(root, relative).apply { mkdirs() }
        .let { File(it, "synthetic.bin").apply { writeText(syntheticContent) } }

    @Test fun `confirmed cleanup removes every managed media directory and the captured cache root`() {
        val roots = roots()
        val retained = File(roots.files, "synthetic-unrelated-state.bin").apply { writeText("synthetic state") }
        val context: Context = mock()
        whenever(context.filesDir).thenReturn(roots.files)
        whenever(context.cacheDir).thenReturn(roots.cache)
        assertTrue(FileUtils.clearAllMedia(context))
        mediaDirectories.forEach { assertFalse(File(roots.files, it).exists()) }
        assertFalse(roots.cache.exists())
        assertEquals("synthetic state", retained.readText())
        verify(context, times(1)).cacheDir
    }

    @Test fun `refused deletion preserves readable bytes and still attempts every other target`() {
        val roots = roots()
        val refused = File(roots.files, "files/incoming")
        val attempted = mutableListOf<File>()
        assertFalse(FileUtils.clearAllMedia({ roots.files }, { roots.cache }, deleteTree = { target ->
            attempted += target
            if (target == refused) false else target.deleteRecursively()
        }))
        assertEquals(syntheticContent, File(refused, "synthetic.bin").readText())
        assertEquals(8, attempted.size)
        assertFalse(File(roots.files, "voicenotes").exists())
        assertFalse(roots.cache.exists())
    }

    @Test fun `deletion exception cannot skip remaining media or claim confirmed erasure`() {
        val roots = roots()
        val refused = File(roots.files, "files/incoming")
        val attempted = mutableListOf<File>()
        assertFalse(FileUtils.clearAllMedia({ roots.files }, { roots.cache }, deleteTree = { target ->
            attempted += target
            if (target == refused) throw AccessDeniedException("synthetic denied deletion")
            target.deleteRecursively()
        }))
        assertEquals(syntheticContent, File(refused, "synthetic.bin").readText())
        assertEquals(8, attempted.size)
        assertFalse(roots.cache.exists())
    }

    @Test fun `delete acknowledgement without actual absence is not confirmed`() {
        val roots = roots()
        val retained = File(roots.files, "files/incoming")
        assertFalse(FileUtils.clearAllMedia({ roots.files }, { roots.cache }, deleteTree = { target ->
            if (target == retained) true else target.deleteRecursively()
        }))
        assertEquals(syntheticContent, File(retained, "synthetic.bin").readText())
        assertFalse(roots.cache.exists())
    }

    @Test fun `unreadable stat is not interpreted as an absent media directory`() {
        val roots = roots()
        val denied = File(roots.files, "files/incoming")
        assertFalse(FileUtils.clearAllMedia({ roots.files }, { roots.cache }, probe = { target ->
            if (target == denied) throw AccessDeniedException("synthetic denied stat")
            Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)
        }))
        assertEquals(syntheticContent, File(denied, "synthetic.bin").readText())
        assertFalse(roots.cache.exists())
    }

    @Test fun `already absent media remains a successful idempotent cleanup`() {
        val roots = roots(withMedia = false)
        assertTrue(FileUtils.clearAllMedia({ roots.files }, { roots.cache }))
        var deletes = 0
        assertTrue(FileUtils.clearAllMedia({ roots.files }, { roots.cache }, deleteTree = { deletes++; false }))
        assertEquals(0, deletes)
    }

    @Test fun `unavailable files root still allows cache erasure and cannot report success`() {
        val roots = roots()
        assertFalse(FileUtils.clearAllMedia(filesDir = { error("synthetic root unavailable") }, cacheDir = { roots.cache }))
        assertFalse(roots.cache.exists())
        assertEquals(syntheticContent, File(roots.files, "files/incoming/synthetic.bin").readText())
    }

    @Test fun `unexpected media entry type remains unconfirmed rather than silently absent`() {
        val roots = roots()
        val unexpected = File(roots.files, "files/incoming")
        assertTrue(unexpected.deleteRecursively())
        unexpected.writeText(syntheticContent)
        assertFalse(FileUtils.clearAllMedia({ roots.files }, { roots.cache }))
        assertEquals(syntheticContent, unexpected.readText())
        assertFalse(roots.cache.exists())
    }
}
