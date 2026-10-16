package com.bitchat.android.ui.pave

import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role

val LocalWeshColors = staticCompositionLocalOf { WeshTokens.Nuit }

/** False when the person turned animations off (Android "Remove animations"). */
val LocalWeshAnimationsEnabled = staticCompositionLocalOf { true }

object WeshPave {
    val colors: WeshColors
        @Composable @ReadOnlyComposable get() = LocalWeshColors.current

    val animationsEnabled: Boolean
        @Composable @ReadOnlyComposable get() = LocalWeshAnimationsEnabled.current

    @Composable
    @ReadOnlyComposable
    fun duration(baseMs: Int): Int = WeshMotion.duration(baseMs, LocalWeshAnimationsEnabled.current)
}

/** Appearance setting: Nuit by default, Jour, or follow the system. */
enum class WeshAppearance { Nuit, Jour, Automatique }

@Composable
fun WeshAppearance.resolve(): WeshThemeId = when (this) {
    WeshAppearance.Nuit -> WeshThemeId.Nuit
    WeshAppearance.Jour -> WeshThemeId.Jour
    WeshAppearance.Automatique -> if (isSystemInDarkTheme()) WeshThemeId.Nuit else WeshThemeId.Jour
}

internal fun weshColorScheme(c: WeshColors): ColorScheme {
    val base = if (c.isNight) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c.privateColor, onPrimary = c.onPrivate,
        primaryContainer = c.surfaceRaised, onPrimaryContainer = c.ink,
        secondary = c.publicColor, onSecondary = c.onPublic,
        secondaryContainer = c.surfaceRaised, onSecondaryContainer = c.ink,
        tertiary = c.internet, onTertiary = c.onInternet,
        background = c.bg, onBackground = c.ink,
        surface = c.bg, onSurface = c.ink,
        surfaceVariant = c.surfaceRaised, onSurfaceVariant = c.inkMuted,
        surfaceContainerLowest = c.bg, surfaceContainerLow = c.surface,
        surfaceContainer = c.surface, surfaceContainerHigh = c.surfaceRaised,
        surfaceContainerHighest = c.surfaceRaised,
        inverseSurface = c.ink, inverseOnSurface = c.bg,
        outline = c.lineStrong, outlineVariant = c.line,
        error = c.danger, onError = c.onDanger,
        errorContainer = c.surfaceRaised, onErrorContainer = c.danger,
        scrim = c.bg,
    )
}

internal val WeshTypography = Typography(
    displaySmall = WeshType.wordmark,
    headlineMedium = WeshType.title,
    headlineSmall = WeshType.title,
    titleLarge = WeshType.headline,
    titleMedium = WeshType.label,
    bodyLarge = WeshType.body,
    bodyMedium = WeshType.body,
    bodySmall = WeshType.caption,
    labelLarge = WeshType.label,
    labelMedium = WeshType.caption,
    labelSmall = WeshType.caption,
)

/** Pavé theme for every new Wesh screen. Nuit is the default. */
@Composable
fun WeshPaveTheme(
    theme: WeshThemeId = WeshThemeId.Nuit,
    content: @Composable () -> Unit,
) {
    val colors = WeshTokens.colors(theme)
    val context = LocalContext.current
    val inspection = LocalInspectionMode.current
    val animationsEnabled = remember(context, inspection) { inspection || systemAnimationsEnabled(context) }
    CompositionLocalProvider(
        LocalWeshColors provides colors,
        LocalWeshAnimationsEnabled provides animationsEnabled,
    ) {
        MaterialTheme(
            colorScheme = weshColorScheme(colors),
            typography = WeshTypography,
            content = content,
        )
    }
}

internal fun systemAnimationsEnabled(context: Context): Boolean =
    runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }.getOrDefault(true)

/** True while TalkBack (or another touch-exploration service) is on. */
internal fun touchExplorationEnabled(context: Context): Boolean =
    (context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager)?.isTouchExplorationEnabled == true

/**
 * Haptics that carry meaning (design system, "Mouvement et haptique"):
 * send = light, scan success = success, failure = error,
 * erase hold = rising pulses then a heavy final one. No haptic on foreground reception.
 */
class WeshHaptics(private val feedback: HapticFeedback) {
    fun send() = feedback.performHapticFeedback(HapticFeedbackType.KeyboardTap)
    fun success() = feedback.performHapticFeedback(HapticFeedbackType.Confirm)
    fun error() = feedback.performHapticFeedback(HapticFeedbackType.Reject)
    fun holdPulse(strong: Boolean) = feedback.performHapticFeedback(
        if (strong) HapticFeedbackType.SegmentTick else HapticFeedbackType.SegmentFrequentTick
    )
    fun heavy() = feedback.performHapticFeedback(HapticFeedbackType.LongPress)
}

@Composable
fun rememberWeshHaptics(): WeshHaptics {
    val feedback = LocalHapticFeedback.current
    return remember(feedback) { WeshHaptics(feedback) }
}

/**
 * Clickable with the Pavé focus ring (3 dp `focus`) for keyboard, switch access and TalkBack focus.
 * [label] is what TalkBack announces as the action ("Double-tap to ...").
 */
fun Modifier.paveClickable(
    shape: Shape,
    label: String? = null,
    role: Role = Role.Button,
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val ring = if (focused) Modifier.border(WeshStroke.focus, WeshPave.colors.focus, shape) else Modifier
    this
        .then(ring)
        .clip(shape)
        .clickable(
            interactionSource = interaction,
            indication = LocalIndication.current,
            enabled = enabled,
            onClickLabel = label,
            role = role,
            onClick = onClick,
        )
}
