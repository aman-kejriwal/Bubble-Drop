import Foundation

/// Body of `POST /api/v2/status` (phone → Mac): what the desktop widget shows about the phone.
public struct PhoneStatus: Codable, Sendable, Equatable {
    /// 0–100.
    public var battery: Int
    public var charging: Bool
    /// Minutes until full, while charging and when the phone can tell.
    public var minutesToFull: Int?
    /// Battery saver is on.
    public var powerSaver: Bool?
    public var bluetoothOn: Bool
    /// Bluetooth accessories connected to the phone.
    public var accessories: [Accessory]
    /// Whether the phone is on Wi-Fi.
    public var wifi: Bool
    /// The Wi-Fi network's name, when the phone may read it.
    public var wifiName: String?
    /// Wi-Fi and mobile signal, 0–4 bars.
    public var wifiBars: Int?
    public var carrier: String?
    public var cellBars: Int?
    /// `ring`, `vibrate` or `silent`.
    public var ringer: String
    public var doNotDisturb: Bool
    /// The phone's Wi-Fi hotspot is on. Optional: phones on older builds don't send it.
    public var hotspot: Bool?

    public struct Accessory: Codable, Sendable, Equatable, Hashable {
        public var name: String
        /// `headphones`, `watch`, `speaker`, `car`, `input` or `other`.
        public var kind: String
        /// 0–100, when the accessory reports it.
        public var battery: Int?

        public init(name: String, kind: String, battery: Int?) {
            self.name = name
            self.kind = kind
            self.battery = battery
        }
    }

    public init(battery: Int, charging: Bool, minutesToFull: Int?, powerSaver: Bool?, bluetoothOn: Bool,
                accessories: [Accessory], wifi: Bool, wifiName: String?, wifiBars: Int?, carrier: String?,
                cellBars: Int?, ringer: String, doNotDisturb: Bool, hotspot: Bool? = nil) {
        self.battery = battery
        self.charging = charging
        self.minutesToFull = minutesToFull
        self.powerSaver = powerSaver
        self.bluetoothOn = bluetoothOn
        self.accessories = accessories
        self.wifi = wifi
        self.wifiName = wifiName
        self.wifiBars = wifiBars
        self.carrier = carrier
        self.cellBars = cellBars
        self.ringer = ringer
        self.doNotDisturb = doNotDisturb
        self.hotspot = hotspot
    }
}

/// What the Mac app hands the widget: the paired phone to show and how it was last heard from.
/// Written to a folder the sandboxed widget may read (see `WidgetSnapshot.directory`).
public struct WidgetSnapshot: Codable, Sendable, Equatable {
    public var phoneName: String?
    public var status: PhoneStatus?
    /// When the phone last reported its status.
    public var statusAt: Date?
    /// Whether the phone is visible to BubbleDrop on this Mac's network right now.
    public var nearby: Bool
    /// Whether BubbleDrop on the Mac is running and receiving.
    public var macReceiving: Bool

    public init(phoneName: String?, status: PhoneStatus?, statusAt: Date?, nearby: Bool, macReceiving: Bool) {
        self.phoneName = phoneName
        self.status = status
        self.statusAt = statusAt
        self.nearby = nearby
        self.macReceiving = macReceiving
    }

    /// The phone sends its status every minute; this long without one means it's gone.
    public static let staleAfter: TimeInterval = 150

    /// Connected on BubbleDrop: nearby and reporting.
    public func isConnected(at date: Date = Date()) -> Bool {
        guard nearby, macReceiving, let statusAt else { return false }
        return date.timeIntervalSince(statusAt) < Self.staleAfter
    }

    /// `~/Library/Application Support/BubbleDrop/Widget`. Only this folder is readable by the widget,
    /// never the parent, which holds the device identity and pairings.
    public static func directory(home: URL) -> URL {
        home.appendingPathComponent("Library/Application Support/BubbleDrop/Widget", isDirectory: true)
    }

    public static func file(home: URL) -> URL {
        directory(home: home).appendingPathComponent("status.json")
    }

    public static func load(home: URL) -> WidgetSnapshot? {
        guard let data = try? Data(contentsOf: file(home: home)) else { return nil }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return try? decoder.decode(WidgetSnapshot.self, from: data)
    }

    public func save(home: URL) throws {
        try FileManager.default.createDirectory(at: Self.directory(home: home), withIntermediateDirectories: true)
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        try encoder.encode(self).write(to: Self.file(home: home), options: .atomic)
    }
}

/// Body of `POST /api/v2/find` and `POST /api/v2/mirror/request` (Mac → phone): no fields.
public struct EmptyRequest: Codable, Sendable {
    public init() {}
}
