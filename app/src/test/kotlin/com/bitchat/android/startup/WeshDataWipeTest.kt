package com.bitchat.android.startup

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WeshDataWipeTest {
    @get:Rule val directory = TemporaryFolder()

    private val ce by lazy { directory.newFolder("ce") }
    private val de by lazy { directory.newFolder("de") }
    private val noBackup by lazy { File(ce, "no_backup").apply { mkdirs() } }
    private val marker by lazy { File(noBackup, "wesh-drafts-wipe-v1") }
    private var keyWipes = 0
    private var androidClears = 0

    private fun write(root: File, path: String) = File(root, path).apply { parentFile!!.mkdirs(); writeText("RDVQAMARKER1") }

    private fun populate() {
        write(ce, "databases/conversations.db")
        write(ce, "databases/conversations.db-wal")
        write(ce, "shared_prefs/bitchat_prefs.xml")
        write(ce, "files/arti/state/state.json")
        write(ce, "files/images/incoming/a.jpg")
        write(ce, "cache/testhook/results/w1.json")
        write(ce, "app_webview/Default/Cookies")
        write(noBackup, "wesh-meeting-note-v1.bin")
        write(de, "shared_prefs/boot.xml")
        marker.writeBytes(byteArrayOf(1))
    }

    private fun remaining(): List<String> = listOf(ce, de).flatMap { root ->
        root.walkTopDown().filter { it != root && !(it.isDirectory && it == noBackup) }.map { it.relativeTo(directory.root).path }.toList()
    }

    @Test
    fun `own wipe deletes every app file and key but keeps the erase marker`() {
        populate()
        val outside = write(directory.newFolder("outside"), "secret.txt")
        Files.createSymbolicLink(File(ce, "files/link").toPath(), outside.toPath())

        WeshDataWipe.wipe(listOf(ce, de), noBackup) { keyWipes++ }

        assertEquals(listOf("ce/no_backup/wesh-drafts-wipe-v1"), remaining())
        assertEquals(1, keyWipes)
        assertTrue("a link is removed, never followed", outside.exists())
    }

    @Test
    fun `a file written back during the wipe fails it`() {
        populate()
        try {
            WeshDataWipe.wipe(listOf(ce, de), noBackup) { write(ce, "shared_prefs/late.xml") }
            fail("a late write must not pass as a complete wipe")
        } catch (_: IllegalStateException) { }
    }

    @Test
    fun `a pending erase at launch wipes again then lifts the marker`() {
        populate()

        WeshDataWipe.finishPendingErase(listOf(ce, de), noBackup, { keyWipes++ }) { androidClears++ }

        assertEquals(emptyList<String>(), remaining())
        assertEquals(1, keyWipes)
        assertEquals(0, androidClears)
    }

    @Test
    fun `a launch without a pending erase touches nothing`() {
        populate()
        marker.delete()
        val before = remaining()

        WeshDataWipe.finishPendingErase(listOf(ce, de), noBackup, { keyWipes++ }) { androidClears++ }

        assertEquals(before, remaining())
        assertEquals(0, keyWipes)
        assertEquals(0, androidClears)
    }

    @Test
    fun `a failed launch wipe hands the erase to Android and keeps the marker`() {
        populate()

        WeshDataWipe.finishPendingErase(listOf(ce, de), noBackup, { error("Synthetic keystore failure") }) { androidClears++ }

        assertEquals(1, androidClears)
        assertTrue(marker.exists())
        assertFalse(File(ce, "databases/conversations.db").exists())
    }
}
