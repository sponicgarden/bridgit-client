import BridgitAppletRuntime
import BridgitCore
import Combine
import CoreFoundation
import CryptoKit
import Foundation
#if DEBUG
import SQLite3
#endif

/// Native hosts use the maintained account/edition/instance store, never a second package copy in BridgitCore.
@MainActor final class PortableLibraryController: ObservableObject {
    @Published private(set) var editions: [LocalAppletLibrary.Edition] = []
    @Published private(set) var pausedInstallations = Set<String>()
    @Published private(set) var page: CatalogPage?
    @Published var problem: String?
    let cloud: WatchClient
    private let session: String?
    private let database: URL
    private let bindingRead: (String) -> String?
    private let bindingWrite: (String, String) -> Bool
    private var opened: LocalAppletLibrary?
    private var installationChanges: AnyCancellable?
    private static let installationStateChanged = Notification.Name("BridgitMaintainedInstallationStateChanged")

    init(cloud: WatchClient, database: URL? = nil,
         bindingRead: @escaping (String) -> String? = Keychain.get,
         bindingWrite: @escaping (String, String) -> Bool = { value, key in Keychain.set(value, for: key) }) {
        #if DEBUG
        let fixture = database == nil && PortableLibraryUIFixture.enabled
        let selectedCloud = fixture ? PortableLibraryUIFixture.cloud() : cloud
        let selectedDatabase = fixture ? PortableLibraryUIFixture.database : database
        #else
        let selectedCloud = cloud, selectedDatabase = database
        #endif
        self.cloud = selectedCloud; session = selectedCloud.session
        // Exact upstream location. Account is the verified server subject, not email or a guessed hash.
        self.database = selectedDatabase ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("AppletLibrary/installed.sqlite")
        self.bindingRead = bindingRead; self.bindingWrite = bindingWrite
        installationChanges = NotificationCenter.default.publisher(for: Self.installationStateChanged).sink { [weak self] _ in
            Task { @MainActor [weak self] in
                // Each observer reloads only its own verified account; the event
                // carries no account identifier, package or saved data.
                guard let self, self.session != nil else { return }
                try? await self.loadInstalled()
            }
        }
    }
    func requireCurrent() throws {
        try Task.checkCancellation()
        guard let session, session == cloud.session else { throw AppletRuntimeError.revoked }
    }
    func library() async throws -> LocalAppletLibrary {
        try requireCurrent()
        if let opened { return opened }
        let key = "library-" + SHA256.hash(data: Data(session!.utf8)).map { String(format: "%02x", $0) }.joined()
        let account: String
        if let saved = bindingRead(key) { account = saved }
        else {
            account = try await cloud.libraryAccount()
            try requireCurrent()
            guard account.range(of: #"\A[a-f0-9]{64}\z"#, options: .regularExpression) != nil,
                  bindingWrite(account, key) else { throw AppletRuntimeError.invalid("The verified library account could not be saved") }
        }
        try requireCurrent()
        let value = try LocalAppletLibrary(database: database, account: account)
        opened = value
        return value
    }
    func loadInstalled() async throws {
        let library = try await library()
        let rows = try await library.editions()
        var paused = Set<String>()
        for row in rows {
            if try await library.isPaused(row) { paused.insert(row.hostID) }
        }
        try requireCurrent(); editions = rows; pausedInstallations = paused
    }
    func isPaused(_ edition: LocalAppletLibrary.Edition) -> Bool { pausedInstallations.contains(edition.hostID) }
    func setPaused(_ paused: Bool, edition: LocalAppletLibrary.Edition,
                   commit: ((LocalAppletLibrary) async throws -> Void)? = nil) async throws {
        let library = try await library()
        try requireCurrent()
        if let commit { try await commit(library) }
        else { try await library.setPaused(paused, in: edition) }
        try await loadInstalled()
        NotificationCenter.default.post(name: Self.installationStateChanged, object: nil)
    }
    func catalog(query: String = "", after: String = "", owned: Bool = false) async throws {
        try requireCurrent()
        let result = try await cloud.catalog(query: query, after: after, owned: owned)
        try requireCurrent(); page = result
    }
    func review(_ entry: CatalogEntry) async throws -> CatalogRelease {
        try requireCurrent()
        let release = try await cloud.catalogRelease(entry)
        try requireCurrent(); return release
    }
    func install(_ release: RegistryPackage) async throws -> LocalAppletLibrary.Edition {
        let library = try await library()
        let files = try await cloud.packageMedia(release)
        try requireCurrent()
        try await library.install(release, media: files)
        try requireCurrent()
        guard let edition = try await library.installed(release.selection.key) else { throw AppletRuntimeError.invalid("Installation was not confirmed") }
        #if DEBUG
        if PortableLibraryUIFixture.enabled, database == PortableLibraryUIFixture.database,
           CommandLine.arguments.contains("-fixtureSavedAppletPhoto"), edition.id == "tpl_native_note",
           let item = release.package.cameraItems.first,
           try await library.rows(in: edition, table: "photos", limit: 1).isEmpty {
            // A known saved image for gallery presentation tests. No camera is activated.
            try await capturePhoto(item, edition: edition) { _, _ in
                guard let png = Data(base64Encoded: "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGPQqt0DAAI4AWQxmI8QAAAAAElFTkSuQmCC") else { throw AppletRuntimeError.invalid("Invalid fixture PNG") }
                return .init(jpeg: try LocalAppletModel.jpeg(png), camera: "default")
            }
        }
        if PortableLibraryUIFixture.enabled, database == PortableLibraryUIFixture.database,
           CommandLine.arguments.contains("-fixtureOwnerCSVRows"), edition.id == "tpl_native_note",
           try await library.rows(in: edition, table: "notes", limit: 1).isEmpty {
            try PortableLibraryUIFixture.seedOwnerCSVRows(database: database)
        }
        #endif
        try await loadInstalled()
        return edition
    }
    func remove(_ edition: LocalAppletLibrary.Edition, commit: ((LocalAppletLibrary) async throws -> Void)? = nil) async throws {
        let library = try await library()
        try requireCurrent()
        if let commit { try await commit(library) }
        else { try await library.remove(edition) }
        try requireCurrent()
        try await loadInstalled()
    }
    func reviewUpdate(_ release: RegistryPackage, replacing edition: LocalAppletLibrary.Edition) async throws -> LocalAppletLibrary.Update {
        let library = try await library()
        let update = try await cloud.reviewUpdate(to: release, from: edition, in: library)
        try requireCurrent(); return update
    }
    func apply(_ update: LocalAppletLibrary.Update) async throws -> LocalAppletLibrary.Edition {
        let library = try await library()
        let files = try await cloud.packageMedia(update.release)
        try requireCurrent()
        try await library.apply(update, media: files)
        try requireCurrent()
        guard let edition = try await library.installed(update.from.id) else { throw AppletRuntimeError.invalid("Updated installation was not confirmed") }
        try await loadInstalled(); return edition
    }
    func open(_ edition: LocalAppletLibrary.Edition) async throws -> LocalAppletLibrary.Instance {
        let library = try await library()
        let instance = try await library.open(edition)
        try requireCurrent(); return instance
    }
    func rowsPage(_ edition: LocalAppletLibrary.Edition, table: String, before: String? = nil) async throws -> LocalAppletLibrary.RowsPage {
        let library = try await library()
        #if DEBUG
        let delayedFixture = CommandLine.arguments.contains("-fixtureDelayRecordsPage")
        #else
        let delayedFixture = false
        #endif
        let page = try await library.rowsPage(in: edition, table: table, limit: delayedFixture ? 2 : 500, before: before)
        #if DEBUG
        if before != nil, delayedFixture {
            try await Task.sleep(nanoseconds: 8_000_000_000)
        }
        #endif
        try requireCurrent(); return page
    }
    func deleteOwnerRow(_ id: String, edition: LocalAppletLibrary.Edition, table: String) async throws {
        let library = try await library()
        try requireCurrent()
        try await library.deleteOwnerRow(id, in: edition, table: table)
        try requireCurrent()
    }
    @discardableResult func deleteOwnerRows(edition: LocalAppletLibrary.Edition, table: String) async throws -> Int {
        #if DEBUG
        if CommandLine.arguments.contains("-fixtureDelayRecordsPage") {
            try await Task.sleep(nanoseconds: 8_000_000_000)
        }
        #endif
        let library = try await library()
        try requireCurrent()
        let removed = try await library.deleteOwnerRows(in: edition, table: table)
        try requireCurrent(); return removed
    }
    func exportOwnerTableCSV(edition: LocalAppletLibrary.Edition, table: String) async throws -> Data {
        let library = try await library()
        let instance = try await library.open(edition)
        guard case .array(let tables)? = instance.package.definition["processing"]?["tables"],
              let schema = tables.first(where: { $0["id"]?.text == table }),
              let fields = schema["columns"]?.fields else { throw DeviceAPIError(404, "table_not_found", "Choose a declared table.") }
        let definitionsData = try JSONEncoder().encode(JSONValue.object(fields))
        guard let definitions = try JSONSerialization.jsonObject(with: definitionsData) as? [String: [String: Any]] else {
            throw AppletRuntimeError.invalid("Could not read the table columns.")
        }
        let columns = AppletPresentation.csvColumns(order: instance.columnOrder[table] ?? [], definitions: definitions)
        guard !columns.isEmpty else { throw DeviceAPIError(422, "invalid_export", "This table has no supported fields to export.") }
        try requireCurrent()
        let records: [[String: JSONValue]]
        do { records = try await library.ownerTableRows(in: edition, table: table) }
        catch let error as OwnerTableExportLimitError {
            throw DeviceAPIError(413, "export_too_large", error.localizedDescription)
        }
        let bytes = try JSONEncoder().encode(records)
        guard let rows = try JSONSerialization.jsonObject(with: bytes) as? [[String: Any]] else {
            throw AppletRuntimeError.invalid("Could not read the saved table.")
        }
        try requireCurrent()
        let data = AppletExport.csv(rows: rows, columns: columns.map { ($0.key, $0.label) })
        guard data.count <= 20 * 1024 * 1024 else { throw DeviceAPIError(413, "export_too_large", "This CSV exceeds the 20 MB export limit.") }
        return data
    }
    func gallery(_ piece: JSONValue, edition: LocalAppletLibrary.Edition) async throws -> [LocalAppletLibrary.GalleryPhoto] {
        let library = try await library()
        let photos = try await library.gallery(piece, in: edition)
        try requireCurrent()
        return photos
    }

    func photo(_ reference: String, edition: LocalAppletLibrary.Edition) async throws -> Data {
        let library = try await library()
        let bytes = try await library.photo(reference, in: edition)
        try requireCurrent()
        return bytes
    }

    func capturePhoto(_ item: JSONValue, edition: LocalAppletLibrary.Edition,
                      review: @escaping @MainActor @Sendable (String, AppletPackage.CameraSize) async throws -> LocalAppletLibrary.ReviewedPhoto) async throws {
        let library = try await library()
        _ = try await library.captureCardPhoto(item, in: edition) { [weak self] lens, size in
            guard let self else { throw CancellationError() }
            try await self.requireCurrent()
            let photo = try await review(lens, size)
            try await self.requireCurrent()
            return photo
        }
        try requireCurrent()
    }
    func exportCSV(_ button: AppletPackage.CSVButton, edition: LocalAppletLibrary.Edition) async throws -> Data {
        let library = try await library()
        let records = try await library.csvRows(in: edition, table: button.table, filter: button.filter)
        let bytes = try JSONEncoder().encode(records)
        guard let rows = try JSONSerialization.jsonObject(with: bytes) as? [[String: Any]] else {
            throw AppletRuntimeError.invalid("Could not read the saved table.")
        }
        try requireCurrent()
        let data = AppletExport.csv(rows: rows, columns: button.columns)
        guard data.count <= 20 * 1024 * 1024 else {
            throw AppletRuntimeError.invalid("This CSV exceeds the 20 MB export limit.")
        }
        return data
    }
    func run(_ name: String, edition: LocalAppletLibrary.Edition,
             present: @escaping @Sendable (LocalAppletLibrary.Notice) async throws -> Void) async throws -> FlowOutcome {
        let library = try await library()
        try requireCurrent()
        return try await library.runButton(name, in: edition) { [weak self] notice in
            guard let self else { throw CancellationError() }
            try await self.requireCurrent()
            try await present(notice)
        }
    }
}

/// Cancellation retains ownership until the attempt settles; a stale completion cannot clear a newer attempt.
@MainActor final class PortableExecutionOwner: ObservableObject {
    @Published private(set) var running = false
    @Published private(set) var cancelling = false
    private var attempt: UUID?
    private var work: Task<Void, Never>?
    @discardableResult func start(_ action: @escaping @MainActor () async -> Void) -> Bool {
        guard work == nil else { return false }
        let id = UUID(); attempt = id; running = true; cancelling = false
        work = Task {
            defer {
                if attempt == id { work = nil; attempt = nil; running = false; cancelling = false }
            }
            await action()
        }
        return true
    }
    func cancel() { if work != nil { cancelling = true; work?.cancel() } }
}

#if DEBUG
/// Explicit developer fixture only: normal catalog/review/install UI, synthetic registry and isolated persistent database.
private enum PortableLibraryUIFixture {
    static var enabled: Bool { CommandLine.arguments.contains("-fixturePortableLibrary") }
    static var database: URL {
        let arguments = CommandLine.arguments
        let raw = arguments.firstIndex(of: "-fixturePortableRun").flatMap { $0 + 1 < arguments.count ? arguments[$0 + 1] : nil } ?? "default"
        let run = raw.range(of: "^[a-zA-Z0-9_-]{1,64}$", options: .regularExpression) != nil ? raw : "default"
        return FileManager.default.temporaryDirectory.appendingPathComponent("BridgitPortableLibrary-" + run).appendingPathComponent("AppletLibrary/installed.sqlite")
    }
    @MainActor static func cloud() -> WatchClient {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [PortableLibraryUIProtocol.self]
        return WatchClient(base: URL(string: "https://portable-library-fixture.invalid")!, session: "fixture-portable-library", http: URLSession(configuration: configuration))
    }
    /// Test-only paginated export fixture; does not call a camera or a production account.
    static func seedOwnerCSVRows(database: URL) throws {
        var db: OpaquePointer?
        guard sqlite3_open_v2(database.path, &db, SQLITE_OPEN_READWRITE | SQLITE_OPEN_FULLMUTEX, nil) == SQLITE_OK,
              let db else { throw AppletRuntimeError.invalid("Fixture database unavailable.") }
        defer { sqlite3_close(db) }
        sqlite3_busy_timeout(db, 5000)
        guard sqlite3_exec(db, "BEGIN IMMEDIATE", nil, nil, nil) == SQLITE_OK else { throw AppletRuntimeError.invalid("Fixture transaction failed.") }
        do {
            let sql = "INSERT INTO applet_rows(instance,table_id,effect,at,expires,payload) SELECT instance,'notes',?,unixepoch(),unixepoch()+86400,? FROM local_applets WHERE key='tpl_native_note'"
            var statement: OpaquePointer?
            guard sqlite3_prepare_v2(db, sql, -1, &statement, nil) == SQLITE_OK, let statement else { throw AppletRuntimeError.invalid("Fixture insert unavailable.") }
            defer { sqlite3_finalize(statement) }
            let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
            for index in 0...600 {
                let text = index == 0 ? "Line 1, \"quoted\"\nLine 2" : String(format: "row-%04d", index)
                let value: [String: Any] = ["z_text": text, "n_count": index == 0 ? 12.5 : Double(index),
                                             "b_enabled": index == 0, "a_empty": NSNull(), "p_photo": NSNull()]
                let payload = try JSONSerialization.data(withJSONObject: value, options: [.sortedKeys])
                sqlite3_reset(statement); sqlite3_clear_bindings(statement)
                sqlite3_bind_text(statement, 1, "owner-csv-\(index)", -1, transient)
                _ = payload.withUnsafeBytes { sqlite3_bind_blob(statement, 2, $0.baseAddress, Int32($0.count), transient) }
                guard sqlite3_step(statement) == SQLITE_DONE, sqlite3_changes(db) == 1 else { throw AppletRuntimeError.invalid("Fixture insert failed.") }
            }
            guard sqlite3_exec(db, "COMMIT", nil, nil, nil) == SQLITE_OK else { throw AppletRuntimeError.invalid("Fixture commit failed.") }
        } catch { sqlite3_exec(db, "ROLLBACK", nil, nil, nil); throw error }
    }
    static func response(_ request: URLRequest) throws -> Data {
        let entry: [String: Any] = ["key": "tpl_native_note", "id": "native-note", "revision": 7, "name": "Native note", "summary": "Synthetic local records"]
        let id = "a-sorta-fairytale-about-me"
        let story: [String: Any] = ["key": id, "id": id, "revision": 1, "name": "A Sorta Fairytale About Me", "summary": "A story about your surroundings"]
        let includeStory = CommandLine.arguments.contains("-fixtureFairytaleRegistry")
        let object: [String: Any]
        switch request.url?.path {
        case "/v1/templates/account": object = ["library_account": String(repeating: "d", count: 64)]
        case "/v1/templates/catalog":
            let query = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems ?? []
            let owned = query.contains { $0.name == "scope" && $0.value == "owned" }
            let search = query.first { $0.name == "q" }?.value ?? ""
            let entries = includeStory ? [story, entry] : [entry]
            let matches = entries.filter { search.isEmpty || ($0["name"] as? String)?.localizedCaseInsensitiveContains(search) == true }
            object = ["format": "bridgit-applet-catalog/1", "scope": owned ? "owned" : "public", "applets": matches, "next": NSNull()]
        case "/v1/templates/a-sorta-fairytale-about-me/1" where includeStory:
            let data: Data
            if let encoded = ProcessInfo.processInfo.environment["BRIDGIT_3347_FAIRYTALE_PACKAGE"] {
                guard let fixture = Data(base64Encoded: encoded), fixture.count <= 131_072 else { throw URLError(.cannotDecodeRawData) }
                data = fixture
            } else {
                guard let file = Bundle.main.url(forResource: id, withExtension: "json", subdirectory: "Applets") else {
                    throw URLError(.fileDoesNotExist)
                }
                data = try Data(contentsOf: file)
            }
            let definition = try JSONSerialization.jsonObject(with: data)
            object = ["key": id, "revision": 1, "state": "published", "definition": definition]
        case "/v1/templates/tpl_native_note/7":
            if let encoded = ProcessInfo.processInfo.environment["BRIDGIT_ORDERED_REGISTRY_FIXTURE"] {
                guard let data = Data(base64Encoded: encoded), data.count <= 131_072 else { throw URLError(.cannotDecodeRawData) }
                return data
            }
            object = ["key": "tpl_native_note", "revision": 7, "state": "published", "definition": [
                "format": "bridgit-template/1", "kind": "applet", "id": "native-note", "version": "1.0.0", "name": "Native note",
                "runtime": ["adapter": "bridgit.package.v1", "applet_key": "native-note", "supports_dynamic_execution": false],
                "config_schema": ["type": "object", "properties": [:], "additionalProperties": false], "defaults": [:],
                "permissions": ["required": ["workflows", "applet_data", "interactions", "camera"], "optional": [], "remote": [], "automatic_grants": false],
                "ui": ["card": ["sources": ["photos": ["table": "photos"]], "pieces": [["type": "buttons", "items": [
                    ["id": "take", "label": "Photograph note", "do": ["camera": ["table": "photos", "photo": "photo", "row": ["status": "new"]]]],
                    ["label": "Share notes", "do": ["step": ["type": "share", "as": "csv", "table": "notes"]]]
                ]], ["type": "gallery", "photos": ["$source": "photos"], "photo": "photo", "caption": "status", "words": ["new": "New"], "empty": "No photos yet."]]]],
                "processing": ["tables": [["id": "notes", "columns": ["message": ["type": "text"]], "max_rows": 1000, "keep_days": 90],
                    ["id": "photos", "columns": ["photo": ["type": "photo"], "status": ["type": "text"]], "keep_days": 90, "max_rows": 100]],
                    "workflow": ["name": "Save a note", "trigger": ["type": "button"], "steps": [
                        ["id": "save", "type": "record", "table": "notes", "row": ["message": "maintained-native-flow-complete"]],
                        ["id": "done", "type": "notice", "text": "Saved locally"]]]
            ]]]
        default: throw URLError(.resourceUnavailable)
        }
        return try JSONSerialization.data(withJSONObject: object)
    }
}
private final class PortableLibraryUIProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "portable-library-fixture.invalid" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        do {
            guard request.value(forHTTPHeaderField: "Authorization") == "Bearer fixture-portable-library" else { throw URLError(.userAuthenticationRequired) }
            let data = try PortableLibraryUIFixture.response(request)
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/json"])!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data); client?.urlProtocolDidFinishLoading(self)
        } catch { client?.urlProtocol(self, didFailWithError: error) }
    }
    override func stopLoading() {}
}
#endif

/// The shutter starts only after every visible tick has elapsed.
@MainActor enum PortablePhotoCountdown {
    static func run(_ seconds: Int, update: (Int) -> Void,
                    tick: () async throws -> Void = { try await Task.sleep(for: .seconds(1)) }) async throws {
        guard (0...10).contains(seconds) else { throw AppletRuntimeError.invalid("Unsupported photo countdown.") }
        for remaining in (0..<seconds).reversed() {
            try Task.checkCancellation(); update(remaining + 1); try await tick()
        }
        try Task.checkCancellation(); update(0)
    }
}

/// The card owns the temporary movie until it is deleted or closed.
final class PortableCardMovie {
    let url: URL
    init(url: URL) { self.url = url }
    deinit { try? FileManager.default.removeItem(at: url) }
}

/// An open installed card owns only reviewed, in-memory captures. This object is
/// never shared between editions or card presentations.
@MainActor final class PortableCardCaptureSession: ObservableObject {
    struct ReviewedMovie {
        let movie: PortableCardMovie
        let frames: [Data]
        let camera: String
    }
    private var images: [String: Data] = [:]
    private var movies: [String: PortableCardMovie] = [:]
    private var generation = UUID()
    private var active = false
    private var takingPhoto = false
    func activate() { if !active { active = true; generation = UUID() } }
    func close() { active = false; generation = UUID(); images.removeAll(); movies.removeAll() }
    private func check(_ revision: UUID) throws {
        try Task.checkCancellation()
        guard active, revision == generation else { throw CancellationError() }
    }
    private func require(_ package: AppletPackage, _ names: [String]) throws {
        guard package.cardHTML != nil, case .array(let grants)? = package.definition["permissions"]?["required"],
              names.allSatisfy({ grants.contains(.string($0)) }) else { throw AppletRuntimeError.unavailable("applet captures") }
    }
    func action(_ request: JSONValue,
                authorize: @MainActor () async throws -> AppletPackage,
                table: @MainActor (JSONValue) async throws -> JSONValue,
                reviewPhoto: @MainActor (String, Int, String?) async throws -> LocalAppletLibrary.ReviewedPhoto,
                reviewVideo: @MainActor (String, Int, Int, Int, String?) async throws -> ReviewedMovie = { _,_,_,_,_ in throw AppletRuntimeError.unavailable("video recording") },
                reviewExistingVideo: @MainActor (PortableCardMovie) async throws -> Bool = { _ in throw AppletRuntimeError.unavailable("video review") }) async throws -> JSONValue {
        let revision = generation
        try check(revision)
        let path = request["path"]?.text ?? ""
        if path.hasPrefix("/v1/applet/tables/") { let value = try await table(request); try check(revision); return value }
        let package = try await authorize()
        try check(revision)
        guard package.cardHTML != nil, case .array(let grants)? = package.definition["permissions"]?["required"],
              grants.contains(.string("captures")) else { throw AppletRuntimeError.unavailable("applet captures") }
        if path == "/v1/camera/snapshot" {
            guard grants.contains(.string("camera")), request["method"] == .string("POST"), !takingPhoto,
                  let body = request["body"]?.fields,
                  body.keys.allSatisfy({ ["camera","viewfinder","countdown_seconds","max_edge_px","quality","store","return_image","instructions"].contains($0) }),
                  let lens = body["camera"]?.text, ["front","rear"].contains(lens),
                  body["viewfinder"] == nil || body["viewfinder"] == .bool(true),
                  body["store"] == .array([.string("memory")]), body["return_image"] == .bool(false),
                  case .number(let delay) = body["countdown_seconds"] ?? .number(0), let countdown = Int(exactly: delay), (0...10).contains(countdown),
                  case .number(let edge) = body["max_edge_px"] ?? .number(960), (64...1280).contains(edge), edge.rounded() == edge,
                  case .number(let quality) = body["quality"] ?? .number(80), (1...100).contains(quality), quality.rounded() == quality,
                  body["instructions"] == nil || (body["instructions"]?.text?.count ?? 501) <= 500 else {
                throw AppletRuntimeError.invalid("This camera request is not supported.")
            }
            guard images.count < 24 else { throw AppletRuntimeError.invalid("Card capture limit reached.") }
            takingPhoto = true; defer { takingPhoto = false }
            let reviewed = try await reviewPhoto(lens, countdown, body["instructions"]?.text)
            try check(revision)
            let updated = try await authorize(); try check(revision); try require(updated, ["camera", "captures"])
            let jpeg = try LocalAppletModel.jpeg(reviewed.jpeg, maxEdge: Int(edge), quality: quality / 100)
            guard jpeg.count <= 2_000_000 else { throw AppletRuntimeError.invalid("The photo is too large.") }
            let id = UUID().uuidString.lowercased()
            images[id] = jpeg
            return .object(["camera":.string(reviewed.camera),"capture":.object(["capture_id":.string(id)])])
        }
        if path == "/v1/camera/video" {
            guard grants.contains(.string("camera")), grants.contains(.string("video")),
                  request["method"] == .string("POST"), !takingPhoto,
                  let body = request["body"]?.fields,
                  body.keys.allSatisfy({ ["camera","duration_seconds","countdown_seconds","audio","resolution","extract_frames","viewfinder","instructions"].contains($0) }),
                  let lens = body["camera"]?.text, ["front","rear"].contains(lens),
                  body["viewfinder"] == nil || body["viewfinder"] == .bool(true),
                  body["audio"] == .bool(false), body["resolution"] == .string("720p"),
                  case .number(let duration) = body["duration_seconds"] ?? .number(10), let seconds = Int(exactly: duration), (1...30).contains(seconds),
                  case .number(let delay) = body["countdown_seconds"] ?? .number(0), let countdown = Int(exactly: delay), (0...10).contains(countdown),
                  case .number(let samples)? = body["extract_frames"], let count = Int(exactly: samples), (1...8).contains(count),
                  body["instructions"] == nil || (body["instructions"]?.text?.count ?? 501) <= 500,
                  movies.count < 2, images.count + count <= 24 else { throw AppletRuntimeError.invalid("This video request is not supported.") }
            takingPhoto = true; defer { takingPhoto = false }
            let reviewed = try await reviewVideo(lens, seconds, countdown, count, body["instructions"]?.text)
            try check(revision); let updated = try await authorize(); try check(revision); try require(updated, ["camera", "captures", "video"])
            guard reviewed.frames.count == count, reviewed.frames.allSatisfy({ $0.starts(with: [0xff,0xd8,0xff]) && $0.count <= 2_000_000 }) else { throw AppletRuntimeError.invalid("Recorded frames are unavailable.") }
            let videoID = UUID().uuidString.lowercased()
            movies[videoID] = reviewed.movie
            let refs = reviewed.frames.map { frame -> JSONValue in
                let id = UUID().uuidString.lowercased(); images[id] = frame
                return .object(["capture_id":.string(id)])
            }
            return .object(["video_capture_id":.string(videoID),"video_saved":.bool(true),"frames":.array(refs),"count":.number(Double(count)),"camera":.string(reviewed.camera)])
        }
        if path == "/v1/camera/video/review" {
            guard grants.contains(.string("camera")), grants.contains(.string("video")), request["method"] == .string("POST"), !takingPhoto,
                  let body = request["body"]?.fields, body.count == 1,
                  let id = body["capture_id"]?.text, let movie = movies[id] else { throw AppletRuntimeError.invalid("Video unavailable.") }
            takingPhoto = true; defer { takingPhoto = false }
            let delete = try await reviewExistingVideo(movie)
            try check(revision); let updated = try await authorize(); try check(revision); try require(updated, ["camera", "captures", "video"])
            if delete { movies.removeValue(forKey: id) }
            return .object(["deleted":.bool(delete)])
        }
        if path.hasPrefix("/v1/captures/") {
            let id = String(path.dropFirst("/v1/captures/".count))
            guard UUID(uuidString: id) != nil, request["body"] == nil else { throw AppletRuntimeError.invalid("Invalid capture reference.") }
            let method = request["method"] ?? .string("GET")
            if method == .string("DELETE") {
                guard request["query"] == nil else { throw AppletRuntimeError.invalid("Invalid capture request.") }
                let removedImage = images.removeValue(forKey: id) != nil
                let removedMovie = movies.removeValue(forKey: id) != nil
                return .object(["deleted":.bool(removedImage || removedMovie)])
            }
            guard method == .string("GET"), let jpeg = images[id],
                  request["query"] == nil || request["query"]?.fields?.keys.allSatisfy({ $0 == "max_edge_px" }) == true,
                  case .number(let edge) = request["query"]?["max_edge_px"] ?? .number(960), (64...1280).contains(edge), edge.rounded() == edge else {
                throw AppletRuntimeError.invalid("Capture unavailable or invalid preview request.")
            }
            let preview = try LocalAppletModel.jpeg(jpeg, maxEdge: Int(edge), quality: 0.8)
            return .object(["data_base64":.string(preview.base64EncodedString()),"mime_type":.string("image/jpeg")])
        }
        throw AppletRuntimeError.invalid("This action is not available on this device yet.")
    }
}

/// Bridges the maintained account store into the existing authenticated host routes.
/// HostDispatcher still owns admission, action idempotency and the activity audit.
@MainActor enum MaintainedAppletHost {
    static func viewerNames(_ controller: PortableLibraryController) async throws -> [[String: Any]] {
        try await controller.loadInstalled()
        try controller.requireCurrent()
        return controller.editions.map { ["id": $0.hostID, "name": $0.name] }
    }

    static func installed(_ controller: PortableLibraryController, agent: HostAgent) async throws -> [[String: Any]] {
        try await controller.loadInstalled()
        var result: [[String: Any]] = []
        let library = try await controller.library()
        for edition in controller.editions where agent.allows(.appletsRead) && agent.allowsApplet(edition.hostID, operation: .appletsRead) {
            let instance = try await controller.open(edition)
            result.append(try await describe(edition, instance: instance, agent: agent, paused: library.isPaused(edition)))
        }
        try controller.requireCurrent()
        return result
    }

    private static func integer(_ value: Any?, fallback: Int, range: ClosedRange<Int>) throws -> Int {
        guard let value else { return fallback }
        let parsed: Int?
        if let text = value as? String { parsed = Int(text) }
        else if let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID() { parsed = Int(exactly: number.doubleValue) }
        else { parsed = nil }
        guard let parsed, range.contains(parsed) else { throw DeviceAPIError(422, "invalid_parameter", "Integer parameter is outside the supported range.") }
        return parsed
    }

    private static func describe(_ edition: LocalAppletLibrary.Edition, instance: LocalAppletLibrary.Instance, agent: HostAgent, paused: Bool) throws -> [String: Any] {
        let package = instance.package
        let config = try JSONSerialization.jsonObject(with: JSONEncoder().encode(instance.config))
        let operations: [(String, HostOperation)] = [("read", .appletsRead), ("data", .appletsRead), ("rows", .appletsRead),
                                                    ("rows_write", .appletsWrite), ("photos", .appletsMediaRead), ("pause", .appletsManage), ("remove", .appletsManage)]
        let allowed = operations.filter {
            agent.allows($0.1) && agent.allowsApplet(edition.hostID, operation: $0.1)
            && ($0.0 != "pause" || canPause(edition, agent: agent))
        }.map { $0.0 }
        return ["id": edition.hostID, "package_id": package.id, "registry_key": edition.id,
         "revision": edition.revision, "name": edition.name, "version": package.version, "config": config,
         "local": true, "source": "maintained_library", "status": paused ? "paused" : "active",
         "supported_operations": operations.map { $0.0 }, "allowed_operations": allowed,
         "export_formats": ["json", "csv"], "data_supported": true,
         "data_url": "/v1/applets/installed/" + edition.hostID + "/data"]
    }

    // Unlike read/data access, pause changes execution. An owner-assigned
    // installation scope is required unless the agent is an administrator.
    private static func canPause(_ edition: LocalAppletLibrary.Edition, agent: HostAgent) -> Bool {
        agent.operations.contains(.administrator) || agent.applets.contains(edition.hostID)
    }

    static func handle(_ request: HostRequest, agent: HostAgent, controller: PortableLibraryController,
                       coreStore: AppletStore, directAuthority: HostAuthority? = nil,
                       authorize: () async throws -> Void) async throws -> HostResponse? {
        do {
            return try await perform(request, agent: agent, controller: controller, coreStore: coreStore,
                                     directAuthority: directAuthority, authorize: authorize)
        }
        catch let error as AppletRuntimeError {
            switch error {
            case .revoked: throw DeviceAPIError(403, "account_changed", "The library account is no longer authorized.")
            case .paused: throw DeviceAPIError(409, "applet_paused", error.localizedDescription)
            case .executionStopped: throw DeviceAPIError(409, "applet_run_stopped", error.localizedDescription)
            case .invalid(let message): throw DeviceAPIError(422, "invalid_parameter", message)
            case .unavailable: throw DeviceAPIError(501, "unsupported_operation", "This applet operation is unavailable on this device.")
            case .missingReference: throw DeviceAPIError(404, "record_not_found", "The saved record is unavailable.")
            }
        }
    }

    private static func mutate<T: Sendable>(_ library: LocalAppletLibrary, directAuthority: HostAuthority?,
        agent: HostAgent, operation: HostOperation, applet: String,
        effect: @escaping @Sendable (isolated LocalAppletLibrary) throws -> T) async throws -> T {
        if agent.ownerDirectPeers != nil {
            guard let directAuthority else {
                throw DeviceAPIError(403, "agent_not_allowed", "Current direct authority is required for this applet change.")
            }
            return try await directAuthority.withDirectAppletMutation(agent, operation: operation, applet: applet) { permit in
                try await library.withHostMutation { owned in try permit.commit { try effect(owned) } }
            }
        }
        return try await library.withHostMutation(effect)
    }

    private static func perform(_ request: HostRequest, agent: HostAgent, controller: PortableLibraryController,
                                coreStore: AppletStore, directAuthority: HostAuthority?,
                                authorize: () async throws -> Void) async throws -> HostResponse? {
        let parts = request.path.split(separator: "/").map(String.init)
        guard parts.count >= 4, Array(parts.prefix(3)) == ["v1", "applets", "installed"],
              parts[3].hasPrefix("library-") else { return nil }
        try await authorize()
        try await controller.loadInstalled()
        guard let edition = controller.editions.first(where: { $0.hostID == parts[3] }) else { return nil }
        guard let operation = request.route?.0, agent.allows(operation), agent.allowsApplet(edition.hostID, operation: operation) else {
            throw DeviceAPIError(403, "agent_not_allowed", "This agent cannot access this installed applet.")
        }
        if (try? await coreStore.package(edition.hostID)) != nil {
            throw DeviceAPIError(409, "applet_identity_conflict", "Two applet stores reported the same installation identity.")
        }
        if request.method == "POST", parts.count == 5, parts[4] == "pause" {
            guard canPause(edition, agent: agent) else {
                throw DeviceAPIError(403, "agent_not_allowed", "The owner has not assigned this applet to this agent.")
            }
            guard request.query.isEmpty, Set(request.body.keys) == ["paused"],
                  let value = request.body["paused"] as? NSNumber, CFGetTypeID(value) == CFBooleanGetTypeID() else {
                throw DeviceAPIError(422, "invalid_parameter", "Supply only a paused boolean.")
            }
            let library = try await controller.library()
            let instance = try await controller.open(edition)
            try await authorize(); try controller.requireCurrent()
            let pausedValue = value.boolValue
            try await controller.setPaused(pausedValue, edition: edition) { target in
                try await mutate(target, directAuthority: directAuthority, agent: agent, operation: operation, applet: edition.hostID) {
                    try $0.setPaused(pausedValue, in: edition)
                }
            }
            let paused = try await library.isPaused(edition)
            try await authorize(); try controller.requireCurrent()
            return try .json(describe(edition, instance: instance, agent: agent, paused: paused))
        }
        if operation == .appletsWrite, (parts.count == 7 || parts.count == 8), parts[4] == "tables", parts[6] == "rows" {
            guard request.query.isEmpty else { throw DeviceAPIError(422, "invalid_parameter", "Row changes take no query.") }
            let library = try await controller.library()
            try await authorize(); try controller.requireCurrent()
            if request.method == "DELETE", parts.count == 7, request.body.isEmpty {
                let instance = try await library.open(edition)
                guard case .array(let tables)? = instance.package.definition["processing"]?["tables"],
                      tables.contains(where: { $0["id"]?.text == parts[5] }) else {
                    throw DeviceAPIError(404, "table_not_found", "Choose a declared table.")
                }
                try await authorize(); try controller.requireCurrent()
                let count = try await mutate(library, directAuthority: directAuthority, agent: agent, operation: operation, applet: edition.hostID) {
                    try $0.deleteOwnerRows(in: edition, table: parts[5])
                }
                try controller.requireCurrent()
                return try .json(["deleted": count, "table": parts[5]])
            }
            if request.method == "DELETE", parts.count == 8, request.body.isEmpty {
                try await mutate(library, directAuthority: directAuthority, agent: agent, operation: operation, applet: edition.hostID) {
                    try $0.deleteAgentRow(parts[7], in: edition, table: parts[5])
                }
                try controller.requireCurrent()
                return try .json(["deleted": true, "row_id": parts[7]])
            }
            let field = request.method == "POST" ? "row" : "set"
            guard (request.method == "POST" && parts.count == 7) || (request.method == "PATCH" && parts.count == 8),
                  Set(request.body.keys) == [field], let values = request.body[field] as? [String: Any] else {
                throw DeviceAPIError(422, "invalid_parameter", "Supply only the declared row or set object.")
            }
            let typed = try JSONDecoder().decode([String: JSONValue].self, from: JSONSerialization.data(withJSONObject: values))
            let row = try await mutate(library, directAuthority: directAuthority, agent: agent, operation: operation, applet: edition.hostID) {
                try $0.writeAgentRow(typed, in: edition, table: parts[5], updating: parts.count == 8 ? parts[7] : nil)
            }
            try controller.requireCurrent()
            let data = try JSONEncoder().encode(row)
            return .init(status: request.method == "POST" ? 201 : 200, data: data)
        }
        guard request.body.isEmpty else { throw DeviceAPIError(422, "invalid_parameter", "Reads and removal take no body.") }
        let library = try await controller.library()
        if request.method == "GET", parts.count >= 5 {
            let base = "/v1/applets/installed/" + edition.hostID
            if parts[4] == "data", parts.count == 5 || (parts.count == 6 && parts[5] == "export") {
                guard Set(request.query.keys).isSubset(of: ["format", "table"]) else {
                    throw DeviceAPIError(422, "invalid_parameter", "Use format and table for saved-data exports.")
                }
                let csv = request.query["format"] as? String == "csv"
                if csv {
                    guard parts.count == 6, let table = request.query["table"] as? String,
                          table.range(of: "^[a-z][a-z0-9_]{0,31}$", options: .regularExpression) != nil else {
                        throw DeviceAPIError(422, "invalid_export", "Choose a declared table for CSV export.")
                    }
                } else {
                    guard request.query["format"] == nil || request.query["format"] as? String == "json", request.query["table"] == nil else {
                        throw DeviceAPIError(422, "invalid_parameter", "JSON discovery exports all declared tables.")
                    }
                }
                if csv {
                    let table = request.query["table"] as! String
                    let data = try await controller.exportOwnerTableCSV(edition: edition, table: table)
                    try await authorize(); try controller.requireCurrent()
                    return .init(contentType: "text/csv; charset=utf-8", data: data,
                                 headers: ["Content-Disposition": "attachment; filename=\"" + edition.hostID + ".csv\"", "Cache-Control": "no-store"])
                }
                let bytes = try await library.savedDataSnapshot(in: edition)
                let snapshot = try JSONSerialization.jsonObject(with: bytes) as! [String: Any]
                var result = AppletDataContract.make(snapshot: snapshot, applet: edition.hostID, includeRows: parts.count == 6,
                                                     mediaAllowed: agent.allows(.appletsMediaRead) && agent.allowsApplet(edition.hostID, operation: .appletsMediaRead))
                if agent.allows(.appletsWrite), agent.allowsApplet(edition.hostID, operation: .appletsWrite),
                   var tables = result["tables"] as? [[String: Any]] {
                    for index in tables.indices {
                        if let table = tables[index]["id"] as? String {
                            tables[index]["delete_all_url"] = base + "/tables/" + table + "/rows"
                            tables[index]["delete_all_method"] = "DELETE"
                        }
                    }
                    result["tables"] = tables
                }
                let sources = snapshot["native_sources"] as? [String: Any] ?? [:]
                result["native_sources"] = sources; result["complete"] = sources.isEmpty
                result["source"] = "maintained_library"
                try await authorize(); try controller.requireCurrent()
                return try .json(result)
            }
            if parts.count == 7, parts[4] == "tables", parts[6] == "rows" {
                guard Set(request.query.keys).isSubset(of: ["limit", "since"]), request.query["since"] == nil || request.query["since"] is String else {
                    throw DeviceAPIError(422, "invalid_parameter", "Use limit and the previous next cursor as since.")
                }
                let instance = try await controller.open(edition)
                guard case .array(let declarations) = instance.package.definition["processing"]?["tables"], declarations.contains(where: { $0["id"]?.text == parts[5] }) else {
                    throw DeviceAPIError(404, "table_not_found", "This applet has no such table.")
                }
                let page = try await library.rowsPage(in: edition, table: parts[5], limit: integer(request.query["limit"], fallback: 100, range: 1...200), before: request.query["since"] as? String)
                let rows = try JSONSerialization.jsonObject(with: JSONEncoder().encode(page.rows))
                try await authorize(); try controller.requireCurrent()
                return try .json(["rows": rows, "next": page.next as Any? ?? NSNull(), "applet": edition.hostID, "table": parts[5], "rows_url": base + "/tables/" + parts[5] + "/rows"])
            }
            if parts.count == 6, parts[4] == "photos" {
                guard Set(request.query.keys).isSubset(of: ["offset", "length"]) else { throw DeviceAPIError(422, "invalid_parameter", "Use offset and length for photo chunks.") }
                let bytes = try await controller.photo("kept:" + parts[5], edition: edition)
                let offset = try integer(request.query["offset"], fallback: 0, range: 0...Int.max)
                guard offset < bytes.count else { throw DeviceAPIError(416, "invalid_range", "Offset is outside this photo.") }
                try await authorize(); try controller.requireCurrent()
                if !request.query.isEmpty {
                    let length = try integer(request.query["length"], fallback: 2 * 1024 * 1024, range: 1...(2 * 1024 * 1024))
                    let end = offset + min(length, bytes.count - offset)
                    return .init(status: 206, contentType: "application/octet-stream", data: bytes.subdata(in: offset..<end), headers: ["Content-Range": "bytes \(offset)-\(end - 1)/\(bytes.count)", "Cache-Control": "no-store"])
                }
                guard bytes.count <= 5 * 1024 * 1024 else { throw DeviceAPIError(413, "photo_requires_chunks", "Read this photo using offset and length.") }
                return .init(contentType: "image/jpeg", data: bytes, headers: ["Cache-Control": "no-store"])
            }
        }
        guard request.query.isEmpty else {
            throw DeviceAPIError(422, "invalid_parameter", "Installation reads and removal take no query.")
        }
        if parts.count == 4, request.method == "GET" {
            let instance = try await controller.open(edition)
            let paused = try await library.isPaused(edition)
            try await authorize(); try controller.requireCurrent()
            return try .json(describe(edition, instance: instance, agent: agent, paused: paused))
        }
        if parts.count == 4, request.method == "DELETE" {
            try await authorize()
            try controller.requireCurrent()
            try await controller.remove(edition) { library in
                try await mutate(library, directAuthority: directAuthority, agent: agent, operation: operation, applet: edition.hostID) {
                    try $0.remove(edition)
                }
            }
            return try .json(["removed": true, "id": edition.hostID, "left_behind": []])
        }
        throw DeviceAPIError(501, "unsupported_operation", "This maintained library operation has not been connected to the host API yet.")
    }
}
