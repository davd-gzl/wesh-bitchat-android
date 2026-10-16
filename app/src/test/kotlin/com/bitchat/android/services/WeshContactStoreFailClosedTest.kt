package com.bitchat.android.services

import android.app.Application
import android.content.Context
import com.bitchat.android.identity.SecureIdentityStateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class WeshContactStoreFailClosedTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val identityPrefs get() = app.getSharedPreferences("synthetic-contacts-fail", Context.MODE_PRIVATE)
    private val blocks get() = app.getSharedPreferences("synthetic-blocks-fail", Context.MODE_PRIVATE)
    private val unreadable = "{not a contact list"

    @Before
    fun setUp() {
        identityPrefs.edit().clear().putString("wesh_qr_contacts_v1", unreadable).commit()
        blocks.edit().clear().commit()
    }

    private fun store() = WeshContactStore(SecureIdentityStateManager(identityPrefs, true), blocks)

    @Test
    fun `failed load blocks every write and keeps the stored contacts`() {
        val store = store()

        assertTrue(store.loadFailed.value)
        assertFalse(store.record(ByteArray(32) { 7 }, "Synthetic"))
        assertFalse(store.remove("any"))
        assertTrue(store.contacts.value.isEmpty())
        assertEquals(unreadable, identityPrefs.getString("wesh_qr_contacts_v1", null))
    }

    @Test
    fun `QR enrollment is refused after a failed load`() {
        val store = store()
        assertNull(store.enroll(ByteArray(32) { 7 }, "Synthetic", null, null, emptySet()))
        assertTrue(store.contacts.value.isEmpty())
        assertEquals(unreadable, identityPrefs.getString("wesh_qr_contacts_v1", null))
    }

    @Test
    fun `retry restores writes once the contacts are readable`() {
        val store = store()
        identityPrefs.edit().putString("wesh_qr_contacts_v1", "[]").commit()

        assertTrue(store.retryLoad())
        assertFalse(store.loadFailed.value)
        assertTrue(store.record(ByteArray(32) { 7 }, "Synthetic"))
        assertEquals(1, store.contacts.value.size)
    }

    @Test
    fun `panic wipe still clears an unreadable list`() {
        val store = store()
        store.clear()

        assertFalse(store.loadFailed.value)
        assertEquals("[]", identityPrefs.getString("wesh_qr_contacts_v1", null))
    }
}
