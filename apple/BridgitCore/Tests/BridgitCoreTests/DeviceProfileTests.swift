import XCTest
@testable import BridgitCore

final class DeviceProfileTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)
    private func profile() -> DeviceProfile {
        DeviceProfile(profileRevision: 1, observedAt: now.addingTimeInterval(-1), expiresAt: now.addingTimeInterval(60),
                      device: .init(installID: "bgi_example", name: "Same name", deviceType: "computer", platform: "macos",
                                    osVersion: "15.0", appVersion: "local", apiRevision: 0, services: ["host"], hardware: ["camera": "unknown"]),
                      availability: .init(state: "online", inForeground: true),
                      capabilities: ["v1.camera": .init(runtimeID: "camera", implemented: true, supported: true,
                                                       enabled: true, ready: true, agentAllowed: true, callable: true)])
    }
    func testSharedSatelliteFixtureHasNoCameraAndNoCallableOperation() throws {
        let url = try XCTUnwrap(Bundle.module.url(forResource: "satellite-no-camera", withExtension: "json", subdirectory: "Fixtures"))
        let p = try DeviceProfile.decode(Data(contentsOf: url))
        XCTAssertEqual(p.device.hardware["camera"], "absent")
        XCTAssertEqual(p.link?.target, "sat:fixture")
        XCTAssertFalse(p.canCall("v1.camera", at: p.observedAt))
    }
    func testRoundTripPreservesCatalogAndRuntimeIDs() throws {
        let p = profile(); let decoded = try DeviceProfile.decode(p.encoded())
        XCTAssertEqual(p, decoded); XCTAssertTrue(decoded.canCall("v1.camera", at: now))
        XCTAssertFalse(decoded.canCall("camera", at: now))
    }
    func testStaleOfflineFutureVersionAndPausedFailClosed() {
        var p = profile(); p.expiresAt = now
        XCTAssertFalse(p.canCall("v1.camera", at: now))
        p = profile(); p.availability.state = "offline"
        XCTAssertFalse(p.canCall("v1.camera", at: now))
        p = profile(); p.schemaVersion = 99
        XCTAssertFalse(p.canCall("v1.camera", at: now))
        p = profile(); p.observedAt = now.addingTimeInterval(1)
        XCTAssertFalse(p.canCall("v1.camera", at: now))
        p = profile(); p.link = .init(id: "lnk_a", role: "satellite", target: "sat:a", allowedServices: ["sensors"], state: "paused")
        XCTAssertFalse(p.canCall("v1.camera", at: now))
    }
    func testReportedCallableCannotOverrideAnyGate() {
        for key in [\ProfileCapability.implemented, \.supported, \.enabled, \.ready, \.agentAllowed, \.callable] {
            var p = profile(); p.capabilities["v1.camera"]![keyPath: key] = false
            XCTAssertFalse(p.canCall("v1.camera", at: now))
        }
    }
    func testForegroundConstraintsAndUnknownBlockerFailClosed() {
        var p = profile(); p.availability.inForeground = false
        p.capabilities["v1.camera"]!.constraints.execution = ["foreground"]
        XCTAssertFalse(p.canCall("v1.camera", at: now))
        p.availability.inForeground = true
        XCTAssertTrue(p.canCall("v1.camera", at: now))
        p.capabilities["v1.camera"]!.blockedReason = "future_block"
        XCTAssertFalse(p.canCall("v1.camera", at: now))
    }
    func testBackgroundReadLeaseAllowsOnlyListedGETWhileFresh() {
        var p = profile(); p.availability.inForeground = false
        let get = ProfileOperation(method: "GET", path: "/v1/status")
        let post = ProfileOperation(method: "POST", path: "/v1/mcp")
        p.capabilities["v1.camera"]!.constraints.execution = ["foreground", "background_read"]
        p.capabilities["v1.camera"]!.operations = [get, post]
        XCTAssertTrue(p.canCall("v1.camera", operation: get, at: now))
        XCTAssertFalse(p.canCall("v1.camera", operation: post, at: now))
        XCTAssertFalse(p.canCall("v1.camera", operation: .init(method: "GET", path: "/unlisted"), at: now))
        p.expiresAt = now
        XCTAssertFalse(p.canCall("v1.camera", operation: get, at: now))
    }
    func testViewFlavorDoesNotImplySensorOrControlAccess() {
        let link = DeviceLink(id: "lnk_view", role: "satellite", target: nil, allowedServices: ["view", "future_service"], state: "active")
        XCTAssertEqual(link.flavors, ["view"]); XCTAssertNil(link.target)
    }
    func testUnknownReasonAndFieldsDecodeWithoutBecomingCallable() throws {
        var p = profile(); p.capabilities["v1.camera"]!.ready = false
        p.capabilities["v1.camera"]!.blockedReason = "future_block"
        var object = try JSONSerialization.jsonObject(with: p.encoded()) as! [String: Any]
        object["future_field"] = ["anything": true]
        let decoded = try DeviceProfile.decode(JSONSerialization.data(withJSONObject: object))
        XCTAssertEqual(decoded.capabilities["v1.camera"]?.blockedReason, "future_block")
        XCTAssertFalse(decoded.canCall("v1.camera", at: now))
    }
    func testFractionalDatesAndMissingGrants() throws {
        let data = try profile().encoded(); let text = String(decoding: data, as: UTF8.self)
        XCTAssertNoThrow(try DeviceProfile.decode(Data(text.replacingOccurrences(of: "Z\"", with: ".123Z\"").utf8)))
        let missing = text.replacingOccurrences(of: "\"agent_allowed\" : true,", with: "")
        XCTAssertThrowsError(try DeviceProfile.decode(Data(missing.utf8)))
    }
}
