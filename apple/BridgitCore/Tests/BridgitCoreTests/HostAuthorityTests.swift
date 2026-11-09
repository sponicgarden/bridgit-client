import Foundation
import XCTest
@testable import BridgitCore

final class HostAuthorityTests: XCTestCase {
    private func store() throws -> AppletStore {
        let path = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: path) }
        return try AppletStore(directory: path)
    }
    private func job(_ agent: HostAgent, sequence: Int64 = 1, device: String = "host-device", request: HostRequest = .init(method: "GET", path: "/v1/health")) -> HostJob {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        return .init(id: UUID().uuidString, sequence: sequence, agentID: agent.id, generation: agent.generation, deviceID: device, deadline: now + 30_000, createdAt: now, request: request)
    }
    func testBulkTableDeleteUsesExistingWriteGrantAndDiscovery() {
        let path = "/v1/applets/installed/library-test/tables/notes/rows"
        let request = HostRequest(method: "DELETE", path: path)
        XCTAssertEqual(request.route?.0, .appletsWrite)
        XCTAssertEqual(request.route?.1, "library-test")
        XCTAssertTrue(HostAPIContract.operations.contains { $0.name == "applets_rows_delete_all" && $0.method == "DELETE" && $0.grant == .appletsWrite })
    }
    func testStartsDisabledNeverImportsViewerAndPersistsReplay() async throws {
        let store = try store(), authority = HostAuthority(store: store, deviceID: "host-device")
        let (agent, token) = try await authority.create(name: "Fixture", operations: [.deviceRead], applets: [])
        XCTAssertTrue(Pairing.validToken(token))
        do { _ = try await authority.admit(job(agent)); XCTFail() } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "foreground_required") }
        await authority.setState(enabled: true, awake: true, foreground: true)
        _ = try await authority.admit(job(agent))
        let restarted = HostAuthority(store: store, deviceID: "host-device")
        await restarted.setState(enabled: true, awake: true, foreground: true)
        do { _ = try await restarted.admit(job(agent)); XCTFail() } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "replay") }
        do { _ = try await restarted.admit(job(agent, sequence: 2, device: "other-device")); XCTFail() } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "agent_revoked") }
    }
    func testBuildPromptUsesActiveScopedAgentEventFeed() async throws {
        let store = try store(), authority = HostAuthority(store: store, deviceID: "host-device")
        let engine = AppletEngine(store: store) { _, _, _ in [:] }
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: engine) { _ in throw CancellationError() }
        let (reader, _) = try await authority.create(name: "Reader", operations: [.interactionsRead], applets: [])
        let (other, _) = try await authority.create(name: "Other", operations: [.interactionsRead], applets: [])
        let (observer, _) = try await authority.create(name: "Observer", operations: [.deviceRead], applets: [])
        let read = HostRequest(method: "GET", path: "/v1/events", query: ["types": "applet.create_requested"])
        await authority.setState(enabled: true, awake: true, foreground: true)
        let initial = try await authority.buildInstructionRecipients()
        XCTAssertTrue(initial.isEmpty)
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        _ = try await authority.admit(job(reader, request: read), now: now)
        _ = try await authority.admit(job(observer), now: now)
        let eligible = try await authority.buildInstructionRecipients(now: now + 600_000)
        XCTAssertEqual(eligible.map(\.id), [reader.id])
        let expired = try await authority.buildInstructionRecipients(now: now + 600_001)
        XCTAssertTrue(expired.isEmpty)

        _ = try await authority.admit(job(other, request: read), now: now)
        let bothEligible = try await authority.buildInstructionRecipients(now: now)
        XCTAssertEqual(Set(bothEligible.map(\.id)), [reader.id, other.id])

        let guide = try XCTUnwrap(URL(string: "https://bridgit.cc/recipes/build-an-applet/"))
        try await authority.queueBuildInstructions(for: reader, prompt: "Fixture prompt", instructions: guide)
        let response = await dispatcher.dispatch(job(reader, sequence: 2, request: read))
        XCTAssertEqual(response.status, 200)
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: response.data) as? [String: Any])
        let events = try XCTUnwrap(body["events"] as? [[String: Any]])
        XCTAssertEqual(events.count, 1)
        let payload = try XCTUnwrap(events.first?["data"] as? [String: String])
        XCTAssertEqual(payload, ["prompt": "Fixture prompt", "instructions": guide.absoluteString])
        let otherResponse = await dispatcher.dispatch(job(other, sequence: 2, request: read))
        XCTAssertEqual(otherResponse.status, 200)
        let otherBody = try XCTUnwrap(JSONSerialization.jsonObject(with: otherResponse.data) as? [String: Any])
        XCTAssertEqual((otherBody["events"] as? [[String: Any]])?.count, 0)
        try await authority.pause(reader.id, paused: true)
        do {
            try await authority.queueBuildInstructions(for: reader, prompt: "Blocked", instructions: guide)
            XCTFail("Paused recipient accepted prompt")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "agent_unavailable") }
        await authority.setConnected(false)
        let offline = try await authority.buildInstructionRecipients()
        XCTAssertTrue(offline.isEmpty)
    }

    func testScopePauseForegroundAndGenerationFailClosed() async throws {
        let authority = HostAuthority(store: try store(), deviceID: "host-device")
        let (agent, _) = try await authority.create(name: "Scoped", operations: [.deviceRead, .appletsRead], applets: ["inventory"])
        await authority.setState(enabled: true, awake: true, foreground: true)
        _ = try await authority.validate(job(agent, request: .init(method: "GET", path: "/v1/applets/installed/inventory")))
        for request in [HostRequest(method: "GET", path: "/v1/applets/installed/another"), .init(method: "POST", path: "/v1/applets/installed/inventory/tables/items/rows")] {
            do { _ = try await authority.validate(job(agent, request: request)); XCTFail() } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "agent_not_allowed") }
        }
        try await authority.pause(agent.id, paused: true)
        do { _ = try await authority.validate(job(agent)); XCTFail() } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "agent_revoked") }
        try await authority.pause(agent.id, paused: false)
        await authority.setState(enabled: true, awake: true, foreground: false)
        do { _ = try await authority.validate(job(agent)); XCTFail() } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "foreground_required") }
    }
    func testDispatcherScopesRowsAndIdempotencyToAgentAndAction() async throws {
        let store = try store(), authority = HostAuthority(store: store, deviceID: "host-device")
        let engine = AppletEngine(store: store) { _, _, _ in [:] }
        try await engine.install(Data(contentsOf: XCTUnwrap(Bundle.module.url(forResource: "insurance-inventory", withExtension: "json", subdirectory: "Fixtures"))))
        let (agent, _) = try await authority.create(name: "Writer", operations: [.deviceRead, .appletsRead, .appletsWrite], applets: ["insurance-inventory"])
        await authority.setState(enabled: true, awake: true, foreground: true)
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: engine) { _ in throw DeviceAPIError(501, "fixture", "No profile") }
        var request = HostRequest(method: "POST", path: "/v1/applets/installed/insurance-inventory/tables/items/rows", body: ["row": ["label": "Lamp"]], idempotencyKey: "once")
        let first = await dispatcher.dispatch(job(agent, request: request)); XCTAssertEqual(first.status, 201)
        let retry = await dispatcher.dispatch(job(agent, sequence: 2, request: request)); XCTAssertEqual(first.data, retry.data)
        request.body = ["row": ["label": "Other"]]
        let conflict = await dispatcher.dispatch(job(agent, sequence: 3, request: request)); XCTAssertEqual(conflict.status, 409)
        let rows = try await store.rows(applet: "insurance-inventory", table: "items"); XCTAssertEqual(rows.count, 1)
        let id = try XCTUnwrap(rows.first?["id"] as? String)
        let delete = HostRequest(method: "DELETE", path: "/v1/applets/installed/insurance-inventory/tables/items/rows/" + id, idempotencyKey: "remove-once")
        let removed = await dispatcher.dispatch(job(agent, sequence: 4, request: delete))
        let removedAgain = await dispatcher.dispatch(job(agent, sequence: 5, request: delete))
        XCTAssertEqual(removed.status, 200); XCTAssertEqual(removed.data, removedAgain.data)
        let remaining = try await store.rows(applet: "insurance-inventory", table: "items")
        XCTAssertTrue(remaining.isEmpty)
    }
    func testBackgroundAwakeAuthorityPreservesGrantsAndSleepStopsWork() async throws {
        let store = try store()
        let mac = HostAuthority(store: store, deviceID: "host-device", allowsBackgroundAwake: true)
        let (agent, _) = try await mac.create(name: "Background fixture", operations: [.deviceRead], applets: [])
        await mac.setState(enabled: true, awake: true, foreground: false)
        XCTAssertFalse(mac.requiresForeground)
        _ = try await mac.validate(job(agent))
        do { _ = try await mac.validate(job(agent, request: .init(method: "POST", path: "/v1/camera/snapshot"))); XCTFail("Background state expanded caller grants") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.code, "agent_not_allowed") }
        await mac.setState(enabled: true, awake: false, foreground: false)
        do { _ = try await mac.validate(job(agent)); XCTFail("Asleep host executed") } catch { }
        await mac.setState(enabled: false, awake: true, foreground: false)
        do { _ = try await mac.validate(job(agent)); XCTFail("Stopped host executed") } catch { }
        let ios = HostAuthority(store: store, deviceID: "host-device")
        await ios.setState(enabled: true, awake: true, foreground: false)
        XCTAssertTrue(ios.requiresForeground)
        do { _ = try await ios.validate(job(agent)); XCTFail("iOS foreground restriction lost") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.code, "foreground_required") }
    }
    func testChunksAreRawJSONAndPreserveUnicodeAcrossByteBoundary() throws {
        let data = try JSONSerialization.data(withJSONObject: ["text": String(repeating: "🍏", count: 20000)])
        let chunks = try RelayHostConnection.resultChunks(data)
        XCTAssertGreaterThan(chunks.count, 1); XCTAssertEqual(Data(chunks.joined().utf8), data)
        XCTAssertTrue(chunks.allSatisfy { $0.utf8.count <= 32768 })
        XCTAssertNil(RelayHostConnection.integer(true)); XCTAssertNil(RelayHostConnection.integer(1.5))
    }
}

extension HostAuthorityTests {
    func testBoundedBackgroundLeaseNeverPromotesForegroundOrAllowsActionsIncludingNestedMCP() async throws {
        let store = try store(), authority = HostAuthority(store: store, deviceID: "host-device")
        let (agent, _) = try await authority.create(name: "Wake fixture", operations: Set(HostOperation.allCases), applets: ["inventory"])
        await authority.setState(enabled: false, awake: true, foreground: false)
        await authority.setConnected(true)
        do { _ = try await authority.validate(job(agent)); XCTFail("Connecting forged foreground") } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "foreground_required") }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        try await authority.setBackgroundReadLease(until: now + 20000, now: now)
        _ = try await authority.validate(job(agent), now: now)
        for request in [
            HostRequest(method: "POST", path: "/v1/camera/snapshot"),
            .init(method: "POST", path: "/v1/interactions"),
            .init(method: "POST", path: "/v1/settings"),
            .init(method: "POST", path: "/v1/applets/installed/inventory/tables/items/rows"),
            .init(method: "POST", path: "/v1/applets/installed/inventory/flows/read/run")
        ] {
            do { _ = try await authority.validate(job(agent, request: request), now: now); XCTFail("Background action admitted: \(request.path)") }
            catch let error as DeviceAPIError { XCTAssertEqual(error.code, "foreground_required") }
        }
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: AppletEngine(store: store) { _, _, _ in XCTFail("Paid work during wake"); return [:] }, profile: { _ in throw CancellationError() })
        let initialized = await dispatcher.dispatch(job(agent, sequence: 1, request: .init(method: "POST", path: "/v1/mcp", body: ["jsonrpc": "2.0", "id": 1, "method": "initialize", "params": ["protocolVersion": "2025-11-25", "clientInfo": ["name": "Fixture", "version": "1"], "capabilities": [:]]])))
        XCTAssertEqual(initialized.status, 200)
        let write = await dispatcher.dispatch(job(agent, sequence: 2, request: .init(method: "POST", path: "/v1/mcp", body: ["jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": ["name": "settings_change", "arguments": ["body": ["field": "host_camera", "value": true], "idempotency_key": "blocked"]]])))
        let value = try JSONSerialization.jsonObject(with: write.data) as! [String: Any]
        XCTAssertEqual((value["result"] as? [String: Any])?["isError"] as? Bool, true)
        let requests = try await store.ownerRequests(); XCTAssertTrue(requests.isEmpty)
        do { _ = try await authority.validate(job(agent), now: now + 20001); XCTFail("Expired lease") } catch {}
        do { try await authority.setBackgroundReadLease(until: now + 20001, now: now); XCTFail("Overlong lease") } catch {}
        await authority.setState(enabled: true, awake: false, foreground: false)
        do { _ = try await authority.validate(job(agent), now: now); XCTFail("Asleep despite lease") } catch {}
    }
}
