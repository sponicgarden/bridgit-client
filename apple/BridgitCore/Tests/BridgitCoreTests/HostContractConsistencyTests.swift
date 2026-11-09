import Foundation
import XCTest
@testable import BridgitCore

/// A0249: the Apple host contract agrees with itself and with Android on the self-description
/// paths, the applet package reference, interaction IDs, settings cancellation and host_wake.
final class HostContractConsistencyTests: XCTestCase {
    private func store() throws -> AppletStore {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: url) }
        return try AppletStore(directory: url)
    }

    func testSelfDescriptionPathsHaveOneSourceMatchingAndroid() {
        XCTAssertEqual(HostAPIPaths.openAPI, "/v1/openapi.json")
        let byName = Dictionary(uniqueKeysWithValues: HostAPIContract.operations.map { ($0.name, $0) })
        XCTAssertEqual(byName["openapi"]?.path, HostAPIPaths.openAPI)
        XCTAssertEqual(byName["guide"]?.path, HostAPIPaths.guide)
        XCTAssertEqual(byName["api_changes"]?.path, HostAPIPaths.changes)
        for path in HostAPIPaths.selfDescription {
            XCTAssertEqual(HostRequest(method: "GET", path: path).route?.0, .deviceRead, path)
        }
        let links = HostAPIChanges.document(since: 0, appVersion: "fixture")["links"] as? [String: String]
        XCTAssertEqual(links?["openapi"], HostAPIPaths.openAPI)
        XCTAssertEqual(links?["applet_package_format"], HostAPIPaths.appletPackageFormat)
    }

    func testAppletPackageFormatIsDiscoverableFromTheContract() async throws {
        let store = try store(), authority = HostAuthority(store: store, deviceID: "fixture")
        let (manager, _) = try await authority.create(name: "Applet manager", operations: [.deviceRead, .appletsManage], applets: [])
        let openAPI = HostAPIContract.openAPI(manager)
        XCTAssertEqual((openAPI["externalDocs"] as? [String: String])?["url"], HostAPIPaths.appletPackageFormat)
        let paths = try XCTUnwrap(openAPI["paths"] as? [String: [String: Any]])
        for path in ["/v1/applets/validate", "/v1/applets/install"] {
            let post = try XCTUnwrap(paths[path]?["post"] as? [String: Any], path)
            XCTAssertEqual((post["externalDocs"] as? [String: String])?["url"], HostAPIPaths.appletPackageFormat)
            let body = try XCTUnwrap(((post["requestBody"] as? [String: Any])?["content"] as? [String: [String: [String: Any]]])?["application/json"]?["schema"])
            let package = try XCTUnwrap((body["properties"] as? [String: [String: Any]])?["package"])
            XCTAssertTrue((package["description"] as? String ?? "").contains(HostAPIPaths.appletPackageFormat))
            XCTAssertTrue((package["description"] as? String ?? "").contains("bridgit.package.v1"))
        }
        XCTAssertEqual(HostAPIContract.discovery(manager)["applet_package_format"] as? String, HostAPIPaths.appletPackageFormat)
        let tools = HostAPIContract.tools(manager)
        let install = try XCTUnwrap(tools.first { $0["name"] as? String == "applets_install" })
        XCTAssertTrue((install["description"] as? String ?? "").contains(HostAPIPaths.appletPackageFormat))
    }

    func testSamplesDescribeThisHostsUUIDInteractionIDs() throws {
        let root: [String: Any] = [
            "api_revision": 112,
            "conventions": ["create": "Reply 201: {\"interaction_id\": <number>}", "wait": "kept"],
            "samples": [
                ["id": "question-buttons", "kind": "question", "request": ["kind": "question", "title": "Pay?", "text": "Due Friday.", "controls": [["type": "button", "id": "pay", "label": "Pay it"]]]],
                ["id": "html-connection-test", "kind": "html", "request": ["kind": "html", "title": "Connection test", "html": "<p>Hi</p>", "expires_in_seconds": 3600]],
                ["id": "notice", "kind": "notice", "request": ["kind": "notice", "title": "Train", "priority": "urgent", "speak": true]],
                ["id": "pieces-status", "kind": "pieces", "request": ["kind": "pieces", "title": "Status", "pieces": []]],
                ["id": "text-panel", "kind": "text", "request": ["kind": "text", "title": "Log"],
                 "calls": [["method": "POST", "path": "/v1/text/stream", "reply": ["interaction_id": 42]]]]
            ]
        ]
        let adapted = try HostSamples.appleHost(root)
        let ids = (adapted["samples"] as? [[String: Any]] ?? []).compactMap { $0["id"] as? String }
        XCTAssertEqual(ids, ["question-buttons", "html-connection-test"])
        let conventions = try XCTUnwrap(adapted["conventions"] as? [String: String])
        XCTAssertTrue(conventions["create"]?.contains("UUID string") == true)
        XCTAssertFalse(conventions["create"]?.contains("<number>") == true)
        XCTAssertEqual(conventions["wait"], "kept")
        XCTAssertEqual(adapted["interaction_id_format"] as? String, "uuid")
        XCTAssertEqual(adapted["api_revision"] as? Int, HostAPIChanges.current)
        XCTAssertFalse(String(decoding: try JSONSerialization.data(withJSONObject: adapted), as: UTF8.self).contains("42"))
        // Every listed request is accepted as is, and the reply's interaction_id is a UUID string.
        for sample in adapted["samples"] as? [[String: Any]] ?? [] {
            let request = try XCTUnwrap(sample["request"] as? [String: Any])
            XCTAssertEqual(request["no_takeover"] as? Bool, true)
            let created = try InteractionValidation.create(request, now: Date())
            XCTAssertNotNil(UUID(uuidString: try XCTUnwrap(created["interaction_id"] as? String)))
        }
        XCTAssertEqual((try HostSamples.appleHost(root, id: "html-connection-test"))["id"] as? String, "html-connection-test")
        XCTAssertThrowsError(try HostSamples.appleHost(root, id: "text-panel")) { error in
            XCTAssertEqual((error as? DeviceAPIError)?.code, "sample_not_found")
        }
    }

    func testSettingsCancelRefusesFinishedRequestsAndRepeatsCancelled() async throws {
        let store = try store()
        let finished = try await store.createOwnerRequest(owner: "a:g", sender: "Fixture", summary: "Host camera: On", payload: ["value": true])
        let finishedID = try XCTUnwrap(finished["request_id"] as? String)
        _ = try await store.beginOwnerRequest(id: finishedID)
        try await store.commitOwnerRequest(id: finishedID)
        try await store.finishOwnerRequest(id: finishedID, status: "completed")
        do { _ = try await store.cancelOwnerRequest(owner: "a:g", id: finishedID); XCTFail("A completed change reported a successful cancel") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.status, 409); XCTAssertEqual(error.code, "request_closed") }
        let unchanged = try await store.ownerRequest(owner: "a:g", id: finishedID)
        XCTAssertEqual(unchanged["status"] as? String, "completed")
        // Native owner cancellation of a finished request is quiet.
        try await store.cancelConfigurationOperation(id: finishedID)

        let queued = try await store.createOwnerRequest(owner: "a:g", sender: "Fixture", summary: "Host camera: Off", payload: ["value": false])
        let queuedID = try XCTUnwrap(queued["request_id"] as? String)
        let cancelled = try await store.cancelOwnerRequest(owner: "a:g", id: queuedID)
        XCTAssertEqual(cancelled["status"] as? String, "cancelled")
        let repeated = try await store.cancelOwnerRequest(owner: "a:g", id: queuedID)
        XCTAssertEqual(repeated["status"] as? String, "cancelled")
        let summary = HostAPIContract.operations.first { $0.name == "settings_cancel" }?.summary ?? ""
        XCTAssertTrue(summary.contains("request_closed"))
    }

    func testHostWakeIsListedExactlyWhereItCanBeChanged() async throws {
        let store = try store(), authority = HostAuthority(store: store, deviceID: "fixture")
        let (writer, _) = try await authority.create(name: "Settings writer", operations: [.configurationWrite], applets: [])
        let listed = HostSettingChange.availableFields(crashReporting: false).contains("host_wake")
        let inSchema = (HostSettingChange.schema["oneOf"] as? [[String: Any]] ?? []).contains { choice in
            ((choice["properties"] as? [String: [String: Any]])?["field"]?["const"] as? String) == "host_wake"
        }
        XCTAssertEqual(listed, HostSettingChange.hostWakeSupported)
        XCTAssertEqual(inSchema, HostSettingChange.hostWakeSupported)
        let change = { try HostSettingChange(.init(method: "POST", path: "/v1/settings", body: ["field": "host_wake", "value": true]), agent: writer) }
        let reset = { try HostSettingChange(.init(method: "DELETE", path: "/v1/settings/host_wake"), agent: writer) }
        if HostSettingChange.hostWakeSupported {
            XCTAssertNoThrow(try change()); XCTAssertNoThrow(try reset())
        } else {
            for attempt in [change, reset] {
                XCTAssertThrowsError(try attempt()) { XCTAssertEqual(($0 as? DeviceAPIError)?.code, "unsupported_setting") }
            }
        }
        #if os(macOS)
        XCTAssertFalse(HostSettingChange.hostWakeSupported)
        #endif
    }
}
