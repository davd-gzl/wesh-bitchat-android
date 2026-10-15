package com.bitchat.android.ui.media

import android.app.Application
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class ImageExportTest {
    private lateinit var resolver: ContentResolver
    private lateinit var source: File
    private val destination = Uri.parse("content://synthetic.images/export/1")

    @Before fun setUp() {
        resolver = mock(ContentResolver::class.java)
        source = File.createTempFile("synthetic-image-", ".png").apply {
            deleteOnExit()
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            `when`(resolver.insert(eq(MediaStore.Downloads.EXTERNAL_CONTENT_URI), any(ContentValues::class.java)))
                .thenReturn(destination)
            `when`(resolver.update(eq(destination), any(ContentValues::class.java), isNull(), isNull())).thenReturn(1)
        }
    }

    @Test fun publishesOnlyAfterWritingAndClosingTheWholeFile() {
        val bytes = object : ByteArrayOutputStream() {
            var closed = false
            override fun close() { closed = true; super.close() }
        }
        `when`(resolver.openOutputStream(destination, "w")).thenReturn(bytes)
        `when`(resolver.update(eq(destination), any(ContentValues::class.java), isNull(), isNull())).thenAnswer {
            assertTrue(bytes.closed)
            assertArrayEquals(source.readBytes(), bytes.toByteArray())
            assertEquals(0, it.getArgument<ContentValues>(1).getAsInteger(MediaStore.Downloads.IS_PENDING).toInt())
            1
        }
        assertTrue(ImageExport.toDownloads(resolver, source))
        verify(resolver, never()).delete(any(Uri::class.java), isNull(), isNull())
    }

    @Test fun missingDestinationDoesNotReportSuccess() {
        `when`(resolver.insert(eq(MediaStore.Downloads.EXTERNAL_CONTENT_URI), any(ContentValues::class.java))).thenReturn(null)
        assertFalse(ImageExport.toDownloads(resolver, source))
        verify(resolver, never()).openOutputStream(any(Uri::class.java), anyString())
    }

    @Test fun nullStreamRemovesIncompleteExportWithoutPublishingIt() {
        assertFalse(ImageExport.toDownloads(resolver, source))
        verify(resolver).delete(destination, null, null)
        verify(resolver, never()).update(any(Uri::class.java), any(ContentValues::class.java), isNull(), isNull())
    }

    @Test fun interruptedCopyRemovesIncompleteExport() {
        `when`(resolver.openOutputStream(destination, "w")).thenReturn(object : OutputStream() {
            override fun write(value: Int) { throw IOException("Synthetic full storage") }
        })
        assertFalse(ImageExport.toDownloads(resolver, source))
        verify(resolver).delete(destination, null, null)
    }

    @Test fun failedPublishCannotReportSuccessEvenIfCleanupIsDenied() {
        `when`(resolver.openOutputStream(destination, "w")).thenReturn(ByteArrayOutputStream())
        `when`(resolver.update(eq(destination), any(ContentValues::class.java), isNull(), isNull())).thenReturn(0)
        `when`(resolver.delete(destination, null, null)).thenThrow(SecurityException("Synthetic provider refusal"))
        assertFalse(ImageExport.toDownloads(resolver, source))
    }

    @Test
    @Config(sdk = [26, 28])
    fun olderAndroidUsesExplicitDocumentGrantWithoutDownloadsApi() {
        val intent = ImageExport.documentIntent(source)
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
        assertEquals("image/png", intent.type)
        assertEquals(source.name, intent.getStringExtra(Intent.EXTRA_TITLE))
        val bytes = ByteArrayOutputStream()
        `when`(resolver.openOutputStream(destination, "w")).thenReturn(bytes)
        assertTrue(ImageExport.toDocument(resolver, source, destination))
        assertArrayEquals(source.readBytes(), bytes.toByteArray())
        verify(resolver, never()).insert(any(Uri::class.java), any(ContentValues::class.java))
    }
}
