import Foundation

/// One operation registry drives Apple HTTP discovery, OpenAPI and MCP. Discovery
/// describes implementation; the authority and runtime profile still decide execution.
struct HostAPIOperation {
    var name: String
    var method: String
    var path: String
    var grant: HostOperation
    var summary: String
    var variables: [String] {
        path.split(separator: "/").compactMap { $0.hasPrefix("{") ? String($0.dropFirst().dropLast()) : nil }
    }
    var requiresIdempotency: Bool { method != "GET" && name != "log_note" }
    var requiresBody: Bool { ["configuration_callback_set", "log_note", "feedback_create", "feedback_status", "feedback_followup", "mic_record", "inbound_shares_create", "configuration_connections_create", "configuration_connections_update", "configuration_connections_rotate", "connections_send", "account_link_phone", "agents_add", "agents_update", "agents_rotate", "agent_update_self", "files_upload", "interactions_send", "applets_rows_add", "applets_rows_update", "settings_change", "applets_photo_import", "applets_validate", "applets_install", "applets_update", "applets_pause", "applets_retry", "linked_devices_add", "linked_devices_rename", "linked_devices_attach", "linked_devices_pause"].contains(name) }

    private func pathPattern(_ variable: String) -> String {
        if name == "lan_brand_logo" { return "^[a-z0-9-]{1,64}$" }
        if name == "log_entry" || (name.hasPrefix("feedback_") && variable == "id") { return "^[0-9]+$" }
        return name == "applets_photo" && variable == "photo" ? "^[a-z0-9]{1,40}$" : "^[A-Za-z0-9_-]{1,128}$"
    }
    var schema: [String: Any] { schema(crashReporting: false) }

    func schema(crashReporting: Bool) -> [String: Any] {
        var properties: [String: Any] = ["query": querySchema]
        var required = variables
        for variable in variables { properties[variable] = ["type": "string", "pattern": pathPattern(variable)] }
        if method != "GET" {
            properties["idempotency_key"] = idempotencySchema
            properties["body"] = bodySchema(crashReporting: crashReporting)
            if requiresIdempotency { required.append("idempotency_key") }
            if requiresBody { required.append("body") }
        }
        return ["type": "object", "properties": properties, "required": required, "additionalProperties": false]
    }
    var idempotencySchema: [String: Any] {
        if name == "log_note" { return ["type": "string", "pattern": HostLogNote.keyPattern] }
        return ["type": "string", "minLength": 1, "maxLength": 128]
    }
    var querySchema: [String: Any] {
        var schema = baseQuerySchema
        let sample = path.replacingOccurrences(of: "{id}", with: "fixture")
        if SatelliteForwardPolicy.operation(method: method, path: sample) != nil {
            var fields = schema["properties"] as? [String: Any] ?? [:]
            fields["target"] = ["type": "string", "description": "An owner-assigned satellite ID from GET /v1/satellites. Omit to call this host."]
            schema["properties"] = fields
        }
        return schema
    }
    private var baseQuerySchema: [String: Any] {
        if name == "log_note" { return ["type": "object", "description": "Note creation ignores query fields."] }
        if ["log_list", "log_stats", "log_export"].contains(name) {
            let boundary: [String: Any] = ["type": ["string", "integer"], "description": "Nonnegative entry ID or ISO 8601 timestamp."]
            var properties: [String: Any] = ["since": boundary]
            if name != "log_stats" {
                properties.merge([
                    "channel": ["type": "string", "description": "Comma-separated audit, diagnostic, agent."],
                    "agent": ["type": "string", "description": "Comma-separated names or IDs; me for this agent, bridgit for system."],
                    "kind": ["type": "string", "description": "Comma-separated log kinds."],
                    "endpoint": ["type": "string", "description": "Literal endpoint prefix."],
                    "result": ["type": "string", "description": "ok, error, or a recorded error code."],
                    "level": ["enum": HostLogLevel.allCases.map(\.rawValue), "description": "Minimum severity."],
                    "until": boundary,
                    "q": ["type": "string", "description": "Literal message or endpoint text, up to 200 UTF-16 units."]
                ]) { _, new in new }
            }
            if name == "log_list" {
                properties["limit"] = ["type": "integer", "minimum": 1, "maximum": 500, "default": 100]
                properties["order"] = ["enum": ["asc", "desc"], "default": "desc"]
                properties["cursor"] = ["type": "string", "pattern": "^c[0-9a-z]+$"]
                properties["wait"] = ["type": "integer", "minimum": 0, "maximum": 55, "description": "Requires ascending order and an ID since."]
            }
            if name == "log_export" { properties["format"] = ["enum": ["jsonl", "csv"], "default": "jsonl"] }
            return ["type": "object", "properties": properties, "additionalProperties": false]
        }
        var fields: [String: Any] = [:]
        if name == "lan_brand_logo" { fields["raw"] = ["enum": ["1", "0", "true", "false", true, false, 1, 0]] }
        if name == "feedback_list" {
            fields = [
                "mine": ["type": "boolean"], "status": ["enum": HostFeedbackStatus.allCases.map(\.rawValue)],
                "kind": ["enum": ["bug", "feature"]], "since": ["type": "string", "format": "date-time"],
                "limit": ["type": "integer", "minimum": 1, "maximum": 100, "default": 50],
                "wait": ["type": "integer", "minimum": 0, "maximum": 55, "description": "Requires since."]
            ]
        }
        if name == "feedback_team_key" { fields = ["rotate": ["type": "boolean"]] }
        if name == "feedback_get" {
            fields = ["size": ["enum": ["agent", "thumb", "large", "original"]],
                      "max_edge_px": ["type": "integer", "minimum": 64, "maximum": 20_000],
                      "max_width_px": ["type": "integer", "minimum": 64, "maximum": 20_000],
                      "quality": ["type": "integer", "minimum": 40, "maximum": 100]]
        }
        if ["interactions_list", "events", "applets_rows_list"].contains(name) { fields["limit"] = ["type": "integer", "minimum": 1, "maximum": name == "applets_rows_list" ? 200 : 100] }
        if name == "applets_rows_list" { fields["since"] = ["type": "string", "maxLength": 1024] }
        if ["applets_data", "applets_export"].contains(name) { fields["format"] = ["enum": ["json", "csv"]]; fields["table"] = ["type": "string", "pattern": "^[a-z][a-z0-9_]{0,31}$"] }
        if ["applets_photo", "files_get", "shares_get", "downloads_get"].contains(name) { fields["offset"] = ["type": "integer", "minimum": 0]; fields["length"] = ["type": "integer", "minimum": 1, "maximum": 2 * 1024 * 1024] }
        if name == "interactions_list" { fields["status"] = ["enum": ["pending", "seen", "answered", "dismissed", "expired", "cancelled"]] }
        if ["interactions_get", "events"].contains(name) { fields["wait"] = ["type": "integer", "minimum": 0, "maximum": 55] }
        if name == "settings_reset" { fields["applet"] = ["type": "string", "pattern": "^[a-z][a-z0-9-]{0,63}$"] }
        if name == "api_changes" { fields["since"] = ["type": "integer", "minimum": 0, "default": 0] }
        if ["samples", "discover"].contains(name) { fields["id"] = ["type": "string", "maxLength": 128] }
        if name == "connections_deliveries" { fields["before"] = ["type": "string", "format": "uuid"] }
        if name == "events" { fields["since"] = ["type": "string", "pattern": "^[0-9]+$"]; fields["types"] = ["type": "string", "maxLength": 800] }
        if name == "mic_record" { fields["store"] = ["enum": ["none", ""]] }
        if name == "camera_snapshot" { fields["raw"] = ["enum": ["1", "0", true, false, 1, 0]] }
        return ["type": "object", "properties": fields, "additionalProperties": false]
    }
    var bodySchema: [String: Any] { bodySchema(crashReporting: false) }

    func bodySchema(crashReporting: Bool) -> [String: Any] {
        if name == "lan_discover" { return HostLanRequest.discoverSchema }
        if name == "configuration_callback_set" {
            return ["type": "object", "required": ["url"], "properties": ["url": ["type": "string", "maxLength": 2000, "writeOnly": true], "secret": ["type": "string", "pattern": "^cbs_[A-Za-z0-9_-]{43}$", "writeOnly": true]], "additionalProperties": false]
        }
        if name == "log_note" {
            return ["type": "object", "required": ["message"], "properties": [
                "message": ["type": "string", "minLength": 1, "maxLength": HostLogNote.messageBytes, "description": "Nonblank, at most 1024 UTF-8 bytes."],
                "level": ["enum": HostLogLevel.allCases.map(\.rawValue), "default": "info"],
                "data": ["type": "object", "description": "At most 4096 UTF-8 bytes of JSON, redacted before persistence."]
            ]]
        }
        if name == "feedback_create" {
            return ["type": "object", "required": ["kind", "description"], "properties": [
                "kind": ["enum": ["bug", "feature"]], "description": ["type": "string", "maxLength": 10_000, "writeOnly": true],
                "no_autofix": ["type": "boolean"], "agent_info": ["type": "object", "maxProperties": 20, "writeOnly": true],
                "images": ["type": "array", "maxItems": 5, "writeOnly": true, "description": "Fixer Mode only.",
                           "items": ["type": "object", "required": ["data_base64"], "properties": ["data_base64": ["type": "string"]]]]
            ]]
        }
        if name == "feedback_status" || name == "feedback_followup" {
            let note = ["type": "string", "maxLength": 500, "writeOnly": true] as [String: Any]
            if name == "feedback_status" {
                return ["type": "object", "required": ["status"], "properties": [
                    "status": ["enum": ["in_progress", "fixed", "wont_fix", "duplicate"]], "note": note]]
            }
            return ["type": "object", "properties": ["outcome": ["enum": ["works", "still_broken"]], "note": note]]
        }
        if name == "mic_record" {
            return ["type": "object", "additionalProperties": false, "required": ["duration_seconds"], "properties": [
                "duration_seconds": ["type": "integer", "minimum": 1, "maximum": MicrophoneClipRequest.maximumSeconds],
                "transcribe": ["type": "boolean", "default": false],
                "return_audio": ["type": "boolean", "default": true],
                "language": ["type": "string", "maxLength": 64],
                "store": ["enum": [[], ["none"], "none", NSNull()], "description": "Return only. Capture retention is unavailable."]
            ]]
        }
        if name == "inbound_shares_create" {
            return ["type": "object", "additionalProperties": false, "required": ["request_id", "mode", "recipient_id", "generation"],
                "oneOf": [["required": ["text"]], ["required": ["items"]]], "properties": [
                    "request_id": ["type": "string", "format": "uuid"], "mode": ["enum": ["transfer", "file_import"]],
                    "recipient_id": ["type": "string"], "generation": ["type": "string"],
                    "text": ["type": "string", "maxLength": 200000], "items": ["type": "array", "minItems": 1, "maxItems": 10,
                        "items": ["type": "object", "additionalProperties": false, "required": ["name", "mime", "data_base64"], "properties": ["name": ["type": "string", "maxLength": 120], "mime": ["type": "string", "maxLength": 100], "data_base64": ["type": "string"]]]]]]
        }
        if ["configuration_connections_create", "configuration_connections_update", "configuration_connections_rotate"].contains(name) {
            var fields: [String: Any] = [
                "name": ["type": "string", "minLength": 1, "maxLength": 80],
                "url": ["type": "string", "maxLength": 2048, "writeOnly": true],
                "preset": ["enum": ConnectionPreset.all.map(\.id)],
                "allow_private": ["type": "boolean"], "enabled": ["type": "boolean"],
                "headers": ["type": ["object", "null"], "maxProperties": 8, "additionalProperties": ["type": "string", "maxLength": 500], "writeOnly": true],
                "extra": ["type": ["object", "null"], "additionalProperties": ["type": "string", "maxLength": 200], "writeOnly": true],
                "signing_secret": ["type": "string", "pattern": "^whsec_[A-Za-z0-9+/]{43}=?$", "writeOnly": true]
            ]
            let required: [String]
            if name == "configuration_connections_rotate" {
                fields = ["signing_secret": fields["signing_secret"]!]; required = ["signing_secret"]
            } else { required = name == "configuration_connections_create" ? ["name", "url"] : [] }
            return ["type": "object", "properties": fields, "required": required, "minProperties": 1, "additionalProperties": false]
        }
        if name == "connections_send" {
            return ["type": "object", "properties": ["to": ["type": "string", "format": "uuid"],
                    "text": ["type": "string", "maxLength": 1000],
                    "photo": ["type": "string", "pattern": "^kept:[a-z0-9]{1,40}$", "description": "A kept photo in the sender's existing applet media-read scope"],
                    "data": ["type": ["object", "null"], "maxProperties": 32, "additionalProperties": ["type": ["string", "number", "boolean", "null"]]],
                    "wait_reply": ["type": "boolean", "default": false]], "required": ["to"], "additionalProperties": false]
        }
        if name == "agents_rotate" {
            return ["type": "object", "required": ["token_hash"], "additionalProperties": false, "properties": [
                "token_hash": ["type": "string", "pattern": "^[a-f0-9]{64}$", "writeOnly": true]
            ]]
        }
        if ["agents_add", "agents_update", "agent_update_self"].contains(name) {
            var fields: [String: Any] = ["name": ["type": "string", "maxLength": 80], "operations": ["type": "array", "items": ["enum": HostOperation.allCases.filter { $0 != .feedback }.map(\.rawValue)]], "applets": ["type": "array", "items": ["type": "string"]], "paused": ["type": "boolean"]]
            if name != "agents_add" {
                fields["name"] = ["type": "string", "maxLength": 24, "pattern": "^[A-Za-z0-9][A-Za-z0-9 _-]{0,23}$",
                    "description": "New control agent name after trimming, unique ignoring ASCII case."]
            }
            if name == "agents_update" {
                fields["notes"] = ["type": "string", "maxLength": 200, "writeOnly": true,
                    "description": "Owner notes for a different control agent. Use at most 200 UTF-16 code units; empty clears the notes. The administrator roster returns them."]
                fields["owner_direct_peers"] = ["type": "array", "maxItems": 4, "uniqueItems": true, "items": ["type": "string"],
                    "description": "For a direct agent only, allowed numeric Tailscale addresses. An empty array unlocks first-use binding."]
                fields["dismiss_owner_direct_peer_alert"] = ["type": "string", "description": "Dismiss this numeric Tailscale peer alert on a direct agent."]
            }
            if name == "agents_add" {
                fields["token_hash"] = ["type": "string", "pattern": "^[a-f0-9]{64}$", "description": "SHA-256 hex of a new locally generated rly_ bearer with 32 random bytes (base64url without padding)."]
                fields["peer"] = ["type": "string", "description": "Optional base64 P-256 X9.63 public key for sealed requests."]
            }
            return ["type": "object", "properties": fields, "required": name == "agents_add" ? ["name", "operations", "token_hash"] : [], "minProperties": 1, "additionalProperties": false]
        }
        if name == "files_upload" {
            return ["type": "object", "properties": ["name": ["type": "string", "maxLength": 120],
                    "mime": ["type": "string", "maxLength": 100], "data_base64": ["type": "string"]],
                    "required": ["name", "data_base64"], "additionalProperties": false]
        }
        if name == "account_link_phone" { return ["type": "object", "properties": ["code": ["type": "string", "pattern": "^[A-HJ-NP-Za-hj-np-z2-9]{4}-?[A-HJ-NP-Za-hj-np-z2-9]{4}$", "writeOnly": true]], "required": ["code"], "additionalProperties": false] }
        if name == "linked_devices_add" { return ["type": "object", "properties": ["setup": ["type": "string", "minLength": 1, "maxLength": 65536, "writeOnly": true]], "required": ["setup"], "additionalProperties": false] }
        if name == "linked_devices_rename" { return ["type": "object", "properties": ["name": ["type": ["string", "null"], "minLength": 1, "maxLength": 60]], "required": ["name"], "additionalProperties": false] }
        if name == "linked_devices_attach" { return ["type": "object", "properties": ["id": ["type": ["string", "null"], "maxLength": 160]], "required": ["id"], "additionalProperties": false] }
        if name == "linked_devices_pause" { return ["type": "object", "properties": ["paused": ["type": "boolean"]], "required": ["paused"], "additionalProperties": false] }
        if ["account_sign_in", "account_cancel_sign_in"].contains(name) { return ["type": "object", "maxProperties": 0] }
        if ["applets_validate", "applets_install", "applets_update"].contains(name) {
            return ["type": "object", "properties": ["package": ["type": "object"], "config": ["type": "object", "maxProperties": 0]], "required": ["package"], "additionalProperties": false]
        }
        if name == "applets_pause" { return ["type": "object", "properties": ["paused": ["type": "boolean"]], "required": ["paused"], "additionalProperties": false] }
        if name == "applets_retry" { return ["type": "object", "properties": ["acknowledge_possible_charge": ["const": true]], "required": ["acknowledge_possible_charge"], "additionalProperties": false] }
        if name == "applets_batch_start" { return ["type": "object", "maxProperties": 0] }
        if name == "applets_photo_import" { return PhotoImportOptions.schema }
        if name == "settings_change" { return HostSettingChange.schema(crashReporting: crashReporting) }
        if name == "interactions_send" { return InteractionValidation.schema }
        if name == "applets_rows_add" { return ["type": "object", "properties": ["row": ["type": "object"]], "required": ["row"], "additionalProperties": false] }
        if name == "applets_rows_update" { return ["type": "object", "properties": ["set": ["type": "object"]], "required": ["set"], "additionalProperties": false] }
        if name == "applets_flow_run" { return ["type": "object", "properties": ["with": ["type": "object"]], "additionalProperties": false] }
        if method == "DELETE" { return ["type": "object", "maxProperties": 0] }
        return ["type": "object"]
    }
    func request(_ arguments: [String: Any]) throws -> HostRequest {
        let fields = Set(variables + (method == "GET" ? ["query"] : ["query", "body", "idempotency_key"]))
        guard Set(arguments.keys).isSubset(of: fields) else { throw DeviceAPIError(422, "invalid_parameter", "Unsupported tool argument.") }
        guard !requiresBody || arguments["body"] is [String: Any] else { throw DeviceAPIError(422, "invalid_parameter", "This tool requires a body object.") }
        var route = path
        for variable in variables {
            guard let value = arguments[variable] as? String, value.range(of: pathPattern(variable), options: .regularExpression) != nil else { throw DeviceAPIError(422, "invalid_parameter", "Invalid path argument.") }
            route = route.replacingOccurrences(of: "{" + variable + "}", with: value)
        }
        if let value = arguments["query"], !(value is [String: Any]) { throw DeviceAPIError(422, "invalid_parameter", "Query must be an object.") }
        if let value = arguments["body"], !(value is [String: Any]) { throw DeviceAPIError(422, "invalid_parameter", "Body must be an object.") }
        if name == "log_note", let key = arguments["idempotency_key"], !(key is String) {
            throw DeviceAPIError(422, "invalid_parameter", "idempotency_key must be a string.")
        }
        return .init(method: method, path: route, query: arguments["query"] as? [String: Any] ?? [:], body: arguments["body"] as? [String: Any] ?? [:], idempotencyKey: arguments["idempotency_key"] as? String)
    }
}
/// Apple host revisions describe Apple implementations, not Android version numbers.
/// Increment this history when the Apple agent-facing contract changes.
public enum HostAPIChanges {
    public static let current = 9
    static func key(_ agent: HostAgent) -> String { "host-api-known:" + agent.id + ":" + agent.generation }
    static func document(since: Int, appVersion: String) -> [String: Any] {
        let revisions: [[String: Any]] = [
            ["revision": 1, "app_version": NSNull(), "date": NSNull(), "items": [["what": "Caller-scoped Apple host guide, OpenAPI and device profile", "endpoint": "/v1/guide"]]],
            ["revision": 2, "app_version": NSNull(), "date": NSNull(), "items": [["what": "Per-agent API revision and change notifications", "endpoint": "/v1/changes"]]],
            ["revision": 3, "app_version": NSNull(), "date": NSNull(), "items": [["what": "Owner-configured callback delivery and test", "endpoint": "/v1/callback/test"]]],
            ["revision": 4, "app_version": NSNull(), "date": NSNull(), "items": [["what": "Capture notification availability and owner OS-permission request", "endpoint": "/v1/settings"]]],
            ["revision": 5, "app_version": NSNull(), "date": NSNull(), "items": [["what": "Wi-Fi history location authorization status and explicit permission initiation", "endpoint": "/v1/settings"]]],
            ["revision": 6, "app_version": NSNull(), "date": NSNull(), "items": [["what": "Read, change and reset the Bridgit device name independently of the OS name", "endpoint": "/v1/settings"]]],
            ["revision": 7, "app_version": NSNull(), "date": NSNull(), "items": [["what": "Read, change and reset crash reporting on configured iPhone and iPad builds", "endpoint": "/v1/settings"]]],
            ["revision": 8, "app_version": NSNull(), "date": NSNull(), "items": [["what": "Crash reporting settings on configured Mac builds", "endpoint": "/v1/settings"]]],
            ["revision": 9, "app_version": NSNull(), "date": "2026-11-08", "items": [["what": "Bridgit names: register this Bridgit's Device Domain Name, change its label and visibility, or hide it (bridgit_name in capabilities); a visible name sends a heartbeat", "endpoint": "/v1/owner/configuration/name"]]]
        ]
        return ["api_revision": current, "app_version": appVersion, "since": since,
                "changes": revisions.filter { ($0["revision"] as? Int ?? 0) > since },
                "links": ["guide": "/v1/guide", "openapi": "/v1/openapi.json", "capabilities": "/v1/capabilities", "changes": "/v1/changes"]]
    }
}

enum HostAPIContract {
    static let operations: [HostAPIOperation] = TransferKind.allCases.flatMap { kind -> [HostAPIOperation] in
        let name = kind.rawValue, path = "/v1/" + name
        return [
            .init(name: name + "_list", method: "GET", path: path, grant: kind.grant, summary: "List this agent's private " + name + " transfers"),
            .init(name: name + "_get", method: "GET", path: path + "/{id}", grant: kind.grant, summary: "Read one transfer. Use offset and length for chunks up to 2 MB."),
            .init(name: name + "_delete", method: "DELETE", path: path + "/{id}", grant: kind.grant, summary: "Delete one of this agent's transfers")
        ]
    } + [
        .init(name: "lan_catalog", method: "GET", path: "/v1/lan/catalog", grant: .lanDiscovery, summary: "Bundled local network discovery coverage; no scan"),
        .init(name: "lan_discovered", method: "GET", path: "/v1/lan/discovered", grant: .lanDiscovery, summary: "Remembered devices on the current LAN; no scan"),
        .init(name: "lan_brand_logo", method: "GET", path: "/v1/lan/brands/{id}/logo", grant: .lanDiscovery, summary: "Bundled manufacturer logo; raw=1 returns passive image bytes"),
        .init(name: "lan_discover", method: "POST", path: "/v1/lan/discover", grant: .lanDiscovery, summary: "Bounded discovery on this device's direct local network"),
        .init(name: "configuration_callbacks", method: "GET", path: "/v1/owner/configuration/callbacks", grant: .configurationRead, summary: "List callback presence per agent. Destinations and signing keys are write-only."),
        .init(name: "configuration_callback_set", method: "POST", path: "/v1/owner/configuration/callbacks/{id}", grant: .configurationWrite, summary: "Set an agent callback URL and receiver-provided signing key under the current owner network policy."),
        .init(name: "configuration_callback_remove", method: "DELETE", path: "/v1/owner/configuration/callbacks/{id}", grant: .configurationWrite, summary: "Remove an agent callback. Pending deliveries consult the current configuration at their next attempt."),
        .init(name: "configuration_name_read", method: "GET", path: "/v1/owner/configuration/name", grant: .configurationRead, summary: "Read this Bridgit's Device Domain Name (bridgit.cc/names), label and visibility from the names service."),
        .init(name: "configuration_name_set", method: "POST", path: "/v1/owner/configuration/name", grant: .configurationWrite, summary: "Register a Device Domain Name {name} when this Bridgit has none (first come, first served; a Bridgit keeps its first name), or change {label, visible}. label null clears it. A visible name sends a heartbeat."),
        .init(name: "configuration_name_hide", method: "DELETE", path: "/v1/owner/configuration/name", grant: .configurationWrite, summary: "Hide the name and clear its label. The name stays this Bridgit's."),
        .init(name: "callback_test", method: "POST", path: "/v1/callback/test", grant: .interactionsRead, summary: "Send a signed test event to this agent’s owner-configured callback."),
        .init(name: "inbound_shares_list", method: "GET", path: "/v1/owner/configuration/inbound-shares", grant: .configurationRead, summary: "Read system-share availability, minimal current recipients and metadata-only staging/receipt status. No payloads or host credentials."),
        .init(name: "inbound_shares_create", method: "POST", path: "/v1/owner/configuration/inbound-shares", grant: .configurationWrite, summary: "Stage owner-supplied text or items for an exact recipient/generation. Also requires caller's files or shares action grant. Stable UUID request_id prevents duplicate/retargeted retries. Transfer: ten items, 25MiB each and 200000 UTF16 text units. File import: one item, 512KiB, JPEG/PNG/WebP, MP4/MPEG/OGG audio, PDF or plain text; text clipped to32768 units. Existing host request-size limits apply; generic files API stays unchanged. Delivery atomically rechecks current feature, agent mode/generation, pause and grant."),
        .init(name: "inbound_share_read", method: "GET", path: "/v1/owner/configuration/inbound-shares/{id}", grant: .configurationRead, summary: "Read a metadata-only share receipt; queued is not delivered."),
        .init(name: "inbound_share_cancel", method: "DELETE", path: "/v1/owner/configuration/inbound-shares/{id}", grant: .configurationWrite, summary: "Cancel pending staging. An interrupted in-progress delivery reports cancel_requested until its private atomic receipt resolves; completed transfers cannot be recalled."),
        .init(name: "inbound_share_retry", method: "POST", path: "/v1/owner/configuration/inbound-shares/{id}/retry", grant: .configurationWrite, summary: "Explicitly retry a rejected share to the same generation/recipient; also requires the files/shares action grant. No retarget or duplicate completed delivery."),
        .init(name: "configuration_connections_read", method: "GET", path: "/v1/owner/configuration/connections", grant: .configurationRead, summary: "Read connection metadata, presets and configured flags. URLs, headers, extra credentials and signing keys stay write-only."),
        .init(name: "configuration_connections_create", method: "POST", path: "/v1/owner/configuration/connections", grant: .configurationWrite, summary: "Provision a receiver-owned connection under explicit owner-issued settings.write authority; no additional manual approval. Use Idempotency-Key. Receiver-provided signing_secret is write-only; creates no send grant."),
        .init(name: "configuration_connections_update", method: "PATCH", path: "/v1/owner/configuration/connections/{id}", grant: .configurationWrite, summary: "Update native connection fields. Omitted preserves; null headers/extra clears subject to preset validation. Credentials stay write-only."),
        .init(name: "configuration_connections_remove", method: "DELETE", path: "/v1/owner/configuration/connections/{id}", grant: .configurationWrite, summary: "Revoke and erase this destination. In-flight sends recheck before writing; a send already received cannot be recalled. Revocation cancels pending deliveries and removes their queued photo copies."),
        .init(name: "configuration_connections_rotate", method: "POST", path: "/v1/owner/configuration/connections/{id}/rotate", grant: .configurationWrite, summary: "Replace signing_secret with 32 receiver-owned random bytes (whsec_ base64). Previous key signs for 24h; same-value retries do not extend overlap. Neither key is returned."),
        .init(name: "connections_list", method: "GET", path: "/v1/connections", grant: .connections, summary: "List owner-approved outbound connections by ID, name and service. No addresses, headers or secrets."),
        .init(name: "connections_send", method: "POST", path: "/v1/connections/send", grant: .connections, summary: "Send text/data to an owner-approved connection. Queued with durable retries by default; wait_reply:true sends once and returns bounded redacted JSON/text. Queue IDs are stable for receiver idempotency; delivery can be at least once. Inline kept photos require the sender's existing applet media-read grant and applet scope. Photo links and workflow sends are not implemented."),
        .init(name: "connections_deliveries", method: "GET", path: "/v1/connections/deliveries", grant: .connections, summary: "Read your current generation's safe delivery receipts, 100 per page. Follow next using before. No messages, addresses or credentials."),
        .init(name: "connections_delivery", method: "GET", path: "/v1/connections/deliveries/{id}", grant: .connections, summary: "Read your delivery status, attempts and uncertain outcome. No message body or credentials."),
        .init(name: "connections_cancel", method: "DELETE", path: "/v1/connections/deliveries/{id}", grant: .connections, summary: "Cancel your pending delivery. If bytes had started, may_have_arrived is true; cancellation cannot undo receiver effects."),
        .init(name: "agent_self", method: "GET", path: "/v1/agents/me", grant: .configurationRead, summary: "Read your name and current grants."),
        .init(name: "agent_update_self", method: "PATCH", path: "/v1/agents/me", grant: .configurationWrite, summary: "Rename yourself or narrow your grants and applet scope. Retain settings.write to receive the reply."),
        .init(name: "agents_list", method: "GET", path: "/v1/agents", grant: .administrator, summary: "List host agents, grants, pause state and owner notes."),
        .init(name: "agents_add", method: "POST", path: "/v1/agents", grant: .administrator, summary: "Register an agent using its token hash and explicit grants."),
        .init(name: "agents_rotate", method: "POST", path: "/v1/agents/{id}/rotate", grant: .administrator, summary: "Replace the credential of a different paired control agent with a fresh token hash, preserving the agent ID and grants. No key is returned."),
        .init(name: "agents_update", method: "PATCH", path: "/v1/agents/{id}", grant: .administrator, summary: "Rename, edit owner notes, replace grants or applet scopes, or pause/resume another agent. The calling administrator must retain active access."),
        .init(name: "agents_remove", method: "DELETE", path: "/v1/agents/{id}", grant: .administrator, summary: "Revoke another host agent."),
        .init(name: "files_upload", method: "POST", path: "/v1/files", grant: .files, summary: "Send a private file as base64 in a JSON request up to 1 MB. Retained for 30 days."),
        .init(name: "satellites_list", method: "GET", path: "/v1/satellites", grant: .deviceRead, summary: "List only the satellites assigned to this agent by the owner, with last observed readiness and supported endpoints. A target never falls back to this host."),
        .init(name: "discover", method: "GET", path: "/v1/discover", grant: .deviceRead, summary: "Read caller-scoped operation discovery, optionally filtered by runtime ID"),
        .init(name: "openapi", method: "GET", path: "/v1/openapi.json", grant: .deviceRead, summary: "Read this caller's OpenAPI contract"),
        .init(name: "guide", method: "GET", path: "/v1/guide", grant: .deviceRead, summary: "Read the Apple host operation guide"),
        .init(name: "account_phones", method: "GET", path: "/v1/account/phones", grant: .configurationRead, summary: "List devices on the signed-in account with nullable self-reported platform and device_type. These descriptive facts do not imply permissions."),
        .init(name: "account_link_phone", method: "POST", path: "/v1/account/phones", grant: .configurationWrite, summary: "Link a phone using its one-time account code. Relay pairing is separate."),
        .init(name: "account_unlink_phone", method: "DELETE", path: "/v1/account/phones/{id}", grant: .configurationWrite, summary: "Remove a phone and its stored account history, keeping independent relay pairings."),
        .init(name: "account_read", method: "GET", path: "/v1/account", grant: .configurationRead, summary: "Read sign-in status and account email; session credentials are never returned"),
        .init(name: "account_sign_in", method: "POST", path: "/v1/account/sign-in", grant: .configurationWrite, summary: "Initiate the real system web sign-in sheet; requires its human account sign-in steps; poll /v1/account"),
        .init(name: "account_cancel_sign_in", method: "DELETE", path: "/v1/account/sign-in", grant: .configurationWrite, summary: "Cancel the current system sign-in sheet"),
        .init(name: "account_sign_out", method: "DELETE", path: "/v1/account", grant: .configurationWrite, summary: "Sign out the account, invalidate stale replies and clear saved session credentials; independent pairing grants remain"),
        .init(name: "linked_devices_read", method: "GET", path: "/v1/linked-devices", grant: .configurationRead, summary: "Read device access and roles, attachment and view-pause support. A relay view pairs a local satellite with a remote hub. Account snapshots alone assign no relationship."),
        .init(name: "linked_devices_add", method: "POST", path: "/v1/linked-devices", grant: .configurationWrite, summary: "Save a supplied pairing link/setup through the same claim and Keychain path as the owner UI; credentials write-only; creates no local-host authority"),
        .init(name: "linked_devices_rename", method: "PATCH", path: "/v1/linked-devices/pairings/{id}", grant: .configurationWrite, summary: "Set a local link alias, or name=null to clear it; the reported device name is preserved"),
        .init(name: "linked_devices_remove", method: "DELETE", path: "/v1/linked-devices/pairings/{id}", grant: .configurationWrite, summary: "Remove this local link and its credential; leaves the remote device's domain data intact"),
        .init(name: "linked_devices_attach", method: "POST", path: "/v1/linked-devices/attachment", grant: .configurationWrite, summary: "Attach/select a known device ID, local, or null to detach; creates no sensor/control grant"),
        .init(name: "linked_devices_pause", method: "POST", path: "/v1/linked-devices/pause", grant: .configurationWrite, summary: "Set the native global view-pause control when supported; inspect supports_views_pause"),
        .init(name: "settings_read", method: "GET", path: "/v1/settings", grant: .configurationRead, summary: "Read local settings, configuration authority and OS readiness, including capture notification permission and remaining owner action; secrets excluded"),
        .init(name: "settings_change", method: "POST", path: "/v1/settings", grant: .configurationWrite, summary: "Queue an authorized settings change; applies automatically after response delivery, preserving OS consent. The capture_notifications_request action asks the OS for permission while Bridgit is active; the owner must respond. Poll its request URL for outcome; if still queued, repeat the original action with the same Idempotency-Key"),
        .init(name: "settings_reset", method: "DELETE", path: "/v1/settings/{field}", grant: .configurationWrite, summary: "Queue an authorized reset to the default; applies after response delivery, preserving OS consent"),
        .init(name: "settings_request", method: "GET", path: "/v1/settings/requests/{id}", grant: .configurationRead, summary: "Read this caller's configuration operation status; reads never execute. If queued, repeat the original action with the same Idempotency-Key"),
        .init(name: "settings_cancel", method: "DELETE", path: "/v1/settings/requests/{id}", grant: .configurationWrite, summary: "Cancel this caller's pending configuration request"),
        .init(name: "samples", method: "GET", path: "/v1/samples", grant: .deviceRead, summary: "Read bundled interaction samples; id=html-connection-test checks the answer path"),
        .init(name: "device_health", method: "GET", path: "/v1/health", grant: .deviceRead, summary: "Read host health"),
        .init(name: "api_changes", method: "GET", path: "/v1/changes", grant: .deviceRead, summary: "Read Apple host API changes since a revision"),
        .init(name: "device_capabilities", method: "GET", path: "/v1/capabilities", grant: .deviceRead, summary: "Read current capability readiness"),
        .init(name: "device_profile", method: "GET", path: "/v1/device/profile", grant: .deviceRead, summary: "Read the caller-filtered device profile"),
        .init(name: "feedback_list", method: "GET", path: "/v1/feedback", grant: .feedback, summary: "List reports you filed, or all reports in Fixer Mode."),
        .init(name: "feedback_team_key", method: "GET", path: "/v1/feedback/team-key", grant: .feedback, summary: "Read or rotate your own Bridgit team key when feedback sending is enabled. Never put this credential in logs."),
        .init(name: "feedback_get", method: "GET", path: "/v1/feedback/{id}", grant: .feedback, summary: "Read your report and history. Fixer Mode also allows attachments, context and first-read acknowledgement."),
        .init(name: "feedback_create", method: "POST", path: "/v1/feedback", grant: .feedback, summary: "File a bug or feature request. Only Fixer Mode can attach supplied images."),
        .init(name: "feedback_status", method: "POST", path: "/v1/feedback/{id}/status", grant: .feedbackFixer, summary: "Move a report under the Fixer Mode transition rules."),
        .init(name: "feedback_followup", method: "POST", path: "/v1/feedback/{id}/followup", grant: .feedback, summary: "Comment on a report you filed or confirm or reopen it after triage."),
        .init(name: "log_list", method: "GET", path: "/v1/log", grant: .log, summary: "Read scoped Activity, page via next_cursor, or tail new entries for up to 55 seconds."),
        .init(name: "log_entry", method: "GET", path: "/v1/log/{id}", grant: .log, summary: "Read a single Activity entry within the current visibility scope."),
        .init(name: "log_stats", method: "GET", path: "/v1/log/stats", grant: .log, summary: "Count requests and errors by agent and endpoint; latency percentiles use measured samples."),
        .init(name: "log_export", method: "GET", path: "/v1/log/export", grant: .log, summary: "Export all matching Activity as JSONL or CSV, within the relay result limit."),
        .init(name: "log_note", method: "POST", path: "/v1/log", grant: .log, summary: "Write a redacted agent-channel note, up to 60 per minute. Returns 201 with ok=true and channel=agent."),
        .init(name: "mic_record", method: "POST", path: "/v1/mic/record", grant: .microphoneRecord, summary: "Record an M4A clip with owner microphone consent and optional offline transcription"),
        .init(name: "camera_snapshot", method: "POST", path: "/v1/camera/snapshot", grant: .cameraSnapshot, summary: "Capture a bounded JPEG with owner camera consent"),
        .init(name: "interactions_send", method: "POST", path: "/v1/interactions", grant: .interactionsWrite, summary: "Send a normal-priority inbox notice, question or HTML card; no_takeover=true required"),
        .init(name: "interactions_list", method: "GET", path: "/v1/interactions", grant: .interactionsRead, summary: "List this agent's cards"),
        .init(name: "interactions_get", method: "GET", path: "/v1/interactions/{id}", grant: .interactionsRead, summary: "Read a card or wait up to 55 seconds for its answer"),
        .init(name: "interactions_cancel", method: "DELETE", path: "/v1/interactions/{id}", grant: .interactionsWrite, summary: "Cancel this agent's pending card"),
        .init(name: "events", method: "GET", path: "/v1/events", grant: .interactionsRead, summary: "Read scoped interaction events with since, types, limit and wait"),
        .init(name: "applets_validate", method: "POST", path: "/v1/applets/validate", grant: .appletsManage, summary: "Preflight an inline package without installation, grants, sensors or paid work. Unsupported package execution is rejected. URL imports and nonempty config are not supported yet"),
        .init(name: "applets_install", method: "POST", path: "/v1/applets/install", grant: .appletsManage, summary: "Install a preflighted inline package under explicit owner-issued applet management authority; camera, speech and paid models keep their separate grants"),
        .init(name: "applets_update", method: "POST", path: "/v1/applets/installed/{applet}/update", grant: .appletsManage, summary: "Update the matching package without destructive table migration; refuses active work"),
        .init(name: "applets_pause", method: "POST", path: "/v1/applets/installed/{applet}/pause", grant: .appletsManage, summary: "Persist pause/resume and invalidate old executions; active paid work may already have been submitted"),
        .init(name: "applets_remove", method: "DELETE", path: "/v1/applets/installed/{applet}", grant: .appletsManage, summary: "Remove an idle applet and its domain rows, photos and indexed checkpoints; reports leftover files/cache. Paid usage and idempotency audits retain their own policy"),
        .init(name: "applets_batches", method: "GET", path: "/v1/applets/installed/{applet}/batches", grant: .appletsRead, summary: "Read declared per-button batch progress; interrupted work is not automatically repeated"),
        .init(name: "applets_batch_start", method: "POST", path: "/v1/applets/installed/{applet}/batches/{button}/start", grant: .workflowsRun, summary: "Start a declared batch after validating all inputs. Current caller, speech and cloud grants remain enforced throughout work"),
        .init(name: "applets_batch_cancel", method: "DELETE", path: "/v1/applets/installed/{applet}/batches/{button}", grant: .workflowsRun, summary: "Cancel the named batch and wait for its current task to stop"),
        .init(name: "applets_retry", method: "POST", path: "/v1/applets/installed/{applet}/retry", grant: .workflowsRun, summary: "Explicitly acknowledge another possible charge and clear this idle applet's uncertain execution markers; does not start paid work"),
        .init(name: "applets_data", method: "GET", path: "/v1/applets/installed/{applet}/data", grant: .appletsRead, summary: "Discover bridgit-applet-data/2 schemas, saved processing states, pagination and photo links without executing anything"),
        .init(name: "applets_export", method: "GET", path: "/v1/applets/installed/{applet}/data/export", grant: .appletsRead, summary: "Export all saved tables as JSON or one table as CSV; agents create document formats outside the app"),
        .init(name: "applets_photo", method: "GET", path: "/v1/applets/installed/{applet}/photos/{photo}", grant: .appletsMediaRead, summary: "Read an existing saved photo; optional offset/length chunks, no capture or model call"),
        .init(name: "applets_photo_import", method: "POST", path: "/v1/applets/installed/{applet}/photos", grant: .appletsMediaWrite, summary: "Import a supplied photo into a declared table; normalized to JPEG with no automatic model call"),
        .init(name: "applets_installed_list", method: "GET", path: "/v1/applets/installed", grant: .appletsRead, summary: "List granted local applets"),
        .init(name: "applets_installed_get", method: "GET", path: "/v1/applets/installed/{applet}", grant: .appletsRead, summary: "Read a granted applet"),
        .init(name: "applets_rows_list", method: "GET", path: "/v1/applets/installed/{applet}/tables/{table}/rows", grant: .appletsRead, summary: "Read applet rows"),
        .init(name: "applets_rows_add", method: "POST", path: "/v1/applets/installed/{applet}/tables/{table}/rows", grant: .appletsWrite, summary: "Add a validated row"),
        .init(name: "applets_rows_delete_all", method: "DELETE", path: "/v1/applets/installed/{applet}/tables/{table}/rows", grant: .appletsWrite, summary: "Atomically delete all rows from one declared maintained-library table; requires an idempotency key"),
        .init(name: "applets_rows_delete", method: "DELETE", path: "/v1/applets/installed/{applet}/tables/{table}/rows/{row}", grant: .appletsWrite, summary: "Delete an applet row; referenced photos retain their existing collection policy"),
        .init(name: "applets_rows_update", method: "PATCH", path: "/v1/applets/installed/{applet}/tables/{table}/rows/{row}", grant: .appletsWrite, summary: "Update a validated row"),
        .init(name: "applets_flow_run", method: "POST", path: "/v1/applets/installed/{applet}/flows/{flow}/run", grant: .workflowsRun, summary: "Run a granted called flow; camera, speech and cloud consent stay separate")
    ]
    static func allowed(_ agent: HostAgent, lanAvailable: Bool = false, callbacksAvailable: Bool = false) -> [HostAPIOperation] {
        if agent.viewer == true {
            guard agent.viewerClaimUntil == nil else { return [] }
            return HostViewerPairing.reads.sorted().enumerated().map { index, path in
                HostAPIOperation(name: "viewer_read_" + String(index), method: "GET", path: path, grant: .deviceRead, summary: "Read owner-approved native viewer data.")
            }
        }
        return operations.filter { operation in
            guard operation.grant != .lanDiscovery || lanAvailable else { return false }
            guard callbacksAvailable || !["configuration_callbacks", "configuration_callback_set", "configuration_callback_remove", "callback_test"].contains(operation.name) else { return false }
            guard agent.allows(operation.grant),
                  agent.ownerDirectPeers == nil || OwnerDirectHostService.permitsOperation(operation) else { return false }
            guard operation.variables.contains("applet"), !agent.hasGlobalAppletScope(for: operation.grant) else { return true }
            return agent.applets.contains { applet in
                agent.ownerDirectPeers == nil || OwnerDirectHostService.supportsApplet(applet, operation: operation)
            }
        }
    }
    static func operation(matching request: HostRequest) -> HostAPIOperation? {
        if let exact = operations.first(where: { $0.method == request.method && $0.path == request.path }) { return exact }
        let parts = request.path.split(separator: "/")
        return operations.first { operation in
            guard operation.method == request.method, !operation.variables.isEmpty else { return false }
            let template = operation.path.split(separator: "/")
            return template.count == parts.count && zip(template, parts).allSatisfy {
                $0 == $1 || $0.hasPrefix("{")
            }
        }
    }

    static func discovery(_ agent: HostAgent, id: String? = nil, lanAvailable: Bool = false, callbacksAvailable: Bool = false) -> [String: Any] {
        let entries = Dictionary(grouping: allowed(agent, lanAvailable: lanAvailable, callbacksAvailable: callbacksAvailable), by: { $0.grant.capability }).sorted { $0.key < $1.key }.compactMap { runtime, operations -> [String: Any]? in
            guard id == nil || id == runtime else { return nil }
            return ["id": runtime, "runtime_id": runtime, "operations": operations.map { ["id": $0.name, "method": $0.method, "path": $0.path, "summary": $0.summary] }, "applets": runtime == "applets" || runtime == "workflows" ? agent.applets.sorted() : [], "profile_url": "/v1/device/profile"]
        }
        return ["capabilities": entries, "scope": "this_device", "idempotency": ["retention_seconds": Int(HostIdempotencyPolicy.retentionSeconds), "per_caller_limit": HostIdempotencyPolicy.perCallerLimit, "device_limit": HostIdempotencyPolicy.deviceLimit, "cached_bytes": HostIdempotencyPolicy.cachedBytes, "compacted_result": "idempotency_result_expired"]]
    }
    static func openAPI(_ agent: HostAgent, lanAvailable: Bool = false, callbacksAvailable: Bool = false, crashReporting: Bool = false) -> [String: Any] {
        let direct = agent.ownerDirectPeers != nil
        let securityName = direct ? "directAgent" : "relayAgent"
        let securityDescription = direct
            ? "Owner-issued direct bearer key from a currently allowed Tailscale device."
            : "Relay credentials and optionally authenticated sealed envelopes; grants are enforced on the device."
        var paths: [String: [String: Any]] = [:]
        for operation in allowed(agent, lanAvailable: lanAvailable, callbacksAvailable: callbacksAvailable) {
            var item: [String: Any] = ["operationId": operation.name, "summary": operation.summary, "responses": ["200": ["description": "Success"], "201": ["description": "Created"], "4XX": ["description": "Refused by validation, scope, readiness or limits"]], "security": [[securityName: []]]]
            var parameters: [[String: Any]] = operation.variables.map { variable in
                ["name": variable, "in": "path", "required": true,
                 "schema": operation.name == "log_entry" ? ["type": "string", "pattern": "^[0-9]+$"] : ["type": "string"]]
            }
            for (name, schema) in operation.querySchema["properties"] as? [String: Any] ?? [:] { parameters.append(["name": name, "in": "query", "required": false, "schema": schema]) }
            item["parameters"] = parameters
            if operation.method != "GET" {
                var parameters = item["parameters"] as! [[String: Any]]
                parameters.append(["name": "Idempotency-Key", "in": "header", "required": operation.requiresIdempotency, "schema": operation.idempotencySchema])
                item["parameters"] = parameters
                item["requestBody"] = ["required": operation.requiresBody, "content": ["application/json": ["schema": operation.bodySchema(crashReporting: crashReporting)]]]
            }
            if operation.name == "log_export" {
                if direct { item["summary"] = "Stream all matching Activity as JSONL or CSV." }
                var responses: [String: Any] = ["200": ["description": "Complete scoped export", "content": [
                    "application/x-ndjson": ["schema": ["type": "string"]],
                    "text/csv": ["schema": ["type": "string"]]
                ]], "4XX": ["description": "Invalid filter or refused scope"]]
                if !direct { responses["413"] = ["description": "Relay result limit"] }
                item["responses"] = responses
            }
            if operation.name == "log_note" {
                item["responses"] = [
                    "201": ["description": "Note written", "content": ["application/json": ["schema": [
                        "type": "object", "required": ["ok", "channel"],
                        "properties": ["ok": ["const": true], "channel": ["const": "agent"]]
                    ]]]],
                    "429": ["description": "The caller exhausted its rolling 60-note limit. Retry-After gives the delay."],
                    "4XX": ["description": "Refused by validation, scope or readiness"]
                ]
            }
            paths[operation.path, default: [:]][operation.method.lowercased()] = item
        }
        return ["openapi": "3.1.0", "info": ["title": "Bridgit Apple host", "version": "local-m3"], "paths": paths, "components": ["securitySchemes": [securityName: ["type": "http", "scheme": "bearer", "description": securityDescription]]]]
    }
    static func tools(_ agent: HostAgent, lanAvailable: Bool = false, callbacksAvailable: Bool = false, crashReporting: Bool = false) -> [[String: Any]] {
        allowed(agent, lanAvailable: lanAvailable, callbacksAvailable: callbacksAvailable).map { ["name": $0.name, "description": $0.summary, "inputSchema": $0.schema(crashReporting: crashReporting)] }
    }
}

public enum HostAPIRoutes {
    public static func operations(for agent: HostAgent, capability: String, installedApplets: Set<String> = [], lanAvailable: Bool = false, callbacksAvailable: Bool = false) -> [ProfileOperation] {
        HostAPIContract.allowed(agent, lanAvailable: lanAvailable, callbacksAvailable: callbacksAvailable).filter { $0.grant.capability == capability }.flatMap { operation -> [ProfileOperation] in
            if operation.variables.contains("applet") {
                let scope = agent.hasGlobalAppletScope(for: operation.grant) ? agent.applets.union(installedApplets) : agent.applets
                return scope.sorted().compactMap { applet in
                    if agent.ownerDirectPeers != nil, !OwnerDirectHostService.supportsApplet(applet, operation: operation) { return nil }
                    return .init(method: operation.method, path: operation.path.replacingOccurrences(of: "{applet}", with: applet))
                }
            }
            return [.init(method: operation.method, path: operation.path)]
        }
    }
}

/// Static route support only. Device readiness and caller grants must be checked separately.
public enum HostOperationCatalog {
    public static func contains(method: String, path: String) -> Bool {
        if path == "/v1/mcp" { return HostRequest(method: method, path: path).route != nil }
        return HostAPIContract.operations.contains { $0.method == method && $0.path == path }
    }
}
