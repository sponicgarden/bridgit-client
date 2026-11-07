import Foundation

extension AppletPackage {
    /// Keep installed values; a new default fills only a previously absent top-level input.
    func configuration(preserving old: [String: JSONValue]) throws -> [String: JSONValue] {
        guard let schema = definition["config_schema"], schema["type"]?.text == "object" else {
            throw AppletRuntimeError.invalid("Missing package configuration schema")
        }
        let result = defaults.merging(old) { _, existing in existing }
        try Self.checkValue(.object(result), schema, "$")
        return result
    }

    private static func checkValue(_ value: JSONValue, _ schema: JSONValue, _ path: String) throws {
        func invalid(_ reason: String) -> AppletRuntimeError { .invalid("Configuration " + path + ": " + reason) }
        func strings(_ node: JSONValue?) -> [String] {
            if let s = node?.text { return [s] }
            if case .array(let a)? = node { return a.compactMap(\.text) }
            return []
        }
        func bound(_ field: String, _ fallback: Double) throws -> Double {
            guard let field = schema[field] else { return fallback }
            guard case .number(let n) = field else { throw invalid("invalid schema bound") }
            return n
        }
        func within(_ n: Double, minimum: String, maximum: String, defaultMax: Double = .infinity) throws {
            let low = try bound(minimum, minimum == "minimum" ? -.infinity : 0)
            let high = try bound(maximum, defaultMax)
            guard n >= low && n <= high else { throw invalid("outside allowed bounds") }
        }
        let types = strings(schema["type"])
        let type: String
        switch value {
        case .null: type = "null"
        case .bool: type = "boolean"
        case .string: type = "string"
        case .number(let n): type = n.rounded() == n && types.contains("integer") ? "integer" : "number"
        case .array: type = "array"
        case .object: type = "object"
        }
        guard types.contains(type) else { throw invalid("expected " + types.joined(separator: " or ")) }
        if case .array(let options)? = schema["enum"], !options.contains(value) { throw invalid("outside allowed choices") }
        switch value {
        case .number(let n): try within(n, minimum:"minimum", maximum:"maximum")
        case .string(let s):
            try within(Double(s.utf16.count), minimum:"minLength", maximum:"maxLength", defaultMax:100000)
            if schema["format"]?.text == "timezone", TimeZone(identifier:s) == nil { throw invalid("unknown time zone") }
        case .array(let list):
            try within(Double(list.count), minimum:"minItems", maximum:"maxItems", defaultMax:1000)
            guard let items = schema["items"] else { throw invalid("missing array item schema") }
            for (index,item) in list.enumerated() { try checkValue(item, items, path + "[\(index)]") }
        case .object(let fields):
            guard schema["additionalProperties"] == .bool(false), let props = schema["properties"]?.fields else { throw invalid("expected a closed object schema") }
            for key in strings(schema["required"]) where fields[key] == nil { throw invalid("missing " + key) }
            for (key,item) in fields {
                guard let prop = props[key] else { throw invalid("unrecognized field " + key) }
                try checkValue(item, prop, path + "." + key)
            }
        default: break
        }
    }
}
