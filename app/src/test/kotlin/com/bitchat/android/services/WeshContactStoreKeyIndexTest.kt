package com.bitchat.android.services

import android.app.Application
import android.content.Context
import com.bitchat.android.identity.SecureIdentityStateManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class WeshContactStoreKeyIndexTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val identityPrefs get() = app.getSharedPreferences("synthetic-contacts-index", Context.MODE_PRIVATE)
    private val blocks get() = app.getSharedPreferences("synthetic-blocks-index", Context.MODE_PRIVATE)
    private val key = ByteArray(32) { 7 }
    private val other = ByteArray(32) { 8 }

    @Before
    fun setUp() {
        identityPrefs.edit().clear().commit()
        blocks.edit().clear().commit()
    }

    private fun store() = WeshContactStore(SecureIdentityStateManager(identityPrefs, true), blocks)

    @Test
    fun `enrolled key lookup follows every change to the contact list`() {
        val store = store()
        assertFalse(store.isEnrolledKey(key))
        store.record(key, "Synthetic")
        assertTrue(store.isEnrolledKey(key))
        assertFalse(store.isEnrolledKey(other))

        assertTrue("a reloaded store knows its contacts", store().isEnrolledKey(key))

        store.remove(ContactIdentityResolver.contactConversationIdForNoiseKey(key))
        assertFalse(store.isEnrolledKey(key))
        store.record(other, "Synthetic")
        store.clear()
        assertFalse(store.isEnrolledKey(other))
    }
}
