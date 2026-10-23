import Foundation

/// Body of `POST /api/v2/mirror`.
public struct MirrorRequest: Codable, Sendable {
    public var sender: DeviceInfo
    /// Whether the phone accepts input from the viewer (its remote-control service is on).
    public var control: Bool?

    public init(sender: DeviceInfo, control: Bool? = nil) {
        self.sender = sender
        self.control = control
    }
}

/// The packets a phone sends after `POST /api/v2/mirror` is accepted (PROTOCOL.md "Mirroring"):
/// a 13-byte header (type, presentation time in µs, payload length; big-endian) then the payload.
public final class MirrorStream: @unchecked Sendable {
    public enum Packet: Sendable {
        /// H.264 parameter sets (SPS/PPS) in Annex-B form; sent again whenever the size changes.
        case config(Data)
        /// One H.264 access unit in Annex-B form.
        case frame(Data, presentationTimeUs: Int64)
        /// The phone switched remote control on or off.
        case controlAvailable(Bool)
    }

    private let wire: Wire
    /// Whether the phone accepted input when the session started.
    public let controlAvailable: Bool

    init(_ wire: Wire, controlAvailable: Bool) {
        self.wire = wire
        self.controlAvailable = controlAvailable
    }

    /// The next packet, or nil once the phone ends the stream.
    public func next() async throws -> Packet? {
        while true {
            // The phone repeats the last frame when its screen is idle, so silence means it's gone.
            wire.arm(15)
            let head = try await wire.readBody(length: 13)
            let type = head[0]
            let pts = head[1..<9].reduce(Int64(0)) { $0 << 8 | Int64($1) }
            let length = head[9..<13].reduce(Int(0)) { $0 << 8 | Int($1) }
            let payload = try await wire.readBody(length: length, limit: 16 << 20)
            switch type {
            case 1: return .config(payload)
            case 2: return .frame(payload, presentationTimeUs: pts)
            case 3: return nil
            case 4: return .controlAvailable(payload.first == 1)
            default: continue
            }
        }
    }

    /// Sends one input event (a JSON object, see PROTOCOL.md "Mirroring") to the phone.
    public func send(input event: [String: Any]) {
        guard let json = try? JSONSerialization.data(withJSONObject: event) else { return }
        var header = Data([10])
        header.append(contentsOf: [UInt8](repeating: 0, count: 8))
        let length = UInt32(json.count)
        header.append(contentsOf: [UInt8(length >> 24 & 0xff), UInt8(length >> 16 & 0xff),
                                   UInt8(length >> 8 & 0xff), UInt8(length & 0xff)])
        wire.sendNoWait(header + json)
    }

    /// Ends the stream from this side (e.g. the viewer window was closed).
    public func close() {
        wire.close()
    }
}
