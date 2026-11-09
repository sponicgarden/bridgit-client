import Foundation
import SQLite3
import XCTest
@testable import BridgitAppletRuntime

final class RowsPageTests: XCTestCase {
    @MainActor func testPagesBeyond500KeepStableOrderAfterCursorDeletionAndRejectOtherScopes() async throws {
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        var definition = try JSONSerialization.jsonObject(with: Data(contentsOf: root.appendingPathComponent("docs/applet-authoring-examples/png-icon.json"))) as! [String: Any]
        definition["processing"] = ["tables": ["notes", "other"].map { ["id": $0, "columns": ["message": ["type": "text"]], "max_rows": 1000, "keep_days": 90] }]
        let entry = try JSONDecoder().decode(CatalogEntry.self, from: JSONSerialization.data(withJSONObject: ["key": "tpl_rows", "id": "blue-note", "revision": 1, "name": "Synthetic rows", "summary": "Test rows"]))
        let release = try RegistryPackage(response: JSONSerialization.data(withJSONObject: ["key": entry.key, "revision": 1, "state": "published", "definition": definition]), selection: entry)
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let database = folder.appendingPathComponent("library.sqlite")
        let library = try LocalAppletLibrary(database: database, account: String(repeating: "e", count: 64))
        try await library.install(release)
        let loaded = try await library.installed(entry.key)
        let edition = try XCTUnwrap(loaded)
        var db: OpaquePointer?
        XCTAssertEqual(sqlite3_open(database.path, &db), SQLITE_OK)
        defer { sqlite3_close(db) }
        XCTAssertEqual(sqlite3_exec(db, "BEGIN IMMEDIATE", nil, nil, nil), SQLITE_OK)
        // Synthetic read fixture only. This does not lower the real runtime's per-minute write guard.
        for index in 0..<601 {
            let body = "{\"message\":\"row-\(index)\"}"
            let sql = "INSERT INTO applet_rows SELECT instance,'notes','fixture-\(index)',unixepoch(),unixepoch()+86400,'\(body)' FROM local_applets WHERE key='tpl_rows'"
            XCTAssertEqual(sqlite3_exec(db, sql, nil, nil, nil), SQLITE_OK)
        }
        XCTAssertEqual(sqlite3_exec(db, "COMMIT", nil, nil, nil), SQLITE_OK)
        let snapshot = try await library.savedDataSnapshot(in: edition)
        let decoded = try JSONSerialization.jsonObject(with: snapshot) as! [String: Any]
        let tables = decoded["tables"] as! [[String: Any]]
        let noteColumns = tables.first { $0["id"] as? String == "notes" }?["columns"] as? [String: [String: Any]]
        XCTAssertEqual(noteColumns?["message"]?["type"] as? String, "text")
        XCTAssertEqual((tables.first { $0["id"] as? String == "notes" }?["rows"] as? [[String: Any]])?.count, 601)
        XCTAssertEqual((tables.first { $0["id"] as? String == "other" }?["rows"] as? [[String: Any]])?.count, 0)
        let foreign = try LocalAppletLibrary(database: database, account: String(repeating: "f", count: 64))
        do { _ = try await foreign.savedDataSnapshot(in: edition); XCTFail("Snapshot crossed account") }
        catch is AppletRuntimeError { }
        let first = try await library.rowsPage(in: edition, table: "notes")
        XCTAssertEqual(first.rows.count, 500); XCTAssertNotNil(first.next)
        XCTAssertEqual(first.rows.first?["message"]?.text, "row-600")
        XCTAssertEqual(first.rows.last?["message"]?.text, "row-101")
        XCTAssertEqual(sqlite3_exec(db, "DELETE FROM applet_rows WHERE effect='fixture-101'", nil, nil, nil), SQLITE_OK)
        let second = try await library.rowsPage(in: edition, table: "notes", before: first.next)
        XCTAssertEqual(second.rows.count, 101); XCTAssertNil(second.next)
        XCTAssertEqual(second.rows.first?["message"]?.text, "row-100")
        XCTAssertEqual(second.rows.last?["message"]?.text, "row-0")
        XCTAssertEqual(Set((first.rows + second.rows).compactMap { $0["id"]?.text }).count, 601)
        do { _ = try await library.rowsPage(in: edition, table: "other", before: first.next); XCTFail("Table cursor crossed scope") }
        catch let error as AppletRuntimeError { XCTAssertEqual(error, .invalid("Invalid table cursor")) }
        let otherEntry = try JSONDecoder().decode(CatalogEntry.self, from: JSONSerialization.data(withJSONObject: ["key": "tpl_second", "id": "blue-note", "revision": 1, "name": "Other edition", "summary": "Other rows"]))
        try await library.install(RegistryPackage(response: JSONSerialization.data(withJSONObject: ["key": otherEntry.key, "revision": 1, "state": "published", "definition": definition]), selection: otherEntry))
        let otherLoaded = try await library.installed(otherEntry.key)
        let otherEdition = try XCTUnwrap(otherLoaded)
        do { _ = try await library.rowsPage(in: otherEdition, table: "notes", before: first.next); XCTFail("Edition cursor crossed scope") }
        catch let error as AppletRuntimeError { XCTAssertEqual(error, .invalid("Invalid table cursor")) }
        let cancelled = Task {
            withUnsafeCurrentTask { $0?.cancel() }
            return try await library.rowsPage(in: edition, table: "notes")
        }
        do { _ = try await cancelled.value; XCTFail("Cancelled row retrieval completed") } catch is CancellationError { }
        let exported = try await library.ownerTableRows(in: edition, table: "notes")
        XCTAssertEqual(exported.count, 600)
        XCTAssertEqual(exported.first?["message"]?.text, "row-0")
        XCTAssertEqual(exported.last?["message"]?.text, "row-600")
        let preCancelledExport = Task {
            withUnsafeCurrentTask { $0?.cancel() }
            return try await library.ownerTableRows(in: edition, table: "notes")
        }
        do { _ = try await preCancelledExport.value; XCTFail("Cancelled owner export succeeded") } catch is CancellationError { }
        let preCancelledDelete = Task {
            withUnsafeCurrentTask { $0?.cancel() }
            return try await library.deleteOwnerRows(in: edition, table: "notes")
        }
        do { _ = try await preCancelledDelete.value; XCTFail("Cancelled owner delete succeeded") } catch is CancellationError { }
        let unchanged = try await library.ownerTableRows(in: edition, table: "notes")
        XCTAssertEqual(unchanged.count, 600)
        let otherAccountEdition = try await library.ownerTableRows(in: otherEdition, table: "notes")
        XCTAssertTrue(otherAccountEdition.isEmpty)
        let newest = try XCTUnwrap(exported.last?["id"]?.text)
        do { try await library.deleteOwnerRow(newest, in: otherEdition, table: "notes"); XCTFail("Deletion crossed same-account installation") }
        catch is AppletRuntimeError { }
        do { _ = try await foreign.ownerTableRows(in: edition, table: "notes"); XCTFail("Owner export crossed account") }
        catch is AppletRuntimeError { }
        do { _ = try await library.ownerTableRows(in: edition, table: "missing"); XCTFail("Exported undeclared table") }
        catch is AppletRuntimeError { }
        do { _ = try await foreign.deleteOwnerRows(in: edition, table: "notes"); XCTFail("Owner deletion crossed account") }
        catch is AppletRuntimeError { }
        do { _ = try await library.deleteOwnerRows(in: edition, table: "missing"); XCTFail("Deleted undeclared table") }
        catch is AppletRuntimeError { }

        // One photo is shared by another table; only the deleted row's exclusive photo may be removed.
        let seed = """
            INSERT INTO applet_rows SELECT instance,'other','fixture-other',unixepoch(),unixepoch()+86400,'{"message":"keep"}' FROM local_applets WHERE key='tpl_rows';
            INSERT INTO applet_photos SELECT instance,'shared',unixepoch(),x'0102' FROM local_applets WHERE key='tpl_rows';
            INSERT INTO applet_photos SELECT instance,'exclusive',unixepoch(),x'0102' FROM local_applets WHERE key='tpl_rows';
            INSERT INTO applet_row_photos SELECT instance,'fixture-600','shared' FROM local_applets WHERE key='tpl_rows';
            INSERT INTO applet_row_photos SELECT instance,'fixture-600','exclusive' FROM local_applets WHERE key='tpl_rows';
            INSERT INTO applet_row_photos SELECT instance,'fixture-other','shared' FROM local_applets WHERE key='tpl_rows';
            """
        XCTAssertEqual(sqlite3_exec(db, seed, nil, nil, nil), SQLITE_OK)
        let newestID = try XCTUnwrap(exported.last?["id"]?.text)
        try await library.deleteOwnerRow(newestID, in: edition, table: "notes")
        func photoCount(_ ref: String) -> Int32 {
            var q: OpaquePointer?; defer { sqlite3_finalize(q) }
            XCTAssertEqual(sqlite3_prepare_v2(db, "SELECT COUNT(*) FROM applet_photos WHERE reference=?", -1, &q, nil), SQLITE_OK)
            sqlite3_bind_text(q, 1, ref, -1, unsafeBitCast(-1, to: sqlite3_destructor_type.self))
            XCTAssertEqual(sqlite3_step(q), SQLITE_ROW); return sqlite3_column_int(q, 0)
        }
        XCTAssertEqual(photoCount("exclusive"), 0); XCTAssertEqual(photoCount("shared"), 1)
        do { try await library.deleteOwnerRow(newestID, in: edition, table: "notes"); XCTFail("Deleted absent row") }
        catch is AppletRuntimeError { }
        let removed = try await library.deleteOwnerRows(in: edition, table: "notes")
        XCTAssertEqual(removed, 599)
        let remaining = try await library.rowsPage(in: edition, table: "notes")
        XCTAssertTrue(remaining.rows.isEmpty)
        let otherRows = try await library.rowsPage(in: edition, table: "other")
        XCTAssertEqual(otherRows.rows.first?["message"]?.text, "keep")
        XCTAssertEqual(photoCount("shared"), 1)
        // A synthetic over-limit table must fail explicitly rather than return a truncated export.
        let overLimit = """
            WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<10001)
            INSERT INTO applet_rows SELECT instance,'other','overflow-' || x,unixepoch(),unixepoch()+86400,'{"message":"overflow"}'
            FROM local_applets,n WHERE key='tpl_rows';
            """
        XCTAssertEqual(sqlite3_exec(db, overLimit, nil, nil, nil), SQLITE_OK)
        do { _ = try await library.ownerTableRows(in: edition, table: "other"); XCTFail("Over-limit export silently succeeded") }
        catch is OwnerTableExportLimitError { }
        let reopened = try LocalAppletLibrary(database: database, account: String(repeating: "e", count: 64))
        let persisted = try await reopened.rowsPage(in: edition, table: "notes")
        XCTAssertTrue(persisted.rows.isEmpty)
        try await library.remove(edition)
        try await library.install(release)
        let reinstalledValue = try await library.installed(entry.key)
        let reinstalled = try XCTUnwrap(reinstalledValue)
        _ = try await library.writeAgentRow(["message": .string("new installation")], in: reinstalled, table: "notes")
        do { _ = try await library.deleteOwnerRows(in: edition, table: "notes"); XCTFail("Stale edition deleted replacement") }
        catch is AppletRuntimeError { }
        let replacement = try await library.rowsPage(in: reinstalled, table: "notes")
        XCTAssertEqual(replacement.rows.first?["message"]?.text, "new installation")
    }
}
