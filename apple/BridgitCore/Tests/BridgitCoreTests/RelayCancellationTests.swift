import Foundation
import XCTest
@testable import BridgitCore

/// A private URLSession protocol. Tests never contact the relay or use stored credentials.
private final class DelayedRelayProtocol: URLProtocol {
    static let lock = NSLock()
    static var calls = 0
    private var work: DispatchWorkItem?
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "relay.invalid" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.lock.lock(); Self.calls += 1; Self.lock.unlock()
        let job = DispatchWorkItem { [weak self] in
            guard let self else { return }
            let response = HTTPURLResponse(url: self.request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            self.client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            self.client?.urlProtocol(self, didLoad: Data("{\"response\":{\"status\":200,\"body\":{\"ok\":true}}}".utf8))
            self.client?.urlProtocolDidFinishLoading(self)
        }
        work = job; DispatchQueue.global().asyncAfter(deadline: .now() + 0.15, execute: job)
    }
    override func stopLoading() { work?.cancel() }
    static func count() -> Int { lock.lock(); defer { lock.unlock() }; return calls }
}
final class RelayCancellationTests: XCTestCase {
    private func makeClient() -> (RelayClient, URLSession) {
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [DelayedRelayProtocol.self]
        let session = URLSession(configuration: config)
        let pairing = Pairing(base: URL(string: "https://relay.invalid/d/dev_ABCDEFGH12")!, deviceId: "dev_ABCDEFGH12",
                              token: "rly_fixture_never_real", name: "Fixture")
        return (RelayClient(pairing: pairing, http: session), session)
    }
    func testCancelledQueuedRequestNeverStarts() async throws {
        let before = DelayedRelayProtocol.count()
        let (client, session) = makeClient(); defer { session.invalidateAndCancel() }
        let first = Task { try await client.get("/v1/health") }
        // Wait until the first call holds the queue. The deadline turns a regression into a failure.
        for _ in 0..<100 {
            if DelayedRelayProtocol.count() > before { break }
            try await Task.sleep(nanoseconds: 5_000_000)
        }
        XCTAssertEqual(DelayedRelayProtocol.count(), before + 1)
        let second = Task { try await client.get("/v1/health") }
        second.cancel()
        _ = try await first.value
        do { _ = try await second.value; XCTFail("Cancelled request executed") }
        catch is CancellationError { }
        catch { XCTFail("Expected cancellation, got \(error)") }
        XCTAssertEqual(DelayedRelayProtocol.count(), before + 1)
    }
    func testExpiredDeadlineDoesNotSend() async throws {
        let before = DelayedRelayProtocol.count()
        let (client, session) = makeClient(); defer { session.invalidateAndCancel() }
        do {
            _ = try await client.call(method: "GET", path: "/v1/health", query: [:], body: nil, wait: 1, deadline: 1)
            XCTFail("Expired job executed")
        } catch RelayError.timeout { }
        XCTAssertEqual(DelayedRelayProtocol.count(), before)
    }
}
