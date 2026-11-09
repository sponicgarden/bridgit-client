import Foundation
import XCTest
@testable import BridgitCore

private actor ManagementCalls {
    var calls = 0
    var events: [String] = []
    func appraise() -> [String: Any] { calls += 1; return ["items": []] }
    func changed(_ event: String) { events.append(event) }
}
private actor ManagementGate {
    var calls = 0
    var waiting = false
    private var continuation: CheckedContinuation<Void, Never>?
    func appraise() async -> [String: Any] {
        calls += 1; waiting = true
        await withCheckedContinuation { continuation = $0 }
        return ["items": []]
    }
    func release() { continuation?.resume(); continuation = nil }
}
final class AppletManagementTests: XCTestCase {
    private func package() throws -> [String: Any] {
        let url = try XCTUnwrap(Bundle.module.url(forResource: "insurance-inventory", withExtension: "json", subdirectory: "Fixtures"))
        return try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: Any]
    }
    private func setup(gate: ManagementGate? = nil) async throws -> (AppletStore, AppletEngine, HostAuthority, HostDispatcher, HostAgent, ManagementCalls) {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory), calls = ManagementCalls()
        let engine = AppletEngine(store: store) { _, _, _ in
            if let gate { return await gate.appraise() }
            return await calls.appraise()
        }
        let authority = HostAuthority(store: store, deviceID: "management-fixture")
        let (manager, _) = try await authority.create(name: "Owner control", operations: [.deviceRead, .appletsManage, .workflowsRun], applets: ["insurance-inventory"])
        await authority.setState(enabled: true, awake: true, foreground: true)
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: engine, appletChanged: { _, event in await calls.changed(event) }, profile: { _ in throw CancellationError() })
        return (store, engine, authority, dispatcher, manager, calls)
    }
    private func job(_ agent: HostAgent, _ sequence: Int64, _ request: HostRequest) -> HostJob {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        return .init(id: UUID().uuidString, sequence: sequence, agentID: agent.id, generation: agent.generation, deviceID: "management-fixture", deadline: now + 30000, createdAt: now, request: request)
    }
    private func object(_ response: HostResponse) throws -> [String: Any] { try JSONSerialization.jsonObject(with: response.data) as! [String: Any] }
    func testPreflightInstallRetryAndGlobalOwnerReadsDoNotWidenOrdinaryScopes() async throws {
        let (store, _, authority, dispatcher, manager, calls) = try await setup(), definition = try package()
        let validate = await dispatcher.dispatch(job(manager, 1, .init(method: "POST", path: "/v1/applets/validate", body: ["package": definition], idempotencyKey: "validate")))
        XCTAssertEqual(validate.status, 200); XCTAssertEqual(try object(validate)["installed"] as? Bool, false)
        let before = try await store.packages(); XCTAssertTrue(before.isEmpty)
        let install = HostRequest(method: "POST", path: "/v1/applets/install", body: ["package": definition], idempotencyKey: "install")
        let first = await dispatcher.dispatch(job(manager, 2, install)), retry = await dispatcher.dispatch(job(manager, 3, install))
        XCTAssertEqual(first.status, 201); XCTAssertEqual(first.data, retry.data)
        let events = await calls.events; XCTAssertEqual(events, ["install"])
        let duplicate = await dispatcher.dispatch(job(manager, 4, .init(method: "POST", path: install.path, body: install.body, idempotencyKey: "duplicate")))
        XCTAssertEqual(duplicate.status, 409)
        let read = await dispatcher.dispatch(job(manager, 5, .init(method: "GET", path: "/v1/applets/installed/insurance-inventory/data")))
        XCTAssertEqual(read.status, 200, "Explicit management keys inspect all applets without an installer-ownership gate")
        XCTAssertFalse(manager.allows(.cameraSnapshot)); XCTAssertFalse(manager.allows(.speechHear)); XCTAssertFalse(manager.allows(.configurationWrite))
        let (reader, _) = try await authority.create(name: "Named applet reader", operations: [.appletsRead], applets: ["other"])
        let denied = await dispatcher.dispatch(job(reader, 1, .init(method: "GET", path: "/v1/applets/installed/insurance-inventory/data")))
        XCTAssertEqual(denied.status, 403)
        let cannotInstall = await dispatcher.dispatch(job(reader, 2, install)); XCTAssertEqual(cannotInstall.status, 403)
        let tools = HostAPIContract.tools(manager).compactMap { $0["name"] as? String }
        XCTAssertTrue(tools.contains("applets_install")); XCTAssertTrue(tools.contains("applets_photo"))
        XCTAssertFalse(HostAPIContract.tools(reader).contains { $0["name"] as? String == "applets_install" })
    }
    func testUpdateValidationPauseReadbackAndRemovalPreserveOtherApplet() async throws {
        let (store, engine, _, dispatcher, manager, _) = try await setup(), definition = try package()
        try await engine.install(JSONSerialization.data(withJSONObject: definition))
        var other = definition; other["id"] = "other"
        try await engine.install(JSONSerialization.data(withJSONObject: other))
        _ = try await store.addRows(applet: "other", table: "items", rows: [["label": "Keep me"]])
        let photo = try await engine.addPhoto(applet: "insurance-inventory", jpeg: Data([1]))
        let base = "/v1/applets/installed/insurance-inventory"
        let mismatched = await dispatcher.dispatch(job(manager, 1, .init(method: "POST", path: base + "/update", body: ["package": other], idempotencyKey: "mismatch")))
        XCTAssertEqual(mismatched.status, 422)
        let pause = await dispatcher.dispatch(job(manager, 2, .init(method: "POST", path: base + "/pause", body: ["paused": true], idempotencyKey: "pause")))
        XCTAssertEqual(pause.status, 200); XCTAssertEqual(try object(pause)["status"] as? String, "paused")
        let read = await dispatcher.dispatch(job(manager, 3, .init(method: "GET", path: base)))
        XCTAssertEqual(try object(read)["status"] as? String, "paused")
        let denied = await dispatcher.dispatch(job(manager, 4, .init(method: "POST", path: base + "/flows/read/run", body: ["with": ["row": photo["id"]!, "photo": photo["photo"]!]], idempotencyKey: "paused-run")))
        XCTAssertEqual(denied.status, 409)
        let wrong = await dispatcher.dispatch(job(manager, 5, .init(method: "POST", path: base + "/pause", body: ["paused": 1], idempotencyKey: "wrong-bool")))
        XCTAssertEqual(wrong.status, 422)
        let removed = await dispatcher.dispatch(job(manager, 6, .init(method: "DELETE", path: base, idempotencyKey: "remove")))
        XCTAssertEqual(removed.status, 200); XCTAssertEqual(try object(removed)["removed"] as? Bool, true)
        do { _ = try await store.package("insurance-inventory"); XCTFail("Package survived removal") } catch {}
        do { _ = try await store.photo(applet: "insurance-inventory", ref: photo["photo"] as! String); XCTFail("Photo survived removal") } catch {}
        let kept = try await store.rows(applet: "other", table: "items"); XCTAssertEqual(kept.first?["label"] as? String, "Keep me")
    }
    func testBatchRechecksCallerRevocationAfterPaidReplyBeforeRecordingOrNextPhoto() async throws {
        let gate = ManagementGate(), (store, engine, authority, dispatcher, manager, _) = try await setup(gate: gate)
        try await engine.install(JSONSerialization.data(withJSONObject: package()))
        for _ in 0..<2 { _ = try await engine.addPhoto(applet: "insurance-inventory", jpeg: Data([1])) }
        let base = "/v1/applets/installed/insurance-inventory"
        let started = await dispatcher.dispatch(job(manager, 1, .init(method: "POST", path: base + "/batches/create/start", idempotencyKey: "batch")))
        XCTAssertEqual(started.status, 202)
        while !(await gate.waiting) { await Task.yield() }
        try await authority.pause(manager.id, paused: true)
        await gate.release(); await engine.waitForBatch(applet: "insurance-inventory")
        let state = await engine.batch(applet: "insurance-inventory"), calls = await gate.calls
        XCTAssertEqual(state.state, "blocked"); XCTAssertEqual(calls, 1)
        let photos = try await store.rows(applet: "insurance-inventory", table: "photos")
        XCTAssertTrue(photos.allSatisfy { $0["status"] as? String == "new" })
    }
    func testDirectCalledWorkflowAndBatchUseCurrentPeerAtEffects() async throws {
        actor FirstAppraisal {
            var calls = 0
            private var released = false
            private var pending: CheckedContinuation<Void, Never>?
            func run() async -> [String: Any] {
                calls += 1
                if calls == 1, !released { await withCheckedContinuation { pending = $0 } }
                return ["items": []]
            }
            func release() {
                released = true
                pending?.resume()
                pending = nil
            }
        }
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory)
        let gate = FirstAppraisal()
        let engine = AppletEngine(store: store) { _, _, _ in await gate.run() }
        try await engine.install(JSONSerialization.data(withJSONObject: package()))
        let applet = "insurance-inventory"
        for _ in 0..<2 { _ = try await engine.addPhoto(applet: applet, jpeg: Data([1])) }
        let authority = HostAuthority(store: store, deviceID: "management-fixture")
        await authority.setState(enabled: false, awake: true, foreground: true)
        await authority.setOwnerDirectEnabled(true)
        let (controller, key) = try await authority.createOwnerDirect(name: "Direct workflow", operations: [.workflowsRun], applets: [applet])
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: engine, profile: { _ in throw CancellationError() })
        let direct = OwnerDirectHostService(authority: authority, dispatcher: dispatcher)
        func call(_ path: String, source: String, action: String, body: [String: Any] = [:]) async throws -> HostResponse {
            await direct.handle(.init(method: "POST", target: path, headers: [
                "authorization": "Bearer " + key, "idempotency-key": action
            ], body: try JSONSerialization.data(withJSONObject: body)), source: source)
        }
        let base = "/v1/applets/installed/" + applet
        let batch = try await call(base + "/batches/create/start", source: "100.80.3.1", action: "direct-batch")
        XCTAssertEqual(batch.status, 202)
        for _ in 0..<100 {
            if await gate.calls > 0 { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        let beforeRevocation = await gate.calls
        XCTAssertEqual(beforeRevocation, 1)
        do { try await authority.setOwnerDirectPeers(controller.id, unlock: true) }
        catch { await gate.release(); throw error }
        await gate.release()
        await engine.waitForBatch(applet: applet)
        let state = await engine.batch(applet: applet)
        let afterRevocation = await gate.calls
        XCTAssertEqual(state.state, "blocked")
        XCTAssertEqual(afterRevocation, 1)
        let untouched = try await store.rows(applet: applet, table: "photos")
        XCTAssertTrue(untouched.allSatisfy { $0["status"] as? String == "new" })

        let photo = try await engine.addPhoto(applet: applet, jpeg: Data([2]))
        let id = try XCTUnwrap(photo["id"] as? String)
        let ref = try XCTUnwrap(photo["photo"] as? String)
        let flow = try await call(base + "/flows/read/run", source: "100.80.3.2", action: "direct-flow",
            body: ["with": ["row": id, "photo": ref]])
        XCTAssertEqual(flow.status, 200)
        let stored = try await store.rows(applet: applet, table: "photos")
        XCTAssertEqual(stored.first(where: { $0["id"] as? String == id })?["status"] as? String, "done")
    }

    func testUncertainRetryRequiresExplicitBooleanAcknowledgmentAndDoesNotStartWork() async throws {
        let (_, engine, _, dispatcher, manager, calls) = try await setup()
        try await engine.install(JSONSerialization.data(withJSONObject: package()))
        let path = "/v1/applets/installed/insurance-inventory/retry"
        for (index, body) in ([[:], ["acknowledge_possible_charge": 1], ["acknowledge_possible_charge": false]] as [[String: Any]]).enumerated() {
            let response = await dispatcher.dispatch(job(manager, Int64(index + 1), .init(method: "POST", path: path, body: body, idempotencyKey: "bad-\(index)")))
            XCTAssertEqual(response.status, 422)
        }
        let response = await dispatcher.dispatch(job(manager, 4, .init(method: "POST", path: path, body: ["acknowledge_possible_charge": true], idempotencyKey: "ack")))
        XCTAssertEqual(response.status, 200); XCTAssertEqual(try object(response)["work_started"] as? Bool, false)
        let count = await calls.calls; XCTAssertEqual(count, 0)
    }
    func testManagementDoesNotExpandNamedWorkflowSpeechOrConfigurationScope() async throws {
        let (_, engine, authority, dispatcher, manager, _) = try await setup()
        var other = try package(); other["id"] = "other"
        try await engine.install(JSONSerialization.data(withJSONObject: other))
        XCTAssertTrue(manager.allowsApplet("other", operation: .appletsRead))
        XCTAssertFalse(manager.allowsApplet("other", operation: .workflowsRun))
        XCTAssertFalse(manager.allowsApplet("other", operation: .speechHear))
        XCTAssertFalse(manager.allowsApplet("other", operation: .configurationWrite))
        let denied = await dispatcher.dispatch(job(manager, 1, .init(method: "POST", path: "/v1/applets/installed/other/batches/create/start", idempotencyKey: "outside-scope")))
        XCTAssertEqual(denied.status, 403)
        do { try await authority.authorizeOngoing(manager, operation: .workflowsRun, applet: "other"); XCTFail("Management widened workflow scope") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.status, 403) }
    }
    func testConcurrentCreatesCannotOverwriteAnExistingDefinition() async throws {
        let (store, _, authority, dispatcher, first, calls) = try await setup()
        let (second, _) = try await authority.create(name: "Second owner control", operations: [.appletsManage], applets: [])
        var a = try package(), b = a; a["version"] = "A"; b["version"] = "B"
        let firstJob = job(first, 1, .init(method: "POST", path: "/v1/applets/install", body: ["package": a], idempotencyKey: "one"))
        let secondJob = job(second, 1, .init(method: "POST", path: "/v1/applets/install", body: ["package": b], idempotencyKey: "two"))
        async let one = dispatcher.dispatch(firstJob)
        async let two = dispatcher.dispatch(secondJob)
        let results = await [one, two]
        XCTAssertEqual(results.map(\.status).sorted(), [201, 409])
        let winner = try object(XCTUnwrap(results.first { $0.status == 201 }))
        let saved = try await store.package("insurance-inventory")
        XCTAssertEqual(saved["version"] as? String, winner["version"] as? String)
        let events = await calls.events; XCTAssertEqual(events, ["install"])
    }

}
