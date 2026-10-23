import AppKit
import AVFoundation
import CoreMedia
import SwiftUI
import BubbleDropCore

/// Shows a paired phone's screen in its own window while it mirrors to this Mac, and sends the
/// mouse and keyboard back to it when the phone allows remote control.
enum MirrorViewer {
    /// Runs until the phone stops or the window is closed.
    static func run(sender: DeviceInfo, stream: MirrorStream) async {
        let window = await MainActor.run { MirrorWindowController(title: sender.name, stream: stream) }
        let decoder = H264Decoder(renderer: window.renderer) { size in
            Task { @MainActor in window.fit(to: size) }
        }
        do {
            while let packet = try await stream.next() {
                switch packet {
                case .config(let data): decoder.configure(annexB: data)
                case .frame(let data, let pts): decoder.decode(annexB: data, presentationTimeUs: pts)
                case .controlAvailable(let on): await MainActor.run { window.setControl(on) }
                }
            }
        } catch {
            // Window closed, phone stopped, or the connection dropped: all end the session.
        }
        await MainActor.run { window.finish() }
    }
}

@MainActor
private final class MirrorWindowController: NSObject, NSWindowDelegate {
    nonisolated let renderer: AVSampleBufferVideoRenderer
    private let window: NSWindow
    private let input: MirrorInputView
    private let stream: MirrorStream
    private var lastSize: CGSize?

    init(title: String, stream: MirrorStream) {
        self.stream = stream
        input = MirrorInputView(controlEnabled: stream.controlAvailable) { stream.send(input: $0) }
        renderer = input.displayLayer.sampleBufferRenderer
        let height = min((NSScreen.main?.visibleFrame.height ?? 900) * 0.8, 860)
        window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: height * 9 / 19.5, height: height),
                          styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView],
                          backing: .buffered, defer: false)
        super.init()
        window.title = title
        window.titlebarAppearsTransparent = true
        window.backgroundColor = .black
        window.isReleasedWhenClosed = false
        window.delegate = self
        window.collectionBehavior = [.fullScreenPrimary]
        window.acceptsMouseMovedEvents = true
        window.contentView = input
        window.center()
        NSApp.activate(ignoringOtherApps: true)
        window.makeKeyAndOrderFront(nil)
        window.makeFirstResponder(input)
    }

    /// Resize to the phone's aspect ratio (it changes when the phone rotates), keeping the height.
    func fit(to size: CGSize) {
        guard size != lastSize, size.width > 0, size.height > 0 else { return }
        lastSize = size
        input.videoSize = size
        window.contentAspectRatio = size
        let visible = window.screen?.visibleFrame ?? NSScreen.main?.visibleFrame ?? .zero
        var frame = window.frame
        var height = min(visible.height * 0.85, size.height >= size.width ? 860 : 560)
        var width = height * size.width / size.height
        if width > visible.width * 0.85 {
            width = visible.width * 0.85
            height = width * size.height / size.width
        }
        let center = CGPoint(x: frame.midX, y: frame.midY)
        frame.size = window.frameRect(forContentRect: NSRect(origin: .zero, size: CGSize(width: width, height: height))).size
        frame.origin = CGPoint(x: center.x - frame.width / 2, y: center.y - frame.height / 2)
        window.setFrame(frame, display: true, animate: true)
    }

    func setControl(_ on: Bool) {
        input.controlEnabled = on
    }

    /// Windows whose session ended, kept alive briefly: AppKit may still deliver tracking-area
    /// updates to a window in the run loop pass after it closes, and freeing it then crashes.
    private static var closing: [MirrorWindowController] = []

    /// The stream ended; close the window if it's still open.
    func finish() {
        window.delegate = nil
        input.trackingAreas.forEach(input.removeTrackingArea)
        window.orderOut(nil)
        window.close()
        Self.closing.append(self)
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
            MainActor.assumeIsolated { Self.closing.removeAll { $0 === self } }
        }
    }

    func windowWillClose(_ notification: Notification) {
        stream.close()
    }
}

/// Shows the video and turns mouse, trackpad and keyboard input into phone input events with
/// coordinates as fractions (0–1) of the phone screen.
private final class MirrorInputView: NSView {
    let displayLayer = AVSampleBufferDisplayLayer()
    var videoSize: CGSize = .zero
    var controlEnabled: Bool {
        didSet { controls.rootView = MirrorControls(enabled: controlEnabled, send: send) }
    }
    private let send: ([String: Any]) -> Void
    private let controls: NSHostingView<MirrorControls>

    private var stroke: [CGPoint] = []
    private var strokeStart: TimeInterval = 0
    private var scrollAnchor: CGPoint?
    private var scrollDelta = CGSize.zero
    private var scrollStart: TimeInterval = 0
    private var wheelFlush: DispatchWorkItem?

    init(controlEnabled: Bool, send: @escaping ([String: Any]) -> Void) {
        self.controlEnabled = controlEnabled
        self.send = send
        controls = NSHostingView(rootView: MirrorControls(enabled: controlEnabled, send: send))
        super.init(frame: .zero)
        wantsLayer = true
        layer?.backgroundColor = NSColor.black.cgColor
        displayLayer.videoGravity = .resizeAspect
        displayLayer.autoresizingMask = [.layerWidthSizable, .layerHeightSizable]
        layer?.addSublayer(displayLayer)
        controls.alphaValue = 0
        addSubview(controls)
        addTrackingArea(NSTrackingArea(rect: .zero, options: [.mouseEnteredAndExited, .activeAlways, .inVisibleRect],
                                       owner: self))
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    override var acceptsFirstResponder: Bool { true }
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }

    override func layout() {
        super.layout()
        displayLayer.frame = bounds
        let size = controls.fittingSize
        controls.frame = NSRect(x: (bounds.width - size.width) / 2, y: 14, width: size.width, height: size.height)
    }

    override func mouseEntered(with event: NSEvent) {
        NSAnimationContext.runAnimationGroup { $0.duration = 0.2; controls.animator().alphaValue = 1 }
    }

    override func mouseExited(with event: NSEvent) {
        NSAnimationContext.runAnimationGroup { $0.duration = 0.3; controls.animator().alphaValue = 0 }
    }

    // MARK: Coordinates

    private var videoRect: CGRect {
        videoSize == .zero ? .zero : AVMakeRect(aspectRatio: videoSize, insideRect: bounds)
    }

    /// The event's position as a fraction of the phone screen (y down), clamped when [clamp].
    private func phonePoint(_ event: NSEvent, clamp: Bool = false) -> CGPoint? {
        let rect = videoRect
        guard rect.width > 0 else { return nil }
        let p = convert(event.locationInWindow, from: nil)
        guard clamp || rect.contains(p) else { return nil }
        let x = (p.x - rect.minX) / rect.width
        let y = 1 - (p.y - rect.minY) / rect.height
        return CGPoint(x: min(max(x, 0), 1), y: min(max(y, 0), 1))
    }

    private func json(_ p: CGPoint) -> [Double] {
        [(Double(p.x) * 10_000).rounded() / 10_000, (Double(p.y) * 10_000).rounded() / 10_000]
    }

    // MARK: Mouse

    override func mouseDown(with event: NSEvent) {
        guard controlEnabled, let p = phonePoint(event) else { return }
        stroke = [p]
        strokeStart = event.timestamp
    }

    override func mouseDragged(with event: NSEvent) {
        guard !stroke.isEmpty, let p = phonePoint(event, clamp: true) else { return }
        stroke.append(p)
    }

    override func mouseUp(with event: NSEvent) {
        guard let first = stroke.first else { return }
        if let p = phonePoint(event, clamp: true) { stroke.append(p) }
        let duration = event.timestamp - strokeStart
        let rect = videoRect
        let moved = stroke.map { hypot(($0.x - first.x) * rect.width, ($0.y - first.y) * rect.height) }.max() ?? 0
        if moved < 6 {
            // A held click is a long press.
            send(["t": "tap", "x": json(first)[0], "y": json(first)[1], "ms": duration > 0.45 ? 650 : 50])
        } else {
            let step = max(1, stroke.count / 60)
            var points = stride(from: 0, to: stroke.count, by: step).map { stroke[$0] }
            if points.last != stroke.last, let last = stroke.last { points.append(last) }
            send(["t": "swipe", "p": points.map(json), "ms": Int(min(max(duration, 0.08), 5) * 1000)])
        }
        stroke = []
    }

    override func rightMouseDown(with event: NSEvent) {
        if controlEnabled { send(["t": "key", "k": "back"]) }
    }

    override func otherMouseDown(with event: NSEvent) {
        if controlEnabled { send(["t": "key", "k": "home"]) }
    }

    // MARK: Scrolling

    override func scrollWheel(with event: NSEvent) {
        guard controlEnabled, event.momentumPhase.isEmpty else { return }
        if event.phase.contains(.began) || scrollAnchor == nil {
            scrollAnchor = phonePoint(event, clamp: true) ?? CGPoint(x: 0.5, y: 0.5)
            scrollDelta = .zero
            scrollStart = event.timestamp
        }
        let scale: CGFloat = event.hasPreciseScrollingDeltas ? 1.5 : 12
        scrollDelta.width += event.scrollingDeltaX * scale
        scrollDelta.height += event.scrollingDeltaY * scale

        if event.phase.contains(.ended) || event.phase.contains(.cancelled) {
            flushScroll(duration: event.timestamp - scrollStart)
        } else if event.phase.isEmpty {
            // A mouse wheel has no phases: send what built up once the clicks pause.
            wheelFlush?.cancel()
            let flush = DispatchWorkItem { [weak self] in self?.flushScroll(duration: 0.15) }
            wheelFlush = flush
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.08, execute: flush)
        }
    }

    /// One swipe for the whole scroll gesture, as fast as the fingers moved, so the phone flings naturally.
    private func flushScroll(duration: TimeInterval) {
        defer { scrollAnchor = nil }
        guard let anchor = scrollAnchor, videoRect.width > 0 else { return }
        // Content follows the fingers, so the swipe goes the same way as the scroll delta.
        let end = CGPoint(x: min(max(anchor.x + scrollDelta.width / videoRect.width, 0), 1),
                          y: min(max(anchor.y + scrollDelta.height / videoRect.height, 0), 1))
        guard hypot(end.x - anchor.x, end.y - anchor.y) > 0.01 else { return }
        send(["t": "swipe", "p": [json(anchor), json(end)], "ms": Int(min(max(duration, 0.08), 1) * 1000)])
    }

    // MARK: Keyboard

    override func keyDown(with event: NSEvent) {
        guard controlEnabled, !event.modifierFlags.contains(.command) else { return super.keyDown(with: event) }
        switch event.keyCode {
        case 53: send(["t": "key", "k": "back"])           // Esc
        case 36, 76: send(["t": "key", "k": "enter"])      // Return, Enter
        case 51: send(["t": "key", "k": "backspace"])      // Delete
        default:
            guard let text = event.characters, !text.isEmpty,
                  text.unicodeScalars.allSatisfy({ !CharacterSet.controlCharacters.contains($0) && $0.value < 0xF700 })
            else { return super.keyDown(with: event) }
            send(["t": "text", "s": text])
        }
    }
}

/// Back / Home / Recents, shown while the pointer is over the window.
private struct MirrorControls: View {
    let enabled: Bool
    let send: ([String: Any]) -> Void

    var body: some View {
        Group {
            if enabled {
                HStack(spacing: 4) {
                    button("chevron.backward", "Back (Esc or right-click)", key: "back")
                    button("circle", "Home", key: "home")
                    button("square.on.square", "Recent apps", key: "recents")
                }
            } else {
                Text("Turn on BubbleDrop remote control on the phone to use your mouse")
                    .font(.caption)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 220)
                    .padding(.horizontal, 6)
            }
        }
        .padding(6)
        .background(.ultraThinMaterial, in: Capsule())
        .environment(\.colorScheme, .dark)
    }

    private func button(_ symbol: String, _ help: String, key: String) -> some View {
        Button { send(["t": "key", "k": key]) } label: {
            Image(systemName: symbol)
                .font(.system(size: 15, weight: .semibold))
                .frame(width: 40, height: 30)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .foregroundStyle(.white)
        .help(help)
    }
}

/// Turns the phone's Annex-B H.264 into sample buffers for the display layer, which decodes them
/// in hardware.
private final class H264Decoder: @unchecked Sendable {
    private let renderer: AVSampleBufferVideoRenderer
    private let onSize: (CGSize) -> Void
    private var format: CMVideoFormatDescription?
    private var sps: Data?
    private var pps: Data?

    init(renderer: AVSampleBufferVideoRenderer, onSize: @escaping (CGSize) -> Void) {
        self.renderer = renderer
        self.onSize = onSize
    }

    func configure(annexB: Data) {
        for nal in Self.nalUnits(annexB) { take(parameterSet: nal) }
        rebuildFormat()
    }

    func decode(annexB: Data, presentationTimeUs: Int64) {
        var avcc = Data()
        var changed = false
        for nal in Self.nalUnits(annexB) {
            if take(parameterSet: nal) {
                changed = true
                continue
            }
            var length = UInt32(nal.count).bigEndian
            avcc.append(Data(bytes: &length, count: 4))
            avcc.append(nal)
        }
        if changed { rebuildFormat() }
        guard let format, !avcc.isEmpty, let sample = Self.sampleBuffer(avcc, format: format, ptsUs: presentationTimeUs) else {
            return
        }
        if renderer.status == .failed { renderer.flush() }
        renderer.enqueue(sample)
    }

    /// Records SPS (7) / PPS (8); returns whether [nal] was one.
    @discardableResult
    private func take(parameterSet nal: Data) -> Bool {
        guard let header = nal.first else { return false }
        switch header & 0x1f {
        case 7: sps = nal
        case 8: pps = nal
        default: return false
        }
        return true
    }

    private func rebuildFormat() {
        guard let sps, let pps else { return }
        var newFormat: CMVideoFormatDescription?
        let status = sps.withUnsafeBytes { spsBytes in
            pps.withUnsafeBytes { ppsBytes in
                let pointers = [spsBytes.bindMemory(to: UInt8.self).baseAddress!,
                                ppsBytes.bindMemory(to: UInt8.self).baseAddress!]
                let sizes = [sps.count, pps.count]
                return CMVideoFormatDescriptionCreateFromH264ParameterSets(
                    allocator: kCFAllocatorDefault, parameterSetCount: 2, parameterSetPointers: pointers,
                    parameterSetSizes: sizes, nalUnitHeaderLength: 4, formatDescriptionOut: &newFormat)
            }
        }
        guard status == noErr, let newFormat else { return }
        if let format, CMFormatDescriptionEqual(format, otherFormatDescription: newFormat) { return }
        format = newFormat
        renderer.flush()
        let dimensions = CMVideoFormatDescriptionGetPresentationDimensions(newFormat, usePixelAspectRatio: true,
                                                                         useCleanAperture: true)
        onSize(dimensions)
    }

    private static func sampleBuffer(_ avcc: Data, format: CMVideoFormatDescription, ptsUs: Int64) -> CMSampleBuffer? {
        var block: CMBlockBuffer?
        guard CMBlockBufferCreateWithMemoryBlock(allocator: kCFAllocatorDefault, memoryBlock: nil,
                                                 blockLength: avcc.count, blockAllocator: kCFAllocatorDefault,
                                                 customBlockSource: nil, offsetToData: 0, dataLength: avcc.count,
                                                 flags: kCMBlockBufferAssureMemoryNowFlag, blockBufferOut: &block) == noErr,
              let block else { return nil }
        let copied = avcc.withUnsafeBytes {
            CMBlockBufferReplaceDataBytes(with: $0.baseAddress!, blockBuffer: block, offsetIntoDestination: 0,
                                          dataLength: avcc.count)
        }
        guard copied == noErr else { return nil }
        var timing = CMSampleTimingInfo(duration: .invalid,
                                        presentationTimeStamp: CMTime(value: ptsUs, timescale: 1_000_000),
                                        decodeTimeStamp: .invalid)
        var size = avcc.count
        var sample: CMSampleBuffer?
        guard CMSampleBufferCreateReady(allocator: kCFAllocatorDefault, dataBuffer: block, formatDescription: format,
                                        sampleCount: 1, sampleTimingEntryCount: 1, sampleTimingArray: &timing,
                                        sampleSizeEntryCount: 1, sampleSizeArray: &size,
                                        sampleBufferOut: &sample) == noErr,
              let sample else { return nil }
        // Show each frame as soon as it's decoded rather than scheduling it on a clock.
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: true),
           CFArrayGetCount(attachments) > 0 {
            let dict = unsafeBitCast(CFArrayGetValueAtIndex(attachments, 0), to: CFMutableDictionary.self)
            CFDictionarySetValue(dict, Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(),
                                 Unmanaged.passUnretained(kCFBooleanTrue).toOpaque())
        }
        return sample
    }

    /// Splits Annex-B data on 00 00 01 / 00 00 00 01 start codes.
    static func nalUnits(_ data: Data) -> [Data] {
        let bytes = [UInt8](data)
        var units: [Data] = []
        var start: Int?
        var i = 0
        while i + 2 < bytes.count {
            if bytes[i] == 0, bytes[i + 1] == 0, bytes[i + 2] == 1 {
                if let s = start { units.append(Data(trimmingZeros(bytes[s..<i]))) }
                i += 3
                start = i
            } else {
                i += 1
            }
        }
        if let s = start, s < bytes.count { units.append(Data(bytes[s...])) }
        return units.filter { !$0.isEmpty }
    }

    /// The zero byte that leads a 4-byte start code belongs to the next code, not this unit.
    private static func trimmingZeros(_ slice: ArraySlice<UInt8>) -> ArraySlice<UInt8> {
        var end = slice.endIndex
        while end > slice.startIndex, slice[end - 1] == 0 { end -= 1 }
        return slice[slice.startIndex..<end]
    }
}
