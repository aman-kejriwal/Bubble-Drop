package dev.mydrop

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder

/** Minimal HTTP/1.1 request framing for the receive server. One request per connection. */
class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: InputStream,
) {
    val contentLength: Long get() = headers["content-length"]?.toLongOrNull() ?: 0

    fun readJson(limit: Int = 1 shl 20): JSONObject {
        val length = contentLength
        if (length > limit) throw IOException("body too large")
        val bytes = ByteArray(length.toInt())
        var read = 0
        while (read < bytes.size) {
            val n = body.read(bytes, read, bytes.size - read)
            if (n < 0) throw IOException("connection closed")
            read += n
        }
        return if (bytes.isEmpty()) JSONObject() else JSONObject(String(bytes, Charsets.UTF_8))
    }

    companion object {
        fun read(input: InputStream, limit: Int = 64 * 1024): HttpRequest {
            val head = ByteArrayOutputStream()
            var matched = 0
            val terminator = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
            while (matched < 4) {
                val b = input.read()
                if (b < 0) throw IOException("connection closed")
                head.write(b)
                matched = if (b.toByte() == terminator[matched]) matched + 1 else if (b == '\r'.code) 1 else 0
                if (head.size() > limit) throw IOException("header too large")
            }
            val lines = head.toString(Charsets.UTF_8.name()).split("\r\n").filter { it.isNotEmpty() }
            val parts = lines.first().split(" ")
            if (parts.size < 2) throw IOException("bad request line")
            val target = parts[1]
            val path = target.substringBefore('?')
            val query = target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to
                    URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            }
            val headers = lines.drop(1).mapNotNull { line ->
                val colon = line.indexOf(':')
                if (colon <= 0) null else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
            }.toMap()
            return HttpRequest(parts[0], path, query, headers, input)
        }
    }
}

fun OutputStream.writeJsonResponse(status: Int, json: JSONObject) {
    val body = json.toString().toByteArray(Charsets.UTF_8)
    val reason = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        409 -> "Conflict"
        503 -> "Service Unavailable"
        else -> "Error"
    }
    val head = "HTTP/1.1 $status $reason\r\n" +
        "Content-Type: application/json\r\n" +
        "Content-Length: ${body.size}\r\n" +
        "Connection: close\r\n\r\n"
    write(head.toByteArray(Charsets.UTF_8))
    write(body)
    flush()
}

fun errorJson(message: String): JSONObject = JSONObject().put("error", message)
