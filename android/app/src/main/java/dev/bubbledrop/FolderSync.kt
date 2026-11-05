package dev.bubbledrop

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import android.provider.DocumentsContract
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

// Folder sync (PROTOCOL.md, "Folder sync"): one folder kept identical with the paired device.
// The engine only changes this phone's folder; the other device pulls what it needs from us.

/** One file in a sync index. [hash] is null once deleted here; [base] is the last agreed content. */
data class SyncEntry(val path: String, val size: Long, val hash: String?, val base: String?) {
    fun toJson(): JSONObject = JSONObject().put("path", path).put("size", size)
        .put("hash", hash ?: JSONObject.NULL).put("base", base ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject) = SyncEntry(
            path = o.getString("path"),
            size = o.optLong("size", 0),
            hash = o.optStringOrNull("hash"),
            base = o.optStringOrNull("base"),
        )
    }
}

private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else optString(key)

class SyncIndex(val folder: String, val entries: List<SyncEntry>) {
    fun toJson(): JSONObject = JSONObject().put("folder", folder).put("entries", JSONArray(entries.map { it.toJson() }))

    companion object {
        fun fromJson(o: JSONObject): SyncIndex {
            val list = o.optJSONArray("entries") ?: JSONArray()
            return SyncIndex(o.optString("folder"), (0 until list.length()).map { SyncEntry.fromJson(list.getJSONObject(it)) })
        }
    }
}

sealed interface SyncAction {
    val path: String

    /** Download [hash] into [path]; [expectedLocal] is what's there now (null: nothing). */
    data class Pull(
        override val path: String, val hash: String, val size: Long,
        val expectedLocal: String?, val keepLocalAsConflict: Boolean,
    ) : SyncAction

    /** Move the local file, which must still hold [expectedLocal], to the trash. */
    data class Delete(override val path: String, val expectedLocal: String) : SyncAction
}

class ChangedLocallyException : IOException("It changed here meanwhile; trying again next round.")

object SyncPaths {
    private val ignoredNames = setOf("thumbs.db", "desktop.ini")
    private val ignoredSuffixes = listOf(".tmp", ".part", ".crdownload", ".download")

    /** Hidden files (incl. BubbleDrop's own partial files and trash), Office lock files, temp downloads. */
    fun isIgnored(name: String): Boolean {
        val lower = name.lowercase()
        return name.startsWith(".") || name.startsWith("~$") || lower in ignoredNames ||
            ignoredSuffixes.any { lower.endsWith(it) }
    }

    /** A relative path from the other device that is safe to create inside the folder. */
    fun isValid(path: String): Boolean {
        if (path.isEmpty() || path.toByteArray().size > 1024 || path.startsWith("/")) return false
        if (path.contains('\\') || path.any { it.code < 0x20 || it.code == 0x7f }) return false
        return path.split('/').all { part ->
            part.isNotEmpty() && part != "." && part != ".." && part.toByteArray().size <= 255 && !isIgnored(part)
        }
    }
}

enum class SyncDecision {
    InSync, Wait, TakeRemote, KeepBothTakeRemote;

    companion object {
        /** PROTOCOL.md "Deciding". */
        fun decide(local: String?, localBase: String?, remote: String?, remoteBase: String?, localWins: Boolean): SyncDecision {
            if (local == remote) return InSync
            val remoteNewer = remoteBase == local && localBase != remote
            val localNewer = localBase == remote && remoteBase != local
            if (remoteNewer && !localNewer) return TakeRemote
            if (localNewer && !remoteNewer) return Wait
            if (local == null) return TakeRemote
            if (remote == null) return Wait
            return if (localWins) Wait else KeepBothTakeRemote
        }
    }
}

/** This phone's sync folder and what it knows about each file. Thread-safe; downloads happen outside it. */
class FolderSyncEngine(val root: File, private val stateFile: File) {
    private class Record(var size: Long, var mtime: Long, var hash: String?, var base: String?)

    class ScanResult(val changed: Boolean, val unsettled: Boolean, val directories: List<File>)

    private val records = HashMap<String, Record>()

    init {
        runCatching {
            val state = JSONObject(stateFile.readText())
            if (state.getString("root") == root.path) {
                val saved = state.getJSONObject("records")
                for (path in saved.keys()) {
                    val r = saved.getJSONObject(path)
                    records[path] = Record(r.getLong("size"), r.getLong("mtime"), r.optStringOrNull("hash"), r.optStringOrNull("base"))
                }
            }
        }
    }

    val fileCount: Int @Synchronized get() = records.values.count { it.hash != null }

    @Synchronized
    fun scan(): ScanResult {
        if (!root.isDirectory) throw IOException("The sync folder isn't available: ${root.path}")
        val seen = HashSet<String>()
        val dirs = mutableListOf(root)
        var changed = false
        var unsettled = false
        val now = System.currentTimeMillis()

        fun walk(dir: File, prefix: String) {
            // Unreadable must not look like deleted.
            val items = dir.listFiles() ?: throw IOException("Can't read ${dir.path}")
            for (item in items) {
                val name = item.name
                if (SyncPaths.isIgnored(name) || Files.isSymbolicLink(item.toPath())) continue
                val path = prefix + name
                if (item.isDirectory) {
                    dirs += item
                    walk(item, "$path/")
                    continue
                }
                if (!item.isFile) continue
                seen += path
                if (seen.size > MAX_FILES) throw IOException("The folder has too many files to sync (over 100,000).")
                val size = item.length()
                val mtime = item.lastModified()
                val old = records[path]
                if (old != null && old.hash != null && old.size == size && old.mtime == mtime) continue
                if (now - mtime < SETTLE_MS) {
                    unsettled = true
                    continue
                }
                val hash = runCatching { sha256(item) }.getOrNull()
                if (hash == null) {
                    unsettled = true
                    continue
                }
                if (old?.hash != hash) changed = true
                records[path] = Record(size, mtime, hash, old?.base)
            }
        }
        walk(root, "")

        val gone = records.filter { (path, r) -> path !in seen && r.hash != null }
        for ((path, record) in gone) {
            changed = true
            if (record.base == null) records.remove(path) else record.hash = null
        }
        save()
        return ScanResult(changed, unsettled, dirs)
    }

    /** Rescans, then returns the index to send to the other device. */
    @Synchronized
    fun index(): SyncIndex {
        scan()
        return SyncIndex(root.name, records.map { (path, r) -> SyncEntry(path, r.size, r.hash, r.base) })
    }

    /** Decides every path against the other side's index; paths in sync get their base recorded now. */
    @Synchronized
    fun plan(remote: List<SyncEntry>, localFingerprint: String, remoteFingerprint: String): List<SyncAction> {
        val theirs = remote.filter { SyncPaths.isValid(it.path) }.associateBy { it.path }
        val localWins = localFingerprint < remoteFingerprint
        val actions = mutableListOf<SyncAction>()
        for (path in records.keys + theirs.keys) {
            val mine = records[path]
            val other = theirs[path]
            when (val decision = SyncDecision.decide(mine?.hash, mine?.base, other?.hash, other?.base, localWins)) {
                SyncDecision.InSync -> if (mine == null || mine.hash == null) records.remove(path) else mine.base = mine.hash
                SyncDecision.Wait -> Unit
                SyncDecision.TakeRemote, SyncDecision.KeepBothTakeRemote -> {
                    val hash = other?.hash
                    val local = mine?.hash
                    if (other != null && hash != null) {
                        actions += SyncAction.Pull(path, hash, other.size, local, decision == SyncDecision.KeepBothTakeRemote)
                    } else if (local != null) {
                        actions += SyncAction.Delete(path, local)
                    }
                }
            }
        }
        save()
        // Smaller files first, so a big video doesn't hold up everything else.
        return actions.sortedBy { if (it is SyncAction.Pull) it.size else -1 }
    }

    /** Where to download before [apply]: inside the folder (atomic rename) under a hidden name. */
    fun partialFile(): File = File(root, ".bubbledrop-partial-${UUID.randomUUID()}")

    @Synchronized
    fun apply(action: SyncAction, downloaded: File?, deviceName: String) {
        val target = File(root, action.path)
        val expected = when (action) {
            is SyncAction.Pull -> action.expectedLocal
            is SyncAction.Delete -> action.expectedLocal
        }
        checkUnchanged(action.path, target, expected)
        when (action) {
            is SyncAction.Delete -> {
                moveToTrash(target, action.path)
                records.remove(action.path)
                removeEmptyParents(target)
            }
            is SyncAction.Pull -> {
                downloaded ?: throw IOException("missing download")
                target.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw IOException("Can't create ${it.name}") }
                if (action.expectedLocal != null) {
                    if (action.keepLocalAsConflict) {
                        val copy = conflictFile(target, deviceName)
                        if (!target.renameTo(copy)) throw IOException("Can't keep a copy of ${target.name}")
                        records[relativePath(copy)] = Record(copy.length(), copy.lastModified(), action.expectedLocal, null)
                    } else {
                        moveToTrash(target, action.path)
                    }
                }
                if (!downloaded.renameTo(target)) throw IOException("Can't save ${target.name}")
                records[action.path] = Record(target.length(), target.lastModified(), action.hash, action.hash)
            }
        }
        save()
    }

    /** The file to serve for [path]: only one the scan indexed, never anything outside the folder. */
    @Synchronized
    fun file(path: String): File? {
        if (!SyncPaths.isValid(path) || records[path]?.hash == null) return null
        return File(root, path).takeIf { it.isFile }
    }

    /** Deletes trashed files older than 30 days. */
    fun purgeTrash() {
        val cutoff = System.currentTimeMillis() - TRASH_DAYS * 24 * 3600 * 1000L
        File(root, TRASH).listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
    }

    private fun checkUnchanged(path: String, file: File, expected: String?) {
        val exists = file.exists()
        if (expected == null) {
            if (exists) throw IOException("A file with that name already exists here.")
            return
        }
        val record = records[path]
        if (!file.isFile || record == null || record.hash != expected ||
            record.size != file.length() || record.mtime != file.lastModified()) {
            throw ChangedLocallyException()
        }
    }

    private fun moveToTrash(file: File, path: String) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HHmmss", Locale.US).format(Date())
        val dest = File(File(root, TRASH), "$stamp/$path")
        dest.parentFile?.mkdirs()
        if (!file.renameTo(dest)) throw IOException("Can't move ${file.name} to the trash")
    }

    private fun conflictFile(file: File, deviceName: String): File {
        val stamp = SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).format(Date())
        val ext = file.extension
        val base = file.nameWithoutExtension
        val device = deviceName.replace('/', '-')
        var n = 1
        while (true) {
            val suffix = if (n == 1) "" else " $n"
            val name = "$base (conflict from $device $stamp$suffix)" + if (ext.isEmpty()) "" else ".$ext"
            val candidate = File(file.parentFile, name)
            if (!candidate.exists()) return candidate
            n++
        }
    }

    private fun relativePath(file: File) = file.path.removePrefix(root.path).removePrefix("/")

    private fun removeEmptyParents(file: File) {
        var dir = file.parentFile
        while (dir != null && dir.path.startsWith(root.path + "/") && dir.list()?.isEmpty() == true) {
            dir.delete()
            dir = dir.parentFile
        }
    }

    /** Best effort: a state that couldn't be saved is rebuilt by the next scan (with no bases). */
    private fun save() {
        runCatching {
            val saved = JSONObject()
            for ((path, r) in records) {
                saved.put(path, JSONObject().put("size", r.size).put("mtime", r.mtime)
                    .put("hash", r.hash ?: JSONObject.NULL).put("base", r.base ?: JSONObject.NULL))
            }
            val tmp = File(stateFile.path + ".tmp")
            tmp.writeText(JSONObject().put("root", root.path).put("records", saved).toString())
            tmp.renameTo(stateFile)
        }
    }

    companion object {
        const val TRASH = ".bubbledrop-trash"
        private const val TRASH_DAYS = 30
        private const val MAX_FILES = 100_000
        /** Files modified more recently than this are still being written; they wait for the next scan. */
        private const val SETTLE_MS = 2_000L

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().toHex()
        }
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** inotify on every folder in the tree (FileObserver isn't recursive); [onEvent] runs on a binder thread. */
private class TreeWatcher(private val onEvent: () -> Unit) {
    private val observers = HashMap<String, FileObserver>()

    @Synchronized
    fun watch(directories: List<File>) {
        val wanted = directories.associateBy { it.path }
        (observers.keys - wanted.keys).forEach { observers.remove(it)?.stopWatching() }
        for ((path, dir) in wanted) {
            if (path in observers) continue
            val observer = object : FileObserver(dir, MASK) {
                override fun onEvent(event: Int, name: String?) {
                    if (name != null && SyncPaths.isIgnored(name)) return
                    onEvent()
                }
            }
            observer.startWatching()
            observers[path] = observer
        }
    }

    @Synchronized
    fun stop() {
        observers.values.forEach { it.stopWatching() }
        observers.clear()
    }

    companion object {
        private const val MASK = FileObserver.CLOSE_WRITE or FileObserver.CREATE or FileObserver.DELETE or
            FileObserver.MOVED_FROM or FileObserver.MOVED_TO or FileObserver.DELETE_SELF or FileObserver.MOVE_SELF
    }
}

/**
 * Keeps one folder on this phone identical with a folder on the paired device, while BubbleDrop is
 * on. A change here is noticed by inotify and announced to the other device, which pulls it; a
 * change there arrives as `/sync/changed` and is pulled from here. A full round also runs when the
 * device comes into view and every few minutes, catching anything inotify missed.
 */
object FolderSync {
    enum class Phase { Off, NoAccess, Waiting, NotSetUpThere, Syncing, Ready }

    data class Status(
        val folder: String? = null,
        val phase: Phase = Phase.Off,
        val lastSynced: Long? = null,
        val error: String? = null,
        val peerFolder: String? = null,
        val fileCount: Int = 0,
    )

    private const val TAG = "FolderSync"
    private const val PREF_FOLDER = "syncFolder"
    private const val FULL_SYNC_MINUTES = 5L

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "folder-sync").apply { isDaemon = true } }
    @Volatile private var engine: FolderSyncEngine? = null
    private var watcher: TreeWatcher? = null
    private var context: Context? = null
    private var scanFuture: ScheduledFuture<*>? = null
    private var syncFuture: ScheduledFuture<*>? = null
    private var periodic: ScheduledFuture<*>? = null
    private var peerWatch: Job? = null

    // Lifecycle: runs while BubbleDropService does.

    fun start(context: Context) = worker.execute {
        if (this.context != null) return@execute
        val app = context.applicationContext
        this.context = app
        openSaved(app)
        periodic = worker.scheduleWithFixedDelay({ syncNow() }, FULL_SYNC_MINUTES, FULL_SYNC_MINUTES, TimeUnit.MINUTES)
        peerWatch = Graph.scope.launch {
            Graph.discovery.peers.map { syncPeer() != null }.distinctUntilChanged().collect { nearby ->
                if (nearby) requestSync() else worker.execute { setPhaseIfIdle(Phase.Waiting) }
            }
        }
    }

    fun stop() = worker.execute {
        peerWatch?.cancel()
        periodic?.cancel(false)
        close()
        context = null
    }

    /** After returning from the "All files access" screen: start if it was just allowed. */
    fun refreshAccess(context: Context) = worker.execute {
        val app = context.applicationContext
        if (folderPath(app) == null) return@execute
        if (engine == null && this.context != null) openSaved(app)
        else if (!hasAccess(app)) {
            close()
            _status.update { it.copy(phase = Phase.NoAccess) }
        }
    }

    // Choosing the folder

    /** The folder an "Open folder" pick points at, or null if it isn't on this phone's storage. */
    fun folderFromTree(uri: Uri): File? = runCatching {
        val id = DocumentsContract.getTreeDocumentId(uri)
        val volume = id.substringBefore(':')
        val relative = id.substringAfter(':', "")
        val base = if (volume.equals("primary", ignoreCase = true)) Environment.getExternalStorageDirectory()
            else File("/storage/$volume")
        if (relative.isEmpty()) null else File(base, relative)
    }.getOrNull()

    fun setFolder(context: Context, folder: File) = worker.execute {
        val app = context.applicationContext
        if (folder.path != folderPath(app)) {
            close()
            // A new folder starts over: nothing is known about it, so the first round only adds files.
            stateFile(app).delete()
        }
        prefs(app).edit().putString(PREF_FOLDER, folder.path).apply()
        _status.value = Status(folder = folder.path, phase = Phase.Waiting)
        if (this.context != null) openSaved(app)
    }

    fun stopSyncing(context: Context) = worker.execute {
        val app = context.applicationContext
        close()
        prefs(app).edit().remove(PREF_FOLDER).apply()
        stateFile(app).delete()
        _status.value = Status()
    }

    fun hasAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** Android 11+: the "All files access" screen for BubbleDrop. */
    fun accessSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))

    /** The saved folder, shown even while BubbleDrop is off. */
    fun loadSaved(context: Context) {
        val path = folderPath(context) ?: return
        if (_status.value.folder == null) {
            _status.value = Status(folder = path, phase = if (hasAccess(context)) Phase.Off else Phase.NoAccess)
        }
    }

    // Triggers

    fun syncNow() {
        scanNow()
        requestSync()
    }

    /** The other device's folder changed. */
    fun peerChanged() = requestSync()

    private fun scanNow() = scheduleScan(0)

    private fun scheduleScan(delayMs: Long) {
        synchronized(this) {
            scanFuture?.cancel(false)
            scanFuture = worker.schedule(::localScan, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    /** Syncs after a moment, so a burst of changes is one round. Rounds run one at a time on [worker]. */
    private fun requestSync() {
        synchronized(this) {
            syncFuture?.cancel(false)
            syncFuture = worker.schedule(::round, 500, TimeUnit.MILLISECONDS)
        }
    }

    // Server hooks (any thread)

    fun index(): SyncIndex? = engine?.let { runCatching { it.index() }.getOrNull() }

    fun file(path: String): File? = engine?.file(path)

    // On the worker

    private fun openSaved(context: Context) {
        val path = folderPath(context) ?: run {
            _status.value = Status()
            return
        }
        if (!hasAccess(context)) {
            _status.value = Status(folder = path, phase = Phase.NoAccess)
            return
        }
        val engine = FolderSyncEngine(File(path), stateFile(context))
        this.engine = engine
        watcher = TreeWatcher { scheduleScan(1_500) }
        _status.update { Status(folder = path, phase = Phase.Waiting, fileCount = engine.fileCount, peerFolder = it.peerFolder) }
        runCatching { engine.purgeTrash() }
        localScan()
        requestSync()
    }

    private fun close() {
        watcher?.stop()
        watcher = null
        engine = null
    }

    /** Rescans after a local change and tells the other device if anything changed. */
    private fun localScan() {
        val engine = engine ?: return
        try {
            val scan = engine.scan()
            watcher?.watch(scan.directories)
            _status.update { it.copy(fileCount = engine.fileCount) }
            if (scan.changed) syncPeer()?.let { peer -> runCatching { Graph.sender.postToPaired(peer, "/api/v2/sync/changed", JSONObject()) } }
            if (scan.unsettled) scheduleScan(2_500)
        } catch (e: Exception) {
            _status.update { it.copy(error = e.message) }
        }
    }

    /** One full round: fetch the other side's index, decide every path, then pull and delete here. */
    private fun round() {
        val engine = engine ?: return
        val peer = syncPeer() ?: return setPhaseIfIdle(Phase.Waiting)
        val pinned = peer.fingerprint ?: return
        _status.update { it.copy(phase = Phase.Syncing) }
        try {
            val scan = engine.scan()
            watcher?.watch(scan.directories)
            val remote = Graph.sender.syncIndex(peer, pinned)
            if (remote == null) {
                _status.update { it.copy(phase = Phase.NotSetUpThere, error = null) }
                return
            }
            val actions = engine.plan(remote.entries, Graph.identity.fingerprint, pinned)
            var changes = 0
            var retry = false
            val failures = mutableListOf<String>()
            for (action in actions) {
                if (this.engine !== engine) return
                var downloaded: File? = null
                try {
                    if (action is SyncAction.Pull) {
                        downloaded = engine.partialFile()
                        // It changed there after the index was made; the next round gets the new version.
                        if (Graph.sender.syncDownload(peer, pinned, action.path, downloaded) != action.hash) {
                            throw ChangedLocallyException()
                        }
                    }
                    engine.apply(action, downloaded, Graph.device.name)
                    changes++
                } catch (_: ChangedLocallyException) {
                    retry = true
                } catch (e: Exception) {
                    Log.w(TAG, "sync ${action.path} failed", e)
                    failures += "${action.path}: ${e.message}"
                } finally {
                    downloaded?.delete()
                }
            }
            // Conflict copies made here are new files the other device should pull.
            runCatching { engine.scan() }
            if (changes > 0) runCatching { Graph.sender.postToPaired(peer, "/api/v2/sync/changed", JSONObject()) }
            _status.update {
                it.copy(
                    phase = Phase.Ready, lastSynced = System.currentTimeMillis(), peerFolder = remote.folder,
                    fileCount = engine.fileCount,
                    error = when (failures.size) {
                        0 -> null
                        1 -> failures[0]
                        else -> "${failures.size} files didn't sync. ${failures[0]}"
                    },
                )
            }
            // Retry right away only if this round made progress; otherwise wait for the next change.
            if (retry && changes > 0) requestSync()
        } catch (e: Exception) {
            Log.w(TAG, "sync round failed", e)
            _status.update { it.copy(phase = Phase.Ready, error = e.message ?: "Sync failed") }
        }
    }

    private fun setPhaseIfIdle(phase: Phase) {
        if (engine != null) _status.update { if (it.phase == Phase.Syncing) it else it.copy(phase = phase) }
    }

    /** The paired device, if it's nearby. */
    private fun syncPeer(): Peer? {
        if (!Graph.server.receivingEnabled) return null
        val paired = Graph.trust.devices.value.firstOrNull()?.fingerprint ?: return null
        return Graph.discovery.peers.value.firstOrNull { it.fingerprint == paired }
    }

    private fun prefs(context: Context) = context.getSharedPreferences("bubbledrop", Context.MODE_PRIVATE)
    private fun folderPath(context: Context) = prefs(context).getString(PREF_FOLDER, null)
    private fun stateFile(context: Context) = File(context.filesDir, "sync-state.json")
}
