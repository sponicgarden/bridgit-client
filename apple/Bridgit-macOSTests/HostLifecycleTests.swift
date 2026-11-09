import AVFoundation
import BridgitCore
import XCTest
@testable import Bridgit_macOS

@MainActor private final class HostStartGate {
    var starts = 0
    var stops = 0
    private var continuation: CheckedContinuation<Void, Never>?
    func start() async {
        starts += 1
        if starts == 1 { await withCheckedContinuation { continuation = $0 } }
    }
    func release() { continuation?.resume(); continuation = nil }
}
final class HostLifecycleTests: XCTestCase {
    @MainActor private func local() -> LocalAppletModel {
        let path = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: path) }
        return LocalAppletModel(directory: path)
    }
    @MainActor func testDeviceProfileDistinguishesHardwareFromAgentAccess() async throws {
        let local = local()
        let host = try XCTUnwrap(local.host)
        let authority = HostAuthority(store: try XCTUnwrap(local.store), deviceID: "profile-fixture")
        let (agent, _) = try await authority.create(name: "Reader", operations: [.deviceRead], applets: [])
        let profile = host.profile(for: agent)
        for (name, capability, mediaType) in [("camera", "camera", AVMediaType.video), ("microphone", "mic", .audio)] {
            let present = AVCaptureDevice.default(for: mediaType) != nil
            XCTAssertEqual(profile.device.hardware[name], present ? "present" : "absent")
            let state = try XCTUnwrap(profile.capabilities[capability])
            XCTAssertEqual(state.supported, present)
            XCTAssertFalse(state.agentAllowed)
            XCTAssertFalse(state.callable)
        }
    }
    @MainActor func testActivityNoteGrantAdvertisesAndPersistsNativeHostDetails() async throws {
        let local = local()
        let host = try XCTUnwrap(local.host), store = try XCTUnwrap(local.store)
        let authority = HostAuthority(store: store, deviceID: "notes-fixture")
        let (writer, _) = try await authority.create(name: "Fixture author", operations: [.log], applets: [])
        let (reader, _) = try await authority.create(name: "Fixture reader", operations: [.deviceRead], applets: [])
        let writeCapability = try XCTUnwrap(host.profile(for: writer).capabilities["log"])
        XCTAssertTrue(writeCapability.agentAllowed)
        XCTAssertFalse(writeCapability.callable, "The app host remains disconnected in this fixture.")
        XCTAssertEqual(writeCapability.operations, [.init(method: "POST", path: "/v1/log")])
        XCTAssertEqual(host.profile(for: reader).capabilities["log"]?.operations, [])
        await authority.setState(enabled: true, awake: true, foreground: true)
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: try XCTUnwrap(local.engine),
                                        profile: { agent in await host.profile(for: agent) })
        let now = Int64(Date().timeIntervalSince1970 * 1_000)
        let response = await dispatcher.dispatch(.init(id: UUID().uuidString, sequence: 1, agentID: writer.id,
            generation: writer.generation, deviceID: "notes-fixture", deadline: now + 10_000, createdAt: now,
            request: .init(method: "POST", path: "/v1/log",
                body: ["message": "Shelf checked", "level": "warn", "data": ["count": 2, "api_token": "fixture-private"]])))
        XCTAssertEqual(response.status, 201)
        let entries = try await store.hostActivity(kinds: [.note])
        let detail = HostActivityDetail(try XCTUnwrap(entries.first))
        XCTAssertEqual(detail.text(for: "level"), "warn")
        XCTAssertEqual(detail.text(for: "message"), "Shelf checked")
        XCTAssertEqual(detail.text(for: "data"), "{\"api_token\":\"[redacted]\",\"count\":2}")
    }

    @MainActor func testSleepSceneEventAndWakeDuringEnrollmentReconcileOnceOldStartEnds() async {
        let local = local(), gate = HostStartGate()
        let host = LocalHostModel(local: local, startConnection: { await gate.start() }, stopConnection: { gate.stops += 1 })
        let first = Task { await host.start() }
        while gate.starts == 0 { await Task.yield() }
        await host.sleep()
        await host.lifecycle(active: false) // AppKit may deliver this after willSleep.
        await host.wake() // Original HTTP await has not completed yet.
        XCTAssertEqual(gate.starts, 1)
        gate.release(); await first.value
        XCTAssertEqual(gate.starts, 2, "Wake must queue a replacement connection")
        XCTAssertFalse(host.busy)
        await host.lifecycle(active: false)
        XCTAssertEqual(gate.starts, 2); XCTAssertEqual(gate.stops, 2, "Losing focus while awake must not stop Mac hosting")
    }
    @MainActor func testExplicitStopWhileAsleepCancelsQueuedWake() async {
        let local = local(), gate = HostStartGate()
        let host = LocalHostModel(local: local, startConnection: { await gate.start() }, stopConnection: { gate.stops += 1 })
        let first = Task { await host.start() }
        while gate.starts == 0 { await Task.yield() }
        await host.sleep(); await host.wake(); await host.stop()
        gate.release(); await first.value
        await host.wake()
        XCTAssertEqual(gate.starts, 1, "Stop must win over a pending wake")
    }
    @MainActor func testSatelliteIntentSurvivesSuspendUntilExplicitStop() async throws {
        let path = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: path) }
        let local = LocalAppletModel(directory: path)
        await local.reload()
        var stopped = 0
        let host = LocalHostModel(local: local, satelliteStart: { _, port in port }, satelliteStop: { stopped += 1 })
        do { await host.prepare(identityOverride: try .ephemeral()) }
        catch { XCTFail("Identity preparation failed: \(type(of: error))"); return }
        guard let authority = host.authority else { XCTFail("Host preparation failed: \(host.problem ?? "unknown")"); return }
        guard let address = TailnetAddress("100.64.0.5") else { XCTFail("Fixture address invalid"); return }
        do { try await host.startSatelliteListener(address) }
        catch { XCTFail("Fake listener start failed: \(type(of: error))"); await host.stopCallbacksForTesting(); return }
        XCTAssertTrue(host.satelliteIntent); XCTAssertEqual(host.satelliteAddress, address)
        let active = await authority.callbackExecutionAvailable(); XCTAssertTrue(active)
        await host.sleep()
        XCTAssertTrue(host.satelliteIntent); XCTAssertNil(host.satelliteAddress)
        let asleep = await authority.callbackExecutionAvailable(); XCTAssertFalse(asleep)
        await host.wake()
        let awake = await authority.callbackExecutionAvailable(); XCTAssertTrue(awake)
        await host.stopSatelliteListener()
        XCTAssertFalse(host.satelliteIntent)
        let stoppedState = await authority.callbackExecutionAvailable(); XCTAssertFalse(stoppedState)
        XCTAssertGreaterThanOrEqual(stopped, 2)
        await host.stopCallbacksForTesting()
        try await local.store?.closeTemporaryFixture(at: path)
    }

    @MainActor func testSatelliteStopWinsWhileStartIsSuspended() async throws {
        let path = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: path) }
        let local = LocalAppletModel(directory: path); await local.reload()
        let gate = HostStartGate()
        let host = LocalHostModel(local: local, satelliteStart: { _, port in await gate.start(); return port }, satelliteStop: { gate.stops += 1 })
        await host.prepare(identityOverride: try .ephemeral())
        let address = try XCTUnwrap(TailnetAddress("100.64.0.5"))
        let start = Task { try await host.startSatelliteListener(address) }
        while gate.starts == 0 { await Task.yield() }
        await host.stopSatelliteListener(); gate.release()
        do { try await start.value; XCTFail("Stopped start must not bind") } catch is CancellationError { }
        XCTAssertFalse(host.satelliteIntent); XCTAssertNil(host.satelliteAddress)
        let authority = try XCTUnwrap(host.authority)
        let enabled = await authority.callbackExecutionAvailable(); XCTAssertFalse(enabled)
        await host.stopCallbacksForTesting()
        try await local.store?.closeTemporaryFixture(at: path)
    }

    @MainActor func testHostIntentIsDurableAndSeparateFromConnectionState() async {
        let suite = "bridgit-host-intent-" + UUID().uuidString, saved = UserDefaults(suiteName: suite)!
        let path = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { saved.removePersistentDomain(forName: suite); try? FileManager.default.removeItem(at: path) }
        let local = LocalAppletModel(directory: path, settings: saved)
        let host = LocalHostModel(local: local, startConnection: {}, stopConnection: {})
        await host.start(); XCTAssertTrue(host.enabledIntent)
        await host.sleep(); XCTAssertTrue(host.enabledIntent)
        let reopened = LocalHostModel(local: local, startConnection: {}, stopConnection: {})
        XCTAssertTrue(reopened.enabledIntent); XCTAssertEqual(reopened.state, .stopped)
        await reopened.stop()
        let stopped = LocalHostModel(local: local, startConnection: {}, stopConnection: {})
        XCTAssertFalse(stopped.enabledIntent)
    }

}

/// A0128: new control agents default to Tailscale when this device has a Tailscale address.
final class ControlAgentConnectionDefaultTests: XCTestCase {
    func testDefaultsToTailscaleWhenFound() {
        XCTAssertEqual(ControlAgentConnection.initial(current: .relay, chosen: false, tailscaleFound: true), .direct)
        XCTAssertEqual(ControlAgentConnection.initial(current: .direct, chosen: false, tailscaleFound: false), .relay)
    }
    func testExplicitChoiceAndHostedClientsStay() {
        XCTAssertEqual(ControlAgentConnection.initial(current: .relay, chosen: true, tailscaleFound: true), .relay)
        XCTAssertEqual(ControlAgentConnection.initial(current: .claude, chosen: false, tailscaleFound: true), .claude)
        XCTAssertEqual(ControlAgentConnection.initial(current: .chatGPT, chosen: false, tailscaleFound: true), .chatGPT)
    }
    func testSaysWhenTailscaleIsMissing() {
        XCTAssertEqual(ControlAgentConnection.direct.detail(tailscaleFound: false), "Tailscale not found on this device.")
        XCTAssertEqual(ControlAgentConnection.direct.detail(tailscaleFound: true), ControlAgentConnection.direct.detail)
    }
}
