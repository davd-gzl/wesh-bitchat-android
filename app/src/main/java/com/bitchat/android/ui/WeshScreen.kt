package com.bitchat.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.services.VerificationService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat

internal object WeshColors {
    val silver = Color(0xFFCBD1D9)
    val silverShadow = Color(0xFFAEB6C0)
    val messageSurface = Color(0xFF272A30)
    val secondaryText = Color(0xFFB4B4B4)
    val outline = Color(0xFF747B85)
}

internal fun weshColorScheme(): ColorScheme = darkColorScheme(
    primary = WeshColors.silver, onPrimary = Color.Black,
    primaryContainer = WeshColors.messageSurface, onPrimaryContainer = Color.White,
    background = Color.Black, onBackground = Color.White,
    surface = Color.Black, onSurface = Color.White,
    surfaceVariant = Color.Black, onSurfaceVariant = WeshColors.secondaryText,
    surfaceContainer = Color.Black, surfaceContainerHigh = Color.Black,
    surfaceContainerLow = Color.Black, surfaceContainerLowest = Color.Black, surfaceContainerHighest = Color.Black,
    outline = WeshColors.outline, surfaceTint = Color.Black,
    secondary = WeshColors.silver, onSecondary = Color.Black,
    secondaryContainer = WeshColors.messageSurface, onSecondaryContainer = Color.White,
    tertiary = WeshColors.silver, onTertiary = Color.Black,
    tertiaryContainer = WeshColors.messageSurface, onTertiaryContainer = Color.White
)

@Composable
fun WeshTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = weshColorScheme(), typography = Typography(), content = content)
}

/** Native messenger surface. All messages and contacts come from the existing service state. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WeshScreen(viewModel: ChatViewModel) {
    val context = LocalContext.current
    val windowHeightPx = LocalWindowInfo.current.containerSize.height
    val headerMaxHeight = with(LocalDensity.current) { windowHeightPx.toDp() * 0.3f }
    val prefs = viewModel.weshPreferences
    val preferencesEpoch by prefs.resetEpoch.collectAsState()
    val editorEpoch = preferencesEpoch
    val meeting by prefs.meetingDraft.collectAsState()
    val scope = rememberCoroutineScope()
    val publicMessages by viewModel.messages.collectAsState()
    val publicSending by viewModel.weshPublicSending.collectAsState()
    val privateChats by viewModel.privateChats.collectAsState()
    val peers by viewModel.connectedPeers.collectAsState()
    val contacts by viewModel.weshContacts.collectAsState()
    val drafts by viewModel.weshDraftStore.drafts.collectAsState()
    val draftPersistence by viewModel.weshDraftStore.persistence.collectAsState()
    val panicRecoveryRequired by com.bitchat.android.startup.WeshRuntime.draftWipeRequired.collectAsState()
    val panicInProgress by viewModel.weshPanicInProgress.collectAsState()
    val panicRecovery by com.bitchat.android.startup.WeshRuntime.recoveryState.collectAsState()
    if (panicInProgress) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp).semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text("Effacement en cours…")
        }
        return
    }
    if (panicRecoveryRequired) {
        com.bitchat.android.startup.StartupScreen(
            failed = true, onRetry = {}, draftWipeRequired = true, recovery = panicRecovery,
            onEraseAll = { com.bitchat.android.startup.WeshRuntime.clearInterruptedPanic(context) }
        )
        return
    }
    val requestedContactSheet by viewModel.weshContactSheet.collectAsState()
    val requests by viewModel.weshRequests.collectAsState()
    val contactStoreFailed by viewModel.weshContactStoreFailed.collectAsState()
    var requestAction by remember { mutableStateOf<com.bitchat.android.services.WeshMessageRequests.Request?>(null) }
    val conversations by viewModel.conversations.collectAsState()
    val nickname by viewModel.nickname.collectAsState()
    val selectedPrivate by viewModel.selectedPrivateChatPeer.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var publicOpen by rememberSaveable { mutableStateOf(false) }
    var contactOpen by rememberSaveable { mutableStateOf(false) }
    val destination = if (publicOpen) "public" else viewModel.weshDraftDestination(selectedPrivate)
    val draft = destination?.let(drafts::get).orEmpty()
    var confirmPanic by remember { mutableStateOf(false) }
    var noteOpen by remember { mutableStateOf(false) }
    var noteSaving by remember { mutableStateOf(false) }
    var noteFeedback by remember(preferencesEpoch) { mutableStateOf<String?>(null) }
    var contactAction by remember { mutableStateOf<Pair<String, String>?>(null) }
    var publicRequest by remember { mutableStateOf<WeshMessagePolicy.PublicSendRequest?>(null) }
    var publicCapture by remember { mutableStateOf<WeshDraftStore.Capture?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var muted by remember(preferencesEpoch) { mutableStateOf(prefs.publicMuted) }
    var editedNickname by rememberSaveable(nickname) { mutableStateOf(nickname) }
    var revision by remember { mutableIntStateOf(0) }
    // Session invalidation must disable sending even when a persisted contact does not change.
    LaunchedEffect(viewModel) { while (true) { delay(1000); revision++ } }
    LaunchedEffect(selectedPrivate) { if (selectedPrivate != null) publicOpen = false }
    LaunchedEffect(requestedContactSheet) { if (requestedContactSheet) contactOpen = true }
    val privatePeer = selectedPrivate
    val inConversation = publicOpen || privatePeer != null
    val conversationTitle = if (publicOpen) "Salon local" else if (privatePeer != null)
        conversations.firstOrNull { it.conversationID == privatePeer }?.displayName ?: "Contact"
        else listOf("Discussions", "Réglages")[tab]
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
            else viewModel.sendWeshPublic(sentDraft) { accepted ->
                if (accepted) viewModel.weshDraftStore.clearIfUnchanged(capture)
                else feedback = "Transmission locale interrompue ou indisponible. Votre texte est conservé (2 000 octets maximum)."
            }
        } else if (privatePeer != null) {
            viewModel.sendWeshPrivate(sentDraft, privatePeer) { accepted ->
                if (accepted) viewModel.weshDraftStore.clearIfUnchanged(capture) else feedback = "Envoi indisponible. Vérifiez l’identité et la connexion Bluetooth."
            }
        }
    }
    Scaffold(
        topBar = {
            Column(Modifier.fillMaxWidth().heightIn(max = headerMaxHeight).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
                if (inConversation) TextButton(onClick = { back() }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Retour aux discussions" }) { Text("‹ Discussions") }
                Text(conversationTitle, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                Text(if (publicOpen) if (bluetoothReady) "Public · Bluetooth" else "Public · Bluetooth indisponible" else if (privatePeer != null) if (canSend) "Identité vérifiée sur ce téléphone" else "Vérification ou reconnexion nécessaire" else "Wesh", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (privatePeer != null) FlowRow {
                    TextButton(onClick = { contactAction = "block" to privatePeer }, modifier = Modifier.semantics { contentDescription = "Bloquer $conversationTitle" }) { Text("Bloquer") }
                    TextButton(onClick = { contactAction = "remove" to privatePeer }, modifier = Modifier.semantics { contentDescription = "Retirer le contact $conversationTitle" }) { Text("Retirer le contact") }
                }
            }
        },
        bottomBar = {
            if (!inConversation) NavigationBar(modifier = Modifier.heightIn(min = 80.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)), containerColor = Color.Black, tonalElevation = 0.dp) {
                listOf("Discussions", "Réglages").forEachIndexed { index, label ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Text(listOf("◌", "⚙")[index], Modifier.clearAndSetSemantics {}) }, label = { Text(label) })
                }
            }
        }
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).imePadding()) {
            val footerMaxHeight = (maxHeight * 0.55f).coerceAtLeast(minOf(96.dp, maxHeight))
            Column(Modifier.fillMaxSize()) {
                if (inConversation) {
                    val messages = if (publicOpen) publicMessages.filter { !it.isPrivate && it.channel == null && (it.senderPeerID == null || !viewModel.weshIsBlocked(it.senderPeerID)) }
                    else privateChats[privatePeer].orEmpty()
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (publicOpen) item { Text("Aucune confirmation individuelle de réception. Toute personne sur le mesh Bluetooth peut lire et conserver ces messages. Les pseudonymes ne prouvent pas une identité.", style = MaterialTheme.typography.bodySmall) }
                        if (messages.isEmpty()) item { Text(if (publicOpen) "Le salon est calme. Dites bonjour aux personnes autour de vous." else "Votre conversation privée commence ici.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        items(messages, key = { it.id }) { message ->
                            WeshMessage(message, viewModel.meshService.myPeerID, publicOpen,
                                canRetry = !publicOpen && canSend && (message.deliveryStatus is com.bitchat.android.model.DeliveryStatus.Failed || message.deliveryStatus is com.bitchat.android.model.DeliveryStatus.Sending),
                                onRetry = { privatePeer?.let { viewModel.retryWeshPrivate(it, message.id) } },
                                onBlock = { message.senderPeerID?.let { contactAction = "publicBlock" to it } },
                                onReport = { message.senderPeerID?.let { contactAction = "report" to it } })
                        }
                    }
                    // Keep the editor and every recovery action reachable with large fonts or the IME.
                    Column(Modifier.fillMaxWidth().heightIn(max = footerMaxHeight).verticalScroll(rememberScrollState())) {
                        feedback?.let { Text(it, Modifier.padding(horizontal = 20.dp).semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error) }
                        if (!canSend) Text(if (publicOpen) "Activez Bluetooth dans les réglages du téléphone pour envoyer dans le salon." else "Scannez le QR de ce contact et attendez la confirmation. Un changement de clé exige une nouvelle vérification.", Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium)
                        Text(when (draftPersistence) {
                            WeshDraftStore.Persistence.Loading -> "Chargement des brouillons… Votre saisie n’est pas encore confirmée sur ce téléphone."
                            WeshDraftStore.Persistence.Unconfirmed -> "Brouillon en mémoire · enregistrement non confirmé."
                            WeshDraftStore.Persistence.Confirmed -> "Brouillon enregistré sur ce téléphone."
                            WeshDraftStore.Persistence.Error -> "Enregistrement ou restauration indisponible. Votre saisie reste en mémoire ; ne fermez pas l’application."
                            WeshDraftStore.Persistence.WipeRequired -> if (panicInProgress) "Effacement en cours…" else "Effacement inachevé. La récupération doit être confirmée."
                            WeshDraftStore.Persistence.Wiped -> "Brouillons effacés."
                        }, Modifier.padding(horizontal = 20.dp).semantics {
                            if (draftPersistence == WeshDraftStore.Persistence.Error) liveRegion = LiveRegionMode.Polite
                        }, style = MaterialTheme.typography.bodySmall)
                        if (draftPersistence == WeshDraftStore.Persistence.Error) TextButton(onClick = viewModel::retryWeshDrafts) { Text("Réessayer l’enregistrement ou la restauration") }
                        WeshComposer(draft = draft, editable = destination != null && draftPersistence != WeshDraftStore.Persistence.WipeRequired && draftPersistence != WeshDraftStore.Persistence.Wiped,
                            canSend = canSend && !publicSending && draft.isNotBlank(), onSend = { send() }, onValueChange = { text ->
                                destination?.let { if (!viewModel.weshDraftStore.save(it, text)) feedback = "Brouillon trop long (8 192 octets) ou limite de restauration atteinte. Votre texte précédent est conservé." }
                            })
                        if (!publicOpen && !canSend) TextButton(onClick = { contactOpen = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Vérifier un contact") }
                    }
                } else when (tab) {
                    0 -> LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item { WeshInboxRow("Salon local", "Public · Bluetooth · sans QR", onClick = { viewModel.endPrivateChat(); publicOpen = true }) }
                        item { Text("Messages privés", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp).semantics { heading() }) }
                        item { Button(onClick = { contactOpen = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Ajouter un contact par QR") } }
                        if (contactStoreFailed) item {
                            Text("Contacts illisibles sur ce téléphone. Aucun contact ne peut être ajouté ni retiré pour ne rien écraser.", Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = viewModel::retryWeshContacts, modifier = Modifier.heightIn(min = 48.dp)) { Text("Réessayer la lecture des contacts") }
                        }
                        if (contacts.isEmpty()) item { Text("Rencontrez-vous, scannez vos QR et vérifiez chacun l’identité sur votre téléphone.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        items(contacts, key = { it.conversationID }) { contact ->
                            val summary = conversations.firstOrNull { it.conversationID == contact.conversationID }
                            val blocked = contact.conversationID.let(viewModel::weshIsBlocked)
                            WeshInboxRow(contact.nickname,
                                if (blocked) "Bloqué sur ce téléphone" else "Identité vérifiée sur ce téléphone · ${summary?.latestMessagePreview.orEmpty()}",
                                onClick = { publicOpen = false; scope.launch { viewModel.startPrivateChat(contact.conversationID) } })
                        }
                        if (requests.isNotEmpty()) {
                            item { Text("Demandes", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp).semantics { heading() }) }
                            item { Text("Messages de personnes non vérifiées. Leur contenu n’est ni affiché ni conservé. Scannez leur QR en personne pour échanger.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            items(requests, key = { it.fingerprint ?: it.peerID }) { request ->
                                Column {
                                    Text(request.nickname, style = MaterialTheme.typography.titleMedium)
                                    Text("non vérifié", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    FlowRow {
                                        TextButton(onClick = { contactOpen = true }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Scanner le QR de ${request.nickname} pour vérifier" }) { Text("Scanner son QR") }
                                        TextButton(onClick = { viewModel.weshDismissRequest(request) }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Supprimer la demande de ${request.nickname}" }) { Text("Supprimer") }
                                        TextButton(onClick = { requestAction = request }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Bloquer ${request.nickname}" }) { Text("Bloquer") }
                                    }
                                }
                            }
                        }
                    }
                    1 -> LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        feedback?.let { value -> item { Text(value, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) } }
                        item { OutlinedTextField(editedNickname, { editedNickname = it }, label = { Text("Pseudonyme") }, modifier = Modifier.fillMaxWidth()) }
                        item { Button(onClick = {
                            if (editedNickname.isNotBlank() && editedNickname.toByteArray().size <= 255) {
                                viewModel.setNickname(editedNickname.trim())
                                feedback = "Pseudonyme mis à jour."
                            } else feedback = "Choisissez un pseudonyme non vide de 255 octets maximum."
                        }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Enregistrer le pseudonyme") } }
                        item { Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = muted, role = Role.Switch, onValueChange = { muted = it; prefs.publicMuted = it }), verticalAlignment = Alignment.CenterVertically) {
                            Text("Couper les notifications du salon public", Modifier.weight(1f)); Switch(muted, onCheckedChange = null)
                        } }
                        item { Text("État du Bluetooth", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() }); Text("${if (bluetoothReady) "Bluetooth activé" else "Bluetooth indisponible"}. ${peers.size} connexion(s) au mesh. La présence ne confirme pas la réception d’un message.") }
                        item { Text("Pour tester hors ligne", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() }); Text("Activez le mode avion, puis réactivez Bluetooth. Gardez Wesh ouvert pour le premier échange. La disponibilité en arrière-plan dépend du téléphone et de ses réglages de batterie.") }
                        item { TextButton(onClick = { noteOpen = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Note personnelle de rendez-vous") } }
                        item { Text("Contacts", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() }) }
                        items(contacts, key = { it.conversationID }) { contact ->
                            Text(contact.nickname)
                            FlowRow { TextButton(onClick = { contactAction = "remove" to contact.conversationID }, modifier = Modifier.semantics { contentDescription = "Retirer le contact ${contact.nickname}" }) { Text("Retirer") }
                                TextButton(onClick = { contactAction = "block" to contact.conversationID }, modifier = Modifier.semantics { contentDescription = "Bloquer ${contact.nickname}" }) { Text("Bloquer") } }
                        }
                        item { Text("Personnes bloquées", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() }) }
                        items(if (revision >= 0) viewModel.weshBlockedFingerprints().toList() else emptyList()) { fingerprint ->
                            Text("${viewModel.weshContactName(fingerprint)} · ${fingerprint.take(8)}")
                            TextButton(onClick = { contactAction = "unblock" to fingerprint }, modifier = Modifier.semantics { contentDescription = "Débloquer ${viewModel.weshContactName(fingerprint)}" }) { Text("Débloquer") }
                        }
                        item { Text("Brouillons", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() }); Text("Les brouillons sont enregistrés chiffrés sur ce téléphone et restent disponibles après fermeture de Wesh. Quitter l’application ne les efface pas ; ils ne sont jamais envoyés automatiquement.") }
                        item { Text("Confidentialité", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() }); Text("Le salon local est public. Les messages privés utilisent le chiffrement du protocole existant. Les contacts et les messages peuvent rester stockés : fermer le salon ne les efface pas. Bloquer masque une identité sur ce téléphone, sans empêcher un relais de conserver ses messages.") }
                        item {
                            TextButton(onClick = {
                                runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(WeshLinks.TERMS_URL))) }
                                    .onFailure { feedback = "Aucun navigateur disponible pour ouvrir les conditions d’utilisation." }
                            }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Conditions d’utilisation") }
                        }
                        item {
                            Text("Effacement d’urgence", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                            Text("Effacer vos données locales et renouveler votre identité Wesh.")
                            OutlinedButton(onClick = { confirmPanic = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text("Effacer mes données de Wesh")
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmPanic) AlertDialog(
        onDismissRequest = { confirmPanic = false },
        title = { Text("Effacer vos données de Wesh ?") },
        text = { WeshDialogText("Cette action efface votre identité actuelle, vos contacts et vérifications, votre historique, vos brouillons, votre note personnelle et les fichiers locaux gérés par Wesh. Une nouvelle identité sera créée et vos contacts devront être vérifiés à nouveau. Les copies déjà reçues, exportées ou sauvegardées hors de Wesh ne sont pas retirées. Il s’agit d’un effacement logique, sans garantie d’effacement physique des supports. Si l’effacement ne peut pas être confirmé, Wesh restera verrouillé et proposera une récupération par Android.") },
        confirmButton = { TextButton(onClick = {
            confirmPanic = false
            viewModel.panicClearAllData()
        }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Effacer mes données") } },
        dismissButton = { TextButton(onClick = { confirmPanic = false }) { Text("Annuler") } }
    )
    publicRequest?.let { request ->
        AlertDialog(onDismissRequest = { publicRequest = null }, title = { Text("Envoyer dans le salon public ?") },
            text = { WeshDialogText("Toute personne sur le mesh Bluetooth peut lire, relayer et conserver votre message. Les pseudonymes ne sont pas des identités vérifiées.") },
            confirmButton = { TextButton(onClick = {
                val capture = publicCapture
                if (capture != null && viewModel.weshDraftStore.isCurrent(capture) && request.canConfirm(if (publicOpen && viewModel.selectedPrivateChatPeer.value == null) "public" else "other",
                        viewModel.weshDraftStore.drafts.value["public"].orEmpty(), viewModel.weshBluetoothReady())) {
                    viewModel.sendWeshPublic(request.content) { accepted ->
                        if (accepted) {
                            prefs.publicDisclosureAccepted = true
                            viewModel.weshDraftStore.clearIfUnchanged(capture)
                        } else feedback = "Transmission locale interrompue ou indisponible. Votre texte est conservé."
                    }
                } else feedback = "Envoi annulé : Bluetooth, destination ou brouillon a changé. Votre texte est conservé."
                publicRequest = null
            }) { Text("Envoyer en public") } },
            dismissButton = { TextButton(onClick = { publicRequest = null }) { Text("Annuler") } })
    }
    if (contactOpen) WeshContactSheet(viewModel, onDismiss = { dismissContact() })
    contactAction?.let { (action, target) ->
        val title = when (action) { "remove" -> "Retirer ce contact ?"; "unblock" -> "Débloquer cette personne ?"; "report" -> "Signaler cette personne ?"; else -> "Bloquer cette personne ?" }
        val targetName = if (action == "unblock") viewModel.weshContactName(target)
            else contacts.firstOrNull { it.conversationID == target }?.nickname
                ?: conversations.firstOrNull { it.conversationID == target }?.displayName
                ?: viewModel.peerNicknames.value[target] ?: "Contact"
        AlertDialog(onDismissRequest = { contactAction = null }, title = { Text("$title\n$targetName", Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState())) },
            text = { WeshDialogText(if (action == "remove") "Un nouveau scan sera nécessaire avant l’envoi privé. Historique et brouillon restent conservés."
                else if (action == "unblock") "Débloquer ne vérifie pas une nouvelle identité et ne restaure pas un contact retiré."
                else if (action == "report") "Le signalement reste sur ce téléphone : cette personne est bloquée et ses messages sont masqués. Rien n’est envoyé à Wesh ni aux autres participants."
                else "Le blocage est local ; il ne supprime pas les copies chez les autres participants.") },
            confirmButton = { TextButton(onClick = {
                when (action) {
                    "remove" -> { viewModel.removeWeshContact(target); if (privatePeer == target) back() }
                    "unblock" -> viewModel.weshUnblock(target)
                    "report", "publicBlock" -> feedback = if (viewModel.weshBlockPublicSender(target))
                        if (action == "report") "Personne signalée et masquée sur ce téléphone." else "Personne bloquée sur ce téléphone."
                        else "Identité inconnue : blocage indisponible."
                    else -> if (!viewModel.weshBlock(target)) feedback = "Identité inconnue : blocage indisponible."
                }
                revision++; contactAction = null
            }) { Text(when (action) { "remove" -> "Retirer le contact"; "unblock" -> "Débloquer"; "report" -> "Signaler et masquer"; else -> "Bloquer" }) } }, dismissButton = { TextButton(onClick = { contactAction = null }) { Text("Annuler") } })
    }
    requestAction?.let { request ->
        AlertDialog(onDismissRequest = { requestAction = null }, title = { Text("Bloquer cette personne ?\n${request.nickname}") },
            text = { WeshDialogText("Le blocage est local. Ses prochaines demandes seront ignorées sur ce téléphone.") },
            confirmButton = { TextButton(onClick = {
                if (!viewModel.weshBlockRequest(request)) feedback = "Identité inconnue : blocage indisponible."
                revision++; requestAction = null
            }) { Text("Bloquer") } },
            dismissButton = { TextButton(onClick = { requestAction = null }) { Text("Annuler") } })
    }
    if (noteOpen) ModalBottomSheet(onDismissRequest = { noteOpen = false }, modifier = Modifier.semantics { paneTitle = "Note personnelle" }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Note personnelle", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            OutlinedTextField(meeting, { value ->
                if (prefs.editMeetingDraft(value, editorEpoch)) noteFeedback = null
            }, label = { Text("Lieu, heure et notes") }, modifier = Modifier.fillMaxWidth(), minLines = 5)
            WeshMetalButton(onClick = {
                noteSaving = true
                noteFeedback = null
                scope.launch {
                    try {
                        val confirmed = prefs.saveMeetingDraft(editorEpoch)
                        if (prefs.resetEpoch.value == editorEpoch) {
                            noteFeedback = if (confirmed) "Note enregistrée sur ce téléphone."
                            else "Enregistrement non confirmé. Votre saisie reste en mémoire ; réessayez avant de fermer Wesh."
                        }
                    } finally { noteSaving = false }
                }
            }, enabled = !noteSaving) { Text(if (noteSaving) "Enregistrement…" else "Enregistrer") }
            noteFeedback?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            Text("La note reste locale. Elle n’est pas envoyée aux contacts.")
            TextButton(onClick = { noteOpen = false }) { Text("Fermer") }
        }
    }
}

@Composable
private fun WeshDialogText(text: String) {
    Text(text, Modifier.verticalScroll(rememberScrollState()))
}

@Composable
private fun WeshComposer(draft: String, editable: Boolean, canSend: Boolean, onSend: () -> Unit, onValueChange: (String) -> Unit) {
    val largeText = LocalDensity.current.fontScale >= 1.3f
    BoxWithConstraints(Modifier.fillMaxWidth().padding(12.dp)) {
        if (largeText || maxWidth < 360.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = draft, enabled = editable, onValueChange = onValueChange,
                    label = { Text("Message") }, modifier = Modifier.fillMaxWidth(), maxLines = 6)
                WeshMetalButton(onClick = onSend, enabled = canSend, modifier = Modifier.fillMaxWidth()) { Text("Envoyer") }
            }
        } else {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = draft, enabled = editable, onValueChange = onValueChange,
                    label = { Text("Message") }, modifier = Modifier.weight(1f), maxLines = 6)
                WeshMetalButton(onClick = onSend, enabled = canSend) { Text("Envoyer") }
            }
        }
    }
}

@Composable
private fun WeshInboxRow(title: String, subtitle: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).semantics { role = Role.Button }) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeshMessage(message: BitchatMessage, myPeerID: String, public: Boolean, canRetry: Boolean, onRetry: () -> Unit, onBlock: () -> Unit, onReport: () -> Unit) {
    val outgoing = message.senderPeerID == myPeerID
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start) {
        Surface(shape = MaterialTheme.shapes.medium, color = if (outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(14.dp).widthIn(max = 320.dp).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!outgoing) Text(message.sender, style = MaterialTheme.typography.labelLarge)
                Text(if (message.type == BitchatMessageType.Message) message.content else "Média non pris en charge", style = MaterialTheme.typography.bodyLarge)
                Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(message.timestamp), style = MaterialTheme.typography.bodySmall)
                if (!public && outgoing) Text(WeshMessagePolicy.deliveryLabel(message.deliveryStatus), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (!public && outgoing && canRetry) TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp).semantics {
            contentDescription = "Réessayer l’envoi du message du ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(message.timestamp)}"
        }) { Text("Réessayer") }
        if (public && !outgoing && message.senderPeerID != null) FlowRow {
            TextButton(onClick = onReport, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Signaler ${message.sender}" }) { Text("Signaler") }
            TextButton(onClick = onBlock, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Bloquer ${message.sender}" }) { Text("Bloquer") }
        }
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
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.semantics { paneTitle = "Ajouter un contact" }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 650.dp).verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Ajouter un contact", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text("Montrez ce QR en direct, en personne, pas une photo. Scannez chacun le QR de l’autre. Le salon public reste accessible sans QR.")
            PrimaryTabRow(selectedTabIndex = if (scanning) 1 else 0) {
                Tab(selected = !scanning, onClick = { scanning = false }, text = { Text("Mon QR") })
                Tab(selected = scanning, onClick = { scanning = true }, text = { Text("Scanner") })
            }
            reviewedLink?.let { raw ->
                Text("Un lien ne prouve pas qui vous rencontrez. Confirmez que ce QR est affiché en direct par la personne devant vous.")
                TextButton(onClick = { scan(raw); reviewedLink = null; viewModel.weshIncomingQR.value = null }) { Text("Vérifier après contrôle en personne") }
            }
            if (scanning) Box(Modifier.fillMaxWidth().height(320.dp)) {
                ScanTabContent(MaterialTheme.colorScheme.primary) { raw -> if (scanning) { scanning = false; scan(raw) } }
            } else qr?.let { raw -> BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Surface(color = Color.White) { QRCodeImage(raw, minOf(240.dp, maxWidth)) }
            } } ?: Text("Préparation de votre identité…")
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
            if (enabled) Brush.linearGradient(listOf(WeshColors.silverShadow, Color.White, WeshColors.silver))
            else Brush.linearGradient(listOf(Color.Black, Color.Black)), MaterialTheme.shapes.large), content = content)
}
