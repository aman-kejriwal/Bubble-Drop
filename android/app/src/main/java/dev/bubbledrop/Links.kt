package dev.bubbledrop

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat

/**
 * Opens links from paired devices in the default browser. Android only lets an app start
 * activities while it's on screen, so otherwise the link arrives as a notification to tap.
 */
object Links {
    /** True while MainActivity is resumed. */
    @Volatile var appVisible = false

    /** Returns whether the browser was opened (false: a notification was posted instead). */
    fun open(context: Context, sender: DeviceInfo, url: String): Boolean {
        val view = viewIntent(url)
        if (appVisible && runCatching { context.startActivity(view) }.isSuccess) return true

        val tap = PendingIntent.getActivity(
            context, url.hashCode(), view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_INCOMING)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentTitle("Link from ${sender.name}")
            .setContentText(url)
            .setStyle(NotificationCompat.BigTextStyle().bigText(url))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .addAction(0, "Open", tap)
            .build()
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                .notify(LINK_NOTIFICATION_BASE + (url.hashCode() and 0xffff), notification)
        }
        return false
    }

    fun viewIntent(url: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private const val LINK_NOTIFICATION_BASE = 1000
}
