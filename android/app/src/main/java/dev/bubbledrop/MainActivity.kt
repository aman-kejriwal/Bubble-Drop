package dev.bubbledrop

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                        // Lets the Mac's call screen answer and end calls.
                        if (checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
                            add(Manifest.permission.ANSWER_PHONE_CALLS)
                        }
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
        if (intent?.getBooleanExtra(PairSuggestions.EXTRA_PAIR, false) == true) {
            intent.removeExtra(PairSuggestions.EXTRA_PAIR)
            Graph.pairSuggestions.pair()
            return
        }
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


@Composable
private fun MainScreen(sharedUris: List<Uri>, sharedLink: String?, onClearShared: () -> Unit) {
    val context = LocalContext.current
    val ios = Ios.colors
    val peers by Graph.discovery.peers.collectAsState()
    val transfers by Graph.transfers.transfers.collectAsState()
    val running by BubbleDropService.running.collectAsState()
    val pending by Graph.prompts.current.collectAsState()
    val trusted by Graph.trust.devices.collectAsState()
    val suggestion by Graph.pairSuggestions.suggestion.collectAsState()
    val suggestionStatus by Graph.pairSuggestions.status.collectAsState()
    val nearby = remember(peers, trusted) { peers.byPriority(trusted) }
    var pickTarget by remember { mutableStateOf<Peer?>(null) }
    var mirrorTarget by remember { mutableStateOf<Peer?>(null) }
    var unpairing by remember { mutableStateOf<TrustedDevice?>(null) }
    val mirroringTo by MirrorService.active.collectAsState()
    val mirrorError by MirrorService.error.collectAsState()
    val controlEnabled by ControlService.enabled.collectAsState()
    val relayEnabled by NotificationRelay.enabled.collectAsState()
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

    fun tapPeer(peer: Peer) {
        // Nothing works with an unpaired device; tapping one pairs it instead.
        if (trusted.none { it.fingerprint == peer.fingerprint }) {
            Graph.pairSuggestions.pair(peer)
        } else if (sharedLink != null) {
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

    val listState = rememberLazyListState()
    val density = LocalDensity.current
    // The large title scrolls away and the compact centered title fades into the bar, as in UINavigationBar.
    val collapsed by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 ||
                listState.firstVisibleItemScrollOffset > with(density) { 36.dp.toPx() }
        }
    }
    val barAlpha by animateFloatAsState(if (collapsed) 1f else 0f, tween(180), label = "bar")
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(Modifier.fillMaxSize().background(ios.groupedBackground)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = statusBar + 44.dp, bottom = navBar + 32.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            item(key = "title") {
                Text("BubbleDrop", style = IosType.largeTitle, color = ios.label,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 0.dp))
            }

            suggestion?.let { peer ->
                item(key = "suggestion") { PairSuggestionBanner(peer, suggestionStatus) }
            }

            if (sharing) {
                item(key = "share") { ShareBanner(sharedUris.size, sharedLink, onClearShared) }
            } else {
                item(key = "hero") { HeroSection(running) }
            }

            mirroringTo?.let { name ->
                item(key = "mirroring") {
                    GroupedSection(header = "Screen Mirroring") {
                        ListRow(
                            title = "Mirroring to $name",
                            subtitle = "Your screen is visible on $name",
                            leading = { IconTile(Icons.Rounded.Cast, ios.green) },
                            trailing = {
                                IosButton("Stop", { MirrorService.stop(context) }, style = ButtonStyle.Tinted,
                                    color = ios.red, large = false)
                            },
                            divider = true,
                        )
                        ListRow(
                            title = "Remote Control",
                            subtitle = if (controlEnabled) "$name can use its mouse and keyboard here"
                                else "Turn on \"BubbleDrop remote control\" to control this phone from $name",
                            leading = { IconTile(Icons.Rounded.Mouse, ios.indigo) },
                            trailing = {
                                if (controlEnabled) Badge("On", ios.green)
                                else IosButton("Turn On", { ControlService.openSettings(context) },
                                    style = ButtonStyle.Tinted, large = false)
                            },
                        )
                    }
                }
            }
            mirrorError?.let { message ->
                item(key = "mirror-error") {
                    GroupedSection {
                        ListRow(
                            title = "Mirroring Stopped",
                            subtitle = message,
                            leading = { IconTile(Icons.Rounded.PriorityHigh, ios.red) },
                            trailing = { TextAction("OK", style = IosType.headline) { MirrorService.clearError() } },
                        )
                    }
                }
            }

            item(key = "nearby") {
                GroupedSection(
                    header = "Nearby Devices",
                    headerAction = if (running && peers.isNotEmpty()) ({ ActivityIndicator(14.dp) }) else null,
                    footer = if (peers.isEmpty()) null else {
                        val anyPaired = nearby.any { p -> trusted.any { it.fingerprint == p.fingerprint } }
                        (if (anyPaired) "Paired devices are listed first. " else "") +
                            (if (sharing) "Tap a device to send." else "Tap a device to choose files to send it.") +
                            if (nearby.any { p -> trusted.none { it.fingerprint == p.fingerprint } })
                                " Devices must be paired before you can share or mirror." else ""
                    },
                    modifier = Modifier.animateContentSize(spring(stiffness = Spring.StiffnessMediumLow)),
                ) {
                    if (peers.isEmpty()) {
                        EmptyNearby(running)
                    }
                    nearby.forEachIndexed { i, peer ->
                        val paired = trusted.any { it.fingerprint == peer.fingerprint }
                        val pairingThis = suggestion?.id == peer.id && suggestionStatus == PairSuggestions.Status.Pairing
                        ListRow(
                            title = peer.name,
                            subtitle = if (paired) listOfNotNull("Paired", peer.model.ifBlank { null }).joinToString(" · ")
                                else if (pairingThis) "Pairing…" else "Not paired",
                            leading = { DeviceAvatar(peer.platform) },
                            titleAccessory = if (paired) ({
                                Icon(Icons.Rounded.Lock, "Paired", tint = ios.green, modifier = Modifier.size(14.dp))
                            }) else null,
                            trailing = {
                                when {
                                    pairingThis -> ActivityIndicator(18.dp)
                                    !paired -> IosButton("Pair", { Graph.pairSuggestions.pair(peer) }, large = false)
                                    sharing -> IosButton("Send", { tapPeer(peer) }, large = false)
                                    peer.platform == "macos" && mirroringTo == null ->
                                        IosButton("Mirror", { mirror(peer) }, style = ButtonStyle.Tinted, large = false)
                                }
                            },
                            chevron = paired && !sharing,
                            divider = i < nearby.lastIndex,
                            onClick = { tapPeer(peer) },
                        )
                    }
                }
            }

            if (sharing) {
                item(key = "hero") { HeroSection(running) }
            }

            item(key = "mac") {
                GroupedSection(
                    header = "On Your Mac",
                    footer = "Show this phone's notifications on your paired Macs and reply from there. " +
                        "Remote control lets your Mac drive this phone while you mirror its screen.",
                ) {
                    ListRow(
                        title = "Notifications",
                        leading = { IconTile(Icons.Rounded.Notifications, ios.red) },
                        trailing = { Text(if (relayEnabled) "On" else "Off", style = IosType.body, color = ios.secondaryLabel) },
                        chevron = true,
                        divider = true,
                        onClick = { NotificationRelay.openSettings(context) },
                    )
                    ListRow(
                        title = "Remote Control",
                        leading = { IconTile(Icons.Rounded.Mouse, ios.indigo) },
                        trailing = { Text(if (controlEnabled) "On" else "Off", style = IosType.body, color = ios.secondaryLabel) },
                        chevron = true,
                        onClick = { ControlService.openSettings(context) },
                    )
                }
            }

            if (trusted.isNotEmpty()) {
                item(key = "paired") {
                    GroupedSection(
                        header = "Paired Devices",
                        footer = "Paired devices are verified with a code once, then connect securely without asking again.",
                        modifier = Modifier.animateContentSize(),
                    ) {
                        val recent = trusted.sortedByDescending { it.lastUsed }
                        recent.forEachIndexed { i, device ->
                            val here = peers.any { it.fingerprint == device.fingerprint }
                            ListRow(
                                title = device.name,
                                subtitle = if (here) "Nearby now" else "Last used " +
                                    DateUtils.getRelativeTimeSpanString(device.lastUsed, System.currentTimeMillis(),
                                        DateUtils.MINUTE_IN_MILLIS).toString().replaceFirstChar { it.lowercase() },
                                subtitleColor = if (here) ios.green else ios.secondaryLabel,
                                leading = { DeviceAvatar(device.platform, 32.dp) },
                                trailing = { TextAction("Unpair", color = ios.red, style = IosType.body) { unpairing = device } },
                                divider = i < recent.lastIndex,
                            )
                        }
                    }
                }
            }

            if (transfers.isNotEmpty()) {
                item(key = "transfers") {
                    GroupedSection(
                        header = "Transfers",
                        headerAction = { TextAction("Clear") { Graph.transfers.clearFinished() } },
                        modifier = Modifier.animateContentSize(),
                    ) {
                        transfers.forEachIndexed { i, transfer -> TransferRow(transfer, divider = i < transfers.lastIndex) }
                    }
                }
            }
        }

        // Navigation bar: transparent over the large title, frosted once content scrolls beneath it.
        Column(Modifier.fillMaxWidth().graphicsLayer { alpha = barAlpha }) {
            Box(
                Modifier.fillMaxWidth().background(ios.groupedBackground.copy(alpha = 0.94f))
                    .padding(top = statusBar).height(44.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("BubbleDrop", style = IosType.headline, color = ios.label)
            }
            Hairline()
        }

        DropOverlay(
            offer = pending?.takeIf { it.kind is PromptKind.Offer },
            transfer = spotlight,
            onDismissTransfer = { dismissed = dismissed + it.id },
        )
    }

    unpairing?.let { device ->
        IosAlert(
            title = "Unpair ${device.name}?",
            message = "You'll need to confirm a pairing code again the next time you share with it.",
            onDismiss = { unpairing = null },
            actions = listOf(
                AlertAction("Cancel") { unpairing = null },
                AlertAction("Unpair", bold = true, destructive = true) {
                    Graph.trust.remove(device.fingerprint)
                    unpairing = null
                },
            ),
        )
    }

    pending?.takeIf { it.kind !is PromptKind.Offer }?.let { prompt -> PromptDialog(prompt) }
}

/** "Pair with …?" for an unpaired device found right after BubbleDrop was turned on. */
@Composable
private fun PairSuggestionBanner(peer: Peer, status: PairSuggestions.Status) {
    val ios = Ios.colors
    GroupedSection(
        header = "New Device Nearby",
        footer = "You'll compare a 6-digit code on both screens. Pairing is only needed once.",
    ) {
        ListRow(
            title = "Pair with ${peer.name}?",
            subtitle = when (status) {
                PairSuggestions.Status.Pairing -> "Connecting…"
                is PairSuggestions.Status.Failed -> status.message
                PairSuggestions.Status.Idle -> peer.model.ifBlank { null } ?: "Not paired yet"
            },
            subtitleColor = if (status is PairSuggestions.Status.Failed) ios.red else ios.secondaryLabel,
            leading = { DeviceAvatar(peer.platform) },
            trailing = if (status == PairSuggestions.Status.Pairing) ({ ActivityIndicator(18.dp) }) else null,
            below = if (status == PairSuggestions.Status.Pairing) null else ({
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    IosButton("Not Now", { Graph.pairSuggestions.notNow() }, Modifier.weight(1f),
                        style = ButtonStyle.Tinted, color = ios.gray, large = false)
                    IosButton(if (status is PairSuggestions.Status.Failed) "Try Again" else "Pair",
                        { Graph.pairSuggestions.pair() }, Modifier.weight(1f), large = false)
                }
            }),
        )
    }
}

/** This phone, AirDrop-style: avatar with a radar pulse while visible, and the visibility switch. */
@Composable
private fun HeroSection(running: Boolean) {
    val context = LocalContext.current
    val ios = Ios.colors
    GroupedSection(
        footer = if (running) "Macs on the same Wi-Fi can see this phone and send it files."
            else "BubbleDrop is off. This phone won't appear on other devices or receive files.",
    ) {
        Column(
            Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RadarAvatar(running)
            Spacer(Modifier.height(10.dp))
            Text(Graph.device.name, style = IosType.title2, color = ios.label, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val dot by animateColorAsState(if (running) ios.green else ios.gray, label = "dot")
                Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(6.dp))
                Text(if (running) "Visible to nearby devices" else "Not visible", style = IosType.subheadline,
                    color = ios.secondaryLabel)
            }
        }
        Hairline(Modifier.padding(start = 16.dp))
        ListRow(
            title = "Discoverable",
            trailing = {
                IosSwitch(running) { if (it) BubbleDropService.start(context) else BubbleDropService.stop(context) }
            },
            divider = BubbleDropTile.canRequestAdd,
        )
        if (BubbleDropTile.canRequestAdd) {
            ListRow(
                title = "Add to Quick Settings",
                titleColor = ios.blue,
                onClick = { BubbleDropTile.requestAdd(context) },
            )
        }
    }
}

@Composable
private fun RadarAvatar(active: Boolean) {
    val ios = Ios.colors
    val pulse by rememberInfiniteTransition(label = "radar")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "pulse")
    val saturation by animateFloatAsState(if (active) 1f else 0f, tween(400), label = "sat")
    Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
        if (active) {
            Canvas(Modifier.fillMaxSize()) {
                val base = 42.dp.toPx()
                for (i in 0 until 3) {
                    val t = (pulse + i / 3f) % 1f
                    drawCircle(ios.blue.copy(alpha = (1f - t) * 0.28f), radius = base + (size.minDimension / 2 - base) * t)
                }
            }
        }
        Box(Modifier.graphicsLayer { alpha = 0.45f + 0.55f * saturation }) {
            DeviceAvatar("android", 84.dp)
        }
    }
}

@Composable
private fun ShareBanner(count: Int, link: String?, onCancel: () -> Unit) {
    val ios = Ios.colors
    GroupedSection(footer = "Choose a nearby device below.") {
        ListRow(
            title = if (link != null) "Open Link on a Device" else if (count == 1) "Send 1 Item" else "Send $count Items",
            subtitle = link ?: "Ready to share with BubbleDrop",
            leading = { IconTile(if (link != null) Icons.Rounded.Link else Icons.Rounded.Description, ios.blue) },
            trailing = { TextAction("Cancel", style = IosType.body, onClick = onCancel) },
        )
    }
}

@Composable
private fun EmptyNearby(running: Boolean) {
    val ios = Ios.colors
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (running) ActivityIndicator(24.dp)
        else Icon(Icons.Rounded.WifiOff, null, tint = ios.tertiaryLabel, modifier = Modifier.size(30.dp))
        Spacer(Modifier.height(12.dp))
        Text(if (running) "Looking for Devices" else "BubbleDrop Is Off", style = IosType.headline, color = ios.label)
        Spacer(Modifier.height(4.dp))
        Text(
            if (running) "Open BubbleDrop on your Mac and make sure both are on the same Wi-Fi."
            else "Turn on Discoverable to find nearby devices.",
            style = IosType.footnote, color = ios.secondaryLabel, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PromptDialog(prompt: Prompts.Pending) {
    val respond = { accept: Boolean -> Graph.prompts.respond(prompt.id, accept) }
    when (val kind = prompt.kind) {
        is PromptKind.Offer -> {
            val ask = kind.request
            IosAlert(
                title = "${ask.sender.name} wants to send you " +
                    if (ask.files.size == 1) ask.files[0].name else "${ask.files.size} files",
                message = "${formatBytes(ask.totalSize)} · saved to Downloads/BubbleDrop",
                actions = listOf(AlertAction("Decline") { respond(false) }, AlertAction("Accept", bold = true) { respond(true) }),
            )
        }
        is PromptKind.PairIncoming -> PairingDialog(
            title = "Pair with ${kind.sender.name}?",
            detail = "Only tap Pair if ${kind.sender.name} shows this same code.",
            code = kind.code, respond = respond,
        )
        is PromptKind.PairOutgoing -> PairingDialog(
            title = "Pair with ${kind.peerName}?",
            detail = "Only tap Pair if ${kind.peerName} shows this same code. You only need to pair once.",
            code = kind.code, respond = respond,
        )
    }
}

@Composable
private fun PairingDialog(title: String, detail: String, code: String, respond: (Boolean) -> Unit) {
    val ios = Ios.colors
    IosAlert(
        title = title,
        message = detail,
        actions = listOf(AlertAction("Cancel") { respond(false) }, AlertAction("Pair", bold = true) { respond(true) }),
    ) {
        Spacer(Modifier.height(16.dp))
        Text(
            formatCode(code),
            style = IosType.largeTitle.copy(fontFamily = FontFamily.Monospace, letterSpacing = 4.sp),
            color = ios.label,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(ios.fill.copy(alpha = 0.5f))
                .padding(horizontal = 20.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun TransferRow(transfer: Transfer, divider: Boolean) {
    val context = LocalContext.current
    val ios = Ios.colors
    val status = transfer.status
    val link = transfer.link
    val (icon, tint) = when {
        status is Transfer.Status.Failed -> Icons.Rounded.PriorityHigh to ios.red
        link != null -> Icons.Rounded.Link to ios.indigo
        transfer.incoming -> Icons.Rounded.ArrowDownward to ios.green
        else -> Icons.Rounded.ArrowUpward to ios.blue
    }
    val subtitle = when (status) {
        is Transfer.Status.Failed -> status.message
        Transfer.Status.Waiting -> when {
            link != null -> "Sending to ${transfer.peerName}…"
            transfer.incoming -> "Waiting…"
            else -> "Waiting for ${transfer.peerName} to accept…"
        }
        Transfer.Status.Active -> "${formatBytes(transfer.doneBytes)} of ${formatBytes(transfer.totalBytes)}"
        Transfer.Status.Done -> when {
            link != null && transfer.incoming -> "From ${transfer.peerName}"
            link != null && transfer.linkOpened -> "Opened on ${transfer.peerName}"
            link != null -> "Sent to ${transfer.peerName}"
            transfer.incoming -> "From ${transfer.peerName} · in Downloads/BubbleDrop"
            else -> "Sent to ${transfer.peerName}"
        }
    }
    val open: (() -> Unit)? = when {
        link != null -> ({ runCatching { context.startActivity(Links.viewIntent(link)) } })
        transfer.incoming && status == Transfer.Status.Done -> ({ openReceived(context, transfer) })
        else -> null
    }
    ListRow(
        title = transfer.summary,
        subtitle = subtitle,
        subtitleColor = if (status is Transfer.Status.Failed) ios.red else ios.secondaryLabel,
        leading = { IconTile(icon, tint) },
        trailing = if (status == Transfer.Status.Done && link == null) ({
            Icon(Icons.Rounded.CheckCircle, null, tint = ios.green, modifier = Modifier.size(20.dp))
        }) else null,
        chevron = open != null,
        divider = divider,
        onClick = open,
        below = if (status == Transfer.Status.Active) ({
            IosProgressBar(transfer.fraction, Modifier.padding(top = 6.dp, bottom = 2.dp),
                color = if (transfer.incoming) ios.green else ios.blue)
        }) else null,
    )
}
