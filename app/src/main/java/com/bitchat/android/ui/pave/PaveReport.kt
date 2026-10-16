package com.bitchat.android.ui.pave

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.bitchat.android.R
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.services.WeshMessageRequests
import com.bitchat.android.ui.WeshLinks

/** Reasons offered when the report is sent to the Wesh team. */
enum class PaveReportReason(@StringRes val label: Int) {
    Harassment(R.string.wesh_report_reason_harassment),
    Illegal(R.string.wesh_report_reason_illegal),
    Spam(R.string.wesh_report_reason_spam),
    Impersonation(R.string.wesh_report_reason_impersonation),
    Other(R.string.wesh_report_reason_other),
}

/** Where the person was met. Only a public room message carries text: everyone around could read it. */
sealed interface PaveReportSource {
    data class PublicMessage(val text: String) : PaveReportSource
    data object Request : PaveReportSource
    data object Contact : PaveReportSource
}

/** Who is reported: the nickname as received (shown only through the remote name rule) and the short fingerprint. */
data class PaveReportTarget(val rawNickname: String?, val fingerprint: String?, val source: PaveReportSource)

/** A request is reported by its nickname and fingerprint only. */
internal fun paveReportTarget(request: WeshMessageRequests.Request) =
    PaveReportTarget(request.nickname, paveShortFingerprintFromHex(request.fingerprint), PaveReportSource.Request)

/**
 * The public message an action targets, from the rows actually rendered. Message IDs are chosen by
 * the sender, so a row is used only when exactly one rendered row has [id]: a reused ID can neither
 * redirect the block nor quote someone else's text.
 */
internal fun paveReportedPublicMessage(
    rendered: List<BitchatMessage>,
    id: String,
    fingerprint: (String?) -> String?,
): Pair<String, PaveReportTarget>? {
    val message = rendered.singleOrNull { it.id == id } ?: return null
    val peerID = message.senderPeerID ?: return null
    return peerID to PaveReportTarget(message.sender, fingerprint(peerID), PaveReportSource.PublicMessage(message.content))
}

/**
 * The local block always runs first, on its own; sending the report is then only offered, whether
 * the block succeeded or not. Nothing is sent by the app.
 */
internal inline fun paveBlockThenOfferReport(target: PaveReportTarget, block: () -> Boolean): Pair<Boolean, PaveReportTarget> =
    block() to target

/**
 * The e-mail draft handed to the user's mail app, addressed to [WeshLinks.REPORT_EMAIL]. It never
 * holds private message content, the user's keys or a location: only what [PaveReportTarget] carries.
 */
internal data class PaveReportMail(val address: String, val subject: String, val body: String, val uri: String) {
    companion object {
        const val SUBJECT = "Signalement Wesh"
        /** Separates the fields written by the app from the quoted text written by someone else. */
        const val QUOTE_DELIMITER = "-----"
        private const val MAX_PUBLIC_TEXT = 1_000

        /** [text] reads a string in the phone language, like Context.getString(id, args). */
        fun compose(
            target: PaveReportTarget,
            reason: PaveReportReason,
            rule: RemoteNameRule,
            text: (Int, Array<out Any>) -> String,
        ): PaveReportMail {
            fun t(id: Int, vararg args: Any) = text(id, args)
            val where = when (target.source) {
                is PaveReportSource.PublicMessage -> R.string.wesh_report_where_public
                PaveReportSource.Request -> R.string.wesh_report_where_request
                PaveReportSource.Contact -> R.string.wesh_report_where_contact
            }
            val lines = mutableListOf(
                t(R.string.wesh_report_mail_intro),
                "",
                t(R.string.wesh_report_mail_nickname, rule.show(target.rawNickname)),
                t(R.string.wesh_report_mail_fingerprint, target.fingerprint ?: t(R.string.wesh_pave_fingerprint_unavailable)),
                t(R.string.wesh_report_mail_reason, t(reason.label)),
                t(R.string.wesh_report_mail_where, t(where)),
            )
            // Last, after a delimiter, every line quoted: the text cannot pass for a field of the report.
            (target.source as? PaveReportSource.PublicMessage)?.let { message ->
                lines += listOf("", QUOTE_DELIMITER, t(R.string.wesh_report_mail_public_message), quote(message.text))
            }
            val body = lines.joinToString("\n")
            val uri = "mailto:" + WeshLinks.REPORT_EMAIL +
                "?subject=" + encode(SUBJECT) + "&body=" + encode(body.replace("\n", "\r\n"))
            return PaveReportMail(WeshLinks.REPORT_EMAIL, SUBJECT, body, uri)
        }

        /**
         * Someone else's text, made inert: line breaks unified, invisible and steering characters
         * (bidi, zero-width, controls) removed, capped, then every line prefixed with "> ".
         */
        internal fun quote(text: String): String {
            val unified = text.replace("\r\n", "\n").replace('\r', '\n').replace('\u2028', '\n').replace('\u2029', '\n')
            val cleaned = buildString {
                unified.codePoints().forEach { cp -> if (cp == '\n'.code || !PaveDisplayName.isInvisible(cp)) appendCodePoint(cp) }
            }
            val capped = if (cleaned.codePointCount(0, cleaned.length) > MAX_PUBLIC_TEXT) {
                cleaned.substring(0, cleaned.offsetByCodePoints(0, MAX_PUBLIC_TEXT)) + "…"
            } else cleaned
            return capped.split('\n').joinToString("\n") { "> $it" }
        }

        /** RFC 6068: every byte outside the unreserved set is percent-encoded, spaces included. */
        internal fun encode(value: String): String = buildString {
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val c = byte.toInt() and 0xFF
                if (c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code || c.toChar() in "-._~") {
                    append(c.toChar())
                } else append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 0x0F])
            }
        }
    }
}

/** Opens the mail app on the draft; false when the phone has none. The user reads, edits and sends it. */
internal fun paveOpenReportMail(context: Context, mail: PaveReportMail): Boolean {
    val intent = Intent(Intent.ACTION_SENDTO, mail.uri.toUri()).apply {
        putExtra(Intent.EXTRA_EMAIL, arrayOf(mail.address))
        putExtra(Intent.EXTRA_SUBJECT, mail.subject)
        putExtra(Intent.EXTRA_TEXT, mail.body)
    }
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/**
 * After the local block: offers to send the report to the Wesh team by e-mail, with a reason. If no
 * mail app is installed, shows the address and lets the user copy it.
 */
@Composable
internal fun PaveReportSendDialog(target: PaveReportTarget, onFeedback: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val c = WeshPave.colors
    var reason by remember(target) { mutableStateOf<PaveReportReason?>(null) }
    var noMailApp by remember(target) { mutableStateOf(false) }
    val copied = stringResource(R.string.wesh_report_address_copied)

    if (noMailApp) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.wesh_report_no_mail_title), style = WeshType.headline) },
            text = { Text(stringResource(R.string.wesh_report_no_mail_body, WeshLinks.REPORT_EMAIL), style = WeshType.body) },
            confirmButton = {
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText(null, WeshLinks.REPORT_EMAIL))
                    onFeedback(copied)
                    onDismiss()
                }) { Text(stringResource(R.string.wesh_report_copy_address), style = WeshType.label) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.wesh_pave_close), style = WeshType.label) } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.wesh_report_send_title), style = WeshType.headline) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(WeshSpace.s2)) {
                Text(stringResource(R.string.wesh_report_send_body), style = WeshType.body)
                Text(stringResource(R.string.wesh_report_reason_label), style = WeshType.label, color = c.inkMuted)
                PaveReportReason.entries.forEach { option ->
                    val selected = option == reason
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = WeshTouch.primary)
                            .selectable(selected = selected, role = Role.RadioButton, onClick = { reason = option }),
                    ) {
                        Text(stringResource(option.label), style = WeshType.body, color = c.ink, modifier = Modifier.weight(1f))
                        Box(
                            Modifier
                                .size(24.dp)
                                .border(WeshStroke.s2, if (selected) c.ink else c.lineStrong, CircleShape)
                                .padding(5.dp)
                                .background(if (selected) c.ink else c.bg, CircleShape),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = reason != null,
                onClick = {
                    val picked = reason ?: return@TextButton
                    val mail = PaveReportMail.compose(target, picked, RemoteNameRule.of(context)) { id, args -> resources.getString(id, *args) }
                    if (paveOpenReportMail(context, mail)) onDismiss() else noMailApp = true
                },
            ) { Text(stringResource(R.string.wesh_report_send_action), style = WeshType.label) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.wesh_pave_close), style = WeshType.label) } },
    )
}
