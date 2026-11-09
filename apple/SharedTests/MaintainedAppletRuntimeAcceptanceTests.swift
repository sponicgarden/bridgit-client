import BridgitAppletRuntime
@testable import BridgitCore
import CryptoKit
import Foundation
import SQLite3
import UniformTypeIdentifiers
import XCTest
#if os(macOS)
@testable import Bridgit_macOS
#else
@testable import Bridgit_iOS
#endif

private final class MaintainedRegistryFixture: URLProtocol {
    static let lock = NSLock()
    static var responses: [String: Data] = [:]
    static var requests: [URLRequest] = []
    static var offline = false
    static func prepare(_ responses: [String: Data]) {
        lock.lock(); defer { lock.unlock() }
        self.responses = responses; requests = []; offline = false
    }
    static func setOffline() { lock.lock(); offline = true; lock.unlock() }
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "maintained-runtime.invalid" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.lock.lock()
        Self.requests.append(request)
        let data = Self.responses[request.url!.path], offline = Self.offline
        Self.lock.unlock()
        guard !offline, let data else { client?.urlProtocol(self, didFailWithError: URLError(.notConnectedToInternet)); return }
        let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

@MainActor final class MaintainedAppletRuntimeAcceptanceTests: XCTestCase {
    private func package() throws -> [String: Any] {
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<3 { root.deleteLastPathComponent() }
        var package = try JSONSerialization.jsonObject(with: Data(contentsOf: root.appendingPathComponent("docs/applet-authoring-examples/png-icon.json"))) as! [String: Any]
        package["config_schema"] = ["type": "object", "properties": ["caption": ["type": "string"]], "required": ["caption"], "additionalProperties": false]
        package["defaults"] = ["caption": "Default caption"]
        package["processing"] = [
            "tables": [["id": "notes", "label": "Notes", "columns": ["message": ["type": "text"]], "max_rows": 100, "keep_days": 90],
                       ["id": "photos", "columns": ["photo": ["type": "photo"], "status": ["type": "text"]], "max_rows": 100, "keep_days": 90]],
            "workflow": ["name": "Save a note", "trigger": ["type": "button"], "steps": [
                ["id": "save", "type": "record", "table": "notes", "row": ["message": "maintained-native-flow-complete"]],
                ["id": "done", "type": "notice", "text": "Saved locally"]
            ]]
        ]
        var ui = package["ui"] as? [String: Any] ?? [:]
        let gallery: [String: Any] = ["type": "gallery", "photos": ["$source": "photos"], "photo": "photo",
                                      "caption": "status", "words": ["new": "New"], "empty": "No photos yet."]
        ui["card"] = ["sources": ["photos": ["table": "photos"]], "pieces": [["type": "buttons", "items": [
            ["id": "take", "label": "Photograph note", "do": ["camera": ["table": "photos", "photo": "photo", "row": ["status": "new"]]]],
            ["label": "Share notes", "do": ["step": ["type": "share", "as": "csv", "table": "notes"]]]
        ]], gallery]]
        package["ui"] = ui
        package["permissions"] = ["required": ["workflows", "applet_data", "interactions", "camera"], "optional": [], "remote": [], "automatic_grants": false]
        return package
    }
    @MainActor func testLocalCSVShareAppliesDeclaredFilterBeforeWritingBytes() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let local = LocalAppletModel(directory: directory)
        let store = try XCTUnwrap(local.store)
        addTeardownBlock {
            #if DEBUG
            try await store.closeTemporaryFixture(at: directory)
            try FileManager.default.removeItem(at: directory)
            #endif
        }
        let definition = try XCTUnwrap(HomeBundledDefinition.all.first { $0.id == "insurance-inventory" }).definition()
        try await store.install(definition)
        _ = try await store.addRows(applet: "insurance-inventory", table: "items",
                                    rows: [["label": "Selected"], ["label": "Excluded"]])
        let declaration: [String: Any] = ["columns": ["label"], "where": ["column": "label", "is": "selected"]]
        let data = try await local.exportData(applet: "insurance-inventory", table: "items", declaration: declaration)
        XCTAssertEqual(String(decoding: data, as: UTF8.self), "\u{feff}Item\r\nSelected\r\n")
        do {
            _ = try await local.exportData(applet: "insurance-inventory", table: "items",
                                           declaration: ["where": ["column": "missing", "is": "selected"]])
            XCTFail("Malformed filters must not fall back to exporting every row")
        } catch AppletRuntimeError.invalid {}
    }

    func testRegistryInstallRunsMaintainedNativeFlow() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let account = String(repeating: "d", count: 64)
        let entry: [String: Any] = ["key": "tpl_native_note", "id": "blue-note", "revision": 7, "name": "Native note", "summary": "Synthetic native acceptance"]
        MaintainedRegistryFixture.prepare([
            "/v1/templates/account": try JSONSerialization.data(withJSONObject: ["library_account": account]),
            "/v1/templates/catalog": try JSONSerialization.data(withJSONObject: ["format": "bridgit-applet-catalog/1", "scope": "public", "applets": [entry], "next": NSNull()]),
            "/v1/templates/tpl_native_note/7": try JSONSerialization.data(withJSONObject: ["key": "tpl_native_note", "revision": 7, "state": "published", "definition": package()])
        ])
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [MaintainedRegistryFixture.self]
        let http = URLSession(configuration: configuration)
        defer { http.invalidateAndCancel() }
        let cloud = WatchClient(base: URL(string: "https://maintained-runtime.invalid")!, session: "synthetic-session", http: http)
        let database = folder.appendingPathComponent("AppletLibrary/installed.sqlite")
        var bindings: [String: String] = [:]
        let controller = PortableLibraryController(cloud: cloud, database: database, bindingRead: { bindings[$0] }, bindingWrite: { value, key in bindings[key] = value; return true })
        try await controller.catalog()
        let selected = try XCTUnwrap(controller.page?.applets.first)
        guard case .local(let reviewed) = try await controller.review(selected) else { return XCTFail("Expected a maintained local edition") }
        let edition = try await controller.install(reviewed)
        XCTAssertEqual(edition.id, "tpl_native_note"); XCTAssertEqual(edition.revision, 7)
        let outcome = try await controller.run("main", edition: edition) { _ in }
        XCTAssertNotNil(outcome.results["save"])
        let firstRows = try await controller.rowsPage(edition, table: "notes").rows
        XCTAssertEqual(firstRows.count, 1)
        XCTAssertEqual(firstRows[0]["message"]?.text, "maintained-native-flow-complete")
        let originalID = try XCTUnwrap(firstRows[0]["id"]?.text)
        MaintainedRegistryFixture.setOffline()
        let reopened = PortableLibraryController(cloud: cloud, database: database, bindingRead: { bindings[$0] }, bindingWrite: { value, key in bindings[key] = value; return true })
        try await reopened.loadInstalled()
        let sameInstall = try XCTUnwrap(reopened.editions.first)
        let persisted = try await reopened.rowsPage(sameInstall, table: "notes").rows
        XCTAssertEqual(persisted.count, 1); XCTAssertEqual(persisted[0]["id"]?.text, originalID)
        XCTAssertEqual(persisted[0]["message"]?.text, "maintained-native-flow-complete")
        let package = try await reopened.open(sameInstall).package
        let share = try XCTUnwrap(package.csvButtons.first)
        let csv = try await reopened.exportCSV(share, edition: sameInstall)
        XCTAssertEqual(String(decoding: csv, as: UTF8.self), "\u{feff}message\r\nmaintained-native-flow-complete\r\n")
        let source = try XCTUnwrap(AppleBuildDetails.revision)
        let executable = try XCTUnwrap(Bundle.main.executableURL)
        let debugLibrary = executable.deletingLastPathComponent().appendingPathComponent(executable.lastPathComponent + ".debug.dylib")
        let binary = FileManager.default.fileExists(atPath: debugLibrary.path) ? debugLibrary : executable
        let binaryHash = SHA256.hash(data: try Data(contentsOf: binary)).map { String(format: "%02x", $0) }.joined()
        let receipt: [String: Any] = [
            "host_binary_sha256": binaryHash, "host_binary_name": binary.lastPathComponent,
            "source_revision": source, "bundle_identifier": Bundle.main.bundleIdentifier ?? "",
            "native_model_type": String(reflecting: PortableLibraryController.self),
            "runtime_types": [String(reflecting: LocalAppletLibrary.self), String(reflecting: FlowRuntime.self)],
            "persisted_output": "maintained-native-flow-complete", "registry_key": sameInstall.id, "revision": sameInstall.revision
        ]
        let attachment = XCTAttachment(data: try JSONSerialization.data(withJSONObject: receipt, options: [.sortedKeys]), uniformTypeIdentifier: UTType.json.identifier)
        attachment.name = "maintained-runtime-execution"; attachment.lifetime = .keepAlways
        add(attachment)
        let camera = try XCTUnwrap(package.cameraItems.first)
        let library = try await reopened.library()
        guard case .array(let pieces)? = package.definition["ui"]?["card"]?["pieces"] else { return XCTFail("Missing pieces") }
        let gallery = try XCTUnwrap(pieces.first { $0["type"] == .string("gallery") })
        let jpeg = try LocalAppletModel.jpeg(XCTUnwrap(package.iconPNG))
        try await reopened.capturePhoto(camera, edition: sameInstall) { _, _ in .init(jpeg: jpeg, camera: "rear") }
        let saved = try await reopened.gallery(gallery, edition: sameInstall)
        XCTAssertEqual(saved.map(\.caption), ["New"])
        let ref = try XCTUnwrap(saved.first?.reference)
        let displayedBytes = try await reopened.photo(ref, edition: sameInstall)
        XCTAssertEqual(displayedBytes, jpeg)

        var otherEntry = entry
        otherEntry["key"] = "tpl_other_note"
        let otherPin = try JSONDecoder().decode(CatalogEntry.self, from: JSONSerialization.data(withJSONObject: otherEntry))
        let otherRelease = try RegistryPackage(response: JSONSerialization.data(withJSONObject: [
            "key": otherPin.key, "revision": otherPin.revision, "state": "published", "definition": self.package()
        ]), selection: otherPin)
        try await library.install(otherRelease)
        let other = try await library.installed(otherPin.key)
        let otherEdition = try XCTUnwrap(other)
        let otherPhotos = try await reopened.gallery(gallery, edition: otherEdition)
        XCTAssertTrue(otherPhotos.isEmpty)
        do { _ = try await reopened.photo(ref, edition: otherEdition); XCTFail("Displayed another edition's photo") }
        catch let error as AppletRuntimeError { XCTAssertEqual(error, .invalid("Photo unavailable")) }
        do {
            try await reopened.capturePhoto(camera, edition: sameInstall) { _, _ in throw CancellationError() }
            XCTFail("Cancelling photo review succeeded")
        } catch is CancellationError { }
        do {
            try await reopened.capturePhoto(camera, edition: sameInstall) { _, _ in
                cloud.session = nil
                return .init(jpeg: Data(), camera: "default")
            }
            XCTFail("A signed-out review was accepted")
        } catch let error as AppletRuntimeError { XCTAssertEqual(error, .revoked) }
        let photos = try await library.rowsPage(in: sameInstall, table: "photos")
        XCTAssertEqual(photos.rows.compactMap { $0["photo"]?.text }, [ref])
        do { _ = try await reopened.exportCSV(share, edition: sameInstall); XCTFail("Signed-out account exported applet records") }
        catch let error as AppletRuntimeError { XCTAssertEqual(error, .revoked) }
        do { _ = try await reopened.rowsPage(sameInstall, table: "notes").rows; XCTFail("Signed-out session read local account records") }
        catch let error as AppletRuntimeError { XCTAssertEqual(error, .revoked) }
        do { try await reopened.remove(sameInstall); XCTFail("Signed-out session removed an edition") }
        catch let error as AppletRuntimeError { XCTAssertEqual(error, .revoked) }
        cloud.session = "synthetic-session"
        let coreStore = try AppletStore(directory: folder.appendingPathComponent("host"))
        let authority = HostAuthority(store: coreStore, deviceID: "maintained-fixture")
        let (manager, _) = try await authority.create(name: "Owner manager", operations: [.appletsManage], applets: [sameInstall.hostID])
        let (reader, _) = try await authority.create(name: "Scoped reader", operations: [.appletsRead], applets: [sameInstall.hostID])
        let (otherReader, _) = try await authority.create(name: "Unrelated reader", operations: [.appletsRead], applets: ["unrelated-edition"])
        let viewerNames = try await MaintainedAppletHost.viewerNames(reopened)
        XCTAssertTrue(viewerNames.contains { $0["id"] as? String == sameInstall.hostID })
        XCTAssertTrue(viewerNames.allSatisfy { Set($0.keys) == ["id", "name"] })
        let settingsBefore = try await reopened.open(sameInstall)
        try await library.saveSettings(["caption": .string("Owner's saved caption")], replacing: settingsBefore)
        let all = try await MaintainedAppletHost.installed(reopened, agent: manager)
        XCTAssertTrue(all.contains { $0["id"] as? String == sameInstall.hostID })
        let scoped = try await MaintainedAppletHost.installed(reopened, agent: reader)
        XCTAssertEqual(scoped.compactMap { $0["id"] as? String }, [sameInstall.hostID])
        XCTAssertEqual(scoped.first?["allowed_operations"] as? [String], ["read", "data", "rows"])
        let configured = try await reopened.open(sameInstall).config
        let configBytes = try JSONSerialization.data(withJSONObject: XCTUnwrap(scoped.first?["config"]))
        XCTAssertEqual(try JSONDecoder().decode([String: BridgitAppletRuntime.JSONValue].self, from: configBytes), configured)
        XCTAssertEqual(configured["caption"], .string("Owner's saved caption"))
        XCTAssertFalse(viewerNames.contains { $0["config"] != nil })
        XCTAssertEqual(scoped.first?["export_formats"] as? [String], ["json", "csv"])
        XCTAssertTrue((scoped.first?["supported_operations"] as? [String])?.contains("rows_write") == true)
        XCTAssertTrue((all.first { $0["id"] as? String == sameInstall.hostID }?["allowed_operations"] as? [String])?.contains("remove") == true)
        let hidden = try await MaintainedAppletHost.installed(reopened, agent: otherReader)
        XCTAssertTrue(hidden.isEmpty)
        let base = "/v1/applets/installed/" + sameInstall.hostID
        let pauseRequest = HostRequest(method: "POST", path: base + "/pause", body: ["paused": true])
        let (otherManager, _) = try await authority.create(name: "Unrelated manager", operations: [.appletsManage], applets: [otherEdition.hostID])
        for denied in [reader, otherManager] {
            do {
                _ = try await MaintainedAppletHost.handle(pauseRequest, agent: denied, controller: reopened, coreStore: coreStore, authorize: {})
                XCTFail("Agent without management access paused this installation")
            } catch let error as DeviceAPIError { XCTAssertEqual(error.status, 403) }
        }
        for body: [String: Any] in [[:], ["paused": 1], ["paused": "true"], ["paused": true, "extra": true]] {
            do {
                _ = try await MaintainedAppletHost.handle(.init(method: "POST", path: base + "/pause", body: body), agent: manager, controller: reopened, coreStore: coreStore, authorize: {})
                XCTFail("Malformed pause was accepted")
            } catch let error as DeviceAPIError { XCTAssertEqual(error.status, 422) }
        }
        let pauseResponse = try await MaintainedAppletHost.handle(pauseRequest, agent: manager, controller: reopened, coreStore: coreStore, authorize: {})
        let pausedObject = try JSONSerialization.jsonObject(with: XCTUnwrap(pauseResponse).data) as! [String: Any]
        XCTAssertEqual(pausedObject["status"] as? String, "paused")
        for _ in 0..<200 where !controller.isPaused(sameInstall) { try await Task.sleep(for: .milliseconds(10)) }
        XCTAssertTrue(controller.isPaused(sameInstall), "An already-open owner controller must reflect API pause")
        XCTAssertTrue((pausedObject["allowed_operations"] as? [String])?.contains("pause") == true)
        let pausedList = try await MaintainedAppletHost.installed(reopened, agent: reader)
        XCTAssertEqual(pausedList.first?["status"] as? String, "paused")
        XCTAssertFalse((pausedList.first?["allowed_operations"] as? [String])?.contains("pause") == true)
        do {
            _ = try await reopened.run("main", edition: sameInstall) { _ in XCTFail("Paused run delivered a notice") }
            XCTFail("Paused run completed")
        } catch AppletRuntimeError.paused {}
        let pausedRows = try await reopened.rowsPage(sameInstall, table: "notes").rows
        XCTAssertEqual(pausedRows.count, 1)
        let pausedPhoto = try await reopened.photo(ref, edition: sameInstall)
        XCTAssertEqual(pausedPhoto, jpeg)
        let resumeResponse = try await MaintainedAppletHost.handle(.init(method: "POST", path: base + "/pause", body: ["paused": false]), agent: manager, controller: reopened, coreStore: coreStore, authorize: {})
        let resumedObject = try JSONSerialization.jsonObject(with: XCTUnwrap(resumeResponse).data) as! [String: Any]
        XCTAssertEqual(resumedObject["status"] as? String, "active")
        for _ in 0..<200 where controller.isPaused(sameInstall) { try await Task.sleep(for: .milliseconds(10)) }
        XCTAssertFalse(controller.isPaused(sameInstall), "An already-open owner controller must reflect API resume")
        let otherPaused = try await library.isPaused(otherEdition)
        XCTAssertFalse(otherPaused)
        let (administrator, _) = try await authority.create(name: "Administrator", operations: [.administrator], applets: [])
        let adminPause = try await MaintainedAppletHost.handle(pauseRequest, agent: administrator, controller: reopened, coreStore: coreStore, authorize: {})
        XCTAssertEqual(adminPause?.status, 200)
        let adminResume = try await MaintainedAppletHost.handle(.init(method: "POST", path: base + "/pause", body: ["paused": false]), agent: administrator, controller: reopened, coreStore: coreStore, authorize: {})
        XCTAssertEqual(adminResume?.status, 200)
        let (writer, _) = try await authority.create(name: "Scoped writer", operations: [.appletsWrite], applets: [sameInstall.hostID])
        let rowPath = base + "/tables/notes/rows"
        let addRequest = HostRequest(method: "POST", path: rowPath, body: ["row": ["message": "agent-created"]], idempotencyKey: "native-row-add")
        do {
            _ = try await MaintainedAppletHost.handle(addRequest, agent: reader, controller: reopened, coreStore: coreStore, authorize: {})
            XCTFail("Read-only agent inserted a row")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.status, 403) }
        let addResponse = try await MaintainedAppletHost.handle(addRequest, agent: writer, controller: reopened, coreStore: coreStore, authorize: {})
        let added = try XCTUnwrap(addResponse)
        XCTAssertEqual(added.status, 201)
        let addedObject = try JSONSerialization.jsonObject(with: added.data) as! [String: Any]
        let addedID = try XCTUnwrap(addedObject["id"] as? String)
        let changed = try await MaintainedAppletHost.handle(.init(method: "PATCH", path: rowPath + "/" + addedID, body: ["set": ["message": "agent-edited"]]), agent: writer, controller: reopened, coreStore: coreStore, authorize: {})
        let changedObject = try JSONSerialization.jsonObject(with: XCTUnwrap(changed).data) as! [String: Any]
        XCTAssertEqual(changedObject["message"] as? String, "agent-edited")
        do {
            _ = try await MaintainedAppletHost.handle(.init(method: "PATCH", path: rowPath + "/" + addedID, body: ["set": ["unknown": "bad"]]), agent: writer, controller: reopened, coreStore: coreStore, authorize: {})
            XCTFail("Undeclared column accepted")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.status, 422) }
        let deleted = try await MaintainedAppletHost.handle(.init(method: "DELETE", path: rowPath + "/" + addedID), agent: writer, controller: reopened, coreStore: coreStore, authorize: {})
        XCTAssertEqual(deleted?.status, 200)
        do {
            _ = try await MaintainedAppletHost.handle(.init(method: "PATCH", path: rowPath + "/" + addedID, body: ["set": ["message": "missing"]]), agent: writer, controller: reopened, coreStore: coreStore, authorize: {})
            XCTFail("Removed row was updated")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.status, 404) }
        let discovery = try await MaintainedAppletHost.handle(.init(method: "GET", path: base + "/data"), agent: manager, controller: reopened, coreStore: coreStore, authorize: {})
        let contract = try JSONSerialization.jsonObject(with: XCTUnwrap(discovery).data) as! [String: Any]
        XCTAssertEqual(contract["contract"] as? String, "bridgit-applet-data/2")
        XCTAssertEqual(contract["complete"] as? Bool, true)
        let discoverTables = try XCTUnwrap(contract["tables"] as? [[String: Any]])
        let notesDiscovery = try XCTUnwrap(discoverTables.first { $0["id"] as? String == "notes" })
        XCTAssertEqual(notesDiscovery["delete_all_url"] as? String, rowPath)
        XCTAssertEqual(notesDiscovery["delete_all_method"] as? String, "DELETE")
        let exported = try await MaintainedAppletHost.handle(.init(method: "GET", path: base + "/data/export", query: ["format": "csv", "table": "notes"]), agent: reader, controller: reopened, coreStore: coreStore, authorize: {})
        XCTAssertEqual(exported?.contentType, "text/csv; charset=utf-8")
        XCTAssertEqual(exported?.headers["Cache-Control"], "no-store")
        XCTAssertEqual(try XCTUnwrap(exported).data, csv)
        do {
            _ = try await MaintainedAppletHost.handle(.init(method: "GET", path: base + "/data/export", query: ["format": "csv", "table": "missing"]), agent: reader, controller: reopened, coreStore: coreStore, authorize: {})
            XCTFail("An undeclared table was exported")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.status, 404); XCTAssertEqual(error.code, "table_not_found") }
        let ownerCSV = try await reopened.exportOwnerTableCSV(edition: sameInstall, table: "notes")
        XCTAssertEqual(try XCTUnwrap(exported).data, ownerCSV, "Owner and scoped API must serialize identical rows and headers")
        do {
            _ = try await MaintainedAppletHost.handle(.init(method: "GET", path: base + "/data/export", query: ["format": "csv", "table": "notes"]), agent: otherReader, controller: reopened, coreStore: coreStore, authorize: {})
            XCTFail("Unassigned reader exported another applet's rows")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.status, 403) }
        var csvAuthorizations = 0
        do {
            _ = try await MaintainedAppletHost.handle(.init(method: "GET", path: base + "/data/export", query: ["format": "csv", "table": "notes"]), agent: reader, controller: reopened, coreStore: coreStore) {
                csvAuthorizations += 1
                if csvAuthorizations == 2 { cloud.session = nil }
            }
            XCTFail("Account changed during CSV export returned data")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.status, 403) }
        cloud.session = "synthetic-session"
        for query: [String: Any] in [["format": "csv"], ["format": "json", "table": "notes"], ["format": "pdf"], ["format": true]] {
            do {
                _ = try await MaintainedAppletHost.handle(.init(method: "GET", path: base + "/data/export", query: query), agent: reader, controller: reopened, coreStore: coreStore, authorize: {})
                XCTFail("Invalid export request accepted")
            } catch let error as DeviceAPIError { XCTAssertEqual(error.status, 422) }
        }
        do {
            _ = try await MaintainedAppletHost.handle(.init(method: "GET", path: base + "/data/export", query: ["format": "csv", "table": "missing"]), agent: reader, controller: reopened, coreStore: coreStore, authorize: {})
            XCTFail("Unknown table exported")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.status, 404) }
        let schemas = contract["tables"] as! [[String: Any]]
        XCTAssertTrue(schemas.contains { $0["id"] as? String == "notes" && $0["cursor_parameter"] as? String == "since" })
        let links = contract["photos"] as! [[String: Any]]
        let photoURL = try XCTUnwrap(links.first { $0["ref"] as? String == ref }?["url"] as? String)
        let savedPhoto = try await MaintainedAppletHost.handle(.init(method: "GET", path: photoURL), agent: manager, controller: reopened, coreStore: coreStore, authorize: {})
        XCTAssertEqual(savedPhoto?.contentType, "image/jpeg")
        XCTAssertFalse(try XCTUnwrap(savedPhoto).data.isEmpty)
        do {
            _ = try await MaintainedAppletHost.handle(.init(method: "GET", path: photoURL), agent: reader, controller: reopened, coreStore: coreStore, authorize: {})
            XCTFail("Reader without media grant read photo bytes")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.status, 403) }
        let recordPage = try await MaintainedAppletHost.handle(.init(method: "GET", path: base + "/tables/notes/rows", query: ["limit": "1"]), agent: reader, controller: reopened, coreStore: coreStore, authorize: {})
        let records = try JSONSerialization.jsonObject(with: XCTUnwrap(recordPage).data) as! [String: Any]
        XCTAssertEqual((records["rows"] as? [[String: Any]])?.count, 1)
        do {
            _ = try await MaintainedAppletHost.handle(.init(method: "GET", path: base + "/tables/notes/rows", query: ["since": "invalid-cursor"]), agent: reader, controller: reopened, coreStore: coreStore, authorize: {})
            XCTFail("Malformed cursor was accepted")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.status, 422) }
        var authorizations = 0
        do {
            _ = try await MaintainedAppletHost.handle(.init(method: "GET", path: base + "/data"), agent: reader, controller: reopened, coreStore: coreStore) {
                authorizations += 1
                if authorizations == 2 { cloud.session = nil }
            }
            XCTFail("Account changed during final authorization returned data")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.status, 403) }
        cloud.session = "synthetic-session"
        // Bulk deletion is scoped to a declared table and the installed edition.
        let notesBeforeBulk = try await reopened.rowsPage(sameInstall, table: "notes").rows
        XCTAssertFalse(notesBeforeBulk.isEmpty)
        let (otherWriter, _) = try await authority.create(name: "Other writer", operations: [.appletsWrite], applets: [otherEdition.hostID])
        for denied in [reader, otherWriter] {
            do {
                _ = try await MaintainedAppletHost.handle(.init(method: "DELETE", path: rowPath), agent: denied, controller: reopened, coreStore: coreStore, authorize: {})
                XCTFail("Unscoped agent deleted the table")
            } catch let error as DeviceAPIError { XCTAssertEqual(error.status, 403) }
        }
        do {
            _ = try await MaintainedAppletHost.handle(.init(method: "DELETE", path: base + "/tables/missing/rows"), agent: writer, controller: reopened, coreStore: coreStore, authorize: {})
            XCTFail("Undeclared table deletion succeeded")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.status, 404); XCTAssertEqual(error.code, "table_not_found") }
        do {
            _ = try await MaintainedAppletHost.handle(.init(method: "DELETE", path: rowPath, query: ["unexpected": "1"]), agent: writer, controller: reopened, coreStore: coreStore, authorize: {})
            XCTFail("Bulk deletion accepted a query")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.status, 422) }
        var bulkChecks = 0
        do {
            _ = try await MaintainedAppletHost.handle(.init(method: "DELETE", path: rowPath), agent: writer, controller: reopened, coreStore: coreStore) {
                bulkChecks += 1
                if bulkChecks == 2 { cloud.session = nil }
            }
            XCTFail("Signed-out account deleted rows")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.status, 403); XCTAssertEqual(error.code, "account_changed") }
        cloud.session = "synthetic-session"
        let notesAfterDenied = try await reopened.rowsPage(sameInstall, table: "notes").rows
        XCTAssertEqual(notesAfterDenied.count, notesBeforeBulk.count)
        await authority.setState(enabled: true, awake: true, foreground: true)
        let hostEngine = AppletEngine(store: coreStore) { _, _, _ in [:] }
        let dispatcher = HostDispatcher(authority: authority, store: coreStore, engine: hostEngine, profile: { _ in throw CancellationError() }, additionalAppletAction: { request, agent in
            try await MaintainedAppletHost.handle(request, agent: agent, controller: reopened, coreStore: coreStore, directAuthority: authority) {
                guard let route = request.route else { throw DeviceAPIError(501, "unsupported_operation", "Unknown route.") }
                try await authority.authorizeOngoing(agent, operation: route.0, applet: route.1)
            }
        })
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        func bulkJob(_ sequence: Int64, key: String?) -> HostJob {
            HostJob(id: "bulk-job-\(sequence)", sequence: sequence, agentID: writer.id, generation: writer.generation,
                    deviceID: "maintained-fixture", deadline: now + 30000, createdAt: now,
                    request: .init(method: "DELETE", path: rowPath, idempotencyKey: key))
        }
        let missingKey = await dispatcher.dispatch(bulkJob(1, key: nil))
        XCTAssertEqual(missingKey.status, 400)
        let bulk = await dispatcher.dispatch(bulkJob(2, key: "bulk-key"))
        XCTAssertEqual(bulk.status, 200)
        let bulkJSON = try XCTUnwrap(JSONSerialization.jsonObject(with: bulk.data) as? [String: Any])
        XCTAssertEqual(bulkJSON["deleted"] as? Int, notesBeforeBulk.count)
        let notesAfterBulk = try await reopened.rowsPage(sameInstall, table: "notes").rows
        XCTAssertTrue(notesAfterBulk.isEmpty)
        _ = try await library.writeAgentRow(["message": .string("after bulk")], in: sameInstall, table: "notes")
        let retry = await dispatcher.dispatch(bulkJob(3, key: "bulk-key"))
        XCTAssertEqual(retry.status, 200); XCTAssertEqual(retry.data, bulk.data)
        let afterRetry = try await reopened.rowsPage(sameInstall, table: "notes").rows
        XCTAssertEqual(afterRetry.count, 1, "An idempotent retry must not delete later rows")
        let cleanup = await dispatcher.dispatch(bulkJob(4, key: "bulk-cleanup"))
        XCTAssertEqual(cleanup.status, 200)
        let audit = try await coreStore.hostActivity(agentFilter: .identified(writer.id), kinds: [.request], includeSettings: false)
        XCTAssertEqual(audit.map(\.status), [200, 200, 200, 400])
        XCTAssertTrue(audit.allSatisfy { $0.operation == HostOperation.appletsWrite.rawValue })
        await authority.setOwnerDirectEnabled(true)
        let (directWriter, directKey) = try await authority.createOwnerDirect(name: "Direct maintained writer",
            operations: [.appletsRead, .appletsWrite, .appletsManage], applets: [sameInstall.hostID])
        let (directReader, readerKey) = try await authority.createOwnerDirect(name: "Direct maintained reader",
            operations: [.appletsRead], applets: [sameInstall.hostID])
        let directSource = "100.80.4.1"
        let nextSource = "100.80.4.2"
        _ = try await authority.authenticateOwnerDirect(key: directKey, source: directSource)
        _ = try await authority.authenticateOwnerDirect(key: readerKey, source: directSource)
        func directJob(_ caller: HostAgent, request: HostRequest, source: String = "100.80.4.1") -> HostJob {
            let created = Int64(Date().timeIntervalSince1970 * 1000)
            return .init(id: UUID().uuidString, sequence: 0, agentID: caller.id, generation: caller.generation,
                deviceID: "maintained-fixture", mode: OwnerDirectHostService.mode, deadline: created + 30_000,
                createdAt: created, request: request, tailnetSource: source)
        }
        let readOnly = await dispatcher.dispatch(directJob(directReader, request: .init(method: "GET", path: base + "/data")))
        XCTAssertEqual(readOnly.status, 200)
        let directAdd = HostRequest(method: "POST", path: rowPath,
            body: ["row": ["message": "direct-maintained"]], idempotencyKey: "direct-maintained-row")
        let deniedAdd = await dispatcher.dispatch(directJob(directReader, request: directAdd))
        XCTAssertEqual(deniedAdd.status, 403)
        let allowedAdd = await dispatcher.dispatch(directJob(directWriter, request: directAdd))
        XCTAssertEqual(allowedAdd.status, 201)
        let directRow = try XCTUnwrap(JSONSerialization.jsonObject(with: allowedAdd.data) as? [String: Any])
        let directRowID = try XCTUnwrap(directRow["id"] as? String)
        let directDelete = HostRequest(method: "DELETE", path: rowPath + "/" + directRowID, idempotencyKey: "delete-direct-row")
        let stale = try await authority.admit(directJob(directWriter, request: directDelete))
        try await authority.setOwnerDirectPeers(directWriter.id, unlock: true)
        _ = try await authority.authenticateOwnerDirect(key: directKey, source: nextSource)
        do {
            _ = try await MaintainedAppletHost.handle(directDelete, agent: stale, controller: reopened,
                coreStore: coreStore, directAuthority: authority, authorize: {})
            XCTFail("A prepared direct mutation used its revoked peer at the maintained store.")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "wrong_device") }
        let beforeDirectDelete = try await reopened.rowsPage(sameInstall, table: "notes").rows
        XCTAssertEqual(beforeDirectDelete.compactMap { $0["message"]?.text }, ["direct-maintained"])

        let current = try await authority.admit(directJob(directWriter, request: directDelete, source: nextSource))
        do {
            try await authority.withDirectAppletMutation(current, operation: .appletsWrite, applet: sameInstall.hostID) { permit in
                await authority.setOwnerDirectEnabled(false)
                await authority.setOwnerDirectEnabled(true)
                try await library.withHostMutation { owned in
                    try permit.commit { try owned.deleteAgentRow(directRowID, in: sameInstall, table: "notes") }
                }
            }
            XCTFail("A staged maintained mutation committed after the listener stopped and restarted.")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "foreground_required") }
        await authority.setOwnerDirectEnabled(true)
        let afterStop = try await reopened.rowsPage(sameInstall, table: "notes").rows
        XCTAssertEqual(afterStop.compactMap { $0["message"]?.text }, ["direct-maintained"])
        let permittedDelete = await dispatcher.dispatch(directJob(directWriter, request: directDelete, source: nextSource))
        XCTAssertEqual(permittedDelete.status, 200)
        let afterDirectDelete = try await reopened.rowsPage(sameInstall, table: "notes").rows
        XCTAssertTrue(afterDirectDelete.isEmpty)
        let directPause = await dispatcher.dispatch(directJob(directWriter,
            request: .init(method: "POST", path: base + "/pause", body: ["paused": true], idempotencyKey: "direct-maintained-pause"),
            source: nextSource))
        XCTAssertEqual(directPause.status, 200)
        XCTAssertTrue(reopened.isPaused(sameInstall))
        let directResume = await dispatcher.dispatch(directJob(directWriter,
            request: .init(method: "POST", path: base + "/pause", body: ["paused": false], idempotencyKey: "direct-maintained-resume"),
            source: nextSource))
        XCTAssertEqual(directResume.status, 200)
        XCTAssertFalse(reopened.isPaused(sameInstall))
        let retainedPhotos = try await reopened.rowsPage(sameInstall, table: "photos").rows
        XCTAssertEqual(retainedPhotos.compactMap { $0["photo"]?.text }, [ref])
        // Three large rows stay below the row limit but exceed the CSV byte limit.
        var byteDatabase: OpaquePointer?
        XCTAssertEqual(sqlite3_open(database.path, &byteDatabase), SQLITE_OK)
        let byteOverflow = """
            WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<3)
            INSERT INTO applet_rows SELECT instance,'notes','host-byte-overflow-' || x,unixepoch(),unixepoch()+86400,
                json_object('message',replace(hex(zeroblob(7340032)),'00','x'))
            FROM local_applets,n WHERE key='tpl_native_note';
            """
        XCTAssertEqual(sqlite3_exec(byteDatabase, byteOverflow, nil, nil, nil), SQLITE_OK)
        var byteCount: OpaquePointer?
        XCTAssertEqual(sqlite3_prepare_v2(byteDatabase, "SELECT sum(length(json_extract(payload, '$.message'))) FROM applet_rows WHERE effect LIKE 'host-byte-overflow-%'", -1, &byteCount, nil), SQLITE_OK)
        XCTAssertEqual(sqlite3_step(byteCount), SQLITE_ROW)
        XCTAssertGreaterThan(sqlite3_column_int64(byteCount, 0), 20 * 1024 * 1024)
        sqlite3_finalize(byteCount)
        sqlite3_close(byteDatabase)
        do {
            _ = try await reopened.exportOwnerTableCSV(edition: sameInstall, table: "notes")
            XCTFail("Over-byte-limit owner CSV was returned")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.status, 413); XCTAssertEqual(error.code, "export_too_large") }
        do {
            _ = try await MaintainedAppletHost.handle(.init(method: "GET", path: base + "/data/export", query: ["format": "csv", "table": "notes"]), agent: reader, controller: reopened, coreStore: coreStore, authorize: {})
            XCTFail("Over-byte-limit API CSV was returned")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.status, 413); XCTAssertEqual(error.code, "export_too_large") }
        XCTAssertEqual(sqlite3_open(database.path, &byteDatabase), SQLITE_OK)
        XCTAssertEqual(sqlite3_exec(byteDatabase, "DELETE FROM applet_rows WHERE effect LIKE 'host-byte-overflow-%'", nil, nil, nil), SQLITE_OK)
        sqlite3_close(byteDatabase)
        // Synthetic over-limit data verifies that the host refuses rather than returning a partial CSV.
        var raw: OpaquePointer?
        XCTAssertEqual(sqlite3_open(database.path, &raw), SQLITE_OK)
        let overflow = """
            WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<10001)
            INSERT INTO applet_rows SELECT instance,'notes','host-overflow-' || x,unixepoch(),unixepoch()+86400,'{"message":"overflow"}'
            FROM local_applets,n WHERE key='tpl_native_note';
            """
        XCTAssertEqual(sqlite3_exec(raw, overflow, nil, nil, nil), SQLITE_OK)
        sqlite3_close(raw)
        do {
            _ = try await MaintainedAppletHost.handle(.init(method: "GET", path: base + "/data/export", query: ["format": "csv", "table": "notes"]), agent: reader, controller: reopened, coreStore: coreStore, authorize: {})
            XCTFail("Over-limit API CSV was returned")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.status, 413); XCTAssertEqual(error.code, "export_too_large") }
        let deletion = HostRequest(method: "DELETE", path: "/v1/applets/installed/" + sameInstall.hostID, idempotencyKey: "remove-fixture")
        do {
            _ = try await MaintainedAppletHost.handle(deletion, agent: reader, controller: reopened, coreStore: coreStore, authorize: {})
            XCTFail("Read-only agent removed an edition")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.status, 403) }
        let removed = try await MaintainedAppletHost.handle(deletion, agent: manager, controller: reopened, coreStore: coreStore, authorize: {})
        XCTAssertEqual(removed?.status, 200)
        XCTAssertEqual(reopened.editions.map(\.id), [otherEdition.id])
        try await library.install(reviewed)
        try await reopened.loadInstalled()
        let reinstalled = try XCTUnwrap(reopened.editions.first { $0.id == sameInstall.id })
        do { _ = try await library.open(sameInstall); XCTFail("Removed edition remained usable after reinstall") }
        catch let error as AppletRuntimeError {
            XCTAssertEqual(error, .invalid("That revision is not installed for this account"))
        }
        let remainingNotes = try await reopened.rowsPage(reinstalled, table: "notes").rows
        let remainingPhotos = try await reopened.gallery(gallery, edition: reinstalled)
        XCTAssertTrue(remainingNotes.isEmpty)
        XCTAssertTrue(remainingPhotos.isEmpty)
        do { _ = try await reopened.photo(ref, edition: reinstalled); XCTFail("Removed photo remained readable") }
        catch let error as AppletRuntimeError { XCTAssertEqual(error, .invalid("Photo unavailable")) }
        try await reopened.remove(sameInstall)
        XCTAssertTrue(reopened.editions.contains { $0.id == reinstalled.id }, "Stale removal must preserve a reinstallation")
        _ = try await library.open(reinstalled)
        let directRemove = await dispatcher.dispatch(directJob(directWriter, request: .init(method: "DELETE",
            path: "/v1/applets/installed/" + reinstalled.hostID, idempotencyKey: "direct-maintained-remove"), source: nextSource))
        XCTAssertEqual(directRemove.status, 200)
        XCTAssertFalse(reopened.editions.contains { $0.hostID == reinstalled.hostID })
    }
}

private actor HeldPortableCallback {
    var continuation: CheckedContinuation<Void, Never>?
    var holding: Bool { continuation != nil }
    func wait() async { await withCheckedContinuation { continuation = $0 } }
    func release() { let value = continuation; continuation = nil; value?.resume() }
}

extension MaintainedAppletRuntimeAcceptanceTests {
    func testCancellationKeepsExecutionUntilHeldCallbackSettles() async throws {
        let owner = PortableExecutionOwner(), first = HeldPortableCallback(), second = HeldPortableCallback()
        XCTAssertTrue(owner.start { await first.wait() })
        for _ in 0..<1000 { if await first.holding { break }; await Task.yield() }
        XCTAssertTrue(owner.running)
        owner.cancel()
        XCTAssertTrue(owner.running); XCTAssertTrue(owner.cancelling)
        XCTAssertFalse(owner.start { XCTFail("A second execution overlapped cancelled work") })
        await first.release()
        for _ in 0..<1000 { if !owner.running { break }; await Task.yield() }
        XCTAssertFalse(owner.running)
        var cancelledSecond = false
        XCTAssertTrue(owner.start { await second.wait(); cancelledSecond = Task.isCancelled })
        for _ in 0..<1000 { if await second.holding { break }; await Task.yield() }
        XCTAssertTrue(owner.running, "The previous attempt must not clear the new task")
        owner.cancel(); await second.release()
        for _ in 0..<1000 { if !owner.running { break }; await Task.yield() }
        XCTAssertTrue(cancelledSecond); XCTAssertFalse(owner.running)
    }
}
