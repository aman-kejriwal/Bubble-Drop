import AppKit
import AVFoundation
import CoreAudio

/// Plays a ringtone through the Mac's built-in speakers, even when AirPods or other Bluetooth
/// audio is the system's output. `NSSound` always uses the current default output, so this drives
/// an audio engine bound to the built-in output device instead.
@MainActor
final class RingtonePlayer {
    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    /// Used only when the Mac has no built-in output or the file won't decode for the engine.
    private var fallback: NSSound?

    init() {
        engine.attach(player)
    }

    /// Plays `url`, looping if asked. Returns false when nothing could play it.
    @discardableResult
    func play(_ url: URL, loops: Bool) -> Bool {
        stop()
        if let buffer = Self.buffer(for: url),
           let device = Self.builtInOutputDevice(),
           start(device: device, format: buffer.format) {
            player.scheduleBuffer(buffer, at: nil, options: loops ? .loops : [], completionHandler: nil)
            player.play()
            return true
        }
        let sound = NSSound(contentsOf: url, byReference: true)
        sound?.loops = loops
        fallback = sound
        return sound?.play() ?? false
    }

    func stop() {
        player.stop()
        engine.stop()
        fallback?.stop()
        fallback = nil
    }

    private func start(device: AudioDeviceID, format: AVAudioFormat) -> Bool {
        let unit: AudioUnit? = engine.outputNode.audioUnit
        guard let unit else { return false }
        var id = device
        guard AudioUnitSetProperty(unit, kAudioOutputUnitProperty_CurrentDevice, kAudioUnitScope_Global, 0,
                                   &id, UInt32(MemoryLayout<AudioDeviceID>.size)) == noErr else { return false }
        engine.connect(player, to: engine.mainMixerNode, format: format)
        do {
            try engine.start()
            return true
        } catch {
            return false
        }
    }

    private static func buffer(for url: URL) -> AVAudioPCMBuffer? {
        guard let file = try? AVAudioFile(forReading: url),
              let buffer = AVAudioPCMBuffer(pcmFormat: file.processingFormat,
                                            frameCapacity: AVAudioFrameCount(file.length)) else { return nil }
        do {
            try file.read(into: buffer)
            return buffer
        } catch {
            return nil
        }
    }

    /// The Mac's built-in speakers (or headphone jack), found by transport type rather than name.
    static func builtInOutputDevice() -> AudioDeviceID? {
        let system = AudioObjectID(kAudioObjectSystemObject)
        var listAddress = AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyDevices,
                                                     mScope: kAudioObjectPropertyScopeGlobal,
                                                     mElement: kAudioObjectPropertyElementMain)
        var size: UInt32 = 0
        guard AudioObjectGetPropertyDataSize(system, &listAddress, 0, nil, &size) == noErr, size > 0 else { return nil }
        var devices = [AudioDeviceID](repeating: 0, count: Int(size) / MemoryLayout<AudioDeviceID>.size)
        guard AudioObjectGetPropertyData(system, &listAddress, 0, nil, &size, &devices) == noErr else { return nil }
        return devices.first { isBuiltIn($0) && hasOutput($0) }
    }

    private static func isBuiltIn(_ device: AudioDeviceID) -> Bool {
        var address = AudioObjectPropertyAddress(mSelector: kAudioDevicePropertyTransportType,
                                                 mScope: kAudioObjectPropertyScopeGlobal,
                                                 mElement: kAudioObjectPropertyElementMain)
        var transport: UInt32 = 0
        var size = UInt32(MemoryLayout<UInt32>.size)
        guard AudioObjectGetPropertyData(device, &address, 0, nil, &size, &transport) == noErr else { return false }
        return transport == kAudioDeviceTransportTypeBuiltIn
    }

    private static func hasOutput(_ device: AudioDeviceID) -> Bool {
        var address = AudioObjectPropertyAddress(mSelector: kAudioDevicePropertyStreams,
                                                 mScope: kAudioObjectPropertyScopeOutput,
                                                 mElement: kAudioObjectPropertyElementMain)
        var size: UInt32 = 0
        return AudioObjectGetPropertyDataSize(device, &address, 0, nil, &size) == noErr && size > 0
    }
}
