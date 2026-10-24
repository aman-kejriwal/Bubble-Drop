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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The Quick Settings tile: turns BubbleDrop on and off, and shows whether it's visible. */
class BubbleDropTile : TileService() {
    private var watch: Job? = null

    override fun onStartListening() {
        watch = CoroutineScope(Dispatchers.Main).launch {
            BubbleDropService.running.collect(::render)
        }
    }

    override fun onStopListening() {
        watch?.cancel()
        watch = null
    }

    override fun onClick() {
        if (BubbleDropService.running.value) {
            BubbleDropService.stop(this)
            return
        }
        // Android can refuse a foreground-service start from here; opening the app starts it instead.
        runCatching { BubbleDropService.start(this) }
            .onFailure { Log.w(TAG, "couldn't start from the tile, opening the app", it); openApp() }
    }

    private fun render(running: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        tile.subtitle = if (running) "Visible" else "Off"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile)
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        private const val TAG = "BubbleDropTile"

        /** Whether this Android version lets the app offer to add the tile itself. */
        val canRequestAdd: Boolean get() = Build.VERSION.SDK_INT >= 33

        /** Asks the system to add the tile to Quick Settings (Android 13+); it shows its own confirmation. */
        fun requestAdd(context: Context) {
            if (Build.VERSION.SDK_INT < 33) return
            context.getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(context, BubbleDropTile::class.java),
                context.getString(R.string.app_name),
                Icon.createWithResource(context, R.drawable.ic_tile),
                context.mainExecutor,
            ) { result -> Log.i(TAG, "add tile result $result") }
        }
    }
}
