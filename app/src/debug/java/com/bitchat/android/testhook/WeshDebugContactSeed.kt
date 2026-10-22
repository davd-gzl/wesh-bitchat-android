package com.bitchat.android.testhook

import com.bitchat.android.services.WeshContactStore
import com.bitchat.android.services.WeshNpubPolicy

/**
 * Debug builds only. Enrolls a QR contact with an npub without a camera or Bluetooth, so an
 * emulator can exchange Internet messages with a second emulator or a scripted Nostr peer. It
 * stands for a scanned QR whose npub proof verified, and goes through the same enrollment checks
 * (valid npub, not mine, not another contact's). Release builds do not contain this file.
 */
internal object WeshDebugContactSeed {
    fun seed(
        contacts: WeshContactStore,
        ownedNpubs: Set<String>?,
        noiseHex: String,
        nickname: String,
        npub: String
    ): Boolean {
        val key = noiseHex.lowercase().takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
            ?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray() ?: return false
        val enrollment = WeshNpubPolicy.enroll(
            candidate = npub,
            proof = true,
            ownedNpubs = ownedNpubs,
            contacts = contacts.contacts.value,
            noiseHex = noiseHex.lowercase()
        )
        if (enrollment.npub != npub || enrollment.warning) return false
        return contacts.record(key, nickname, enrollment)
    }
}
