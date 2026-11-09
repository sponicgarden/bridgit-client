import Foundation
import XCTest
@testable import BridgitCore
final class HostIdempotencyTests: XCTestCase {
    private func store() throws -> AppletStore {
        let path = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: path) }
        return try AppletStore(directory: path)
    }
    func testCacheCompactionKeepsDuplicateProtectionWithoutThePayload() async throws {
        let store = try store(), prefix = "host-idempotency:fixture:g:"
        _ = try await store.reserveHostAction(prefix + "one", ownerPrefix: prefix, fingerprint: "first")
        try await store.completeHostAction(prefix + "one", result: ["fingerprint": "first", "body_base64": String(repeating: "a", count: 512), "status": 201], cacheLimit: 100)
        let previous = try await store.reserveHostAction(prefix + "one", ownerPrefix: prefix, fingerprint: "different")
        let value = try JSONSerialization.jsonObject(with: Data(try XCTUnwrap(previous).utf8)) as! [String: Any]
        XCTAssertEqual(value["fingerprint"] as? String, "first")
        XCTAssertEqual(value["result_expired"] as? Bool, true)
        XCTAssertNil(value["body_base64"])
    }
    func testRetryQuotaExpiresAndNeverDropsRecentReservationsToAdmitMoreWork() async throws {
        let store = try store(), prefix = "host-idempotency:fixture:g:", now = Date(timeIntervalSince1970: 1000)
        for index in 0..<HostIdempotencyPolicy.perCallerLimit {
            _ = try await store.reserveHostAction(prefix + String(index), ownerPrefix: prefix, fingerprint: String(index), now: now)
        }
        do { _ = try await store.reserveHostAction(prefix + "overflow", ownerPrefix: prefix, fingerprint: "overflow", now: now); XCTFail("Quota evicted a recent key") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.code, "action_history_full") }
        let existing = try await store.reserveHostAction(prefix + "0", ownerPrefix: prefix, fingerprint: "0", now: now)
        XCTAssertNotNil(existing, "Retries must still work at the quota")
        let expired = try await store.reserveHostAction(prefix + "0", ownerPrefix: prefix, fingerprint: "new", now: now.addingTimeInterval(HostIdempotencyPolicy.retentionSeconds + 1))
        XCTAssertNil(expired, "An expired retry window must release capacity")
    }
    func testPruningCardAlsoCompactsItsCachedResponse() async throws {
        let store = try store(), prefix = "host-idempotency:fixture:g:", input: [String: Any] = ["kind": "notice", "title": "Fixture", "no_takeover": true]
        let first = try await store.createInteraction(owner: "fixture", input: input, now: Date(timeIntervalSince1970: 1000))
        let id = first["interaction_id"] as! String
        _ = try await store.reserveHostAction(prefix + "first", ownerPrefix: prefix, fingerprint: "first")
        try await store.completeHostAction(prefix + "first", result: ["fingerprint": "first", "body_base64": "cHJpdmF0ZQ==", "interaction_id": id, "status": 201])
        _ = try await store.cancelInteraction(owner: "fixture", id: id)
        for _ in 0..<202 {
            let card = try await store.createInteraction(owner: "fixture", input: input)
            _ = try await store.cancelInteraction(owner: "fixture", id: card["interaction_id"] as! String)
        }
        let stored = try await store.state(prefix + "first")
        let previous = try XCTUnwrap(stored)
        let value = try JSONSerialization.jsonObject(with: Data(previous.utf8)) as! [String: Any]
        XCTAssertNil(value["body_base64"]); XCTAssertEqual(value["result_expired"] as? Bool, true)
    }
}
