package com.bitchat.android.ui.pave

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.bitchat.android.R
import com.bitchat.android.services.ContactIdentityResolver
import java.util.Locale

/** Short key fingerprint shown next to a name, so the nickname is never the only identity cue. */
object PaveFingerprint {
    private const val NOISE_KEY_BYTES = 32
    private const val GROUPS = 4
    private const val GROUP_SIZE = 4

    /** "7F3A 91C2 0B5E 44D8": first four groups of the SHA-256 fingerprint of a Noise static key. */
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

/**
 * A remote nickname ready to display: sanitized, middle-elided, and masked when it imitates the
 * app's own words. Every Pavé component that shows someone else's name goes through this.
 */
@Composable
fun remoteDisplayName(raw: String?, @StringRes fallback: Int = R.string.wesh_pave_unnamed): String {
    val fallbackText = stringResource(fallback)
    val reserved = listOf(
        fallbackText,
        stringResource(R.string.wesh_pave_unnamed),
        stringResource(R.string.wesh_pave_message_from_you),
        stringResource(R.string.wesh_pave_exchange_contact_fallback),
        stringResource(R.string.wesh_pave_verified),
        stringResource(R.string.wesh_pave_unverified),
    )
    val template = stringResource(R.string.wesh_pave_name_masked, "\u0000")
    return PaveDisplayName.remote(raw, fallbackText, reserved) { template.replace("\u0000", it) }
}
