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
    public let identity: TLSIdentity
    public let trust: TrustStore

    public init(device: DeviceInfo, identity: TLSIdentity, trust: TrustStore) {
        self.device = device
        self.identity = identity
        self.trust = trust
    }

    @discardableResult
    public func send(files: [URL], to endpoint: NWEndpoint,
                     confirmPairing: @escaping (PairingPrompt) async -> Bool,
                     progress: @escaping (SendStage) -> Void = { _ in }) async throws -> DeviceInfo {
        progress(.connecting)
        let items = try files.map(Self.describe)
        let total = items.reduce(0) { $0 + $1.offer.size }

        let (receiver, serverFingerprint) = try await connectPaired(endpoint, confirmPairing: confirmPairing) {
            progress(.pairing)
        }

        progress(.waitingForAccept)
        let ask = AskRequest(sender: device, files: items.map(\.offer))
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
                     confirmPairing: @escaping (PairingPrompt) async -> Bool,
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

    public func connectPaired(_ endpoint: NWEndpoint,
                              confirmPairing: @escaping (PairingPrompt) async -> Bool,
                              willPair: () -> Void = {}) async throws -> (DeviceInfo, String) {
        let (_, data, fingerprint) = try await roundTrip(endpoint, pinned: nil, path: "/api/v2/discover",
                                                         body: try JSONEncoder().encode(device), timeout: 15)
        let discovered = try JSONDecoder().decode(DiscoverResponse.self, from: data)
        if discovered.paired && trust.isTrusted(fingerprint) {
            trust.touch(fingerprint)
            return (discovered.device, fingerprint)
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
        guard await confirm(PairingPrompt(peerName: receiver.name, code: code)) else {
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

    // MARK: Helpers

    private struct Item {
        var url: URL
        var offer: FileOffer
    }

    private static func describe(_ url: URL) throws -> Item {
        let values = try url.resourceValues(forKeys: [.isDirectoryKey, .fileSizeKey, .contentTypeKey])
        if values.isDirectory == true {
            throw BubbleDropError.unsupportedItem("Folders aren't supported yet: \(url.lastPathComponent)")
        }
        let mime = values.contentType?.preferredMIMEType
            ?? UTType(filenameExtension: url.pathExtension)?.preferredMIMEType
            ?? "application/octet-stream"
        return Item(url: url, offer: FileOffer(name: url.lastPathComponent,
                                              size: Int64(values.fileSize ?? 0), mimeType: mime))
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

    private func readResponse(_ wire: Wire) async throws -> (Int, Data) {
        let head = try ResponseHead(try await wire.readHead())
        let length = Int(head.headers["content-length"] ?? "") ?? 0
        return (head.status, try await wire.readBody(length: length))
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
                           timeout: TimeInterval, allowErrorStatus: Bool = false) async throws -> (Int, Data, String) {
        let (wire, fingerprint) = try await connect(endpoint, pinned: pinned)
        defer { wire.close() }
        wire.arm(timeout)
        try await wire.send(requestHead(path: path, length: Int64(body.count),
                                        contentType: "application/json") + body)
        let (status, data) = try await readResponse(wire)
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
