import AppKit
import SwiftUI
import UniformTypeIdentifiers
import BubbleDropCore

@main
struct BubbleDropApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @ObservedObject private var model = AppModel.shared

    var body: some Scene {
        MenuBarExtra("BubbleDrop", systemImage: "paperplane.circle.fill") {
            ContentView()
                .environmentObject(model)
        }
        .menuBarExtraStyle(.window)
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.accessory)
        Task { @MainActor in
            PhoneNotifications.shared.start()
            AppModel.shared.start()
        }
    }

    /// Files from the Share menu (`bubbledrop://share?path=…`) or opened with BubbleDrop.
    func application(_ application: NSApplication, open urls: [URL]) {
        Task { @MainActor in
            // bubbledrop://send, mirror, find and open come from the desktop widget's buttons.
            if let url = urls.first, url.scheme == "bubbledrop", let action = PhoneDashboard.Action(url: url) {
                PhoneDashboard.shared.perform(action)
            } else {
                AppModel.shared.share(urls)
            }
        }
    }
}

struct ContentView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("BubbleDrop").font(.headline)
                    Text(model.device.name).font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Toggle("Receiving", isOn: $model.receivingEnabled)
                    .toggleStyle(.switch)
                    .controlSize(.small)
            }

            if let error = model.startupError {
                Text(error).font(.caption).foregroundStyle(.red)
            }

            if let peer = model.pairSuggestion {
                PairSuggestionCard(peer: peer)
            }

            Divider()

            Text("Nearby").font(.subheadline.weight(.semibold))
            if model.peers.isEmpty {
                HStack(spacing: 8) {
                    ProgressView().controlSize(.small)
                    Text("Looking for devices… Open BubbleDrop on your phone and join the same Wi-Fi.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            } else {
                ForEach(model.peers) { peer in
                    PeerRow(peer: peer)
                }
            }

            if !model.transfers.isEmpty {
                Divider()
                HStack {
                    Text("Transfers").font(.subheadline.weight(.semibold))
                    Spacer()
                    Button("Clear") { model.clearFinished() }
                        .buttonStyle(.link)
                        .font(.caption)
                }
                ForEach(model.transfers.prefix(5)) { transfer in
                    TransferRow(transfer: transfer)
                }
            }

            if !model.trustedDevices.isEmpty {
                Divider()
                FromPhoneSection()
            }

            if !model.trustedDevices.isEmpty {
                Divider()
                DisclosureGroup("Paired devices (\(model.trustedDevices.count))") {
                    ForEach(model.recentDevices) { device in
                        HStack {
                            VStack(alignment: .leading, spacing: 1) {
                                Text(device.name).lineLimit(1)
                                if model.isNearby(device) {
                                    Text("Nearby now").foregroundStyle(.green)
                                } else {
                                    Text("Last used \(device.lastActive.formatted(.relative(presentation: .named)))")
                                        .foregroundStyle(.secondary)
                                }
                            }
                            Spacer()
                            Button("Unpair") { model.unpair(device) }
                                .buttonStyle(.link)
                        }
                        .font(.caption)
                    }
                }
                .font(.subheadline.weight(.semibold))
            }

            Divider()
            HStack {
                Button("Open Downloads") { NSWorkspace.shared.open(model.saveDirectory) }
                Spacer()
                Button("Quit") { NSApp.terminate(nil) }
            }
            .buttonStyle(.borderless)
            .font(.caption)
        }
        .padding(14)
        .frame(width: 320)
    }
}

/// Switches for what a paired phone may show on this Mac.
struct FromPhoneSection: View {
    @ObservedObject private var phone = PhoneNotifications.shared
    @ObservedObject private var calls = PhoneCalls.shared

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("From your phone").font(.subheadline.weight(.semibold))
            Toggle("Notifications", isOn: $phone.showNotifications)
            Toggle("Calls", isOn: $phone.showCalls)
            Toggle("Speaker on when answering here", isOn: $calls.speakerOnAnswer)
                .disabled(!phone.showCalls)
            Picker("Ringtone", selection: $calls.ringtoneName) {
                Text(PhoneCalls.silentRingtone).tag(PhoneCalls.silentRingtone)
                Divider()
                ForEach(PhoneCalls.ringtones, id: \.self) { Text(PhoneCalls.displayName($0)).tag($0) }
            }
            .pickerStyle(.menu)
            .disabled(!phone.showCalls)
            if !phone.knownApps.isEmpty {
                DisclosureGroup("Apps (\(phone.knownApps.count))") {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 4) {
                            ForEach(phone.knownApps.sorted { $0.value.localizedCaseInsensitiveCompare($1.value) == .orderedAscending },
                                    id: \.key) { package, name in
                                Toggle(name, isOn: Binding(get: { phone.isShown(package) },
                                                           set: { phone.setShown($0, for: package) }))
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .frame(maxHeight: 140)
                }
                .disabled(!phone.showNotifications && !phone.showCalls)
            }
        }
        .toggleStyle(.switch)
        .controlSize(.small)
        .font(.caption)
    }
}

/// "Pair with …?" for an unpaired device found right after BubbleDrop was turned on.
struct PairSuggestionCard: View {
    @EnvironmentObject private var model: AppModel
    let peer: Peer

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 10) {
                Image(systemName: peer.platform == "android" ? "iphone.gen3" : "laptopcomputer")
                    .font(.title2)
                    .frame(width: 28)
                VStack(alignment: .leading, spacing: 1) {
                    Text("Pair with \(peer.name)?").font(.subheadline.weight(.semibold)).lineLimit(1)
                    switch model.pairSuggestionStatus {
                    case .idle:
                        Text("Not paired, so nothing can be shared yet. You'll compare a code on both screens.")
                            .foregroundStyle(.secondary)
                    case .pairing:
                        Text("Connecting…").foregroundStyle(.secondary)
                    case .failed(let message):
                        Text(message).foregroundStyle(.red).lineLimit(2)
                    }
                }
                .font(.caption)
                Spacer(minLength: 0)
            }
            if model.pairSuggestionStatus != .pairing {
                HStack {
                    Button("Not Now") { model.dismissPairSuggestion() }
                    Spacer()
                    Button(model.pairSuggestionStatus == .idle ? "Pair" : "Try Again") { model.pair() }
                        .buttonStyle(.borderedProminent)
                }
                .controlSize(.small)
            }
        }
        .padding(10)
        .background(RoundedRectangle(cornerRadius: 10).fill(Color.accentColor.opacity(0.12)))
    }
}

struct PeerRow: View {
    @EnvironmentObject private var model: AppModel
    let peer: Peer
    @State private var isTargeted = false

    private var paired: Bool { model.isPaired(peer) }

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: peer.platform == "android" ? "iphone.gen3" : "laptopcomputer")
                .font(.title2)
                .frame(width: 28)
            VStack(alignment: .leading, spacing: 1) {
                HStack(spacing: 4) {
                    Text(peer.name).lineLimit(1)
                    if model.isPaired(peer) {
                        Image(systemName: "lock.fill")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                            .help("Paired")
                    }
                }
                Text(paired ? (peer.platform == "android" ? "Android · \(peer.model)" : peer.model)
                     : model.canPair(peer) ? "Not paired · you'll pair before sending"
                     : "Unpair \(model.pairedName ?? "your device") to pair")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer()
            // Sending to an unpaired device pairs first (AppModel.send); nothing goes out unpaired.
            Button("Send…") { model.chooseFiles(for: peer) }
                .controlSize(.small)
                .disabled((model.pairSuggestion?.id == peer.id && model.pairSuggestionStatus == .pairing)
                          || (!paired && !model.canPair(peer)))
        }
        .padding(8)
        .background(RoundedRectangle(cornerRadius: 8)
            .fill(isTargeted ? Color.accentColor.opacity(0.2) : Color.primary.opacity(0.05)))
        .onDrop(of: [UTType.fileURL], isTargeted: $isTargeted) { providers in
            guard paired || model.canPair(peer) else { return false }
            loadFileURLs(providers) { urls in model.send(urls, to: peer) }
            return true
        }
        .help(paired ? "Drop files here to send them to \(peer.name)"
              : "Drop files here: you'll pair with \(peer.name) first, then they're sent")
    }
}

struct TransferRow: View {
    @EnvironmentObject private var model: AppModel
    let transfer: Transfer

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Image(systemName: transfer.link != nil ? "link.circle"
                      : transfer.direction == .incoming ? "arrow.down.circle" : "arrow.up.circle")
                Text(transfer.summary).lineLimit(1)
                Spacer()
                if transfer.status == .done {
                    Button {
                        model.reveal(transfer)
                    } label: {
                        Image(systemName: "magnifyingglass")
                    }
                    .buttonStyle(.borderless)
                    .help("Show in Finder")
                }
            }
            .font(.caption)
            switch transfer.status {
            case .waiting:
                Text(transfer.direction == .outgoing ? "Waiting for \(transfer.peerName) to accept…" : "Waiting…")
                    .font(.caption2).foregroundStyle(.secondary)
            case .active:
                ProgressView(value: transfer.fraction)
                Text("\(formatBytes(transfer.doneBytes)) of \(formatBytes(transfer.totalBytes))")
                    .font(.caption2).foregroundStyle(.secondary)
            case .done:
                Text(transfer.direction == .outgoing ? "Sent to \(transfer.peerName)" : "From \(transfer.peerName)")
                    .font(.caption2).foregroundStyle(.secondary)
            case .failed(let message):
                Text(message).font(.caption2).foregroundStyle(.red).lineLimit(2)
            }
        }
    }
}

private func loadFileURLs(_ providers: [NSItemProvider], completion: @escaping ([URL]) -> Void) {
    let group = DispatchGroup()
    let lock = NSLock()
    var urls: [URL] = []
    for provider in providers {
        group.enter()
        _ = provider.loadObject(ofClass: URL.self) { url, _ in
            if let url, url.isFileURL {
                lock.lock()
                urls.append(url)
                lock.unlock()
            }
            group.leave()
        }
    }
    group.notify(queue: .main) { completion(urls) }
}
