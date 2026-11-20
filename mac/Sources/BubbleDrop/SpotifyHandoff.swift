import AppKit
import BubbleDropCore

/// Carries on playing on this Mac what the phone was playing in Spotify (PROTOCOL.md
/// `/spotify/play`). The phone pauses before it asks, so the Mac picks up at the same second:
/// Spotify for Mac starts the track muted, jumps to the phone's position, then unmutes, so the
/// start of the track is never heard. Without Spotify for Mac, the web player opens instead.
@MainActor
enum SpotifyHandoff {
    private static let bundleId = "com.spotify.client"
    private static var busy = false
    private static let volumeKey = "spotifyHandoffVolume"

    /// The volume to put back after the muted start. Spotify can report 0 while it's still starting
    /// up, and putting that back would leave it silent, so a near-zero reading falls back to the
    /// last good volume.
    private static func volumeToRestore(_ read: Int) -> Int {
        if read >= 10 {
            UserDefaults.standard.set(read, forKey: volumeKey)
            return read
        }
        let saved = UserDefaults.standard.integer(forKey: volumeKey)
        return saved >= 10 ? saved : 80
    }

    static func play(_ request: SpotifyPlayRequest, from sender: DeviceInfo) async -> SpotifyPlayResponse {
        let art = request.art.flatMap { Data(base64Encoded: $0) }.flatMap(NSImage.init(data:))
        let stage = HandoffStage()
        HandoffOverlay.present(
            HandoffTrack(title: request.title, artist: request.artist, art: art, phoneName: sender.name),
            stage: stage)

        // One at a time: a second handoff must never read the volume the first one muted.
        while busy { try? await Task.sleep(nanoseconds: 100_000_000) }
        busy = true
        let response = await start(request)
        busy = false
        stage.finish(response.playing ? .playing
                     : response.app == "none" ? .failed(response.message ?? "Couldn't play it here")
                     : .opened(response.message ?? "Opened in Spotify"))
        return response
    }

    private static func start(_ request: SpotifyPlayRequest) async -> SpotifyPlayResponse {
        let app = NSWorkspace.shared.urlForApplication(withBundleIdentifier: bundleId)
        guard let uri = request.validURI else {
            // Nothing to play by name: search for it, in the app if there is one.
            let query = "\(request.title) \(request.artist)".trimmingCharacters(in: .whitespaces)
            let encoded = query.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? ""
            let url = app != nil ? URL(string: "spotify:search:\(encoded)") : URL(string: "https://open.spotify.com/search/\(encoded)")
            if let url { NSWorkspace.shared.open(url) }
            return SpotifyPlayResponse(playing: false, app: "search", message: "Searched Spotify for it")
        }
        let parts = uri.split(separator: ":")
        let webURL = URL(string: "https://open.spotify.com/\(parts[1])/\(parts[2])")!
        guard app != nil else {
            NSWorkspace.shared.open(webURL)
            return SpotifyPlayResponse(playing: false, app: "web", message: "Opened in the Spotify web player")
        }

        // Starts the track muted and reports the volume to put back once it's at the right second.
        let startScript = """
        on run argv
            set u to item 1 of argv
            tell application "Spotify"
                repeat 40 times
                    try
                        get player state
                        exit repeat
                    end try
                    delay 0.25
                end repeat
                set v to sound volume
                set sound volume to 0
                set ok to false
                repeat with attempt from 1 to 20
                    try
                        play track u
                        delay 0.25
                        if player state is playing and (attempt > 3 or (id of current track) is u) then
                            set ok to true
                            exit repeat
                        end if
                    end try
                    delay 0.25
                end repeat
                if not ok then
                    set sound volume to v
                    error "Spotify didn't start the track."
                end if
            end tell
            return v
        end run
        """
        let started = await osascript(startScript, arguments: [uri])
        guard started.ok, let read = Int(started.output.trimmingCharacters(in: .whitespacesAndNewlines)) else {
            if started.error.contains("-1743") {
                // Not allowed to control Spotify: show the track there at least.
                NSWorkspace.shared.open(URL(string: uri)!)
                return SpotifyPlayResponse(playing: false, app: "spotify",
                    message: "Allow BubbleDrop to control Spotify in System Settings › Privacy & Security › Automation")
            }
            NSWorkspace.shared.open(webURL)
            return SpotifyPlayResponse(playing: false, app: "web", message: "Opened in the Spotify web player")
        }

        let volume = volumeToRestore(read)
        let seekScript = """
        on run argv
            tell application "Spotify"
                try
                    set player position to ((item 1 of argv) as integer) / 1000
                end try
                delay 0.15
                set sound volume to ((item 2 of argv) as integer)
            end tell
        end run
        """
        let positionMs = request.durationMs > 0 ? min(request.positionMs, max(request.durationMs - 1500, 0)) : request.positionMs
        let seeked = await osascript(seekScript, arguments: [String(max(positionMs, 0)), String(volume)])
        if !seeked.ok {
            // Never leave Spotify muted.
            _ = await osascript("on run argv\ntell application \"Spotify\" to set sound volume to ((item 1 of argv) as integer)\nend run",
                                arguments: [String(volume)])
        }
        return SpotifyPlayResponse(playing: true, app: "spotify")
    }

    /// Runs AppleScript with `argv`, so values are never pasted into the script's text.
    private static func osascript(_ source: String, arguments: [String]) async -> (ok: Bool, output: String, error: String) {
        await withCheckedContinuation { continuation in
            let process = Process()
            process.executableURL = URL(fileURLWithPath: "/usr/bin/osascript")
            process.arguments = ["-e", source] + arguments
            let out = Pipe(), err = Pipe()
            process.standardOutput = out
            process.standardError = err
            process.terminationHandler = { process in
                let output = String(decoding: out.fileHandleForReading.readDataToEndOfFile(), as: UTF8.self)
                let error = String(decoding: err.fileHandleForReading.readDataToEndOfFile(), as: UTF8.self)
                continuation.resume(returning: (process.terminationStatus == 0, output, error))
            }
            do {
                try process.run()
            } catch {
                continuation.resume(returning: (false, "", error.localizedDescription))
            }
        }
    }
}
