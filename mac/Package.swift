// swift-tools-version:6.0
import PackageDescription

let swift5: [SwiftSetting] = [.unsafeFlags(["-swift-version", "5"])]

let package = Package(
    name: "MyDrop",
    platforms: [.macOS(.v15)],
    products: [
        .executable(name: "MyDrop", targets: ["MyDrop"]),
        .executable(name: "mydrop-cli", targets: ["mydrop-cli"]),
    ],
    targets: [
        .target(name: "MyDropCore", swiftSettings: swift5),
        .executableTarget(name: "MyDrop", dependencies: ["MyDropCore"], swiftSettings: swift5),
        .executableTarget(name: "mydrop-cli", dependencies: ["MyDropCore"], swiftSettings: swift5),
    ]
)
