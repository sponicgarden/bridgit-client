import Foundation
import SQLite3
import XCTest
@testable import BridgitCore

final class ConnectionReceiptRetentionTests: XCTestCase {
    private func fixture() async throws -> (URL, AppletStore, HostAgent, OwnerConnection) {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory)
        let authority = HostAuthority(store: store, deviceID: "fixture")
        let (agent, _) = try await authority.create(name: "Fixture", operations: [.connections], applets: [])
        let connection = try OwnerConnection(name: "Fixture", address: "https://example.com/hook", presetID: "home_assistant")
        try await store.addOwnerConnection(connection)
        return (directory, store, agent, connection)
    }
    private func enqueue(_ store: AppletStore, _ agent: HostAgent, _ connection: OwnerConnection, at date: Date) async throws -> ConnectionDelivery {
        let token = UUID().uuidString
        let row = ConnectionDelivery(id: UUID().uuidString, destination: connection.id, agentID: agent.id, generation: agent.generation,
            mode: agent.mode, idempotency: ConnectionDelivery.digest(Data(token.utf8)), fingerprint: ConnectionDelivery.digest(Data(token.utf8)),
            text: "Fixture", payload: Data("{}".utf8), created: date.timeIntervalSince1970, next: date.timeIntervalSince1970)
        return try await store.enqueueDelivery(row, connection: connection, now: date)
    }
    private func assertMissing(_ store: AppletStore, id: String) async throws {
        do { _ = try await store.queueDelivery(id); XCTFail("Expired receipt survived cleanup") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.code, "not_found") }
    }
    func testPauseKeepsPendingSendAndRestoresBudgetWhenRefusedAfterLease() async throws {
        let (_, store, agent, connection) = try await fixture()
        let now = Date()
        let row = try await enqueue(store, agent, connection, at: now)
        let blocked = OutboundConnectionService(store: store, transport: { _, _ in
            XCTFail("An already paused queue must not claim or send"); return 204
        }, outboundAccess: .init(paused: { true }))
        let before = try await blocked.processQueue(now: now, authorize: { _, _, _, _ in })
        XCTAssertFalse(before)
        let pending = try await store.queueDelivery(row.id)
        XCTAssertEqual(pending.attempts, 0)
        XCTAssertEqual(pending.state, .queued)

        // The production transport checks again after its authorization
        // callback prepares the write lease, before issuing the write itself.
        let midflight = OutboundConnectionService(store: store, transport: { _, authorize in
            try await authorize()
            throw DeviceAPIError(503, "external_paused", "Fixture paused before write")
        })
        let during = try await midflight.processQueue(now: now, authorize: { _, _, _, _ in })
        XCTAssertFalse(during)
        let deferred = try await store.queueDelivery(row.id)
        XCTAssertEqual(deferred.state, .queued)
        XCTAssertEqual(deferred.attempts, 0)
        XCTAssertFalse(deferred.writing)
        XCTAssertFalse(deferred.uncertain)
        XCTAssertEqual(deferred.payload, pending.payload)

        let resumed = OutboundConnectionService(store: store, transport: { _, authorize in try await authorize(); return 204 })
        let processed = try await resumed.processQueue(now: now, authorize: { _, _, _, _ in })
        XCTAssertTrue(processed)
        let result = try await store.queueDelivery(row.id)
        XCTAssertEqual(result.state, .accepted)
        XCTAssertEqual(result.attempts, 1)
    }
    func testLongOfflineDeliveryRetainsReceiptForFullDayAfterCompletion() async throws {
        let (directory, store, agent, connection) = try await fixture()
        let created = Date(timeIntervalSince1970: 1000)
        let row = try await enqueue(store, agent, connection, at: created)
        let resumed = created.addingTimeInterval(3 * 86400)
        let service = OutboundConnectionService(store: store, transport: { _, authorize in try await authorize(); return 204 })
        let processed = try await service.processQueue(now: resumed, authorize: { _, _, _, _ in })
        XCTAssertTrue(processed)
        let receipt = try await store.queueDelivery(row.id)
        XCTAssertEqual(receipt.state, .accepted)
        let finished = try XCTUnwrap(receipt.finishedAt)
        XCTAssertGreaterThanOrEqual(finished, resumed.timeIntervalSince1970)
        XCTAssertEqual(receipt.summary["finished_at"] as? Double, finished * 1000)
        let reopened = try AppletStore(directory: directory)
        _ = try await enqueue(reopened, agent, connection, at: Date(timeIntervalSince1970: finished + 1))
        let retained = try await reopened.queueDelivery(row.id)
        XCTAssertEqual(retained.id, row.id)
        _ = try await enqueue(reopened, agent, connection, at: Date(timeIntervalSince1970: finished + 86400))
        let boundary = try await reopened.queueDelivery(row.id)
        XCTAssertEqual(boundary.id, row.id, "Exactly 24 hours remains inside retention")
        _ = try await enqueue(reopened, agent, connection, at: Date(timeIntervalSince1970: finished + 86400.001))
        try await assertMissing(reopened, id: row.id)
    }
    func testLegacyTerminalRetryTimestampIsNotReportedOrUsedAsCompletion() async throws {
        let (directory, store, agent, connection) = try await fixture()
        let old = Date(timeIntervalSince1970: 1000), encountered = old.addingTimeInterval(5 * 86400)
        var legacyIDs: [String] = []
        for state: ConnectionDelivery.State in [.accepted, .cancelled, .failed] {
            let row = try await enqueue(store, agent, connection, at: old)
            let claimed = try await store.claimDelivery(row.id, lease: UUID().uuidString, now: old)
            try await store.finishDelivery(claimed, state: state, http: state == .accepted ? 204 : nil, next: old)
            var legacy = try await store.queueDelivery(row.id)
            legacy.finishedAt = nil; legacy.next = old.timeIntervalSince1970
            let body = String(decoding: try JSONEncoder().encode(legacy), as: UTF8.self)
            var db: OpaquePointer?
            XCTAssertEqual(sqlite3_open(directory.appendingPathComponent("applets.sqlite").path, &db), SQLITE_OK)
            defer { sqlite3_close(db) }
            // Write precisely the pre-marker persisted shape, including the old retry next_at.
            let sql = "UPDATE connection_deliveries SET body='" + body.replacingOccurrences(of: "'", with: "''") + "',next_at=1000 WHERE id='" + row.id + "'"
            XCTAssertEqual(sqlite3_exec(db, sql, nil, nil, nil), SQLITE_OK)
            let stored = try await store.queueDelivery(row.id)
            XCTAssertNil(stored.summary["finished_at"], "Legacy retry time is not a known completion time")
            legacyIDs.append(row.id)
        }
        _ = try await enqueue(store, agent, connection, at: encountered)
        let reopened = try AppletStore(directory: directory)
        for id in legacyIDs {
            let receipt = try await reopened.queueDelivery(id)
            XCTAssertEqual(receipt.legacyRetentionStartedAt, encountered.timeIntervalSince1970)
            XCTAssertNil(receipt.summary["finished_at"])
        }
        _ = try await enqueue(reopened, agent, connection, at: encountered.addingTimeInterval(86400))
        for id in legacyIDs { _ = try await reopened.queueDelivery(id) }
        _ = try await enqueue(reopened, agent, connection, at: encountered.addingTimeInterval(86400.001))
        for id in legacyIDs { try await assertMissing(reopened, id: id) }
    }
}
