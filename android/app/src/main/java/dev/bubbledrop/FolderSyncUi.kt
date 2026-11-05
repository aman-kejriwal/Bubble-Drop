package dev.bubbledrop

import android.Manifest
import android.os.Build
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** "Folder Sync" on the main screen: pick, show and stop the folder kept in sync with the paired device. */
@Composable
fun FolderSyncSection(pairedName: String?) {
    val context = LocalContext.current
    val ios = Ios.colors
    val status by FolderSync.status.collectAsState()
    var pickError by remember { mutableStateOf<String?>(null) }
    var stopping by remember { mutableStateOf(false) }
    val other = pairedName ?: "your Mac"

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val folder = FolderSync.folderFromTree(uri)
        pickError = if (folder == null) "Choose a folder on this phone's storage, not the whole storage or a cloud app." else null
        if (folder != null) FolderSync.setFolder(context, folder)
    }
    val legacyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        FolderSync.refreshAccess(context)
    }
    fun allowAccess() {
        if (Build.VERSION.SDK_INT >= 30) context.startActivity(FolderSync.accessSettingsIntent(context))
        else legacyPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    val folder = status.folder
    GroupedSection(
        header = "Folder Sync",
        footer = if (folder == null) "Keep a folder identical on this phone and $other. Changes on either side " +
            "sync within seconds while BubbleDrop is on and both are on the same Wi-Fi. Replaced and deleted files " +
            "are kept in a hidden .bubbledrop-trash folder for 30 days."
        else "Syncs while BubbleDrop is on and $other is on this Wi-Fi. Hidden files are skipped.",
        modifier = Modifier.animateContentSize(),
    ) {
        if (folder == null) {
            ListRow(
                title = "Choose Folder…",
                subtitle = pickError,
                subtitleColor = ios.red,
                leading = { IconTile(Icons.Rounded.FolderOpen, ios.blue) },
                chevron = true,
                onClick = { picker.launch(null) },
            )
            return@GroupedSection
        }

        ListRow(
            title = folder.substringAfterLast('/'),
            subtitle = folder.removePrefix("/storage/emulated/0/").ifEmpty { folder },
            leading = { IconTile(Icons.Rounded.Folder, ios.blue) },
            trailing = { TextAction("Change", style = IosType.body) { picker.launch(null) } },
            divider = true,
        )
        if (status.phase == FolderSync.Phase.NoAccess) {
            ListRow(
                title = "Allow File Access",
                subtitle = "BubbleDrop needs access to all files to watch this folder and keep it in sync",
                leading = { IconTile(Icons.Rounded.PriorityHigh, ios.orange) },
                trailing = { IosButton("Allow", ::allowAccess, style = ButtonStyle.Tinted, large = false) },
                divider = true,
            )
        } else {
            val files = if (status.fileCount == 1) "1 file" else "${status.fileCount} files"
            ListRow(
                title = when (status.phase) {
                    FolderSync.Phase.Syncing -> "Syncing…"
                    FolderSync.Phase.Waiting -> "Waiting for $other"
                    FolderSync.Phase.NotSetUpThere -> "Not set up on $other"
                    FolderSync.Phase.Off -> "Paused"
                    else -> if (status.error != null) "Couldn't sync everything" else "Up to date"
                },
                subtitle = status.error ?: when (status.phase) {
                    FolderSync.Phase.Waiting -> "$files · changes sync when it's on this Wi-Fi"
                    FolderSync.Phase.NotSetUpThere -> "Choose a folder in BubbleDrop on $other to start syncing"
                    FolderSync.Phase.Off -> "Turn on BubbleDrop to sync"
                    else -> files + (status.peerFolder?.let { " · with “$it”" } ?: "") + (status.lastSynced?.let {
                        " · " + DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                    } ?: "")
                },
                subtitleColor = if (status.error != null) ios.red else ios.secondaryLabel,
                leading = { IconTile(Icons.Rounded.Sync, ios.green) },
                trailing = {
                    if (status.phase == FolderSync.Phase.Syncing) ActivityIndicator(18.dp)
                    else if (status.phase != FolderSync.Phase.Off) TextAction("Sync Now", style = IosType.body) { FolderSync.syncNow() }
                },
                divider = true,
            )
        }
        ListRow(
            title = "Stop Syncing",
            titleColor = ios.red,
            onClick = { stopping = true },
        )
    }

    if (stopping) {
        IosAlert(
            title = "Stop syncing this folder?",
            message = "The files stay on both devices; they just won't be kept in sync anymore.",
            onDismiss = { stopping = false },
            actions = listOf(
                AlertAction("Cancel") { stopping = false },
                AlertAction("Stop", bold = true, destructive = true) {
                    FolderSync.stopSyncing(context)
                    stopping = false
                },
            ),
        )
    }
}
