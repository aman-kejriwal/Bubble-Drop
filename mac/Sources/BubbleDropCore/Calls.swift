import Foundation

/// Body of `POST /api/v2/call` (phone → Mac): a phone call ringing, connected or over.
public struct PhoneCall: Codable, Sendable, Equatable {
    /// Stays the same for the whole call.
    public var id: String
    /// `ringing`, `active` or `ended`.
    public var state: String
    /// The contact's name, or the number.
    public var caller: String
    /// Whether the phone's speaker is on (connected calls).
    public var speaker: Bool?
}

/// Body of `POST /api/v2/call/action` (Mac → phone).
public struct CallActionRequest: Codable, Sendable {
    /// `answer`, `decline`, `hangup` or `speaker`.
    public var action: String
    /// For `answer`: turn the speaker on once connected. For `speaker`: on or off.
    public var speaker: Bool?

    public init(action: String, speaker: Bool? = nil) {
        self.action = action
        self.speaker = speaker
    }
}
