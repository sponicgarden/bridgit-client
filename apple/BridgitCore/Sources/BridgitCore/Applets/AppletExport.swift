import Foundation

public enum AppletExport {
    public static func groups(_ rows: [[String: Any]], by key: String) -> [(String, [[String: Any]])] {
        let grouped = Dictionary(grouping: rows) { ($0[key] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "Other" }
        return grouped.keys.sorted().map { ($0, grouped[$0]!) }
    }
    public static func total(_ rows: [[String: Any]], column: String) -> Double {
        rows.reduce(0) { sum, row in
            guard let n = row[column] as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID(), n.doubleValue.isFinite else { return sum }
            return sum + n.doubleValue
        }
    }
    public static func csv(rows: [[String: Any]], columns: [(String, String)]) -> Data {
        func cell(_ text: String, protectText: Bool = true) -> String {
            let looksExecutable = text.first.map { "=+-@\t\r".contains($0) } ?? false
            let isNumber = Double(text)?.isFinite == true
            let safe = protectText && looksExecutable && !isNumber ? "'" + text : text
            guard safe.rangeOfCharacter(from: CharacterSet(charactersIn: "\",\n\r")) != nil else { return safe }
            return "\"" + safe.replacingOccurrences(of: "\"", with: "\"\"") + "\""
        }

        let header = columns.map { cell($0.1) }.joined(separator: ",")
        let lines = rows.map { row in
            columns.map { column -> String in
                guard let value = row[column.0], !(value is NSNull) else { return "" }
                if let number = value as? NSNumber {
                    if CFGetTypeID(number) == CFBooleanGetTypeID() { return number.boolValue ? "Yes" : "No" }
                    return cell(number.stringValue, protectText: false)
                }
                return cell(value as? String ?? String(describing: value))
            }.joined(separator: ",")
        }
        return Data(("\u{FEFF}" + ([header] + lines).joined(separator: "\r\n") + "\r\n").utf8)
    }
}
