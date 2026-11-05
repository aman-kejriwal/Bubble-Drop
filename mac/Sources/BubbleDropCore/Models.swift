import Foundation

public let bubbleDropProtocolVersion = 2
public let bubbleDropServiceType = "_bubbledrop._tcp"

public struct DeviceInfo: Codable, Hashable, Sendable {
    public var id: String
    public var name: String
    public var model: String
    public var platform: String
    public var protocolVersion: Int

    public init(id: String, name: String, model: String, platform: String = "macos",
                protocolVersion: Int = bubbleDropProtocolVersion) {
        self.id = id
        self.name = name
        self.model = model
        self.platform = platform
        self.protocolVersion = protocolVersion
    }

    /// This Mac, with an ID persisted in the given defaults.
    public static func local(defaults: UserDefaults = .standard, name: String? = nil) -> DeviceInfo {
        let key = "BubbleDropDeviceID"
        let id: String
        if let saved = defaults.string(forKey: key) {
            id = saved
        } else {
            id = UUID().uuidString
            defaults.set(id, forKey: key)
        }
        return DeviceInfo(id: id,
                          name: name ?? Host.current().localizedName ?? "Mac",
                          model: hardwareModel())
    }

    var txtRecord: [String: String] {
        ["id": id, "name": name, "model": model, "platform": platform, "v": String(protocolVersion)]
    }
}

public struct FileOffer: Codable, Hashable, Sendable {
    public var id: String
    /// The file's name alone; receivers that don't know `path` save the file under it.
    public var name: String
    public var size: Int64
    public var mimeType: String
    /// For a file in a sent folder: its path from (and including) that folder, e.g. "Photos/2024/a.jpg".
    public var path: String?

    public init(id: String = UUID().uuidString, name: String, size: Int64, mimeType: String, path: String? = nil) {
        self.id = id
        self.name = name
        self.size = size
        self.mimeType = mimeType
        self.path = path
    }

    /// The folders to save this file in, outermost first, from a safe `path`; empty for a loose file
    /// or a path that isn't safe (which is then saved like a loose file).
    public var folders: [String] {
        guard let path, isSafeRelativePath(path) else { return [] }
        return path.split(separator: "/").dropLast().map { sanitizeFileName(String($0)) }
    }
}

/// What an offer looks like at the top level: loose files, and sent folders with their file count.
public struct OfferItem: Hashable, Sendable {
    public var name: String
    public var isFolder: Bool
    public var fileCount: Int
    public var size: Int64

    public var fileCountText: String { fileCount == 0 ? "empty" : fileCount == 1 ? "1 file" : "\(fileCount) files" }
}

/// A relative path that can't leave the folder it's created in.
public func isSafeRelativePath(_ path: String) -> Bool {
    guard !path.isEmpty, path.utf8.count <= 1024, !path.hasPrefix("/"), !path.contains("\\"),
          !path.unicodeScalars.contains(where: { $0.value < 0x20 || $0.value == 0x7f }) else { return false }
    return path.split(separator: "/", omittingEmptySubsequences: false).allSatisfy {
        !$0.isEmpty && $0 != "." && $0 != ".." && $0.utf8.count <= 255
    }
}

/// A web page to open on the receiver: `POST /api/v2/link`.
public struct LinkRequest: Codable, Sendable {
    public var sender: DeviceInfo
    public var url: String

    public init(sender: DeviceInfo, url: String) {
        self.sender = sender
        self.url = url
    }
}

public struct LinkResponse: Codable, Sendable {
    /// Whether the receiver opened the link right away (otherwise it showed a notification).
    public var opened: Bool

    public init(opened: Bool) {
        self.opened = opened
    }
}

/// Only http(s) links are sent or opened; other schemes could launch arbitrary apps or files.
public func isWebLink(_ url: URL) -> Bool {
    ["http", "https"].contains(url.scheme?.lowercased() ?? "") && !(url.host ?? "").isEmpty
}

public struct AskRequest: Codable, Sendable {
    public var sender: DeviceInfo
    public var files: [FileOffer]
    /// Folders in a sent folder that hold no files, as paths like FileOffer.path ("Photos/Empty").
    /// Optional: older senders leave it out, and older receivers ignore it.
    public var emptyFolders: [String]?

    public init(sender: DeviceInfo, files: [FileOffer], emptyFolders: [String]? = nil) {
        self.sender = sender
        self.files = files
        self.emptyFolders = emptyFolders?.isEmpty == true ? nil : emptyFolders
    }

    /// The empty folders to create, each as safe folder names outermost first (unsafe paths dropped).
    public var emptyFolderComponents: [[String]] {
        (emptyFolders ?? []).filter(isSafeRelativePath).map { $0.split(separator: "/").map { sanitizeFileName(String($0)) } }
    }

    public var totalSize: Int64 { files.reduce(0) { $0 + $1.size } }

    /// Sent folders and loose files, in the order offered.
    public var items: [OfferItem] {
        var items: [OfferItem] = []
        for file in files {
            let folder = file.folders.first
            if let folder, let i = items.firstIndex(where: { $0.isFolder && $0.name == folder }) {
                items[i].fileCount += 1
                items[i].size += file.size
            } else {
                items.append(OfferItem(name: folder ?? file.name, isFolder: folder != nil, fileCount: 1, size: file.size))
            }
        }
        // A sent folder holding only empty folders still shows up.
        for top in emptyFolderComponents.compactMap(\.first) where !items.contains(where: { $0.isFolder && $0.name == top }) {
            items.append(OfferItem(name: top, isFolder: true, fileCount: 0, size: 0))
        }
        return items
    }

    /// "photo.jpg", "Photos (12 files)", "3 files" or "4 items".
    public var summary: String { describeOffer(items) }
}

public func describeOffer(_ items: [OfferItem]) -> String {
    if items.count == 1, let item = items.first {
        return item.isFolder ? "\(item.name) (\(item.fileCountText))" : item.name
    }
    return items.contains(where: \.isFolder) ? "\(items.count) items" : "\(items.count) files"
}

struct AskResponse: Codable {
    var sessionId: String
}

struct DiscoverResponse: Codable {
    var device: DeviceInfo
    var paired: Bool
}

struct PairStartRequest: Codable {
    var sender: DeviceInfo
}

struct PairStartResponse: Codable {
    var commitment: String
}

struct NonceBody: Codable {
    var nonce: String
}

/// Response of `/api/v2/pair/wait`: how the receiver's user answered the code.
struct PairWaitResponse: Codable {
    var accepted: Bool
}

struct ErrorBody: Codable {
    var error: String
}

struct EmptyBody: Codable {}

public enum BubbleDropError: LocalizedError {
    case connectionClosed
    case timeout
    case badMessage(String)
    case http(Int, String)
    case declined
    case busy
    case receivingOff
    case unsupportedItem(String)
    case pairingRequired
    case pairingFailed(String)
    /// The user clicked Cancel on the pairing code.
    case pairingCancelled
    /// This Mac trusts the device, but the device no longer trusts this Mac (it unpaired).
    case notTrustedBy(String)

    public var errorDescription: String? {
        switch self {
        case .connectionClosed: return "The connection closed unexpectedly."
        case .timeout: return "The other device didn't respond in time."
        case .badMessage(let m): return "Bad message: \(m)"
        case .http(let code, let m): return "Request failed (\(code)): \(m)"
        case .declined: return "The other device declined."
        case .busy: return "The other device is busy with another request."
        case .receivingOff: return "The other device has receiving turned off."
        case .unsupportedItem(let m): return m
        case .pairingRequired: return "This device needs to be paired first."
        case .pairingFailed(let m): return m
        case .pairingCancelled: return "Pairing cancelled."
        case .notTrustedBy(let name): return "\(name) doesn't trust this Mac anymore. Pair again to share with it."
        }
    }
}

func hardwareModel() -> String {
    var size = 0
    sysctlbyname("hw.model", nil, &size, nil, 0)
    guard size > 0 else { return "Mac" }
    var buf = [CChar](repeating: 0, count: size)
    sysctlbyname("hw.model", &buf, &size, nil, 0)
    return String(cString: buf)
}

/// Reduce an untrusted file name to a safe single path component.
public func sanitizeFileName(_ raw: String) -> String {
    var name = raw.replacingOccurrences(of: "\\", with: "/")
    name = name.split(separator: "/").last.map(String.init) ?? ""
    name = name.replacingOccurrences(of: ":", with: "_")
    name = name.filter { !$0.isNewline && $0 != "\0" }
    name = name.trimmingCharacters(in: .whitespaces)
    while name.hasPrefix(".") { name.removeFirst() }
    if name.isEmpty { name = "file" }
    return String(name.prefix(200))
}

/// `dir/name`, or `dir/name (n).ext` if that already exists.
func uniqueDestination(in dir: URL, name: String) -> URL {
    let fm = FileManager.default
    var candidate = dir.appendingPathComponent(name)
    let base = (name as NSString).deletingPathExtension
    let ext = (name as NSString).pathExtension
    var n = 1
    while fm.fileExists(atPath: candidate.path) {
        let numbered = ext.isEmpty ? "\(base) (\(n))" : "\(base) (\(n)).\(ext)"
        candidate = dir.appendingPathComponent(numbered)
        n += 1
    }
    return candidate
}
