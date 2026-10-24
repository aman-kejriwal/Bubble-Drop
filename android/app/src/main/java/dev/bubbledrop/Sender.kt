package dev.bubbledrop

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

class DeclinedException(message: String) : IOException(message)

/** Sends content URIs to a peer over mutual TLS: Discover → (Pair) → Ask → Upload each file. */
class Sender(
    private val context: Context,
    private val device: DeviceInfo,
    private val identity: TlsIdentity,
    private val trust: TrustStore,
    private val prompts: Prompts,
    private val transfers: TransferRepository,
) {
    private class Item(val offer: FileOffer, val open: () -> InputStream, val cleanup: () -> Unit = {})

    private class Response(val status: Int, val json: JSONObject, val fingerprint: String)

    /** Blocking; call from a background thread. */
    fun sendLink(peer: Peer, url: String) {
        val transferId = UUID.randomUUID().toString()
        transfers.upsert(
            Transfer(
                id = transferId, incoming = false, peerName = peer.name, peerPlatform = peer.platform,
                summary = url, totalBytes = 0, status = Transfer.Status.Waiting, link = url,
            )
        )
        try {
            val pinned = connectPaired(peer)
            val response = postJson(peer, pinned, "/api/v2/link", LinkRequest(device, url).toJson(), readTimeoutMs = 30_000)
            when (response.status) {
                200 -> Unit
                401 -> throw IOException("${peer.name} no longer trusts this device. Unpair it and pair again.")
                503 -> throw IOException("${peer.name} has receiving turned off.")
                else -> throw IOException(response.json.optString("error", "Request failed (${response.status})"))
            }
            val opened = response.json.optBoolean("opened", false)
            transfers.update(transferId) { it.copy(status = Transfer.Status.Done, linkOpened = opened) }
        } catch (e: Exception) {
            transfers.update(transferId) { it.copy(status = Transfer.Status.Failed(e.message ?: "Send failed")) }
        }
    }

    /** Blocking; call from a background thread. */
    fun send(peer: Peer, uris: List<Uri>) {
        val transferId = UUID.randomUUID().toString()
        var items: List<Item> = emptyList()
        transfers.upsert(
            Transfer(
                id = transferId, incoming = false, peerName = peer.name, peerPlatform = peer.platform,
                summary = "Preparing…",
                totalBytes = 0, status = Transfer.Status.Waiting,
            )
        )
        try {
            items = uris.map(::describe)
            val total = items.sumOf { it.offer.size }
            transfers.update(transferId) {
                it.copy(summary = describeNames(items.map { item -> item.offer.name }), totalBytes = total)
            }

            val pinned = connectPaired(peer)
            val ask = AskRequest(device, items.map { it.offer })
            val response = postJson(peer, pinned, "/api/v2/ask", ask.toJson(), readTimeoutMs = 150_000)
            when (response.status) {
                200 -> Unit
                401 -> throw IOException("${peer.name} no longer trusts this device. Unpair it and pair again.")
                403 -> throw DeclinedException("${peer.name} declined.")
                409 -> throw IOException("${peer.name} is busy with another request.")
                503 -> throw IOException("${peer.name} has receiving turned off.")
                else -> throw IOException(response.json.optString("error", "Request failed (${response.status})"))
            }
            val sessionId = response.json.getString("sessionId")

            transfers.update(transferId) { it.copy(status = Transfer.Status.Active) }
            var sent = 0L
            try {
                for (item in items) {
                    val base = sent
                    upload(peer, pinned, sessionId, item) { written ->
                        transfers.update(transferId) { it.copy(doneBytes = base + written) }
                    }
                    sent += item.offer.size
                }
            } catch (e: Exception) {
                runCatching { postJson(peer, pinned, "/api/v2/cancel?sessionId=${enc(sessionId)}", JSONObject(), 5_000) }
                throw e
            }
            transfers.update(transferId) { it.copy(doneBytes = it.totalBytes, status = Transfer.Status.Done) }
        } catch (e: Exception) {
            transfers.update(transferId) { it.copy(status = Transfer.Status.Failed(e.message ?: "Send failed")) }
        } finally {
            items.forEach { it.cleanup() }
        }
    }

    /**
     * Discovers [peer] and pairs with it unless both sides already trust each other.
     * Returns the certificate fingerprint every later request is pinned to.
     */
    /**
     * Posts [body] to [peer] only if it's already paired, pinned to the paired certificate; never
     * starts pairing. Returns the status, or null if [peer] isn't paired. Blocking.
     */
    fun postToPaired(peer: Peer, path: String, body: JSONObject): Int? {
        val fingerprint = peer.fingerprint?.takeIf(trust::isTrusted) ?: return null
        return postJson(peer, fingerprint, path, body, readTimeoutMs = 10_000).status
            .also { if (it == 200) trust.touch(fingerprint) }
    }

    /**
     * The certificate fingerprint of [peer] when both sides trust each other. Blocking. It pairs
     * only when [allowPairing] is set (the explicit Pair action); sending and mirroring never do,
     * so nothing works with a device until it's paired.
     */
    fun connectPaired(peer: Peer, allowPairing: Boolean = false): String {
        val discover = postJson(peer, null, "/api/v2/discover", device.toJson(), readTimeoutMs = 15_000)
        if (discover.status != 200) throw IOException("Couldn't reach ${peer.name} (${discover.status})")
        val fingerprint = discover.fingerprint
        val receiver = DeviceInfo.fromJson(discover.json.getJSONObject("device"))
        if (discover.json.optBoolean("paired") && trust.isTrusted(fingerprint)) {
            trust.touch(fingerprint)
            return fingerprint
        }
        if (!allowPairing) {
            throw IOException(
                if (trust.isTrusted(fingerprint)) "${receiver.name} doesn't trust this phone anymore. Unpair it and pair again."
                else "Pair with ${receiver.name} first."
            )
        }
        pair(peer, fingerprint, receiver)
        return fingerprint
    }

    private fun pair(peer: Peer, fingerprint: String, receiver: DeviceInfo) {
        fun cancel() = runCatching { postJson(peer, fingerprint, "/api/v2/pair/cancel", JSONObject(), 5_000) }

        val start = postJson(peer, fingerprint, "/api/v2/pair/start",
            JSONObject().put("sender", device.toJson()), 15_000)
        when (start.status) {
            200 -> Unit
            409 -> throw IOException("${peer.name} is busy with another request.")
            503 -> throw IOException("${peer.name} has receiving turned off.")
            else -> throw IOException(start.json.optString("error", "Pairing failed (${start.status})"))
        }
        val commitment = Base64.decode(start.json.getString("commitment"), Base64.NO_WRAP)

        val clientNonce = PairingMath.nonce()
        val reveal = postJson(peer, fingerprint, "/api/v2/pair/reveal",
            JSONObject().put("nonce", Base64.encodeToString(clientNonce, Base64.NO_WRAP)), 15_000)
        if (reveal.status != 200) throw IOException(reveal.json.optString("error", "Pairing failed (${reveal.status})"))
        val serverNonce = Base64.decode(reveal.json.getString("nonce"), Base64.NO_WRAP)
        if (!PairingMath.commitment(identity.fingerprint, fingerprint, serverNonce).contentEquals(commitment)) {
            cancel()
            throw IOException("Pairing check failed. Someone may be interfering with the connection.")
        }

        val code = PairingMath.code(identity.fingerprint, fingerprint, clientNonce, serverNonce)
        val confirmed = prompts.ask(PromptKind.PairOutgoing(receiver.name, code))
        if (confirmed != true) {
            cancel()
            throw if (confirmed == null) IOException("Another prompt is open.") else PairingCancelledException()
        }
        val confirm = postJson(peer, fingerprint, "/api/v2/pair/confirm", JSONObject(), 150_000)
        when (confirm.status) {
            200 -> trust.add(fingerprint, receiver)
            403 -> throw IOException("${receiver.name} didn't confirm the code.")
            else -> throw IOException(confirm.json.optString("error", "Pairing failed (${confirm.status})"))
        }
    }

    private fun describe(uri: Uri): Item {
        val resolver = context.contentResolver
        var name: String? = null
        var size: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0)
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val displayName = name ?: uri.lastPathSegment ?: "file"
        val open = { resolver.openInputStream(uri) ?: throw IOException("Can't read $displayName") }

        val known = size
        if (known != null && known >= 0) {
            return Item(FileOffer(UUID.randomUUID().toString(), displayName, known, mime), open)
        }
        // Unknown length: stage a copy so we can send an exact Content-Length.
        val staged = File.createTempFile("send", null, context.cacheDir)
        open().use { input -> staged.outputStream().use { input.copyTo(it) } }
        return Item(
            FileOffer(UUID.randomUUID().toString(), displayName, staged.length(), mime),
            open = { staged.inputStream() },
            cleanup = { staged.delete() },
        )
    }

    /** Opens an HTTPS connection presenting our certificate; with [pinned], only that server cert is accepted. */
    private fun open(peer: Peer, pinned: String?, path: String): Pair<HttpsURLConnection, PinningTrustManager> {
        val trustManager = PinningTrustManager(pinned)
        val conn = URL(peer.baseUrl + path).openConnection() as HttpsURLConnection
        conn.sslSocketFactory = identity.sslContext(trustManager).socketFactory
        // Identity comes from the pinned certificate fingerprint, not from hostnames.
        conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
        conn.setRequestProperty("Connection", "close")
        conn.connectTimeout = 10_000
        return conn to trustManager
    }

    private fun postJson(peer: Peer, pinned: String?, path: String, body: JSONObject, readTimeoutMs: Int): Response {
        val (conn, trustManager) = open(peer, pinned, path)
        try {
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            conn.requestMethod = "POST"
            conn.readTimeout = readTimeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
            val json = if (text.isBlank()) JSONObject() else JSONObject(text)
            val fingerprint = trustManager.seen ?: throw IOException("No server certificate")
            return Response(status, json, fingerprint)
        } finally {
            conn.disconnect()
        }
    }

    private fun upload(peer: Peer, pinned: String, sessionId: String, item: Item, progress: (Long) -> Unit) {
        val path = "/api/v2/upload?sessionId=${enc(sessionId)}&fileId=${enc(item.offer.id)}"
        val (conn, _) = open(peer, pinned, path)
        try {
            conn.requestMethod = "POST"
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.setFixedLengthStreamingMode(item.offer.size)
            item.open().use { input ->
                conn.outputStream.use { out ->
                    val buffer = ByteArray(256 * 1024)
                    var written = 0L
                    var lastReport = 0L
                    while (written < item.offer.size) {
                        val n = input.read(buffer, 0, minOf(buffer.size.toLong(), item.offer.size - written).toInt())
                        if (n < 0) throw IOException("${item.offer.name} changed while sending")
                        out.write(buffer, 0, n)
                        written += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 150 || written == item.offer.size) {
                            lastReport = now
                            progress(written)
                        }
                    }
                }
            }
            val status = conn.responseCode
            if (status != 200) {
                val text = conn.errorStream?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
                throw IOException(runCatching { JSONObject(text).getString("error") }.getOrDefault("Upload failed ($status)"))
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
}

/** The user tapped Cancel on the pairing code. */
class PairingCancelledException : IOException("Pairing cancelled.")
