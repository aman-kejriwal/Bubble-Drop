package dev.bubbledrop

import org.json.JSONArray
import org.json.JSONObject

// Wire types for PROTOCOL.md v2.

const val PROTOCOL_VERSION = 2
const val SERVICE_TYPE = "_bubbledrop._tcp"

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

/**
 * [name] is the file's name alone (receivers that don't know [path] save it under that). [path] is set
 * for a file in a sent folder: its path from, and including, that folder, e.g. "Photos/2024/a.jpg".
 */
data class FileOffer(val id: String, val name: String, val size: Long, val mimeType: String, val path: String? = null) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("size", size)
        .put("mimeType", mimeType)
        .apply { if (path != null) put("path", path) }

    /** The folders to save this file in, outermost first; empty for a loose file or an unsafe path. */
    val folders: List<String>
        get() = path?.takeIf(::isSafeRelativePath)?.split('/')?.dropLast(1)?.map(::sanitizeFileName).orEmpty()

    companion object {
        fun fromJson(o: JSONObject) = FileOffer(
            id = o.getString("id"),
            name = o.optString("name", "file"),
            size = o.getLong("size"),
            mimeType = o.optString("mimeType", "application/octet-stream"),
            path = if (o.isNull("path")) null else o.optString("path"),
        )
    }
}

/** What an offer looks like at the top level: loose files, and sent folders with their file count. */
data class OfferItem(val name: String, val isFolder: Boolean, val fileCount: Int, val size: Long)

/** A relative path that can't leave the folder it's created in. */
fun isSafeRelativePath(path: String): Boolean {
    if (path.isEmpty() || path.toByteArray().size > 1024 || path.startsWith("/") || path.contains('\\')) return false
    if (path.any { it.code < 0x20 || it.code == 0x7f }) return false
    return path.split('/').all { it.isNotEmpty() && it != "." && it != ".." && it.toByteArray().size <= 255 }
}

fun offerItems(files: List<FileOffer>, emptyFolderTops: List<String> = emptyList()): List<OfferItem> {
    val items = mutableListOf<OfferItem>()
    for (file in files) {
        val folder = file.folders.firstOrNull()
        val i = if (folder == null) -1 else items.indexOfFirst { it.isFolder && it.name == folder }
        if (i >= 0) items[i] = items[i].copy(fileCount = items[i].fileCount + 1, size = items[i].size + file.size)
        else items += OfferItem(folder ?: file.name, folder != null, 1, file.size)
    }
    // A sent folder holding only empty folders still shows up.
    for (top in emptyFolderTops.distinct()) {
        if (items.none { it.isFolder && it.name == top }) items += OfferItem(top, true, 0, 0)
    }
    return items
}

fun OfferItem.label(): String = if (!isFolder) name
    else "$name (${when (fileCount) { 0 -> "empty"; 1 -> "1 file"; else -> "$fileCount files" }})"

/** "photo.jpg", "Photos (12 files)", "3 files" or "4 items". */
fun describeOffer(items: List<OfferItem>): String {
    items.singleOrNull()?.let { return it.label() }
    return if (items.any { it.isFolder }) "${items.size} items" else "${items.size} files"
}

/**
 * [emptyFolders]: folders in a sent folder that hold no files, as paths like [FileOffer.path]
 * ("Photos/Empty"). Older senders leave it out, and older receivers ignore it.
 */
data class AskRequest(val sender: DeviceInfo, val files: List<FileOffer>, val emptyFolders: List<String> = emptyList()) {
    val totalSize: Long get() = files.sumOf { it.size }

    /** The empty folders to create, each as safe folder names outermost first (unsafe paths dropped). */
    val emptyFolderComponents: List<List<String>>
        get() = emptyFolders.filter(::isSafeRelativePath).map { path -> path.split('/').map(::sanitizeFileName) }

    /** Sent folders and loose files, in the order offered. */
    fun items(): List<OfferItem> = offerItems(files, emptyFolderComponents.map { it.first() })

    /** "photo.jpg", "Photos (12 files)", "3 files" or "4 items". */
    fun summary(): String = describeOffer(items())

    fun toJson(): JSONObject = JSONObject()
        .put("sender", sender.toJson())
        .put("files", JSONArray(files.map { it.toJson() }))
        .apply { if (emptyFolders.isNotEmpty()) put("emptyFolders", JSONArray(emptyFolders)) }

    companion object {
        fun fromJson(o: JSONObject): AskRequest {
            val files = o.getJSONArray("files")
            val folders = o.optJSONArray("emptyFolders")
            return AskRequest(
                sender = DeviceInfo.fromJson(o.getJSONObject("sender")),
                files = (0 until files.length()).map { FileOffer.fromJson(files.getJSONObject(it)) },
                emptyFolders = if (folders == null) emptyList() else (0 until folders.length()).map { folders.getString(it) },
            )
        }
    }
}

/** A web page to open on the receiver: `POST /api/v2/link`. */
data class LinkRequest(val sender: DeviceInfo, val url: String) {
    fun toJson(): JSONObject = JSONObject()
        .put("sender", sender.toJson())
        .put("url", url)

    companion object {
        fun fromJson(o: JSONObject) = LinkRequest(
            sender = DeviceInfo.fromJson(o.getJSONObject("sender")),
            url = o.getString("url"),
        )
    }
}

/** Body of `POST /api/v2/mirror`; screen packets follow on the same connection. */
data class MirrorRequest(val sender: DeviceInfo, val control: Boolean) {
    fun toJson(): JSONObject = JSONObject().put("sender", sender.toJson()).put("control", control)
}

/** Only http(s) links are sent or opened; other schemes could launch arbitrary apps or files. */
fun isWebLink(url: String): Boolean = runCatching {
    val uri = java.net.URI(url)
    uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrEmpty()
}.getOrDefault(false)

/** The first web link in shared text, e.g. "Look at this https://example.com" from a browser's share. */
fun firstWebLink(text: String): String? =
    Regex("""https?://\S+""", RegexOption.IGNORE_CASE).find(text)?.value
        ?.trimEnd('.', ',', ')', ']', '"', '\'')
        ?.takeIf(::isWebLink)

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
