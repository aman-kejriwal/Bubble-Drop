// Draws BubbleDrop's app icon: `swift make-icon.swift OUT.png` (1024×1024). build.sh turns it into AppIcon.icns.
// Mirrors the Android launcher icon (android/app/src/main/res/drawable/ic_launcher_{background,foreground}.xml):
// a white droplet with a blue highlight bubble on a light-blue → blue vertical gradient.
import AppKit

let size: CGFloat = 1024
let image = NSImage(size: NSSize(width: size, height: size))
image.lockFocus()

// macOS icon grid: 824pt squircle centred on the 1024 canvas.
let inset: CGFloat = 100
let tile = NSRect(x: inset, y: inset, width: size - inset * 2, height: size - inset * 2)
let squircle = NSBezierPath(roundedRect: tile, xRadius: 185, yRadius: 185)
NSGradient(colors: [
    NSColor(red: 0x5A / 255.0, green: 0xC8 / 255.0, blue: 0xFA / 255.0, alpha: 1), // #5AC8FA (top)
    NSColor(red: 0x00 / 255.0, green: 0x7A / 255.0, blue: 0xFF / 255.0, alpha: 1), // #007AFF (bottom)
])!.draw(in: squircle, angle: -90)

// Android adaptive icons are 108×108 with the visible area being the central 72×72 (18…90).
// Map that visible square onto the squircle; Android's y axis points down, AppKit's up.
let scale = tile.width / 72
func p(_ x: CGFloat, _ y: CGFloat) -> NSPoint {
    NSPoint(x: inset + (x - 18) * scale, y: size - inset - (y - 18) * scale)
}

// Droplet: M54,28 C54,28 36,48 36,60.5 C36,70.4 44.1,78.5 54,78.5 C63.9,78.5 72,70.4 72,60.5 C72,48 54,28 54,28Z
let drop = NSBezierPath()
drop.move(to: p(54, 28))
drop.curve(to: p(36, 60.5), controlPoint1: p(54, 28), controlPoint2: p(36, 48))
drop.curve(to: p(54, 78.5), controlPoint1: p(36, 70.4), controlPoint2: p(44.1, 78.5))
drop.curve(to: p(72, 60.5), controlPoint1: p(63.9, 78.5), controlPoint2: p(72, 70.4))
drop.curve(to: p(54, 28), controlPoint1: p(72, 48), controlPoint2: p(54, 28))
drop.close()
NSColor.white.setFill()
drop.fill()

// Highlight bubble: circle at (46.5, 61.5), r = 5, filled #007AFF at 30% opacity.
let r = 5 * scale
let c = p(46.5, 61.5)
NSColor(red: 0, green: 0x7A / 255.0, blue: 1, alpha: 0x4D / 255.0).setFill()
NSBezierPath(ovalIn: NSRect(x: c.x - r, y: c.y - r, width: r * 2, height: r * 2)).fill()

image.unlockFocus()

let rep = NSBitmapImageRep(data: image.tiffRepresentation!)!
try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: CommandLine.arguments[1]))
