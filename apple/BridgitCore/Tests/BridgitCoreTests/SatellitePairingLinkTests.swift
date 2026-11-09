import XCTest
@testable import BridgitCore
final class SatellitePairingLinkTests: XCTestCase {
    func testNumericTailnetBoundaryAndCanonicalPeerAddresses() {
        for text in ["100.64.0.1", "100.127.255.254", "fd7a:115c:a1e0::1", "FD7A:115C:A1E0::1", "::ffff:100.80.2.3"] { XCTAssertNotNil(TailnetAddress(text), text) }
        for text in ["100.63.255.255", "100.128.0.1", "127.0.0.1", "192.168.1.2", "fd7a:115c:a1e1::1", "example.invalid", "100.80.1.1.evil.invalid", "100.80.1.1:8080", "100.80.1.1 ", "fd7a:115c:a1e0::1%utun0"] { XCTAssertNil(TailnetAddress(text), text) }
        XCTAssertEqual(TailnetAddress("::ffff:100.80.2.3")?.value, "100.80.2.3")
    }
    func testActualPinnedSatelliteFormatRoundTripsWithoutFieldInjection() throws {
        let link = try SatellitePairingLink(address: XCTUnwrap(TailnetAddress("100.80.1.2")), port: 18471, claim: "sct_" + String(repeating: "a", count: 43), name: "Garage & #100%\n", install: "bgi_" + String(repeating: "a", count: 24))
        XCTAssertEqual(try SatellitePairingLink.parse(link.url.absoluteString), link)
        XCTAssertEqual(link.name, "Garage & #100%")
        for suffix in ["&host=100.80.2.3", "&claim=sct_" + String(repeating: "b", count: 43), "&port=1234"] { XCTAssertThrowsError(try SatellitePairingLink.parse(link.url.absoluteString + suffix)) }
        XCTAssertThrowsError(try SatellitePairingLink.parse(link.url.absoluteString.replacingOccurrences(of: "100.80.1.2", with: "127.0.0.1")))
        XCTAssertThrowsError(try SatellitePairingLink.parse(link.url.absoluteString.replacingOccurrences(of: "v=1", with: "v=2")))
    }
}
