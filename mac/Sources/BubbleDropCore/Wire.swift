import Foundation
import Network

/// Minimal HTTP/1.1 framing over an NWConnection. One request per connection.
final class Wire {
    let conn: NWConnection
    private var buffer = Data()
    private let lock = NSLock()
    private var timeoutItem: DispatchWorkItem?
    private(set) var timedOut = false

    init(_ conn: NWConnection) {
        self.conn = conn
    }

    static func connect(to endpoint: NWEndpoint, using parameters: NWParameters,
                        timeout: TimeInterval = 10) async throws -> Wire {
        let conn = NWConnection(to: endpoint, using: parameters)
        let wire = Wire(conn)
        let once = Once()
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            conn.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    once.run { cont.resume() }
                case .failed(let error), .waiting(let error):
                    once.run { cont.resume(throwing: error) }
                    conn.cancel()
                case .cancelled:
                    once.run { cont.resume(throwing: BubbleDropError.timeout) }
                default:
                    break
                }
            }
            conn.start(queue: .global())
            DispatchQueue.global().asyncAfter(deadline: .now() + timeout) {
                once.run { cont.resume(throwing: BubbleDropError.timeout) }
                if conn.state != .ready { conn.cancel() }
            }
        }
        conn.stateUpdateHandler = nil
        return wire
    }

    /// Cancel the connection if no further call to `arm` happens within `seconds`.
    func arm(_ seconds: TimeInterval) {
        lock.lock()
        timeoutItem?.cancel()
        let item = DispatchWorkItem { [weak self] in
            self?.timedOut = true
            self?.conn.cancel()
        }
        timeoutItem = item
        lock.unlock()
        DispatchQueue.global().asyncAfter(deadline: .now() + seconds, execute: item)
    }

    func close() {
        lock.lock()
        timeoutItem?.cancel()
        lock.unlock()
        conn.cancel()
    }

    // MARK: Reading

    private func receiveChunk() async throws -> Data? {
        do {
            return try await withCheckedThrowingContinuation { cont in
                conn.receive(minimumIncompleteLength: 1, maximumLength: 256 * 1024) { data, _, isComplete, error in
                    if let data, !data.isEmpty {
                        cont.resume(returning: data)
                    } else if let error {
                        cont.resume(throwing: error)
                    } else if isComplete {
                        cont.resume(returning: nil)
                    } else {
                        cont.resume(returning: Data())
                    }
                }
            }
        } catch {
            throw timedOut ? BubbleDropError.timeout : error
        }
    }

    func readHead(limit: Int = 64 * 1024) async throws -> Data {
        let separator = Data("\r\n\r\n".utf8)
        while true {
            if let range = buffer.range(of: separator) {
                let head = buffer.subdata(in: buffer.startIndex..<range.lowerBound)
                buffer.removeSubrange(buffer.startIndex..<range.upperBound)
                return head
            }
            if buffer.count > limit { throw BubbleDropError.badMessage("header too large") }
            guard let chunk = try await receiveChunk() else {
                throw timedOut ? BubbleDropError.timeout : BubbleDropError.connectionClosed
            }
            buffer.append(chunk)
        }
    }

    func readBody(length: Int, limit: Int = 1 << 20) async throws -> Data {
        guard length <= limit else { throw BubbleDropError.badMessage("body too large") }
        while buffer.count < length {
            guard let chunk = try await receiveChunk() else { throw BubbleDropError.connectionClosed }
            buffer.append(chunk)
        }
        let body = buffer.prefix(length)
        buffer.removeFirst(length)
        return Data(body)
    }

    /// Copy exactly `length` body bytes to `handle`, re-arming an idle timeout per chunk.
    func streamBody(length: Int64, to handle: FileHandle, idleTimeout: TimeInterval = 30,
                    progress: (Int64) -> Void) async throws {
        var written: Int64 = 0
        while written < length {
            let chunk: Data
            if !buffer.isEmpty {
                chunk = buffer
                buffer = Data()
            } else {
                arm(idleTimeout)
                guard let next = try await receiveChunk() else { throw BubbleDropError.connectionClosed }
                chunk = next
            }
            let take = Int(min(Int64(chunk.count), length - written))
            try handle.write(contentsOf: chunk.prefix(take))
            if take < chunk.count { buffer = Data(chunk.dropFirst(take)) }
            written += Int64(take)
            progress(written)
        }
    }

    // MARK: Writing

    func send(_ data: Data, final: Bool = false) async throws {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            conn.send(content: data,
                      contentContext: final ? .finalMessage : .defaultMessage,
                      isComplete: final,
                      completion: .contentProcessed { error in
                          if let error { cont.resume(throwing: error) } else { cont.resume() }
                      })
        }
    }

    func sendResponse<T: Encodable>(status: Int, json: T) async throws {
        let body = try JSONEncoder().encode(json)
        var head = "HTTP/1.1 \(status) \(reasonPhrase(status))\r\n"
        head += "Content-Type: application/json\r\n"
        head += "Content-Length: \(body.count)\r\n"
        head += "Connection: close\r\n\r\n"
        try await send(Data(head.utf8) + body, final: true)
    }
}

struct RequestHead {
    var method: String
    var path: String
    var query: [String: String]
    var headers: [String: String]

    var contentLength: Int64 { Int64(headers["content-length"] ?? "") ?? 0 }

    init(_ data: Data) throws {
        guard let text = String(data: data, encoding: .utf8) else { throw BubbleDropError.badMessage("encoding") }
        var lines = text.components(separatedBy: "\r\n")
        let parts = lines.removeFirst().split(separator: " ")
        guard parts.count >= 2 else { throw BubbleDropError.badMessage("request line") }
        method = String(parts[0])
        let components = URLComponents(string: String(parts[1]))
        path = components?.path ?? String(parts[1])
        query = Dictionary(components?.queryItems?.map { ($0.name, $0.value ?? "") } ?? [],
                           uniquingKeysWith: { a, _ in a })
        headers = parseHeaders(lines)
    }
}

struct ResponseHead {
    var status: Int
    var headers: [String: String]

    init(_ data: Data) throws {
        guard let text = String(data: data, encoding: .utf8) else { throw BubbleDropError.badMessage("encoding") }
        var lines = text.components(separatedBy: "\r\n")
        let parts = lines.removeFirst().split(separator: " ")
        guard parts.count >= 2, let code = Int(parts[1]) else { throw BubbleDropError.badMessage("status line") }
        status = code
        headers = parseHeaders(lines)
    }
}

private func parseHeaders(_ lines: [String]) -> [String: String] {
    var headers: [String: String] = [:]
    for line in lines {
        guard let colon = line.firstIndex(of: ":") else { continue }
        let key = line[..<colon].trimmingCharacters(in: .whitespaces).lowercased()
        headers[key] = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
    }
    return headers
}

func reasonPhrase(_ status: Int) -> String {
    switch status {
    case 200: return "OK"
    case 400: return "Bad Request"
    case 401: return "Unauthorized"
    case 403: return "Forbidden"
    case 404: return "Not Found"
    case 409: return "Conflict"
    case 503: return "Service Unavailable"
    default: return "Error"
    }
}

/// Runs its closure at most once, from any thread.
final class Once: @unchecked Sendable {
    private let lock = NSLock()
    private var done = false

    func run(_ body: () -> Void) {
        lock.lock()
        defer { lock.unlock() }
        guard !done else { return }
        done = true
        body()
    }
}
