import Foundation

/// A notification from a paired phone (PROTOCOL.md "Notifications").
public struct PhoneNotification: Codable, Sendable {
    public struct Action: Codable, Sendable, Hashable {
        public var index: Int
        public var title: String
        /// Whether the action takes typed text (a quick reply).
        public var reply: Bool?
    }

    /// The phone's key for this notification; a re-post with the same key replaces it.
    public var key: String
    public var package: String
    /// The app's name, e.g. "WhatsApp".
    public var app: String
    public var title: String?
    public var text: String?
    public var subText: String?
    /// Milliseconds since 1970.
    public var time: Int64?
    /// Android's category, e.g. "call" or "msg".
    public var category: String?
    public var actions: [Action]?
    /// The app's icon as a base64 PNG.
    public var icon: String?

    public var isCall: Bool { category == "call" }
}

/// Body of `POST /api/v2/notify`.
public struct NotifyEvent: Codable, Sendable {
    /// `posted` or `removed`.
    public var event: String
    public var notification: PhoneNotification?
    public var key: String?
}

/// The paired phone a notification came from.
public struct PhoneSource: Sendable, Hashable {
    public var fingerprint: String
    public var name: String
}

/// Body of `POST /api/v2/notification/action` (to the phone).
public struct NotificationActionRequest: Codable, Sendable {
    public var key: String
    public var index: Int
    public var reply: String?

    public init(key: String, index: Int, reply: String? = nil) {
        self.key = key
        self.index = index
        self.reply = reply
    }
}

/// Body of `POST /api/v2/notification/dismiss` (to the phone).
public struct NotificationDismissRequest: Codable, Sendable {
    public var key: String

    public init(key: String) {
        self.key = key
    }
}
