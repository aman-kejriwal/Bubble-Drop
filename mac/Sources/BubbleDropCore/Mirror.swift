import Foundation

/// Body of `POST /api/v2/mirror`.
public struct MirrorRequest: Codable, Sendable {
    public var sender: DeviceInfo

    public init(sender: DeviceInfo) {
        self.sender = sender
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
    }

    private let wire: Wire

    init(_ wire: Wire) {
        self.wire = wire
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
            default: continue
            }
        }
    }

    /// Ends the stream from this side (e.g. the viewer window was closed).
    public func close() {
        wire.close()
    }
}
