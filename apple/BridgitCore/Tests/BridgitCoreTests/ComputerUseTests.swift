#if os(macOS)
import Foundation
import XCTest
@testable import BridgitCore

/// A0112–A0114: Mac computer use, its off-by-default setting and the Superpowers default.
final class ComputerUseTests: XCTestCase {
    private final class FakeBackend: ComputerUseBackend, @unchecked Sendable {
        var granted = ComputerUsePermissions(accessibility: true, screenRecording: true)
        var prompted = 0
        var performed: [ComputerUseAction] = []
        func permissions() async -> ComputerUsePermissions { granted }
        func requestPermissions() async -> ComputerUsePermissions { prompted += 1; return granted }
        func screenshot(maxEdge: Int, format: String, quality: Int) async throws -> [String: Any] {
            ["mime": "image/jpeg", "data_base64": "AA==", "width_px": maxEdge, "height_px": maxEdge / 2, "width_points": 1440.0, "height_points": 900.0]
        }
        func apps() async throws -> [[String: Any]] { [["pid": 42, "bundle_id": "com.apple.TextEdit", "name": "TextEdit", "frontmost": true]] }
        func tree(pid: Int32?, bundleID: String?, maxDepth: Int, maxNodes: Int) async throws -> [String: Any] {
            ["tree": ["id": "0", "role": "AXApplication"], "nodes": 1, "truncated": false]
        }
        func perform(_ action: ComputerUseAction) async throws -> [String: Any] { performed.append(action); return ["done": true] }
    }

    private struct Fixture {
        let store: AppletStore, authority: HostAuthority, dispatcher: HostDispatcher, service: ComputerUseService, backend: FakeBackend
    }
    private var sequence: Int64 = 0

    private func fixture() async throws -> Fixture {
        let path = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: path) }
        let store = try AppletStore(directory: path), authority = HostAuthority(store: store, deviceID: "fixture")
        await authority.setState(enabled: true, awake: true, foreground: true)
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: AppletEngine(store: store) { _, _, _ in [:] }, profile: { _ in throw CancellationError() })
        let backend = FakeBackend()
        let service = ComputerUseService(store: store, backend: backend)
        await dispatcher.attachComputerUse(service)
        return .init(store: store, authority: authority, dispatcher: dispatcher, service: service, backend: backend)
    }
    private func call(_ f: Fixture, _ agent: HostAgent, _ method: String, _ path: String, query: [String: Any] = [:], body: [String: Any] = [:], key: String? = nil) async -> HostResponse {
        sequence += 1
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let request = HostRequest(method: method, path: path, query: query, body: body, idempotencyKey: method == "GET" ? nil : key ?? UUID().uuidString)
        return await f.dispatcher.dispatch(.init(id: UUID().uuidString, sequence: sequence, agentID: agent.id, generation: agent.generation, deviceID: "fixture", deadline: now + 60_000, createdAt: now, request: request))
    }
    private func object(_ response: HostResponse) throws -> [String: Any] { try XCTUnwrap(JSONSerialization.jsonObject(with: response.data) as? [String: Any]) }
    private func code(_ response: HostResponse) -> String? { (try? object(response))?["error"] as? String ?? (try? object(response))?["code"] as? String }

    private let plain: Set<HostOperation> = [.deviceRead, .configurationRead, .configurationWrite, .screenObserve, .screenControl, .log]
    private let settings = "/v1/owner/configuration/computer-use"

    func testResolutionLayers() {
        XCTAssertEqual(ComputerUsePolicy.resolve(.inherit, superpowers: false).label, "off (default)")
        XCTAssertEqual(ComputerUsePolicy.resolve(.inherit, superpowers: true).label, "on (default from superpowers)")
        XCTAssertEqual(ComputerUsePolicy.resolve(.off, superpowers: true).label, "off (specifically disabled)")
        XCTAssertFalse(ComputerUsePolicy.resolve(.off, superpowers: true).enabled)
        XCTAssertTrue(ComputerUsePolicy.resolve(.on, superpowers: false).enabled)
        XCTAssertEqual(ComputerUsePolicy.resolve(.on, superpowers: false).label, "on (specifically enabled)")
    }

    func testFreshDeviceReadsOffAndRefusesActions() async throws {
        let f = try await fixture()
        let (agent, _) = try await f.authority.create(name: "Plain", operations: plain, applets: [])
        let read = await call(f, agent, "GET", settings)
        XCTAssertEqual(read.status, 200)
        let state = try object(read)
        XCTAssertEqual(state["setting"] as? String, "inherit")
        XCTAssertEqual(state["enabled"] as? Bool, false)
        XCTAssertEqual(state["state"] as? String, "off (default)")
        XCTAssertEqual(state["reason"] as? String, "default_off")
        let shot = await call(f, agent, "GET", "/v1/computer-use/screenshot")
        XCTAssertEqual(shot.status, 403); XCTAssertEqual(code(shot), "computer_use_off")
        let click = await call(f, agent, "POST", "/v1/computer-use/actions", body: ["action": "click", "x": 10, "y": 10])
        XCTAssertEqual(click.status, 403); XCTAssertEqual(code(click), "computer_use_off")
        XCTAssertTrue(f.backend.performed.isEmpty)
    }

    func testAgentWithoutSuperpowersCannotTurnItOnAndIsToldWhy() async throws {
        let f = try await fixture()
        let (agent, _) = try await f.authority.create(name: "Plain", operations: plain, applets: [])
        let refused = await call(f, agent, "POST", settings, body: ["setting": "on"])
        XCTAssertEqual(refused.status, 403)
        XCTAssertEqual(code(refused), "superpowers_required")
        XCTAssertTrue(String(decoding: refused.data, as: UTF8.self).contains("agents.admin"))
        let choice = try await f.service.choice()
        XCTAssertEqual(choice.setting, .inherit)
        // Off is always allowed, and inherit can give no one more than Superpowers already has.
        let off = await call(f, agent, "POST", settings, body: ["setting": "off"])
        XCTAssertEqual(off.status, 200)
        XCTAssertEqual(try object(off)["state"] as? String, "off (specifically disabled)")
        let inherit = await call(f, agent, "POST", settings, body: ["setting": "inherit"])
        XCTAssertEqual(inherit.status, 200)
    }

    func testSuperpowersDefaultsOnAndSpecificOffWins() async throws {
        let f = try await fixture()
        let (agent, _) = try await f.authority.create(name: "Super", operations: [.administrator], applets: [])
        // A0114 (2): no enable step needed.
        let status = await call(f, agent, "GET", "/v1/computer-use")
        XCTAssertEqual(try object(status)["state"] as? String, "on (default from superpowers)")
        let shot = await call(f, agent, "GET", "/v1/computer-use/screenshot", query: ["max_edge": "800"])
        XCTAssertEqual(shot.status, 200)
        XCTAssertEqual(try object(shot)["width_px"] as? Int, 800)
        XCTAssertEqual(shot.headers["Cache-Control"], "no-store")
        let typed = await call(f, agent, "POST", "/v1/computer-use/actions", body: ["action": "type", "text": "hello"])
        XCTAssertEqual(typed.status, 200)
        XCTAssertEqual(f.backend.performed.map(\.kind), [.type])
        // A0114 (3): a specific off refuses actions even with Superpowers on.
        let off = await call(f, agent, "POST", settings, body: ["setting": "off"])
        XCTAssertEqual(try object(off)["state"] as? String, "off (specifically disabled)")
        let refused = await call(f, agent, "POST", "/v1/computer-use/actions", body: ["action": "key", "key": "return"])
        XCTAssertEqual(refused.status, 403); XCTAssertEqual(code(refused), "computer_use_off")
        // A0114 (4): clearing returns to the Superpowers default.
        let cleared = await call(f, agent, "POST", settings, body: ["setting": "inherit"])
        XCTAssertEqual(try object(cleared)["state"] as? String, "on (default from superpowers)")
        let again = await call(f, agent, "POST", "/v1/computer-use/actions", body: ["action": "key", "key": "return"])
        XCTAssertEqual(again.status, 200)
    }

    func testSuperpowersCanTurnItOnForOthersAndOwnerOnOutlastsSuperpowers() async throws {
        let f = try await fixture()
        let (admin, _) = try await f.authority.create(name: "Super", operations: [.administrator], applets: [])
        let (plainAgent, _) = try await f.authority.create(name: "Plain", operations: plain, applets: [])
        let on = await call(f, admin, "POST", settings, body: ["setting": "on"])
        XCTAssertEqual(on.status, 200)
        let state = try object(on)
        XCTAssertEqual(state["state"] as? String, "on (specifically enabled)")
        XCTAssertEqual(state["change_source"] as? String, "agent_superpowers")
        XCTAssertEqual(state["changed_by"] as? String, "Super")
        let apps = await call(f, plainAgent, "GET", "/v1/computer-use/apps")
        XCTAssertEqual(apps.status, 200)
        // A specific on stands after the setter loses Superpowers.
        try await f.authority.setAdministrator(admin.id, enabled: false)
        let after = try await f.service.resolution(for: plainAgent)
        XCTAssertTrue(after.enabled)
        // The owner's own change in the app.
        try await f.service.set(.off, by: nil)
        let owner = try await f.service.choice()
        XCTAssertEqual(owner.source, "owner"); XCTAssertEqual(owner.changedBy, "Owner")
    }

    func testEveryChangeIsInActivityWithItsSource() async throws {
        let f = try await fixture()
        let (admin, _) = try await f.authority.create(name: "Super", operations: [.administrator], applets: [])
        _ = await call(f, admin, "POST", settings, body: ["setting": "on"])
        try await f.service.set(.off, by: nil)
        let rows = try await f.store.hostActivity(limit: 50).filter { $0.kind == .setting }.map(\.operation)
        XCTAssertTrue(rows.contains("Super (Superpowers) turned computer use on (was inherit)"), "\(rows)")
        XCTAssertTrue(rows.contains("You turned computer use off (was on)"), "\(rows)")
        // The request itself is recorded under its grant too.
        let requests = try await f.store.hostActivity(limit: 50).filter { $0.kind == .request }.map(\.operation)
        XCTAssertTrue(requests.contains(HostOperation.configurationWrite.rawValue))
    }

    func testGrantsAreEnforcedPerCaller() async throws {
        let f = try await fixture()
        try await f.service.set(.on, by: nil)
        let (reader, _) = try await f.authority.create(name: "Reader", operations: [.deviceRead], applets: [])
        let settingsRead = await call(f, reader, "GET", settings)
        XCTAssertEqual(settingsRead.status, 403)
        let shot = await call(f, reader, "GET", "/v1/computer-use/screenshot")
        XCTAssertEqual(shot.status, 403); XCTAssertEqual(code(shot), "agent_not_allowed")
        let (observer, _) = try await f.authority.create(name: "Observer", operations: [.screenObserve], applets: [])
        let ui = await call(f, observer, "GET", "/v1/computer-use/ui")
        XCTAssertEqual(ui.status, 200)
        let act = await call(f, observer, "POST", "/v1/computer-use/actions", body: ["action": "click", "x": 1, "y": 2])
        XCTAssertEqual(act.status, 403)
        XCTAssertTrue(f.backend.performed.isEmpty)
        let change = await call(f, observer, "POST", settings, body: ["setting": "off"])
        XCTAssertEqual(change.status, 403)
        let kept = try await f.service.choice()
        XCTAssertEqual(kept.setting, .on)
    }

    func testMissingMacPermissionNamesTheOwnerStepAndAgentsCanOnlyAsk() async throws {
        let f = try await fixture()
        f.backend.granted = .init(accessibility: false, screenRecording: false)
        let (admin, _) = try await f.authority.create(name: "Super", operations: [.administrator], applets: [])
        let shot = await call(f, admin, "GET", "/v1/computer-use/screenshot")
        XCTAssertEqual(shot.status, 409); XCTAssertEqual(code(shot), "os_permission_required")
        XCTAssertTrue(String(decoding: shot.data, as: UTF8.self).contains("Privacy & Security"))
        let state = try object(await call(f, admin, "GET", settings))
        XCTAssertEqual(state["ready"] as? Bool, false)
        XCTAssertTrue((state["remaining_human_step"] as? String ?? "").contains("Accessibility"))
        let ask = await call(f, admin, "POST", "/v1/owner/configuration/computer-use/permissions")
        XCTAssertEqual(ask.status, 200)
        XCTAssertEqual(f.backend.prompted, 1)
        XCTAssertEqual((try object(ask)["os_permissions"] as? [String: Bool])?["accessibility"], false)
    }

    func testRetriesReplayAndInvalidValuesAreRefused() async throws {
        let f = try await fixture()
        let (admin, _) = try await f.authority.create(name: "Super", operations: [.administrator], applets: [])
        let first = await call(f, admin, "POST", "/v1/computer-use/actions", body: ["action": "click", "x": 5, "y": 6], key: "click-once")
        let retry = await call(f, admin, "POST", "/v1/computer-use/actions", body: ["action": "click", "x": 5, "y": 6], key: "click-once")
        XCTAssertEqual(first.status, 200); XCTAssertEqual(retry.status, 200)
        XCTAssertEqual(f.backend.performed.count, 1)
        let conflict = await call(f, admin, "POST", "/v1/computer-use/actions", body: ["action": "click", "x": 9, "y": 6], key: "click-once")
        XCTAssertEqual(conflict.status, 409)
        for body: [String: Any] in [["action": "fly"], ["action": "click", "x": 1], ["action": "key", "key": "hyper"],
                                    ["action": "type", "text": ""], ["action": "press", "element": "0.1"], ["action": "click", "x": 1, "y": 1, "extra": true],
                                    ["action": "press", "element": "../1", "pid": 4]] {
            let response = await call(f, admin, "POST", "/v1/computer-use/actions", body: body)
            XCTAssertEqual(response.status, 422, "\(body)")
        }
        let badSetting = await call(f, admin, "POST", settings, body: ["setting": "maybe"])
        let extraField = await call(f, admin, "POST", settings, body: ["setting": "on", "extra": 1])
        let badEdge = await call(f, admin, "GET", "/v1/computer-use/screenshot", query: ["max_edge": "9"])
        XCTAssertEqual([badSetting.status, extraField.status, badEdge.status], [422, 422, 422])
        XCTAssertEqual(f.backend.performed.count, 1)
    }

    func testDiscoveryListsComputerUseOnlyForGrantedCallers() async throws {
        let f = try await fixture()
        let (admin, _) = try await f.authority.create(name: "Super", operations: [.administrator], applets: [])
        let (reader, _) = try await f.authority.create(name: "Reader", operations: [.deviceRead], applets: [])
        let names = Set(HostAPIContract.allowed(admin).map(\.name))
        XCTAssertTrue(names.isSuperset(of: ["configuration_computer_use_read", "configuration_computer_use_set", "configuration_computer_use_permissions",
                                            "computer_use_status", "computer_use_screenshot", "computer_use_apps", "computer_use_ui", "computer_use_action"]))
        XCTAssertFalse(HostAPIContract.allowed(reader).contains { $0.name.contains("computer_use") })
        let paths = try XCTUnwrap(HostAPIContract.openAPI(admin)["paths"] as? [String: Any])
        let action = try XCTUnwrap((paths["/v1/computer-use/actions"] as? [String: Any])?["post"] as? [String: Any])
        XCTAssertEqual((action["requestBody"] as? [String: Any])?["required"] as? Bool, true)
        XCTAssertTrue(OwnerDirectHostService.supportedGrants.isSuperset(of: [.screenObserve, .screenControl]))
        XCTAssertEqual(HostRequest(method: "POST", path: "/v1/computer-use/actions").route?.0, .screenControl)
        XCTAssertEqual(HostRequest(method: "GET", path: settings).route?.0, .configurationRead)
        let mcpTool = HostAPIContract.tools(admin).first { $0["name"] as? String == "computer_use_action" }
        XCTAssertNotNil(mcpTool)
    }
}
#endif
