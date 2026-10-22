package dev.mydrop

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Keeps the receiver and Bonjour advertising alive while MyDrop is on. */
class MyDropService : Service() {
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val stop = PendingIntent.getService(
            this, 0, Intent(this, MyDropService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("MyDrop is on")
            .setContentText("Visible to nearby devices as ${Graph.device.name}")
            .setContentIntent(open)
            .addAction(0, "Turn off", stop)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)

        multicastLock = getSystemService(WifiManager::class.java).createMulticastLock("mydrop").apply {
            setReferenceCounted(false)
            acquire()
        }
        try {
            val port = Graph.server.start()
            Log.i("MyDropService", "receiving on port $port")
            Graph.discovery.register(port)
            Graph.discovery.startBrowsing()
            _running.value = true
        } catch (e: Exception) {
            Log.e("MyDropService", "start failed", e)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        Graph.discovery.stop()
        Graph.server.stop()
        multicastLock?.release()
        _running.value = false
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "dev.mydrop.STOP"
        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, MyDropService::class.java))

        fun stop(context: Context) = context.stopService(Intent(context, MyDropService::class.java))
    }
}
