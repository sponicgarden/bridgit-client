// swift-tools-version: 5.9
import PackageDescription
let package = Package(name: "BridgitSpeech", platforms: [.iOS(.v17), .macOS("15.0")],
    products: [.library(name: "BridgitSpeech", targets: ["BridgitSpeech"])],
    targets: [.target(name: "BridgitSpeech"), .testTarget(name: "BridgitSpeechTests", dependencies: ["BridgitSpeech"])])
