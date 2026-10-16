package com.bitchat.android.ui.pave

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import com.bitchat.android.R
import com.bitchat.android.services.ContactIdentityResolver
import java.util.Locale

/**
 * A remote nickname ready to display, through [PaveDisplayName.remote] with the central
 * [PaveReservedWords]. Every Pavé component that shows someone else's name goes through this.
 */
@Composable
fun remoteDisplayName(raw: String?): String {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val reservedWords = remember(context, configuration) { PaveReservedWords.words(context) }
    return remoteDisplayName(raw, LocalResources.current::getString, reservedWords)
}

/** The Compose-free part of [remoteDisplayName]: [label] reads a string in the phone language. */
internal fun remoteDisplayName(raw: String?, label: (Int) -> String, reservedWords: Collection<String>): String =
    PaveDisplayName.remote(
        raw,
        unnamed = label(R.string.wesh_pave_unnamed),
        reserved = label(R.string.wesh_pave_unnamed_reserved),
        reservedWords = reservedWords,
    )

/**
 * [remoteDisplayName] for what Compose does not draw: notifications and system messages. Labels
 * and reserved words are read at each call, so a language change applies at once.
 */
fun interface RemoteNameRule {
    /** The name to show for [raw], a nickname that comes from someone else. */
    fun show(raw: String?): String

    companion object {
        fun of(context: Context): RemoteNameRule = RemoteNameRule { raw ->
            remoteDisplayName(raw, context::getString, PaveReservedWords.words(context))
        }
    }
}

/** Short key fingerprint shown after a remote name, so the nickname is never the only identity cue. */
object PaveFingerprint {
    private const val NOISE_KEY_BYTES = 32

    // 4 groups of 4 hex characters = 64 bits. A cue to tell two identical names apart, not a
    // verification (the QR exchange is); do not shorten it.
    private const val GROUPS = 4
    private const val GROUP_SIZE = 4

    /** "7F3A 91C2 0B5E 44D8": first 16 hex characters of the SHA-256 of a Noise static key, uppercase. */
    fun short(noiseKey: ByteArray?): String? {
        if (noiseKey == null || noiseKey.size != NOISE_KEY_BYTES) return null
        return ContactIdentityResolver.fingerprintHex(noiseKey)
            .take(GROUPS * GROUP_SIZE)
            .uppercase(Locale.ROOT)
            .chunked(GROUP_SIZE)
            .joinToString(" ")
    }

    /** One character at a time for TalkBack: "7 F 3 A, 9 1 C 2, ...". */
    fun spoken(short: String): String = short.split(' ').joinToString(", ") { it.toList().joinToString(" ") }
}
