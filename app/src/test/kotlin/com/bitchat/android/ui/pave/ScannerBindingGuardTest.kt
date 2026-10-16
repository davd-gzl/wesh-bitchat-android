package com.bitchat.android.ui.pave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerBindingGuardTest {

    @Test
    fun `a camera that becomes ready after disposal is never bound`() {
        val guard = ScannerBindingGuard()
        var bound = 0
        guard.dispose()
        assertFalse(guard.bindIfActive { bound++; {} })
        assertEquals(0, bound)
    }

    @Test
    fun `disposing after binding releases what was bound, once`() {
        val guard = ScannerBindingGuard()
        var released = 0
        assertTrue(guard.bindIfActive { { released++ } })
        guard.dispose()
        guard.dispose()
        assertEquals(1, released)
    }

    @Test
    fun `a new binding releases the previous one first`() {
        val guard = ScannerBindingGuard()
        val events = mutableListOf<String>()
        guard.bindIfActive { events += "bind 1"; { events += "release 1" } }
        guard.bindIfActive { events += "bind 2"; { events += "release 2" } }
        guard.dispose()
        assertEquals(listOf("bind 1", "release 1", "bind 2", "release 2"), events)
    }

    @Test
    fun `results decoded after disposal are dropped`() {
        val guard = ScannerBindingGuard()
        val delivered = mutableListOf<String>()
        guard.deliverIfActive { delivered += "before" }
        guard.dispose()
        guard.deliverIfActive { delivered += "after" }
        assertEquals(listOf("before"), delivered)
    }
}
