import Foundation
import Network

public enum IncomingEvent: Sendable {
    case accepted(sessionId: String, request: AskRequest)
    case progress(sessionId: String, receivedBytes: Int64)
    case fileReceived(sessionId: String, url: URL)
    case finished(sessionId: String, files: [URL])
    case failed(sessionId: String, message: String)
}

/// A pairing code to show the user while another device asks to pair.
public struct PairingRequest: Sendable {
    public let sender: DeviceInfo
    public let code: String
    public let fingerprint: String
}

/// Receives files: advertises over Bonjour and serves the PROTOCOL.md v2 endpoints over mutual TLS.
public final class BubbleDropServer {
    public let device: DeviceInfo
    public let identity: TLSIdentity
    public let trust: TrustStore
    public var saveDirectory: URL
    public var receivingEnabled = true {
        // Turning receiving off also ends a screen already being shown, not just new ones.
        didSet { if !receivingEnabled { locked { mirrorStream }?.close() } }
    }
    /// Decide whether to accept an offer from a paired device. Times out after 120 s.
    public var askHandler: (AskRequest) async -> Bool = { _ in false }
    /// Opens a link from a paired device; returns whether it was opened.
    public var linkHandler: (DeviceInfo, URL) async -> Bool = { _, _ in false }
    /// Shows a paired device's screen; returns when the viewer is closed or the stream ends.
    public var mirrorHandler: (DeviceInfo, MirrorStream) async -> Void = { _, _ in }
    /// A paired phone posted or removed a notification.
    public var notificationHandler: (PhoneSource, NotifyEvent) async -> Void = { _, _ in }
    /// A paired phone's call rang, connected or ended.
    public var callHandler: (PhoneSource, PhoneCall) async -> Void = { _, _ in }
    /// A paired phone reported its battery, connections and sound settings.
    public var statusHandler: (PhoneSource, PhoneStatus) async -> Void = { _, _ in }
    private var mirroring = false
    /// The screen being shown right now and who sends it, so turning receiving off or unpairing can end it.
    private var mirrorStream: MirrorStream?
    private var mirrorClient: String?
    /// Show the pairing code and ask the user to confirm it matches. Times out after 120 s.
    public var pairHandler: (PairingRequest) async -> Bool = { _ in false }
    /// The other device abandoned pairing; dismiss any code prompt.
    public var onPairingCancelled: () -> Void = {}
    public var onEvent: (IncomingEvent) -> Void = { _ in }
    public var onReady: (UInt16) -> Void = { _ in }

    private var listener: NWListener?
    private let sessions = SessionStore()
    private let gate = NSLock()
    private var askInFlight = false
    private var pairing: PairingState?

    private final class PairingState {
        let clientFingerprint: String
        let sender: DeviceInfo
        let serverNonce = PairingMath.nonce()
        let started = Date()
        var decision: Task<Bool, Never>?

        init(clientFingerprint: String, sender: DeviceInfo) {
            self.clientFingerprint = clientFingerprint
            self.sender = sender
        }
    }

    public init(device: DeviceInfo, identity: TLSIdentity, trust: TrustStore, saveDirectory: URL) {
        self.device = device
        self.identity = identity
        self.trust = trust
        self.saveDirectory = saveDirectory
    }

    public func start(port: UInt16 = 0, advertise: Bool = true) throws {
        let parameters = identity.tlsParameters(requirePeerCertificate: true)
        let listener = try NWListener(using: parameters, on: port == 0 ? .any : NWEndpoint.Port(rawValue: port)!)
        if advertise {
            var txt = device.txtRecord
            txt["fp"] = identity.fingerprint
            listener.service = NWListener.Service(name: device.name, type: bubbleDropServiceType,
                                                  domain: nil, txtRecord: NWTXTRecord(txt).data)
        }
        listener.stateUpdateHandler = { [weak self, weak listener] state in
            if case .ready = state, let port = listener?.port?.rawValue {
                self?.onReady(port)
            }
        }
        listener.newConnectionHandler = { [weak self] conn in
            conn.start(queue: .global())
            Task { await self?.handle(Wire(conn)) }
        }
        listener.start(queue: .global())
        self.listener = listener
    }

    public func stop() {
        listener?.cancel()
        listener = nil
    }

    private func handle(_ wire: Wire) async {
        defer { wire.close() }
        do {
            wire.arm(30)
            let head = try RequestHead(try await wire.readHead())
            guard let client = peerFingerprint(of: wire.conn) else {
                throw BubbleDropError.badMessage("no client certificate")
            }
            if trust.isTrusted(client) { trust.touch(client) }
            guard head.method == "POST" else {
                return try await wire.sendResponse(status: 404, json: ErrorBody(error: "not found"))
            }
            switch head.path {
            case "/api/v2/discover":
                _ = try await wire.readBody(length: Int(head.contentLength))
                try await wire.sendResponse(status: 200,
                                            json: DiscoverResponse(device: device, paired: trust.isTrusted(client)))
            case "/api/v2/pair/start":
                let body = try await wire.readBody(length: Int(head.contentLength))
                try await pairStart(try JSONDecoder().decode(PairStartRequest.self, from: body), client, wire)
            case "/api/v2/pair/reveal":
                let body = try await wire.readBody(length: Int(head.contentLength))
                try await pairReveal(try JSONDecoder().decode(NonceBody.self, from: body), client, wire)
            case "/api/v2/pair/confirm":
                _ = try await wire.readBody(length: Int(head.contentLength))
                wire.arm(180)
                try await pairConfirm(client, wire)
            case "/api/v2/pair/wait":
                _ = try await wire.readBody(length: Int(head.contentLength))
                wire.arm(180)
                try await pairWait(client, wire)
            case "/api/v2/pair/cancel":
                _ = try await wire.readBody(length: Int(head.contentLength))
                pairCancel(client)
                try await wire.sendResponse(status: 200, json: EmptyBody())
            case "/api/v2/ask":
                guard trust.isTrusted(client) else { return try await sendPairingRequired(wire) }
                let body = try await wire.readBody(length: Int(head.contentLength))
                wire.arm(180)
                try await handleAsk(try JSONDecoder().decode(AskRequest.self, from: body), client, wire)
            case "/api/v2/upload":
                guard trust.isTrusted(client) else { return try await sendPairingRequired(wire) }
                try await handleUpload(head, client, wire)
            case "/api/v2/link":
                guard trust.isTrusted(client) else { return try await sendPairingRequired(wire) }
                let body = try await wire.readBody(length: Int(head.contentLength))
                try await handleLink(try JSONDecoder().decode(LinkRequest.self, from: body), client, wire)
            case "/api/v2/notify":
                guard trust.isTrusted(client) else { return try await sendPairingRequired(wire) }
                let body = try await wire.readBody(length: Int(head.contentLength))
                let event = try JSONDecoder().decode(NotifyEvent.self, from: body)
                let source = PhoneSource(fingerprint: client, name: trust.device(for: client)?.name ?? "Phone")
                await notificationHandler(source, event)
                try await wire.sendResponse(status: 200, json: EmptyBody())
            case "/api/v2/call":
                guard trust.isTrusted(client) else { return try await sendPairingRequired(wire) }
                let body = try await wire.readBody(length: Int(head.contentLength))
                let call = try JSONDecoder().decode(PhoneCall.self, from: body)
                let source = PhoneSource(fingerprint: client, name: trust.device(for: client)?.name ?? "Phone")
                await callHandler(source, call)
                try await wire.sendResponse(status: 200, json: EmptyBody())
            case "/api/v2/status":
                guard trust.isTrusted(client) else { return try await sendPairingRequired(wire) }
                let body = try await wire.readBody(length: Int(head.contentLength))
                let status = try JSONDecoder().decode(PhoneStatus.self, from: body)
                let source = PhoneSource(fingerprint: client, name: trust.device(for: client)?.name ?? "Phone")
                await statusHandler(source, status)
                try await wire.sendResponse(status: 200, json: EmptyBody())
            case "/api/v2/mirror":
                guard trust.isTrusted(client) else { return try await sendPairingRequired(wire) }
                let body = try await wire.readBody(length: Int(head.contentLength))
                try await handleMirror(try JSONDecoder().decode(MirrorRequest.self, from: body), client, wire)
            case "/api/v2/cancel":
                if let id = head.query["sessionId"] { await sessions.remove(id, client: client) }
                try await wire.sendResponse(status: 200, json: EmptyBody())
            default:
                try await wire.sendResponse(status: 404, json: ErrorBody(error: "not found"))
            }
        } catch {
            try? await wire.sendResponse(status: 400, json: ErrorBody(error: error.localizedDescription))
        }
    }

    private func handleLink(_ request: LinkRequest, _ client: String, _ wire: Wire) async throws {
        guard receivingEnabled else {
            return try await wire.sendResponse(status: 503, json: ErrorBody(error: "receiving is off"))
        }
        guard let url = URL(string: request.url), isWebLink(url) else {
            return try await wire.sendResponse(status: 400, json: ErrorBody(error: "only http and https links can be opened"))
        }
        var sender = request.sender
        if let known = trust.device(for: client) { sender.name = known.name }
        let opened = await linkHandler(sender, url)
        try await wire.sendResponse(status: 200, json: LinkResponse(opened: opened))
    }

    private func handleMirror(_ request: MirrorRequest, _ client: String, _ wire: Wire) async throws {
        guard receivingEnabled else {
            return try await wire.sendResponse(status: 503, json: ErrorBody(error: "receiving is off"))
        }
        let began: Bool = locked {
            if mirroring { return false }
            mirroring = true
            return true
        }
        guard began else {
            return try await wire.sendResponse(status: 409, json: ErrorBody(error: "already showing a screen"))
        }
        defer {
            locked {
                mirroring = false
                mirrorStream = nil
                mirrorClient = nil
            }
        }
        var sender = request.sender
        if let known = trust.device(for: client) { sender.name = known.name }
        try await wire.sendResponse(status: 200, json: EmptyBody(), final: false)
        let stream = MirrorStream(wire, controlAvailable: request.control ?? false)
        locked {
            mirrorStream = stream
            mirrorClient = client
        }
        if !receivingEnabled || !trust.isTrusted(client) { stream.close() }
        await mirrorHandler(sender, stream)
    }

    /// Ends the screen being shown if its sender is no longer paired. Call after unpairing.
    public func endUntrustedMirroring() {
        let (stream, client) = locked { (mirrorStream, mirrorClient) }
        if let stream, let client, !trust.isTrusted(client) { stream.close() }
    }

    private func sendPairingRequired(_ wire: Wire) async throws {
        try await wire.sendResponse(status: 401, json: ErrorBody(error: "pairing required"))
    }

    // MARK: Pairing

    private func pairStart(_ request: PairStartRequest, _ client: String, _ wire: Wire) async throws {
        guard receivingEnabled else {
            return try await wire.sendResponse(status: 503, json: ErrorBody(error: "receiving is off"))
        }
        // A Mac never pairs with itself (its own certificate on the other end).
        guard client != identity.fingerprint else {
            return try await wire.sendResponse(status: 403, json: ErrorBody(error: "can't pair with itself"))
        }
        // One pairing per device: once paired, no other device can even ask.
        guard trust.allowsPairing(fingerprint: client, deviceId: request.sender.id) else {
            return try await wire.sendResponse(status: 403, json: ErrorBody(error: "already paired with another device"))
        }
        let state: PairingState? = locked {
            if let current = pairing, current.clientFingerprint != client,
               Date().timeIntervalSince(current.started) < 150 {
                return nil
            }
            pairing?.decision?.cancel()
            let fresh = PairingState(clientFingerprint: client, sender: request.sender)
            pairing = fresh
            return fresh
        }
        guard let state else {
            return try await wire.sendResponse(status: 409, json: ErrorBody(error: "busy"))
        }
        let commitment = PairingMath.commitment(clientFingerprint: client, serverFingerprint: identity.fingerprint,
                                                serverNonce: state.serverNonce)
        try await wire.sendResponse(status: 200, json: PairStartResponse(commitment: commitment.base64EncodedString()))
    }

    private func pairReveal(_ body: NonceBody, _ client: String, _ wire: Wire) async throws {
        guard let clientNonce = Data(base64Encoded: body.nonce), clientNonce.count == 32 else {
            return try await wire.sendResponse(status: 400, json: ErrorBody(error: "bad nonce"))
        }
        let state: PairingState? = locked {
            guard let current = pairing, current.clientFingerprint == client, current.decision == nil else { return nil }
            return current
        }
        guard let state else {
            return try await wire.sendResponse(status: 404, json: ErrorBody(error: "no pairing in progress"))
        }
        let code = PairingMath.code(clientFingerprint: client, serverFingerprint: identity.fingerprint,
                                    clientNonce: clientNonce, serverNonce: state.serverNonce)
        let request = PairingRequest(sender: state.sender, code: code, fingerprint: client)
        let handler = pairHandler
        locked { state.decision = Task { await handler(request) } }
        try await wire.sendResponse(status: 200, json: NonceBody(nonce: state.serverNonce.base64EncodedString()))
    }

    private func pairConfirm(_ client: String, _ wire: Wire) async throws {
        let decision: Task<Bool, Never>? = locked {
            guard let current = pairing, current.clientFingerprint == client else { return nil }
            return current.decision
        }
        guard let decision else {
            return try await wire.sendResponse(status: 404, json: ErrorBody(error: "no pairing in progress"))
        }
        let confirmed = await firstResult(timeout: 120) { await decision.value }
        let state: PairingState? = locked {
            defer { if pairing?.clientFingerprint == client { pairing = nil } }
            return pairing?.clientFingerprint == client ? pairing : nil
        }
        guard confirmed, let state else {
            return try await wire.sendResponse(status: 403, json: ErrorBody(error: "pairing declined"))
        }
        trust.add(fingerprint: client, device: state.sender)
        try await wire.sendResponse(status: 200, json: EmptyBody())
    }

    /// Held until this Mac's user answers the code, so the other device can close its own code
    /// prompt as soon as the user here declines. Unlike confirm, it doesn't vouch for that device's user.
    private func pairWait(_ client: String, _ wire: Wire) async throws {
        let decision: Task<Bool, Never>? = locked {
            guard let current = pairing, current.clientFingerprint == client else { return nil }
            return current.decision
        }
        guard let decision else {
            return try await wire.sendResponse(status: 404, json: ErrorBody(error: "no pairing in progress"))
        }
        let accepted = await firstResult(timeout: 120) { await decision.value }
        if !accepted {
            locked { if pairing?.clientFingerprint == client { pairing = nil } }
        }
        try await wire.sendResponse(status: 200, json: PairWaitResponse(accepted: accepted))
    }

    private func pairCancel(_ client: String) {
        let cancelled: Bool = locked {
            guard let current = pairing, current.clientFingerprint == client else { return false }
            current.decision?.cancel()
            pairing = nil
            return current.decision != nil
        }
        if cancelled { onPairingCancelled() }
    }

    // MARK: Transfers

    private func handleAsk(_ request: AskRequest, _ client: String, _ wire: Wire) async throws {
        guard receivingEnabled else {
            return try await wire.sendResponse(status: 503, json: ErrorBody(error: "receiving is off"))
        }
        guard (1...1000).contains(request.files.count), request.files.allSatisfy({ $0.size >= 0 }) else {
            return try await wire.sendResponse(status: 400, json: ErrorBody(error: "invalid file list"))
        }
        let began: Bool = locked {
            if askInFlight { return false }
            askInFlight = true
            return true
        }
        guard began else {
            return try await wire.sendResponse(status: 409, json: ErrorBody(error: "busy"))
        }
        defer { locked { askInFlight = false } }

        // Name the sender as it was paired, not as it currently claims to be.
        var request = request
        if let known = trust.device(for: client) { request.sender.name = known.name }
        let handler = askHandler
        let offer = request
        let accepted = await firstResult(timeout: 120) { await handler(offer) }
        guard accepted else {
            return try await wire.sendResponse(status: 403, json: ErrorBody(error: "declined"))
        }
        let id = await sessions.create(client: client, files: request.files)
        onEvent(.accepted(sessionId: id, request: request))
        try await wire.sendResponse(status: 200, json: AskResponse(sessionId: id))
    }

    private func handleUpload(_ head: RequestHead, _ client: String, _ wire: Wire) async throws {
        guard let sessionId = head.query["sessionId"], let fileId = head.query["fileId"],
              let offer = await sessions.take(sessionId: sessionId, fileId: fileId, client: client) else {
            return try await wire.sendResponse(status: 404, json: ErrorBody(error: "unknown session or file"))
        }
        guard head.contentLength == offer.size else {
            await sessions.restore(sessionId: sessionId, offer: offer)
            return try await wire.sendResponse(status: 400, json: ErrorBody(error: "length mismatch"))
        }

        let fm = FileManager.default
        try fm.createDirectory(at: saveDirectory, withIntermediateDirectories: true)
        let partial = saveDirectory.appendingPathComponent(".bubbledrop-\(UUID().uuidString).partial")
        fm.createFile(atPath: partial.path, contents: nil)
        do {
            let handle = try FileHandle(forWritingTo: partial)
            defer { try? handle.close() }
            let base = await sessions.receivedBytes(sessionId)
            var lastReport = Date.distantPast
            try await wire.streamBody(length: offer.size, to: handle) { written in
                if Date().timeIntervalSince(lastReport) > 0.1 || written == offer.size {
                    lastReport = Date()
                    self.onEvent(.progress(sessionId: sessionId, receivedBytes: base + written))
                }
            }
        } catch {
            try? fm.removeItem(at: partial)
            await sessions.restore(sessionId: sessionId, offer: offer)
            onEvent(.failed(sessionId: sessionId, message: error.localizedDescription))
            throw error
        }

        let destination = uniqueDestination(in: saveDirectory, name: sanitizeFileName(offer.name))
        try fm.moveItem(at: partial, to: destination)
        onEvent(.fileReceived(sessionId: sessionId, url: destination))
        if let finished = await sessions.complete(sessionId: sessionId, size: offer.size, url: destination) {
            onEvent(.finished(sessionId: sessionId, files: finished))
        }
        try await wire.sendResponse(status: 200, json: EmptyBody())
    }

    private func locked<T>(_ body: () -> T) -> T {
        gate.lock()
        defer { gate.unlock() }
        return body()
    }
}

/// `body`'s result, or `false` if it takes longer than `timeout` seconds. Unlike a task group,
/// this returns on timeout even if `body` is still running (e.g. a modal prompt).
func firstResult(timeout: TimeInterval, _ body: @escaping () async -> Bool) async -> Bool {
    await withCheckedContinuation { continuation in
        let once = Once()
        Task {
            let result = await body()
            once.run { continuation.resume(returning: result) }
        }
        Task {
            try? await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
            once.run { continuation.resume(returning: false) }
        }
    }
}

actor SessionStore {
    struct Session {
        var client: String
        var pending: [String: FileOffer]
        var inFlight = 0
        var receivedBytes: Int64 = 0
        var received: [URL] = []
        var lastActivity = Date()
    }

    private var sessions: [String: Session] = [:]
    private let expiry: TimeInterval = 600

    func create(client: String, files: [FileOffer]) -> String {
        let now = Date()
        sessions = sessions.filter { now.timeIntervalSince($0.value.lastActivity) < expiry }
        let id = UUID().uuidString
        sessions[id] = Session(client: client, pending: Dictionary(files.map { ($0.id, $0) },
                                                                    uniquingKeysWith: { a, _ in a }))
        return id
    }

    func take(sessionId: String, fileId: String, client: String) -> FileOffer? {
        guard var s = sessions[sessionId], s.client == client,
              let offer = s.pending.removeValue(forKey: fileId) else { return nil }
        s.inFlight += 1
        s.lastActivity = Date()
        sessions[sessionId] = s
        return offer
    }

    func restore(sessionId: String, offer: FileOffer) {
        guard var s = sessions[sessionId] else { return }
        s.pending[offer.id] = offer
        s.inFlight -= 1
        sessions[sessionId] = s
    }

    func receivedBytes(_ sessionId: String) -> Int64 {
        sessions[sessionId]?.receivedBytes ?? 0
    }

    /// Records a finished file; returns all received URLs when the session is complete.
    func complete(sessionId: String, size: Int64, url: URL) -> [URL]? {
        guard var s = sessions[sessionId] else { return nil }
        s.inFlight -= 1
        s.receivedBytes += size
        s.received.append(url)
        s.lastActivity = Date()
        if s.pending.isEmpty && s.inFlight == 0 {
            sessions[sessionId] = nil
            return s.received
        }
        sessions[sessionId] = s
        return nil
    }

    func remove(_ id: String, client: String) {
        if sessions[id]?.client == client { sessions[id] = nil }
    }
}
