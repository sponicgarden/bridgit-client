import Network
import XCTest
@testable import BridgitCore
final class TailnetHTTPTests: XCTestCase {
    func testIncrementalUnambiguousJSONAndCanonicalTarget() throws {
        let header = "POST /v1/satellite/claim HTTP/1.1\r\nHost: 100.80.1.2:18471\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n"
        XCTAssertNil(try TailnetHTTPCodec.parse(Data(header.utf8)))
        let parsed = try XCTUnwrap(TailnetHTTPCodec.parse(Data((header + "{}").utf8)))
        XCTAssertEqual(try parsed.hostRequest.path, "/v1/satellite/claim")
        XCTAssertTrue(try parsed.hostRequest.body.isEmpty)
        XCTAssertThrowsError(try TailnetHTTPCodec.parse(Data((header + "{}x").utf8)))
        for request in ["GET http://evil.invalid/v1/health HTTP/1.1\r\nHost: x\r\n\r\n", "POST /v1/satellite/claim HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n", "POST /v1/satellite/claim HTTP/1.1\r\nHost: x\r\nContent-Length: 0\r\nContent-Length: 2\r\n\r\n", "GET /v1/health HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer fixture\r\nAuthorization: Bearer other\r\n\r\n"] {
            XCTAssertThrowsError(try TailnetHTTPCodec.parse(Data(request.utf8)))
        }
        let encoded = try XCTUnwrap(TailnetHTTPCodec.parse(Data("GET /v1/%73atellite/manifest HTTP/1.1\r\nHost: x\r\n\r\n".utf8)))
        XCTAssertThrowsError(try encoded.hostRequest)
    }
    func testTargetHeaderIsPreservedAndCannotConflictWithExplicitQuery() throws {
        let base = "Host: fixture\r\nX-Bridgit-Target: sat:garage\r\n\r\n"
        let header = try XCTUnwrap(TailnetHTTPCodec.parse(Data(("GET /v1/health HTTP/1.1\r\n" + base).utf8)))
        XCTAssertEqual(try header.hostRequest.query["target"] as? String, "sat:garage")
        let conflict = try XCTUnwrap(TailnetHTTPCodec.parse(Data(("GET /v1/health?target=sat:other HTTP/1.1\r\n" + base).utf8)))
        XCTAssertThrowsError(try conflict.hostRequest)
    }
    func testOwnerListenerCancelsInFlightWorkWhenPeerDisconnects() async throws {
        let entered = expectation(description: "Request reached owner handler")
        let cancelled = expectation(description: "Peer disconnect cancelled owner handler")
        let listener = TailnetHTTPListener(fixtureLoopbackHandler: { _, _ in
            entered.fulfill()
            do { try await Task.sleep(for: .seconds(8)); XCTFail("Disconnected request kept running.") }
            catch { cancelled.fulfill() }
            return .failure(CancellationError())
        }, cancelOnPeerEOF: true)
        addTeardownBlock { await listener.stop() }
        let port = try await listener.start(address: "127.0.0.1", port: 0)
        let connection = NWConnection(host: "127.0.0.1", port: try XCTUnwrap(.init(rawValue: port)), using: .tcp)
        defer { connection.cancel() }
        connection.start(queue: DispatchQueue(label: "cc.bridgit.owner-disconnect-test"))
        let request = Data("GET /v1/fixture HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".utf8)
        try await withCheckedThrowingContinuation { (sent: CheckedContinuation<Void, Error>) in
            connection.send(content: request, completion: .contentProcessed {
                if let error = $0 { sent.resume(throwing: error) } else { sent.resume() }
            })
        }
        await fulfillment(of: [entered], timeout: 2)
        connection.cancel()
        await fulfillment(of: [cancelled], timeout: 2)
    }

    func testActualOwnedLoopbackTransportFixtureAndProductionRefusesLoopback() async throws {
        let real = TailnetHTTPListener(handler: { _, _ in try! .json(["never": true]) })
        do { _ = try await real.start(address: "127.0.0.1", port: 18471); XCTFail("Production listener must reject LAN/loopback") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.code, "tailnet_required") }
        let fixture = TailnetHTTPListener(fixtureLoopbackHandler: { request, peer in
            XCTAssertEqual(peer, "127.0.0.1")
            return try! .json(["path": request.target])
        })
        let port = try await fixture.start(address: "127.0.0.1", port: 0)
        let ready = await fixture.isReady; XCTAssertTrue(ready)
        let config = URLSessionConfiguration.ephemeral; config.timeoutIntervalForRequest = 5
        let session = URLSession(configuration: config)
        defer { session.invalidateAndCancel() }
        let (data, response) = try await session.data(from: URL(string: "http://127.0.0.1:\(port)/v1/fixture")!)
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
        XCTAssertEqual((try JSONSerialization.jsonObject(with: data) as? [String: String])?["path"], "/v1/fixture")
        await fixture.stop()
        let stopped = await fixture.isReady; XCTAssertFalse(stopped)
    }
}
