package dev.bubbledrop

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import android.util.Log
import org.json.JSONObject

/**
 * Phone calls on the Mac (PROTOCOL.md "Calls"). The default phone app's call notification tells
 * us who's calling and whether it's ringing or connected; the Mac answers, declines and hangs up
 * through Telecom (or the notification's own buttons) and can ask for the speaker.
 */
object Calls {
    private const val TAG = "Calls"
    // CallStyle extras (Notification.EXTRA_CALL_TYPE and friends; the intents aren't public API).
    private const val EXTRA_CALL_PERSON = "android.callPerson"
    private const val EXTRA_CALL_TYPE = "android.callType"
    private const val CALL_TYPE_INCOMING = 1
    private const val CALL_TYPE_ONGOING = 2
    private const val EXTRA_ANSWER = "android.answerIntent"
    private const val EXTRA_DECLINE = "android.declineIntent"
    private const val EXTRA_HANG_UP = "android.hangUpIntent"

    private class Call(val key: String, val caller: String, val ringing: Boolean, val notification: Notification)

    @Volatile private var current: Call? = null
    /** Set when the Mac answered with speaker on; applied once the call connects. */
    @Volatile private var speakerOnConnect = false
    private val main = Handler(Looper.getMainLooper())
    /** Re-sends the current call while it lasts, so a Mac that missed "ended" can time the call out. */
    private var heartbeat: Runnable? = null
    private const val HEARTBEAT_MS = 5_000L

    /** Whether [sbn] is the default phone app's call notification (shown as the call screen, not a notification). */
    fun isPhoneCall(context: Context, sbn: StatusBarNotification): Boolean =
        sbn.notification.category == Notification.CATEGORY_CALL &&
            sbn.packageName == context.getSystemService(TelecomManager::class.java).defaultDialerPackage

    fun posted(context: Context, sbn: StatusBarNotification) {
        val n = sbn.notification
        val extras = n.extras
        @Suppress("DEPRECATION")
        val person = extras.getParcelable<Person>(EXTRA_CALL_PERSON)
        val caller = person?.name?.toString()?.takeIf { it.isNotBlank() }
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() }
            ?: "Unknown caller"
        val ringing = when (extras.getInt(EXTRA_CALL_TYPE, 0)) {
            CALL_TYPE_INCOMING -> true
            CALL_TYPE_ONGOING -> false
            else -> findAction(n, "answer") != null
        }
        val previous = current
        val call = Call(sbn.key, caller, ringing, n)
        current = call
        // The dialer re-posts its notification for every second of the call timer; only state changes matter.
        if (previous?.key == call.key && previous.ringing == ringing && previous.caller == caller) return
        if (!ringing && speakerOnConnect) {
            speakerOnConnect = false
            // Give the phone app a moment to set up its audio route before switching it.
            main.postDelayed({ setSpeaker(context, true) }, 700)
        }
        if (!ringing) main.postDelayed({ refresh(context) }, 1500)
        send(call, if (ringing) "ringing" else "active", context)
        startHeartbeat(context)
    }

    fun removed(context: Context, sbn: StatusBarNotification) {
        val call = current?.takeIf { it.key == sbn.key } ?: return
        end(context, call)
    }

    private fun end(context: Context, call: Call) {
        current = null
        speakerOnConnect = false
        stopHeartbeat()
        send(call, "ended", context)
        // A Mac that was briefly unreachable still gets it; repeats are harmless on the Mac.
        for (delay in longArrayOf(2_000, 6_000)) {
            main.postDelayed({ if (current == null) send(call, "ended", context) }, delay)
        }
    }

    private fun startHeartbeat(context: Context) {
        if (heartbeat != null) return
        val tick = object : Runnable {
            override fun run() {
                val call = current ?: return stopHeartbeat()
                // The listener can miss a removal (e.g. while it reconnects): end calls whose notification is gone.
                val relay = NotificationRelay.instance
                val stillShown = relay == null || runCatching {
                    relay.getActiveNotifications(arrayOf(call.key)).orEmpty().isNotEmpty()
                }.getOrDefault(true)
                if (!stillShown) return end(context, call)
                send(call, if (call.ringing) "ringing" else "active", context)
                main.postDelayed(this, HEARTBEAT_MS)
            }
        }
        heartbeat = tick
        main.postDelayed(tick, HEARTBEAT_MS)
    }

    private fun stopHeartbeat() {
        heartbeat?.let(main::removeCallbacks)
        heartbeat = null
    }

    /** Runs [action] from the Mac: answer, decline, hangup or speaker. */
    fun perform(context: Context, action: String, speaker: Boolean): Boolean {
        val call = current ?: return false
        return when (action) {
            "answer" -> {
                speakerOnConnect = speaker
                telecom(context) { acceptRingingCall() } || fire(call.notification, EXTRA_ANSWER, "answer")
            }
            "decline" -> telecom(context) { endCall() } || fire(call.notification, EXTRA_DECLINE, "decline")
            "hangup" -> telecom(context) { endCall() } || fire(call.notification, EXTRA_HANG_UP, "hang up")
            "speaker" -> setSpeaker(context, speaker).also { main.postDelayed({ refresh(context) }, 600) }
            else -> false
        }
    }

    private fun telecom(context: Context, block: TelecomManager.() -> Unit): Boolean {
        if (context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        return runCatching {
            @Suppress("DEPRECATION")
            context.getSystemService(TelecomManager::class.java).block()
        }.onFailure { Log.w(TAG, "telecom failed", it) }.isSuccess
    }

    /** Sends the call notification's own button: the CallStyle intent, or an action whose title matches. */
    private fun fire(n: Notification, extra: String, title: String): Boolean {
        @Suppress("DEPRECATION")
        val intent = n.extras.getParcelable<PendingIntent>(extra) ?: findAction(n, title)?.actionIntent
        return intent != null && runCatching { intent.send() }.isSuccess
    }

    private fun findAction(n: Notification, title: String): Notification.Action? =
        n.actions?.firstOrNull { it.title?.toString()?.contains(title, ignoreCase = true) == true }

    /**
     * Speaker on or off. The phone app's own "Speaker" button is the reliable way to switch an
     * ongoing call; routing the communication device ourselves is the fallback.
     */
    private fun setSpeaker(context: Context, on: Boolean): Boolean {
        val audio = context.getSystemService(AudioManager::class.java)
        if (isSpeakerOn(audio) == on) return true
        current?.notification?.let(::speakerAction)?.let { action ->
            if (runCatching { action.actionIntent.send() }.isSuccess) return true
        }
        return runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                @Suppress("DEPRECATION")
                audio.isSpeakerphoneOn = on
                true
            } else if (on) {
                val speaker = audio.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER } ?: return false
                audio.setCommunicationDevice(speaker)
            } else {
                audio.clearCommunicationDevice()
                true
            }
        }.onFailure { Log.w(TAG, "speaker failed", it) }.getOrDefault(false)
    }

    private fun speakerAction(n: Notification): Notification.Action? =
        n.actions?.firstOrNull { action ->
            val title = action.title?.toString().orEmpty()
            title.contains("speaker", ignoreCase = true) || title.contains("loudspeaker", ignoreCase = true)
        }

    @Suppress("DEPRECATION")
    private fun isSpeakerOn(audio: AudioManager): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audio.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } else {
            audio.isSpeakerphoneOn
        }

    /** Re-sends the connected call, so the Mac's speaker switch shows what the phone is doing. */
    private fun refresh(context: Context) {
        current?.takeIf { !it.ringing }?.let { send(it, "active", context) }
    }

    private fun send(call: Call, state: String, context: Context) {
        val audio = context.getSystemService(AudioManager::class.java)
        NotificationRelay.forward("/api/v2/call", JSONObject()
            .put("id", call.key)
            .put("state", state)
            .put("caller", call.caller)
            .put("speaker", state == "active" && isSpeakerOn(audio)))
    }
}
