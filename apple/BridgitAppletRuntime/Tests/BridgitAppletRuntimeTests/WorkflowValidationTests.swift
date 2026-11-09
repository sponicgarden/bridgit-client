import Foundation
import SQLite3
import XCTest
@testable import BridgitAppletRuntime

private actor WorkflowSeen {
    var values: [String] = []
    var calls = 0
    var keys: [String] = []
    func append(_ value: String) { values.append(value); calls += 1 }
    func called(_ key: String) -> Int { calls += 1; keys.append(key); return calls }
}
private struct WorkflowPolicyHost: AppletHost {
    enum Failure: Sendable { case afterTimeout, beforeTransient, beforeInvalid, authorization, readTimeout }
    let seen: WorkflowSeen
    let failure: Failure
    let supportedSteps: Set<String> = ["notice", "lookup"]
    func authorize(instance: String) async throws {}
    func perform(type: String, arguments: [String: JSONValue], context: AppletActionContext) async throws -> JSONValue {
        let call = await seen.called(context.idempotencyKey)
        if call == 1 {
            switch failure {
            case .afterTimeout: await seen.append(arguments["text"]?.text ?? "First"); throw URLError(.timedOut)
            case .beforeTransient: throw AppletStepFailure(status: 503, code: "busy", message: "Synthetic pre-effect", effectStarted: false)
            case .beforeInvalid: throw AppletStepFailure(status: 422, code: "invalid_parameter", message: "Synthetic validation", effectStarted: false)
            case .authorization: throw AppletStepFailure(status: 403, code: "agent_not_allowed", message: "Synthetic revocation", effectStarted: false)
            case .readTimeout: throw URLError(.timedOut)
            }
        }
        await seen.append(arguments["text"]?.text ?? "Read")
        return .object(["answer": .string("yes")])
    }
}

final class WorkflowValidationTests: XCTestCase {
    private func root() -> URL {
        var root = URL(fileURLWithPath: #filePath); for _ in 0..<5 { root.deleteLastPathComponent() }; return root
    }
    private func definition(steps: [[String: Any]], named: Bool = false, condition: [String: Any]? = nil) throws -> [String: Any] {
        var value = try JSONSerialization.jsonObject(with: Data(contentsOf: root().appendingPathComponent("docs/applet-authoring-examples/png-icon.json"))) as! [String: Any]
        var flow: [String: Any] = ["name": "Synthetic workflow", "trigger": ["type": "button"], "steps": steps]
        flow["only_if"] = condition
        if named { flow["id"] = "named"; value["processing"] = ["flows": [flow]] }
        else { value["processing"] = ["workflow": flow] }
        value["permissions"] = ["required": ["workflows", "interactions"], "optional": [], "remote": [], "automatic_grants": false]
        return value
    }
    private func release(_ definition: [String: Any], revision: Int = 1) throws -> RegistryPackage {
        let selection = try JSONDecoder().decode(CatalogEntry.self, from: JSONSerialization.data(withJSONObject: ["key": "tpl_workflow", "id": "blue-note", "revision": revision, "name": "Synthetic", "summary": "Validation"] ))
        return try RegistryPackage(response: JSONSerialization.data(withJSONObject: ["key": selection.key, "revision": revision, "state": "published", "definition": definition]), selection: selection)
    }
    private func database() throws -> (URL, LocalAppletLibrary) {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: folder) }
        let url = folder.appendingPathComponent("library.sqlite")
        return (url, try LocalAppletLibrary(database: url, account: String(repeating: "d", count: 64)))
    }
    func testRegistryMainAndNamedFalseFlowConditionsSuppressAllEffects() async throws {
        for named in [false, true] {
            let (_, library) = try database()
            let selected = try release(definition(steps: [["type": "notice", "text": "Should not appear"]], named: named, condition: ["trigger": "missing", "exists": true]))
            try await library.install(selected)
            let loaded = try await library.installed("tpl_workflow"), edition = try XCTUnwrap(loaded)
            let seen = WorkflowSeen()
            let result = try await library.runButton(named ? "named" : "main", in: edition) { notice in await seen.append(notice.text) }
            XCTAssertEqual(result.skipped, [0]); XCTAssertTrue(result.results.isEmpty)
            let values = await seen.values; XCTAssertTrue(values.isEmpty)
        }
    }
    func testMalformedPoliciesAndLateUnsupportedConditionsRejectBeforeReviewOrInstall() async throws {
        for named in [false, true] {
            for policy: Any in [true, false, NSNull(), 3, ["retry": true], ["stop"], "continue"] {
                XCTAssertThrowsError(try release(definition(steps: [["type": "notice", "text": "First"], ["type": "notice", "text": "Late", "on_error": policy]], named: named)))
            }
            for value: Any in [true, "2", NSNull(), 0, 4, 1.5] {
                XCTAssertThrowsError(try release(definition(steps: [["type": "notice", "text": "First"], ["type": "notice", "text": "Late", "on_error": "retry", "retries": value]], named: named)))
            }
            for (field, value): (String, Any) in [("on_error", true), ("retries", 1), ("max_paid_per_day", true), ("max_paid_per_day", "50"), ("max_paid_per_day", 0), ("max_paid_per_day", 501), ("max_paid_per_day", 1.5)] {
                var doc = try definition(steps: [["type": "notice", "text": "First"]], named: named)
                var processing = doc["processing"] as! [String: Any]
                var flow = named ? (processing["flows"] as! [[String: Any]])[0] : processing["workflow"] as! [String: Any]
                flow[field] = value
                if named { processing["flows"] = [flow] } else { processing["workflow"] = flow }
                doc["processing"] = processing
                XCTAssertThrowsError(try release(doc))
            }
            let late = try definition(steps: [["type": "notice", "text": "First"], ["type": "notice", "text": "Late", "only_if": ["time": ["days": ["mon"]]]]], named: named)
            XCTAssertThrowsError(try release(late))
            XCTAssertThrowsError(try release(definition(steps: [["type": "notice", "on_error": "skip", "retries": 1]], named: named)))
        }
    }
    func testEarlierStepConditionsAndGroupsRemainSupportedAndForwardReferencesReject() async throws {
        let seen = WorkflowSeen(), host = WorkflowPolicyHost(seen: seen, failure: .beforeTransient)
        let first = try FlowStep(.object(["type": .string("lookup"), "id": .string("look"), "on_error": .string("retry")]))
        let condition: JSONValue = .object(["all": .array([.object(["step": .string("look.answer"), "is": .string(" YES ")]), .object(["trigger": .string("missing"), "exists": .bool(false)])])])
        let next = try FlowStep(.object(["type": .string("notice"), "text": .string("Matched"), "only_if": condition]))
        _ = try await FlowRuntime(host: host, retrySleep: { _ in }).run(instance: "fixture", steps: [first, next])
        let values = await seen.values; XCTAssertEqual(values, ["Read", "Matched"])
        let future = try FlowStep(.object(["type": .string("notice"), "only_if": .object(["step": .string("future.answer"), "is": .string("yes")])]))
        let before = await seen.calls
        do { _ = try await FlowRuntime(host: host).run(instance: "fixture", steps: [first, future]); XCTFail("Forward source was accepted") } catch {}
        let after = await seen.calls; XCTAssertEqual(after, before)
    }
    func testUnknownPostEffectTimeoutNeverRetriesOrSkipsAndHardAuthorizationNeverSkips() async throws {
        for policy in ["retry", "skip"] {
            for failure: WorkflowPolicyHost.Failure in [.afterTimeout, .authorization] {
                let seen = WorkflowSeen(), host = WorkflowPolicyHost(seen: seen, failure: failure)
                let steps = try [FlowStep(.object(["type": .string("notice"), "text": .string("First"), "on_error": .string(policy)])),
                                 FlowStep(.object(["type": .string("notice"), "text": .string("Second")]))]
                do { _ = try await FlowRuntime(host: host, retrySleep: { _ in }).run(instance: "fixture", steps: steps); XCTFail("Unsafe failure carried on") } catch {}
                let values = await seen.values
                XCTAssertEqual(values, failure == .afterTimeout ? ["First"] : [])
            }
        }
    }
    func testKnownPreEffectTransientAndRepeatableReadRetryButValidationDoesNot() async throws {
        for (type, failure): (String, WorkflowPolicyHost.Failure) in [("notice", .beforeTransient), ("lookup", .readTimeout)] {
            let seen = WorkflowSeen(), host = WorkflowPolicyHost(seen: seen, failure: failure)
            let step = try FlowStep(.object(["type": .string(type), "text": .string("Delivered once"), "on_error": .string("retry"), "retries": .number(3)]))
            _ = try await FlowRuntime(host: host, retrySleep: { _ in }).run(instance: "fixture", steps: [step])
            let values = await seen.values; XCTAssertEqual(values, ["Delivered once"])
            let keys = await seen.keys; XCTAssertEqual(keys.count, 2); XCTAssertEqual(Set(keys).count, 1)
        }
        let seen = WorkflowSeen(), host = WorkflowPolicyHost(seen: seen, failure: .beforeInvalid)
        let step = try FlowStep(.object(["type": .string("notice"), "on_error": .string("retry"), "retries": .number(3)]))
        do { _ = try await FlowRuntime(host: host, retrySleep: { _ in XCTFail("Validation was retried") }).run(instance: "fixture", steps: [step]); XCTFail() } catch {}
        let calls = await seen.calls; XCTAssertEqual(calls, 1)
    }
    func testPreexistingMalformedStoredFlowRefusesBeforeEffects() async throws {
        let (url, library) = try database()
        try await library.install(release(definition(steps: [["type": "notice", "text": "Valid"]])))
        let loaded = try await library.installed("tpl_workflow"), edition = try XCTUnwrap(loaded)
        var db: OpaquePointer?; XCTAssertEqual(sqlite3_open(url.path, &db), SQLITE_OK); defer { sqlite3_close(db) }
        for bad in [try definition(steps: [["type": "notice", "text": "First"], ["type": "notice", "on_error": true]]),
                    try definition(steps: [["type": "notice", "text": "First"], ["type": "notice", "only_if": ["time": ["days": ["mon"]]]]])] {
            let data = try JSONSerialization.data(withJSONObject: bad)
            var statement: OpaquePointer?; XCTAssertEqual(sqlite3_prepare_v2(db, "UPDATE local_applets SET definition=? WHERE key='tpl_workflow'", -1, &statement, nil), SQLITE_OK)
            _ = data.withUnsafeBytes { sqlite3_bind_blob(statement, 1, $0.baseAddress, Int32(data.count), unsafeBitCast(-1, to: sqlite3_destructor_type.self)) }
            XCTAssertEqual(sqlite3_step(statement), SQLITE_DONE); sqlite3_finalize(statement)
            let seen = WorkflowSeen()
            do { _ = try await library.runButton("main", in: edition) { value in await seen.append(value.text) }; XCTFail("Old malformed installation ran") } catch {}
            let values = await seen.values; XCTAssertTrue(values.isEmpty)
        }
    }
    func testConfigurationBoundInvalidConditionBlocksInstallAndUpdateWithoutReplacingEdition() async throws {
        let (_, library) = try database()
        let original = try release(definition(steps: [["type": "notice", "text": "Valid"]]))
        try await library.install(original)
        let loaded = try await library.installed("tpl_workflow"), edition = try XCTUnwrap(loaded)
        var bad = try definition(steps: [["type": "notice", "text": "Never"]], condition: ["trigger": "value", "below": ["$input": "threshold"]])
        bad["config_schema"] = ["type": "object", "properties": ["threshold": ["type": "string"]], "required": [], "additionalProperties": false]
        bad["defaults"] = ["threshold": "not a number"]; bad["version"] = "1.1.0"
        let selected = try release(bad, revision: 2)
        do { _ = try await library.reviewUpdate(selected, replacing: edition); XCTFail("Invalid bound condition reviewed") } catch {}
        let current = try await library.open(edition); XCTAssertEqual(current.data, original.data)
        let (_, empty) = try database()
        do { try await empty.install(selected); XCTFail("Invalid bound condition installed") } catch {}
        let editions = try await empty.editions(); XCTAssertTrue(editions.isEmpty)
    }
    func testSettingsCannotPersistInvalidBoundCondition() async throws {
        let (_, library) = try database()
        var doc = try definition(steps: [["type": "notice", "text": "Conditional"]], condition: ["trigger": "value", "below": ["$input": "threshold"]])
        doc["config_schema"] = ["type": "object", "properties": ["threshold": ["type": ["number", "string"]]], "required": ["threshold"], "additionalProperties": false]
        doc["defaults"] = ["threshold": 10]
        try await library.install(release(doc))
        let loaded = try await library.installed("tpl_workflow"), edition = try XCTUnwrap(loaded)
        let before = try await library.open(edition)
        do {
            try await library.saveSettings(["threshold": .string("invalid comparison")], replacing: before)
            XCTFail("Saved a schema-valid setting that breaks the bound workflow condition")
        } catch is AppletRuntimeError {}
        let unchanged = try await library.open(edition)
        XCTAssertEqual(unchanged.config, before.config)
        try await library.saveSettings(["threshold": .number(20)], replacing: before)
        let changed = try await library.open(edition)
        XCTAssertEqual(changed.config["threshold"], .number(20))
    }

    func testTrackedPortablePublicDefinitionsKeepTheirConditionContracts() throws {
        var checked = 0
        for folder in ["applets", "site-content/public/recipes"] {
            let files = FileManager.default.enumerator(at: root().appendingPathComponent(folder), includingPropertiesForKeys: nil)!
            for case let url as URL in files where url.lastPathComponent == "applet.json" {
                let data = try Data(contentsOf: url)
                guard let value = try JSONSerialization.jsonObject(with: data) as? [String: Any], value["format"] as? String == "bridgit-template/1",
                      let runtime = value["runtime"] as? [String: Any], ["bridgit.package.v1", "android.package.v1"].contains(runtime["adapter"] as? String ?? "") else { continue }
                _ = try AppletPackage(data: data); checked += 1
            }
        }
        XCTAssertGreaterThan(checked, 5)
    }
}


private actor WorkflowHeldCallback {
    var continuation: CheckedContinuation<Void, Never>?
    var holding: Bool { continuation != nil }
    func wait() async { await withCheckedContinuation { continuation = $0 } }
    func release() { let held = continuation; continuation = nil; held?.resume() }
}

extension WorkflowValidationTests {
    @MainActor func testCancellationAfterEffectAndDuringRetryNeverContinues() async throws {
        let (_, library) = try database()
        try await library.install(release(definition(steps: [["type": "notice", "text": "First", "on_error": "skip"], ["type": "notice", "text": "Second"]])))
        let loaded = try await library.installed("tpl_workflow"), edition = try XCTUnwrap(loaded)
        let seen = WorkflowSeen(), held = WorkflowHeldCallback()
        let running = Task {
            try await library.runButton("main", in: edition) { notice in await seen.append(notice.text); await held.wait() }
        }
        for _ in 0..<1000 { if await held.holding { break }; await Task.yield() }
        let holding = await held.holding; XCTAssertTrue(holding)
        running.cancel(); await held.release()
        do { _ = try await running.value; XCTFail("Cancellation was skipped") } catch is CancellationError { }
        let values = await seen.values; XCTAssertEqual(values, ["First"])

        let retrySeen = WorkflowSeen(), retryHeld = WorkflowHeldCallback()
        let host = WorkflowPolicyHost(seen: retrySeen, failure: .beforeTransient)
        let step = try FlowStep(.object(["type": .string("notice"), "on_error": .string("retry")]))
        let retry = Task {
            try await FlowRuntime(host: host, retrySleep: { _ in await retryHeld.wait(); try Task.checkCancellation() }).run(instance: "fixture", steps: [step])
        }
        for _ in 0..<1000 { if await retryHeld.holding { break }; await Task.yield() }
        let waiting = await retryHeld.holding; XCTAssertTrue(waiting)
        retry.cancel(); await retryHeld.release()
        do { _ = try await retry.value; XCTFail("Cancelled retry continued") } catch is CancellationError { }
        let calls = await retrySeen.calls; XCTAssertEqual(calls, 1)
    }
    func testNormalInstalledNoticeTimeoutStopsUnderRetryAndSkip() async throws {
        for policy in ["retry", "skip"] {
            let (_, library) = try database()
            try await library.install(release(definition(steps: [["type": "notice", "text": "First", "on_error": policy], ["type": "notice", "text": "Second"]])))
            let loaded = try await library.installed("tpl_workflow"), edition = try XCTUnwrap(loaded)
            let seen = WorkflowSeen()
            do {
                _ = try await library.runButton("main", in: edition) { notice in await seen.append(notice.text); throw URLError(.timedOut) }
                XCTFail("Unknown installed effect continued")
            } catch let failure as AppletStepFailure { XCTAssertEqual(failure.code, "unknown_outcome") }
            let values = await seen.values; XCTAssertEqual(values, ["First"])
        }
    }
}


private struct AlwaysRetryReadHost: AppletHost {
    let seen: WorkflowSeen
    let supportedSteps: Set<String> = ["lookup"]
    func authorize(instance: String) async throws {}
    func perform(type: String, arguments: [String: JSONValue], context: AppletActionContext) async throws -> JSONValue {
        _ = await seen.called(context.idempotencyKey)
        throw AppletStepFailure(status: 503, code: "busy", message: "Synthetic transient read", effectStarted: false)
    }
}
private actor WorkflowRetryClock {
    var delays: [UInt64] = []
    func sleep(_ value: UInt64) { delays.append(value) }
}
extension WorkflowValidationTests {
    func testRetriesStayBoundedWithOneStableActionIdentity() async throws {
        let seen = WorkflowSeen(), clock = WorkflowRetryClock()
        let host = AlwaysRetryReadHost(seen: seen)
        let step = try FlowStep(.object(["type": .string("lookup"), "on_error": .string("retry"), "retries": .number(3)]))
        do { _ = try await FlowRuntime(host: host, retrySleep: { await clock.sleep($0) }).run(instance: "fixture", steps: [step]); XCTFail("Exhausted retry succeeded") } catch {}
        let calls = await seen.calls, keys = await seen.keys, delays = await clock.delays
        XCTAssertEqual(calls, 4); XCTAssertEqual(Set(keys).count, 1)
        XCTAssertEqual(delays, [2_000_000_000, 5_000_000_000, 10_000_000_000])
    }
}
