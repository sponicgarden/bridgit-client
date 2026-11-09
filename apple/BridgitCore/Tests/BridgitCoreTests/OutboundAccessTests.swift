import Foundation
import XCTest
@testable import BridgitCore

private final class OutboundTransportFixture: URLProtocol {
    private static let lock = NSLock()
    private static var count = 0

    static func reset() { lock.withLock { count = 0 } }
    static var requests: Int { lock.withLock { count } }
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.lock.withLock { Self.count += 1 }
        let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(#"{"linked":false,"deletion_pending":true}"#.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

private final class OutboundPauseFixture: @unchecked Sendable {
    private let lock = NSLock()
    private var value = true
    var paused: Bool { lock.withLock { value } }
    func resume() { lock.withLock { value = false } }
}

final class OutboundAccessTests: XCTestCase {
    func testPausedHTTPSAndWebSocketNeverStartTransportAndExistingClientCanResume() async throws {
        OutboundTransportFixture.reset()
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [OutboundTransportFixture.self]
        let http = URLSession(configuration: config)
        defer { http.invalidateAndCancel() }
        let pause = OutboundPauseFixture()
        let outbound = OutboundAccess(paused: { pause.paused })
        let base = try XCTUnwrap(URL(string: "https://offline.invalid"))
        let install = InstallClient(signer: try .ephemeral(), base: base, http: http, outboundAccess: outbound)
        let pair = Pairing(base: base, deviceId: "fixture", token: "fixture-token", name: "Fixture")
        let relay = RelayClient(pairing: pair, http: http, outboundAccess: outbound)
        let host = URLSessionHostTransport(http: http, outboundAccess: outbound)
        let request = URLRequest(url: base)

        do { _ = try await install.turnOffWebView(); XCTFail("Install call reached a paused transport") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.code, "external_paused") }
        do { _ = try await relay.get("/v1/health"); XCTFail("Relay client reached a paused transport") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.code, "external_paused") }
        do { _ = try await host.post(request); XCTFail("Relay host reached a paused transport") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.code, "external_paused") }
        do { try await host.open(request); XCTFail("Paused relay host opened a WebSocket") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.code, "external_paused") }
        XCTAssertEqual(OutboundTransportFixture.requests, 0)

        pause.resume()
        let deletionPending = try await install.turnOffWebView()
        XCTAssertTrue(deletionPending)
        let (_, status) = try await host.post(request)
        XCTAssertEqual(status, 200)
        XCTAssertEqual(OutboundTransportFixture.requests, 2)
    }

    func testPausedPinnedRequestsFailBeforeResolutionAndTailnetStillValidatesItsDestination() async throws {
        let access = OutboundAccess(paused: { true })
        let request = ConnectionHTTPRequest(address: "https://offline.invalid", allowPrivate: false, headers: [:], body: Data())
        do {
            _ = try await PinnedConnectionHTTP.send(request, outboundAccess: access) {}
            XCTFail("Paused connection should never resolve its destination")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "external_paused") }
        let wrongTailnet = ConnectionHTTPRequest(callbackAddress: "http://example.com/hook", destination: .tailnet, headers: [:], body: Data())
        do {
            _ = try await PinnedConnectionHTTP.send(wrongTailnet, outboundAccess: access) {}
            XCTFail("A claimed Tailnet destination cannot bypass URL validation")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "invalid_connection") }
    }
}
