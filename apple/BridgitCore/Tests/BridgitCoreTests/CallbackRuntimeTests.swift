import XCTest
@testable import BridgitCore

private actor RuntimeSecrets: SatelliteSecrets {
    var data: [String: String] = [:]
    func load(_ id: String) async throws -> String? { data[id] }
    func save(_ value: String, for id: String) async throws { data[id] = value }
    func remove(_ id: String) async throws { data.removeValue(forKey: id) }
}
private actor RuntimeCapture {
    var bodies: [Data] = []
    func add(_ body: Data) { bodies.append(body) }
}
private actor RuntimeGate {
    private var waiting: CheckedContinuation<Void, Never>?
    private var used = false
    private(set) var entered = false
    func pauseOnce() async {
        guard !used else { return }
        used = true; entered = true
        await withCheckedContinuation { waiting = $0 }
    }
    func release() { waiting?.resume(); waiting = nil }
}
final class CallbackRuntimeTests: XCTestCase {
    func testStoppedRuntimeReleasesAuthorityAndStore() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        weak var weakAuthority: HostAuthority?
        weak var weakStore: AppletStore?
        do {
            let store = try AppletStore(directory: directory)
            let authority = HostAuthority(store: store, deviceID: "fixture")
            let config = IndexedCallbackConfiguration(store: store, secrets: RuntimeSecrets())
            let runtime = CallbackRuntime(store: store, authority: authority, configuration: config, appVersion: "fixture", policy: { .init(tailnetDomain: nil, allowInternet: false) })
            weakAuthority = authority; weakStore = store
            try await runtime.start(); await runtime.stop()
        }
        for _ in 0..<200 {
            if weakAuthority == nil && weakStore == nil { break }
            try await Task.sleep(for: .milliseconds(5))
        }
        XCTAssertNil(weakAuthority); XCTAssertNil(weakStore)
    }

    func testStopDuringStartupCannotInstallTasks() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory), authority = HostAuthority(store: store, deviceID: "fixture")
        let config = IndexedCallbackConfiguration(store: store, secrets: RuntimeSecrets()), gate = RuntimeGate()
        let runtime = CallbackRuntime(store: store, authority: authority, configuration: config, appVersion: "fixture", policy: { .init(tailnetDomain: nil, allowInternet: false) }, beforeStart: { await gate.pauseOnce() })
        let start = Task { try await runtime.start() }
        for _ in 0..<200 { if await gate.entered { break }; try await Task.sleep(for: .milliseconds(5)) }
        let entered = await gate.entered; XCTAssertTrue(entered)
        let stop = Task { await runtime.stop() }
        try await Task.sleep(for: .milliseconds(50)); await gate.release()
        try await start.value; await stop.value
        // A fresh start and stop must work after the interrupted setup.
        try await runtime.start(); await runtime.stop()
    }

    func testStoppedDrainCannotMutateRestartedWorker() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory), authority = HostAuthority(store: store, deviceID: "fixture")
        let (agent, _) = try await authority.create(name: "Fixture", operations: [.interactionsRead], applets: [])
        await authority.setState(enabled: false, awake: true, foreground: true); await authority.setCallbackExecutionEnabled(true)
        let config = IndexedCallbackConfiguration(store: store, secrets: RuntimeSecrets()), gate = RuntimeGate(), capture = RuntimeCapture()
        let policy = CallbackNetworkPolicy(tailnetDomain: nil, allowInternet: true)
        _ = try await config.set(agentID: agent.id, url: "https://example.com/hook", secret: "cbs_" + String(repeating: "a", count: 43), caller: nil, policy: { policy }, authorize: {})
        let runtime = CallbackRuntime(store: store, authority: authority, configuration: config, appVersion: "fixture", policy: { policy }, transport: { request, check in try await check(); await capture.add(request.body); return .init(status: 204) }, beforeEnqueue: { await gate.pauseOnce() })
        func emit(_ title: String) async throws {
            let card = try await store.createInteraction(owner: agent.eventOwner, input: ["kind": "notice", "title": title, "no_takeover": true])
            try await store.markInteractionSeen(id: try XCTUnwrap(card["interaction_id"] as? String))
        }
        try await runtime.start(); try await emit("Old")
        for _ in 0..<500 { if await gate.entered { break }; try await Task.sleep(for: .milliseconds(5)) }
        let entered = await gate.entered; XCTAssertTrue(entered)
        await runtime.stop(); try await runtime.start(); await gate.release(); try await emit("New")
        for _ in 0..<500 { if await capture.bodies.count == 1 { break }; try await Task.sleep(for: .milliseconds(10)) }
        try await Task.sleep(for: .milliseconds(1100))
        let count = await capture.bodies.count; XCTAssertEqual(count, 1)
        await runtime.stop()
    }

    func testEventBeforeConfigurationIsNeverDeliveredAfterSetup() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory), authority = HostAuthority(store: store, deviceID: "fixture")
        let (agent, _) = try await authority.create(name: "Fixture", operations: [.interactionsRead], applets: [])
        await authority.setState(enabled: true, awake: true, foreground: true)
        await authority.setCallbackExecutionEnabled(true)
        let config = IndexedCallbackConfiguration(store: store, secrets: RuntimeSecrets())
        let policy = CallbackNetworkPolicy(tailnetDomain: nil, allowInternet: true), capture = RuntimeCapture()
        let runtime = CallbackRuntime(store: store, authority: authority, configuration: config, appVersion: "fixture", policy: { policy }, transport: { request, check in try await check(); await capture.add(request.body); return .init(status: 204) })
        try await runtime.start()
        func emit(_ title: String) async throws {
            let card = try await store.createInteraction(owner: agent.eventOwner, input: ["kind": "notice", "title": title, "no_takeover": true])
            try await store.markInteractionSeen(id: try XCTUnwrap(card["interaction_id"] as? String))
        }
        try await emit("Before")
        _ = try await config.set(agentID: agent.id, url: "https://example.com/hook", secret: "cbs_" + String(repeating: "a", count: 43), caller: nil, policy: { policy }, authorize: {})
        try await emit("After")
        for _ in 0..<500 { if await capture.bodies.count == 1 { break }; try await Task.sleep(for: .milliseconds(10)) }
        try await Task.sleep(for: .milliseconds(1100))
        let bodies = await capture.bodies; XCTAssertEqual(bodies.count, 1)
        await runtime.stop()
    }

    func testHostIntentDefersThenDeliversWithoutInboundConnection() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory), authority = HostAuthority(store: store, deviceID: "fixture")
        let (agent, _) = try await authority.create(name: "Fixture", operations: [.interactionsRead], applets: [])
        await authority.setState(enabled: false, awake: true, foreground: true)
        let config = IndexedCallbackConfiguration(store: store, secrets: RuntimeSecrets())
        let policy = CallbackNetworkPolicy(tailnetDomain: nil, allowInternet: true), capture = RuntimeCapture()
        _ = try await config.set(agentID: agent.id, url: "https://example.com/hook", secret: "cbs_" + String(repeating: "a", count: 43), caller: nil, policy: { policy }, authorize: {})
        let runtime = CallbackRuntime(store: store, authority: authority, configuration: config, appVersion: "fixture", policy: { policy }, transport: { request, check in try await check(); await capture.add(request.body); return .init(status: 204) })
        try await runtime.start()
        let card = try await store.createInteraction(owner: agent.eventOwner, input: ["kind": "notice", "title": "Deferred", "no_takeover": true])
        try await store.markInteractionSeen(id: try XCTUnwrap(card["interaction_id"] as? String))
        try await Task.sleep(for: .milliseconds(1300))
        let before = await capture.bodies.count; XCTAssertEqual(before, 0)
        await authority.setCallbackExecutionEnabled(true)
        for _ in 0..<500 { if await capture.bodies.count == 1 { break }; try await Task.sleep(for: .milliseconds(10)) }
        let after = await capture.bodies.count; XCTAssertEqual(after, 1)
        await runtime.stop()
    }

    func testCommittedEventIsDeliveredOnceWithoutReplayingOldEvents() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory), authority = HostAuthority(store: store, deviceID: "fixture")
        let (agent, _) = try await authority.create(name: "Fixture", operations: [.interactionsRead], applets: [])
        await authority.setState(enabled: true, awake: true, foreground: true)
        await authority.setCallbackExecutionEnabled(true)
        let config = IndexedCallbackConfiguration(store: store, secrets: RuntimeSecrets())
        let policy = CallbackNetworkPolicy(tailnetDomain: nil, allowInternet: true)
        _ = try await config.set(agentID: agent.id, url: "https://example.com/hook", secret: "cbs_" + String(repeating: "a", count: 43), caller: nil, policy: { policy }, authorize: {})
        func emit(_ title: String) async throws {
            let card = try await store.createInteraction(owner: agent.eventOwner, input: ["kind": "notice", "title": title, "no_takeover": true])
            try await store.markInteractionSeen(id: try XCTUnwrap(card["interaction_id"] as? String))
        }
        try await emit("Old")
        let capture = RuntimeCapture()
        let runtime = CallbackRuntime(store: store, authority: authority, configuration: config, appVersion: "fixture", policy: { policy }, transport: { request, check in
            try await check(); await capture.add(request.body); return .init(status: 204)
        })
        try await runtime.start(); try await emit("New")
        for _ in 0..<500 { if await capture.bodies.count == 1 { break }; try await Task.sleep(for: .milliseconds(10)) }
        let bodies = await capture.bodies; XCTAssertEqual(bodies.count, 1)
        let payload = try XCTUnwrap(try JSONSerialization.jsonObject(with: try XCTUnwrap(bodies.first)) as? [String: Any])
        XCTAssertEqual((payload["event"] as? [String: Any])?["type"] as? String, "interaction.seen")
        await runtime.stop()
    }
}
