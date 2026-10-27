import AppKit
import SwiftUI
import BubbleDropCore

/// An incoming request waiting for Accept / Decline in the drop panel.
struct IncomingOffer: Identifiable, Equatable {
    let id = UUID()
    let senderName: String
    let platform: String
    let fileNames: [String]
    let totalBytes: Int64
    /// Set once the user accepts, until the server reports the session and the transfer takes over.
    var accepted = false
}

/// The floating AirDrop-style card in the top-right corner: offers, live progress and the finish.
@MainActor
final class DropPanel {
    static let shared = DropPanel()

    private static let size = NSSize(width: 340, height: 380)

    private lazy var panel: NSPanel = {
        let panel = KeyablePanel(contentRect: NSRect(origin: .zero, size: Self.size),
                                 styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: true)
        panel.isFloatingPanel = true
        panel.level = .floating
        panel.backgroundColor = .clear
        panel.isOpaque = false
        panel.hasShadow = true
        panel.hidesOnDeactivate = false
        panel.isMovableByWindowBackground = true
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        panel.contentView = NSHostingView(rootView: DropCard().environmentObject(AppModel.shared))
        return panel
    }()

    private var isShown = false

    func present(focus: Bool) {
        if !isShown {
            isShown = true
            if let screen = NSScreen.main?.visibleFrame {
                panel.setFrameOrigin(NSPoint(x: screen.maxX - Self.size.width - 16,
                                             y: screen.maxY - Self.size.height - 12))
            }
            panel.alphaValue = 0
            panel.orderFrontRegardless()
            NSAnimationContext.runAnimationGroup { context in
                context.duration = 0.22
                panel.animator().alphaValue = 1
            }
            panel.invalidateShadow()
        }
        if focus {
            NSApp.activate(ignoringOtherApps: true)
            panel.makeKey()
        }
    }

    func dismiss() {
        guard isShown else { return }
        isShown = false
        NSAnimationContext.runAnimationGroup({ context in
            context.duration = 0.25
            panel.animator().alphaValue = 0
        }, completionHandler: {
            MainActor.assumeIsolated {
                if !DropPanel.shared.isShown { DropPanel.shared.panel.orderOut(nil) }
            }
        })
    }
}

final class KeyablePanel: NSPanel {
    override var canBecomeKey: Bool { true }
}

struct VisualEffect: NSViewRepresentable {
    func makeNSView(context: Context) -> NSVisualEffectView {
        let view = NSVisualEffectView()
        view.material = .hudWindow
        view.blendingMode = .behindWindow
        view.state = .active
        return view
    }

    func updateNSView(_ nsView: NSVisualEffectView, context: Context) {}
}

struct DropCard: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        ZStack {
            VisualEffect()
            Group {
                if let offer = model.offer {
                    OfferContent(offer: offer)
                } else if !model.shareFiles.isEmpty || model.shareLink != nil {
                    SharePicker(files: model.shareFiles, link: model.shareLink)
                } else if let transfer = model.spotlight {
                    TransferContent(transfer: transfer)
                }
            }
            .padding(22)
            .transition(.opacity)
        }
        .frame(width: 340, height: 380)
        .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 28, style: .continuous).strokeBorder(.white.opacity(0.12)))
        .animation(.easeInOut(duration: 0.25), value: model.offer?.id)
        .animation(.easeInOut(duration: 0.25), value: model.shareFiles)
        .animation(.easeInOut(duration: 0.25), value: model.shareLink)
    }
}

/// Shown after "Share → BubbleDrop": the shared files on top, nearby devices as bubbles below.
private struct SharePicker: View {
    @EnvironmentObject private var model: AppModel
    let files: [URL]
    let link: URL?

    var body: some View {
        VStack(spacing: 12) {
            HStack(spacing: 12) {
                if let link {
                    Image(systemName: "link")
                        .font(.system(size: 20, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(width: 44, height: 44)
                        .background(Circle().fill(Color.accentColor.gradient))
                    VStack(alignment: .leading, spacing: 2) {
                        Text(link.host ?? link.absoluteString).font(.headline).lineLimit(1)
                        Text(link.absoluteString)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                            .truncationMode(.middle)
                    }
                } else {
                    Image(nsImage: NSWorkspace.shared.icon(forFile: files[0].path))
                        .resizable()
                        .frame(width: 44, height: 44)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(files.count == 1 ? files[0].lastPathComponent : "\(files.count) items")
                            .font(.headline)
                            .lineLimit(1)
                            .truncationMode(.middle)
                        Text(formatBytes(totalBytes)).font(.caption).foregroundStyle(.secondary)
                    }
                }
                Spacer(minLength: 0)
            }
            Divider()
            Text(link != nil ? "Open on a device" : "Send with BubbleDrop")
                .font(.subheadline.weight(.semibold))
                .frame(maxWidth: .infinity, alignment: .leading)
            if model.peers.isEmpty {
                Spacer(minLength: 0)
                ZStack {
                    ForEach(0..<3, id: \.self) { RippleRing(delay: Double($0) * 0.8, diameter: 60) }
                    Image(systemName: "dot.radiowaves.left.and.right")
                        .font(.system(size: 24, weight: .medium))
                        .foregroundStyle(Color.accentColor)
                }
                .frame(height: 100)
                Text("Looking for nearby devices…").font(.callout).foregroundStyle(.secondary)
                Spacer(minLength: 0)
            } else {
                ScrollView {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 88), spacing: 8)], spacing: 14) {
                        ForEach(model.peers) { PeerBubble(peer: $0) }
                    }
                    .padding(.vertical, 6)
                }
            }
            Button { model.cancelShare() } label: { Text("Cancel").frame(maxWidth: .infinity) }
                .keyboardShortcut(.cancelAction)
                .controlSize(.large)
        }
    }

    private var totalBytes: Int64 {
        files.reduce(0) { $0 + Int64((try? $1.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) }
    }
}

private struct PeerBubble: View {
    @EnvironmentObject private var model: AppModel
    let peer: Peer
    @State private var hovering = false

    var body: some View {
        Button { model.sendShared(to: peer) } label: {
            VStack(spacing: 6) {
                ZStack {
                    Circle()
                        .fill(LinearGradient(colors: [Color(red: 0.2, green: 0.8, blue: 0.98), .accentColor, .purple],
                                             startPoint: .topLeading, endPoint: .bottomTrailing))
                    Circle()
                        .fill(.white.opacity(0.35))
                        .frame(width: 16, height: 10)
                        .offset(x: -14, y: -18)
                    Image(systemName: peer.platform == "android" ? "iphone.gen3" : "laptopcomputer")
                        .font(.system(size: 26, weight: .medium))
                        .foregroundStyle(.white)
                }
                .frame(width: 64, height: 64)
                .shadow(color: .accentColor.opacity(hovering ? 0.5 : 0.2), radius: hovering ? 10 : 4)
                .scaleEffect(hovering ? 1.08 : 1)
                Text(peer.name)
                    .font(.caption)
                    .lineLimit(2)
                    .multilineTextAlignment(.center)
                if !model.isPaired(peer) {
                    Text(model.canPair(peer) ? "Pairs first" : "Can't pair").font(.caption2).foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!model.isPaired(peer) && !model.canPair(peer))
        .onHover { hovering = $0 }
        .animation(.spring(response: 0.3, dampingFraction: 0.6), value: hovering)
        .help(model.isPaired(peer) ? "Send to \(peer.name)" : "Pair with \(peer.name), then send")
    }
}

private struct OfferContent: View {
    @EnvironmentObject private var model: AppModel
    let offer: IncomingOffer

    var body: some View {
        VStack(spacing: 0) {
            DropOrb(platform: offer.platform, phase: .waiting)
            Text(offer.senderName)
                .font(.title2.weight(.semibold))
                .lineLimit(1)
            Text(offer.accepted ? "Connecting…"
                 : "wants to share \(offer.fileNames.count == 1 ? "a file" : "\(offer.fileNames.count) files")")
                .foregroundStyle(.secondary)
            VStack(alignment: .leading, spacing: 2) {
                ForEach(offer.fileNames.prefix(2), id: \.self) { Text($0).lineLimit(1).truncationMode(.middle) }
                if offer.fileNames.count > 2 {
                    Text("and \(offer.fileNames.count - 2) more").foregroundStyle(.secondary)
                }
                Text("\(formatBytes(offer.totalBytes)) · saves to Downloads")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .font(.callout)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(10)
            .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(.primary.opacity(0.06)))
            .padding(.top, 12)
            Spacer(minLength: 12)
            HStack(spacing: 10) {
                Button { model.answerOffer(false) } label: { Text("Decline").frame(maxWidth: .infinity) }
                    .keyboardShortcut(.cancelAction)
                Button { model.acceptOffer() } label: { Text("Accept").frame(maxWidth: .infinity) }
                    .keyboardShortcut(.defaultAction)
                    .buttonStyle(.borderedProminent)
            }
            .controlSize(.large)
            .disabled(offer.accepted)
        }
    }
}

private struct TransferContent: View {
    @EnvironmentObject private var model: AppModel
    let transfer: Transfer

    private var incoming: Bool { transfer.direction == .incoming }

    var body: some View {
        VStack(spacing: 4) {
            DropOrb(platform: transfer.peerPlatform, phase: phase)
            Text(title)
                .font(.title2.weight(.semibold))
                .lineLimit(1)
            Text(transfer.summary)
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .truncationMode(.middle)
            Text(detail)
                .font(.callout.monospacedDigit())
                .foregroundStyle(isFailed ? AnyShapeStyle(.red) : AnyShapeStyle(.secondary))
                .multilineTextAlignment(.center)
                .lineLimit(2)
            Spacer(minLength: 12)
            HStack(spacing: 10) {
                switch transfer.status {
                case .done where incoming:
                    Button { model.closeSpotlight() } label: { Text("Done").frame(maxWidth: .infinity) }
                    Button {
                        model.reveal(transfer)
                        model.closeSpotlight()
                    } label: { Text("Show in Finder").frame(maxWidth: .infinity) }
                        .buttonStyle(.borderedProminent)
                        .keyboardShortcut(.defaultAction)
                case .done, .failed:
                    Button { model.closeSpotlight() } label: { Text("Done").frame(maxWidth: .infinity) }
                        .keyboardShortcut(.defaultAction)
                default:
                    Button { model.closeSpotlight() } label: { Text("Hide").frame(maxWidth: .infinity) }
                }
            }
            .controlSize(.large)
        }
    }

    private var isFailed: Bool {
        if case .failed = transfer.status { return true }
        return false
    }

    private var phase: OrbPhase {
        switch transfer.status {
        case .waiting: .waiting
        case .active: .progress(transfer.fraction)
        case .done: .done
        case .failed: .failed
        }
    }

    private var title: String {
        switch transfer.status {
        case .waiting: incoming ? "Connecting…"
            : transfer.link != nil ? "Sending to \(transfer.peerName)" : "Waiting for \(transfer.peerName)"
        case .active: incoming ? "Receiving from \(transfer.peerName)" : "Sending to \(transfer.peerName)"
        case .done where transfer.link != nil: incoming || transfer.linkOpened ? "Opened" : "Sent"
        case .done: incoming ? "Received" : "Sent"
        case .failed: incoming ? "Couldn't receive" : "Couldn't send"
        }
    }

    private var detail: String {
        switch transfer.status {
        case .waiting:
            return incoming || transfer.link != nil ? "" : "They'll be asked to accept"
        case .active:
            var text = "\(formatBytes(transfer.doneBytes)) of \(formatBytes(transfer.totalBytes))"
            if let started = transfer.startedAt {
                let elapsed = Date().timeIntervalSince(started)
                if elapsed > 0.5 {
                    let speed = Double(transfer.doneBytes - transfer.startBytes) / elapsed
                    if speed > 0 {
                        text += " · \(formatBytes(Int64(speed)))/s"
                        let remaining = Double(transfer.totalBytes - transfer.doneBytes) / speed
                        if remaining >= 1 {
                            let s = Int(remaining)
                            text += s < 60 ? " · \(s)s left" : " · \(s / 60)m \(s % 60)s left"
                        }
                    }
                }
            }
            return text
        case .done where transfer.link != nil:
            if incoming { return "Link from \(transfer.peerName)" }
            return transfer.linkOpened ? "In \(transfer.peerName)'s browser"
                : "\(transfer.peerName) got a notification to open it"
        case .done:
            return incoming ? "From \(transfer.peerName) · \(formatBytes(transfer.totalBytes))"
                : "Delivered to \(transfer.peerName)"
        case .failed(let message):
            return message
        }
    }
}

enum OrbPhase: Equatable {
    case waiting
    case progress(Double)
    case done
    case failed
}

/// The device avatar wrapped in a progress ring. Ripples radiate while waiting; on success the
/// ring closes, the core turns green, a checkmark draws itself and a ring of sparks bursts out.
struct DropOrb: View {
    let platform: String
    let phase: OrbPhase

    @State private var check: CGFloat = 0
    @State private var burst = false
    @State private var pop: CGFloat = 1

    private static let green = Color(red: 0.20, green: 0.78, blue: 0.35)
    private let ring: CGFloat = 112

    var body: some View {
        ZStack {
            if phase == .waiting {
                ForEach(0..<3, id: \.self) { RippleRing(delay: Double($0) * 0.8, diameter: ring) }
                SpinningArc(diameter: ring)
            }
            Circle()
                .stroke(.primary.opacity(0.1), lineWidth: 6)
                .frame(width: ring, height: ring)
            Circle()
                .trim(from: 0, to: progress)
                .stroke(ringStyle, style: StrokeStyle(lineWidth: 6, lineCap: .round))
                .rotationEffect(.degrees(-90))
                .frame(width: ring, height: ring)
                .animation(.spring(response: 0.5, dampingFraction: 0.9), value: progress)
            Circle()
                .fill(coreStyle)
                .frame(width: ring - 22, height: ring - 22)
                .scaleEffect(pop)
                .animation(.easeInOut(duration: 0.35), value: phase)
            ForEach(0..<14, id: \.self) { i in
                Circle()
                    .fill(i.isMultiple(of: 2) ? Self.green : Color.accentColor)
                    .frame(width: 7, height: 7)
                    .offset(x: burst ? ring * 0.95 : ring * 0.5)
                    .rotationEffect(.degrees(Double(i) * 360 / 14 + 8))
                    .opacity(burst ? 0 : (phase == .done ? 1 : 0))
            }
            switch phase {
            case .done:
                Checkmark()
                    .trim(from: 0, to: check)
                    .stroke(.white, style: StrokeStyle(lineWidth: 7, lineCap: .round, lineJoin: .round))
                    .frame(width: 44, height: 36)
            case .failed:
                Image(systemName: "exclamationmark")
                    .font(.system(size: 38, weight: .bold))
                    .foregroundStyle(.white)
            default:
                Image(systemName: platform == "android" ? "iphone.gen3" : "laptopcomputer")
                    .font(.system(size: 38, weight: .medium))
                    .foregroundStyle(Color.accentColor)
            }
        }
        .frame(width: 180, height: 170)
        .onAppear { if phase == .done { celebrate() } }
        .onChange(of: phase == .done) { _, done in if done { celebrate() } }
    }

    private var progress: CGFloat {
        switch phase {
        case .waiting: 0
        case .progress(let fraction): CGFloat(min(max(fraction, 0), 1))
        case .done, .failed: 1
        }
    }

    private var ringStyle: AnyShapeStyle {
        switch phase {
        case .done: AnyShapeStyle(Self.green)
        case .failed: AnyShapeStyle(.red)
        default: AnyShapeStyle(AngularGradient(colors: [.accentColor, .purple, .accentColor], center: .center,
                                               startAngle: .degrees(0), endAngle: .degrees(360)))
        }
    }

    private var coreStyle: AnyShapeStyle {
        switch phase {
        case .done: AnyShapeStyle(Self.green.gradient)
        case .failed: AnyShapeStyle(Color.red.gradient)
        default: AnyShapeStyle(Color.accentColor.opacity(0.15))
        }
    }

    private func celebrate() {
        check = 0
        burst = false
        pop = 0.85
        withAnimation(.spring(response: 0.45, dampingFraction: 0.45)) { pop = 1 }
        withAnimation(.easeOut(duration: 0.9)) { burst = true }
        withAnimation(.easeInOut(duration: 0.4).delay(0.12)) { check = 1 }
    }
}

private struct RippleRing: View {
    let delay: Double
    let diameter: CGFloat
    @State private var animate = false

    var body: some View {
        Circle()
            .stroke(Color.accentColor.opacity(0.6), lineWidth: 2)
            .frame(width: diameter, height: diameter)
            .scaleEffect(animate ? 1.55 : 1)
            .opacity(animate ? 0 : 0.8)
            .onAppear {
                withAnimation(.easeOut(duration: 2.4).repeatForever(autoreverses: false).delay(delay)) { animate = true }
            }
    }
}

private struct SpinningArc: View {
    let diameter: CGFloat
    @State private var spin = false

    var body: some View {
        Circle()
            .trim(from: 0, to: 0.2)
            .stroke(Color.accentColor, style: StrokeStyle(lineWidth: 6, lineCap: .round))
            .frame(width: diameter, height: diameter)
            .rotationEffect(.degrees(spin ? 360 : 0))
            .onAppear {
                withAnimation(.linear(duration: 1.1).repeatForever(autoreverses: false)) { spin = true }
            }
    }
}

private struct Checkmark: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.move(to: CGPoint(x: rect.minX, y: rect.midY + rect.height * 0.05))
        path.addLine(to: CGPoint(x: rect.minX + rect.width * 0.36, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.minY))
        return path
    }
}
