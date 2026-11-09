import BridgitCore
import XCTest
@testable import Bridgit_macOS

@MainActor private final class MediaPermissionGate {
    var waiting = false
    var count = 0
    private var continuation: CheckedContinuation<Bool, Never>?
    func request() async -> Bool { count += 1; waiting = true; return await withCheckedContinuation { continuation = $0 } }
    func allow() { continuation?.resume(returning: true); continuation = nil }
}
@MainActor private final class OwnerConfigurationWorkspace {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    private var stores: [AppletStore] = []

    func model(hostCameraPermission: (() async -> Bool)? = nil, hostMicrophonePermission: (() async -> Bool)? = nil,
               settings: UserDefaults? = nil) -> LocalAppletModel {
        let local = LocalAppletModel(directory: directory, hostCameraPermission: hostCameraPermission,
                                    hostMicrophonePermission: hostMicrophonePermission, settings: settings)
        if let store = local.store { stores.append(store) }
        return local
    }

    func cleanUp() async throws {
        #if DEBUG
        for store in stores { try await store.closeTemporaryFixture(at: directory) }
        try FileManager.default.removeItem(at: directory)
        #endif
    }
}
private final class ExternalPauseValue: @unchecked Sendable {
    private let lock = NSLock()
    private var value = false
    var paused: Bool { lock.withLock { value } }
    func set(_ value: Bool) { lock.withLock { self.value = value } }
}
private actor NamesCalls {
    private var replies: [(Int, [String: Any])] = []
    private(set) var methods: [String] = []
    var count: Int { methods.count }
    func queue(_ status: Int, _ body: [String: Any]) { replies.append((status, body)) }
    func answer(_ method: String, _ path: String, _ body: [String: Any]?) -> (Int, [String: Any]) {
        methods.append(method + " " + path)
        return replies.isEmpty ? (500, ["error": "unscripted"]) : replies.removeFirst()
    }
}
private final class NamesCache: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Data?
    var data: Data? { get { lock.withLock { value } } set { lock.withLock { value = newValue } } }
}
final class OwnerConfigurationTests: XCTestCase {
    @MainActor private func workspace() -> OwnerConfigurationWorkspace {
        let workspace = OwnerConfigurationWorkspace()
        addTeardownBlock { try await workspace.cleanUp() }
        return workspace
    }

    @MainActor private func local(gate: MediaPermissionGate) async throws -> (LocalAppletModel, HostAgent) {
        let workspace = workspace()
        let local = workspace.model(hostCameraPermission: { await gate.request() })
        await local.host?.prepare(identityOverride: try .ephemeral())
        let authority = try XCTUnwrap(local.host?.authority)
        let (agent, _) = try await authority.create(name: "Fixture", operations: [.configurationRead, .configurationWrite], applets: [])
        await authority.setState(enabled: true, awake: true, foreground: true)
        return (local, agent)
    }
    #if DEBUG
    @MainActor func testLocalDirectKeyRotationShowsFreshSetupWithoutInventingListener() async throws {
        let fixture = workspace()
        let local = fixture.model()
        let host = try XCTUnwrap(local.host)
        await host.prepare(identityOverride: try .ephemeral())
        let authority = try XCTUnwrap(host.authority)
        let (agent, oldKey) = try await authority.createOwnerDirectFixture(
            name: "Rotation fixture", operations: [.deviceRead, .log], applets: ["fixture-applet"])

        let rotated = await host.rotate(agent)
        XCTAssertTrue(rotated)
        let setup = try XCTUnwrap(host.oneTimeSetup)
        let fields = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(setup.utf8)) as? [String: Any])
        let replacement = try XCTUnwrap(fields["token"] as? String)
        XCTAssertTrue(replacement.hasPrefix("brg_"))
        XCTAssertTrue(replacement != oldKey)
        XCTAssertEqual(fields["agent_id"] as? String, agent.id)
        XCTAssertEqual(fields["mode"] as? String, "owner_direct")
        XCTAssertEqual(fields["operations"] as? [String], agent.operations.map(\.rawValue).sorted())
        XCTAssertEqual(fields["applets"] as? [String], ["fixture-applet"])
        XCTAssertNil(fields["base"])
        XCTAssertNil(fields["mcp"])
        let current = try await authority.agent(agent.id)
        let saved = try XCTUnwrap(current)
        XCTAssertEqual(fields["generation"] as? String, saved.generation)
        XCTAssertNotEqual(saved.generation, agent.generation)
        XCTAssertTrue(saved.tokenHash == PairingClaim.tokenHash(replacement))
        // Project the same open setup across a reported bound endpoint and a later stop.
        // The address is a fixture input here; no listener or Tailnet is started.
        let address = try XCTUnwrap(TailnetAddress("100.80.1.4"))
        let bound = try LocalHostModel.presentedDirectControlSetup(setup, address: address, port: 8765)
        let boundFields = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(bound.utf8)) as? [String: Any])
        XCTAssertEqual(boundFields["base"] as? String, "http://100.80.1.4:8765/v1")
        XCTAssertEqual(boundFields["mcp"] as? String, "http://100.80.1.4:8765/v1/mcp")
        XCTAssertTrue(boundFields["token"] as? String == replacement)
        XCTAssertEqual(boundFields["generation"] as? String, saved.generation)
        XCTAssertTrue(host.oneTimeSetup == setup)
        let stopped = try LocalHostModel.presentedDirectControlSetup(bound, address: nil, port: nil)
        XCTAssertTrue(stopped == setup)
        host.oneTimeSetup = nil
        XCTAssertNil(host.oneTimeSetup)
    }

    #endif
    @MainActor func testActivityVisibilityUsesAcknowledgedAuthenticatedConfiguration() async throws {
        let (local, writer) = try await local(gate: MediaPermissionGate())
        let authority = try XCTUnwrap(local.host?.authority)
        let store = try XCTUnwrap(local.store)
        let (reader, _) = try await authority.create(name: "Settings reader", operations: [.configurationRead], applets: [])
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: try XCTUnwrap(local.engine),
            profile: { _ in throw CancellationError() }, configuration: local.configuration)
        func dispatch(_ sequence: Int64, _ caller: HostAgent, _ request: HostRequest) async -> HostResponse {
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            return await dispatcher.dispatch(.init(id: UUID().uuidString, sequence: sequence, agentID: caller.id,
                generation: caller.generation, deviceID: authority.deviceID, deadline: now + 30_000, createdAt: now, request: request))
        }
        func visibleSetting(_ sequence: Int64) async throws -> String? {
            let response = await dispatch(sequence, reader, .init(method: "GET", path: "/v1/settings"))
            XCTAssertEqual(response.status, 200)
            let object = try XCTUnwrap(JSONSerialization.jsonObject(with: response.data) as? [String: Any])
            XCTAssertTrue((object["supported_fields"] as? [String])?.contains("log_visibility") == true)
            return (object["values"] as? [String: Any])?["log_visibility"] as? String
        }
        let own = HostRequest(method: "POST", path: "/v1/settings",
            body: ["field": "log_visibility", "value": "own"], idempotencyKey: "own-activity")
        let denied = await dispatch(1, reader, own)
        let queued = await dispatch(1, writer, own)
        XCTAssertEqual(denied.status, 403)
        XCTAssertEqual(queued.status, 202)
        let before = try await visibleSetting(2)
        XCTAssertEqual(before, "all")
        await queued.afterDelivery?()
        let after = try await visibleSetting(3)
        XCTAssertEqual(after, "own")
        let reset = await dispatch(2, writer, .init(method: "DELETE", path: "/v1/settings/log_visibility", idempotencyKey: "reset-activity"))
        XCTAssertEqual(reset.status, 202)
        try await authority.pause(writer.id, paused: true)
        await reset.afterDelivery?()
        let afterPause = try await visibleSetting(4)
        XCTAssertEqual(afterPause, "own")
        try await authority.pause(writer.id, paused: false)
        let allowedReset = await dispatch(3, writer, .init(method: "DELETE", path: "/v1/settings/log_visibility", idempotencyKey: "retry-activity"))
        XCTAssertEqual(allowedReset.status, 202)
        await allowedReset.afterDelivery?()
        let afterReset = try await visibleSetting(5)
        XCTAssertEqual(afterReset, "all")
    }

    @MainActor func testDeviceCapabilityReductionRequiresDeliveryAndPreservesIndependentSettings() async throws {
        let suite = "BridgitDevicePower-" + UUID().uuidString
        let settings = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { settings.removePersistentDomain(forName: suite) }
        settings.set(true, forKey: "host.camera")
        settings.set(true, forKey: "host.microphone")
        settings.set(true, forKey: "cloud_appraisals.enabled")
        settings.set(true, forKey: "lan.discovery.enabled")

        let local = workspace().model(settings: settings)
        let host = try XCTUnwrap(local.host)
        let store = try XCTUnwrap(local.store)
        await host.prepare(identityOverride: try .ephemeral())
        let authority = try XCTUnwrap(host.authority)
        let (writer, _) = try await authority.create(name: "Settings writer", operations: [.configurationWrite], applets: [])
        let (reader, _) = try await authority.create(name: "Settings reader", operations: [.configurationRead], applets: [])
        await authority.setState(enabled: true, awake: true, foreground: true)
        try await store.setHostLogVisibility(.own)
        try await host.setCallbackInternetAllowed(true)
        for kind in TransferKind.allCases { try await store.setTransferEnabled(kind, enabled: true) }
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: try XCTUnwrap(local.engine),
            profile: { _ in throw CancellationError() }, configuration: local.configuration)
        func dispatch(_ sequence: Int64, _ caller: HostAgent, _ request: HostRequest) async -> HostResponse {
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            return await dispatcher.dispatch(.init(id: UUID().uuidString, sequence: sequence, agentID: caller.id,
                generation: caller.generation, deviceID: authority.deviceID, deadline: now + 30_000, createdAt: now, request: request))
        }
        func read(_ sequence: Int64, expecting enabled: Bool) async throws {
            let result = await dispatch(sequence, reader, .init(method: "GET", path: "/v1/settings"))
            XCTAssertEqual(result.status, 200)
            let body = try XCTUnwrap(JSONSerialization.jsonObject(with: result.data) as? [String: Any])
            let fields = try XCTUnwrap(body["device_capabilities_off_fields"] as? [String])
            XCTAssertEqual(Set(fields), ["host_camera", "host_microphone", "cloud_appraisals", "lan_discovery_enabled",
                "files_enabled", "shares_enabled", "downloads_enabled", "callback_allow_internet"])
            let values = try XCTUnwrap(body["values"] as? [String: Any])
            for field in fields { XCTAssertEqual(values[field] as? Bool, enabled, field) }
            XCTAssertEqual(values["log_visibility"] as? String, "own")
        }
        let request = HostRequest(method: "POST", path: "/v1/settings",
            body: ["field": HostSettingChange.disableDeviceCapabilities, "value": true], idempotencyKey: "device-capabilities-off")
        let denied = await dispatch(1, reader, request)
        XCTAssertEqual(denied.status, 403)
        let accepted = await dispatch(1, writer, request)
        XCTAssertEqual(accepted.status, 202)
        try await read(2, expecting: true)
        XCTAssertTrue(host.callbackInternetAllowed)
        await accepted.afterDelivery?()
        try await read(3, expecting: false)
        XCTAssertFalse(host.callbackInternetAllowed)
        let saved = try await authority.agent(writer.id)
        XCTAssertEqual(saved?.operations, writer.operations)
        XCTAssertEqual(saved?.generation, writer.generation)
        XCTAssertFalse(host.enabledIntent)
        XCTAssertFalse(host.ownerDirectIntent)
        for key in ["host.camera", "host.microphone", "cloud_appraisals.enabled", "lan.discovery.enabled"] {
            XCTAssertFalse(settings.bool(forKey: key), key)
        }
    }

    @MainActor func testCrashReportingGrantDeliveryResetAndAggregate() async throws {
        let (local, writer) = try await local(gate: MediaPermissionGate())
        let authority = try XCTUnwrap(local.host?.authority)
        let store = try XCTUnwrap(local.store)
        let (reader, _) = try await authority.create(name: "Crash settings reader", operations: [.configurationRead], applets: [])
        let state = ExternalPauseValue()
        let configured = CrashReportingSettings(read: { !state.paused }, write: { state.set(!$0) }, reconcile: {})
        let fixture = OwnerConfigurationModel(local: local, crashReporting: configured)
        let realPlatform = OwnerConfigurationModel(local: local)
        let engine = try XCTUnwrap(local.engine)
        func dispatcher(_ model: OwnerConfigurationModel) -> HostDispatcher {
            HostDispatcher(authority: authority, store: store, engine: engine,
                profile: { _ in throw CancellationError() }, configuration: model)
        }
        let supported = dispatcher(fixture)
        let unavailable = dispatcher(realPlatform)
        func dispatch(_ sequence: Int64, _ caller: HostAgent, _ request: HostRequest, through host: HostDispatcher) async -> HostResponse {
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            return await host.dispatch(.init(id: UUID().uuidString, sequence: sequence, agentID: caller.id,
                generation: caller.generation, deviceID: authority.deviceID, deadline: now + 30_000, createdAt: now, request: request))
        }
        let field = HostSettingChange.crashReportsField
        let write = HostRequest(method: "POST", path: "/v1/settings",
            body: ["field": field, "value": false], idempotencyKey: "crashes-off")
        func read(_ sequence: Int64, through host: HostDispatcher, expected: Bool?) async throws {
            let response = await dispatch(sequence, reader, .init(method: "GET", path: "/v1/settings"), through: host)
            XCTAssertEqual(response.status, 200)
            let value = try XCTUnwrap(JSONSerialization.jsonObject(with: response.data) as? [String: Any])
            let settings = try XCTUnwrap(value["values"] as? [String: Any])
            XCTAssertEqual(settings[field] as? Bool, expected)
            XCTAssertEqual((value["supported_fields"] as? [String])?.contains(field), expected != nil)
            XCTAssertEqual((value["device_capabilities_off_fields"] as? [String])?.contains(field), expected != nil)
        }

        try await read(1, through: unavailable, expected: nil)
        let unsupported = await dispatch(1, writer, .init(method: "POST", path: "/v1/settings",
            body: ["field": field, "value": false], idempotencyKey: "unavailable"), through: unavailable)
        XCTAssertEqual(unsupported.status, 422)
        let denied = await dispatch(2, reader, write, through: supported)
        XCTAssertEqual(denied.status, 403)

        let off = await dispatch(2, writer, write, through: supported)
        XCTAssertEqual(off.status, 202)
        try await read(3, through: supported, expected: true)
        await off.afterDelivery?()
        try await read(4, through: supported, expected: false)

        let stale = await dispatch(3, writer, .init(method: "DELETE", path: "/v1/settings/" + field,
            idempotencyKey: "crashes-stale-reset"), through: supported)
        XCTAssertEqual(stale.status, 202)
        try await authority.pause(writer.id, paused: true)
        await stale.afterDelivery?()
        try await read(5, through: supported, expected: false)
        try await authority.pause(writer.id, paused: false)
        let reset = await dispatch(4, writer, .init(method: "DELETE", path: "/v1/settings/" + field,
            idempotencyKey: "crashes-reset"), through: supported)
        XCTAssertEqual(reset.status, 202)
        await reset.afterDelivery?()
        try await read(6, through: supported, expected: true)

        let aggregate = await dispatch(5, writer, .init(method: "POST", path: "/v1/settings",
            body: ["field": HostSettingChange.disableDeviceCapabilities, "value": true], idempotencyKey: "all-off"), through: supported)
        XCTAssertEqual(aggregate.status, 202)
        await aggregate.afterDelivery?()
        try await read(7, through: supported, expected: false)
    }

    @MainActor func testDeviceNameSettingsApplyAfterDeliveryAndRejectPausedWriter() async throws {
        let (local, writer) = try await local(gate: MediaPermissionGate())
        let authority = try XCTUnwrap(local.host?.authority)
        let store = try XCTUnwrap(local.store)
        let (reader, _) = try await authority.create(name: "Device name reader", operations: [.configurationRead], applets: [])
        let suite = "cc.bridgit.fixture." + UUID().uuidString
        defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
        let preference = DeviceNamePreference(
            read: { UserDefaults(suiteName: suite)?.string(forKey: DeviceNamePreference.key) ?? "" },
            write: { UserDefaults(suiteName: suite)?.set($0, forKey: DeviceNamePreference.key) })
        let model = OwnerConfigurationModel(local: local, deviceNamePreference: preference)
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: try XCTUnwrap(local.engine),
            profile: { _ in throw CancellationError() }, configuration: model)
        func dispatch(_ sequence: Int64, _ caller: HostAgent, _ request: HostRequest) async -> HostResponse {
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            return await dispatcher.dispatch(.init(id: UUID().uuidString, sequence: sequence, agentID: caller.id,
                generation: caller.generation, deviceID: authority.deviceID, deadline: now + 30_000, createdAt: now, request: request))
        }
        let field = HostSettingChange.deviceNameField
        func read(_ sequence: Int64, override: String, effective: String) async throws {
            let response = await dispatch(sequence, reader, .init(method: "GET", path: "/v1/settings"))
            XCTAssertEqual(response.status, 200)
            let body = try XCTUnwrap(JSONSerialization.jsonObject(with: response.data) as? [String: Any])
            let values = try XCTUnwrap(body["values"] as? [String: Any])
            XCTAssertEqual(values[field] as? String, override)
            XCTAssertEqual(values["device_name_effective"] as? String, effective)
        }
        let initial = LocalHostModel.effectiveDeviceName(override: "")
        let change = HostRequest(method: "POST", path: "/v1/settings", body: ["field": field, "value": "  Lab Łódź  "],
                                 idempotencyKey: "name-change")
        let denied = await dispatch(1, reader, change)
        XCTAssertEqual(denied.status, 403)
        let accepted = await dispatch(1, writer, change)
        XCTAssertEqual(accepted.status, 202)
        try await read(2, override: "", effective: initial)
        await accepted.afterDelivery?()
        try await read(3, override: "Lab Łódź", effective: "Lab Łódź")
        XCTAssertEqual(OwnerConfigurationModel(local: local, deviceNamePreference: preference).deviceNameOverride, "Lab Łódź")
        let stale = await dispatch(2, writer, .init(method: "DELETE", path: "/v1/settings/" + field, idempotencyKey: "stale-reset"))
        XCTAssertEqual(stale.status, 202)
        try await authority.pause(writer.id, paused: true)
        await stale.afterDelivery?()
        try await read(4, override: "Lab Łódź", effective: "Lab Łódź")
        try await authority.pause(writer.id, paused: false)
        let reset = await dispatch(3, writer, .init(method: "DELETE", path: "/v1/settings/" + field, idempotencyKey: "reset"))
        XCTAssertEqual(reset.status, 202)
        await reset.afterDelivery?()
        try await read(5, override: "", effective: initial)
    }

    @MainActor func testExternalCallsSettingAppliesAfterDeliveryAndRejectsPausedWriter() async throws {
        let (local, writer) = try await local(gate: MediaPermissionGate())
        let authority = try XCTUnwrap(local.host?.authority)
        let store = try XCTUnwrap(local.store)
        let (reader, _) = try await authority.create(name: "External calls reader", operations: [.configurationRead], applets: [])
        let state = ExternalPauseValue()
        let policy = DeviceOutboundPolicy(access: .init(paused: { state.paused }), set: { state.set($0) })
        let model = OwnerConfigurationModel(local: local, outboundPolicy: policy)
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: try XCTUnwrap(local.engine),
            profile: { _ in throw CancellationError() }, configuration: model)
        func dispatch(_ sequence: Int64, _ caller: HostAgent, _ request: HostRequest) async -> HostResponse {
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            return await dispatcher.dispatch(.init(id: UUID().uuidString, sequence: sequence, agentID: caller.id,
                generation: caller.generation, deviceID: authority.deviceID, deadline: now + 30_000, createdAt: now, request: request))
        }
        let field = HostSettingChange.externalCallsPausedField
        let request = HostRequest(method: "POST", path: "/v1/settings", body: ["field": field, "value": true],
                                  idempotencyKey: "external-pause")
        let denied = await dispatch(1, reader, request)
        XCTAssertEqual(denied.status, 403)
        let queued = await dispatch(1, writer, request)
        XCTAssertEqual(queued.status, 202)
        XCTAssertNoThrow(try policy.access.require())
        await queued.afterDelivery?()
        XCTAssertTrue(model.externalCallsPaused)
        XCTAssertThrowsError(try policy.access.require())
        let read = await dispatch(2, reader, .init(method: "GET", path: "/v1/settings"))
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: read.data) as? [String: Any])
        let values = try XCTUnwrap(object["values"] as? [String: Any])
        XCTAssertEqual(values[field] as? Bool, true)

        let pending = await dispatch(2, writer, .init(method: "DELETE", path: "/v1/settings/" + field, idempotencyKey: "stale-resume"))
        XCTAssertEqual(pending.status, 202)
        try await authority.pause(writer.id, paused: true)
        await pending.afterDelivery?()
        XCTAssertTrue(state.paused)
        try await authority.pause(writer.id, paused: false)
        let resume = await dispatch(3, writer, .init(method: "DELETE", path: "/v1/settings/" + field, idempotencyKey: "current-resume"))
        XCTAssertEqual(resume.status, 202)
        await resume.afterDelivery?()
        XCTAssertFalse(model.externalCallsPaused)
        XCTAssertNoThrow(try policy.access.require())
    }

    @MainActor func testExternalPauseStopsRelayWithoutClearingOwnerIntentAndResumes() async throws {
        let local = workspace().model()
        let state = ExternalPauseValue()
        var starts = 0, stops = 0
        let host = LocalHostModel(local: local, startConnection: { starts += 1 }, stopConnection: { stops += 1 },
                                  outboundAccess: .init(paused: { state.paused }))
        await host.start()
        XCTAssertEqual(starts, 1)
        XCTAssertTrue(host.enabledIntent)
        state.set(true)
        await host.reconcileExternalCalls()
        XCTAssertEqual(stops, 1)
        XCTAssertTrue(host.enabledIntent)
        await host.start()
        XCTAssertEqual(starts, 1)
        state.set(false)
        await host.reconcileExternalCalls()
        XCTAssertEqual(starts, 2)
        XCTAssertTrue(host.enabledIntent)
    }

    @MainActor func testExternalPauseSupersedesSuspendedRelayStartBeforeResume() async throws {
        let local = workspace().model()
        let state = ExternalPauseValue()
        let (gate, release) = AsyncStream<Void>.makeStream()
        defer { release.finish() }
        var starts = 0, stops = 0
        let host = LocalHostModel(local: local, startConnection: {
            starts += 1
            if starts == 1 { for await _ in gate { break } }
        }, stopConnection: { stops += 1 }, outboundAccess: .init(paused: { state.paused }))
        let first = Task { await host.start() }
        for _ in 0..<500 where starts == 0 { try await Task.sleep(for: .milliseconds(2)) }
        guard starts == 1 else { XCTFail("Relay start did not enter the fixture gate"); first.cancel(); return }
        state.set(true)
        await host.reconcileExternalCalls()
        XCTAssertEqual(stops, 1)
        XCTAssertEqual(starts, 1)
        XCTAssertTrue(host.enabledIntent)
        state.set(false)
        await host.reconcileExternalCalls()
        XCTAssertEqual(starts, 1, "Resume waits for the stale start to exit.")
        release.finish()
        await first.value
        XCTAssertEqual(starts, 2)
        XCTAssertTrue(host.enabledIntent)
    }

    @MainActor func testActivityRetentionUsesAcknowledgedIndependentSettings() async throws {
        let (local, writer) = try await local(gate: MediaPermissionGate())
        let authority = try XCTUnwrap(local.host?.authority)
        let store = try XCTUnwrap(local.store)
        let (reader, _) = try await authority.create(name: "Retention reader", operations: [.configurationRead], applets: [])
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: try XCTUnwrap(local.engine),
            profile: { _ in throw CancellationError() }, configuration: local.configuration)
        func dispatch(_ sequence: Int64, _ caller: HostAgent, _ request: HostRequest) async -> HostResponse {
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            return await dispatcher.dispatch(.init(id: UUID().uuidString, sequence: sequence, agentID: caller.id,
                generation: caller.generation, deviceID: authority.deviceID, deadline: now + 30_000, createdAt: now, request: request))
        }
        let entries = HostActivityRetentionField.entries.rawValue
        let days = HostActivityRetentionField.days.rawValue
        try await local.configuration.setActivityRetention([.days: 30])
        func assertLimits(_ expectedEntries: Int, _ expectedDays: Int, sequence: Int64) async throws {
            let read = await dispatch(sequence, reader, .init(method: "GET", path: "/v1/settings"))
            XCTAssertEqual(read.status, 200)
            let body = try XCTUnwrap(JSONSerialization.jsonObject(with: read.data) as? [String: Any])
            let values = try XCTUnwrap(body["values"] as? [String: Any])
            XCTAssertEqual(values[entries] as? Int, expectedEntries)
            XCTAssertEqual(values[days] as? Int, expectedDays)
        }
        let change = HostRequest(method: "POST", path: "/v1/settings",
            body: ["field": entries, "value": 1000], idempotencyKey: "retention-entries")
        let denied = await dispatch(1, reader, change)
        XCTAssertEqual(denied.status, 403)
        let queued = await dispatch(1, writer, change)
        XCTAssertEqual(queued.status, 202)
        try await assertLimits(0, 30, sequence: 2)
        await queued.afterDelivery?()
        try await assertLimits(1000, 30, sequence: 3)
        let reset = await dispatch(2, writer, .init(method: "DELETE", path: "/v1/settings/" + entries, idempotencyKey: "retention-reset"))
        XCTAssertEqual(reset.status, 202)
        await reset.afterDelivery?()
        try await assertLimits(0, 30, sequence: 4)
    }

    @MainActor func testSpeakerCeilingUsesAcknowledgedSettingsAndPersists() async throws {
        let suite = "BridgitSpeaker-" + UUID().uuidString
        let settings = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { settings.removePersistentDomain(forName: suite) }
        let local = workspace().model(settings: settings)
        let store = try XCTUnwrap(local.store)
        await local.host?.prepare(identityOverride: try .ephemeral())
        let authority = try XCTUnwrap(local.host?.authority)
        let (writer, _) = try await authority.create(name: "Speaker writer", operations: [.configurationWrite], applets: [])
        let (reader, _) = try await authority.create(name: "Speaker reader", operations: [.configurationRead], applets: [])
        await authority.setState(enabled: true, awake: true, foreground: true)
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: try XCTUnwrap(local.engine),
            profile: { _ in throw CancellationError() }, configuration: local.configuration)
        func dispatch(_ sequence: Int64, _ caller: HostAgent, _ request: HostRequest) async -> HostResponse {
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            return await dispatcher.dispatch(.init(id: UUID().uuidString, sequence: sequence, agentID: caller.id,
                generation: caller.generation, deviceID: authority.deviceID, deadline: now + 30_000, createdAt: now, request: request))
        }
        func assertCeiling(_ expected: Int, sequence: Int64) async throws {
            let response = await dispatch(sequence, reader, .init(method: "GET", path: "/v1/settings"))
            XCTAssertEqual(response.status, 200)
            let body = try XCTUnwrap(JSONSerialization.jsonObject(with: response.data) as? [String: Any])
            let values = try XCTUnwrap(body["values"] as? [String: Any])
            XCTAssertEqual(values[HostSettingChange.speakerVolumeField] as? Int, expected)
            XCTAssertEqual(local.speech.speakerVolumeCeiling, expected)
        }
        let field = HostSettingChange.speakerVolumeField
        let change = HostRequest(method: "POST", path: "/v1/settings",
            body: ["field": field, "value": 100], idempotencyKey: "speaker-maximum")
        let denied = await dispatch(1, reader, change)
        XCTAssertEqual(denied.status, 403)
        let allowed = await dispatch(1, writer, change)
        XCTAssertEqual(allowed.status, 202)
        try await assertCeiling(70, sequence: 2)
        await allowed.afterDelivery?()
        try await assertCeiling(100, sequence: 3)
        XCTAssertEqual(LocalSpeechModel(settings: settings).speakerVolumeCeiling, 100)

        let reset = await dispatch(2, writer, .init(method: "DELETE", path: "/v1/settings/" + field, idempotencyKey: "speaker-reset"))
        XCTAssertEqual(reset.status, 202)
        try await authority.pause(writer.id, paused: true)
        await reset.afterDelivery?()
        try await assertCeiling(100, sequence: 4)
        try await authority.pause(writer.id, paused: false)
        let resetAgain = await dispatch(3, writer, .init(method: "DELETE", path: "/v1/settings/" + field, idempotencyKey: "speaker-reset-again"))
        await resetAgain.afterDelivery?()
        try await assertCeiling(70, sequence: 5)
    }

    @MainActor func testMicrophoneSettingUsesAuthenticatedWriteReadResetAndOSGate() async throws {
        let workspace = workspace()
        let gate = MediaPermissionGate()
        let local = workspace.model(hostMicrophonePermission: { await gate.request() })
        let host = try XCTUnwrap(local.host)
        await host.prepare(identityOverride: try .ephemeral())
        let authority = try XCTUnwrap(host.authority)
        let (writer, _) = try await authority.create(name: "Settings Writer", operations: [.configurationRead, .configurationWrite], applets: [])
        let (reader, _) = try await authority.create(name: "Settings Reader", operations: [.configurationRead], applets: [])
        await authority.setState(enabled: true, awake: true, foreground: true)
        let dispatcher = HostDispatcher(authority: authority, store: try XCTUnwrap(local.store), engine: try XCTUnwrap(local.engine),
                                        profile: { _ in throw CancellationError() }, configuration: local.configuration)
        func dispatch(_ sequence: Int64, _ caller: HostAgent, _ request: HostRequest) async -> HostResponse {
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            return await dispatcher.dispatch(.init(id: UUID().uuidString, sequence: sequence, agentID: caller.id,
                generation: caller.generation, deviceID: authority.deviceID, deadline: now + 30_000, createdAt: now, request: request))
        }
        let on = HostRequest(method: "POST", path: "/v1/settings", body: ["field": "host_microphone", "value": true], idempotencyKey: "mic-on")
        let denied = await dispatch(1, reader, on)
        XCTAssertEqual(denied.status, 403)
        let queued = await dispatch(1, writer, on)
        XCTAssertEqual(queued.status, 202)
        XCTAssertFalse(host.microphoneEnabled)
        XCTAssertFalse(gate.waiting)
        let apply = Task { await queued.afterDelivery?() }
        for _ in 0..<200 {
            if gate.waiting { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        guard gate.waiting else { apply.cancel(); return XCTFail("The acknowledged write did not reach the simulated OS permission gate.") }
        XCTAssertFalse(host.microphoneEnabled)
        gate.allow()
        await apply.value
        let read = await dispatch(2, writer, .init(method: "GET", path: "/v1/settings"))
        let values = try XCTUnwrap(JSONSerialization.jsonObject(with: read.data) as? [String: Any])
        XCTAssertTrue((values["supported_fields"] as? [String])?.contains("host_microphone") == true)
        let settings = try XCTUnwrap(values["values"] as? [String: Any])
        XCTAssertEqual(settings["host_microphone"] as? Bool, true)
        let statuses = try XCTUnwrap(settings["os_authorization"] as? [String: String])
        let known = ["not_determined", "restricted", "denied", "authorized", "unknown"]
        XCTAssertTrue(known.contains(try XCTUnwrap(statuses["microphone"])))
        XCTAssertTrue(known.contains(try XCTUnwrap(statuses["speech_recognition"])))
        let reset = await dispatch(3, writer, .init(method: "DELETE", path: "/v1/settings/host_microphone", idempotencyKey: "mic-reset"))
        XCTAssertEqual(reset.status, 202)
        await reset.afterDelivery?()
        XCTAssertFalse(host.microphoneEnabled)
        XCTAssertEqual(gate.count, 1)
    }

    @MainActor func testWiFiPermissionInitiationIsScopedDeferredAndReportsBlockedStates() async throws {
        let workspace = workspace(), local = workspace.model()
        let host = try XCTUnwrap(local.host)
        await host.prepare(identityOverride: try .ephemeral())
        let authority = try XCTUnwrap(host.authority)
        let (writer, _) = try await authority.create(name: "Settings Writer", operations: [.configurationRead, .configurationWrite], applets: [])
        let (reader, _) = try await authority.create(name: "Settings Reader", operations: [.configurationRead], applets: [])
        let (revoked, _) = try await authority.create(name: "Revoked Writer", operations: [.configurationWrite], applets: [])
        await authority.setState(enabled: true, awake: true, foreground: true)
        var state = "not_determined", prompts = 0
        local.configuration.wifiHistoryAccess = WiFiHistoryAccess(status: { state }, active: { true }, prompt: { prompts += 1 })
        let dispatcher = HostDispatcher(authority: authority, store: try XCTUnwrap(local.store), engine: try XCTUnwrap(local.engine),
                                        profile: { _ in throw CancellationError() }, configuration: local.configuration)
        func dispatch(_ sequence: Int64, _ caller: HostAgent, _ request: HostRequest) async -> HostResponse {
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            return await dispatcher.dispatch(.init(id: UUID().uuidString, sequence: sequence, agentID: caller.id,
                generation: caller.generation, deviceID: authority.deviceID, deadline: now + 30_000, createdAt: now, request: request))
        }
        func request(_ key: String) -> HostRequest { .init(method: "POST", path: "/v1/settings", body: ["field": HostSettingChange.wifiHistoryPermissionRequest, "value": true], idempotencyKey: key) }
        let deniedReader = await dispatch(1, reader, request("denied-reader"))
        XCTAssertEqual(deniedReader.status, 403)
        let queued = await dispatch(1, writer, request("initiated"))
        XCTAssertEqual(queued.status, 202); XCTAssertEqual(prompts, 0)
        await queued.afterDelivery?(); XCTAssertEqual(prompts, 1)
        let stale = await dispatch(1, revoked, request("revoked-before-ack"))
        XCTAssertEqual(stale.status, 202)
        try await authority.pause(revoked.id, paused: true)
        await stale.afterDelivery?(); XCTAssertEqual(prompts, 1)
        for (index, blocked) in ["denied", "reduced_accuracy"].enumerated() {
            state = blocked
            let response = await dispatch(Int64(index + 2), writer, request("blocked-" + blocked))
            XCTAssertEqual(response.status, 202)
            let body = try XCTUnwrap(JSONSerialization.jsonObject(with: response.data) as? [String: Any])
            let id = try XCTUnwrap(body["request_id"] as? String)
            await response.afterDelivery?()
            let outcome = try await local.store!.ownerRequest(owner: writer.eventOwner, id: id)
            XCTAssertEqual(outcome["status"] as? String, "needs_os_permission")
            XCTAssertEqual(prompts, 1)
        }
    }

    @MainActor func testNotificationPermissionRequestIsScopedDeferredAndReportsHumanAction() async throws {
        let workspace = workspace()
        let local = workspace.model()
        let host = try XCTUnwrap(local.host)
        await host.prepare(identityOverride: try .ephemeral())
        let authority = try XCTUnwrap(host.authority)
        let (writer, _) = try await authority.create(name: "Settings Writer", operations: [.configurationRead, .configurationWrite], applets: [])
        let (reader, _) = try await authority.create(name: "Settings Reader", operations: [.configurationRead], applets: [])
        await authority.setState(enabled: true, awake: true, foreground: true)
        var allowed = false, prompts = 0, active = true
        local.configuration.captureNotices = CaptureNotices(read: { allowed ? .allowed : .unavailable }, send: { _, _ in },
            prompt: { prompts += 1 }, isActive: { active })
        let dispatcher = HostDispatcher(authority: authority, store: try XCTUnwrap(local.store), engine: try XCTUnwrap(local.engine),
                                        profile: { _ in throw CancellationError() }, configuration: local.configuration)
        func dispatch(_ sequence: Int64, _ caller: HostAgent, _ request: HostRequest) async -> HostResponse {
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            return await dispatcher.dispatch(.init(id: UUID().uuidString, sequence: sequence, agentID: caller.id,
                generation: caller.generation, deviceID: authority.deviceID, deadline: now + 30_000, createdAt: now, request: request))
        }
        func request(_ key: String) -> HostRequest { .init(method: "POST", path: "/v1/settings", body: ["field": HostSettingChange.captureNotificationsRequest, "value": true], idempotencyKey: key) }
        let deniedReader = await dispatch(1, reader, request("denied"))
        XCTAssertEqual(deniedReader.status, 403)
        let denied = await dispatch(1, writer, request("prompt-denied"))
        XCTAssertEqual(denied.status, 202); XCTAssertEqual(prompts, 0)
        let deniedBody = try XCTUnwrap(JSONSerialization.jsonObject(with: denied.data) as? [String: Any])
        let deniedID = try XCTUnwrap(deniedBody["request_id"] as? String)
        await denied.afterDelivery?()
        XCTAssertEqual(prompts, 1)
        let deniedStatus = try await local.store!.ownerRequest(owner: writer.eventOwner, id: deniedID)
        XCTAssertEqual(deniedStatus["status"] as? String, "needs_os_permission")
        let settings = await dispatch(2, writer, .init(method: "GET", path: "/v1/settings"))
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: settings.data) as? [String: Any])
        let values = try XCTUnwrap(body["values"] as? [String: Any])
        XCTAssertEqual(values["capture_notifications_remaining_action"] as? String, "enable_alerts_in_system_settings")
        active = false; allowed = true
        let inactive = await dispatch(3, writer, request("prompt-inactive"))
        let inactiveBody = try XCTUnwrap(JSONSerialization.jsonObject(with: inactive.data) as? [String: Any])
        let inactiveID = try XCTUnwrap(inactiveBody["request_id"] as? String)
        await inactive.afterDelivery?()
        XCTAssertEqual(prompts, 1)
        let inactiveStatus = try await local.store!.ownerRequest(owner: writer.eventOwner, id: inactiveID)
        XCTAssertEqual(inactiveStatus["status"] as? String, "failed")
        XCTAssertTrue((inactiveStatus["message"] as? String ?? "").contains("Open Bridgit"))
        active = true
        let granted = await dispatch(4, writer, request("prompt-granted"))
        let grantedBody = try XCTUnwrap(JSONSerialization.jsonObject(with: granted.data) as? [String: Any])
        let grantedID = try XCTUnwrap(grantedBody["request_id"] as? String)
        await granted.afterDelivery?()
        XCTAssertEqual(prompts, 2)
        let grantedStatus = try await local.store!.ownerRequest(owner: writer.eventOwner, id: grantedID)
        XCTAssertEqual(grantedStatus["status"] as? String, "completed")
    }

    @MainActor func testPendingMicrophonePermissionCannotOverrideOffBackgroundOrSleep() async throws {
        enum Interruption: CaseIterable { case off, background, sleep, stopped }
        for interruption in Interruption.allCases {
            let workspace = workspace()
                let gate = MediaPermissionGate()
            let local = workspace.model(hostMicrophonePermission: { await gate.request() })
            let host = try XCTUnwrap(local.host)
            let enabling = Task { try await host.setMicrophoneEnabled(true) }
            for _ in 0..<200 {
                if gate.waiting { break }
                try await Task.sleep(for: .milliseconds(10))
            }
            guard gate.waiting else { enabling.cancel(); return XCTFail("The permission gate did not open.") }
            switch interruption {
            case .off: try await host.setMicrophoneEnabled(false)
            case .background: await host.lifecycle(active: false, backgrounded: true)
            case .sleep: await host.sleep()
            case .stopped: await host.stop()
            }
            gate.allow()
            do { try await enabling.value; XCTFail("A stale permission response enabled recording.") }
            catch is CancellationError { }
            XCTAssertFalse(host.microphoneEnabled)
        }
    }

    @MainActor func testMicrophonePermissionMayCompleteDuringTransientInactiveWithoutStartingHost() async throws {
        let workspace = workspace()
        let gate = MediaPermissionGate()
        let local = workspace.model(hostMicrophonePermission: { await gate.request() })
        let host = try XCTUnwrap(local.host)
        let enabling = Task { try await host.setMicrophoneEnabled(true) }
        for _ in 0..<200 {
            if gate.waiting { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        guard gate.waiting else { enabling.cancel(); return XCTFail("The permission gate did not open.") }
        await host.lifecycle(active: false)
        gate.allow()
        try await enabling.value
        XCTAssertTrue(host.microphoneEnabled)
        XCTAssertEqual(host.state, .stopped)
        XCTAssertFalse(host.enabledIntent)
    }

    @MainActor func testMicrophoneAuthorizationCannotCommitAfterConsentInvalidationOrRevocation() async throws {
        enum Interruption: CaseIterable { case off, background, revoked }
        for interruption in Interruption.allCases {
            let workspace = workspace()
                let gate = MediaPermissionGate()
            let local = workspace.model(hostMicrophonePermission: { true })
            let host = try XCTUnwrap(local.host)
            var commits = 0, revoked = false
            let enabling = Task {
                try await host.setMicrophoneEnabled(true, authorize: {
                    _ = await gate.request()
                    if revoked { throw DeviceAPIError(403, "agent_revoked", "Fixture grant revoked.") }
                }, commit: { commits += 1 })
            }
            for _ in 0..<200 {
                if gate.waiting { break }
                try await Task.sleep(for: .milliseconds(10))
            }
            guard gate.waiting else { enabling.cancel(); return XCTFail("Authorization did not reach its await.") }
            switch interruption {
            case .off: try await host.setMicrophoneEnabled(false)
            case .background: await host.lifecycle(active: false, backgrounded: true)
            case .revoked: revoked = true
            }
            gate.allow()
            do { try await enabling.value; XCTFail("Invalidated consent was accepted.") }
            catch is CancellationError { XCTAssertFalse(revoked) }
            catch let error as DeviceAPIError { XCTAssertEqual(error.code, "agent_revoked"); XCTAssertTrue(revoked) }
            XCTAssertEqual(commits, 0)
            XCTAssertFalse(host.microphoneEnabled)
        }
    }

    @MainActor func testMicrophoneDiscoveryDistinguishesAppletHearingAndClips() throws {
        let clip = ProfileOperation(method: "POST", path: "/v1/mic/record")
        let hearingOnly = LocalHostModel.microphoneCapability(recordingAllowed: true, hearingAllowed: true,
            recordingEnabled: false, hearingEnabled: true, microphonePresent: true, microphoneAllowed: true,
            offlineHearing: true, hostReady: true, contextAllowed: true)
        XCTAssertTrue(hearingOnly.enabled)
        XCTAssertTrue(hearingOnly.ready)
        XCTAssertFalse(hearingOnly.callable)
        XCTAssertFalse(hearingOnly.operations.contains(clip))
        XCTAssertEqual(hearingOnly.constraints.parameters?["applet_hearing"], ["ready"])
        let recordOnly = LocalHostModel.microphoneCapability(recordingAllowed: true, hearingAllowed: false,
            recordingEnabled: true, hearingEnabled: false, microphonePresent: true, microphoneAllowed: true,
            offlineHearing: false, hostReady: true, contextAllowed: true)
        XCTAssertTrue(recordOnly.callable)
        XCTAssertTrue(recordOnly.operations.contains(clip))
        XCTAssertEqual(recordOnly.constraints.parameters?["applet_hearing"], ["unavailable"])
        XCTAssertEqual(recordOnly.constraints.parameters?["on_device_transcription"], ["unavailable_for_default_language"])
        let noNotices = LocalHostModel.microphoneCapability(recordingAllowed: true, hearingAllowed: true,
            recordingEnabled: true, hearingEnabled: true, microphonePresent: true, microphoneAllowed: true,
            offlineHearing: true, hostReady: true, contextAllowed: true, notificationsAllowed: false)
        XCTAssertFalse(noNotices.ready)
        XCTAssertFalse(noNotices.callable)
        XCTAssertEqual(noNotices.blockedReason, "notifications_off")
        XCTAssertEqual(noNotices.constraints.parameters?["applet_hearing"], ["unavailable"])
        XCTAssertEqual(noNotices.constraints.parameters?["on_device_transcription"], ["unavailable_for_default_language"])
    }

    @MainActor func testCameraPermissionMayCompleteDuringTransientInactiveWithoutStartingHost() async throws {
        let gate = MediaPermissionGate(), (local, _) = try await local(gate: gate)
        let host = try XCTUnwrap(local.host)
        let enabling = Task { await host.setCameraEnabled(true) }
        for _ in 0..<200 {
            if gate.waiting { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        guard gate.waiting else { enabling.cancel(); return XCTFail("The permission gate did not open.") }
        await host.lifecycle(active: false)
        gate.allow()
        await enabling.value
        XCTAssertTrue(host.cameraEnabled)
        XCTAssertEqual(host.state, .stopped)
        XCTAssertFalse(host.enabledIntent)
    }

    @MainActor func testPendingCameraAuthorizationCannotOverrideOffBackgroundSleepOrStop() async throws {
        enum Interruption: CaseIterable { case off, background, sleep, stopped }
        for interruption in Interruption.allCases {
            let workspace = workspace()
            let local = workspace.model(hostCameraPermission: { true })
            let host = try XCTUnwrap(local.host)
            let gate = MediaPermissionGate()
            var commits = 0
            let enabling = Task {
                await host.setCameraEnabled(true, authorize: { _ = await gate.request() }, commit: { commits += 1 })
            }
            for _ in 0..<200 {
                if gate.waiting { break }
                try await Task.sleep(for: .milliseconds(10))
            }
            guard gate.waiting else { enabling.cancel(); return XCTFail("Authorization did not reach its await.") }
            switch interruption {
            case .off: await host.setCameraEnabled(false)
            case .background: await host.lifecycle(active: false, backgrounded: true)
            case .sleep: await host.sleep()
            case .stopped: await host.stop()
            }
            gate.allow()
            await enabling.value
            XCTAssertFalse(host.cameraEnabled)
            XCTAssertEqual(commits, 0)
        }
    }

    @MainActor func testNetworkTabIsDiscoveredAndChangesOnlyAfterDelivery() async throws {
        let defaults = AppleFixtureWorkspace.defaults
        let previous = defaults.object(forKey: "devices.tab")
        defer { defaults.set(previous, forKey: "devices.tab") }
        defaults.set("map", forKey: "devices.tab")
        let (local, agent) = try await local(gate: MediaPermissionGate())
        let initial = try await local.configuration.configuration(.init(method: "GET", path: "/v1/settings"), agent: agent)
        let before = try XCTUnwrap(JSONSerialization.jsonObject(with: initial.data) as? [String: Any])
        XCTAssertTrue((before["supported_fields"] as? [String] ?? []).contains("devices_tab"))
        XCTAssertEqual((before["values"] as? [String: Any])?["devices_tab"] as? String, "map")
        let response = try await local.configuration.configuration(.init(method: "POST", path: "/v1/settings", body: ["field": "devices_tab", "value": "list"]), agent: agent)
        XCTAssertEqual(response.status, 202)
        XCTAssertEqual(defaults.string(forKey: "devices.tab"), "map")
        await response.afterDelivery?()
        XCTAssertEqual(defaults.string(forKey: "devices.tab"), "list")
        let read = try await local.configuration.configuration(.init(method: "GET", path: "/v1/settings"), agent: agent)
        let after = try XCTUnwrap(JSONSerialization.jsonObject(with: read.data) as? [String: Any])
        XCTAssertEqual((after["values"] as? [String: Any])?["devices_tab"] as? String, "list")
        let reset = try await local.configuration.configuration(.init(method: "DELETE", path: "/v1/settings/devices_tab"), agent: agent)
        XCTAssertEqual(defaults.string(forKey: "devices.tab"), "list")
        await reset.afterDelivery?()
        XCTAssertEqual(defaults.string(forKey: "devices.tab"), "map")
    }

    @MainActor func testLanDiscoverySettingAppliesAfterDeliveryAndResets() async throws {
        let (local, agent) = try await local(gate: MediaPermissionGate())
        XCTAssertNotNil(local.lan)
        XCTAssertFalse(local.lanDiscoveryEnabled)
        let response = try await local.configuration.configuration(.init(method: "POST", path: "/v1/settings", body: ["field": "lan_discovery_enabled", "value": true]), agent: agent)
        XCTAssertEqual(response.status, 202)
        XCTAssertFalse(local.lanDiscoveryEnabled)
        await response.afterDelivery?()
        XCTAssertTrue(local.lanDiscoveryEnabled)
        let serviceEnabled = await local.lan?.isEnabled()
        XCTAssertEqual(serviceEnabled, true)
        let read = try await local.configuration.configuration(.init(method: "GET", path: "/v1/settings"), agent: agent)
        let values = try XCTUnwrap(JSONSerialization.jsonObject(with: read.data) as? [String: Any])
        XCTAssertEqual((values["values"] as? [String: Any])?["lan_discovery_enabled"] as? Bool, true)
        let reset = try await local.configuration.configuration(.init(method: "DELETE", path: "/v1/settings/lan_discovery_enabled"), agent: agent)
        await reset.afterDelivery?()
        XCTAssertFalse(local.lanDiscoveryEnabled)
    }

    @MainActor func testAuthorizedCameraChangeStartsAfterDeliveryAndCanBeCancelledDuringOSPrompt() async throws {
        let gate = MediaPermissionGate(), (local, agent) = try await local(gate: gate)
        let response = try await local.configuration.configuration(.init(method: "POST", path: "/v1/settings", body: ["field": "host_camera", "value": true]), agent: agent)
        XCTAssertEqual(response.status, 202); XCTAssertFalse(local.host!.cameraEnabled); XCTAssertFalse(gate.waiting)
        let value = try JSONSerialization.jsonObject(with: response.data) as! [String: Any], id = value["request_id"] as! String
        let applying = Task { await response.afterDelivery?() }
        while !gate.waiting { await Task.yield() }
        _ = try await local.configuration.configuration(.init(method: "DELETE", path: "/v1/settings/requests/" + id), agent: agent)
        gate.allow(); await applying.value
        XCTAssertFalse(local.host!.cameraEnabled)
        let final = try await local.store!.ownerRequest(owner: agent.id + ":" + agent.generation, id: id)
        XCTAssertEqual(final["status"] as? String, "cancelled")
    }
    @MainActor func testAuthenticatedSettingsRoundTripAppliesWithoutAnotherOwnerApprovalAndRetriesOnlyOneOperation() async throws {
        let gate = MediaPermissionGate(), (local, agent) = try await local(gate: gate)
        let authority = try XCTUnwrap(local.host?.authority), store = try XCTUnwrap(local.store), engine = try XCTUnwrap(local.engine)
        await authority.setState(enabled: true, awake: true, foreground: true)
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: engine, profile: { _ in throw CancellationError() }, configuration: local.configuration)
        func job(_ sequence: Int64, _ request: HostRequest) -> HostJob {
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            return .init(id: UUID().uuidString, sequence: sequence, agentID: agent.id, generation: agent.generation, deviceID: local.host!.deviceID!, deadline: now + 30000, createdAt: now, request: request)
        }
        let change = HostRequest(method: "POST", path: "/v1/settings", body: ["field": "host_camera", "value": true], idempotencyKey: "camera-on")
        let first = await dispatcher.dispatch(job(1, change)), retry = await dispatcher.dispatch(job(2, change))
        XCTAssertEqual(first.status, 202); XCTAssertEqual(first.data, retry.data)
        XCTAssertFalse(local.host!.cameraEnabled)
        let proposed = try JSONSerialization.jsonObject(with: first.data) as! [String: Any], id = proposed["request_id"] as! String
        let pending = try await store.ownerRequests(); XCTAssertEqual(pending.count, 1)
        let applying = Task { await retry.afterDelivery?() }
        while !gate.waiting { await Task.yield() }
        gate.allow(); await applying.value
        XCTAssertTrue(local.host!.cameraEnabled)
        await first.afterDelivery?() // A second acknowledged retry cannot apply twice.
        XCTAssertTrue(local.host!.cameraEnabled)
        let read = await dispatcher.dispatch(job(3, .init(method: "GET", path: "/v1/settings")))
        let state = try JSONSerialization.jsonObject(with: read.data) as! [String: Any]
        XCTAssertEqual((state["values"] as? [String: Any])?["host_camera"] as? Bool, true)
        let status = await dispatcher.dispatch(job(4, .init(method: "GET", path: "/v1/settings/requests/" + id)))
        let completed = try JSONSerialization.jsonObject(with: status.data) as! [String: Any]
        XCTAssertEqual(completed["status"] as? String, "completed"); XCTAssertNil(completed["payload"])
    }
    @MainActor func testBridgitNameConfigurationRoundTripGrantAndValidation() async throws {
        let gate = MediaPermissionGate(), (local, _) = try await local(gate: gate)
        let authority = try XCTUnwrap(local.host?.authority), store = try XCTUnwrap(local.store), engine = try XCTUnwrap(local.engine)
        let calls = NamesCalls()
        let cache = NamesCache()
        let names = DeviceDomainNameService(call: { method, path, body in await calls.answer(method, path, body) },
                                            load: { cache.data }, store: { cache.data = $0 })
        let configuration = OwnerConfigurationModel(local: local, bridgitNames: names)
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: engine, profile: { _ in throw CancellationError() }, configuration: configuration)
        let (agent, _) = try await authority.create(name: "Namer", operations: [.deviceRead, .configurationRead, .configurationWrite], applets: [])
        let (reader, _) = try await authority.create(name: "Reader", operations: [.deviceRead], applets: [])
        func job(_ sequence: Int64, _ request: HostRequest, as caller: HostAgent? = nil) -> HostJob {
            let now = Int64(Date().timeIntervalSince1970 * 1000), who = caller ?? agent
            return .init(id: UUID().uuidString, sequence: sequence, agentID: who.id, generation: who.generation, deviceID: local.host!.deviceID!, deadline: now + 30000, createdAt: now, request: request)
        }
        func object(_ response: HostResponse) throws -> [String: Any] { try JSONSerialization.jsonObject(with: response.data) as! [String: Any] }

        let refused = await dispatcher.dispatch(job(1, .init(method: "POST", path: "/v1/owner/configuration/name", body: ["name": "lab-mac"], idempotencyKey: "n1"), as: reader))
        XCTAssertEqual(refused.status, 403)
        let invalid = await dispatcher.dispatch(job(2, .init(method: "POST", path: "/v1/owner/configuration/name", body: ["name": "Lab Mac!"], idempotencyKey: "n2")))
        XCTAssertEqual(invalid.status, 422)
        let extra = await dispatcher.dispatch(job(3, .init(method: "POST", path: "/v1/owner/configuration/name", body: ["visible": true, "owner": "me"], idempotencyKey: "n3")))
        XCTAssertEqual(extra.status, 422)
        let none = await calls.count
        XCTAssertEqual(none, 0, "refused and invalid requests never reach the names service")

        let unnamed = try object(await dispatcher.dispatch(job(4, .init(method: "GET", path: "/v1/health"))))
        XCTAssertEqual((unnamed["bridgit_name"] as? [String: Any])?["status"] as? String, "unregistered")
        let blocked = try object(await dispatcher.dispatch(job(5, .init(method: "GET", path: "/v1/health"), as: reader)))
        XCTAssertNotNil((blocked["bridgit_name"] as? [String: Any])?["blocked"])

        await calls.queue(201, ["name": "lab-mac", "label": "Lab", "visible": true, "heartbeat_every_s": 300])
        let registered = await dispatcher.dispatch(job(6, .init(method: "POST", path: "/v1/owner/configuration/name", body: ["name": "lab-mac", "label": "Lab", "visible": true], idempotencyKey: "n6")))
        XCTAssertEqual(registered.status, 200)
        XCTAssertEqual(try object(registered)["name"] as? String, "lab-mac")
        XCTAssertEqual(configuration.bridgitName?.name, "lab-mac")

        await calls.queue(200, ["name": "lab-mac", "label": "Lab", "visible": true])
        let read = try object(await dispatcher.dispatch(job(7, .init(method: "GET", path: "/v1/owner/configuration/name"))))
        XCTAssertEqual(read["status"] as? String, "registered")
        let health = try object(await dispatcher.dispatch(job(8, .init(method: "GET", path: "/v1/health"))))
        XCTAssertEqual((health["bridgit_name"] as? [String: Any])?["name"] as? String, "lab-mac")

        let renamed = await dispatcher.dispatch(job(9, .init(method: "POST", path: "/v1/owner/configuration/name", body: ["name": "other-mac"], idempotencyKey: "n9")))
        XCTAssertEqual(renamed.status, 409)

        await calls.queue(200, ["name": "lab-mac", "label": NSNull(), "visible": false])
        let hidden = await dispatcher.dispatch(job(10, .init(method: "DELETE", path: "/v1/owner/configuration/name", idempotencyKey: "n10")))
        XCTAssertEqual(hidden.status, 200)
        XCTAssertEqual(configuration.bridgitName?.visible, false)
        let methods = await calls.methods
        XCTAssertEqual(methods, ["POST /v1/names", "GET /v1/names/me", "DELETE /v1/names/me"])
    }
    @MainActor func testAgentRevocationWhileOSPromptIsOpenCannotGrantCamera() async throws {
        let gate = MediaPermissionGate(), (local, agent) = try await local(gate: gate)
        let response = try await local.configuration.configuration(.init(method: "POST", path: "/v1/settings", body: ["field": "host_camera", "value": true]), agent: agent)
        let value = try JSONSerialization.jsonObject(with: response.data) as! [String: Any], id = value["request_id"] as! String
        let applying = Task { await response.afterDelivery?() }
        while !gate.waiting { await Task.yield() }
        try await local.host!.authority!.remove(agent.id)
        gate.allow(); await applying.value
        XCTAssertFalse(local.host!.cameraEnabled)
    }
    @MainActor func testConcurrentAcknowledgedRetriesClaimConfigurationOnlyOnce() async throws {
        let gate = MediaPermissionGate(), (local, agent) = try await local(gate: gate)
        let response = try await local.configuration.configuration(.init(method: "POST", path: "/v1/settings", body: ["field": "host_camera", "value": true]), agent: agent)
        let value = try JSONSerialization.jsonObject(with: response.data) as! [String: Any], id = value["request_id"] as! String
        let workers = (0..<20).map { _ in Task { await response.afterDelivery?() } }
        while !gate.waiting { await Task.yield() }
        // Yield to all competing claims while the real worker is inside the OS prompt.
        for _ in 0..<100 { await Task.yield() }
        let active = try await local.store!.ownerRequest(owner: agent.id + ":" + agent.generation, id: id)
        XCTAssertEqual(active["status"] as? String, "applying"); XCTAssertEqual(gate.count, 1)
        gate.allow()
        for worker in workers { await worker.value }
        let final = try await local.store!.ownerRequest(owner: agent.id + ":" + agent.generation, id: id)
        XCTAssertEqual(final["status"] as? String, "completed"); XCTAssertTrue(local.host!.cameraEnabled)
    }
    @MainActor func testAuthorizedAppearanceWriteAndResetNeedNoNativeApproval() async throws {
        let gate = MediaPermissionGate(), (local, agent) = try await local(gate: gate)
        let write = try await local.configuration.configuration(.init(method: "POST", path: "/v1/settings", body: ["field": "appearance", "value": "Dark"]), agent: agent)
        await write.afterDelivery?()
        XCTAssertEqual(AppleFixtureWorkspace.defaults.string(forKey: "appearance"), "Dark")
        let reset = try await local.configuration.configuration(.init(method: "DELETE", path: "/v1/settings/appearance"), agent: agent)
        await reset.afterDelivery?()
        XCTAssertEqual(AppleFixtureWorkspace.defaults.string(forKey: "appearance"), "System")
        XCTAssertFalse(gate.waiting)
    }
    @MainActor func testHostSuspensionPreservesConfiguredCloudAndCameraAccessUntilRevoked() async throws {
        let suite = "bridgit-configuration-" + UUID().uuidString, saved = UserDefaults(suiteName: suite)!
        let workspace = workspace()
        defer { saved.removePersistentDomain(forName: suite) }
        let local = workspace.model(hostCameraPermission: { true }, settings: saved)
        await local.enableModels(); await local.host?.setCameraEnabled(true)
        await local.host?.sleep()
        XCTAssertTrue(local.modelEnabled); XCTAssertTrue(local.host?.cameraEnabled == true)
        let reopened = workspace.model(hostCameraPermission: { true }, settings: saved)
        XCTAssertTrue(reopened.modelEnabled); XCTAssertTrue(reopened.host?.cameraEnabled == true)
        reopened.disableModels(); await reopened.host?.setCameraEnabled(false)
        let revoked = workspace.model(settings: saved)
        XCTAssertFalse(revoked.modelEnabled); XCTAssertFalse(revoked.host?.cameraEnabled == true)
    }
    @MainActor func testOwnerSwitchingCameraOffWinsOverEarlierPermissionPrompt() async throws {
        let gate = MediaPermissionGate(), (local, _) = try await local(gate: gate)
        let enabling = Task { await local.host!.setCameraEnabled(true) }
        while !gate.waiting { await Task.yield() }
        await local.host!.setCameraEnabled(false)
        gate.allow(); await enabling.value
        XCTAssertFalse(local.host!.cameraEnabled)
    }
}

@MainActor private final class WakePreparationGate {
    var waiting = false
    private var continuation: CheckedContinuation<Void, Never>?
    func wait() async { waiting = true; await withCheckedContinuation { continuation = $0 } }
    func release() { continuation?.resume(); continuation = nil }
}
extension OwnerConfigurationTests {
    @MainActor func testWakeOffAndStopWinOverAnEarlierPendingEnable() async throws {
        for stopping in [false, true] {
            let workspace = workspace()
                let local = workspace.model(), gate = WakePreparationGate()
            var registrations = 0
            let host = LocalHostModel(local: local, wakePreparation: { await gate.wait() }, wakeRegistration: { registrations += 1 })
            let enabling = Task { await host.setWakeEnabled(true) }
            while !gate.waiting { await Task.yield() }
            if stopping { await host.stop() } else { await host.setWakeEnabled(false) }
            gate.release(); await enabling.value
            XCTAssertFalse(host.wakeEnabled); XCTAssertEqual(registrations, 0)
        }
    }
    @MainActor func testWakeOffWhileApprovalIsCommittingCannotRegisterAfterward() async throws {
        let workspace = workspace()
        let local = workspace.model(), gate = WakePreparationGate()
        var registrations = 0
        let host = LocalHostModel(local: local, wakePreparation: {}, wakeRegistration: { registrations += 1 })
        let enabling = Task { await host.setWakeEnabled(true, commit: { await gate.wait() }) }
        while !gate.waiting { await Task.yield() }
        await host.setWakeEnabled(false); gate.release(); await enabling.value
        XCTAssertFalse(host.wakeEnabled); XCTAssertEqual(registrations, 0)
    }
}
