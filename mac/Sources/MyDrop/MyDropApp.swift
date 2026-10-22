import AppKit
import SwiftUI
import UniformTypeIdentifiers
import MyDropCore

@main
struct MyDropApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @ObservedObject private var model = AppModel.shared

    var body: some Scene {
        MenuBarExtra("MyDrop", systemImage: "paperplane.circle.fill") {
            ContentView()
                .environmentObject(model)
        }
        .menuBarExtraStyle(.window)
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.accessory)
        Task { @MainActor in AppModel.shared.start() }
    }
}

struct ContentView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("MyDrop").font(.headline)
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

            Divider()

            Text("Nearby").font(.subheadline.weight(.semibold))
            if model.peers.isEmpty {
                HStack(spacing: 8) {
                    ProgressView().controlSize(.small)
                    Text("Looking for devices… Open MyDrop on your phone and join the same Wi-Fi.")
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
                DisclosureGroup("Paired devices (\(model.trustedDevices.count))") {
                    ForEach(model.trustedDevices) { device in
                        HStack {
                            Text(device.name).lineLimit(1)
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

struct PeerRow: View {
    @EnvironmentObject private var model: AppModel
    let peer: Peer
    @State private var isTargeted = false

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
                Text(peer.platform == "android" ? "Android · \(peer.model)" : peer.model)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer()
            Button("Send…") { model.chooseFiles(for: peer) }
                .controlSize(.small)
        }
        .padding(8)
        .background(RoundedRectangle(cornerRadius: 8)
            .fill(isTargeted ? Color.accentColor.opacity(0.2) : Color.primary.opacity(0.05)))
        .onDrop(of: [UTType.fileURL], isTargeted: $isTargeted) { providers in
            loadFileURLs(providers) { urls in model.send(urls, to: peer) }
            return true
        }
        .help("Drop files here to send them to \(peer.name)")
    }
}

struct TransferRow: View {
    @EnvironmentObject private var model: AppModel
    let transfer: Transfer

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Image(systemName: transfer.direction == .incoming ? "arrow.down.circle" : "arrow.up.circle")
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
