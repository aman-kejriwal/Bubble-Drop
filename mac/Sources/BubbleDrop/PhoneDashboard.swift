import AppKit
import Foundation
import WidgetKit
import BubbleDropCore

/// The paired phone's battery, accessories, connections and sound settings, as last reported, kept
/// for the desktop widget. Also runs the widget's buttons (bubbledrop://send, mirror, find, open).
@MainActor
final class PhoneDashboard {
    static let shared = PhoneDashboard()

    enum Action: String {
        case send, mirror, find, open

        init?(url: URL) {
            guard let host = url.host, let action = Action(rawValue: host) else { return nil }
            self = action
        }
    }

    private var statuses: [String: (status: PhoneStatus, at: Date)] = [:]
    private var lastWritten: WidgetSnapshot?
    private var pendingWrite = false
    private let home = URL(fileURLWithPath: NSHomeDirectory())

    private init() {}

    func update(_ status: PhoneStatus, from source: PhoneSource) {
        statuses[source.fingerprint] = (status, Date())
        refresh()
    }

    /// Re-publishes the snapshot (coalesced): call when peers, pairings or receiving change.
    func refresh() {
        guard !pendingWrite else { return }
        pendingWrite = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
            MainActor.assumeIsolated {
                PhoneDashboard.shared.pendingWrite = false
                PhoneDashboard.shared.write()
            }
        }
    }

    /// The phone the widget shows: the most recently used paired phone.
    private var phone: TrustedDevice? {
        AppModel.shared.recentDevices.first { $0.platform == "android" }
    }

    private func write() {
        let model = AppModel.shared
        let phone = phone
        let reported = phone.flatMap { statuses[$0.fingerprint] }
        let snapshot = WidgetSnapshot(
            phoneName: phone?.name,
            status: reported?.status,
            statusAt: reported?.at,
            nearby: phone.map { model.isNearby($0) } ?? false,
            macReceiving: model.isReceiving)
        guard snapshot != lastWritten else { return }
        // The phone reports every minute; redraws are budgeted, so only ask for one when something
        // shown changes. The widget works out "disconnected" from statusAt by itself.
        var shown = snapshot, wasShown = lastWritten
        shown.statusAt = nil
        wasShown?.statusAt = nil
        let visibleChange = shown != wasShown || snapshot.isConnected() != lastWritten?.isConnected()
        do {
            try snapshot.save(home: home)
            lastWritten = snapshot
            if visibleChange { WidgetCenter.shared.reloadAllTimelines() }
        } catch {
            NSLog("BubbleDrop: couldn't write the widget status: \(error)")
        }
    }

    // MARK: Widget buttons

    func perform(_ action: Action) {
        guard let phone else { return alert("No phone paired", "Pair your phone with BubbleDrop first.") }
        guard let peer = AppModel.shared.peers.first(where: { $0.fingerprint == phone.fingerprint }) else {
            return alert("\(phone.name) isn't nearby",
                         "Open BubbleDrop on the phone and make sure both are on the same Wi-Fi.")
        }
        switch action {
        case .open:
            break
        case .send:
            AppModel.shared.chooseFiles(for: peer)
        case .find:
            ask(phone, path: "/api/v2/find", failure: "Couldn't ring \(phone.name)")
        case .mirror:
            ask(phone, path: "/api/v2/mirror/request", failure: "Couldn't ask \(phone.name) to mirror")
        }
    }

    private func ask(_ phone: TrustedDevice, path: String, failure: String) {
        Task {
            let status = await AppModel.shared.sendToPhone(EmptyRequest(), path: path, fingerprint: phone.fingerprint)
            if status != 200 {
                alert(failure, status == nil ? "\(phone.name) didn't respond." : "Update BubbleDrop on the phone and try again.")
            }
        }
    }

    private func alert(_ title: String, _ message: String) {
        NSApp.activate(ignoringOtherApps: true)
        let alert = NSAlert()
        alert.messageText = title
        alert.informativeText = message
        alert.runModal()
    }
}
