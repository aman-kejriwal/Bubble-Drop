import Foundation
import Network

public struct Peer: Identifiable, Hashable, Sendable {
    public let id: String
    public let name: String
    public let model: String
    public let platform: String
    /// Certificate fingerprint the device advertises. A hint for UI only; trust is decided by TLS.
    public let fingerprint: String?
    public let endpoint: NWEndpoint

    public init(id: String, name: String, model: String, platform: String, fingerprint: String?,
                endpoint: NWEndpoint) {
        self.id = id
        self.name = name
        self.model = model
        self.platform = platform
        self.fingerprint = fingerprint
        self.endpoint = endpoint
    }
}

/// Finds other MyDrop devices advertising `_mydrop._tcp` on the local network.
public final class PeerBrowser {
    public var onChange: ([Peer]) -> Void = { _ in }
    private let selfId: String
    private var browser: NWBrowser?

    public init(selfId: String) {
        self.selfId = selfId
    }

    public func start(queue: DispatchQueue = .main) {
        let browser = NWBrowser(for: .bonjourWithTXTRecord(type: myDropServiceType, domain: nil),
                                using: NWParameters())
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            guard let self else { return }
            var byId: [String: Peer] = [:]
            for result in results {
                var txt: [String: String] = [:]
                if case .bonjour(let record) = result.metadata { txt = record.dictionary }
                var serviceName = "\(result.endpoint)"
                if case .service(let name, _, _, _) = result.endpoint { serviceName = name }
                let id = txt["id"] ?? serviceName
                guard id != self.selfId else { continue }
                byId[id] = Peer(id: id, name: txt["name"] ?? serviceName, model: txt["model"] ?? "",
                                platform: txt["platform"] ?? "", fingerprint: txt["fp"],
                                endpoint: result.endpoint)
            }
            self.onChange(byId.values.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending })
        }
        browser.start(queue: queue)
        self.browser = browser
    }

    public func stop() {
        browser?.cancel()
        browser = nil
    }
}
