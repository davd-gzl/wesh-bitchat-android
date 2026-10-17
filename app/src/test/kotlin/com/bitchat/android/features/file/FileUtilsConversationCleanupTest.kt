package com.bitchat.android.features.file

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class FileUtilsConversationCleanupTest {

    @Test
    fun `unreferenced media cleanup removes only app-owned files`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testDirectory = File(
            context.cacheDir,
            "conversation-cleanup-${UUID.randomUUID()}"
        ).apply { mkdirs() }
        val appOwned = File(testDirectory, "legacy.jpg").apply {
            writeBytes(byteArrayOf(1))
        }
        val outsideAppStorage = File.createTempFile(
            "conversation-cleanup-",
            ".jpg"
        ).apply {
            writeBytes(byteArrayOf(3))
        }

        try {
            FileUtils.deleteStoredMediaPaths(
                context,
                // An empty path is what a Wesh media placeholder carries: it must delete nothing.
                listOf(appOwned.absolutePath, outsideAppStorage.absolutePath, "", testDirectory.absolutePath)
            )

            assertFalse(appOwned.exists())
            assertTrue(outsideAppStorage.exists())
            assertTrue(testDirectory.isDirectory)
        } finally {
            testDirectory.deleteRecursively()
            outsideAppStorage.delete()
        }
    }
}
