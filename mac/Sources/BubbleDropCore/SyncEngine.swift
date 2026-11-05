import CoreServices
import CryptoKit
import Foundation
import Network

// Folder sync (PROTOCOL.md, "Folder sync"): one folder kept identical with the paired device.
// The engine only changes this side's folder; the other device pulls what it needs from us.

/// One file in a sync index.
public struct SyncEntry: Codable, Sendable, Equatable {
    public var path: String
    public var size: Int64
    /// The content now; nil once the file was deleted here.
    public var hash: String?
    /// The content both devices last agreed on; nil if never.
    public var base: String?

    public init(path: String, size: Int64, hash: String?, base: String?) {
        self.path = path
        self.size = size
        self.hash = hash
        self.base = base
    }
}

/// Response of `/api/v2/sync/index`.
public struct SyncIndex: Codable, Sendable {
    public var folder: String
    public var entries: [SyncEntry]

    public init(folder: String, entries: [SyncEntry]) {
        self.folder = folder
        self.entries = entries
    }
}

/// Body of `/api/v2/sync/file`.
public struct SyncFileRequest: Codable, Sendable {
    public var path: String

    public init(path: String) {
        self.path = path
    }
}

/// What a sync round does to this side's folder.
public enum SyncAction: Sendable {
    /// Download `hash` into `path`. `expectedLocal` is what's there now (nil: nothing). With
    /// `keepLocalAsConflict` the local file is kept under a conflict name instead of trashed.
    case pull(path: String, hash: String, size: Int64, expectedLocal: String?, keepLocalAsConflict: Bool)
    /// Trash the local file, which must still hold `expectedLocal`.
    case delete(path: String, expectedLocal: String)

    public var path: String {
        switch self {
        case .pull(let path, _, _, _, _), .delete(let path, _): return path
        }
    }
}

public enum SyncError: LocalizedError {
    case folderMissing(String)
    case changedLocally
    case nameTaken
    case tooManyFiles

    public var errorDescription: String? {
        switch self {
        case .folderMissing(let path): return "The sync folder isn't available: \(path)"
        case .changedLocally: return "It changed here meanwhile; trying again next round."
        case .nameTaken: return "A file with a similar name already exists here."
        case .tooManyFiles: return "The folder has too many files to sync (over 100,000)."
        }
    }
}

public enum SyncPaths {
    private static let ignoredNames: Set<String> = ["thumbs.db", "desktop.ini"]
    private static let ignoredSuffixes = [".tmp", ".part", ".crdownload", ".download"]

    /// Files and folders never synced: hidden ones (incl. BubbleDrop's own partial files), Office
    /// lock files and common temporary downloads.
    public static func isIgnored(name: String) -> Bool {
        let lower = name.lowercased()
        return name.hasPrefix(".") || name.hasPrefix("~$") || ignoredNames.contains(lower)
            || ignoredSuffixes.contains { lower.hasSuffix($0) }
    }

    /// A relative path from the other device that is safe to create inside the folder.
    public static func isValid(_ path: String) -> Bool {
        guard !path.isEmpty, path.utf8.count <= 1024, !path.hasPrefix("/") else { return false }
        if path.contains("\\") || path.unicodeScalars.contains(where: { $0.value < 0x20 || $0.value == 0x7f }) {
            return false
        }
        let parts = path.split(separator: "/", omittingEmptySubsequences: false)
        return parts.allSatisfy { part in
            !part.isEmpty && part != "." && part != ".." && part.utf8.count <= 255 && !isIgnored(name: String(part))
        }
    }
}

/// The per-path decision of PROTOCOL.md "Deciding".
public enum SyncDecision: Equatable, Sendable {
    case inSync
    case wait
    case takeRemote
    case keepBothTakeRemote

    public static func decide(local: String?, localBase: String?, remote: String?, remoteBase: String?,
                              localWins: Bool) -> SyncDecision {
        if local == remote { return .inSync }
        let remoteNewer = remoteBase == local && localBase != remote
        let localNewer = localBase == remote && remoteBase != local
        if remoteNewer && !localNewer { return .takeRemote }
        if localNewer && !remoteNewer { return .wait }
        if local == nil { return .takeRemote }
        if remote == nil { return .wait }
        return localWins ? .wait : .keepBothTakeRemote
    }
}

/// This side's folder and what it knows about each file. Thread-safe; downloads happen outside it.
public final class FolderSyncEngine: @unchecked Sendable {
    public let root: URL
    private let stateFile: URL
    private let lock = NSLock()
    private var records: [String: Record] = [:]
    private static let maxFiles = 100_000
    /// Files modified more recently than this are still being written; they wait for the next scan.
    private static let settleTime: TimeInterval = 2

    private struct Record: Codable {
        var size: Int64
        var mtime: Double
        var hash: String?
        var base: String?
    }

    private struct State: Codable {
        var root: String
        var records: [String: Record]
    }

    public struct ScanResult: Sendable {
        /// A file was added, edited or deleted since the last scan.
        public var changed: Bool
        /// Some files are still being written; scan again shortly.
        public var unsettled: Bool
    }

    /// Loads what was known about [root] (nothing, if the state was for another folder).
    public init(root: URL, stateFile: URL) {
        self.root = root.standardizedFileURL
        self.stateFile = stateFile
        if let data = try? Data(contentsOf: stateFile),
           let state = try? JSONDecoder().decode(State.self, from: data), state.root == self.root.path {
            records = state.records
        }
    }

    /// Forgets everything known about the folder (after choosing another one).
    public static func reset(stateFile: URL) {
        try? FileManager.default.removeItem(at: stateFile)
    }

    public var fileCount: Int {
        locked { records.values.filter { $0.hash != nil }.count }
    }

    // MARK: Scanning

    @discardableResult
    public func scan() throws -> ScanResult {
        try locked { try scanLocked() }
    }

    /// Rescans, then returns the index to send to the other device.
    public func index() throws -> SyncIndex {
        try locked {
            _ = try scanLocked()
            let entries = records.map { SyncEntry(path: $0.key, size: $0.value.size, hash: $0.value.hash, base: $0.value.base) }
            return SyncIndex(folder: root.lastPathComponent, entries: entries)
        }
    }

    private func scanLocked() throws -> ScanResult {
        var isDirectory: ObjCBool = false
        guard FileManager.default.fileExists(atPath: root.path, isDirectory: &isDirectory), isDirectory.boolValue else {
            throw SyncError.folderMissing(root.path)
        }
        var seen = Set<String>()
        var changed = false
        var unsettled = false
        let now = Date().timeIntervalSince1970
        let keys: [URLResourceKey] = [.isRegularFileKey, .isDirectoryKey, .isSymbolicLinkKey, .fileSizeKey,
                                      .contentModificationDateKey]

        func walk(_ dir: URL, prefix: String) throws {
            // Throws if unreadable: an unreadable folder must not look like deleted files.
            let items = try FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: keys)
            for item in items {
                let name = item.lastPathComponent
                guard !SyncPaths.isIgnored(name: name),
                      let values = try? item.resourceValues(forKeys: Set(keys)),
                      values.isSymbolicLink != true else { continue }
                let path = prefix + name
                if values.isDirectory == true {
                    try walk(item, prefix: path + "/")
                    continue
                }
                guard values.isRegularFile == true else { continue }
                seen.insert(path)
                guard seen.count <= Self.maxFiles else { throw SyncError.tooManyFiles }
                let size = Int64(values.fileSize ?? 0)
                let mtime = values.contentModificationDate?.timeIntervalSince1970 ?? 0
                let old = records[path]
                if let old, old.hash != nil, old.size == size, old.mtime == mtime { continue }
                if now - mtime < Self.settleTime {
                    unsettled = true
                    continue
                }
                guard let hash = try? Self.sha256(of: item) else {
                    unsettled = true
                    continue
                }
                if old?.hash != hash { changed = true }
                records[path] = Record(size: size, mtime: mtime, hash: hash, base: old?.base)
            }
        }
        try walk(root, prefix: "")

        for (path, record) in records where !seen.contains(path) && record.hash != nil {
            changed = true
            if record.base == nil {
                records[path] = nil
            } else {
                records[path]?.hash = nil
            }
        }
        save()
        return ScanResult(changed: changed, unsettled: unsettled)
    }

    // MARK: Planning and applying

    /// Decides every path against the other side's index. Paths already in sync have their base
    /// recorded right away; the rest come back as actions to run one by one.
    public func plan(remote: [SyncEntry], localFingerprint: String, remoteFingerprint: String) -> [SyncAction] {
        locked {
            let theirs = Dictionary(remote.filter { SyncPaths.isValid($0.path) }.map { ($0.path, $0) },
                                    uniquingKeysWith: { a, _ in a })
            let localWins = localFingerprint < remoteFingerprint
            var actions: [SyncAction] = []
            for path in Set(records.keys).union(theirs.keys) {
                let mine = records[path]
                let other = theirs[path]
                let decision = SyncDecision.decide(local: mine?.hash, localBase: mine?.base,
                                                   remote: other?.hash, remoteBase: other?.base, localWins: localWins)
                switch decision {
                case .inSync:
                    if mine?.hash == nil { records[path] = nil } else { records[path]?.base = mine?.hash }
                case .wait:
                    break
                case .takeRemote, .keepBothTakeRemote:
                    if let hash = other?.hash, let other {
                        actions.append(.pull(path: path, hash: hash, size: other.size, expectedLocal: mine?.hash,
                                             keepLocalAsConflict: decision == .keepBothTakeRemote))
                    } else if let local = mine?.hash {
                        actions.append(.delete(path: path, expectedLocal: local))
                    }
                }
            }
            save()
            // Smaller files first, so a big video doesn't hold up everything else.
            return actions.sorted { a, b in
                func size(_ action: SyncAction) -> Int64 {
                    if case .pull(_, _, let size, _, _) = action { return size }
                    return -1
                }
                return size(a) < size(b)
            }
        }
    }

    /// Where to download a file before [apply] moves it into place: inside the folder (so the move
    /// is atomic) under a hidden name the scan ignores.
    public func partialURL() -> URL {
        root.appendingPathComponent(".bubbledrop-partial-\(UUID().uuidString)")
    }

    /// Runs one action. For a pull, [downloaded] holds the verified content. [deviceName] names
    /// this device in a conflict copy.
    public func apply(_ action: SyncAction, downloaded: URL?, deviceName: String) throws {
        try locked {
            let fm = FileManager.default
            let path = action.path
            let target = url(for: path)
            let expected: String? = switch action {
            case .pull(_, _, _, let local, _): local
            case .delete(_, let local): local
            }
            try checkUnchanged(path: path, at: target, expected: expected)

            switch action {
            case .delete:
                try fm.trashItem(at: target, resultingItemURL: nil)
                records[path] = nil
                removeEmptyParents(of: target)
            case .pull(_, let hash, _, let local, let keepLocal):
                guard let downloaded else { throw BubbleDropError.badMessage("missing download") }
                try fm.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
                if local != nil {
                    if keepLocal {
                        let copy = conflictURL(for: target, deviceName: deviceName)
                        try fm.moveItem(at: target, to: copy)
                        if let (size, mtime) = stat(copy) {
                            records[relativePath(of: copy)] = Record(size: size, mtime: mtime, hash: local, base: nil)
                        }
                    } else {
                        try fm.trashItem(at: target, resultingItemURL: nil)
                    }
                }
                try fm.moveItem(at: downloaded, to: target)
                let (size, mtime) = stat(target) ?? (0, 0)
                records[path] = Record(size: size, mtime: mtime, hash: hash, base: hash)
            }
            save()
        }
    }

    /// The file to serve for [path]: only one the scan indexed, never anything outside the folder.
    public func file(at path: String) -> (URL, Int64)? {
        locked {
            guard SyncPaths.isValid(path), records[path]?.hash != nil else { return nil }
            let url = url(for: path)
            guard let (size, _) = stat(url) else { return nil }
            return (url, size)
        }
    }

    // MARK: Helpers

    /// The local file must still be what the plan saw: unchanged, or still absent.
    private func checkUnchanged(path: String, at url: URL, expected: String?) throws {
        let exists = FileManager.default.fileExists(atPath: url.path)
        guard let expected else {
            // On a case-insensitive disk "a.txt" may already exist as "A.txt".
            if exists { throw SyncError.nameTaken }
            return
        }
        guard exists, let record = records[path], record.hash == expected,
              let (size, mtime) = stat(url), size == record.size, mtime == record.mtime else {
            throw SyncError.changedLocally
        }
    }

    private func url(for path: String) -> URL {
        root.appendingPathComponent(path, isDirectory: false)
    }

    private func relativePath(of url: URL) -> String {
        String(url.standardizedFileURL.path.dropFirst(root.path.count + 1))
    }

    private func stat(_ url: URL) -> (Int64, Double)? {
        guard let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey, .contentModificationDateKey]),
              values.isRegularFile == true else { return nil }
        return (Int64(values.fileSize ?? 0), values.contentModificationDate?.timeIntervalSince1970 ?? 0)
    }

    private func conflictURL(for url: URL, deviceName: String) -> URL {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd HHmm"
        let stamp = formatter.string(from: Date())
        let dir = url.deletingLastPathComponent()
        let ext = url.pathExtension
        let base = url.deletingPathExtension().lastPathComponent
        let device = deviceName.replacingOccurrences(of: "/", with: "-")
        var n = 1
        while true {
            let suffix = n == 1 ? "" : " \(n)"
            let name = "\(base) (conflict from \(device) \(stamp)\(suffix))" + (ext.isEmpty ? "" : ".\(ext)")
            let candidate = dir.appendingPathComponent(name)
            if !FileManager.default.fileExists(atPath: candidate.path) { return candidate }
            n += 1
        }
    }

    private func removeEmptyParents(of url: URL) {
        var dir = url.deletingLastPathComponent().standardizedFileURL
        while dir.path.count > root.path.count, dir.path.hasPrefix(root.path + "/") {
            let contents = (try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? ["?"]
            // .DS_Store alone doesn't keep a folder alive.
            guard contents.allSatisfy({ $0 == ".DS_Store" }) else { return }
            try? FileManager.default.removeItem(at: dir)
            dir = dir.deletingLastPathComponent()
        }
    }

    private func save() {
        guard let data = try? JSONEncoder().encode(State(root: root.path, records: records)) else { return }
        try? FileManager.default.createDirectory(at: stateFile.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? data.write(to: stateFile, options: .atomic)
    }

    private func locked<T>(_ body: () throws -> T) rethrows -> T {
        lock.lock()
        defer { lock.unlock() }
        return try body()
    }

    public static func sha256(of url: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var hasher = SHA256()
        while let chunk = try handle.read(upToCount: 1 << 20), !chunk.isEmpty {
            hasher.update(data: chunk)
        }
        return hexString(hasher.finalize())
    }
}

func hexString<D: Sequence>(_ bytes: D) -> String where D.Element == UInt8 {
    bytes.map { String(format: "%02x", $0) }.joined()
}

/// Calls [onChange] (on a background queue) when anything inside [url] changes, via FSEvents.
public final class FolderWatcher {
    private var stream: FSEventStreamRef?
    private let onChange: () -> Void
    private let queue = DispatchQueue(label: "bubbledrop.folder-watch")

    public init(url: URL, onChange: @escaping () -> Void) {
        self.onChange = onChange
        var context = FSEventStreamContext(version: 0, info: Unmanaged.passUnretained(self).toOpaque(),
                                           retain: nil, release: nil, copyDescription: nil)
        let callback: FSEventStreamCallback = { _, info, _, _, _, _ in
            guard let info else { return }
            Unmanaged<FolderWatcher>.fromOpaque(info).takeUnretainedValue().onChange()
        }
        let flags = FSEventStreamCreateFlags(kFSEventStreamCreateFlagWatchRoot | kFSEventStreamCreateFlagNoDefer)
        guard let stream = FSEventStreamCreate(nil, callback, &context, [url.path] as CFArray,
                                               FSEventStreamEventId(kFSEventStreamEventIdSinceNow), 0.5, flags) else {
            return
        }
        FSEventStreamSetDispatchQueue(stream, queue)
        FSEventStreamStart(stream)
        self.stream = stream
    }

    public func stop() {
        guard let stream else { return }
        FSEventStreamStop(stream)
        FSEventStreamInvalidate(stream)
        FSEventStreamRelease(stream)
        self.stream = nil
    }

    deinit { stop() }
}

/// What one sync round did.
public struct SyncRoundResult: Sendable {
    /// The folder's name on the other device.
    public var remoteFolder: String
    /// Files pulled, deleted or kept as conflict copies here.
    public var changes: Int
    /// Files that couldn't sync, as "path: reason".
    public var failures: [String]
    /// Something changed on either side mid-round; run another round soon.
    public var retry: Bool
}

extension BubbleDropClient {
    /// One full round against the paired device at [endpoint]: fetch its index, decide every path,
    /// then pull and delete here. Returns nil if that device has no sync folder. Tells the other
    /// device when anything changed here, so it records the new bases.
    public func syncRound(engine: FolderSyncEngine, endpoint: NWEndpoint, pinned: String,
                          deviceName: String) async throws -> SyncRoundResult? {
        try engine.scan()
        guard let remote = try await syncIndex(from: endpoint, pinned: pinned) else { return nil }
        let actions = engine.plan(remote: remote.entries, localFingerprint: identity.fingerprint,
                                  remoteFingerprint: pinned)
        var result = SyncRoundResult(remoteFolder: remote.folder, changes: 0, failures: [], retry: false)
        for action in actions {
            var downloaded: URL?
            do {
                if case .pull(let path, let hash, _, _, _) = action {
                    let partial = engine.partialURL()
                    downloaded = partial
                    let got = try await syncDownload(path: path, from: endpoint, pinned: pinned, to: partial)
                    // It changed there after the index was made; the next round gets the new version.
                    guard got == hash else { throw SyncError.changedLocally }
                }
                try engine.apply(action, downloaded: downloaded, deviceName: deviceName)
                result.changes += 1
            } catch SyncError.changedLocally {
                result.retry = true
            } catch {
                result.failures.append("\(action.path): \(error.localizedDescription)")
            }
            if let downloaded { try? FileManager.default.removeItem(at: downloaded) }
        }
        // Conflict copies made here are new files the other device should pull.
        try? engine.scan()
        if result.changes > 0 {
            _ = try? await post(EmptyRequest(), path: "/api/v2/sync/changed", to: endpoint, pinned: pinned)
        }
        return result
    }
}
