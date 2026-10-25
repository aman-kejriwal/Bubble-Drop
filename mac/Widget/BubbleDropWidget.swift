import SwiftUI
import WidgetKit
import BubbleDropCore

// The desktop widget: the paired phone's battery and accessories, laid out like Apple's Batteries
// widget, plus its connections, sound settings and (large size) quick actions. The Mac app writes
// the data (WidgetSnapshot); this sandboxed extension may read only that one folder.

@main
struct BubbleDropWidgets: WidgetBundle {
    var body: some Widget {
        PhoneWidget()
        GlassTestA()
        GlassTestB()
        GlassTestC()
    }
}

// TEMPORARY: three background variants to find which one gets the system's Liquid Glass.
struct GlassTestA: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "dev.bubbledrop.glasstest.A", provider: Provider()) { entry in
            PhoneWidgetView(entry: entry).containerBackground(.background, for: .widget)
        }
        .configurationDisplayName("Glass Test A")
        .description("Semantic background")
        .supportedFamilies([.systemMedium])
    }
}

struct GlassTestB: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "dev.bubbledrop.glasstest.B", provider: Provider()) { entry in
            PhoneWidgetView(entry: entry)
        }
        .configurationDisplayName("Glass Test B")
        .description("No container background")
        .supportedFamilies([.systemMedium])
    }
}

struct GlassTestC: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "dev.bubbledrop.glasstest.C", provider: Provider()) { entry in
            PhoneWidgetView(entry: entry).containerBackground(for: .widget) { Color.clear }
        }
        .configurationDisplayName("Glass Test C")
        .description("Clear background")
        .supportedFamilies([.systemMedium])
    }
}

struct PhoneWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "dev.bubbledrop.phone", provider: Provider()) { entry in
            PhoneWidgetView(entry: entry)
                // Clear, like Apple's own widgets: the system draws its Liquid Glass platter behind
                // the widget, and any fill here would cover it with a flat panel.
                .containerBackground(for: .widget) { Color.clear }
        }
        .configurationDisplayName("Phone")
        .description("Your phone's battery, accessories and connection to this Mac.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

// MARK: Timeline

struct Entry: TimelineEntry {
    let date: Date
    let snapshot: WidgetSnapshot?

    var connected: Bool { snapshot?.isConnected(at: date) ?? false }
}

struct Provider: TimelineProvider {
    /// The real home folder: inside the sandbox NSHomeDirectory() is the widget's container.
    private var home: URL {
        guard let entry = getpwuid(getuid()), let dir = entry.pointee.pw_dir else {
            return URL(fileURLWithPath: NSHomeDirectory())
        }
        return URL(fileURLWithPath: String(cString: dir))
    }

    func placeholder(in context: Context) -> Entry { Entry(date: .now, snapshot: .sample) }

    func getSnapshot(in context: Context, completion: @escaping (Entry) -> Void) {
        let saved = WidgetSnapshot.load(home: home)
        completion(Entry(date: .now, snapshot: context.isPreview && saved?.status == nil ? .sample : saved))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<Entry>) -> Void) {
        let snapshot = WidgetSnapshot.load(home: home)
        var entries = [Entry(date: .now, snapshot: snapshot)]
        // If reports stop (phone gone, Mac app quit), show it as disconnected once they're overdue.
        if let snapshot, snapshot.isConnected(), let at = snapshot.statusAt {
            entries.append(Entry(date: at.addingTimeInterval(WidgetSnapshot.staleAfter), snapshot: snapshot))
        }
        completion(Timeline(entries: entries, policy: .after(.now.addingTimeInterval(15 * 60))))
    }
}

extension WidgetSnapshot {
    /// What the widget gallery shows before a phone has reported.
    static let sample = WidgetSnapshot(
        phoneName: "Phone",
        status: PhoneStatus(battery: 78, charging: true, minutesToFull: 40, powerSaver: false, bluetoothOn: true,
                            accessories: [.init(name: "Earbuds", kind: "headphones", battery: 64),
                                          .init(name: "Watch", kind: "watch", battery: 91)],
                            wifi: true, wifiName: "Home", wifiBars: 4, carrier: "Carrier", cellBars: 3,
                            ringer: "ring", doNotDisturb: false, hotspot: true),
        statusAt: .now, nearby: true, macReceiving: true)
}

// MARK: Views

struct PhoneWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: Entry

    var body: some View {
        PhoneWidgetContent(family: family, entry: entry)
    }
}

/// The widget's content for a given size; separate from the environment so it can be previewed.
struct PhoneWidgetContent: View {
    let family: WidgetFamily
    let entry: Entry

    var body: some View {
        if let snapshot = entry.snapshot, let name = snapshot.phoneName {
            if let status = snapshot.status {
                switch family {
                case .systemSmall: SmallView(name: name, status: status, entry: entry)
                case .systemMedium: MediumView(status: status, entry: entry)
                default: LargeView(name: name, status: status, entry: entry)
                }
            } else {
                EmptyState(symbol: "smartphone", title: name,
                           message: "Open BubbleDrop on the phone to see its battery here.")
            }
        } else {
            EmptyState(symbol: "smartphone", title: "No Phone",
                       message: "Pair your phone with BubbleDrop to see it here.")
        }
    }
}

/// A battery device: the phone or one of its accessories.
private struct Device: Identifiable {
    let id: String
    let name: String
    let symbol: String
    let level: Int?
    var charging = false
    var powerSaver = false

    static func phone(_ name: String, _ status: PhoneStatus) -> Device {
        Device(id: "phone", name: name, symbol: "smartphone", level: status.battery,
               charging: status.charging, powerSaver: status.powerSaver == true)
    }

    static func accessories(_ status: PhoneStatus) -> [Device] {
        status.accessories.enumerated().map { i, accessory in
            Device(id: "acc\(i)", name: accessory.name, symbol: symbol(for: accessory.kind), level: accessory.battery)
        }
    }

    private static func symbol(for kind: String) -> String {
        switch kind {
        case "headphones": "headphones"
        case "watch": "applewatch"
        case "speaker": "hifispeaker.fill"
        case "car": "car.fill"
        case "input": "keyboard"
        default: "antenna.radiowaves.left.and.right"
        }
    }

    /// Green, yellow in battery saver, red when low; as in the Batteries widget.
    var color: Color {
        guard let level else { return .secondary }
        if level <= 20 && !charging { return .red }
        return powerSaver ? .yellow : .green
    }
}

/// The Batteries widget's ring: device glyph inside, level as the arc, a bolt while charging.
private struct BatteryRing: View {
    let device: Device?
    let size: CGFloat

    var body: some View {
        let line = size * 0.09
        ZStack {
            Circle().stroke(.quaternary, lineWidth: line)
            if let device, let level = device.level {
                Circle()
                    .trim(from: 0, to: CGFloat(max(level, 2)) / 100)
                    .stroke(device.color, style: StrokeStyle(lineWidth: line, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .widgetAccentable()
            }
            if let device {
                Image(systemName: device.symbol)
                    .font(.system(size: size * 0.36, weight: .medium))
                    .foregroundStyle(.primary)
            }
        }
        .frame(width: size, height: size)
        .overlay(alignment: .top) {
            if device?.charging == true {
                Image(systemName: "bolt.fill")
                    .font(.system(size: size * 0.2, weight: .bold))
                    .foregroundStyle(.green)
                    .offset(y: -size * 0.1)
            }
        }
    }
}

private struct Percent: View {
    let level: Int?
    var font: Font = .caption

    var body: some View {
        Text(level.map { "\($0)%" } ?? "—")
            .font(font)
            .monospacedDigit()
    }
}

private func connectionText(_ entry: Entry) -> Text {
    if entry.connected { return Text("Connected") }
    if let at = entry.snapshot?.statusAt { return Text("Seen \(at, style: .relative) ago") }
    return Text("Not connected")
}

/// "Connected" with the BubbleDrop glyph, or when it was last seen.
private struct ConnectionLabel: View {
    let entry: Entry

    var body: some View {
        if entry.connected {
            Label("Connected", systemImage: "paperplane.circle.fill")
                .foregroundStyle(.green)
        } else if let at = entry.snapshot?.statusAt {
            Label { Text("Seen \(at, style: .relative) ago") } icon: { Image(systemName: "paperplane.circle") }
                .foregroundStyle(.secondary)
        } else {
            Label("Not connected", systemImage: "paperplane.circle")
                .foregroundStyle(.secondary)
        }
    }
}

/// Tiny glyphs for Wi-Fi, mobile signal, ringer and Do Not Disturb.
private struct StatusGlyphs: View {
    let status: PhoneStatus

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: status.wifi ? "wifi" : "wifi.slash",
                  variableValue: status.wifi ? Double(status.wifiBars ?? 4) / 4 : nil)
            if let bars = status.cellBars {
                Image(systemName: "cellularbars", variableValue: Double(bars) / 4)
            }
            if status.hotspot == true {
                Image(systemName: "personalhotspot").foregroundStyle(.green)
            }
            Image(systemName: ringerSymbol(status.ringer))
            if status.doNotDisturb {
                Image(systemName: "moon.fill").foregroundStyle(.indigo)
            }
        }
        .imageScale(.small)
        .foregroundStyle(.secondary)
    }
}

private func ringerSymbol(_ ringer: String) -> String {
    switch ringer {
    case "silent": "bell.slash.fill"
    case "vibrate": "iphone.radiowaves.left.and.right"
    default: "bell.fill"
    }
}

private func ringerName(_ ringer: String) -> String {
    switch ringer {
    case "silent": "Silent"
    case "vibrate": "Vibrate"
    default: "Ring"
    }
}

/// "Full in 40 min", "Charging", "Low Battery" or "Battery Saver".
private func batteryNote(_ status: PhoneStatus) -> String? {
    if status.charging {
        guard let minutes = status.minutesToFull, minutes > 0 else { return status.battery >= 100 ? "Charged" : "Charging" }
        return minutes >= 60 ? "Full in \(minutes / 60) h \(minutes % 60) min" : "Full in \(minutes) min"
    }
    if status.battery <= 20 { return "Low Battery" }
    if status.powerSaver == true { return "Battery Saver" }
    return nil
}

// MARK: Sizes

private struct SmallView: View {
    let name: String
    let status: PhoneStatus
    let entry: Entry

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .top) {
                BatteryRing(device: .phone(name, status), size: 56)
                Spacer(minLength: 0)
                Image(systemName: entry.connected ? "paperplane.circle.fill" : "paperplane.circle")
                    .font(.title3)
                    .foregroundStyle(entry.connected ? Color.green : Color.secondary)
            }
            Spacer(minLength: 4)
            Percent(level: status.battery, font: .system(size: 30, weight: .semibold))
            Text(batteryNote(status) ?? name)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)
            StatusGlyphs(status: status)
                .padding(.top, 4)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
    }
}

private struct MediumView: View {
    let status: PhoneStatus
    let entry: Entry

    var body: some View {
        let devices = [Device.phone(entry.snapshot?.phoneName ?? "Phone", status)] + Device.accessories(status)
        VStack(spacing: 0) {
            // Four slots like the Batteries widget; empty ones stay as faint rings.
            HStack(spacing: 0) {
                ForEach(0..<4, id: \.self) { i in
                    let device = i < devices.count ? devices[i] : nil
                    VStack(spacing: 6) {
                        BatteryRing(device: device, size: 52)
                        Percent(level: device?.level, font: .caption.weight(.medium))
                            .opacity(device == nil ? 0 : 1)
                    }
                    .frame(maxWidth: .infinity)
                }
            }
            Spacer(minLength: 6)
            HStack {
                ConnectionLabel(entry: entry)
                    .lineLimit(1)
                Spacer(minLength: 6)
                StatusGlyphs(status: status)
            }
            .font(.caption)
        }
        .frame(maxHeight: .infinity)
    }
}

private struct LargeView: View {
    let name: String
    let status: PhoneStatus
    let entry: Entry

    var body: some View {
        // Rows like the Batteries widget's large size: the phone, then up to four accessories.
        let devices = [Device.phone(name, status)] + Device.accessories(status).prefix(4)
        VStack(alignment: .leading, spacing: 10) {
            ForEach(devices) { device in
                DeviceRow(device: device, note: device.id == "phone" ? batteryNote(status) : nil)
            }
            Divider()
            Grid(alignment: .leading, horizontalSpacing: 12, verticalSpacing: 10) {
                GridRow {
                    Detail(symbol: entry.connected ? "paperplane.circle.fill" : "paperplane.circle", title: "BubbleDrop",
                           value: connectionText(entry), tint: entry.connected ? .green : nil)
                    Detail(symbol: status.wifi ? "wifi" : "wifi.slash", title: "Wi-Fi",
                           value: Text(status.wifi ? (status.wifiName ?? "Connected") : "Off"),
                           bars: status.wifi ? status.wifiBars : nil)
                }
                GridRow {
                    Detail(symbol: "cellularbars", title: "Mobile",
                           value: Text(status.carrier ?? "No Service"), bars: status.cellBars)
                    Detail(symbol: ringerSymbol(status.ringer), title: "Sound",
                           value: Text(ringerName(status.ringer)))
                }
                GridRow {
                    Detail(symbol: "antenna.radiowaves.left.and.right", title: "Bluetooth",
                           value: Text(!status.bluetoothOn ? "Off"
                               : status.accessories.isEmpty ? "On" : "\(status.accessories.count) connected"))
                    Detail(symbol: status.doNotDisturb ? "moon.fill" : "moon", title: "Focus",
                           value: Text(status.doNotDisturb ? "Do Not Disturb" : "Off"),
                           tint: status.doNotDisturb ? .indigo : nil)
                }
                if let hotspot = status.hotspot {
                    GridRow {
                        Detail(symbol: "personalhotspot", title: "Hotspot", value: Text(hotspot ? "On" : "Off"),
                               tint: hotspot ? .green : nil)
                        Color.clear.gridCellUnsizedAxes([.horizontal, .vertical])
                    }
                }
            }
            Spacer(minLength: 0)
            HStack(spacing: 8) {
                ActionButton(title: "Send File", symbol: "arrow.up.doc", action: "send")
                ActionButton(title: "Mirror", symbol: "rectangle.on.rectangle", action: "mirror")
                ActionButton(title: "Find", symbol: "speaker.wave.2.fill", action: "find")
            }
        }
    }
}

private struct DeviceRow: View {
    let device: Device
    let note: String?

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: device.symbol)
                .font(.system(size: 15, weight: .medium))
                .frame(width: 30, height: 30)
                .background(.quaternary, in: Circle())
            VStack(alignment: .leading, spacing: 4) {
                HStack(alignment: .firstTextBaseline) {
                    Text(device.name).font(.subheadline).lineLimit(1)
                    if device.charging {
                        Image(systemName: "bolt.fill").font(.caption2).foregroundStyle(.green)
                    }
                    Spacer()
                    if let note {
                        Text(note).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    }
                    Percent(level: device.level, font: .subheadline.weight(.medium))
                }
                GeometryReader { proxy in
                    ZStack(alignment: .leading) {
                        Capsule().fill(.quaternary)
                        Capsule().fill(device.color)
                            .frame(width: proxy.size.width * CGFloat(device.level ?? 0) / 100)
                            .widgetAccentable()
                    }
                }
                .frame(height: 4)
            }
        }
    }
}

private struct Detail: View {
    let symbol: String
    let title: String
    let value: Text
    var bars: Int?
    var tint: Color?

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: symbol, variableValue: bars.map { Double($0) / 4 })
                .frame(width: 18)
                .foregroundStyle(tint ?? .secondary)
            VStack(alignment: .leading, spacing: 0) {
                Text(title).font(.caption2).foregroundStyle(.secondary)
                value.font(.caption).lineLimit(1)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Opens bubbledrop://<action> in the app, which runs it (see PhoneDashboard).
private struct ActionButton: View {
    let title: String
    let symbol: String
    let action: String

    var body: some View {
        Link(destination: URL(string: "bubbledrop://\(action)")!) {
            Label(title, systemImage: symbol)
                .font(.caption.weight(.medium))
                .lineLimit(1)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 7)
                .background(.quaternary, in: Capsule())
        }
    }
}

private struct EmptyState: View {
    let symbol: String
    let title: String
    let message: String

    var body: some View {
        VStack(spacing: 6) {
            Image(systemName: symbol).font(.title2).foregroundStyle(.secondary)
            Text(title).font(.headline).lineLimit(1)
            Text(message)
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
