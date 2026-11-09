import Foundation

public struct PhotoImportOptions {
    public let data: Data
    public let table: String
    public let column: String
    public let row: [String: Any]
    public init(body: [String: Any], query: [String: Any]) throws {
        guard query.isEmpty, Set(body.keys).isSubset(of: ["data_base64", "table", "column", "row"]),
              let encoded = body["data_base64"] as? String, encoded.utf8.count <= 1_024_000,
              let data = Data(base64Encoded: encoded), !data.isEmpty,
              body["table"] == nil || body["table"] is String,
              body["column"] == nil || body["column"] is String,
              body["row"] == nil || body["row"] is [String: Any] else { throw DeviceAPIError(422, "invalid_photo", "Supply a bounded photo, optional table/column names and an initial row object.") }
        let table = body["table"] as? String ?? "photos", column = body["column"] as? String ?? "photo"
        guard [table, column].allSatisfy({ $0.range(of: "^[a-z][a-z0-9_]{0,31}$", options: .regularExpression) != nil }) else { throw DeviceAPIError(422, "invalid_photo", "Use declared table and photo-column IDs.") }
        self.data = data; self.table = table; self.column = column; row = body["row"] as? [String: Any] ?? [:]
    }
    public static let schema: [String: Any] = ["type": "object", "required": ["data_base64"], "additionalProperties": false, "properties": ["data_base64": ["type": "string", "minLength": 4, "maxLength": 1_024_000, "contentEncoding": "base64"], "table": ["type": "string", "pattern": "^[a-z][a-z0-9_]{0,31}$"], "column": ["type": "string", "pattern": "^[a-z][a-z0-9_]{0,31}$"], "row": ["type": "object"]]]
}
