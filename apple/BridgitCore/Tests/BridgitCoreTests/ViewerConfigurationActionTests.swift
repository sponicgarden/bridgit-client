import Foundation
import XCTest
@testable import BridgitCore

final class ViewerConfigurationActionTests: XCTestCase {
    func testViewerRoutesPreserveIndependentConfigurationGrants() {
        for path in ["/v1/account", "/v1/linked-devices"] { XCTAssertEqual(HostRequest(method: "GET", path: path).route?.0, .configurationRead) }
        for (method, path) in [("POST", "/v1/account/sign-in"), ("DELETE", "/v1/account"), ("POST", "/v1/linked-devices"), ("DELETE", "/v1/linked-devices/pairings/device_123456"), ("POST", "/v1/linked-devices/attachment")] {
            XCTAssertEqual(HostRequest(method: method, path: path).route?.0, .configurationWrite)
        }
        XCTAssertNil(HostRequest(method: "DELETE", path: "/v1/linked-devices/attachment").route)
    }
    func testMalformedViewerInputsCannotBecomeImplicitDefaultsOrSecretsReads() throws {
        for request in [
            HostRequest(method: "GET", path: "/v1/account", query: ["session": "read"]),
            .init(method: "POST", path: "/v1/account/sign-in", body: ["session": "not accepted"]),
            .init(method: "POST", path: "/v1/linked-devices", body: ["setup": "invalid"]),
            .init(method: "POST", path: "/v1/linked-devices/attachment", body: [:]),
            .init(method: "POST", path: "/v1/linked-devices/pause", body: ["paused": 1]),
            .init(method: "PATCH", path: "/v1/linked-devices/pairings/device_123456", body: ["name": "  "]),
            .init(method: "PATCH", path: "/v1/linked-devices/pairings/device_123456", body: ["name": "Fixture", "token": "no"])
        ] { XCTAssertThrowsError(try ViewerConfigurationAction(request)) }
        if case .rename("device_123456", nil) = try ViewerConfigurationAction(.init(method: "PATCH", path: "/v1/linked-devices/pairings/device_123456", body: ["name": NSNull()])) {} else { XCTFail("Explicit null must clear the local alias") }
        if case .attach(nil) = try ViewerConfigurationAction(.init(method: "POST", path: "/v1/linked-devices/attachment", body: ["id": NSNull()])) {} else { XCTFail("Explicit null must detach") }
    }
    func testDevicesTabSettingHasStrictEnumAndMapReset() throws {
        let agent = HostAgent(id: "fixture", name: "Fixture", generation: "generation", tokenHash: "not-a-token", operations: [.configurationWrite], applets: [], paused: false, peer: nil)
        for value: Any in [true, 1, "grid", NSNull()] { XCTAssertThrowsError(try HostSettingChange(.init(method: "POST", path: "/v1/settings", body: ["field": "devices_tab", "value": value]), agent: agent)) }
        let list = try HostSettingChange(.init(method: "POST", path: "/v1/settings", body: ["field": "devices_tab", "value": "list"]), agent: agent)
        XCTAssertEqual(list.value as? String, "list")
        let reset = try HostSettingChange(.init(method: "DELETE", path: "/v1/settings/devices_tab"), agent: agent)
        XCTAssertEqual(reset.value as? String, "map")
    }
}
