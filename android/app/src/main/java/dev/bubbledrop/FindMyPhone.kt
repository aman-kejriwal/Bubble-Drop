package dev.bubbledrop

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Find My Phone (PROTOCOL.md "Find"): a paired Mac makes the phone ring at full volume for
 * 30 seconds, even on silent, with a notification to stop it.
 */
@SuppressLint("StaticFieldLeak")
object FindMyPhone {
    private const val NOTIFICATION_ID = 4
    private const val RING_MS = 30_000L

    private val main = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private var savedVolume: Int? = null
    private var context: Context? = null
    private val autoStop = Runnable { stop() }

    fun ring(context: Context, macName: String) = main.post {
        stop()
        val app = context.applicationContext
        this.context = app
        val audio = app.getSystemService(AudioManager::class.java)
        // Loud enough to find under a cushion: the alarm stream ignores silent mode; restore it after.
        savedVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0) }
        val uri = RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ringtone = RingtoneManager.getRingtone(app, uri)?.apply {
            audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            isLooping = true
            play()
        }
        app.getSystemService(Vibrator::class.java)?.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0),
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
        )
        notify(app, macName)
        main.postDelayed(autoStop, RING_MS)
    }

    fun stop() {
        main.removeCallbacks(autoStop)
        ringtone?.stop()
        ringtone = null
        val app = context ?: return
        context = null
        app.getSystemService(Vibrator::class.java)?.cancel()
        savedVolume?.let { volume ->
            runCatching { app.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, volume, 0) }
        }
        savedVolume = null
        app.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun notify(context: Context, macName: String) {
        val stop = PendingIntent.getBroadcast(context, 20, Intent(context, FindStopReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("$macName is looking for this phone")
            .setContentText("Tap to stop the sound")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(stop)
            .setDeleteIntent(stop)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification) }
    }
}

class FindStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = FindMyPhone.stop()
}

/**
 * A paired Mac asked to show this phone's screen (from its widget). Mirroring needs the user's
 * consent on the phone, so this only asks: the app, or a notification, offers to start it.
 */
object MirrorRequests {
    private const val NOTIFICATION_ID = 5
    const val EXTRA_FINGERPRINT = "dev.bubbledrop.MIRROR_TO"

    private val _pending = MutableStateFlow<String?>(null)
    /** The fingerprint of the Mac waiting to be mirrored to; the main screen starts consent for it. */
    val pending: StateFlow<String?> = _pending

    fun request(context: Context, fingerprint: String, macName: String) {
        if (Links.appVisible) {
            _pending.value = fingerprint
            return
        }
        val open = PendingIntent.getActivity(
            context, 30,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_FINGERPRINT, fingerprint),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Mirror this phone to $macName?")
            .setContentText("Tap to start showing your screen on $macName")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification) }
    }

    /** From the notification's tap: offer mirroring now that the app is open. */
    fun opened(fingerprint: String) {
        _pending.value = fingerprint
    }

    fun handled() {
        _pending.value = null
    }
}
