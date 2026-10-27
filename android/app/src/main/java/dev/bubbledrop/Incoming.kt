package dev.bubbledrop

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

sealed interface PromptKind {
    /** A paired device wants to send files. */
    data class Offer(val request: AskRequest) : PromptKind

    /** Another device started pairing with us; confirm its screen shows [code]. */
    data class PairIncoming(val sender: DeviceInfo, val code: String) : PromptKind

    /** We're pairing with [peerName]; confirm its screen shows [code]. */
    data class PairOutgoing(val peerName: String, val code: String) : PromptKind
}

/**
 * Holds the one question currently put to the user. Network threads block in [await] until the
 * user answers from the in-app dialog or the notification actions.
 */
class Prompts(private val context: Context) {
    data class Pending(val id: String, val kind: PromptKind, internal val answer: CompletableFuture<Boolean>)

    private val _current = MutableStateFlow<Pending?>(null)
    val current: StateFlow<Pending?> = _current

    /** Shows [kind]; returns null if another prompt is already showing. */
    fun begin(kind: PromptKind): Pending? {
        val pending = Pending(UUID.randomUUID().toString(), kind, CompletableFuture())
        synchronized(this) {
            if (_current.value != null) return null
            _current.value = pending
        }
        if (kind !is PromptKind.PairOutgoing) showNotification(pending)
        return pending
    }

    /** Blocks until the user answers [pending] or [timeoutMs] passes (counted as "no"). */
    fun await(pending: Pending, timeoutMs: Long): Boolean {
        val answer = try {
            pending.answer.get(timeoutMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            false
        }
        dismiss(pending)
        return answer
    }

    fun ask(kind: PromptKind, timeoutMs: Long = 120_000): Boolean? = begin(kind)?.let { await(it, timeoutMs) }

    fun respond(id: String, accept: Boolean) {
        val pending = _current.value
        if (pending?.id == id) pending.answer.complete(accept)
    }

    /** Withdraws [pending] (answering "no" if it was still open). */
    fun dismiss(pending: Pending) {
        pending.answer.complete(false)
        synchronized(this) { if (_current.value?.id == pending.id) _current.value = null }
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun showNotification(pending: Pending) {
        val (title, text, accept) = when (val kind = pending.kind) {
            is PromptKind.Offer -> Triple(
                "${kind.request.sender.name} wants to send you " +
                    if (kind.request.files.size == 1) kind.request.files[0].name else "${kind.request.files.size} files",
                formatBytes(kind.request.totalSize),
                // Accepting needs a fingerprint, which can't be asked from a notification: open the app.
                "Open",
            )
            is PromptKind.PairIncoming -> Triple(
                "Pair with ${kind.sender.name}?",
                "Check that ${kind.sender.name} shows ${formatCode(kind.code)}",
                // Pairing needs a fingerprint too: open the app, where the code is confirmed.
                "Open",
            )
            is PromptKind.PairOutgoing -> return
        }
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_INCOMING)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "Decline", actionIntent(pending.id, false))
            .addAction(0, accept, open)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification) }
    }

    private fun actionIntent(id: String, accept: Boolean): PendingIntent {
        val intent = Intent(context, PromptActionReceiver::class.java)
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_ACCEPT, accept)
        return PendingIntent.getBroadcast(
            context, if (accept) 1 else 2, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val NOTIFICATION_ID = 2
        const val EXTRA_ID = "id"
        const val EXTRA_ACCEPT = "accept"
    }
}

class PromptActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(Prompts.EXTRA_ID) ?: return
        Graph.prompts.respond(id, intent.getBooleanExtra(Prompts.EXTRA_ACCEPT, false))
    }
}

fun formatCode(code: String) = "${code.take(3)} ${code.drop(3)}"
