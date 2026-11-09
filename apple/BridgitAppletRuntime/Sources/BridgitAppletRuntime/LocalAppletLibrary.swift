import Foundation
import SQLite3
import CryptoKit
import Darwin
import ImageIO
import UniformTypeIdentifiers

/// An owner table export never returns a partial snapshot when its row bound is exceeded.
public struct OwnerTableExportLimitError: LocalizedError, Equatable, Sendable {
    public init() {}
    public var errorDescription: String? { "An export holds at most 10,000 rows." }
}

/// One shared SQLite database, partitioned by the registry's authenticated owner identifier.
/// Each installed definition, its revision and its instance defaults commit in one statement.
public actor LocalAppletLibrary {
    public struct Edition: Identifiable, Sendable {
        public let id: String // registry key, distinct even for publishers using the same package id
        let instanceID: String
        /// Public host identity pins this installation, not its reusable registry key.
        /// Reinstallation changes this ID so an old scoped grant or queued delete
        /// cannot silently apply to the replacement. Fits the existing host ID grammar.
        public var hostID: String {
            "library-" + SHA256.hash(data: Data(("bridgit-library-instance-v1:" + instanceID).utf8))
                .map { String(format: "%02x", $0) }.joined().prefix(56)
        }
        public let revision: Int
        public let name: String
    }

    public struct Instance: Sendable {
        fileprivate let edition: Edition
        public let package: AppletPackage
        fileprivate let configData: Data
        public let config: [String: JSONValue]
        public let data: Data
        /// Absent table keys mean the original declaration order was not retained.
        public let columnOrder: [String: [String]]
    }

    private let db: OpaquePointer
    private let batchLock: URL
    private let account: String
    private let executionScope: String

    public init(database: URL, account: String) throws {
        guard account.range(of: #"\A[a-f0-9]{64}\z"#, options: .regularExpression) != nil else {
            throw AppletRuntimeError.invalid("Missing verified library account")
        }
        try FileManager.default.createDirectory(at: database.deletingLastPathComponent(), withIntermediateDirectories: true)
        var handle: OpaquePointer?
        guard sqlite3_open_v2(database.path, &handle, SQLITE_OPEN_CREATE | SQLITE_OPEN_READWRITE | SQLITE_OPEN_FULLMUTEX, nil) == SQLITE_OK,
              let handle else {
            sqlite3_close(handle)
            throw AppletRuntimeError.invalid("Could not open the local applet library")
        }
        sqlite3_busy_timeout(handle, 5000)
        do {
            try Self.prepareDatabase(handle)
        } catch {
            sqlite3_close(handle)
            throw error
        }
        db = handle
        self.batchLock = database.appendingPathExtension("batch-lock")
        self.account = account
        self.executionScope = database.resolvingSymlinksInPath().standardizedFileURL.path + ":" + account
    }
    deinit { sqlite3_close(db) }

    private static func prepareDatabase(_ handle: OpaquePointer) throws {
        func exec(_ sql: String) throws {
            guard sqlite3_exec(handle,sql,nil,nil,nil) == SQLITE_OK else {
                throw AppletRuntimeError.invalid("Local library: " + String(cString: sqlite3_errmsg(handle)))
            }
        }
        try exec("BEGIN IMMEDIATE")
        do {
            try exec("""
                CREATE TABLE IF NOT EXISTS local_applets (
                  account TEXT NOT NULL, key TEXT NOT NULL, revision INTEGER NOT NULL,
                  name TEXT NOT NULL, definition BLOB NOT NULL, config BLOB NOT NULL,
                  PRIMARY KEY(account,key))
                """)
            var query: OpaquePointer?
            guard sqlite3_prepare_v2(handle,"PRAGMA user_version",-1,&query,nil) == SQLITE_OK else { throw AppletRuntimeError.invalid("Cannot read library version") }
            let version = sqlite3_step(query) == SQLITE_ROW ? sqlite3_column_int(query,0) : -1
            sqlite3_finalize(query)
            switch version {
            case 0:
                try exec("ALTER TABLE local_applets ADD COLUMN instance TEXT NOT NULL DEFAULT ''")
                try exec("UPDATE local_applets SET instance=lower(hex(randomblob(16)))")
                try exec("PRAGMA user_version=1")
            case 1...11: break
            default: throw AppletRuntimeError.invalid("Unsupported local library version")
            }
            if version < 2 {
                try exec("""
                    CREATE TABLE applet_rows (
                        instance TEXT NOT NULL, table_id TEXT NOT NULL, effect TEXT NOT NULL,
                        at INTEGER NOT NULL, expires INTEGER NOT NULL, payload BLOB NOT NULL,
                        PRIMARY KEY(instance,effect));
                    CREATE INDEX applet_rows_table ON applet_rows(instance,table_id,at);
                    CREATE INDEX applet_rows_expiry ON applet_rows(expires);
                    CREATE TRIGGER remove_applet_rows AFTER DELETE ON local_applets BEGIN
                        DELETE FROM applet_rows WHERE instance=old.instance;
                    END;
                    CREATE TABLE applet_row_writes(instance TEXT NOT NULL, at INTEGER NOT NULL);
                    CREATE INDEX applet_row_writes_time ON applet_row_writes(at);
                    CREATE TRIGGER remove_applet_writes AFTER DELETE ON local_applets BEGIN
                        DELETE FROM applet_row_writes WHERE instance=old.instance;
                    END;
                    PRAGMA user_version=2;
                    """)
            }
            if version < 3 {
                try exec("""
                    CREATE TABLE applet_photos(instance TEXT NOT NULL, reference TEXT NOT NULL,
                        created INTEGER NOT NULL, jpeg BLOB NOT NULL, PRIMARY KEY(instance,reference));
                    CREATE TABLE applet_row_photos(instance TEXT NOT NULL, effect TEXT NOT NULL, reference TEXT NOT NULL,
                        PRIMARY KEY(instance,effect,reference));
                    CREATE INDEX applet_photo_links ON applet_row_photos(instance,reference);
                    CREATE TRIGGER unlink_applet_photos AFTER DELETE ON applet_rows BEGIN
                        DELETE FROM applet_row_photos WHERE instance=old.instance AND effect=old.effect;
                    END;
                    CREATE TRIGGER remove_applet_photos AFTER DELETE ON local_applets BEGIN
                        DELETE FROM applet_photos WHERE instance=old.instance;
                    END;
                    PRAGMA user_version=3;
                    """)
            }
            if version < 4 {
                try exec("""
                    CREATE TABLE applet_paid_days(instance TEXT NOT NULL, flow TEXT NOT NULL, day TEXT NOT NULL,
                        calls INTEGER NOT NULL, PRIMARY KEY(instance,flow));
                    CREATE TRIGGER remove_applet_paid_days AFTER DELETE ON local_applets BEGIN
                        DELETE FROM applet_paid_days WHERE instance=old.instance;
                    END;
                    PRAGMA user_version=4;
                    """)
            }
            if version < 5 {
                try exec("""
                    CREATE UNIQUE INDEX applet_public_row_id ON applet_rows(
                        instance,table_id,lower(replace(replace(effect,'-',''),':','')));
                    PRAGMA user_version=5;
                    """)
            }
            if version < 6 {
                try exec("""
                    CREATE TABLE applet_batch_runs(
                        instance TEXT NOT NULL, source_id TEXT NOT NULL, identity TEXT NOT NULL, execution TEXT NOT NULL,
                        PRIMARY KEY(instance,source_id,identity));
                    CREATE TABLE applet_batch_answers(
                        instance TEXT NOT NULL, source_id TEXT NOT NULL, effect TEXT NOT NULL, result BLOB NOT NULL,
                        PRIMARY KEY(instance,effect));
                    CREATE INDEX applet_batch_source ON applet_batch_answers(instance,source_id);
                    CREATE TRIGGER remove_batch_answers AFTER DELETE ON applet_rows BEGIN
                        DELETE FROM applet_batch_runs WHERE instance=old.instance
                          AND source_id=lower(replace(replace(old.effect,'-',''),':',''));
                        DELETE FROM applet_batch_answers WHERE instance=old.instance
                          AND source_id=lower(replace(replace(old.effect,'-',''),':',''));
                    END;
                    CREATE TRIGGER complete_batch_run AFTER DELETE ON applet_batch_runs BEGIN
                        DELETE FROM applet_batch_answers WHERE instance=old.instance AND source_id=old.source_id
                          AND effect GLOB old.execution || ':*';
                    END;
                    CREATE TRIGGER retire_batch_runs AFTER UPDATE OF revision ON local_applets BEGIN
                        DELETE FROM applet_batch_runs WHERE instance=old.instance;
                    END;
                    PRAGMA user_version=6;
                    """)
            }
            if version < 7 {
                try exec("""
                    CREATE TABLE applet_gallery_inputs(instance TEXT NOT NULL, id INTEGER NOT NULL,
                        digest TEXT NOT NULL, PRIMARY KEY(instance,id), UNIQUE(instance,digest));
                    CREATE TRIGGER remove_gallery_inputs AFTER DELETE ON local_applets BEGIN
                        DELETE FROM applet_gallery_inputs WHERE instance=old.instance;
                    END;
                    PRAGMA user_version=7;
                    """)
            }
            if version < 8 {
                try exec("""
                    CREATE TABLE applet_package_media(instance TEXT NOT NULL,name TEXT NOT NULL,bytes BLOB NOT NULL,PRIMARY KEY(instance,name));
                    CREATE TRIGGER remove_package_media AFTER DELETE ON local_applets BEGIN
                        DELETE FROM applet_package_media WHERE instance=old.instance;
                    END;
                    PRAGMA user_version=8;
                    """)
            }
            if version < 9 {
                try exec("""
                    CREATE TABLE applet_history_archives(instance TEXT PRIMARY KEY, document BLOB NOT NULL);
                    CREATE TRIGGER remove_history_archive AFTER DELETE ON local_applets BEGIN
                        DELETE FROM applet_history_archives WHERE instance=old.instance;
                    END;
                    PRAGMA user_version=9;
                    """)
            }
            if version < 10 {
                try exec("""
                    CREATE TABLE IF NOT EXISTS applet_execution_control(instance TEXT PRIMARY KEY, paused INTEGER NOT NULL, generation INTEGER NOT NULL);
                    CREATE TRIGGER IF NOT EXISTS remove_execution_control AFTER DELETE ON local_applets BEGIN
                        DELETE FROM applet_execution_control WHERE instance=old.instance;
                    END;
                    PRAGMA user_version=10;
                    """)
            }
            if version < 11 {
                try exec("ALTER TABLE local_applets ADD COLUMN column_order BLOB")
                try exec("PRAGMA user_version=11")
            }
            try exec("DELETE FROM applet_rows WHERE expires <= unixepoch()")
            try exec(Self.collectPhotos)
            try exec("COMMIT")
        } catch {
            try? exec("ROLLBACK")
            throw error
        }
    }

    private static let collectPhotos = """
        DELETE FROM applet_photos WHERE created <= unixepoch()-604800 AND NOT EXISTS (
            SELECT 1 FROM applet_row_photos l WHERE l.instance=applet_photos.instance AND l.reference=applet_photos.reference)
        """

    private enum Value { case text(String), integer(Int), bytes(Data) }
    private func statement<T>(_ sql: String, _ values: [Value], _ body: (OpaquePointer) throws -> T) throws -> T {
        var query: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &query, nil) == SQLITE_OK, let query else { throw failure() }
        defer { sqlite3_finalize(query) }
        let copy = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
        for (offset,value) in values.enumerated() {
            let index = Int32(offset + 1)
            let result: Int32
            switch value {
            case .text(let s): result = sqlite3_bind_text(query,index,s,-1,copy)
            case .integer(let n): result = sqlite3_bind_int64(query,index,Int64(n))
            case .bytes(let d): result = d.withUnsafeBytes { sqlite3_bind_blob(query,index,$0.baseAddress,Int32($0.count),copy) }
            }
            guard result == SQLITE_OK else { throw failure() }
        }
        return try body(query)
    }
    private func failure() -> AppletRuntimeError { .invalid("Local library: " + String(cString: sqlite3_errmsg(db))) }

    private func transaction(_ work: () throws -> Void) throws {
        try execute("BEGIN IMMEDIATE")
        do { try work(); try execute("COMMIT") }
        catch { try? execute("ROLLBACK"); throw error }
    }

    private func storeMedia(_ media: [String:Data], for package: AppletPackage, instance: String) throws {
        guard Set(media.keys) == Set(package.media.keys) else { throw AppletRuntimeError.invalid("Missing or unexpected package media") }
        try execute("DELETE FROM applet_package_media WHERE instance=?",[.text(instance)])
        for (name,descriptor) in package.media {
            guard let data = media[name] else { throw AppletRuntimeError.invalid("Missing package media") }
            try descriptor.verify(data)
            try execute("INSERT INTO applet_package_media(instance,name,bytes) VALUES(?,?,?)",[.text(instance),.text(name),.bytes(data)])
        }
    }

    public func media(named name:String, in edition:Edition) throws -> Data {
        let package = try open(edition).package
        guard let descriptor = package.media[name] else { throw AppletRuntimeError.invalid("Unknown revision asset") }
        return try statement("SELECT bytes FROM applet_package_media WHERE instance=? AND name=?",[.text(edition.instanceID),.text(name)]) { query in
            guard sqlite3_step(query) == SQLITE_ROW else { throw AppletRuntimeError.invalid("Missing installed media") }
            let bytes = Data(bytes:sqlite3_column_blob(query,0),count:Int(sqlite3_column_bytes(query,0)))
            try descriptor.verify(bytes)
            return bytes
        }
    }

    public func install(_ release: RegistryPackage, media:[String:Data] = [:]) throws {
        try transaction { try installInTransaction(release,media:media) }
    }

    private func installInTransaction(_ release: RegistryPackage, media:[String:Data]) throws {
        let entry = release.selection
        if release.package.definition["ui"]?["practices"] != nil { try AppletPractices.requireChange(release.package.definition) }
        let configuration = try release.package.configuration(preserving: [:])
        try release.package.validateFlows(configuration: configuration)
        let config = try JSONEncoder().encode(configuration)
        try statement("""
            INSERT INTO local_applets(account,key,revision,name,definition,config,column_order,instance) VALUES (?,?,?,?,?,?,?,lower(hex(randomblob(16))))
            ON CONFLICT(account,key) DO NOTHING
            """, [.text(account),.text(entry.key),.integer(entry.revision),.text(entry.name),.bytes(release.data),.bytes(config),
                  .bytes(try JSONEncoder().encode(release.columnOrder))]) { query in
            guard sqlite3_step(query) == SQLITE_DONE else { throw failure() }
        }
        if sqlite3_changes(db) == 0 {
            guard let existing = try installed(entry.key), existing.revision == entry.revision else { throw AppletRuntimeError.invalid("Review the installed revision before updating it") }
            let stored = try open(existing)
            guard stored.data == release.data else { throw AppletRuntimeError.invalid("The installed release differs. Review an update before replacing it.") }
            for name in release.package.media.keys { _ = try self.media(named:name,in:existing) }
        } else {
            guard let edition = try installed(entry.key) else { throw failure() }
            try storeMedia(media,for:release.package,instance:edition.instanceID)
        }
    }

    public func editions() throws -> [Edition] {
        try editionRows("WHERE account=? ORDER BY name,key", [.text(account)])
    }

    public func installed(_ key: String) throws -> Edition? {
        try editionRows("WHERE account=? AND key=?", [.text(account),.text(key)]).first
    }

    private func editionRows(_ clause: String, _ values: [Value]) throws -> [Edition] {
        try statement("SELECT key,revision,name,instance FROM local_applets " + clause, values) { query in
            var rows: [Edition] = []
            while true {
                switch sqlite3_step(query) {
                case SQLITE_DONE: return rows
                case SQLITE_ROW:
                    rows.append(Edition(id: String(cString: sqlite3_column_text(query,0)), instanceID: String(cString: sqlite3_column_text(query,3)), revision: Int(sqlite3_column_int64(query,1)), name: String(cString: sqlite3_column_text(query,2))))
                default: throw failure()
                }
            }
        }
    }

    public func open(_ edition: Edition) throws -> Instance {
        try statement("SELECT definition,config,column_order FROM local_applets WHERE account=? AND key=? AND revision=? AND instance=?",
                      [.text(account),.text(edition.id),.integer(edition.revision),.text(edition.instanceID)]) { query in
            switch sqlite3_step(query) {
            case SQLITE_ROW:
                func bytes(_ column: Int32) -> Data {
                    Data(bytes: sqlite3_column_blob(query,column), count: Int(sqlite3_column_bytes(query,column)))
                }
                let data = bytes(0), configData = bytes(1)
                let order = sqlite3_column_type(query, 2) == SQLITE_NULL ? [:] : try JSONDecoder().decode([String: [String]].self, from: bytes(2))
                return Instance(edition:edition, package: try AppletPackage(data: data), configData: configData, config: try JSONDecoder().decode([String: JSONValue].self, from: configData), data: data, columnOrder: order)
            case SQLITE_DONE: throw AppletRuntimeError.invalid("That revision is not installed for this account")
            default: throw failure()
            }
        }
    }

    /// Compare with the settings the owner opened so a second editor or a package upgrade cannot lose an edit.
    public func saveSettings(_ settings: [String:JSONValue], replacing snapshot: Instance) throws {
        let edition = snapshot.edition
        let checked = try snapshot.package.configuration(preserving:settings)
        try snapshot.package.validateFlows(configuration: checked)
        let data = try JSONEncoder().encode(checked)
        guard data.count <= 131072 else { throw AppletRuntimeError.invalid("Settings exceed 128 KiB") }
        try statement("""
            UPDATE local_applets SET config=?
            WHERE account=? AND key=? AND instance=? AND revision=? AND definition=? AND config=?
            """, [.bytes(data),.text(account),.text(edition.id),.text(edition.instanceID),.integer(edition.revision),
                  .bytes(snapshot.data),.bytes(snapshot.configData)]) { query in
            guard sqlite3_step(query) == SQLITE_DONE else { throw failure() }
            guard sqlite3_changes(db) == 1 else {
                throw AppletRuntimeError.invalid("The installation changed. Reopen settings to edit the current values.")
            }
        }
    }

    public struct Update: Sendable {
        public let from: Edition
        public var targetRevision: Int { release.selection.revision }
        public var targetVersion: String { release.package.version }
        public let addedStorage: [String]
        public let newPermissions: [String]
        public let remoteDisclosures: [String]
        fileprivate let original: Instance
        public let release: RegistryPackage
        fileprivate let config: [String: JSONValue]
    }

    public func reviewUpdate(_ release: RegistryPackage, replacing edition: Edition) throws -> Update {
        let old = try open(edition)
        let incoming = release.package
        if incoming.definition["ui"]?["practices"] != nil || old.package.definition["ui"]?["practices"] != nil { try AppletPractices.requireChange(incoming.definition,previous:old.package.definition) }
        guard release.selection.key == edition.id, incoming.id == old.package.id,
              release.selection.revision > edition.revision,
              old.package.version.compare(incoming.version, options: .numeric) == .orderedAscending else {
            throw AppletRuntimeError.invalid("Update must advance the same installed edition")
        }
        if let migration = incoming.definition["migration"] {
            if let source = migration["from"]?.text, source != old.package.version { throw AppletRuntimeError.invalid("This migration requires version " + source) }
            if migration["rules"] != .array([]) { throw AppletRuntimeError.invalid("This update requires a data migration") }
        }
        let oldTables = try LocalRecordTable.declarations(old.package)
        let newTables = try LocalRecordTable.declarations(incoming)
        func disclosure(_ name: String, in table: LocalRecordTable) -> String {
            table.attributes["share_with_installer"] == .bool(true) ? name + " (shared with installer)" : name
        }
        var additions = newTables.values.filter { oldTables[$0.id] == nil }.map { disclosure($0.id, in:$0) }
        for (name, existing) in oldTables {
            guard let next = newTables[name], existing.attributes == next.attributes,
                  existing.columns.allSatisfy({ next.columns[$0.key] == $0.value }) else {
                throw AppletRuntimeError.invalid("Changing an existing table declaration requires a data migration: " + name)
            }
            additions += next.columns.keys.filter { existing.columns[$0] == nil }.map { disclosure(name + "." + $0, in:next) }
        }
        let config = try incoming.configuration(preserving: old.config)
        try incoming.validateFlows(configuration: config)
        func permissions(_ package: AppletPackage, _ field: String) -> [String] {
            if case .array(let entries)? = package.definition["permissions"]?[field] { return entries.compactMap(\.text) }
            return []
        }
        let previous = Set(permissions(old.package,"required"))
        return Update(from:edition,addedStorage:additions.sorted(),newPermissions:permissions(incoming,"required").filter { !previous.contains($0) },
                      remoteDisclosures:permissions(incoming,"remote"),original:old,release:release,config:config)
    }

    /// One SQLite transaction publishes the reviewed definition, configuration, pin and verified media.
    public func apply(_ update: Update, media:[String:Data] = [:]) throws {
        try transaction {
            try applyDefinition(update)
            try storeMedia(media,for:update.release.package,instance:update.from.instanceID)
        }
    }

    private func applyDefinition(_ update: Update) throws {
        let old = update.from, release = update.release
        try statement("""
            UPDATE local_applets SET revision=?,name=?,definition=?,config=?,column_order=?
            WHERE account=? AND key=? AND instance=? AND revision=? AND definition=? AND config=?
            """, [.integer(release.selection.revision),.text(release.package.definition["name"]?.text ?? release.selection.name),.bytes(release.data),
                  .bytes(try JSONEncoder().encode(update.config)),.bytes(try JSONEncoder().encode(release.columnOrder)),.text(account),.text(old.id),.text(old.instanceID),
                  .integer(old.revision),.bytes(update.original.data),.bytes(update.original.configData)]) { query in
            guard sqlite3_step(query) == SQLITE_DONE else { throw failure() }
            guard sqlite3_changes(db) == 1 else { throw AppletRuntimeError.invalid("The installation changed. Review the current revision again.") }
        }
    }

    public struct HistoricalEpisode: Decodable, Sendable {
        public let kind: String
        public let start: Int64
        public let lastSeen: Int64
        public let frames: Int
    }

    private struct LegacyDogHistory: Decodable {
        struct Tracker: Decodable {
            let episodes: [HistoricalEpisode]
            let readings: [JSONValue]
            let spans: [[Int64]]
        }
        let format: String
        let packageId: String
        let instanceId: String
        let packageSha256: String
        let tracker: Tracker
        let settings: [String:JSONValue]
        let soundEpisodes: [String:JSONValue]
        let soundReadings: [JSONValue]

        static func parse(_ data: Data) throws -> Self {
            guard data.count <= 8 * 1024 * 1024 else { throw AppletRuntimeError.invalid("History archive exceeds 8 MiB") }
            let decoder = JSONDecoder()
            decoder.keyDecodingStrategy = .convertFromSnakeCase
            let value = try decoder.decode(Self.self,from:data)
            guard value.format == "bridgit-dog-monitor-history/1", value.packageId == "dog-monitor", !value.instanceId.isEmpty,
                  value.packageSha256.range(of:#"\A[0-9a-f]{64}\z"#, options:.regularExpression) != nil,
                  value.tracker.readings.count <= 60, value.tracker.spans.count <= 1000,
                  value.tracker.episodes.count <= 2000, value.tracker.episodes.allSatisfy({
                    ["eating","drinking","resting","sleeping"].contains($0.kind) && $0.start > 0 &&
                    $0.lastSeen >= $0.start && $0.frames > 0
                  }) else { throw AppletRuntimeError.invalid("Invalid Dog Monitor history archive") }
            return value
        }
    }

    /// An owner-selected archive is kept privately beside the installation. Its sensor settings are data only.
    public func importDogHistory(_ data: Data, into edition: Edition) throws -> [HistoricalEpisode] {
        let history = try LegacyDogHistory.parse(data)
        try transaction {
            let package = try open(edition).package
            guard package.id == "dog-monitor", package.definition["runtime"]?["adapter"] == .string("bridgit.package.v1") else {
                throw AppletRuntimeError.invalid("Choose the installed portable Dog Monitor edition")
            }
            if let original = try dogHistoryBytes(in:edition) {
                guard original == data else { throw AppletRuntimeError.invalid("This instance already has a different history archive") }
            } else {
                try execute("INSERT INTO applet_history_archives(instance,document) VALUES(?,?)",
                            [.text(edition.instanceID),.bytes(data)])
            }
        }
        return history.tracker.episodes
    }

    public func dogHistoryBytes(in edition: Edition) throws -> Data? {
        _ = try open(edition)
        return try statement("SELECT document FROM applet_history_archives WHERE instance=?",[.text(edition.instanceID)]) { query in
            switch sqlite3_step(query) {
            case SQLITE_ROW: return Data(bytes:sqlite3_column_blob(query,0),count:Int(sqlite3_column_bytes(query,0)))
            case SQLITE_DONE: return nil
            default: throw failure()
            }
        }
    }

    public func importedDogEpisodes(in edition: Edition) throws -> [HistoricalEpisode]? {
        try dogHistoryBytes(in:edition).map { try LegacyDogHistory.parse($0).tracker.episodes }
    }

    public func remove(_ edition: Edition) throws {
        try statement("DELETE FROM local_applets WHERE account=? AND key=? AND revision=? AND instance=?", [.text(account),.text(edition.id),.integer(edition.revision),.text(edition.instanceID)]) { query in
            guard sqlite3_step(query) == SQLITE_DONE else { throw failure() }
        }
    }
}

/// Foreground execution uses the reviewed local pin and its persisted configuration.
/// Only explicit owner-triggered flows can enter through this host.
extension LocalAppletLibrary {
    public struct Notice: Sendable, Identifiable {
        public let id: String
        public let title: String
        public let text: String
    }

    public struct OwnerQuestion: Identifiable, Sendable {
        public let id: String
        public let title: String
        public let text: String
        public let choices: [String]
        public init(id: String, title: String, text: String, choices: [String]) {
            self.id = id; self.title = title; self.text = text; self.choices = choices
        }
    }

    /// Owner-reviewed capture held only for the duration of the foreground flow.
    public struct ReviewedPhoto: Sendable {
        public let jpeg: Data
        public let camera: String
        public init(jpeg: Data, camera: String) { self.jpeg = jpeg; self.camera = camera }
    }
    public enum DecisionService: String, Sendable { case clef, appraise, chef, story, illustration = "images/generations", context = "services/context", voice = "speech/resolve", speak = "speech/speak" }
    public struct Decision: Sendable {
        public let service: DecisionService
        public let options: [String:JSONValue]
        public let questions: [String:JSONValue]
        public let jpeg: Data?
        public let flow: String
        public let libraryKey: String
        public let libraryRevision: Int
        public let packageID: String
        public let owner: String
        public let budget: JSONValue?
        public let name: String
    }
    public typealias Decide = @Sendable (Decision, @Sendable () async throws -> Void) async throws -> JSONValue
    public typealias Hear = @Sendable (_ seconds: Int, _ language: String?) async throws -> String
    public typealias CapturePhoto = @Sendable (_ camera: String) async throws -> ReviewedPhoto
    public typealias AnswerOwner = @Sendable (OwnerQuestion) async throws -> String

    /// The card cannot choose the caller identity, budget, or destination URL.
    public func cardContext(_ request: JSONValue, in edition: Edition, decide: @escaping Decide) async throws -> JSONValue {
        return try await withExecution(edition) { [self] in
        let package = try open(edition).package
        guard package.cardHTML != nil, case .array(let grants)? = package.definition["permissions"]?["required"],
              grants.contains(.string("judgment")), request["method"] == .string("POST"),
              request["path"] == .string("/v1/services/context"), let body = request["body"]?.fields,
              body.keys.allSatisfy({ ["service","latitude","longitude","radius_m","limit"].contains($0) }),
              let service = body["service"]?.text, ["places","airquality"].contains(service),
              case .number(let lat)? = body["latitude"], (-90...90).contains(lat),
              case .number(let lon)? = body["longitude"], (-180...180).contains(lon),
              case .number(let radius) = body["radius_m"] ?? .number(500), (1...1000).contains(radius),
              case .number(let limit) = body["limit"] ?? .number(5), (1...10).contains(limit), limit.rounded() == limit else {
            throw AppletRuntimeError.invalid("Unsupported applet context request")
        }
        return try await cardDecision(.context,action:service,options:body,package:package,in:edition,decide:decide)
            }
    }

    public func cardStory(_ request: JSONValue, in edition: Edition,
                          capture: @escaping @Sendable (String) async throws -> Data, decide: @escaping Decide) async throws -> JSONValue {
        return try await withExecution(edition) { [self] in
        let package = try open(edition).package
        guard package.cardHTML != nil, case .array(let grants)? = package.definition["permissions"]?["required"],
              ["judgment","captures"].allSatisfy({ grants.contains(.string($0)) }),
              request["method"] == .string("POST"), request["path"] == .string("/v1/story/generate"),
              let body = request["body"]?.fields,
              body.keys.allSatisfy({ ["mode","frame_capture_ids","portrait_capture_id","context"].contains($0) }),
              let mode = body["mode"]?.text, ["kind","wild"].contains(mode),
              case .array(let frames)? = body["frame_capture_ids"], (2...8).contains(frames.count),
              let portrait = body["portrait_capture_id"]?.text else { throw AppletRuntimeError.invalid("Invalid story request") }
        let context = body["context"] ?? .object([:])
        guard context.fields != nil, try JSONEncoder().encode(context).count <= 16000 else {
            throw AppletRuntimeError.invalid("Story context exceeds its allowed size")
        }
        var images = [JSONValue](), total = 0
        for reference in frames + [.string(portrait)] {
            guard let id = reference.text else { throw AppletRuntimeError.invalid("Expected a capture ID") }
            let jpeg = try await capture(id)
            total += jpeg.count
            guard jpeg.starts(with:[0xff,0xd8]), total <= 4_500_000 else { throw AppletRuntimeError.invalid("Invalid or oversized story captures") }
            images.append(.string("data:image/jpeg;base64," + jpeg.base64EncodedString()))
        }
        let portraitImage = images.removeLast()
        let references = frames.compactMap(\.text) + [portrait]
        return try await cardDecision(.story,action:"story",options:["mode":.string(mode),"frames":.array(images),
            "portrait":portraitImage,"context":context],package:package,in:edition,decide:decide) {
            for id in references { _ = try await capture(id) }
        }
            }
    }

    public func cardIllustration(_ request: JSONValue, in edition: Edition, decide: @escaping Decide) async throws -> JSONValue {
        return try await withExecution(edition) { [self] in
        let package = try open(edition).package
        guard package.cardHTML != nil, case .array(let grants)? = package.definition["permissions"]?["required"],
              ["judgment","interactions"].allSatisfy({ grants.contains(.string($0)) }),
              request["method"] == .string("POST"), request["path"] == .string("/v1/story/image"),
              let body = request["body"]?.fields, body.keys.allSatisfy({ ["presentation_token","title","narration","request_id"].contains($0) }),
              let title = body["title"]?.text, !title.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty, title.utf16.count <= 160,
              let text = body["narration"]?.text, !text.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty, text.utf16.count <= 8000,
              let id = body["request_id"]?.text, id.range(of:#"\A[A-Za-z0-9_-]{8,128}\z"#,options:.regularExpression) != nil else {
            throw AppletRuntimeError.invalid("Invalid illustration request")
        }
        return try await cardDecision(.illustration,action:"illustrate",options:[
            "request_id":.string(id),"title":.string(title),"narration":.string(text)
        ],package:package,in:edition,decide:decide)
            }
    }

    public func cardSpeech(_ request: JSONValue, in edition: Edition, decide: @escaping Decide) async throws -> JSONValue {
        return try await withExecution(edition) { [self] in
        let package = try open(edition).package
        guard package.cardHTML != nil, case .array(let grants)? = package.definition["permissions"]?["required"],
              grants.contains(.string("speaker")) else { throw AppletRuntimeError.unavailable("speaker") }
        if request["path"] == .string("/v1/speech/models"), (request["method"] ?? .string("GET")) == .string("GET"), request["body"] == nil {
            let selection = try await cardDecision(.voice,action:"narrate",options:["operation":.string("speak")],
                                                   package:package,in:edition,decide:decide)
            return .object(["speaking_model":selection])
        }
        guard request["path"] == .string("/v1/speech/speak"), request["method"] == .string("POST"),
              let body = request["body"]?.fields,
              body.keys.allSatisfy({ ["text","expected_model","allow_cloud","language","replace","presentation_token"].contains($0) }),
              let text = body["text"]?.text, !text.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty, text.utf16.count <= 600,
              let model = body["expected_model"]?.text, !model.isEmpty, body["allow_cloud"] == .bool(true),
              body["replace"] == .string("queue"), let language = body["language"]?.text else {
            throw AppletRuntimeError.invalid("Unsupported narration request")
        }
        let options: [String:JSONValue] = ["text":.string(text),"expected_model":.string(model),
                                           "allow_cloud":.bool(true),"language":.string(language)]
        return try await cardDecision(.speak,action:"narrate",options:options,package:package,in:edition,decide:decide)
            }
    }

    private func cardDecision(_ service: DecisionService, action: String, options: [String:JSONValue], package: AppletPackage,
                              in edition: Edition, decide: Decide, revalidate: @escaping @Sendable () async throws -> Void = {}) async throws -> JSONValue {
        try authorizeRun(edition)
        let decision = Decision(service:service,options:options,questions:[:],jpeg:nil,flow:action,
                                libraryKey:edition.id,libraryRevision:edition.revision,packageID:package.id,owner:account,
                                budget:package.definition["budget"],name:edition.name)
        let response = try await decide(decision) {
            try await revalidate()
            try Task.checkCancellation()
            try await self.authorizeRun(edition)
        }
        try authorizeRun(edition)
        return response
    }

    public func captureTranscript(_ item: JSONValue, in edition: Edition,
                                  capture: @escaping @Sendable (Int) async throws -> String) async throws -> [String:JSONValue] {
        return try await withExecution(edition) { [self] in
        let instance = try open(edition)
        guard let button = instance.package.microphoneButton(item) else { throw AppletRuntimeError.unavailable("microphone card action") }
        let started = ISO8601DateFormatter().string(from: Date())
        try Task.checkCancellation()
        let text = try await capture(button.seconds)
        try Task.checkCancellation()
        try authorizeRun(edition)
        guard !text.isEmpty, text.utf8.count <= 65536 else { throw AppletRuntimeError.invalid("Missing or oversized transcript") }
        return [button.reply + ".transcript":.string(text), button.reply + ".recorded_at":.string(started)]
            }
    }

    /// Selecting a file authorizes this scan. A saved gallery ID alone cannot read a photo.
    public func scanSelectedImage(_ data: Data, with item: JSONValue, replacing snapshot: Instance) async throws -> (Instance,[String:JSONValue]) {
        return try await withExecution(snapshot.edition) { [self] in
        let edition = snapshot.edition
        guard try open(edition).configData == snapshot.configData else {
            throw AppletRuntimeError.invalid("Settings changed. Reopen the applet before scanning.")
        }
        guard let button = snapshot.package.ocrButton(item) else { throw AppletRuntimeError.unavailable("OCR card action") }
        let text = try await AppletImageText.read(data)
        try Task.checkCancellation()
        let digest = SHA256.hash(data:data).map { String(format:"%02x",$0) }.joined()
        try execute("BEGIN IMMEDIATE")
        do {
            let arguments: [Value] = [.text(edition.instanceID),.text(digest)]
            try execute("""
                INSERT INTO applet_gallery_inputs(instance,digest,id)
                SELECT ?,?,COALESCE(MAX(id),0)+1 FROM applet_gallery_inputs WHERE instance=?
                ON CONFLICT(instance,digest) DO NOTHING
                """,arguments + [.text(edition.instanceID)])
            let id = try statement("SELECT id FROM applet_gallery_inputs WHERE instance=? AND digest=?",arguments) { q in
                guard sqlite3_step(q) == SQLITE_ROW else { throw failure() }
                return sqlite3_column_int64(q,0)
            }
            var config = snapshot.config
            config[button.input] = .number(Double(id))
            try saveSettings(config,replacing:snapshot)
            let current = try open(edition)
            try execute("COMMIT")
            return (current,[button.reply + ".text":.string(text)])
        } catch {
            try? execute("ROLLBACK")
            throw error
        }
            }
    }

    public func recordDraft(_ item: JSONValue, in edition: Edition, drafts: [String:String],
                            replies: [String:JSONValue], operation: UUID, reviewed: Instance? = nil) throws {
        try Task.checkCancellation()
        let instance = try open(edition)
        guard instance.package.isDraftSave(item), let name = item["do"]?["record"]?.text,
              let table = try LocalRecordTable.declarations(instance.package)[name], let fields = item["do"]?["row"]?.fields,
              case .array(let grants)? = instance.package.definition["permissions"]?["required"],
              grants.contains(.string("interactions")) else { throw AppletRuntimeError.unavailable("record draft") }
        var row: [String:JSONValue] = [:]
        for (key,field) in fields {
            if let id = field["$draft"]?.text, let draft = instance.package.cardDrafts.first(where:{ $0.id == id }),
               let value = drafts[id], !value.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty, value.utf16.count <= draft.limit {
                row[key] = .string(value)
            } else if let source = field["$source"]?.text, let value = replies[source] {
                row[key] = value
            } else if let input = field["$input"]?.text, let value = instance.config[input] {
                row[key] = value
            } else { throw AppletRuntimeError.invalid("Missing or invalid value for " + key) }
        }
        try writeRows(edition,table:table,writes:[(operation.uuidString + ":card",row)],source:nil,reviewed:reviewed ?? instance)
    }

    public func captureCardPhoto(_ item: JSONValue, in edition: Edition,
                                 capture: @escaping @Sendable (String, AppletPackage.CameraSize) async throws -> ReviewedPhoto) async throws -> String {
        return try await withExecution(edition) { [self] in
        let package = try open(edition).package
        guard let button = package.cameraButton(item),
              case .array(let scopes)? = package.definition["permissions"]?["required"],
              scopes.contains(.string("interactions")), scopes.contains(.string("camera")),
              let table = try LocalRecordTable.declarations(package)[button.table],
              table.columns[button.photo]?["type"] == .string("photo") else {
            throw AppletRuntimeError.invalid("Unavailable camera card action")
        }
        var row = button.values
        _ = try table.payload(row)
        try Task.checkCancellation()
        let reviewed = try await capture(button.lens,button.size)
        let effect = UUID().uuidString + ":camera"
        try RunPhotos.validate(reviewed, allowDefaultCamera: true)
        try Task.checkCancellation()
        let ref = "kept:" + Self.rowID(effect)
        row[button.photo] = .string(ref)
        if table.columns["taken_at"]?["type"] == .string("time"), (row["taken_at"] ?? .null) == .null {
            row["taken_at"] = .string(ISO8601DateFormatter().string(from:Date()))
        }
        try writeRows(edition,table:table,writes:[(effect,row)],cameraPhoto:(ref,reviewed.jpeg))
        return Self.rowID(effect)
            }
    }

    private struct BatchSource: Sendable {
        let id: String
        let table: String
        let payload: Data
    }

    /// Only one foreground batch per database may offer paid submissions at once, even across processes.
    /// The OS releases the lock after a crash. Stable execution IDs recover previously saved steps.
    public func runCardBatch(_ item: JSONValue, in edition: Edition, ask: AnswerOwner? = nil,
                             capture: CapturePhoto? = nil, decide: Decide? = nil,
                             present: @escaping @Sendable (Notice) async throws -> Void) async throws -> Int {
        return try await withExecution(edition) { [self] in
        let descriptor = Darwin.open(batchLock.path,O_CREAT | O_RDWR | O_CLOEXEC,S_IRUSR | S_IWUSR)
        guard descriptor >= 0 else { throw AppletRuntimeError.invalid("Cannot open batch lock") }
        defer { Darwin.close(descriptor) }
        guard flock(descriptor,LOCK_EX | LOCK_NB) == 0 else { throw AppletRuntimeError.invalid("Another applet batch is running") }
        let instance = try open(edition)
        guard let batch = instance.package.batchButton(item), let flow = instance.package.flows[batch.flow],
              let table = try LocalRecordTable.declarations(instance.package)[batch.table],
              table.columns[batch.filterColumn] != nil else { throw AppletRuntimeError.invalid("Unsupported card batch") }
        let rows = try rows(in:edition,table:batch.table,limit:batch.sourceLimit)
        var completed = 0
        for row in rows where row[batch.filterColumn] != batch.excluded {
            try Task.checkCancellation()
            guard let rowID = row["id"]?.text else { throw AppletRuntimeError.invalid("Missing batch row ID") }
            let snapshot = try statement("""
                SELECT payload FROM applet_rows WHERE instance=? AND table_id=?
                AND lower(replace(replace(effect,'-',''),':',''))=? AND expires>unixepoch()
                """,[.text(edition.instanceID),.text(table.id),.text(rowID)]) { query -> Data? in
                guard sqlite3_step(query) == SQLITE_ROW else { return nil }
                return Data(bytes:sqlite3_column_blob(query,0),count:Int(sqlite3_column_bytes(query,0)))
            }
            guard let snapshot,
                  try JSONDecoder().decode([String:JSONValue].self,from:snapshot) == row.filter({ table.columns[$0.key] != nil }) else {
                continue
            }
            let source = BatchSource(id:rowID,table:table.id,payload:snapshot)
            let arguments = batch.columns.compactMapValues { row[$0]?.text.map(JSONValue.string) }
            guard arguments.count == batch.columns.count else { throw AppletRuntimeError.invalid("Missing called-flow row value") }
            let encoded = JSONEncoder()
            encoded.outputFormatting = [.sortedKeys]
            let identity: [JSONValue] = [.string(edition.instanceID),.number(Double(edition.revision)),.string(batch.flow),
                                         .string(rowID),.object(arguments)]
            let identityKey = SHA256.hash(data:try encoded.encode(identity)).prefix(16).map { String(format:"%02x",$0) }.joined()
            let execution = try statement("""
                INSERT INTO applet_batch_runs(instance,source_id,identity,execution)
                SELECT a.instance,?,?,lower(hex(randomblob(16))) FROM local_applets a
                JOIN applet_rows r ON r.instance=a.instance AND r.table_id=?
                WHERE a.account=? AND a.key=? AND a.revision=? AND a.instance=? AND r.expires>unixepoch()
                  AND lower(replace(replace(r.effect,'-',''),':',''))=?
                ON CONFLICT(instance,source_id,identity) DO UPDATE SET identity=excluded.identity
                RETURNING execution
                """,[.text(rowID),.text(identityKey),.text(batch.table),.text(account),.text(edition.id),
                     .integer(edition.revision),.text(edition.instanceID),.text(rowID)]) { query in
                guard sqlite3_step(query) == SQLITE_ROW else { throw AppletRuntimeError.revoked }
                return String(cString:sqlite3_column_text(query,0))
            }
            _ = try await runFlow(batch.flow,flow:flow,instance:instance,in:edition,execution:execution,
                                  trigger:.object(["with":.object(arguments)]),source:source,
                                  ask:ask,capture:capture,decide:decide,present:present)
            try execute("DELETE FROM applet_batch_runs WHERE instance=? AND source_id=? AND identity=?",
                        [.text(edition.instanceID),.text(rowID),.text(identityKey)])
            completed += 1
            if completed == batch.maximum { break }
        }
        return completed
            }
    }

    /// Frames stay in memory. Each paid call still requires the host's foreground owner review.
    public func observeCooking(_ flow: String, in edition: Edition, camera: String, goals: String,
                               capture: @escaping CapturePhoto, decide: @escaping Decide,
                               observed: @escaping @Sendable (_ action: String, _ tip: String) async throws -> Void) async throws {
        return try await withExecution(edition) { [self] in
        let instance = try open(edition)
        let package = instance.package
        let tables = try LocalRecordTable.declarations(package)
        guard let timing = package.cookingTiming(flow), let activity = tables["activity"], tables["ingredients"] != nil,
              ["front","rear"].contains(camera), goals.utf16.count <= 1000,
              case .array(let grants)? = package.definition["permissions"]?["required"],
              ["workflows","decisions","camera.snapshot","audio.speak"].allSatisfy({ grants.contains(.string($0)) }),
              case .array(let remote)? = package.definition["permissions"]?["remote"], remote.contains(where:{ $0.text?.isEmpty == false }),
              package.definition["data_policy"]?["device_only"]?["remote_models"] == .bool(true),
              case .number(let cap) = package.flows[flow]?["max_paid_per_day"] ?? .number(50),
              cap.rounded() == cap, (1...500).contains(cap) else { throw AppletRuntimeError.unavailable("cooking session") }
        // Verify the table contract before requesting a photo or spending from the budget.
        _ = try activity.payload(["kind":.string("tip"),"tip":.string(""),"action":.string(""),"cooking":.bool(true)])
        _ = try activity.payload(["kind":.string("observation")])
        let descriptor = Darwin.open(batchLock.appendingPathExtension("cooking").path,O_CREAT | O_RDWR | O_CLOEXEC,S_IRUSR | S_IWUSR)
        guard descriptor >= 0 else { throw AppletRuntimeError.invalid("Cannot open cooking session lock") }
        defer { Darwin.close(descriptor) }
        guard flock(descriptor,LOCK_EX | LOCK_NB) == 0 else { throw AppletRuntimeError.invalid("Another cooking session is running") }
        let deadline = ContinuousClock.now.advanced(by:.seconds(timing.session))
        while ContinuousClock.now < deadline {
            try Task.checkCancellation()
            try authorizeRun(edition)
            let photo = try await capture(camera)
            try Task.checkCancellation()
            try authorizeRun(edition)
            guard ContinuousClock.now < deadline else { return }
            try RunPhotos.validate(photo)
            guard photo.camera == camera else { throw AppletRuntimeError.invalid("Missing selected cooking camera frame") }
            let pantry = try rows(in:edition,table:"ingredients",limit:200).map { JSONValue.object($0.filter { ["label","note"].contains($0.key) }) }
            let previous = try statement("""
                SELECT payload,at,effect FROM applet_rows WHERE instance=? AND table_id='activity' AND expires>unixepoch()
                AND json_extract(CAST(payload AS TEXT),'$.kind')='tip' ORDER BY at DESC,rowid DESC LIMIT 1
                """,[.text(edition.instanceID)]) { query -> [String:JSONValue]? in
                switch sqlite3_step(query) {
                case SQLITE_ROW: return try decodeRow(query)
                case SQLITE_DONE: return nil
                default: throw failure()
                }
            }
            let options: [String:JSONValue] = ["mode":.string("observe"),"goals":.string(goals),"pantry":.array(pantry),
                                                "previous_tip":previous?["tip"] ?? .string("")]
            let request = Decision(service:.chef,options:options,questions:[:],jpeg:photo.jpeg,flow:flow,libraryKey:edition.id,
                                   libraryRevision:edition.revision,packageID:package.id,owner:account,budget:package.definition["budget"],name:edition.name)
            let response = try await decide(request) {
                try Task.checkCancellation()
                guard ContinuousClock.now < deadline else { throw CancellationError() }
                try await self.reserveDecision(edition,flow:flow,limit:Int(cap),source:nil)
            }
            try Task.checkCancellation()
            try authorizeRun(edition)
            guard ContinuousClock.now < deadline else { return }
            guard case .bool(let cooking)? = response["cooking"], let action = response["action"]?.text,
                  var tip = response["tip"]?.text, action.utf16.count <= 160, tip.utf16.count <= 400 else {
                throw AppletRuntimeError.invalid("Incomplete cooking observation")
            }
            if !cooking || previous?["tip"] == .string(tip) { tip = "" }
            if case .number(let at)? = previous?["at"], Date().timeIntervalSince1970*1000-at < Double(timing.tipGap) * 1000 { tip = "" }
            try writeRow(edition,table:activity,row:["kind":.string(tip.isEmpty ? "observation" : "tip"),
                         "cooking":.bool(cooking),"action":.string(action),"tip":.string(tip)],effect:UUID().uuidString,source:nil)
            try await observed(action,tip)
            try await ContinuousClock().sleep(until:min(deadline,ContinuousClock.now.advanced(by:.seconds(timing.interval))))
        }
            }
    }

    public func runButton(_ name: String, in edition: Edition,
                          ask: AnswerOwner? = nil, capture: CapturePhoto? = nil, hear: Hear? = nil, decide: Decide? = nil,
                          present: @escaping @Sendable (Notice) async throws -> Void) async throws -> FlowOutcome {
        return try await withExecution(edition) { [self] in
        let instance = try open(edition)
        guard let flow = instance.package.flows[name], instance.package.runButtons.contains(where: { $0.flow == name }) else {
            throw AppletRuntimeError.invalid("Choose a button-triggered flow")
        }
        return try await runFlow(name,flow:flow,instance:instance,in:edition,execution:UUID().uuidString,
                                 trigger:.object([:]),source:nil,ask:ask,capture:capture,hear:hear,decide:decide,present:present)
            }
    }

    private func runFlow(_ name: String, flow: JSONValue, instance: Instance, in edition: Edition, execution: String,
                         trigger: JSONValue, source: BatchSource?, ask: AnswerOwner?, capture: CapturePhoto?, hear: Hear? = nil, decide: Decide?,
                         present: @escaping @Sendable (Notice) async throws -> Void) async throws -> FlowOutcome {
        guard case .array(let permissions)? = instance.package.definition["permissions"]?["required"],
              permissions.contains(.string("workflows")) else {
            throw AppletRuntimeError.invalid("Button flows require the workflows permission")
        }
        let steps = try instance.package.steps(for: name, configuration: instance.config)
        let tables = try LocalRecordTable.declarations(instance.package)
        let paid = steps.filter { ["ask_model","appraise","kitchen"].contains($0.type) }
        let cap = flow["max_paid_per_day"] ?? .number(50)
        guard case .number(let limit) = cap, limit.rounded() == limit, (1...500).contains(limit),
              paid.count <= 4 else { throw AppletRuntimeError.invalid("Invalid paid workflow limits") }
        let disclosures = instance.package.definition["permissions"]?["remote"]
        let allowsRemote: Bool
        if case .array(let entries) = disclosures { allowsRemote = entries.contains { $0.text?.isEmpty == false } }
        else { allowsRemote = false }
        let canDecide = permissions.contains(.string("decisions")) && allowsRemote &&
            instance.package.definition["data_policy"]?["device_only"]?["remote_models"] == .bool(true)
        let host = ForegroundHost(library: self, edition: edition, packageID: instance.package.id, title: flow["name"]?.text ?? edition.name,
                                  tables: tables, photos: RunPhotos(), flow: name, limit: Int(limit),
                                  owner: account, source:source, budget: instance.package.definition["budget"], decide: canDecide ? decide : nil,
                                  capture: permissions.contains(.string("camera.snapshot")) ? capture : nil,
                                  hear: permissions.contains(.string("microphone.clip")) ? hear : nil,
                                  mayNotify: permissions.contains(.string("interactions")), ask: ask, present: present)
        for step in steps {
            let fields: Set<String>
            switch step.type {
            case "ask_model":
                guard host.decide != nil else { throw AppletRuntimeError.unavailable("ask_model") }
                fields = ["only_if","model","questions","photo"]
                guard step.arguments["model"] == .string("clef"), step.onError != "retry",
                      let questions = step.arguments["questions"]?.fields, (1...10).contains(questions.count),
                      questions.values.allSatisfy({ q in
                          guard let instructions = q["instructions"]?.text else { return false }
                          return (1...1000).contains(instructions.utf16.count) && ["choice","score","noul"].contains(q["type"]?.text)
                      }) else { throw AppletRuntimeError.unavailable("requested model, questions or automatic paid retries") }
            case "appraise","kitchen":
                guard host.decide != nil, step.onError != "retry", step.arguments["photo"] != nil else {
                    throw AppletRuntimeError.unavailable(step.type)
                }
                if step.type == "appraise" {
                    fields = ["only_if","photo","max_items"]
                    guard case .number(let maximum) = step.arguments["max_items"] ?? .number(20),
                          maximum.rounded() == maximum, (1...30).contains(maximum) else {
                        throw AppletRuntimeError.invalid("Appraisal accepts 1–30 items")
                    }
                } else {
                    fields = ["only_if","mode","photo","area"]
                    guard step.arguments["mode"] == .string("scan") else { throw AppletRuntimeError.unavailable("kitchen observation") }
                }
            case "photo":
                fields = ["only_if","camera","keep","view"]
                if let view = step.arguments["view"] {
                    guard let name = view.text, name.range(of: "^[a-z][a-z0-9_]{0,31}$", options: .regularExpression) != nil else {
                        throw AppletRuntimeError.invalid("Invalid saved camera view name")
                    }
                }
                guard case .bool = step.arguments["keep"] ?? .bool(false) else {
                    throw AppletRuntimeError.invalid("photo.keep must be boolean")
                }
                if let camera = step.arguments["camera"], ![.string("front"), .string("rear")].contains(camera) {
                    throw AppletRuntimeError.unavailable("requested camera")
                }
            case "update": fields = ["only_if","table","row","set"]
            case "record":
                fields = ["only_if","table","row","for_each"]
                guard step.arguments["row"]?.fields?.isEmpty == false else { throw AppletRuntimeError.invalid("Record needs a row template") }
            case "hear":
                fields = ["only_if","duration_seconds","language","allow_cloud"]
                guard host.hear != nil, step.onError != "retry",
                      (step.arguments["allow_cloud"] ?? .bool(false)) == .bool(false),
                      case .number(let seconds) = step.arguments["duration_seconds"] ?? .number(5),
                      seconds.rounded() == seconds, (1...30).contains(seconds),
                      step.arguments["language"] == nil || (2...32).contains(step.arguments["language"]?.text?.utf16.count ?? 0)
                else { throw AppletRuntimeError.unavailable("requested hearing options or microphone permission") }
            case "ask_owner": fields = ["only_if","title","text","choices","wait_s"]
            case "notice": fields = ["only_if","title","text","priority"]
            default: continue
            }
            guard Set(step.arguments.keys).isSubset(of:fields) else { throw AppletRuntimeError.unavailable(step.type + " options") }
        }
        return try await FlowRuntime(host: host).run(instance: edition.id, steps: steps, execution:execution, trigger:trigger, onlyIf: try instance.package.flowCondition(for: name, configuration: instance.config))
    }

    private struct ExecutionLease: Sendable {
        let account: String
        let instance: String
        let generation: Int64
    }
    @TaskLocal private static var executionLease: ExecutionLease?

    private func executionState(_ edition: Edition) throws -> (paused: Bool, generation: Int64) {
        try statement("SELECT paused,generation FROM applet_execution_control WHERE instance=?", [.text(edition.instanceID)]) { query in
            switch sqlite3_step(query) {
            case SQLITE_ROW: return (sqlite3_column_int(query, 0) != 0, sqlite3_column_int64(query, 1))
            case SQLITE_DONE: return (false, 0)
            default: throw failure()
            }
        }
    }

    public func isPaused(_ edition: Edition) throws -> Bool {
        _ = try open(edition)
        return try executionState(edition).paused
    }

    /// Changing pause state permanently invalidates earlier execution leases.
    /// Saved-data access outside an execution retains normal installation checks.
    public func setPaused(_ paused: Bool, in edition: Edition) throws {
        _ = try open(edition)
        let state = try executionState(edition)
        guard state.paused != paused else {
            if paused { ExecutionCancellation.shared.cancel(scope: executionScope + ":" + edition.instanceID) }
            return
        }
        try execute("""
            INSERT INTO applet_execution_control(instance,paused,generation) VALUES(?,?,1)
            ON CONFLICT(instance) DO UPDATE SET paused=excluded.paused,generation=generation+1
            """, [.text(edition.instanceID),.integer(paused ? 1 : 0)])
        if paused { ExecutionCancellation.shared.cancel(scope: executionScope + ":" + edition.instanceID) }
    }

    private func lease(for edition: Edition) throws -> ExecutionLease {
        try authorizeRun(edition)
        if let current = Self.executionLease {
            guard current.account == account, current.instance == edition.instanceID else { throw AppletRuntimeError.revoked }
            return current
        }
        let state = try executionState(edition)
        guard !state.paused else { throw AppletRuntimeError.paused }
        return ExecutionLease(account: account, instance: edition.instanceID, generation: state.generation)
    }

    private func withExecution<T: Sendable>(_ edition: Edition, operation: @escaping () async throws -> T) async throws -> T {
        let lease = try lease(for: edition)
        if Self.executionLease != nil {
            let value = try await operation()
            try Task.checkCancellation(); try authorizeRun(edition)
            return value
        }
        let id = UUID()
        let task = Task {
            try await Self.$executionLease.withValue(lease) {
                try Task.checkCancellation(); try authorizeRun(edition)
                let value = try await operation()
                try Task.checkCancellation(); try authorizeRun(edition)
                return value
            }
        }
        ExecutionCancellation.shared.register(id, scope: executionScope + ":" + edition.instanceID) { task.cancel() }
        defer { ExecutionCancellation.shared.remove(id) }
        return try await withTaskCancellationHandler { try await task.value } onCancel: { task.cancel() }
    }

    private func authorizeRun(_ edition: Edition) throws {
        try statement("SELECT 1 FROM local_applets WHERE account=? AND key=? AND revision=? AND instance=?",
                      [.text(account),.text(edition.id),.integer(edition.revision),.text(edition.instanceID)]) { query in
            switch sqlite3_step(query) {
            case SQLITE_ROW: break
            case SQLITE_DONE: throw AppletRuntimeError.revoked
            default: throw failure()
            }
        }
        if let lease = Self.executionLease {
            let state = try executionState(edition)
            guard lease.account == account, lease.instance == edition.instanceID else { throw AppletRuntimeError.revoked }
            guard !state.paused else { throw AppletRuntimeError.paused }
            guard lease.generation == state.generation else { throw AppletRuntimeError.executionStopped }
        }
    }

    private actor RunPhotos {
        var images = [String: Data]()
        var keptReferences = [String: String]()

        func image(_ reference: String) -> Data? { images[reference] }
        func kept(_ reference: String) -> String? { keptReferences[reference] }
        func associate(_ reference: String, _ kept: String) { keptReferences[reference] = kept }
        static func validate(_ photo: ReviewedPhoto, allowDefaultCamera: Bool = false) throws {
            guard (["front","rear"].contains(photo.camera) || (allowDefaultCamera && photo.camera == "default")), photo.jpeg.starts(with: [0xff, 0xd8]),
                  photo.jpeg.count <= 2 * 1024 * 1024,
                  let source = CGImageSourceCreateWithData(photo.jpeg as CFData, nil),
                  (CGImageSourceGetType(source) as String?) == UTType.jpeg.identifier,
                  let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString:Any],
                  let width = properties[kCGImagePropertyPixelWidth] as? Int,
                  let height = properties[kCGImagePropertyPixelHeight] as? Int,
                  (1...4096).contains(width), (1...4096).contains(height) else {
                throw AppletRuntimeError.invalid("Invalid reviewed JPEG")
            }
        }

        func store(_ photo: ReviewedPhoto, key: String) throws -> JSONValue {
            try Self.validate(photo)
            guard images.values.reduce(0, { $0 + $1.count }) + photo.jpeg.count <= 16 * 1024 * 1024 else {
                throw AppletRuntimeError.invalid("This run has reached its photo limit")
            }
            let reference = "photo:" + key
            images[reference] = photo.jpeg
            return .object(["photo":.string(reference), "camera":.string(photo.camera),
                            "taken_at":.string(ISO8601DateFormatter().string(from: Date()))])
        }
    }

    private struct ForegroundHost: AppletHost {
        let library: LocalAppletLibrary
        let edition: Edition
        let packageID: String
        let title: String
        let tables: [String: LocalRecordTable]
        let photos: RunPhotos
        let flow: String
        let limit: Int
        let owner: String
        let source: BatchSource?
        let budget: JSONValue?
        let decide: Decide?
        let capture: CapturePhoto?
        let hear: Hear?
        let mayNotify: Bool
        let ask: AnswerOwner?
        let present: @Sendable (Notice) async throws -> Void
        var supportedSteps: Set<String> {
            var supported = Set<String>()
            if mayNotify { supported.insert("notice"); if ask != nil { supported.insert("ask_owner") } }
            if decide != nil { supported.formUnion(["ask_model","kitchen","appraise"]) }
            if capture != nil { supported.insert("photo") }
            if hear != nil { supported.insert("hear") }
            if !tables.isEmpty { supported.formUnion(["record","update"]) }
            return supported
        }
        func authorize(instance: String) async throws {
            guard instance == edition.id else { throw AppletRuntimeError.revoked }
            try await library.authorizeRun(edition)
        }
        func perform(type: String, arguments: [String: JSONValue], context: AppletActionContext) async throws -> JSONValue {
            if type == "hear", let hear, case .number(let seconds) = arguments["duration_seconds"] ?? .number(5) {
                let text = try await hear(Int(seconds),arguments["language"]?.text)
                guard !text.isEmpty, text.utf8.count <= 65536 else { throw AppletRuntimeError.invalid("Missing or oversized transcript") }
                return .object(["transcript":.string(text),"processing":.string("device"),"model":.string("apple-on-device"),"cost_usd":.number(0)])
            }
            if ["ask_model","appraise","kitchen"].contains(type), let decide {
                var jpeg: Data?
                let service: DecisionService
                var options = [String:JSONValue]()
                if type == "ask_model" {
                    service = .clef
                    if let photo = arguments["photo"] {
                        guard let ref = photo.text, let data = await photos.image(ref) else { throw AppletRuntimeError.invalid("Missing photo from this run") }
                        jpeg = data
                    }
                } else {
                    guard let reference = arguments["photo"]?.text else { throw AppletRuntimeError.invalid("Missing saved photo") }
                    let kept = await photos.kept(reference) ?? reference
                    jpeg = try await library.photo(kept,in:edition)
                    if type == "appraise" {
                        service = .appraise
                        options["max_items"] = arguments["max_items"] ?? .number(20)
                    } else {
                        service = .chef
                        options["mode"] = .string("scan")
                        if let area = arguments["area"] {
                            guard ["Fridge","Cupboard","Spices"].contains(area.text) else { throw AppletRuntimeError.invalid("Unknown kitchen area") }
                            options["area"] = area
                        }
                    }
                }
                let request = Decision(service:service,options:options,questions:arguments["questions"]?.fields ?? [:],
                                       jpeg:jpeg, flow:flow, libraryKey:edition.id,libraryRevision:edition.revision, packageID:packageID, owner:owner, budget:budget, name:edition.name)
                if let source, let answer = try await library.batchAnswer(in:edition,source:source,effect:context.idempotencyKey) {
                    return answer
                }
                let answer = try await decide(request) {
                    try Task.checkCancellation()
                    try await library.reserveDecision(edition, flow:flow, limit:limit, source:source)
                }
                if let source { try await library.saveBatchAnswer(answer,in:edition,source:source,effect:context.idempotencyKey) }
                return answer
            }
            if type == "photo", let capture {
                let photo = try await capture(arguments["camera"]?.text ?? "rear")
                try Task.checkCancellation()
                try await authorize(instance: context.instance)
                var result = try await photos.store(photo, key: context.idempotencyKey).fields ?? [:]
                if arguments["keep"] == .bool(true), let reference = result["photo"]?.text {
                    let kept = try await library.keepPhoto(photo.jpeg, in: edition, effect: context.idempotencyKey)
                    await photos.associate(reference, kept)
                    result["kept"] = .string(kept)
                }
                return .object(result)
            }
            if type == "record" || type == "update" {
                guard let name = arguments["table"]?.text, let table = tables[name] else {
                    throw AppletRuntimeError.invalid("A table write requires a declared table")
                }
                func mapped(_ value: JSONValue?) async throws -> [String:JSONValue] {
                    guard var row = value?.fields, !row.isEmpty else { throw AppletRuntimeError.invalid("Missing row values") }
                    for (key, column) in table.columns where column["type"] == .string("photo") {
                        if let reference = row[key]?.text, let kept = await photos.kept(reference) { row[key] = .string(kept) }
                    }
                    return row
                }
                if type == "record", case .array(let batch)? = arguments["row"] {
                    var rows = [[String:JSONValue]]()
                    for value in batch { rows.append(try await mapped(value)) }
                    let ids = try await library.writeBatch(edition, table:table, rows:rows, effect:context.idempotencyKey, source:source)
                    return .object(["row_ids":.array(ids.map(JSONValue.string)), "count":.number(Double(ids.count))])
                }
                let row = try await mapped(arguments[type == "update" ? "set" : "row"])
                if type == "update" {
                    guard let id = arguments["row"]?.text, !id.isEmpty else { throw AppletRuntimeError.invalid("Missing row ID") }
                    try await library.writeRow(edition, table:table, row:row, effect:context.idempotencyKey, updating:id, source:source)
                    return .object(["row_id":.string(id)])
                }
                try await library.writeRow(edition, table:table, row:row, effect:context.idempotencyKey, source:source)
                return .object(["row_id":.string(LocalAppletLibrary.rowID(context.idempotencyKey))])
            }
            guard mayNotify, ["notice","ask_owner"].contains(type), arguments["priority"]?.text != "urgent" else { throw AppletRuntimeError.unavailable(type) }
            func text(_ value: JSONValue?) throws -> String {
                switch value {
                case nil: return ""
                case .string(let s): return s
                case .array(let parts): return try parts.map { try text($0) }.joined()
                default: throw AppletRuntimeError.invalid("Notice text must contain text fragments")
                }
            }
            let heading = try text(arguments["title"])
            let notice = Notice(id: context.idempotencyKey, title: String((heading.isEmpty ? title : heading).prefix(120)),
                                text: String(try text(arguments["text"]).prefix(4096)))
            if type == "ask_owner" {
                guard let ask, case .array(let values)? = arguments["choices"],
                      (2...6).contains(values.count) else { throw AppletRuntimeError.invalid("Choose 2–6 answers") }
                let choices = values.compactMap(\.text)
                guard choices.count == values.count, Set(choices).count == choices.count,
                      choices.allSatisfy({ (1...80).contains($0.utf16.count) }),
                      case .number(let seconds) = arguments["wait_s"] ?? .number(300),
                      seconds.rounded() == seconds, (30...3600).contains(seconds) else {
                    throw AppletRuntimeError.invalid("Invalid owner question choices or timeout")
                }
                let question = OwnerQuestion(id: notice.id, title: notice.title, text: notice.text, choices: choices)
                return try await withThrowingTaskGroup(of: JSONValue.self) { group in
                    group.addTask {
                        let answer = try await ask(question)
                        guard choices.contains(answer) else { throw AppletRuntimeError.invalid("Unknown answer") }
                        return .object(["choice": .string(answer)])
                    }
                    group.addTask {
                        try await Task.sleep(nanoseconds: UInt64(seconds) * 1_000_000_000)
                        return .object(["timed_out": .bool(true)])
                    }
                    defer { group.cancelAll() }
                    guard let result = try await group.next() else { throw CancellationError() }
                    return result
                }
            }
            try await present(notice)
            return .object(["interaction_id":.string(notice.id)])
        }
    }
}

extension LocalAppletLibrary {
    // A run UUID and step index already uniquely identify this write. The public row
    // identifier must also fit the package row-reference grammar (1–40 alphanumerics).
    private static func rowID(_ effect: String) -> String {
        effect.lowercased().filter { $0 != "-" && $0 != ":" }
    }

    private func execute(_ sql: String, _ bindings: [Value] = []) throws {
        try statement(sql, bindings) { guard sqlite3_step($0) == SQLITE_DONE else { throw failure() } }
    }

    private func reserveDecision(_ edition: Edition, flow: String, limit: Int, source: BatchSource?) throws {
        var bindings: [Value] = [.text(flow),.text(account),.text(edition.id),.text(edition.instanceID),.integer(edition.revision)]
        var guardSource = ""
        if let source {
            guardSource = """
                AND EXISTS (SELECT 1 FROM applet_rows r WHERE r.instance=local_applets.instance
                AND r.table_id=? AND lower(replace(replace(r.effect,'-',''),':',''))=? AND r.payload=? AND r.expires>unixepoch())
                """
            bindings += [.text(source.table),.text(source.id),.bytes(source.payload)]
        }
        try execute("""
            INSERT INTO applet_paid_days(instance,flow,day,calls)
            SELECT instance,?,date('now','localtime'),1 FROM local_applets WHERE account=? AND key=? AND instance=? AND revision=?
            \(guardSource)
            ON CONFLICT(instance,flow) DO UPDATE SET day=excluded.day,
                calls=CASE WHEN day=excluded.day THEN calls+1 ELSE 1 END
            WHERE day!=excluded.day OR calls<?
            """,bindings + [.integer(limit)])
        if sqlite3_changes(db) == 0 {
            try authorizeRun(edition)
            if let source { try authorizeSource(source,in:edition) }
            throw AppletRuntimeError.invalid("Workflow daily paid-call limit reached")
        }
    }

    private func authorizeSource(_ source: BatchSource, in edition: Edition) throws {
        try statement("""
            SELECT 1 FROM applet_rows WHERE instance=? AND table_id=? AND lower(replace(replace(effect,'-',''),':',''))=?
            AND payload=? AND expires>unixepoch()
            """,[.text(edition.instanceID),.text(source.table),.text(source.id),.bytes(source.payload)]) { query in
            guard sqlite3_step(query) == SQLITE_ROW else { throw AppletRuntimeError.revoked }
        }
    }

    private func batchAnswer(in edition: Edition, source: BatchSource, effect: String) throws -> JSONValue? {
        try authorizeRun(edition)
        try authorizeSource(source,in:edition)
        return try statement("SELECT result FROM applet_batch_answers WHERE instance=? AND source_id=? AND effect=?",
                             [.text(edition.instanceID),.text(source.id),.text(effect)]) { query in
            switch sqlite3_step(query) {
            case SQLITE_DONE: return nil
            case SQLITE_ROW:
                let bytes = Data(bytes:sqlite3_column_blob(query,0),count:Int(sqlite3_column_bytes(query,0)))
                return try JSONDecoder().decode(JSONValue.self,from:bytes)
            default: throw failure()
            }
        }
    }

    private func saveBatchAnswer(_ answer: JSONValue, in edition: Edition, source: BatchSource, effect: String) throws {
        let data = try JSONEncoder().encode(answer)
        guard data.count <= 131072 else { throw AppletRuntimeError.invalid("Batch response exceeds 128 KiB") }
        try execute("""
            INSERT INTO applet_batch_answers(instance,source_id,effect,result)
            SELECT a.instance,?,?,? FROM local_applets a JOIN applet_rows r ON r.instance=a.instance
            WHERE a.account=? AND a.key=? AND a.instance=? AND a.revision=?
            AND lower(replace(replace(r.effect,'-',''),':',''))=? AND r.expires>unixepoch() AND r.table_id=? AND r.payload=?
            """,[.text(source.id),.text(effect),.bytes(data),.text(account),.text(edition.id),.text(edition.instanceID),.integer(edition.revision),.text(source.id),.text(source.table),.bytes(source.payload)])
        guard sqlite3_changes(db) == 1 else { throw AppletRuntimeError.revoked }
    }

    private func keepPhoto(_ bytes: Data, in edition: Edition, effect: String) throws -> String {
        let ref = "kept:" + Self.rowID(effect)
        let instance = Value.text(edition.instanceID)
        try execute("BEGIN IMMEDIATE")
        do {
            try authorizeRun(edition)
            try execute("DELETE FROM applet_rows WHERE expires <= unixepoch()")
            try execute(Self.collectPhotos)
            try insertPhoto(bytes,reference:ref,instance:instance)
            try execute("COMMIT")
            return ref
        } catch { try? execute("ROLLBACK"); throw error }
    }

    private func insertPhoto(_ bytes: Data, reference ref: String, instance: Value) throws {
        try execute("INSERT OR IGNORE INTO applet_photos VALUES(?,?,unixepoch(),?)", [instance,.text(ref),.bytes(bytes)])
        try statement("SELECT COUNT(*), SUM(length(jpeg)) FROM applet_photos WHERE instance=?", [instance]) { query in
            guard sqlite3_step(query) == SQLITE_ROW else { throw failure() }
            guard sqlite3_column_int(query,0) <= 1000, sqlite3_column_int64(query,1) <= 500 * 1024 * 1024 else {
                throw AppletRuntimeError.invalid("Applet photo storage limit reached")
            }
        }
    }

    public func photo(_ reference: String, in edition: Edition) throws -> Data {
        try authorizeRun(edition)
        return try statement("""
            SELECT p.jpeg FROM applet_photos p JOIN local_applets a ON p.instance=a.instance
            WHERE a.account=? AND a.key=? AND a.revision=? AND a.instance=? AND p.reference=?
            AND (p.created > unixepoch()-604800 OR EXISTS (
                SELECT 1 FROM applet_row_photos l JOIN applet_rows r ON r.instance=l.instance AND r.effect=l.effect
                WHERE l.instance=p.instance AND l.reference=p.reference AND r.expires>unixepoch()))
            """, [.text(account),.text(edition.id),.integer(edition.revision),.text(edition.instanceID),.text(reference)]) { query in
                guard sqlite3_step(query) == SQLITE_ROW else { throw AppletRuntimeError.invalid("Photo unavailable") }
                return Data(bytes:sqlite3_column_blob(query,0), count:Int(sqlite3_column_bytes(query,0)))
            }
    }

    private func writeBatch(_ edition: Edition, table: LocalRecordTable, rows: [[String:JSONValue]], effect: String, source: BatchSource?) throws -> [String] {
        guard rows.count <= min(50, table.limit) else { throw AppletRuntimeError.invalid("Too many rows for one record step") }
        let writes = rows.enumerated().map { (effect + "x" + String($0.offset),$0.element) }
        try writeRows(edition, table:table, writes:writes, source:source)
        return writes.map { Self.rowID($0.0) }
    }

    private func writeRow(_ edition: Edition, table: LocalRecordTable, row: [String:JSONValue], effect: String, updating id: String? = nil, source: BatchSource?) throws {
        try writeRows(edition, table:table, writes:[(effect,row)], updating:id, source:source)
    }

    private func writeRows(_ edition: Edition, table: LocalRecordTable, writes: [(String,[String:JSONValue])], updating id: String? = nil, cameraPhoto: (String,Data)? = nil, source: BatchSource? = nil, reviewed: Instance? = nil) throws {
        try execute("BEGIN IMMEDIATE")
        do {
            try authorizeRun(edition)
            if let reviewed {
                guard reviewed.edition.instanceID == edition.instanceID, reviewed.edition.revision == edition.revision,
                      try open(edition).configData == reviewed.configData else {
                    throw AppletRuntimeError.invalid("Settings changed. Review the input and its draft again.")
                }
            }
            if let source { try authorizeSource(source,in:edition) }
            try execute("DELETE FROM applet_rows WHERE expires <= unixepoch()")
            try execute(Self.collectPhotos)
            let instance = Value.text(edition.instanceID)
            if let (ref,bytes) = cameraPhoto { try insertPhoto(bytes,reference:ref,instance:instance) }
            var changed = false
            for (effect,row) in writes {
                if try persistRow(instance, table:table, row:row, effect:effect, updating:id) { changed = true }
            }
            if changed && cameraPhoto == nil {
                try execute("DELETE FROM applet_row_writes WHERE at<=unixepoch()-60")
                try statement("SELECT COUNT(*) FROM applet_row_writes WHERE instance=?", [instance]) { query in
                    guard sqlite3_step(query) == SQLITE_ROW else { throw failure() }
                    guard sqlite3_column_int(query,0) < 60 else { throw AppletRuntimeError.invalid("Wait a minute before recording more rows") }
                }
                try execute("INSERT INTO applet_row_writes VALUES(?,unixepoch())",[instance])
            }
            try execute("""
                DELETE FROM applet_rows WHERE rowid IN (
                    SELECT rowid FROM applet_rows WHERE instance=? AND table_id=?
                    ORDER BY at DESC,rowid DESC LIMIT -1 OFFSET ?)
                """, [instance,.text(table.id),.integer(table.limit)])
            try statement("SELECT SUM(length(payload)+200) FROM applet_rows WHERE instance=?", [instance]) { query in
                guard sqlite3_step(query) == SQLITE_ROW else { throw failure() }
                guard sqlite3_column_int64(query,0) <= 5_000_000 else { throw AppletRuntimeError.invalid("Applet row quota reached") }
            }
            try execute("COMMIT")
        } catch {
            try? execute("ROLLBACK")
            throw error
        }
    }

    private func persistRow(_ instance: Value, table: LocalRecordTable, row: [String:JSONValue], effect: String, updating id: String?) throws -> Bool {
        var values = row
        var rowEffect = effect
        if let id {
            let existing = try statement("""
                SELECT effect,payload FROM applet_rows WHERE instance=? AND table_id=?
                AND lower(replace(replace(effect,'-',''),':',''))=?
                """, [instance,.text(table.id),.text(id)]) { query -> (String,[String:JSONValue]) in
                guard sqlite3_step(query) == SQLITE_ROW else { throw AppletRuntimeError.invalid("Row no longer available") }
                let bytes = Data(bytes:sqlite3_column_blob(query,1),count:Int(sqlite3_column_bytes(query,1)))
                return (String(cString:sqlite3_column_text(query,0)),try JSONDecoder().decode([String:JSONValue].self,from:bytes))
            }
            rowEffect = existing.0
            values = existing.1.merging(row) { _, new in new }
            let payload = try table.payload(values)
            try execute("DELETE FROM applet_row_photos WHERE instance=? AND effect=?", [instance,.text(rowEffect)])
            try execute("UPDATE applet_rows SET payload=? WHERE instance=? AND effect=?", [.bytes(payload),instance,.text(rowEffect)])
        } else {
            let payload = try table.payload(values)
            try execute("""
                INSERT INTO applet_rows(instance,table_id,effect,at,expires,payload)
                VALUES(?,?,?,unixepoch(),unixepoch()+?,?) ON CONFLICT(instance,effect) DO NOTHING
                """, [instance,.text(table.id),.text(effect),.integer(table.days*86400),.bytes(payload)])
        }
        guard sqlite3_changes(db) > 0 else { return false }
        for (key, column) in table.columns where column["type"] == .string("photo") {
            if let reference = values[key]?.text {
                try execute("""
                    INSERT OR IGNORE INTO applet_row_photos SELECT instance,?,reference FROM applet_photos
                    WHERE instance=? AND reference=?
                    """, [.text(rowEffect),instance,.text(reference)])
                // Multiple columns can name the same photo.
                try statement("SELECT 1 FROM applet_row_photos WHERE instance=? AND effect=? AND reference=?",
                              [instance,.text(rowEffect),.text(reference)]) { query in
                    guard sqlite3_step(query) == SQLITE_ROW else { throw AppletRuntimeError.invalid("Photo is unavailable to this instance") }
                }
            }
        }
        return true
    }

    public struct TimelineEntry: Identifiable, Sendable {
        public let id: String
        public let label: String
        public let detail: String?
        public let date: Date
    }

    public func timeline(_ piece: JSONValue, in edition: Edition) throws -> [TimelineEntry] {
        let package = try open(edition).package
        let path = piece["items"]?["$source"]?.text?.components(separatedBy:".") ?? []
        guard piece["type"] == .string("timeline"), piece["only_if"] == nil,
              let key = path.first, path == [key,"items"] || path == [key],
              let source = package.definition["ui"]?["card"]?["sources"]?[key],
              source.fields?.keys.allSatisfy({ ["table","limit"].contains($0) }) == true,
              let table = source["table"]?.text, let declaration = try LocalRecordTable.declarations(package)[table],
              piece["time"] == .string("at"), let label = piece["label"]?.text,
              declaration.columns[label] != nil,
              [nil,"text","words"].contains(piece["label_format"]?.text),
              [nil,"time","ago"].contains(piece["time_format"]?.text),
              case .number(let count) = source["limit"] ?? .number(20), count.rounded() == count, (1...500).contains(count),
              case .number(let limit) = piece["limit"] ?? .number(10), limit.rounded() == limit, (1...20).contains(limit)
        else { throw AppletRuntimeError.unavailable("this timeline source or format") }
        func display(_ value: JSONValue?) -> String? {
            switch value {
            case .string(let text): return text
            case .number(let number): return number.formatted()
            case .bool(let flag): return String(flag)
            default: return nil
            }
        }
        return try rows(in:edition,table:table,limit:Int(min(count,limit))).compactMap { row in
            guard let id = row["id"]?.text, case .number(let at)? = row["at"] else { return nil }
            let raw = display(row[label]) ?? "—"
            let words = piece["label_format"] == .string("text") ? nil : piece["label_words"]
            return TimelineEntry(id:id,label:words?[raw]?.text ?? raw,
                                 detail:piece["detail"]?.text.flatMap { display(row[$0]) },
                                 date:Date(timeIntervalSince1970:at/1000))
        }
    }

    public struct GalleryPhoto: Identifiable, Sendable {
        public let id: String
        public let reference: String
        public let caption: String
    }

    public func gallery(_ piece: JSONValue, in edition: Edition) throws -> [GalleryPhoto] {
        let package = try open(edition).package
        guard let card = package.definition["ui"]?["card"], case .array(let pieces)? = card["pieces"],
              pieces.contains(piece), piece["type"] == .string("gallery"),
              piece.fields?.keys.allSatisfy({ ["type","photos","photo","caption","caption_count","words","limit","empty","caption_format"].contains($0) }) == true,
              let sourceName = piece["photos"]?["$source"]?.text, piece["photos"]?.fields?.count == 1,
              let source = card["sources"]?[sourceName], let table = source["table"]?.text,
              source.fields?.keys.allSatisfy({ ["table","limit"].contains($0) }) == true,
              let column = piece["photo"]?.text,
              try LocalRecordTable.declarations(package)[table]?.columns[column]?["type"] == .string("photo"),
              case .number(let sourceLimit) = source["limit"] ?? .number(20), (1...500).contains(sourceLimit), sourceLimit.rounded() == sourceLimit,
              case .number(let limit) = piece["limit"] ?? .number(12), (1...100).contains(limit), limit.rounded() == limit,
              [nil,"text","words"].contains(piece["caption_format"]?.text) else {
            throw AppletRuntimeError.unavailable("requested gallery source or format")
        }
        return try rows(in:edition,table:table,limit:Int(min(limit,sourceLimit))).compactMap { row in
            guard let id = row["id"]?.text, let reference = row[column]?.text else { return nil }
            var parts = [String]()
            if let key = piece["caption"]?.text, let raw = row[key]?.text {
                let words = piece["caption_format"] == .string("text") ? nil : piece["words"]
                parts.append(words?[raw]?.text ?? raw)
            }
            if let countKey = piece["caption_count"]?.text, case .number(let n)? = row[countKey],
               let count = Int(exactly:n), count >= 0 {
                parts.append(count == 1 ? "1 item" : String(count) + " items")
            }
            return GalleryPhoto(id:id,reference:reference,caption:parts.joined(separator:" · "))
        }
    }

    private func decodeRow(_ query: OpaquePointer) throws -> [String:JSONValue] {
        let data = Data(bytes:sqlite3_column_blob(query,0),count:Int(sqlite3_column_bytes(query,0)))
        var row = try JSONDecoder().decode([String:JSONValue].self,from:data)
        row["at"] = .number(Double(sqlite3_column_int64(query,1)) * 1000)
        let effect = String(cString:sqlite3_column_text(query,2))
        row["id"] = .string(Self.rowID(effect))
        if effect.hasSuffix(":card") {
            row["by"] = .object(["type":.string("card")])
        } else if effect.hasSuffix(":agent") {
            row["by"] = .object(["type": .string("agent")])
        } else if effect.hasSuffix(":camera") {
            row["by"] = .object(["type":.string("camera"),"camera":.bool(true)])
        } else {
            row["by"] = .object(["type":.string("flow"),"run_id":.string(String(effect.prefix(while: { $0 != ":" })))])
        }
        return row
    }

    /// The host must check its caller's applet write grant. Storage still enforces
    /// the exact account/installation, declared schema, retention and shared write quotas.
    /// Let a host run an already prepared effect under its authorization fence
    /// on the same actor that owns the maintained SQLite connection.
    public func withHostMutation<T: Sendable>(_ effect: @Sendable (isolated LocalAppletLibrary) throws -> T) rethrows -> T {
        try effect(self)
    }

    public func writeAgentRow(_ values: [String: JSONValue], in edition: Edition, table name: String, updating id: String? = nil) throws -> [String: JSONValue] {
        let package = try open(edition).package
        guard let table = try LocalRecordTable.declarations(package)[name] else { throw AppletRuntimeError.missingReference(name) }
        if let id {
            try statement("SELECT 1 FROM applet_rows WHERE instance=? AND table_id=? AND expires>unixepoch() AND lower(replace(replace(effect,'-',''),':',''))=?",
                          [.text(edition.instanceID), .text(name), .text(id)]) { query in
                guard sqlite3_step(query) == SQLITE_ROW else { throw AppletRuntimeError.missingReference(id) }
            }
        }
        let effect = UUID().uuidString + ":agent"
        try writeRow(edition, table: table, row: values, effect: effect, updating: id, source: nil)
        let rowID = id ?? Self.rowID(effect)
        return try statement("SELECT payload,at,effect FROM applet_rows WHERE instance=? AND table_id=? AND lower(replace(replace(effect,'-',''),':',''))=?",
                             [.text(edition.instanceID), .text(name), .text(rowID)]) { query in
            guard sqlite3_step(query) == SQLITE_ROW else { throw AppletRuntimeError.missingReference(rowID) }
            return try decodeRow(query)
        }
    }

    public func deleteAgentRow(_ id: String, in edition: Edition, table name: String) throws {
        try execute("BEGIN IMMEDIATE")
        do {
            let package = try open(edition).package
            guard try LocalRecordTable.declarations(package)[name] != nil else { throw AppletRuntimeError.missingReference(name) }
            try execute("DELETE FROM applet_rows WHERE instance=? AND table_id=? AND expires>unixepoch() AND lower(replace(replace(effect,'-',''),':',''))=?",
                        [.text(edition.instanceID), .text(name), .text(id)])
            guard sqlite3_changes(db) == 1 else { throw AppletRuntimeError.missingReference(id) }
            try execute(Self.collectPhotos)
            try execute("COMMIT")
        } catch { try? execute("ROLLBACK"); throw error }
    }

    /// Owner-initiated deletion uses the same installation and declared-table boundaries as agent operations.
    public func deleteOwnerRow(_ id: String, in edition: Edition, table name: String) throws {
        let count = try deleteOwnerRows(in: edition, table: name, rowID: id)
        guard count == 1 else { throw AppletRuntimeError.missingReference(id) }
    }

    /// Atomically deletes all live rows in one declared table and only photos exclusively referenced by them.
    @discardableResult public func deleteOwnerRows(in edition: Edition, table name: String) throws -> Int {
        try deleteOwnerRows(in: edition, table: name, rowID: nil)
    }

    private func deleteOwnerRows(in edition: Edition, table name: String, rowID: String?) throws -> Int {
        try Task.checkCancellation()
        try execute("BEGIN IMMEDIATE")
        do {
            let package = try open(edition).package
            guard try LocalRecordTable.declarations(package)[name] != nil else { throw AppletRuntimeError.missingReference(name) }
            let condition = rowID == nil ? "" : " AND lower(replace(replace(effect,'-',''),':',''))=?"
            var rowValues: [Value] = [.text(edition.instanceID), .text(name)]
            if let rowID { rowValues.append(.text(rowID)) }
            var photoValues: [Value] = [.text(edition.instanceID)]
            photoValues.append(contentsOf: rowValues)
            let photos: [String] = try statement("""
                SELECT DISTINCT reference FROM applet_row_photos WHERE instance=? AND effect IN
                (SELECT effect FROM applet_rows WHERE instance=? AND table_id=? AND expires>unixepoch()\(condition))
                """, photoValues) { query in
                    var result: [String] = []
                    while true {
                        switch sqlite3_step(query) {
                        case SQLITE_DONE: return result
                        case SQLITE_ROW: result.append(String(cString: sqlite3_column_text(query, 0)))
                        default: throw failure()
                        }
                    }
                }
            try execute("DELETE FROM applet_rows WHERE instance=? AND table_id=? AND expires>unixepoch()" + condition, rowValues)
            let count = Int(sqlite3_changes(db))
            if let rowID, count != 1 { throw AppletRuntimeError.missingReference(rowID) }
            for ref in photos {
                try Task.checkCancellation()
                try execute("""
                    DELETE FROM applet_photos WHERE instance=? AND reference=? AND NOT EXISTS
                    (SELECT 1 FROM applet_row_photos WHERE instance=? AND reference=?)
                    """, [.text(edition.instanceID), .text(ref), .text(edition.instanceID), .text(ref)])
            }
            try Task.checkCancellation()
            try execute("COMMIT")
            return count
        } catch { try? execute("ROLLBACK"); throw error }
    }

    /// Routes are scoped to the installed card principal. Never proxy a package-supplied path to the network.
    public func cardTableAction(_ request: JSONValue, in edition: Edition) throws -> JSONValue {
        try Task.checkCancellation()
        let package = try open(edition).package
        guard package.cardHTML != nil,
              case .array(let grants)? = package.definition["permissions"]?["required"], grants.contains(.string("applet_data")),
              let path = request["path"]?.text else { throw AppletRuntimeError.unavailable("applet_data") }
        let parts = path.components(separatedBy:"/")
        guard parts.count == 6, Array(parts.prefix(4)) == ["","v1","applet","tables"], parts.last == "rows",
              let table = try LocalRecordTable.declarations(package)[parts[4]] else {
            throw AppletRuntimeError.unavailable(path)
        }
        let scope: [Value] = [.text(edition.instanceID),.text(table.id)]
        let method = request["method"] ?? .string("GET")
        if method == .string("POST"), let row = request["body"]?["row"]?.fields {
            let effect = UUID().uuidString + ":card"
            try writeRow(edition,table:table,row:row,effect:effect,source:nil)
            return try statement("SELECT payload,at,effect FROM applet_rows WHERE instance=? AND table_id=? AND effect=?",
                                 scope + [.text(effect)]) { query in
                guard sqlite3_step(query) == SQLITE_ROW else { throw failure() }
                return .object(try decodeRow(query))
            }
        }
        guard method == .string("GET"), request["body"] == nil else { throw AppletRuntimeError.unavailable(path) }
        let query = request["query"]
        guard query == nil || query?.fields?.keys.allSatisfy({ ["limit","since"].contains($0) }) == true else {
            throw AppletRuntimeError.invalid("Invalid row query")
        }
        let limit: Int
        switch query?["limit"] {
        case nil: limit = 50
        case .number(let n): limit = Int(exactly:n) ?? 0
        case .string(let n): limit = Int(n) ?? 0
        default: limit = 0
        }
        guard (1...200).contains(limit) else { throw AppletRuntimeError.invalid("Read 1–200 rows at once") }
        try execute("BEGIN")
        defer { try? execute("ROLLBACK") }
        try authorizeRun(edition)
        let whereTable = " FROM applet_rows WHERE instance=? AND table_id=?"
        var after = 0
        if let since = query?["since"] {
            guard let id = since.text else { throw AppletRuntimeError.invalid("Invalid row cursor") }
            after = try statement("SELECT rowid" + whereTable + " AND lower(replace(replace(effect,'-',''),':',''))=?",
                                  scope + [.text(id)]) { cursor in
                guard sqlite3_step(cursor) == SQLITE_ROW else { throw AppletRuntimeError.invalid("Unknown row cursor") }
                return Int(sqlite3_column_int64(cursor,0))
            }
        }
        let live = whereTable + " AND expires>unixepoch()"
        let count = try statement("SELECT COUNT(*)" + live, scope) { cursor in
            guard sqlite3_step(cursor) == SQLITE_ROW else { throw failure() }
            return Int(sqlite3_column_int64(cursor,0))
        }
        return try statement("SELECT payload,at,effect" + live + " AND rowid>? ORDER BY rowid LIMIT ?",
                             scope + [.integer(after),.integer(limit+1)]) { cursor in
            var items = [JSONValue](), bytes = 0
            var next = JSONValue.null
            while true {
                switch sqlite3_step(cursor) {
                case SQLITE_ROW:
                    let row = JSONValue.object(try decodeRow(cursor))
                    bytes += try JSONEncoder().encode(row).count + 2
                    if items.count == limit || bytes > 40000 {
                        next = items.last?["id"] ?? .null
                    } else { items.append(row); continue }
                case SQLITE_DONE: break
                default: throw failure()
                }
                return .object(["items":.array(items),"next":next,"count":.number(Double(count))])
            }
        }
    }

    public func deleteDisplayedRow(_ id: String, from piece: JSONValue, in edition: Edition) throws {
        try execute("BEGIN IMMEDIATE")
        do {
            let package = try open(edition).package
            guard let card = package.definition["ui"]?["card"], case .array(let pieces)? = card["pieces"],
                  pieces.contains(piece), piece["type"] == .string("table"), piece["owner_delete"] == .bool(true),
                  piece["only_if"] == nil, let reference = piece["rows"]?["$source"]?.text,
                  let source = reference.split(separator:".").first.map(String.init),
                  reference == source || reference == source + ".items",
                  let table = card["sources"]?[source]?["table"]?.text,
                  try LocalRecordTable.declarations(package)[table] != nil else {
                throw AppletRuntimeError.unavailable("Owner deletion for this table")
            }
            try execute("""
                DELETE FROM applet_rows WHERE instance=? AND table_id=?
                  AND lower(replace(replace(effect,'-',''),':',''))=?
                """,[.text(edition.instanceID),.text(table),.text(id)])
            guard sqlite3_changes(db) == 1 else { throw AppletRuntimeError.invalid("Row changed. Reopen the table.") }
            try execute(Self.collectPhotos)
            try execute("COMMIT")
        } catch {
            try? execute("ROLLBACK")
            throw error
        }
    }

    public struct RowsPage: Sendable {
        public let rows: [[String: JSONValue]]
        public let next: String?
    }
    private struct RowsCursor: Codable {
        let scope: String
        let at: Int64
        let sequence: Int64
    }
    public func rows(in edition: Edition, table: String, limit: Int) throws -> [[String:JSONValue]] {
        try readRowsPage(in: edition, table: table, limit: limit, before: nil, validateEdition: false).rows
    }
    /// Read the rows for an owner's declared CSV action from a single database snapshot.
    public func csvRows(in edition: Edition, table: String, filter: CSVRowFilter? = nil) throws -> [[String: JSONValue]] {
        try execute("BEGIN")
        defer { try? execute("ROLLBACK") }
        let package = try open(edition).package
        guard package.csvButtons.contains(where: { $0.table == table && $0.filter == filter }) else {
            throw AppletRuntimeError.unavailable("CSV export for " + table)
        }
        var records: [[String: JSONValue]] = []
        var cursor: String?
        repeat {
            let page = try readRowsPage(in: edition, table: table, limit: 500, before: cursor, validateEdition: false)
            records += page.rows
            guard records.count <= 10_000 else { throw AppletRuntimeError.invalid("An export holds at most 10,000 rows.") }
            cursor = page.next
        } while cursor != nil
        return records.reversed().filter { filter?.includes($0) ?? true }
    }

    /// Complete owner export for a declared table, independent of package-provided share actions.
    public func ownerTableRows(in edition: Edition, table: String) throws -> [[String: JSONValue]] {
        try Task.checkCancellation()
        try execute("BEGIN")
        defer { try? execute("ROLLBACK") }
        let package = try open(edition).package
        guard try LocalRecordTable.declarations(package)[table] != nil else { throw AppletRuntimeError.missingReference(table) }
        var records: [[String: JSONValue]] = [], cursor: String?
        repeat {
            try Task.checkCancellation()
            let page = try readRowsPage(in: edition, table: table, limit: 500, before: cursor, validateEdition: false)
            records += page.rows
            guard records.count <= 10_000 else { throw OwnerTableExportLimitError() }
            cursor = page.next
        } while cursor != nil
        try Task.checkCancellation()
        return records.reversed()
    }

    /// Read-only, atomic domain snapshot for the shared host data contract. Never
    /// executes an applet, captures media or changes retention state.
    public func savedDataSnapshot(in edition: Edition) throws -> Data {
        try execute("BEGIN")
        defer { try? execute("ROLLBACK") }
        let instance = try open(edition)
        let declarations = try LocalRecordTable.declarations(instance.package)
        var tables: [[String: JSONValue]] = []
        var references = Set<String>()
        for table in declarations.values.sorted(by: { $0.id < $1.id }) {
            var rows: [[String: JSONValue]] = [], cursor: String?
            repeat {
                let page = try readRowsPage(in: edition, table: table.id, limit: 500, before: cursor, validateEdition: false)
                rows.append(contentsOf: page.rows); cursor = page.next
            } while cursor != nil
            for (column, schema) in table.columns where schema["type"] == .string("photo") {
                for row in rows { if let ref = row[column]?.text { references.insert(ref) } }
            }
            var value = table.attributes
            value["columns"] = .object(table.columns)
            value["rows"] = .array(rows.map(JSONValue.object))
            tables.append(value)
        }
        let photos: [[String: JSONValue]] = try statement("SELECT reference,length(jpeg) FROM applet_photos WHERE instance=?", [.text(edition.instanceID)]) { query in
            var values: [[String: JSONValue]] = []
            while true {
                switch sqlite3_step(query) {
                case SQLITE_DONE: return values
                case SQLITE_ROW:
                    let ref = String(cString: sqlite3_column_text(query, 0))
                    if references.contains(ref) {
                        values.append(["ref": .string(ref), "bytes": .number(Double(sqlite3_column_int64(query, 1))), "available": .bool(true), "mime": .string("image/jpeg")])
                    }
                default: throw failure()
                }
            }
        }
        var sources: [String: JSONValue] = [:]
        if let history = try importedDogEpisodes(in: edition), !history.isEmpty {
            sources["historical_episodes"] = .object(["supported": .bool(false), "count": .number(Double(history.count))])
        }
        let result: [String: JSONValue] = ["package": instance.package.definition,
            "tables": .array(tables.map(JSONValue.object)), "photos": .array(photos.map(JSONValue.object)), "native_sources": .object(sources)]
        let data = try JSONEncoder().encode(result)
        try execute("COMMIT")
        return data
    }

    /// Stable keyset pages remain valid if the cursor row is deleted. Cursors confer no access.
    public func rowsPage(in edition: Edition, table: String, limit: Int = 500, before: String? = nil) throws -> RowsPage {
        try readRowsPage(in: edition, table: table, limit: limit, before: before, validateEdition: true)
    }
    private func readRowsPage(in edition: Edition, table: String, limit: Int, before: String?, validateEdition: Bool) throws -> RowsPage {
        try Task.checkCancellation()
        guard (1...500).contains(limit) else { throw AppletRuntimeError.invalid("Read 1–500 rows at once") }
        if validateEdition { _ = try open(edition) }
        let identity = [account, edition.id, String(edition.revision), edition.instanceID, table].joined(separator: "\n")
        let scope = SHA256.hash(data: Data(identity.utf8)).map { String(format: "%02x", $0) }.joined()
        var cursor: RowsCursor?
        if let before {
            guard before.count <= 512, let data = Data(base64Encoded: before),
                  let value = try? JSONDecoder().decode(RowsCursor.self, from: data), value.scope == scope,
                  value.sequence > 0 else { throw AppletRuntimeError.invalid("Invalid table cursor") }
            cursor = value
        }
        var values: [Value] = [.text(account),.text(edition.id),.integer(edition.revision),.text(edition.instanceID),.text(table)]
        var predicate = ""
        if let cursor {
            guard let timestamp = Int(exactly: cursor.at), let sequence = Int(exactly: cursor.sequence) else { throw AppletRuntimeError.invalid("Invalid table cursor") }
            predicate = " AND (r.at < ? OR (r.at = ? AND r.rowid < ?))"
            values += [.integer(timestamp), .integer(timestamp), .integer(sequence)]
        }
        values.append(.integer(limit + 1))
        return try statement("""
            SELECT r.payload,r.at,r.effect,r.rowid FROM applet_rows r JOIN local_applets a ON a.instance=r.instance
            WHERE a.account=? AND a.key=? AND a.revision=? AND a.instance=? AND r.table_id=? AND r.expires>unixepoch()
            """ + predicate + " ORDER BY r.at DESC,r.rowid DESC LIMIT ?", values) { query in
            var rows = [[String:JSONValue]](), last: RowsCursor?
            while true {
                try Task.checkCancellation()
                switch sqlite3_step(query) {
                case SQLITE_DONE: return RowsPage(rows: rows, next: nil)
                case SQLITE_ROW:
                    if rows.count == limit {
                        return RowsPage(rows: rows, next: try last.map { try JSONEncoder().encode($0).base64EncodedString() })
                    }
                    rows.append(try decodeRow(query))
                    last = RowsCursor(scope: scope, at: sqlite3_column_int64(query,1), sequence: sqlite3_column_int64(query,3))
                default: throw failure()
                }
            }
        }
    }
}
