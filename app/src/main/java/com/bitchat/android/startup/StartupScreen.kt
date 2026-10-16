package com.bitchat.android.startup

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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun StartupScreen(failed: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier, draftWipeRequired: Boolean = false, recovery: InterruptedPanicRecovery.State = InterruptedPanicRecovery.State.Idle, onEraseAll: () -> Unit = {}) {
    var confirmErase by remember { mutableStateOf(false) }
    if (confirmErase) AlertDialog(
        onDismissRequest = { confirmErase = false },
        title = { Text("Effacer toutes les données de Wesh ?", Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState())) },
        text = { Text("Votre identité, vos messages, vos brouillons et vos réglages seront perdus. Vous devrez autoriser à nouveau les permissions après la fermeture de Wesh.", Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { confirmErase = false; onEraseAll() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Tout effacer") } },
        dismissButton = { TextButton(onClick = { confirmErase = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Annuler") } }
    )
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp).semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Wesh", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
        if (failed) {
            Text(if (draftWipeRequired) "Un effacement est inachevé ou ne peut pas être vérifié. Wesh reste verrouillé pour protéger vos données. Vous pouvez demander à Android d’effacer toutes les données de Wesh." else "Votre identité est momentanément indisponible. Réessayez sans effacer les données de l’application.")
            if (recovery == InterruptedPanicRecovery.State.Failed) Text("La demande d’effacement a échoué. Wesh reste verrouillé. Réessayez ou utilisez les réglages Android de l’application.")
            if (recovery == InterruptedPanicRecovery.State.Requested) Text("Demande transmise à Android. Wesh va se fermer ; l’effacement n’est pas encore confirmé.")
            Button(onClick = { if (draftWipeRequired) confirmErase = true else onRetry() }, enabled = recovery != InterruptedPanicRecovery.State.Running && recovery != InterruptedPanicRecovery.State.Requested,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (draftWipeRequired) "Effacer toutes les données de Wesh" else "Réessayer")
            }
        } else {
            CircularProgressIndicator()
            Text("Préparation de votre espace sécurisé…")
        }
    }
}
