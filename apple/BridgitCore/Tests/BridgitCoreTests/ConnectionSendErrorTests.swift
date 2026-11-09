import Foundation
import XCTest
@testable import BridgitCore

final class ConnectionSendErrorTests: XCTestCase {
    private func fixture() async throws -> (URL, AppletStore, HostAuthority, HostAgent, OwnerConnection) {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory)
        let authority = HostAuthority(store: store, deviceID: "fixture")
        let (agent, _) = try await authority.create(name: "Fixture", operations: [.connections], applets: [])
        await authority.setState(enabled: true, awake: true, foreground: true)
        let connection = try OwnerConnection(name: "Fixture", address: "https://example.com/webhook", presetID: "home_assistant")
        try await store.addOwnerConnection(connection)
        return (directory, store, authority, agent, connection)
    }
    private func dispatcher(_ store: AppletStore, _ authority: HostAuthority) -> HostDispatcher {
        HostDispatcher(authority: authority, store: store, engine: AppletEngine(store: store) { _, _, _ in [:] },
                       profile: { _ in throw CancellationError() },
                       connections: OutboundConnectionService(store: store, transport: { _, authorize in try await authorize(); return 204 }))
    }
    private func job(_ agent: HostAgent, _ sequence: Int64, _ connection: OwnerConnection, key: String, wait: Bool = false) -> HostJob {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        return .init(id: UUID().uuidString, sequence: sequence, agentID: agent.id, generation: agent.generation,
                     deviceID: "fixture", deadline: now + 120000, createdAt: now,
                     request: .init(method: "POST", path: "/v1/connections/send", body: ["to": connection.id, "text": "Fixture", "wait_reply": wait], idempotencyKey: key))
    }
    private func assertRateLimit(_ response: HostResponse, seconds: Int, file: StaticString = #filePath, line: UInt = #line) throws {
        XCTAssertEqual(response.status, 429, file: file, line: line)
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: response.data) as? [String: Any])
        XCTAssertEqual(body["error"] as? String, "rate_limited", file: file, line: line)
        XCTAssertEqual(body["retry_after_s"] as? Int, seconds, file: file, line: line)
        XCTAssertNotNil(body["message"] as? String, file: file, line: line)
        XCTAssertEqual(Set(body.keys), ["error", "message", "retry_after_s"], file: file, line: line)
    }
    func testSenderQueueFullMapsPublicResponseAndPersistsReplay() async throws {
        let (directory, store, authority, agent, connection) = try await fixture()
        let second = try OwnerConnection(name: "Second", address: "https://example.com/second", presetID: "home_assistant")
        try await store.addOwnerConnection(second)
        let now = Date()
        for index in 0..<100 {
            let destination = index < 50 ? connection : second
            let row = ConnectionDelivery(id: UUID().uuidString, destination: destination.id, agentID: agent.id,
                generation: agent.generation, mode: agent.mode,
                idempotency: ConnectionDelivery.digest(Data("key-\(index)".utf8)),
                fingerprint: ConnectionDelivery.digest(Data("intent-\(index)".utf8)), text: "Fixture", payload: Data("{}".utf8),
                created: now.timeIntervalSince1970, next: now.timeIntervalSince1970)
            _ = try await store.enqueueDelivery(row, connection: destination, now: now)
        }
        // The private service retains queue_full; only the public route maps it.
        do {
            _ = try await OutboundConnectionService(store: store).enqueue(to: connection.id, text: "Fixture", data: [:], agent: agent, phone: "fixture", idempotencyKey: "private", authorize: {})
            XCTFail("Full queue admitted another delivery")
        } catch let error as DeviceAPIError {
            XCTAssertEqual(error.code, "queue_full")
            XCTAssertNil(error.connectionSendRetryAfterSeconds)
        }
        let first = await dispatcher(store, authority).dispatch(job(agent, 1, connection, key: "overflow"))
        try assertRateLimit(first, seconds: 60)
        try await store.cancelConnectionDeliveries(connection.id)
        let reopened = try AppletStore(directory: directory)
        let host = dispatcher(reopened, authority)
        let replay = await host.dispatch(job(agent, 2, connection, key: "overflow"))
        XCTAssertEqual(replay.status, first.status); XCTAssertEqual(replay.data, first.data)
        let fresh = await host.dispatch(job(agent, 3, connection, key: "fresh"))
        XCTAssertEqual(fresh.status, 200)
    }
    func testDestinationHourlyLimitIncludesRetryForQueuedAndImmediateSendsAndReplay() async throws {
        let (directory, store, authority, agent, connection) = try await fixture()
        for _ in 0..<60 { try await store.reserveConnectionSend(connection, sender: agent.eventOwner) }
        do {
            try await store.reserveConnectionSend(connection, sender: agent.eventOwner)
            XCTFail("Hourly limit admitted another send")
        } catch let error as DeviceAPIError {
            XCTAssertEqual(error.code, "rate_limited")
            let privateResponse = HostResponse.failure(error)
            let body = try JSONSerialization.jsonObject(with: privateResponse.data) as! [String: Any]
            XCTAssertNil(body["retry_after_s"], "Other routes retain their existing error envelope")
        }
        let host = dispatcher(store, authority)
        let queued = await host.dispatch(job(agent, 1, connection, key: "queued"))
        let immediate = await host.dispatch(job(agent, 2, connection, key: "immediate", wait: true))
        try assertRateLimit(queued, seconds: 300); try assertRateLimit(immediate, seconds: 300)
        let rateKey = "connection-rate:" + connection.id + ":" + ConnectionDelivery.digest(Data(agent.eventOwner.utf8))
        try await store.setState(rateKey, value: "[]")
        let reopened = try AppletStore(directory: directory)
        let restoredHost = dispatcher(reopened, authority)
        let replay = await restoredHost.dispatch(job(agent, 3, connection, key: "queued"))
        XCTAssertEqual(replay.status, queued.status); XCTAssertEqual(replay.data, queued.data)
        let immediateReplay = await restoredHost.dispatch(job(agent, 4, connection, key: "immediate", wait: true))
        XCTAssertEqual(immediateReplay.status, immediate.status); XCTAssertEqual(immediateReplay.data, immediate.data)
        let fresh = await restoredHost.dispatch(job(agent, 5, connection, key: "fresh", wait: true))
        XCTAssertEqual(fresh.status, 200)
    }
}
