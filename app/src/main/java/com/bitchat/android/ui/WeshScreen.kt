package com.bitchat.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
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
    val colors = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF8CB9FF))
    else lightColorScheme(primary = Color(0xFF2164D8), background = Color(0xFFF5F6F8), surface = Color.White)
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
    val verified by viewModel.verifiedFingerprints.collectAsState()
    val conversations by viewModel.conversations.collectAsState()
    val nickname by viewModel.nickname.collectAsState()
    val selectedPrivate by viewModel.selectedPrivateChatPeer.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var publicOpen by rememberSaveable { mutableStateOf(false) }
    var contactOpen by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable(publicOpen, selectedPrivate) { mutableStateOf("") }
    var disclosure by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var muted by remember { mutableStateOf(prefs.publicMuted) }
    var meeting by rememberSaveable { mutableStateOf(prefs.meetingNote) }
    var editedNickname by rememberSaveable(nickname) { mutableStateOf(nickname) }
    var revision by remember { mutableIntStateOf(0) }
    // Session invalidation must disable sending even when a persisted contact does not change.
    LaunchedEffect(viewModel) { while (true) { delay(1000); revision++ } }
    val privatePeer = selectedPrivate
    val inConversation = publicOpen || privatePeer != null
    val bluetoothReady = revision >= 0 && viewModel.weshBluetoothReady()
    val canSend = if (publicOpen) bluetoothReady else privatePeer != null && revision >= 0 && viewModel.weshCanSendPrivate(privatePeer)
    fun back() { publicOpen = false; viewModel.endPrivateChat(); feedback = null }
    BackHandler(inConversation || contactOpen) { if (contactOpen) contactOpen = false else back() }
    fun send() {
        if (publicOpen) {
            if (!prefs.publicDisclosureAccepted) disclosure = true
            else if (viewModel.sendWeshPublic(draft)) draft = "" else feedback = "Message vide ou trop long (2 000 octets maximum)."
        } else if (privatePeer != null) {
            viewModel.sendWeshPrivate(draft, privatePeer) { accepted ->
                if (accepted) draft = "" else feedback = "Envoi indisponible. Vérifiez l’identité et la connexion Bluetooth."
            }
        }
    }
    Scaffold(
        topBar = {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                if (inConversation) TextButton(onClick = { back() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("‹ Discussions") }
                Text(if (publicOpen) "Salon local" else if (privatePeer != null) conversations.firstOrNull { it.conversationID == privatePeer }?.displayName ?: "Contact" else listOf("Discussions", "Rendez-vous", "Réglages")[tab], style = MaterialTheme.typography.headlineMedium)
                Text(if (publicOpen) if (bluetoothReady) "Public · Bluetooth" else "Public · Bluetooth indisponible" else if (privatePeer != null) if (canSend) "Identité vérifiée sur ce téléphone" else "Vérification ou reconnexion nécessaire" else "Wesh", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        bottomBar = {
            if (!inConversation) NavigationBar {
                listOf("Discussions", "Rendez-vous", "Réglages").forEachIndexed { index, label ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Text(listOf("◌", "◇", "⚙")[index]) }, label = { Text(label) })
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
                            onBlock = { message.senderPeerID?.let { if (viewModel.weshBlock(it)) { revision++; feedback = "Personne bloquée sur ce téléphone." } else feedback = "Identité inconnue : blocage indisponible." } })
                    }
                }
                feedback?.let { Text(it, Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.error) }
                if (!canSend) Text(if (publicOpen) "Activez Bluetooth dans les réglages du téléphone pour envoyer dans le salon." else "Scannez le QR de ce contact et attendez la confirmation. Un changement de clé exige une nouvelle vérification.", Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom) {
                    OutlinedTextField(value = draft, onValueChange = { draft = it }, label = { Text("Message") }, modifier = Modifier.weight(1f), maxLines = 6)
                    Button(onClick = { send() }, enabled = canSend && draft.isNotBlank(), modifier = Modifier.padding(start = 8.dp).heightIn(min = 48.dp)) { Text("Envoyer") }
                }
                if (!publicOpen && !canSend) TextButton(onClick = { contactOpen = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Vérifier un contact") }
            } else when (tab) {
                0 -> LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { WeshInboxRow("Salon local", "Public · Bluetooth · sans QR", onClick = { viewModel.endPrivateChat(); publicOpen = true }) }
                    item { Text("Messages privés", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp)) }
                    item { Button(onClick = { contactOpen = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Ajouter un contact par QR") } }
                    if (conversations.isEmpty()) item { Text("Rencontrez-vous, scannez vos QR et vérifiez chacun l’identité sur votre téléphone.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(conversations, key = { it.conversationID }) { contact ->
                        val resolution = ContactDirectory.resolve(contact.conversationID)
                        val locallyVerified = if (contact.connectedPeerID != null) viewModel.weshCanSendPrivate(contact.conversationID) else resolution.noisePublicKey?.let { verified.contains(ContactIdentityResolver.fingerprintHex(it)) } == true
                        WeshInboxRow(contact.displayName,
                            if (locallyVerified) "Identité vérifiée sur ce téléphone · ${contact.latestMessagePreview}" else "Identité à vérifier · ${contact.latestMessagePreview}",
                            onClick = { publicOpen = false; scope.launch { viewModel.startPrivateChat(contact.conversationID) } })
                    }
                    val existing = conversations.map { it.connectedPeerID }.toSet()
                    items(peers.filter { it !in existing && viewModel.weshCanSendPrivate(it) }) { peer ->
                        WeshInboxRow(names[peer] ?: "Contact", "Identité vérifiée sur ce téléphone", onClick = { scope.launch { viewModel.startPrivateChat(peer) } })
                    }
                }
                1 -> Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Un point de rencontre à garder sur ce téléphone.", style = MaterialTheme.typography.bodyLarge)
                    OutlinedTextField(meeting, { meeting = it }, label = { Text("Lieu, heure et notes") }, modifier = Modifier.fillMaxWidth(), minLines = 5)
                    Button(onClick = { prefs.meetingNote = meeting; feedback = "Note enregistrée sur ce téléphone." }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Enregistrer") }
                    Text("La note reste locale. Elle n’est pas envoyée aux contacts.", style = MaterialTheme.typography.bodyMedium)
                    feedback?.let { Text(it) }
                }
                2 -> LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item { OutlinedTextField(editedNickname, { editedNickname = it }, label = { Text("Pseudonyme") }, modifier = Modifier.fillMaxWidth()) }
                    item { Button(onClick = { if (editedNickname.isNotBlank() && editedNickname.toByteArray().size <= 255) viewModel.setNickname(editedNickname.trim()) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Enregistrer le pseudonyme") } }
                    item { Row(verticalAlignment = Alignment.CenterVertically) { Text("Couper les notifications du salon public", Modifier.weight(1f)); Switch(muted, { muted = it; prefs.publicMuted = it }) } }
                    item { Text("État du Bluetooth", style = MaterialTheme.typography.titleMedium); Text("${if (bluetoothReady) "Bluetooth activé" else "Bluetooth indisponible"}. ${peers.size} connexion(s) au mesh. La présence ne confirme pas la réception d’un message.") }
                    item { Text("Pour tester hors ligne", style = MaterialTheme.typography.titleMedium); Text("Activez le mode avion, puis réactivez Bluetooth. Gardez Wesh ouvert pour le premier échange. La disponibilité en arrière-plan dépend du téléphone et de ses réglages de batterie.") }
                    item { Text("Confidentialité", style = MaterialTheme.typography.titleMedium); Text("Le salon local est public. Les messages privés utilisent le chiffrement du protocole existant. Les contacts et les messages peuvent rester stockés : fermer le salon ne les efface pas. Bloquer masque une identité sur ce téléphone, sans empêcher un relais de conserver ses messages.") }
                }
            }
        }
    }
    if (disclosure) AlertDialog(onDismissRequest = { disclosure = false }, title = { Text("Envoyer dans le salon public ?") }, text = { Text("Toute personne sur le mesh Bluetooth peut lire, relayer et conserver votre message. Les pseudonymes ne sont pas des identités vérifiées.") }, confirmButton = { TextButton(onClick = { prefs.publicDisclosureAccepted = true; disclosure = false; send() }) { Text("Envoyer en public") } }, dismissButton = { TextButton(onClick = { disclosure = false }) { Text("Annuler") } })
    if (contactOpen) WeshContactSheet(viewModel, verified, onDismiss = { contactOpen = false })
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
private fun WeshMessage(message: BitchatMessage, myPeerID: String, public: Boolean, onBlock: () -> Unit) {
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
        if (public && !outgoing && message.senderPeerID != null) TextButton(onClick = onBlock, modifier = Modifier.heightIn(min = 48.dp)) { Text("Bloquer") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WeshContactSheet(viewModel: ChatViewModel, verified: Set<String>, onDismiss: () -> Unit) {
    var scanning by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var pendingNoise by remember { mutableStateOf<String?>(null) }
    var qr by remember { mutableStateOf<String?>(null) }
    val nickname by viewModel.nickname.collectAsState()
    LaunchedEffect(nickname) { while (true) { qr = viewModel.buildMyQRString(nickname, null); delay(60_000) } }
    LaunchedEffect(verified, pendingNoise) {
        pendingNoise?.let { noise ->
            val key = noise.dataFromHexString()
            if (key != null && verified.contains(ContactIdentityResolver.fingerprintHex(key))) status = "Identité vérifiée sur ce téléphone. L’autre personne doit aussi scanner votre QR."
        }
    }
    LaunchedEffect(pendingNoise) {
        val pending = pendingNoise ?: return@LaunchedEffect
        delay(60_000)
        val key = pending.dataFromHexString()
        if (key == null || !viewModel.verifiedFingerprints.value.contains(ContactIdentityResolver.fingerprintHex(key))) {
            status = "Aucune confirmation reçue. Gardez les deux téléphones ouverts, puis scannez de nouveau."
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 650.dp).verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Ajouter un contact", style = MaterialTheme.typography.headlineSmall)
            Text("Montrez ce QR en direct, en personne, pas une photo. Scannez chacun le QR de l’autre. Le salon public reste accessible sans QR.")
            Row { TextButton(onClick = { scanning = false }) { Text("Mon QR") }; TextButton(onClick = { scanning = true }) { Text("Scanner") } }
            if (scanning) Box(Modifier.fillMaxWidth().height(320.dp)) {
                ScanTabContent(MaterialTheme.colorScheme.primary) { raw ->
                    val parsed = VerificationService.verifyScannedQR(raw)
                    if (parsed == null) status = "QR invalide ou expiré. Demandez un nouveau code."
                    else if (viewModel.beginQRVerification(parsed)) { pendingNoise = parsed.noiseKeyHex; status = "Vérification en cours via Bluetooth. Attendez la confirmation."; scanning = false }
                    else status = "Contact introuvable ou identité différente. Gardez les deux téléphones ouverts à proximité."
                }
            } else qr?.let { Surface(color = Color.White) { QRCodeImage(it, 240.dp) } } ?: Text("Préparation de votre identité…")
            status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Fermer") }
        }
    }
}
