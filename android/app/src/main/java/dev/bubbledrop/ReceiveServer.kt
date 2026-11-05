package dev.bubbledrop

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/** Serves the PROTOCOL.md v2 endpoints over mutual TLS and saves files to Downloads/BubbleDrop. */
class ReceiveServer(
    private val context: Context,
    private val device: DeviceInfo,
    private val identity: TlsIdentity,
    private val trust: TrustStore,
    private val prompts: Prompts,
    private val transfers: TransferRepository,
) {
    private class Session(val client: String, offers: List<FileOffer>, emptyFolderTops: List<String>) {
        /** The whole offer is one sent folder, so Open can show that folder. */
        val singleFolder = (offers.map { it.folders.firstOrNull() } + emptyFolderTops).distinct().singleOrNull() != null
        val pending = ConcurrentHashMap(offers.associateBy { it.id })
        val totalBytes = offers.sumOf { it.size }
        var inFlight = 0
        var receivedBytes = 0L
        val saved = mutableListOf<Uri>()
        /** Sent folder name → the name it's saved under (e.g. "Photos (1)" if "Photos" already existed). */
        val folders = HashMap<String, String>()
        @Volatile var lastActivity = System.currentTimeMillis()
    }

    private class PairingState(val client: String, val sender: DeviceInfo) {
        val serverNonce = PairingMath.nonce()
        val started = System.currentTimeMillis()
        @Volatile var prompt: Prompts.Pending? = null
        @Volatile var revealedAt = 0L
    }

    @Volatile var receivingEnabled = true
    private val sessions = ConcurrentHashMap<String, Session>()
    private val pool = Executors.newCachedThreadPool()
    private var socket: ServerSocket? = null
    private val pairLock = Any()
    private var pairing: PairingState? = null

    /** Starts listening on an ephemeral port and returns it. */
    fun start(): Int {
        val server = identity.sslContext(PinningTrustManager()).serverSocketFactory
            .createServerSocket(0) as SSLServerSocket
        server.needClientAuth = true
        socket = server
        thread(name = "bubbledrop-accept", isDaemon = true) {
            while (!server.isClosed) {
                val client = try { server.accept() as SSLSocket } catch (_: IOException) { break }
                pool.execute { handle(client) }
            }
        }
        return server.localPort
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun handle(client: SSLSocket) = client.use { s ->
        s.soTimeout = 30_000
        try {
            s.startHandshake()
        } catch (e: IOException) {
            Log.w(TAG, "TLS handshake failed: ${e.message}")
            return@use
        }
        val peer = (s.session.peerCertificates.firstOrNull() as? X509Certificate)?.let(::fingerprintOf) ?: return@use
        val input = BufferedInputStream(s.inputStream, 256 * 1024)
        val output = BufferedOutputStream(s.outputStream)
        try {
            val request = HttpRequest.read(input)
            val paired = trust.isTrusted(peer)
            if (paired) trust.touch(peer)
            // Folder sync: the file goes out as the raw body rather than JSON.
            if (request.path == "/api/v2/sync/file" && request.method == "POST" && paired && receivingEnabled) {
                val file = FolderSync.file(request.readJson().getString("path"))
                if (file != null) output.writeFileResponse(file) else output.writeJsonResponse(404, errorJson("no such file"))
                return@use
            }
            val (status, body) = if (request.method != "POST") 404 to errorJson("not found") else when (request.path) {
                "/api/v2/discover" -> {
                    request.readJson()
                    200 to JSONObject().put("device", device.toJson()).put("paired", paired)
                }
                "/api/v2/pair/start" -> pairStart(peer, DeviceInfo.fromJson(request.readJson().getJSONObject("sender")))
                "/api/v2/pair/reveal" -> pairReveal(peer, request.readJson().getString("nonce"))
                "/api/v2/pair/confirm" -> {
                    request.readJson()
                    pairConfirm(peer)
                }
                "/api/v2/pair/wait" -> {
                    request.readJson()
                    pairWait(peer)
                }
                "/api/v2/pair/cancel" -> {
                    request.readJson()
                    pairCancel(peer)
                    200 to JSONObject()
                }
                "/api/v2/ask" ->
                    if (!paired) PAIRING_REQUIRED else handleAsk(peer, AskRequest.fromJson(request.readJson()))
                "/api/v2/upload" -> if (!paired) PAIRING_REQUIRED else handleUpload(peer, request)
                "/api/v2/link" ->
                    if (!paired) PAIRING_REQUIRED else handleLink(peer, LinkRequest.fromJson(request.readJson()))
                "/api/v2/notification/action" -> if (!paired) PAIRING_REQUIRED else {
                    val body = request.readJson()
                    val relay = NotificationRelay.instance
                    val done = relay?.perform(body.getString("key"), body.getInt("index"),
                        body.optString("reply").takeIf { body.has("reply") }) == true
                    if (done) 200 to JSONObject() else 404 to errorJson("notification is gone")
                }
                "/api/v2/call/action" -> if (!paired) PAIRING_REQUIRED else {
                    val body = request.readJson()
                    if (Calls.perform(context, body.getString("action"), body.optBoolean("speaker"))) {
                        200 to JSONObject()
                    } else {
                        404 to errorJson("no call")
                    }
                }
                "/api/v2/find" -> if (!paired) PAIRING_REQUIRED else {
                    request.readJson()
                    FindMyPhone.ring(context, trust.device(peer)?.name ?: "Your Mac")
                    200 to JSONObject()
                }
                "/api/v2/mirror/request" -> if (!paired) PAIRING_REQUIRED else {
                    val ownerConfirmed = request.readJson().optBoolean("ownerConfirmed", false)
                    MirrorRequests.request(context, peer, trust.device(peer)?.name ?: "your Mac", ownerConfirmed)
                    200 to JSONObject()
                }
                "/api/v2/notification/dismiss" -> if (!paired) PAIRING_REQUIRED else {
                    NotificationRelay.instance?.dismiss(request.readJson().getString("key"))
                    200 to JSONObject()
                }
                "/api/v2/sync/index" -> if (!paired) PAIRING_REQUIRED else {
                    request.readJson()
                    if (!receivingEnabled) 503 to errorJson("receiving is off")
                    else FolderSync.index()?.let { 200 to it.toJson() } ?: (404 to errorJson("folder sync is off"))
                }
                "/api/v2/sync/file" -> if (!paired) PAIRING_REQUIRED else 503 to errorJson("receiving is off")
                "/api/v2/sync/changed" -> if (!paired) PAIRING_REQUIRED else {
                    request.readJson()
                    if (receivingEnabled) FolderSync.peerChanged()
                    200 to JSONObject()
                }
                "/api/v2/cancel" -> {
                    request.query["sessionId"]?.let { id -> sessions.computeIfPresent(id) { _, v -> v.takeIf { it.client != peer } } }
                    200 to JSONObject()
                }
                else -> 404 to errorJson("not found")
            }
            output.writeJsonResponse(status, body)
        } catch (e: Exception) {
            Log.w(TAG, "request failed", e)
            runCatching { output.writeJsonResponse(400, errorJson(e.message ?: "bad request")) }
        }
    }

    // Links

    private fun handleLink(client: String, link: LinkRequest): Pair<Int, JSONObject> {
        if (!receivingEnabled) return 503 to errorJson("receiving is off")
        if (!isWebLink(link.url)) return 400 to errorJson("only http and https links can be opened")
        val sender = trust.device(client)?.let { link.sender.copy(name = it.name) } ?: link.sender
        val opened = Links.open(context, sender, link.url)
        transfers.upsert(
            Transfer(
                id = UUID.randomUUID().toString(), incoming = true, peerName = sender.name,
                peerPlatform = sender.platform, summary = link.url, totalBytes = 0,
                status = Transfer.Status.Done, link = link.url, linkOpened = opened,
            )
        )
        return 200 to JSONObject().put("opened", opened)
    }

    // Pairing

    private fun pairStart(client: String, sender: DeviceInfo): Pair<Int, JSONObject> {
        if (!receivingEnabled) return 503 to errorJson("receiving is off")
        // A phone never pairs with itself (its own certificate on the other end).
        if (client == identity.fingerprint) return 403 to errorJson("can't pair with itself")
        // One pairing per device: once paired, nobody can even ask until it's unpaired here.
        if (!trust.acceptsPairing) return 403 to errorJson("already paired")
        val state = synchronized(pairLock) {
            val current = pairing
            if (current != null && current.client != client && System.currentTimeMillis() - current.started < 150_000) {
                return 409 to errorJson("busy")
            }
            current?.prompt?.let(prompts::dismiss)
            PairingState(client, sender).also { pairing = it }
        }
        val commitment = PairingMath.commitment(client, identity.fingerprint, state.serverNonce)
        return 200 to JSONObject().put("commitment", b64(commitment))
    }

    private fun pairReveal(client: String, nonce: String): Pair<Int, JSONObject> {
        val clientNonce = Base64.decode(nonce, Base64.NO_WRAP)
        if (clientNonce.size != 32) return 400 to errorJson("bad nonce")
        val state = synchronized(pairLock) { pairing?.takeIf { it.client == client && it.prompt == null } }
            ?: return 404 to errorJson("no pairing in progress")
        val code = PairingMath.code(client, identity.fingerprint, clientNonce, state.serverNonce)
        state.prompt = prompts.begin(PromptKind.PairIncoming(state.sender, code)) ?: return 409 to errorJson("busy")
        state.revealedAt = System.currentTimeMillis()
        return 200 to JSONObject().put("nonce", b64(state.serverNonce))
    }

    private fun pairConfirm(client: String): Pair<Int, JSONObject> {
        val state = synchronized(pairLock) { pairing?.takeIf { it.client == client } }
        val prompt = state?.prompt ?: return 404 to errorJson("no pairing in progress")
        val confirmed = prompts.await(prompt, PROMPT_TIMEOUT_MS - (System.currentTimeMillis() - state.revealedAt))
        val stillCurrent = synchronized(pairLock) {
            (pairing === state).also { if (it) pairing = null }
        }
        if (!confirmed || !stillCurrent) return 403 to errorJson("pairing declined")
        // Another pairing may have finished while this one waited for the user.
        if (!trust.acceptsPairing) return 403 to errorJson("already paired")
        trust.add(client, state.sender)
        return 200 to JSONObject()
    }

    /**
     * Held until this phone's user answers the code, so the other device can close its own code
     * prompt as soon as the user here declines. Unlike confirm, it doesn't vouch for that device's user.
     */
    private fun pairWait(client: String): Pair<Int, JSONObject> {
        val state = synchronized(pairLock) { pairing?.takeIf { it.client == client } }
        val prompt = state?.prompt ?: return 404 to errorJson("no pairing in progress")
        val accepted = prompts.await(prompt, PROMPT_TIMEOUT_MS - (System.currentTimeMillis() - state.revealedAt))
        if (!accepted) synchronized(pairLock) { if (pairing === state) pairing = null }
        return 200 to JSONObject().put("accepted", accepted)
    }

    private fun pairCancel(client: String) {
        val state = synchronized(pairLock) {
            pairing?.takeIf { it.client == client }?.also { pairing = null }
        }
        state?.prompt?.let(prompts::dismiss)
    }

    // Transfers

    private fun handleAsk(client: String, offer: AskRequest): Pair<Int, JSONObject> {
        if (!receivingEnabled) return 503 to errorJson("receiving is off")
        if (offer.files.size + offer.emptyFolders.size == 0 || offer.files.size > 1000 || offer.emptyFolders.size > 1000 ||
            offer.files.any { it.size < 0 }) {
            return 400 to errorJson("invalid file list")
        }
        // Name the sender as it was paired, not as it currently claims to be.
        val request = trust.device(client)?.let { offer.copy(sender = offer.sender.copy(name = it.name)) } ?: offer
        val accepted = prompts.ask(PromptKind.Offer(request), PROMPT_TIMEOUT_MS) ?: return 409 to errorJson("busy")
        if (!accepted) return 403 to errorJson("declined")

        val now = System.currentTimeMillis()
        sessions.entries.removeIf { now - it.value.lastActivity > SESSION_EXPIRY_MS }
        val id = UUID.randomUUID().toString()
        val session = Session(client, request.files, request.emptyFolderComponents.map { it.first() })
        sessions[id] = session
        transfers.upsert(
            Transfer(
                id = id, incoming = true, peerName = request.sender.name, peerPlatform = request.sender.platform,
                summary = request.summary(),
                totalBytes = session.totalBytes, status = Transfer.Status.Active,
            )
        )
        // Empty folders are made right away, in the same place the folder's files will go.
        for (folders in request.emptyFolderComponents) {
            val path = relativePath(session, folders)
            runCatching { makeFolder(path) }.onFailure { Log.w(TAG, "couldn't create $path", it) }
            showFolder(id, session, path)
        }
        if (request.files.isEmpty()) {
            sessions.remove(id)
            transfers.update(id) { it.copy(status = Transfer.Status.Done) }
        }
        return 200 to JSONObject().put("sessionId", id)
    }

    private fun handleUpload(client: String, request: HttpRequest): Pair<Int, JSONObject> {
        val sessionId = request.query["sessionId"]
        val session = sessionId?.let { sessions[it] }?.takeIf { it.client == client }
        val offer = request.query["fileId"]?.let { session?.pending?.remove(it) }
        if (sessionId == null || session == null || offer == null) {
            return 404 to errorJson("unknown session or file")
        }
        if (request.contentLength != offer.size) {
            session.pending[offer.id] = offer
            return 400 to errorJson("length mismatch")
        }
        synchronized(session) { session.inFlight++ }

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, sanitizeFileName(offer.name))
            put(MediaStore.Downloads.MIME_TYPE, offer.mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath(session, offer.folders).also { path ->
                if (offer.folders.isNotEmpty()) showFolder(sessionId, session, path)
            })
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("could not create file in Downloads")
        try {
            val base = synchronized(session) { session.receivedBytes }
            resolver.openOutputStream(uri)!!.use { out ->
                val buffer = ByteArray(256 * 1024)
                var remaining = offer.size
                var lastReport = 0L
                while (remaining > 0) {
                    val n = request.body.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (n < 0) throw IOException("upload truncated")
                    out.write(buffer, 0, n)
                    remaining -= n
                    session.lastActivity = System.currentTimeMillis()
                    if (session.lastActivity - lastReport > 150 || remaining == 0L) {
                        lastReport = session.lastActivity
                        val done = base + offer.size - remaining
                        transfers.update(sessionId) { it.copy(doneBytes = done) }
                    }
                }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            synchronized(session) { session.inFlight-- }
            session.pending[offer.id] = offer
            transfers.update(sessionId) { it.copy(status = Transfer.Status.Failed(e.message ?: "upload failed")) }
            throw e
        }

        val finished = synchronized(session) {
            session.inFlight--
            session.receivedBytes += offer.size
            session.saved += uri
            session.pending.isEmpty() && session.inFlight == 0
        }
        transfers.update(sessionId) { it.copy(savedUris = session.saved.toList()) }
        if (finished) {
            sessions.remove(sessionId)
            transfers.update(sessionId) { it.copy(doneBytes = it.totalBytes, status = Transfer.Status.Done) }
        }
        return 200 to JSONObject()
    }

    /** What Open shows for this transfer: the sent folder, or Downloads/BubbleDrop if there's more. */
    private fun showFolder(sessionId: String, session: Session, path: String) {
        val shown = if (session.singleFolder) path.split('/').take(3).joinToString("/") else "Download/BubbleDrop"
        transfers.update(sessionId) { it.copy(savedFolder = shown) }
    }

    /**
     * Creates the folder at [relative] (e.g. "Download/BubbleDrop/Photos/Empty"). MediaStore only
     * makes folders for files in them, so if this app may not create one directly, a placeholder
     * file makes it and is then removed.
     */
    private fun makeFolder(relative: String) {
        val dir = File(Environment.getExternalStorageDirectory(), relative)
        if (dir.isDirectory || dir.mkdirs()) return
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "bubbledrop-folder-${UUID.randomUUID()}")
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, relative)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("can't create $relative")
        resolver.openOutputStream(uri)?.close()
        resolver.delete(uri, null, null)
    }

    /** Downloads/BubbleDrop, or the sent folder inside it for a file from one ([folders] outermost first). */
    private fun relativePath(session: Session, folders: List<String>): String {
        val top = folders.firstOrNull() ?: return "Download/BubbleDrop"
        val saved = synchronized(session) {
            session.folders.getOrPut(top) {
                // A sent folder never merges into one already there.
                val base = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "BubbleDrop")
                var name = top
                var n = 1
                while (File(base, name).exists()) name = "$top (${n++})"
                name
            }
        }
        return (listOf("Download/BubbleDrop", saved) + folders.drop(1)).joinToString("/")
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    companion object {
        private const val TAG = "BubbleDropServer"
        private const val SESSION_EXPIRY_MS = 10 * 60 * 1000L
        private const val PROMPT_TIMEOUT_MS = 120_000L
        private val PAIRING_REQUIRED get() = 401 to errorJson("pairing required")
    }
}
