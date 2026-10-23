import AppKit
import UniformTypeIdentifiers

/// The "BubbleDrop" entry in the system Share menu. It has no UI of its own: it collects the shared
/// files and hands their paths to BubbleDrop.app through a `bubbledrop://share?path=…` URL, where the
/// user picks a nearby device.
@objc(ShareViewController)
final class ShareViewController: NSViewController {
    override func loadView() {
        view = NSView(frame: NSRect(x: 0, y: 0, width: 1, height: 1))
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        let items = extensionContext?.inputItems as? [NSExtensionItem] ?? []
        let providers = items.flatMap { $0.attachments ?? [] }
        Task { @MainActor in
            var query: [URLQueryItem] = []
            for provider in providers {
                if let link = await Self.webLink(from: provider) {
                    query.append(URLQueryItem(name: "link", value: link.absoluteString))
                } else if let url = await Self.fileURL(from: provider) {
                    query.append(URLQueryItem(name: "path", value: url.path))
                }
            }
            if !query.isEmpty {
                var components = URLComponents()
                components.scheme = "bubbledrop"
                components.host = "share"
                components.queryItems = query
                if let url = components.url { NSWorkspace.shared.open(url) }
            }
            extensionContext?.completeRequest(returningItems: nil)
        }
    }

    /// The web page [provider] shares (e.g. Safari's Share button), if it's an http(s) link.
    private static func webLink(from provider: NSItemProvider) async -> URL? {
        guard !provider.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier),
              provider.hasItemConformingToTypeIdentifier(UTType.url.identifier) else { return nil }
        let item = try? await provider.loadItem(forTypeIdentifier: UTType.url.identifier)
        let url = (item as? URL) ?? (item as? Data).flatMap { URL(dataRepresentation: $0, relativeTo: nil) }
        guard let url, ["http", "https"].contains(url.scheme?.lowercased() ?? "") else { return nil }
        return url
    }

    /// A file on disk for [provider]: the original file when one was shared, otherwise a copy of the
    /// shared data (e.g. an image from an app that doesn't share files) in this extension's temp dir.
    private static func fileURL(from provider: NSItemProvider) async -> URL? {
        if provider.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) {
            let item = try? await provider.loadItem(forTypeIdentifier: UTType.fileURL.identifier)
            if let url = item as? URL { return url }
            if let data = item as? Data { return URL(dataRepresentation: data, relativeTo: nil) }
        }
        guard let type = provider.registeredTypeIdentifiers.first(where: {
            UTType($0)?.conforms(to: .data) == true
        }) else { return nil }
        return await withCheckedContinuation { continuation in
            _ = provider.loadFileRepresentation(forTypeIdentifier: type) { url, _ in
                // The provided file is deleted when this handler returns, so keep a copy.
                guard let url else { return continuation.resume(returning: nil) }
                let dir = FileManager.default.temporaryDirectory
                    .appendingPathComponent("shared-\(UUID().uuidString)", isDirectory: true)
                let copy = dir.appendingPathComponent(provider.suggestedName.map {
                    $0.contains(".") ? $0 : $0 + "." + url.pathExtension
                } ?? url.lastPathComponent)
                do {
                    try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
                    try FileManager.default.copyItem(at: url, to: copy)
                    continuation.resume(returning: copy)
                } catch {
                    continuation.resume(returning: nil)
                }
            }
        }
    }
}
