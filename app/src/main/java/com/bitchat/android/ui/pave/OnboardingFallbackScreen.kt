package com.bitchat.android.ui.pave

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bitchat.android.R

/** Required system settings retain explicit actions and never complete onboarding themselves. */
@Composable
internal fun OnboardingFallbackScreen(
    modifier: Modifier,
    @StringRes title: Int,
    @StringRes body: Int,
    @StringRes primaryAction: Int?,
    onPrimaryAction: () -> Unit,
    onRetry: () -> Unit,
    isLoading: Boolean
) {
    val colors = WeshPave.colors
    Column(
        modifier.fillMaxSize().background(colors.bg).verticalScroll(rememberScrollState())
            .padding(horizontal = WeshSpace.gutter, vertical = WeshSpace.s8),
        verticalArrangement = Arrangement.spacedBy(WeshSpace.s4)
    ) {
        Icon(Icons.Rounded.Sensors, contentDescription = null, tint = colors.ink, modifier = Modifier.size(48.dp))
        Text(stringResource(title), style = WeshType.title, color = colors.ink, modifier = Modifier.semantics { heading() })
        Text(stringResource(body), style = WeshType.body, color = colors.ink)
        Spacer(Modifier.weight(1f))
        if (isLoading) {
            CircularProgressIndicator(color = colors.ink)
            Text(stringResource(R.string.wesh_onboarding_waiting), style = WeshType.body, color = colors.ink)
        }
        if (primaryAction != null) {
            PrimaryButton(stringResource(primaryAction), onPrimaryAction, enabled = !isLoading)
            SecondaryButton(stringResource(R.string.wesh_onboarding_retry), onRetry, Modifier.fillMaxWidth(), enabled = !isLoading)
        } else {
            PrimaryButton(stringResource(R.string.wesh_onboarding_retry), onRetry, enabled = !isLoading)
        }
    }
}
