package com.bitchat.android.ui

import com.bitchat.android.ui.pave.PaveDisplayName

/**
 * Two nicknames that read the same under the nickname rule's skeleton ("Nora", "nora", "Nоra" with a
 * Cyrillic o). Used to warn when a request copies a QR contact's nickname; never to display a name.
 */
internal fun weshNamesakes(a: String, b: String): Boolean {
    val ka = PaveDisplayName.skeletonWords(a).joinToString("")
    return ka.isNotEmpty() && ka == PaveDisplayName.skeletonWords(b).joinToString("")
}
