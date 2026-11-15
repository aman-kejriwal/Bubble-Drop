package dev.bubbledrop

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * Uses this phone as a paired Mac's trackpad (PROTOCOL.md "Trackpad"): finger movement, clicks
 * and scrolling go out as small packets on one long-lived TLS connection. The session lasts
 * only while the trackpad screen is open.
 */
object Trackpad {
    sealed interface State {
        object Idle : State
        data class Connecting(val name: String) : State
        data class Active(val name: String) : State
        data class Failed(val name: String, val message: String) : State
    }

    const val BUTTON_LEFT = 0
    const val BUTTON_RIGHT = 1

    const val SWIPE_LEFT = 0
    const val SWIPE_RIGHT = 1
    const val SWIPE_UP = 2
    const val SWIPE_DOWN = 3

    private const val TAG = "Trackpad"
    private const val TYPE_KEEPALIVE = 0
    private const val TYPE_MOVE = 1
    private const val TYPE_BUTTON = 2
    private const val TYPE_SCROLL = 3
    private const val TYPE_SWIPE = 4
    /** The Mac drops a phone it hasn't heard from in 10 s. */
    private const val KEEPALIVE_MS = 2_000L
    /** The Mac sends a keepalive every 2 s; this much silence means it's gone. */
    private const val SILENCE_LIMIT_MS = 8_000
    /** Packets waiting to go out when the connection stalls; older movement isn't worth keeping. */
    private const val MAX_QUEUED = 512

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    @Volatile private var session: Session? = null
    private var lastPeer: Peer? = null

    /** Connects to [peer] and shows the trackpad screen. */
    fun open(peer: Peer) {
        session?.end()
        lastPeer = peer
        val next = Session(peer)
        session = next
        _state.value = State.Connecting(peer.name)
        thread(name = "trackpad") { next.run() }
    }

    /** Connects again after a failure. */
    fun retry() {
        lastPeer?.let(::open)
    }

    /** Ends the session and leaves the trackpad screen. */
    fun close() {
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
            if (connected && !closed && queue.size < MAX_QUEUED) queue.offer(packet)
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
                val (status, error) = readResponse(input)
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
                if (!closed && session === this) _state.value = State.Active(peer.name)

                // The Mac only sends keepalives; the read ends when it closes the connection or goes quiet.
                thread(name = "trackpad-input") {
                    runCatching {
                        val buffer = ByteArray(64)
                        while (input.read(buffer) >= 0) Unit
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
                        batch.forEach(bytes::write)
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

        /** The status, and the Mac's reason from the JSON body if it gave one. */
        private fun readResponse(input: InputStream): Pair<Int, String?> {
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
            val error = runCatching { JSONObject(String(body, Charsets.UTF_8)).optString("error") }.getOrNull()
            return status to error?.ifBlank { null }
        }
    }
}
