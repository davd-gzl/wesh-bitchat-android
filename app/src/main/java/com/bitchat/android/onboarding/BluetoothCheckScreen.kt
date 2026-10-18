package com.bitchat.android.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bitchat.android.R
import com.bitchat.android.ui.pave.OnboardingFallbackScreen
import com.bitchat.android.ui.pave.PavePreview
import com.bitchat.android.ui.pave.PaveScreenPreviews

/** The required Bluetooth setting has no path around it. */
@Composable
fun BluetoothCheckScreen(
    modifier: Modifier,
    status: BluetoothStatus,
    onEnableBluetooth: () -> Unit,
    onRetry: () -> Unit,
    isLoading: Boolean = false
) {
    val title = when (status) {
        BluetoothStatus.DISABLED -> R.string.wesh_onboarding_bluetooth_off_title
        BluetoothStatus.NOT_SUPPORTED -> R.string.wesh_onboarding_bluetooth_unavailable_title
        BluetoothStatus.ENABLED -> R.string.wesh_onboarding_bluetooth_on_title
    }
    val body = when (status) {
        BluetoothStatus.DISABLED -> R.string.wesh_onboarding_bluetooth_off_body
        BluetoothStatus.NOT_SUPPORTED -> R.string.wesh_onboarding_bluetooth_unavailable_body
        BluetoothStatus.ENABLED -> R.string.wesh_onboarding_bluetooth_on_body
    }
    OnboardingFallbackScreen(
        modifier = modifier,
        title = title,
        body = body,
        primaryAction = if (status == BluetoothStatus.DISABLED) R.string.wesh_onboarding_enable_bluetooth else null,
        onPrimaryAction = onEnableBluetooth,
        onRetry = onRetry,
        isLoading = isLoading
    )
}

@PaveScreenPreviews
@Composable
private fun BluetoothOffPreview() = PavePreview(padded = false) {
    BluetoothCheckScreen(Modifier, BluetoothStatus.DISABLED, {}, {})
}

@PaveScreenPreviews
@Composable
private fun BluetoothUnavailablePreview() = PavePreview(padded = false) {
    BluetoothCheckScreen(Modifier, BluetoothStatus.NOT_SUPPORTED, {}, {})
}
