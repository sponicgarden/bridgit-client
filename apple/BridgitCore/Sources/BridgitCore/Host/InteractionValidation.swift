import Foundation
import CoreFoundation

/// Validate the implemented inbox contract before writing anything. Unsupported delivery
/// features fail explicitly rather than silently dropping urgency, speech or workflow controls.
enum InteractionValidation {
    static func create(_ input: [String: Any], now: Date) throws -> [String: Any] {
        guard try JSONSerialization.data(withJSONObject: input).count <= 300 * 1024 else { throw DeviceAPIError(413, "interaction_too_large", "Use an interaction below 300 KB.") }
        let allowed: Set<String> = ["kind", "title", "text", "html", "controls", "priority", "speak", "no_takeover", "expires_in_seconds"]
        if let key = Set(input.keys).subtracting(allowed).sorted().first { throw invalid("Unsupported interaction field: " + key, code: "unsupported_interaction_field") }
        guard let kind = input["kind"] as? String, ["notice", "question", "html"].contains(kind),
              let title = input["title"] as? String, !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, title.count <= 80, title.utf8.count <= 320 else { throw invalid("Use a notice, question or HTML card with a title of 1–80 characters.") }
        if let text = input["text"], !(text is String && (text as! String).count <= 2000 && (text as! String).utf8.count <= 8000) { throw invalid("Text must be at most 2,000 characters.") }
        if let priority = input["priority"], priority as? String != "normal" { throw invalid("This build supports normal-priority inbox delivery.", code: "unsupported_interaction_field") }
        if let speak = input["speak"], !(isBoolean(speak) && speak as? Bool == false) { throw invalid("Use a separately granted speech flow for speech.", code: "unsupported_interaction_field") }
        if !isBoolean(input["no_takeover"] as Any) || input["no_takeover"] as? Bool != true { throw invalid("This build supports inbox delivery only; use no_takeover=true.", code: "unsupported_interaction_field") }
        let seconds: Int
        if let value = input["expires_in_seconds"] {
            guard let n = RelayHostConnection.integer(value), (60...604800).contains(n) else { throw invalid("Expiry must be 60–604800 seconds.") }; seconds = Int(n)
        } else { seconds = 86400 }
        var body = input
        if kind == "html" {
            guard let html = input["html"] as? String, !html.isEmpty, html.utf8.count <= 256 * 1024, input["controls"] == nil else { throw invalid("An HTML card needs at most 256 KB of HTML and no native controls.") }
        } else {
            guard input["html"] == nil else { throw invalid("HTML requires kind=html.") }
            let controls: [[String: Any]]
            if let value = input["controls"] {
                guard let list = value as? [[String: Any]], list.count <= 6 else { throw invalid("Use at most six controls.") }; controls = list
            } else { controls = [] }
            if kind == "question" && controls.isEmpty { throw invalid("A question needs controls.") }
            var ids = Set<String>()
            for control in controls {
                guard let id = control["id"] as? String, id.range(of: "^[a-zA-Z0-9_-]{1,40}$", options: .regularExpression) != nil, ids.insert(id).inserted,
                      let type = control["type"] as? String, ["button", "text", "choice", "link"].contains(type),
                      let label = control["label"] as? String, !label.isEmpty, label.count <= 120, label.utf8.count <= 480 else { throw invalid("Controls need distinct IDs, supported types and brief labels.") }
                let fields: Set<String> = Set(["id", "type", "label"] + (type == "button" ? ["style"] : type == "text" ? ["placeholder"] : type == "choice" ? ["options"] : ["url"]))
                guard Set(control.keys).isSubset(of: fields), kind != "notice" || type == "link" else { throw invalid("Unsupported control declaration.") }
                if type == "link" {
                    guard let raw = control["url"] as? String, let url = URL(string: raw), url.scheme == "https", url.host != nil, url.user == nil, url.password == nil else { throw invalid("Links must use HTTPS.") }
                }
                if type == "choice" {
                    guard let options = control["options"] as? [String], (1...20).contains(options.count), options.allSatisfy({ !$0.isEmpty &&  $0.count <= 120 && $0.utf8.count <= 480 }), Set(options).count == options.count else { throw invalid("Choices need 1–20 distinct text options.") }
                }
                if let style = control["style"], !(style is String && ["primary", "plain", "tonal"].contains(style as! String)) { throw invalid("Unsupported button style.") }
                if let placeholder = control["placeholder"], !(placeholder is String && (placeholder as! String).count <= 120 && (placeholder as! String).utf8.count <= 480) { throw invalid("Use a brief placeholder.") }
            }
            body["controls"] = controls
        }
        body["no_takeover"] = true
        body["interaction_id"] = UUID().uuidString.lowercased()
        body["status"] = "pending"
        body["created_at"] = ISO8601DateFormatter().string(from: now)
        body["expires_at"] = ISO8601DateFormatter().string(from: now.addingTimeInterval(TimeInterval(seconds)))
        body["retention"] = ["terminal_cards_device": 200, "events_per_agent": 1000]
        body["display"] = ["shown_as": "inbox", "notifications": false, "visible": false]
        return body
    }
    static var schema: [String: Any] {
        let label: [String: Any] = ["type": "string", "minLength": 1, "maxLength": 120]
        let common: [String: Any] = ["id": ["type": "string", "pattern": "^[a-zA-Z0-9_-]{1,40}$"], "label": label]
        let controls: [[String: Any]] = ["button", "text", "choice", "link"].map { kind in
            var properties = common; properties["type"] = ["const": kind]
            var required = ["id", "type", "label"]
            switch kind {
            case "button": properties["style"] = ["enum": ["primary", "plain", "tonal"]]
            case "text": properties["placeholder"] = ["type": "string", "maxLength": 120]
            case "choice": properties["options"] = ["type": "array", "items": label, "minItems": 1, "maxItems": 20, "uniqueItems": true]; required.append("options")
            default: properties["url"] = ["type": "string", "format": "uri", "pattern": "^https://"]; required.append("url")
            }
            return ["type": "object", "properties": properties, "required": required, "additionalProperties": false]
        }
        return ["type": "object", "properties": [
            "kind": ["enum": ["notice", "question", "html"]], "title": ["type": "string", "minLength": 1, "maxLength": 80],
            "text": ["type": "string", "maxLength": 2000], "html": ["type": "string", "maxLength": 262144],
            "controls": ["type": "array", "items": ["oneOf": controls], "maxItems": 6],
            "no_takeover": ["const": true], "priority": ["const": "normal"], "speak": ["const": false],
            "expires_in_seconds": ["type": "integer", "minimum": 60, "maximum": 604800]
        ], "required": ["kind", "title", "no_takeover"], "additionalProperties": false,
        "allOf": [
            ["if": ["properties": ["kind": ["const": "html"]]], "then": ["required": ["html"], "not": ["required": ["controls"]]], "else": ["not": ["required": ["html"]]]],
            ["if": ["properties": ["kind": ["const": "question"]]], "then": ["required": ["controls"], "properties": ["controls": ["minItems": 1]]]],
            ["if": ["properties": ["kind": ["const": "notice"]]], "then": ["properties": ["controls": ["items": ["properties": ["type": ["const": "link"]]]]]]]
        ]]
    }
    static func answer(_ answer: [String: Any], for body: [String: Any]) throws {
        guard Set(answer.keys).isSubset(of: ["button", "values", "data"]) else { throw invalid("Unsupported answer fields.") }
        if body["kind"] as? String == "html" {
            guard answer.keys.contains("data") else { throw invalid("HTML cards answer through Bridgit.respond.") }; return
        }
        guard body["kind"] as? String == "question", let controls = body["controls"] as? [[String: Any]],
              let values = answer["values"] as? [String: String] else { throw invalid("Use the question's controls to answer.") }
        let button = answer["button"] as? String
        if let button { guard controls.contains(where: { $0["id"] as? String == button && $0["type"] as? String == "button" }) else { throw invalid("Unknown answer button.") } }
        for (id, value) in values {
            guard value.count <= 2000, let control = controls.first(where: { $0["id"] as? String == id }), ["text", "choice"].contains(control["type"] as? String ?? "") else { throw invalid("Unknown answer field.") }
            if control["type"] as? String == "choice", !(control["options"] as? [String] ?? []).contains(value) { throw invalid("Choose one of the offered options.") }
        }
        guard button != nil || !values.isEmpty else { throw invalid("Choose an answer first.") }
    }
    private static func isBoolean(_ value: Any) -> Bool {
        guard let number = value as? NSNumber else { return false }
        return CFGetTypeID(number) == CFBooleanGetTypeID()
    }
    static func invalid(_ message: String, code: String = "invalid_interaction") -> DeviceAPIError { .init(422, code, message) }
}
