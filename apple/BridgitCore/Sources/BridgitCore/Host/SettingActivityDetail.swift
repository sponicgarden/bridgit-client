import Foundation

/// One LOG-01 projection for on-device details and exports. Absent request telemetry stays null.
public struct HostActivityDetail {
    public static let keys = [
        "id", "ts", "channel", "level", "kind", "agent", "agent_id", "source_ip", "method",
        "endpoint", "params", "http", "result", "bytes_in", "bytes_out", "duration_ms",
        "request_id", "message", "data"
    ]
    let fields: [String: Any]

    public init(_ entry: HostActivityEntry, timeZone: TimeZone = .current, currentAgentName: String? = nil) {
        var fields: [String: Any] = Dictionary(uniqueKeysWithValues: Self.keys.map { ($0, NSNull()) })
        fields["id"] = entry.id
        let timestamp = ISO8601DateFormatter()
        timestamp.timeZone = timeZone
        fields["ts"] = timestamp.string(from: entry.at)
        let callbackData = entry.noteData.flatMap { try? JSONSerialization.jsonObject(with: Data($0.utf8)) as? [String: Any] }
        let callback = callbackData?["__callback"] as? Bool == true
        switch entry.kind {
        case .service, .crash: fields["channel"] = "diagnostic"
        case .note: fields["channel"] = "agent"
        case .request, .setting, .agent, .pairing: fields["channel"] = callback ? "diagnostic" : "audit"
        }
        fields["level"] = entry.noteLevel?.rawValue ?? (entry.status >= 500 ? "error" : entry.status >= 400 ? "warn" : "info")
        fields["kind"] = entry.kind.rawValue
        if entry.kind == .request {
            if !entry.isAnonymousRequest { fields["agent"] = currentAgentName ?? entry.displayAgentName }
            if let agentID = entry.agentID { fields["agent_id"] = agentID }
            fields["http"] = entry.status
            if let request = entry.requestFacts {
                fields["method"] = request.method
                fields["endpoint"] = request.endpoint
                if let value = request.sourceIP { fields["source_ip"] = value }
                if let value = request.result { fields["result"] = value }
                if let value = request.bytesIn { fields["bytes_in"] = value }
                if let value = request.bytesOut { fields["bytes_out"] = value }
                if let value = request.durationMS { fields["duration_ms"] = value }
                if let value = request.requestID { fields["request_id"] = value }
            }
        }
        if entry.kind == .agent || entry.kind == .pairing || entry.kind == .note {
            fields["agent"] = currentAgentName ?? entry.displayAgentName
            if let agentID = entry.agentID { fields["agent_id"] = agentID }
        }
        if callback {
            if entry.status > 0 { fields["http"] = entry.status }
            if let result = callbackData?["__callback_result"] as? String { fields["result"] = result }
        }
        fields["params"] = entry.requestFacts?.params.flatMap {
            try? JSONSerialization.jsonObject(with: Data($0.utf8)) as? [String: Any]
        } ?? [:]
        if let noteData = entry.noteData, let data = try? JSONSerialization.jsonObject(with: Data(noteData.utf8)) as? [String: Any] {
            var publicData = data
            publicData.removeValue(forKey: "__callback"); publicData.removeValue(forKey: "__callback_result")
            fields["data"] = publicData
        } else if let reason = entry.requestFacts?.denialReason {
            fields["data"] = ["reason": reason]
        } else {
            fields["data"] = [String: Any]()
        }
        // Older rows keep their original operation and absent telemetry.
        fields["message"] = entry.operation
        self.fields = fields
    }

    public func text(for key: String) -> String {
        switch fields[key] {
        case let value as String: return value
        case let value as Int64: return String(value)
        case let value as Int: return String(value)
        case let value as [String: Any]:
            guard let data = try? JSONSerialization.data(withJSONObject: value, options: [.sortedKeys]) else { return "{}" }
            return String(decoding: data, as: UTF8.self)
        default: return "null"
        }
    }

    public func json() throws -> String {
        let data = try JSONSerialization.data(withJSONObject: fields, options: [.sortedKeys, .prettyPrinted])
        return String(decoding: data, as: UTF8.self)
    }
}

/// Compatibility view for owner setting changes: no HTTP request or control agent is implied.
public struct SettingActivityDetail {
    public static let keys = HostActivityDetail.keys
    private let detail: HostActivityDetail
    var fields: [String: Any] { detail.fields }
    public init?(_ entry: HostActivityEntry) {
        guard entry.isSetting else { return nil }
        detail = HostActivityDetail(entry)
    }
    public func text(for key: String) -> String { detail.text(for: key) }
    public func json() throws -> String { try detail.json() }
}
