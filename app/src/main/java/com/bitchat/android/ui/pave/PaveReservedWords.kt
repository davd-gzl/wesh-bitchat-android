package com.bitchat.android.ui.pave

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import com.bitchat.android.R
import java.util.Locale

/**
 * The single list of app labels a remote nickname may not imitate: every label drawn next to a
 * nickname ("Vous", "Sans surnom", badges, delivery states, transport, public room, Échanger,
 * Demandes, fingerprint, the Échanger screen and its checks, Profil). Checked in French and
 * English whatever the phone language, so a peer cannot pick the other language's word.
 * PaveReservedWordsTest fails when a label of these families is missing.
 */
object PaveReservedWords {
    @StringRes
    val keys: List<Int> = listOf(
        R.string.wesh_pave_message_from_you,
        R.string.wesh_pave_unnamed,
        R.string.wesh_pave_unnamed_reserved,
        R.string.wesh_pave_verified,
        R.string.wesh_pave_verified_short,
        R.string.wesh_pave_unverified,
        R.string.wesh_pave_no_message_yet,
        R.string.wesh_pave_contact_state_verified,
        R.string.wesh_pave_contact_state_blocked,
        R.string.wesh_pave_delivery_waiting,
        R.string.wesh_pave_delivery_sent,
        R.string.wesh_pave_delivery_delivered,
        R.string.wesh_pave_delivery_read,
        R.string.wesh_pave_delivery_failed,
        R.string.wesh_pave_failed_retry,
        R.string.wesh_pave_retry,
        R.string.wesh_pave_via_internet,
        R.string.wesh_pave_via_bluetooth,
        R.string.wesh_pave_public_room,
        R.string.wesh_pave_public_room_readable,
        R.string.wesh_pave_action_exchange,
        R.string.wesh_pave_section_requests,
        R.string.wesh_pave_fingerprint_label,
        R.string.wesh_pave_fingerprint_unavailable,
        R.string.wesh_pave_fingerprint_mine,
        R.string.wesh_pave_fingerprint_contact,
        R.string.wesh_pave_exchange_title,
        R.string.wesh_pave_exchange_intro,
        R.string.wesh_pave_exchange_my_code,
        R.string.wesh_pave_exchange_aim,
        R.string.wesh_pave_exchange_they_scanned,
        R.string.wesh_pave_exchange_they_pending,
        R.string.wesh_pave_exchange_i_scanned,
        R.string.wesh_pave_exchange_i_pending,
        R.string.wesh_pave_exchange_footer,
        R.string.wesh_pave_exchange_success,
        R.string.wesh_pave_exchange_open_chat,
        R.string.wesh_pave_step_done,
        R.string.wesh_pave_step_pending,
        R.string.wesh_pave_settings_profile,
    )

    private val locales = listOf(Locale.FRENCH, Locale.ENGLISH)

    /** Every reserved label in the current language, French and English. */
    fun words(context: Context): Set<String> {
        val contexts = listOf(context) + locales.map { locale ->
            context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(locale) })
        }
        return words(contexts) { getString(it) }
    }

    /** Every reserved label read from each of [sources]: one per language. */
    internal fun <T> words(sources: Iterable<T>, getString: T.(Int) -> String): Set<String> =
        sources.flatMapTo(LinkedHashSet()) { source -> keys.map { source.getString(it) } }
}
