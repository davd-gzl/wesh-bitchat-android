package com.bitchat.android.ui

import org.junit.Assert.*
import org.junit.Test

class WeshDraftStoreTest {
    @Test fun `navigation between public and two stable private destinations preserves isolated drafts`() {
        val store = WeshDraftStore()
        store.save("public", "public synthetic")
        store.save("contact_synthetic-a", "private A")
        store.save("contact_synthetic-b", "private B")
        assertEquals("public synthetic", store.drafts.value["public"])
        assertEquals("private A", store.drafts.value["contact_synthetic-a"])
        store.clearIfUnchanged("contact_synthetic-a", "private A")
        assertNull(store.drafts.value["contact_synthetic-a"])
        assertEquals("private B", store.drafts.value["contact_synthetic-b"])
    }
    @Test fun `late send callback cannot erase newer text or a different destination`() {
        val store = WeshDraftStore()
        store.save("public", "new synthetic draft")
        store.save("contact_synthetic-a", "A")
        store.clearIfUnchanged("public", "old synthetic draft")
        assertEquals("new synthetic draft", store.drafts.value["public"])
        assertEquals("A", store.drafts.value["contact_synthetic-a"])
    }
    @Test fun `drafts have bounded memory and reject excess UTF8 bytes without truncation`() {
        val store = WeshDraftStore()
        store.save("public", "kept")
        store.save("public", "é".repeat(4097))
        assertEquals("kept", store.drafts.value["public"])
        repeat(70) { store.save("synthetic-$it", "x") }
        assertEquals(64, store.drafts.value.size)
    }
}
