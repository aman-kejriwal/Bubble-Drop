package dev.bubbledrop

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** Files handed to us from another app's share sheet, waiting for a device to be picked. */
    private var sharedUris by mutableStateOf<List<Uri>>(emptyList())
    /** A web link shared from a browser or other app, waiting for a device to be picked. */
    private var sharedLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleShareIntent(intent)
        setContent {
            BubbleDropTheme {
                val permissions = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { BubbleDropService.start(this) }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    val needed = buildList {
                        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                        if (Build.VERSION.SDK_INT >= 37) add(PERMISSION_LOCAL_NETWORK)
                    }
                    if (needed.isEmpty()) BubbleDropService.start(this@MainActivity)
                    else permissions.launch(needed.toTypedArray())
                }
                MainScreen(sharedUris = sharedUris, sharedLink = sharedLink, onClearShared = {
                    sharedUris = emptyList()
                    sharedLink = null
                })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Links.appVisible = true
    }

    override fun onPause() {
        Links.appVisible = false
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        val uris = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) {
            sharedUris = uris
            sharedLink = null
        } else if (intent?.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let(::firstWebLink)?.let {
                sharedLink = it
                sharedUris = emptyList()
            }
        }
    }

    companion object {
        // Android 17 local-network permission; named literally so older SDKs still compile.
        private const val PERMISSION_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(sharedUris: List<Uri>, sharedLink: String?, onClearShared: () -> Unit) {
    val context = LocalContext.current
    val peers by Graph.discovery.peers.collectAsState()
    val transfers by Graph.transfers.transfers.collectAsState()
    val running by BubbleDropService.running.collectAsState()
    val pending by Graph.prompts.current.collectAsState()
    val trusted by Graph.trust.devices.collectAsState()
    var pickTarget by remember { mutableStateOf<Peer?>(null) }
    var mirrorTarget by remember { mutableStateOf<Peer?>(null) }
    val mirroringTo by MirrorService.active.collectAsState()
    val mirrorError by MirrorService.error.collectAsState()
    val mirrorConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val peer = mirrorTarget
        val data = result.data
        if (peer != null && result.resultCode == android.app.Activity.RESULT_OK && data != null) {
            MirrorService.start(context, peer, result.resultCode, data)
        }
        mirrorTarget = null
    }
    fun mirror(peer: Peer) {
        mirrorTarget = peer
        MirrorService.clearError()
        mirrorConsent.launch(
            context.getSystemService(android.media.projection.MediaProjectionManager::class.java)
                .createScreenCaptureIntent()
        )
    }

    val sharing = sharedUris.isNotEmpty() || sharedLink != null

    fun send(peer: Peer, uris: List<Uri>) {
        if (uris.isEmpty()) return
        Graph.scope.launch { Graph.sender.send(peer, uris) }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        pickTarget?.let { send(it, uris) }
        pickTarget = null
    }

    // Transfers seen in flight while the app is open; only these get the full-screen card.
    var live by remember { mutableStateOf(emptySet<String>()) }
    var dismissed by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(transfers) {
        live = live + transfers.filter {
            it.status == Transfer.Status.Waiting || it.status == Transfer.Status.Active
        }.map { it.id }
    }
    val spotlight = transfers.firstOrNull { it.id in live && it.id !in dismissed }
    LaunchedEffect(spotlight?.id, spotlight?.status == Transfer.Status.Done) {
        if (spotlight?.status == Transfer.Status.Done) {
            delay(4_000)
            dismissed = dismissed + spotlight.id
        }
    }

    Box(Modifier.fillMaxSize()) {
    Scaffold(topBar = { TopAppBar(title = { Text("BubbleDrop") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(Graph.device.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (running) "Visible to nearby devices" else "Off: not visible or receiving",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(checked = running, onCheckedChange = {
                            if (it) BubbleDropService.start(context) else BubbleDropService.stop(context)
                        })
                    }
                }
            }

            mirroringTo?.let { name ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Mirroring to $name", style = MaterialTheme.typography.titleSmall)
                                Text("Your screen is visible on $name", style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { MirrorService.stop(context) }) { Text("Stop") }
                        }
                    }
                }
            }
            mirrorError?.let { message ->
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Mirroring stopped: $message", color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { MirrorService.clearError() }) { Text("OK") }
                    }
                }
            }

            if (sharing) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    when {
                                        sharedLink != null -> "Tap a device to open this link there"
                                        sharedUris.size == 1 -> "Tap a device to send 1 item"
                                        else -> "Tap a device to send ${sharedUris.size} items"
                                    }
                                )
                                sharedLink?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis)
                                }
                            }
                            TextButton(onClick = onClearShared) { Text("Cancel") }
                        }
                    }
                }
            }

            item { Text("Nearby", style = MaterialTheme.typography.titleSmall) }
            if (peers.isEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (running) "Looking for devices… Open BubbleDrop on your Mac and join the same Wi-Fi."
                            else "Turn BubbleDrop on to find devices.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            items(peers, key = { it.id }) { peer ->
                Card(
                    Modifier.fillMaxWidth().clickable {
                        if (sharedLink != null) {
                            Graph.scope.launch { Graph.sender.sendLink(peer, sharedLink) }
                            onClearShared()
                        } else if (sharedUris.isNotEmpty()) {
                            send(peer, sharedUris)
                            onClearShared()
                        } else {
                            pickTarget = peer
                            picker.launch(arrayOf("*/*"))
                        }
                    }
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (peer.platform == "macos") "💻" else "📱", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(peer.name, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                if (trusted.any { it.fingerprint == peer.fingerprint }) {
                                    Text("  🔒 Paired", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Text(peer.model, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(if (sharing) "Send" else "Choose files",
                                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                            if (!sharing && peer.platform == "macos" && mirroringTo == null) {
                                TextButton(onClick = { mirror(peer) }, contentPadding = PaddingValues(0.dp)) {
                                    Text("Mirror screen", style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                }
            }

            if (trusted.isNotEmpty()) {
                item { Text("Paired devices", style = MaterialTheme.typography.titleSmall) }
                items(trusted, key = { it.fingerprint }) { device ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(device.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { Graph.trust.remove(device.fingerprint) }) { Text("Unpair") }
                    }
                }
            }

            if (transfers.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Transfers", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { Graph.transfers.clearFinished() }) { Text("Clear") }
                    }
                }
                items(transfers, key = { it.id }) { TransferRow(it) }
            }
        }
    }

    DropOverlay(
        offer = pending?.takeIf { it.kind is PromptKind.Offer },
        transfer = spotlight,
        onDismissTransfer = { dismissed = dismissed + it.id },
    )
    }

    pending?.takeIf { it.kind !is PromptKind.Offer }?.let { prompt -> PromptDialog(prompt) }
}

@Composable
private fun PromptDialog(prompt: Prompts.Pending) {
    val respond = { accept: Boolean -> Graph.prompts.respond(prompt.id, accept) }
    when (val kind = prompt.kind) {
        is PromptKind.Offer -> {
            val ask = kind.request
            AlertDialog(
                onDismissRequest = {},
                title = { Text("${ask.sender.name} wants to send you " +
                    if (ask.files.size == 1) ask.files[0].name else "${ask.files.size} files") },
                text = {
                    Column {
                        ask.files.take(6).forEach { Text("• ${it.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        if (ask.files.size > 6) Text("…and ${ask.files.size - 6} more")
                        Spacer(Modifier.size(8.dp))
                        Text("${formatBytes(ask.totalSize)} · saved to Downloads/BubbleDrop",
                            style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = { TextButton(onClick = { respond(true) }) { Text("Accept") } },
                dismissButton = { TextButton(onClick = { respond(false) }) { Text("Decline") } },
            )
        }
        is PromptKind.PairIncoming -> PairingDialog(
            title = "Pair with ${kind.sender.name}?",
            detail = "Check that ${kind.sender.name} shows this code, then tap Pair.",
            code = kind.code, respond = respond,
        )
        is PromptKind.PairOutgoing -> PairingDialog(
            title = "Pair with ${kind.peerName}?",
            detail = "Check that ${kind.peerName} shows the same code. You only need to pair once.",
            code = kind.code, respond = respond,
        )
    }
}

@Composable
private fun PairingDialog(title: String, detail: String, code: String, respond: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(detail, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.size(16.dp))
                Text(formatCode(code), style = MaterialTheme.typography.displaySmall)
            }
        },
        confirmButton = { TextButton(onClick = { respond(true) }) { Text("Pair") } },
        dismissButton = { TextButton(onClick = { respond(false) }) { Text("Cancel") } },
    )
}

@Composable
private fun TransferRow(transfer: Transfer) {
    val context = LocalContext.current
    val link = transfer.link
    if (link != null) {
        Column(Modifier.fillMaxWidth().clickable { runCatching { context.startActivity(Links.viewIntent(link)) } }) {
            Text("🔗 ${transfer.summary}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when (val status = transfer.status) {
                    is Transfer.Status.Failed -> status.message
                    Transfer.Status.Done -> when {
                        transfer.incoming -> "From ${transfer.peerName} · tap to open"
                        transfer.linkOpened -> "Opened on ${transfer.peerName}"
                        else -> "Sent to ${transfer.peerName}"
                    }
                    else -> "Sending to ${transfer.peerName}…"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (transfer.status is Transfer.Status.Failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    Column(Modifier.fillMaxWidth()) {
        Text("${if (transfer.incoming) "↓" else "↑"} ${transfer.summary}", maxLines = 1, overflow = TextOverflow.Ellipsis)
        when (val status = transfer.status) {
            Transfer.Status.Waiting -> Text(
                if (transfer.incoming) "Waiting…" else "Waiting for ${transfer.peerName} to accept…",
                style = MaterialTheme.typography.bodySmall,
            )
            Transfer.Status.Active -> {
                LinearProgressIndicator(progress = { transfer.fraction }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                Text("${formatBytes(transfer.doneBytes)} of ${formatBytes(transfer.totalBytes)}",
                    style = MaterialTheme.typography.bodySmall)
            }
            Transfer.Status.Done -> Text(
                if (transfer.incoming) "From ${transfer.peerName} · in Downloads/BubbleDrop" else "Sent to ${transfer.peerName}",
                style = MaterialTheme.typography.bodySmall,
            )
            is Transfer.Status.Failed -> Text(status.message, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun BubbleDropTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}
