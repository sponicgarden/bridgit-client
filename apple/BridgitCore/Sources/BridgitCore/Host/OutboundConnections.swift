import CoreFoundation
import CryptoKit
import Foundation
import Security

public struct ConnectionPreset: Identifiable, Sendable {
    public let id: String
    public let name: String
    public let note: String
    public let needsText: Bool
    public let signs: Bool
    public let extra: [String]
    public let customHeaders: Bool
    public let paid: Bool
    public var takesPhoto: Bool { ["home_assistant", "n8n", "discord", "telegram", "ntfy", "pushover", "json"].contains(id) }

    public static let all: [Self] = [
        .init(id: "home_assistant", name: "Home Assistant", note: "JSON body; an automation with a webhook trigger reads it as trigger.json.", needsText: false, signs: false, extra: [], customHeaders: false, paid: false),
        .init(id: "n8n", name: "n8n", note: "JSON body with a Standard Webhooks signature; optional header auth.", needsText: false, signs: true, extra: [], customHeaders: true, paid: false),
        .init(id: "discord", name: "Discord", note: "Message text and a photo file.", needsText: true, signs: false, extra: [], customHeaders: false, paid: false),
        .init(id: "slack", name: "Slack", note: "Message text. Slack incoming webhooks take no files.", needsText: true, signs: false, extra: [], customHeaders: false, paid: false),
        .init(id: "telegram", name: "Telegram", note: "A bot message to one chat; needs the chat ID.", needsText: true, signs: false, extra: ["chat_id"], customHeaders: false, paid: false),
        .init(id: "ntfy", name: "ntfy", note: "Push notification. Your service account and server limits apply.", needsText: true, signs: false, extra: [], customHeaders: false, paid: false),
        .init(id: "pushover", name: "Pushover", note: "Push notification; needs your user key and app token. Your account limits apply.", needsText: true, signs: false, extra: ["user", "token"], customHeaders: false, paid: false),
        .init(id: "zapier", name: "Zapier", note: "Flat JSON. Check webhook support in your Zapier plan.", needsText: false, signs: false, extra: [], customHeaders: false, paid: true),
        .init(id: "make", name: "Make", note: "JSON. Depends on your Make plan.", needsText: false, signs: false, extra: [], customHeaders: false, paid: true),
        .init(id: "ifttt", name: "IFTTT", note: "value1 (text), value2 (data) and value3. Check webhook support in your IFTTT plan.", needsText: false, signs: false, extra: [], customHeaders: false, paid: true),
        .init(id: "json", name: "Any JSON webhook", note: "JSON body with a Standard Webhooks signature and the headers you set.", needsText: false, signs: true, extra: [], customHeaders: true, paid: false)
    ]
    public static func named(_ id: String) -> Self? { all.first { $0.id == id } }

    public static func recognize(_ text: String) -> String {
        guard let u = URLComponents(string: text.trimmingCharacters(in: .whitespacesAndNewlines)), let host = u.host?.lowercased() else { return "json" }
        let path = u.path
        if (host == "discord.com" || host == "discordapp.com" || host.hasSuffix(".discord.com")), path.hasPrefix("/api/webhooks/") { return "discord" }
        if host == "hooks.slack.com", path.hasPrefix("/services/") { return "slack" }
        if host == "api.telegram.org", path.hasPrefix("/bot") { return "telegram" }
        if host == "api.pushover.net" { return "pushover" }
        if host == "ntfy.sh" { return "ntfy" }
        if host == "hooks.zapier.com", path.hasPrefix("/hooks/catch/") { return "zapier" }
        if host.hasPrefix("hook."), host.hasSuffix(".make.com") { return "make" }
        if host == "maker.ifttt.com", path.hasPrefix("/trigger/") { return "ifttt" }
        if path.range(of: "^/api/webhook/[^/]+/?$", options: .regularExpression) != nil { return "home_assistant" }
        if path.contains("/webhook/") || path.contains("/webhook-test/") { return "n8n" }
        return "json"
    }
}

/// Owner-only storage representation. Never serialize this type in an agent response.
public struct OwnerConnection: Codable, Identifiable, Sendable {
    public var id: String
    public var name: String
    public var address: String
    public var presetID: String
    public var allowPrivate: Bool
    public var headers: [String: String]
    public var extra: [String: String]
    public var enabled: Bool
    public var failing = false
    public var revision: String
    public var signingSecret: String
    public var previousSecret: String?
    public var previousUntil: TimeInterval?
    public var preset: ConnectionPreset { ConnectionPreset.named(presetID)! }

    enum CodingKeys: String, CodingKey { case id, name, address, presetID, allowPrivate, headers, extra, enabled, failing, revision, signingSecret, previousSecret, previousUntil }
    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(String.self, forKey: .id); name = try values.decode(String.self, forKey: .name)
        address = try values.decode(String.self, forKey: .address); presetID = try values.decode(String.self, forKey: .presetID)
        allowPrivate = try values.decodeIfPresent(Bool.self, forKey: .allowPrivate) ?? false
        headers = try values.decodeIfPresent([String: String].self, forKey: .headers) ?? [:]
        extra = try values.decodeIfPresent([String: String].self, forKey: .extra) ?? [:]
        enabled = try values.decodeIfPresent(Bool.self, forKey: .enabled) ?? false
        failing = try values.decodeIfPresent(Bool.self, forKey: .failing) ?? false
        revision = try values.decode(String.self, forKey: .revision); signingSecret = try values.decode(String.self, forKey: .signingSecret)
        previousSecret = try values.decodeIfPresent(String.self, forKey: .previousSecret)
        previousUntil = try values.decodeIfPresent(TimeInterval.self, forKey: .previousUntil)
    }

    public init(name: String, address: String, presetID: String, allowPrivate: Bool = false, headers: [String: String] = [:], extra: [String: String] = [:]) throws {
        self.id = UUID().uuidString; self.revision = UUID().uuidString
        self.name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        self.address = address.trimmingCharacters(in: .whitespacesAndNewlines)
        self.presetID = presetID; self.allowPrivate = allowPrivate; self.headers = headers; self.extra = extra; enabled = true
        signingSecret = try Self.newSecret()
        try validate()
    }

    static func newSecret() throws -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else { throw DeviceAPIError(503, "random_unavailable", "Could not create a signing secret.") }
        return "whsec_" + Data(bytes).base64EncodedString()
    }
    public func validate() throws {
        guard (1...80).contains(name.count), name.utf8.count <= 320, !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, !name.contains(where: \.isNewline) else { throw DeviceAPIError(422, "invalid_connection", "Give the connection a name of 1–80 characters.") }
        guard UUID(uuidString: id) != nil, UUID(uuidString: revision) != nil,
              signingSecret.hasPrefix("whsec_"), Data(base64Encoded: String(signingSecret.dropFirst(6)))?.count == 32 else {
            throw DeviceAPIError(422, "invalid_connection", "The connection identity or signing secret is invalid.")
        }
        guard let preset = ConnectionPreset.named(presetID) else { throw DeviceAPIError(422, "invalid_connection", "Choose a supported service.") }
        _ = try ConnectionAddress.check(address, allowPrivate: allowPrivate)
        guard headers.count <= 8, preset.customHeaders || headers.isEmpty else { throw DeviceAPIError(422, "invalid_connection", "This service does not support extra headers.") }
        var names = Set<String>()
        for (name, value) in headers {
            let lower = name.lowercased()
            guard name.range(of: "^[A-Za-z0-9!#$%&'*+.^_`|~-]{1,64}$", options: .regularExpression) != nil,
                  !["host", "content-length", "content-type", "connection", "transfer-encoding", "user-agent", "idempotency-key", "expect", "upgrade", "te", "trailer"].contains(lower),
                  !lower.hasPrefix("webhook-"), !lower.hasPrefix("x-bridgit-"), names.insert(lower).inserted,
                  value.count <= 500, value.utf8.count <= 2000, !value.unicodeScalars.contains(where: { $0.value < 32 || $0.value == 127 }) else {
                throw DeviceAPIError(422, "invalid_connection", "Use at most eight distinct, single-line headers. Bridgit sets transport and signing headers itself.")
            }
        }
        guard Set(extra.keys).isSubset(of: Set(preset.extra)) else { throw DeviceAPIError(422, "invalid_connection", "This service does not use those extra fields.") }
        for key in preset.extra {
            guard let value = extra[key], !value.isEmpty, value.count <= 200, !value.contains(where: \.isWhitespace) else {
                throw DeviceAPIError(422, "invalid_connection", key == "chat_id" ? "Telegram needs a chat ID." : "Pushover needs your user key and app token.")
            }
        }
        if presetID == "telegram" {
            guard let u = URLComponents(string: address), u.path.range(of: "^/bot[0-9]+:[A-Za-z0-9_-]+/?(sendMessage|sendPhoto)?$", options: .regularExpression) != nil else { throw DeviceAPIError(422, "invalid_connection", "A Telegram address looks like https://api.telegram.org/bot<token>.") }
        }
    }
    public var maskedAddress: String {
        guard let u = URLComponents(string: address), let host = u.host else { return "•••" }
        return (u.scheme ?? "https") + "://" + host + (u.port.map { ":\($0)" } ?? "") + "/•••"
    }
    public var agentDescription: [String: Any] {
        ["id": id, "name": name, "preset": presetID, "service": preset.name, "photos": preset.takesPhoto,
         "needs_text": preset.needsText, "private_network": allowPrivate, "enabled": enabled,
         "failing": failing, "delivery": "queued_or_synchronous_text"]
    }
}

extension AppletStore {
    private static var connectionsKey: String { "owner-outbound-connections.v1" }
    func connectionState() throws -> OwnerConnectionState {
        guard let value = try state(Self.connectionsKey) else { return .init() }
        do {
            let data = Data(value.utf8)
            let result: OwnerConnectionState
            if let legacy = try? JSONDecoder().decode([OwnerConnection].self, from: data) { result = .init(rows: legacy) }
            else { result = try JSONDecoder().decode(OwnerConnectionState.self, from: data) }
            guard result.rows.count <= 25, result.version == 1, result.creations.count <= 1000,
                  Set(result.rows.map(\.id)).count == result.rows.count else { throw invalidConnectionStorage() }
            for row in result.rows { try row.validate() }
            return result
        } catch { throw invalidConnectionStorage() }
    }
    private func invalidConnectionStorage() -> DeviceAPIError { .init(500, "connection_storage_invalid", "Connection storage could not be read.") }
    func writeConnectionState(_ value: OwnerConnectionState) throws {
        try setState(Self.connectionsKey, value: String(decoding: JSONEncoder().encode(value), as: UTF8.self))
    }
    public func ownerConnections() throws -> [OwnerConnection] { try connectionState().rows }
    private func writeConnections(_ rows: [OwnerConnection]) throws {
        var current = try connectionState(); current.rows = rows; try writeConnectionState(current)
    }
    public func updateOwnerConnection(_ row: OwnerConnection, expectedRevision: String) throws {
        try row.validate(); var rows = try ownerConnections()
        guard let index = rows.firstIndex(where: { $0.id == row.id }), rows[index].revision == expectedRevision else {
            throw DeviceAPIError(409, "connection_changed", "The connection changed. Reopen it before saving.")
        }
        var changed = row; changed.revision = UUID().uuidString; changed.failing = false
        rows[index] = changed; try writeConnections(rows)
    }
    public func addOwnerConnection(_ row: OwnerConnection) throws {
        try row.validate(); var rows = try ownerConnections()
        guard rows.count < 25 else { throw DeviceAPIError(422, "connection_limit", "At most 25 connections can be saved.") }
        guard !rows.contains(where: { $0.id == row.id }) else { throw DeviceAPIError(409, "connection_exists", "This connection already exists.") }
        rows.append(row); try writeConnections(rows)
    }
    public func revokeOwnerConnection(_ id: String) throws {
        var rows = try ownerConnections(); rows.removeAll { $0.id == id }; try writeConnections(rows)
        try cancelConnectionDeliveries(id)
    }
    public func setOwnerConnection(_ id: String, enabled: Bool) throws {
        var rows = try ownerConnections()
        guard let index = rows.firstIndex(where: { $0.id == id }) else { throw DeviceAPIError(404, "not_found", "This connection was removed.") }
        rows[index].enabled = enabled; rows[index].revision = UUID().uuidString; try writeConnections(rows)
        if !enabled { try cancelConnectionDeliveries(id) }
    }
    public func rotateOwnerConnectionSecret(_ id: String, now: Date = Date()) throws {
        var rows = try ownerConnections()
        guard let index = rows.firstIndex(where: { $0.id == id }), rows[index].preset.signs else { throw DeviceAPIError(404, "not_found", "This connection has no signing secret.") }
        rows[index].previousSecret = rows[index].signingSecret; rows[index].previousUntil = now.timeIntervalSince1970 + 86400
        rows[index].signingSecret = try OwnerConnection.newSecret(); rows[index].revision = UUID().uuidString; try writeConnections(rows)
    }
    func requireCurrentConnection(_ row: OwnerConnection) throws {
        guard let current = try ownerConnections().first(where: { $0.id == row.id }), current.enabled, current.revision == row.revision else { throw DeviceAPIError(409, "connection_changed", "The owner changed or revoked this connection. Review it before sending again.") }
    }
    func recordConnectionOutcome(_ row: OwnerConnection, failing: Bool) throws {
        var rows = try ownerConnections()
        guard let index = rows.firstIndex(where: { $0.id == row.id && $0.revision == row.revision }) else { return }
        rows[index].failing = failing; try writeConnections(rows)
    }
    func reserveConnectionSend(_ row: OwnerConnection, sender: String, now: Date = Date()) throws {
        try requireCurrentConnection(row)
        let senderHash = SHA256.hash(data: Data(sender.utf8)).map { String(format: "%02x", $0) }.joined()
        let key = "connection-rate:" + row.id + ":" + senderHash
        let previous: [Double]
        if let raw = try state(key) {
            guard let decoded = try? JSONDecoder().decode([Double].self, from: Data(raw.utf8)) else { throw DeviceAPIError(503, "connection_rate_unavailable", "The send limit could not be checked.") }
            previous = decoded
        } else { previous = [] }
        let recent = previous.filter { $0 > now.timeIntervalSince1970 - 3600 }
        guard recent.count < 60 else {
            var error = DeviceAPIError(429, "rate_limited", "At most 60 sends an hour to this connection from one sender.")
            error.connectionSendRetryAfterSeconds = 300
            throw error
        }
        try setState(key, value: String(decoding: JSONEncoder().encode(recent + [now.timeIntervalSince1970]), as: UTF8.self))
    }
}

public struct ConnectionHTTPRequest: Sendable {
    public let address: String
    public let method: String
    public let allowPrivate: Bool
    public let headers: [String: String]
    public let body: Data
    public let timeout: TimeInterval
    let callbackDestination: CallbackRules.Destination?
    public init(address: String, method: String = "POST", allowPrivate: Bool, headers: [String: String], body: Data, timeout: TimeInterval = 15) {
        self.method = method; self.address = address; self.allowPrivate = allowPrivate; self.headers = headers; self.body = body; self.timeout = timeout
        self.callbackDestination = nil
    }
    init(callbackAddress: String, destination: CallbackRules.Destination, headers: [String: String], body: Data) {
        self.method = "POST"; self.address = callbackAddress; self.allowPrivate = true; self.headers = headers; self.body = body
        self.timeout = CallbackRules.timeout; self.callbackDestination = destination
    }
}

/// The host never accepts an address or credentials from a sending agent.
public actor OutboundConnectionService {
    public typealias Transport = (ConnectionHTTPRequest, @escaping () async throws -> Void) async throws -> Int
    let store: AppletStore
    public typealias ReplyTransport = (ConnectionHTTPRequest, @escaping () async throws -> Void) async throws -> ConnectionHTTPResponse
    let transport: ReplyTransport
    let outboundAccess: OutboundAccess
    let deviceName: (@Sendable () async -> String)?
    var queueWorker: Task<Void, Never>?
    var queueEpoch = UUID()
    var drainingQueue = false
    public init(store: AppletStore, transport: Transport? = nil, replyTransport: ReplyTransport? = nil, outboundAccess: OutboundAccess = .unrestricted, deviceName: (@Sendable () async -> String)? = nil) {
        self.store = store; self.deviceName = deviceName; self.outboundAccess = outboundAccess
        if let replyTransport { self.transport = replyTransport }
        else if let transport { self.transport = { request, authorize in .init(status: try await transport(request, authorize)) } }
        else { self.transport = { request, authorize in try await PinnedConnectionHTTP.send(request, outboundAccess: outboundAccess, authorize: authorize) } }
    }
    public func list() async throws -> [[String: Any]] { try await store.ownerConnections().filter(\.enabled).map(\.agentDescription) }
    public func send(to id: String, text: String, data: [String: Any], sender: String, phone: String, appletName: String? = nil, photo: ConnectionSendPhoto? = nil, authorize: @escaping () async throws -> Void) async throws -> [String: Any] {
        try Self.validateMessage(text: text, data: data)
        guard let row = try await store.ownerConnections().first(where: { $0.id == id && $0.enabled }) else { throw DeviceAPIError(404, "not_found", "There is no enabled connection with that ID.") }
        guard !row.preset.needsText || !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !data.isEmpty else { throw DeviceAPIError(422, "invalid_parameter", "This service needs message text or data.") }
        let now = Date(), messageID = UUID().uuidString
        var fields = data
        fields["applet"] = appletName ?? (sender == "owner" ? "Bridgit" : sender)
        fields["flow"] = ""; fields["run_id"] = ""
        let timestamp = ISO8601DateFormatter(); timestamp.timeZone = .current
        fields["sent_at"] = timestamp.string(from: now)
        fields["phone"] = await deviceName?() ?? phone
        let request = try Self.request(row, text: text, data: fields, id: messageID, now: now, photo: photo?.jpeg)
        try await authorize()
        try outboundAccess.require()
        try await store.reserveConnectionSend(row, sender: sender, now: now)
        let response: ConnectionHTTPResponse
        do {
            response = try await transport(request) { [store] in
                try Task.checkCancellation(); try await authorize(); try await store.requireCurrentConnection(row)
            }
        } catch let error as DeviceAPIError where error.code == "external_paused" {
            throw error
        } catch {
            try? await store.recordConnectionOutcome(row, failing: true)
            throw error
        }
        try Task.checkCancellation(); try await authorize(); try await store.requireCurrentConnection(row)
        let status = response.status
        try? await store.recordConnectionOutcome(row, failing: !(200...299).contains(status))
        guard (200...299).contains(status) else { throw DeviceAPIError(502, "upstream_failed", "The connection did not accept the send (HTTP \(status)).") }
        var result = response.result(for: row)
        result["delivery_id"] = messageID
        return result
    }
    static func validateMessage(text: String, data: [String: Any]) throws {
        guard text.count <= 1000, text.utf8.count <= 16 * 1024, data.count <= 32,
              data.values.allSatisfy({ value in
                  if value is NSNull { return true }
                  if let text = value as? String { return text.count <= 4096 }
                  if let n = value as? NSNumber { return n.doubleValue.isFinite }
                  return false
              }), let encoded = try? JSONSerialization.data(withJSONObject: data), encoded.count <= 16 * 1024 else { throw DeviceAPIError(422, "invalid_parameter", "Use up to 1,000 text characters and 32 simple data values (16 KiB).") }
    }
    static func request(_ row: OwnerConnection, text: String, data: [String: Any], id: String, now: Date, photo: Data? = nil, waitReply: Bool = true) throws -> ConnectionHTTPRequest {
        if let photo {
            guard row.preset.takesPhoto else { throw DeviceAPIError(422, "photo_not_supported", "This service requires a photo link. Photo links are not available; omit photo or choose an inline-photo service.") }
            guard !photo.isEmpty, photo.count <= photoMaxBytes else { throw DeviceAPIError(413, "photo_too_large", "Use a JPEG no larger than 1,000,000 bytes.") }
        }
        let own: Set<String> = ["applet", "flow", "run_id", "sent_at", "phone"]
        func scalar(_ value: Any) -> String {
            if value is NSNull { return "null" }
            if let number = value as? NSNumber { return CFGetTypeID(number) == CFBooleanGetTypeID() ? (number.boolValue ? "true" : "false") : number.stringValue }
            return value as? String ?? ""
        }
        func lines(excluding: Set<String> = []) -> String {
            data.keys.sorted().filter { !own.contains($0) && !excluding.contains($0) }.map { $0 + ": " + scalar(data[$0]!) }.joined(separator: "\n")
        }
        func chatText(_ limit: Int, excluding: Set<String> = []) -> String {
            let result = [text, lines(excluding: excluding)].filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }.joined(separator: "\n")
            return String(result.prefix(limit))
        }
        // Android Connections.headerValue: collapse line breaks and encode non-ASCII headers once.
        func headerValue(_ value: String) -> String {
            let one = String(value.replacingOccurrences(of: "[\r\n]+", with: " ", options: .regularExpression).trimmingCharacters(in: .whitespacesAndNewlines).prefix(500))
            return one.unicodeScalars.allSatisfy { (32...126).contains($0.value) } ? one : "=?UTF-8?B?" + Data(one.utf8).base64EncodedString() + "?="
        }
        let custom = lines(), chat = chatText(4096)
        let messageTitle = (data["applet"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "Bridgit"
        var address = row.address, headers = row.headers, payload: [String: Any], method = "POST"
        switch row.presetID {
        case "discord":
            var parts = URLComponents(string: address)!
            parts.queryItems = (parts.queryItems ?? []).filter { $0.name != "wait" } + [.init(name: "wait", value: String(waitReply))]
            address = parts.string!; payload = ["content": String(chat.prefix(2000)), "allowed_mentions": ["parse": [] as [String]]]
        case "slack": payload = ["text": String(chat.prefix(3000))]
        case "telegram":
            var u = URLComponents(string: address)!
            var path = u.path
            for suffix in ["/sendMessage", "/sendPhoto"] where path.hasSuffix(suffix) { path.removeLast(suffix.count) }
            while path.hasSuffix("/") { path.removeLast() }
            u.path = path + (photo == nil ? "/sendMessage" : "/sendPhoto"); address = u.string!
            payload = ["chat_id": row.extra["chat_id"]!, "text": String(chat.prefix(4096))]
        case "pushover": payload = ["token": row.extra["token"]!, "user": row.extra["user"]!, "message": String(chat.prefix(1024)), "title": String(messageTitle.prefix(250))]
        case "ifttt": payload = ["value1": text.isEmpty ? custom : text, "value2": String(decoding: try JSONSerialization.data(withJSONObject: data, options: [.sortedKeys]), as: UTF8.self), "value3": ""]
        default: payload = data; if !text.isEmpty { payload["text"] = text }
        }
        let body: Data
        func multipart(_ fields: [(String, String)], file: String, jpeg: Data) -> Data {
            let boundary = "bridgit" + UUID().uuidString.replacingOccurrences(of: "-", with: "")
            headers["Content-Type"] = "multipart/form-data; boundary=" + boundary
            var result = Data()
            for (name, value) in fields {
                let type = value.hasPrefix("{") ? "application/json" : "text/plain; charset=utf-8"
                result.append(Data(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\nContent-Type: " + type + "\r\n\r\n" + value + "\r\n").utf8))
            }
            result.append(Data(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + file + "\"; filename=\"photo.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n").utf8))
            result.append(jpeg); result.append(Data(("\r\n--" + boundary + "--\r\n").utf8)); return result
        }
        if let photo, row.presetID == "discord" {
            let content = try JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys])
            body = multipart([("payload_json", String(decoding: content, as: UTF8.self))], file: "files[0]", jpeg: photo)
        } else if let photo, row.presetID == "telegram" {
            body = multipart([("chat_id", row.extra["chat_id"]!), ("caption", String(chat.prefix(1024)))], file: "photo", jpeg: photo)
        } else if let photo, row.presetID == "pushover" {
            body = multipart([("token", row.extra["token"]!), ("user", row.extra["user"]!), ("message", String(chat.prefix(1024))), ("title", String(messageTitle.prefix(250)))], file: "attachment", jpeg: photo)
        } else if row.presetID == "ntfy" {
            let title = data["title"].flatMap { $0 is NSNull ? nil : scalar($0) }.flatMap { $0.isEmpty ? nil : $0 } ?? messageTitle
            headers["X-Title"] = headerValue(title)
            if let raw = data["priority"] {
                let priority = scalar(raw)
                if ["1", "2", "3", "4", "5", "min", "low", "default", "high", "max", "urgent"].contains(priority) { headers["X-Priority"] = priority }
            }
            if let click = data["click"] as? String, click.hasPrefix("https://"), click.count < 400 { headers["X-Click"] = headerValue(click) }
            let message = chatText(4096, excluding: ["title", "priority", "click"])
            if let photo {
                method = "PUT"; headers["Filename"] = "photo.jpg"
                let caption = chatText(400, excluding: ["title", "priority", "click"])
                headers["X-Message"] = headerValue(caption.isEmpty ? messageTitle : caption)
                headers["Content-Type"] = "image/jpeg"; body = photo
            } else {
                body = Data((message.isEmpty ? "(no text)" : message).utf8)
                headers["Content-Type"] = "text/plain; charset=utf-8"
            }
        } else {
            if let photo { payload["photo"] = ["type": "image/jpeg", "data_base64": photo.base64EncodedString()] }
            body = try JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys]); headers["Content-Type"] = "application/json"
        }
        headers["User-Agent"] = "Bridgit-Connections/1"; headers["Idempotency-Key"] = id
        if row.preset.signs {
            let stamp = String(Int64(now.timeIntervalSince1970))
            func signature(_ secret: String) throws -> String {
                guard secret.hasPrefix("whsec_"), let key = Data(base64Encoded: String(secret.dropFirst(6))), key.count == 32 else { throw DeviceAPIError(503, "connection_secret_invalid", "The connection needs a new signing secret.") }
                let mac = HMAC<SHA256>.authenticationCode(for: Data((id + "." + stamp + ".").utf8) + body, using: SymmetricKey(data: key))
                return "v1," + Data(mac).base64EncodedString()
            }
            var signatures = [try signature(row.signingSecret)]
            if let previous = row.previousSecret, (row.previousUntil ?? 0) > now.timeIntervalSince1970 { signatures.append(try signature(previous)) }
            headers["webhook-id"] = id; headers["webhook-timestamp"] = stamp; headers["webhook-signature"] = signatures.joined(separator: " ")
        }
        return .init(address: address, method: method, allowPrivate: row.allowPrivate, headers: headers, body: body)
    }
}

extension OutboundConnectionService {
    public func configuration(_ request: HostRequest, agent: HostAgent, authorize: @escaping () async throws -> Void) async throws -> HostResponse {
        let operation: HostOperation = request.method == "GET" ? .configurationRead : .configurationWrite
        guard agent.allows(operation) else { throw DeviceAPIError(403, "agent_not_allowed", "Owner-issued configuration authority is required.") }
        return try await store.configureConnection(request, agent: agent, authorize: authorize)
    }
}
