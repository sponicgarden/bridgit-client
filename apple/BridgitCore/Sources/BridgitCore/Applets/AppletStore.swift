import Foundation
import SQLite3

public enum HostActivityKind: String, Codable, CaseIterable, Sendable {
    case request, setting, agent, pairing, service, crash, note
}

public struct HostActivityEntry: Identifiable, Codable, Sendable {
    public let id: Int64
    public let at: Date
    public let agent: String
    public let currentAgentName: String?
    public var isAnonymousRequest: Bool { kind == .request && agentID == nil && agent.isEmpty }
    public var displayAgentName: String { isAnonymousRequest ? "Unidentified client" : currentAgentName ?? agent }
    public let operation: String
    public let status: Int
    public let isSetting: Bool
    public let agentID: String?
    public let kind: HostActivityKind
    public let noteLevel: HostLogLevel?
    let noteData: String?
    public let requestFacts: HostActivityRequestFacts?

    public init(id: Int64, at: Date, agent: String, operation: String, status: Int, isSetting: Bool, agentID: String? = nil, kind: HostActivityKind? = nil, noteLevel: HostLogLevel? = nil, noteData: String? = nil, requestFacts: HostActivityRequestFacts? = nil, currentAgentName: String? = nil) {
        self.id = id; self.at = at; self.agent = agent; self.operation = operation
        self.status = status; self.isSetting = isSetting; self.agentID = agentID
        self.kind = kind ?? (isSetting ? .setting : .request)
        self.noteLevel = noteLevel; self.noteData = noteData; self.requestFacts = requestFacts
        self.currentAgentName = currentAgentName
    }

    private enum CodingKeys: String, CodingKey { case id, at, agent, operation, status, isSetting, agentID, kind, noteLevel, noteData, requestFacts, currentAgentName }
    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(Int64.self, forKey: .id)
        at = try values.decode(Date.self, forKey: .at)
        agent = try values.decode(String.self, forKey: .agent)
        operation = try values.decode(String.self, forKey: .operation)
        status = try values.decode(Int.self, forKey: .status)
        isSetting = try values.decode(Bool.self, forKey: .isSetting)
        agentID = try values.decodeIfPresent(String.self, forKey: .agentID)
        kind = try values.decodeIfPresent(HostActivityKind.self, forKey: .kind) ?? (isSetting ? .setting : .request)
        noteLevel = try values.decodeIfPresent(HostLogLevel.self, forKey: .noteLevel)
        noteData = try values.decodeIfPresent(String.self, forKey: .noteData)
        requestFacts = try values.decodeIfPresent(HostActivityRequestFacts.self, forKey: .requestFacts)
        currentAgentName = try values.decodeIfPresent(String.self, forKey: .currentAgentName)
    }

    public func reportLog(includingRecent recent: [Self]) -> [Self] {
        recent.contains { $0.id == id } ? recent : [self] + recent
    }
}

public enum HostActivityAgentFilter: Hashable, Sendable {
    case identified(String)
    case legacyName(String)
}

public struct HostActivityAgent: Identifiable, Hashable, Sendable {
    public let id: HostActivityAgentFilter
    public let name: String
    public init(id: HostActivityAgentFilter, name: String) { self.id = id; self.name = name }
}

/// Device-local SQLite tables. Every lookup includes the applet identity; package strings never become SQL.
public actor AppletStore {
    private var db: OpaquePointer?
    public enum Change { case inbox, transfers, activity, hostAgents, callbackEvents }
    private var transactionHasCallbackEvents = false
    private var observers: [Change: [UUID: AsyncStream<Void>.Continuation]] = [:]
    private let directory: URL
    private static let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
    public init(directory: URL) throws {
        self.directory = directory
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        let path = directory.appendingPathComponent("applets.sqlite").path
        guard sqlite3_open(path, &db) == SQLITE_OK else { throw DeviceAPIError(500, "storage_unavailable", "Could not open applet storage.") }
        func supportedVersion() throws -> Int {
            guard let value = try strings("PRAGMA user_version").first.flatMap(Int.init), value <= 9 else {
                throw DeviceAPIError(409, "storage_version_unsupported", "This applet database belongs to a newer build.")
            }
            return value
        }
        sqlite3_busy_timeout(db, 5000)
        _ = try supportedVersion()
        guard sqlite3_exec(db, "PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON; BEGIN IMMEDIATE;", nil, nil, nil) == SQLITE_OK else {
            sqlite3_close(db); db = nil
            throw DeviceAPIError(500, "storage_unavailable", "Could not prepare applet storage.")
        }
        let existingVersion = try supportedVersion()
        let schema = """
        CREATE TABLE IF NOT EXISTS packages(id TEXT PRIMARY KEY, body TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS records(applet TEXT NOT NULL, table_id TEXT NOT NULL, row_id TEXT NOT NULL, at REAL NOT NULL, body TEXT NOT NULL, PRIMARY KEY(applet,table_id,row_id));
        CREATE INDEX IF NOT EXISTS record_order ON records(applet,table_id,at);
        CREATE TABLE IF NOT EXISTS media(applet TEXT NOT NULL, ref TEXT PRIMARY KEY, file TEXT NOT NULL, bytes INTEGER NOT NULL, at REAL NOT NULL);
        CREATE TABLE IF NOT EXISTS state(key TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS applet_state(applet TEXT NOT NULL, key TEXT NOT NULL UNIQUE, PRIMARY KEY(applet,key));
        CREATE TABLE IF NOT EXISTS interactions(id TEXT PRIMARY KEY, owner TEXT NOT NULL, body TEXT NOT NULL, expires REAL NOT NULL, at REAL NOT NULL);
        CREATE TABLE IF NOT EXISTS interaction_events(id INTEGER PRIMARY KEY AUTOINCREMENT, owner TEXT NOT NULL, body TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS callback_event_epochs(event_id INTEGER PRIMARY KEY REFERENCES interaction_events(id) ON DELETE CASCADE, grant_epoch INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS callback_config(agent_id TEXT PRIMARY KEY, active_key TEXT, staged_key TEXT, removal_epoch INTEGER NOT NULL DEFAULT 0);
        CREATE TABLE IF NOT EXISTS callback_retired_keys(agent_id TEXT NOT NULL, key TEXT NOT NULL, PRIMARY KEY(agent_id,key));
        CREATE INDEX IF NOT EXISTS interactions_owner ON interactions(owner,at);
        CREATE INDEX IF NOT EXISTS events_owner ON interaction_events(owner,id);
        CREATE TABLE IF NOT EXISTS transfers(id TEXT PRIMARY KEY, owner TEXT NOT NULL, kind TEXT NOT NULL, created INTEGER NOT NULL, metadata TEXT NOT NULL, payload BLOB NOT NULL);
        CREATE INDEX IF NOT EXISTS transfer_owner ON transfers(owner,kind,created);
        CREATE TABLE IF NOT EXISTS owner_requests(id TEXT PRIMARY KEY, owner TEXT NOT NULL, body TEXT NOT NULL, payload TEXT NOT NULL, at REAL NOT NULL, expires REAL NOT NULL);
        CREATE TABLE IF NOT EXISTS host_activity(id INTEGER PRIMARY KEY AUTOINCREMENT, at REAL NOT NULL, agent TEXT NOT NULL, operation TEXT NOT NULL, status INTEGER NOT NULL);
        CREATE INDEX IF NOT EXISTS host_activity_time ON host_activity(at);
        CREATE TABLE IF NOT EXISTS host_retired_credentials(hash TEXT PRIMARY KEY, mode TEXT NOT NULL, reason TEXT NOT NULL, at REAL NOT NULL);
        CREATE INDEX IF NOT EXISTS host_retired_credentials_time ON host_retired_credentials(at);
        \(existingVersion < 3 ? "ALTER TABLE host_activity ADD COLUMN kind TEXT NOT NULL DEFAULT 'request';" : "")
        CREATE TABLE IF NOT EXISTS connection_deliveries(id TEXT PRIMARY KEY, owner TEXT NOT NULL, idempotency TEXT NOT NULL, state TEXT NOT NULL, next_at REAL NOT NULL, lease_until REAL NOT NULL, created REAL NOT NULL, body TEXT NOT NULL, UNIQUE(owner,idempotency));
        CREATE INDEX IF NOT EXISTS connection_delivery_due ON connection_deliveries(state,next_at);
        CREATE INDEX IF NOT EXISTS connection_delivery_owner ON connection_deliveries(owner,created);
        \(existingVersion < 5 ? "ALTER TABLE host_activity ADD COLUMN agent_id TEXT;" : "")
        \(existingVersion < 6 ? "ALTER TABLE host_activity ADD COLUMN note_level TEXT; ALTER TABLE host_activity ADD COLUMN note_data TEXT;" : "")
        \(existingVersion < 7 ? "ALTER TABLE host_activity ADD COLUMN request_facts TEXT;" : "")
        \(existingVersion < 8 ? "ALTER TABLE callback_event_epochs ADD COLUMN config_epoch INTEGER NOT NULL DEFAULT 0; ALTER TABLE callback_event_epochs ADD COLUMN eligible INTEGER NOT NULL DEFAULT 0;" : "")
        CREATE TABLE IF NOT EXISTS host_note_rate(agent_id TEXT NOT NULL, at REAL NOT NULL);
        CREATE INDEX IF NOT EXISTS host_note_rate_agent ON host_note_rate(agent_id,at);
        CREATE INDEX IF NOT EXISTS host_note_rate_time ON host_note_rate(at);
        \(existingVersion < 9 ? """
        INSERT INTO host_note_rate(agent_id, at)
            SELECT agent_id, at FROM host_activity
            WHERE kind='note' AND agent_id IS NOT NULL AND at > CAST(strftime('%s','now') AS REAL)-60;
        DROP INDEX IF EXISTS host_activity_note_rate;
        """ : "")
        CREATE TRIGGER IF NOT EXISTS host_activity_retention AFTER INSERT ON host_activity
        WHEN NEW.id % 200 = 0
        BEGIN
            DELETE FROM host_activity
                WHERE \(HostLogQuery.channelSQL) IN ('audit','agent')
                  AND CAST((SELECT value FROM state WHERE key='log_retention_days') AS INTEGER) > 0
                  AND at < CAST(strftime('%s','now') AS REAL) - 86400 * CAST((SELECT value FROM state WHERE key='log_retention_days') AS INTEGER);
            DELETE FROM host_activity
                WHERE \(HostLogQuery.channelSQL) IN ('audit','agent')
                  AND CAST((SELECT value FROM state WHERE key='log_retention_entries') AS INTEGER) > 0
                  AND id <= (SELECT id FROM host_activity WHERE \(HostLogQuery.channelSQL) IN ('audit','agent')
                    ORDER BY id DESC LIMIT 1 OFFSET COALESCE(CAST((SELECT value FROM state WHERE key='log_retention_entries') AS INTEGER),0));
        END;
        PRAGMA user_version=9;
        COMMIT;
        """
        guard sqlite3_exec(db, schema, nil, nil, nil) == SQLITE_OK else { sqlite3_close(db); db = nil; throw DeviceAPIError(500, "storage_unavailable", "Could not prepare applet storage.") }
        try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: path)
    }
    deinit { sqlite3_close(db) }

    #if DEBUG
    /// Close an explicitly named disposable fixture before its tests unlink files.
    public func closeTemporaryFixture(at expectedDirectory: URL) throws {
        let expected = expectedDirectory.resolvingSymlinksInPath().standardizedFileURL
        let temporary = FileManager.default.temporaryDirectory.resolvingSymlinksInPath().standardizedFileURL
        guard expected == directory.resolvingSymlinksInPath().standardizedFileURL,
              expected.deletingLastPathComponent().path == temporary.path,
              UUID(uuidString: expected.lastPathComponent) != nil else {
            throw DeviceAPIError(500, "fixture_path_invalid", "Only an owned temporary fixture can be closed here.")
        }
        guard let db else { return }
        guard sqlite3_close(db) == SQLITE_OK else {
            throw DeviceAPIError(500, "fixture_store_busy", "The fixture still holds a live SQLite statement.")
        }
        self.db = nil
    }
    #endif

    private func statement(_ sql: String, _ values: [String] = []) throws -> OpaquePointer {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &statement, nil) == SQLITE_OK, let statement else { throw DeviceAPIError(500, "storage_failed", "The applet database could not prepare a request.") }
        for (index, value) in values.enumerated() { sqlite3_bind_text(statement, Int32(index + 1), value, -1, Self.transient) }
        return statement
    }
    private func execute(_ sql: String, _ values: [String] = []) throws {
        let s = try statement(sql, values); defer { sqlite3_finalize(s) }
        guard sqlite3_step(s) == SQLITE_DONE else { throw DeviceAPIError(500, "storage_failed", "The applet database could not save this change.") }
    }
    private func strings(_ sql: String, _ values: [String] = []) throws -> [String] {
        let s = try statement(sql, values); defer { sqlite3_finalize(s) }
        var out: [String] = []
        while true {
            let code = sqlite3_step(s)
            if code == SQLITE_DONE { return out }
            guard code == SQLITE_ROW else { throw DeviceAPIError(500, "storage_failed", "The applet database could not read this data.") }
            if let text = sqlite3_column_text(s, 0) { out.append(String(cString: text)) }
        }
    }
    private func json(_ object: Any) throws -> String {
        let data = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys, .fragmentsAllowed])
        return String(decoding: data, as: UTF8.self)
    }
    private func object(_ text: String) throws -> [String: Any] {
        guard let object = try JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any] else { throw DeviceAPIError(500, "stored_data_invalid", "The saved applet data is not an object.") }
        return object
    }
    private func transaction<T>(_ block: () throws -> T) throws -> T {
        try execute("BEGIN IMMEDIATE")
        transactionHasCallbackEvents = false
        do {
            let result = try block(); try execute("COMMIT")
            let changed = transactionHasCallbackEvents; transactionHasCallbackEvents = false
            if changed { didChange(.callbackEvents) }
            return result
        } catch { transactionHasCallbackEvents = false; try? execute("ROLLBACK"); throw error }
    }
    public func validateInstallation(_ definition: [String: Any]) throws {
        guard let id = definition["id"] as? String, id.range(of: "^[a-z][a-z0-9-]{0,63}$", options: .regularExpression) != nil,
              definition["format"] as? String == "bridgit-template/1",
              let adapter = (definition["runtime"] as? [String: Any])?["adapter"] as? String,
              ["android.package.v1", "bridgit.package.v1"].contains(adapter) else { throw DeviceAPIError(422, "invalid_applet", "This package uses an unsupported applet format.") }
        let body = try json(definition)
        guard body.utf8.count <= 1_048_576 else { throw DeviceAPIError(413, "applet_too_large", "The applet package is too large.") }
        let tables = try Self.tableDefinitions(definition)
        if let existing = try strings("SELECT body FROM packages WHERE id=?", [id]).first {
            let oldTables = try Self.tableDefinitions(object(existing))
            for old in oldTables {
                let oldID = old["id"] as! String
                let count = Int(try strings("SELECT count(*) FROM records WHERE applet=? AND table_id=?", [id, oldID]).first ?? "0") ?? 0
                if count > 0 {
                    guard let new = tables.first(where: { $0["id"] as? String == oldID }),
                          try json(old["columns"] ?? [:]) == json(new["columns"] ?? [:]) else {
                        throw DeviceAPIError(409, "migration_required", "This update changes a table that contains data. Keep the existing applet until a migration is available.")
                    }
                }
            }
        }
    }
    public func install(_ definition: [String: Any], mode: AppletInstallMode = .upsert, requestedBy: HostAgent? = nil) throws {
        try transaction {
            guard let id = definition["id"] as? String else { throw DeviceAPIError(422, "invalid_applet", "The applet needs an ID.") }
            try requireCurrentDirectRequest(requestedBy, operation: .appletsManage, applet: id)
            let exists = try strings("SELECT id FROM packages WHERE id=?", [id]).first != nil
            if mode == .create, exists { throw DeviceAPIError(409, "applet_exists", "Use the installed applet's update endpoint.") }
            if mode == .update, !exists { throw DeviceAPIError(404, "applet_not_found", "This applet is not installed.") }
            try validateInstallation(definition)
            try execute("INSERT INTO packages(id,body) VALUES(?,?) ON CONFLICT(id) DO UPDATE SET body=excluded.body", [definition["id"] as! String, try json(definition)])
        }
    }
    public func paused(_ applet: String) throws -> Bool { _ = try package(applet); return try state("applet-paused:" + applet) == "true" }
    public func setPaused(_ applet: String, value: Bool, requestedBy: HostAgent? = nil) throws {
        try transaction {
            try requireCurrentDirectRequest(requestedBy, operation: .appletsManage, applet: applet)
            _ = try package(applet)
            try setAppletStateBody(applet: applet, key: "applet-paused:" + applet, value: value ? "true" : "false")
        }
    }
    /// Settings/checkpoints are indexed for targeted removal; other applets and host authority are untouched.
    public func setAppletState(applet: String, key: String, value: String, requestedBy: HostAgent? = nil) throws {
        try transaction {
            try requireCurrentDirectRequest(requestedBy, operation: .workflowsRun, applet: applet)
            try setAppletStateBody(applet: applet, key: key, value: value)
        }
    }
    private func setAppletStateBody(applet: String, key: String, value: String) throws {
        try setState(key, value: value)
        try execute("INSERT INTO applet_state(applet,key) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET applet=excluded.applet", [applet, key])
    }
    public func removeApplet(_ applet: String, requestedBy: HostAgent? = nil) throws -> [String] {
        try requireCurrentDirectRequest(requestedBy, operation: .appletsManage, applet: applet)
        _ = try package(applet)
        var retained: [String] = []
        let refs = try strings("SELECT ref FROM media WHERE applet=?", [applet])
        // Keep failed file deletions indexed so later cleanup can retry them.
        for ref in refs { do { try removeMedia(applet: applet, ref: ref) } catch { retained.append(ref) } }
        try transaction {
            try execute("DELETE FROM records WHERE applet=?", [applet])
            try execute("DELETE FROM state WHERE key IN (SELECT key FROM applet_state WHERE applet=?)", [applet])
            try execute("DELETE FROM applet_state WHERE applet=?", [applet])
            try execute("DELETE FROM packages WHERE id=?", [applet])
        }
        // Retry/idempotency audit records have their own retention and are not erased
        // by removing domain data. Unattributed checkpoints from earlier local builds
        // cannot safely be assigned to this applet or another applet.
        if !(try strings("SELECT key FROM state WHERE substr(key,1,5)='flow:' AND key NOT IN (SELECT key FROM applet_state) LIMIT 1")).isEmpty { retained.append("unattributed_execution_cache") }
        return retained
    }
    public func packages() throws -> [[String: Any]] { try strings("SELECT body FROM packages ORDER BY id").map(object) }
    public func package(_ applet: String) throws -> [String: Any] {
        guard let text = try strings("SELECT body FROM packages WHERE id=?", [applet]).first else { throw DeviceAPIError(404, "applet_not_found", "This applet is not installed on this device.") }
        return try object(text)
    }
    public static func tableDefinitions(_ package: [String: Any]) throws -> [[String: Any]] {
        let rawTables = (package["processing"] as? [String: Any])?["tables"]
        guard rawTables == nil || rawTables is [[String: Any]] else { throw DeviceAPIError(422, "invalid_applet", "Tables must be a list of objects.") }
        let tables = rawTables as? [[String: Any]] ?? []
        guard tables.count <= 3 else { throw DeviceAPIError(422, "invalid_applet", "An applet can declare at most three tables.") }
        var seen = Set<String>()
        for table in tables {
            for (field, range) in [("max_rows", 1...10000), ("keep_days", 1...3650)] {
                if let value = table[field] {
                    guard let number = RelayHostConnection.integer(value), let integer = Int(exactly: number), range.contains(integer) else { throw DeviceAPIError(422, "invalid_applet", field + " must be a bounded integer.") }
                }
            }
            guard let id = table["id"] as? String, id.range(of: "^[a-z][a-z0-9_]{0,31}$", options: .regularExpression) != nil, seen.insert(id).inserted,
                  let columns = table["columns"] as? [String: Any], (1...16).contains(columns.count),
                  (1...10000).contains(table["max_rows"] as? Int ?? 1000), (1...3650).contains(table["keep_days"] as? Int ?? 90) else {
                throw DeviceAPIError(422, "invalid_applet", "The applet has an invalid or duplicate table.")
            }
            for (key, value) in columns {
                guard key.range(of: "^[a-z][a-z0-9_]{0,31}$", options: .regularExpression) != nil, !["id", "at", "by"].contains(key),
                      let column = value as? [String: Any], let type = column["type"] as? String,
                      ["text", "number", "integer", "boolean", "time", "date", "choice", "photo"].contains(type) else { throw DeviceAPIError(422, "invalid_applet", "The applet declares an invalid column.") }
                if type == "choice" {
                    guard let options = column["options"] as? [String], (2...32).contains(options.count), Set(options).count == options.count,
                          options.allSatisfy({ !$0.isEmpty && $0.count <= 80 }) else { throw DeviceAPIError(422, "invalid_applet", "A choice column needs distinct text options.") }
                }
            }
        }
        return tables
    }
    private func table(_ applet: String, _ table: String) throws -> [String: Any] {
        guard let found = try Self.tableDefinitions(package(applet)).first(where: { $0["id"] as? String == table }) else { throw DeviceAPIError(404, "table_not_found", "This applet has no such table.") }
        return found
    }
    private func normalize(_ row: [String: Any], table: [String: Any], applet: String) throws -> [String: Any] {
        let columns = table["columns"] as! [String: [String: Any]]
        guard row.keys.allSatisfy({ columns[$0] != nil }) else { throw DeviceAPIError(422, "invalid_row", "The row contains an undeclared column.") }
        var out: [String: Any] = [:]
        for (key, column) in columns {
            guard let value = row[key], !(value is NSNull) else { out[key] = NSNull(); continue }
            let type = column["type"] as! String
            switch type {
            case "text":
                let text = value as? String ?? (try? json(value)) ?? String(describing: value)
                guard text.count <= 1024 else { throw DeviceAPIError(422, "invalid_row", "A text cell is too long.") }; out[key] = text
            case "number", "integer":
                guard let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID(), number.doubleValue.isFinite,
                      type != "integer" || number.doubleValue.rounded() == number.doubleValue else { throw DeviceAPIError(422, "invalid_row", "A numeric column needs a finite number.") }; out[key] = number
            case "boolean":
                guard let number = value as? NSNumber, CFGetTypeID(number) == CFBooleanGetTypeID() else { throw DeviceAPIError(422, "invalid_row", "A boolean cell must be true or false.") }; out[key] = number
            case "photo":
                guard let ref = value as? String, !(try strings("SELECT ref FROM media WHERE applet=? AND ref=?", [applet, ref])).isEmpty else { throw DeviceAPIError(404, "photo_not_found", "A photo must belong to this applet.") }; out[key] = ref
            case "choice":
                guard let text = value as? String, let options = column["options"] as? [String], let option = options.first(where: { $0.caseInsensitiveCompare(text) == .orderedSame }) else { throw DeviceAPIError(422, "invalid_row", "This choice is not in the declared options.") }; out[key] = option
            case "time", "date":
                guard value is String || value is NSNumber else { throw DeviceAPIError(422, "invalid_row", "Use a date or time value.") }; out[key] = value
            default: throw DeviceAPIError(422, "invalid_row", "Unsupported column type.")
            }
        }
        guard try json(out).utf8.count <= 4096 else { throw DeviceAPIError(413, "row_too_large", "One row can contain at most 4096 bytes.") }
        return out
    }
    public func rows(applet: String, table tableID: String, limit: Int = 1000) throws -> [[String: Any]] {
        let definition = try table(applet, tableID)
        let cutoff = Date().timeIntervalSince1970 * 1000 - Double(definition["keep_days"] as? Int ?? 90) * 86400000
        try execute("DELETE FROM records WHERE applet=? AND table_id=? AND at<?", [applet, tableID, String(cutoff)])
        return try strings("SELECT body FROM records WHERE applet=? AND table_id=? ORDER BY at DESC,row_id DESC LIMIT ?", [applet, tableID, String(max(1, min(limit, 10000)))]).map(object)
    }
    public func addRows(applet: String, table tableID: String, rows: [[String: Any]], by: String = "owner", requestedBy: HostAgent? = nil) throws -> [[String: Any]] {
        try transaction {
            try requireCurrentDirectRequest(requestedBy, operation: .appletsWrite, applet: applet)
            return try addRowsBody(applet: applet, tableID: tableID, rows: rows, by: by)
        }
    }
    private func addRowsBody(applet: String, tableID: String, rows: [[String: Any]], by: String) throws -> [[String: Any]] {
        let definition = try table(applet, tableID)
        guard rows.count <= 200 else { throw DeviceAPIError(422, "invalid_row", "A record step can add at most 200 rows.") }
        let normalized = try rows.map { try normalize($0, table: definition, applet: applet) }
        var saved: [[String: Any]] = []
        for row in normalized {
            var full = row
            full["id"] = UUID().uuidString.lowercased()
            full["at"] = Date().timeIntervalSince1970 * 1000
            full["by"] = ["type": by]
            saved.append(full)
        }
        let bodies = try saved.map { try json($0) }
        let total = Int(try strings("SELECT coalesce(sum(length(CAST(body AS BLOB))),0) FROM records WHERE applet=?", [applet]).first ?? "0") ?? 0
        guard total + bodies.reduce(0, { $0 + $1.utf8.count }) <= 5_000_000 else { throw DeviceAPIError(413, "table_storage_full", "The applet has reached its row storage limit.") }
        for (row, body) in zip(saved, bodies) {
            try execute("INSERT INTO records(applet,table_id,row_id,at,body) VALUES(?,?,?,?,?)", [applet, tableID, row["id"] as! String, String((row["at"] as! Double)), body])
        }
    let maxRows = definition["max_rows"] as? Int ?? 1000
    try execute("DELETE FROM records WHERE applet=? AND table_id=? AND row_id NOT IN (SELECT row_id FROM records WHERE applet=? AND table_id=? ORDER BY at DESC,row_id DESC LIMIT ?)", [applet, tableID, applet, tableID, String(maxRows)])
    return saved
    }
    /// Row writes and their checkpoint commit together, so a resumed flow cannot duplicate records.
    public func recordOnce(applet: String, table: String, rows: [[String: Any]], checkpoint: String, requestedBy: HostAgent? = nil) throws -> [String: Any] {
        try transaction {
            try requireCurrentDirectRequest(requestedBy, operation: .workflowsRun, applet: applet)
            if let saved = try state(checkpoint) { return try object(saved) }
            let saved = try addRowsBody(applet: applet, tableID: table, rows: rows, by: "flow")
            let result: [String: Any] = ["row_ids": saved.compactMap { $0["id"] as? String }, "count": saved.count]
            try setAppletStateBody(applet: applet, key: checkpoint, value: json(result)); return result
        }
    }
    public func update(applet: String, table: String, rowID: String, values: [String: Any], requestedBy: HostAgent? = nil) throws -> [String: Any] {
        try updateRow(applet: applet, table: table, rowID: rowID, values: values, requestedBy: requestedBy, operation: .appletsWrite)
    }
    func updateWorkflowRow(applet: String, table: String, rowID: String, values: [String: Any], requestedBy: HostAgent?) throws -> [String: Any] {
        try updateRow(applet: applet, table: table, rowID: rowID, values: values, requestedBy: requestedBy, operation: .workflowsRun)
    }
    private func updateRow(applet: String, table tableID: String, rowID: String, values: [String: Any],
                           requestedBy: HostAgent?, operation: HostOperation) throws -> [String: Any] {
        try transaction {
        try requireCurrentDirectRequest(requestedBy, operation: operation, applet: applet)
        let definition = try table(applet, tableID)
        guard let text = try strings("SELECT body FROM records WHERE applet=? AND table_id=? AND row_id=?", [applet, tableID, rowID]).first else { throw DeviceAPIError(404, "row_not_found", "This row is no longer on the device.") }
        var row = try object(text)
        guard let id = row.removeValue(forKey: "id"), let at = row.removeValue(forKey: "at"), let by = row.removeValue(forKey: "by") else { throw DeviceAPIError(500, "stored_data_invalid", "The stored row has invalid metadata.") }
        let metadata = ["id": id, "at": at, "by": by]
        for (key, value) in values { row[key] = value }
        row = try normalize(row, table: definition, applet: applet)
        for (key, value) in metadata { row[key] = value }
        let body = try json(row)
        let total = Int(try strings("SELECT coalesce(sum(length(CAST(body AS BLOB))),0) FROM records WHERE applet=?", [applet]).first ?? "0") ?? 0
        guard total - text.utf8.count + body.utf8.count <= 5_000_000 else { throw DeviceAPIError(413, "table_storage_full", "The applet has reached its row storage limit.") }
        try execute("UPDATE records SET body=? WHERE applet=? AND table_id=? AND row_id=?", [body, applet, tableID, rowID])
        return row
        }
    }
    public func delete(applet: String, table: String, rowID: String, requestedBy: HostAgent? = nil) throws {
        try transaction {
            try requireCurrentDirectRequest(requestedBy, operation: .appletsWrite, applet: applet)
            _ = try self.table(applet, table)
            try execute("DELETE FROM records WHERE applet=? AND table_id=? AND row_id=?", [applet, table, rowID])
        }
    }
    public func savePhoto(applet: String, jpeg: Data) throws -> String {
        _ = try package(applet)
        guard !jpeg.isEmpty, jpeg.count <= 10 * 1024 * 1024 else { throw DeviceAPIError(413, "photo_too_large", "Use a photo no larger than 10 MB.") }
        let used = Int(try strings("SELECT coalesce(sum(bytes),0) FROM media WHERE applet=?", [applet]).first ?? "0") ?? 0
        let count = Int(try strings("SELECT count(*) FROM media WHERE applet=?", [applet]).first ?? "0") ?? 0
        guard used + jpeg.count <= 500 * 1024 * 1024, count < 1000 else { throw DeviceAPIError(413, "photo_storage_full", "This applet has reached its photo storage limit.") }
        let id = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased(), ref = "kept:" + id, name = id + ".jpg"
        let file = directory.appendingPathComponent(name)
        try jpeg.write(to: file, options: [.atomic]); try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: file.path)
        do { try execute("INSERT INTO media(applet,ref,file,bytes,at) VALUES(?,?,?,?,?)", [applet, ref, name, String(jpeg.count), String(Date().timeIntervalSince1970 * 1000)]) }
        catch { try? FileManager.default.removeItem(at: file); throw error }
        return ref
    }
    /// Media and its first table reference are created without an actor suspension.
    public func addPhotoRow(applet: String, jpeg: Data, table: String, column: String, initial: [String: Any], requestedBy: HostAgent? = nil) throws -> [String: Any] {
        try requireCurrentDirectRequest(requestedBy, operation: .appletsMediaWrite, applet: applet)
        let definition = try self.table(applet, table)
        guard let columns = definition["columns"] as? [String: [String: Any]], columns[column]?["type"] as? String == "photo" else {
            throw DeviceAPIError(422, "invalid_photo_column", "The camera action needs a declared photo column.")
        }
        try collectMedia(applet: applet)
        let ref = try savePhoto(applet: applet, jpeg: jpeg)
        do {
            var values = initial; values[column] = ref
            if columns["taken_at"] != nil { values["taken_at"] = ISO8601DateFormatter().string(from: Date()) }
            return try addRows(applet: applet, table: table, rows: [values])[0]
        } catch { try? removeMedia(applet: applet, ref: ref); throw error }
    }
    private func removeMedia(applet: String, ref: String) throws {
        guard let name = try strings("SELECT file FROM media WHERE applet=? AND ref=?", [applet, ref]).first,
              name.range(of: "^[a-f0-9]{32}\\.jpg$", options: .regularExpression) != nil else { return }
        // Remove the file first: failed removal retains the database entry for the next collection.
        let file = directory.appendingPathComponent(name)
        if FileManager.default.fileExists(atPath: file.path) { try FileManager.default.removeItem(at: file) }
        try execute("DELETE FROM media WHERE applet=? AND ref=?", [applet, ref])
    }
    /// Keep unreferenced captures for seven days so interrupted flows may resume. Never delete a
    /// photo referenced by any surviving table in the applet. Called before imports and on demand.
    public func collectMedia(applet: String, grace: TimeInterval = 7 * 86400, now: Date = Date()) throws {
        var referenced = Set<String>()
        for text in try strings("SELECT body FROM records WHERE applet=?", [applet]) {
            for value in try object(text).values { if let ref = value as? String, ref.hasPrefix("kept:") { referenced.insert(ref) } }
        }
        let old = try strings("SELECT ref FROM media WHERE applet=? AND at<?", [applet, String((now.timeIntervalSince1970 - max(0, grace)) * 1000)])
        for ref in old where !referenced.contains(ref) { try removeMedia(applet: applet, ref: ref) }
    }
    func connectionPhotoApplet(ref: String) throws -> String? {
        try strings("SELECT applet FROM media WHERE ref=?", [ref]).first
    }
    public func photo(applet: String, ref: String) throws -> Data {
        guard let name = try strings("SELECT file FROM media WHERE applet=? AND ref=?", [applet, ref]).first,
              name.range(of: "^[a-f0-9]{32}\\.jpg$", options: .regularExpression) != nil else { throw DeviceAPIError(404, "photo_not_found", "This applet has no such photo.") }
        return try Data(contentsOf: directory.appendingPathComponent(name))
    }
    public func state(_ key: String) throws -> String? { try strings("SELECT value FROM state WHERE key=?", [key]).first }
    public func setState(_ key: String, value: String) throws { try execute("INSERT INTO state(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", [key, value]) }
    public func removeState(_ key: String) throws { try execute("DELETE FROM state WHERE key=?", [key]) }
    public func removeStates(prefix: String) throws {
        // Prefix matching is literal; caller-provided wildcard characters never become SQL patterns.
        try execute("DELETE FROM state WHERE substr(key,1,?)=?", [String(prefix.count), prefix])
    }
    /// Persist a sequence before executing any job. Replays remain refused after a process restart.
    public func claimSequence(agent: String, generation: String, sequence: Int64, now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) throws -> Bool {
        let key = "relay-sequence:\(agent):\(generation)"
        return try transaction {
            let previous = Int64(try state(key) ?? "0") ?? 0
            guard sequence > previous else { return false }
            try setState(key, value: String(sequence))
            try setState("host-last-use:\(agent):\(generation)", value: String(now))
            return true
        }
    }
    func recentAgentOwners(now: Int64) throws -> [String] {
        let prefix = "host-last-use:"
        return try strings("SELECT substr(key, ?) FROM state WHERE key GLOB 'host-last-use:*' AND CAST(value AS INTEGER) BETWEEN ? AND ? ORDER BY CAST(value AS INTEGER) DESC, key", [String(prefix.count + 1), String(now - 600_000), String(now)])
    }

    func queueBuildInstructions(owner: String, prompt: String, instructions: URL) throws {
        try transaction {
            try interactionEvent(owner: owner, type: "applet.create_requested",
                                 fields: ["instructions": instructions.absoluteString, "prompt": prompt])
        }
    }
    /// Claim unresolved intent and its optional paid budget in one SQLite transaction.
    /// The key is semantic intent, independent of the caller's request/idempotency key.
    public func beginActionAttempt(applet: String, flow: String, limit: Int?, key: String, unknownCode: String, now: Date = Date(), requestedBy: HostAgent? = nil) throws {
        try transaction {
            try requireCurrentDirectRequest(requestedBy, operation: .workflowsRun, applet: applet)
            let historical = try strings("SELECT key FROM state WHERE substr(key,1,?)=? OR substr(key,1,?)=? LIMIT 1", [String("paid-attempt:\(applet):flow:".count), "paid-attempt:\(applet):flow:", String("action-attempt:\(applet):flow:".count), "action-attempt:\(applet):flow:"])
            guard historical.isEmpty, try state(key) == nil else { throw DeviceAPIError(409, unknownCode, "This action has an unresolved outcome. Explicitly acknowledge a retry before another attempt.") }
            if let limit {
                let day = String(ISO8601DateFormatter().string(from: now).prefix(10)), budget = "paid:\(applet):\(flow):\(day)"
                let used = Int(try state(budget) ?? "0") ?? 0
                guard used < limit else { throw DeviceAPIError(429, "paid_cap", "This flow reached its daily paid-call limit.") }
                try setState(budget, value: String(used + 1))
            }
            try setAppletStateBody(applet: applet, key: key, value: "pending")
        }
    }
    func resetAppletActionAttempts(applet: String, requestedBy: HostAgent?) throws {
        try transaction {
            try requireCurrentDirectRequest(requestedBy, operation: .workflowsRun, applet: applet)
            try removeStates(prefix: "paid-attempt:\(applet):")
            try removeStates(prefix: "action-attempt:\(applet):")
        }
    }
    func requireCurrentWorkflow(applet: String, requestedBy: HostAgent?) throws {
        try requireCurrentDirectRequest(requestedBy, operation: .workflowsRun, applet: applet)
    }
    public func reservePaid(applet: String, flow: String, limit: Int, now: Date = Date()) throws {
        let day = String(ISO8601DateFormatter().string(from: now).prefix(10)), key = "paid:\(applet):\(flow):\(day)"
        try transaction {
            let used = Int(try state(key) ?? "0") ?? 0
            guard used < limit else { throw DeviceAPIError(429, "paid_cap", "This flow reached its daily paid-call limit.") }
            try setState(key, value: String(used + 1))
        }
    }
}

// These owner-visible interactions are independent from applet tables and cannot be
// reached through an applet's records API. Agent scope comes from the host authority.
extension AppletStore {
    public func createInteraction(owner: String, input: [String: Any], senderName: String = "Agent", now: Date = Date(), requestedBy: HostAgent? = nil) throws -> [String: Any] {
        defer { didChange(.inbox) }
        var body = try InteractionValidation.create(input, now: now)
        body["sender"] = ["name": String(senderName.prefix(80))]
        let id = body["interaction_id"] as! String
        let expires = ISO8601DateFormatter().date(from: body["expires_at"] as! String)!.timeIntervalSince1970
        return try transaction {
            try requireCurrentDirectRequest(requestedBy, operation: .interactionsWrite)
            try expireInteractions(now: now)
            try pruneTerminalInteractions()
            let existing = try strings("SELECT body FROM interactions WHERE owner=?", [owner]).map(object)
            guard existing.filter({ ["pending", "seen"].contains($0["status"] as? String ?? "") }).count < 5,
                  Int(try strings("SELECT count(*) FROM interactions").first ?? "0") ?? 0 < 500 else { throw DeviceAPIError(429, "inbox_full", "Close existing cards before sending more.") }
            try execute("INSERT INTO interactions(id,owner,body,expires,at) VALUES(?,?,?,?,?)", [id, owner, try json(body), String(expires), String(now.timeIntervalSince1970)])
            return body
        }
    }
    /// The initial event loads current data. Subsequent events coalesce until the UI can read again.
    public func changes(to topic: Change) -> AsyncStream<Void> {
        let id = UUID()
        let (stream, continuation) = AsyncStream<Void>.makeStream(bufferingPolicy: .bufferingNewest(1))
        observers[topic, default: [:]][id] = continuation
        continuation.onTermination = { [weak self] _ in Task { await self?.removeObserver(id, from: topic) } }
        continuation.yield(())
        return stream
    }

    public func inboxChanges() -> AsyncStream<Void> { changes(to: .inbox) }

    private func removeObserver(_ id: UUID, from topic: Change) { observers[topic]?[id] = nil }

    private func didChange(_ topic: Change) {
        guard let entries = observers[topic] else { return }
        for observer in entries.values { observer.yield(()) }
    }

    /// Only owner-assigned names and known operation identifiers enter the request history.
    func recordHostActivity(agent: HostAgent, operation: HostOperation, status: Int, at: Date = Date(), facts: HostActivityRequestFacts? = nil) throws {
        let name = operation == .log ? HostLogNote.scrub(agent.name, max: 200) : agent.name
        let encoded = try facts.map { String(decoding: try JSONEncoder().encode($0), as: UTF8.self) }
        try execute("INSERT INTO host_activity(at,agent,operation,status,agent_id,request_facts) VALUES(?,?,?,?,?,NULLIF(?,''))",
                    [String(at.timeIntervalSince1970), name, operation.rawValue, String(status), agent.id, encoded ?? ""])
        didChange(.activity)
    }

    func recordAnonymousHostDenial(status: Int, at: Date, facts: HostActivityRequestFacts) throws {
        let encoded = String(decoding: try JSONEncoder().encode(facts), as: UTF8.self)
        let message: String
        if status == 404 { message = "Unknown direct endpoint" }
        else if [401, 403, 429].contains(status) { message = "Direct sign-in refused" }
        else { message = "Direct HTTP request refused" }
        try execute("INSERT INTO host_activity(at,agent,operation,status,agent_id,request_facts) VALUES(?,'',?,?,NULL,?)",
                    [String(at.timeIntervalSince1970), message, String(status), encoded])
        didChange(.activity)
    }

    /// The note and its successful replay receipt commit together. Recheck the
    /// persisted grant on the same store transaction as the note insertion.
    func recordHostNote(_ note: HostLogNote, agent: HostAgent,
                        reservation: (key: String, fingerprint: String)? = nil, at: Date = Date()) throws -> HostResponse {
        var inserted = false
        let response = try transaction {
            try requireCurrentDirectRequest(agent, operation: .log)
            try Task.checkCancellation()
            guard let current = try transferAgents().first(where: { $0.id == agent.id }),
                  current.mode == agent.mode, current.generation == agent.generation, !current.paused,
                  current.viewer != true, current.allows(.log) else {
                throw DeviceAPIError(403, "agent_revoked", "This caller no longer has log authority.")
            }
            let now = at.timeIntervalSince1970
            try execute("DELETE FROM host_note_rate WHERE at<=?", [String(now - 60)])
            let oldest = try strings("""
                SELECT CAST(at AS TEXT) FROM host_note_rate WHERE agent_id=? AND at>?
                ORDER BY at DESC LIMIT 1 OFFSET ?
                """, [current.id, String(now - 60), String(HostLogNote.limit - 1)]).first.flatMap(Double.init)
            var response: HostResponse
            if let oldest {
                let retry = Int(ceil(min(60, max(1, oldest + 60 - now))))
                response = try .json(["error": "rate_limited", "message": "At most 60 notes per minute.", "retry_after_s": retry], status: 429)
                response.headers["Retry-After"] = String(retry)
            } else {
                try execute("INSERT INTO host_activity(at,agent,operation,status,kind,agent_id,note_level,note_data) VALUES(?,?,?,0,'note',?,?,?)",
                    [String(now), HostLogNote.scrub(current.name, max: 200), note.message, current.id, note.level.rawValue, note.data])
                try execute("INSERT INTO host_note_rate(agent_id,at) VALUES(?,?)", [current.id, String(now)])
                inserted = true
                response = try .json(["ok": true, "channel": "agent"], status: 201)
            }
            if let reservation {
                response.headers["X-Bridgit-Idempotency-Retention"] = String(Int(HostIdempotencyPolicy.retentionSeconds))
                var result = response.envelope
                result["fingerprint"] = reservation.fingerprint
                try saveHostActionResult(reservation.key, result: result, cacheLimit: HostIdempotencyPolicy.cachedBytes)
            }
            return response
        }
        if inserted { didChange(.activity) }
        return response
    }

    /// Callback diagnostics retain only safe host/event metadata and the stable agent identity.
    func recordCallbackAttempt(agent: HostAgent, host: String, eventID: Int64, eventType: String, status: Int?, at: Date = Date()) throws {
        let safeHost = HostLogNote.scrub(host, max: 253), safeType = HostLogNote.scrub(eventType, max: 80)
        let success = status.map { (200...299).contains($0) } ?? false
        let message = "Callback to " + safeHost + ": " + (status.map { "HTTP \($0)" } ?? "delivery failed") + " (" + safeType + ")"
        let data = try json(["__callback": true, "__callback_result": success ? "delivered" : "failed", "host": safeHost, "event_id": String(eventID), "event_type": safeType])
        try execute("INSERT INTO host_activity(at,agent,operation,status,kind,agent_id,note_level,note_data) VALUES(?,?,?,?, 'agent',?,?,?)",
                    [String(at.timeIntervalSince1970), HostLogNote.scrub(agent.name, max: 200), message, String(status ?? 0), agent.id, success ? "info" : "warn", data])
        didChange(.activity)
    }
    func recordCallbackOverflow(agent: HostAgent, count: Int, at: Date = Date()) throws {
        guard count > 0 else { return }
        let data = try json(["__callback": true, "dropped": count])
        try execute("INSERT INTO host_activity(at,agent,operation,status,kind,agent_id,note_level,note_data) VALUES(?,?,?,0,'agent',?,'warn',?)",
                    [String(at.timeIntervalSince1970), HostLogNote.scrub(agent.name, max: 200), "Callback queue full: dropped \(count) pending events; they remain available to pull.", agent.id, data])
        didChange(.activity)
    }

    /// Records an actual relay lifecycle transition, without connection error payloads or credentials.
    public func recordRelayState(_ state: RelayHostConnection.State, at: Date = Date()) throws {
        try execute("INSERT INTO host_activity(at,agent,operation,status,kind) VALUES(?, 'Bridgit (system)', ?, ?, 'service')",
                    [String(at.timeIntervalSince1970), "Relay host: " + state.rawValue, state == .failed ? "503" : "200"])
        didChange(.activity)
    }

    // The caller inserts this row inside the same transaction as the owner action.
    private func recordSetting(_ message: String, at: Date) throws {
        try execute("INSERT INTO host_activity(at,agent,operation,status,kind) VALUES(?, 'Bridgit (system)', ?, 200, 'setting')",
                    [String(at.timeIntervalSince1970), message])
    }

    /// A0113/A0114: the computer-use choice and its audit row commit together, so the
    /// Activity log always explains how computer use came to be on or off.
    func saveComputerUseChoice(key: String, value: String, message: String, at: Date = Date()) throws {
        try transaction {
            try setState(key, value: value)
            try recordSetting(message, at: at)
        }
        didChange(.activity)
    }

    /// Owner audit emitted only after the native export reports a completed write.
    public func recordSuccessfulHostActivityExport(count: Int, at date: Date = Date()) throws {
        guard count >= 0 else { throw DeviceAPIError(422, "invalid_parameter", "Invalid exported activity count.") }
        try recordSetting("You exported \(count) log entries", at: date)
        didChange(.activity)
    }

    /// IDs are never reconstructed for rows written by earlier versions.
    public func hostActivityAgentIdentities() throws -> [HostActivityAgent] {
        let cursor = try statement("SELECT agent_id, agent FROM host_activity WHERE kind IN ('request','agent','pairing','note') AND (agent_id IS NOT NULL OR agent != '') ORDER BY id DESC")
        defer { sqlite3_finalize(cursor) }
        var seen = Set<HostActivityAgentFilter>(), result: [HostActivityAgent] = []
        while true {
            switch sqlite3_step(cursor) {
            case SQLITE_DONE: return result
            case SQLITE_ROW:
                let name = String(cString: sqlite3_column_text(cursor, 1))
                let filter = sqlite3_column_text(cursor, 0).map { HostActivityAgentFilter.identified(String(cString: $0)) } ?? .legacyName(name)
                if seen.insert(filter).inserted { result.append(HostActivityAgent(id: filter, name: name)) }
            default: throw DeviceAPIError(500, "storage_failed", "Could not read activity agents.")
            }
        }
    }

    public func hostActivity(limit: Int = 1000, before: Int64? = nil, query: String = "",
                             agentFilter: HostActivityAgentFilter? = nil, errorsOnly: Bool = false, kinds: Set<HostActivityKind>? = nil, systemOnly: Bool = false,
                             includeRequests: Bool = true, includeSettings: Bool = true) throws -> [HostActivityEntry] {
        guard (1...1000).contains(limit) else { throw DeviceAPIError(422, "invalid_parameter", "Invalid activity limit.") }
        var filters: [String] = []
        var values: [String] = []
        if let before { filters.append("id < ?"); values.append(String(before)) }
        if !includeRequests { filters.append("kind != 'request'") }
        if !includeSettings { filters.append("kind != 'setting'") }
        if systemOnly { filters.append("kind IN ('setting','service','crash')") }
        if let kinds {
            if kinds.isEmpty { return [] }
            filters.append("kind IN (" + Array(repeating: "?", count: kinds.count).joined(separator: ",") + ")")
            values.append(contentsOf: kinds.map(\.rawValue).sorted())
        }
        if let agentFilter {
            switch agentFilter {
            case .identified(let id): filters.append("agent_id = ?"); values.append(id)
            case .legacyName(let name): filters.append("kind='request' AND agent_id IS NULL AND agent = ?"); values.append(name)
            }
        }
        if errorsOnly { filters.append("(status >= 400 OR (kind='note' AND note_level='error'))") }
        let term = query.trimmingCharacters(in: .whitespacesAndNewlines)
        if !term.isEmpty {
            filters.append("(instr(lower(agent),lower(?)) > 0 OR instr(lower(operation),lower(?)) > 0 OR (kind='request' AND instr(CAST(status AS TEXT),?) > 0))")
            values.append(contentsOf: [term, term, term])
        }
        return try readActivityRows(predicate: filters.isEmpty ? "1" : filters.joined(separator: " AND "),
                                    values: values, ascending: false, limit: limit, names: try currentActivityAgentNames())
    }

    public func ownerDirectDeviceLock() throws -> Bool { try state("owner-direct-lock-v1") != "0" }

    public func setOwnerDirectDeviceLock(_ enabled: Bool, requestedBy agent: HostAgent? = nil) throws {
        let changed = try transaction {
            try Task.checkCancellation()
            if let agent { try requireCurrentConfigurationWriter(agent) }
            guard try ownerDirectDeviceLock() != enabled else { return false }
            try setState("owner-direct-lock-v1", value: enabled ? "1" : "0")
            try recordSetting(enabled ? "You enabled Tailscale device lock" : "You disabled Tailscale device lock", at: Date())
            return true
        }
        if changed { didChange(.activity) }
    }

    private func requireCurrentDirectRequest(_ agent: HostAgent?, operation: HostOperation, applet: String? = nil) throws {
        guard let agent, agent.ownerDirectPeers != nil else { return }
        try Task.checkCancellation()
        guard let peer = agent.ownerDirectRequestPeer,
              let current = try transferAgents().first(where: { $0.id == agent.id }),
              current.generation == agent.generation, current.mode == agent.mode, !current.paused,
              current.ownerDirectPeers?.contains(peer) == true, current.allows(operation),
              applet.map({ current.allowsApplet($0, operation: operation) }) ?? true else {
            throw DeviceAPIError(403, "agent_revoked", "The requesting direct agent no longer has this access.")
        }
    }

    private func requireCurrentConfigurationWriter(_ agent: HostAgent) throws {
        guard let current = try transferAgents().first(where: { $0.id == agent.id }),
              current.generation == agent.generation, current.mode == agent.mode, !current.paused,
              current.viewer != true, current.allows(.configurationWrite),
              current.ownerDirectPeers == nil || agent.ownerDirectRequestPeer.map({ current.ownerDirectPeers?.contains($0) == true }) == true else {
            throw DeviceAPIError(403, "agent_revoked", "The requesting agent no longer has this access.")
        }
    }

    public func hostActivityRetention() throws -> [HostActivityRetentionField: Int] {
        try Dictionary(uniqueKeysWithValues: HostActivityRetentionField.allCases.map { field in
            (field, try state(field.rawValue).flatMap(Int.init) ?? 0)
        })
    }

    public func setHostActivityRetention(_ values: [HostActivityRetentionField: Int],
                                         request: (id: String, agent: HostAgent)? = nil) throws {
        let changed = try transaction {
            try Task.checkCancellation()
            for (field, value) in values where !field.range.contains(value) {
                throw DeviceAPIError(422, "invalid_setting", "Activity retention exceeds its allowed limit.")
            }
            if let request { try commitAuthorizedOwnerRequest(id: request.id, agent: request.agent) }
            let prior = try hostActivityRetention()
            let changes = values.filter { prior[$0.key] != $0.value }
            guard !changes.isEmpty else { return false }
            for (field, value) in changes { try setState(field.rawValue, value: String(value)) }
            let updated = try hostActivityRetention()
            try recordSetting("Activity retention changed to \(updated[.entries] ?? 0) entries and \(updated[.days] ?? 0) days", at: Date())
            return true
        }
        if changed { didChange(.activity) }
    }

    public func hostLogVisibility() throws -> HostLogVisibility {
        guard let value = try state("host-log-visibility") else { return .all }
        guard let visibility = HostLogVisibility(rawValue: value) else {
            throw DeviceAPIError(500, "storage_failed", "Saved Activity visibility is invalid.")
        }
        return visibility
    }

    public func setHostLogVisibility(_ visibility: HostLogVisibility, requestedBy agent: HostAgent? = nil, at: Date = Date()) throws {
        let changed = try transaction {
            try Task.checkCancellation()
            if let agent { try requireCurrentConfigurationWriter(agent) }
            guard try hostLogVisibility() != visibility else { return false }
            try setState("host-log-visibility", value: visibility.rawValue)
            try recordSetting(visibility == .all ? "Agents can read all Activity" : "Agents can read their own Activity", at: at)
            return true
        }
        if changed { didChange(.activity) }
    }

    func hostLogReadScope(for agent: HostAgent) throws -> HostLogReadScope {
        guard let current = try transferAgents().first(where: { $0.id == agent.id }),
              current.mode == agent.mode, current.generation == agent.generation, !current.paused,
              current.viewer != true, current.allows(.log) else {
            throw DeviceAPIError(403, "agent_revoked", "This caller no longer has log authority.")
        }
        let visibility = try hostLogVisibility()
        return .init(ownID: current.operations.contains(.administrator) || visibility == .all ? nil : current.id)
    }

    func hostLogLatestID() throws -> Int64 {
        try strings("SELECT CAST(COALESCE(MAX(id),0) AS TEXT) FROM host_activity").first.flatMap(Int64.init) ?? 0
    }

    func hostLogRows(_ query: HostLogQuery, scope: HostLogReadScope, maximumID: Int64? = nil) throws -> [HostActivityEntry] {
        let (predicate, values) = query.predicate(ownID: scope.ownID, maximumID: maximumID)
        return try readActivityRows(predicate: predicate, values: values, ascending: query.ascending, limit: query.limit)
    }

    func hostLogEntry(_ id: Int64, scope: HostLogReadScope) throws -> HostActivityEntry? {
        let values = [String(id)] + (scope.ownID.map { [$0] } ?? [])
        return try readActivityRows(predicate: scope.ownID == nil ? "id=?" : "id=? AND agent_id=?",
                                    values: values, ascending: false, limit: 1).first
    }

    private func currentActivityAgentNames() throws -> [String: String] {
        Dictionary(uniqueKeysWithValues: try transferAgents().map { ($0.id, HostLogNote.scrub($0.name, max: 200)) })
    }

    private func readActivityRows(predicate: String, values: [String], ascending: Bool, limit: Int, names: [String: String] = [:]) throws -> [HostActivityEntry] {
        let order = ascending ? "ASC" : "DESC"
        let cursor = try statement("SELECT id,at,agent,operation,status,kind,agent_id,note_level,note_data,request_facts FROM host_activity WHERE " +
                                   predicate + " ORDER BY id " + order + " LIMIT ?", values + [String(limit)])
        defer { sqlite3_finalize(cursor) }
        var rows: [HostActivityEntry] = []
        while true {
            switch sqlite3_step(cursor) {
            case SQLITE_DONE: return rows
            case SQLITE_ROW:
                let agentID = sqlite3_column_text(cursor, 6).map { String(cString: $0) }
                rows.append(HostActivityEntry(id: sqlite3_column_int64(cursor, 0),
                                              at: Date(timeIntervalSince1970: sqlite3_column_double(cursor, 1)),
                                              agent: String(cString: sqlite3_column_text(cursor, 2)),
                                              operation: String(cString: sqlite3_column_text(cursor, 3)),
                                              status: Int(sqlite3_column_int(cursor, 4)),
                                              isSetting: String(cString: sqlite3_column_text(cursor, 5)) == "setting",
                                              agentID: agentID,
                                              kind: HostActivityKind(rawValue: String(cString: sqlite3_column_text(cursor, 5))) ?? .request,
                                              noteLevel: sqlite3_column_text(cursor, 7).flatMap { HostLogLevel(rawValue: String(cString: $0)) },
                                              noteData: sqlite3_column_text(cursor, 8).map { String(cString: $0) },
                                              requestFacts: try sqlite3_column_text(cursor, 9).map {
                                                  try JSONDecoder().decode(HostActivityRequestFacts.self, from: Data(String(cString: $0).utf8))
                                              }, currentAgentName: agentID.flatMap { names[$0] }))
            default: throw DeviceAPIError(500, "storage_failed", "Could not read host activity.")
            }
        }
    }

    public func hostActivityTotals(since date: Date) throws -> (requests: Int, photos: Int, clips: Int, errors: Int) {
        let query = try statement("""
            SELECT count(*), count(CASE WHEN operation=? AND status BETWEEN 200 AND 299 THEN 1 END),
                   count(CASE WHEN operation=? AND status BETWEEN 200 AND 299 THEN 1 END),
                   count(CASE WHEN status >= 400 THEN 1 END) FROM host_activity WHERE kind='request' AND at >= ?
            """, [HostOperation.cameraSnapshot.rawValue, HostOperation.microphoneRecord.rawValue, String(date.timeIntervalSince1970)])
        defer { sqlite3_finalize(query) }
        guard sqlite3_step(query) == SQLITE_ROW else { throw DeviceAPIError(500, "storage_failed", "Could not read activity totals.") }
        return (Int(sqlite3_column_int64(query, 0)), Int(sqlite3_column_int64(query, 1)), Int(sqlite3_column_int64(query, 2)), Int(sqlite3_column_int64(query, 3)))
    }

    public func inboxBadge(now: Date = Date()) throws -> (count: Int, nextExpiry: Date?) {
        // Expired, unanswered questions still need attention. Only unread notices age out.
        let notice = "json_extract(body,'$.kind')='notice' AND json_extract(body,'$.status')='pending' AND expires>?"
        let timestamp = [String(now.timeIntervalSince1970)]
        let count = try strings("SELECT count(*) FROM interactions WHERE (" + notice + ") OR (json_extract(body,'$.kind') IN ('html','question') AND json_extract(body,'$.status') IN ('pending','seen','expired'))", timestamp)
        let expiry = try strings("SELECT min(expires) FROM interactions WHERE " + notice, timestamp)
        return (Int(count.first ?? "0") ?? 0, expiry.first.flatMap(Double.init).map(Date.init(timeIntervalSince1970:)))
    }

    public func interactions(owner: String? = nil, status: String? = nil, limit: Int = 500, now: Date = Date()) throws -> [[String: Any]] {
        guard (1...500).contains(limit) else { throw DeviceAPIError(422, "invalid_parameter", "Invalid inbox limit.") }
        try transaction { try expireInteractions(now: now) }
        var condition = "1=1", args: [String] = []
        if let owner { condition += " AND owner=?"; args.append(owner) }
        if let status { condition += " AND json_extract(body,'$.status')=?"; args.append(status) }
        let order = owner == nil ? "CASE WHEN json_extract(body,'$.status') IN ('pending','seen') THEN 0 ELSE 1 END,at DESC" : "at DESC"
        return try strings("SELECT body FROM interactions WHERE " + condition + " ORDER BY " + order + " LIMIT ?", args + [String(limit)]).map(object)
    }
    public func interaction(owner: String, id: String, now: Date = Date()) throws -> [String: Any] {
        try transaction { try expireInteractions(now: now) }
        guard let text = try strings("SELECT body FROM interactions WHERE owner=? AND id=?", [owner, id]).first else { throw DeviceAPIError(404, "interaction_not_found", "This interaction is unavailable.") }
        return try object(text)
    }
    public func cancelInteraction(owner: String, id: String, requestedBy: HostAgent? = nil) throws -> [String: Any] {
        defer { didChange(.inbox) }
        return try transaction {
            try requireCurrentDirectRequest(requestedBy, operation: .interactionsWrite)
            guard let text = try strings("SELECT body FROM interactions WHERE owner=? AND id=?", [owner, id]).first else { throw DeviceAPIError(404, "interaction_not_found", "This interaction is unavailable.") }
            var body = try object(text)
            if ["pending", "seen"].contains(body["status"] as? String ?? "") {
                body["status"] = "cancelled"
                try execute("UPDATE interactions SET body=? WHERE owner=? AND id=?", [try json(body), owner, id])
                try interactionEvent(owner: owner, type: "interaction.cancelled", id: id)
            }
            return body
        }
    }
    /// Called only by trusted owner UI, never by a caller-supplied owner flag or HTML action.
    public func answerInteraction(id: String, answer: [String: Any], now: Date = Date()) throws -> [String: Any] {
        defer { didChange(.inbox) }
        guard try JSONSerialization.data(withJSONObject: answer).count <= 16 * 1024 else { throw DeviceAPIError(413, "answer_too_large", "Keep the answer below 16 KB.") }
        return try transaction {
            try expireInteractions(now: now)
            guard let text = try strings("SELECT body FROM interactions WHERE id=?", [id]).first,
                  let owner = try strings("SELECT owner FROM interactions WHERE id=?", [id]).first else { throw DeviceAPIError(404, "interaction_not_found", "This interaction is unavailable.") }
            var body = try object(text)
            guard body["status"] as? String != "cancelled", body["status"] as? String != "dismissed" else { throw DeviceAPIError(409, "interaction_closed", "This card has been closed.") }
            try InteractionValidation.answer(answer, for: body)
            let previous = body["answer"]
            let late = body["status"] as? String == "expired" || (body["late"] as? Bool == true)
            body["answer"] = answer; body["status"] = "answered"; body["late"] = late
            body["answered_at"] = ISO8601DateFormatter().string(from: now)
            try execute("UPDATE interactions SET body=? WHERE id=?", [try json(body), id])
            try interactionEvent(owner: owner, type: "interaction.answered", id: id, fields: ["answer": answer, "late": late, "resubmission": previous != nil, "previous_answer": previous ?? NSNull()])
            return body
        }
    }
    public func markInteractionSeen(id: String, now: Date = Date()) throws {
        defer { didChange(.inbox) }
        try transaction {
            try expireInteractions(now: now)
            guard let text = try strings("SELECT body FROM interactions WHERE id=?", [id]).first,
                  let owner = try strings("SELECT owner FROM interactions WHERE id=?", [id]).first else { return }
            var body = try object(text)
            guard body["status"] as? String == "pending" else { return }
            body["status"] = "seen"; body["seen_at"] = ISO8601DateFormatter().string(from: now)
            try execute("UPDATE interactions SET body=? WHERE id=?", [try json(body), id])
            try interactionEvent(owner: owner, type: "interaction.seen", id: id)
        }
    }
    public func dismissInteraction(id: String) throws {
        defer { didChange(.inbox) }
        try transaction {
            guard let text = try strings("SELECT body FROM interactions WHERE id=?", [id]).first,
                  let owner = try strings("SELECT owner FROM interactions WHERE id=?", [id]).first else { throw DeviceAPIError(404, "interaction_not_found", "This interaction is unavailable.") }
            var body = try object(text)
            guard body["status"] as? String != "cancelled" && body["status"] as? String != "dismissed" else { return }
            body["status"] = "dismissed"
            try execute("UPDATE interactions SET body=? WHERE id=?", [try json(body), id])
            try interactionEvent(owner: owner, type: "interaction.dismissed", id: id)
        }
    }
    public func interactionEvents(owner: String, after: Int64? = nil, limit: Int = 50, types: [String] = [], now: Date = Date()) throws -> [String: Any] {
        guard (1...100).contains(limit), types.count <= 10, types.allSatisfy({ $0.count <= 80 }), after == nil || after! >= 0 else { throw DeviceAPIError(422, "invalid_parameter", "Invalid event query.") }
        try transaction { try expireInteractions(now: now) }
        var condition = "owner=?", arguments = [owner]
        if let after { condition += " AND id>?"; arguments.append(String(after)) }
        if !types.isEmpty { condition += " AND json_extract(body,'$.type') IN (" + Array(repeating: "?", count: types.count).joined(separator: ",") + ")"; arguments += types }
        let select = "SELECT json_set(body,'$.id',CAST(id AS TEXT)) AS value,id FROM interaction_events WHERE " + condition
        let sql = after == nil ? "SELECT value FROM (" + select + " ORDER BY id DESC LIMIT ?) ORDER BY id" : "SELECT value FROM (" + select + " ORDER BY id LIMIT ?) ORDER BY id"
        let values = try strings(sql, arguments + [String(limit)]).map(object)
        let latest = try strings("SELECT coalesce(max(id),0) FROM interaction_events WHERE owner=?", [owner]).first ?? "0"
        return ["events": values, "next_cursor": values.last?["id"] ?? latest, "retained_event_limit": 1000]
    }
    /// Lookup retains the original event owner across a same-agent key rotation.
    /// Atomic registration+cursor prevents startup gaps while excluding old events.
    func callbackEventSubscription() throws -> (cursor: Int64, changes: AsyncStream<Void>) {
        let cursor = Int64(try strings("SELECT coalesce(max(id),0) FROM interaction_events").first ?? "0") ?? 0
        return (cursor, changes(to: .callbackEvents))
    }
    func callbackEvents(after: Int64, limit: Int = 200) throws -> [[String: Any]] {
        guard after >= 0, (1...200).contains(limit) else { throw DeviceAPIError(422, "invalid_parameter", "Invalid callback event cursor.") }
        return try strings("SELECT json_object('owner',e.owner,'event',json_set(e.body,'$.id',CAST(e.id AS TEXT)),'grant_epoch',c.grant_epoch,'config_epoch',c.config_epoch,'eligible',c.eligible) FROM interaction_events e JOIN callback_event_epochs c ON c.event_id=e.id WHERE e.id>? ORDER BY e.id LIMIT ?", [String(after), String(limit)]).map(object)
    }

    func createCallbackTestEvent(owner: String, requestedBy: HostAgent) throws -> Int64 {
        try transaction {
            try requireCurrentDirectRequest(requestedBy, operation: .interactionsRead)
            guard let current = try transferAgents().first(where: { $0.id == requestedBy.id }),
                  current.generation == requestedBy.generation, current.mode == requestedBy.mode, !current.paused,
                  current.allows(.interactionsRead), current.eventOwner == owner else {
                throw DeviceAPIError(403, "agent_revoked", "The control agent no longer has callback access.")
            }
            try interactionEvent(owner: owner, type: "callback.test", fields: ["note": "Test from Bridgit. If you can read this, your callback works."])
            return sqlite3_last_insert_rowid(db)
        }
    }

    func callbackEventRecord(owner: String, id: Int64) throws -> [String: Any]? {
        guard id > 0, let text = try strings("SELECT json_object('event',json_set(e.body,'$.id',CAST(e.id AS TEXT)),'grant_epoch',c.grant_epoch,'config_epoch',c.config_epoch,'eligible',c.eligible) FROM interaction_events e JOIN callback_event_epochs c ON c.event_id=e.id WHERE e.owner=? AND e.id=?", [owner, String(id)]).first else { return nil }
        return try object(text)
    }

    /// Final pinned-send check reads the persisted agent, event and epochs in one transaction.
    func authorizeCallbackDelivery(agentID: String, grantEpoch: Int64, configEpoch: Int64,
                                   owner: String, eventID: Int64, additional: HostOperation?) throws {
        try transaction {
            guard let agent = try transferAgents().first(where: { $0.id == agentID }), agent.viewer != true, !agent.paused,
                  agent.allows(.interactionsRead), additional.map({ agent.allows($0) }) ?? true,
                  try callbackGrantEpoch(agentID) == grantEpoch else {
                throw DeviceAPIError(403, "agent_revoked", "Control agent access changed.")
            }
            let config = try callbackIndex(agentID)
            guard config.active != nil, config.removalEpoch == configEpoch,
                  let record = try callbackEventRecord(owner: owner, id: eventID),
                  (record["eligible"] as? NSNumber)?.intValue == 1,
                  (record["grant_epoch"] as? NSNumber)?.int64Value == grantEpoch,
                  (record["config_epoch"] as? NSNumber)?.int64Value == configEpoch else {
                throw DeviceAPIError(403, "agent_revoked", "Callback eligibility changed.")
            }
        }
    }

    func callbackEvent(owner: String, id: Int64) throws -> [String: Any]? {
        guard id > 0, let text = try strings("SELECT json_set(body,'$.id',CAST(id AS TEXT)) FROM interaction_events WHERE owner=? AND id=?", [owner, String(id)]).first else { return nil }
        return try object(text)
    }

    private func expireInteractions(now: Date) throws {
        for text in try strings("SELECT body FROM interactions WHERE expires<=?", [String(now.timeIntervalSince1970)]) {
            var body = try object(text)
            guard ["pending", "seen"].contains(body["status"] as? String ?? ""), let id = body["interaction_id"] as? String,
                  let owner = try strings("SELECT owner FROM interactions WHERE id=?", [id]).first else { continue }
            body["status"] = "expired"
            try execute("UPDATE interactions SET body=? WHERE id=?", [try json(body), id])
            try interactionEvent(owner: owner, type: "interaction.expired", id: id)
        }
    }
    /// Keep pending cards and the newest 200 terminal cards across this device.
    /// This bound also recovers inboxes created before retention was introduced.
    private func pruneTerminalInteractions() throws {
        let removed = try strings("SELECT id FROM interactions WHERE json_extract(body,'$.status') NOT IN ('pending','seen') AND id NOT IN (SELECT id FROM interactions WHERE json_extract(body,'$.status') NOT IN ('pending','seen') ORDER BY at DESC,id DESC LIMIT 200)")
        for id in removed {
            try execute("UPDATE state SET value=json_set(json_remove(value,'$.body_base64','$.headers'),'$.result_expired',json('true')) WHERE key LIKE 'host-idempotency:%' AND json_extract(value,'$.interaction_id')=?", [id])
            try execute("DELETE FROM interactions WHERE id=?", [id])
        }
    }
    private func interactionEvent(owner: String, type: String, id: String? = nil, fields: [String: Any] = [:]) throws {
        var data = fields; data["interaction_id"] = id
        var body: [String: Any] = ["type": type, "data": data]
        body["at"] = ISO8601DateFormatter().string(from: Date())
        try execute("INSERT INTO interaction_events(owner,body) VALUES(?,?)", [owner, try json(body)])
        let agentID = String(owner.split(separator: ":", maxSplits: 1).first ?? "")
        let epoch = try callbackGrantEpoch(agentID)
        let config = try callbackIndex(agentID)
        try execute("INSERT INTO callback_event_epochs(event_id,grant_epoch,config_epoch,eligible) VALUES(last_insert_rowid(),?,?,?)", [String(epoch), String(config.removalEpoch), config.active == nil ? "0" : "1"])
        transactionHasCallbackEvents = true
        try execute("DELETE FROM interaction_events WHERE owner=? AND id NOT IN (SELECT id FROM interaction_events WHERE owner=? ORDER BY id DESC LIMIT 1000)", [owner, owner])
    }
}

extension AppletStore {
    /// Returns a previous reservation, or nil after atomically reserving a new key.
    func reserveHostAction(_ key: String, ownerPrefix: String, fingerprint: String, now: Date = Date()) throws -> String? {
        try transaction {
            let at = now.timeIntervalSince1970
            // Adopt old local checkpoints without discarding their duplicate protection.
            try execute("UPDATE state SET value=json_set(value,'$.created_at',?) WHERE key LIKE 'host-idempotency:%' AND json_type(value,'$.created_at') IS NULL", [String(at)])
            try execute("DELETE FROM state WHERE key LIKE 'host-idempotency:%' AND CAST(json_extract(value,'$.created_at') AS REAL)<?", [String(at - HostIdempotencyPolicy.retentionSeconds)])
            if let saved = try strings("SELECT value FROM state WHERE key=?", [key]).first { return saved }
            let caller = Int(try strings("SELECT count(*) FROM state WHERE key LIKE ?", [ownerPrefix + "%"]).first ?? "0") ?? 0
            let all = Int(try strings("SELECT count(*) FROM state WHERE key LIKE 'host-idempotency:%'").first ?? "0") ?? 0
            guard caller < HostIdempotencyPolicy.perCallerLimit, all < HostIdempotencyPolicy.deviceLimit else { throw DeviceAPIError(429, "action_history_full", "The action retry window is full. Try again after older reservations expire.") }
            try execute("INSERT INTO state(key,value) VALUES(?,?)", [key, try json(["fingerprint": fingerprint, "created_at": at])])
            return nil
        }
    }
    func completeHostAction(_ key: String, result: [String: Any], cacheLimit: Int = HostIdempotencyPolicy.cachedBytes) throws {
        try transaction { try saveHostActionResult(key, result: result, cacheLimit: cacheLimit) }
    }

    private func saveHostActionResult(_ key: String, result: [String: Any], cacheLimit: Int) throws {
        guard let saved = try strings("SELECT value FROM state WHERE key=?", [key]).first else { throw DeviceAPIError(409, "outcome_unknown", "The action reservation is unavailable. Inspect device state before retrying.") }
        var result = result; result["created_at"] = try object(saved)["created_at"] ?? Date().timeIntervalSince1970
        try execute("UPDATE state SET value=? WHERE key=?", [try json(result), key])
        var used = Int(try strings("SELECT coalesce(sum(length(value)),0) FROM state WHERE key LIKE 'host-idempotency:%' AND json_type(value,'$.body_base64') IS NOT NULL").first ?? "0") ?? 0
        if used > cacheLimit {
            for text in try strings("SELECT json_object('key',key,'bytes',length(value)) FROM state WHERE key LIKE 'host-idempotency:%' AND json_type(value,'$.body_base64') IS NOT NULL ORDER BY CAST(json_extract(value,'$.created_at') AS REAL),key") {
                guard used > cacheLimit else { break }
                let entry = try object(text), oldKey = entry["key"] as! String
                try execute("UPDATE state SET value=json_set(json_remove(value,'$.body_base64','$.headers'),'$.result_expired',json('true')) WHERE key=?", [oldKey])
                used -= entry["bytes"] as? Int ?? 0
            }
        }
    }
}

extension AppletStore {
    public func createOwnerRequest(owner: String, sender: String, summary: String, payload: [String: Any], now: Date = Date()) throws -> [String: Any] {
        let id = UUID().uuidString.lowercased()
        let body: [String: Any] = ["request_id": id, "sender": sender, "summary": summary, "status": "queued", "queued_recovery": "If this operation stays queued, repeat its original action with the same Idempotency-Key. Status reads never apply changes.", "created_at": ISO8601DateFormatter().string(from: now), "expires_at": ISO8601DateFormatter().string(from: now.addingTimeInterval(600))]
        guard try JSONSerialization.data(withJSONObject: payload).count <= 1_048_576 else { throw DeviceAPIError(413, "request_too_large", "Configuration request is too large.") }
        return try transaction {
            try expireOwnerRequests(now: now)
            let mine = Int(try strings("SELECT count(*) FROM owner_requests WHERE owner=? AND json_extract(body,'$.status') IN ('queued','applying','committing')", [owner]).first ?? "0") ?? 0
            let all = Int(try strings("SELECT count(*) FROM owner_requests WHERE json_extract(body,'$.status') IN ('queued','applying','committing')").first ?? "0") ?? 0
            guard mine < 3, all < 100 else { throw DeviceAPIError(429, "owner_queue_full", "Wait for existing configuration operations.") }
            try execute("INSERT INTO owner_requests(id,owner,body,payload,at,expires) VALUES(?,?,?,?,?,?)", [id, owner, try json(body), try json(payload), String(now.timeIntervalSince1970), String(now.timeIntervalSince1970 + 600)])
            try execute("DELETE FROM owner_requests WHERE json_extract(body,'$.status') NOT IN ('queued','applying','committing') AND id NOT IN (SELECT id FROM owner_requests ORDER BY at DESC LIMIT 200)")
            return body
        }
    }
    public func ownerRequests(now: Date = Date()) throws -> [[String: Any]] {
        try transaction { try expireOwnerRequests(now: now) }
        return try strings("SELECT body FROM owner_requests WHERE json_extract(body,'$.status') IN ('queued','applying','committing') ORDER BY at").map(object)
    }
    public func ownerRequest(owner: String, id: String, now: Date = Date()) throws -> [String: Any] {
        try transaction { try expireOwnerRequests(now: now) }
        guard let text = try strings("SELECT body FROM owner_requests WHERE owner=? AND id=?", [owner, id]).first else { throw DeviceAPIError(404, "request_not_found", "This request is unavailable.") }
        return try object(text)
    }
    public func cancelOwnerRequest(owner: String, id: String) throws -> [String: Any] {
        try transaction {
            try expireOwnerRequests(now: Date())
            guard let text = try strings("SELECT body FROM owner_requests WHERE owner=? AND id=?", [owner,id]).first else { throw DeviceAPIError(404, "request_not_found", "This request is unavailable.") }
            var body = try object(text)
            let status = body["status"] as? String ?? ""
            if status == "committing" { throw DeviceAPIError(409, "request_committing", "This change has committed. Read its outcome before proposing another change.") }
            // Repeating a successful cancel is harmless and returns the cancelled request.
            if status == "cancelled" { return body }
            // A request that already finished (completed, failed, declined, needs_os_permission,
            // expired or outcome_unknown) was not cancelled: say so, as for committing (A0249).
            guard ["queued", "applying"].contains(status) else {
                throw DeviceAPIError(409, "request_closed", "This request already finished with status \(status) and was not cancelled. Read its outcome at /v1/settings/requests/\(id).")
            }
            body["status"] = "cancelled"
            try execute("UPDATE owner_requests SET body=?,payload='{}' WHERE id=?", [try json(body), id])
            return body
        }
    }
    /// Native owner cancellation uses the same commit boundary as authenticated callers.
    /// A request that finished meanwhile simply leaves the owner's pending list.
    public func cancelConfigurationOperation(id: String) throws {
        guard let owner = try strings("SELECT owner FROM owner_requests WHERE id=?", [id]).first else { return }
        do { _ = try cancelOwnerRequest(owner: owner, id: id) }
        catch let error as DeviceAPIError where error.code == "request_closed" {}
    }
    /// Claims a queued operation exactly once. Its payload is never exposed by HTTP/MCP.
    public func beginOwnerRequest(id: String, now: Date = Date()) throws -> (owner: String, payload: [String: Any]) {
        try transaction {
            try expireOwnerRequests(now: now)
            guard let text = try strings("SELECT body FROM owner_requests WHERE id=?", [id]).first,
                  let owner = try strings("SELECT owner FROM owner_requests WHERE id=?", [id]).first,
                  let payload = try strings("SELECT payload FROM owner_requests WHERE id=?", [id]).first,
                  try object(text)["status"] as? String == "queued" else { throw DeviceAPIError(409, "request_closed", "This configuration operation is no longer queued.") }
            var body = try object(text); body["status"] = "applying"
            try execute("UPDATE owner_requests SET body=? WHERE id=?", [try json(body), id])
            return (owner, try object(payload))
        }
    }
    public func ensureOwnerRequestApplying(id: String, now: Date = Date()) throws {
        try transaction { try expireOwnerRequests(now: now) }
        guard let text = try strings("SELECT body FROM owner_requests WHERE id=?", [id]).first, try object(text)["status"] as? String == "applying" else { throw DeviceAPIError(409, "request_closed", "This request was cancelled or expired.") }
    }
    /// Irrevocable effect boundary. Cancellation/expiry may win before this transaction,
    /// but cannot label a later committed effect cancelled. Called only after current configuration authority is checked.
    public func commitOwnerRequest(id: String, now: Date = Date()) throws {
        try transaction { try markOwnerRequestCommitting(id: id, now: now) }
    }
    /// Checks the persisted principal and closes cancellation before writing
    /// the startup preference, which transport clients read synchronously.
    public func setExternalCallsPolicy(paused: Bool, request: (id: String, agent: HostAgent)? = nil,
                                       apply: @Sendable () -> Void) throws {
        try transaction {
            try Task.checkCancellation()
            if let request { try commitAuthorizedOwnerRequest(id: request.id, agent: request.agent) }
            try recordSetting(paused ? "You paused external calls" : "You resumed external calls", at: Date())
        }
        apply()
        didChange(.activity)
    }

    public func setHostCrashReporting(_ enabled: Bool, request: (id: String, agent: HostAgent)? = nil,
                                      apply: @Sendable (Bool) -> Void) throws {
        try transaction {
            try Task.checkCancellation()
            if let request { try commitAuthorizedOwnerRequest(id: request.id, agent: request.agent) }
            try recordSetting(enabled ? "You enabled crash reports" : "You disabled crash reports", at: Date())
        }
        apply(enabled)
        didChange(.activity)
    }

    public func setHostDeviceName(_ name: String, request: (id: String, agent: HostAgent)? = nil,
                                  apply: @Sendable (String) -> Void) throws {
        let normalized = try HostDeviceName.normalized(name)
        try transaction {
            try Task.checkCancellation()
            if let request { try commitAuthorizedOwnerRequest(id: request.id, agent: request.agent) }
            try recordSetting(normalized.isEmpty ? "You reset the Bridgit device name" : "You changed the Bridgit device name", at: Date())
        }
        apply(normalized)
        didChange(.activity)
    }

    private func commitAuthorizedOwnerRequest(id: String, agent: HostAgent) throws {
        try requireCurrentConfigurationWriter(agent)
        guard try strings("SELECT owner FROM owner_requests WHERE id=?", [id]).first == agent.eventOwner else {
            throw DeviceAPIError(403, "agent_revoked", "This settings request belongs to another credential.")
        }
        try markOwnerRequestCommitting(id: id, now: Date())
    }

    private func markOwnerRequestCommitting(id: String, now: Date) throws {
        try expireOwnerRequests(now: now)
        guard let text = try strings("SELECT body FROM owner_requests WHERE id=?", [id]).first,
              try object(text)["status"] as? String == "applying" else { throw DeviceAPIError(409, "request_closed", "This request was cancelled or expired.") }
        var body = try object(text); body["status"] = "committing"
        try execute("UPDATE owner_requests SET body=? WHERE id=?", [try json(body), id])
    }
    public func finishOwnerRequest(id: String, status: String, message: String? = nil) throws {
        guard ["completed", "failed", "declined", "needs_os_permission"].contains(status) else { throw DeviceAPIError(422, "invalid_parameter", "Invalid request outcome.") }
        try transaction {
            guard let text = try strings("SELECT body FROM owner_requests WHERE id=?", [id]).first else { return }
            var body = try object(text)
            guard ["queued", "applying", "committing"].contains(body["status"] as? String ?? "") else { return }
            if status == "completed", body["status"] as? String != "committing" { throw DeviceAPIError(409, "request_not_committed", "This request has not committed a change.") }
            body["status"] = status
            if let message { body["message"] = message }
            try execute("UPDATE owner_requests SET body=?,payload='{}' WHERE id=?", [try json(body), id])
        }
    }
    private func expireOwnerRequests(now: Date) throws {
        // A crash may interrupt a committed effect. Never pretend it was cancelled;
        // expose uncertainty and release its quota after the operation execution window.
        try execute("UPDATE owner_requests SET body=json_set(body,'$.status','outcome_unknown'),payload='{}' WHERE expires<=? AND json_extract(body,'$.status')='committing'", [String(now.timeIntervalSince1970)])
        try execute("UPDATE owner_requests SET body=json_set(body,'$.status','expired'),payload='{}' WHERE expires<=? AND json_extract(body,'$.status') IN ('queued','applying')", [String(now.timeIntervalSince1970)])
    }
}

extension AppletStore {
    /// Live keyset pagination survives removal of the cursor row. A cursor is data,
    /// never authority: bind it to this applet/table and recheck the caller every time.
    public func rowPage(applet: String, table tableID: String, limit: Int = 100, since: String? = nil) throws -> [String: Any] {
        guard (1...200).contains(limit) else { throw DeviceAPIError(422, "invalid_parameter", "Read 1–200 rows per page.") }
        _ = try rows(applet: applet, table: tableID, limit: 1) // Apply declared retention.
        var condition = "applet=? AND table_id=?", arguments = [applet, tableID]
        if let since {
            guard since.utf8.count <= 1024, let bytes = Data(base64Encoded: since),
                  let cursor = try? JSONSerialization.jsonObject(with: bytes) as? [String: Any],
                  Set(cursor.keys) == Set(["v", "applet", "table", "at", "id"]), RelayHostConnection.integer(cursor["v"]) == 1,
                  cursor["applet"] as? String == applet, cursor["table"] as? String == tableID,
                  let at = cursor["at"] as? NSNumber, CFGetTypeID(at) != CFBooleanGetTypeID(), at.doubleValue.isFinite, at.doubleValue >= 0,
                  let id = cursor["id"] as? String, UUID(uuidString: id) != nil else { throw DeviceAPIError(422, "invalid_cursor", "Use next from this applet table's previous page.") }
            condition += " AND (at>? OR (at=? AND row_id>?))"
            arguments += [String(at.doubleValue), String(at.doubleValue), id]
        }
        let candidates = try strings("SELECT body FROM records WHERE " + condition + " ORDER BY at,row_id LIMIT ?", arguments + [String(limit + 1)]).map(object)
        var page: [[String: Any]] = [], bytes = 0
        for row in candidates.prefix(limit) {
            let size = try json(row).utf8.count + 2
            if !page.isEmpty && bytes + size > 256 * 1024 { break }
            page.append(row); bytes += size
        }
        var next: Any = NSNull()
        if candidates.count > page.count, let last = page.last {
            next = try JSONSerialization.data(withJSONObject: ["v": 1, "applet": applet, "table": tableID, "at": last["at"]!, "id": last["id"]!], options: [.sortedKeys, .fragmentsAllowed]).base64EncodedString()
        }
        let count = Int(try strings("SELECT count(*) FROM records WHERE applet=? AND table_id=?", [applet, tableID]).first ?? "0") ?? 0
        return ["items": page, "count": count, "next": next]
    }
    /// All declared sources and their saved rows, from one SQLite transaction.
    /// This does not capture media, run recognition or open a share sheet.
    public func appletDataSnapshot(_ applet: String) throws -> [String: Any] {
        try transaction {
            let package = try package(applet)
            var tables: [[String: Any]] = []
            for definition in try Self.tableDefinitions(package) {
                let id = definition["id"] as! String
                var value = definition
                value["rows"] = try rows(applet: applet, table: id, limit: 10000)
                tables.append(value)
            }
            let photos = try strings("SELECT json_object('ref',ref,'size_bytes',bytes,'taken_at',at,'file',file) FROM media WHERE applet=? ORDER BY at,ref", [applet]).map(object).map { value -> [String: Any] in
                var value = value
                let file = value.removeValue(forKey: "file") as? String ?? ""
                value["available"] = file.range(of: "^[a-f0-9]{32}\\.jpg$", options: .regularExpression) != nil && FileManager.default.fileExists(atPath: directory.appendingPathComponent(file).path)
                return value
            }
            return ["package": package, "tables": tables, "photos": photos]
        }
    }
}

public enum TransferKind: String, CaseIterable {
    case files, shares, downloads

    public static func isInstallable(name: String, mime: String) -> Bool {
        let suffix = (name as NSString).pathExtension.lowercased()
        return ["apk", "apks", "xapk", "aab", "ipa", "pkg", "dmg", "app", "mobileconfig"].contains(suffix)
            || mime.lowercased() == "application/vnd.android.package-archive"
    }

    public var settingTitle: String {
        switch self {
        case .files: return "Receive files"
        case .downloads: return "Selected Downloads"
        case .shares: return "Share to a control agent"
        }
    }

    public var grant: HostOperation {
        switch self { case .files: return .files; case .shares: return .shares; case .downloads: return .downloads }
    }
}

extension AppletStore {
    public func enabledTransfers() throws -> Set<TransferKind> {
        try Set(TransferKind.allCases.filter { try state("transfer-enabled:" + $0.rawValue) == "1" })
    }

    public func setTransferEnabled(_ kind: TransferKind, enabled: Bool) throws {
        let changed = try transaction { try changeTransferEnabled(kind, enabled: enabled) }
        if changed { didChange(.activity); didChange(.transfers) }
    }

    public func callbackInternetAllowed() throws -> Bool {
        try state("callback.allowInternet") == "true"
    }

    public func setCallbackInternetAllowed(_ enabled: Bool, requestedBy agent: HostAgent? = nil) throws {
        let changed = try transaction {
            try Task.checkCancellation()
            if let agent { try requireCurrentConfigurationWriter(agent) }
            return try changeCallbackInternetAllowed(enabled)
        }
        if changed { didChange(.activity) }
    }

    private func changeCallbackInternetAllowed(_ enabled: Bool) throws -> Bool {
        guard try callbackInternetAllowed() != enabled else { return false }
        try setState("callback.allowInternet", value: enabled ? "true" : "false")
        try recordSetting("You turned Internet callback destinations " + (enabled ? "on" : "off"), at: Date())
        return true
    }

    /// Apply the durable portion of the device capability reduction together with
    /// the remote acknowledgement boundary. Local owner actions need no request.
    public func disableDurableDeviceCapabilities(request: (id: String, agent: HostAgent)? = nil,
                                                 disableCrashReporting: (@Sendable () -> Void)? = nil) throws {
        let changed = try transaction {
            try Task.checkCancellation()
            if let request {
                try commitAuthorizedOwnerRequest(id: request.id, agent: request.agent)
            }
            let callbacks = try changeCallbackInternetAllowed(false)
            let transfers = try TransferKind.allCases.map { try changeTransferEnabled($0, enabled: false) }.contains(true)
            if disableCrashReporting != nil { try recordSetting("You disabled crash reports", at: Date()) }
            return (callbacks, transfers)
        }
        disableCrashReporting?()
        if changed.0 || changed.1 || disableCrashReporting != nil { didChange(.activity) }
        if changed.1 { didChange(.transfers) }
    }

    private func changeTransferEnabled(_ kind: TransferKind, enabled: Bool) throws -> Bool {
        let key = "transfer-enabled:" + kind.rawValue
        guard (try state(key) == "1") != enabled else { return false }
        try setState(key, value: enabled ? "1" : "0")
        try recordSetting("You turned " + kind.settingTitle + (enabled ? " on" : " off"), at: Date())
        return true
    }

    public func requireTransferEnabled(_ kind: TransferKind) throws {
        guard try state("transfer-enabled:" + kind.rawValue) == "1" else {
            throw DeviceAPIError(403, "capability_disabled", "Turn on this file feature in Feature enablement first.")
        }
    }

    private func pruneTransfers(now: Date) throws {
        let cutoff = Int64(now.addingTimeInterval(-30 * 86400).timeIntervalSince1970 * 1000)
        try execute("DELETE FROM transfers WHERE created < ?", [String(cutoff)])
    }

    func saveHostAgents(_ encoded: String, removingTransfersFor owner: String?, consumingSatelliteClaim expected: String? = nil,
                        satelliteRecords: String? = nil, permit: HostMutationPermit? = nil) throws {
        func commit() throws {
            try transaction {
                if let expected {
                    guard try state(SatelliteAuthorityPolicy.claimKey) == expected else { throw DeviceAPIError(409, "pairing_closed", "This satellite pairing code was replaced or consumed.") }
                    let pending = try JSONDecoder().decode(PendingSatelliteClaim.self, from: Data(expected.utf8))
                    try setState("host-satellite-name-v1", value: pending.name)
                    try removeState(SatelliteAuthorityPolicy.claimKey)
                }
                let prior = try transferAgents()
                let current = try JSONDecoder().decode([HostAgent].self, from: Data(encoded.utf8))
                let oldByID = Dictionary(uniqueKeysWithValues: prior.map { ($0.id, $0) })
                let newByID = Dictionary(uniqueKeysWithValues: current.map { ($0.id, $0) })
                let now = Date().timeIntervalSince1970
                try execute("DELETE FROM host_retired_credentials WHERE at < ?", [String(now - RetiredHostCredential.lifetime)])
                for id in Set(oldByID.keys).union(newByID.keys).sorted() {
                    let old = oldByID[id], new = newByID[id]
                    if old != nil && new == nil { try setState("callback-prune:" + id, value: "1"); try retireCallbackKeys(id) }
                    if let old, let new, old.operations != new.operations || old.applets != new.applets {
                        let key = "callback-grant-epoch:" + id
                        let epoch = Int64(try state(key) ?? "0") ?? 0
                        try setState(key, value: String(epoch + 1))
                    }
                    let retired: RetiredHostCredential?
                    if let old, old.viewer != true, old.tailnetPeer == nil {
                        if let new { retired = old.tokenHash == new.tokenHash ? nil : .rotated }
                        else { retired = .removed }
                        if let retired {
                            try execute("INSERT OR REPLACE INTO host_retired_credentials(hash,mode,reason,at) VALUES(?,?,?,?)",
                                        [old.tokenHash, old.mode, retired.rawValue, String(now)])
                        }
                    } else { retired = nil }
                    let message: String?
                    if old == nil, let new { message = "Added " + new.name }
                    else if let old, new == nil { message = "Removed " + old.name }
                    else if retired == .rotated, let new { message = "Rotated the key for " + new.name }
                    else if let old, let new,
                            old.name != new.name || old.notes != new.notes || old.paused != new.paused || old.operations != new.operations || old.applets != new.applets ||
                            old.generation != new.generation || old.tokenHash != new.tokenHash || old.peer != new.peer ||
                            old.tailnetPeer != new.tailnetPeer || old.satelliteRole != new.satelliteRole ||
                            old.ownerDirectPeers != new.ownerDirectPeers || old.ownerDirectPeerAlerts != new.ownerDirectPeerAlerts ||
                            old.viewer != new.viewer || old.viewerClaimUntil != new.viewerClaimUntil || old.viewerRelayReceipt != new.viewerRelayReceipt {
                        message = "Updated " + new.name
                    }
                    else { message = nil }
                    if let message, let subject = new ?? old {
                        try execute("INSERT INTO host_activity(at,agent,operation,status,kind,agent_id) VALUES(?,?,?,?,?,?)",
                            [String(Date().timeIntervalSince1970), subject.name, message, "200", subject.viewer == true ? "pairing" : "agent", subject.id])
                    }
                }
                try setState("host-agents-v1", value: encoded)
                if let satelliteRecords { try setState("host-satellites-v1", value: satelliteRecords) }
                if let owner { try execute("DELETE FROM transfers WHERE owner=?", [owner]) }
            }

        }
        if let permit { try permit.commit(commit) }
        else { try commit() }
        didChange(.transfers)
        didChange(.activity)
        didChange(.hostAgents)
    }

    func retiredHostCredential(_ hash: String, mode: String? = nil, at date: Date = Date()) throws -> RetiredHostCredential? {
        let cutoff = String(date.timeIntervalSince1970 - RetiredHostCredential.lifetime)
        let suffix = mode == nil ? "" : " AND mode=?"
        let arguments = [hash, cutoff] + (mode.map { [$0] } ?? [])
        return try strings("SELECT reason FROM host_retired_credentials WHERE hash=? AND at >= ?" + suffix, arguments)
            .first.flatMap(RetiredHostCredential.init(rawValue:))
    }

    /// Agent removal retains a durable callback credential cleanup marker; rotation does not.
    /// SQLite stores only opaque credential IDs; URL and signing key remain in Keychain.
    func callbackMutationLease() throws -> CallbackMutationLease { try CallbackMutationLease(directory: directory) }
    struct CallbackIndex: Equatable { let active: String?; let staged: String?; let removalEpoch: Int64 }
    func callbackIndex(_ id: String) throws -> CallbackIndex {
        let values = try strings("SELECT json_object('active',active_key,'staged',staged_key,'epoch',removal_epoch) FROM callback_config WHERE agent_id=?", [id])
        guard let text = values.first else { return .init(active: nil, staged: nil, removalEpoch: 0) }
        let row = try object(text)
        return .init(active: row["active"] as? String, staged: row["staged"] as? String, removalEpoch: (row["epoch"] as? NSNumber)?.int64Value ?? 0)
    }
    private func requireCallbackTarget(_ id: String, caller: HostAgent?) throws {
        try Task.checkCancellation()
        if let caller { try requireCurrentConfigurationWriter(caller) }
        guard try transferAgents().contains(where: { $0.id == id }) else { throw DeviceAPIError(404, "not_found", "Unknown agent.") }
    }
    func stageCallbackKey(_ key: String, agentID: String, caller: HostAgent?) throws {
        try transaction {
            try requireCallbackTarget(agentID, caller: caller)
            try execute("INSERT OR IGNORE INTO callback_config(agent_id) VALUES(?)", [agentID])
            guard try callbackIndex(agentID).staged == nil else { throw DeviceAPIError(409, "callback_busy", "Another callback change is in progress.") }
            try execute("UPDATE callback_config SET staged_key=? WHERE agent_id=?", [key, agentID])
        }
    }
    func commitCallbackKey(_ key: String, agentID: String, caller: HostAgent?) throws {
        try transaction {
            try requireCallbackTarget(agentID, caller: caller)
            let current = try callbackIndex(agentID)
            guard current.staged == key else { throw DeviceAPIError(409, "callback_changed", "The callback change is no longer current.") }
            if let active = current.active { try execute("INSERT OR IGNORE INTO callback_retired_keys(agent_id,key) VALUES(?,?)", [agentID, active]) }
            try execute("UPDATE callback_config SET active_key=?,staged_key=NULL WHERE agent_id=?", [key, agentID])
        }
    }
    func abandonCallbackKey(_ key: String, agentID: String) throws {
        try transaction {
            try execute("INSERT OR IGNORE INTO callback_retired_keys(agent_id,key) VALUES(?,?)", [agentID, key])
            try execute("UPDATE callback_config SET staged_key=NULL WHERE agent_id=? AND staged_key=?", [agentID, key])
        }
    }
    private func retireCallbackKeys(_ id: String, invalidatePending: Bool = true) throws {
        try execute("INSERT OR IGNORE INTO callback_retired_keys(agent_id,key) SELECT agent_id,active_key FROM callback_config WHERE agent_id=? AND active_key IS NOT NULL", [id])
        try execute("INSERT OR IGNORE INTO callback_retired_keys(agent_id,key) SELECT agent_id,staged_key FROM callback_config WHERE agent_id=? AND staged_key IS NOT NULL", [id])
        let increment = invalidatePending ? 1 : 0
        try execute("UPDATE callback_config SET active_key=NULL,staged_key=NULL,removal_epoch=removal_epoch+? WHERE agent_id=?", [String(increment), id])
    }
    func removeCallbackKey(agentID: String, caller: HostAgent?, invalidatePending: Bool = true) throws {
        try transaction { try requireCallbackTarget(agentID, caller: caller); try retireCallbackKeys(agentID, invalidatePending: invalidatePending) }
    }
    func stagedCallbacks() throws -> [(agentID: String, key: String)] {
        try strings("SELECT json_object('agent_id',agent_id,'key',staged_key) FROM callback_config WHERE staged_key IS NOT NULL").map { text in
            let row = try object(text)
            guard let id = row["agent_id"] as? String, let key = row["key"] as? String else { throw DeviceAPIError(500, "storage_failed", "Callback staging needs repair.") }
            return (id, key)
        }
    }
    func retiredCallbackAgents() throws -> [String] { try strings("SELECT DISTINCT agent_id FROM callback_retired_keys ORDER BY agent_id") }
    func retiredCallbackKeys(_ id: String) throws -> [String] { try strings("SELECT key FROM callback_retired_keys WHERE agent_id=? ORDER BY key", [id]) }
    func finishRetiredCallbackKey(_ key: String, agentID: String) throws { try execute("DELETE FROM callback_retired_keys WHERE agent_id=? AND key=?", [agentID, key]) }

    /// Pending Keychain erasures are committed with agent removal, so a crash
    /// cannot silently lose cleanup. Rotation retains the stable agent ID.

    func callbackGrantEpoch(_ id: String) throws -> Int64 { Int64(try state("callback-grant-epoch:" + id) ?? "0") ?? 0 }
    public func pendingCallbackPrunes() throws -> [String] {
        try strings("SELECT substr(key,16) FROM state WHERE key LIKE 'callback-prune:%' ORDER BY key")
    }
    public func finishCallbackPrune(_ id: String) throws { try removeState("callback-prune:" + id) }

    public func transferAgents() throws -> [HostAgent] {
        let data = Data((try state("host-agents-v1") ?? "[]").utf8)
        return try JSONDecoder().decode([HostAgent].self, from: data).sorted { $0.name < $1.name }
    }

    public func transfers(for agent: HostAgent? = nil, kind: TransferKind? = nil, now: Date = Date()) throws -> [[String: Any]] {
        if agent != nil {
            guard let kind else { throw DeviceAPIError(422, "invalid_parameter", "Select a transfer kind for the agent.") }
            try requireTransferEnabled(kind)
        }
        try pruneTransfers(now: now)
        var clauses: [String] = [], arguments: [String] = []
        if let agent { clauses.append("owner=?"); arguments.append(agent.eventOwner) }
        if let kind { clauses.append("kind=?"); arguments.append(kind.rawValue) }
        let predicate = clauses.isEmpty ? "" : " WHERE " + clauses.joined(separator: " AND ")
        return try strings("SELECT metadata FROM transfers" + predicate + " ORDER BY created DESC,id", arguments).map(object)
    }

    public func saveTransfer(for agent: HostAgent, kind: TransferKind, name: String,
                             mime: String, bytes: Data, now: Date = Date(), requestedBy: HostAgent? = nil) throws -> [String: Any] {
        let saved = try transaction {
            try requireCurrentDirectRequest(requestedBy, operation: kind.grant)
            return try saveTransferBody(for: agent, kind: kind, name: name, mime: mime, bytes: bytes, now: now)
        }
        didChange(.transfers); return saved
    }
    private func saveTransferBody(for agent: HostAgent, kind: TransferKind, name: String,
                                  mime: String, bytes: Data, now: Date) throws -> [String: Any] {
        guard !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, name.utf16.count <= 120,
              name != ".", name != "..", !name.contains("/"), !name.contains("\\"),
              name.rangeOfCharacter(from: .controlCharacters) == nil,
              mime.count <= 100, mime.range(of: #"\A[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+\z"#, options: .regularExpression) != nil else {
            throw DeviceAPIError(422, "invalid_parameter", "Choose a filename without path separators and a valid MIME type.")
        }
        if kind == .files && TransferKind.isInstallable(name: name, mime: mime) {
            throw DeviceAPIError(422, "invalid_parameter", "App packages cannot be sent through file transfers.")
        }
        let mib = 1024 * 1024
        guard bytes.count <= 25 * mib else { throw DeviceAPIError(413, "payload_too_large", "Transfers are limited to 25 MB each.") }
            try requireTransferEnabled(kind)
            try pruneTransfers(now: now)
            let agents = try transferAgents()
            guard let current = agents.first(where: { $0.eventOwner == agent.eventOwner }), !current.paused, current.allows(kind.grant) else {
                throw DeviceAPIError(403, "agent_not_allowed", "Choose an active agent with access to these files.")
            }
            let usage = try statement("""
                SELECT count(*), coalesce(sum(length(payload)),0),
                       count(*) FILTER (WHERE kind='files'), coalesce(sum(length(payload)) FILTER (WHERE kind='files'),0),
                       count(*) FILTER (WHERE kind='files' AND owner=?), coalesce(sum(length(payload)) FILTER (WHERE kind='files' AND owner=?),0)
                FROM transfers
                """, [agent.eventOwner, agent.eventOwner])
            defer { sqlite3_finalize(usage) }
            guard sqlite3_step(usage) == SQLITE_ROW else { throw DeviceAPIError(500, "storage_failed", "Could not read file usage.") }
            func fits(_ column: Int32, count: Int, size: Int) -> Bool {
                Int(sqlite3_column_int64(usage, column)) < count &&
                Int(sqlite3_column_int64(usage, column + 1)) + bytes.count <= size * mib
            }
            guard fits(0, count: 100, size: 100),
                  kind != .files || (fits(2, count: 50, size: 50) && fits(4, count: 25, size: 25)) else {
                throw DeviceAPIError(429, "quota_exceeded", "Remove old transfers before adding another file.")
            }
            let id = UUID().uuidString.lowercased()
            let timestamp = Int64(now.timeIntervalSince1970 * 1000)
            let metadata: [String: Any] = ["id": id, "agent_id": agent.id, "kind": kind.rawValue, "name": name,
                                           "mime": mime, "size_bytes": bytes.count, "created_at": timestamp]
            let insert = try statement("INSERT INTO transfers VALUES(?,?,?,?,?,?)",
                                       [id, agent.eventOwner, kind.rawValue, String(timestamp), try json(metadata)])
            defer { sqlite3_finalize(insert) }
            let binding = bytes.isEmpty ? sqlite3_bind_zeroblob(insert, 6, 0) : bytes.withUnsafeBytes {
                sqlite3_bind_blob(insert, 6, $0.baseAddress, Int32($0.count), Self.transient)
            }
            guard binding == SQLITE_OK, sqlite3_step(insert) == SQLITE_DONE else {
                throw DeviceAPIError(500, "storage_failed", "Could not save the transfer.")
            }
            if current.allows(.interactionsRead) {
                if kind == .files {
                    try interactionEvent(owner: agent.eventOwner, type: "file.received", fields: ["file_id": id, "name": name])
                } else if current.allows(.shares) {
                    try interactionEvent(owner: agent.eventOwner, type: "share.created", fields: ["id": id, "kind": kind.rawValue, "name": name])
                }
            }
            return metadata
    }

    public func inboundShareCatalog() throws -> InboundShareCatalog {
        let enabled = try enabledTransfers()
        return .init(recipients: try transferAgents().filter { !$0.paused }.compactMap { agent in
            let shares = enabled.contains(.shares) && agent.allows(.shares), files = enabled.contains(.files) && agent.allows(.files)
            guard shares || files else { return nil }
            return .init(id: agent.id, generation: agent.generation, mode: agent.mode, name: agent.name, shares: shares, files: files)
        })
    }
    /// Existing private storage owns both the durable receipt and transfer rows. No
    /// shared catalog is trusted as delivery authority, including after restart.
    public func consumeInboundShare(_ inbox: InboundShareInbox, id: String) throws -> InboundShareRequest {
        var imported = false
        defer { if imported { didChange(.transfers) } }
        return try inbox.deliver(id) { request, read in
            let key = "inbound-share-receipt:" + request.id
            if let previous = try state(key) {
                let receipt = try object(previous)
                guard receipt["fingerprint"] as? String == request.fingerprint, let ids = receipt["transfer_ids"] as? [String] else { throw DeviceAPIError(409, "share_replay_mismatch", "This share ID already belongs to another delivery.") }
                return ids
            }
            if request.status == "cancel_requested" { return nil }
            guard !request.items.isEmpty, request.items.count <= request.mode.itemLimit,
                  request.items.allSatisfy({ $0.size >= 0 && $0.size <= request.mode.byteLimit }) else { throw DeviceAPIError(413, "invalid_share_size", "This share exceeds the selected mode's limits.") }
            let ids: [String] = try transaction {
                try Task.checkCancellation()
                try requireTransferEnabled(request.mode.kind)
                guard let agent = try transferAgents().first(where: { $0.id == request.recipient.id && $0.generation == request.recipient.generation && $0.mode == request.recipient.mode }),
                      !agent.paused, agent.allows(request.mode.kind.grant) else { throw DeviceAPIError(403, "share_access_revoked", "This control agent no longer has access to this share mode.") }
                var ids: [String] = []
                for item in request.items {
                    try Task.checkCancellation()
                    guard request.mode.accepts(mime: item.mime) else { throw DeviceAPIError(422, "unsupported_file", "Import images, PDF, plain text or supported audio.") }
                    let bytes = try read(item)
                    guard ["file", "text"].contains(item.origin) else { throw DeviceAPIError(422, "unsupported_share", "Unsupported shared input.") }
                    if item.origin == "text" {
                        guard let text = String(data: bytes, encoding: .utf8), text.utf16.count <= (request.mode == .transfer ? 200_000 : 32768) else { throw DeviceAPIError(413, "share_text_too_large", "The shared text exceeds this mode's limit.") }
                    }
                    let row = try saveTransferBody(for: agent, kind: request.mode.kind, name: item.name, mime: item.mime, bytes: bytes, now: Date())
                    ids.append(row["id"] as! String)
                }
                try Task.checkCancellation()
                try setState(key, value: json(["fingerprint": request.fingerprint, "transfer_ids": ids]))
                return ids
            }
            imported = true; return ids
        }
    }

    public func transferBytes(id: String, for agent: HostAgent?, kind: TransferKind, now: Date = Date()) throws -> (data: Data, mime: String) {
        try pruneTransfers(now: now)
        if agent != nil { try requireTransferEnabled(kind) }
        let scope = agent.map { [$0.eventOwner] } ?? []
        let query = try statement("SELECT payload,json_extract(metadata,'$.mime') FROM transfers WHERE id=? AND kind=?" +
                                  (scope.isEmpty ? "" : " AND owner=?"), [id,kind.rawValue] + scope)
        defer { sqlite3_finalize(query) }
        guard sqlite3_step(query) == SQLITE_ROW, let mime = sqlite3_column_text(query, 1) else {
            throw DeviceAPIError(404, "not_found", "No such transfer.")
        }
        let data = sqlite3_column_blob(query, 0).map { Data(bytes: $0, count: Int(sqlite3_column_bytes(query, 0))) } ?? Data()
        return (data, String(cString: mime))
    }

    public func deleteTransfer(id: String, for agent: HostAgent?, kind: TransferKind, now: Date = Date(), requestedBy: HostAgent? = nil) throws {
        try pruneTransfers(now: now)
        if agent != nil { try requireTransferEnabled(kind) }
        let scope = agent.map { [$0.eventOwner] } ?? []
        try transaction {
            try requireCurrentDirectRequest(requestedBy, operation: kind.grant)
            try execute("DELETE FROM transfers WHERE id=? AND kind=?" + (scope.isEmpty ? "" : " AND owner=?"), [id,kind.rawValue] + scope)
            guard sqlite3_changes(db) == 1 else { throw DeviceAPIError(404, "not_found", "No such transfer.") }
            if agent == nil { try recordSetting("Owner deleted a transfer", at: now) }
        }
        if agent == nil { didChange(.activity) }
        didChange(.transfers)
    }
}


// Queue state and its indexed columns are written together in one SQLite statement.
extension AppletStore {
    private func decodeDelivery(_ raw: String) throws -> ConnectionDelivery {
        let parts = raw.split(separator: "|", maxSplits: 3, omittingEmptySubsequences: false)
        guard raw.utf8.count <= 33 * 1024, parts.count == 4,
              let value = try? JSONDecoder().decode(ConnectionDelivery.self, from: Data(parts[3].utf8)),
              value.id == parts[0], value.owner == parts[1], value.state.rawValue == parts[2] else {
            throw DeviceAPIError(500, "connection_queue_invalid", "Saved connection delivery could not be read.")
        }
        return try value.checked()
    }
    func queuedReceipt(owner: String, key: String) throws -> ConnectionDelivery? {
        guard let raw = try strings("SELECT id || '|' || owner || '|' || state || '|' || body FROM connection_deliveries WHERE owner=? AND idempotency=?", [owner, key]).first else { return nil }
        let row = try decodeDelivery(raw)
        guard row.owner == owner, row.idempotency == key else { throw DeviceAPIError(500, "connection_queue_invalid", "Saved connection delivery could not be read.") }
        return row
    }
    func queueDelivery(_ id: String, owner: String? = nil) throws -> ConnectionDelivery {
        let rows = try strings("SELECT id || '|' || owner || '|' || state || '|' || body FROM connection_deliveries WHERE id=?" + (owner == nil ? "" : " AND owner=?"), [id] + (owner.map { [$0] } ?? []))
        guard let raw = rows.first else { throw DeviceAPIError(404, "not_found", "No such connection delivery.") }
        let row = try decodeDelivery(raw)
        guard row.id == id, owner == nil || row.owner == owner else { throw DeviceAPIError(500, "connection_queue_invalid", "Saved connection delivery could not be read.") }
        return row
    }
    private func writeDelivery(_ row: ConnectionDelivery) throws {
        _ = try row.checked()
        let body = String(decoding: try JSONEncoder().encode(row), as: UTF8.self)
        try execute("UPDATE connection_deliveries SET state=?,next_at=?,lease_until=?,body=? WHERE id=? AND owner=?", [row.state.rawValue,String(row.next),String(row.leaseUntil),body,row.id,row.owner])
        guard sqlite3_changes(db) == 1 else { throw DeviceAPIError(409, "delivery_changed", "This delivery changed.") }
    }
    private func requireQueuedDirectSource(_ row: ConnectionDelivery) throws {
        guard row.mode == OwnerDirectHostService.mode else { return }
        try Task.checkCancellation()
        guard let source = row.sourcePeer, let agent = try transferAgents().first(where: { $0.id == row.agentID }),
              agent.generation == row.generation, agent.mode == row.mode, !agent.paused,
              agent.ownerDirectPeers?.contains(source) == true, agent.allows(.connections) else {
            throw DeviceAPIError(403, "agent_revoked", "This direct sender no longer has delivery authority.")
        }
        if let applet = row.photoApplet {
            guard agent.allows(.appletsMediaRead), agent.allowsApplet(applet, operation: .appletsMediaRead) else {
                throw DeviceAPIError(403, "agent_not_allowed", "The direct sender no longer has media authority.")
            }
        }
    }

    func enqueueDelivery(_ row: ConnectionDelivery, connection: OwnerConnection, now: Date, photoStorageLimit: Int = 100_000_000) throws -> ConnectionDelivery {
        try transaction {
            try requireQueuedDirectSource(row)
            if let prior = try queuedReceipt(owner: row.owner, key: row.idempotency) {
                guard prior.fingerprint == row.fingerprint else { throw DeviceAPIError(409, "idempotency_conflict", "This key queued a different send.") }; return prior
            }
            _ = try row.checked()
            let cutoff = now.timeIntervalSince1970 - 86400
            for raw in try strings("SELECT id || '|' || owner || '|' || state || '|' || body FROM connection_deliveries WHERE state NOT IN ('queued','sending') AND next_at < CAST(? AS REAL)", [String(cutoff)]) {
                var receipt = try decodeDelivery(raw)
                if let finished = receipt.finishedAt ?? receipt.legacyRetentionStartedAt {
                    if finished < cutoff { try execute("DELETE FROM connection_deliveries WHERE id=? AND owner=?", [receipt.id, receipt.owner]) }
                } else {
                    // Legacy next_at is not a completion timestamp. Its true completion
                    // is unknown, so grant a full retention window from this encounter.
                    receipt.legacyRetentionStartedAt = now.timeIntervalSince1970; receipt.next = now.timeIntervalSince1970
                    try writeDelivery(receipt)
                }
            }
            let count = try strings("SELECT COUNT(*) FROM connection_deliveries WHERE state='queued' AND owner=?", [row.owner]).first.flatMap(Int.init) ?? 100
            let total = try strings("SELECT COUNT(*) FROM connection_deliveries WHERE state IN ('queued','sending')").first.flatMap(Int.init) ?? 1000
            guard count < 100, total < 1000 else { throw DeviceAPIError(429, "queue_full", "The connection delivery queue is full.") }
            if let photo = row.photo {
                // Base64 size provides a conservative decoded-byte estimate, including older rows.
                let used = try strings("SELECT coalesce(sum((length(json_extract(body,'$.photo')) * 3) / 4),0) FROM connection_deliveries WHERE state IN ('queued','sending')").first.flatMap(Int.init) ?? 100_000_000
                guard used + photo.count <= photoStorageLimit else { throw DeviceAPIError(429, "queue_full", "The queued photo storage limit has been reached.") }
            }
            try reserveConnectionSend(connection, sender: row.owner, now: now)
            let body = String(decoding: try JSONEncoder().encode(row), as: UTF8.self)
            try execute("INSERT INTO connection_deliveries(id,owner,idempotency,state,next_at,lease_until,created,body) VALUES(?,?,?,?,?,?,?,?)", [row.id,row.owner,row.idempotency,row.state.rawValue,String(row.next),"0",String(row.created),body])
            return row
        }
    }
    func nextDelivery(now: Date, eligibleModes: Set<String>? = nil) throws -> ConnectionDelivery? {
        if eligibleModes?.isEmpty == true { return nil }
        return try transaction {
            // An expired in-flight lease recovers the same ID/body, never a newly generated send.
            for raw in try strings("SELECT id || '|' || owner || '|' || state || '|' || body FROM connection_deliveries WHERE state='sending' AND lease_until <= CAST(? AS REAL) LIMIT 1000", [String(now.timeIntervalSince1970)]) {
                var row = try decodeDelivery(raw)
                guard row.leaseUntil <= now.timeIntervalSince1970 else { throw DeviceAPIError(500, "connection_queue_invalid", "Saved connection delivery could not be read.") }
                row.uncertain = row.uncertain || row.writing
                row.finish(row.attempts >= 6 ? .failed : .queued, error: "delivery_unconfirmed", now: now)
                row.next = now.timeIntervalSince1970; try writeDelivery(row)
            }
            let modes = eligibleModes?.sorted() ?? []
            let modeFilter = modes.isEmpty ? "" : " AND json_extract(body,'$.mode') IN (" + Array(repeating: "?", count: modes.count).joined(separator: ",") + ")"
            return try strings("SELECT id || '|' || owner || '|' || state || '|' || body FROM connection_deliveries WHERE state='queued' AND next_at <= CAST(? AS REAL)" +
                modeFilter + " ORDER BY next_at,rowid LIMIT 1", [String(now.timeIntervalSince1970)] + modes).first.map(decodeDelivery)
        }
    }
    func claimDelivery(_ id: String, lease: String, now: Date) throws -> ConnectionDelivery {
        try transaction {
            var row = try queueDelivery(id)
            guard row.state == .queued, row.next <= now.timeIntervalSince1970, row.attempts < 6 else { throw DeviceAPIError(409, "delivery_changed", "This delivery is no longer due.") }
            row.state = .sending; row.attempts += 1; row.lease = lease; row.writing = false; row.leaseUntil = now.timeIntervalSince1970 + 60
            try writeDelivery(row); return row
        }
    }
    func requireDeliveryLease(_ id: String, lease: String) throws {
        let row = try queueDelivery(id)
        guard row.state == .sending, row.lease == lease else { throw DeviceAPIError(409, "delivery_cancelled", "This delivery was cancelled or replaced.") }
    }
    func markDeliveryWriting(_ id: String, lease: String) throws {
        try transaction {
            try requireDeliveryLease(id, lease: lease)
            var row = try queueDelivery(id)
            try requireQueuedDirectSource(row)
            row.writing = true
            try writeDelivery(row)
        }
    }
    /// The transport refused before the write. Keep the original queued payload
    /// and retry budget even if the write lease was prepared immediately before refusal.
    func deferUnsentDelivery(_ item: ConnectionDelivery) throws {
        try transaction {
            var row = try queueDelivery(item.id)
            guard row.state == .sending, row.lease == item.lease else { return }
            row.finish(.queued, error: row.error, http: row.http)
            row.attempts -= 1
            row.writing = false
            try writeDelivery(row)
        }
    }
    func finishDelivery(_ item: ConnectionDelivery, state: ConnectionDelivery.State, error: String? = nil, http: Int? = nil, next: Date? = nil) throws {
        try transaction {
            var row = try queueDelivery(item.id)
            guard row.state == item.state, row.lease == item.lease else { return }
            row.uncertain = state == .accepted ? false : row.uncertain || row.writing
            row.finish(state, error: error, http: http, now: next ?? Date())
            if let next { row.next = next.timeIntervalSince1970 }
            try writeDelivery(row)
        }
    }
    func cancelDelivery(_ id: String, owner: String? = nil, requestedBy: HostAgent? = nil) throws -> ConnectionDelivery {
        try transaction {
            try requireCurrentDirectRequest(requestedBy, operation: .connections)
            var row = try queueDelivery(id, owner: owner)
            if row.pending { row.uncertain = row.uncertain || row.writing; row.finish(.cancelled, error: "delivery_cancelled"); try writeDelivery(row) }
            return row
        }
    }
    func cancelConnectionDeliveries(_ id: String) throws {
        for raw in try strings("SELECT id || '|' || owner || '|' || state || '|' || body FROM connection_deliveries WHERE state IN ('queued','sending')") {
            let row = try decodeDelivery(raw)
            if row.destination == id { _ = try cancelDelivery(row.id) }
        }
    }
    func deliveryPage(owner: String, before: String? = nil) throws -> (rows: [ConnectionDelivery], next: String?) {
        var query = "SELECT id || '|' || owner || '|' || state || '|' || body FROM connection_deliveries WHERE owner=?", values = [owner]
        if let before {
            _ = try queueDelivery(before, owner: owner)
            query += " AND rowid < (SELECT rowid FROM connection_deliveries WHERE id=? AND owner=?)"; values += [before,owner]
        }
        let rows = try strings(query + " ORDER BY rowid DESC LIMIT 101", values).map(decodeDelivery)
        return (Array(rows.prefix(100)), rows.count > 100 ? rows[99].id : nil)
    }
}
