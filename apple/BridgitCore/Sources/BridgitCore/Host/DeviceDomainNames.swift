import Foundation

/// Bridgit names (bridgit.cc/names): this Bridgit's Device Domain Name, registered with the team backend
/// (POST /v1/names, signed as this install). The first Bridgit to register a name keeps it for good.
/// While the name is visible, a heartbeat says it is on. The local copy only caches the backend's answer;
/// the backend decides. Same behavior as Android's DeviceNames and the Bridgit Portal's DeviceNameModel.
public enum DeviceDomainName {
    /// Same advice the backend gives (bridgit-cloud feedback/src/names.mjs NAME_ADVICE).
    public static let advice = "Pick a Device Domain Name for this Bridgit: about 10 characters, as cute and friendly to a person as you can make it, like kitchen-tab or porch-cam. " +
        "3 to 30 lowercase letters, digits and hyphens; no slashes. First come, first served, like a domain: if it's taken, pick another."
    public static let operationPath = "/v1/owner/configuration/name"
    public static let operation = "POST " + operationPath
    public static let about = "https://bridgit.cc/agents/names/" // Android Learn.NAMES_URL
    public static let labelMaximum = 60
    public static let heartbeatDefaultSeconds = 300

    /// A Device Domain Name as the backend accepts it, lower-cased; nil when invalid.
    public static func parse(_ text: String) -> String? {
        let value = text.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard (3...30).contains(value.count),
              value.range(of: "^[a-z0-9]+(-[a-z0-9]+)*$", options: .regularExpression) != nil else { return nil }
        return value
    }

    /// A first suggestion from the device's own name ("Rahul's MacBook Air" → "rahuls-macbook-air").
    public static func suggestion(from deviceName: String) -> String {
        var out = "", hyphen = false
        for scalar in deviceName.lowercased().folding(options: .diacriticInsensitive, locale: nil).unicodeScalars {
            if ("a"..."z").contains(scalar) || ("0"..."9").contains(scalar) {
                out.unicodeScalars.append(scalar); hyphen = false
            } else if scalar == "'" || scalar == "’" {
                continue
            } else if !out.isEmpty, !hyphen {
                out.append("-"); hyphen = true
            }
        }
        while out.hasSuffix("-") { out.removeLast() }
        if out.count > 30 {
            out = String(out.prefix(30))
            while out.hasSuffix("-") { out.removeLast() }
        }
        return parse(out) ?? "my-bridgit"
    }
}

/// The backend's view of this Bridgit's name, as cached on the device.
public struct DeviceDomainNameRecord: Codable, Equatable, Sendable {
    public var name: String
    public var label: String?
    public var visible: Bool
    public var heartbeatEverySeconds: Int

    public init(name: String, label: String? = nil, visible: Bool = false, heartbeatEverySeconds: Int = DeviceDomainName.heartbeatDefaultSeconds) {
        self.name = name; self.label = label; self.visible = visible; self.heartbeatEverySeconds = heartbeatEverySeconds
    }

    init?(backend json: [String: Any]) {
        guard let raw = json["name"] as? String, let name = DeviceDomainName.parse(raw) else { return nil }
        let every = (json["heartbeat_every_s"] as? NSNumber)?.intValue ?? DeviceDomainName.heartbeatDefaultSeconds
        self.init(name: name, label: json["label"] as? String, visible: json["visible"] as? Bool ?? false,
                  heartbeatEverySeconds: (60...3600).contains(every) ? every : DeviceDomainName.heartbeatDefaultSeconds)
    }
}

/// Registers, reads, changes and hides this Bridgit's name; sends the heartbeat while it is visible.
public actor DeviceDomainNameService {
    /// A signed call to the team backend: (status, JSON body). Throws only when the backend can't be reached.
    public typealias Call = @Sendable (_ method: String, _ path: String, _ body: [String: Any]?) async throws -> (Int, [String: Any])
    private let call: Call
    private let load: @Sendable () -> Data?
    private let store: @Sendable (Data?) -> Void
    private let now: @Sendable () -> Date
    private var lastHeartbeat: Date?

    public init(call: @escaping Call, load: @escaping @Sendable () -> Data?, store: @escaping @Sendable (Data?) -> Void,
                now: @escaping @Sendable () -> Date = { Date() }) {
        self.call = call; self.load = load; self.store = store; self.now = now
    }

    public var cached: DeviceDomainNameRecord? { load().flatMap { try? JSONDecoder().decode(DeviceDomainNameRecord.self, from: $0) } }

    private func save(_ record: DeviceDomainNameRecord?) {
        store(record.flatMap { try? JSONEncoder().encode($0) })
    }

    /// What an agent should do about the name, without a network call (capabilities and health).
    public func hint(canConfigure: Bool? = nil) -> [String: Any] {
        guard let own = cached else {
            var value: [String: Any] = ["status": "unregistered", "advice": DeviceDomainName.advice, "about": DeviceDomainName.about,
                                        "how": "Register a name first: \(DeviceDomainName.operation) {\"name\":\"kitchen-tab\"}. Taken? Suggest another."]
            if canConfigure == false { value["blocked"] = "owner.configuration grant required: ask the owner to allow app configuration" }
            return value
        }
        return ["status": "registered", "name": own.name, "label": own.label as Any? ?? NSNull(), "visible": own.visible,
                "how": "Registered. Change label or visible with \(DeviceDomainName.operation).", "about": DeviceDomainName.about]
    }

    /// GET /v1/names/me: the backend's answer, cached; unregistered when the backend has none.
    public func refresh() async throws -> [String: Any] {
        let (status, json) = try await call("GET", "/v1/names/me", nil)
        if status == 404, json["error"] as? String == "no_name" { save(nil); return hint() }
        try check(status, json)
        guard let record = DeviceDomainNameRecord(backend: json) else { throw Self.invalidReply() }
        save(record)
        var value = hint()
        value["visible_since"] = json["visible_since"] ?? NSNull()
        value["last_seen_at"] = json["last_seen_at"] ?? NSNull()
        return value
    }

    /// Registers `name` (POST /v1/names) when this Bridgit has none; otherwise changes label and visibility only.
    /// `label`: nil leaves it, .some(nil) clears it.
    public func set(name: String?, label: String??, visible: Bool?) async throws -> [String: Any] {
        let held = cached?.name
        if held == nil, let name {
            var body: [String: Any] = ["name": name]
            if let label { body["label"] = label ?? NSNull() }
            if let visible { body["visible"] = visible }
            let (status, json) = try await call("POST", "/v1/names", body)
            if status == 409, json["error"] as? String == "already_named" {
                _ = try? await refresh()
                throw DeviceAPIError(409, "already_named", json["message"] as? String ?? "This Bridgit already has a name. A Bridgit keeps its first name.")
            }
            try check(status, json)
            guard let record = DeviceDomainNameRecord(backend: json) else { throw Self.invalidReply() }
            save(record); lastHeartbeat = now()
            var value = hint(); value["registered"] = true; value["created"] = status == 201
            return value
        }
        if held == nil {
            // The cache may be empty (cleared or reinstalled); ask before saying there is no name.
            guard try await refresh()["status"] as? String == "registered" else {
                throw DeviceAPIError(422, "invalid_parameter", "name is required: this Bridgit has no name yet. " + DeviceDomainName.advice)
            }
            return try await set(name: name, label: label, visible: visible)
        }
        if let name, name != held {
            throw DeviceAPIError(409, "already_named", "This Bridgit is already \(held!). A Bridgit keeps its first name.")
        }
        var changes: [String: Any] = [:]
        if let label { changes["label"] = label ?? NSNull() }
        if let visible { changes["visible"] = visible }
        if changes.isEmpty { return try await refresh() }
        let (status, json) = try await call("PATCH", "/v1/names/me", changes)
        try check(status, json)
        guard let record = DeviceDomainNameRecord(backend: json) else { throw Self.invalidReply() }
        save(record)
        return hint()
    }

    /// DELETE /v1/names/me: hides the name and clears the label; the name stays this Bridgit's.
    public func hide() async throws -> [String: Any] {
        let (status, json) = try await call("DELETE", "/v1/names/me", nil)
        if status == 404, json["error"] as? String == "no_name" { save(nil); return hint() }
        try check(status, json)
        guard let record = DeviceDomainNameRecord(backend: json) else { throw Self.invalidReply() }
        save(record)
        return hint()
    }

    /// Says "I'm on" once per heartbeat interval while the name is visible. Never throws; the next tick retries.
    @discardableResult public func heartbeatIfDue() async -> Bool {
        guard let own = cached, own.visible else { return false }
        let at = now()
        if let lastHeartbeat, at.timeIntervalSince(lastHeartbeat) < Double(own.heartbeatEverySeconds) - 15 { return false }
        lastHeartbeat = at
        guard let (status, json) = try? await call("POST", "/v1/names/heartbeat", [:]) else { return false }
        if status == 200, let record = DeviceDomainNameRecord(backend: json) { save(record); return true }
        if status == 404, json["error"] as? String == "no_name" { save(nil) }
        return false
    }

    private func check(_ status: Int, _ json: [String: Any]) throws {
        guard !(200..<300).contains(status) else { return }
        let message = (json["message"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "The team backend answered \(status)."
        switch json["error"] as? String {
        case "name_taken":
            var detail = message
            if let lookup = json["lookup"] as? [String: Any], let label = lookup["label"] as? String, !label.isEmpty { detail += " (\(label))" }
            throw DeviceAPIError(409, "name_taken", detail)
        case "already_named": throw DeviceAPIError(409, "already_named", message)
        case "invalid_parameter": throw DeviceAPIError(422, "invalid_parameter", message)
        case "rate_limited": throw DeviceAPIError(429, "rate_limited", message)
        case "unknown_install": throw DeviceAPIError(503, "team_unavailable", "This device must register with the team backend again. Retry.")
        default: throw DeviceAPIError(502, "upstream_failed", message)
        }
    }

    private static func invalidReply() -> DeviceAPIError { .init(502, "invalid_response", "The names service returned an invalid answer.") }
}

/// Owner configuration request bodies for /v1/owner/configuration/name, validated like Android.
public struct DeviceDomainNameChange: Equatable {
    public let name: String?
    public let label: String??
    public let visible: Bool?

    public init(body: [String: Any]) throws {
        guard !body.isEmpty else { throw DeviceAPIError(422, "invalid_parameter", "Send name to register, or label/visible to change.") }
        guard Set(body.keys).isSubset(of: ["name", "label", "visible"]) else {
            throw DeviceAPIError(422, "invalid_parameter", "Only name, label and visible can be set.")
        }
        if let raw = body["name"] {
            guard let text = raw as? String, let parsed = DeviceDomainName.parse(text) else {
                throw DeviceAPIError(422, "invalid_parameter", "name must be a Device Domain Name. " + DeviceDomainName.advice)
            }
            name = parsed
        } else { name = nil }
        if let raw = body["label"] {
            if raw is NSNull { label = .some(nil) }
            else {
                guard let text = raw as? String, text.count <= DeviceDomainName.labelMaximum,
                      text.unicodeScalars.allSatisfy({ $0.value >= 32 && $0.value != 127 }) else {
                    throw DeviceAPIError(422, "invalid_parameter", "label must be text up to 60 characters, or null to clear.")
                }
                let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
                label = .some(trimmed.isEmpty ? nil : trimmed)
            }
        } else { label = nil }
        if let raw = body["visible"] {
            guard let number = raw as? NSNumber, CFGetTypeID(number) == CFBooleanGetTypeID() else {
                throw DeviceAPIError(422, "invalid_parameter", "visible must be true or false.")
            }
            visible = number.boolValue
        } else { visible = nil }
    }
}
