import Foundation
import SQLite3
import XCTest
@testable import BridgitCore

private actor ServiceSecrets: SatelliteSecrets {
    var data: [String: String] = [:]
    func load(_ id: String) async throws -> String? { data[id] }
    func save(_ value: String, for id: String) async throws { data[id] = value }
    func remove(_ id: String) async throws { data.removeValue(forKey: id) }
}
private actor ServiceCapture {
    var bodies: [Data] = []
    func add(_ body: Data) { bodies.append(body) }
}
private actor PendingCallbackGate {
    private var waiter: CheckedContinuation<Void, Never>?
    private(set) var entered = false
    func wait() async { entered = true; await withCheckedContinuation { waiter = $0 } }
    func release() { waiter?.resume(); waiter = nil }
}
private actor CallbackDestinations {
    private(set) var values: [String] = []
    func add(_ address: String) { values.append(address) }
}
final class CallbackHostServiceTests: XCTestCase {
    func testDispatcherDiscoveryAuthorizationAndMCPUseWiredServiceOnly() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory), authority = HostAuthority(store: store, deviceID: "fixture")
        let (agent, _) = try await authority.create(name: "Fixture", operations: [.deviceRead, .configurationRead, .configurationWrite, .interactionsRead], applets: [])
        let (denied, _) = try await authority.create(name: "Denied", operations: [.deviceRead], applets: [])
        await authority.setState(enabled: true, awake: true, foreground: true)
        await authority.setCallbackExecutionEnabled(true)
        let capture = ServiceCapture(), policy = CallbackNetworkPolicy(tailnetDomain: nil, allowInternet: true)
        let service = CallbackHostService(authority: authority, store: store, secrets: ServiceSecrets(), appVersion: "fixture", policy: { policy }, transport: { request, check in try await check(); await capture.add(request.body); return .init(status: 204) })
        try await service.start()
        let engine = AppletEngine(store: store) { _, _, _ in [:] }
        let wired = HostDispatcher(authority: authority, store: store, engine: engine, profile: { _ in throw CancellationError() }, callbacks: service)
        let unwired = HostDispatcher(authority: authority, store: store, engine: engine, profile: { _ in throw CancellationError() })
        func job(_ who: HostAgent, _ seq: Int64, _ request: HostRequest) -> HostJob {
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            return .init(id: UUID().uuidString, sequence: seq, agentID: who.id, generation: who.generation, deviceID: "fixture", deadline: now + 30000, createdAt: now, request: request)
        }
        let unwiredAPI = await unwired.dispatch(job(agent, 1, .init(method: "GET", path: "/v1/openapi.json")))
        let unwiredObject = try XCTUnwrap(try JSONSerialization.jsonObject(with: unwiredAPI.data) as? [String: Any])
        let unwiredPaths = try XCTUnwrap(unwiredObject["paths"] as? [String: Any]); XCTAssertNil(unwiredPaths["/v1/callback/test"])
        let wiredAPI = await wired.dispatch(job(agent, 2, .init(method: "GET", path: "/v1/openapi.json")))
        let wiredObject = try XCTUnwrap(try JSONSerialization.jsonObject(with: wiredAPI.data) as? [String: Any])
        let wiredPaths = try XCTUnwrap(wiredObject["paths"] as? [String: Any]); XCTAssertNotNil(wiredPaths["/v1/callback/test"])
        let deniedSet = await wired.dispatch(job(denied, 1, .init(method: "POST", path: "/v1/owner/configuration/callbacks/" + agent.id, body: ["url": "https://example.com/hook", "secret": "cbs_" + String(repeating: "a", count: 43)], idempotencyKey: "denied")))
        XCTAssertEqual(deniedSet.status, 403)
        let set = await wired.dispatch(job(agent, 3, .init(method: "POST", path: "/v1/owner/configuration/callbacks/" + agent.id, body: ["url": "https://example.com/hook", "secret": "cbs_" + String(repeating: "a", count: 43)], idempotencyKey: "set")))
        XCTAssertEqual(set.status, 200)
        let activity = try await store.hostActivity(kinds: [.request])
        let setting = try XCTUnwrap(activity.first { $0.requestFacts?.endpoint == "/v1/owner/configuration/callbacks/{id}" && $0.status == 200 })
        let params = try XCTUnwrap(setting.requestFacts?.params)
        XCTAssertFalse(params.contains("example.com")); XCTAssertFalse(params.contains("cbs_"))
        let redacted = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(params.utf8)) as? [String: String])
        XCTAssertEqual(redacted["url"], "[redacted]"); XCTAssertEqual(redacted["secret"], "[redacted]")
        let ownStatus = try await service.status(agent: agent)
        XCTAssertEqual(ownStatus["set"] as? Bool, true)
        XCTAssertEqual(ownStatus["host"] as? String, "example.com")
        XCTAssertEqual(Set(ownStatus.keys), Set(["set", "host"]))
        let otherStatus = try await service.status(agent: denied)
        XCTAssertEqual(otherStatus["set"] as? Bool, false)
        XCTAssertTrue(otherStatus["host"] is NSNull)
        let testRequest = HostRequest(method: "POST", path: "/v1/callback/test", idempotencyKey: "test-once")
        let first = await wired.dispatch(job(agent, 4, testRequest)); XCTAssertEqual(first.status, 200)
        let replay = await wired.dispatch(job(agent, 5, testRequest)); XCTAssertEqual(replay.status, 200)
        let count = await capture.bodies.count; XCTAssertEqual(count, 1)
        let callbackLogs = try await store.hostActivity(kinds: [.agent])
        XCTAssertTrue(callbackLogs.contains { $0.operation.contains("example.com") && $0.operation.contains("callback.test") })
        XCTAssertFalse(callbackLogs.contains { $0.operation.contains("/hook") || $0.operation.contains("cbs_") })
        let initialize: [String: Any] = ["jsonrpc": "2.0", "id": 1, "method": "initialize", "params": ["protocolVersion": "2025-11-25", "capabilities": [:], "clientInfo": ["name": "Fixture", "version": "1"]]]
        _ = await wired.dispatch(job(agent, 6, .init(method: "POST", path: "/v1/mcp", body: initialize)))
        let call: [String: Any] = ["jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": ["name": "callback_test", "arguments": ["idempotency_key": "mcp-test"]]]
        let result = await wired.dispatch(job(agent, 7, .init(method: "POST", path: "/v1/mcp", body: call)))
        XCTAssertEqual(result.status, 200)
        let object = try XCTUnwrap(try JSONSerialization.jsonObject(with: result.data) as? [String: Any])
        XCTAssertFalse((object["result"] as? [String: Any])?["isError"] as? Bool ?? true)
        let total = await capture.bodies.count; XCTAssertEqual(total, 2)
        let mcpSet: [String: Any] = ["jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": ["name": "configuration_callback_set", "arguments": ["id": agent.id, "idempotency_key": "mcp-set", "body": ["url": "https://private.example.org/path?token=fixture", "secret": "cbs_" + String(repeating: "z", count: 43)]]]]
        let mcpSetResult = await wired.dispatch(job(agent, 8, .init(method: "POST", path: "/v1/mcp", body: mcpSet)))
        XCTAssertEqual(mcpSetResult.status, 200)
        let mcpObject = try XCTUnwrap(try JSONSerialization.jsonObject(with: mcpSetResult.data) as? [String: Any])
        XCTAssertFalse((mcpObject["result"] as? [String: Any])?["isError"] as? Bool ?? true)
        let entries = try await store.hostActivity(kinds: [.request])
        let setEntries = entries.filter { $0.requestFacts?.endpoint == "/v1/owner/configuration/callbacks/{id}" && $0.status == 200 }
        XCTAssertEqual(setEntries.count, 2)
        for entry in setEntries {
            let savedParams = try XCTUnwrap(entry.requestFacts?.params)
            XCTAssertFalse(savedParams.contains("private.example.org")); XCTAssertFalse(savedParams.contains("cbs_"))
            let fields = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(savedParams.utf8)) as? [String: String])
            XCTAssertEqual(fields["url"], "[redacted]"); XCTAssertEqual(fields["secret"], "[redacted]")
        }
        await service.stop()
    }

    func testCallbackTestFailureReturnsSanitizedStatusOrReason() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory), authority = HostAuthority(store: store, deviceID: "fixture")
        let (agent, _) = try await authority.create(name: "Fixture", operations: [.configurationWrite, .interactionsRead], applets: [])
        await authority.setState(enabled: true, awake: true, foreground: true); await authority.setCallbackExecutionEnabled(true)
        let secrets = ServiceSecrets(), policy = CallbackNetworkPolicy(tailnetDomain: nil, allowInternet: true)
        let service = CallbackHostService(authority: authority, store: store, secrets: secrets, appVersion: "fixture", policy: { policy }, transport: { _, check in try await check(); return .init(status: 418) })
        try await service.start()
        _ = try await service.configuration(.init(method: "POST", path: "/v1/owner/configuration/callbacks/" + agent.id, body: ["url": "https://example.com/private?token=fixture", "secret": "cbs_" + String(repeating: "a", count: 43)]), agent: agent)
        let failure = try await service.test(agent: agent)
        XCTAssertEqual(failure.status, 502)
        let body = try XCTUnwrap(try JSONSerialization.jsonObject(with: failure.data) as? [String: Any])
        XCTAssertEqual(body["error"] as? String, "callback_failed"); XCTAssertEqual(body["host"] as? String, "example.com")
        XCTAssertEqual(body["http_status"] as? Int, 418); XCTAssertEqual(body["delivered"] as? Bool, false)
        XCTAssertFalse(String(decoding: failure.data, as: UTF8.self).contains("private")); XCTAssertFalse(String(decoding: failure.data, as: UTF8.self).contains("cbs_"))
        await service.stop()
        let failedNetwork = CallbackHostService(authority: authority, store: store, secrets: secrets, appVersion: "fixture", policy: { policy }, transport: { _, check in try await check(); throw DeviceAPIError(504, "connection_timeout", "secret-bearing text") })
        try await failedNetwork.start()
        let timeout = try await failedNetwork.test(agent: agent)
        let timeoutBody = try XCTUnwrap(try JSONSerialization.jsonObject(with: timeout.data) as? [String: Any])
        XCTAssertEqual(timeout.status, 502); XCTAssertEqual(timeoutBody["reason"] as? String, "timeout")
        XCTAssertFalse(String(decoding: timeout.data, as: UTF8.self).contains("secret-bearing"))
        await failedNetwork.stop()
        let disallowed = CallbackHostService(authority: authority, store: store, secrets: secrets, appVersion: "fixture", policy: { .init(tailnetDomain: nil, allowInternet: false) }, transport: { _, _ in XCTFail("Disallowed callback must not send"); return .init(status: 204) })
        try await disallowed.start()
        let blocked = try await disallowed.test(agent: agent)
        let blockedBody = try XCTUnwrap(try JSONSerialization.jsonObject(with: blocked.data) as? [String: Any])
        XCTAssertEqual(blocked.status, 502); XCTAssertEqual(blockedBody["reason"] as? String, "not_allowed")
        let events = try await store.interactionEvents(owner: agent.eventOwner)
        XCTAssertEqual((events["events"] as? [[String: Any]])?.filter { $0["type"] as? String == "callback.test" }.count, 3)
        await disallowed.stop()
    }

    func testDelegatedRemoveReaddPreservesEligibilityButNativeRemoveInvalidates() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory), authority = HostAuthority(store: store, deviceID: "fixture")
        let (agent, _) = try await authority.create(name: "Fixture", operations: [.configurationWrite, .interactionsRead], applets: [])
        await authority.setState(enabled: true, awake: true, foreground: true); await authority.setCallbackExecutionEnabled(true)
        let secrets = ServiceSecrets(), policy = CallbackNetworkPolicy(tailnetDomain: nil, allowInternet: true), capture = ServiceCapture()
        let service = CallbackHostService(authority: authority, store: store, secrets: secrets, appVersion: "fixture", policy: { policy }, transport: { request, check in try await check(); await capture.add(request.body); return .init(status: 204) })
        let config = IndexedCallbackConfiguration(store: store, secrets: secrets)
        let delivery = CallbackDelivery(authority: authority, store: store, configuration: config, appVersion: "fixture", policy: { policy }, transport: { request, check in try await check(); await capture.add(request.body); return .init(status: 204) })
        try await service.start()
        let path = "/v1/owner/configuration/callbacks/" + agent.id
        func set(_ suffix: String) async throws {
            _ = try await service.configuration(.init(method: "POST", path: path, body: ["url": "https://example.com/" + suffix, "secret": "cbs_" + String(repeating: "a", count: 43)]), agent: agent)
        }
        try await set("first")
        let card = try await store.createInteraction(owner: agent.eventOwner, input: ["kind": "notice", "title": "Fixture", "no_takeover": true])
        try await store.markInteractionSeen(id: try XCTUnwrap(card["interaction_id"] as? String))
        let page = try await store.interactionEvents(owner: agent.eventOwner)
        let event = try XCTUnwrap((page["events"] as? [[String: Any]])?.last)
        let id = try XCTUnwrap(Int64(try XCTUnwrap(event["id"] as? String)))
        _ = try await service.configuration(.init(method: "DELETE", path: path), agent: agent)
        let missing = await delivery.outcome(agentID: agent.id, grantRevision: 0, configEpoch: 0, eventOwner: agent.eventOwner, eventID: id)
        XCTAssertEqual(missing, .dropped)
        try await set("second")
        let retained = await delivery.outcome(agentID: agent.id, grantRevision: 0, configEpoch: 0, eventOwner: agent.eventOwner, eventID: id)
        XCTAssertEqual(retained, .delivered)
        try await service.ownerRemove(agentID: agent.id)
        try await set("third")
        let invalidated = await delivery.outcome(agentID: agent.id, grantRevision: 0, configEpoch: 0, eventOwner: agent.eventOwner, eventID: id)
        XCTAssertEqual(invalidated, .dropped)
        await service.stop()
    }

    func testDelegatedRemoveReaddPreservesActualPendingDelivery() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory), authority = HostAuthority(store: store, deviceID: "fixture")
        let (agent, _) = try await authority.create(name: "Fixture", operations: [.configurationWrite, .interactionsRead], applets: [])
        await authority.setState(enabled: true, awake: true, foreground: true); await authority.setCallbackExecutionEnabled(true)
        let gate = PendingCallbackGate(), captured = CallbackDestinations(), policy = CallbackNetworkPolicy(tailnetDomain: nil, allowInternet: true)
        let service = CallbackHostService(authority: authority, store: store, secrets: ServiceSecrets(), appVersion: "fixture", policy: { policy }, transport: { request, check in try await check(); await captured.add(request.address); return .init(status: 204) }, beforeRunDue: { await gate.wait() })
        try await service.start()
        let path = "/v1/owner/configuration/callbacks/" + agent.id
        func set(_ suffix: String) async throws {
            _ = try await service.configuration(.init(method: "POST", path: path, body: ["url": "https://example.com/" + suffix, "secret": "cbs_" + String(repeating: "a", count: 43)]), agent: agent)
        }
        try await set("first")
        let card = try await store.createInteraction(owner: agent.eventOwner, input: ["kind": "notice", "title": "Fixture", "no_takeover": true])
        try await store.markInteractionSeen(id: try XCTUnwrap(card["interaction_id"] as? String))
        for _ in 0..<500 { if await gate.entered { break }; try await Task.sleep(for: .milliseconds(10)) }
        let entered = await gate.entered; XCTAssertTrue(entered)
        _ = try await service.configuration(.init(method: "DELETE", path: path), agent: agent)
        try await set("second")
        await gate.release()
        for _ in 0..<500 { if await captured.values.count == 1 { break }; try await Task.sleep(for: .milliseconds(10)) }
        let values = await captured.values; XCTAssertEqual(values, ["https://example.com/second"])
        await service.stop()
    }

    func testNativeRemoveCancelsActualPendingDelivery() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory), authority = HostAuthority(store: store, deviceID: "fixture")
        let (agent, _) = try await authority.create(name: "Fixture", operations: [.configurationWrite, .interactionsRead], applets: [])
        await authority.setState(enabled: true, awake: true, foreground: true); await authority.setCallbackExecutionEnabled(true)
        let gate = PendingCallbackGate(), captured = CallbackDestinations(), policy = CallbackNetworkPolicy(tailnetDomain: nil, allowInternet: true)
        let service = CallbackHostService(authority: authority, store: store, secrets: ServiceSecrets(), appVersion: "fixture", policy: { policy }, transport: { request, check in try await check(); await captured.add(request.address); return .init(status: 204) }, beforeRunDue: { await gate.wait() })
        try await service.start()
        let path = "/v1/owner/configuration/callbacks/" + agent.id
        func set() async throws {
            _ = try await service.configuration(.init(method: "POST", path: path, body: ["url": "https://example.com/fixture", "secret": "cbs_" + String(repeating: "a", count: 43)]), agent: agent)
        }
        try await set()
        let card = try await store.createInteraction(owner: agent.eventOwner, input: ["kind": "notice", "title": "Fixture", "no_takeover": true])
        try await store.markInteractionSeen(id: try XCTUnwrap(card["interaction_id"] as? String))
        for _ in 0..<500 { if await gate.entered { break }; try await Task.sleep(for: .milliseconds(10)) }
        let entered = await gate.entered; XCTAssertTrue(entered)
        try await service.ownerRemove(agentID: agent.id); try await set(); await gate.release()
        try await Task.sleep(for: .milliseconds(1200))
        let values = await captured.values; XCTAssertTrue(values.isEmpty)
        await service.stop()
    }

    func testCallbackDiagnosticsAreAgentScopedAndFilterable() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory), authority = HostAuthority(store: store, deviceID: "fixture")
        let (one, _) = try await authority.create(name: "One", operations: [.log], applets: [])
        let (two, _) = try await authority.create(name: "Two", operations: [.log], applets: [])
        try await store.recordCallbackAttempt(agent: one, host: "one.example", eventID: 17, eventType: "interaction.seen", status: 418)
        try await store.recordCallbackAttempt(agent: two, host: "two.example", eventID: 18, eventType: "interaction.seen", status: 204)
        var raw: OpaquePointer?
        XCTAssertEqual(sqlite3_open(directory.appendingPathComponent("applets.sqlite").path, &raw), SQLITE_OK)
        defer { sqlite3_close(raw) }
        XCTAssertEqual(sqlite3_exec(raw, "INSERT INTO host_activity(at,agent,operation,status,kind,note_data) VALUES(0,'Legacy','old',200,'agent','legacy-not-json')", nil, nil, nil), SQLITE_OK)
        let query = try HostLogQuery(["channel": "diagnostic", "kind": "agent", "result": "failed"], caller: one, agents: [one, two])
        let own = try await store.hostLogRows(query, scope: .init(ownID: one.id))
        let other = try await store.hostLogRows(query, scope: .init(ownID: two.id))
        let all = try await store.hostLogRows(query, scope: .init(ownID: nil)); XCTAssertEqual(all.count, 1)
        XCTAssertEqual(own.count, 1); XCTAssertTrue(other.isEmpty)
        let detail = HostActivityDetail(try XCTUnwrap(own.first))
        XCTAssertEqual(detail.text(for: "channel"), "diagnostic"); XCTAssertEqual(detail.text(for: "kind"), "agent")
        XCTAssertEqual(detail.text(for: "agent_id"), one.id); XCTAssertEqual(detail.text(for: "http"), "418")
        XCTAssertEqual(detail.text(for: "result"), "failed"); XCTAssertEqual(detail.text(for: "level"), "warn")
        XCTAssertFalse(detail.text(for: "data").contains("__callback"))
    }

    func testNativeOwnerCreatesNewOneTimeKeyAndCanRemove() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory), authority = HostAuthority(store: store, deviceID: "fixture")
        let (agent, _) = try await authority.create(name: "Fixture", operations: [.interactionsRead], applets: [])
        let service = CallbackHostService(authority: authority, store: store, secrets: ServiceSecrets(), appVersion: "fixture", policy: { .init(tailnetDomain: nil, allowInternet: true) })
        try await service.start()
        try await service.validateOwnerURL("https://example.com/private?key=fixture")
        let first = try await service.ownerSet(agentID: agent.id, url: "https://example.com/private?key=fixture")
        XCTAssertTrue(CallbackRules.validSecret(first))
        let setupSecret = try await service.ownerSetupSecret(agentID: agent.id)
        XCTAssertTrue(setupSecret == first)
        let ownerURL = try await service.ownerURL(agentID: agent.id)
        XCTAssertEqual(ownerURL, "https://example.com/private?key=fixture")
        let status = try await service.ownerStatus(agentID: agent.id)
        XCTAssertEqual(status["host"] as? String, "example.com"); XCTAssertEqual(status["set"] as? Bool, true)
        XCTAssertEqual(Set(status.keys), Set(["set", "host"]))
        let second = try await service.ownerSet(agentID: agent.id, url: "https://example.com/other")
        XCTAssertTrue(CallbackRules.validSecret(second)); XCTAssertTrue(first != second)
        try await service.ownerRemove(agentID: agent.id)
        let removed = try await service.ownerStatus(agentID: agent.id); XCTAssertEqual(removed["set"] as? Bool, false)
        await service.stop()
    }

    func testOwnerConfigurationAndSynchronousTestDoesNotQueueTwice() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory), authority = HostAuthority(store: store, deviceID: "fixture")
        let (agent, _) = try await authority.create(name: "Fixture", operations: [.configurationRead, .configurationWrite, .interactionsRead], applets: [])
        await authority.setState(enabled: true, awake: true, foreground: true)
        await authority.setCallbackExecutionEnabled(true)
        let capture = ServiceCapture(), policy = CallbackNetworkPolicy(tailnetDomain: nil, allowInternet: true)
        let service = CallbackHostService(authority: authority, store: store, secrets: ServiceSecrets(), appVersion: "fixture", policy: { policy }, transport: { request, check in
            try await check(); await capture.add(request.body); return .init(status: 204)
        })
        try await service.start()
        let secret = "cbs_" + String(repeating: "a", count: 43), url = "https://example.com/private?token=x"
        let set = try await service.configuration(.init(method: "POST", path: "/v1/owner/configuration/callbacks/" + agent.id, body: ["url": url, "secret": secret]), agent: agent)
        XCTAssertEqual(set.status, 200); XCTAssertFalse(String(decoding: set.data, as: UTF8.self).contains(secret))
        let list = try await service.configuration(.init(method: "GET", path: "/v1/owner/configuration/callbacks"), agent: agent)
        let text = String(decoding: list.data, as: UTF8.self); XCTAssertFalse(text.contains(secret)); XCTAssertFalse(text.contains("example.com"))
        let tested = try await service.test(agent: agent); XCTAssertEqual(tested.status, 200)
        try await Task.sleep(for: .milliseconds(1200))
        let bodies = await capture.bodies; XCTAssertEqual(bodies.count, 1)
        let payload = try XCTUnwrap(try JSONSerialization.jsonObject(with: try XCTUnwrap(bodies.first)) as? [String: Any])
        XCTAssertEqual((payload["event"] as? [String: Any])?["type"] as? String, "callback.test")
        let removal = try await service.configuration(.init(method: "DELETE", path: "/v1/owner/configuration/callbacks/" + agent.id), agent: agent)
        XCTAssertEqual(removal.status, 200)
        do { _ = try await service.test(agent: agent); XCTFail("Removed callback should not send") } catch {}
        await service.stop()
    }
}
