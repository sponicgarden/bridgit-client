import Foundation

/// The row predicate declared on an applet's CSV share action.
public struct CSVRowFilter: Sendable, Equatable {
    private let column: String
    private let operation: String
    private let operand: JSONValue

    public init(_ declaration: JSONValue, columns: Set<String>) throws {
        guard let fields = declaration.fields, fields.count == 2,
              let column = fields["column"]?.text, columns.contains(column) || column == "at",
              let (operation, operand) = fields.first(where: { $0.key != "column" }) else {
            throw AppletRuntimeError.invalid("CSV filter needs one test on a declared column.")
        }
        switch (operation, operand) {
        case ("is", .string), ("is", .number), ("is", .bool),
             ("is_not", .string), ("is_not", .number), ("is_not", .bool),
             ("at_least", .number), ("below", .number), ("exists", .bool): break
        case ("in", .array(let values)) where (1...20).contains(values.count): break
        default: throw AppletRuntimeError.invalid("Invalid CSV filter test.")
        }
        self.column = column
        self.operation = operation
        self.operand = operand
    }

    public func includes(_ row: [String: JSONValue]) -> Bool {
        let value = row[column] ?? .null
        if operation == "exists" { return operand == .bool(value != .null) }
        guard value != .null else { return false }
        switch (operation, operand) {
        case ("is", _): return Self.same(value, operand)
        case ("is_not", _): return !Self.same(value, operand)
        case ("in", .array(let values)): return values.contains { Self.same(value, $0) }
        case ("at_least", .number(let threshold)): return Self.number(value).map { $0 >= threshold } ?? false
        case ("below", .number(let threshold)): return Self.number(value).map { $0 < threshold } ?? false
        default: return false
        }
    }

    private static func same(_ lhs: JSONValue, _ rhs: JSONValue) -> Bool {
        if case .number(let a) = lhs, case .number(let b) = rhs { return a == b }
        func text(_ value: JSONValue) -> String? {
            if case .string(let string) = value { return string }
            guard value != .null, let bytes = try? JSONEncoder().encode(value) else { return nil }
            return String(decoding: bytes, as: UTF8.self)
        }
        guard var a = text(lhs), var b = text(rhs) else { return false }
        if case .bool = lhs { return a.caseInsensitiveCompare(b) == .orderedSame }
        if case .bool = rhs { return a.caseInsensitiveCompare(b) == .orderedSame }
        a = a.trimmingCharacters(in: .whitespacesAndNewlines)
        b = b.trimmingCharacters(in: .whitespacesAndNewlines)
        return a.caseInsensitiveCompare(b) == .orderedSame
    }

    private static func number(_ value: JSONValue) -> Double? {
        if case .number(let n) = value { return n }
        guard let string = value.text else { return nil }
        let normalized = string.replacingOccurrences(of: #"[\p{Sc}\s\u00a0]"#, with: "", options: .regularExpression)
            .replacingOccurrences(of: #",(?=\d{3}(\D|$))"#, with: "", options: .regularExpression)
        guard normalized.range(of: #"^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$"#, options: .regularExpression) != nil,
              let n = Double(normalized), n.isFinite else { return nil }
        return n
    }
}
