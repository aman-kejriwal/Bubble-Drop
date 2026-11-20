package dev.bubbledrop

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.quicksettings.TileService
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Moves what Spotify is playing on this phone to the paired Mac in one tap (PROTOCOL.md
 * `/spotify/play`). Spotify's media session is read through the Notification access the app
 * already has for notifications on the Mac. The phone pauses first, so the Mac can pick up at
 * the same second; if the Mac can't take it, the phone plays on.
 */
object SpotifyHandoff {
    private const val TAG = "SpotifyHandoff"
    const val SPOTIFY = "com.spotify.music"

    data class NowPlaying(
        val title: String,
        val artist: String,
        val album: String,
        /** `spotify:track:…`, when Spotify says. */
        val uri: String?,
        val art: Bitmap?,
        val durationMs: Long,
        val playing: Boolean,
    )

    sealed interface Status {
        data object Idle : Status
        data class Sending(val macName: String) : Status
        /** [playing]: it's playing on the Mac; otherwise the Mac opened it ([message] says how). */
        data class Done(val macName: String, val playing: Boolean, val message: String?) : Status
        data class Failed(val message: String) : Status
    }

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    /** What Spotify on this phone has loaded, or null (also while Notification access is off). */
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status

    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var manager: MediaSessionManager? = null
    private var controller: MediaController? = null

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { pick(it) }
    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
        override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
        override fun onSessionDestroyed() = pick(null)
    }

    /** Starts following Spotify; called when Android connects the notification listener. */
    fun attach(context: Context) = main.post {
        appContext = context.applicationContext
        val sessions = context.getSystemService(MediaSessionManager::class.java)
        val listener = ComponentName(context, NotificationRelay::class.java)
        runCatching {
            sessions.addOnActiveSessionsChangedListener(sessionsChanged, listener, main)
            manager = sessions
            pick(sessions.getActiveSessions(listener))
        }.onFailure { Log.w(TAG, "can't read media sessions", it) }
    }

    fun detach() = main.post {
        runCatching { manager?.removeOnActiveSessionsChangedListener(sessionsChanged) }
        manager = null
        controller?.unregisterCallback(callback)
        controller = null
        refresh()
    }

    private fun pick(sessions: List<MediaController>?) {
        val list = sessions ?: runCatching {
            manager?.getActiveSessions(ComponentName(appContext ?: return, NotificationRelay::class.java))
        }.getOrNull()
        val spotify = list?.firstOrNull { it.packageName == SPOTIFY }
        if (spotify?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = spotify
            spotify?.registerCallback(callback, main)
        }
        refresh()
    }

    private fun refresh() {
        val c = controller
        val metadata = c?.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim()
        _nowPlaying.value = if (c == null || title.isNullOrEmpty()) null else NowPlaying(
            title = title,
            artist = (metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty(),
            album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            uri = listOf(MediaMetadata.METADATA_KEY_MEDIA_ID, MediaMetadata.METADATA_KEY_MEDIA_URI)
                .firstNotNullOfOrNull { key -> metadata.getString(key)?.takeIf { it.startsWith("spotify:") } },
            art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
            durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
            playing = c.playbackState?.state == PlaybackState.STATE_PLAYING,
        )
        appContext?.let { context ->
            runCatching { TileService.requestListeningState(context, ComponentName(context, SpotifyTile::class.java)) }
        }
    }

    /** The paired Mac that's nearby right now, if any. */
    fun pairedMac(): Peer? = Graph.discovery.peers.value.firstOrNull { peer ->
        peer.platform == "macos" && peer.fingerprint?.let(Graph.trust::isTrusted) == true
    }

    /** One tap: pause here and carry on from the same second on the Mac. Call on the main thread. */
    fun handoff() {
        if (_status.value is Status.Sending) return
        val c = controller
        val now = _nowPlaying.value
        if (c == null || now == null) return fail("Play something in Spotify first.")
        val mac = pairedMac() ?: return fail(
            if (Graph.trust.devices.value.isEmpty()) "Pair with your Mac first."
            else "Your Mac isn't nearby. Make sure both are on the same Wi-Fi with BubbleDrop on."
        )
        val position = position(c.playbackState)
        val wasPlaying = now.playing
        if (wasPlaying) c.transportControls.pause()
        _status.value = Status.Sending(mac.name)

        Graph.scope.launch {
            val body = JSONObject()
                .put("sender", Graph.device.toJson())
                .put("title", now.title)
                .put("artist", now.artist)
                .put("album", now.album)
                .put("positionMs", position)
                .put("durationMs", now.durationMs)
                .apply {
                    now.uri?.let { put("uri", it) }
                    now.art?.let(::encodeArt)?.let { put("art", it) }
                }
            val result = runCatching { Graph.sender.playOnMac(mac, body) }
            result.onSuccess { reply ->
                _status.value = Status.Done(mac.name, reply.optBoolean("playing"),
                    reply.optString("message").takeIf { it.isNotEmpty() })
            }.onFailure { error ->
                Log.w(TAG, "handoff failed", error)
                // The Mac couldn't take it: keep the music going here.
                if (wasPlaying) main.post { c.transportControls.play() }
                _status.value = Status.Failed(error.message ?: "Couldn't reach ${mac.name}.")
            }
            val shown = _status.value
            delay(6_000)
            _status.compareAndSet(shown, Status.Idle)
        }
    }

    private fun fail(message: String) {
        val failed = Status.Failed(message)
        _status.value = failed
        Graph.scope.launch {
            delay(5_000)
            _status.compareAndSet(failed, Status.Idle)
        }
    }

    /** Where playback is right now, allowing for time since Spotify last reported it. */
    private fun position(state: PlaybackState?): Long {
        state ?: return 0
        if (state.state != PlaybackState.STATE_PLAYING) return state.position.coerceAtLeast(0)
        val elapsed = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
        return (state.position + elapsed * state.playbackSpeed).toLong().coerceAtLeast(0)
    }

    /** The cover as a small base64 JPEG, for the Mac's animation. */
    private fun encodeArt(art: Bitmap): String? = runCatching {
        val side = 320
        val scaled = if (art.width > side || art.height > side) {
            val scale = side.toFloat() / maxOf(art.width, art.height)
            Bitmap.createScaledBitmap(art, (art.width * scale).toInt().coerceAtLeast(1),
                (art.height * scale).toInt().coerceAtLeast(1), true)
        } else art
        val bytes = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 86, it) }.toByteArray()
        Base64.encodeToString(bytes, Base64.NO_WRAP)
    }.getOrNull()
}
