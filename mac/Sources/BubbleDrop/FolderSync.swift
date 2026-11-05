import AppKit
import BubbleDropCore
import Foundation
import SwiftUI

/// Keeps one folder on this Mac identical with a folder on the paired device (PROTOCOL.md,
/// "Folder sync"). A change here is noticed by FSEvents and announced to the other device, which
/// pulls it; a change there arrives as `/sync/changed` and is pulled from here. A full round also
/// runs when the device comes into view and every few minutes, so nothing missed stays missed.
@MainActor
final class FolderSync: ObservableObject {
    static let shared = FolderSync()

    enum Phase: Equatable {
        case off
        case waiting
        case notSetUpThere
        case syncing
        case ready
    }

    @Published private(set) var folder: URL?
    @Published private(set) var phase = Phase.off
    @Published private(set) var lastSynced: Date?
    @Published private(set) var lastError: String?
    /// The folder's name on the other device.
    @Published private(set) var peerFolder: String?
    @Published private(set) var fileCount = 0

    private static let folderKey = "BubbleDropSyncFolder"
    private static let fullSyncInterval: TimeInterval = 300
    private let stateFile = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("BubbleDrop/sync-state.json")
    private var engine: FolderSyncEngine?
    private var watcher: FolderWatcher?
    private var timer: Timer?
    private var running = false
    private var again = false
    private var scanTask: Task<Void, Never>?
    private var syncTask: Task<Void, Never>?
    private var peerWasNearby = false

    private init() {}

    func start() {
        if let path = UserDefaults.standard.string(forKey: Self.folderKey) {
            open(URL(fileURLWithPath: path, isDirectory: true))
        }
        timer = Timer.scheduledTimer(withTimeInterval: Self.fullSyncInterval, repeats: true) { _ in
            MainActor.assumeIsolated {
                FolderSync.shared.localChanged(after: 0)
                FolderSync.shared.requestSync()
            }
        }
    }

    // MARK: Choosing the folder

    func chooseFolder() {
        NSApp.activate(ignoringOtherApps: true)
        let panel = NSOpenPanel()
        panel.canChooseFiles = false
        panel.canChooseDirectories = true
        panel.canCreateDirectories = true
        panel.allowsMultipleSelection = false
        panel.prompt = "Sync This Folder"
        panel.message = "Choose a folder to keep in sync with \(AppModel.shared.pairedName ?? "your phone")."
        guard panel.runModal() == .OK, let url = panel.url else { return }
        let home = FileManager.default.homeDirectoryForCurrentUser.standardizedFileURL
        guard url.standardizedFileURL != home, url.path != "/" else {
            lastError = "Choose a folder inside your home folder, not the whole disk or home folder."
            return
        }
        if url.standardizedFileURL != folder?.standardizedFileURL {
            stop()
            // A new folder starts over: nothing is known about it, so the first round only adds files.
            FolderSyncEngine.reset(stateFile: stateFile)
        }
        UserDefaults.standard.set(url.path, forKey: Self.folderKey)
        open(url)
    }

    func stopSyncing() {
        stop()
        UserDefaults.standard.removeObject(forKey: Self.folderKey)
        FolderSyncEngine.reset(stateFile: stateFile)
        folder = nil
        phase = .off
        lastSynced = nil
        lastError = nil
        peerFolder = nil
        fileCount = 0
    }

    func reveal() {
        if let folder { NSWorkspace.shared.open(folder) }
    }

    private func open(_ url: URL) {
        let engine = FolderSyncEngine(root: url, stateFile: stateFile)
        self.engine = engine
        folder = url
        lastError = nil
        phase = .waiting
        fileCount = engine.fileCount
        watcher = FolderWatcher(url: url) {
            Task { @MainActor in FolderSync.shared.localChanged() }
        }
        localChanged(after: 0)
        requestSync()
    }

    private func stop() {
        watcher?.stop()
        watcher = nil
        engine = nil
        scanTask?.cancel()
        scanTask = nil
    }

    // MARK: Triggers

    /// Something in the folder changed: once it settles, rescan and tell the other device.
    func localChanged(after delay: TimeInterval = 1.5) {
        scanTask?.cancel()
        scanTask = Task {
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            guard !Task.isCancelled, let engine else { return }
            let result: Result<FolderSyncEngine.ScanResult, Error> = await Task.detached {
                Result { try engine.scan() }
            }.value
            guard engine === self.engine else { return }
            switch result {
            case .success(let scan):
                fileCount = engine.fileCount
                if scan.changed { poke() }
                if scan.unsettled { localChanged(after: 2.5) }
            case .failure(let error):
                lastError = error.localizedDescription
            }
        }
    }

    /// The other device's folder changed: sync after a moment, so a burst of changes is one round.
    func peerChanged() {
        syncTask?.cancel()
        syncTask = Task {
            try? await Task.sleep(nanoseconds: 500_000_000)
            guard !Task.isCancelled else { return }
            requestSync()
        }
    }

    /// Nearby devices changed: sync as soon as the paired device appears.
    func peersChanged() {
        let nearby = AppModel.shared.syncTarget != nil
        defer { peerWasNearby = nearby }
        if nearby && !peerWasNearby { requestSync() }
        if !nearby && engine != nil && !running { phase = .waiting }
    }

    func syncNow() {
        localChanged(after: 0)
        requestSync()
    }

    // MARK: Syncing

    private func poke() {
        guard let (peer, fingerprint) = AppModel.shared.syncTarget, let client = AppModel.shared.syncClient else { return }
        Task.detached {
            _ = try? await client.post(EmptyRequest(), path: "/api/v2/sync/changed", to: peer.endpoint, pinned: fingerprint)
        }
    }

    private func requestSync() {
        guard let engine else { return }
        if running {
            again = true
            return
        }
        guard AppModel.shared.receivingEnabled, let (peer, fingerprint) = AppModel.shared.syncTarget,
              let client = AppModel.shared.syncClient else {
            phase = .waiting
            return
        }
        running = true
        phase = .syncing
        let deviceName = AppModel.shared.device.name
        Task {
            let outcome = await Task.detached {
                await Self.round(engine: engine, client: client, peer: peer, pinned: fingerprint, deviceName: deviceName)
            }.value
            finish(outcome, engine: engine)
        }
    }

    private enum Outcome {
        case notSetUpThere
        case done(folder: String, changes: Int, failures: [String], retry: Bool)
        case failed(String)
    }

    private func finish(_ outcome: Outcome, engine: FolderSyncEngine) {
        running = false
        guard engine === self.engine else { return }
        fileCount = engine.fileCount
        switch outcome {
        case .notSetUpThere:
            phase = .notSetUpThere
            lastError = nil
        case .done(let folder, _, let failures, let retry):
            phase = .ready
            peerFolder = folder
            lastSynced = Date()
            lastError = failures.isEmpty ? nil
                : failures.count == 1 ? failures[0] : "\(failures.count) files didn't sync. \(failures[0])"
            if retry { again = true }
        case .failed(let message):
            phase = AppModel.shared.syncTarget == nil ? .waiting : .ready
            lastError = message
        }
        if again {
            again = false
            requestSync()
        }
    }

    /// One full round (see `BubbleDropClient.syncRound`).
    private nonisolated static func round(engine: FolderSyncEngine, client: BubbleDropClient, peer: Peer,
                                          pinned: String, deviceName: String) async -> Outcome {
        do {
            guard let result = try await client.syncRound(engine: engine, endpoint: peer.endpoint, pinned: pinned,
                                                          deviceName: deviceName) else { return .notSetUpThere }
            // Retry right away only if this round made progress; otherwise wait for the next change.
            return .done(folder: result.remoteFolder, changes: result.changes, failures: result.failures,
                         retry: result.retry && result.changes > 0)
        } catch {
            return .failed(error.localizedDescription)
        }
    }

    // MARK: Server hooks (called off the main thread)

    nonisolated func index() async -> SyncIndex? {
        guard let engine = await currentEngine() else { return nil }
        return try? engine.index()
    }

    nonisolated func file(at path: String) async -> (URL, Int64)? {
        await currentEngine()?.file(at: path)
    }

    private func currentEngine() -> FolderSyncEngine? { engine }

    // MARK: Status text

    var statusText: String {
        let phone = AppModel.shared.pairedName ?? "your phone"
        switch phase {
        case .off: return ""
        case .syncing: return "Syncing with \(phone)…"
        case .waiting: return "Waiting for \(phone) on this Wi-Fi. Changes sync when it's back."
        case .notSetUpThere: return "Choose a folder in BubbleDrop on \(phone) to start syncing."
        case .ready:
            let files = fileCount == 1 ? "1 file" : "\(fileCount) files"
            guard let lastSynced else { return files }
            let there = peerFolder.map { " with “\($0)” on \(phone)" } ?? ""
            return "\(files) · in sync\(there) · \(lastSynced.formatted(.relative(presentation: .named)))"
        }
    }
}

/// The menu-bar section: choose, show and stop the synced folder.
struct FolderSyncSection: View {
    @ObservedObject private var sync = FolderSync.shared

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text("Folder sync").font(.subheadline.weight(.semibold))
                Spacer()
                if sync.folder != nil {
                    if sync.phase == .syncing {
                        ProgressView().controlSize(.mini)
                    } else {
                        Button("Sync Now") { sync.syncNow() }.buttonStyle(.link)
                    }
                }
            }
            if let folder = sync.folder {
                HStack(spacing: 6) {
                    Image(systemName: "folder.fill").foregroundStyle(.blue)
                    Text(folder.lastPathComponent).lineLimit(1).truncationMode(.middle)
                    Spacer()
                    Button("Show") { sync.reveal() }.buttonStyle(.link)
                    Button("Change…") { sync.chooseFolder() }.buttonStyle(.link)
                    Button("Stop") { sync.stopSyncing() }.buttonStyle(.link).foregroundStyle(.red)
                }
                Text(sync.statusText).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            } else {
                Text("Keep a folder identical on this Mac and \(AppModel.shared.pairedName ?? "your phone"). " +
                     "Changes on either side sync within seconds on the same Wi-Fi.")
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                Button("Choose Folder…") { sync.chooseFolder() }.controlSize(.small)
            }
            if let error = sync.lastError {
                Text(error).foregroundStyle(.red).lineLimit(3).fixedSize(horizontal: false, vertical: true)
            }
        }
        .font(.caption)
    }
}
