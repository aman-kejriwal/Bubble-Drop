import Foundation

public let myDropProtocolVersion = 2
public let myDropServiceType = "_mydrop._tcp"

public struct DeviceInfo: Codable, Hashable, Sendable {
    public var id: String
    public var name: String
    public var model: String
    public var platform: String
    public var protocolVersion: Int

    public init(id: String, name: String, model: String, platform: String = "macos",
                protocolVersion: Int = myDropProtocolVersion) {
        self.id = id
        self.name = name
        self.model = model
        self.platform = platform
        self.protocolVersion = protocolVersion
    }

    /// This Mac, with an ID persisted in the given defaults.
    public static func local(defaults: UserDefaults = .standard, name: String? = nil) -> DeviceInfo {
        let key = "MyDropDeviceID"
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
    public var name: String
    public var size: Int64
    public var mimeType: String

    public init(id: String = UUID().uuidString, name: String, size: Int64, mimeType: String) {
        self.id = id
        self.name = name
        self.size = size
        self.mimeType = mimeType
    }
}

public struct AskRequest: Codable, Sendable {
    public var sender: DeviceInfo
    public var files: [FileOffer]

    public var totalSize: Int64 { files.reduce(0) { $0 + $1.size } }
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

struct ErrorBody: Codable {
    var error: String
}

struct EmptyBody: Codable {}

public enum MyDropError: LocalizedError {
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
