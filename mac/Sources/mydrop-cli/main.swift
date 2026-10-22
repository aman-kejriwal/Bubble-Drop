import Foundation
import Network
import MyDropCore

// Headless MyDrop for testing and scripting. Each --state directory is a separate device
// (its own TLS identity and paired-device list).
//   mydrop-cli serve [--state DIR] [--dir PATH] [--port N] [--name NAME] [--auto-accept]
//   mydrop-cli browse
//   mydrop-cli send [--state DIR] [--yes] <device-name | host:port> FILE...
//   mydrop-cli paired [--state DIR] [--forget FINGERPRINT]

func usage() -> Never {
    print("""
    usage: mydrop-cli serve [--state DIR] [--dir PATH] [--port N] [--name NAME] [--auto-accept]
           mydrop-cli browse
           mydrop-cli send [--state DIR] [--yes] <device-name | host:port> FILE...
           mydrop-cli paired [--state DIR] [--forget FINGERPRINT]
    """)
    exit(2)
}

func option(_ name: String, in args: inout [String]) -> String? {
    guard let i = args.firstIndex(of: name), i + 1 < args.count else { return nil }
    let value = args[i + 1]
    args.removeSubrange(i...(i + 1))
    return value
}

func flag(_ name: String, in args: inout [String]) -> Bool {
    guard let i = args.firstIndex(of: name) else { return false }
    args.remove(at: i)
    return true
}

func browse(for seconds: Double) async -> [Peer] {
    let browser = PeerBrowser(selfId: "cli-\(UUID().uuidString)")
    let box = PeerBox()
    browser.onChange = { box.peers = $0 }
    browser.start(queue: .global())
    try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
    browser.stop()
    return box.peers
}

final class PeerBox: @unchecked Sendable {
    var peers: [Peer] = []
}

struct State {
    let device: DeviceInfo
    let identity: TLSIdentity
    let trust: TrustStore

    init(directory: URL, name: String?) throws {
        let defaults = UserDefaults(suiteName: "mydrop-cli-\(directory.path.hashValue)") ?? .standard
        var device = DeviceInfo.local(defaults: defaults, name: name)
        let idFile = directory.appendingPathComponent("device-id")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        if let saved = try? String(contentsOf: idFile, encoding: .utf8) {
            device.id = saved
        } else {
            try device.id.write(to: idFile, atomically: true, encoding: .utf8)
        }
        self.device = device
        identity = try TLSIdentity.loadOrCreate(directory: directory)
        trust = TrustStore(file: directory.appendingPathComponent("trusted.json"))
    }
}

func ask(_ question: String) -> Bool {
    print("\(question) [y/N] ", terminator: "")
    return readLine()?.lowercased().hasPrefix("y") == true
}

let byteFormat = ByteCountFormatter()
setvbuf(stdout, nil, _IOLBF, 0)

var args = Array(CommandLine.arguments.dropFirst())
guard let command = args.first else { usage() }
args.removeFirst()
let stateDir = option("--state", in: &args).map { URL(fileURLWithPath: $0) }
    ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("MyDrop-cli")

switch command {
case "serve":
    let dir = option("--dir", in: &args).map { URL(fileURLWithPath: $0) }
        ?? FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0]
    let port = UInt16(option("--port", in: &args) ?? "0") ?? 0
    let state = try State(directory: stateDir, name: option("--name", in: &args))
    let autoAccept = flag("--auto-accept", in: &args)

    let server = MyDropServer(device: state.device, identity: state.identity, trust: state.trust,
                              saveDirectory: dir)
    server.pairHandler = { request in
        print("Pairing request from \(request.sender.name). Code: \(request.code)")
        if autoAccept { print("  auto-confirmed"); return true }
        return ask("Does \(request.sender.name) show the same code?")
    }
    server.onPairingCancelled = { print("Pairing cancelled by the other device") }
    server.askHandler = { request in
        print("Offer from \(request.sender.name) (\(request.sender.platform)): " +
              "\(request.files.count) file(s), \(byteFormat.string(fromByteCount: request.totalSize))")
        for file in request.files { print("  - \(file.name) [\(file.mimeType)]") }
        if autoAccept { print("  auto-accepted"); return true }
        return ask("Accept?")
    }
    server.onEvent = { event in
        switch event {
        case .fileReceived(_, let url): print("Saved \(url.path)")
        case .finished(_, let files): print("Done, \(files.count) file(s)")
        case .failed(_, let message): print("Failed: \(message)")
        default: break
        }
    }
    server.onReady = { port in
        print("\"\(state.device.name)\" receiving on port \(port), saving to \(dir.path)")
        print("Certificate \(state.identity.fingerprint)")
    }
    try server.start(port: port)
    while true { try await Task.sleep(nanoseconds: 3_600_000_000_000) }

case "browse":
    let peers = await browse(for: 3)
    if peers.isEmpty { print("No devices found.") }
    for peer in peers { print("\(peer.name)\t\(peer.platform)\t\(peer.model)\t\(peer.endpoint)") }

case "paired":
    let state = try State(directory: stateDir, name: nil)
    if let forget = option("--forget", in: &args) {
        state.trust.remove(fingerprint: forget)
        print("Forgot \(forget)")
    }
    print("This device: \(state.identity.fingerprint)")
    for device in state.trust.all {
        print("\(device.name)\t\(device.platform)\t\(device.fingerprint)\t\(device.pairedAt)")
    }

case "send":
    let autoConfirm = flag("--yes", in: &args)
    guard args.count >= 2 else { usage() }
    let target = args.removeFirst()
    let files = args.map { URL(fileURLWithPath: $0) }
    let endpoint: NWEndpoint
    if let colon = target.lastIndex(of: ":"), let port = UInt16(target[target.index(after: colon)...]) {
        endpoint = .hostPort(host: NWEndpoint.Host(String(target[..<colon])),
                             port: NWEndpoint.Port(rawValue: port)!)
    } else if let peer = await browse(for: 3).first(where: {
        $0.name.caseInsensitiveCompare(target) == .orderedSame || $0.id == target
    }) {
        endpoint = peer.endpoint
    } else {
        print("No device named \"\(target)\" found.")
        exit(1)
    }
    let state = try State(directory: stateDir, name: nil)
    let client = MyDropClient(device: state.device, identity: state.identity, trust: state.trust)
    do {
        let receiver = try await client.send(files: files, to: endpoint, confirmPairing: { prompt in
            print("Pairing with \(prompt.peerName). Code: \(prompt.code)")
            if autoConfirm { print("  auto-confirmed"); return true }
            return ask("Does \(prompt.peerName) show the same code?")
        }) { stage in
            switch stage {
            case .connecting: print("Connecting…")
            case .pairing: print("Not paired yet, starting pairing…")
            case .waitingForAccept: print("Waiting for the other device to accept…")
            case .uploading(let sent, let total):
                print("\r\(byteFormat.string(fromByteCount: sent)) / \(byteFormat.string(fromByteCount: total))",
                      terminator: "")
            case .done: print("\nSent.")
            }
        }
        print("Delivered to \(receiver.name)")
    } catch {
        print("\nError: \(error.localizedDescription)")
        exit(1)
    }

default:
    usage()
}
