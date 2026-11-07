// swift-tools-version: 5.9
import PackageDescription
let package = Package(name: "BridgitAppletRuntime", platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "BridgitAppletRuntime", targets: ["BridgitAppletRuntime"])],
    targets: [.target(name: "BridgitAppletRuntime"), .testTarget(name: "BridgitAppletRuntimeTests", dependencies: ["BridgitAppletRuntime"])])
