import XCTest
@testable import BridgitCore

private actor CallbackAttemptProbe {
    var count = 0
    func fail() -> CallbackAttemptOutcome { count += 1; return .failed }
}
private actor CallbackAttemptGate {
    var entered = false
    var waiter: CheckedContinuation<Void, Never>?
    func attempt() async -> CallbackAttemptOutcome {
        entered = true; await withCheckedContinuation { waiter = $0 }; return .failed
    }
    func release() { waiter?.resume(); waiter = nil }
}
final class CallbackWorkerTests: XCTestCase {
    func testGrantChangeWhileAttemptIsInFlightCannotRestoreRetry() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory), authority = HostAuthority(store: store, deviceID: "fixture")
        let (agent, _) = try await authority.create(name: "Fixture", operations: [.interactionsRead], applets: [])
        let gate = CallbackAttemptGate(), worker = CallbackWorker { _, _, _, _, _ in await gate.attempt() }
        await worker.reconcile([agent], revisions: [agent.id: 0])
        _ = await worker.enqueue(agent: agent, grantRevision: 0, configEpoch: 0, eventOwner: agent.eventOwner, eventID: 1, now: Date())
        let run = Task { await worker.runOne(now: Date().addingTimeInterval(1)) }
        for _ in 0..<500 { if await gate.entered { break }; try await Task.sleep(for: .milliseconds(2)) }
        let entered = await gate.entered; XCTAssertTrue(entered)
        var changed = agent; changed.operations.insert(.files)
        await worker.reconcile([changed], revisions: [agent.id: 1])
        await gate.release(); await run.value
        let due = await worker.nextDue(); XCTAssertNil(due)
    }

    func testWorkerRetriesExactlyAtPinnedScheduleAndThenStops() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory), authority = HostAuthority(store: store, deviceID: "fixture")
        let (agent, _) = try await authority.create(name: "Fixture", operations: [.interactionsRead], applets: [])
        let probe = CallbackAttemptProbe(), worker = CallbackWorker { _, _, _, _, _ in await probe.fail() }
        await worker.reconcile([agent], revisions: [agent.id: 0])
        let start = Date(timeIntervalSince1970: 1000)
        _ = await worker.enqueue(agent: agent, grantRevision: 0, configEpoch: 0, eventOwner: agent.eventOwner, eventID: 1, now: start)
        for (offset, next) in [(0.0, 10.0), (10.0, 70.0), (70.0, 370.0)] {
            await worker.runOne(now: start.addingTimeInterval(offset), completedAt: { start.addingTimeInterval(offset) })
            let due = await worker.nextDue(); XCTAssertEqual(due, start.addingTimeInterval(next))
        }
        await worker.runOne(now: start.addingTimeInterval(370), completedAt: { start.addingTimeInterval(370) })
        let due = await worker.nextDue(), count = await probe.count
        XCTAssertNil(due); XCTAssertEqual(count, 4)
    }
    func testDroppedOutcomeDoesNotScheduleRetry() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory), authority = HostAuthority(store: store, deviceID: "fixture")
        let (agent, _) = try await authority.create(name: "Fixture", operations: [.interactionsRead], applets: [])
        let worker = CallbackWorker { _, _, _, _, _ in .dropped }
        await worker.reconcile([agent], revisions: [agent.id: 0])
        _ = await worker.enqueue(agent: agent, grantRevision: 0, configEpoch: 0, eventOwner: agent.eventOwner, eventID: 1, now: Date())
        await worker.runOne(now: Date().addingTimeInterval(1))
        let due = await worker.nextDue(); XCTAssertNil(due)
    }

    func testEventEligibilityTracksRemovalButNotCredentialReplacement() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory), authority = HostAuthority(store: store, deviceID: "fixture")
        let (agent, _) = try await authority.create(name: "Fixture", operations: [.interactionsRead], applets: [])
        let subscription = try await store.callbackEventSubscription()
        func emit() async throws {
            let card = try await store.createInteraction(owner: agent.eventOwner, input: ["kind": "notice", "title": "Fixture", "no_takeover": true])
            try await store.markInteractionSeen(id: try XCTUnwrap(card["interaction_id"] as? String))
        }
        try await emit()
        try await store.stageCallbackKey("first", agentID: agent.id, caller: nil)
        try await store.commitCallbackKey("first", agentID: agent.id, caller: nil)
        try await emit()
        try await store.stageCallbackKey("second", agentID: agent.id, caller: nil)
        try await store.commitCallbackKey("second", agentID: agent.id, caller: nil)
        let replaced = try await store.callbackIndex(agent.id); XCTAssertEqual(replaced.removalEpoch, 0)
        try await store.removeCallbackKey(agentID: agent.id, caller: nil)
        try await emit()
        try await store.stageCallbackKey("third", agentID: agent.id, caller: nil)
        try await store.commitCallbackKey("third", agentID: agent.id, caller: nil)
        let current = try await store.callbackIndex(agent.id); XCTAssertEqual(current.removalEpoch, 1)
        let events = try await store.callbackEvents(after: subscription.cursor)
        XCTAssertEqual(events.compactMap { $0["eligible"] as? Int }, [0, 1, 0])
        XCTAssertEqual(events.compactMap { $0["config_epoch"] as? Int }, [0, 0, 1])
    }
    func testRemovedAgentCannotCommitStagedCallback() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory), authority = HostAuthority(store: store, deviceID: "fixture")
        let (agent, _) = try await authority.create(name: "Fixture", operations: [.interactionsRead], applets: [])
        try await store.stageCallbackKey("staged", agentID: agent.id, caller: nil)
        try await authority.remove(agent.id)
        do { try await store.commitCallbackKey("staged", agentID: agent.id, caller: nil); XCTFail("Removed target must not commit") } catch {}
        let retired = try await store.retiredCallbackKeys(agent.id); XCTAssertEqual(retired, ["staged"])
    }

    func testCommittedEventRetainsCreationEpochAcrossCoalescedGrantChanges() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory), authority = HostAuthority(store: store, deviceID: "fixture")
        let (agent, _) = try await authority.create(name: "Fixture", operations: [.interactionsRead], applets: [])
        let subscription = try await store.callbackEventSubscription()
        let card = try await store.createInteraction(owner: agent.eventOwner, input: ["kind": "notice", "title": "Fixture", "no_takeover": true])
        try await store.markInteractionSeen(id: try XCTUnwrap(card["interaction_id"] as? String))
        var changed = agent; changed.operations.insert(.files)
        try await store.saveHostAgents(String(decoding: try JSONEncoder().encode([changed]), as: UTF8.self), removingTransfersFor: nil)
        try await store.saveHostAgents(String(decoding: try JSONEncoder().encode([agent]), as: UTF8.self), removingTransfersFor: nil)
        let events = try await store.callbackEvents(after: subscription.cursor)
        let first = try XCTUnwrap(events.first)
        XCTAssertEqual(first["grant_epoch"] as? Int, 0)
        let revision = try await store.callbackGrantEpoch(agent.id); XCTAssertEqual(revision, 2)
        let worker = CallbackWorker { _, _, _, _, _ in .delivered }
        await worker.reconcile([agent], revisions: [agent.id: revision])
        let id = Int64(try XCTUnwrap((first["event"] as? [String: Any])?["id"] as? String))!
        _ = await worker.enqueue(agent: agent, grantRevision: try XCTUnwrap(first["grant_epoch"] as? Int64), configEpoch: 0, eventOwner: agent.eventOwner, eventID: id, now: Date())
        let pending = await worker.pendingCount(agentID: agent.id); XCTAssertEqual(pending, 0)
        let publicPage = try await store.interactionEvents(owner: agent.eventOwner)
        XCTAssertFalse(String(describing: publicPage).contains("grant_epoch"))
    }

    func testCoalescedGrantChangeAndRevertStillCancels() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory), authority = HostAuthority(store: store, deviceID: "fixture")
        let (agent, _) = try await authority.create(name: "Fixture", operations: [.interactionsRead], applets: [])
        let worker = CallbackWorker { _, _, _, _, _ in .delivered }
        await worker.reconcile([agent], revisions: [agent.id: 0])
        _ = await worker.enqueue(agent: agent, grantRevision: 0, configEpoch: 0, eventOwner: agent.eventOwner, eventID: 1, now: Date())
        var changed = agent; changed.operations.insert(.files)
        try await store.saveHostAgents(String(decoding: try JSONEncoder().encode([changed]), as: UTF8.self), removingTransfersFor: nil)
        try await store.saveHostAgents(String(decoding: try JSONEncoder().encode([agent]), as: UTF8.self), removingTransfersFor: nil)
        let revision = try await store.callbackGrantEpoch(agent.id); XCTAssertEqual(revision, 2)
        await worker.reconcile([agent], revisions: [agent.id: revision])
        let pending = await worker.pendingCount(agentID: agent.id); XCTAssertEqual(pending, 0)
    }

    func testGrantChangeCancelsButRotationWithSameGrantsRetainsQueue() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory), authority = HostAuthority(store: store, deviceID: "fixture")
        let (agent, _) = try await authority.create(name: "Fixture", operations: [.interactionsRead], applets: [])
        let worker = CallbackWorker { _, _, _, _, _ in .delivered }
        await worker.reconcile([agent], revisions: [agent.id: 0]); _ = await worker.enqueue(agent: agent, grantRevision: 0, configEpoch: 0, eventOwner: agent.eventOwner, eventID: 1, now: Date())
        var rotated = agent; rotated.generation = UUID().uuidString
        await worker.reconcile([rotated], revisions: [agent.id: 0]); let retained = await worker.pendingCount(agentID: agent.id); XCTAssertEqual(retained, 1)
        rotated.applets.insert("new-grant")
        await worker.reconcile([rotated], revisions: [agent.id: 0]); let cancelled = await worker.pendingCount(agentID: agent.id); XCTAssertEqual(cancelled, 0)
    }
}
