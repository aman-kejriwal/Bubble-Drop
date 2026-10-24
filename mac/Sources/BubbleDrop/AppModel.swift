import AppKit
import Foundation
import BubbleDropCore

struct Transfer: Identifiable {
    enum Direction { case incoming, outgoing }
    enum Status: Equatable {
        case waiting
        case active
        case done
        case failed(String)
    }

    let id: String
    let direction: Direction
    var peerName: String
    var peerPlatform = ""
    var summary: String
    var totalBytes: Int64
    var doneBytes: Int64 = 0
    var status: Status
    var files: [URL] = []
    /// Set when this transfer is a web link rather than files.
    var link: URL?
    /// For links: whether the receiver opened it right away (otherwise it showed a notification).
    var linkOpened = false
    /// When bytes started moving, and how many had moved by then; for speed and time left.
    var startedAt: Date?
    var startBytes: Int64 = 0

    var fraction: Double { totalBytes > 0 ? Double(doneBytes) / Double(totalBytes) : 0 }
}

@MainActor
final class AppModel: ObservableObject {
    static let shared = AppModel()

    /// Nearby devices, paired ones first (most recently used at the top), then the rest by name.
    @Published private(set) var peers: [Peer] = []
    @Published private(set) var transfers: [Transfer] = []
    @Published var receivingEnabled = true {
        didSet {
            server?.receivingEnabled = receivingEnabled
            if receivingEnabled { armPairSuggestion() } else { withdrawPairSuggestion() }
        }
    }

    enum SuggestionStatus: Equatable {
        case idle
        case pairing
        case failed(String)
    }

    /// An unpaired device found right after BubbleDrop was turned on, offered for pairing.
    @Published private(set) var pairSuggestion: Peer?
    @Published private(set) var pairSuggestionStatus = SuggestionStatus.idle

    @Published private(set) var trustedDevices: [TrustedDevice] = []
    @Published private(set) var startupError: String?
    /// The request currently asking for Accept / Decline in the drop panel.
    @Published private(set) var offer: IncomingOffer?
    /// The transfer the drop panel is following.
    @Published private(set) var spotlightId: String?
    var spotlight: Transfer? { transfers.first { $0.id == spotlightId } }
    /// Files shared from another app, waiting for the user to pick a device in the drop panel.
    @Published private(set) var shareFiles: [URL] = []
    /// A web link shared from another app (e.g. Safari), waiting for the user to pick a device.
    @Published private(set) var shareLink: URL?
    private var offerAnswer: CheckedContinuation<Bool, Never>?

    let device = DeviceInfo.local()
    let saveDirectory = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0]
    private let stateDirectory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("BubbleDrop")
    private var server: BubbleDropServer?
    private var client: BubbleDropClient?
    private var trust: TrustStore?
    private let browser: PeerBrowser
    private var pairingAlertOpen = false
    private var discoveredPeers: [Peer] = []
    /// Suggestions are only made until then, so only the device that was just turned on asks.
    private var suggestUntil = Date.distantPast
    private static let suggestWindow: TimeInterval = 120
    /// Devices whose suggestion was dismissed since BubbleDrop was last turned on.
    private var dismissedSuggestions: Set<String> = []

    private init() {
        browser = PeerBrowser(selfId: device.id)
    }

    func start() {
        browser.onChange = { [weak self] peers in
            self?.discoveredPeers = peers
            self?.refreshPeers()
        }
        armPairSuggestion()
        browser.start()
        do {
            let identity = try TLSIdentity.loadOrCreate(directory: stateDirectory)
            let trust = TrustStore(file: stateDirectory.appendingPathComponent("trusted.json"))
            trust.onChange = {
                Task { @MainActor in
                    AppModel.shared.trustedDevices = trust.all
                    AppModel.shared.refreshPeers()
                    AppModel.shared.server?.endUntrustedMirroring()
                }
            }
            trustedDevices = trust.all
            let server = BubbleDropServer(device: device, identity: identity, trust: trust, saveDirectory: saveDirectory)
            server.askHandler = { request in
                await AppModel.shared.confirm(request)
            }
            server.linkHandler = { sender, url in
                await AppModel.shared.open(url, from: sender)
            }
            server.mirrorHandler = { sender, stream in
                await MirrorViewer.run(sender: sender, stream: stream)
            }
            server.notificationHandler = { source, event in
                await PhoneNotifications.shared.handle(event, from: source)
            }
            server.callHandler = { source, call in
                await PhoneCalls.shared.handle(call, from: source)
            }
            server.pairHandler = { request in
                await MainActor.run {
                    AppModel.shared.confirmPairing(
                        title: "Pair with \(request.sender.name)?",
                        detail: "Only click Pair if \(request.sender.name) shows this same code.", code: request.code)
                }
            }
            server.onPairingCancelled = {
                RunLoop.main.perform(inModes: [.common, .modalPanel]) {
                    MainActor.assumeIsolated { AppModel.shared.dismissPairingAlert() }
                }
            }
            server.onEvent = { event in
                Task { @MainActor in AppModel.shared.handle(event) }
            }
            try server.start()
            self.trust = trust
            self.server = server
            let client = BubbleDropClient(device: device, identity: identity, trust: trust)
            // The modal code alert runs its own run loop, so reach it in the modal mode too.
            client.onPairingDeclined = {
                RunLoop.main.perform(inModes: [.common, .modalPanel]) {
                    MainActor.assumeIsolated { AppModel.shared.dismissPairingAlert() }
                }
            }
            self.client = client
        } catch {
            startupError = "BubbleDrop couldn't start: \(error.localizedDescription)"
        }
    }

    func isPaired(_ peer: Peer) -> Bool {
        guard let fingerprint = peer.fingerprint else { return false }
        // The store itself, not `trustedDevices`: that copy updates a moment after pairing.
        return trust?.isTrusted(fingerprint) ?? trustedDevices.contains { $0.fingerprint == fingerprint }
    }

    func unpair(_ device: TrustedDevice) {
        trust?.remove(fingerprint: device.fingerprint)
    }

    /// Paired devices, most recently used first.
    var recentDevices: [TrustedDevice] { trustedDevices.sorted { $0.lastActive > $1.lastActive } }

    func isNearby(_ device: TrustedDevice) -> Bool { peers.contains { $0.fingerprint == device.fingerprint } }

    private func refreshPeers() {
        let lastUsed = Dictionary(trustedDevices.map { ($0.fingerprint, $0.lastActive) }, uniquingKeysWith: max)
        peers = discoveredPeers.sorted { a, b in
            let ua = a.fingerprint.flatMap { lastUsed[$0] }, ub = b.fingerprint.flatMap { lastUsed[$0] }
            if ua != ub { return (ua ?? .distantPast) > (ub ?? .distantPast) }
            return a.name.localizedCaseInsensitiveCompare(b.name) == .orderedAscending
        }
        evaluatePairSuggestion()
    }

    // MARK: Pair suggestions

    // Nothing works with an unpaired device (sending never pairs on its own), so turning BubbleDrop
    // on asks to pair with each unpaired device nearby, one at a time. It only asks: the code
    // exchange starts when the user clicks Pair. "Not now" skips a device until the next turn-on.

    private func armPairSuggestion() {
        suggestUntil = Date().addingTimeInterval(Self.suggestWindow)
        dismissedSuggestions = []
        evaluatePairSuggestion()
    }

    private func evaluatePairSuggestion() {
        if let current = pairSuggestion {
            // Withdraw it once the device leaves or gets paired (from either side).
            let gone = !peers.contains { $0.id == current.id }
            if (gone || isPaired(current)) && pairSuggestionStatus != .pairing { withdrawPairSuggestion() }
            return
        }
        guard Date() < suggestUntil, server != nil, receivingEnabled else { return }
        guard let candidate = peers.first(where: { peer in
            guard let fingerprint = peer.fingerprint else { return false }
            return !isPaired(peer) && !dismissedSuggestions.contains(fingerprint)
        }) else { return }
        pairSuggestionStatus = .idle
        pairSuggestion = candidate
        PhoneNotifications.shared.showPairSuggestion(name: candidate.name)
    }

    /// Pairs with [peer] (the suggested device if nil); the card shows progress and the code alert
    /// follows. [then] runs once pairing succeeds, e.g. the send that needed it.
    func pair(_ peer: Peer? = nil, then: (@MainActor () -> Void)? = nil) {
        guard let peer = peer ?? pairSuggestion, let client, pairSuggestionStatus != .pairing else { return }
        let detail = then == nil
            ? "Only click Pair if \(peer.name) shows this same code. Pairing is only needed once."
            : "You need to pair with \(peer.name) before sharing with it. Only click Pair if it shows this same code."
        PhoneNotifications.shared.removePairSuggestion()
        pairSuggestion = peer
        pairSuggestionStatus = .pairing
        Task {
            do {
                _ = try await client.connectPaired(peer.endpoint, confirmPairing: { prompt in
                    await MainActor.run {
                        AppModel.shared.confirmPairing(title: "Pair with \(prompt.peerName)?", detail: detail,
                                                       code: prompt.code)
                    }
                })
                finishPairing()
                then?()
            } catch BubbleDropError.pairingCancelled {
                if let fingerprint = peer.fingerprint { dismissedSuggestions.insert(fingerprint) }
                finishPairing()
            } catch {
                pairSuggestionStatus = .failed(error.localizedDescription)
            }
        }
    }

    /// Skips the suggested device until BubbleDrop is next turned on, then offers the next one.
    func dismissPairSuggestion() {
        if let fingerprint = pairSuggestion?.fingerprint { dismissedSuggestions.insert(fingerprint) }
        finishPairing()
    }

    private func finishPairing() {
        withdrawPairSuggestion()
        evaluatePairSuggestion()
    }

    private func withdrawPairSuggestion() {
        pairSuggestion = nil
        pairSuggestionStatus = .idle
        PhoneNotifications.shared.removePairSuggestion()
    }

    // MARK: Pairing

    /// Shows the code and returns whether the user confirmed it matches the other screen.
    private func confirmPairing(title: String, detail: String, code: String) -> Bool {
        NSApp.activate(ignoringOtherApps: true)
        let alert = NSAlert()
        alert.messageText = title
        alert.informativeText = "\(detail)\n\n\(code.prefix(3)) \(code.suffix(3))"
        alert.addButton(withTitle: "Pair")
        alert.addButton(withTitle: "Cancel")
        pairingAlertOpen = true
        defer { pairingAlertOpen = false }
        return alert.runModal() == .alertFirstButtonReturn
    }

    private func dismissPairingAlert() {
        if pairingAlertOpen { NSApp.abortModal() }
    }

    // MARK: Receiving

    /// Shows the offer in the drop panel and waits for Accept / Decline (declining after 120 s,
    /// when the sender has stopped waiting anyway).
    private func confirm(_ request: AskRequest) async -> Bool {
        answerOffer(false)
        let offer = IncomingOffer(senderName: request.sender.name, platform: request.sender.platform,
                                  fileNames: request.files.map(\.name), totalBytes: request.totalSize)
        return await withCheckedContinuation { continuation in
            offerAnswer = continuation
            self.offer = offer
            spotlightId = nil
            NSSound(named: "Pop")?.play()
            DropPanel.shared.present(focus: true)
            DispatchQueue.main.asyncAfter(deadline: .now() + 120) {
                MainActor.assumeIsolated {
                    if let current = AppModel.shared.offer, current.id == offer.id, !current.accepted {
                        AppModel.shared.answerOffer(false)
                    }
                }
            }
        }
    }

    func answerOffer(_ accept: Bool) {
        guard let answer = offerAnswer else { return }
        offerAnswer = nil
        if accept {
            offer?.accepted = true
        } else {
            offer = nil
            if spotlightId == nil { DropPanel.shared.dismiss() }
        }
        answer.resume(returning: accept)
    }

    func closeSpotlight() {
        spotlightId = nil
        if offer == nil, shareFiles.isEmpty, shareLink == nil { DropPanel.shared.dismiss() }
    }

    // MARK: Phone notifications

    /// Sends [body] to the paired phone with [fingerprint], if it's nearby.
    /// Returns the HTTP status, or nil if the phone couldn't be reached.
    @discardableResult
    func sendToPhone<Body: Encodable & Sendable>(_ body: Body, path: String, fingerprint: String) async -> Int? {
        guard let client, let peer = peers.first(where: { $0.fingerprint == fingerprint }) else { return nil }
        return try? await client.post(body, path: path, to: peer.endpoint, pinned: fingerprint)
    }

    // MARK: Links

    /// A paired device sent a web link: open it in the default browser and confirm briefly.
    private func open(_ url: URL, from sender: DeviceInfo) -> Bool {
        let opened = NSWorkspace.shared.open(url)
        let id = UUID().uuidString
        upsert(Transfer(id: id, direction: .incoming, peerName: sender.name, peerPlatform: sender.platform,
                        summary: url.absoluteString, totalBytes: 0,
                        status: opened ? .done : .failed("Couldn't open the link"), link: url, linkOpened: opened))
        if offer == nil, shareFiles.isEmpty, shareLink == nil {
            spotlightId = id
            DropPanel.shared.present(focus: false)
            hideSpotlight(id, after: 3)
        }
        return opened
    }

    func sendLink(_ url: URL, to peer: Peer, repaired: Bool = false) {
        // Nothing is shared with an unpaired device: pair first, then send.
        guard isPaired(peer) else { return pair(peer) { AppModel.shared.sendLink(url, to: peer) } }
        let id = UUID().uuidString
        spotlightId = id
        DropPanel.shared.present(focus: false)
        upsert(Transfer(id: id, direction: .outgoing, peerName: peer.name, peerPlatform: peer.platform,
                        summary: url.absoluteString, totalBytes: 0, status: .waiting, link: url))
        guard let client else { return }
        Task {
            do {
                let opened = try await client.send(link: url, to: peer.endpoint, confirmPairing: nil)
                update(id) {
                    $0.linkOpened = opened
                    $0.status = .done
                }
            } catch where !repaired && Self.needsRepair(error) {
                update(id) { $0.status = .failed("\(peer.name) needs to pair again") }
                pair(peer) { AppModel.shared.sendLink(url, to: peer, repaired: true) }
            } catch {
                update(id) { $0.status = .failed(error.localizedDescription) }
            }
        }
    }

    // MARK: Share menu

    func share(_ urls: [URL]) {
        var files: [URL] = []
        var link: URL?
        for url in urls {
            if url.isFileURL {
                files.append(url)
            } else if url.scheme == "bubbledrop", url.host == "share",
                      let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems {
                files += items.filter { $0.name == "path" }.compactMap(\.value).map { URL(fileURLWithPath: $0) }
                link = items.first { $0.name == "link" }?.value.flatMap(URL.init(string:)).flatMap {
                    isWebLink($0) ? $0 : nil
                } ?? link
            }
        }
        files = files.filter { FileManager.default.fileExists(atPath: $0.path) }
        guard !files.isEmpty || link != nil else { return }
        shareFiles = files
        shareLink = files.isEmpty ? link : nil
        spotlightId = nil
        DropPanel.shared.present(focus: true)
    }

    func sendShared(to peer: Peer) {
        let files = shareFiles
        let link = shareLink
        shareFiles = []
        shareLink = nil
        if let link { sendLink(link, to: peer) } else { send(files, to: peer) }
    }

    func cancelShare() {
        shareFiles = []
        shareLink = nil
        if offer == nil, spotlightId == nil { DropPanel.shared.dismiss() }
    }

    private func handle(_ event: IncomingEvent) {
        switch event {
        case .accepted(let id, let request):
            upsert(Transfer(id: id, direction: .incoming, peerName: request.sender.name,
                            peerPlatform: request.sender.platform,
                            summary: describe(request.files.map(\.name)), totalBytes: request.totalSize,
                            status: .active))
            offer = nil
            spotlightId = id
            DropPanel.shared.present(focus: false)
        case .progress(let id, let received):
            update(id) { $0.doneBytes = received }
        case .fileReceived(let id, let url):
            update(id) { $0.files.append(url) }
        case .finished(let id, _):
            update(id) {
                $0.doneBytes = $0.totalBytes
                $0.status = .done
            }
        case .failed(let id, let message):
            update(id) { $0.status = .failed(message) }
        }
    }

    // MARK: Sending

    /// Send…: makes sure both sides are paired (pairing first if not), then opens the file picker.
    func chooseFiles(for peer: Peer, repaired: Bool = false) {
        let again = { AppModel.shared.chooseFiles(for: peer, repaired: true) }
        guard isPaired(peer) else { return pair(peer, then: again) }
        guard let client else { return }
        Task {
            do {
                // Only this Mac's side is known locally; ask the device whether it still trusts us.
                _ = try await client.connectPaired(peer.endpoint, confirmPairing: nil)
            } catch where !repaired && Self.needsRepair(error) {
                return pair(peer, then: again)
            } catch {
                // Unreachable and the like: the send itself will report it.
            }
            openFilePicker(for: peer)
        }
    }

    private func openFilePicker(for peer: Peer) {
        NSApp.activate(ignoringOtherApps: true)
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = true
        panel.canChooseDirectories = false
        panel.prompt = "Send"
        panel.message = "Choose files to send to \(peer.name)"
        if panel.runModal() == .OK { send(panel.urls, to: peer) }
    }

    func send(_ urls: [URL], to peer: Peer, repaired: Bool = false) {
        guard !urls.isEmpty else { return }
        // Nothing is shared with an unpaired device: pair first, then send.
        guard isPaired(peer) else { return pair(peer) { AppModel.shared.send(urls, to: peer) } }
        let id = UUID().uuidString
        let total = urls.reduce(Int64(0)) { sum, url in
            sum + Int64((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
        }
        spotlightId = id
        DropPanel.shared.present(focus: false)
        upsert(Transfer(id: id, direction: .outgoing, peerName: peer.name, peerPlatform: peer.platform,
                        summary: describe(urls.map(\.lastPathComponent)), totalBytes: total,
                        status: .waiting, files: urls))
        guard let client else { return }
        Task {
            do {
                try await client.send(files: urls, to: peer.endpoint, confirmPairing: nil) { stage in
                    Task { @MainActor in
                        AppModel.shared.update(id) { transfer in
                            switch stage {
                            case .connecting, .pairing, .waitingForAccept: transfer.status = .waiting
                            case .uploading(let sent, _):
                                transfer.status = .active
                                transfer.doneBytes = sent
                            case .done:
                                transfer.status = .done
                                transfer.doneBytes = transfer.totalBytes
                            }
                        }
                    }
                }
            } catch where !repaired && Self.needsRepair(error) {
                update(id) { $0.status = .failed("\(peer.name) needs to pair again") }
                pair(peer) { AppModel.shared.send(urls, to: peer, repaired: true) }
            } catch {
                update(id) { $0.status = .failed(error.localizedDescription) }
            }
        }
    }

    /// The device stopped trusting this Mac (it unpaired on its side): pairing again fixes it.
    private static func needsRepair(_ error: Error) -> Bool {
        switch error as? BubbleDropError {
        case .notTrustedBy, .pairingRequired: return true
        default: return false
        }
    }

    // MARK: Helpers

    func reveal(_ transfer: Transfer) {
        if transfer.files.isEmpty {
            NSWorkspace.shared.open(saveDirectory)
        } else {
            NSWorkspace.shared.activateFileViewerSelecting(transfer.files)
        }
    }

    func clearFinished() {
        transfers.removeAll { $0.status != .active && $0.status != .waiting }
    }

    private func upsert(_ transfer: Transfer) {
        if let i = transfers.firstIndex(where: { $0.id == transfer.id }) {
            transfers[i] = transfer
        } else {
            transfers.insert(transfer, at: 0)
            if transfers.count > 20 { transfers.removeLast() }
        }
    }

    private func update(_ id: String, _ change: (inout Transfer) -> Void) {
        guard let i = transfers.firstIndex(where: { $0.id == id }) else { return }
        let before = transfers[i].status
        change(&transfers[i])
        let after = transfers[i].status
        if after == .active, transfers[i].startedAt == nil {
            transfers[i].startedAt = Date()
            transfers[i].startBytes = transfers[i].doneBytes
        }
        guard before != after, id == spotlightId else { return }
        switch after {
        case .done:
            NSSound(named: "Glass")?.play()
            NSHapticFeedbackManager.defaultPerformer.perform(.levelChange, performanceTime: .now)
            hideSpotlight(id, after: 4)
        case .failed:
            NSSound(named: "Basso")?.play()
            hideSpotlight(id, after: 8)
        default:
            break
        }
    }

    private func hideSpotlight(_ id: String, after seconds: Double) {
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds) {
            MainActor.assumeIsolated {
                if AppModel.shared.spotlightId == id { AppModel.shared.closeSpotlight() }
            }
        }
    }

    private func describe(_ names: [String]) -> String {
        names.count == 1 ? names[0] : "\(names.count) files"
    }
}

func formatBytes(_ bytes: Int64) -> String {
    ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
}
