package com.bitchat.android.startup

import android.content.SharedPreferences
import com.bitchat.android.identity.DurableIdentityPreferences
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*

class DurableIdentityPreferencesTest {
    private class MemoryBeforeFailedCommit {
        val values = mutableMapOf<String, String>()
        val prefs = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        var succeeds = false
        var commits = 0
        init {
            `when`(prefs.edit()).thenReturn(editor)
            `when`(prefs.getString(anyString(), nullable(String::class.java))).thenAnswer { values[it.getArgument(0)] }
            `when`(editor.putString(anyString(), anyString())).thenAnswer {
                values[it.getArgument(0)] = it.getArgument(1); editor
            }
            `when`(editor.remove(anyString())).thenAnswer { values.remove(it.getArgument<String>(0)); editor }
            `when`(editor.clear()).thenAnswer { values.clear(); editor }
            `when`(editor.commit()).thenAnswer { commits++; succeeds }
        }
    }

    @Test fun memoryAfterFailedCommitCannotBecomeReadyUntilRewriteSucceeds() {
        val storage = MemoryBeforeFailedCommit()
        assertThrows(IllegalStateException::class.java) {
            DurableIdentityPreferences.write(storage.prefs, mapOf("synthetic-key" to "same-identity"))
        }
        assertEquals("same-identity", storage.values["synthetic-key"])
        assertThrows(IllegalStateException::class.java) {
            DurableIdentityPreferences.read(storage.prefs, "synthetic-key")
        }
        assertEquals(2, storage.commits)
        storage.succeeds = true
        assertEquals(listOf("same-identity"), DurableIdentityPreferences.read(storage.prefs, "synthetic-key"))
        assertEquals(3, storage.commits)
        DurableIdentityPreferences.read(storage.prefs, "synthetic-key")
        assertEquals(3, storage.commits)
    }

    @Test fun panicSupersedesPendingWriteAndFailedWipeMustRepairBeforeAnyRead() {
        val storage = MemoryBeforeFailedCommit()
        assertThrows(IllegalStateException::class.java) {
            DurableIdentityPreferences.write(storage.prefs, mapOf("synthetic-key" to "old-identity"))
        }
        assertThrows(IllegalStateException::class.java) { DurableIdentityPreferences.clear(storage.prefs) }
        assertThrows(IllegalStateException::class.java) { DurableIdentityPreferences.read(storage.prefs, "synthetic-key") }
        storage.succeeds = true
        assertEquals(listOf<String?>(null), DurableIdentityPreferences.read(storage.prefs, "synthetic-key"))
        assertTrue(storage.values.isEmpty())
    }

    @Test fun failedRemovalCannotRestoreSigningKeyFromMemoryOnRetry() {
        val storage = MemoryBeforeFailedCommit()
        storage.values["synthetic-key"] = "old-identity"
        assertThrows(IllegalStateException::class.java) { DurableIdentityPreferences.remove(storage.prefs, "synthetic-key") }
        assertThrows(IllegalStateException::class.java) { DurableIdentityPreferences.read(storage.prefs, "synthetic-key") }
        storage.succeeds = true
        assertEquals(listOf<String?>(null), DurableIdentityPreferences.read(storage.prefs, "synthetic-key"))
    }
}
