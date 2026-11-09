import Foundation
import XCTest
@testable import BridgitCore

final class CallbackRulesTests: XCTestCase {
    func testDestinationAndSecrets() throws {
        XCTAssertEqual(try CallbackRules.destination("http://100.80.1.2:8080/hook", tailnetDomain: nil, allowInternet: false), .tailnet)
        XCTAssertEqual(try CallbackRules.destination("https://phone.example.ts.net/hook", tailnetDomain: "example.ts.net", allowInternet: false), .tailnet)
        XCTAssertEqual(try CallbackRules.destination("https://example.com/hook", tailnetDomain: nil, allowInternet: true), .internet)
        for bad in ["http://192.168.1.3/hook", "https://other.ts.net/hook", "https://user:pass@example.com/", "https://example.com/#part", "http://example.com/"] {
            XCTAssertThrowsError(try CallbackRules.destination(bad, tailnetDomain: "example.ts.net", allowInternet: true), bad)
        }
        XCTAssertTrue(CallbackRules.validSecret("cbs_" + String(repeating: "a", count: 43)))
        XCTAssertFalse(CallbackRules.validSecret("cbs_" + String(repeating: "a", count: 42)))
        XCTAssertFalse(CallbackRules.validSecret("cbs_" + String(repeating: "a", count: 43) + "\n"))
        XCTAssertFalse(CallbackRules.validSecret("cbs_" + String(repeating: "a", count: 43) + "\r\n"))
        XCTAssertFalse(CallbackRules.validSecret("cbs_" + String(repeating: "a", count: 42) + "é"))
        XCTAssertTrue(CallbackRules.allowsResolvedAddress("100.80.1.2", destination: .tailnet))
        XCTAssertFalse(CallbackRules.allowsResolvedAddress("192.168.1.2", destination: .tailnet))
        XCTAssertFalse(CallbackRules.allowsResolvedAddress("100.80.1.2", destination: .internet))
        XCTAssertTrue(CallbackRules.allowsResolvedAddress("8.8.8.8", destination: .internet))
        XCTAssertTrue(CallbackRules.allowsResolvedAddress("::ffff:100.80.1.2", destination: .tailnet))
        XCTAssertFalse(CallbackRules.allowsResolvedAddress("::ffff:192.168.1.2", destination: .tailnet))
        XCTAssertFalse(CallbackRules.allowsResolvedAddress("64:ff9b::6450:102", destination: .tailnet))
        XCTAssertFalse(CallbackRules.allowsResolvedAddress("64:ff9b::6450:102", destination: .internet))
        XCTAssertThrowsError(try CallbackRules.validateSendURL("http://100.80.1.2/hook", destination: .internet))
        XCTAssertThrowsError(try CallbackRules.validateSendURL("https://other.ts.net/hook", destination: .internet))
        XCTAssertThrowsError(try CallbackRules.validateSendURL("https://other.ts.net./hook", destination: .internet))
    }
    func testPinnedAndroidGoldenSignatureAndRawRequest() throws {
        let body = Data(#"{"event":{"id":"42","type":"interaction.answered","at":"2026-10-27T12:00:00Z","data":{}},"agent":"Grok"}"#.utf8)
        XCTAssertEqual(CallbackRules.signature(secret: "cbs_test-secret-0123456789", timestamp: 1761566400, body: body), "sha256=117468517fecab256d0a734748b2c0ce3d1a5a3378f9935301a018885022d6f0")
        let request = try CallbackRules.request(address: "https://example.com/hook", destination: .internet, secret: "cbs_" + String(repeating: "a", count: 43), timestamp: 1761566400, eventID: "42", body: body, appVersion: "1.0")
        XCTAssertThrowsError(try CallbackRules.request(address: "https://example.com", destination: .internet, secret: "cbs_" + String(repeating: "a", count: 43), timestamp: 1, eventID: "١", body: body, appVersion: "1"))
        XCTAssertEqual(request.body, body)
        XCTAssertEqual(request.headers["X-Bridgit-Timestamp"], "1761566400")
        XCTAssertEqual(request.headers["X-Bridgit-Event-Id"], "42")
        XCTAssertEqual(request.headers["X-Bridgit-Signature"], CallbackRules.signature(secret: "cbs_" + String(repeating: "a", count: 43), timestamp: 1761566400, body: body))
        XCTAssertEqual(request.headers["X-Bridget-Signature"], request.headers["X-Bridgit-Signature"])
    }

    func testSignatureAndRetryQueue() {
        XCTAssertEqual(CallbackRules.signature(secret: "key", timestamp: 123, body: Data("body".utf8)), "sha256=6db85a64c3a5ac19dfbd3de4208b151bb6de9ae6375ea71af20ab32b463df8d2")
        XCTAssertEqual((1...4).map { CallbackRules.nextDelay(after: $0) }, [10, 60, 300, nil])
        var queue = CallbackQueue<Int>()
        for i in 0...500 { _ = queue.add(.init(agentID: "a", value: i, attempt: 1, due: Date(timeIntervalSince1970: Double(i)))) }
        _ = queue.add(.init(agentID: "b", value: 1, attempt: 1, due: Date(timeIntervalSince1970: 0)))
        XCTAssertEqual(queue.items.filter { $0.agentID == "a" }.count, 500)
        XCTAssertEqual(queue.takeDue(now: Date(timeIntervalSince1970: 0))?.agentID, "b")
        queue.remove(agentID: "a"); XCTAssertTrue(queue.items.isEmpty)
    }
}
