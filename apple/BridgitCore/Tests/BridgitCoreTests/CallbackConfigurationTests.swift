import XCTest
@testable import BridgitCore

private actor CallbackMemorySecrets: SatelliteSecrets {
    private var values: [String: String] = [:]
    func load(_ id: String) async throws -> String? { values[id] }
    func save(_ value: String, for id: String) async throws { values[id] = value }
    func remove(_ id: String) async throws { values.removeValue(forKey: id) }
}

private actor CallbackAuthorizationFixture {
    private var checks = 0
    func failThird() throws {
        checks += 1
        if checks == 3 { throw DeviceAPIError(403, "agent_not_allowed", "Revoked") }
    }
}

private actor BlockingCallbackSecrets: SatelliteSecrets {
    private var values: [String: String] = [:]
    private var saves = 0, blockAt = Int.max
    private var waiter: CheckedContinuation<Void, Never>?
    private var entered = false
    func arm(after count: Int) { blockAt = saves + count; entered = false }
    func blocked() -> Bool { entered }
    func release() { waiter?.resume(); waiter = nil }
    func load(_ id: String) async throws -> String? { values[id] }
    func save(_ value: String, for id: String) async throws {
        saves += 1
        if saves == blockAt { entered = true; await withCheckedContinuation { waiter = $0 } }
        values[id] = value
    }
    func remove(_ id: String) async throws { values.removeValue(forKey: id) }
}

private actor CallbackPolicyFixture {
    private var reads = 0
    func policy() -> CallbackNetworkPolicy {
        reads += 1
        return .init(tailnetDomain: nil, allowInternet: reads < 3)
    }
}

final class CallbackConfigurationTests: XCTestCase {
    func testGenerationChangeKeepsStableAgentCallback() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory), authority = HostAuthority(store: store, deviceID: "callback-fixture")
        let (agent, _) = try await authority.create(name: "Agent", operations: [.deviceRead], applets: [])
        let secrets = CallbackMemorySecrets(), config = CallbackConfiguration(secrets: secrets)
        let policy = CallbackNetworkPolicy(tailnetDomain: nil, allowInternet: true)
        _ = try await config.set(agentID: agent.id, url: "https://example.com/hook", secret: "cbs_" + String(repeating: "a", count: 43), policy: { policy }, agentExists: { true }, authorize: {})
        var rotated = agent; rotated.generation = UUID().uuidString
        let encoded = String(decoding: try JSONEncoder().encode([rotated]), as: UTF8.self)
        try await store.saveHostAgents(encoded, removingTransfersFor: agent.eventOwner)
        let pending = try await store.pendingCallbackPrunes(); XCTAssertTrue(pending.isEmpty)
        let saved = try await secrets.load("callback:" + agent.id); XCTAssertNotNil(saved)
    }

    func testAuthorityRemovalPersistsAndReconcilesPruneMarker() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory), authority = HostAuthority(store: store, deviceID: "callback-fixture")
        let (agent, _) = try await authority.create(name: "Agent", operations: [.deviceRead], applets: [])
        let secrets = CallbackMemorySecrets(), config = CallbackConfiguration(secrets: secrets)
        let policy = CallbackNetworkPolicy(tailnetDomain: nil, allowInternet: true)
        _ = try await config.set(agentID: agent.id, url: "https://example.com/hook", secret: "cbs_" + String(repeating: "a", count: 43), policy: { policy }, agentExists: { true }, authorize: {})
        await authority.setCallbackPruner { _ in throw DeviceAPIError(503, "fixture_failure", "Fixture") }
        try await authority.remove(agent.id)
        let pending = try await store.pendingCallbackPrunes(); XCTAssertEqual(pending, [agent.id])
        let reopened = try AppletStore(directory: directory), next = HostAuthority(store: reopened, deviceID: "callback-fixture")
        await next.setCallbackPruner { id in try await config.pruneRemovedAgent(id) }
        await next.reconcileCallbackPrunes()
        let remaining = try await reopened.pendingCallbackPrunes(); XCTAssertTrue(remaining.isEmpty)
        let saved = try await secrets.load("callback:" + agent.id); XCTAssertNil(saved)
    }
    func testDeliveryRechecksCurrentNetworkPolicyAndAuthority() async throws {
        let secrets = CallbackMemorySecrets(), config = CallbackConfiguration(secrets: secrets)
        let allow = CallbackNetworkPolicy(tailnetDomain: nil, allowInternet: true)
        let deny = CallbackNetworkPolicy(tailnetDomain: nil, allowInternet: false)
        _ = try await config.set(agentID: "one", url: "https://example.com/hook", secret: "cbs_" + String(repeating: "a", count: 43), policy: { allow }, agentExists: { true }, authorize: {})
        let ready = try await config.delivery(agentID: "one", policy: { allow }, authorize: {})
        XCTAssertNotNil(ready)
        do { _ = try await config.delivery(agentID: "one", policy: { deny }, authorize: {}); XCTFail("Changed policy must reject delivery") } catch {}
        do { _ = try await config.delivery(agentID: "one", policy: { allow }, authorize: { throw DeviceAPIError(403, "revoked", "Revoked") }); XCTFail("Revoked caller must be denied") } catch {}
    }

    func testDeviceCapabilityReductionRetainsCallbackConfigurationAndTailnetDelivery() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory)
        try await store.setCallbackInternetAllowed(true)
        let config = CallbackConfiguration(secrets: CallbackMemorySecrets())
        let policy: @Sendable () async -> CallbackNetworkPolicy = {
            .init(tailnetDomain: nil, allowInternet: (try? await store.callbackInternetAllowed()) == true)
        }
        let secret = "cbs_" + String(repeating: "a", count: 43)
        _ = try await config.set(agentID: "internet", url: "https://example.com/hook", secret: secret,
            policy: policy, agentExists: { true }, authorize: {})
        _ = try await config.set(agentID: "tailnet", url: "http://100.80.1.2/hook", secret: secret,
            policy: policy, agentExists: { true }, authorize: {})
        let before = try await config.delivery(agentID: "internet", policy: policy, authorize: {})
        XCTAssertNotNil(before)

        try await store.disableDurableDeviceCapabilities()
        do {
            _ = try await config.delivery(agentID: "internet", policy: policy, authorize: {})
            XCTFail("An Internet callback must be rejected after reduction")
        } catch let error as DeviceAPIError {
            XCTAssertEqual(error.status, 422)
            XCTAssertEqual(error.code, "invalid_parameter")
        }
        let allowed = try await config.delivery(agentID: "tailnet", policy: policy, authorize: {})
        XCTAssertNotNil(allowed)

        try await store.setCallbackInternetAllowed(true)
        let restored = try await config.delivery(agentID: "internet", policy: policy, authorize: {})
        XCTAssertNotNil(restored)
    }

    func testRemoteAgentRemovalSchedulesCallbackPrune() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory), authority = HostAuthority(store: store, deviceID: "callback-fixture")
        let (admin, _) = try await authority.create(name: "Admin", operations: [.administrator], applets: [])
        let (target, _) = try await authority.create(name: "Target", operations: [.deviceRead], applets: [])
        await authority.setState(enabled: true, awake: true, foreground: true)
        let secrets = CallbackMemorySecrets(), config = CallbackConfiguration(secrets: secrets)
        let policy = CallbackNetworkPolicy(tailnetDomain: nil, allowInternet: true)
        _ = try await config.set(agentID: target.id, url: "https://example.com/hook", secret: "cbs_" + String(repeating: "a", count: 43), policy: { policy }, agentExists: { true }, authorize: {})
        await authority.setCallbackPruner { id in try await config.pruneRemovedAgent(id) }
        let result = try await authority.administer(.init(method: "DELETE", path: "/v1/agents/" + target.id), caller: admin)
        XCTAssertEqual(result.status, 200)
        await authority.reconcileCallbackPrunes()
        let saved = try await secrets.load("callback:" + target.id); XCTAssertNil(saved)
        let pending = try await store.pendingCallbackPrunes(); XCTAssertTrue(pending.isEmpty)
    }

    func testAuthorityRemovalWinsSuspendedCallbackSave() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory), authority = HostAuthority(store: store, deviceID: "callback-fixture")
        let (agent, _) = try await authority.create(name: "Agent", operations: [.deviceRead], applets: [])
        let secrets = BlockingCallbackSecrets(), config = CallbackConfiguration(secrets: secrets)
        let policy = CallbackNetworkPolicy(tailnetDomain: nil, allowInternet: true)
        _ = try await config.set(agentID: agent.id, url: "https://example.com/old", secret: "cbs_" + String(repeating: "a", count: 43), policy: { policy }, agentExists: { try await authority.agent(agent.id) != nil }, authorize: {})
        await authority.setCallbackPruner { id in try await config.pruneRemovedAgent(id) }
        await secrets.arm(after: 1)
        let changing = Task { try await config.set(agentID: agent.id, url: "https://example.com/new", secret: nil, policy: { policy }, agentExists: { try await authority.agent(agent.id) != nil }, authorize: {}) }
        for _ in 0..<500 { if await secrets.blocked() { break }; try await Task.sleep(for: .milliseconds(2)) }
        let blocked = await secrets.blocked(); XCTAssertTrue(blocked)
        let removal = Task { try await authority.remove(agent.id) }
        for _ in 0..<500 { if await config.isRemoved(agent.id) { break }; try await Task.sleep(for: .milliseconds(2)) }
        let removed = await config.isRemoved(agent.id); XCTAssertTrue(removed)
        await secrets.release(); _ = try? await changing.value; try await removal.value
        await authority.reconcileCallbackPrunes()
        let saved = try await secrets.load("callback:" + agent.id); XCTAssertNil(saved)
        let pending = try await store.pendingCallbackPrunes(); XCTAssertTrue(pending.isEmpty)
    }

    func testRemovalWinsOverSuspendedSaveAndRollback() async throws {
        for rollback in [false, true] {
            let secrets = BlockingCallbackSecrets(), config = CallbackConfiguration(secrets: secrets)
            let policy = CallbackNetworkPolicy(tailnetDomain: nil, allowInternet: true)
            let old = "cbs_" + String(repeating: "a", count: 43), new = "cbs_" + String(repeating: "b", count: 43)
            _ = try await config.set(agentID: "one", url: "https://example.com/old", secret: old, policy: { policy }, agentExists: { true }, authorize: {})
            await secrets.arm(after: rollback ? 2 : 1)
            let gate = CallbackAuthorizationFixture()
            let changing = Task {
                try await config.set(agentID: "one", url: "https://example.com/new", secret: new, policy: { policy }, agentExists: { true }, authorize: { if rollback { try await gate.failThird() } })
            }
            for _ in 0..<500 { if await secrets.blocked() { break }; try await Task.sleep(for: .milliseconds(2)) }
            let blocked = await secrets.blocked(); XCTAssertTrue(blocked)
            do { _ = try await config.list(agentIDs: ["one"], authorize: {}); XCTFail("Tentative credentials must not be listed") } catch {}
            let pruning = Task { try await config.pruneRemovedAgent("one") }
            for _ in 0..<500 { if await config.isRemoved("one") { break }; try await Task.sleep(for: .milliseconds(2)) }
            let removed = await config.isRemoved("one"); XCTAssertTrue(removed)
            await secrets.release()
            _ = try? await changing.value
            try await pruning.value
            let final = try await secrets.load("callback:one"); XCTAssertNil(final)
        }
    }

    func testIndexedConfigurationReplacesWithoutCancellingAndRecoversStaging() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory), authority = HostAuthority(store: store, deviceID: "fixture")
        let (agent, _) = try await authority.create(name: "Agent", operations: [.configurationWrite], applets: [])
        let secrets = CallbackMemorySecrets(), config = IndexedCallbackConfiguration(store: store, secrets: secrets)
        let policy = CallbackNetworkPolicy(tailnetDomain: nil, allowInternet: true)
        let secret = "cbs_" + String(repeating: "a", count: 43)
        _ = try await config.set(agentID: agent.id, url: "https://example.com/first", secret: secret, caller: agent, policy: { policy }, authorize: {})
        let first = try await store.callbackIndex(agent.id); XCTAssertNotNil(first.active)
        _ = try await config.set(agentID: agent.id, url: "https://example.com/second", secret: nil, caller: agent, policy: { policy }, authorize: {})
        let second = try await store.callbackIndex(agent.id); XCTAssertNotEqual(first.active, second.active); XCTAssertEqual(first.removalEpoch, second.removalEpoch)
        let oldKey = try XCTUnwrap(first.active)
        let old = try await secrets.load("callback:" + agent.id + ":" + oldKey); XCTAssertNil(old)
        let current = try await config.current(agentID: agent.id, policy: { policy }, authorize: {})
        XCTAssertEqual(current?.url, "https://example.com/second"); XCTAssertEqual(current?.secret, secret)
        try await store.stageCallbackKey("interrupted", agentID: agent.id, caller: agent)
        try await secrets.save("staged secret", for: "callback:" + agent.id + ":interrupted")
        let reopened = IndexedCallbackConfiguration(store: try AppletStore(directory: directory), secrets: secrets)
        try await reopened.reconcile()
        let staged = try await secrets.load("callback:" + agent.id + ":interrupted"); XCTAssertNil(staged)
        let stillActive = try await store.callbackIndex(agent.id); XCTAssertEqual(stillActive.active, second.active); XCTAssertNil(stillActive.staged)
    }
    func testIndexedReopenCannotEraseRetiredKeyWhileWriterIsSuspended() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory), authority = HostAuthority(store: store, deviceID: "fixture")
        let (agent, _) = try await authority.create(name: "Agent", operations: [.configurationWrite], applets: [])
        let secrets = BlockingCallbackSecrets(), writer = IndexedCallbackConfiguration(store: store, secrets: secrets)
        let policy = CallbackNetworkPolicy(tailnetDomain: nil, allowInternet: true)
        await secrets.arm(after: 1)
        let setting = Task { try await writer.set(agentID: agent.id, url: "https://example.com/hook", secret: "cbs_" + String(repeating: "a", count: 43), caller: agent, policy: { policy }, authorize: {}) }
        for _ in 0..<500 { if await secrets.blocked() { break }; try await Task.sleep(for: .milliseconds(2)) }
        let blocked = await secrets.blocked(); XCTAssertTrue(blocked)
        let current = try await store.callbackIndex(agent.id)
        let staged = try XCTUnwrap(current.staged)
        try await authority.remove(agent.id)
        let before = try await store.retiredCallbackKeys(agent.id); XCTAssertEqual(before, [staged])
        let reopenedStore = try AppletStore(directory: directory)
        let cleaner = IndexedCallbackConfiguration(store: reopenedStore, secrets: secrets)
        do { try await cleaner.reconcile(); XCTFail("Concurrent cleanup must retain staged work") } catch {}
        let stillRetired = try await reopenedStore.retiredCallbackKeys(agent.id); XCTAssertEqual(stillRetired, [staged])
        await secrets.release()
        do { _ = try await setting.value; XCTFail("Removed agent cannot commit") } catch {}
        try await cleaner.reconcile()
        let final = try await secrets.load("callback:" + agent.id + ":" + staged); XCTAssertNil(final)
        let retired = try await reopenedStore.retiredCallbackKeys(agent.id); XCTAssertTrue(retired.isEmpty)
    }

    func testIndexedWriteCannotCommitAfterAgentRemoval() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory), authority = HostAuthority(store: store, deviceID: "fixture")
        let (agent, _) = try await authority.create(name: "Agent", operations: [.configurationWrite], applets: [])
        let secrets = BlockingCallbackSecrets(), config = IndexedCallbackConfiguration(store: store, secrets: secrets)
        let policy = CallbackNetworkPolicy(tailnetDomain: nil, allowInternet: true)
        await secrets.arm(after: 1)
        let setting = Task { try await config.set(agentID: agent.id, url: "https://example.com/hook", secret: "cbs_" + String(repeating: "a", count: 43), caller: agent, policy: { policy }, authorize: {}) }
        for _ in 0..<500 { if await secrets.blocked() { break }; try await Task.sleep(for: .milliseconds(2)) }
        let blocked = await secrets.blocked(); XCTAssertTrue(blocked)
        try await authority.remove(agent.id)
        await secrets.release()
        do { _ = try await setting.value; XCTFail("Removed caller and target cannot commit") } catch {}
        let index = try await store.callbackIndex(agent.id); XCTAssertNil(index.active); XCTAssertNil(index.staged)
        let retired = try await store.retiredCallbackKeys(agent.id); XCTAssertTrue(retired.isEmpty)
    }

    func testPolicyRevokedAfterWriteRestoresPreviousRecord() async throws {
        let secrets = CallbackMemorySecrets(), config = CallbackConfiguration(secrets: secrets)
        let allow = CallbackNetworkPolicy(tailnetDomain: nil, allowInternet: true)
        let secret = "cbs_" + String(repeating: "a", count: 43)
        _ = try await config.set(agentID: "one", url: "https://example.com/old", secret: secret, policy: { allow }, agentExists: { true }, authorize: {})
        let before = try await secrets.load("callback:one"), policy = CallbackPolicyFixture()
        do {
            _ = try await config.set(agentID: "one", url: "https://example.com/new", secret: nil, policy: { await policy.policy() }, agentExists: { true }, authorize: {})
            XCTFail("Revoked policy must fail")
        } catch {}
        let after = try await secrets.load("callback:one"); XCTAssertEqual(after, before)
    }

    func testRevokedAfterWriteRestoresPreviousSecretRecord() async throws {
        let secrets = CallbackMemorySecrets(), config = CallbackConfiguration(secrets: secrets)
        let policy = CallbackNetworkPolicy(tailnetDomain: nil, allowInternet: true)
        let old = "cbs_" + String(repeating: "a", count: 43), new = "cbs_" + String(repeating: "b", count: 43)
        _ = try await config.set(agentID: "one", url: "https://example.com/old", secret: old, policy: { policy }, agentExists: { true }, authorize: {})
        let previous = try await secrets.load("callback:one")
        let gate = CallbackAuthorizationFixture()
        do {
            _ = try await config.set(agentID: "one", url: "https://example.com/new", secret: new, policy: { policy }, agentExists: { true }, authorize: { try await gate.failThird() })
            XCTFail("Revoked write must fail")
        } catch {}
        let actual = try await secrets.load("callback:one")
        XCTAssertEqual(actual, previous)
    }

    func testWriteOnlyConfigurationAndReceiverSecret() async throws {
        let secrets = CallbackMemorySecrets(), config = CallbackConfiguration(secrets: secrets)
        let policy = CallbackNetworkPolicy(tailnetDomain: nil, allowInternet: true)
        let secret = "cbs_" + String(repeating: "a", count: 43)
        do { _ = try await config.set(agentID: "one", url: "https://example.com/private?token=x", secret: nil, policy: { policy }, agentExists: { true }, authorize: {}); XCTFail("A new callback needs a receiver key") } catch {}
        let result = try await config.set(agentID: "one", url: "https://example.com/private?token=x", secret: secret, policy: { policy }, agentExists: { true }, authorize: {})
        XCTAssertEqual(result["configured"] as? Bool, true)
        XCTAssertFalse(String(describing: result).contains(secret)); XCTAssertFalse(String(describing: result).contains("private"))
        _ = try await config.set(agentID: "one", url: "https://example.com/other", secret: nil, policy: { policy }, agentExists: { true }, authorize: {})
        let list = try await config.list(agentIDs: ["one", "two"], authorize: {})
        XCTAssertEqual(list[0]["configured"] as? Bool, true); XCTAssertEqual(list[1]["configured"] as? Bool, false)
        XCTAssertFalse(String(describing: list).contains(secret)); XCTAssertFalse(String(describing: list).contains("example.com"))
        _ = try await config.remove(agentID: "one", agentExists: { true }, authorize: {})
        let saved = try await secrets.load("callback:one"); XCTAssertNil(saved)
    }
}
