import CryptoKit
import Foundation
import Network
import UniformTypeIdentifiers

public enum SendStage: Sendable, Equatable {
    case connecting
    case pairing
    case waitingForAccept
    case uploading(sentBytes: Int64, totalBytes: Int64)
    case done
}

/// What the sending user must compare against the other device's screen.
public struct PairingPrompt: Sendable {
    public let peerName: String
    public let code: String
}

/// Sends files to a peer over mutual TLS: Discover → (Pair) → Ask → Upload each file.
public final class BubbleDropClient {
    public let device: DeviceInfo
    /// The other device's user declined the code while ours was still deciding: close our code prompt.
    /// Called off the main thread.
    public var onPairingDeclined: () -> Void = {}
    public let identity: TLSIdentity
    public let trust: TrustStore

    public init(device: DeviceInfo, identity: TLSIdentity, trust: TrustStore) {
        self.device = device
        self.identity = identity
        self.trust = trust
    }

    @discardableResult
    /// Pairs first only when [confirmPairing] is given; otherwise an unpaired receiver is an error.
    public func send(files: [URL], to endpoint: NWEndpoint,
                     confirmPairing: ((PairingPrompt) async -> Bool)?,
                     progress: @escaping (SendStage) -> Void = { _ in }) async throws -> DeviceInfo {
        progress(.connecting)
        let described = try files.map(Self.describe)
        let items = described.flatMap(\.items)
        let emptyFolders = described.flatMap(\.emptyFolders)
        guard items.count + emptyFolders.count > 0 else {
            throw BubbleDropError.unsupportedItem("There's nothing to send in that folder.")
        }
        guard items.count <= 1000, emptyFolders.count <= 1000 else {
            throw BubbleDropError.unsupportedItem("That's over 1,000 files; up to 1,000 can be sent at once.")
        }
        let total = items.reduce(0) { $0 + $1.offer.size }

        let (receiver, serverFingerprint) = try await connectPaired(endpoint, confirmPairing: confirmPairing) {
            progress(.pairing)
        }

        progress(.waitingForAccept)
        let ask = AskRequest(sender: device, files: items.map(\.offer), emptyFolders: emptyFolders)
        let (status, askData, _) = try await roundTrip(endpoint, pinned: serverFingerprint, path: "/api/v2/ask",
                                                       body: try JSONEncoder().encode(ask), timeout: 150,
                                                       allowErrorStatus: true)
        switch status {
        case 200: break
        case 401: throw BubbleDropError.pairingRequired
        case 403: throw BubbleDropError.declined
        case 409: throw BubbleDropError.busy
        case 503: throw BubbleDropError.receivingOff
        default: throw BubbleDropError.http(status, Self.errorMessage(askData))
        }
        let sessionId = try JSONDecoder().decode(AskResponse.self, from: askData).sessionId

        var sent: Int64 = 0
        progress(.uploading(sentBytes: 0, totalBytes: total))
        do {
            for item in items {
                var query = URLComponents()
                query.queryItems = [URLQueryItem(name: "sessionId", value: sessionId),
                                    URLQueryItem(name: "fileId", value: item.offer.id)]
                let base = sent
                try await upload(endpoint, pinned: serverFingerprint,
                                 path: "/api/v2/upload?" + (query.percentEncodedQuery ?? ""),
                                 file: item.url, size: item.offer.size) { written in
                    progress(.uploading(sentBytes: base + written, totalBytes: total))
                }
                sent += item.offer.size
            }
        } catch {
            _ = try? await roundTrip(endpoint, pinned: serverFingerprint, path: "/api/v2/cancel?sessionId=\(sessionId)",
                                     body: Data(), timeout: 5, allowErrorStatus: true)
            throw error
        }
        progress(.done)
        return receiver
    }

    /// Discovers the peer and pairs with it unless both sides already trust each other.
    /// Returns the receiver and the certificate fingerprint every later request is pinned to.
    /// Opens [link] on the receiver; returns whether it opened right away (rather than as a notification).
    public func send(link: URL, to endpoint: NWEndpoint,
                     confirmPairing: ((PairingPrompt) async -> Bool)?,
                     progress: @escaping (SendStage) -> Void = { _ in }) async throws -> Bool {
        progress(.connecting)
        let (_, serverFingerprint) = try await connectPaired(endpoint, confirmPairing: confirmPairing) {
            progress(.pairing)
        }
        let request = LinkRequest(sender: device, url: link.absoluteString)
        let (status, data, _) = try await roundTrip(endpoint, pinned: serverFingerprint, path: "/api/v2/link",
                                                    body: try JSONEncoder().encode(request), timeout: 30,
                                                    allowErrorStatus: true)
        switch status {
        case 200: break
        case 401: throw BubbleDropError.pairingRequired
        case 503: throw BubbleDropError.receivingOff
        default: throw BubbleDropError.http(status, Self.errorMessage(data))
        }
        progress(.done)
        return (try? JSONDecoder().decode(LinkResponse.self, from: data))?.opened ?? false
    }

    /// Posts [body] to a paired device, accepting only its pinned certificate. Returns the HTTP status.
    @discardableResult
    public func post<Body: Encodable>(_ body: Body, path: String, to endpoint: NWEndpoint,
                                      pinned: String) async throws -> Int {
        let (status, _, _) = try await roundTrip(endpoint, pinned: pinned, path: path,
                                                 body: try JSONEncoder().encode(body), timeout: 15,
                                                 allowErrorStatus: true)
        if status == 200 { trust.touch(pinned) }
        return status
    }

    /// Connects to a device both sides trust. Without [confirmPairing] it never starts pairing:
    /// an unpaired device is an error, so pairing stays a separate, deliberate step.
    public func connectPaired(_ endpoint: NWEndpoint,
                              confirmPairing: ((PairingPrompt) async -> Bool)?,
                              willPair: () -> Void = {}) async throws -> (DeviceInfo, String) {
        let (_, data, fingerprint) = try await roundTrip(endpoint, pinned: nil, path: "/api/v2/discover",
                                                         body: try JSONEncoder().encode(device), timeout: 15)
        let discovered = try JSONDecoder().decode(DiscoverResponse.self, from: data)
        if discovered.paired && trust.isTrusted(fingerprint) {
            trust.touch(fingerprint)
            return (discovered.device, fingerprint)
        }
        guard let confirmPairing else {
            if trust.isTrusted(fingerprint) { throw BubbleDropError.notTrustedBy(discovered.device.name) }
            throw BubbleDropError.pairingFailed("Pair with \(discovered.device.name) first.")
        }
        guard fingerprint != identity.fingerprint else {
            throw BubbleDropError.pairingFailed("That's this Mac; it can't pair with itself.")
        }
        guard trust.acceptsPairing else {
            let name = discovered.device.name
            if trust.isTrusted(fingerprint) {
                throw BubbleDropError.pairingFailed("\(name) no longer trusts this Mac. Unpair \(name) here, then pair again.")
            }
            let current = trust.all.first?.name ?? "another device"
            throw BubbleDropError.pairingFailed("This Mac is already paired with \(current). Unpair it first to pair with \(name).")
        }
        willPair()
        try await pair(endpoint, serverFingerprint: fingerprint, receiver: discovered.device,
                       confirm: confirmPairing)
        return (discovered.device, fingerprint)
    }

    private func pair(_ endpoint: NWEndpoint, serverFingerprint: String, receiver: DeviceInfo,
                      confirm: (PairingPrompt) async -> Bool) async throws {
        func cancel() async {
            _ = try? await roundTrip(endpoint, pinned: serverFingerprint, path: "/api/v2/pair/cancel",
                                     body: Data(), timeout: 5, allowErrorStatus: true)
        }

        let start = try JSONEncoder().encode(PairStartRequest(sender: device))
        let (status, startData, _) = try await roundTrip(endpoint, pinned: serverFingerprint,
                                                         path: "/api/v2/pair/start", body: start, timeout: 15,
                                                         allowErrorStatus: true)
        switch status {
        case 200: break
        case 403: throw BubbleDropError.pairingFailed(
            "\(receiver.name) is already paired. Unpair on \(receiver.name) first to pair it with this Mac.")
        case 409: throw BubbleDropError.busy
        case 503: throw BubbleDropError.receivingOff
        default: throw BubbleDropError.http(status, Self.errorMessage(startData))
        }
        guard let commitment = Data(base64Encoded: try JSONDecoder().decode(PairStartResponse.self,
                                                                            from: startData).commitment) else {
            throw BubbleDropError.badMessage("bad commitment")
        }

        let clientNonce = PairingMath.nonce()
        let reveal = try JSONEncoder().encode(NonceBody(nonce: clientNonce.base64EncodedString()))
        let (_, revealData, _) = try await roundTrip(endpoint, pinned: serverFingerprint, path: "/api/v2/pair/reveal",
                                                     body: reveal, timeout: 15)
        guard let serverNonce = Data(base64Encoded: try JSONDecoder().decode(NonceBody.self, from: revealData).nonce),
              PairingMath.commitment(clientFingerprint: identity.fingerprint, serverFingerprint: serverFingerprint,
                                     serverNonce: serverNonce) == commitment else {
            await cancel()
            throw BubbleDropError.pairingFailed("Pairing check failed. Someone may be interfering with the connection.")
        }

        let code = PairingMath.code(clientFingerprint: identity.fingerprint, serverFingerprint: serverFingerprint,
                                    clientNonce: clientNonce, serverNonce: serverNonce)
        // Learn the other user's answer while ours is still open, so a decline there closes our prompt.
        // Devices without /pair/wait answer 404, and pairing then works as before.
        let declined = Flag()
        let onDeclined = onPairingDeclined
        let watch = Task {
            guard let (status, data, _) = try? await roundTrip(endpoint, pinned: serverFingerprint,
                                                               path: "/api/v2/pair/wait", body: Data(),
                                                               timeout: 150, allowErrorStatus: true),
                  status == 200, let answer = try? JSONDecoder().decode(PairWaitResponse.self, from: data),
                  !answer.accepted else { return }
            declined.set()
            onDeclined()
        }
        guard await confirm(PairingPrompt(peerName: receiver.name, code: code)) else {
            watch.cancel()
            if declined.isSet { throw BubbleDropError.pairingFailed("\(receiver.name) declined pairing.") }
            await cancel()
            throw BubbleDropError.pairingCancelled
        }
        let (confirmStatus, confirmData, _) = try await roundTrip(endpoint, pinned: serverFingerprint,
                                                                  path: "/api/v2/pair/confirm", body: Data(),
                                                                  timeout: 150, allowErrorStatus: true)
        guard confirmStatus == 200 else {
            throw confirmStatus == 403
                ? BubbleDropError.pairingFailed("\(receiver.name) didn't confirm the code.")
                : BubbleDropError.http(confirmStatus, Self.errorMessage(confirmData))
        }
        trust.add(fingerprint: serverFingerprint, device: receiver)
    }

    // MARK: Folder sync

    /// The paired device's sync index, or nil if it has no sync folder.
    public func syncIndex(from endpoint: NWEndpoint, pinned: String) async throws -> SyncIndex? {
        let (status, data, _) = try await roundTrip(endpoint, pinned: pinned, path: "/api/v2/sync/index",
                                                    body: Data("{}".utf8), timeout: 600, allowErrorStatus: true,
                                                    limit: 64 << 20)
        switch status {
        case 200: return try JSONDecoder().decode(SyncIndex.self, from: data)
        case 404: return nil
        case 401: throw BubbleDropError.pairingRequired
        case 503: throw BubbleDropError.receivingOff
        default: throw BubbleDropError.http(status, Self.errorMessage(data))
        }
    }

    /// Downloads [path] from the paired device's sync folder into [destination]; returns its SHA-256.
    public func syncDownload(path: String, from endpoint: NWEndpoint, pinned: String,
                             to destination: URL) async throws -> String {
        let (wire, _) = try await connect(endpoint, pinned: pinned)
        defer { wire.close() }
        let body = try JSONEncoder().encode(SyncFileRequest(path: path))
        wire.arm(60)
        try await wire.send(requestHead(path: "/api/v2/sync/file", length: Int64(body.count),
                                        contentType: "application/json") + body)
        let head = try ResponseHead(try await wire.readHead())
        guard let length = Int64(head.headers["content-length"] ?? ""), length >= 0 else {
            throw BubbleDropError.badMessage("missing length")
        }
        guard head.status == 200 else {
            let data = try await wire.readBody(length: Int(min(length, 1 << 20)))
            throw head.status == 401 ? BubbleDropError.pairingRequired
                : BubbleDropError.http(head.status, Self.errorMessage(data))
        }
        guard FileManager.default.createFile(atPath: destination.path, contents: nil) else {
            throw BubbleDropError.badMessage("can't write \(destination.lastPathComponent)")
        }
        let handle = try FileHandle(forWritingTo: destination)
        defer { try? handle.close() }
        var hasher = SHA256()
        try await wire.streamBody(length: length) { chunk in
            try handle.write(contentsOf: chunk)
            hasher.update(data: chunk)
        }
        return hexString(hasher.finalize())
    }

    // MARK: Helpers

    private struct Item {
        var url: URL
        var offer: FileOffer
    }

    /// A file, or every file in a folder plus the folders in it that hold no files (hidden files and
    /// symlinks left out). An empty folder is sent as just its path; its parents are implied.
    private static func describe(_ url: URL) throws -> (items: [Item], emptyFolders: [String]) {
        let values = try url.resourceValues(forKeys: [.isDirectoryKey])
        guard values.isDirectory == true else { return ([try describeFile(url, path: nil)], []) }
        let root = url.standardizedFileURL
        let keys: [URLResourceKey] = [.isRegularFileKey, .isDirectoryKey, .isSymbolicLinkKey]
        guard let walker = FileManager.default.enumerator(at: root, includingPropertiesForKeys: keys,
                                                          options: [.skipsHiddenFiles]) else { return ([], []) }
        var items: [Item] = []
        var folders: [String] = [root.lastPathComponent]
        for case let file as URL in walker {
            let fileValues = try file.resourceValues(forKeys: Set(keys))
            guard fileValues.isSymbolicLink != true else { continue }
            let path = root.lastPathComponent + "/" + String(file.standardizedFileURL.path.dropFirst(root.path.count + 1))
            if fileValues.isDirectory == true {
                folders.append(path)
            } else if fileValues.isRegularFile == true {
                items.append(try describeFile(file, path: path))
            }
            if items.count > 1000 || folders.count > 1001 { break }
        }
        // Only folders with nothing sent inside them; the rest are made by their contents.
        let contents = items.compactMap(\.offer.path) + folders
        let empty = folders.filter { folder in !contents.contains { $0.hasPrefix(folder + "/") } }
        return (items, empty)
    }

    private static func describeFile(_ url: URL, path: String?) throws -> Item {
        let values = try url.resourceValues(forKeys: [.fileSizeKey, .contentTypeKey])
        let mime = values.contentType?.preferredMIMEType
            ?? UTType(filenameExtension: url.pathExtension)?.preferredMIMEType
            ?? "application/octet-stream"
        return Item(url: url, offer: FileOffer(name: url.lastPathComponent, size: Int64(values.fileSize ?? 0),
                                              mimeType: mime, path: path))
    }

    private static func errorMessage(_ data: Data) -> String {
        (try? JSONDecoder().decode(ErrorBody.self, from: data))?.error
            ?? String(data: data, encoding: .utf8) ?? ""
    }

    private func requestHead(path: String, length: Int64, contentType: String) -> Data {
        var head = "POST \(path) HTTP/1.1\r\n"
        head += "Host: bubbledrop\r\n"
        head += "Content-Type: \(contentType)\r\n"
        head += "Content-Length: \(length)\r\n"
        head += "Connection: close\r\n\r\n"
        return Data(head.utf8)
    }

    private func readResponse(_ wire: Wire, limit: Int = 1 << 20) async throws -> (Int, Data) {
        let head = try ResponseHead(try await wire.readHead())
        let length = Int(head.headers["content-length"] ?? "") ?? 0
        return (head.status, try await wire.readBody(length: length, limit: limit))
    }

    private func connect(_ endpoint: NWEndpoint, pinned: String?) async throws -> (Wire, String) {
        let wire = try await Wire.connect(to: endpoint, using: identity.tlsParameters(requirePeerCertificate: true,
                                                                                    pinnedPeer: pinned))
        guard let fingerprint = peerFingerprint(of: wire.conn), pinned == nil || fingerprint == pinned else {
            wire.close()
            throw BubbleDropError.pairingFailed("The device presented an unexpected certificate.")
        }
        return (wire, fingerprint)
    }

    private func roundTrip(_ endpoint: NWEndpoint, pinned: String?, path: String, body: Data,
                           timeout: TimeInterval, allowErrorStatus: Bool = false,
                           limit: Int = 1 << 20) async throws -> (Int, Data, String) {
        let (wire, fingerprint) = try await connect(endpoint, pinned: pinned)
        defer { wire.close() }
        wire.arm(timeout)
        try await wire.send(requestHead(path: path, length: Int64(body.count),
                                        contentType: "application/json") + body)
        let (status, data) = try await readResponse(wire, limit: limit)
        if !allowErrorStatus && status != 200 {
            throw status == 401 ? BubbleDropError.pairingRequired : BubbleDropError.http(status, Self.errorMessage(data))
        }
        return (status, data, fingerprint)
    }

    private func upload(_ endpoint: NWEndpoint, pinned: String, path: String, file: URL, size: Int64,
                        progress: (Int64) -> Void) async throws {
        let (wire, _) = try await connect(endpoint, pinned: pinned)
        defer { wire.close() }
        let handle = try FileHandle(forReadingFrom: file)
        defer { try? handle.close() }

        wire.arm(30)
        try await wire.send(requestHead(path: path, length: size, contentType: "application/octet-stream"))
        var written: Int64 = 0
        while written < size {
            guard let chunk = try handle.read(upToCount: 256 * 1024), !chunk.isEmpty else {
                throw BubbleDropError.badMessage("\(file.lastPathComponent) changed while sending")
            }
            let take = chunk.prefix(Int(size - written))
            wire.arm(30)
            try await wire.send(take)
            written += Int64(take.count)
            progress(written)
        }
        wire.arm(60)
        let (status, data) = try await readResponse(wire)
        guard status == 200 else { throw BubbleDropError.http(status, Self.errorMessage(data)) }
    }
}

/// A thread-safe one-way switch.
private final class Flag: @unchecked Sendable {
    private let lock = NSLock()
    private var value = false

    var isSet: Bool {
        lock.lock()
        defer { lock.unlock() }
        return value
    }

    func set() {
        lock.lock()
        value = true
        lock.unlock()
    }
}
