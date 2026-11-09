import Foundation

public enum HostLogVisibility: String, Codable, Sendable {
    case all, own
}

struct HostLogReadScope: Equatable, Sendable {
    let ownID: String?
}

struct HostLogQuery: Sendable {
    static let channels = ["audit", "diagnostic", "agent"]
    static let kinds = ["request", "setting", "agent", "grant", "pairing", "interaction", "share", "file",
        "service", "crash", "note", "feedback", "battery", "wakewords", "sounds", "schedule", "applets",
        "judgment", "watch", "capture_stored", "capture_upload", "capture_storage", "security", "satellite",
        "people", "vision", "mode"]
    static let maximumPage = 500
    static let channelSQL = "CASE WHEN kind IN ('service','crash') OR (kind='agent' AND CASE WHEN json_valid(note_data) THEN json_extract(note_data,'$.__callback') ELSE 0 END=1) THEN 'diagnostic' WHEN kind='note' THEN 'agent' ELSE 'audit' END"
    static let levelSQL = "COALESCE(note_level,CASE WHEN status>=500 THEN 'error' WHEN status>=400 THEN 'warn' ELSE 'info' END)"
    static let endpointSQL = "json_extract(request_facts,'$.endpoint')"
    static let resultSQL = "COALESCE(CASE WHEN json_valid(note_data) THEN json_extract(note_data,'$.__callback_result') END,json_extract(request_facts,'$.result'))"

    enum Boundary: Sendable {
        case id(Int64), time(Date)
    }
    var channels: [String]
    var kinds: [String]
    private var agentIDs: Set<String>?
    private var endpoint: String?
    private var result: String?
    private var minimumLevel: HostLogLevel?
    var since: Boundary?
    var until: Boundary?
    private var term: String?
    var limit: Int
    var ascending: Bool
    var cursor: Int64?
    let wait: Int

    var hasIDSince: Bool {
        if case .id = since { return true }
        return false
    }

    init(_ fields: [String: Any], caller: HostAgent, agents: [HostAgent]) throws {
        func list(_ key: String) throws -> [String] {
            let value = try Self.text(fields[key], name: key)
            return Array(Set((value ?? "").split(separator: ",").map {
                $0.trimmingCharacters(in: .whitespacesAndNewlines)
            }.filter { !$0.isEmpty })).sorted()
        }
        channels = try list("channel")
        guard channels.allSatisfy(Self.channels.contains) else { throw Self.invalid("channel must be audit, diagnostic or agent.") }
        kinds = try list("kind")
        guard kinds.allSatisfy(Self.kinds.contains) else { throw Self.invalid("Unknown log kind.") }
        if fields["agent"] != nil {
            agentIDs = Set(try list("agent").map { value in
                if value == "me" { return caller.id }
                if ["bridgit", "bridget"].contains(value.lowercased()) || value == "system" { return "" }
                guard let match = agents.first(where: { $0.id == value }) ?? agents.first(where: {
                    $0.name.caseInsensitiveCompare(value) == .orderedSame
                }) else { throw Self.invalid("Unknown log agent.") }
                return match.id
            })
        }
        if let level = try Self.text(fields["level"], name: "level") {
            guard let resolved = HostLogLevel(rawValue: level) else { throw Self.invalid("level must be debug, info, warn or error.") }
            minimumLevel = resolved
        }
        endpoint = try Self.text(fields["endpoint"], name: "endpoint").flatMap { $0.isEmpty ? nil : $0 }
        result = try Self.text(fields["result"], name: "result").flatMap { $0.isEmpty ? nil : $0 }
        term = try Self.text(fields["q"], name: "q").flatMap { $0.isEmpty ? nil : HostLogNote.utf16Prefix($0, max: 200) }
        since = try Self.boundary(fields["since"], name: "since")
        until = try Self.boundary(fields["until"], name: "until")
        limit = try Self.integer(fields["limit"], name: "limit", default: 100, range: 1...Self.maximumPage)
        wait = try Self.integer(fields["wait"], name: "wait", default: 0, range: 0...55)
        let order = try Self.text(fields["order"], name: "order") ?? "desc"
        guard ["desc", "asc"].contains(order) else { throw Self.invalid("order must be asc or desc.") }
        ascending = order == "asc"
        if let raw = try Self.text(fields["cursor"], name: "cursor") {
            guard raw.first == "c", let value = Int64(raw.dropFirst(), radix: 36), value >= 0 else {
                throw Self.invalid("cursor is not valid.")
            }
            cursor = value
        }
    }

    static func encodeCursor(_ id: Int64) -> String { "c" + String(id, radix: 36) }

    static func text(_ value: Any?, name: String) throws -> String? {
        guard let value else { return nil }
        guard let text = value as? String, !text.utf8.contains(0) else { throw invalid(name + " must be text.") }
        return text
    }

    static func integer(_ value: Any?, name: String, default fallback: Int, range: ClosedRange<Int>) throws -> Int {
        guard let value else { return fallback }
        let number = (value as? String).flatMap(Int.init) ?? RelayHostConnection.integer(value).flatMap(Int.init(exactly:))
        guard let number, range.contains(number) else { throw invalid(name + " is outside its supported range.") }
        return number
    }

    private static func boundary(_ value: Any?, name: String) throws -> Boundary? {
        guard let value else { return nil }
        if let integer = RelayHostConnection.integer(value) {
            guard integer >= 0 else { throw invalid(name + " must not be negative.") }
            return .id(integer)
        }
        guard let text = try text(value, name: name), !text.isEmpty else { return nil }
        if let integer = Int64(text) {
            guard integer >= 0 else { throw invalid(name + " must not be negative.") }
            return .id(integer)
        }
        let iso = ISO8601DateFormatter()
        iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = iso.date(from: text) { return .time(date) }
        iso.formatOptions = [.withInternetDateTime]
        guard let date = iso.date(from: text) else { throw invalid(name + " must be a log id or an ISO time.") }
        return .time(date)
    }

    static func invalid(_ message: String) -> DeviceAPIError { .init(422, "invalid_parameter", message) }

    /// Only these fixed SQL fragments vary. Every caller-supplied value is bound.
    func predicate(ownID: String?, maximumID: Int64? = nil) -> (String, [String]) {
        var clauses: [String] = [], values: [String] = []
        func add(_ clause: String, _ value: String) { clauses.append(clause); values.append(value) }
        func members(_ field: String, _ items: [String]) {
            if !items.isEmpty {
                clauses.append("(" + field + ") IN (" + Array(repeating: "?", count: items.count).joined(separator: ",") + ")")
                values += items
            }
        }
        func like(_ text: String) -> String {
            text.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "%", with: "\\%")
                .replacingOccurrences(of: "_", with: "\\_")
        }
        members(Self.channelSQL, channels)
        members("kind", kinds)
        if let ownID { add("agent_id=?", ownID) }
        if let agentIDs {
            let identified = agentIDs.filter { !$0.isEmpty }.sorted()
            if agentIDs.contains("") {
                let idClause = identified.isEmpty ? "" : " OR agent_id IN (" + Array(repeating: "?", count: identified.count).joined(separator: ",") + ")"
                clauses.append("(agent_id IS NULL" + idClause + ")"); values += identified
            } else if identified.isEmpty { clauses.append("0") }
            else { members("agent_id", identified) }
        }
        if let endpoint { add(Self.endpointSQL + " LIKE ? ESCAPE '\\'", like(endpoint) + "%") }
        if result == "error" {
            clauses.append("(status>=400 OR " + Self.levelSQL + "='error')")
        } else if let result { add(Self.resultSQL + "=?", result) }
        if let minimumLevel, let rank = HostLogLevel.allCases.firstIndex(of: minimumLevel) {
            members(Self.levelSQL, HostLogLevel.allCases.dropFirst(rank).map(\.rawValue))
        }
        switch since {
        case .id(let id): add("id>?", String(id))
        case .time(let date): add("at>=?", String(date.timeIntervalSince1970))
        case nil: break
        }
        switch until {
        case .id(let id): add("id<?", String(id))
        case .time(let date): add("at<=?", String(date.timeIntervalSince1970))
        case nil: break
        }
        if let term {
            let pattern = "%" + like(term) + "%"
            add("(operation LIKE ? ESCAPE '\\' OR " + Self.endpointSQL + " LIKE ? ESCAPE '\\')", pattern)
            values.append(pattern)
        }
        if let cursor { add(ascending ? "id>?" : "id<?", String(cursor)) }
        if let maximumID { add("id<=?", String(maximumID)) }
        return (clauses.isEmpty ? "1" : clauses.joined(separator: " AND "), values)
    }
}
