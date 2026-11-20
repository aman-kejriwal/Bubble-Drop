package dev.bubbledrop

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * Uses this phone as a paired Mac's trackpad and keyboard (PROTOCOL.md "Trackpad"): finger
 * movement, clicks, scrolling and typing go out as small packets on one long-lived TLS connection. The session lasts
 * only while the trackpad screen is open.
 */
object Trackpad {
    sealed interface State {
        object Idle : State
        data class Connecting(val name: String) : State
        /** [keyboard] is whether this Mac can be typed on too; older ones only take the trackpad. */
        data class Active(val name: String, val keyboard: Boolean) : State
        data class Failed(val name: String, val message: String) : State
    }

    const val BUTTON_LEFT = 0
    const val BUTTON_RIGHT = 1

    const val SWIPE_LEFT = 0
    const val SWIPE_RIGHT = 1
    const val SWIPE_UP = 2
    const val SWIPE_DOWN = 3

    const val ZOOM_BEGIN = 0
    const val ZOOM_CHANGE = 1
    const val ZOOM_END = 2

    const val KEY_VOLUME_UP = 0
    const val KEY_VOLUME_DOWN = 1
    const val KEY_MUTE = 2
    const val KEY_BRIGHTNESS_UP = 3
    const val KEY_BRIGHTNESS_DOWN = 4
    const val KEY_PLAY_PAUSE = 5
    const val KEY_RETURN = 6
    const val KEY_DELETE = 7
    const val KEY_ESCAPE = 8
    const val KEY_TAB = 9
    const val KEY_LEFT = 10
    const val KEY_RIGHT = 11
    const val KEY_UP = 12
    const val KEY_DOWN = 13
    const val KEY_LOCK = 14
    const val KEY_COPY = 15
    const val KEY_PASTE = 16
    const val KEY_CUT = 17
    const val KEY_SELECT_ALL = 18
    const val KEY_UNDO = 19

    private const val TAG = "Trackpad"
    private const val TYPE_KEEPALIVE = 0
    private const val TYPE_MOVE = 1
    private const val TYPE_BUTTON = 2
    private const val TYPE_SCROLL = 3
    private const val TYPE_SWIPE = 4
    private const val TYPE_ZOOM = 5
    private const val TYPE_KEY = 6
    private const val TYPE_TEXT = 7
    private const val TYPE_SETTING = 8
    private const val SETTING_BLUETOOTH = 0
    /** The most text in one packet, in UTF-16 units; at most 3 bytes each, inside the 1024-byte payload. */
    private const val TEXT_CHUNK = 250
    /** The Mac drops a phone it hasn't heard from in 10 s. */
    private const val KEEPALIVE_MS = 2_000L
    /** The Mac sends a keepalive every 2 s; this much silence means it's gone. */
    private const val SILENCE_LIMIT_MS = 8_000
    /** Packets waiting to go out when the connection stalls; past this, new movement is dropped. */
    private const val MAX_QUEUED = 512

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    @Volatile private var session: Session? = null
    private var lastPeer: Peer? = null

    /** The most characters of what was typed that are kept; the oldest go first. */
    private const val TYPED_KEPT = 4000

    private val _macBluetooth = MutableStateFlow<Boolean?>(null)
    /** Whether the Mac's Bluetooth is on, once the Mac has said; null before, and for a Mac that doesn't. */
    val macBluetooth: StateFlow<Boolean?> = _macBluetooth

    /** Switches the Mac's Bluetooth. The Mac answers with how it ended up. */
    fun setMacBluetooth(on: Boolean) {
        _macBluetooth.value = on
        send(packet(TYPE_SETTING, byteArrayOf(SETTING_BLUETOOTH.toByte(), if (on) 1 else 0)))
    }

    private val _typed = MutableStateFlow("")
    /** What has been typed on the phone's keyboard this session, which the trackpad screen shows. */
    val typed: StateFlow<String> = _typed

    /** Empties what the trackpad screen shows as typed. */
    fun clearTyped() {
        _typed.value = ""
    }

    /** How long the Quick Settings tile waits for the paired Mac to turn up nearby. */
    private const val FIND_MS = 10_000L

    /** The wait for the paired Mac to be found, when the tile opened the trackpad before it was. */
    private var search: Job? = null

    /** The paired Mac, if it's nearby. */
    private fun pairedMac(peers: List<Peer>, trusted: List<TrustedDevice>): Peer? =
        peers.firstOrNull { p -> p.platform == "macos" && trusted.any { it.fingerprint == p.fingerprint } }

    /**
     * Shows the trackpad screen and connects to the paired Mac, waiting a little for it to be
     * found: the Quick Settings tile can open the app before it has looked around.
     */
    fun openPaired() {
        pairedMac(Graph.discovery.peers.value, Graph.trust.devices.value)?.let { return open(it) }
        search?.cancel()
        session?.end()
        session = null
        lastPeer = null
        _typed.value = ""
        _macBluetooth.value = null
        val name = Graph.trust.devices.value.firstOrNull()?.name
        if (name == null) {
            _state.value = State.Failed("", "Pair this phone with a Mac in BubbleDrop first.")
            return
        }
        _state.value = State.Connecting(name)
        search = Graph.scope.launch {
            val mac = withTimeoutOrNull(FIND_MS) {
                Graph.discovery.peers.combine(Graph.trust.devices, ::pairedMac).first { it != null }
            }
            // Closed, or opened some other way, while waiting.
            if (!isActive) return@launch
            if (mac != null) {
                open(mac)
            } else {
                _state.value = State.Failed(name, "Couldn't find $name nearby. Check that BubbleDrop is open on it " +
                    "and that it's on the same Wi-Fi as this phone.")
            }
        }
    }

    /** Connects to [peer] and shows the trackpad screen. */
    fun open(peer: Peer) {
        search?.cancel()
        _typed.value = ""
        _macBluetooth.value = null
        session?.end()
        lastPeer = peer
        val next = Session(peer)
        session = next
        _state.value = State.Connecting(peer.name)
        thread(name = "trackpad") { next.run() }
    }

    /** Connects again after a failure, to wherever the Mac is now: it may have restarted on a new port. */
    fun retry() {
        val last = lastPeer ?: return openPaired()
        open(Graph.discovery.peers.value.firstOrNull { it.id == last.id } ?: last)
    }

    /** Ends the session and leaves the trackpad screen. */
    fun close() {
        search?.cancel()
        _typed.value = ""
        _macBluetooth.value = null
        session?.end()
        session = null
        _state.value = State.Idle
    }

    /** The finger moved by this much, in dp. */
    fun move(dx: Float, dy: Float) = send(deltas(TYPE_MOVE, dx, dy))

    /** Two fingers moved by this much, in dp. */
    fun scroll(dx: Float, dy: Float) = send(deltas(TYPE_SCROLL, dx, dy))

    /** Three fingers swiped this way; the Mac switches spaces or shows Mission Control. */
    fun swipe(direction: Int) = send(packet(TYPE_SWIPE, byteArrayOf(direction.toByte())))

    /** A pinch began, changed or ended. [magnification] is the zoom since the last event: 1 doubles the size. */
    fun zoom(phase: Int, magnification: Float = 0f) =
        send(packet(TYPE_ZOOM, ByteBuffer.allocate(5).put(phase.toByte()).putFloat(magnification).array()))

    /** One of the volume, brightness or play buttons, or a typing key such as Return or Delete. */
    fun key(key: Int) {
        when (key) {
            KEY_DELETE -> _typed.update { if (it.isEmpty()) it else it.substring(0, it.offsetByCodePoints(it.length, -1)) }
            KEY_RETURN -> typed("\n")
        }
        send(packet(TYPE_KEY, byteArrayOf(key.toByte())))
    }

    /** Text typed on the phone's keyboard, for the Mac to type. */
    fun text(text: String) {
        typed(text)
        var start = 0
        while (start < text.length) {
            var end = minOf(start + TEXT_CHUNK, text.length)
            // Both halves of an emoji or other surrogate pair go in the same packet.
            if (end < text.length && Character.isHighSurrogate(text[end - 1])) end--
            send(packet(TYPE_TEXT, text.substring(start, end).toByteArray(Charsets.UTF_8)))
            start = end
        }
    }

    private fun typed(text: String) = _typed.update {
        val all = (it + text).takeLast(TYPED_KEPT)
        // Not half of an emoji at the cut.
        if (all.isNotEmpty() && Character.isLowSurrogate(all[0])) all.drop(1) else all
    }

    /** [clicks] is 1 for a single click, 2 for the second click of a double click. */
    fun button(button: Int, down: Boolean, clicks: Int = 1) =
        send(packet(TYPE_BUTTON, byteArrayOf(button.toByte(), if (down) 1 else 0, clicks.toByte())))

    fun click(button: Int, clicks: Int = 1) {
        button(button, true, clicks)
        button(button, false, clicks)
    }

    private fun send(packet: ByteArray) {
        session?.send(packet)
    }

    private fun deltas(type: Int, dx: Float, dy: Float) =
        packet(type, ByteBuffer.allocate(8).putFloat(dx).putFloat(dy).array())

    /** A 3-byte header (type, payload length; big-endian) then the payload. */
    private fun packet(type: Int, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(3 + payload.size).put(type.toByte()).putShort(payload.size.toShort()).put(payload).array()

    private class Session(private val peer: Peer) {
        private val queue = LinkedBlockingQueue<ByteArray>()
        @Volatile private var closed = false
        @Volatile private var connected = false
        @Volatile private var socket: SSLSocket? = null

        fun send(packet: ByteArray) {
            if (!connected || closed) return
            // Only movement is dropped when the connection stalls. A lost button-up would leave
            // the Mac's button held, and a lost zoom end would leave its gesture open.
            if (queue.size >= MAX_QUEUED && isMovement(packet)) return
            queue.offer(packet)
        }

        private fun isMovement(packet: ByteArray) =
            packet.isNotEmpty() && (packet[0].toInt() == TYPE_MOVE || packet[0].toInt() == TYPE_SCROLL)

        /**
         * Writes [batch] to [bytes], adding up runs of moves or scrolls into one packet each: the
         * Mac ends up in the same place with less to read.
         */
        private fun writeMerged(batch: List<ByteArray>, bytes: ByteArrayOutputStream) {
            var type = -1
            var dx = 0f
            var dy = 0f
            fun flush() {
                if (type >= 0) bytes.write(deltas(type, dx, dy))
                type = -1
                dx = 0f
                dy = 0f
            }
            for (packet in batch) {
                if (isMovement(packet) && packet.size == 11) {
                    if (packet[0].toInt() != type) flush()
                    type = packet[0].toInt()
                    val values = ByteBuffer.wrap(packet, 3, 8)
                    dx += values.float
                    dy += values.float
                } else {
                    flush()
                    bytes.write(packet)
                }
            }
            flush()
        }

        /** Ends the session quietly (the user left the trackpad screen). */
        fun end() {
            if (closed) return
            closed = true
            shutDown()
        }

        /** Ends the session and shows why, unless it was already ended. */
        private fun fail(message: String) {
            if (closed) return
            closed = true
            shutDown()
            if (session === this) _state.value = State.Failed(peer.name, message)
        }

        private fun shutDown() {
            queue.offer(ByteArray(0))
            // Closing a TLS socket writes to it, which Android doesn't allow on the main thread.
            thread(name = "trackpad-close") { runCatching { socket?.close() } }
        }

        fun run() {
            try {
                val pinned = Graph.sender.connectPaired(peer)
                val socket = Graph.identity.sslContext(PinningTrustManager(pinned)).socketFactory
                    .createSocket(peer.address, peer.port) as SSLSocket
                this.socket = socket
                if (closed) return
                socket.tcpNoDelay = true
                socket.soTimeout = 15_000
                socket.startHandshake()
                val output = socket.outputStream
                val body = JSONObject().put("sender", Graph.device.toJson()).toString().toByteArray(Charsets.UTF_8)
                output.write(("POST /api/v2/trackpad HTTP/1.1\r\nHost: ${peer.address.hostAddress}\r\n" +
                    "Content-Type: application/json\r\nContent-Length: ${body.size}\r\n\r\n").toByteArray(Charsets.US_ASCII) + body)
                output.flush()
                val input = socket.inputStream
                val (status, answer) = readResponse(input)
                val error = answer?.optString("error")?.ifBlank { null }
                when (status) {
                    200 -> Unit
                    401 -> throw IOException("${peer.name} no longer trusts this device. Unpair it and pair again.")
                    403 -> throw IOException(error ?: "${peer.name} doesn't allow a trackpad.")
                    404 -> throw IOException("Update BubbleDrop on ${peer.name} to use this phone as its trackpad.")
                    503 -> throw IOException("${peer.name} has receiving turned off.")
                    else -> throw IOException("${peer.name} refused the trackpad ($status)")
                }
                socket.soTimeout = SILENCE_LIMIT_MS
                connected = true
                if (!closed && session === this) _state.value = State.Active(peer.name, answer?.optBoolean("keyboard") == true)

                // The Mac sends keepalives and the state of its settings, in packets like the phone's;
                // the read ends when it closes the connection or goes quiet.
                thread(name = "trackpad-input") {
                    runCatching {
                        val packets = DataInputStream(input)
                        while (true) {
                            val type = packets.readUnsignedByte()
                            val payload = ByteArray(packets.readUnsignedShort())
                            packets.readFully(payload)
                            if (type == TYPE_SETTING && payload.size >= 2 && payload[0].toInt() == SETTING_BLUETOOTH &&
                                session === this) {
                                _macBluetooth.value = payload[1].toInt() == 1
                            }
                        }
                    }
                    fail("Lost the connection to ${peer.name}")
                }
                // Unpairing mid-session ends it, like a mirror session.
                val trustWatch = Graph.scope.launch {
                    Graph.trust.devices.collect { devices ->
                        if (devices.none { it.fingerprint == pinned }) fail("${peer.name} was unpaired")
                    }
                }
                try {
                    val batch = ArrayList<ByteArray>()
                    val bytes = ByteArrayOutputStream()
                    while (!closed) {
                        val first = queue.poll(KEEPALIVE_MS, TimeUnit.MILLISECONDS)
                        batch.clear()
                        if (first == null) {
                            batch.add(packet(TYPE_KEEPALIVE, ByteArray(0)))
                        } else {
                            batch.add(first)
                            queue.drainTo(batch)
                        }
                        // One write for everything that queued up, so it leaves as one TLS record.
                        bytes.reset()
                        writeMerged(batch, bytes)
                        if (bytes.size() == 0) continue
                        output.write(bytes.toByteArray())
                        output.flush()
                    }
                } finally {
                    trustWatch.cancel()
                }
            } catch (e: Exception) {
                if (!closed) Log.w(TAG, "trackpad failed", e)
                fail(e.message ?: "Couldn't connect to ${peer.name}")
            } finally {
                runCatching { socket?.close() }
            }
        }

        /** The status, and the Mac's JSON body if it sent one. */
        private fun readResponse(input: InputStream): Pair<Int, JSONObject?> {
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
            if (length !in 0..(64 * 1024)) throw IOException("response too large")
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n < 0) throw IOException("connection closed")
                read += n
            }
            return status to runCatching { JSONObject(String(body, Charsets.UTF_8)) }.getOrNull()
        }
    }
}
