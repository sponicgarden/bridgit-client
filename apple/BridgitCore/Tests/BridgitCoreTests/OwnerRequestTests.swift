import Foundation
import XCTest
@testable import BridgitCore

final class OwnerRequestTests: XCTestCase {
    private func store() throws -> AppletStore {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: url) }
        return try AppletStore(directory: url)
    }
    func testCaptureNotificationPromptIsTrueOnlyAndDiscoverable() async throws {
        let store = try store(), authority = HostAuthority(store: store, deviceID: "fixture")
        let (writer, _) = try await authority.create(name: "Settings writer", operations: [.configurationWrite], applets: [])
        let field = HostSettingChange.captureNotificationsRequest
        XCTAssertTrue(HostSettingChange.fields.contains(field))
        let action = try HostSettingChange(.init(method: "POST", path: "/v1/settings", body: ["field": field, "value": true]), agent: writer)
        XCTAssertEqual(action.value as? Bool, true)
        for value: Any in [false, 1, "true"] {
            XCTAssertThrowsError(try HostSettingChange(.init(method: "POST", path: "/v1/settings", body: ["field": field, "value": value]), agent: writer))
        }
        XCTAssertThrowsError(try HostSettingChange(.init(method: "DELETE", path: "/v1/settings/" + field), agent: writer))
        let choices = HostSettingChange.schema["oneOf"] as? [[String: Any]] ?? []
        let entry = try XCTUnwrap(choices.first { choice in
            let properties = choice["properties"] as? [String: [String: Any]]
            return properties?["field"]?["const"] as? String == field
        })
        let properties = try XCTUnwrap(entry["properties"] as? [String: [String: Any]])
        XCTAssertEqual(properties["value"]?["const"] as? Bool, true)
        XCTAssertTrue((properties["value"]?["description"] as? String ?? "").contains("owner"))
    }
    func testWiFiHistoryPromptCannotGrantOrResetOSPermission() async throws {
        let store = try store(), authority = HostAuthority(store: store, deviceID: "fixture")
        let (writer, _) = try await authority.create(name: "Settings writer", operations: [.configurationWrite], applets: [])
        let field = HostSettingChange.wifiHistoryPermissionRequest
        XCTAssertTrue(HostSettingChange.fields.contains(field))
        let action = try HostSettingChange(.init(method: "POST", path: "/v1/settings", body: ["field": field, "value": true]), agent: writer)
        XCTAssertEqual(action.value as? Bool, true)
        for value: Any in [false, 1, "true"] {
            XCTAssertThrowsError(try HostSettingChange(.init(method: "POST", path: "/v1/settings", body: ["field": field, "value": value]), agent: writer))
        }
        XCTAssertThrowsError(try HostSettingChange(.init(method: "DELETE", path: "/v1/settings/" + field), agent: writer))
        let choices = HostSettingChange.schema["oneOf"] as? [[String: Any]] ?? []
        let entry = try XCTUnwrap(choices.first { choice in
            let properties = choice["properties"] as? [String: [String: Any]]
            return properties?["field"]?["const"] as? String == field
        })
        let properties = try XCTUnwrap(entry["properties"] as? [String: [String: Any]])
        XCTAssertEqual(properties["value"]?["const"] as? Bool, true)
        XCTAssertTrue((properties["value"]?["description"] as? String ?? "").contains("never grants"))
    }
    func testCrashReportingContractIsOnlyDiscoveredWhenConfiguredAndResetsOn() async throws {
        let authority = HostAuthority(store: try store(), deviceID: "fixture")
        let (agent, _) = try await authority.create(name: "Crash settings writer", operations: [.configurationWrite], applets: [])
        let field = HostSettingChange.crashReportsField
        let change = HostRequest(method: "POST", path: "/v1/settings", body: ["field": field, "value": false])
        XCTAssertThrowsError(try HostSettingChange(change, agent: agent))
        XCTAssertEqual(try HostSettingChange(change, agent: agent, crashReporting: true).value as? Bool, false)
        let reset = HostRequest(method: "DELETE", path: "/v1/settings/" + field)
        XCTAssertThrowsError(try HostSettingChange(reset, agent: agent))
        XCTAssertEqual(try HostSettingChange(reset, agent: agent, crashReporting: true).value as? Bool, true)
        XCTAssertThrowsError(try HostSettingChange(.init(method: "POST", path: "/v1/settings",
            body: ["field": field, "value": 1]), agent: agent, crashReporting: true))

        func containsField(_ object: Any) throws -> Bool {
            let encoded = try JSONSerialization.data(withJSONObject: object)
            return String(decoding: encoded, as: UTF8.self).contains(#""crash_reports""#)
        }
        XCTAssertFalse(try containsField(HostAPIContract.openAPI(agent)))
        XCTAssertFalse(try containsField(HostAPIContract.tools(agent)))
        XCTAssertTrue(try containsField(HostAPIContract.openAPI(agent, crashReporting: true)))
        XCTAssertTrue(try containsField(HostAPIContract.tools(agent, crashReporting: true)))
    }

    func testDeviceNameValidatesRawUTF16AndResetsToSystem() async throws {
        let authority = HostAuthority(store: try store(), deviceID: "fixture")
        let (agent, _) = try await authority.create(name: "Settings writer", operations: [.configurationWrite], applets: [])
        let field = HostSettingChange.deviceNameField
        func change(_ value: Any) throws -> String? {
            try HostSettingChange(.init(method: "POST", path: "/v1/settings",
                body: ["field": field, "value": value]), agent: agent).value as? String
        }

        let limit = String(repeating: "🎈", count: 30)
        XCTAssertEqual(try change(limit), limit)
        XCTAssertThrowsError(try change(limit + " "))
        XCTAssertThrowsError(try change("Lab\n"))
        XCTAssertThrowsError(try change("Lab\u{7f}"))
        XCTAssertThrowsError(try change(true))
        XCTAssertEqual(try change("  Bureau Łódź  "), "Bureau Łódź")
        XCTAssertEqual(try change("   "), "")
        let reset = try HostSettingChange(.init(method: "DELETE", path: "/v1/settings/" + field), agent: agent)
        XCTAssertEqual(reset.value as? String, "")
        XCTAssertEqual(HostDeviceName.effective(override: "", systemName: " Apple\n Device ", fallback: "This device"), "Apple Device")
        XCTAssertEqual(HostDeviceName.effective(override: "Lab", systemName: "System", fallback: "This device"), "Lab")
        XCTAssertEqual(HostDeviceName.effective(override: "", systemName: " ", fallback: "This device"), "This device")
        XCTAssertEqual(HostDeviceName.effective(override: "", systemName: "X" + limit, fallback: "This device"),
                       "X" + String(repeating: "🎈", count: 29))
    }

    func testCallerIsolationCancellationAndExactOwnerOnlyTransition() async throws {
        let store = try store()
        let request = try await store.createOwnerRequest(owner: "a:g", sender: "Fixture", summary: "Host camera: On", payload: ["field": "host_camera", "value": true])
        let id = request["request_id"] as! String
        XCTAssertNil(request["payload"])
        do { _ = try await store.ownerRequest(owner: "b:g", id: id); XCTFail("Cross-caller read") } catch {}
        do { _ = try await store.cancelOwnerRequest(owner: "a:other-generation", id: id); XCTFail("Cross-generation cancellation") } catch {}
        let approved = try await store.beginOwnerRequest(id: id)
        XCTAssertEqual(approved.owner, "a:g"); XCTAssertEqual(approved.payload["value"] as? Bool, true)
        do { _ = try await store.beginOwnerRequest(id: id); XCTFail("Repeated approval") } catch {}
        _ = try await store.cancelOwnerRequest(owner: "a:g", id: id)
        do { try await store.ensureOwnerRequestApplying(id: id); XCTFail("Cancelled approval remains valid") } catch {}
        try await store.finishOwnerRequest(id: id, status: "completed")
        let cancelled = try await store.ownerRequest(owner: "a:g", id: id)
        XCTAssertEqual(cancelled["status"] as? String, "cancelled")
    }
    func testOwnerRequestsExpireAndQueueIsBoundedWithoutDiscardingPendingWork() async throws {
        let store = try store(), now = Date(timeIntervalSince1970: 1000)
        for _ in 0..<3 { _ = try await store.createOwnerRequest(owner: "a:g", sender: "Fixture", summary: "Host: On", payload: [:], now: now) }
        do { _ = try await store.createOwnerRequest(owner: "a:g", sender: "Fixture", summary: "Fourth", payload: [:], now: now); XCTFail("Unbounded queue") } catch let error as DeviceAPIError { XCTAssertEqual(error.status, 429) }
        let pending = try await store.ownerRequests(now: now); XCTAssertEqual(pending.count, 3)
        let expired = try await store.ownerRequests(now: now.addingTimeInterval(601)); XCTAssertTrue(expired.isEmpty)
        let id = pending[0]["request_id"] as! String
        do { _ = try await store.beginOwnerRequest(id: id, now: now.addingTimeInterval(601)); XCTFail("Expired approval") } catch {}
    }
    func testCancellationAfterCommitCannotLieAboutAppliedEffectsAndInterruptedCommitIsExplicit() async throws {
        let store = try store(), now = Date()
        let request = try await store.createOwnerRequest(owner: "a:g", sender: "Fixture", summary: "Host camera: On", payload: ["value": true], now: now)
        let id = request["request_id"] as! String
        _ = try await store.beginOwnerRequest(id: id)
        try await store.commitOwnerRequest(id: id)
        // The owner has crossed the effect boundary, but completion has not been saved.
        do { _ = try await store.cancelOwnerRequest(owner: "a:g", id: id); XCTFail("Committed effect reported cancelled") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.code, "request_committing") }
        try await store.finishOwnerRequest(id: id, status: "completed")
        let completed = try await store.ownerRequest(owner: "a:g", id: id)
        XCTAssertEqual(completed["status"] as? String, "completed")
        let second = try await store.createOwnerRequest(owner: "a:g", sender: "Fixture", summary: "Host camera: Off", payload: [:], now: now)
        let interruptedID = second["request_id"] as! String
        _ = try await store.beginOwnerRequest(id: interruptedID)
        try await store.commitOwnerRequest(id: interruptedID)
        let interrupted = try await store.ownerRequest(owner: "a:g", id: interruptedID, now: now.addingTimeInterval(601))
        XCTAssertEqual(interrupted["status"] as? String, "outcome_unknown")
    }
    func testDeviceCapabilityReductionCommitsOnlyForCurrentUncancelledWriter() async throws {
        let store = try store()
        let authority = HostAuthority(store: store, deviceID: "fixture")
        let (writer, _) = try await authority.create(name: "Settings writer", operations: [.configurationWrite], applets: [])
        let (other, _) = try await authority.create(name: "Other writer", operations: [.configurationWrite], applets: [])
        for kind in TransferKind.allCases { try await store.setTransferEnabled(kind, enabled: true) }
        try await store.setCallbackInternetAllowed(true)

        func begin() async throws -> String {
            let request = try await store.createOwnerRequest(owner: writer.eventOwner, sender: writer.name,
                summary: "Turn off device capabilities", payload: ["field": HostSettingChange.disableDeviceCapabilities, "value": true])
            let id = try XCTUnwrap(request["request_id"] as? String)
            _ = try await store.beginOwnerRequest(id: id)
            return id
        }
        let id = try await begin()
        do { try await store.disableDurableDeviceCapabilities(request: (id, other)); XCTFail("Another writer consumed the request") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.status, 403) }
        try await authority.pause(writer.id, paused: true)
        do { try await store.disableDurableDeviceCapabilities(request: (id, writer)); XCTFail("Paused writer changed transfers") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.status, 403) }
        try await authority.pause(writer.id, paused: false)
        _ = try await store.cancelOwnerRequest(owner: writer.eventOwner, id: id)
        do { try await store.disableDurableDeviceCapabilities(request: (id, writer)); XCTFail("Cancelled request changed transfers") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.status, 409) }
        let before = try await store.enabledTransfers()
        XCTAssertEqual(before, Set(TransferKind.allCases))
        let beforePolicy = try await store.callbackInternetAllowed()
        XCTAssertTrue(beforePolicy)

        let accepted = try await begin()
        try await store.disableDurableDeviceCapabilities(request: (accepted, writer))
        let after = try await store.enabledTransfers()
        XCTAssertTrue(after.isEmpty)
        let afterPolicy = try await store.callbackInternetAllowed()
        XCTAssertFalse(afterPolicy)
        let committing = try await store.ownerRequest(owner: writer.eventOwner, id: accepted)
        XCTAssertEqual(committing["status"] as? String, "committing")
    }
    func testSpeakerCeilingAcceptsOnlyBoundedPercentagesAndResetsToDefault() async throws {
        let authority = HostAuthority(store: try store(), deviceID: "fixture")
        let (agent, _) = try await authority.create(name: "Settings writer", operations: [.configurationWrite], applets: [])
        let field = HostSettingChange.speakerVolumeField
        for value: Any in [9, 101, 10.5, true, "70"] {
            XCTAssertThrowsError(try HostSettingChange(.init(method: "POST", path: "/v1/settings",
                body: ["field": field, "value": value]), agent: agent))
        }
        for value in [10, 70, 100] {
            let change = try HostSettingChange(.init(method: "POST", path: "/v1/settings",
                body: ["field": field, "value": value]), agent: agent)
            XCTAssertEqual(change.value as? Int, value)
        }
        let reset = try HostSettingChange(.init(method: "DELETE", path: "/v1/settings/" + field), agent: agent)
        XCTAssertEqual(reset.value as? Int, 70)
    }
    func testRetentionWriteNeedsCurrentWriterAtCommitAndResetsEachLimitIndependently() async throws {
        let store = try store()
        let authority = HostAuthority(store: store, deviceID: "fixture")
        let (writer, _) = try await authority.create(name: "Settings writer", operations: [.configurationWrite], applets: [])
        let entries = HostActivityRetentionField.entries.rawValue
        let days = HostActivityRetentionField.days.rawValue
        let payload: [String: Any] = ["field": entries, "value": 100_000]
        let change = try HostSettingChange(.init(method: "POST", path: "/v1/settings", body: payload), agent: writer)
        XCTAssertEqual(change.value as? Int, 100_000)
        for value: Any in [366, -1, 3.5, true, "3"] {
            XCTAssertThrowsError(try HostSettingChange(.init(method: "POST", path: "/v1/settings",
                body: ["field": days, "value": value]), agent: writer))
        }
        let reset = try HostSettingChange(.init(method: "DELETE", path: "/v1/settings/" + days), agent: writer)
        XCTAssertEqual(reset.value as? Int, 0)

        let queued = try await store.createOwnerRequest(owner: writer.eventOwner, sender: writer.name, summary: "Fixture", payload: payload)
        let id = try XCTUnwrap(queued["request_id"] as? String)
        _ = try await store.beginOwnerRequest(id: id)
        try await authority.pause(writer.id, paused: true)
        do { try await store.setHostActivityRetention([.entries: 100_000], request: (id, writer)); XCTFail("Paused writer committed retention") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.status, 403) }
        let unchanged = try await store.hostActivityRetention()
        XCTAssertEqual(unchanged, [.entries: 0, .days: 0])
        try await authority.pause(writer.id, paused: false)
        try await store.setHostActivityRetention([.days: 365])
        try await store.setHostActivityRetention([.entries: 100_000], request: (id, writer))
        try await store.setHostActivityRetention([.days: 0])
        let saved = try await store.hostActivityRetention()
        XCTAssertEqual(saved, [.entries: 100_000, .days: 0])
        let pending = try await store.createOwnerRequest(owner: writer.eventOwner, sender: writer.name, summary: "Fixture", payload: payload)
        let revokedID = try XCTUnwrap(pending["request_id"] as? String)
        _ = try await store.beginOwnerRequest(id: revokedID)
        try await authority.remove(writer.id)
        do { try await store.setHostActivityRetention([.entries: 1], request: (revokedID, writer)); XCTFail("Revoked writer committed retention") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.status, 403) }
        let afterRevoke = try await store.hostActivityRetention()
        XCTAssertEqual(afterRevoke, saved)
    }

    func testSettingsNeverCoerceNumbersOrUnknownFieldsAndEnforceAppletScope() async throws {
        let authority = HostAuthority(store: try store(), deviceID: "fixture")
        let (agent, _) = try await authority.create(name: "Fixture", operations: [.configurationWrite], applets: ["insurance-inventory"])
        for body: [String: Any] in [
            ["field": "host_camera", "value": 1], ["field": "host_camera", "value": "true"],
            ["field": "host_camera", "value": true, "retries": 3],
            ["field": "host_camera", "value": true, "applet": "insurance-inventory"],
            ["field": "speech_cloud", "value": true, "applet": "other"],
            ["field": HostSettingChange.disableDeviceCapabilities, "value": false],
            ["field": HostSettingChange.disableDeviceCapabilities, "value": 1],
            ["field": HostSettingChange.disableDeviceCapabilities, "value": true, "applet": "insurance-inventory"],
            ["field": "appearance", "value": "Purple"], ["field": "secret", "value": "x"]
        ] { XCTAssertThrowsError(try HostSettingChange(.init(method: "POST", path: "/v1/settings", body: body), agent: agent)) }
        XCTAssertNoThrow(try HostSettingChange(.init(method: "POST", path: "/v1/settings",
            body: ["field": HostSettingChange.disableDeviceCapabilities, "value": true]), agent: agent))
        XCTAssertThrowsError(try HostSettingChange(.init(method: "DELETE", path: "/v1/settings/device_capabilities_off"), agent: agent))
        let reset = try HostSettingChange(.init(method: "DELETE", path: "/v1/settings/speech_cloud", query: ["applet": "insurance-inventory"]), agent: agent)
        XCTAssertEqual(reset.value as? Bool, false)
        XCTAssertEqual(reset.applet, "insurance-inventory")
        XCTAssertNil(HostRequest(method: "POST", path: "/v1/settings/requests/" + UUID().uuidString + "/approve").route)
    }
}
