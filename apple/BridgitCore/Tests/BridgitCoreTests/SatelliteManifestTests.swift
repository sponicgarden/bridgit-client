import XCTest
@testable import BridgitCore
final class SatelliteManifestTests: XCTestCase {
    func testReportedTypeNeverCreatesCapabilityAndUnknownFlagsStayUnknown() throws {
        let manifest = try SatelliteManifest(["name": "Fixture", "platform": " IOS ", "device_type": " TABLET ",
                                              "capabilities": [["id": "camera", "enabled": true]],
                                              "endpoints": [["method": "POST", "path": "/v1/camera/snapshot", "capability": "camera"]]], fallbackName: "Fallback")
        XCTAssertEqual(manifest.platform, "ios"); XCTAssertEqual(manifest.deviceType, "tablet")
        XCTAssertNil(manifest.capabilities.first?.ready)
        XCTAssertFalse(manifest.permits(.init(method: "POST", path: "/v1/camera/snapshot")))
        let unknown = try SatelliteManifest(["device_type": "future_device", "endpoints": []], fallbackName: "Fallback")
        XCTAssertEqual(unknown.deviceType, "future_device"); XCTAssertNil(unknown.platform)
        XCTAssertTrue(unknown.capabilities.isEmpty)
    }
    func testManifestCannotRelabelControlAsCameraOrAuthorizeUnlistedRoutes() throws {
        let object: [String: Any] = ["capabilities": [["id": "camera", "enabled": true, "ready": true]],
                                     "endpoints": [["method": "POST", "path": "/v1/camera/snapshot", "capability": "camera"],
                                                   ["method": "POST", "path": "/v1/settings", "capability": "camera"],
                                                   ["method": "GET", "path": "/v1/health", "capability": "status"]]]
        let manifest = try SatelliteManifest(object, fallbackName: "Fixture")
        XCTAssertTrue(manifest.permits(.init(method: "POST", path: "/v1/camera/snapshot")))
        XCTAssertFalse(manifest.permits(.init(method: "POST", path: "/v1/settings")))
        XCTAssertTrue(manifest.permits(.init(method: "GET", path: "/v1/health")))
        XCTAssertThrowsError(try SatelliteManifest(["endpoints": [["method": "POST", "path": "/v1/camera/snapshot", "capability": "status"]]], fallbackName: "Fixture"))
        XCTAssertNil(SatelliteForwardPolicy.operation(method: "POST", path: "/v1/satellite/claim"))
        XCTAssertNil(SatelliteForwardPolicy.operation(method: "POST", path: "/v1/applets/install"))
    }
    func testCallerKeysUseWholeIdentityAndKeyWithoutTruncationCollisions() {
        var first = HostAgent(id: "first", name: "First", generation: "generation1", tokenHash: "hash", operations: [.deviceRead], applets: [], paused: false, peer: nil)
        let sharedPrefix = String(repeating: "a", count: 128)
        let one = SatelliteForwardPolicy.forwardedIdempotencyKey(agent: first, key: sharedPrefix + "one")
        let two = SatelliteForwardPolicy.forwardedIdempotencyKey(agent: first, key: sharedPrefix + "two")
        XCTAssertNotEqual(one, two); XCTAssertLessThanOrEqual(one.count, 128)
        first.generation = "generation2"
        XCTAssertNotEqual(one, SatelliteForwardPolicy.forwardedIdempotencyKey(agent: first, key: sharedPrefix + "one"))
        XCTAssertEqual(SatelliteForwardPolicy.newID(name: "Garage phone", existing: ["sat:garage-phone"]), "sat:garage-phone-2")
    }
}
