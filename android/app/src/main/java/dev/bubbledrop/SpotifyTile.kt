package dev.bubbledrop

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * "Play on Mac": one tap in Quick Settings moves the song Spotify is playing to the paired Mac,
 * from wherever the phone is, without opening the app.
 */
class SpotifyTile : TileService() {
    override fun onStartListening() = update()

    private fun update() {
        val tile = qsTile ?: return
        val now = SpotifyHandoff.nowPlaying.value
        val status = SpotifyHandoff.status.value
        tile.label = getString(R.string.spotify_tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_music)
        tile.state = when {
            !NotificationRelay.enabled.value -> Tile.STATE_INACTIVE
            now == null -> Tile.STATE_UNAVAILABLE
            else -> Tile.STATE_ACTIVE
        }
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = when {
                status is SpotifyHandoff.Status.Sending -> "Sending…"
                status is SpotifyHandoff.Status.Done -> "On ${status.macName}"
                status is SpotifyHandoff.Status.Failed -> "Didn't work"
                !NotificationRelay.enabled.value -> "Tap to set up"
                now == null -> "Nothing playing"
                else -> now.title
            }
        }
        tile.updateTile()
    }

    override fun onClick() {
        // Without Notification access the phone can't see what Spotify plays: set it up in the app.
        if (!NotificationRelay.enabled.value) return openApp()
        SpotifyHandoff.handoff()
        update()
        // Follow the result on the tile while Quick Settings stays open.
        Graph.scope.launchStatusWatch(applicationContext)
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 3, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        private const val TAG = "SpotifyTile"

        /** Asks the system to add the tile to Quick Settings (Android 13+); it shows its own confirmation. */
        fun requestAdd(context: Context) {
            if (Build.VERSION.SDK_INT < 33) return
            context.getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(context, SpotifyTile::class.java),
                context.getString(R.string.spotify_tile_label),
                Icon.createWithResource(context, R.drawable.ic_tile_music),
                context.mainExecutor,
            ) { result -> Log.i(TAG, "add tile result $result") }
        }

        val canRequestAdd: Boolean get() = Build.VERSION.SDK_INT >= 33
    }
}

/** Refreshes the tile each time the handoff's status changes, until it settles. */
private fun CoroutineScope.launchStatusWatch(context: Context) = launch {
    SpotifyHandoff.status.collect { status ->
        runCatching { TileService.requestListeningState(context, ComponentName(context, SpotifyTile::class.java)) }
        if (status == SpotifyHandoff.Status.Idle) cancel()
    }
}
