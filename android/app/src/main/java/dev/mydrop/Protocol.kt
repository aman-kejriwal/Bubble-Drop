package dev.mydrop

import org.json.JSONArray
import org.json.JSONObject

// Wire types for PROTOCOL.md v2.

const val PROTOCOL_VERSION = 2
const val SERVICE_TYPE = "_mydrop._tcp"

data class DeviceInfo(
    val id: String,
    val name: String,
    val model: String,
    val platform: String = "android",
    val protocolVersion: Int = PROTOCOL_VERSION,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("model", model)
        .put("platform", platform)
        .put("protocolVersion", protocolVersion)

    companion object {
        fun fromJson(o: JSONObject) = DeviceInfo(
            id = o.getString("id"),
            name = o.optString("name", "Unknown"),
            model = o.optString("model", ""),
            platform = o.optString("platform", ""),
            protocolVersion = o.optInt("protocolVersion", PROTOCOL_VERSION),
        )
    }
}

data class FileOffer(val id: String, val name: String, val size: Long, val mimeType: String) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("size", size)
        .put("mimeType", mimeType)

    companion object {
        fun fromJson(o: JSONObject) = FileOffer(
            id = o.getString("id"),
            name = o.optString("name", "file"),
            size = o.getLong("size"),
            mimeType = o.optString("mimeType", "application/octet-stream"),
        )
    }
}

data class AskRequest(val sender: DeviceInfo, val files: List<FileOffer>) {
    val totalSize: Long get() = files.sumOf { it.size }

    fun toJson(): JSONObject = JSONObject()
        .put("sender", sender.toJson())
        .put("files", JSONArray(files.map { it.toJson() }))

    companion object {
        fun fromJson(o: JSONObject): AskRequest {
            val files = o.getJSONArray("files")
            return AskRequest(
                sender = DeviceInfo.fromJson(o.getJSONObject("sender")),
                files = (0 until files.length()).map { FileOffer.fromJson(files.getJSONObject(it)) },
            )
        }
    }
}

/** Reduce an untrusted file name to a safe single path component. */
fun sanitizeFileName(raw: String): String {
    var name = raw.replace('\\', '/').substringAfterLast('/')
    name = name.replace(':', '_').filter { it != '\u0000' && it != '\n' && it != '\r' }.trim()
    name = name.trimStart('.')
    if (name.isEmpty()) name = "file"
    return name.take(200)
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1000) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1000.0
    var unit = 0
    while (value >= 1000 && unit < units.lastIndex) {
        value /= 1000
        unit++
    }
    return if (value >= 100) "%.0f %s".format(value, units[unit]) else "%.1f %s".format(value, units[unit])
}
