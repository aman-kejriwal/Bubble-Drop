package dev.bubbledrop

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Text sent between paired devices' clipboards (PROTOCOL.md `/text`). Android lets an app write
 * the clipboard at any time, but only read it while the app is on screen, so text is sent from
 * the app (or shared to it) rather than picked up in the background.
 */
object Texts {
    /** Puts [text] on this phone's clipboard; returns whether that worked. Safe from any thread. */
    fun copy(context: Context, text: String): Boolean {
        fun set() = runCatching {
            context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("BubbleDrop", text))
        }.isSuccess
        if (Looper.myLooper() == Looper.getMainLooper()) return set()
        var copied = false
        val done = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            copied = set()
            done.countDown()
        }
        return done.await(5, TimeUnit.SECONDS) && copied
    }

    /** Text arrived from [sender]: copy it, and say so in a notification unless the app is showing it. */
    fun received(context: Context, sender: DeviceInfo, text: String): Boolean {
        if (!copy(context, text)) return false
        if (Links.appVisible) return true
        val open = PendingIntent.getActivity(
            context, 20, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_INCOMING)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentTitle("Copied text from ${sender.name}")
            .setContentText(preview(text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(1000)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification) }
        return true
    }

    /** The text on this phone's clipboard, if any. Only works while the app is on screen. */
    fun clipboard(context: Context): String? = runCatching {
        context.getSystemService(ClipboardManager::class.java).primaryClip
            ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** The start of [text] on one line, for a transfer's title. */
    fun preview(text: String): String {
        val line = text.lineSequence().joinToString(" ").trim()
        return if (line.length > 80) line.take(80) + "…" else line.ifEmpty { "Text" }
    }

    private const val NOTIFICATION_ID = 2000
}
