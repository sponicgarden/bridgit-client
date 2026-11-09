import Foundation

/// The current cross-platform discovery shape; operation availability remains explicit.
public enum AppletDataContract {
    public static let version = "bridgit-applet-data/2"
    public static func make(snapshot: [String: Any], applet: String, includeRows: Bool, mediaAllowed: Bool, batches: [String: Any] = [:]) -> [String: Any] {
        let package = snapshot["package"] as? [String: Any] ?? [:], base = "/v1/applets/installed/" + applet
        var references = Set<String>()
        let tables = (snapshot["tables"] as? [[String: Any]] ?? []).map { definition -> [String: Any] in
            let id = definition["id"] as? String ?? "", columns = definition["columns"] as? [String: [String: Any]] ?? [:]
            let rows = definition["rows"] as? [[String: Any]] ?? []
            var choices: [String: Any] = [:]
            for (column, schema) in columns {
                if schema["type"] as? String == "photo" { for row in rows { if let ref = row[column] as? String { references.insert(ref) } } }
                if schema["type"] as? String == "choice" {
                    choices[column] = ["values": Dictionary(uniqueKeysWithValues: (schema["options"] as? [String] ?? []).map { option in (option, rows.filter { $0[column] as? String == option }.count) }), "null_count": rows.filter { $0[column] == nil || $0[column] is NSNull }.count]
                }
            }
            var value: [String: Any] = ["id": id, "label": definition["label"] ?? id, "columns": columns, "count": rows.count, "choice_counts": choices, "rows_url": base + "/tables/" + id + "/rows", "cursor_parameter": "since", "limit_max": 200]
            if includeRows { value["rows"] = rows }
            return value
        }
        let media = (snapshot["photos"] as? [[String: Any]] ?? []).filter { references.contains($0["ref"] as? String ?? "") }
        let photos = mediaAllowed ? media.map { photo -> [String: Any] in
            var value = photo; value["url"] = base + "/photos/" + String((photo["ref"] as? String ?? "").dropFirst(5))
            value["chunk_bytes"] = 2 * 1024 * 1024; value["chunk_parameters"] = "offset,length"
            return value
        } : []
        return ["contract": version, "format": version, "instance_id": applet, "package_id": package["id"] ?? applet, "name": package["name"] ?? applet, "version": package["version"] ?? "", "generated_at": Int64(Date().timeIntervalSince1970 * 1000), "consistency": "atomic saved-table snapshot; media availability may change afterward", "access": "explicit_applet_scope", "complete": true, "excluded_tables": [], "tables": tables, "photos": photos, "media_complete": references.isEmpty || (mediaAllowed && references.count == media.count && media.allSatisfy { $0["available"] as? Bool == true }), "media_read_grant_required": !mediaAllowed && !references.isEmpty, "export_url": base + "/data/export", "archive_url": NSNull(), "native_sources": [:], "records": ["count": 0, "supported": false], "batches": batches, "row_metadata": ["id": "stable row ID", "at": "creation time, milliseconds", "by": "write provenance"], "next": "Follow each table's rows_url and pass next as since until null. Photo reads need the separate applets.media.read grant. Existing-data reads do not capture or run paid work."]
    }
}
