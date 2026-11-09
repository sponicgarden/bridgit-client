import XCTest
@testable import BridgitCore

final class LanDiscoveryObservationTests: XCTestCase {
    func testUntrustedNamesCannotInjectControlOrDirectionalCharacters() {
        XCTAssertEqual(LanDiscoveryObservation.cleanName("  AABBCCDDEEFF@Living\u{202E}\nroom  "), "Livingroom")
        let long = LanDiscoveryObservation.cleanName(String(repeating: "😀", count: 80))
        XCTAssertEqual(long.utf16.count, 100)
        XCTAssertFalse(long.contains("�"))
    }

    func testOffLinkAndDuplicateHintsDoNotBecomeExtraDevices() async throws {
        let store = LanDiscoveryAccumulator(network: .init(name: "fixture", index: 4, address: "192.168.2.1", prefix: 24))
        let outside = await store.add(host: "192.168.3.2", source: .ssdp, service: "upnp")
        let selfAddress = await store.add(host: "192.168.2.1", source: .ssdp, service: "upnp")
        let first = await store.add(host: "192.168.2.2", source: .mdns, service: "_http._tcp.", port: 80, now: Date(timeIntervalSince1970: 42))
        let duplicate = await store.add(host: "192.168.2.2", source: .mdns, service: "_http._tcp.", port: 80)
        XCTAssertFalse(outside); XCTAssertFalse(selfAddress); XCTAssertTrue(first); XCTAssertFalse(duplicate)
        let values = await store.snapshot()
        XCTAssertEqual(values.count, 1)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(values[0])) as? [String: Any])
        XCTAssertEqual(object["verified"] as? Bool, false)
        XCTAssertEqual(object["enrolled"] as? Bool, false)
        XCTAssertEqual(object["observed_at"] as? Int, 42000)
    }

    func testDiscoveryFloodRemainsBounded() async {
        let store = LanDiscoveryAccumulator(network: .init(name: "fixture", index: 4, address: "10.0.0.1", prefix: 24))
        for index in 0..<400 {
            _ = await store.add(host: "10.0.0.2", source: .mdns, service: "service\(index)")
        }
        let values = await store.snapshot()
        XCTAssertEqual(values.count, 256)
    }
}
