import Foundation
import SQLite3
import XCTest
@testable import BridgitCore

final class StoreVersionTests: XCTestCase {
    func testFutureDatabaseIsRejectedWithoutDowngradingOrTouchingItsTables() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        var db: OpaquePointer?
        XCTAssertEqual(sqlite3_open(directory.appendingPathComponent("applets.sqlite").path, &db), SQLITE_OK)
        defer { sqlite3_close(db) }
        XCTAssertEqual(sqlite3_exec(db, "CREATE TABLE future_fixture(value TEXT); INSERT INTO future_fixture VALUES('keep'); PRAGMA user_version=999;", nil, nil, nil), SQLITE_OK)
        XCTAssertThrowsError(try AppletStore(directory: directory)) { error in XCTAssertEqual((error as? DeviceAPIError)?.code, "storage_version_unsupported") }
        var statement: OpaquePointer?
        XCTAssertEqual(sqlite3_prepare_v2(db, "PRAGMA user_version", -1, &statement, nil), SQLITE_OK)
        XCTAssertEqual(sqlite3_step(statement), SQLITE_ROW); XCTAssertEqual(sqlite3_column_int(statement, 0), 999); sqlite3_finalize(statement)
        XCTAssertEqual(sqlite3_prepare_v2(db, "SELECT value FROM future_fixture", -1, &statement, nil), SQLITE_OK)
        XCTAssertEqual(sqlite3_step(statement), SQLITE_ROW); XCTAssertEqual(String(cString: sqlite3_column_text(statement, 0)), "keep"); sqlite3_finalize(statement)
    }
    func testVersionTwoHistoryRemainsQueryableAlongsideNewOwnerSettings() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        var db: OpaquePointer?
        XCTAssertEqual(sqlite3_open(directory.appendingPathComponent("applets.sqlite").path, &db), SQLITE_OK)
        let schema = """
        CREATE TABLE host_activity(id INTEGER PRIMARY KEY AUTOINCREMENT, at REAL NOT NULL, agent TEXT NOT NULL, operation TEXT NOT NULL, status INTEGER NOT NULL);
        INSERT INTO host_activity VALUES(10,50,'Bridgit (system)','device.read',200);
        PRAGMA user_version=2;
        """
        XCTAssertEqual(sqlite3_exec(db, schema, nil, nil, nil), SQLITE_OK)
        sqlite3_close(db)
        let store = try AppletStore(directory: directory)
        try await store.setTransferEnabled(.files, enabled: true)
        try await store.setTransferEnabled(.files, enabled: true)
        let reopened = try AppletStore(directory: directory)
        let requests = try await reopened.hostActivity(agentFilter: .legacyName("Bridgit (system)"))
        let settings = try await reopened.hostActivity(includeRequests: false)
        XCTAssertEqual(requests.map(\.id), [10])
        XCTAssertNil(requests.first?.agentID)
        let old = try await reopened.hostActivity(agentFilter: .legacyName("Bridgit (system)"))
        XCTAssertEqual(old.map(\.id), [10])
        let unknownID = try await reopened.hostActivity(agentFilter: .identified("Bridgit (system)"))
        XCTAssertTrue(unknownID.isEmpty)
        XCTAssertEqual(settings.map(\.operation), ["You turned Receive files on"])
        XCTAssertEqual(settings.map(\.isSetting), [true])
        let totals = try await reopened.hostActivityTotals(since: Date(timeIntervalSince1970: 0))
        XCTAssertEqual(totals.requests, 1)
    }

    func testVersionFourRequestRemainsUnidentifiedAfterMigrationAndExport() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        var db: OpaquePointer?
        XCTAssertEqual(sqlite3_open(directory.appendingPathComponent("applets.sqlite").path, &db), SQLITE_OK)
        XCTAssertEqual(sqlite3_exec(db, "CREATE TABLE host_activity(id INTEGER PRIMARY KEY AUTOINCREMENT, at REAL NOT NULL, agent TEXT NOT NULL, operation TEXT NOT NULL, status INTEGER NOT NULL, kind TEXT NOT NULL DEFAULT 'request'); INSERT INTO host_activity VALUES(1,50,'Same name','device.read',200,'request'); PRAGMA user_version=4;", nil, nil, nil), SQLITE_OK)
        sqlite3_close(db)
        let store = try AppletStore(directory: directory)
        let old = try await store.hostActivity(agentFilter: .legacyName("Same name"))
        XCTAssertEqual(old.count, 1); XCTAssertNil(old[0].agentID)
        let filtered = try await store.hostActivity(agentFilter: .identified("Same name"))
        XCTAssertTrue(filtered.isEmpty)
        let snapshot = try await store.hostActivityExport(agentFilter: .legacyName("Same name"))
        let decoded = try JSONSerialization.jsonObject(with: snapshot.data(format: .jsonl)) as? [String: Any]
        XCTAssertTrue(decoded?["agent_id"] is NSNull)
    }

    func testLegacySavedFeedbackActivityDecodesWithoutInventingIdentity() throws {
        let old = Data(#"{"id":7,"at":0,"agent":"Same name","operation":"device.read","status":200,"isSetting":false}"#.utf8)
        let entry = try JSONDecoder().decode(HostActivityEntry.self, from: old)
        XCTAssertNil(entry.agentID)
        XCTAssertEqual(entry.kind, .request)
        let roundTrip = try JSONDecoder().decode(HostActivityEntry.self, from: JSONEncoder().encode(entry))
        XCTAssertNil(roundTrip.agentID); XCTAssertEqual(roundTrip.kind, .request)
    }

    func testLocalAliasIsIndependentOfReportedNameAndRoundTrips() throws {
        var pairing = Pairing(base: URL(string: "https://relay.invalid/d/device_fixture_123")!, deviceId: "device_fixture_123", token: "rly_fixturefixturefixturefixture", name: "Reported name")
        pairing.localAlias = "My label"
        let saved = try JSONDecoder().decode(Pairing.self, from: JSONEncoder().encode(pairing))
        XCTAssertEqual(saved.displayName, "My label"); XCTAssertEqual(saved.name, "Reported name")
        pairing.localAlias = nil; XCTAssertEqual(pairing.displayName, "Reported name")
    }
}
