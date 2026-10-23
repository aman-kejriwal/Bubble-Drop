package dev.bubbledrop

import android.app.Notification
import android.app.NotificationManager
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Forwards this phone's notifications to paired Macs (PROTOCOL.md "Notifications") and carries
 * out what the Mac sends back: replies, notification buttons and dismissals. Needs the user to
 * grant Notification access.
 */
class NotificationRelay : NotificationListenerService() {
    private val icons = ConcurrentHashMap<String, String>()

    override fun onListenerConnected() {
        instance = this
        _enabled.value = true
    }

    override fun onListenerDisconnected() {
        instance = null
        _enabled.value = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        if (!shouldForward(sbn, rankingMap)) return
        val notification = describe(sbn) ?: return
        forward(JSONObject().put("event", "posted").put("notification", notification))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (!shouldForward(sbn, null)) return
        forward(JSONObject().put("event", "removed").put("key", sbn.key))
    }

    private fun shouldForward(sbn: StatusBarNotification, rankingMap: RankingMap?): Boolean {
        val n = sbn.notification
        if (sbn.packageName == packageName) return false
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        // Ongoing status (music, navigation, downloads…) isn't news; calls are the exception.
        if (sbn.isOngoing && n.category != Notification.CATEGORY_CALL) return false
        if (rankingMap != null) {
            val ranking = Ranking()
            if (rankingMap.getRanking(sbn.key, ranking) && ranking.importance <= NotificationManager.IMPORTANCE_MIN) {
                return false
            }
        }
        return true
    }

    private fun describe(sbn: StatusBarNotification): JSONObject? {
        val n = sbn.notification
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim()
        if (title.isNullOrEmpty() && text.isNullOrEmpty()) return null
        val actions = JSONArray()
        n.actions.orEmpty().forEachIndexed { index, action ->
            val title = action.title?.toString()?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
            actions.put(JSONObject().put("index", index).put("title", title)
                .put("reply", action.remoteInputs.orEmpty().any { it.allowFreeFormInput }))
        }
        return JSONObject()
            .put("key", sbn.key)
            .put("package", sbn.packageName)
            .put("app", appLabel(sbn.packageName))
            .put("title", title.orEmpty())
            .put("text", text.orEmpty())
            .put("subText", extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty())
            .put("time", sbn.postTime)
            .put("category", n.category.orEmpty())
            .put("actions", actions)
            .apply { appIcon(sbn.packageName)?.let { put("icon", it) } }
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /** The app's icon as a small base64 PNG, cached per app. */
    private fun appIcon(pkg: String): String? = icons[pkg] ?: runCatching {
        val drawable = packageManager.getApplicationIcon(pkg)
        val bitmap = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, ICON_SIZE, ICON_SIZE)
        drawable.draw(Canvas(bitmap))
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        Base64.encodeToString(png, Base64.NO_WRAP)
    }.getOrNull()?.also { icons[pkg] = it }

    /** Runs the notification's action [index] from the Mac, filling in [reply] for reply actions. */
    fun perform(key: String, index: Int, reply: String?): Boolean {
        val sbn = runCatching { getActiveNotifications(arrayOf(key)) }.getOrNull()?.firstOrNull() ?: return false
        val action = sbn.notification.actions?.getOrNull(index) ?: return false
        val fill = Intent()
        val inputs = action.remoteInputs
        if (reply != null && !inputs.isNullOrEmpty()) {
            RemoteInput.addResultsToIntent(inputs, fill, Bundle().apply {
                inputs.forEach { putCharSequence(it.resultKey, reply) }
            })
        }
        return runCatching { action.actionIntent.send(this, 0, fill) }
            .onFailure { Log.w(TAG, "action failed", it) }
            .isSuccess
    }

    fun dismiss(key: String) {
        runCatching { cancelNotification(key) }
    }

    companion object {
        private const val TAG = "NotificationRelay"
        private const val ICON_SIZE = 96

        @Volatile var instance: NotificationRelay? = null
            private set

        private val _enabled = MutableStateFlow(false)
        /** Whether the user has granted Notification access (and Android has connected the listener). */
        val enabled: StateFlow<Boolean> = _enabled

        /** Sends happen one at a time, in order, off the listener's thread. */
        private val sendQueue = Executors.newSingleThreadExecutor()

        private fun forward(event: JSONObject) = sendQueue.execute {
            Graph.discovery.peers.value.filter { it.platform == "macos" }.forEach { peer ->
                runCatching { Graph.sender.postToPaired(peer, "/api/v2/notify", event) }
                    .onFailure { Log.d(TAG, "couldn't reach ${peer.name}: ${it.message}") }
            }
        }

        fun openSettings(context: Context) {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
