package com.bitchat.android.ui

import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.services.ContactIdentityResolver
import org.junit.Assert.*
import org.junit.Test

class WeshMessagePolicyTest {
    private val key = ByteArray(32) { it.toByte() }
    private val replacement = ByteArray(32) { (it + 1).toByte() }
    private val verified = setOf(ContactIdentityResolver.fingerprintHex(key))

    @Test fun `public text remains literal and cannot select a private or internet channel`() {
        val effects = mutableListOf<String>()
        assertTrue(WeshMessagePolicy.sendPublicText("/join synthetic", "Synthetic", "peer-local",
            store = { message -> assertFalse(message.isPrivate); assertNull(message.channel); effects += "stored" },
            bluetoothSend = { content, mentions, channel ->
                assertEquals("/join synthetic", content); assertTrue(mentions.isEmpty()); assertNull(channel); effects += "bluetooth"
            }))
        assertEquals(listOf("stored", "bluetooth"), effects)
        effects.clear()
        assertFalse(WeshMessagePolicy.sendPublicText(" ", "Synthetic", "peer-local", { effects += "stored" }, { _, _, _ -> effects += "sent" }))
        assertTrue(effects.isEmpty())
    }

    @Test fun `a parsed QR or unavailable session does not authorize private sending`() {
        assertFalse(WeshMessagePolicy.canSendPrivate(null, key, verified))
        assertFalse(WeshMessagePolicy.canSendPrivate(key, key, emptySet()))
        assertTrue(WeshMessagePolicy.canSendPrivate(key, key, verified))
    }
    @Test fun `changed authenticated identity removes outgoing eligibility`() {
        assertFalse(WeshMessagePolicy.canSendPrivate(replacement, key, verified))
        assertFalse(WeshMessagePolicy.canSendPrivate(replacement, replacement, verified))
        assertFalse(WeshMessagePolicy.sessionMatches(replacement, key.joinToString("") { "%02x".format(it) }))
    }
    @Test fun `transport handoff and partial receipt never claim full recipient receipt`() {
        assertEquals("En attente d’envoi", WeshMessagePolicy.deliveryLabel(null))
        assertEquals("Transmis · réception non confirmée", WeshMessagePolicy.deliveryLabel(DeliveryStatus.Sent))
        assertEquals("Réception partielle", WeshMessagePolicy.deliveryLabel(DeliveryStatus.PartiallyDelivered(1, 2)))
        assertEquals("Échec de l’envoi", WeshMessagePolicy.deliveryLabel(DeliveryStatus.Failed("synthetic")))
    }
}
