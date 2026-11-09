import Foundation

/// Exactly the persisted, device-local fields shown by Activity. No request bodies or credentials.
public struct HostActivityExport: Sendable {
    public let entries: [HostActivityEntry]
    public var count: Int { entries.count }
    public enum Format: String, CaseIterable, Sendable { case jsonl, csv }
    public static func suggestedFilename(format: Format, at date: Date = Date(), timeZone: TimeZone = .current) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.timeZone = timeZone
        formatter.dateFormat = "yyyyMMdd-HHmm"
        return "bridgit-log-" + formatter.string(from: date) + "." + format.rawValue
    }
    /// Same ordered columns as pinned Android LogModule.CSV_COLS (APK 3347).
    public static let csvColumns = [
        "id", "ts", "channel", "level", "kind", "agent", "agent_id", "source_ip", "method", "endpoint",
        "http", "result", "bytes_in", "bytes_out", "duration_ms", "request_id", "message"
    ]
    public func data(format: Format, timeZone: TimeZone = .current) throws -> Data {
        var data = Self.header(for: format)
        for entry in entries { data.append(try Self.line(entry, format: format, timeZone: timeZone)) }
        return data
    }

    static func header(for format: Format) -> Data {
        format == .csv ? Data((csvColumns.joined(separator: ",") + "\n").utf8) : Data()
    }

    static func line(_ entry: HostActivityEntry, format: Format, timeZone: TimeZone = .current,
                     currentAgentName: String? = nil) throws -> Data {
        let fields = HostActivityDetail(entry, timeZone: timeZone, currentAgentName: currentAgentName).fields
        switch format {
        case .jsonl:
            var data = try JSONSerialization.data(withJSONObject: fields, options: [.sortedKeys])
            data.append(0x0a)
            return data
        case .csv:
            func quote(_ value: String) -> String {
                guard value.rangeOfCharacter(from: CharacterSet(charactersIn: ",\"\r\n")) != nil else { return value }
                return "\"" + value.replacingOccurrences(of: "\"", with: "\"\"") + "\""
            }
            let line = csvColumns.map { key in
                guard let value = fields[key], !(value is NSNull) else { return "" }
                return quote(String(describing: value))
            }.joined(separator: ",")
            return Data((line + "\n").utf8)
        }
    }

    /// Call with the native exporter completion, after writing; preparing/cancelling is not an export.
    @discardableResult
    public func recordCompletion(_ result: Result<URL, Error>, in store: AppletStore, at date: Date = Date()) async throws -> Int? {
        guard case .success = result else { return nil }
        try await store.recordSuccessfulHostActivityExport(count: count, at: date)
        return count
    }
}

extension AppletStore {
    /// Runs without suspension on the store actor: count and file use the same complete snapshot.
    public func hostActivityExport(query: String = "", agentFilter: HostActivityAgentFilter? = nil, errorsOnly: Bool = false, kinds: Set<HostActivityKind>? = nil, systemOnly: Bool = false,
                                   includeRequests: Bool = true, includeSettings: Bool = true,
                                   pageSize: Int = 1000) throws -> HostActivityExport {
        guard (1...1000).contains(pageSize) else { throw DeviceAPIError(422, "invalid_parameter", "Invalid activity page size.") }
        var entries: [HostActivityEntry] = [], before: Int64?
        while true {
            let page = try hostActivity(limit: pageSize, before: before, query: query, agentFilter: agentFilter,
                                       errorsOnly: errorsOnly, kinds: kinds, systemOnly: systemOnly, includeRequests: includeRequests, includeSettings: includeSettings)
            entries.append(contentsOf: page)
            guard page.count == pageSize, let last = page.last else { break }
            before = last.id
        }
        return HostActivityExport(entries: entries)
    }
}
