import CoreFoundation
import CryptoKit
import Foundation

struct ConnectionCreationReceipt: Codable {
    var id: String
    var fingerprint: String
    var at: TimeInterval
}
struct OwnerConnectionState: Codable {
    var version = 1
    var rows: [OwnerConnection] = []
    var creations: [String: ConnectionCreationReceipt] = [:]
}

extension OwnerConnection {
    var configurationDescription: [String: Any] {
        var result = agentDescription
        result["write_only"] = ["url", "headers", "extra", "signing_secret"]
        result["signing_secret_configured"] = !signingSecret.isEmpty
        return result
    }
    /// Same write-only field semantics as pinned Android ConfigurationConnections.
    static func configured(_ body: [String: Any], previous: Self? = nil, now: Date = Date()) throws -> Self {
        let fields: Set<String> = ["name", "url", "preset", "allow_private", "headers", "extra", "enabled", "signing_secret"]
        guard !body.isEmpty, Set(body.keys).isSubset(of: fields) else { throw invalidConfiguration() }
        func text(_ key: String, _ fallback: String?, limit: Int) throws -> String {
            if let raw = body[key] {
                guard let value = raw as? String, !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, value.count <= limit else { throw invalidConfiguration() }
                return value
            }
            guard let fallback else { throw invalidConfiguration() }; return fallback
        }
        func boolean(_ key: String, _ fallback: Bool) throws -> Bool {
            guard let raw = body[key] else { return fallback }
            guard let value = raw as? NSNumber, CFGetTypeID(value) == CFBooleanGetTypeID() else { throw invalidConfiguration() }
            return value.boolValue
        }
        func dictionary(_ key: String, _ fallback: [String: String]) throws -> [String: String] {
            guard let raw = body[key] else { return fallback }
            if raw is NSNull { return [:] }
            guard let values = raw as? [String: Any], values.values.allSatisfy({ $0 is String }) else { throw invalidConfiguration() }
            return values.mapValues { $0 as! String }
        }
        let name = try text("name", previous?.name, limit: 80)
        let address = try text("url", previous?.address, limit: 2048)
        let preset = try text("preset", previous?.presetID ?? ConnectionPreset.recognize(address), limit: 80)
        let privateNetwork = try boolean("allow_private", previous?.allowPrivate ?? false)
        let headers = try dictionary("headers", previous?.headers ?? [:])
        let extra = try dictionary("extra", previous?.extra ?? [:])
        var row = try previous ?? Self(name: name, address: address, presetID: preset, allowPrivate: privateNetwork, headers: headers, extra: extra)
        row.name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        row.address = address.trimmingCharacters(in: .whitespacesAndNewlines)
        row.presetID = preset; row.allowPrivate = privateNetwork; row.headers = headers; row.extra = extra
        row.enabled = try boolean("enabled", previous?.enabled ?? true)
        if let raw = body["signing_secret"] {
            guard let secret = raw as? String, secret.hasPrefix("whsec_"), Data(base64Encoded: String(secret.dropFirst(6)))?.count == 32 else { throw invalidConfiguration() }
            if let previous, previous.signingSecret != secret {
                row.previousSecret = previous.signingSecret; row.previousUntil = now.timeIntervalSince1970 + 86400
            }
            row.signingSecret = secret
        }
        row.failing = false; row.revision = UUID().uuidString
        try row.validate(); return row
    }
    private static func invalidConfiguration() -> DeviceAPIError {
        .init(422, "invalid_parameter", "Use supported connection fields with native URL, header and preset validation. Credentials are write-only; alert_secret is not supported in this build.")
    }
}

extension AppletStore {
    /// The authorization callback runs at the mutation boundary. The following read/validate/write
    /// has no suspension, and a single SQLite state value atomically includes creation receipts.
    func configureConnection(_ request: HostRequest, agent: HostAgent, authorize: @escaping () async throws -> Void) async throws -> HostResponse {
        guard request.query.isEmpty,
              let encoded = try? JSONSerialization.data(withJSONObject: request.body, options: [.sortedKeys]), encoded.count <= 32768 else {
            throw DeviceAPIError(422, "invalid_parameter", "Connection configuration takes no query and at most 32 KiB of JSON.")
        }
        let parts = request.path.split(separator: "/").map(String.init)
        guard Array(parts.prefix(4)) == ["v1", "owner", "configuration", "connections"] else { throw invalidConfigurationRoute() }
        try await authorize(); try Task.checkCancellation()
        var state = try connectionState()
        if request.method == "GET" {
            guard parts.count == 4, request.body.isEmpty else { throw invalidConfigurationRoute() }
            return try .json(["connections": state.rows.map(\.configurationDescription),
                "presets": ConnectionPreset.all.map { ["id": $0.id, "name": $0.name, "headers": $0.customHeaders, "extra_fields": $0.extra] },
                "approval_required": false, "configuration_grant": HostOperation.configurationWrite.rawValue,
                "unsupported_fields": ["alert_secret"], "send_grant": HostOperation.connections.rawValue])
        }
        guard agent.allows(.configurationWrite) else { throw DeviceAPIError(403, "agent_not_allowed", "Owner-issued configuration authority is required.") }
        if parts.count == 4, request.method == "POST" {
            guard let key = request.idempotencyKey, key.range(of: "^[A-Za-z0-9_-]{8,128}$", options: .regularExpression) != nil else {
                throw DeviceAPIError(422, "invalid_parameter", "Use an Idempotency-Key of 8–128 letters, digits, underscores or hyphens.")
            }
            let reservation = Self.connectionDigest(Data((agent.eventOwner + ":" + key).utf8))
            let fingerprint = Self.connectionDigest(encoded)
            if let saved = state.creations[reservation] {
                guard saved.fingerprint == fingerprint else { throw DeviceAPIError(409, "idempotency_conflict", "This key created a different connection.") }
                guard let row = state.rows.first(where: { $0.id == saved.id }) else { throw DeviceAPIError(404, "not_found", "The previously created connection was revoked.") }
                return try .json(row.configurationDescription)
            }
            guard state.rows.count < 25 else { throw DeviceAPIError(429, "rate_limited", "At most 25 connections can be saved.") }
            let now = Date()
            state.creations = state.creations.filter { $0.value.at > now.timeIntervalSince1970 - 30 * 86400 }
            guard state.creations.count < 1000 else { throw DeviceAPIError(429, "rate_limited", "The retained connection creation limit has been reached.") }
            let row = try OwnerConnection.configured(request.body, now: now)
            state.rows.append(row); state.creations[reservation] = .init(id: row.id, fingerprint: fingerprint, at: now.timeIntervalSince1970)
            try writeConnectionState(state)
            return try .json(row.configurationDescription, status: 201)
        }
        guard parts.count == 5 || parts.count == 6 && parts[5] == "rotate", UUID(uuidString: parts[4]) != nil,
              let index = state.rows.firstIndex(where: { $0.id == parts[4] }) else { throw DeviceAPIError(404, "not_found", "Unknown connection.") }
        if request.method == "DELETE", parts.count == 5 {
            guard request.body.isEmpty else { throw invalidConfigurationRoute() }
            let id = state.rows.remove(at: index).id
            try writeConnectionState(state)
            try cancelConnectionDeliveries(id)
            return try .json(["id": id, "removed": true, "enabled": false])
        }
        let rotation = parts.count == 6 && request.method == "POST"
        guard request.method == "PATCH" && parts.count == 5 || rotation else { throw invalidConfigurationRoute() }
        if rotation {
            guard Set(request.body.keys) == ["signing_secret"] else { throw invalidConfigurationRoute() }
        }
        let row = try OwnerConnection.configured(request.body, previous: state.rows[index])
        state.rows[index] = row; try writeConnectionState(state)
        if !row.enabled { try cancelConnectionDeliveries(row.id) }
        return try .json(row.configurationDescription)
    }
    private func invalidConfigurationRoute() -> DeviceAPIError { .init(422, "invalid_parameter", "Use a declared connection configuration method and body.") }
    private static func connectionDigest(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
}
