import Foundation

/// Body of `POST /api/v2/trackpad`.
public struct TrackpadRequest: Codable, Sendable {
    public var sender: DeviceInfo

    public init(sender: DeviceInfo) {
        self.sender = sender
    }
}

/// Body of the `200` answer to `POST /api/v2/trackpad`: what this Mac can do with the packets.
public struct TrackpadAccepted: Codable, Sendable {
    /// This Mac types text packets and presses the typing keys.
    public var keyboard: Bool

    public init(keyboard: Bool = true) {
        self.keyboard = keyboard
    }
}

/// The packets a phone sends after `POST /api/v2/trackpad` is accepted (PROTOCOL.md "Trackpad"):
/// a 3-byte header (type, payload length; big-endian) then the payload.
public final class TrackpadStream: @unchecked Sendable {
    public enum Button: UInt8, Sendable {
        case left = 0
        case right = 1
    }

    /// Which way three fingers swiped, as the fingers moved.
    public enum Swipe: UInt8, Sendable {
        case left = 0
        case right = 1
        case up = 2
        case down = 3
    }

    /// Where a two-finger pinch is: it begins, changes any number of times, then ends.
    public enum ZoomPhase: UInt8, Sendable {
        case begin = 0
        case change = 1
        case end = 2
    }

    /// The buttons under the phone's trackpad, and the keys of its keyboard that aren't text.
    public enum Key: UInt8, Sendable {
        case volumeUp = 0
        case volumeDown = 1
        case mute = 2
        case brightnessUp = 3
        case brightnessDown = 4
        case playPause = 5
        case returnKey = 6
        case delete = 7
        case escape = 8
        case tab = 9
        case left = 10
        case right = 11
        case up = 12
        case down = 13
    }

    /// Things about the Mac the phone's trackpad screen can switch on and off.
    public enum Setting: UInt8, Sendable {
        case bluetooth = 0
    }

    public enum Event: Sendable {
        /// The finger moved by this much, in the phone's density-independent pixels (y down).
        case move(dx: Double, dy: Double)
        /// A button went down or up. `clicks` is 1 for a single click, 2 for the second of a double click.
        case button(Button, down: Bool, clicks: Int)
        /// Two fingers moved by this much, in the phone's density-independent pixels (y down).
        case scroll(dx: Double, dy: Double)
        /// Three fingers swiped. Sent once per swipe.
        case swipe(Swipe)
        /// Two fingers pinched. `magnification` is how much to zoom since the last event: 1 doubles
        /// the size, negative zooms out.
        case zoom(ZoomPhase, magnification: Double)
        /// One of the volume, brightness or play buttons, or a typing key, was pressed.
        case key(Key)
        /// Text typed on the phone's keyboard. A newline is the Return key.
        case text(String)
        /// The phone asked for a setting to be switched on or off.
        case setting(Setting, on: Bool)
    }

    /// The phone sends something at least every 2 s; this much silence means it's gone.
    static let silenceLimit: TimeInterval = 10

    private let wire: Wire
    private var keepalive: Task<Void, Never>?

    init(_ wire: Wire) {
        self.wire = wire
        // The phone can't tell a Mac that went away from one with nothing to say, so say something.
        keepalive = Task { [wire] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                if Task.isCancelled { return }
                wire.sendNoWait(Data([0, 0, 0]))
            }
        }
    }

    /// The next event. Throws once the phone closes the connection or goes silent.
    public func next() async throws -> Event {
        while true {
            wire.arm(Self.silenceLimit)
            let head = try await wire.readBody(length: 3)
            let type = head[head.startIndex]
            let length = Int(head[head.startIndex + 1]) << 8 | Int(head[head.startIndex + 2])
            let payload = try await wire.readBody(length: length, limit: 1024)
            switch type {
            case 1:
                if let (dx, dy) = Self.deltas(payload) { return .move(dx: dx, dy: dy) }
            case 2:
                guard payload.count >= 3, let button = Button(rawValue: payload[payload.startIndex]) else { continue }
                let clicks = min(max(Int(payload[payload.startIndex + 2]), 1), 3)
                return .button(button, down: payload[payload.startIndex + 1] == 1, clicks: clicks)
            case 3:
                if let (dx, dy) = Self.deltas(payload) { return .scroll(dx: dx, dy: dy) }
            case 4:
                if let first = payload.first, let swipe = Swipe(rawValue: first) { return .swipe(swipe) }
            case 5:
                guard payload.count >= 5, let phase = ZoomPhase(rawValue: payload[payload.startIndex]),
                      let amount = Self.float(payload.dropFirst()) else { continue }
                return .zoom(phase, magnification: Double(min(max(amount, -4), 4)))
            case 6:
                if let first = payload.first, let key = Key(rawValue: first) { return .key(key) }
            case 7:
                if let text = String(data: payload, encoding: .utf8), !text.isEmpty { return .text(text) }
            case 8:
                guard payload.count >= 2, let setting = Setting(rawValue: payload[payload.startIndex]) else { continue }
                return .setting(setting, on: payload[payload.startIndex + 1] == 1)
            default:
                // 0 is the keepalive; anything newer than this Mac knows is skipped.
                continue
            }
        }
    }

    /// Two big-endian float32s, limited to a sane size so a bad packet can't fling the pointer.
    private static func deltas(_ payload: Data) -> (Double, Double)? {
        guard payload.count >= 8 else { return nil }
        let bytes = [UInt8](payload.prefix(8))
        func value(_ offset: Int) -> Double? {
            let bits = bytes[offset..<offset + 4].reduce(UInt32(0)) { $0 << 8 | UInt32($1) }
            let float = Float(bitPattern: bits)
            return float.isFinite ? Double(min(max(float, -2000), 2000)) : nil
        }
        guard let dx = value(0), let dy = value(4) else { return nil }
        return (dx, dy)
    }

    /// One big-endian float32.
    private static func float(_ payload: Data) -> Float? {
        guard payload.count >= 4 else { return nil }
        let float = Float(bitPattern: payload.prefix(4).reduce(UInt32(0)) { $0 << 8 | UInt32($1) })
        return float.isFinite ? float : nil
    }

    /// Tells the phone whether a setting is on, so its switch shows the truth.
    public func report(_ setting: Setting, on: Bool) {
        wire.sendNoWait(Data([8, 0, 2, setting.rawValue, on ? 1 : 0]))
    }

    /// Ends the session from this side.
    public func close() {
        keepalive?.cancel()
        wire.close()
    }
}
