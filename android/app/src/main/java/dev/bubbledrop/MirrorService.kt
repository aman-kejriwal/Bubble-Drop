package dev.bubbledrop

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Mirrors this phone's screen to a paired Mac (PROTOCOL.md "Mirroring"): MediaProjection renders
 * into a hardware H.264 encoder, whose output streams over one long-lived TLS connection.
 */
class MirrorService : Service() {
    @Volatile private var stopping = false
    private var projection: MediaProjection? = null
    private var socket: SSLSocket? = null
    private var output: OutputStream? = null
    private val writeLock = Any()
    /** When the packet being written started (elapsed ms), or 0 between writes; see [watchWrites]. */
    @Volatile private var writeStartedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutDown()
            return START_NOT_STICKY
        }
        val peer = pendingPeer
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
        if (peer == null || data == null || _active.value != null) {
            stopSelf()
            return START_NOT_STICKY
        }
        pendingPeer = null

        // Android requires the mediaProjection foreground service to be running before the projection is created.
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(peer.name),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val projection = getSystemService(MediaProjectionManager::class.java)
            .getMediaProjection(intent.getIntExtra(EXTRA_CODE, 0), data)
        if (projection == null) {
            shutDown()
            return START_NOT_STICKY
        }
        this.projection = projection
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = shutDown()
        }, Handler(Looper.getMainLooper()))

        _active.value = peer.name
        _error.value = null
        thread(name = "mirror") {
            try {
                stream(peer, projection)
            } catch (e: Exception) {
                if (!stopping) {
                    Log.w(TAG, "mirroring failed", e)
                    _error.value = e.message ?: "Mirroring stopped"
                }
            } finally {
                Handler(Looper.getMainLooper()).post { shutDown() }
            }
        }
        return START_NOT_STICKY
    }

    private fun stream(peer: Peer, projection: MediaProjection) {
        val pinned = Graph.sender.connectPaired(peer)
        val socket = (Graph.identity.sslContext(PinningTrustManager(pinned)).socketFactory
            .createSocket(peer.address, peer.port) as SSLSocket)
        this.socket = socket
        socket.tcpNoDelay = true
        socket.soTimeout = 15_000
        socket.startHandshake()
        val output = BufferedOutputStream(socket.outputStream, 64 * 1024)
        this.output = output
        val body = MirrorRequest(Graph.device, ControlService.enabled.value).toJson().toString().toByteArray(Charsets.UTF_8)
        output.write(("POST /api/v2/mirror HTTP/1.1\r\nHost: ${peer.address.hostAddress}\r\n" +
            "Content-Type: application/json\r\nContent-Length: ${body.size}\r\n\r\n").toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
        val input = socket.inputStream
        val status = readResponse(input)
        when (status) {
            200 -> Unit
            401 -> throw IOException("${peer.name} no longer trusts this device. Unpair it and pair again.")
            409 -> throw IOException("${peer.name} is already showing a screen.")
            503 -> throw IOException("${peer.name} has receiving turned off.")
            else -> throw IOException("${peer.name} refused mirroring ($status)")
        }

        // Input from the Mac arrives on the same connection; it closes it when its window is closed.
        socket.soTimeout = 0
        watchWrites(peer.name)
        thread(name = "mirror-input") {
            runCatching { readControl(DataInputStream(input)) }
            if (!stopping) Handler(Looper.getMainLooper()).post { shutDown() }
        }
        // Tell the Mac when remote control is switched on or off mid-session.
        val controlUpdates = Graph.scope.launch {
            ControlService.enabled.drop(1).collect { on ->
                runCatching { writePacket(TYPE_CONTROL_STATE, 0, byteArrayOf(if (on) 1 else 0)) }
            }
        }
        // Trust is checked when the session starts; unpairing mid-session must end it too,
        // since the same connection carries the Mac's remote-control input.
        val trustWatch = Graph.scope.launch {
            Graph.trust.devices.collect { devices ->
                if (devices.none { it.fingerprint == pinned }) {
                    Log.i(TAG, "${peer.name} was unpaired, stopping")
                    Handler(Looper.getMainLooper()).post { shutDown() }
                }
            }
        }
        try {
            Encoder(projection).run()
        } finally {
            controlUpdates.cancel()
            trustWatch.cancel()
        }
    }

    /** Reads packets from the Mac until it closes the connection, forwarding input events. */
    private fun readControl(input: DataInputStream) {
        val header = ByteArray(13)
        while (true) {
            input.readFully(header)
            val type = header[0].toInt()
            val length = ByteBuffer.wrap(header, 9, 4).int
            if (length !in 0..(64 * 1024)) throw IOException("bad control packet")
            val payload = ByteArray(length)
            input.readFully(payload)
            if (type == TYPE_INPUT) {
                val event = runCatching { JSONObject(String(payload, Charsets.UTF_8)) }.getOrNull() ?: continue
                ControlService.instance?.handle(event)
            }
        }
    }

    private fun writePacket(type: Int, ptsUs: Long, payload: ByteArray) = synchronized(writeLock) {
        val output = output ?: throw IOException("not connected")
        writeStartedAt = SystemClock.elapsedRealtime()
        try {
            output.write(ByteBuffer.allocate(13).put(type.toByte()).putLong(ptsUs).putInt(payload.size).array())
            output.write(payload)
            output.flush()
        } finally {
            writeStartedAt = 0
        }
    }

    /**
     * Ends the session when a write stays stuck, i.e. the Mac went away without closing the
     * connection (Wi-Fi dropped, Mac asleep). Sockets have no write timeout, and TCP alone can
     * take minutes to give up while the screen keeps being captured.
     */
    private fun watchWrites(peerName: String) = thread(name = "mirror-watchdog", isDaemon = true) {
        while (!stopping) {
            Thread.sleep(1_000)
            val started = writeStartedAt
            if (started != 0L && SystemClock.elapsedRealtime() - started > STALL_LIMIT_MS && !stopping) {
                Log.w(TAG, "connection to $peerName stalled, stopping")
                _error.value = "Lost the connection to $peerName"
                Handler(Looper.getMainLooper()).post { shutDown() }
                return@thread
            }
        }
    }

    /** Reads the status line and headers, then skips the small JSON body. */
    private fun readResponse(input: InputStream): Int {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) throw IOException("connection closed")
            head.append(b.toChar())
            if (head.length > 16 * 1024) throw IOException("response too large")
        }
        val lines = head.lines()
        val status = lines.first().split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("bad response")
        val length = lines.firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        repeat(length) { if (input.read() < 0) throw IOException("connection closed") }
        return status
    }

    /**
     * Feeds the screen through a hardware encoder and writes packets until stopped. When the phone
     * rotates, the encoder is rebuilt at the new size (new SPS/PPS go out as a config packet).
     */
    private inner class Encoder(private val projection: MediaProjection) {
        @Volatile private var restart = false
        private val displays = getSystemService(DisplayManager::class.java)
        private val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY && captureSize() != size) restart = true
            }
        }
        private var size = captureSize()

        fun run() {
            displays.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
            var virtualDisplay: VirtualDisplay? = null
            try {
                while (!stopping) {
                    size = captureSize()
                    val codec = createCodec(size)
                    val surface = codec.createInputSurface()
                    codec.start()
                    val display = virtualDisplay
                    if (display == null) {
                        virtualDisplay = projection.createVirtualDisplay(
                            "BubbleDrop", size.width, size.height, size.dpi,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null,
                        )
                    } else {
                        display.resize(size.width, size.height, size.dpi)
                        display.surface = surface
                    }
                    restart = false
                    try {
                        drain(codec)
                    } finally {
                        virtualDisplay?.surface = null
                        runCatching { codec.stop() }
                        codec.release()
                        surface.release()
                    }
                }
                writePacket(TYPE_END, 0, ByteArray(0))
            } finally {
                displays.unregisterDisplayListener(listener)
                virtualDisplay?.release()
            }
        }

        private fun drain(codec: MediaCodec) {
            val info = MediaCodec.BufferInfo()
            while (!stopping && !restart) {
                val index = codec.dequeueOutputBuffer(info, 20_000)
                if (index < 0) continue
                val buffer = codec.getOutputBuffer(index)
                if (buffer != null && info.size > 0) {
                    val bytes = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.get(bytes)
                    val type = if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) TYPE_CONFIG else TYPE_FRAME
                    writePacket(type, info.presentationTimeUs, bytes)
                }
                codec.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
    }

    private data class CaptureSize(val width: Int, val height: Int, val dpi: Int)

    /** The screen's current size (it swaps on rotation), scaled so the long edge is at most 1600 px. */
    private fun captureSize(): CaptureSize {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics)
        val scale = minOf(1f, MAX_EDGE.toFloat() / max(metrics.widthPixels, metrics.heightPixels))
        fun even16(v: Float) = max(16, (v / 16).roundToInt() * 16)
        return CaptureSize(even16(metrics.widthPixels * scale), even16(metrics.heightPixels * scale), metrics.densityDpi)
    }

    private fun createCodec(size: CaptureSize): MediaCodec {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size.width, size.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 60)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            // Keep frames coming while the screen is still, so the Mac can tell the stream is alive.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LATENCY, 1)
        }
        return MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
    }

    private fun notification(peerName: String) = NotificationCompat.Builder(this, CHANNEL_SERVICE)
        .setSmallIcon(android.R.drawable.ic_menu_slideshow)
        .setContentTitle("Mirroring to $peerName")
        .setContentText("Your screen is visible on $peerName")
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE))
        .addAction(0, "Stop", PendingIntent.getService(this, 0,
            Intent(this, MirrorService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE))
        .setOngoing(true)
        .build()

    private fun shutDown() {
        if (stopping) return
        stopping = true
        runCatching { socket?.close() }
        runCatching { projection?.stop() }
        _active.value = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val TAG = "MirrorService"
        private const val NOTIFICATION_ID = 3
        private const val ACTION_STOP = "dev.bubbledrop.STOP_MIRROR"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val MAX_EDGE = 1600
        /** The Mac drops a silent stream after 15 s; give up on a stuck write before that. */
        private const val STALL_LIMIT_MS = 10_000L
        private const val TYPE_CONFIG = 1
        private const val TYPE_FRAME = 2
        private const val TYPE_END = 3
        private const val TYPE_CONTROL_STATE = 4
        private const val TYPE_INPUT = 10

        /** Peers aren't parcelable; the one picked in the UI waits here for the service to start. */
        @Volatile private var pendingPeer: Peer? = null

        private val _active = MutableStateFlow<String?>(null)
        /** Name of the device being mirrored to, or null. */
        val active: StateFlow<String?> = _active

        private val _error = MutableStateFlow<String?>(null)
        /** Why the last session ended early, if it did. */
        val error: StateFlow<String?> = _error

        /** Starts mirroring to [peer] with the consent result from MediaProjectionManager. */
        fun start(context: Context, peer: Peer, resultCode: Int, data: Intent) {
            pendingPeer = peer
            context.startForegroundService(
                Intent(context, MirrorService::class.java).putExtra(EXTRA_CODE, resultCode).putExtra(EXTRA_DATA, data)
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MirrorService::class.java).setAction(ACTION_STOP))
        }

        fun clearError() {
            _error.value = null
        }
    }
}
