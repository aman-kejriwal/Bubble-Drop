// Draws BubbleDrop's app icon: `swift make-icon.swift OUT.png` (1024×1024). build.sh turns it into AppIcon.icns.
import AppKit

let size: CGFloat = 1024
let image = NSImage(size: NSSize(width: size, height: size))
image.lockFocus()

// macOS icon grid: 824pt squircle centred on the 1024 canvas.
let inset: CGFloat = 100
let tile = NSRect(x: inset, y: inset, width: size - inset * 2, height: size - inset * 2)
let squircle = NSBezierPath(roundedRect: tile, xRadius: 185, yRadius: 185)
NSGradient(colors: [
    NSColor(red: 0.20, green: 0.82, blue: 0.98, alpha: 1),
    NSColor(red: 0.18, green: 0.45, blue: 0.98, alpha: 1),
    NSColor(red: 0.47, green: 0.30, blue: 0.95, alpha: 1),
])!.draw(in: squircle, angle: -60)

func bubble(_ center: CGPoint, _ radius: CGFloat, alpha: CGFloat) {
    let rect = NSRect(x: center.x - radius, y: center.y - radius, width: radius * 2, height: radius * 2)
    let path = NSBezierPath(ovalIn: rect)
    NSGradient(colors: [NSColor.white.withAlphaComponent(alpha * 0.55), NSColor.white.withAlphaComponent(alpha * 0.15)])!
        .draw(in: path, relativeCenterPosition: NSPoint(x: -0.35, y: 0.4))
    NSColor.white.withAlphaComponent(alpha * 0.7).setStroke()
    path.lineWidth = radius * 0.06
    path.stroke()
    // Specular highlight.
    let shine = NSRect(x: center.x - radius * 0.55, y: center.y + radius * 0.25, width: radius * 0.45, height: radius * 0.28)
    NSColor.white.withAlphaComponent(alpha * 0.8).setFill()
    NSBezierPath(ovalIn: shine).fill()
}

NSGraphicsContext.saveGraphicsState()
squircle.addClip()
bubble(CGPoint(x: 300, y: 760), 70, alpha: 0.7)
bubble(CGPoint(x: 760, y: 300), 95, alpha: 0.6)
bubble(CGPoint(x: 250, y: 290), 45, alpha: 0.6)
bubble(CGPoint(x: 512, y: 512), 255, alpha: 1)
NSGraphicsContext.restoreGraphicsState()

let config = NSImage.SymbolConfiguration(pointSize: 230, weight: .bold)
if let plane = NSImage(systemSymbolName: "paperplane.fill", accessibilityDescription: nil)?
    .withSymbolConfiguration(config) {
    let tinted = NSImage(size: plane.size, flipped: false) { rect in
        plane.draw(in: rect)
        NSColor.white.set()
        rect.fill(using: .sourceAtop)
        return true
    }
    tinted.draw(in: NSRect(x: 512 - plane.size.width / 2 + 8, y: 512 - plane.size.height / 2 - 6,
                           width: plane.size.width, height: plane.size.height))
}
image.unlockFocus()

let rep = NSBitmapImageRep(data: image.tiffRepresentation!)!
try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: CommandLine.arguments[1]))
