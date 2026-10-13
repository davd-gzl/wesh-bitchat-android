package com.bitchat.android.services

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
class WeshVerificationQRTest {
    private val qr = VerificationService.VerificationQR(
        1, "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a",
        "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", null,
        "Nora café", 1700000000, "AAECAwQFBgcICQoLDA0ODw", "ab".repeat(64)
    )

    @Test fun `shared unicode canonical vector remains cross client compatible`() {
        assertEquals(196, qr.canonicalBytes().size)
        val digest = MessageDigest.getInstance("SHA-256").digest(qr.canonicalBytes()).joinToString("") { "%02x".format(it) }
        assertEquals("54177f15665e6a07bcad135c1889fb40b2e932e301cb9da2529c672a945d4b3c", digest)
        assertEquals(qr, VerificationService.VerificationQR.fromUrlString(qr.toUrlString()))
    }

    @Test fun `invalid key sizes nonce duplicate fields and unsupported version cannot start verification`() {
        listOf(
            qr.copy(v = 2).toUrlString(), qr.copy(noiseKeyHex = "aa").toUrlString(),
            qr.copy(signKeyHex = "gg".repeat(32)).toUrlString(), qr.copy(sigHex = "aa").toUrlString(),
            qr.copy(nonceB64 = "AAECAwQFBgcICQoLDA0ODx").toUrlString(),
            qr.copy(nickname = "é".repeat(128)).toUrlString(),
            qr.toUrlString() + "&noise=" + qr.noiseKeyHex,
            qr.toUrlString() + "&unknown=1", qr.toUrlString() + "#fragment",
            qr.toUrlString().replace("verify?", "verify/path?"),
            qr.toUrlString().replace("verify?", "verify:42?"), qr.toUrlString().replace("verify?", "verify:NaN?"), "https://example.invalid/verify"
        ).forEach { assertNull(VerificationService.VerificationQR.fromUrlString(it)) }
    }

    @Test fun `signed codes validate and identity rotation cannot reuse cached code`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val service = com.bitchat.android.crypto.EncryptionService(context)
        VerificationService.configure(service)
        val before = VerificationService.buildMyQRString("Synthetic", null)!!
        assertNotNull(VerificationService.verifyScannedQR(before))
        assertNull(VerificationService.verifyScannedQR(before.replace("nick=Synthetic", "nick=Changed")))
        service.clearPersistentIdentity()
        val after = VerificationService.buildMyQRString("Synthetic", null)!!
        assertNotEquals(before, after)
        assertNotEquals(VerificationService.VerificationQR.fromUrlString(before)!!.noiseKeyHex,
            VerificationService.VerificationQR.fromUrlString(after)!!.noiseKeyHex)
        assertNotNull(VerificationService.verifyScannedQR(after))
    }

    @Test fun `clock window rejects stale and future codes with exact boundaries`() {
        val now = 1700000000L
        assertTrue(VerificationService.isFresh(now - 300, now))
        assertTrue(VerificationService.isFresh(now + 300, now))
        assertFalse(VerificationService.isFresh(now - 301, now))
        assertFalse(VerificationService.isFresh(now + 301, now))
        assertFalse(VerificationService.isFresh(Long.MAX_VALUE, now))
        assertFalse(VerificationService.isFresh(Long.MIN_VALUE, now))
    }
}
