import Foundation
import XCTest
@testable import BridgitCore

final class HostActivityExportTests: XCTestCase {
    private func fixture() async throws -> (AppletStore, HostAgent) {
        let path = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: path) }
        let store = try AppletStore(directory: path)
        let authority = HostAuthority(store: store, deviceID: "fixture")
        let (agent, _) = try await authority.create(name: "Synthetic Agent", operations: [.deviceRead], applets: [])
        return (store, agent)
    }
    func testCompleteFilteredSnapshotCrossesPagesWithoutDuplicatesAndStaysFrozen() async throws {
        let (store, agent) = try await fixture()
        for index in 0..<1005 {
            try await store.recordHostActivity(agent: agent, operation: .deviceRead, status: index % 2 == 0 ? 500 : 200,
                                               at: Date(timeIntervalSince1970: Double(index)))
        }
        try await store.setTransferEnabled(.files, enabled: true)
        let all = try await store.hostActivityExport(pageSize: 100)
        XCTAssertEqual(all.count, 1007)
        XCTAssertEqual(Set(all.entries.map(\.id)).count, all.count)
        XCTAssertEqual(all.entries.map(\.id), all.entries.map(\.id).sorted(by: >))
        let filtered = try await store.hostActivityExport(query: "DEVICE", agentFilter: .identified(agent.id), errorsOnly: true,
                                                         includeSettings: false, pageSize: 3)
        XCTAssertEqual(filtered.count, 503)
        XCTAssertTrue(filtered.entries.allSatisfy { $0.status == 500 && $0.agent == agent.name && !$0.isSetting })
        let settings = try await store.hostActivityExport(kinds: [.setting], includeRequests: false, pageSize: 1)
        XCTAssertEqual(settings.count, 1); XCTAssertTrue(settings.entries[0].isSetting)
        let none = try await store.hostActivityExport(kinds: [], includeRequests: false, includeSettings: false)
        XCTAssertEqual(none.count, 0)
        let literal = try await store.hostActivityExport(query: "%")
        XCTAssertEqual(literal.count, 0, "Search remains literal, matching Activity")
        let bytes = try filtered.data(format: .jsonl)
        try await store.recordHostActivity(agent: agent, operation: .deviceRead, status: 500)
        XCTAssertEqual(try filtered.data(format: .jsonl), bytes, "Count and save bytes use one frozen snapshot")
        XCTAssertEqual(filtered.count, 503)
    }
    func testSuggestedNamesUseLocalGregorianASCIIClockAndExactExtension() throws {
        let date = Date(timeIntervalSince1970: 0)
        let utc = try XCTUnwrap(TimeZone(secondsFromGMT: 0))
        let east = try XCTUnwrap(TimeZone(secondsFromGMT: 3600))
        XCTAssertEqual(HostActivityExport.suggestedFilename(format: .csv, at: date, timeZone: utc), "bridgit-log-19700101-0000.csv")
        XCTAssertEqual(HostActivityExport.suggestedFilename(format: .jsonl, at: date, timeZone: east), "bridgit-log-19700101-0100.jsonl")
        let negative = try XCTUnwrap(TimeZone(secondsFromGMT: -3600))
        XCTAssertEqual(HostActivityExport.suggestedFilename(format: .csv, at: date, timeZone: negative), "bridgit-log-19691231-2300.csv")
    }
    private let csvHeader = "id,ts,channel,level,kind,agent,agent_id,source_ip,method,endpoint,http,result,bytes_in,bytes_out,duration_ms,request_id,message"
    private let logKeys: Set<String> = ["id", "ts", "channel", "level", "kind", "agent", "agent_id", "source_ip", "method",
        "endpoint", "params", "http", "result", "bytes_in", "bytes_out", "duration_ms", "request_id", "message", "data"]

    private func parsedCSV(_ text: String) throws -> [[String]] {
        enum ParseError: Error { case unterminatedQuotedField }
        let chars = Array(text)
        var rows: [[String]] = [], row: [String] = [], field = "", quoted = false, index = 0
        while index < chars.count {
            let char = chars[index]
            if quoted {
                if char == "\"" {
                    if index + 1 < chars.count && chars[index + 1] == "\"" { field.append("\""); index += 1 }
                    else { quoted = false }
                } else { field.append(char) }
            } else if char == "\"" { quoted = true }
            else if char == "," { row.append(field); field = "" }
            else if char == "\n" { row.append(field); rows.append(row); row = []; field = "" }
            else { field.append(char) }
            index += 1
        }
        if quoted { throw ParseError.unterminatedQuotedField }
        if !row.isEmpty || !field.isEmpty { row.append(field); rows.append(row) }
        return rows
    }

    func testJSONLAndCSVUsePinnedSchemaAndPreserveQuotedMultilineValues() throws {
        let entry = HostActivityEntry(id: 7, at: Date(timeIntervalSince1970: 0), agent: "Synthetic, \"quoted\"\n雪",
                                      operation: "device.read,\r\nfailed \"once\"", status: 429, isSetting: false)
        let snapshot = HostActivityExport(entries: [entry])
        let utc = TimeZone(secondsFromGMT: 0)!
        let bytes = try snapshot.data(format: .jsonl, timeZone: utc)
        XCTAssertEqual(bytes.last, 0x0a)
        let lines = String(decoding: bytes, as: UTF8.self).split(separator: "\n")
        XCTAssertEqual(lines.count, 1)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(lines[0].utf8)) as? [String: Any])
        XCTAssertEqual(Set(object.keys), logKeys)
        XCTAssertEqual(object["id"] as? Int, 7)
        XCTAssertEqual(object["agent"] as? String, entry.agent)
        XCTAssertEqual(object["ts"] as? String, "1970-01-01T00:00:00Z")
        XCTAssertEqual(object["kind"] as? String, "request")
        XCTAssertEqual(object["channel"] as? String, "audit")
        XCTAssertEqual(object["level"] as? String, "warn")
        XCTAssertEqual(object["http"] as? Int, 429)
        XCTAssertEqual(object["message"] as? String, entry.operation)
        for key in ["agent_id", "source_ip", "method", "endpoint", "result", "bytes_in", "bytes_out", "duration_ms", "request_id"] {
            XCTAssertTrue(object[key] is NSNull, "\(key) must not be invented")
        }
        XCTAssertEqual((object["params"] as? [String: String])?.count, 0)
        XCTAssertEqual((object["data"] as? [String: String])?.count, 0)

        let csv = try snapshot.data(format: .csv, timeZone: utc)
        let path = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: path) }
        try csv.write(to: path)
        XCTAssertEqual(try Data(contentsOf: path), csv)
        let text = String(decoding: csv, as: UTF8.self)
        XCTAssertTrue(text.hasPrefix(csvHeader + "\n7,1970-01-01T00:00:00Z,audit,warn,request,"))
        XCTAssertTrue(text.contains("\"Synthetic, \"\"quoted\"\"\n雪\""))
        XCTAssertTrue(text.contains("\"device.read,\r\nfailed \"\"once\"\"\""))
        XCTAssertTrue(text.hasSuffix("\n"))
        let parsed = try parsedCSV(text)
        XCTAssertEqual(parsed.count, snapshot.count + 1)
        XCTAssertEqual(parsed.map(\.count), [17, 17])
        XCTAssertEqual(parsed[0], csvHeader.split(separator: ",").map(String.init))
        let fields = Dictionary(uniqueKeysWithValues: zip(parsed[0], parsed[1]))
        XCTAssertEqual(fields["agent"], entry.agent)
        XCTAssertEqual(fields["message"], entry.operation)
        XCTAssertEqual(fields["http"], "429")
        XCTAssertEqual(fields["bytes_out"], "")
        XCTAssertEqual(try HostActivityExport(entries: []).data(format: .jsonl), Data())
        XCTAssertEqual(String(decoding: try HostActivityExport(entries: []).data(format: .csv), as: UTF8.self), csvHeader + "\n")
    }

    func testSafeCSVFieldsRemainUnquotedAndUseLFOnly() throws {
        let entry = HostActivityEntry(id: 9, at: Date(timeIntervalSince1970: 0), agent: "Sender", operation: "device.read", status: 200, isSetting: false)
        let csv = try HostActivityExport(entries: [entry]).data(format: .csv, timeZone: TimeZone(secondsFromGMT: 0)!)
        XCTAssertEqual(String(decoding: csv, as: UTF8.self),
                       csvHeader + "\n9,1970-01-01T00:00:00Z,audit,info,request,Sender,,,,,200,,,,,,device.read\n")
    }

    func testSettingExportReusesExistingDetailProjectionAndRequestDetailsUseSameFields() throws {
        let setting = HostActivityEntry(id: 8, at: Date(timeIntervalSince1970: 100), agent: "Bridgit (system)", operation: "You turned Receive files on", status: 200, isSetting: true)
        let json = try HostActivityExport(entries: [setting]).data(format: .jsonl)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: json) as? [String: Any])
        let detailJSON = try XCTUnwrap(SettingActivityDetail(setting)).json()
        let detail = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(detailJSON.utf8)) as? [String: Any])
        XCTAssertEqual(object as NSDictionary, detail as NSDictionary)
        XCTAssertEqual(Set(object.keys), logKeys)
        XCTAssertTrue(object["agent"] is NSNull)
        XCTAssertTrue(object["http"] is NSNull)
        let request = HostActivityEntry(id: 9, at: setting.at, agent: "Sender", operation: "device.read", status: 503, isSetting: false)
        let requestDetail = HostActivityDetail(request)
        XCTAssertEqual(requestDetail.text(for: "http"), "503")
        XCTAssertEqual(requestDetail.text(for: "message"), "device.read")
        XCTAssertEqual(requestDetail.text(for: "method"), "null")
        XCTAssertEqual(requestDetail.text(for: "level"), "error")
        XCTAssertNil(SettingActivityDetail(request))
    }

    func testActualSuccessfulWriteAuditsFrozenExportCountAfterTheWrite() async throws {
        let (store, agent) = try await fixture()
        try await store.recordHostActivity(agent: agent, operation: .deviceRead, status: 200)
        let snapshot = try await store.hostActivityExport()
        XCTAssertEqual(snapshot.count, 2)
        let bytes = try snapshot.data(format: .csv)
        let before = try await store.hostActivity(kinds: [.setting], includeRequests: false)
        XCTAssertTrue(before.isEmpty, "Preparing bytes is not a successful export")
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: url) }
        try bytes.write(to: url)
        let written = try parsedCSV(String(decoding: Data(contentsOf: url), as: UTF8.self))
        XCTAssertEqual(written.count - 1, snapshot.count)
        let count = try await snapshot.recordCompletion(.success(url), in: store, at: Date(timeIntervalSince1970: 100))
        XCTAssertEqual(count, snapshot.count)
        let audit = try await store.hostActivity(kinds: [.setting], includeRequests: false)
        XCTAssertEqual(audit.map(\.operation), ["You exported 2 log entries"])
        XCTAssertTrue(audit.allSatisfy(\.isSetting))
        XCTAssertFalse(String(decoding: bytes, as: UTF8.self).contains("You exported"))
        let after = try await store.hostActivityExport()
        XCTAssertEqual(after.count, snapshot.count + 1)
    }

    func testCancellationAndActualWriteFailureNeverAuditAnExport() async throws {
        let (store, _) = try await fixture()
        let snapshot = try await store.hostActivityExport()
        let cancelled = try await snapshot.recordCompletion(.failure(CancellationError()), in: store)
        XCTAssertNil(cancelled)
        let invalid = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathComponent("missing/log.csv")
        let result = Result<URL, Error> {
            try snapshot.data(format: .csv).write(to: invalid)
            return invalid
        }
        guard case .failure = result else { return XCTFail("Fixture write must fail") }
        let failed = try await snapshot.recordCompletion(result, in: store)
        XCTAssertNil(failed)
        let audit = try await store.hostActivity(kinds: [.setting], includeRequests: false)
        XCTAssertTrue(audit.isEmpty)
    }

    func testSuccessfulEmptyExportAuditsZeroRatherThanInventingRows() async throws {
        let (store, _) = try await fixture()
        let snapshot = try await store.hostActivityExport(kinds: [.request])
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: url) }
        try snapshot.data(format: .jsonl).write(to: url)
        XCTAssertEqual(try Data(contentsOf: url).count, 0)
        let count = try await snapshot.recordCompletion(.success(url), in: store)
        XCTAssertEqual(count, 0)
        let audit = try await store.hostActivity(kinds: [.setting], includeRequests: false)
        XCTAssertEqual(audit.map(\.operation), ["You exported 0 log entries"])
    }
}
