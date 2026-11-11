import SwiftUI

/// Liquid Glass styling for the menu-bar window.
///
/// On macOS 26+ this uses SwiftUI's native `glassEffect` (real refraction and specular
/// highlights). On macOS 15 it falls back to a frosted material with a hairline highlight,
/// which is the closest look the older system can draw.
extension View {
    @ViewBuilder
    func liquidGlass(cornerRadius: CGFloat = 12, tint: Color? = nil, interactive: Bool = false) -> some View {
        if #available(macOS 26.0, *) {
            glassEffect(.regular.tint(tint).interactive(interactive), in: .rect(cornerRadius: cornerRadius))
        } else {
            background {
                RoundedRectangle(cornerRadius: cornerRadius)
                    .fill(.ultraThinMaterial)
                    .overlay(RoundedRectangle(cornerRadius: cornerRadius).fill(tint ?? .clear))
                    .overlay(RoundedRectangle(cornerRadius: cornerRadius)
                        .strokeBorder(.white.opacity(0.25), lineWidth: 0.5))
                    .shadow(color: .black.opacity(0.08), radius: 4, y: 1)
            }
        }
    }

    /// Groups nearby glass shapes so they blend into each other on macOS 26+.
    @ViewBuilder
    func liquidGlassGroup(spacing: CGFloat = 12) -> some View {
        if #available(macOS 26.0, *) {
            GlassEffectContainer(spacing: spacing) { self }
        } else {
            self
        }
    }
}

/// Glass only shows over something colorful, so the window gets a soft, slowly drifting
/// gradient behind its content for the glass to refract.
struct LiquidBackdrop: View {
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var drift = false

    var body: some View {
        let strength = colorScheme == .dark ? 0.45 : 0.35
        ZStack {
            LinearGradient(colors: [Color.blue.opacity(strength * 0.6), Color.purple.opacity(strength * 0.5)],
                           startPoint: .topLeading, endPoint: .bottomTrailing)
            Circle()
                .fill(Color.cyan.opacity(strength))
                .frame(width: 220, height: 220)
                .blur(radius: 60)
                .offset(x: drift ? -70 : -110, y: drift ? -160 : -120)
            Circle()
                .fill(Color.pink.opacity(strength * 0.9))
                .frame(width: 240, height: 240)
                .blur(radius: 70)
                .offset(x: drift ? 110 : 70, y: drift ? 140 : 190)
            Circle()
                .fill(Color.indigo.opacity(strength))
                .frame(width: 180, height: 180)
                .blur(radius: 60)
                .offset(x: drift ? 60 : 100, y: drift ? -30 : 20)
        }
        .allowsHitTesting(false)
        .onAppear {
            guard !reduceMotion else { return }
            withAnimation(.easeInOut(duration: 9).repeatForever(autoreverses: true)) { drift = true }
        }
    }
}
