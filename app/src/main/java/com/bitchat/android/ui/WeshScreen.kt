package com.bitchat.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.ContactIdentityResolver
import com.bitchat.android.services.VerificationService
import com.bitchat.android.util.dataFromHexString
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat

@Composable
fun WeshTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = Color(0xFFCBD1D9), onPrimary = Color.Black,
        primaryContainer = Color(0xFF272A30), onPrimaryContainer = Color.White,
        background = Color.Black, onBackground = Color.White,
        surface = Color.Black, onSurface = Color.White,
        surfaceVariant = Color.Black, onSurfaceVariant = Color(0xFFB4B4B4),
        surfaceContainer = Color.Black, surfaceContainerHigh = Color.Black,
        surfaceContainerLow = Color.Black, surfaceContainerLowest = Color.Black, surfaceContainerHighest = Color.Black,
        outline = Color(0xFF747B85), surfaceTint = Color.Black,
        secondary = Color(0xFFCBD1D9), onSecondary = Color.Black,
        secondaryContainer = Color(0xFF272A30), onSecondaryContainer = Color.White,
        tertiary = Color(0xFFCBD1D9), onTertiary = Color.Black,
        tertiaryContainer = Color(0xFF272A30), onTertiaryContainer = Color.White
    )
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}

/** Native messenger surface. All messages and contacts come from the existing service state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeshScreen(viewModel: ChatViewModel) {
    val context = LocalContext.current
    val prefs = remember(context) { WeshPreferences(context) }
    val scope = rememberCoroutineScope()
    val publicMessages by viewModel.messages.collectAsState()
    val privateChats by viewModel.privateChats.collectAsState()
    val peers by viewModel.connectedPeers.collectAsState()
    val names by viewModel.peerNicknames.collectAsState()
    val contacts by viewModel.weshContacts.collectAsState()
    val drafts by viewModel.weshDraftStore.drafts.collectAsState()
    val draftPersistence by viewModel.weshDraftStore.persistence.collectAsState()
    val panicRecoveryRequired by com.bitchat.android.startup.WeshRuntime.draftWipeRequired.collectAsState()
    val panicInProgress by viewModel.weshPanicInProgress.collectAsState()
    val panicRecovery by com.bitchat.android.startup.WeshRuntime.recoveryState.collectAsState()
    if (panicRecoveryRequired && !panicInProgress) {
        com.bitchat.android.startup.StartupScreen(
            failed = true, onRetry = {}, draftWipeRequired = true, recovery = panicRecovery,
            onEraseAll = { com.bitchat.android.startup.WeshRuntime.clearInterruptedPanic(context) }
        )
        return
    }
    val requestedContactSheet by viewModel.weshContactSheet.collectAsState()
    val conversations by viewModel.conversations.collectAsState()
    val nickname by viewModel.nickname.collectAsState()
    val selectedPrivate by viewModel.selectedPrivateChatPeer.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var publicOpen by rememberSaveable { mutableStateOf(false) }
    var contactOpen by rememberSaveable { mutableStateOf(false) }
    val destination = if (publicOpen) "public" else viewModel.weshDraftDestination(selectedPrivate)
    val draft = destination?.let(drafts::get).orEmpty()
    var noteOpen by remember { mutableStateOf(false) }
    var contactAction by remember { mutableStateOf<Pair<String, String>?>(null) }
    var publicRequest by remember { mutableStateOf<WeshMessagePolicy.PublicSendRequest?>(null) }
    var publicCapture by remember { mutableStateOf<WeshDraftStore.Capture?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var muted by remember { mutableStateOf(prefs.publicMuted) }
    var meeting by rememberSaveable { mutableStateOf(prefs.meetingNote) }
    var editedNickname by rememberSaveable(nickname) { mutableStateOf(nickname) }
    var revision by remember { mutableIntStateOf(0) }
    // Session invalidation must disable sending even when a persisted contact does not change.
    LaunchedEffect(viewModel) { while (true) { delay(1000); revision++ } }
    LaunchedEffect(selectedPrivate) { if (selectedPrivate != null) publicOpen = false }
    LaunchedEffect(requestedContactSheet) { if (requestedContactSheet) contactOpen = true }
    val privatePeer = selectedPrivate
    val inConversation = publicOpen || privatePeer != null
    val bluetoothReady = revision >= 0 && viewModel.weshBluetoothReady()
    val canSend = if (publicOpen) bluetoothReady else privatePeer != null && revision >= 0 && viewModel.weshCanSendPrivate(privatePeer)
    fun back() { publicOpen = false; viewModel.endPrivateChat(); feedback = null }
    fun dismissContact() { contactOpen = false; viewModel.weshContactSheet.value = false; viewModel.weshIncomingQR.value = null }
    BackHandler(inConversation || contactOpen) { if (contactOpen) dismissContact() else back() }
    fun send() {
        if (viewModel.selectedPrivateChatPeer.value != privatePeer) {
            feedback = "La destination a changé. Votre texte est conservé."; return
        }
        val capture = destination?.let(viewModel.weshDraftStore::capture)
        if (capture == null || capture.text != draft) { feedback = "Le brouillon a changé. Relisez votre texte."; return }
        val sentDraft = capture.text
        if (publicOpen) {
            if (!prefs.publicDisclosureAccepted) {
                publicCapture = capture
                publicRequest = WeshMessagePolicy.PublicSendRequest(sentDraft)
            }
            else if (viewModel.sendWeshPublic(sentDraft)) viewModel.weshDraftStore.clearIfUnchanged(capture) else feedback = "Message vide ou trop long (2 000 octets maximum)."
        } else if (privatePeer != null) {
            viewModel.sendWeshPrivate(sentDraft, privatePeer) { accepted ->
                if (accepted) viewModel.weshDraftStore.clearIfUnchanged(capture) else feedback = "Envoi indisponible. Vérifiez l’identité et la connexion Bluetooth."
            }
        }
    }
    Scaffold(
        topBar = {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                if (inConversation) TextButton(onClick = { back() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("‹ Discussions") }
                if (privatePeer != null) Row { TextButton(onClick = { contactAction = "block" to privatePeer }) { Text("Bloquer") }; TextButton(onClick = { contactAction = "remove" to privatePeer }) { Text("Retirer le contact") } }
                Text(if (publicOpen) "Salon local" else if (privatePeer != null) conversations.firstOrNull { it.conversationID == privatePeer }?.displayName ?: "Contact" else listOf("Discussions", "Réglages")[tab], style = MaterialTheme.typography.headlineMedium)
                Text(if (publicOpen) if (bluetoothReady) "Public · Bluetooth" else "Public · Bluetooth indisponible" else if (privatePeer != null) if (canSend) "Identité vérifiée sur ce téléphone" else "Vérification ou reconnexion nécessaire" else "Wesh", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        bottomBar = {
            if (!inConversation) NavigationBar(containerColor = Color.Black, tonalElevation = 0.dp) {
                listOf("Discussions", "Réglages").forEachIndexed { index, label ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Text(listOf("◌", "⚙")[index]) }, label = { Text(label) })
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (inConversation) {
                val messages = if (publicOpen) publicMessages.filter { !it.isPrivate && it.channel == null && (it.senderPeerID == null || !viewModel.weshIsBlocked(it.senderPeerID)) }
                else privateChats[privatePeer].orEmpty()
                if (publicOpen) Text("Aucune confirmation individuelle de réception. Toute personne sur le mesh Bluetooth peut lire et conserver ces messages. Les pseudonymes ne prouvent pas une identité.", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (messages.isEmpty()) item { Text(if (publicOpen) "Le salon est calme. Dites bonjour aux personnes autour de vous." else "Votre conversation privée commence ici.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(messages, key = { it.id }) { message ->
                        WeshMessage(message, viewModel.meshService.myPeerID, publicOpen,
                            canRetry = !publicOpen && canSend && (message.deliveryStatus is com.bitchat.android.model.DeliveryStatus.Failed || message.deliveryStatus is com.bitchat.android.model.DeliveryStatus.Sending),
                            onRetry = { privatePeer?.let { viewModel.retryWeshPrivate(it, message.id) } },
                            onBlock = { message.senderPeerID?.let { contactAction = "block" to it } })
                    }
                }
                feedback?.let { Text(it, Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.error) }
                if (!canSend) Text(if (publicOpen) "Activez Bluetooth dans les réglages du téléphone pour envoyer dans le salon." else "Scannez le QR de ce contact et attendez la confirmation. Un changement de clé exige une nouvelle vérification.", Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium)
                Text(when (draftPersistence) {
                    WeshDraftStore.Persistence.Loading -> "Chargement des brouillons… Votre saisie n’est pas encore confirmée sur ce téléphone."
                    WeshDraftStore.Persistence.Unconfirmed -> "Brouillon en mémoire · enregistrement non confirmé."
                    WeshDraftStore.Persistence.Confirmed -> "Brouillon enregistré sur ce téléphone."
                    WeshDraftStore.Persistence.Error -> "Enregistrement ou restauration indisponible. Votre saisie reste en mémoire ; ne fermez pas l’application."
                    WeshDraftStore.Persistence.WipeRequired -> if (panicInProgress) "Effacement en cours…" else "Effacement inachevé. La récupération doit être confirmée."
                    WeshDraftStore.Persistence.Wiped -> "Brouillons effacés."
                }, Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall)
                if (draftPersistence == WeshDraftStore.Persistence.Error) TextButton(onClick = viewModel::retryWeshDrafts) { Text("Réessayer l’enregistrement ou la restauration") }
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom) {
                    OutlinedTextField(value = draft, enabled = destination != null && draftPersistence != WeshDraftStore.Persistence.WipeRequired && draftPersistence != WeshDraftStore.Persistence.Wiped, onValueChange = { text ->
                        destination?.let { if (!viewModel.weshDraftStore.save(it, text)) feedback = "Brouillon trop long (8 192 octets) ou limite de restauration atteinte. Votre texte précédent est conservé." }
                    }, label = { Text("Message") }, modifier = Modifier.weight(1f), maxLines = 6)
                    WeshMetalButton(onClick = { send() }, enabled = canSend && draft.isNotBlank(), modifier = Modifier.padding(start = 8.dp)) { Text("Envoyer") }
                }
                if (!publicOpen && !canSend) TextButton(onClick = { contactOpen = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Vérifier un contact") }
            } else when (tab) {
                0 -> LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { WeshInboxRow("Salon local", "Public · Bluetooth · sans QR", onClick = { viewModel.endPrivateChat(); publicOpen = true }) }
                    item { Text("Messages privés", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp)) }
                    item { Button(onClick = { contactOpen = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Ajouter un contact par QR") } }
                    if (contacts.isEmpty()) item { Text("Rencontrez-vous, scannez vos QR et vérifiez chacun l’identité sur votre téléphone.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(contacts, key = { it.conversationID }) { contact ->
                        val summary = conversations.firstOrNull { it.conversationID == contact.conversationID }
                        val blocked = contact.conversationID.let(viewModel::weshIsBlocked)
                        WeshInboxRow(contact.nickname,
                            if (blocked) "Bloqué sur ce téléphone" else "Identité vérifiée sur ce téléphone · ${summary?.latestMessagePreview.orEmpty()}",
                            onClick = { publicOpen = false; scope.launch { viewModel.startPrivateChat(contact.conversationID) } })
                    }
                }
                1 -> LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item { OutlinedTextField(editedNickname, { editedNickname = it }, label = { Text("Pseudonyme") }, modifier = Modifier.fillMaxWidth()) }
                    item { Button(onClick = { if (editedNickname.isNotBlank() && editedNickname.toByteArray().size <= 255) viewModel.setNickname(editedNickname.trim()) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Enregistrer le pseudonyme") } }
                    item { Row(verticalAlignment = Alignment.CenterVertically) { Text("Couper les notifications du salon public", Modifier.weight(1f)); Switch(muted, { muted = it; prefs.publicMuted = it }) } }
                    item { Text("État du Bluetooth", style = MaterialTheme.typography.titleMedium); Text("${if (bluetoothReady) "Bluetooth activé" else "Bluetooth indisponible"}. ${peers.size} connexion(s) au mesh. La présence ne confirme pas la réception d’un message.") }
                    item { Text("Pour tester hors ligne", style = MaterialTheme.typography.titleMedium); Text("Activez le mode avion, puis réactivez Bluetooth. Gardez Wesh ouvert pour le premier échange. La disponibilité en arrière-plan dépend du téléphone et de ses réglages de batterie.") }
                    item { TextButton(onClick = { noteOpen = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Note personnelle de rendez-vous") } }
                    item { Text("Contacts", style = MaterialTheme.typography.titleMedium) }
                    items(contacts, key = { it.conversationID }) { contact ->
                        Text(contact.nickname)
                        Row { TextButton(onClick = { contactAction = "remove" to contact.conversationID }) { Text("Retirer") }
                            TextButton(onClick = { contactAction = "block" to contact.conversationID }) { Text("Bloquer") } }
                    }
                    item { Text("Personnes bloquées", style = MaterialTheme.typography.titleMedium) }
                    items(if (revision >= 0) viewModel.weshBlockedFingerprints().toList() else emptyList()) { fingerprint ->
                        Text("${viewModel.weshContactName(fingerprint)} · ${fingerprint.take(8)}")
                        TextButton(onClick = { contactAction = "unblock" to fingerprint }) { Text("Débloquer") }
                    }
                    item { Text("Brouillons", style = MaterialTheme.typography.titleMedium); Text("Conservés séparément pendant cette session et les changements d’écran. Quitter complètement le processus les efface ; ils ne sont jamais envoyés automatiquement.") }
                    item { Text("Confidentialité", style = MaterialTheme.typography.titleMedium); Text("Le salon local est public. Les messages privés utilisent le chiffrement du protocole existant. Les contacts et les messages peuvent rester stockés : fermer le salon ne les efface pas. Bloquer masque une identité sur ce téléphone, sans empêcher un relais de conserver ses messages.") }
                }
            }
        }
    }
    publicRequest?.let { request ->
        AlertDialog(onDismissRequest = { publicRequest = null }, title = { Text("Envoyer dans le salon public ?") },
            text = { Text("Toute personne sur le mesh Bluetooth peut lire, relayer et conserver votre message. Les pseudonymes ne sont pas des identités vérifiées.") },
            confirmButton = { TextButton(onClick = {
                if (publicCapture?.let(viewModel.weshDraftStore::isCurrent) == true && request.canConfirm(if (publicOpen && viewModel.selectedPrivateChatPeer.value == null) "public" else "other",
                        viewModel.weshDraftStore.drafts.value["public"].orEmpty(), viewModel.weshBluetoothReady()) && viewModel.sendWeshPublic(request.content)) {
                    prefs.publicDisclosureAccepted = true
                    publicCapture?.let(viewModel.weshDraftStore::clearIfUnchanged)
                } else feedback = "Envoi annulé : Bluetooth, destination ou brouillon a changé. Votre texte est conservé."
                publicRequest = null
            }) { Text("Envoyer en public") } },
            dismissButton = { TextButton(onClick = { publicRequest = null }) { Text("Annuler") } })
    }
    if (contactOpen) WeshContactSheet(viewModel, onDismiss = { dismissContact() })
    contactAction?.let { (action, target) ->
        val title = when (action) { "remove" -> "Retirer ce contact ?"; "unblock" -> "Débloquer cette personne ?"; else -> "Bloquer cette personne ?" }
        AlertDialog(onDismissRequest = { contactAction = null }, title = { Text(title) },
            text = { Text(if (action == "remove") "Un nouveau scan sera nécessaire avant l’envoi privé. Historique et brouillon restent conservés."
                else if (action == "unblock") "Débloquer ne vérifie pas une nouvelle identité et ne restaure pas un contact retiré."
                else "Le blocage est local ; il ne supprime pas les copies chez les autres participants.") },
            confirmButton = { TextButton(onClick = {
                when (action) {
                    "remove" -> { viewModel.removeWeshContact(target); if (privatePeer == target) back() }
                    "unblock" -> viewModel.weshUnblock(target)
                    else -> if (!viewModel.weshBlock(target)) feedback = "Identité inconnue : blocage indisponible."
                }
                revision++; contactAction = null
            }) { Text("Confirmer") } }, dismissButton = { TextButton(onClick = { contactAction = null }) { Text("Annuler") } })
    }
    if (noteOpen) ModalBottomSheet(onDismissRequest = { noteOpen = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Note personnelle", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(meeting, { meeting = it }, label = { Text("Lieu, heure et notes") }, modifier = Modifier.fillMaxWidth(), minLines = 5)
            WeshMetalButton(onClick = { prefs.meetingNote = meeting; feedback = "Note enregistrée sur ce téléphone." }) { Text("Enregistrer") }
            Text("La note reste locale. Elle n’est pas envoyée aux contacts.")
            TextButton(onClick = { noteOpen = false }) { Text("Fermer") }
        }
    }
}

@Composable
private fun WeshInboxRow(title: String, subtitle: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WeshMessage(message: BitchatMessage, myPeerID: String, public: Boolean, canRetry: Boolean, onRetry: () -> Unit, onBlock: () -> Unit) {
    val outgoing = message.senderPeerID == myPeerID
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start) {
        Surface(shape = MaterialTheme.shapes.medium, color = if (outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(14.dp).widthIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!outgoing) Text(message.sender, style = MaterialTheme.typography.labelLarge)
                Text(if (message.type == BitchatMessageType.Message) message.content else "Pièce jointe reçue · affichage indisponible", style = MaterialTheme.typography.bodyLarge)
                Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(message.timestamp), style = MaterialTheme.typography.bodySmall)
                if (!public && outgoing) Text(WeshMessagePolicy.deliveryLabel(message.deliveryStatus), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (!public && outgoing && canRetry) TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Réessayer") }
        if (public && !outgoing && message.senderPeerID != null) TextButton(onClick = onBlock, modifier = Modifier.heightIn(min = 48.dp)) { Text("Bloquer") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WeshContactSheet(viewModel: ChatViewModel, onDismiss: () -> Unit) {
    var scanning by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var pendingNoise by remember { mutableStateOf<String?>(null) }
    var attemptRevision by remember { mutableIntStateOf(0) }
    var qr by remember { mutableStateOf<String?>(null) }
    val nickname by viewModel.nickname.collectAsState()
    LaunchedEffect(nickname) { while (true) { qr = viewModel.buildMyQRString(nickname, null); delay(60_000) } }
    val incoming by viewModel.weshIncomingQR.collectAsState()
    var reviewedLink by remember(incoming) { mutableStateOf(incoming) }
    LaunchedEffect(pendingNoise, attemptRevision) {
        val pending = pendingNoise ?: return@LaunchedEffect
        while (true) {
            val progress = viewModel.weshVerificationProgress(pending)
            status = when (progress) {
                "verified" -> "Identité vérifiée sur ce téléphone. L’autre personne doit aussi scanner votre QR."
                "pending" -> "Vérification en cours via Bluetooth. Attendez la confirmation."
                "expired" -> "Tentative expirée. Gardez les deux téléphones ouverts, puis scannez de nouveau."
                else -> "Vérification annulée ou identité indisponible. Scannez de nouveau."
            }
            if (progress != "pending") break
            delay(250)
        }
    }
    fun scan(raw: String) {
        val parsed = VerificationService.verifyScannedQR(raw)
        if (parsed == null) status = "QR invalide ou expiré. Demandez un nouveau code."
        else if (viewModel.beginQRVerification(parsed)) { pendingNoise = parsed.noiseKeyHex; attemptRevision++; status = "Vérification en cours via Bluetooth."; scanning = false }
        else status = "Contact introuvable, bloqué ou identité différente. Gardez les deux téléphones ouverts à proximité."
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 650.dp).verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Ajouter un contact", style = MaterialTheme.typography.headlineSmall)
            Text("Montrez ce QR en direct, en personne, pas une photo. Scannez chacun le QR de l’autre. Le salon public reste accessible sans QR.")
            Row { TextButton(onClick = { scanning = false }) { Text("Mon QR") }; TextButton(onClick = { scanning = true }) { Text("Scanner") } }
            reviewedLink?.let { raw ->
                Text("Un lien ne prouve pas qui vous rencontrez. Confirmez que ce QR est affiché en direct par la personne devant vous.")
                TextButton(onClick = { scan(raw); reviewedLink = null; viewModel.weshIncomingQR.value = null }) { Text("Vérifier après contrôle en personne") }
            }
            if (scanning) Box(Modifier.fillMaxWidth().height(320.dp)) {
                ScanTabContent(MaterialTheme.colorScheme.primary) { raw -> if (scanning) { scanning = false; scan(raw) } }
            } else qr?.let { Surface(color = Color.White) { QRCodeImage(it, 240.dp) } } ?: Text("Préparation de votre identité…")
            status?.let { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium) }
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Fermer") }
        }
    }
}

@Composable
private fun WeshMetalButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    Button(onClick = onClick, enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color.Black,
            disabledContainerColor = Color.Black, disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant),
        modifier = modifier.heightIn(min = 48.dp).background(
            if (enabled) Brush.linearGradient(listOf(Color(0xFFAEB6C0), Color.White, Color(0xFFCBD1D9)))
            else Brush.linearGradient(listOf(Color.Black, Color.Black)), MaterialTheme.shapes.large), content = content)
}
