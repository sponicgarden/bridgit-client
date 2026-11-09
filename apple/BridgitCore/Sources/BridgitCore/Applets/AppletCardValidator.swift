import CoreFoundation
import Foundation

/// Preflight the native card dialect. Unknown executable/presentation declarations fail
/// before install rather than becoming inert controls or misleading exported data.
enum AppletCardValidator {
    static func validate(_ package: [String: Any]) throws {
        guard let uiValue = package["ui"] else { return }
        guard let ui = uiValue as? [String: Any] else { throw invalid("ui must be an object.") }
        guard let value = ui["card"] else { return }
        guard let card = value as? [String: Any] else { throw invalid("card must be an object.") }
        if card["asset"] != nil {
            throw DeviceAPIError(422, "unsupported_execution_field", "This applet needs a bundled card file. This version of Bridgit can display applet cards built from native controls.")
        }
        try fields(card, ["title", "sources", "states", "pieces"])
        if let title = card["title"] { try text(title, maximum: 80) }
        if let value = card["states"] {
            guard let states = value as? [String: Any], Set(states.keys).isSubset(of: ["empty", "loading", "error", "paused"]) else { throw unsupported("card state") }
            for value in states.values { try text(value, maximum: 2048) }
        }
        let tables = try AppletStore.tableDefinitions(package)
        let definitions = Dictionary(uniqueKeysWithValues: tables.compactMap { table -> (String, [String: [String: Any]])? in
            guard let id = table["id"] as? String, let columns = table["columns"] as? [String: [String: Any]] else { return nil }
            return (id, columns)
        })
        guard card["sources"] == nil || card["sources"] is [String: [String: Any]], card["pieces"] == nil || card["pieces"] is [[String: Any]] else { throw invalid("Use card sources and piece objects.") }
        let sources = card["sources"] as? [String: [String: Any]] ?? [:]
        for source in sources.values {
            try fields(source, ["table", "limit", "batch"])
            guard (source["table"] != nil) != (source["batch"] != nil) else { throw invalid("A source names one table or batch.") }
            if let table = source["table"] { guard let id = table as? String, definitions[id] != nil else { throw invalid("A source must name a declared table.") } }
            if let batch = source["batch"] { guard batch is String, source["limit"] == nil else { throw invalid("A batch source names a button.") } }
            try limit(source["limit"])
        }
        func sourceColumns(_ ref: Any?) throws -> [String: [String: Any]] {
            guard let reference = ref as? [String: String], reference.count == 1, let id = reference["$source"], let table = sources[id]?["table"] as? String, let columns = definitions[table] else { throw invalid("Use an exact reference to a declared table source.") }
            return columns
        }
        var buttonIDs = Set<String>()
        let pieces = card["pieces"] as? [[String: Any]] ?? []
        for piece in pieces {
            guard let type = piece["type"] as? String, let allowed = pieceFields[type] else { throw unsupported("card piece") }
            try fields(piece, allowed.union(["type"]))
            try limit(piece["limit"])
            for field in ["text", "eyebrow", "empty"] { if let value = piece[field] { try text(value, maximum: 2048) } }
            if ["header", "note"].contains(type), piece["text"] == nil { throw invalid("A text piece needs text.") }
            switch type {
            case "table":
                let columns = try sourceColumns(piece["rows"])
                try columnList(piece["columns"], columns)
                try columnNames(piece["totals"], columns, numeric: true)
                if let group = piece["group_by"] { try column(group, columns) }
                for flag in ["owner_edit", "owner_delete"] { if let value = piece[flag] { try boolean(value) } }
                if let marks = piece["marks"] { guard let object = marks as? [String: [String: String]], object.keys.allSatisfy({ columns[$0] != nil }) else { throw invalid("Marks must name declared columns and text labels.") } }
                if let value = piece["photo"] {
                    guard let photo = value as? [String: Any] else { throw invalid("photo must name a column.") }
                    try fields(photo, ["column", "box"])
                    guard let key = photo["column"] as? String, columns[key]?["type"] as? String == "photo" else { throw invalid("photo must name a photo column.") }
                    if let box = photo["box"] { try column(box, columns) }
                }
            case "gallery":
                let columns = try sourceColumns(piece["photos"])
                for key in ["photo", "caption", "caption_count"] { if let name = piece[key] { try column(name, columns) } }
                if let words = piece["words"] { guard words is [String: String] else { throw invalid("Gallery words must be text labels.") } }
            case "buttons":
                guard let buttons = piece["items"] as? [[String: Any]], buttons.count <= 16 else { throw invalid("Use up to 16 button objects.") }
                for button in buttons {
                    try fields(button, ["id", "label", "style", "do"])
                    guard let id = button["id"] as? String, !id.isEmpty, buttonIDs.insert(id).inserted,
                          let action = button["do"] as? [String: Any], ["camera", "flow", "step"].filter({ action[$0] != nil }).count == 1 else { throw invalid("Each button needs a distinct ID and one supported action.") }
                    if let label = button["label"] { try text(label, maximum: 80) }
                    if let style = button["style"] { guard ["primary", "plain"].contains(style as? String ?? "") else { throw unsupported("button style") } }
                    if let value = action["camera"] {
                        try fields(action, ["camera"])
                        guard let camera = value as? [String: Any], let table = camera["table"] as? String, let columns = definitions[table], let photo = camera["photo"] as? String, columns[photo]?["type"] as? String == "photo" else { throw invalid("Camera must name a declared photo column.") }
                        try fields(camera, ["table", "photo", "row", "size", "lens"])
                        guard camera["row"] == nil || camera["row"] is [String: Any], camera["size"] == nil || camera["size"] as? String == "large", camera["lens"] == nil || camera["lens"] as? String == "back" else { throw unsupported("camera selector or size") }
                    } else if let value = action["step"] {
                        try fields(action, ["step"])
                        guard let step = value as? [String: Any], step["type"] as? String == "share", step["as"] as? String == "csv", let table = step["table"] as? String, let columns = definitions[table] else { throw unsupported("share action; use CSV or the saved-data API") }
                        try fields(step, ["type", "as", "table", "title", "columns", "group_by"])
                        try columnList(step["columns"], columns)
                        if let group = step["group_by"] { try column(group, columns) }
                    } else {
                        guard let flow = action["flow"] as? [String: String], flow.count == 1, let id = flow["$flow"], ((package["processing"] as? [String: Any])?["flows"] as? [[String: Any]] ?? []).contains(where: { $0["id"] as? String == id }) else { throw invalid("A button must name a declared flow.") }
                        _ = try sourceColumns(action["for_each"])
                    }
                }
            default: break
            }
        }
        for source in sources.values { if let batch = source["batch"] as? String, !buttonIDs.contains(batch) { throw invalid("A batch source must name a declared button.") } }
    }
    private static let pieceFields: [String: Set<String>] = [
        "header": ["text", "eyebrow"], "note": ["text"], "buttons": ["items"],
        "gallery": ["photos", "photo", "caption", "words", "caption_count", "limit", "empty"],
        "table": ["rows", "columns", "group_by", "totals", "marks", "photo", "owner_edit", "owner_delete", "limit", "empty"]
    ]
    private static func column(_ value: Any, _ columns: [String: [String: Any]]) throws {
        guard let id = value as? String, id == "id" || id == "at" || columns[id] != nil else { throw invalid("Name a declared column.") }
    }
    private static func columnNames(_ value: Any?, _ columns: [String: [String: Any]], numeric: Bool = false) throws {
        guard let value else { return }
        guard let names = value as? [String], Set(names).count == names.count, names.count <= 16 else { throw invalid("Use distinct declared columns.") }
        for name in names { try column(name, columns); if numeric && !["number", "integer"].contains(columns[name]?["type"] as? String ?? "") { throw invalid("Totals need number columns.") } }
    }
    private static func columnList(_ value: Any?, _ columns: [String: [String: Any]]) throws {
        guard let value else { return }
        guard let values = value as? [Any], (1...16).contains(values.count) else { throw invalid("Use 1–16 columns.") }
        var seen = Set<String>()
        for value in values {
            let key: String
            if let name = value as? String { key = name }
            else if let object = value as? [String: Any], let name = object["key"] as? String {
                key = name; try fields(object, ["key", "label", "format", "currency"])
                if let format = object["format"] { guard ["text", "number", "money", "yes_no", "date", "time"].contains(format as? String ?? "") else { throw unsupported("column format") } }
                if let currency = object["currency"] { guard let code = currency as? String, code.range(of: "^[A-Z]{3}$", options: .regularExpression) != nil else { throw invalid("Use a three-letter currency code.") } }
            } else { throw invalid("Use column IDs or column objects.") }
            try column(key, columns); guard seen.insert(key).inserted else { throw invalid("Use distinct columns.") }
        }
    }
    private static func text(_ value: Any, maximum: Int) throws { guard let value = value as? String, !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, value.count <= maximum else { throw unsupported("dynamic, empty or oversized presentation text") } }
    private static func boolean(_ value: Any) throws { guard let n = value as? NSNumber, CFGetTypeID(n) == CFBooleanGetTypeID() else { throw invalid("Use a boolean flag.") } }
    private static func limit(_ value: Any?) throws { if let value { guard let n = RelayHostConnection.integer(value), (1...10000).contains(n) else { throw invalid("Use a limit from 1 to 10000.") } } }
    private static func fields(_ object: [String: Any], _ allowed: Set<String>) throws { if let key = object.keys.sorted().first(where: { !allowed.contains($0) }) { throw unsupported(key) } }
    private static func unsupported(_ field: String) -> DeviceAPIError { .init(422, "unsupported_execution_field", "This build does not support " + field + ".") }
    private static func invalid(_ text: String) -> DeviceAPIError { .init(422, "invalid_applet", text) }
}
