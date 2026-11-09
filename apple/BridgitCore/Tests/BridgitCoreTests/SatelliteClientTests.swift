import XCTest
@testable import BridgitCore
final class SatelliteClientTests: XCTestCase {
    func testFixedChunkedAndClosedResponsesRemainBoundedAndUnambiguous() throws {
        let fixed = Data("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nContent-Type: application/json\r\n\r\n{}".utf8)
        XCTAssertEqual(try SatelliteHTTPResponse.parse(fixed, complete: false)?.data, Data("{}".utf8))
        let chunked = Data("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2;fixture=1\r\n{}\r\n0\r\n\r\n".utf8)
        for count in 0..<chunked.count { XCTAssertNil(try SatelliteHTTPResponse.parse(Data(chunked.prefix(count)), complete: false)) }
        XCTAssertEqual(try SatelliteHTTPResponse.parse(chunked, complete: false)?.data, Data("{}".utf8))
        XCTAssertEqual(try SatelliteHTTPResponse.parse(Data("HTTP/1.0 200 OK\r\n\r\n{}".utf8), complete: true)?.data, Data("{}".utf8))
        for response in ["HTTP/1.1 200 OK\r\nContent-Length: 2\r\nContent-Length: 2\r\n\r\n{}", "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nTransfer-Encoding: chunked\r\n\r\n{}", "HTTP/1.1 200 OK\r\nContent-Length: 99999999\r\n\r\n", "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nGG\r\n{}\r\n0\r\n\r\n", "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: 2\r\n\r\n{}"] {
            XCTAssertThrowsError(try SatelliteHTTPResponse.parse(Data(response.utf8), complete: true))
        }
    }
    func testActualClientDoesNotFollowRedirectOrAcceptConnectorToken() async throws {
        let fixture = TailnetHTTPListener(fixtureLoopbackHandler: { _, _ in
            try! .json(["error": "fixture_redirect"], status: 302).withFixtureLocation()
        })
        let port = try await fixture.start(address: "127.0.0.1", port: 0)
        let address = try XCTUnwrap(TailnetAddress("100.80.1.2"))
        let link = try SatellitePairingLink(address: address, port: port, claim: "sct_" + String(repeating: "a", count: 43), name: "Fixture")
        let client = SatelliteClient(transport: TailnetHTTPTransport(fixtureLoopback: true))
        do { _ = try await client.claim(link, hubName: "Fixture"); XCTFail("Redirect is not a claim") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.status, 302) }
        do {
            _ = try await TailnetHTTPTransport(fixtureLoopback: true).call(address: address, port: port, request: .init(method: "GET", path: "/v1/satellite/manifest"), key: "rly_fixture_0123456789abcdef")
            XCTFail("A relay token must not become a direct key")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "invalid_satellite_key") }
        await fixture.stop()
    }
}
private extension HostResponse {
    func withFixtureLocation() -> HostResponse {
        var copy = self; copy.headers["Location"] = "https://must-never-resolve.invalid/"; return copy
    }
}
