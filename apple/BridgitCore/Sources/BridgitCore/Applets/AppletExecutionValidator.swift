import CoreFoundation
import Foundation

/// This runtime is a subset of the portable format. Never accept execution declarations
/// that would silently change their meaning here. Recheck persisted packages before work
/// as well as imports, because older builds may already have saved unsupported controls.
enum AppletExecutionValidator {
    static let defaultPaidPerDay = 50
    static let maximumPaidPerDay = 500
    static let maximumRecordedItems = 50
    static func validate(_ package: [String: Any]) throws {
        _ = try AppletSpendingLimit.limits(for: package)
        try validateActions(package)
        try AppletCardValidator.validate(package)
        guard let value = package["processing"] else { return }
        guard let processing = value as? [String: Any] else { throw invalid("processing must be an object.") }
        try fields(processing, allowed: ["tables", "flows"], location: "processing")
        guard let value = processing["flows"] else { return }
        guard let flows = value as? [[String: Any]], flows.count <= 16 else { throw invalid("Use at most 16 flow objects.") }
        let tables = try AppletStore.tableDefinitions(package)
        let columns = Dictionary(uniqueKeysWithValues: tables.compactMap { table -> (String, [String: [String: Any]])? in
            guard let id = table["id"] as? String, let columns = table["columns"] as? [String: [String: Any]] else { return nil }
            return (id, columns)
        })
        let permissions = (package["permissions"] as? [String: Any])?["required"] as? [String] ?? []
        var ids = Set<String>()
        for flow in flows {
            try fields(flow, allowed: ["id", "name", "description", "max_paid_per_day", "trigger", "steps"], location: "flow")
            guard let id = flow["id"] as? String, !id.isEmpty, ids.insert(id).inserted,
                  let trigger = flow["trigger"] as? [String: Any], trigger["type"] as? String == "called",
                  let steps = flow["steps"] as? [[String: Any]], (1...32).contains(steps.count) else {
                throw DeviceAPIError(422, "unsupported_flow", "Use distinct called flows with 1–32 steps.")
            }
            try fields(trigger, allowed: ["type", "with"], location: "flow trigger")
            let types = try inputTypes(trigger["with"])
            if let cap = flow["max_paid_per_day"] { try whole(cap, range: 1...maximumPaidPerDay, field: "max_paid_per_day") }
            guard steps.filter({ $0["type"] as? String == "appraise" || (["hear", "speak"].contains($0["type"] as? String ?? "") && $0["allow_cloud"] as? Bool == true) }).count <= 4 else { throw invalid("Use at most four potentially paid model or speech steps per run.") }
            var stepIDs = Set<String>()
            for step in steps {
                guard let stepID = step["id"] as? String, !stepID.isEmpty, stepIDs.insert(stepID).inserted else { throw invalid("Each flow step needs a distinct ID.") }
                guard let type = step["type"] as? String, let arguments = stepFields[type] else { throw DeviceAPIError(422, "unsupported_step", "Unsupported flow step type.") }
                try fields(step, allowed: arguments.union(["id", "type"]), location: "step " + stepID)
                let previous = stepIDs.subtracting([stepID])
                let previousTypes = Dictionary(uniqueKeysWithValues: steps.compactMap { step -> (String, String)? in
                    guard let id = step["id"] as? String, previous.contains(id), let type = step["type"] as? String else { return nil }
                    return (id, type)
                })
                switch type {
                case "appraise":
                    guard permissions.contains("decisions"), operandType(step["photo"], inputs: types, steps: previousTypes) == "text" else { throw invalid("An appraise step needs a photo and declared decisions permission.") }
                case "record", "update":
                    guard let table = step["table"] as? String, let declared = columns[table],
                          let row = step[type == "record" ? "row" : "set"] as? [String: Any],
                          Set(row.keys).isSubset(of: Set(declared.keys)),
                          type != "update" || operandType(step["row"], inputs: types, steps: previousTypes) == "text" else { throw invalid("A record/update needs a declared table, columns and required row operands.") }
                    if let each = step["for_each"], operandType(each, inputs: types, steps: previousTypes) != "array" { throw invalid("record.for_each needs a list or a reference to a list result.") }
                    for (key, value) in row { try literal(value, column: declared[key]!) }
                case "hear": guard permissions.contains("mic") else { throw invalid("A hear step needs declared mic permission.") }
                case "speak": guard permissions.contains("speaker"), operandType(step["text"], inputs: types, steps: previousTypes) == "text" else { throw invalid("A speak step needs text and declared speaker permission.") }
                default: break
                }
                for (key, value) in step where !["id", "type", "table"].contains(key) {
                    try references(value, inputs: types, previous: previous, allowItem: type == "record" && step["for_each"] != nil)
                }
                if type == "appraise", let maximum = step["max_items"] { try whole(maximum, range: 1...30, field: "max_items") }
                if type == "hear", let seconds = step["duration_seconds"] { try whole(seconds, range: 1...30, field: "duration_seconds") }
                if let value = step["allow_cloud"] { guard let number = value as? NSNumber, CFGetTypeID(number) == CFBooleanGetTypeID() else { throw invalid("allow_cloud must be a boolean.") } }
                if let language = step["language"] { guard let string = language as? String, !string.isEmpty, string.count <= 64 else { throw invalid("language must be a language tag.") } }
            }
        }
    }
    private static func validateActions(_ package: [String: Any]) throws {
        let card = (package["ui"] as? [String: Any])?["card"] as? [String: Any] ?? [:]
        for piece in card["pieces"] as? [[String: Any]] ?? [] where piece["type"] as? String == "buttons" {
            for button in piece["items"] as? [[String: Any]] ?? [] {
                guard let action = button["do"] as? [String: Any] else { continue }
                for field in ["on_error", "retries"] where action.keys.contains(field) {
                    throw DeviceAPIError(422, "unsupported_execution_field", "This build does not support button.\(field).")
                }
                if action.keys.contains("only_if") {
                    guard action["flow"] != nil,
                          let condition = action["only_if"] as? [String: Any],
                          Set(condition.keys) == Set(["item", "is_not"]),
                          let column = condition["item"] as? String, !column.isEmpty,
                          condition["is_not"] is String else {
                        throw DeviceAPIError(422, "unsupported_execution_field", "This build only supports batch conditions with item and is_not strings.")
                    }
                }
                if action["flow"] != nil {
                    try fields(action, allowed: ["flow", "for_each", "only_if", "with", "max", "progress"], location: "batch action")
                    if let maximum = action["max"] { try whole(maximum, range: 1...200, field: "batch.max") }
                    if let progress = action["progress"] { guard let string = progress as? String, !string.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, string.count <= 24 else { throw invalid("Batch progress must be 1–24 characters.") } }
                    guard action["with"] == nil || action["with"] is [String: Any] else { throw invalid("Batch with must be an object.") }
                }
            }
        }
    }
    /// Android called-flow semantics: undeclared names and wrong types are refused;
    /// omitted or explicit-null inputs become null before any step can run.
    static func inputs(_ values: [String: Any], trigger: [String: Any]) throws -> [String: Any] {
        let types = try inputTypes(trigger["with"])
        guard Set(values.keys).isSubset(of: Set(types.keys)) else { throw DeviceAPIError(422, "bad_with", "with contains a value this flow does not declare.") }
        var result: [String: Any] = [:]
        for (key, type) in types {
            guard let value = values[key], !(value is NSNull) else { result[key] = NSNull(); continue }
            let valid: Bool
            switch type {
            case "text": valid = (value as? String).map { $0.utf16.count <= 4096 } ?? false
            case "number": valid = (value as? NSNumber).map { CFGetTypeID($0) != CFBooleanGetTypeID() && $0.doubleValue.isFinite } ?? false
            default: valid = (value as? NSNumber).map { CFGetTypeID($0) == CFBooleanGetTypeID() } ?? false
            }
            guard valid else { throw DeviceAPIError(422, "bad_with", "with." + key + " must match its declared " + type + " type.") }
            result[key] = value
        }
        let data = try JSONSerialization.data(withJSONObject: result)
        guard String(decoding: data, as: UTF8.self).utf16.count <= 5120 else { throw DeviceAPIError(422, "bad_with", "with is too large.") }
        return result
    }
    private static func operandType(_ value: Any?, inputs: [String: String], steps: [String: String]) -> String? {
        if let text = value as? String { return text.isEmpty ? nil : "text" }
        if value is [Any] { return "array" }
        guard let ref = value as? [String: String], ref.count == 1 else { return nil }
        if let path = ref["$trigger"] {
            let parts = path.split(separator: ".").map(String.init)
            return parts.count == 2 && parts[0] == "with" ? inputs[parts[1]] : nil
        }
        guard let path = ref["$step"] else { return nil }
        let parts = path.split(separator: ".").map(String.init)
        guard parts.count == 2, let type = steps[parts[0]] else { return nil }
        let fields: [String: [String: String]] = [
            "appraise": ["items": "array", "count": "number", "cost_usd": "number"],
            "record": ["row_ids": "array", "count": "number", "row_id": "text"],
            "update": ["row_id": "text"],
            "hear": ["transcript": "text", "model": "text", "processing": "text", "cost_usd": "number"],
            "speak": ["status": "text", "model": "text", "processing": "text", "cost_usd": "number"]
        ]
        return fields[type]?[parts[1]]
    }
    private static func references(_ value: Any, inputs: [String: String], previous: Set<String>, allowItem: Bool, depth: Int = 0) throws {
        guard depth < 32 else { throw invalid("References are too deeply nested.") }
        if let object = value as? [String: Any] {
            let refs = object.keys.filter { $0.hasPrefix("$") }
            if !refs.isEmpty {
                guard object.count == 1, let kind = refs.first, let path = object[kind] as? String else { throw invalid("Use one exact reference per object.") }
                let parts = path.split(separator: ".", omittingEmptySubsequences: false).map(String.init)
                guard !parts.contains("") else { throw invalid("A reference path cannot be empty.") }
                switch kind {
                case "$trigger": guard parts.count == 2, parts[0] == "with", inputs[parts[1]] != nil else { throw invalid("A trigger reference must name a declared input.") }
                case "$step": guard let id = parts.first, previous.contains(id) else { throw invalid("A step reference must name an earlier step.") }
                case "$item": guard allowItem else { throw invalid("Item references need record.for_each.") }
                default: throw invalid("This reference is not implemented.")
                }
            } else { for item in object.values { try references(item, inputs: inputs, previous: previous, allowItem: allowItem, depth: depth + 1) } }
        } else if let list = value as? [Any] { for item in list { try references(item, inputs: inputs, previous: previous, allowItem: allowItem, depth: depth + 1) } }
    }
    private static func literal(_ value: Any, column: [String: Any]) throws {
        if value is NSNull { return }
        if let object = value as? [String: Any], object.keys.contains(where: { $0.hasPrefix("$") }) { return }
        let valid: Bool
        switch column["type"] as? String {
        case "number", "integer": valid = (value as? NSNumber).map { CFGetTypeID($0) != CFBooleanGetTypeID() && $0.doubleValue.isFinite && (column["type"] as? String != "integer" || $0.doubleValue.rounded() == $0.doubleValue) } ?? false
        case "boolean": valid = (value as? NSNumber).map { CFGetTypeID($0) == CFBooleanGetTypeID() } ?? false
        case "choice": valid = (value as? String).map { text in (column["options"] as? [String] ?? []).contains { $0.caseInsensitiveCompare(text) == .orderedSame } } ?? false
        case "photo": valid = value is String
        case "date", "time": valid = value is String || (value as? NSNumber).map { CFGetTypeID($0) != CFBooleanGetTypeID() && $0.doubleValue.isFinite } == true
        default: valid = (value as? String)?.count ?? ((try? JSONSerialization.data(withJSONObject: value, options: [.fragmentsAllowed]).count) ?? Int.max) <= 1024
        }
        guard valid else { throw invalid("A literal row value does not match its declared column.") }
    }
    private static func inputTypes(_ value: Any?) throws -> [String: String] {
        guard let value else { return [:] }
        guard let types = value as? [String: String], types.count <= 8,
              types.allSatisfy({ key, type in key.range(of: "^[a-z][a-z0-9_]{0,31}$", options: .regularExpression) != nil && ["text", "number", "boolean"].contains(type) }) else { throw invalid("Called trigger.with takes up to eight named text, number or boolean inputs.") }
        return types
    }
    private static func whole(_ value: Any, range: ClosedRange<Int>, field: String) throws {
        guard let number = RelayHostConnection.integer(value), let integer = Int(exactly: number), range.contains(integer) else { throw invalid(field + " must be an integer in " + String(range.lowerBound) + "–" + String(range.upperBound) + ".") }
    }
    private static let stepFields: [String: Set<String>] = [
        "appraise": ["photo", "max_items"],
        "record": ["table", "for_each", "row"],
        "update": ["table", "row", "set"],
        "hear": ["duration_seconds", "language", "allow_cloud"],
        "speak": ["text", "language", "allow_cloud"]
    ]
    private static func fields(_ object: [String: Any], allowed: Set<String>, location: String) throws {
        if let key = Set(object.keys).subtracting(allowed).sorted().first {
            throw DeviceAPIError(422, "unsupported_execution_field", "This build does not support \(location).\(key).")
        }
    }
    private static func invalid(_ message: String) -> DeviceAPIError { DeviceAPIError(422, "invalid_applet", message) }
}
