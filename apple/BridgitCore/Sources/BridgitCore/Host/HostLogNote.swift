import Foundation

public enum HostLogLevel: String, CaseIterable, Codable, Sendable {
    case debug, info, warn, error
}

/// An authenticated LOG-11 note. The persistence boundary accepts only this redacted form.
struct HostLogNote {
    static let messageBytes = 1_024
    static let dataBytes = 4_096
    static let limit = 60
    static let fields: Set<String> = ["message", "level", "data"]
    static let keyPattern = "^[A-Za-z0-9_-]{8,128}$"
    let message: String
    let level: HostLogLevel
    let data: String

    init(_ request: HostRequest) throws {
        guard let message = request.body["message"] as? String,
              !message.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              message.utf8.count <= Self.messageBytes else {
            throw DeviceAPIError(422, "invalid_parameter", "Supply a nonblank message of at most 1024 UTF-8 bytes.")
        }
        if let raw = request.body["level"] {
            guard let text = raw as? String, let level = HostLogLevel(rawValue: text) else {
                throw DeviceAPIError(422, "invalid_parameter", "level must be debug, info, warn or error.")
            }
            self.level = level
        } else { level = .info }
        let fields: [String: Any]
        if let raw = request.body["data"] {
            guard let value = raw as? [String: Any], JSONSerialization.isValidJSONObject(value) else {
                throw DeviceAPIError(422, "invalid_parameter", "data must be a JSON object.")
            }
            fields = value
        } else { fields = [:] }
        let raw = try Self.encode(fields)
        guard raw.count <= Self.dataBytes else { throw DeviceAPIError(422, "invalid_parameter", "data is limited to 4096 UTF-8 bytes of JSON.") }
        self.message = Self.scrub(message, max: Self.messageBytes)
        data = try Self.encodedRedacted(fields)
    }

    static func encodedRedacted(_ fields: [String: Any]) throws -> String {
        let redacted = try encode(redact(fields, depth: 0))
        let bounded = redacted.count <= dataBytes ? redacted : try encode(["truncated": true, "size_bytes": redacted.count])
        return String(decoding: bounded, as: UTF8.self)
    }

    private static let key = try! NSRegularExpression(pattern: "(brg_|rly_|bgk_)[A-Za-z0-9_-]{16,200}|(whsec_)[A-Za-z0-9+/]{43}=?")
    private static let bearer = try! NSRegularExpression(pattern: "(?i)\\bbearer [A-Za-z0-9._~+/=-]{8,}")
    private static let secretName = try! NSRegularExpression(pattern: "(?i)key|token|secret|password|passwd|authorization|bearer|cookie|code|signature")
    private static let mediaName = try! NSRegularExpression(pattern: "(?i)data_base64|base64|bytes|html|body|text|answer|clipboard")

    static func scrub(_ input: String, max: Int) -> String {
        let plain = input.replacingOccurrences(of: "\0", with: "�")
        let keysRemoved = key.stringByReplacingMatches(in: plain, range: NSRange(plain.startIndex..., in: plain), withTemplate: "$1$2[redacted]")
        let safe = bearer.stringByReplacingMatches(in: keysRemoved, range: NSRange(keysRemoved.startIndex..., in: keysRemoved), withTemplate: "Bearer [redacted]")
        guard safe.utf16.count > max else { return safe }
        // Android caps UTF-16 units. Keep the suffix within that budget without splitting a surrogate.
        return utf16Prefix(safe, max: max - 1) + "…"
    }

    static func utf16Prefix(_ text: String, max: Int) -> String {
        var units = Array(text.utf16.prefix(max))
        if let last = units.last, (0xD800...0xDBFF).contains(last) { units.removeLast() }
        return String(decoding: units, as: UTF16.self)
    }

    private static func redact(_ fields: [String: Any], depth: Int) -> [String: Any] {
        var result: [String: Any] = [:]
        for key in fields.keys.sorted().prefix(40) {
            guard let value = fields[key] else { continue }
            let field = scrub(key, max: 40)
            let range = NSRange(key.startIndex..., in: key)
            if secretName.firstMatch(in: key, range: range) != nil, key != "idempotency" { result[field] = "[redacted]" }
            else if mediaName.firstMatch(in: key, range: range) != nil, let text = value as? String { result[field] = "[\(text.utf16.count) chars]" }
            else if let object = value as? [String: Any] { result[field] = depth == 0 ? redact(object, depth: 1) as Any : "[object]" }
            else if let list = value as? [Any] { result[field] = list.count <= 20 ? list.map { scalar($0, array: true) } as Any : "[\(list.count) items]" }
            else { result[field] = scalar(value, array: false) }
        }
        if fields.count > 40 { result["…"] = "\(fields.count - 40) more" }
        return result
    }

    private static func scalar(_ value: Any, array: Bool) -> Any {
        if let text = value as? String { return scrub(text, max: 200) }
        if let number = value as? NSNumber { return number }
        return array ? "[item]" as Any : NSNull()
    }

    private static func encode(_ object: [String: Any]) throws -> Data {
        try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys, .withoutEscapingSlashes])
    }
}
