package com.bitchat.android.startup

import com.bitchat.android.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun StartupScreen(failed: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier, draftWipeRequired: Boolean = false, recovery: InterruptedPanicRecovery.State = InterruptedPanicRecovery.State.Idle, onEraseAll: () -> Unit = {}, offerIdentityReset: Boolean = false, onResetIdentity: () -> Unit = {}) {
    var confirmErase by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text(stringResource(R.string.wesh_startup_reset_title), Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState())) },
        text = { Text(stringResource(R.string.wesh_startup_reset_body), Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { confirmReset = false; onResetIdentity() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.wesh_startup_reset_confirm)) } },
        dismissButton = { TextButton(onClick = { confirmReset = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.wesh_startup_cancel)) } }
    )
    if (confirmErase) AlertDialog(
        onDismissRequest = { confirmErase = false },
        title = { Text(stringResource(R.string.wesh_startup_erase_title), Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState())) },
        text = { Text(stringResource(R.string.wesh_startup_erase_body), Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { confirmErase = false; onEraseAll() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.wesh_startup_erase_confirm)) } },
        dismissButton = { TextButton(onClick = { confirmErase = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.wesh_startup_cancel)) } }
    )
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp).semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Wesh", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
        if (failed) {
            Text(if (draftWipeRequired) stringResource(R.string.wesh_startup_erase_locked) else stringResource(R.string.wesh_startup_identity_unavailable))
            if (recovery == InterruptedPanicRecovery.State.Failed) Text(stringResource(R.string.wesh_startup_erase_failed))
            if (recovery == InterruptedPanicRecovery.State.Requested) Text(stringResource(R.string.wesh_startup_erase_requested))
            Button(onClick = { if (draftWipeRequired) confirmErase = true else onRetry() }, enabled = recovery != InterruptedPanicRecovery.State.Running && recovery != InterruptedPanicRecovery.State.Requested,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (draftWipeRequired) stringResource(R.string.wesh_startup_erase_all) else stringResource(R.string.wesh_startup_retry))
            }
            if (offerIdentityReset && !draftWipeRequired) {
                Text(stringResource(R.string.wesh_startup_reset_offer))
                TextButton(onClick = { confirmReset = true }, enabled = recovery != InterruptedPanicRecovery.State.Running && recovery != InterruptedPanicRecovery.State.Requested,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.wesh_startup_reset_action)) }
            }
        } else {
            CircularProgressIndicator()
            Text(stringResource(R.string.wesh_startup_preparing))
        }
    }
}
