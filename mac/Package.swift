// swift-tools-version:6.0
import PackageDescription

let swift5: [SwiftSetting] = [.unsafeFlags(["-swift-version", "5"])]

let package = Package(
    name: "BubbleDrop",
    platforms: [.macOS(.v15)],
    products: [
        .executable(name: "BubbleDrop", targets: ["BubbleDrop"]),
        .executable(name: "bubbledrop-cli", targets: ["bubbledrop-cli"]),
    ],
    targets: [
        .target(name: "BubbleDropCore", swiftSettings: swift5),
        .executableTarget(name: "BubbleDrop", dependencies: ["BubbleDropCore"], swiftSettings: swift5),
        .executableTarget(name: "bubbledrop-cli", dependencies: ["BubbleDropCore"], swiftSettings: swift5),
    ]
)
