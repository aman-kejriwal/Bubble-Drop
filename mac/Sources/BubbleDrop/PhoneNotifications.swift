import AppKit
import Foundation
import UserNotifications
import BubbleDropCore

/// Shows paired phones' notifications as macOS notifications, and sends replies, button presses
/// and dismissals back to the phone. What gets shown is up to the switches in the menu.
@MainActor
final class PhoneNotifications: NSObject, ObservableObject, UNUserNotificationCenterDelegate {
    static let shared = PhoneNotifications()

    /// Show the phone's ordinary notifications.
    @Published var showNotifications: Bool { didSet { defaults.set(showNotifications, forKey: Keys.notifications) } }
    /// Show the phone's call notifications (incoming and ongoing calls).
    @Published var showCalls: Bool { didSet { defaults.set(showCalls, forKey: Keys.calls) } }
    /// Apps (by package) whose notifications are hidden.
    @Published private(set) var mutedApps: Set<String> { didSet { defaults.set(Array(mutedApps), forKey: Keys.muted) } }
    /// Apps that have sent something, package → name, for the per-app switches.
    @Published private(set) var knownApps: [String: String] { didSet { defaults.set(knownApps, forKey: Keys.known) } }

    private let defaults = UserDefaults.standard
    private let center = UNUserNotificationCenter.current()
    private var categories: [String: UNNotificationCategory] = [:]
    private var iconDirectory = FileManager.default.temporaryDirectory.appendingPathComponent("BubbleDropIcons")

    private enum Keys {
        static let notifications = "phoneNotifications"
        static let calls = "phoneCalls"
        static let muted = "mutedPhoneApps"
        static let known = "knownPhoneApps"
    }

    private override init() {
        showNotifications = defaults.object(forKey: Keys.notifications) as? Bool ?? true
        showCalls = defaults.object(forKey: Keys.calls) as? Bool ?? true
        mutedApps = Set(defaults.stringArray(forKey: Keys.muted) ?? [])
        knownApps = defaults.dictionary(forKey: Keys.known) as? [String: String] ?? [:]
        super.init()
    }

    func start() {
        center.delegate = self
        center.requestAuthorization(options: [.alert, .sound, .badge]) { granted, error in
            NSLog("BubbleDrop: notification permission granted=\(granted) error=\(String(describing: error))")
        }
    }

    func isShown(_ package: String) -> Bool { !mutedApps.contains(package) }

    func setShown(_ shown: Bool, for package: String) {
        if shown { mutedApps.remove(package) } else { mutedApps.insert(package) }
    }

    // MARK: From the phone

    func handle(_ event: NotifyEvent, from source: PhoneSource) {
        switch event.event {
        case "posted":
            guard let notification = event.notification else { return }
            if knownApps[notification.package] != notification.app { knownApps[notification.package] = notification.app }
            guard notification.isCall ? showCalls : showNotifications, isShown(notification.package) else { return }
            post(notification, from: source)
        case "removed":
            guard let key = event.key else { return }
            center.removeDeliveredNotifications(withIdentifiers: [identifier(source.fingerprint, key)])
        default:
            break
        }
    }

    private func identifier(_ fingerprint: String, _ key: String) -> String { "\(fingerprint)|\(key)" }

    private func post(_ notification: PhoneNotification, from source: PhoneSource) {
        let content = UNMutableNotificationContent()
        let title = notification.title?.isEmpty == false ? notification.title! : notification.app
        content.title = title
        content.subtitle = [notification.app, notification.subText].compactMap { $0?.isEmpty == false ? $0 : nil }
            .filter { $0 != title }.joined(separator: " · ")
        content.body = notification.text ?? ""
        content.threadIdentifier = "\(source.fingerprint)|\(notification.package)"
        content.sound = .default
        content.userInfo = ["fingerprint": source.fingerprint, "key": notification.key]
        content.categoryIdentifier = category(for: notification.actions ?? [])
        if let attachment = iconAttachment(notification) { content.attachments = [attachment] }
        let request = UNNotificationRequest(identifier: identifier(source.fingerprint, notification.key),
                                            content: content, trigger: nil)
        center.add(request) { error in
            if let error { NSLog("BubbleDrop: couldn't show phone notification: \(error)") }
        }
    }

    /// A category whose buttons mirror [actions]; reply actions get a text field.
    private func category(for actions: [PhoneNotification.Action]) -> String {
        let id = "phone:" + actions.map { "\($0.index)\($0.reply == true ? "r" : "b")\($0.title)" }.joined(separator: "|")
        if categories[id] == nil {
            let buttons: [UNNotificationAction] = actions.prefix(4).map { action in
                let identifier = "action:\(action.index)"
                if action.reply == true {
                    return UNTextInputNotificationAction(identifier: identifier, title: action.title, options: [],
                                                         textInputButtonTitle: "Send", textInputPlaceholder: "Reply")
                }
                return UNNotificationAction(identifier: identifier, title: action.title, options: [])
            }
            categories[id] = UNNotificationCategory(identifier: id, actions: buttons, intentIdentifiers: [],
                                                    options: [.customDismissAction])
            center.setNotificationCategories(Set(categories.values))
        }
        return id
    }

    // MARK: Pair suggestions

    private static let pairSuggestionID = "pair-suggestion"

    /// "Pair with …?" with Pair / Not now buttons, for a device found right after turning BubbleDrop on.
    func showPairSuggestion(name: String) {
        if categories[Self.pairSuggestionID] == nil {
            categories[Self.pairSuggestionID] = UNNotificationCategory(
                identifier: Self.pairSuggestionID,
                actions: [UNNotificationAction(identifier: "pair", title: "Pair", options: []),
                          UNNotificationAction(identifier: "notNow", title: "Not Now", options: [])],
                intentIdentifiers: [], options: [])
            center.setNotificationCategories(Set(categories.values))
        }
        let content = UNMutableNotificationContent()
        content.title = "Pair with \(name)?"
        content.body = "It's nearby. You'll compare a code on both screens; pairing is only needed once."
        content.categoryIdentifier = Self.pairSuggestionID
        content.userInfo = ["pairSuggestion": true]
        center.add(UNNotificationRequest(identifier: Self.pairSuggestionID, content: content, trigger: nil))
    }

    func removePairSuggestion() {
        center.removeDeliveredNotifications(withIdentifiers: [Self.pairSuggestionID])
        center.removePendingNotificationRequests(withIdentifiers: [Self.pairSuggestionID])
    }

    /// The app's icon, shown as the notification's thumbnail.
    private func iconAttachment(_ notification: PhoneNotification) -> UNNotificationAttachment? {
        guard let base64 = notification.icon, let png = Data(base64Encoded: base64) else { return nil }
        try? FileManager.default.createDirectory(at: iconDirectory, withIntermediateDirectories: true)
        // The system moves attachment files into its own store, so write a fresh copy each time.
        let file = iconDirectory.appendingPathComponent("\(UUID().uuidString).png")
        guard (try? png.write(to: file)) != nil else { return nil }
        return try? UNNotificationAttachment(identifier: "icon", url: file, options: nil)
    }

    // MARK: To the phone

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                            withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .list, .sound])
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                            withCompletionHandler completionHandler: @escaping () -> Void) {
        let info = response.notification.request.content.userInfo
        let action = response.actionIdentifier
        let reply = (response as? UNTextInputNotificationResponse)?.userText
        completionHandler()
        if info["pairSuggestion"] != nil {
            Task { @MainActor in
                switch action {
                case "pair", UNNotificationDefaultActionIdentifier: AppModel.shared.pairSuggested()
                case "notNow": AppModel.shared.snoozePairSuggestion()
                default: break
                }
            }
            return
        }
        guard let fingerprint = info["fingerprint"] as? String, let key = info["key"] as? String else { return }
        Task { @MainActor in
            if action.hasPrefix("action:"), let index = Int(action.dropFirst("action:".count)) {
                await AppModel.shared.sendToPhone(NotificationActionRequest(key: key, index: index, reply: reply),
                                                  path: "/api/v2/notification/action", fingerprint: fingerprint)
            } else if action == UNNotificationDismissActionIdentifier {
                await AppModel.shared.sendToPhone(NotificationDismissRequest(key: key),
                                                  path: "/api/v2/notification/dismiss", fingerprint: fingerprint)
            }
        }
    }
}
