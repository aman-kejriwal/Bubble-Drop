import AppKit
import SwiftUI
import BubbleDropCore

/// The paired phone's calls: a call screen at the top of the display that answers, declines and
/// hangs up on the phone. The audio stays on the phone (Android doesn't let apps carry call audio),
/// so answering here can switch the phone to speaker.
@MainActor
final class PhoneCalls: ObservableObject {
    static let shared = PhoneCalls()

    struct Current: Equatable {
        var call: PhoneCall
        var phone: PhoneSource
        var connectedAt: Date?
        /// Answer was clicked here; waiting for the phone to report the call connected.
        var answering = false
        var error: String?
    }

    @Published private(set) var current: Current?
    /// Turn the phone's speaker on when a call is answered from the Mac.
    @Published var speakerOnAnswer: Bool { didSet { UserDefaults.standard.set(speakerOnAnswer, forKey: Self.speakerKey) } }

    /// The ringtone file name (without `.m4r`), or `silentRingtone`.
    @Published var ringtoneName: String {
        didSet {
            UserDefaults.standard.set(ringtoneName, forKey: Self.ringtoneKey)
            previewRingtone()
        }
    }

    private static let speakerKey = "speakerOnAnswer"
    private static let ringtoneKey = "ringtone"
    /// The phone re-sends a live call every 5 s; this long without word means the call (or the phone) is gone.
    private static let staleAfter: TimeInterval = 15
    private var ringtone: NSSound?
    private var preview: NSSound?
    private var previewStop: DispatchWorkItem?
    private var ringTimer: Timer?
    private var staleTimer: Timer?
    /// The call the user hid; the phone's periodic updates mustn't bring it back.
    private var hiddenID: String?

    private init() {
        speakerOnAnswer = UserDefaults.standard.object(forKey: Self.speakerKey) as? Bool ?? true
        ringtoneName = UserDefaults.standard.string(forKey: Self.ringtoneKey) ?? Self.defaultRingtone
    }

    // MARK: From the phone

    func handle(_ call: PhoneCall, from phone: PhoneSource) {
        guard PhoneNotifications.shared.showCalls || current?.call.id == call.id else { return }
        let same = current?.call.id == call.id
        switch call.state {
        case "ringing":
            // Repeats of a ringing call keep "Answering…" and any error showing.
            if same, current?.call.state == "ringing" {
                current?.call = call
            } else {
                current = Current(call: call, phone: phone)
            }
            if hiddenID != call.id {
                startRinging()
                CallPanel.shared.present()
            }
            watchForSilence()
        case "active":
            stopRinging()
            let connectedAt = same ? current?.connectedAt ?? Date() : Date()
            current = Current(call: call, phone: phone, connectedAt: connectedAt)
            if hiddenID != call.id { CallPanel.shared.present() }
            watchForSilence()
        default:
            // The phone follows one call at a time, so its "ended" also clears a call we know by an older id.
            guard let current, current.call.state != "ended",
                  current.call.id == call.id || current.phone.fingerprint == phone.fingerprint else { return }
            showEnded()
        }
    }

    /// Shows "Call ended" briefly, then closes the call screen.
    private func showEnded() {
        guard let id = current?.call.id else { return }
        stopRinging()
        staleTimer?.invalidate()
        staleTimer = nil
        current?.call.state = "ended"
        current?.answering = false
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.6) {
            guard PhoneCalls.shared.current?.call.id == id, PhoneCalls.shared.current?.call.state == "ended" else { return }
            PhoneCalls.shared.current = nil
            PhoneCalls.shared.hiddenID = nil
            CallPanel.shared.dismiss()
        }
    }

    /// Restarts the countdown that ends the call if the phone stops reporting it, e.g. BubbleDrop disconnected.
    private func watchForSilence() {
        staleTimer?.invalidate()
        staleTimer = Timer.scheduledTimer(withTimeInterval: Self.staleAfter, repeats: false) { _ in
            MainActor.assumeIsolated { PhoneCalls.shared.showEnded() }
        }
    }

    // MARK: Ringtones

    static let silentRingtone = "Silent"
    /// macOS's default ringtone, the one FaceTime and iPhone calls ring with on a Mac.
    static let defaultRingtone = "Reflection"
    private static let ringtonesDirectory = URL(fileURLWithPath:
        "/System/Library/PrivateFrameworks/ToneLibrary.framework/Versions/A/Resources/Ringtones")

    /// The ringtones macOS ships, by file name, sorted for the picker.
    static let ringtones: [String] = {
        let files = (try? FileManager.default.contentsOfDirectory(at: ringtonesDirectory, includingPropertiesForKeys: nil)) ?? []
        return files.filter { $0.pathExtension == "m4r" }
            .map { $0.deletingPathExtension().lastPathComponent }
            .sorted { $0.localizedCaseInsensitiveCompare($1) == .orderedAscending }
    }()

    /// "Reflection-EncoreInfinitum" → "Reflection (Encore)", matching how Apple lists the alternates.
    static func displayName(_ ringtone: String) -> String {
        ringtone.hasSuffix("-EncoreInfinitum")
            ? ringtone.replacingOccurrences(of: "-EncoreInfinitum", with: "") + " (Encore)"
            : ringtone
    }

    private static func sound(named name: String) -> NSSound? {
        NSSound(contentsOf: ringtonesDirectory.appendingPathComponent(name + ".m4r"), byReference: true)
            ?? NSSound(contentsOf: ringtonesDirectory.appendingPathComponent(defaultRingtone + ".m4r"), byReference: true)
    }

    /// Plays the first few seconds of the chosen ringtone, so picking one lets you hear it.
    private func previewRingtone() {
        preview?.stop()
        previewStop?.cancel()
        guard ringtone == nil, ringtoneName != Self.silentRingtone, let sound = Self.sound(named: ringtoneName) else { return }
        sound.play()
        preview = sound
        let stop = DispatchWorkItem { [weak self] in
            self?.preview?.stop()
            self?.preview = nil
        }
        previewStop = stop
        DispatchQueue.main.asyncAfter(deadline: .now() + 4, execute: stop)
    }

    private func startRinging() {
        guard ringtone == nil, ringTimer == nil, ringtoneName != Self.silentRingtone else { return }
        preview?.stop()
        if let sound = Self.sound(named: ringtoneName) {
            sound.loops = true
            sound.play()
            ringtone = sound
            return
        }
        // The ringtone lives in a private framework; fall back to an alert sound if it ever moves.
        let ring = { _ = NSSound(named: "Submarine")?.play() }
        ring()
        ringTimer = Timer.scheduledTimer(withTimeInterval: 2.4, repeats: true) { _ in ring() }
    }

    private func stopRinging() {
        ringtone?.stop()
        ringtone = nil
        ringTimer?.invalidate()
        ringTimer = nil
    }

    // MARK: To the phone

    func answer() {
        guard current?.call.state == "ringing" else { return }
        current?.answering = true
        stopRinging()
        send(CallActionRequest(action: "answer", speaker: speakerOnAnswer))
    }

    func decline() {
        stopRinging()
        send(CallActionRequest(action: "decline"))
    }

    func hangUp() {
        send(CallActionRequest(action: "hangup"))
    }

    func setSpeaker(_ on: Bool) {
        current?.call.speaker = on
        send(CallActionRequest(action: "speaker", speaker: on))
    }

    /// Hides the call screen without touching the call.
    func hide() {
        hiddenID = current?.call.id
        stopRinging()
        CallPanel.shared.dismiss()
    }

    private func send(_ request: CallActionRequest) {
        guard let current else { return }
        let id = current.call.id
        Task {
            let status = await AppModel.shared.sendToPhone(request, path: "/api/v2/call/action",
                                                           fingerprint: current.phone.fingerprint)
            guard status != 200, self.current?.call.id == id else { return }
            // 404: the phone has no call any more, so this one is over.
            if status == 404 { return self.showEnded() }
            self.current?.answering = false
            self.current?.error = status == nil ? "Couldn't reach \(current.phone.name)" : "The phone didn't respond to that"
        }
    }
}

/// A floating, Dynamic Island-style card at the top centre of the screen.
@MainActor
final class CallPanel {
    static let shared = CallPanel()

    private static let size = NSSize(width: 400, height: 92)

    private lazy var panel: NSPanel = {
        let panel = KeyablePanel(contentRect: NSRect(origin: .zero, size: Self.size),
                                 styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: true)
        panel.isFloatingPanel = true
        panel.level = .statusBar
        panel.backgroundColor = .clear
        panel.isOpaque = false
        panel.hasShadow = true
        panel.hidesOnDeactivate = false
        panel.isMovableByWindowBackground = true
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        panel.contentView = NSHostingView(rootView: CallCard().environmentObject(PhoneCalls.shared))
        return panel
    }()

    private var isShown = false

    func present() {
        guard !isShown else { return }
        isShown = true
        if let screen = NSScreen.main?.visibleFrame {
            panel.setFrameOrigin(NSPoint(x: screen.midX - Self.size.width / 2, y: screen.maxY - Self.size.height - 10))
        }
        panel.alphaValue = 0
        panel.orderFrontRegardless()
        NSAnimationContext.runAnimationGroup { context in
            context.duration = 0.22
            panel.animator().alphaValue = 1
        }
        panel.invalidateShadow()
    }

    func dismiss() {
        guard isShown else { return }
        isShown = false
        NSAnimationContext.runAnimationGroup({ context in
            context.duration = 0.25
            panel.animator().alphaValue = 0
        }, completionHandler: {
            MainActor.assumeIsolated {
                if !CallPanel.shared.isShown { CallPanel.shared.panel.orderOut(nil) }
            }
        })
    }
}

private struct CallCard: View {
    @EnvironmentObject private var calls: PhoneCalls

    var body: some View {
        ZStack {
            VisualEffect()
            if let current = calls.current {
                HStack(spacing: 14) {
                    CallerAvatar(name: current.call.caller, ringing: current.call.state == "ringing" && !current.answering)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(current.call.caller).font(.headline).lineLimit(1)
                        status(current)
                            .font(.caption)
                            .foregroundStyle(current.error == nil ? Color.secondary : Color.red)
                            .lineLimit(1)
                    }
                    Spacer(minLength: 4)
                    buttons(current)
                }
                .padding(.horizontal, 18)
            }
        }
        .frame(width: 400, height: 92)
        .clipShape(RoundedRectangle(cornerRadius: 30, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 30, style: .continuous).strokeBorder(.white.opacity(0.12)))
        .contextMenu { Button("Hide") { calls.hide() } }
        .animation(.easeInOut(duration: 0.2), value: calls.current)
    }

    @ViewBuilder private func status(_ current: PhoneCalls.Current) -> some View {
        if let error = current.error {
            Text(error)
        } else if current.call.state == "ended" {
            Text("Call ended")
        } else if current.answering {
            Text("Answering on \(current.phone.name)…")
        } else if let connectedAt = current.connectedAt, current.call.state == "active" {
            TimelineView(.periodic(from: connectedAt, by: 1)) { context in
                Text("\(duration(since: connectedAt, now: context.date)) · audio on \(current.phone.name)")
            }
        } else {
            Text("Incoming call · \(current.phone.name)")
        }
    }

    @ViewBuilder private func buttons(_ current: PhoneCalls.Current) -> some View {
        switch current.call.state {
        case "ringing" where !current.answering:
            CallButton(symbol: "phone.down.fill", color: .red, help: "Decline") { calls.decline() }
            CallButton(symbol: "phone.fill", color: .green, help: calls.speakerOnAnswer
                       ? "Answer on speaker" : "Answer") { calls.answer() }
        case "active":
            let speaker = current.call.speaker == true
            CallButton(symbol: speaker ? "speaker.wave.3.fill" : "speaker.fill",
                       color: speaker ? .white : .gray.opacity(0.55), foreground: speaker ? .black : .white,
                       help: speaker ? "Turn speaker off" : "Turn speaker on") { calls.setSpeaker(!speaker) }
            CallButton(symbol: "phone.down.fill", color: .red, help: "Hang up") { calls.hangUp() }
        default:
            EmptyView()
        }
    }

    private func duration(since start: Date, now: Date) -> String {
        let seconds = max(0, Int(now.timeIntervalSince(start)))
        return seconds >= 3600
            ? String(format: "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
            : String(format: "%d:%02d", seconds / 60, seconds % 60)
    }
}

private struct CallButton: View {
    let symbol: String
    let color: Color
    var foreground: Color = .white
    let help: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(foreground)
                .frame(width: 46, height: 46)
                .background(Circle().fill(color.gradient))
        }
        .buttonStyle(.plain)
        .help(help)
    }
}

/// The caller's initials, with rings pulsing out while the call rings.
private struct CallerAvatar: View {
    let name: String
    let ringing: Bool
    @State private var pulse = false

    var body: some View {
        ZStack {
            if ringing {
                ForEach(0..<2) { i in
                    Circle()
                        .stroke(Color.green.opacity(0.6), lineWidth: 2)
                        .scaleEffect(pulse ? 1.45 : 1)
                        .opacity(pulse ? 0 : 1)
                        .animation(.easeOut(duration: 1.4).repeatForever(autoreverses: false).delay(Double(i) * 0.7),
                                   value: pulse)
                }
            }
            Circle().fill(LinearGradient(colors: [.gray.opacity(0.7), .gray.opacity(0.4)],
                                         startPoint: .top, endPoint: .bottom))
            Text(initials).font(.system(size: 18, weight: .semibold)).foregroundStyle(.white)
        }
        .frame(width: 50, height: 50)
        .onAppear { pulse = true }
    }

    private var initials: String {
        let words = name.split(separator: " ").filter { $0.first?.isLetter == true }
        guard !words.isEmpty else { return "☎︎" }
        return words.prefix(2).compactMap { $0.first.map(String.init) }.joined().uppercased()
    }
}
