import AppKit
import ApplicationServices
import BubbleDropCore
import IOBluetooth

/// Lets a paired phone act as this Mac's trackpad and keyboard (PROTOCOL.md "Trackpad"). Off
/// until the user turns it on here, and macOS only lets BubbleDrop move the pointer and type
/// once it's allowed under Privacy & Security → Accessibility.
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
            return "Turn on \"Use phone as trackpad and keyboard\" in BubbleDrop on the Mac first."
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
        // The phone shows a Bluetooth switch: tell it the state now and whenever it changes,
        // including when it's changed on the Mac itself.
        let bluetooth = Task {
            var last: Bool?
            while !Task.isCancelled {
                if let on = MacBluetooth.isOn, on != last {
                    last = on
                    stream.report(.bluetooth, on: on)
                }
                try? await Task.sleep(nanoseconds: 1_000_000_000)
            }
        }
        defer { bluetooth.cancel() }
        while let event = try? await stream.next() {
            switch event {
            case .move(let dx, let dy): pointer.move(dx: dx, dy: dy)
            case .button(let button, let down, let clicks): pointer.button(button, down: down, clicks: clicks)
            case .scroll(let dx, let dy): pointer.scroll(dx: dx, dy: dy)
            case .swipe(let swipe): pointer.swipe(swipe)
            case .zoom(let phase, let magnification): pointer.zoom(phase, magnification: magnification)
            case .key(let key): pointer.key(key)
            case .text(let text): pointer.type(text)
            case .setting(.bluetooth, let on):
                let wasOn = MacBluetooth.isOn
                MacBluetooth.set(on)
                // Bluetooth takes a moment to switch. Then say how it ended up, even if it
                // didn't change, so the phone's switch isn't left showing what was asked for.
                Task {
                    for _ in 0..<20 where MacBluetooth.isOn != on {
                        try? await Task.sleep(nanoseconds: 150_000_000)
                    }
                    guard let now = MacBluetooth.isOn else { return }
                    stream.report(.bluetooth, on: now)
                    // Switched on from the phone: bring a device back too, not just the radio.
                    if on, now, wasOn == false { BluetoothReconnect.shared.start() }
                }
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

/// The Mac's Bluetooth power. macOS has no public way to switch it; these are the calls its own
/// Bluetooth settings use.
enum MacBluetooth {
    private static let framework = dlopen("/System/Library/Frameworks/IOBluetooth.framework/IOBluetooth", RTLD_LAZY)

    /// Whether Bluetooth is on; nil if this macOS doesn't say.
    static var isOn: Bool? {
        typealias Getter = @convention(c) () -> Int32
        guard let framework, let getter = dlsym(framework, "IOBluetoothPreferenceGetControllerPowerState") else { return nil }
        return unsafeBitCast(getter, to: Getter.self)() != 0
    }

    static func set(_ on: Bool) {
        typealias Setter = @convention(c) (Int32) -> Void
        guard let framework, let setter = dlsym(framework, "IOBluetoothPreferenceSetControllerPowerState") else { return }
        unsafeBitCast(setter, to: Setter.self)(on ? 1 : 0)
    }
}

/// After Bluetooth is switched on from the phone, connects the nearest of the Mac's paired
/// devices if none connects by itself. macOS can't tell how far away a device is until it's
/// connected, so the most recently used paired devices are each connected for a moment, their
/// signal strength is read, and only the strongest (so, roughly, the nearest) is kept. A device
/// that's switched off or out of range doesn't answer and is skipped.
final class BluetoothReconnect: NSObject, IOBluetoothDeviceAsyncCallbacks {
    static let shared = BluetoothReconnect()

    /// How long devices that reconnect by themselves (keyboards, mice, AirPods) get to do so.
    private static let headStart: TimeInterval = 3
    /// How many paired devices are tried, from the most recently used.
    private static let most = 4
    /// How long one device gets to answer, in Bluetooth slots of 0.625 ms (about 3 seconds).
    private static let pageTimeout: BluetoothHCIPageTimeout = 0x1400
    /// A connection needs a moment before it reports a signal strength; it's read this many
    /// times, this far apart, and the best reading counts.
    private static let readings = 4
    private static let readingGap: TimeInterval = 0.4

    // All of this is only touched on the main thread, where IOBluetooth also calls back.
    /// The devices still to try.
    private var waiting: [IOBluetoothDevice] = []
    /// The device being tried now.
    private var trying: IOBluetoothDevice?
    /// The devices that answered, with the strongest signal read from each (dBm; nil if none was).
    private var answered: [(device: IOBluetoothDevice, signal: Int?)] = []
    private var run = 0

    func start() {
        DispatchQueue.main.async {
            self.cancel()
            let run = self.run
            DispatchQueue.main.asyncAfter(deadline: .now() + Self.headStart) {
                guard run == self.run, MacBluetooth.isOn == true,
                      let paired = IOBluetoothDevice.pairedDevices() as? [IOBluetoothDevice],
                      !paired.contains(where: { $0.isConnected() }) else { return }
                self.waiting = Array(paired.sorted {
                    ($0.recentAccessDate() ?? .distantPast) > ($1.recentAccessDate() ?? .distantPast)
                }.prefix(Self.most))
                self.next()
            }
        }
    }

    /// Drops a search that's under way, closing the connections it opened to compare devices.
    private func cancel() {
        run += 1
        waiting = []
        trying = nil
        answered.forEach { _ = $0.device.closeConnection() }
        answered = []
    }

    private func next() {
        guard MacBluetooth.isOn == true else { return cancel() }
        while !waiting.isEmpty {
            let device = waiting.removeFirst()
            trying = device
            // Started: connectionComplete says how it went.
            if device.openConnection(self, withPageTimeout: Self.pageTimeout,
                                     authenticationRequired: false) == kIOReturnSuccess { return }
        }
        trying = nil
        // One answer (or none) needs no comparing.
        if answered.count > 1 { measure(run, left: Self.readings) } else { answered = [] }
    }

    func connectionComplete(_ device: IOBluetoothDevice!, status: IOReturn) {
        DispatchQueue.main.async {
            // Only the device being tried: one that was closed or dropped later calls back too.
            guard let device, device === self.trying else { return }
            if status == kIOReturnSuccess { self.answered.append((device, nil)) }
            self.next()
        }
    }

    /// Reads every answering device's signal strength a few times, then keeps the strongest.
    private func measure(_ run: Int, left: Int) {
        guard run == self.run else { return }
        guard MacBluetooth.isOn == true else { return cancel() }
        for i in answered.indices where answered[i].device.isConnected() {
            // 127 means the connection has no reading yet.
            let now = Int(answered[i].device.rawRSSI())
            if now != 127 { answered[i].signal = max(answered[i].signal ?? now, now) }
        }
        if left > 1 {
            DispatchQueue.main.asyncAfter(deadline: .now() + Self.readingGap) { self.measure(run, left: left - 1) }
            return
        }
        // A device with no reading counts as the farthest; ties keep the more recently used.
        let still = answered.filter { $0.device.isConnected() }
        let nearest = still.max { ($0.signal ?? Int.min) < ($1.signal ?? Int.min) }?.device
        for (device, _) in still where device !== nearest { _ = device.closeConnection() }
        answered = []
    }

    func remoteNameRequestComplete(_ device: IOBluetoothDevice!, status: IOReturn) {}

    func sdpQueryComplete(_ device: IOBluetoothDevice!, status: IOReturn) {}
}

/// Turns trackpad events into macOS pointer events. Used by one session's task at a time.
private final class PointerInjector {
    private var position = CGPoint.zero
    private var lastMove: TimeInterval = 0
    private var speed = 0.0
    private var held: Set<TrackpadStream.Button> = []
    private var scrollRemainder = CGPoint.zero
    private var zooming = false
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

    /// A pinch, as the magnify gesture the Mac's own trackpad sends, so apps zoom smoothly around
    /// the pointer. macOS has no public way to make one; these are the fields its own events carry.
    func zoom(_ phase: TrackpadStream.ZoomPhase, magnification: Double) {
        switch phase {
        case .begin:
            if zooming { postZoom(0, phase: 4) }
            zooming = true
            postZoom(0, phase: 1)
        case .change:
            guard zooming, magnification != 0 else { return }
            postZoom(magnification, phase: 2)
        case .end:
            guard zooming else { return }
            zooming = false
            postZoom(0, phase: 4)
        }
    }

    /// `phase` is 1 when the gesture begins, 2 while it changes and 4 when it ends.
    private func postZoom(_ magnification: Double, phase: Int64) {
        guard let event = CGEvent(source: nil), let gesture = CGEventType(rawValue: 29),
              let kind = CGEventField(rawValue: 110), let amount = CGEventField(rawValue: 113),
              let phaseField = CGEventField(rawValue: 132) else { return }
        event.type = gesture
        event.setIntegerValueField(kind, value: 8)  // a zoom gesture
        event.setDoubleValueField(amount, value: magnification)
        event.setIntegerValueField(phaseField, value: phase)
        event.post(tap: .cghidEventTap)
    }

    func key(_ key: TrackpadStream.Key) {
        switch key {
        case .volumeUp: pressMediaKey(0)
        case .volumeDown: pressMediaKey(1)
        case .mute: pressMediaKey(7)
        case .playPause: pressMediaKey(16)
        case .brightnessUp: stepBrightness(1)
        case .brightnessDown: stepBrightness(-1)
        case .returnKey: pressKey(36)
        case .delete: pressKey(51)
        case .escape: pressKey(53)
        case .tab: pressKey(48)
        case .left: pressKey(123, arrow: true)
        case .right: pressKey(124, arrow: true)
        case .down: pressKey(125, arrow: true)
        case .up: pressKey(126, arrow: true)
        case .lock: lockScreen()
        case .copy: pressCommand(8)   // ⌘C
        case .paste: pressCommand(9)  // ⌘V
        case .cut: pressCommand(7)    // ⌘X
        case .selectAll: pressCommand(0)  // ⌘A
        case .undo: pressCommand(6)   // ⌘Z
        }
    }

    /// Locks the Mac, like Control-Command-Q. Goes straight to the login window's own lock, and
    /// falls back to pressing the shortcut if that isn't there.
    private func lockScreen() {
        typealias Lock = @convention(c) () -> Void
        if let login = dlopen("/System/Library/PrivateFrameworks/login.framework/Versions/Current/login", RTLD_LAZY),
           let lock = dlsym(login, "SACLockScreenImmediate") {
            unsafeBitCast(lock, to: Lock.self)()
            return
        }
        for down in [true, false] {
            guard let event = CGEvent(keyboardEventSource: nil, virtualKey: 12, keyDown: down) else { continue }  // Q
            event.flags = [.maskControl, .maskCommand]
            event.post(tap: .cghidEventTap)
        }
    }

    /// Presses `code` with Command held, like ⌘C. Command goes down and comes back up around the
    /// key, like on a real keyboard; without the release macOS keeps treating Command as held.
    private func pressCommand(_ code: CGKeyCode) {
        let source = CGEventSource(stateID: .hidSystemState)
        let command: CGKeyCode = 55
        let steps: [(CGKeyCode, Bool, CGEventFlags)] = [
            (command, true, .maskCommand),
            (code, true, .maskCommand),
            (code, false, .maskCommand),
            (command, false, []),
        ]
        for (key, down, flags) in steps {
            guard let event = CGEvent(keyboardEventSource: source, virtualKey: key, keyDown: down) else { continue }
            if key == command { event.type = .flagsChanged }
            event.flags = flags
            event.post(tap: .cghidEventTap)
        }
    }

    /// Presses and releases one key of the keyboard, with no modifiers held.
    private func pressKey(_ code: CGKeyCode, arrow: Bool = false) {
        for down in [true, false] {
            guard let event = CGEvent(keyboardEventSource: nil, virtualKey: code, keyDown: down) else { continue }
            // Arrow keys carry these two flags when they come from a real keyboard.
            event.flags = arrow ? [.maskSecondaryFn, .maskNumericPad] : []
            event.post(tap: .cghidEventTap)
        }
    }

    /// Types `text` into whatever has the keyboard, one character at a time: each goes as a key
    /// press carrying the character itself, so it doesn't depend on the Mac's keyboard layout.
    func type(_ text: String) {
        for character in text {
            if character.isNewline {
                pressKey(36)
                continue
            }
            if character == "\t" {
                pressKey(48)
                continue
            }
            // Other control characters aren't text.
            guard !character.unicodeScalars.contains(where: { $0.properties.generalCategory == .control }) else { continue }
            let units = Array(String(character).utf16)
            for down in [true, false] {
                guard let event = CGEvent(keyboardEventSource: nil, virtualKey: 0, keyDown: down) else { continue }
                event.flags = []
                event.keyboardSetUnicodeString(stringLength: units.count, unicodeString: units)
                event.post(tap: .cghidEventTap)
            }
        }
    }

    /// Presses one of the keyboard's media keys (the top row), which is how the volume and
    /// play keys reach macOS.
    private func pressMediaKey(_ code: Int) {
        for down in [true, false] {
            let state = down ? 0xa : 0xb
            NSEvent.otherEvent(with: .systemDefined, location: .zero,
                               modifierFlags: NSEvent.ModifierFlags(rawValue: UInt(state << 8)),
                               timestamp: 0, windowNumber: 0, context: nil, subtype: 8,
                               data1: code << 16 | state << 8, data2: -1)?
                .cgEvent?.post(tap: .cghidEventTap)
        }
    }

    /// Moves the built-in display's brightness one of the keyboard's 16 steps. Posted brightness
    /// keys are ignored on Apple silicon, so this sets the display directly.
    private func stepBrightness(_ direction: Float) {
        typealias Getter = @convention(c) (CGDirectDisplayID, UnsafeMutablePointer<Float>) -> Int32
        typealias Setter = @convention(c) (CGDirectDisplayID, Float) -> Int32
        guard let services = dlopen("/System/Library/PrivateFrameworks/DisplayServices.framework/DisplayServices", RTLD_LAZY),
              let getter = dlsym(services, "DisplayServicesGetBrightness"),
              let setter = dlsym(services, "DisplayServicesSetBrightness") else { return }
        let display = CGMainDisplayID()
        var level: Float = 0
        guard unsafeBitCast(getter, to: Getter.self)(display, &level) == 0 else { return }
        _ = unsafeBitCast(setter, to: Setter.self)(display, min(max(level + direction / 16, 0), 1))
    }

    func releaseAll() {
        for button in held { self.button(button, down: false, clicks: 1) }
        zoom(.end, magnification: 0)
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
