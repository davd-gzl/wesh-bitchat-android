package com.bitchat.android.services

import android.app.Application
import android.content.Context
import com.bitchat.android.identity.SecureIdentityStateManager
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class WeshContactStoreNicknameTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val identityPrefs get() = app.getSharedPreferences("synthetic-contacts-names", Context.MODE_PRIVATE)
    private val blocks get() = app.getSharedPreferences("synthetic-blocks-names", Context.MODE_PRIVATE)
    private val key = ByteArray(32) { 7 }

    @Before
    fun setUp() {
        identityPrefs.edit().clear().commit()
        blocks.edit().clear().commit()
    }

    private fun store() = WeshContactStore(SecureIdentityStateManager(identityPrefs, true), blocks)

    @Test
    fun `recorded nickname goes through the shared display name sanitizer`() {
        val store = store()
        store.record(key, "‮evil​name")
        assertEquals("evilname", store.contacts.value.single().nickname)
    }

    @Test
    fun `an invisible nickname falls back to the key fingerprint`() {
        val store = store()
        store.record(key, "ㅤ​")
        val fingerprint = ContactIdentityResolver.fingerprintHex(key)
        assertEquals(fingerprint.take(8), store.contacts.value.single().nickname)
    }

    @Test
    fun `a raw nickname stored by an earlier version is sanitized on load`() {
        val raw = """[{"noise":"${"07".repeat(32)}","nickname":"‮evil​name"}]"""
        identityPrefs.edit().putString("wesh_qr_contacts_v1", raw).commit()
        assertEquals("evilname", store().contacts.value.single().nickname)
    }
}
