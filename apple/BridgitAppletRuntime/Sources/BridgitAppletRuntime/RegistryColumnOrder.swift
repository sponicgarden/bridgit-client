import Foundation

/// Presentation metadata from the original response, before canonicalization sorts keys.
/// Canonical package bytes remain the authority for release identity and validation.
enum RegistryColumnOrder {
    static func read(_ response: Data) throws -> [String: [String]] {
        var parser = Parser(bytes: Array(response))
        let root = try parser.value(depth: 0)
        guard case .array(let tables)? = root.field("definition")?.field("processing")?.field("tables") else { return [:] }
        var result: [String: [String]] = [:]
        for table in tables {
            guard case .string(let id)? = table.field("id"),
                  case .object(let columns)? = table.field("columns") else { continue }
            result[id] = columns.map(\.0)
        }
        return result
    }

    private indirect enum Node {
        case object([(String, Node)]), array([Node]), string(String), scalar
        func field(_ key: String) -> Node? {
            guard case .object(let pairs) = self else { return nil }
            return pairs.first { $0.0 == key }?.1
        }
    }

    /// The caller has already validated JSON with Foundation. This scanner only
    /// retains member order, including escaped names; it does not interpret numbers.
    private struct Parser {
        let bytes: [UInt8]
        var index = 0
        mutating func whitespace() {
            while index < bytes.count && [9, 10, 13, 32].contains(bytes[index]) { index += 1 }
        }
        mutating func consume(_ byte: UInt8) throws {
            whitespace()
            guard index < bytes.count, bytes[index] == byte else { throw invalid() }
            index += 1
        }
        func invalid() -> AppletRuntimeError { .invalid("Invalid registry presentation metadata") }
        mutating func string() throws -> String {
            whitespace()
            let start = index
            try consume(34)
            while index < bytes.count {
                let byte = bytes[index]
                index += 1
                if byte == 92 {
                    guard index < bytes.count else { throw invalid() }
                    index += 1
                } else if byte == 34 {
                    return try JSONDecoder().decode(String.self, from: Data(bytes[start..<index]))
                }
            }
            throw invalid()
        }
        mutating func value(depth: Int) throws -> Node {
            whitespace()
            guard depth < 128, index < bytes.count else { throw invalid() }
            switch bytes[index] {
            case 34: return .string(try string())
            case 123:
                index += 1
                var pairs: [(String, Node)] = []
                var keys = Set<String>()
                whitespace()
                if index < bytes.count, bytes[index] == 125 { index += 1; return .object(pairs) }
                while true {
                    let key = try string()
                    guard keys.insert(key).inserted else { throw invalid() }
                    try consume(58)
                    pairs.append((key, try value(depth: depth + 1)))
                    whitespace()
                    if index < bytes.count, bytes[index] == 125 { index += 1; return .object(pairs) }
                    try consume(44)
                }
            case 91:
                index += 1
                var values: [Node] = []
                whitespace()
                if index < bytes.count, bytes[index] == 93 { index += 1; return .array(values) }
                while true {
                    values.append(try value(depth: depth + 1))
                    whitespace()
                    if index < bytes.count, bytes[index] == 93 { index += 1; return .array(values) }
                    try consume(44)
                }
            default:
                let start = index
                while index < bytes.count && ![9, 10, 13, 32, 44, 93, 125].contains(bytes[index]) { index += 1 }
                guard index > start else { throw invalid() }
                return .scalar
            }
        }
    }
}
