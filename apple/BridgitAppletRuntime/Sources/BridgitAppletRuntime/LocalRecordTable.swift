import Foundation

struct LocalRecordTable: Sendable {
    let id: String
    let columns: [String:JSONValue]
    let attributes: [String:JSONValue]
    let days: Int
    let limit: Int

    static func declarations(_ package: AppletPackage) throws -> [String:Self] {
        guard let definition = package.definition["processing"]?["tables"] else { return [:] }
        guard case .array(let list) = definition, list.count <= 3 else { throw AppletRuntimeError.invalid("Invalid table list") }
        var tables = [String:Self]()
        for entry in list {
            guard let attributes = entry.fields, let id = entry["id"]?.text, tables[id] == nil,
                  id.range(of:#"\A[a-z][a-z0-9_]{0,31}\z"#,options:.regularExpression) != nil,
                  let columns = entry["columns"]?.fields, (1...16).contains(columns.count),
                  columns.keys.allSatisfy({ !["id","at","by"].contains($0) && $0.range(of:#"\A[a-z][a-z0-9_]{0,31}\z"#,options:.regularExpression) != nil }) else {
                throw AppletRuntimeError.invalid("Invalid table declaration")
            }
            for (name,column) in columns where column["type"] == .string("json") {
                let description = column["description"]?.text?.trimmingCharacters(in:.whitespacesAndNewlines) ?? ""
                guard (1...1024).contains(description.utf16.count) else {
                    throw AppletRuntimeError.invalid("JSON column requires a description: " + name)
                }
            }
            func bound(_ field: String, _ fallback: Int, _ max: Int) throws -> Int {
                guard let value = entry[field] else { return fallback }
                guard case .number(let n) = value, n.rounded() == n, (1...Double(max)).contains(n) else {
                    throw AppletRuntimeError.invalid("Invalid table " + field)
                }
                return Int(n)
            }
            tables[id] = try Self(id:id, columns:columns, attributes:attributes.filter { $0.key != "columns" }, days:bound("keep_days",90,3650), limit:bound("max_rows",1000,10000))
        }
        return tables
    }

    private static func checkJSON(_ value: JSONValue, depth: Int) throws {
        guard depth <= 8 else { throw AppletRuntimeError.invalid("JSON column exceeds eight levels") }
        switch value {
        case .object(let fields):
            for child in fields.values { try checkJSON(child,depth:depth+1) }
        case .array(let elements):
            for child in elements { try checkJSON(child,depth:depth+1) }
        case .number(let n) where !n.isFinite:
            throw AppletRuntimeError.invalid("JSON column requires finite numbers")
        default: break
        }
    }

    func payload(_ row: [String:JSONValue]) throws -> Data {
        guard Set(row.keys).isSubset(of:columns.keys) else { throw AppletRuntimeError.invalid("Unknown table column") }
        var values = [String:JSONValue]()
        for (key,column) in columns {
            let value = row[key] ?? .null
            switch (column["type"]?.text, value) {
            case (_, .null), ("boolean",.bool), ("number",.number): break
            case ("json",.object), ("json",.array):
                try Self.checkJSON(value, depth:0)
            case ("integer",.number(let n)) where n.rounded() == n && abs(n) < 9e15: break
            case ("time",.string(let text)) where ISO8601DateFormatter().date(from:text) != nil: break
            case ("text",.object(let box)):
                let coordinates = ["x0","y0","x1","y1"].compactMap { key -> String? in
                    guard case .number(let n)? = box[key], (0...1).contains(n) else { return nil }
                    return String(format:"%.4f",locale:Locale(identifier:"en_US_POSIX"),n)
                }
                guard coordinates.count == 4 else { throw AppletRuntimeError.invalid("Expected normalized box for " + key) }
                values[key] = .string(coordinates.joined(separator:","))
                continue
            case ("text",.string(let text)) where text.utf16.count <= 1024: break
            case ("photo",.string(let ref)) where ref.range(of:#"\Akept:[a-z0-9]{1,40}\z"#, options:.regularExpression) != nil: break
            case ("choice",.string(let choice)):
                guard case .array(let options)? = column["options"], let match = options.compactMap(\.text).first(where:{
                    $0.caseInsensitiveCompare(choice.trimmingCharacters(in:.whitespacesAndNewlines)) == .orderedSame
                }) else { throw AppletRuntimeError.invalid("Unknown choice for " + key) }
                values[key] = .string(match)
                continue
            default: throw AppletRuntimeError.invalid("Unsupported value for table column " + key)
            }
            values[key] = value
        }
        let data = try JSONEncoder().encode(values)
        guard data.count <= 4096 else { throw AppletRuntimeError.invalid("Row exceeds 4 KiB") }
        return data
    }
}
