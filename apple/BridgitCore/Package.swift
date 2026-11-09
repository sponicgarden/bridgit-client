// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "BridgitCore",
    platforms: [.iOS(.v17), .macOS("15.0")],
    products: [.library(name: "BridgitCore", targets: ["BridgitCore"])],
    targets: [
        .target(name: "BridgitCore", resources: [.copy("Resources/DeviceBrands")]),
        .testTarget(name: "BridgitCoreTests", dependencies: ["BridgitCore"], resources: [.copy("Fixtures")])
    ]
)
