import Foundation

/// Pure supported subset of Android FlowConditions. Host facts are rejected before installation/effects.
enum FlowCondition {
    static func validate(_ value: JSONValue, earlier: Set<String>? = nil, flow: Bool = false,
                         allowInputs: Bool = false, grouped: Bool = false) throws {
        guard let fields = value.fields else { throw AppletRuntimeError.invalid("Condition must be an object") }
        if let group = ["all", "any"].first(where: { fields[$0] != nil }) {
            guard !grouped, fields.count == 1, case .array(let children)? = fields[group], (2...8).contains(children.count) else {
                throw AppletRuntimeError.invalid("Condition groups need 2–8 tests and cannot be nested")
            }
            for child in children { try validate(child, earlier: earlier, flow: flow, allowInputs: allowInputs, grouped: true) }
            return
        }
        let sources = ["step", "trigger"].filter { fields[$0] != nil }
        guard sources.count == 1, let source = sources.first else { throw AppletRuntimeError.unavailable("condition source; supported sources are step and trigger") }
        guard let name = fields[source]?.text else { throw AppletRuntimeError.invalid("Condition source must be a field path") }
        let segments = name.split(separator: ".", omittingEmptySubsequences: false).map(String.init)
        guard !segments.isEmpty, segments.allSatisfy({ $0.range(of: "^[A-Za-z0-9_-]{1,40}$", options: .regularExpression) != nil }) else {
            throw AppletRuntimeError.invalid("Invalid condition path")
        }
        if source == "step" {
            guard !flow, segments.count >= 2, earlier == nil || earlier!.contains(segments[0]) else {
                throw AppletRuntimeError.invalid("Condition must name an earlier step; flow conditions cannot read steps")
            }
        }
        let tests = Set(fields.keys).subtracting([source])
        guard tests.count == 1, let test = tests.first, let expected = fields[test] else { throw AppletRuntimeError.invalid("Condition needs exactly one test") }
        guard ["exists", "is", "is_not", "in", "at_least", "below"].contains(test) else { throw AppletRuntimeError.invalid("Unsupported condition test") }
        if allowInputs, expected.fields?.count == 1, expected["$input"]?.text != nil { return }
        switch (test, expected) {
        case ("exists", .bool): return
        case ("is", .string), ("is", .bool), ("is_not", .string), ("is_not", .bool): return
        case ("is", .number(let number)), ("is_not", .number(let number)), ("at_least", .number(let number)), ("below", .number(let number)):
            guard number.isFinite else { throw AppletRuntimeError.invalid("Condition threshold must be finite") }
        case ("in", .array(let values)):
            guard (1...20).contains(values.count), values.allSatisfy({ value in
                switch value { case .string, .bool: return true; case .number(let number): return number.isFinite; default: return false }
            }) else { throw AppletRuntimeError.invalid("in requires 1–20 values") }
        default: throw AppletRuntimeError.invalid("Unsupported condition test or value type")
        }
    }
    static func matches(_ value: JSONValue, results: [String: JSONValue], trigger: JSONValue) throws -> Bool {
        let fields = value.fields!
        if case .array(let values)? = fields["all"] { return try values.allSatisfy { try matches($0, results: results, trigger: trigger) } }
        if case .array(let values)? = fields["any"] { return try values.contains { try matches($0, results: results, trigger: trigger) } }
        func path(_ value: JSONValue?, _ segments: [String]) -> JSONValue? {
            let result = segments.reduce(value) { $0?[$1] }
            return result == .null ? nil : result
        }
        let source: JSONValue?
        if let name = fields["step"]?.text {
            let parts = name.split(separator: ".").map(String.init)
            source = path(results[parts[0]], Array(parts.dropFirst()))
        } else { source = path(trigger, fields["trigger"]!.text!.split(separator: ".").map(String.init)) }
        if case .bool(let expected)? = fields["exists"] { return (source != nil) == expected }
        guard let source else { return false }
        switch source {
        case .string, .bool: break
        case .number(let number): guard number.isFinite else { return false }
        default: return false
        }
        if let expected = fields["is"] { return same(source, expected) }
        if let expected = fields["is_not"] { return !same(source, expected) }
        if case .array(let values)? = fields["in"] { return values.contains { same(source, $0) } }
        guard case .number(let actual) = source else { return false }
        if case .number(let threshold)? = fields["at_least"] { return actual >= threshold }
        if case .number(let threshold)? = fields["below"] { return actual < threshold }
        return false
    }
    private static func same(_ left: JSONValue, _ right: JSONValue) -> Bool {
        if case .number(let left) = left, case .number(let right) = right { return left == right }
        func text(_ value: JSONValue) -> String {
            switch value {
            case .string(let value): return value
            case .bool(let value): return value ? "true" : "false"
            case .number(let value):
                return value.isFinite && value.rounded() == value && abs(value) <= 9_007_199_254_740_991 ? String(Int64(value)) : String(value)
            default: return ""
            }
        }
        if case .bool = left { return text(left) == text(right) }
        if case .bool = right { return text(left) == text(right) }
        return text(left).trimmingCharacters(in: .whitespacesAndNewlines).caseInsensitiveCompare(text(right).trimmingCharacters(in: .whitespacesAndNewlines)) == .orderedSame
    }
}
