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

/**
 * A Quick Settings tile that goes straight to the trackpad for the paired Mac, without a stop at
 * the app's main screen. The trackpad is a screen of the app, so the tile opens it there.
 */
class TrackpadTile : TileService() {
    override fun onStartListening() {
        val tile = qsTile ?: return
        tile.state = if (Trackpad.state.value == Trackpad.State.Idle) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.label = getString(R.string.trackpad_tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_trackpad)
        tile.updateTile()
    }

    override fun onClick() {
        // From the lock screen, after the phone is unlocked.
        if (isLocked) unlockAndRun(::open) else open()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun open() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_OPEN, true)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        private const val TAG = "TrackpadTile"

        /** On the intent that opens MainActivity: go straight to the trackpad. */
        const val EXTRA_OPEN = "dev.bubbledrop.OPEN_TRACKPAD"

        /** Asks the system to add the tile to Quick Settings (Android 13+); it shows its own confirmation. */
        fun requestAdd(context: Context) {
            if (Build.VERSION.SDK_INT < 33) return
            context.getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(context, TrackpadTile::class.java),
                context.getString(R.string.trackpad_tile_label),
                Icon.createWithResource(context, R.drawable.ic_tile_trackpad),
                context.mainExecutor,
            ) { result -> Log.i(TAG, "add tile result $result") }
        }
    }
}
