package dev.bubbledrop

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Pairing on this phone. Nothing works with an unpaired device (sending and mirroring never pair
 * on their own), so turning BubbleDrop on asks to pair with each unpaired device nearby, one at a
 * time. It only asks: the code exchange starts when the user taps Pair, so comparing the codes
 * stays a deliberate step. "Not now" skips a device until the next turn-on.
 */
class PairSuggestions(
    private val context: Context,
    private val scope: CoroutineScope,
    private val discovery: Discovery,
    private val trust: TrustStore,
    private val sender: Sender,
) {
    sealed interface Status {
        data object Idle : Status
        data object Pairing : Status
        data class Failed(val message: String) : Status
    }

    private val _suggestion = MutableStateFlow<Peer?>(null)
    /** The device currently suggested or being paired, if any. */
    val suggestion: StateFlow<Peer?> = _suggestion
    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status

    /** Suggestions are only made until this time, so only the device that was just turned on asks. */
    @Volatile private var armedUntil = 0L
    /** Devices whose suggestion was dismissed since BubbleDrop was last turned on. */
    private val dismissed = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    init {
        scope.launch {
            combine(discovery.peers, trust.devices) { peers, _ -> peers }.collect(::evaluate)
        }
    }

    /** BubbleDrop was just turned on: suggest the first unpaired device found in the next while. */
    fun arm() {
        armedUntil = System.currentTimeMillis() + ARM_WINDOW_MS
        dismissed.clear()
        evaluate(discovery.peers.value)
    }

    /** BubbleDrop was turned off. */
    fun disarm() {
        armedUntil = 0
        clear()
    }

    @Synchronized
    private fun evaluate(peers: List<Peer>) {
        val current = _suggestion.value
        if (current != null) {
            // Withdraw it once the device leaves or gets paired (from either side).
            val gone = peers.none { it.id == current.id }
            if ((gone || isPaired(current)) && _status.value != Status.Pairing) clear()
            return
        }
        if (System.currentTimeMillis() > armedUntil) return
        val candidate = peers.firstOrNull {
            trust.acceptsPairing && !isPaired(it) && it.fingerprint != null && it.fingerprint !in dismissed
        }
            ?: return
        _status.value = Status.Idle
        _suggestion.value = candidate
        if (!Links.appVisible) notify(candidate)
    }

    /** Pairs with [peer] (the suggested device if null); the code then shows in the app for comparison. */
    fun pair(peer: Peer? = null) {
        val target = peer ?: _suggestion.value ?: return
        if (_status.value == Status.Pairing) return
        cancelNotification()
        _suggestion.value = target
        _status.value = Status.Pairing
        scope.launch {
            try {
                sender.connectPaired(target, allowPairing = true)
                next()
            } catch (_: PairingCancelledException) {
                target.fingerprint?.let(dismissed::add)
                next()
            } catch (e: Exception) {
                _status.value = Status.Failed(e.message ?: "Pairing failed")
            }
        }
    }

    /** Skips the suggested device until BubbleDrop is next turned on, then offers the next one. */
    fun notNow() {
        _suggestion.value?.fingerprint?.let(dismissed::add)
        next()
    }

    private fun next() {
        clear()
        evaluate(discovery.peers.value)
    }

    private fun clear() {
        _suggestion.value = null
        _status.value = Status.Idle
        cancelNotification()
    }

    private fun isPaired(peer: Peer) = peer.fingerprint?.let(trust::isTrusted) == true

    private fun notify(peer: Peer) {
        fun open(pair: Boolean) = PendingIntent.getActivity(
            context, if (pair) 11 else 10,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_PAIR, pair),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notNow = PendingIntent.getBroadcast(
            context, 12, Intent(context, PairSuggestionReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Pair with ${peer.name}?")
            .setContentText("It's nearby but not paired, so nothing can be shared with it yet. Pairing is only needed once.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(open(pair = false))
            .setAutoCancel(true)
            .addAction(0, "Not now", notNow)
            .addAction(0, "Pair", open(pair = true))
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification) }
    }

    private fun cancelNotification() {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    companion object {
        /** Intent extra on [MainActivity]: start pairing with the suggested device. */
        const val EXTRA_PAIR = "dev.bubbledrop.PAIR_SUGGESTED"
        private const val NOTIFICATION_ID = 3
        private const val ARM_WINDOW_MS = 2 * 60_000L
    }
}

/** "Not now" on the suggestion notification. */
class PairSuggestionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Graph.pairSuggestions.notNow()
}
