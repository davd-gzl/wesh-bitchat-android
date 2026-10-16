package com.bitchat.android.ui

import com.bitchat.android.services.ContactIdentityResolver
import org.junit.Assert.assertEquals
import org.junit.Test

class WeshPublicBlockPolicyTest {
    @Test
    fun `authenticated session key allows a durable identity block`() {
        val key = ByteArray(32) { 9 }
        assertEquals(
            WeshPublicBlockPolicy.Target.Identity(ContactIdentityResolver.fingerprintHex(key)),
            WeshPublicBlockPolicy.target("peer-a", key)
        )
    }

    @Test
    fun `without proof of key possession only the peer ID is hidden`() {
        assertEquals(WeshPublicBlockPolicy.Target.Session("peer-a"), WeshPublicBlockPolicy.target("peer-a", null))
        assertEquals(WeshPublicBlockPolicy.Target.Session("peer-a"), WeshPublicBlockPolicy.target("peer-a", ByteArray(16)))
    }
}
