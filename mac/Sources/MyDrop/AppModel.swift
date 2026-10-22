import AppKit
import Foundation
import MyDropCore

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
    var summary: String
    var totalBytes: Int64
    var doneBytes: Int64 = 0
    var status: Status
    var files: [URL] = []

    var fraction: Double { totalBytes > 0 ? Double(doneBytes) / Double(totalBytes) : 0 }
}

@MainActor
final class AppModel: ObservableObject {
    static let shared = AppModel()

    @Published private(set) var peers: [Peer] = []
    @Published private(set) var transfers: [Transfer] = []
    @Published var receivingEnabled = true {
        didSet { server?.receivingEnabled = receivingEnabled }
    }

    @Published private(set) var trustedDevices: [TrustedDevice] = []
    @Published private(set) var startupError: String?

    let device = DeviceInfo.local()
    let saveDirectory = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0]
    private let stateDirectory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("MyDrop")
    private var server: MyDropServer?
    private var client: MyDropClient?
    private var trust: TrustStore?
    private let browser: PeerBrowser
    private var pairingAlertOpen = false

    private init() {
        browser = PeerBrowser(selfId: device.id)
    }

    func start() {
        browser.onChange = { [weak self] peers in self?.peers = peers }
        browser.start()
        do {
            let identity = try TLSIdentity.loadOrCreate(directory: stateDirectory)
            let trust = TrustStore(file: stateDirectory.appendingPathComponent("trusted.json"))
            trust.onChange = { Task { @MainActor in AppModel.shared.trustedDevices = trust.all } }
            trustedDevices = trust.all
            let server = MyDropServer(device: device, identity: identity, trust: trust, saveDirectory: saveDirectory)
            server.askHandler = { request in
                await MainActor.run { AppModel.shared.confirm(request) }
            }
            server.pairHandler = { request in
                await MainActor.run {
                    AppModel.shared.confirmPairing(
                        title: "Pair with \(request.sender.name)?",
                        detail: "Check that \(request.sender.name) shows this code, then click Pair.", code: request.code)
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
            client = MyDropClient(device: device, identity: identity, trust: trust)
        } catch {
            startupError = "MyDrop couldn't start: \(error.localizedDescription)"
        }
    }

    func isPaired(_ peer: Peer) -> Bool {
        guard let fingerprint = peer.fingerprint else { return false }
        return trustedDevices.contains { $0.fingerprint == fingerprint }
    }

    func unpair(_ device: TrustedDevice) {
        trust?.remove(fingerprint: device.fingerprint)
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

    private func confirm(_ request: AskRequest) -> Bool {
        NSApp.activate(ignoringOtherApps: true)
        let alert = NSAlert()
        let count = request.files.count
        alert.messageText = "\(request.sender.name) wants to send you \(count == 1 ? request.files[0].name : "\(count) files")"
        let names = request.files.prefix(8).map { "• \($0.name)" }.joined(separator: "\n")
        let more = count > 8 ? "\n…and \(count - 8) more" : ""
        alert.informativeText = "\(names)\(more)\n\n\(formatBytes(request.totalSize)) · saved to Downloads"
        alert.addButton(withTitle: "Accept")
        alert.addButton(withTitle: "Decline")
        return alert.runModal() == .alertFirstButtonReturn
    }

    private func handle(_ event: IncomingEvent) {
        switch event {
        case .accepted(let id, let request):
            upsert(Transfer(id: id, direction: .incoming, peerName: request.sender.name,
                            summary: describe(request.files.map(\.name)), totalBytes: request.totalSize,
                            status: .active))
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

    func chooseFiles(for peer: Peer) {
        NSApp.activate(ignoringOtherApps: true)
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = true
        panel.canChooseDirectories = false
        panel.prompt = "Send"
        panel.message = "Choose files to send to \(peer.name)"
        if panel.runModal() == .OK { send(panel.urls, to: peer) }
    }

    func send(_ urls: [URL], to peer: Peer) {
        guard !urls.isEmpty else { return }
        let id = UUID().uuidString
        let total = urls.reduce(Int64(0)) { sum, url in
            sum + Int64((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
        }
        upsert(Transfer(id: id, direction: .outgoing, peerName: peer.name,
                        summary: describe(urls.map(\.lastPathComponent)), totalBytes: total,
                        status: .waiting, files: urls))
        guard let client else { return }
        Task {
            do {
                try await client.send(files: urls, to: peer.endpoint, confirmPairing: { prompt in
                    await MainActor.run {
                        AppModel.shared.confirmPairing(
                            title: "Pair with \(prompt.peerName)?",
                            detail: "Check that \(prompt.peerName) shows the same code. Pairing is only needed once.", code: prompt.code)
                    }
                }) { stage in
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
            } catch {
                update(id) { $0.status = .failed(error.localizedDescription) }
            }
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
        change(&transfers[i])
    }

    private func describe(_ names: [String]) -> String {
        names.count == 1 ? names[0] : "\(names.count) files"
    }
}

func formatBytes(_ bytes: Int64) -> String {
    ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
}
