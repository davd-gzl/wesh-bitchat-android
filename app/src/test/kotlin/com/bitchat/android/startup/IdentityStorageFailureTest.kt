package com.bitchat.android.startup

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.identity.SecureIdentityStateManager
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.InvocationTargetException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class IdentityStorageFailureTest {
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private val encoded = Base64.encodeToString(ByteArray(32) { it.toByte() }, Base64.NO_WRAP)

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences("startup-synthetic-identity", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        context.getSharedPreferences("bitchat_crypto", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun absentKeysAreFirstUseButPartialKeysBlockWithoutOverwrite() {
        val manager = SecureIdentityStateManager(prefs, testOnly = true)
        assertNull(manager.loadStaticKey())
        prefs.edit().putString("static_private_key", encoded).commit()
        assertThrows(IllegalStateException::class.java) { manager.loadStaticKey() }
        assertEquals(encoded, prefs.getString("static_private_key", null))
        assertFalse(prefs.contains("static_public_key"))
    }

    @Test fun theRetiredSecondSigningKeyIsRemovedAndNeverRecreated() {
        prefs.edit().putString("signing_private_key", encoded).putString("signing_public_key", encoded).commit()
        val manager = SecureIdentityStateManager(prefs, testOnly = true)
        assertTrue(manager.forgetRetiredSigningKey())
        assertFalse(prefs.contains("signing_private_key"))
        assertFalse(prefs.contains("signing_public_key"))
        assertTrue(manager.forgetRetiredSigningKey())
    }

    @Test fun corruptKeysBlockAndAnUnchangedRestoredStoreKeepsIdentity() {
        val manager = SecureIdentityStateManager(prefs, testOnly = true)
        prefs.edit().putString("static_private_key", encoded).putString("static_public_key", "AA==").commit()
        assertThrows(IllegalStateException::class.java) { manager.loadStaticKey() }
        prefs.edit().putString("static_public_key", encoded).commit()
        assertArrayEquals(ByteArray(32) { it.toByte() }, manager.loadStaticKey()!!.first)
        assertArrayEquals(manager.loadStaticKey()!!.second,
            SecureIdentityStateManager(prefs, testOnly = true).loadStaticKey()!!.second)
    }

    @Test fun readErrorsPropagateInsteadOfReturningAbsent() {
        val broken = mock(SharedPreferences::class.java)
        `when`(broken.getString("static_private_key", null)).thenThrow(SecurityException("synthetic failure"))
        val manager = SecureIdentityStateManager(broken, testOnly = true)
        assertThrows(SecurityException::class.java) { manager.loadStaticKey() }
    }

    @Test fun failedIdentityCommitNeverReturnsAUsableNewIdentity() {
        val broken = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        `when`(broken.edit()).thenReturn(editor)
        `when`(editor.putString(anyString(), anyString())).thenReturn(editor)
        `when`(editor.commit()).thenReturn(false)
        val manager = SecureIdentityStateManager(broken, testOnly = true)
        assertThrows(IllegalStateException::class.java) { manager.saveStaticKey(ByteArray(32), ByteArray(32)) }
        assertThrows(IllegalStateException::class.java) { service(broken).generateAndSaveEd25519KeyPair() }
    }

    @Test fun signingReadFailureDoesNotGenerateOrWriteReplacement() {
        val broken = mock(SharedPreferences::class.java)
        `when`(broken.getString("ed25519_signing_private_key", null)).thenThrow(SecurityException("synthetic failure"))
        assertThrows(SecurityException::class.java) { loadSigning(service(broken)) }
        verify(broken, never()).edit()
    }

    @Test fun malformedSigningKeyDoesNotReplaceItself() {
        prefs.edit().putString("ed25519_signing_private_key", "AA==").commit()
        assertThrows(IllegalStateException::class.java) { loadSigning(service(prefs)) }
        assertEquals("AA==", prefs.getString("ed25519_signing_private_key", null))
    }

    @Test fun retryLoadsSamePersistedSigningIdentity() {
        val first = loadSigning(service(prefs)).private as Ed25519PrivateKeyParameters
        val retry = loadSigning(service(prefs)).private as Ed25519PrivateKeyParameters
        assertArrayEquals(first.encoded, retry.encoded)
    }

    @Test fun failedLegacyMigrationKeepsOriginalAndDoesNotGenerate() {
        val legacy = context.getSharedPreferences("bitchat_crypto", Context.MODE_PRIVATE)
        legacy.edit().putString("ed25519_signing_private_key", encoded).commit()
        val broken = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        `when`(broken.edit()).thenReturn(editor)
        `when`(editor.putString(anyString(), anyString())).thenReturn(editor)
        `when`(editor.commit()).thenReturn(false)
        assertThrows(IllegalStateException::class.java) { loadSigning(service(broken)) }
        assertEquals(encoded, legacy.getString("ed25519_signing_private_key", null))
    }

    private fun service(storage: SharedPreferences): EncryptionService {
        val service = object : EncryptionService(context) { override fun initialize() = Unit }
        EncryptionService::class.java.getDeclaredField("prefs").apply { isAccessible = true }.set(service, storage)
        return service
    }

    private fun loadSigning(service: EncryptionService): AsymmetricCipherKeyPair = try {
        EncryptionService::class.java.getDeclaredMethod("loadOrCreateEd25519KeyPair")
            .apply { isAccessible = true }.invoke(service) as AsymmetricCipherKeyPair
    } catch (error: InvocationTargetException) {
        throw error.targetException
    }
}
