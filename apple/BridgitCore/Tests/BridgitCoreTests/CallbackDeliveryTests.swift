import Foundation
import XCTest
@testable import BridgitCore

private actor CallbackDeliverySecrets: SatelliteSecrets {
    var values: [String: String] = [:]
    func load(_ id: String) async throws -> String? { values[id] }
    func save(_ value: String, for id: String) async throws { values[id] = value }
    func remove(_ id: String) async throws { values.removeValue(forKey: id) }
}
private actor CallbackCapture {
    var request: ConnectionHTTPRequest?
    func save(_ value: ConnectionHTTPRequest) { request = value }
}
final class CallbackDeliveryTests: XCTestCase {
    func testAttemptSignsExactScopedEventAndRechecksRevocation() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory), authority = HostAuthority(store: store, deviceID: "fixture")
        let (agent, _) = try await authority.create(name: "Fixture", operations: [.interactionsRead, .interactionsWrite], applets: [])
        await authority.setState(enabled: true, awake: true, foreground: true)
        await authority.setCallbackExecutionEnabled(true)
        let card = try await store.createInteraction(owner: agent.eventOwner, input: ["kind": "notice", "title": "Fixture", "no_takeover": true])
        try await store.markInteractionSeen(id: try XCTUnwrap(card["interaction_id"] as? String))
        let page = try await store.interactionEvents(owner: agent.eventOwner)
        let event = try XCTUnwrap((page["events"] as? [[String: Any]])?.first)
        let id = try XCTUnwrap(Int64(try XCTUnwrap(event["id"] as? String)))
        let config = IndexedCallbackConfiguration(store: store, secrets: CallbackDeliverySecrets())
        let policy = CallbackNetworkPolicy(tailnetDomain: nil, allowInternet: true)
        let emptyService = CallbackDelivery(authority: authority, store: store, configuration: config, appVersion: "fixture", policy: { policy }, transport: { _, _ in XCTFail("No callback should be sent"); return .init(status: 200) })
        let worker = CallbackWorker { id, revision, epoch, owner, event in await emptyService.outcome(agentID: id, grantRevision: revision, configEpoch: epoch, eventOwner: owner, eventID: event) }
        await worker.reconcile([agent], revisions: [agent.id: 0])
        _ = await worker.enqueue(agent: agent, grantRevision: 0, configEpoch: 0, eventOwner: agent.eventOwner, eventID: id, now: Date())
        await worker.runOne(now: Date().addingTimeInterval(1))
        let absentConfigDue = await worker.nextDue(); XCTAssertNil(absentConfigDue)
        _ = await worker.enqueue(agent: agent, grantRevision: 0, configEpoch: 0, eventOwner: agent.eventOwner, eventID: id + 99, now: Date())
        await worker.runOne(now: Date().addingTimeInterval(1))
        let absentEventDue = await worker.nextDue(); XCTAssertNil(absentEventDue)
        let secret = "cbs_" + String(repeating: "a", count: 43)
        _ = try await config.set(agentID: agent.id, url: "https://example.com/hook", secret: secret, caller: nil, policy: { policy }, authorize: {})
        let eligibleCard = try await store.createInteraction(owner: agent.eventOwner, input: ["kind": "notice", "title": "Eligible", "no_takeover": true])
        try await store.markInteractionSeen(id: try XCTUnwrap(eligibleCard["interaction_id"] as? String))
        let eligiblePage = try await store.interactionEvents(owner: agent.eventOwner)
        let eligibleEvent = try XCTUnwrap((eligiblePage["events"] as? [[String: Any]])?.last)
        let eligibleID = try XCTUnwrap(Int64(try XCTUnwrap(eligibleEvent["id"] as? String)))
        let captured = CallbackCapture()
        let service = CallbackDelivery(authority: authority, store: store, configuration: config, appVersion: "fixture", policy: { policy }, transport: { request, check in
            try await check(); await captured.save(request); return .init(status: 204)
        })
        let status = try await service.attempt(agentID: agent.id, grantRevision: 0, configEpoch: 0, eventOwner: agent.eventOwner, eventID: eligibleID, now: Date(timeIntervalSince1970: 123))
        XCTAssertEqual(status, 204)
        let capturedRequest = await captured.request
        let request = try XCTUnwrap(capturedRequest)
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: request.body) as? [String: Any])
        XCTAssertEqual(body["agent"] as? String, "Fixture")
        XCTAssertEqual((body["event"] as? [String: Any])?["id"] as? String, String(eligibleID))
        XCTAssertEqual(request.headers["X-Bridgit-Signature"], CallbackRules.signature(secret: secret, timestamp: 123, body: request.body))
        var viewer = agent; viewer.viewer = true
        try await store.saveHostAgents(String(decoding: try JSONEncoder().encode([viewer]), as: UTF8.self), removingTransfersFor: nil)
        do {
            _ = try await service.attempt(agentID: agent.id, grantRevision: 0, configEpoch: 0, eventOwner: agent.eventOwner, eventID: eligibleID)
            XCTFail("A viewer must not receive callbacks even with stale cached grants")
        } catch { }
        try await store.saveHostAgents(String(decoding: try JSONEncoder().encode([agent]), as: UTF8.self), removingTransfersFor: nil)
        let failed = CallbackDelivery(authority: authority, store: store, configuration: config, appVersion: "fixture", policy: { policy }, transport: { _, check in
            try await check(); throw DeviceAPIError(503, "transport_failed", "Sensitive fixture must not appear")
        })
        let failedOutcome = await failed.outcome(agentID: agent.id, grantRevision: 0, configEpoch: 0, eventOwner: agent.eventOwner, eventID: eligibleID)
        XCTAssertEqual(failedOutcome, .failed)
        let activity = try await store.hostActivity(kinds: [.agent])
        XCTAssertTrue(activity.contains { $0.operation.contains("example.com") && $0.operation.contains("delivery failed") })
        XCTAssertFalse(activity.contains { $0.operation.contains("/hook") || $0.operation.contains("cbs_") || $0.operation.contains("Sensitive fixture") })
        do { _ = try await service.attempt(agentID: agent.id, grantRevision: 0, configEpoch: 0, eventOwner: "other:" + agent.generation, eventID: eligibleID); XCTFail("Foreign owner must fail") } catch {}
        let lateChange = CallbackDelivery(authority: authority, store: store, configuration: config, appVersion: "fixture", policy: { policy }, transport: { _, check in
            try await check(); XCTFail("Final persisted eligibility must reject the send"); return .init(status: 204)
        }, beforeFinalEligibility: {
            var changed = agent; changed.operations.insert(.files)
            try await store.saveHostAgents(String(decoding: try JSONEncoder().encode([changed]), as: UTF8.self), removingTransfersFor: nil)
            try await store.saveHostAgents(String(decoding: try JSONEncoder().encode([agent]), as: UTF8.self), removingTransfersFor: nil)
        })
        do { _ = try await lateChange.attempt(agentID: agent.id, grantRevision: 0, configEpoch: 0, eventOwner: agent.eventOwner, eventID: eligibleID); XCTFail("Late grant changes must reject the send") } catch {}
        // Use a new event at the updated revision for the next interleaving.
        let lateRevision = try await store.callbackGrantEpoch(agent.id); XCTAssertEqual(lateRevision, 2)
        let lateCard = try await store.createInteraction(owner: agent.eventOwner, input: ["kind": "notice", "title": "Late", "no_takeover": true])
        try await store.markInteractionSeen(id: try XCTUnwrap(lateCard["interaction_id"] as? String))
        let latePage = try await store.interactionEvents(owner: agent.eventOwner)
        let lateEvent = try XCTUnwrap((latePage["events"] as? [[String: Any]])?.last)
        let lateID = try XCTUnwrap(Int64(try XCTUnwrap(lateEvent["id"] as? String)))
        let changedThenRestored = CallbackDelivery(authority: authority, store: store, configuration: config, appVersion: "fixture", policy: { policy }, transport: { _, check in
            var changed = agent; changed.operations.insert(.files)
            try await store.saveHostAgents(String(decoding: try JSONEncoder().encode([changed]), as: UTF8.self), removingTransfersFor: nil)
            try await store.saveHostAgents(String(decoding: try JSONEncoder().encode([agent]), as: UTF8.self), removingTransfersFor: nil)
            try await check(); XCTFail("Changed and restored grants must not send"); return .init(status: 204)
        })
        do { _ = try await changedThenRestored.attempt(agentID: agent.id, grantRevision: 2, configEpoch: 0, eventOwner: agent.eventOwner, eventID: lateID); XCTFail("Grant revision must reject send") } catch {}
        let revision = try await store.callbackGrantEpoch(agent.id); XCTAssertEqual(revision, 4)
        let latestCard = try await store.createInteraction(owner: agent.eventOwner, input: ["kind": "notice", "title": "Latest", "no_takeover": true])
        try await store.markInteractionSeen(id: try XCTUnwrap(latestCard["interaction_id"] as? String))
        let latestPage = try await store.interactionEvents(owner: agent.eventOwner)
        let latestEvent = try XCTUnwrap((latestPage["events"] as? [[String: Any]])?.last)
        let latestID = try XCTUnwrap(Int64(try XCTUnwrap(latestEvent["id"] as? String)))
        let revoked = CallbackDelivery(authority: authority, store: store, configuration: config, appVersion: "fixture", policy: { policy }, transport: { _, check in
            try await authority.pause(agent.id, paused: true); try await check(); return .init(status: 204)
        })
        do { _ = try await revoked.attempt(agentID: agent.id, grantRevision: 4, configEpoch: 0, eventOwner: agent.eventOwner, eventID: latestID); XCTFail("Revoked agent must not receive a callback") } catch {}
        await worker.reconcile([agent], revisions: [agent.id: 4])
        _ = await worker.enqueue(agent: agent, grantRevision: 4, configEpoch: 0, eventOwner: agent.eventOwner, eventID: latestID, now: Date())
        await worker.runOne(now: Date().addingTimeInterval(1))
        let pausedDue = await worker.nextDue(); XCTAssertNil(pausedDue)
    }
}
