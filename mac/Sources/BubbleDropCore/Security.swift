import CryptoKit
import Foundation
import Network
import Security

/// This device's long-lived TLS identity: a self-signed P-256 certificate.
/// Peers recognise each other by the SHA-256 fingerprint of this certificate.
public final class TLSIdentity {
    public let identity: SecIdentity
    public let fingerprint: String

    private init(identity: SecIdentity, fingerprint: String) {
        self.identity = identity
        self.fingerprint = fingerprint
    }

    private static let passphrase = "bubbledrop"

    /// Loads `identity.p12` from `directory`, generating it on first use.
    public static func loadOrCreate(directory: URL) throws -> TLSIdentity {
        let fm = FileManager.default
        try fm.createDirectory(at: directory, withIntermediateDirectories: true,
                               attributes: [.posixPermissions: 0o700])
        let p12 = directory.appendingPathComponent("identity.p12")
        if let data = try? Data(contentsOf: p12), let identity = importIdentity(data) {
            return identity
        }
        // Security.framework can't pair a P-256 key with its certificate when the PKCS#12 holds the
        // private scalar in fewer than 32 bytes (about 1 key in 256), so keep generating until one imports.
        for _ in 0..<16 {
            let candidate = try generate()
            if let identity = importIdentity(candidate) {
                try candidate.write(to: p12, options: .atomic)
                try fm.setAttributes([.posixPermissions: 0o600], ofItemAtPath: p12.path)
                return identity
            }
        }
        throw BubbleDropError.badMessage("could not create a TLS identity")
    }

    private static func importIdentity(_ data: Data) -> TLSIdentity? {
        let options: [String: Any] = [
            kSecImportExportPassphrase as String: passphrase,
            kSecImportToMemoryOnly as String: true,
        ]
        var items: CFArray?
        guard SecPKCS12Import(data as CFData, options as CFDictionary, &items) == errSecSuccess,
              let first = (items as? [[String: Any]])?.first,
              let value = first[kSecImportItemIdentity as String] else {
            return nil
        }
        let identity = value as! SecIdentity
        var certificate: SecCertificate?
        SecIdentityCopyCertificate(identity, &certificate)
        guard let certificate else { return nil }
        return TLSIdentity(identity: identity, fingerprint: certificateFingerprint(certificate))
    }

    private static func generate() throws -> Data {
        let work = FileManager.default.temporaryDirectory.appendingPathComponent("bubbledrop-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: work, withIntermediateDirectories: true,
                                                attributes: [.posixPermissions: 0o700])
        defer { try? FileManager.default.removeItem(at: work) }
        let key = work.appendingPathComponent("key.pem").path
        let cert = work.appendingPathComponent("cert.pem").path
        let out = work.appendingPathComponent("identity.p12").path
        try openssl(["ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", key])
        try openssl(["req", "-new", "-x509", "-key", key, "-out", cert, "-days", "3650",
                     "-subj", "/CN=BubbleDrop", "-sha256"])
        try openssl(["pkcs12", "-export", "-inkey", key, "-in", cert, "-out", out,
                     "-passout", "pass:\(passphrase)"])
        return try Data(contentsOf: URL(fileURLWithPath: out))
    }

    private static func openssl(_ arguments: [String]) throws {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/openssl")
        process.arguments = arguments
        process.standardOutput = FileHandle.nullDevice
        process.standardError = FileHandle.nullDevice
        try process.run()
        process.waitUntilExit()
        guard process.terminationStatus == 0 else {
            throw BubbleDropError.badMessage("openssl \(arguments[0]) failed")
        }
    }

    /// TLS 1.2+ parameters presenting this identity. Peer certificates are self-signed, so chain
    /// validation is replaced by fingerprint pinning: with `pinnedPeer` set, any other certificate
    /// fails the handshake; without it, any certificate is accepted and the caller decides.
    func tlsParameters(requirePeerCertificate: Bool, pinnedPeer: String? = nil) -> NWParameters {
        let tls = NWProtocolTLS.Options()
        let options = tls.securityProtocolOptions
        sec_protocol_options_set_local_identity(options, sec_identity_create(identity)!)
        sec_protocol_options_set_min_tls_protocol_version(options, .TLSv12)
        sec_protocol_options_set_peer_authentication_required(options, requirePeerCertificate)
        sec_protocol_options_set_verify_block(options, { _, trust, complete in
            guard let pinnedPeer else { return complete(true) }
            let secTrust = sec_trust_copy_ref(trust).takeRetainedValue()
            let leaf = (SecTrustCopyCertificateChain(secTrust) as? [SecCertificate])?.first
            complete(leaf.map(certificateFingerprint) == pinnedPeer)
        }, .global())
        return NWParameters(tls: tls, tcp: NWProtocolTCP.Options())
    }
}

public func certificateFingerprint(_ certificate: SecCertificate) -> String {
    let der = SecCertificateCopyData(certificate) as Data
    return SHA256.hash(data: der).map { String(format: "%02x", $0) }.joined()
}

/// The certificate fingerprint the other end presented on an established TLS connection.
func peerFingerprint(of connection: NWConnection) -> String? {
    guard let metadata = connection.metadata(definition: NWProtocolTLS.definition) as? NWProtocolTLS.Metadata else {
        return nil
    }
    var fingerprint: String?
    sec_protocol_metadata_access_peer_certificate_chain(metadata.securityProtocolMetadata) { certificate in
        if fingerprint == nil {
            fingerprint = certificateFingerprint(sec_certificate_copy_ref(certificate).takeRetainedValue())
        }
    }
    return fingerprint
}

/// Commit-then-reveal numeric comparison (see PROTOCOL.md "Pairing").
enum PairingMath {
    static func commitment(clientFingerprint: String, serverFingerprint: String, serverNonce: Data) -> Data {
        var input = Data("bubbledrop-commit-v2".utf8)
        input += Data(clientFingerprint.utf8) + Data(serverFingerprint.utf8) + serverNonce
        return Data(SHA256.hash(data: input))
    }

    static func code(clientFingerprint: String, serverFingerprint: String,
                     clientNonce: Data, serverNonce: Data) -> String {
        var input = Data("bubbledrop-code-v2".utf8)
        input += Data(clientFingerprint.utf8) + Data(serverFingerprint.utf8) + clientNonce + serverNonce
        let digest = Array(SHA256.hash(data: input))
        let value = UInt32(digest[0]) << 24 | UInt32(digest[1]) << 16 | UInt32(digest[2]) << 8 | UInt32(digest[3])
        return String(format: "%06u", value % 1_000_000)
    }

    static func nonce() -> Data {
        var bytes = [UInt8](repeating: 0, count: 32)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return Data(bytes)
    }
}

// MARK: - Trusted devices

public struct TrustedDevice: Codable, Hashable, Identifiable, Sendable {
    public var fingerprint: String
    public var deviceId: String
    public var name: String
    public var platform: String
    public var pairedAt: Date

    public var id: String { fingerprint }
}

/// Paired devices, keyed by certificate fingerprint and persisted as JSON.
public final class TrustStore: @unchecked Sendable {
    public var onChange: () -> Void = {}
    private let file: URL
    private let lock = NSLock()
    private var devices: [TrustedDevice]

    public init(file: URL) {
        self.file = file
        let data = try? Data(contentsOf: file)
        devices = data.flatMap { try? JSONDecoder.iso.decode([TrustedDevice].self, from: $0) } ?? []
    }

    public var all: [TrustedDevice] {
        lock.lock()
        defer { lock.unlock() }
        return devices
    }

    public func isTrusted(_ fingerprint: String) -> Bool {
        all.contains { $0.fingerprint == fingerprint }
    }

    public func device(for fingerprint: String) -> TrustedDevice? {
        all.first { $0.fingerprint == fingerprint }
    }

    /// Trusts `fingerprint`, replacing any earlier certificate of the same device.
    public func add(fingerprint: String, device: DeviceInfo) {
        mutate { devices in
            devices.removeAll { $0.fingerprint == fingerprint || $0.deviceId == device.id }
            devices.append(TrustedDevice(fingerprint: fingerprint, deviceId: device.id, name: device.name,
                                         platform: device.platform, pairedAt: Date()))
        }
    }

    public func remove(fingerprint: String) {
        mutate { $0.removeAll { $0.fingerprint == fingerprint } }
    }

    private func mutate(_ change: (inout [TrustedDevice]) -> Void) {
        lock.lock()
        change(&devices)
        let snapshot = devices
        lock.unlock()
        if let data = try? JSONEncoder.iso.encode(snapshot) {
            try? data.write(to: file, options: [.atomic])
            try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: file.path)
        }
        onChange()
    }
}

private extension JSONEncoder {
    static let iso: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = .prettyPrinted
        return encoder
    }()
}

private extension JSONDecoder {
    static let iso: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return decoder
    }()
}
