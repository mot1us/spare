// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "SpareMac",
    platforms: [.macOS(.v13)],
    products: [.executable(name: "Spare", targets: ["SpareMac"])],
    targets: [
        .target(name: "CSpare", linkerSettings: [.linkedLibrary("z")]),
        .target(name: "SpareCore", dependencies: ["CSpare"]),
        .executableTarget(name: "SpareMac", dependencies: ["SpareCore", "CSpare"]),
        .testTarget(name: "SpareCoreTests", dependencies: ["SpareCore"]),
    ]
)
