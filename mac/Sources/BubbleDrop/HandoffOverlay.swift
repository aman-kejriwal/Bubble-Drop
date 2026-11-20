import AppKit
import SwiftUI

// The show that plays when a song moves from the phone to this Mac.
//
// 1. The cover arrives as a swarm of its own pixels: each pixel of the album art streams up from
//    the bottom of the screen (where the phone is), swirls, and lands in its place in the cover.
// 2. The mosaic flashes solid, a shockwave rolls off it, and a vinyl record slides out from
//    behind the cover, spinning, with the cover as its label.
// 3. A ring of bars breathes to the beat around the record and rings pulse off it, in the cover's
//    own colours, while the title writes itself in letter by letter.
// 4. The cover shatters back into pixels that stream into the menu bar, and the screen clears.
//
// Everything is drawn from one clock (seconds since the start) so it never stutters, and the
// window ignores the mouse: the Mac stays fully usable while it plays.

struct HandoffTrack {
    let title: String
    let artist: String
    let art: NSImage?
    let phoneName: String
}

/// How the playback on this Mac turned out; the overlay waits for it before leaving.
@MainActor
final class HandoffStage: ObservableObject {
    enum Outcome: Equatable {
        case playing
        case opened(String)
        case failed(String)
    }

    @Published private(set) var outcome: Outcome?
    private(set) var finishedAt: Date?

    func finish(_ outcome: Outcome) {
        self.outcome = outcome
        finishedAt = Date()
    }
}

@MainActor
enum HandoffOverlay {
    private static var panel: NSPanel?
    private static var generation = 0

    static func present(_ track: HandoffTrack, stage: HandoffStage) {
        panel?.orderOut(nil)
        panel = nil
        generation += 1
        let mine = generation
        guard let screen = NSScreen.main ?? NSScreen.screens.first else { return }

        let panel = NSPanel(contentRect: screen.frame, styleMask: [.borderless, .nonactivatingPanel],
                            backing: .buffered, defer: false)
        panel.level = NSWindow.Level(rawValue: NSWindow.Level.statusBar.rawValue + 1)
        panel.backgroundColor = .clear
        panel.isOpaque = false
        panel.hasShadow = false
        panel.ignoresMouseEvents = true
        panel.hidesOnDeactivate = false
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary, .ignoresCycle]
        let start = Date()
        let scene = HandoffScene(track: track,
                                 reduceMotion: NSWorkspace.shared.accessibilityDisplayShouldReduceMotion)
        panel.contentView = NSHostingView(rootView: HandoffView(track: track, scene: scene, start: start)
            .environmentObject(stage))
        panel.setFrame(screen.frame, display: true)
        panel.orderFrontRegardless()
        self.panel = panel

        Task { @MainActor in
            while true {
                try? await Task.sleep(nanoseconds: 200_000_000)
                guard generation == mine else { return }
                let now = Date().timeIntervalSince(start)
                let finished = stage.finishedAt.map { $0.timeIntervalSince(start) }
                if now > HandoffTiming.exitStart(finished: finished) + HandoffTiming.exitLength + 0.1 { break }
            }
            self.panel?.orderOut(nil)
            self.panel = nil
        }
    }
}

// MARK: - Timing

enum HandoffTiming {
    static let backdropIn = 0.45
    static let flightStart = 0.12
    static let flight = 1.05
    static let solidify = 1.58
    static let vinyl = 1.78
    static let text = 1.95
    static let beat = 60.0 / 112.0
    static let exitLength = 1.25

    /// The show holds until playback has started (or failed), so its outcome can be read.
    static func exitStart(finished: Double?) -> Double {
        guard let finished else { return 18 }
        return min(max(6.4, finished + 2.6), 18)
    }
}

// MARK: - Scene (computed once per handoff)

private struct RGB {
    var r, g, b: Double
    var color: Color { Color(red: r, green: g, blue: b) }
    func mix(_ o: RGB, _ t: Double) -> RGB { RGB(r: r + (o.r - r) * t, g: g + (o.g - g) * t, b: b + (o.b - b) * t) }
    var saturation: Double {
        let mx = max(r, g, b), mn = min(r, g, b)
        return mx == 0 ? 0 : (mx - mn) / mx
    }
    var brightness: Double { max(r, g, b) }
    /// The same hue, pushed to a vivid, bright version for glows.
    var vivid: RGB {
        let mx = max(r, g, b, 0.001), mn = min(r, g, b)
        let spread = max(mx - mn, 0.001)
        func f(_ c: Double) -> Double { 0.25 + 0.75 * (c - mn) / spread }
        return mx - mn < 0.06 ? RGB(r: 0.55, g: 0.6, b: 0.75) : RGB(r: f(r), g: f(g), b: f(b))
    }
}

private struct Particle {
    let gx: Int, gy: Int
    let color: RGB
    let launch: CGPoint     // offset from the launch point, in points
    let delay: Double
    let swirl: Double       // which way, and how hard, the path bends
    let spin: Double        // turns of the final spiral into place
    let exitDelay: Double
    let exitSwirl: Double
}

private struct HandoffScene {
    static let grid = 24
    let particles: [Particle]
    let vibrant: RGB
    let secondary: RGB
    let deep: RGB
    let hasArt: Bool
    let reduceMotion: Bool

    init(track: HandoffTrack, reduceMotion: Bool) {
        self.reduceMotion = reduceMotion
        let n = Self.grid
        var pixels = Self.sample(track.art, grid: n)
        hasArt = pixels != nil
        if pixels == nil {
            // No cover: a Spotify-green gradient mosaic stands in for it.
            pixels = (0..<n * n).map { i in
                let x = Double(i % n) / Double(n - 1), y = Double(i / n) / Double(n - 1)
                let a = RGB(r: 0.11, g: 0.84, b: 0.38), b = RGB(r: 0.05, g: 0.22, b: 0.45)
                let wave = 0.5 + 0.5 * sin(x * 5.0 + y * 3.0)
                return a.mix(b, min(1, (x + y) / 2 * 0.8 + wave * 0.25))
            }
        }
        let colors = pixels!

        // Palette: the most vivid colour weighted by how much of the cover it fills.
        let ranked = colors.sorted { $0.saturation * $0.brightness > $1.saturation * $1.brightness }
        let top = ranked.prefix(max(8, colors.count / 10))
        let avg = top.reduce(RGB(r: 0, g: 0, b: 0)) { RGB(r: $0.r + $1.r, g: $0.g + $1.g, b: $0.b + $1.b) }
        let count = Double(top.count)
        vibrant = RGB(r: avg.r / count, g: avg.g / count, b: avg.b / count).vivid
        let mid = ranked[ranked.count / 3].vivid
        secondary = vibrant.mix(mid, 0.6).mix(RGB(r: vibrant.b, g: vibrant.r, b: vibrant.g), 0.25)
        let all = colors.reduce(RGB(r: 0, g: 0, b: 0)) { RGB(r: $0.r + $1.r, g: $0.g + $1.g, b: $0.b + $1.b) }
        let total = Double(colors.count)
        deep = RGB(r: all.r / total * 0.35, g: all.g / total * 0.35, b: all.b / total * 0.35)

        var rng = SplitMix(seed: UInt64(truncatingIfNeeded: track.title.hashValue))
        particles = colors.enumerated().map { i, color in
            let gx = i % n, gy = i / n
            // The bottom of the cover arrives first: it's built up from the phone's side.
            let rowOrder = Double(n - 1 - gy) / Double(n - 1)
            return Particle(
                gx: gx, gy: gy, color: color,
                launch: CGPoint(x: rng.next(-260, 260), y: rng.next(0, 260)),
                delay: rowOrder * 0.32 + rng.next(0, 0.22),
                swirl: (gx < n / 2 ? -1 : 1) * rng.next(0.25, 0.75),
                spin: rng.next(0.35, 0.9) * (rng.next(0, 1) < 0.5 ? -1 : 1),
                exitDelay: Double(n - 1 - gx) / Double(n - 1) * 0.28 + Double(gy) / Double(n - 1) * 0.12 + rng.next(0, 0.12),
                exitSwirl: rng.next(-0.6, 0.6))
        }
    }

    /// The cover scaled down to grid×grid colours, row 0 at the top.
    private static func sample(_ image: NSImage?, grid n: Int) -> [RGB]? {
        guard let image, let cg = image.cgImage(forProposedRect: nil, context: nil, hints: nil) else { return nil }
        var bytes = [UInt8](repeating: 0, count: n * n * 4)
        let ok = bytes.withUnsafeMutableBytes { buffer -> Bool in
            guard let ctx = CGContext(data: buffer.baseAddress, width: n, height: n, bitsPerComponent: 8,
                                      bytesPerRow: n * 4, space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
            ctx.interpolationQuality = .medium
            ctx.draw(cg, in: CGRect(x: 0, y: 0, width: n, height: n))
            return true
        }
        guard ok else { return nil }
        // CGContext memory starts at the top row.
        return (0..<n * n).map { i in
            RGB(r: Double(bytes[i * 4]) / 255, g: Double(bytes[i * 4 + 1]) / 255, b: Double(bytes[i * 4 + 2]) / 255)
        }
    }
}

private struct SplitMix {
    var state: UInt64
    init(seed: UInt64) { state = seed &+ 0x9E37_79B9_7F4A_7C15 }
    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
    mutating func next(_ lo: Double, _ hi: Double) -> Double {
        lo + (hi - lo) * Double(next() >> 11) / Double(1 << 53)
    }
}

// MARK: - Easing

private func clamp01(_ x: Double) -> Double { min(max(x, 0), 1) }
private func smooth(_ x: Double) -> Double { let t = clamp01(x); return t * t * (3 - 2 * t) }
private func easeOutCubic(_ x: Double) -> Double { let t = clamp01(x); return 1 - pow(1 - t, 3) }
private func easeInCubic(_ x: Double) -> Double { let t = clamp01(x); return t * t * t }
private func easeInOutCubic(_ x: Double) -> Double {
    let t = clamp01(x)
    return t < 0.5 ? 4 * t * t * t : 1 - pow(-2 * t + 2, 3) / 2
}
private func easeOutBack(_ x: Double) -> Double {
    let t = clamp01(x), c1 = 1.55, c3 = c1 + 1
    return 1 + c3 * pow(t - 1, 3) + c1 * pow(t - 1, 2)
}
private func lerp(_ a: CGPoint, _ b: CGPoint, _ t: Double) -> CGPoint {
    CGPoint(x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t)
}

// MARK: - Layout

private struct Layout {
    let size: CGSize
    let side: Double          // cover side
    let cover: CGRect
    let vinylCenter: CGPoint  // where the record ends up
    let radius: Double        // record radius
    let launch: CGPoint       // where the pixels come from (the phone, below the screen)
    let sink: CGPoint         // where they go at the end (the menu bar)
    var cell: Double { side / Double(HandoffScene.grid) }

    init(_ size: CGSize) {
        self.size = size
        side = min(330, size.height * 0.32, size.width * 0.26)
        let center = CGPoint(x: size.width / 2, y: size.height * 0.43)
        cover = CGRect(x: center.x - side * 0.78, y: center.y - side / 2, width: side, height: side)
        radius = side * 0.47
        vinylCenter = CGPoint(x: cover.midX + side * 0.58, y: center.y)
        launch = CGPoint(x: size.width / 2, y: size.height + 70)
        sink = CGPoint(x: size.width - 150, y: 0)
    }

    func cellCenter(_ p: Particle) -> CGPoint {
        CGPoint(x: cover.minX + (Double(p.gx) + 0.5) * cell, y: cover.minY + (Double(p.gy) + 0.5) * cell)
    }
}

// MARK: - View

private struct HandoffView: View {
    let track: HandoffTrack
    let scene: HandoffScene
    let start: Date
    @EnvironmentObject private var stage: HandoffStage

    var body: some View {
        TimelineView(.animation) { timeline in
            let t = timeline.date.timeIntervalSince(start)
            let finished = stage.finishedAt.map { $0.timeIntervalSince(start) }
            let exit = HandoffTiming.exitStart(finished: finished)
            GeometryReader { geo in
                let layout = Layout(geo.size)
                ZStack {
                    Canvas(rendersAsynchronously: false) { context, size in
                        HandoffPainter(scene: scene, layout: layout, art: track.art, t: t, exit: exit)
                            .paint(&context)
                    }
                    Caption(track: track, scene: scene, t: t, exit: exit, outcome: stage.outcome)
                        .frame(width: min(geo.size.width - 80, 640), height: 180, alignment: .top)
                        .position(x: geo.size.width / 2, y: layout.cover.maxY + 54 + 90)
                }
            }
        }
        .ignoresSafeArea()
    }
}

// MARK: - Painting

private struct HandoffPainter {
    let scene: HandoffScene
    let layout: Layout
    let art: NSImage?
    let t: Double
    let exit: Double

    /// 0 → 1 as the leave-taking runs.
    var leaving: Double { clamp01((t - exit) / HandoffTiming.exitLength) }
    /// The record is out and the music is "on".
    var beatEnvelope: Double {
        guard t > HandoffTiming.vinyl else { return 0 }
        let phase = (t - HandoffTiming.vinyl).truncatingRemainder(dividingBy: HandoffTiming.beat) / HandoffTiming.beat
        return exp(-phase * 5.5)
    }

    func paint(_ ctx: inout GraphicsContext) {
        backdrop(&ctx)
        if scene.reduceMotion {
            calmCover(&ctx)
            return
        }
        glow(&ctx)
        rings(&ctx)
        vinyl(&ctx)
        cover(&ctx)
        arrivingParticles(&ctx)
        leavingParticles(&ctx)
        shockwave(&ctx)
    }

    // A dark veil tinted with the cover's colours, with slow aurora light moving in it.
    private func backdrop(_ ctx: inout GraphicsContext) {
        let alpha = smooth(t / HandoffTiming.backdropIn) * (1 - smooth((t - exit - 0.25) / 0.9))
        guard alpha > 0.001 else { return }
        let size = layout.size
        let full = Path(CGRect(origin: .zero, size: size))
        let center = CGPoint(x: size.width / 2, y: layout.cover.midY)
        let reach = max(size.width, size.height) * 0.8
        var layer = ctx
        layer.opacity = alpha
        layer.fill(full, with: .radialGradient(
            Gradient(colors: [scene.deep.color.opacity(0.55), Color.black.opacity(0.62), Color.black.opacity(0.78)]),
            center: center, startRadius: 0, endRadius: reach))

        layer.blendMode = .plusLighter
        let blobs: [(RGB, Double, Double, Double)] = [
            (scene.vibrant, 0.21, 0.0, 0.42),
            (scene.secondary, 0.17, 2.1, 0.36),
            (scene.vibrant.mix(scene.secondary, 0.5), 0.13, 4.2, 0.30),
        ]
        for (color, speed, phase, scale) in blobs {
            let a = t * speed + phase
            let p = CGPoint(x: center.x + cos(a) * size.width * 0.22, y: center.y + sin(a * 1.3) * size.height * 0.18)
            let r = max(size.width, size.height) * scale
            layer.fill(full, with: .radialGradient(
                Gradient(colors: [color.color.opacity(0.22 + 0.06 * beatEnvelope), color.color.opacity(0)]),
                center: p, startRadius: 0, endRadius: r))
        }
    }

    // Light gathering where the cover is being built.
    private func glow(_ ctx: inout GraphicsContext) {
        let gather = smooth((t - 0.5) / 1.2) * (1 - smooth((t - exit) / 0.5))
        guard gather > 0 else { return }
        var layer = ctx
        layer.blendMode = .plusLighter
        let c = CGPoint(x: layout.cover.midX, y: layout.cover.midY)
        let r = layout.side * (0.9 + 0.12 * beatEnvelope)
        layer.fill(Path(ellipseIn: CGRect(x: c.x - r * 1.6, y: c.y - r * 1.6, width: r * 3.2, height: r * 3.2)),
                   with: .radialGradient(Gradient(colors: [scene.vibrant.color.opacity(0.35 * gather), .clear]),
                                         center: c, startRadius: 0, endRadius: r * 1.6))
    }

    private var coverAlpha: Double {
        let arrive = scene.hasArt ? smooth((t - HandoffTiming.solidify) / 0.28) : 0
        return arrive * (1 - smooth((t - exit) / 0.14))
    }

    private var coverScale: Double {
        let settle = 1 + 0.035 * (1 - easeOutCubic((t - HandoffTiming.solidify) / 0.5)) * (t > HandoffTiming.solidify ? 1 : 0)
        return settle + 0.012 * beatEnvelope
    }

    private func cover(_ ctx: inout GraphicsContext) {
        let alpha = coverAlpha
        guard alpha > 0.001, let art else { return }
        let rect = scaled(layout.cover, coverScale)
        let corner = layout.side * 0.035
        let shape = Path(roundedRect: rect, cornerRadius: corner)
        ctx.drawLayer { layer in
            layer.opacity = alpha
            layer.addFilter(.shadow(color: .black.opacity(0.55), radius: 40, x: 0, y: 26))
            layer.fill(shape, with: .color(.black))
        }
        ctx.drawLayer { layer in
            layer.opacity = alpha
            layer.clip(to: shape)
            layer.draw(layer.resolve(Image(nsImage: art)), in: rect)
            // One sweep of light across the cover as it turns solid.
            let sweep = (t - HandoffTiming.solidify - 0.1) / 0.9
            if sweep > 0 && sweep < 1 {
                let x = rect.minX - rect.width + sweep * rect.width * 3
                layer.blendMode = .plusLighter
                layer.fill(shape, with: .linearGradient(
                    Gradient(stops: [.init(color: .clear, location: 0), .init(color: .white.opacity(0.38), location: 0.5),
                                     .init(color: .clear, location: 1)]),
                    startPoint: CGPoint(x: x - rect.width * 0.35, y: rect.minY),
                    endPoint: CGPoint(x: x + rect.width * 0.35, y: rect.maxY)))
            }
            // The flash as the mosaic snaps solid.
            let flash = exp(-pow((t - HandoffTiming.solidify - 0.06) / 0.09, 2))
            if flash > 0.01 {
                layer.blendMode = .plusLighter
                layer.fill(shape, with: .color(.white.opacity(0.55 * flash)))
            }
        }
        ctx.stroke(shape, with: .color(.white.opacity(0.14 * alpha)), lineWidth: 0.75)
    }

    // The pixels of the cover flying up from the phone and locking into place.
    private func arrivingParticles(_ ctx: inout GraphicsContext) {
        let fade = scene.hasArt ? 1 - smooth((t - HandoffTiming.solidify) / 0.28) : 1
        let mosaicHold = 1 - smooth((t - exit) / 0.14)   // for covers without art, the mosaic is the cover
        let alpha = fade * (scene.hasArt ? 1 : mosaicHold)
        guard alpha > 0.001, t < exit + 0.2 else { return }
        let cell = layout.cell
        var glow = ctx
        glow.blendMode = .plusLighter
        for p in scene.particles {
            let raw = (t - HandoffTiming.flightStart - p.delay) / HandoffTiming.flight
            guard raw > 0 else { continue }
            let target = layout.cellCenter(p)
            let pos = flightPoint(p, target: target, q: easeInOutCubic(raw))
            let landed = clamp01(raw)
            let s = 2.5 + (cell * 1.02 - 2.5) * pow(easeOutCubic(raw), 1.6)
            let rect = CGRect(x: pos.x - s / 2, y: pos.y - s / 2, width: s, height: s)
            let shape = Path(roundedRect: rect, cornerRadius: s / 2 * (1 - landed) + cell * 0.12 * landed)
            ctx.fill(shape, with: .color(p.color.color.opacity(alpha * min(1, raw * 8))))
            // Comet trail while in flight.
            if raw < 1 {
                let prev = flightPoint(p, target: target, q: easeInOutCubic(raw - 0.07))
                var trail = Path()
                trail.move(to: prev)
                trail.addLine(to: pos)
                glow.stroke(trail, with: .color(p.color.vivid.color.opacity(0.55 * alpha * (1 - raw))),
                            style: StrokeStyle(lineWidth: max(1, s * 0.55), lineCap: .round))
            }
        }
    }

    /// A curved path from below the screen to the pixel's place, ending in a tightening spiral.
    private func flightPoint(_ p: Particle, target: CGPoint, q: Double) -> CGPoint {
        let q = clamp01(q)
        let start = CGPoint(x: layout.launch.x + p.launch.x, y: layout.launch.y + p.launch.y)
        let dx = target.x - start.x, dy = target.y - start.y
        let dist = max(1, hypot(dx, dy))
        let normal = CGPoint(x: -dy / dist, y: dx / dist)
        let mid = CGPoint(x: (start.x + target.x) / 2 + normal.x * dist * 0.45 * p.swirl,
                          y: (start.y + target.y) / 2 + normal.y * dist * 0.45 * p.swirl)
        let a = lerp(start, mid, q), b = lerp(mid, target, q)
        let onCurve = lerp(a, b, q)
        let k = pow(1 - q, 2.2)
        let angle = k * .pi * 2 * p.spin * 2.2
        let radius = k * layout.side * 0.22
        return CGPoint(x: onCurve.x + cos(angle) * radius, y: onCurve.y + sin(angle) * radius)
    }

    // At the end, the cover breaks back into pixels that stream into the menu bar.
    private func leavingParticles(_ ctx: inout GraphicsContext) {
        guard t > exit else { return }
        let cell = layout.cell
        var glow = ctx
        glow.blendMode = .plusLighter
        let mosaic = smooth((t - exit) / 0.14)
        for p in scene.particles {
            let home = layout.cellCenter(p)
            let raw = (t - exit - 0.08 - p.exitDelay) / 0.78
            if raw <= 0 {
                let rect = CGRect(x: home.x - cell * 0.51, y: home.y - cell * 0.51, width: cell * 1.02, height: cell * 1.02)
                ctx.fill(Path(roundedRect: rect, cornerRadius: cell * 0.12), with: .color(p.color.color.opacity(mosaic)))
                continue
            }
            guard raw < 1 else { continue }
            let pos = exitPoint(p, home: home, q: easeInCubic(raw))
            let s = cell * (1 - 0.85 * easeOutCubic(raw))
            let rect = CGRect(x: pos.x - s / 2, y: pos.y - s / 2, width: s, height: s)
            ctx.fill(Path(roundedRect: rect, cornerRadius: s * 0.3), with: .color(p.color.color.opacity(1 - raw * 0.6)))
            let prev = exitPoint(p, home: home, q: easeInCubic(max(0, raw - 0.08)))
            var trail = Path()
            trail.move(to: prev)
            trail.addLine(to: pos)
            glow.stroke(trail, with: .color(p.color.vivid.color.opacity(0.6 * (1 - raw))),
                        style: StrokeStyle(lineWidth: max(1, s * 0.5), lineCap: .round))
        }
        // A spark where they disappear.
        let spark = (t - exit - 0.75) / 0.6
        if spark > 0 && spark < 1 {
            let r = 6 + 46 * easeOutCubic(spark)
            let c = layout.sink
            glow.stroke(Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: r * 2, height: r * 2)),
                        with: .color(scene.vibrant.color.opacity(0.8 * (1 - spark))), lineWidth: 2)
            glow.fill(Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: r * 2, height: r * 2)),
                      with: .radialGradient(Gradient(colors: [.white.opacity(0.6 * (1 - spark)), .clear]),
                                            center: c, startRadius: 0, endRadius: r))
        }
    }

    private func exitPoint(_ p: Particle, home: CGPoint, q: Double) -> CGPoint {
        let target = layout.sink
        let dx = target.x - home.x, dy = target.y - home.y
        let dist = max(1, hypot(dx, dy))
        let normal = CGPoint(x: -dy / dist, y: dx / dist)
        let mid = CGPoint(x: (home.x + target.x) / 2 + normal.x * dist * 0.3 * p.exitSwirl,
                          y: (home.y + target.y) / 2 + normal.y * dist * 0.3 * p.exitSwirl)
        return lerp(lerp(home, mid, q), lerp(mid, target, q), q)
    }

    private func shockwave(_ ctx: inout GraphicsContext) {
        let k = (t - HandoffTiming.solidify - 0.04) / 0.85
        guard k > 0, k < 1 else { return }
        var layer = ctx
        layer.blendMode = .plusLighter
        let grow = easeOutCubic(k) * layout.side * 0.45
        let rect = layout.cover.insetBy(dx: -grow, dy: -grow)
        layer.stroke(Path(roundedRect: rect, cornerRadius: layout.side * 0.035 + grow),
                     with: .color(scene.vibrant.color.opacity(0.7 * pow(1 - k, 2))), lineWidth: 2.5 * (1 - k) + 0.5)
    }

    /// Where the record is: slid out from behind the cover with a little overshoot, then gone at the end.
    private var vinylState: (center: CGPoint, scale: Double, alpha: Double, angle: Double) {
        let out = easeOutBack((t - HandoffTiming.vinyl) / 0.75)
        let hidden = CGPoint(x: layout.cover.midX, y: layout.cover.midY)
        let center = lerp(hidden, layout.vinylCenter, out)
        let leave = clamp01((t - exit) / 0.45)
        let spinUp = t > exit ? pow(t - exit, 2) * 9 : 0
        let angle = max(0, t - HandoffTiming.vinyl) * 2.6 + spinUp
        let alpha = (t > HandoffTiming.vinyl ? 1 : 0) * (1 - easeInCubic(leave))
        return (center, 1 - 0.35 * easeInCubic(leave), alpha, angle)
    }

    private func vinyl(_ ctx: inout GraphicsContext) {
        let v = vinylState
        guard v.alpha > 0.001 else { return }
        let r = layout.radius * v.scale
        let c = v.center
        let disc = Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: r * 2, height: r * 2))

        ctx.drawLayer { layer in
            layer.opacity = v.alpha
            layer.addFilter(.shadow(color: .black.opacity(0.6), radius: 30, x: 0, y: 20))
            layer.fill(disc, with: .color(Color(white: 0.035)))
        }
        ctx.drawLayer { layer in
            layer.opacity = v.alpha
            layer.clip(to: disc)
            // Grooves.
            var grooves = Path()
            var gr = r * 0.4
            while gr < r * 0.975 {
                grooves.addEllipse(in: CGRect(x: c.x - gr, y: c.y - gr, width: gr * 2, height: gr * 2))
                gr += r * 0.028
            }
            layer.stroke(grooves, with: .color(.white.opacity(0.05)), lineWidth: 0.6)
            // Light catching the grooves stays put while the disc turns, as on a real record.
            layer.blendMode = .plusLighter
            layer.fill(disc, with: .conicGradient(
                Gradient(stops: [
                    .init(color: .clear, location: 0.0), .init(color: .white.opacity(0.16), location: 0.08),
                    .init(color: .clear, location: 0.17), .init(color: .clear, location: 0.5),
                    .init(color: scene.vibrant.color.opacity(0.22), location: 0.58), .init(color: .clear, location: 0.67),
                    .init(color: .clear, location: 1.0)]),
                center: c, angle: .degrees(-35)))
            layer.blendMode = .normal

            // The label: the cover, turning with the record.
            let labelR = r * 0.36
            let label = Path(ellipseIn: CGRect(x: c.x - labelR, y: c.y - labelR, width: labelR * 2, height: labelR * 2))
            var spun = layer
            spun.clip(to: label)
            spun.translateBy(x: c.x, y: c.y)
            spun.rotate(by: .radians(v.angle))
            let labelRect = CGRect(x: -labelR, y: -labelR, width: labelR * 2, height: labelR * 2)
            if let art {
                spun.draw(spun.resolve(Image(nsImage: art)), in: labelRect)
            } else {
                spun.fill(Path(labelRect), with: .linearGradient(
                    Gradient(colors: [scene.vibrant.color, scene.secondary.color]),
                    startPoint: CGPoint(x: -labelR, y: -labelR), endPoint: CGPoint(x: labelR, y: labelR)))
            }
            // A notch on the label makes the turning visible even on plain covers.
            spun.fill(Path(ellipseIn: CGRect(x: labelR * 0.55, y: -labelR * 0.06, width: labelR * 0.12, height: labelR * 0.12)),
                      with: .color(.white.opacity(0.5)))
            layer.stroke(label, with: .color(.black.opacity(0.35)), lineWidth: 1)
            let hole = r * 0.028
            layer.fill(Path(ellipseIn: CGRect(x: c.x - hole, y: c.y - hole, width: hole * 2, height: hole * 2)),
                       with: .color(Color(white: 0.02)))
        }
        ctx.stroke(disc, with: .color(.white.opacity(0.12 * v.alpha)), lineWidth: 0.8)
    }

    // The record's aura: a ring of bars that breathes to the beat, and rings pulsing off it.
    private func rings(_ ctx: inout GraphicsContext) {
        let v = vinylState
        let on = smooth((t - HandoffTiming.vinyl - 0.45) / 0.5) * v.alpha
        guard on > 0.001 else { return }
        var layer = ctx
        layer.blendMode = .plusLighter
        let c = v.center
        let r = layout.radius * v.scale

        // Rings, one per beat.
        let since = t - HandoffTiming.vinyl - 0.45
        let beats = Int(since / HandoffTiming.beat)
        for b in max(0, beats - 3)...max(0, beats) {
            let age = since - Double(b) * HandoffTiming.beat
            let k = age / (HandoffTiming.beat * 3.2)
            guard k > 0, k < 1 else { continue }
            let rr = r * (1.08 + 1.25 * easeOutCubic(k))
            let color = b % 2 == 0 ? scene.vibrant : scene.secondary
            layer.stroke(Path(ellipseIn: CGRect(x: c.x - rr, y: c.y - rr, width: rr * 2, height: rr * 2)),
                         with: .color(color.color.opacity(0.45 * pow(1 - k, 2) * on)), lineWidth: 1.6)
        }

        // Bars.
        let count = 84
        let env = 0.35 + 0.65 * beatEnvelope
        for i in 0..<count {
            let a = Double(i) / Double(count) * .pi * 2 - .pi / 2
            let x = Double(i)
            let spectrum = abs(sin(x * 0.37 + t * 3.1) * 0.5 + sin(x * 0.91 - t * 5.3) * 0.3 + sin(x * 0.13 + t * 1.7) * 0.2)
            let len = r * (0.04 + 0.2 * spectrum * env)
            let inner = r * 1.05
            var bar = Path()
            bar.move(to: CGPoint(x: c.x + cos(a) * inner, y: c.y + sin(a) * inner))
            bar.addLine(to: CGPoint(x: c.x + cos(a) * (inner + len), y: c.y + sin(a) * (inner + len)))
            let mixT = 0.5 + 0.5 * sin(a + t * 0.8)
            let color = scene.vibrant.mix(scene.secondary, mixT)
            layer.stroke(bar, with: .color(color.color.opacity((0.35 + 0.5 * spectrum) * on)),
                         style: StrokeStyle(lineWidth: max(1.5, r * 0.022), lineCap: .round))
        }
    }

    /// With Reduce Motion on: the cover and record simply fade in and out.
    private func calmCover(_ ctx: inout GraphicsContext) {
        let alpha = smooth(t / 0.5) * (1 - smooth((t - exit) / 0.6))
        guard alpha > 0.001 else { return }
        let c = layout.vinylCenter, r = layout.radius
        ctx.drawLayer { layer in
            layer.opacity = alpha
            layer.fill(Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: r * 2, height: r * 2)),
                       with: .color(Color(white: 0.04)))
            let shape = Path(roundedRect: layout.cover, cornerRadius: layout.side * 0.035)
            if let art {
                layer.clip(to: shape)
                layer.draw(layer.resolve(Image(nsImage: art)), in: layout.cover)
            } else {
                layer.fill(shape, with: .color(scene.vibrant.color))
            }
        }
    }

    private func scaled(_ rect: CGRect, _ s: Double) -> CGRect {
        rect.insetBy(dx: rect.width * (1 - s) / 2, dy: rect.height * (1 - s) / 2)
    }
}

// MARK: - Caption

/// The words under the record: where it came from, the title writing itself in, the artist, and
/// how playback on this Mac turned out.
private struct Caption: View {
    let track: HandoffTrack
    let scene: HandoffScene
    let t: Double
    let exit: Double
    let outcome: HandoffStage.Outcome?

    private var leave: Double { clamp01((t - exit) / 0.5) }

    var body: some View {
        let base = HandoffTiming.text
        VStack(spacing: 10) {
            Text("FROM \(track.phoneName.uppercased())")
                .font(.system(size: 11, weight: .semibold))
                .tracking(3)
                .foregroundStyle(.white.opacity(0.6))
                .modifier(Reveal(t: t, at: base - 0.05))

            title(base: base + 0.1)

            if !track.artist.isEmpty {
                Text(track.artist)
                    .font(.system(size: 17, weight: .medium, design: .rounded))
                    .foregroundStyle(.white.opacity(0.72))
                    .lineLimit(1)
                    .modifier(Reveal(t: t, at: base + 0.1 + Double(min(track.title.count, 42)) * 0.028 + 0.1))
            }

            status
                .padding(.top, 8)
                .modifier(Reveal(t: t, at: base + 0.75))
        }
        .multilineTextAlignment(.center)
        .opacity(scene.reduceMotion ? smooth(t / 0.5) * (1 - leave) : 1 - leave)
        .blur(radius: leave * 12)
        .offset(y: -leave * 24)
        .shadow(color: .black.opacity(0.4), radius: 12, y: 4)
    }

    /// The title, one letter at a time, each sharpening out of a blur.
    private func title(base: Double) -> some View {
        let text = track.title.count > 42 ? String(track.title.prefix(41)) + "…" : track.title
        let letters = Array(text)
        return HStack(spacing: 0) {
            ForEach(letters.indices, id: \.self) { i in
                let k = scene.reduceMotion ? 1 : easeOutCubic((t - base - Double(i) * 0.028) / 0.5)
                Text(String(letters[i]))
                    .font(.system(size: 34, weight: .bold, design: .rounded))
                    .foregroundStyle(.white)
                    .opacity(k)
                    .blur(radius: (1 - k) * 10)
                    .offset(y: (1 - k) * 16)
                    .scaleEffect(0.7 + 0.3 * k, anchor: .bottom)
            }
        }
        .fixedSize()
    }

    @ViewBuilder private var status: some View {
        HStack(spacing: 8) {
            switch outcome {
            case nil:
                Circle()
                    .trim(from: 0, to: 0.7)
                    .stroke(.white, style: StrokeStyle(lineWidth: 2, lineCap: .round))
                    .frame(width: 12, height: 12)
                    .rotationEffect(.radians(t * 7))
                Text("Starting Spotify on this Mac…")
            case .playing:
                Equalizer(t: t, color: scene.vibrant.color)
                Text("Playing on this Mac")
            case .opened(let message):
                Image(systemName: "arrow.up.forward.app.fill")
                Text(message)
            case .failed(let message):
                Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.yellow)
                Text(message)
            }
        }
        .font(.system(size: 13, weight: .semibold, design: .rounded))
        .foregroundStyle(.white)
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .background(Capsule().fill(.white.opacity(0.12)))
        .overlay(Capsule().strokeBorder(.white.opacity(0.18), lineWidth: 0.5))
        .animation(.spring(response: 0.4, dampingFraction: 0.8), value: outcome)
    }
}

private struct Reveal: ViewModifier {
    let t: Double
    let at: Double

    func body(content: Content) -> some View {
        let k = easeOutCubic((t - at) / 0.5)
        content
            .opacity(k)
            .blur(radius: (1 - k) * 8)
            .offset(y: (1 - k) * 12)
    }
}

/// Four little bars, bouncing.
private struct Equalizer: View {
    let t: Double
    let color: Color

    var body: some View {
        HStack(alignment: .bottom, spacing: 2) {
            ForEach(0..<4, id: \.self) { i in
                let h = 0.3 + 0.7 * abs(sin(t * (5.0 + Double(i) * 1.7) + Double(i) * 1.3))
                Capsule().fill(color).frame(width: 3, height: 4 + 10 * h)
            }
        }
        .frame(height: 14, alignment: .bottom)
    }
}
