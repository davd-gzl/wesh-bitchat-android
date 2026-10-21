package com.bitchat.android.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bitchat.android.R
import com.bitchat.android.ui.pave.OnboardingFallbackScreen
import com.bitchat.android.ui.pave.PavePreview
import com.bitchat.android.ui.pave.PaveScreenPreviews

/** Only legacy Android needs this system switch for BLE scanning; no coordinates are requested. */
@Composable
fun LocationCheckScreen(
    modifier: Modifier,
    status: LocationStatus,
    onEnableLocation: () -> Unit,
    onRetry: () -> Unit,
    isLoading: Boolean = false
) {
    val title = when (status) {
        LocationStatus.DISABLED -> R.string.wesh_onboarding_legacy_location_title
        LocationStatus.NOT_AVAILABLE -> R.string.wesh_onboarding_legacy_location_unavailable_title
        LocationStatus.ENABLED -> R.string.wesh_onboarding_legacy_location_on_title
    }
    val body = when (status) {
        LocationStatus.DISABLED -> R.string.wesh_onboarding_legacy_location_body
        LocationStatus.NOT_AVAILABLE -> R.string.wesh_onboarding_legacy_location_unavailable_body
        LocationStatus.ENABLED -> R.string.wesh_onboarding_legacy_location_on_body
    }
    OnboardingFallbackScreen(
        modifier = modifier,
        title = title,
        body = body,
        primaryAction = if (status == LocationStatus.DISABLED) R.string.wesh_onboarding_open_android_settings else null,
        onPrimaryAction = onEnableLocation,
        onRetry = onRetry,
        isLoading = isLoading
    )
}

@PaveScreenPreviews
@Composable
private fun LegacyBluetoothScanningPreview() = PavePreview(padded = false) {
    LocationCheckScreen(Modifier, LocationStatus.DISABLED, {}, {})
}
