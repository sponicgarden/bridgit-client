import CoreFoundation
import Foundation

/// Shared declared-column semantics for native cards and saved-data exports.
public enum AppletPresentation {
    public struct Column: Identifiable {
        public var key: String
        public var label: String
        public var format: String?
        public var currency: String?
        public var id: String { key }
        public init(key: String, label: String, format: String? = nil, currency: String? = nil) {
            self.key = key; self.label = label; self.format = format; self.currency = currency
        }
        public func text(_ value: Any?) -> String {
            guard let value, !(value is NSNull) else { return "—" }
            if format == "money", let n = value as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID() {
                let formatter = NumberFormatter(); formatter.numberStyle = .currency; formatter.currencyCode = currency ?? "USD"
                return formatter.string(from: n) ?? n.stringValue
            }
            if let n = value as? NSNumber { return CFGetTypeID(n) == CFBooleanGetTypeID() ? (n.boolValue ? "Yes" : "No") : n.stringValue }
            if let text = value as? String { return text }
            if JSONSerialization.isValidJSONObject(value), let data = try? JSONSerialization.data(withJSONObject: value, options: [.sortedKeys]), let text = String(data: data, encoding: .utf8) { return text }
            return String(describing: value)
        }
    }
    public static func columns(_ declaration: Any?, definitions: [String: [String: Any]]) -> [Column] {
        let values = declaration as? [Any] ?? definitions.keys.sorted().filter { definitions[$0]?["type"] as? String != "photo" }.map { $0 as Any }
        return values.compactMap { value in
            let object = value as? [String: Any]
            guard let key = value as? String ?? object?["key"] as? String else { return nil }
            return Column(key: key, label: object?["label"] as? String ?? definitions[key]?["label"] as? String ?? key,
                          format: object?["format"] as? String, currency: object?["currency"] as? String)
        }
    }
    /// One column contract for owner and authenticated-agent table exports.
    /// Preserve registry order, with a deterministic fallback for older declarations.
    public static func csvColumns(order: [String], definitions: [String: [String: Any]]) -> [Column] {
        var seen = Set<String>()
        let declared = order.filter { definitions[$0] != nil && seen.insert($0).inserted }
        let ordered = declared + definitions.keys.filter { !seen.contains($0) }.sorted()
        let plainTypes: Set<String> = ["text", "number", "integer", "boolean", "time", "date", "choice"]
        return ordered.compactMap { key in
            guard let field = definitions[key], let type = field["type"] as? String,
                  plainTypes.contains(type) else { return nil }
            return Column(key: key, label: field["label"] as? String ?? key)
        }
    }
    public static func groups(_ rows: [[String: Any]], by key: String?) -> [(String, [[String: Any]])] {
        guard let key else { return [("", rows)] }
        return AppletExport.groups(rows, by: key)
    }
    public static func totals(_ rows: [[String: Any]], keys: [String], columns: [Column]) -> String {
        keys.map { key in
            let column = columns.first { $0.key == key } ?? Column(key: key, label: key)
            return column.label + " " + column.text(AppletExport.total(rows, column: key))
        }.joined(separator: " · ")
    }
}
