import XCTest
import AVFoundation
import BridgitCore
import UniformTypeIdentifiers
import CoreImage.CIFilterBuiltins
import ImageIO
#if os(macOS)
@testable import Bridgit_macOS
#else
@testable import Bridgit_iOS
#endif

final class MoreSearchTests: XCTestCase {
    func testLinkedDeviceCountUsesProvenInstallIdentity() throws {
        let phone = try JSONDecoder().decode(Phone.self, from: Data(#"{"phone":"fixture-one","name":"Kitchen"}"#.utf8))
        XCTAssertEqual(PortalDevice.list(phones: [phone], pairings: []).map(\.name), ["Kitchen"])
        let legacy = Pairing(base: URL(string: "https://r.example/d/dev_ABCDEFGH12")!, deviceId: "dev_ABCDEFGH12",
                             token: "rly_0123456789abcdef0123", name: phone.displayName)
        let rows = PortalDevice.list(phones: [phone], pairings: [legacy])
        XCTAssertEqual(rows.count, 2)
        XCTAssertNil(rows[0].cloud)
        XCTAssertEqual(rows[1].cloud?.phone, phone.phone)
        var proven = legacy; proven.install = phone.phone
        XCTAssertEqual(PortalDevice.list(phones: [phone], pairings: [proven]).count, 1)
    }

    func testEveryWordMustMatchTheSameEntryIgnoringCaseAndAccents() {
        XCTAssertTrue(MoreEntry.linked.matches("DEVÍCES  link"))
        XCTAssertTrue(MoreEntry.linked.matches(" satellite "))
        XCTAssertFalse(MoreEntry.linked.matches("devices camera"))
        XCTAssertTrue(MoreEntry.sendReports.matches("feedback upload"))
        XCTAssertFalse(MoreEntry.sendReports.matches("upload privacy"))
        XCTAssertFalse(MoreEntry.bug.matches("upload privacy"))
    }
}

@MainActor final class AbilityCatalogTests: XCTestCase {
    func testLocalCatalogExplainsMicrophoneAndActivity() throws {
        for anchor in ["record-a-short-clip", "everything-is-logged", "agent-notes", "the-api-explains-itself"] {
            let entry = try XCTUnwrap(AbilityCatalogEntry.bundled.first { $0.anchor == anchor })
            let supplement = try XCTUnwrap(entry.localSupplement, anchor)
            XCTAssertGreaterThanOrEqual(supplement.options.count, 2, anchor)
            XCTAssertFalse(supplement.example.isEmpty, anchor)
        }
        let mic = try XCTUnwrap(AbilityCatalogEntry.bundled.first { $0.anchor == "record-a-short-clip" })
        let options = try XCTUnwrap(mic.localSupplement).options.joined()
        XCTAssertTrue(options.contains("1 to 120")); XCTAssertTrue(options.contains("M4A"))
    }

    func testLocalCatalogExplainsAgentSetupMCPAndFeedback() throws {
        for anchor in ["add-an-agent-in-a-minute", "one-key-per-agent", "use-bridgit-over-mcp", "report-a-bug-or-ask-for-a-feature"] {
            let entry = try XCTUnwrap(AbilityCatalogEntry.bundled.first { $0.anchor == anchor })
            XCTAssertFalse(try XCTUnwrap(entry.localDetail).isEmpty, anchor)
            let supplement = try XCTUnwrap(entry.localSupplement, anchor)
            XCTAssertGreaterThanOrEqual(supplement.options.count, 2, anchor)
            XCTAssertFalse(supplement.example.isEmpty, anchor)
        }
        let mcp = try XCTUnwrap(AbilityCatalogEntry.bundled.first { $0.anchor == "use-bridgit-over-mcp" })
        XCTAssertTrue(try XCTUnwrap(mcp.localDetail).contains("MCP"))
        XCTAssertTrue(try XCTUnwrap(mcp.localSupplement).options.joined().contains("relay key"))
        let feedback = try XCTUnwrap(AbilityCatalogEntry.bundled.first { $0.anchor == "report-a-bug-or-ask-for-a-feature" })
        XCTAssertTrue(try XCTUnwrap(feedback.localSupplement).options.joined().contains("screenshots"))
    }

    private let now = Date(timeIntervalSince1970: 1_800_000_000)
    private func profile(allowed: Bool = true, expires: TimeInterval = 60, capabilities: Bool = true) -> DeviceProfile {
        let operation = ProfileOperation(method: "POST", path: "/v1/camera/snapshot")
        let cap = ProfileCapability(runtimeID: "camera", implemented: true, supported: true, enabled: true,
                                    ready: true, agentAllowed: allowed, callable: allowed, operations: [operation])
        return DeviceProfile(profileRevision: 1, observedAt: now, expiresAt: now.addingTimeInterval(expires),
                             device: .init(installID: nil, name: "Fixture", deviceType: "phone", platform: "android", osVersion: "1", appVersion: "1", apiRevision: 1, services: [], hardware: [:]),
                             availability: .init(state: "online", inForeground: true), capabilities: capabilities ? ["camera": cap] : [:])
    }

    func testCatalogAndSelectedScopeUseCallerFilteredAvailability() {
        let catalog = AbilityCatalogEntry.bundled
        XCTAssertEqual(catalog.count, 52)
        XCTAssertEqual(catalog.filter { !$0.technical }.count, 36)
        XCTAssertEqual(catalog.filter(\.technical).count, 16)
        XCTAssertEqual(Set(catalog.map(\.anchor)).count, 52)
        let android = AbilityCatalogScope(identity: "android", name: "Kitchen", platform: "android", local: false, switches: ["camera": .on])
        let apple = AbilityCatalogScope(identity: "apple", name: "Office", platform: "ios", local: false)
        let unknown = AbilityCatalogScope(identity: "unknown", name: "Linked device", platform: nil, local: false)
        XCTAssertEqual(AbilityAvailability.label(for: "one-key-per-agent", scope: .thisDevice, profile: nil, now: now), "Supported")
        XCTAssertEqual(AbilityAvailability.label(for: "take-a-photo", scope: android, profile: profile(), now: now), "Available")
        XCTAssertEqual(AbilityAvailability.label(for: "take-a-photo", scope: android, profile: profile(allowed: false), now: now), "Not granted")
        XCTAssertEqual(AbilityAvailability.label(for: "take-a-photo", scope: android, profile: nil, now: now), "Switch last reported on")
        XCTAssertEqual(AbilityAvailability.label(for: "take-a-photo", scope: apple, profile: profile(expires: -1), now: now), "Not reported")
        XCTAssertEqual(AbilityAvailability.label(for: "take-a-photo", scope: unknown, profile: profile(capabilities: false), now: now), "Not reported")
        let shared = AbilityAvailability.shareText(title: "Take a photo", anchor: "take-a-photo", scope: android)
        XCTAssertTrue(shared.contains("For Kitchen"))
        XCTAssertTrue(shared.contains("/v1/openapi.json"))
        XCTAssertFalse(shared.contains("r2.dev"))
        XCTAssertTrue(shared.contains("POST /v1/camera/snapshot"))
        for entry in catalog { XCTAssertNotEqual(AbilityAvailability.description(for: entry.anchor), "Availability has not been reported.") }
        let cameraOff = LocalAbilityFacts(cameraPresent: true, cameraEnabled: false, cameraPermission: false,
                                          microphonePresent: true, microphoneEnabled: false, microphonePermission: false, transcriptionReady: false)
        let local = AbilityCatalogScope(identity: "local", name: "This device", platform: "ios", local: true, localFacts: cameraOff)
        XCTAssertEqual(AbilityAvailability.label(for: "take-a-photo", scope: local, profile: nil, now: now), "Switch off")
        XCTAssertEqual(AbilityAvailability.label(for: "record-a-short-clip", scope: local, profile: nil, now: now), "Switch off")
        XCTAssertEqual(AbilityAvailability.label(for: "use-bridgit-over-mcp", scope: local, profile: nil, now: now), "Supported")
        XCTAssertEqual(AbilityAvailability.label(for: "add-an-agent-in-a-minute", scope: local, profile: nil, now: now), "On this device")
        let noSpeech = LocalAbilityFacts(cameraPresent: true, cameraEnabled: true, cameraPermission: true,
                                        microphonePresent: true, microphoneEnabled: true, microphonePermission: true, transcriptionReady: false)
        let localNoSpeech = AbilityCatalogScope(identity: "local", name: "This device", platform: "ios", local: true, localFacts: noSpeech)
        XCTAssertEqual(AbilityAvailability.label(for: "transcribe-clips-on-the-phone", scope: localNoSpeech, profile: nil, now: now), "Not ready")
        var micProfile = profile(capabilities: false)
        micProfile.capabilities["mic"] = .init(runtimeID: "mic", implemented: true, supported: true, enabled: true,
            ready: true, agentAllowed: true, callable: true,
            constraints: .init(execution: [], parameters: ["on_device_transcription": ["unavailable"]]),
            operations: [.init(method: "POST", path: "/v1/mic/record")])
        XCTAssertEqual(AbilityAvailability.label(for: "transcribe-clips-on-the-phone", scope: apple, profile: micProfile, now: now), "Not ready")
        micProfile.capabilities["mic"]?.constraints.parameters = nil
        XCTAssertEqual(AbilityAvailability.label(for: "transcribe-clips-on-the-phone", scope: apple, profile: micProfile, now: now), "Not reported")
        micProfile.capabilities["mic"]?.constraints.parameters = ["on_device_transcription": ["available_for_default_language"]]
        XCTAssertEqual(AbilityAvailability.label(for: "transcribe-clips-on-the-phone", scope: apple, profile: micProfile, now: now), "Available")
        XCTAssertNotNil(Bundle.main.url(forResource: "whatcan_take_a_photo", withExtension: "webp"))
    }

    func testTransportCatalogUsesObservedLocalListenerAndSeparateRelay() {
        func facts(_ transport: AbilityTransportFacts) -> AbilityCatalogScope {
            .init(identity: "local", name: "This device", platform: "ios", local: true,
                  localFacts: .init(cameraPresent: true, cameraEnabled: false, cameraPermission: false,
                                    microphonePresent: true, microphoneEnabled: false, microphonePermission: false,
                                    transcriptionReady: false, transport: transport))
        }
        var state = AbilityTransportFacts(directBound: false, directStarting: false, directIntent: false,
                                          directFailed: false, hasTailnetAddress: false, relayState: .stopped)
        let anchor = "only-over-tailscale"
        let entry = try! XCTUnwrap(AbilityCatalogEntry.bundled.first { $0.anchor == anchor })
        XCTAssertEqual(AbilityAvailability.title(for: entry, scope: facts(state)), "Direct Tailscale access")
        XCTAssertEqual(AbilityAvailability.title(for: entry, scope: .init(identity: "remote", name: "Peer", platform: "android", local: false)), "Only over Tailscale")
        XCTAssertTrue(AbilityAvailability.shareText(title: AbilityAvailability.title(for: entry, scope: facts(state)), anchor: anchor, scope: facts(state)).contains("Direct Tailscale access"))
        XCTAssertEqual(AbilityAvailability.label(for: anchor, scope: facts(state), profile: nil, now: now), "Direct off")
        XCTAssertTrue(state.description.contains("no Tailscale address is detected"))
        state.hasTailnetAddress = true
        XCTAssertEqual(state.directLabel, "Direct off")
        XCTAssertFalse(state.description.contains("no Tailscale address"))
        state.directIntent = true
        XCTAssertEqual(state.directLabel, "Direct unavailable")
        state.directStarting = true
        XCTAssertEqual(state.directLabel, "Direct starting")
        state.directStarting = false; state.directFailed = true
        XCTAssertEqual(state.directLabel, "Direct needs attention")
        state.directFailed = false; state.directBound = true; state.relayState = .connected
        XCTAssertEqual(state.directLabel, "Direct listening")
        XCTAssertTrue(state.description.contains("Relay is connected"))
        XCTAssertTrue(state.description.contains("Control-agent access is separate"))
        state.directStarting = true; state.directFailed = true
        XCTAssertEqual(state.directLabel, "Direct listening", "An actually bound listener takes precedence over a concurrent start/error flag")
        XCTAssertTrue(state.description.contains("Direct Tailscale is listening"))
        state.directStarting = false; state.directFailed = false
        state.directBound = false; state.directIntent = false; state.relayState = .failed
        XCTAssertTrue(state.description.contains("Relay needs attention"))
        let linked = AbilityCatalogScope(identity: "cloud:other", name: "Other", platform: nil, local: false)
        XCTAssertEqual(AbilityAvailability.label(for: anchor, scope: linked, profile: nil, now: now), "Not reported")
        XCTAssertNotEqual(AbilityAvailability.label(for: anchor, scope: linked, profile: nil, now: now), state.directLabel)
    }

    func testLateResponseAndFailureCannotRestoreAnOlderProfile() async {
        let reader = AbilityProfileReader()
        var finish: CheckedContinuation<DeviceProfile, Error>?
        let old = Task { await reader.refresh(identity: "old") { try await withCheckedThrowingContinuation { finish = $0 } } }
        while finish == nil { await Task.yield() }
        reader.invalidate()
        await reader.refresh(identity: "new") { self.profile(allowed: false) }
        finish?.resume(returning: profile())
        await old.value
        XCTAssertEqual(reader.profile?.capabilities["camera"]?.agentAllowed, false)
        await reader.refresh(identity: "new") { throw RelayError.notAllowed("fixture") }
        XCTAssertNil(reader.profile)
        XCTAssertEqual(reader.state, .denied)
        await reader.refresh(identity: "new") { self.profile(capabilities: false) }
        XCTAssertTrue(reader.profile?.capabilities.isEmpty == true)
        await reader.refresh(identity: "new") { throw RelayError.offline }
        XCTAssertNil(reader.profile)
        XCTAssertEqual(reader.state, .unknown)
    }
}

@MainActor final class FeedbackStatusTests: XCTestCase {
    func testSavedMetadataSurvivesRelaunchAndRetryAfterBuildChanges() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let defaults = try XCTUnwrap(UserDefaults(suiteName: UUID().uuidString))
        let original = FeedbackMetadata(version: "1.2 (2026110622, debug)", appVersion: "1.2", buildNumber: "2026110622", system: "Synthetic OS 7", model: "Synthetic Model",
                                        revision: "fixture-revision", configuration: "debug")
        let store = FeedbackReports(folder: folder, defaults: defaults, captureMetadata: { original })
        let saved = try store.save(kind: .bug, text: "Synthetic retry", autoFix: false, images: [], log: [], localOnly: false, sourceScreen: "Activity")
        await store.send(saved, submit: { report in
            XCTAssertEqual(report.submissionVersion, original.appVersion)
            throw DeviceAPIError(503, "offline", "Synthetic offline")
        })
        let reopened = FeedbackReports(folder: folder, defaults: defaults, captureMetadata: {
            FeedbackMetadata(version: "9.9 (999, release)", system: "Changed OS", model: "Changed Model", revision: nil, configuration: "release")
        })
        let restored = try XCTUnwrap(reopened.reports.first)
        XCTAssertEqual(restored.version, original.version)
        XCTAssertEqual(restored.appVersion, "1.2")
        XCTAssertEqual(restored.buildNumber, "2026110622")
        XCTAssertEqual(restored.submissionVersionCode, 2026110622)
        XCTAssertEqual(restored.system, original.system)
        XCTAssertEqual(restored.deviceModel, original.model)
        XCTAssertEqual(restored.buildRevision, original.revision)
        XCTAssertEqual(restored.buildConfiguration, original.configuration)
        await reopened.send(restored, submit: { report in
            XCTAssertEqual(report.submissionVersion, original.appVersion)
            XCTAssertEqual(report.deviceModel, original.model)
            return 321
        })
        let delivered = try XCTUnwrap(FeedbackReports(folder: folder, defaults: defaults).reports.first)
        XCTAssertEqual(delivered.remoteID, 321)
        XCTAssertEqual(delivered.version, original.version)
        XCTAssertEqual(delivered.buildRevision, original.revision)
    }

    func testOldReportsRetainKnownVersionWithoutInventingMissingMetadataAfterRelaunch() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let defaults = try XCTUnwrap(UserDefaults(suiteName: UUID().uuidString))
        for (id, version) in [(1, Optional("1.0")), (2, nil), (3, Optional("1.2 (345, debug)"))] {
            var object: [String: Any] = ["id": id, "kind": "bug", "description": "Synthetic legacy", "noAutofix": false, "createdAt": 100]
            object["version"] = version
            try JSONSerialization.data(withJSONObject: object).write(to: folder.appendingPathComponent("\(id).json"))
        }
        let reopened = FeedbackReports(folder: folder, defaults: defaults)
        XCTAssertEqual(reopened.reports.count, 3)
        for report in reopened.reports {
            XCTAssertNil(report.appVersion); XCTAssertNil(report.buildNumber); XCTAssertNil(report.submissionVersionCode)
            XCTAssertNil(report.deviceModel); XCTAssertNil(report.system)
            XCTAssertNil(report.buildRevision); XCTAssertNil(report.buildConfiguration)
            XCTAssertEqual(report.submissionVersion, report.id == 1 ? "1.0" : "")
            await reopened.send(report, submit: { retry in
                XCTAssertEqual(retry.submissionVersion, report.id == 1 ? "1.0" : "")
                XCTAssertNil(retry.deviceModel); XCTAssertNil(retry.buildRevision)
                return report.id + 100
            })
        }
        let reloaded = FeedbackReports(folder: folder, defaults: defaults)
        XCTAssertTrue(reloaded.reports.allSatisfy { $0.remoteID != nil && $0.deviceModel == nil && $0.buildRevision == nil })
    }

    func testVersionCodeKeepsDateBasedBuildAndRejectsUnsafeOrNonintegerBuilds() {
        var report = FeedbackReport(id: 1, kind: .bug, description: "Synthetic", noAutofix: false, createdAt: 1)
        for (build, expected): (String, Int64?) in [("2026110622", 2026110622), ("9007199254740991", 9007199254740991),
                                                    ("9007199254740992", nil), ("123.4", nil), ("-1", nil), ("", nil)] {
            report.buildNumber = build
            XCTAssertEqual(report.submissionVersionCode, expected)
        }
    }

    func testSaveAcknowledgementDistinguishesPrivatePendingAndDeliveredReports() {
        var report = FeedbackReport(id: 1, kind: .bug, description: "Test", noAutofix: false,
                                    createdAt: 10, localOnly: true)
        XCTAssertTrue(report.saveAcknowledgement.contains("Sending reports to the Bridgit team was turned off"))
        report.localOnly = false
        XCTAssertTrue(report.saveAcknowledgement.contains("waiting to send"))
        report.remoteID = 42
        XCTAssertEqual(report.saveAcknowledgement, "Thanks. It’s report M0042, and the Bridgit team has it.")
        XCTAssertEqual(report.reference, "M0042")
        report.remoteID = 12345
        XCTAssertEqual(report.reference, "M12345")
    }

    func testActivityReportPreservesSourceAndHonorsLogChoiceOnDisk() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = try AppletStore(directory: folder.appendingPathComponent("activity"))
        try await store.setTransferEnabled(.downloads, enabled: true)
        let events = try await store.hostActivity()
        let event = try XCTUnwrap(events.first)
        let reportsFolder = folder.appendingPathComponent("reports")
        let reports = FeedbackReports(folder: reportsFolder)
        for includeLog in [true, false] {
            let saved = try reports.save(kind: .bug, text: "Test", autoFix: false, images: [], log: [],
                                         localOnly: true, sourceScreen: "Activity", logEntryID: event.id,
                                         attachedActivity: includeLog ? [event] : nil)
            let reloaded = try XCTUnwrap(FeedbackReports(folder: reportsFolder).reports.first { $0.id == saved.id })
            XCTAssertEqual(reloaded.sourceScreen, "Activity")
            XCTAssertEqual(reloaded.logEntryID, event.id)
            XCTAssertEqual(reloaded.attachedActivity?.first?.operation, includeLog ? event.operation : nil)
            XCTAssertTrue(reloaded.log?.isEmpty == true)
        }
    }

    func testTurningSendingOffKeepsPendingReportsPrivateAfterRestartAndReenable() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let defaults = try XCTUnwrap(UserDefaults(suiteName: UUID().uuidString))
        let store = FeedbackReports(folder: folder, defaults: defaults)
        let queued = try store.save(kind: .bug, text: "Queued", autoFix: false, images: [], log: [],
                                    localOnly: false, sourceScreen: "More")
        try store.setSendingEnabled(false)
        XCTAssertTrue(defaults.bool(forKey: "feedback.uploadOff"))
        let reopened = FeedbackReports(folder: folder, defaults: defaults)
        try reopened.setSendingEnabled(true)
        XCTAssertFalse(defaults.bool(forKey: "feedback.uploadOff"))
        XCTAssertEqual(reopened.reports.first?.localOnly, true)
        XCTAssertEqual(reopened.reports.first?.id, queued.id)
        await reopened.send(queued) { _ in XCTFail("A stale queued copy must never upload"); return 9 }
        let fresh = try reopened.save(kind: .bug, text: "New", autoFix: true, images: [], log: [],
                                      localOnly: false, sourceScreen: "More")
        await reopened.send(fresh) { item in XCTAssertEqual(item.id, fresh.id); return 42 }
        let afterReceipt = FeedbackReports(folder: folder, defaults: defaults)
        XCTAssertEqual(afterReceipt.reports.first { $0.id == fresh.id }?.remoteID, 42)
        XCTAssertNil(afterReceipt.reports.first { $0.id == queued.id }?.remoteID)
    }

    func testOwnerResolutionKeepsItsRetryIdentityAcrossRestartAndRechecksStatus() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let defaults = try XCTUnwrap(UserDefaults(suiteName: UUID().uuidString))
        for id in 1...3 {
            var fixture: [String: Any] = ["id": id, "kind": "bug", "description": "Test", "createdAt": 20,
                                          "noAutofix": false, "remoteID": id + 10, "localOnly": id == 2,
                                          "moves": [["status": "fixed", "at": 30]]]
            if id == 2 { fixture.removeValue(forKey: "remoteID") }
            try JSONSerialization.data(withJSONObject: fixture).write(to: folder.appendingPathComponent(String(id) + ".json"))
        }
        let store = FeedbackReports(folder: folder, defaults: defaults)
        try store.resolve(1, confirmed: true)
        XCTAssertThrowsError(try store.resolve(1, confirmed: false))
        try store.resolve(2, confirmed: false)
        defaults.set(true, forKey: "feedback.uploadOff")
        try store.resolve(3, confirmed: false)
        await store.sendPendingResolutions { _, _, _, _ in XCTFail("Uploads disabled") }
        defaults.set(false, forKey: "feedback.uploadOff")
        var firstID: Int64?
        await store.sendPendingResolutions { remote, key, status, _ in
            XCTAssertEqual(remote, 11)
            XCTAssertEqual(status, "confirmed")
            firstID = key
            throw URLError(.timedOut)
        }
        XCTAssertNotNil(firstID)
        let reopened = FeedbackReports(folder: folder, defaults: defaults)
        var sent: [Int64] = []
        await reopened.sendPendingResolutions { _, key, _, _ in sent.append(key) }
        XCTAssertEqual(sent, [try XCTUnwrap(firstID)])
        let readback = FeedbackReports(folder: folder, defaults: defaults)
        XCTAssertEqual(readback.reports.first { $0.id == 2 }?.status, "reopened")
        XCTAssertEqual(readback.reports.first { $0.id == 3 }?.countsAsFixed, false)
        XCTAssertEqual(readback.reports.first { $0.id == 1 }?.countsAsFixed, true)
        await readback.sendPendingResolutions { _, _, _, _ in XCTFail("Acknowledged history must not repeat") }
    }
    func testTwoPagesSurviveRestartAndKeepLocalReportPrivate() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        for id in 1...2 {
            var report: [String: Any] = ["id": id, "kind": "bug", "description": "Fixture", "noAutofix": false,
                                         "createdAt": 10, "localOnly": id == 2]
            if id == 1 { report["remoteID"] = 101 }
            try JSONSerialization.data(withJSONObject: report).write(to: folder.appendingPathComponent(String(id) + ".json"))
        }
        let defaults = try XCTUnwrap(UserDefaults(suiteName: UUID().uuidString))
        let store = FeedbackReports(folder: folder, defaults: defaults)
        var observed: [Int64] = []
        await store.refresh { time, id in
            observed.append(time)
            XCTAssertEqual(id, time == 0 ? nil : 7)
            let raw: [String: Any] = [
                "updates": [1, 2].map { ["phone_report_id": $0, "status": time == 0 ? "in_progress" : "fixed", "team_note": "Reviewed"] },
                "cursor": 20, "cursor_id": time == 0 ? 7 : 8, "more": time == 0
            ]
            return try JSONDecoder().decode(FeedbackUpdates.self, from: JSONSerialization.data(withJSONObject: raw))
        }
        XCTAssertEqual(observed, [0, 20])
        let reopened = FeedbackReports(folder: folder, defaults: defaults)
        let sent = try XCTUnwrap(reopened.reports.first { $0.id == 1 })
        XCTAssertEqual(sent.moves?.map(\.status), ["in_progress", "fixed"])
        XCTAssertTrue(sent.countsAsFixed)
        XCTAssertEqual(reopened.reports.first { $0.id == 2 }?.status, "new")
        await reopened.refresh { time, id in
            XCTAssertEqual(time, 20)
            XCTAssertEqual(id, 8)
            return try JSONDecoder().decode(FeedbackUpdates.self, from: Data(#"{"updates":[],"cursor":20,"cursor_id":8,"more":false}"#.utf8))
        }
    }
}


final class ActivityExportDocumentTests: XCTestCase {
    func testBothChosenFormatsAreDeclaredWritableWithExactExtensions() throws {
        XCTAssertTrue(ActivityExportDocument.writableContentTypes.contains(.commaSeparatedText))
        XCTAssertTrue(ActivityExportDocument.writableContentTypes.contains(ActivityExportDocument.jsonlType))
        XCTAssertEqual(UTType.commaSeparatedText.preferredFilenameExtension, "csv")
        XCTAssertEqual(ActivityExportDocument.jsonlType.preferredFilenameExtension, "jsonl")
        XCTAssertEqual(Set(ActivityExportDocument.writableContentTypes), Set(ActivityExportDocument.readableContentTypes))
        let bytes = Data("Synthetic, CSV\r\n".utf8)
        XCTAssertEqual(ActivityExportDocument(data: bytes).data, bytes)
    }
}


final class FeedbackLogPresentationTests: XCTestCase {
    private let locale = Locale(identifier: "en_GB")
    private let warsaw = TimeZone(identifier: "Europe/Warsaw")!

    func testSavedUTCBecomesLocalShortTimeWithFullAccessibleDate() {
        let row = FeedbackLogDisplay(entry: ["time": "2026-11-06T20:31:15Z", "message": "Changed listening", "agent": "Claude"])
        XCTAssertEqual(row.shortTime(locale: locale, timeZone: warsaw), "21:31")
        XCTAssertEqual(row.shortTime(locale: locale, timeZone: TimeZone(secondsFromGMT: 0)!), "20:31")
        let accessible = row.accessibilityLabel(locale: locale, timeZone: warsaw)
        XCTAssertTrue(accessible.contains("Claude, Changed listening"))
        XCTAssertTrue(accessible.contains("2026"))
        XCTAssertTrue(accessible.contains("21:31:15"))
        XCTAssertFalse(accessible.contains("T20:31"))
    }

    func testFractionalAndOffsetISOTimeRemainRealRecordedInstants() {
        let fractional = FeedbackLogDisplay(entry: ["time": "2026-11-06T20:31:15.125Z", "message": "Recorded activity"])
        let offset = FeedbackLogDisplay(entry: ["time": "2026-11-06T21:31:15+01:00", "message": "Recorded activity"])
        XCTAssertNotNil(fractional.date)
        XCTAssertNotNil(offset.date)
        XCTAssertEqual(fractional.date!.timeIntervalSince(offset.date!), 0.125, accuracy: 0.001)
        XCTAssertEqual(fractional.shortTime(locale: locale, timeZone: warsaw), "21:31")
    }

    func testLegacyUnknownTimeIsPreservedWithoutInventingADate() {
        let row = FeedbackLogDisplay(entry: ["time": "older device · time unknown", "message": "Saved entry"])
        XCTAssertNil(row.date)
        XCTAssertEqual(row.shortTime(locale: locale, timeZone: warsaw), "older device · time unknown")
        XCTAssertEqual(row.fullTime(locale: locale, timeZone: warsaw), "older device · time unknown")
        XCTAssertTrue(row.accessibilityLabel(locale: locale, timeZone: warsaw).contains("older device · time unknown"))
    }

    func testMissingFieldsStayExplicitlyUnreported() {
        let row = FeedbackLogDisplay(entry: [:])
        XCTAssertNil(row.date)
        XCTAssertNil(row.agent)
        XCTAssertEqual(row.message, "Message not recorded")
        XCTAssertEqual(row.shortTime(locale: locale, timeZone: warsaw), "Time not recorded")
        XCTAssertEqual(row.accessibilityLabel(locale: locale, timeZone: warsaw), "Bridgit, Message not recorded, Time not recorded")
    }
}

#if os(macOS)
import SwiftUI
import AppKit

@MainActor
final class FeedbackStampSurfaceTests: XCTestCase {
    private struct VisibilityProbe: View {
        @Environment(\.buildStampVisible) private var visible
        let changed: (Bool) -> Void
        var body: some View {
            Color.clear.frame(width: 20, height: 20)
                .onAppear { changed(visible) }
                .onChange(of: visible) { _, value in changed(value) }
        }
    }

    func testInactiveComposerDoesNotEraseActiveHistoryOrDetailCoverage() async {
        let hidden = expectation(description: "Covered ancestor stamp hidden")
        hidden.assertForOverFulfill = false
        let root = VisibilityProbe { if !$0 { hidden.fulfill() } }
            .coveringBuildStampSurface(true) // history or saved detail is open
            .coveringBuildStampSurface(false) // its sibling composer is closed
            .buildStampSurface()
        let host = NSHostingView(rootView: root)
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 100, height: 100),
                              styleMask: [.borderless], backing: .buffered, defer: false)
        window.contentView = host
        host.layoutSubtreeIfNeeded()
        await fulfillment(of: [hidden], timeout: 3)
        window.contentView = nil
    }

    private final class CoverState: ObservableObject { @Published var covered = true }
    private struct ChangingScope: View {
        @ObservedObject var state: CoverState
        let changed: (Bool) -> Void
        var body: some View {
            VisibilityProbe(changed: changed)
                .coveringBuildStampSurface(state.covered)
                .coveringBuildStampSurface(false)
                .buildStampSurface()
        }
    }
    func testDismissedPresentationRestoresTheAncestorSurface() async {
        let hidden = expectation(description: "Ancestor covered")
        let restored = expectation(description: "Ancestor restored after dismissal")
        hidden.assertForOverFulfill = false
        restored.assertForOverFulfill = false
        let state = CoverState()
        var wasHidden = false
        let host = NSHostingView(rootView: ChangingScope(state: state) { visible in
            if !visible { wasHidden = true; hidden.fulfill() }
            else if wasHidden && !state.covered { restored.fulfill() }
        })
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 100, height: 100), styleMask: [.borderless], backing: .buffered, defer: false)
        window.contentView = host
        host.layoutSubtreeIfNeeded()
        await fulfillment(of: [hidden], timeout: 3)
        state.covered = false
        host.layoutSubtreeIfNeeded()
        await fulfillment(of: [restored], timeout: 3)
        window.contentView = nil
    }

    func testNestedActiveSurfaceKeepsItsStampWhileAncestorIsCovered() async {
        let hidden = expectation(description: "Ancestor hidden")
        let active = expectation(description: "Presented surface visible")
        hidden.assertForOverFulfill = false
        active.assertForOverFulfill = false
        let root = VStack {
            VisibilityProbe { if !$0 { hidden.fulfill() } }
            VisibilityProbe { if $0 { active.fulfill() } }.buildStampSurface()
        }
        .coveringBuildStampSurface(true)
        .coveringBuildStampSurface(false)
        .buildStampSurface()
        let host = NSHostingView(rootView: root)
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 100, height: 100),
                              styleMask: [.borderless], backing: .buffered, defer: false)
        window.contentView = host
        host.layoutSubtreeIfNeeded()
        await fulfillment(of: [hidden, active], timeout: 3)
        window.contentView = nil
    }
}
#endif

final class LinkedDeviceMetadataTests: XCTestCase {
    func testAccountMetadataLabelsEveryAppleDeviceBeforeFirstSnapshot() throws {
        for (platform, type, label) in [("macos", "computer", "Mac"), ("ios", "tablet", "iPad"), ("ios", "phone", "iPhone")] {
            let data = Data("{\"phone\":\"fixture\",\"name\":\"Misleading Android nickname\",\"platform\":\"\(platform)\",\"device_type\":\"\(type)\"}".utf8)
            let phone = try JSONDecoder().decode(Phone.self, from: data)
            XCTAssertNil(phone.status)
            XCTAssertEqual(DeviceTypeDescription.label(phone.reportedMetadata), label)
            XCTAssertEqual(PortalDevice.list(phones: [phone], pairings: []).first?.reportedMetadata, phone.reportedMetadata)
        }
#if os(macOS)
        XCTAssertEqual(DeviceTypeDescription.local, .init(platform: "macos", deviceType: "computer"))
#endif
    }

    func testBlankOrMalformedStatusDoesNotEraseAccountMetadata() throws {
        let data = Data(#"{"phone":"fixture","platform":"macos","device_type":"computer","status":{"device":{"platform":" ","device_type":"<bad>"}}}"#.utf8)
        let phone = try JSONDecoder().decode(Phone.self, from: data)
        XCTAssertEqual(phone.reportedMetadata, .init(platform: "macos", deviceType: "computer"))
        let authoritative = Data(#"{"phone":"fixture","platform":"macos","device_type":"computer","status":{"device":{"platform":" IOS ","device_type":" TABLET "}}}"#.utf8)
        XCTAssertEqual(try JSONDecoder().decode(Phone.self, from: authoritative).reportedMetadata, .init(platform: "ios", deviceType: "tablet"))
        let unknown = try JSONDecoder().decode(Phone.self, from: Data(#"{"phone":"fixture","name":"iPhone iPad Mac"}"#.utf8))
        XCTAssertEqual(DeviceTypeDescription.label(unknown.reportedMetadata), "Device")
    }
}


final class ApplePermissionHelpTests: XCTestCase {
    func testSevenTopicsPreserveCapabilityAndPlatformBoundaries() {
        XCTAssertEqual(ApplePermissionTopic.hardware, [.camera, .microphone, .photos, .screenshots])
        XCTAssertEqual(ApplePermissionTopic.software, [.notifications, .battery, .updates])
        XCTAssertEqual(ApplePermissionTopic.allCases.filter(\.requestsOSPermission), [.camera, .microphone, .notifications])
        for platform in [ApplePermissionPlatform.iOS, .macOS] {
            for topic in ApplePermissionTopic.allCases {
                XCTAssertFalse(topic.steps(platform).isEmpty)
                XCTAssertTrue(topic.steps(platform).allSatisfy { !$0.isEmpty })
            }
            XCTAssertTrue(ApplePermissionTopic.photos.steps(platform).joined().contains("gallery search and library fetch are not implemented"))
            XCTAssertEqual(ApplePermissionTopic.screenshots.status(platform), "Not implemented")
            XCTAssertTrue(ApplePermissionTopic.notifications.steps(platform).joined().contains("not implemented"))
        }
        XCTAssertEqual(ApplePermissionTopic.updates.status(.iOS), "TestFlight")
        XCTAssertTrue(ApplePermissionTopic.updates.steps(.macOS).joined().contains("Check for updates"))
        XCTAssertTrue(ApplePermissionTopic.battery.steps(.iOS).joined().contains("limited background reads only"))
        XCTAssertTrue(ApplePermissionTopic.battery.steps(.macOS).joined().contains("sleep makes it unavailable"))
    }

    func testGrantAndRecoveryAreLimitedToPresentUndecidedSensors() {
        XCTAssertEqual(HardwareSensor.camera.mediaType, AVMediaType.video)
        XCTAssertEqual(HardwareSensor.microphone.mediaType, AVMediaType.audio)
        XCTAssertEqual(HardwareSensor.allCases.count, 2)
        XCTAssertEqual(HardwareAccessPolicy.action(status: .notDetermined, present: true), .request)
        XCTAssertEqual(HardwareAccessPolicy.action(status: .denied, present: true), .settings)
        for status in [AVAuthorizationStatus.authorized, .restricted] {
            XCTAssertEqual(HardwareAccessPolicy.action(status: status, present: true), .none)
        }
        for status in [AVAuthorizationStatus.notDetermined, .denied, .authorized, .restricted] {
            XCTAssertEqual(HardwareAccessPolicy.action(status: status, present: false), .none)
        }
    }

    func testMoreSearchDiscoversBothPermissionDestinations() {
        XCTAssertTrue(MoreEntry.hardware.matches("camera permissions"))
        XCTAssertTrue(MoreEntry.hardware.matches("photos screenshots"))
        XCTAssertTrue(MoreEntry.software.matches("software access"))
        XCTAssertTrue(MoreEntry.software.matches("notifications battery"))
        XCTAssertFalse(MoreEntry.hardware.matches("notifications"))
        XCTAssertFalse(MoreEntry.software.matches("camera"))
    }
}

@MainActor
final class FirstRunControllerTests: XCTestCase {
    private func controller(owner: Bool = false) -> FirstRunController {
        let name = "FirstRunControllerTests." + UUID().uuidString
        let defaults = UserDefaults(suiteName: name)!
        addTeardownBlock { defaults.removePersistentDomain(forName: name) }
        return FirstRunController(defaults: defaults, hasOwnerData: owner)
    }
    func testIncomingPairingYieldsWithoutCompletingOrChangingThePairing() throws {
        let controller = controller()
        let url = try XCTUnwrap(URL(string: "bridgit://pair#v=1&relay=https%3A%2F%2Fr.example&device=dev_ABCDEFGH12&token=rly_0123456789abcdef0123&name=Fixture"))
        let before = Pairing.parse(link: url)
        XCTAssertNotNil(before)
        controller.receive(url, platform: "ios")
        XCTAssertFalse(controller.isPresented)
        XCTAssertTrue(controller.progress.isRequired)
        XCTAssertNil(controller.progress.completedAction)
        XCTAssertEqual(Pairing.parse(link: url)?.deviceId, before?.deviceId)
    }
    func testUnknownLinksDoNotDismissIntroductionAndLocalLinkDoesNotFinish() {
        let controller = controller()
        controller.receive(URL(string: "https://example.invalid/")!, platform: "ios")
        XCTAssertTrue(controller.isPresented)
        controller.receive(URL(string: "bridgit://device")!, platform: "ios")
        XCTAssertFalse(controller.isPresented); XCTAssertNil(controller.progress.completedAction)
    }
    func testReturningAndExplicitFixtureRootsNeverClaimCompletion() {
        let returning = controller(owner: true)
        XCTAssertFalse(returning.isPresented); XCTAssertNil(returning.progress.completedAction)
        let fixture = FirstRunController.inactive()
        XCTAssertFalse(fixture.isPresented); XCTAssertNil(fixture.progress.completedAction)
    }
    func testSavedLocalAuthorityBypassesWithoutCompletingOrStartingAHost() async throws {
        let controller = controller()
        controller.recognizeSavedAgents([])
        XCTAssertTrue(controller.isPresented)
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory)
        let authority = HostAuthority(store: store, deviceID: "first-run-offline-fixture")
        _ = try await authority.create(name: "Existing local control", operations: [.deviceRead], applets: [])
        controller.recognizeSavedAgents(try await authority.list())
        XCTAssertFalse(controller.isPresented)
        XCTAssertTrue(controller.progress.returningInstall)
        XCTAssertNil(controller.progress.completedAction)
    }
    func testNavigationAndWarningChoiceAreSeparate() {
        let controller = controller()
        XCTAssertFalse(controller.finish(.diagnostic))
        controller.next(); controller.next()
        XCTAssertEqual(controller.progress.step, .privacy)
        controller.back(); XCTAssertEqual(controller.progress.step, .capabilities)
        controller.skip(); XCTAssertTrue(controller.isPresented)
        XCTAssertTrue(controller.finish(.addControlAgent))
        XCTAssertFalse(controller.isPresented)
        XCTAssertEqual(controller.progress.completedAction, .addControlAgent)
    }
}

private final class PairingClaimFixture: URLProtocol {
    final class Server {
        let lock = NSLock()
        var requests: [URLRequest] = []
        var affirmative = true
        var status = 200
        var hold = false
        var onRequest: (() -> Void)?
        func record(_ request: URLRequest) { lock.lock(); requests.append(request); lock.unlock(); onRequest?() }
        var recorded: [URLRequest] { lock.lock(); defer { lock.unlock() }; return requests }
    }
    static var server: Server?
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "pairing-fixture.invalid" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        guard let server = Self.server else { client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse)); return }
        var captured = request
        if captured.httpBody == nil, let stream = captured.httpBodyStream {
            stream.open(); defer { stream.close() }
            var bytes = Data(), buffer = [UInt8](repeating: 0, count: 4096)
            while true {
                let count = stream.read(&buffer, maxLength: buffer.count)
                if count <= 0 { break }
                bytes.append(contentsOf: buffer.prefix(count))
            }
            captured.httpBody = bytes
        }
        server.record(captured)
        guard !server.hold else { return }
        let payload: [String: Any] = ["response": ["status": server.status, "body": ["claimed": server.affirmative]]]
        let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: try! JSONSerialization.data(withJSONObject: payload))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

@MainActor
final class PairingImportFlowTests: XCTestCase {
    private let link = "bridgitportal://pair#v=1&relay=https%3A%2F%2Fpairing-fixture.invalid&device=dev_fixture_123&token=rly_fixture_0123456789abcdef&name=Fixture%20Host&portal=1&claim=1"
    private func setup() -> (PairingImportFlow, PairingClaimFixture.Server, URLSession) {
        let server = PairingClaimFixture.Server(); PairingClaimFixture.server = server
        let configuration = URLSessionConfiguration.ephemeral; configuration.protocolClasses = [PairingClaimFixture.self]
        let session = URLSession(configuration: configuration)
        addTeardownBlock { session.invalidateAndCancel(); PairingClaimFixture.server = nil }
        return (PairingImportFlow(), server, session)
    }
    private func finish(_ flow: PairingImportFlow) async throws {
        for _ in 0..<200 where flow.busy { try await Task.sleep(for: .milliseconds(10)) }
        XCTAssertFalse(flow.busy)
    }
    func testReviewAndCancelNeverClaimOrSave() {
        let (flow, server, _) = setup()
        XCTAssertTrue(flow.review(link))
        XCTAssertTrue(server.recorded.isEmpty)
        XCTAssertFalse(flow.saved)
        flow.reset()
        XCTAssertNil(flow.candidate); XCTAssertTrue(server.recorded.isEmpty)
        XCTAssertFalse(flow.review("bridgitportal://signin#session=not-an-account"))
    }
    func testOwnerConfirmedClaimUsesRealClientAndOnlyPersistsAfterAffirmativeReply() async throws {
        let (flow, server, session) = setup()
        XCTAssertTrue(flow.review(link))
        var saved: Pairing?
        flow.confirm(http: session) { value in
            XCTAssertEqual(server.recorded.count, 1)
            XCTAssertNil(value.needsClaim)
            saved = value
        }
        try await finish(flow)
        XCTAssertTrue(flow.saved)
        let request = try XCTUnwrap(server.recorded.first)
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.url?.path, "/d/dev_fixture_123/jobs")
        let envelope = try XCTUnwrap(try JSONSerialization.jsonObject(with: XCTUnwrap(request.httpBody)) as? [String: Any])
        let call = try XCTUnwrap(envelope["request"] as? [String: Any])
        XCTAssertEqual(call["path"] as? String, "/v1/portal/claim")
        XCTAssertEqual(call["method"] as? String, "POST")
        let body = try XCTUnwrap(call["body"] as? [String: Any])
        XCTAssertEqual(Set(body.keys), ["token_hash"])
        XCTAssertEqual(body["token_hash"] as? String, PairingClaim.tokenHash(try XCTUnwrap(saved).token))
        XCTAssertNotEqual(PairingClaim.tokenHash(try XCTUnwrap(saved).token), PairingClaim.tokenHash(try XCTUnwrap(Pairing.parse(link)).token))
    }
    func testRefusedOrUnconfirmedClaimNeverPersists() async throws {
        for (status, affirmative) in [(409, false), (410, false), (200, false)] {
            let (flow, server, session) = setup(); server.status = status; server.affirmative = affirmative
            XCTAssertTrue(flow.review(link)); var saves = 0
            flow.confirm(http: session) { _ in saves += 1 }
            try await finish(flow)
            XCTAssertFalse(flow.saved); XCTAssertEqual(saves, 0); XCTAssertNotNil(flow.problem)
        }
    }
    func testPersistenceRetryKeepsAcknowledgedReplacementWithoutClaimingAgain() async throws {
        let (flow, server, session) = setup()
        XCTAssertTrue(flow.review(link))
        var acknowledgedHash: String?
        flow.confirm(http: session) { value in
            acknowledgedHash = PairingClaim.tokenHash(value.token)
            throw DeviceAPIError(503, "fixture_keychain_unavailable", "Fixture persistence failure")
        }
        try await finish(flow)
        XCTAssertFalse(flow.saved); XCTAssertNotNil(flow.problem)
        flow.confirm(http: session) { value in XCTAssertEqual(PairingClaim.tokenHash(value.token), acknowledgedHash) }
        try await finish(flow)
        XCTAssertTrue(flow.saved); XCTAssertEqual(server.recorded.count, 1)
    }
    func testCancellationBeforeAcknowledgementNeverSaves() async throws {
        let (flow, server, session) = setup(); server.hold = true
        let sent = expectation(description: "actual client sent claim"); server.onRequest = { sent.fulfill() }
        XCTAssertTrue(flow.review(link)); var saves = 0
        flow.confirm(http: session) { _ in saves += 1 }
        await fulfillment(of: [sent], timeout: 2)
        flow.reset(); try await Task.sleep(for: .milliseconds(50))
        XCTAssertEqual(saves, 0); XCTAssertFalse(flow.saved); XCTAssertFalse(flow.busy); XCTAssertNil(flow.candidate)
    }
}

final class PairingQRImageTests: XCTestCase {
    private func image(_ message: String) throws -> Data {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(message.utf8)
        let output = try XCTUnwrap(filter.outputImage).transformed(by: CGAffineTransform(scaleX: 6, y: 6))
        return try XCTUnwrap(CIContext().pngRepresentation(of: output, format: .RGBA8, colorSpace: CGColorSpaceCreateDeviceRGB()))
    }
    func testRealVisionDecoderFindsOnlyPairingDataInLocalImage() throws {
        let link = "bridgitportal://pair#v=1&relay=https%3A%2F%2Fpairing-fixture.invalid&device=dev_fixture_123&token=rly_fixture_0123456789abcdef&portal=1&claim=1"
        XCTAssertEqual(try PairingQRImage.read(image(link)), link)
        XCTAssertThrowsError(try PairingQRImage.read(image("bridgitportal://signin#session=not-an-account")))
        XCTAssertThrowsError(try PairingQRImage.read(Data("not an image".utf8)))
    }
    func testActualQRDecodeKeepsSatelliteAndViewerInputsSeparate() throws {
        let link = "bridgit://satellite#v=1&host=100.80.1.2&port=18471&claim=sct_" + String(repeating: "a", count: 43) + "&name=Fixture"
        let bytes = try image(link)
        XCTAssertEqual(try PairingQRImage.read(bytes, satellite: true), link)
        XCTAssertThrowsError(try PairingQRImage.read(bytes))
    }
    func testOversizedImageIsRejectedBeforeDecode() {
        XCTAssertThrowsError(try PairingQRImage.read(Data(repeating: 0, count: 20 * 1024 * 1024 + 1))) { error in
            guard case PairingQRImage.Failure.tooLarge = error else { return XCTFail("Expected size limit") }
        }
    }
}

@MainActor final class FeedbackAgentAPITests: XCTestCase {
    func testAuthenticatedReporterAndFixerShareActualLocalReportStore() async {
        do { try await replayAgentFeedbackLifecycle() }
        catch { XCTFail("Feedback lifecycle threw: " + error.localizedDescription) }
    }

    private func replayAgentFeedbackLifecycle() async throws {
        print("Feedback fixture: preparing")
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let activity = try AppletStore(directory: dir)
        let domain = "bridgit.fixture.feedback." + UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: domain))
        defaults.set(true, forKey: "feedback.uploadOff")
        let reportsFolder = dir.appendingPathComponent("feedback")
        let reports = FeedbackReports(folder: reportsFolder, defaults: defaults)
        addTeardownBlock {
            #if DEBUG
            try await activity.closeTemporaryFixture(at: dir)
            try FileManager.default.removeItem(at: dir)
            #endif
            UserDefaults(suiteName: domain)?.removePersistentDomain(forName: domain)
        }
        let owner = try reports.save(kind: .bug, text: "Private owner report", autoFix: true, images: [],
                                     log: [["message": "Private owner Activity"]], localOnly: true, sourceScreen: "Fixture")
        let authority = HostAuthority(store: activity, deviceID: "fixture")
        await authority.setState(enabled: true, awake: true, foreground: true)
        let (reporter, _) = try await authority.create(name: "Authenticated reporter", operations: [.deviceRead], applets: [])
        let (other, _) = try await authority.create(name: "Other", operations: [.deviceRead], applets: [])
        let (fixer, _) = try await authority.create(name: "Fixer", operations: [.deviceRead, .feedbackFixer], applets: [])
        let dispatcher = HostDispatcher(authority: authority, store: activity, engine: AppletEngine(store: activity) { _, _, _ in [:] },
            profile: { _ in throw CancellationError() }, feedback: { request, agent in
                try await reports.agentRequest(request, agent: agent, authority: authority, activity: activity)
            })
        var sequence: Int64 = 0
        func call(_ agent: HostAgent, _ method: String, _ path: String, query: [String: Any] = [:],
                  body: [String: Any] = [:], key: String? = nil) async -> HostResponse {
            sequence += 1
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            return await dispatcher.dispatch(.init(id: UUID().uuidString, sequence: sequence, agentID: agent.id,
                generation: agent.generation, deviceID: "fixture", deadline: now + 30_000, createdAt: now,
                request: .init(method: method, path: path, query: query, body: body, idempotencyKey: key)))
        }
        func object(_ response: HostResponse, status: Int) throws -> [String: Any] {
            XCTAssertEqual(response.status, status)
            return try XCTUnwrap(JSONSerialization.jsonObject(with: response.data) as? [String: Any])
        }
        let body: [String: Any] = ["kind": "feature", "description": "Synthetic issue",
                                  "agent_info": ["name": "Untrusted name"], "log": [["message": "untrusted log"]]]
        print("Feedback fixture: filing")
        let filed = await call(reporter, "POST", "/v1/feedback", body: body, key: "fixture-create")
        let created = try object(filed, status: 201)
        let id = try XCTUnwrap(created["id"] as? Int64)
        XCTAssertEqual(created["no_autofix"] as? Bool, true)
        XCTAssertEqual(created["sent_to_team"] as? String, "off")
        let retry = await call(reporter, "POST", "/v1/feedback", body: body, key: "fixture-create")
        XCTAssertEqual(retry.data, filed.data)
        XCTAssertEqual(reports.reports.count, 2)

        let ownPath = "/v1/feedback/\(id)"
        print("Feedback fixture: reading owner scope")
        let ownRead = await call(reporter, "GET", ownPath)
        print("Feedback fixture: own detail returned", ownRead.status)
        let own = try object(ownRead, status: 200)
        XCTAssertEqual(own["filed_by"] as? String, reporter.name)
        XCTAssertEqual(own["description"] as? String, "Synthetic issue")
        for field in ["context", "images", "log", "allowed_next"] { XCTAssertNil(own[field]) }
        print("Feedback fixture: requesting foreign detail")
        let foreign = await call(other, "GET", ownPath)
        print("Feedback fixture: foreign detail returned", foreign.status)
        XCTAssertEqual(foreign.status, 404)
        print("Feedback fixture: requesting owner detail as ordinary agent")
        let foreignOwner = await call(reporter, "GET", "/v1/feedback/\(owner.id)")
        print("Feedback fixture: owner detail returned", foreignOwner.status)
        XCTAssertEqual(foreignOwner.status, 404)
        print("Feedback fixture: requesting own list")
        let ownList = await call(reporter, "GET", "/v1/feedback", query: ["kind": "feature", "limit": 1])
        print("Feedback fixture: own list returned", ownList.status)
        XCTAssertEqual((try object(ownList, status: 200)["reports"] as? [[String: Any]])?.count, 1)

        print("Feedback fixture: reading Fixer scope")
        let fixerRead = await call(fixer, "GET", ownPath)
        let detail = try object(fixerRead, status: 200)
        XCTAssertEqual(detail["status"] as? String, "seen")
        let context = try XCTUnwrap(detail["context"] as? [String: Any])
        XCTAssertEqual((context["agent"] as? [String: Any])?["id"] as? String, reporter.id)
        XCTAssertFalse(String(decoding: fixerRead.data, as: UTF8.self).contains("untrusted log"))
        XCTAssertEqual(detail["allowed_next"] as? [String], ["in_progress", "wont_fix", "duplicate"])
        let fixerOwner = await call(fixer, "GET", "/v1/feedback/\(owner.id)")
        XCTAssertTrue(String(decoding: fixerOwner.data, as: UTF8.self).contains("Private owner Activity"))
        print("Feedback fixture: moving status")
        let illegal = await call(fixer, "POST", ownPath + "/status", body: ["status": "fixed"], key: "fixture-illegal")
        XCTAssertEqual(illegal.status, 422)
        let progress = await call(fixer, "POST", ownPath + "/status", body: ["status": "in_progress"], key: "fixture-progress")
        XCTAssertEqual(progress.status, 200)
        let fixed = await call(fixer, "POST", ownPath + "/status", body: ["status": "fixed"], key: "fixture-fixed")
        XCTAssertEqual(fixed.status, 200)
        let wrongReporter = await call(fixer, "POST", ownPath + "/followup", body: ["outcome": "works"], key: "fixture-other")
        XCTAssertEqual(wrongReporter.status, 403)
        let reopen = await call(reporter, "POST", ownPath + "/followup",
                                body: ["outcome": "still_broken", "note": "Still reproduced"], key: "fixture-reopen")
        XCTAssertEqual(try object(reopen, status: 200)["status"] as? String, "reopened")
        print("Feedback fixture: reopening store")
        let saved = FeedbackReports(folder: reportsFolder, defaults: defaults)
        let reopened = try XCTUnwrap(saved.reports.first { $0.id == id })
        XCTAssertEqual(reopened.filingAgentID, reporter.id)
        XCTAssertEqual(reopened.status, "reopened")
        XCTAssertEqual(reopened.moves?.last?.actor, .reporter)
        XCTAssertEqual(reopened.moves?.last?.note, "Still reproduced")
        XCTAssertEqual(reopened.localOnly, true)
        print("Feedback fixture: assertions complete")
    }
}

private final class FeedbackOutboxHTTPFixture: URLProtocol {
    private static let lock = NSLock()
    private static var requests: [URLRequest] = []
    static func reset() { lock.withLock { requests.removeAll() } }
    static func captured() -> [URLRequest] { lock.withLock { requests } }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func stopLoading() {}
    override func startLoading() {
        var observed = request
        if observed.httpBody == nil, let stream = observed.httpBodyStream {
            stream.open()
            defer { stream.close() }
            var bytes = [UInt8](repeating: 0, count: 4096)
            var data = Data()
            while true {
                let count = stream.read(&bytes, maxLength: bytes.count)
                if count <= 0 { break }
                data.append(contentsOf: bytes.prefix(count))
            }
            observed.httpBody = data
        }
        Self.lock.withLock { Self.requests.append(observed) }
        guard let url = request.url else { client?.urlProtocol(self, didFailWithError: URLError(.badURL)); return }
        let response: [String: Any]
        switch url.path {
        case "/v1/install/challenge": response = ["challenge": "fixture-only"]
        case "/v1/install/register": response = ["install_id": request.value(forHTTPHeaderField: "X-Bridgit-Install") ?? ""]
        case "/v1/reports": response = ["id": 123]
        case "/v1/reports/123/history": response = ["id": 456]
        default: client?.urlProtocol(self, didFailWithError: URLError(.unsupportedURL)); return
        }
        guard let reply = HTTPURLResponse(url: url, statusCode: 200, httpVersion: nil, headerFields: ["Content-Type": "application/json"]),
              let data = try? JSONSerialization.data(withJSONObject: response) else {
            client?.urlProtocol(self, didFailWithError: URLError(.cannotParseResponse)); return
        }
        client?.urlProtocol(self, didReceive: reply, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data)
        client?.urlProtocolDidFinishLoading(self)
    }
}

extension FeedbackAgentAPITests {
    func testNativeTypedHistoryOutboxUsesSignedClientAndClearsDeliveryReceipt() async {
        do { try await replayTypedOutbox() }
        catch { XCTFail("Feedback outbox fixture threw: " + error.localizedDescription) }
    }

    private func replayTypedOutbox() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let activity = try AppletStore(directory: directory)
        let domain = "bridgit.feedback-outbox-fixture." + UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: domain))
        addTeardownBlock {
            #if DEBUG
            try await activity.closeTemporaryFixture(at: directory)
            try FileManager.default.removeItem(at: directory)
            #endif
            UserDefaults(suiteName: domain)?.removePersistentDomain(forName: domain)
        }
        FeedbackOutboxHTTPFixture.reset()
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [FeedbackOutboxHTTPFixture.self]
        let session = URLSession(configuration: configuration)
        defer { session.invalidateAndCancel() }
        let client = InstallClient(signer: try .ephemeral(), base: URL(string: "https://offline.invalid")!, http: session)
        let authority = HostAuthority(store: activity, deviceID: "outbox-fixture")
        await authority.setState(enabled: true, awake: true, foreground: true)
        let (agent, _) = try await authority.create(name: "Fixture Fixer", operations: [.deviceRead, .feedbackFixer], applets: [])
        let folder = directory.appendingPathComponent("feedback")
        let reports = FeedbackReports(folder: folder, defaults: defaults, clientFactory: { client })
        let report = try reports.save(kind: .bug, text: "Owner fixture", autoFix: true, images: [],
                                      log: [], localOnly: false, sourceScreen: "Test")
        let move = HostRequest(method: "POST", path: "/v1/feedback/\(report.id)/status", body: [
            "status": "in_progress", "note": "Fixture team history"
        ])
        _ = try await reports.agentRequest(move, agent: agent, authority: authority, activity: activity)
        let pending = try XCTUnwrap(reports.reports.first?.moves?.last?.pendingID)
        await reports.send(report)
        for _ in 0..<100 {
            if reports.reports.first?.moves?.last?.pendingID == nil { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        let request = try XCTUnwrap(FeedbackOutboxHTTPFixture.captured().first { $0.url?.path == "/v1/reports/123/history" })
        XCTAssertNotNil(request.value(forHTTPHeaderField: "X-Bridgit-Signature"))
        let sent = try XCTUnwrap(JSONSerialization.jsonObject(with: XCTUnwrap(request.httpBody)) as? [String: Any])
        XCTAssertEqual(sent["phone_history_id"] as? Int64, pending)
        XCTAssertEqual(sent["kind"] as? String, "status")
        XCTAssertEqual(sent["actor"] as? String, "developer")
        XCTAssertEqual(sent["by"] as? String, agent.name)
        XCTAssertEqual(sent["note"] as? String, "Fixture team history")
        let reloaded = FeedbackReports(folder: folder, defaults: defaults, clientFactory: { client })
        XCTAssertEqual(reloaded.reports.first?.remoteID, 123)
        XCTAssertNil(reloaded.reports.first?.moves?.last?.pendingID)
        XCTAssertEqual(reloaded.reports.first?.moves?.last?.note, "Fixture team history")
    }
}

@MainActor private final class FeedbackTeamKeyFixture {
    var cache: [String: String] = [:]
    var failCacheWrite = false
    var minted = 0
    var revoked: [String] = []
    var canUpload = true
    var holdNextMint = false
    private var continuation: CheckedContinuation<Void, Never>?

    func mint(_ agent: HostAgent, check: @escaping FeedbackTeamKeys.Check) async throws -> FeedbackTeamKeys.Credential {
        try await check()
        minted += 1
        if holdNextMint { await withCheckedContinuation { continuation = $0 } }
        let marker = minted == 1 ? "x" : "y"
        let fixture: [String: String] = ["key": "bgk_" + String(repeating: marker, count: 43), "agentID": agent.id,
            "role": "reporter", "baseURL": "https://offline.invalid", "docs": "https://offline.invalid/v1/agent"]
        return try JSONDecoder().decode(FeedbackTeamKeys.Credential.self, from: JSONEncoder().encode(fixture))
    }

    func release() {
        holdNextMint = false
        continuation?.resume()
        continuation = nil
    }
}

extension FeedbackAgentAPITests {
    func testTeamKeyCacheAndRetirementHonorUploadAndCurrentFixerAccess() async {
        do { try await replayTeamKeyLifecycle() }
        catch { XCTFail("Feedback key fixture threw: " + error.localizedDescription) }
    }

    private func replayTeamKeyLifecycle() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let activity = try AppletStore(directory: directory)
        addTeardownBlock {
            #if DEBUG
            try await activity.closeTemporaryFixture(at: directory)
            try FileManager.default.removeItem(at: directory)
            #endif
        }
        let fixture = FeedbackTeamKeyFixture()
        let keys = FeedbackTeamKeys(folder: directory, read: { fixture.cache[$0] }, save: { value, key in
            if fixture.failCacheWrite, value != nil { return false }
            fixture.cache[key] = value
            return true
        }, mint: { agent, check in try await fixture.mint(agent, check: check) },
           revoke: { id in fixture.revoked.append(id) })
        let authority = HostAuthority(store: activity, deviceID: "team-fixture")
        await authority.setState(enabled: true, awake: true, foreground: true)
        let (agent, _) = try await authority.create(name: "Fixture Fixer", operations: [.deviceRead, .feedbackFixer], applets: [])
        func authorizeFixer() async throws {
            guard fixture.canUpload else { throw DeviceAPIError(403, "uploads_off", "Fixture uploads are off.") }
            try await authority.authorizeOngoing(agent, operation: .feedbackFixer)
        }
        let initial = try await keys.get(agent, rotate: false, authorize: authorizeFixer)
        let cached = try await keys.get(agent, rotate: false, authorize: authorizeFixer)
        XCTAssertEqual(cached.key, initial.key)
        XCTAssertEqual(fixture.minted, 1)
        fixture.canUpload = false
        do {
            _ = try await keys.get(agent, rotate: false, authorize: authorizeFixer)
            XCTFail("An uploads-off request must not receive a cached key")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "uploads_off") }
        fixture.canUpload = true
        XCTAssertEqual(fixture.minted, 1)

        fixture.holdNextMint = true
        let inFlight = Task { try await keys.get(agent, rotate: true, authorize: authorizeFixer) }
        for _ in 0..<100 {
            if fixture.minted == 2 { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        guard fixture.minted == 2 else {
            fixture.release()
            inFlight.cancel()
            _ = await inFlight.result
            XCTFail("The synthetic rotation did not enter mint.")
            return
        }
        try await authority.setFeedbackFixer(agent.id, enabled: false)
        let current = try await authority.agent(agent.id)
        let reporter = try XCTUnwrap(current)
        keys.reconcile([reporter])
        fixture.release()
        switch await inFlight.result {
        case .success: XCTFail("Revoked Fixer access returned a credential")
        case .failure(let error): XCTAssertEqual((error as? DeviceAPIError)?.status, 403)
        }
        for _ in 0..<100 {
            if fixture.revoked == [agent.id] { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertEqual(fixture.revoked, [agent.id])
        XCTAssertTrue(fixture.cache.isEmpty)
        func authorizeReporter() async throws { try await authority.authorizeOngoing(reporter, operation: .feedback) }
        fixture.failCacheWrite = true
        do {
            _ = try await keys.get(reporter, rotate: false, authorize: authorizeReporter)
            XCTFail("A key that could not be saved must not be delivered")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.status, 503) }
        fixture.failCacheWrite = false
        for _ in 0..<100 {
            if fixture.revoked == [agent.id, agent.id] { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertEqual(fixture.revoked, [agent.id, agent.id])
        let reminted = try await keys.get(reporter, rotate: false, authorize: authorizeReporter)
        XCTAssertEqual(reminted.agentID, agent.id)
        XCTAssertEqual(fixture.minted, 4)
        let persisted = try String(contentsOf: directory.appendingPathComponent("team-key-state"), encoding: .utf8)
        XCTAssertFalse(persisted.contains("bgk_"))
    }
}

extension FeedbackAgentAPITests {
    func testNativeFixerAttachmentSizingPreservesOrResizesActualJPEG() async {
        do { try await replayNativeAttachmentSizing() }
        catch { XCTFail("Feedback attachment fixture threw: " + error.localizedDescription) }
    }

    private func replayNativeAttachmentSizing() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let activity = try AppletStore(directory: directory)
        let domain = "bridgit.feedback-sizing-fixture." + UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: domain))
        defaults.set(true, forKey: "feedback.uploadOff")
        addTeardownBlock {
            #if DEBUG
            try await activity.closeTemporaryFixture(at: directory)
            try FileManager.default.removeItem(at: directory)
            #endif
            UserDefaults(suiteName: domain)?.removePersistentDomain(forName: domain)
        }
        let source = try XCTUnwrap(CGContext(data: nil, width: 1200, height: 750, bitsPerComponent: 8,
            bytesPerRow: 0, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue))
        for stripe in 0..<75 {
            let value = CGFloat(stripe % 9) / 8
            source.setFillColor(red: value, green: 1 - value, blue: 0.5, alpha: 1)
            source.fill(CGRect(x: 0, y: stripe * 10, width: 1200, height: 10))
        }
        let image = try XCTUnwrap(source.makeImage())
        let encoded = NSMutableData()
        let destination = try XCTUnwrap(CGImageDestinationCreateWithData(encoded, UTType.jpeg.identifier as CFString, 1, nil))
        CGImageDestinationAddImage(destination, image, nil)
        XCTAssertTrue(CGImageDestinationFinalize(destination))
        let original = encoded as Data
        let reports = FeedbackReports(folder: directory.appendingPathComponent("feedback"), defaults: defaults)
        let report = try reports.save(kind: .bug, text: "Synthetic attachment", autoFix: true,
                                      images: [original], log: [], localOnly: true, sourceScreen: "Fixture")
        let authority = HostAuthority(store: activity, deviceID: "sizing-fixture")
        await authority.setState(enabled: true, awake: true, foreground: true)
        let (fixer, _) = try await authority.create(name: "Fixture Fixer", operations: [.deviceRead, .feedbackFixer], applets: [])
        let (ordinary, _) = try await authority.create(name: "Fixture reporter", operations: [.deviceRead], applets: [])
        let path = "/v1/feedback/\(report.id)"
        func picture(_ query: [String: Any]) async throws -> (width: Int, bytes: Data) {
            let detail = try await reports.agentRequest(.init(method: "GET", path: path, query: query),
                                                        agent: fixer, authority: authority, activity: activity)
            let body = try XCTUnwrap(JSONSerialization.jsonObject(with: detail.response.data) as? [String: Any])
            let images = try XCTUnwrap(body["images"] as? [[String: Any]])
            let row = try XCTUnwrap(images.first)
            let base64 = try XCTUnwrap(row["data_base64"] as? String)
            return (try XCTUnwrap(row["width_px"] as? Int), try XCTUnwrap(Data(base64Encoded: base64)))
        }
        let unchanged = try await picture(["size": "original", "quality": 40])
        XCTAssertEqual(unchanged.width, 1200)
        XCTAssertEqual(unchanged.bytes, original)
        let resized = try await picture(["size": "original", "max_edge_px": 600])
        XCTAssertEqual(resized.width, 600)
        XCTAssertEqual(resized.bytes, try LocalAppletModel.jpeg(original, maxEdge: 600, quality: 0.90))
        let thumbnail = try await picture(["size": "thumb"])
        XCTAssertEqual(thumbnail.width, 512)
        XCTAssertLessThan(thumbnail.bytes.count, original.count)
        let attachment = HostRequest(method: "POST", path: "/v1/feedback", body: [
            "kind": "bug", "description": "Synthetic reporter attachment",
            "images": [["data_base64": original.base64EncodedString()]]
        ])
        do {
            _ = try await reports.agentRequest(attachment, agent: ordinary, authority: authority, activity: activity)
            XCTFail("Ordinary reporters must not attach supplied images.")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.status, 403) }
        let created = try await reports.agentRequest(attachment, agent: fixer, authority: authority, activity: activity)
        XCTAssertEqual(created.response.status, 201)
        XCTAssertEqual(reports.reports.first?.images?.count, 1)
    }

    func testFeedbackUploadStateKeepsFailureAndThenActualReceipt() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let domain = "bridgit.feedback-delivery-fixture." + UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: domain))
        defer {
            try? FileManager.default.removeItem(at: folder)
            defaults.removePersistentDomain(forName: domain)
        }
        let reports = FeedbackReports(folder: folder, defaults: defaults)
        let report = try reports.save(kind: .bug, text: "Synthetic retry", autoFix: true, images: [],
                                      log: [], localOnly: false, sourceScreen: "Fixture")
        await reports.send(report) { _ in throw URLError(.notConnectedToInternet) }
        let reopened = FeedbackReports(folder: folder, defaults: defaults)
        let failed = try XCTUnwrap(reopened.reports.first)
        XCTAssertEqual(failed.uploadFailed, true)
        XCTAssertNil(failed.remoteID)
        await reopened.send(failed) { _ in 321 }
        let receipt = try XCTUnwrap(FeedbackReports(folder: folder, defaults: defaults).reports.first)
        XCTAssertNil(receipt.uploadFailed)
        XCTAssertEqual(receipt.remoteID, 321)
    }
}
