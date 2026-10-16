package com.bitchat.android.ui.pave

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.bitchat.android.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Transposition of `docs/plan/design/system/tokens.json` ("Wesh Pavé Fluo", v2).
 * No colour, size or duration used by the Pavé UI lives anywhere else.
 */
@Immutable
data class WeshColors(
    val bg: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val ink: Color,
    val inkMuted: Color,
    val line: Color,
    val lineStrong: Color,
    val publicColor: Color,
    val onPublic: Color,
    val privateColor: Color,
    val onPrivate: Color,
    val internet: Color,
    val onInternet: Color,
    val danger: Color,
    val onDanger: Color,
    val success: Color,
    val warning: Color,
    val focus: Color,
    val isNight: Boolean,
) {
    val deliveryWaiting: Color get() = inkMuted
    val deliverySent: Color get() = ink
    val deliveryReceived: Color get() = success
    val deliveryFailed: Color get() = danger
}

enum class WeshThemeId { Nuit, Jour }

object WeshTokens {
    val Nuit = WeshColors(
        bg = Color(0xFF0B0B0A),
        surface = Color(0xFF171716),
        surfaceRaised = Color(0xFF242422),
        ink = Color(0xFFF4F3EE),
        inkMuted = Color(0xFFA6A59F),
        line = Color(0xFF3A3A37),
        lineStrong = Color(0xFF8B8A84),
        publicColor = Color(0xFFE4FF1A),
        onPublic = Color(0xFF0B0B0A),
        privateColor = Color(0xFFF4F3EE),
        onPrivate = Color(0xFF0B0B0A),
        internet = Color(0xFF86B6FF),
        onInternet = Color(0xFF0B0B0A),
        danger = Color(0xFFFF5640),
        onDanger = Color(0xFF0B0B0A),
        success = Color(0xFF7CE0A3),
        warning = Color(0xFFFFB547),
        focus = Color(0xFFE4FF1A),
        isNight = true,
    )

    val Jour = WeshColors(
        bg = Color(0xFFEFEDE4),
        surface = Color(0xFFFFFFFF),
        surfaceRaised = Color(0xFFE3E0D5),
        ink = Color(0xFF0B0B0A),
        inkMuted = Color(0xFF54534D),
        line = Color(0xFFCAC7BC),
        lineStrong = Color(0xFF6B6A63),
        publicColor = Color(0xFFE4FF1A),
        onPublic = Color(0xFF0B0B0A),
        privateColor = Color(0xFF0B0B0A),
        onPrivate = Color(0xFFF4F3EE),
        internet = Color(0xFF1D4ED8),
        onInternet = Color(0xFFFFFFFF),
        danger = Color(0xFFC4302B),
        onDanger = Color(0xFFFFFFFF),
        success = Color(0xFF1F7A47),
        warning = Color(0xFF8A5A00),
        focus = Color(0xFF0B0B0A),
        isNight = false,
    )

    fun colors(theme: WeshThemeId): WeshColors = if (theme == WeshThemeId.Nuit) Nuit else Jour

    /** QR modules stay dark on light in both themes so every scanner reads them. */
    val QrModule = Color(0xFF0B0B0A)
    val QrBackground = Color(0xFFFFFFFF)
}

object WeshSpace {
    val s1 = 4.dp
    val s2 = 8.dp
    val s3 = 12.dp
    val s4 = 16.dp
    val s6 = 24.dp
    val s8 = 32.dp
    val s12 = 48.dp
    val gutter = s4
}

object WeshRadius {
    val control = 12.dp
    val bubble = 18.dp
    val tail = 4.dp
    val sheet = 24.dp
}

object WeshStroke {
    val s1 = 1.dp
    val s2 = 2.dp
    val focus = 3.dp
    /** Top rule of the public composer. */
    val publicRule = 3.dp
}

object WeshTouch {
    val min = 48.dp
    val primary = 56.dp
    /** InboxRow minimum height. */
    val row = 64.dp
}

object WeshMotion {
    const val STATE_CHANGE_MS = 120
    const val SHEET_MS = 200

    /** One pavé of the reach gauge lights per step; the whole wave stays under [MESH_MAX_MS]. */
    const val MESH_RING_MS = 120
    const val MESH_MAX_MS = 900

    /** Relayed message: slide, then the "relayé par N" stamp. */
    const val RELAY_SLIDE_MS = 240
    const val RELAY_STAMP_MS = 180

    /** QR success: the viewfinder closes, then the VÉRIFIÉ stamp lands. */
    const val QR_LOCK_MS = 250
    const val QR_STAMP_MS = 350

    /** Paint-over after a real wipe. */
    const val ERASE_COVER_MS = 400

    /** "Remove animations" (animator scale 0) turns every duration into an instant change. */
    fun duration(baseMs: Int, animationsEnabled: Boolean): Int = if (animationsEnabled) baseMs else 0
}

/** Type scale from tokens.json, in sp so it follows the system font size. */
object WeshType {
    private val text = FontFamily.Default

    /**
     * Poster face: Archivo ExtraCondensed Black (static OFL instance, bundled, never fetched).
     * Display styles only, always in capitals; running text stays in the system face.
     */
    val Display = FontFamily(Font(R.font.archivo_extracondensed_black, weight = FontWeight.Black))

    val wordmark = TextStyle(fontFamily = Display, fontSize = 56.sp, lineHeight = 48.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp)
    /** The one giant figure of a screen (phones in reach). */
    val numeral = TextStyle(fontFamily = Display, fontSize = 96.sp, lineHeight = 76.sp, fontWeight = FontWeight.Black, letterSpacing = (-2).sp)
    val display = TextStyle(fontFamily = Display, fontSize = 44.sp, lineHeight = 40.sp, fontWeight = FontWeight.Black)
    val displaySmall = TextStyle(fontFamily = Display, fontSize = 26.sp, lineHeight = 26.sp, fontWeight = FontWeight.Black)
    val title = TextStyle(fontFamily = text, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold)
    val headline = TextStyle(fontFamily = text, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold)
    val body = TextStyle(fontFamily = text, fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal)
    val label = TextStyle(fontFamily = text, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
    val caption = TextStyle(fontFamily = text, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
    val fingerprint = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp)
}
