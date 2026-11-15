import AppKit
import ApplicationServices
import BubbleDropCore

/// Lets a paired phone act as this Mac's trackpad (PROTOCOL.md "Trackpad"). Off until the user
/// turns it on here, and macOS only lets BubbleDrop move the pointer once it's allowed under
/// Privacy & Security → Accessibility.
@MainActor
final class PhoneTrackpad: ObservableObject {
    static let shared = PhoneTrackpad()

    @Published var enabled: Bool {
        didSet {
            UserDefaults.standard.set(enabled, forKey: Self.key)
            if enabled {
                // Shows macOS's "BubbleDrop would like to control this computer" prompt if needed.
                let prompt = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary
                accessGranted = AXIsProcessTrustedWithOptions(prompt)
            } else {
                AppModel.shared.endTrackpad()
            }
        }
    }
    /// Whether macOS lets BubbleDrop post pointer events.
    @Published private(set) var accessGranted = AXIsProcessTrusted()
    /// The phone moving the pointer right now.
    @Published private(set) var activeName: String?
    private var activeSession: UUID?

    nonisolated private static let key = "phoneTrackpad"

    private init() {
        enabled = UserDefaults.standard.bool(forKey: Self.key)
    }

    func refreshAccess() {
        accessGranted = AXIsProcessTrusted()
    }

    func openAccessibilitySettings() {
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility") {
            NSWorkspace.shared.open(url)
        }
    }

    /// Why a phone can't be the trackpad right now, in words for the phone's screen; nil if it can.
    nonisolated static func refusal() -> String? {
        guard UserDefaults.standard.bool(forKey: key) else {
            return "Turn on \"Use phone as trackpad\" in BubbleDrop on the Mac first."
        }
        guard AXIsProcessTrusted() else {
            return "On the Mac, allow BubbleDrop under Privacy & Security → Accessibility, then try again."
        }
        return nil
    }

    /// Moves the pointer for `sender` until its stream ends.
    nonisolated static func run(sender: DeviceInfo, stream: TrackpadStream) async {
        let session = UUID()
        await MainActor.run {
            shared.activeSession = session
            shared.activeName = sender.name
            shared.accessGranted = true
        }
        let pointer = PointerInjector()
        while let event = try? await stream.next() {
            switch event {
            case .move(let dx, let dy): pointer.move(dx: dx, dy: dy)
            case .button(let button, let down, let clicks): pointer.button(button, down: down, clicks: clicks)
            case .scroll(let dx, let dy): pointer.scroll(dx: dx, dy: dy)
            case .swipe(let swipe): pointer.swipe(swipe)
            }
        }
        // Never leave a button held down by a phone that's gone.
        pointer.releaseAll()
        await MainActor.run {
            // A reconnecting phone's new session may already have taken over.
            if shared.activeSession == session {
                shared.activeSession = nil
                shared.activeName = nil
            }
        }
    }
}

/// Turns trackpad events into macOS pointer events. Used by one session's task at a time.
private final class PointerInjector {
    private var position = CGPoint.zero
    private var lastMove: TimeInterval = 0
    private var speed = 0.0
    private var held: Set<TrackpadStream.Button> = []
    private var scrollRemainder = CGPoint.zero
    /// macOS's "Natural scrolling" setting; posted events aren't flipped by the system.
    private let naturalScrolling = UserDefaults.standard.object(forKey: "com.apple.swipescrolldirection") as? Bool ?? true

    /// Where the pointer is. The real mouse or trackpad may have moved it since our last event.
    private func currentPosition(now: TimeInterval) -> CGPoint {
        if now - lastMove > 0.2, let actual = CGEvent(source: nil)?.location { position = actual }
        return position
    }

    func move(dx: Double, dy: Double) {
        let now = ProcessInfo.processInfo.systemUptime
        let from = currentPosition(now: now)
        // Slow movement stays precise; fast movement covers the whole screen in one swipe.
        let elapsed = min(max(now - lastMove, 0.004), 0.05)
        speed = speed * 0.6 + (hypot(dx, dy) / elapsed) * 0.4
        let gain = 0.9 + min(speed / 500, 3)
        lastMove = now
        position = clamp(CGPoint(x: from.x + dx * gain, y: from.y + dy * gain), from: from)

        let type: CGEventType = held.contains(.left) ? .leftMouseDragged
            : held.contains(.right) ? .rightMouseDragged : .mouseMoved
        guard let event = CGEvent(mouseEventSource: nil, mouseType: type, mouseCursorPosition: position,
                                  mouseButton: type == .rightMouseDragged ? .right : .left) else { return }
        event.setIntegerValueField(.mouseEventDeltaX, value: Int64((position.x - from.x).rounded()))
        event.setIntegerValueField(.mouseEventDeltaY, value: Int64((position.y - from.y).rounded()))
        event.post(tap: .cghidEventTap)
    }

    func button(_ button: TrackpadStream.Button, down: Bool, clicks: Int) {
        // A second "down", or an "up" for a button that isn't held, would confuse apps.
        guard down != held.contains(button) else { return }
        if down { held.insert(button) } else { held.remove(button) }
        let type: CGEventType
        switch (button, down) {
        case (.left, true): type = .leftMouseDown
        case (.left, false): type = .leftMouseUp
        case (.right, true): type = .rightMouseDown
        case (.right, false): type = .rightMouseUp
        }
        let at = currentPosition(now: ProcessInfo.processInfo.systemUptime)
        guard let event = CGEvent(mouseEventSource: nil, mouseType: type, mouseCursorPosition: at,
                                  mouseButton: button == .left ? .left : .right) else { return }
        event.setIntegerValueField(.mouseEventClickState, value: Int64(clicks))
        event.post(tap: .cghidEventTap)
    }

    func scroll(dx: Double, dy: Double) {
        let direction = naturalScrolling ? 1.0 : -1.0
        scrollRemainder.x += dx * 2 * direction
        scrollRemainder.y += dy * 2 * direction
        let x = scrollRemainder.x.rounded(.towardZero)
        let y = scrollRemainder.y.rounded(.towardZero)
        guard x != 0 || y != 0 else { return }
        scrollRemainder.x -= x
        scrollRemainder.y -= y
        CGEvent(scrollWheelEvent2Source: nil, units: .pixel, wheelCount: 2,
                wheel1: Int32(y), wheel2: Int32(x), wheel3: 0)?.post(tap: .cghidEventTap)
    }

    /// Does what the same three-finger swipe does on the Mac's own trackpad, by pressing the
    /// Mission Control shortcut for it (Control + an arrow key). Fingers moving left bring in the
    /// space or full-screen app on the right, like turning a page.
    func swipe(_ swipe: TrackpadStream.Swipe) {
        let key: CGKeyCode
        switch swipe {
        case .left: key = 124   // Control-→: the space on the right
        case .right: key = 123  // Control-←: the space on the left
        case .up: key = 126     // Control-↑: Mission Control
        case .down: key = 125   // Control-↓: the front app's windows
        }
        // Control goes down and comes back up around the arrow, like on a real keyboard. Without
        // the release macOS keeps treating Control as held, and every later tap is a Control-click.
        let source = CGEventSource(stateID: .hidSystemState)
        let control: CGKeyCode = 59
        let steps: [(CGKeyCode, Bool, CGEventFlags)] = [
            (control, true, .maskControl),
            // Arrow keys carry these two extra flags when they come from a real keyboard.
            (key, true, [.maskControl, .maskSecondaryFn, .maskNumericPad]),
            (key, false, [.maskControl, .maskSecondaryFn, .maskNumericPad]),
            (control, false, []),
        ]
        for (code, down, flags) in steps {
            guard let event = CGEvent(keyboardEventSource: source, virtualKey: code, keyDown: down) else { continue }
            if code == control { event.type = .flagsChanged }
            event.flags = flags
            event.post(tap: .cghidEventTap)
        }
    }

    func releaseAll() {
        for button in held { self.button(button, down: false, clicks: 1) }
    }

    /// Keeps the pointer on a screen: a point past every display's edge is pulled back onto the
    /// display it came from.
    private func clamp(_ point: CGPoint, from old: CGPoint) -> CGPoint {
        var count: UInt32 = 0
        var ids = [CGDirectDisplayID](repeating: 0, count: 16)
        guard CGGetActiveDisplayList(16, &ids, &count) == .success, count > 0 else { return point }
        let screens = ids.prefix(Int(count)).map { CGDisplayBounds($0) }
        if screens.contains(where: { $0.contains(point) }) { return point }
        let home = screens.first { $0.contains(old) } ?? screens[0]
        return CGPoint(x: min(max(point.x, home.minX), home.maxX - 1),
                       y: min(max(point.y, home.minY), home.maxY - 1))
    }
}
