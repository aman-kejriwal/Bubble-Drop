import AppKit
import LocalAuthentication

/// Asks the Mac's owner to confirm with Touch ID (or the login password when Touch ID isn't
/// available) before something sensitive: pairing, accepting files, or asking the phone to share its screen.
enum OwnerAuth {
    /// [reason] completes macOS's "BubbleDrop is trying to …" line, e.g. "accept 2 files from Pixel".
    /// Invalidating [context] closes the prompt, answering false.
    @MainActor
    static func confirm(_ reason: String, context: LAContext = LAContext()) async -> Bool {
        NSApp.activate(ignoringOtherApps: true)
        do {
            return try await context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason)
        } catch {
            return false
        }
    }
}
