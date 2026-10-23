import AppKit
import AVFoundation
import CoreMedia
import BubbleDropCore

/// Shows a paired phone's screen in its own window while it mirrors to this Mac.
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
    private let displayLayer = AVSampleBufferDisplayLayer()
    private let stream: MirrorStream
    private var lastSize: CGSize?

    init(title: String, stream: MirrorStream) {
        self.stream = stream
        renderer = displayLayer.sampleBufferRenderer
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

        let content = NSView()
        content.wantsLayer = true
        content.layer?.backgroundColor = NSColor.black.cgColor
        displayLayer.videoGravity = .resizeAspect
        displayLayer.frame = content.bounds
        displayLayer.autoresizingMask = [.layerWidthSizable, .layerHeightSizable]
        content.layer?.addSublayer(displayLayer)
        window.contentView = content

        window.center()
        NSApp.activate(ignoringOtherApps: true)
        window.makeKeyAndOrderFront(nil)
    }

    /// Resize to the phone's aspect ratio (it changes when the phone rotates), keeping the height.
    func fit(to size: CGSize) {
        guard size != lastSize, size.width > 0, size.height > 0 else { return }
        lastSize = size
        window.contentAspectRatio = size
        let visible = window.screen?.visibleFrame ?? NSScreen.main?.visibleFrame ?? .zero
        var frame = window.frame
        let maxHeight = visible.height * 0.85
        let maxWidth = visible.width * 0.85
        var height = min(maxHeight, size.height >= size.width ? 860 : 560)
        var width = height * size.width / size.height
        if width > maxWidth {
            width = maxWidth
            height = width * size.height / size.width
        }
        let center = CGPoint(x: frame.midX, y: frame.midY)
        frame.size = window.frameRect(forContentRect: NSRect(origin: .zero, size: CGSize(width: width, height: height))).size
        frame.origin = CGPoint(x: center.x - frame.width / 2, y: center.y - frame.height / 2)
        window.setFrame(frame, display: true, animate: true)
    }

    /// The stream ended; close the window if it's still open.
    func finish() {
        window.delegate = nil
        window.close()
    }

    func windowWillClose(_ notification: Notification) {
        stream.close()
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
