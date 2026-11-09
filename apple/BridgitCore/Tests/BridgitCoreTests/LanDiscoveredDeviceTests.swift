import XCTest
@testable import BridgitCore

final class LanDiscoveredDeviceTests: XCTestCase {
    private func observation(_ host: String = "192.168.2.2", name: String? = nil, source: LanDiscoveryObservation.Source = .mdns,
                             service: String = "_http._tcp.", model: String? = nil) -> LanDiscoveryObservation {
        .init(host: host, source: source, service: service, port: nil, name: name, advertisedModel: model, observedAt: 1)
    }
    func testGroupingPrefersFriendlyNameAndKeepsOnlyUniqueHints() throws {
        let devices = LanDiscoveredDevice.devices([
            observation(name: "abcdef012345-Thing", model: "M1"),
            observation(name: "Kitchen", service: "_rtsp._tcp.", model: "M1"),
            observation(name: "Kitchen", service: "_rtsp._tcp."),
            observation("192.168.2.3", source: .onvif, service: "onvif_candidate"),
            observation("8.8.8.8", name: "Invalid")])
        XCTAssertEqual(devices.count, 2)
        let kitchen = try XCTUnwrap(devices.first { $0.name == "Kitchen" })
        XCTAssertEqual(kitchen.services, ["_http._tcp.", "_rtsp._tcp."])
        XCTAssertEqual(kitchen.modelHints, ["M1"])
        XCTAssertTrue(kitchen.cameraCandidate)
        XCTAssertTrue(devices.allSatisfy(\.cameraCandidate))
        XCTAssertEqual(LanDiscoveredDevice.serviceLabel("_rtsp._tcp."), "Camera stream")
        XCTAssertEqual(LanDiscoveredDevice.serviceLabel("urn:schemas:InternetGatewayDevice:1"), "Router service")
    }
    func testPartialScanDoesNotErasePresenceAndFreshnessExpires() throws {
        let device = try XCTUnwrap(LanDiscoveredDevice.devices([observation(name: "Kitchen")]).first)
        var history = LanDiscoveryHistory()
        _ = history.update(scope: "network-a", observed: [device], reliable: true, now: 1000)
        let partial = history.update(scope: "network-a", observed: [], reliable: false, now: 2000)
        XCTAssertTrue(try XCTUnwrap(partial.first).present)
        XCTAssertTrue(try XCTUnwrap(partial.first).connected)
        XCTAssertFalse(try XCTUnwrap(history.read(scope: "network-a", now: 121001).first).connected)
        XCTAssertTrue(try XCTUnwrap(history.read(scope: "network-a", now: 121001).first).present)
        XCTAssertFalse(try XCTUnwrap(history.read(scope: "network-a", now: 999).first).connected)
        let missed = history.update(scope: "network-a", observed: [], reliable: true, now: 122000)
        XCTAssertFalse(try XCTUnwrap(missed.first).present)
        XCTAssertTrue(history.read(scope: "network-b", now: 2000).isEmpty)
    }
    func testBoundedHistorySurvivesEncodingWithoutCrossNetworkLeakage() throws {
        var history = LanDiscoveryHistory()
        let devices = (1...300).map { i in LanDiscoveredDevice(host: "10.0.\(i / 256).\(i % 256)", name: "Device", services: [], cameraCandidate: false, modelHints: []) }
        for i in 0..<10 { _ = history.update(scope: "network-\(i)", observed: devices, reliable: true, now: Int64(i + 1)) }
        let restored = try JSONDecoder().decode(LanDiscoveryHistory.self, from: JSONEncoder().encode(history))
        XCTAssertEqual(restored.scopes.count, 8)
        XCTAssertTrue(restored.read(scope: "network-0", now: 10).isEmpty)
        XCTAssertEqual(restored.read(scope: "network-9", now: 10).count, 256)
    }
    func testMalformedNegativeHistoryTimestampCannotOverflowFreshness() throws {
        let json = Data("""
        {"scopes":{"network":{"updated":0,"devices":[{"host":"192.168.2.2","name":"Device","services":[],"cameraCandidate":false,"modelHints":[],"connected":true,"lastSeen":-9223372036854775808,"present":true}]}}}
        """.utf8)
        let history = try JSONDecoder().decode(LanDiscoveryHistory.self, from: json)
        XCTAssertFalse(try XCTUnwrap(history.read(scope: "network", now: Int64.max).first).connected)
    }

}
