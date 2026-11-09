import CoreFoundation
import Foundation

/// settings.write is a durable owner-issued grant to configure app-owned settings.
/// Actual OS consent remains with the platform; a caller cannot create its own grant.
public protocol HostConfigurationService: AnyObject {
    func configuration(_ request: HostRequest, agent: HostAgent) async throws -> HostResponse
    func supportsCrashReporting() async -> Bool
    func attachDelivery(to response: HostResponse, agent: HostAgent) async -> HostResponse
    /// This Bridgit's Device Domain Name hint for health and capabilities; nil where names are unavailable.
    func bridgitNameHint(canConfigure: Bool) async -> [String: Any]?
}

public extension HostConfigurationService {
    func supportsCrashReporting() async -> Bool { false }
    func attachDelivery(to response: HostResponse, agent: HostAgent) async -> HostResponse { response }
    func bridgitNameHint(canConfigure: Bool) async -> [String: Any]? { nil }
}

public enum HostActivityRetentionField: String, CaseIterable, Sendable {
    case entries = "log_retention_entries"
    case days = "log_retention_days"

    public var range: ClosedRange<Int> {
        switch self {
        case .entries: 0...100_000
        case .days: 0...365
        }
    }
}

public enum HostDeviceName {
    public static let maximumLength = 60

    public static func normalized(_ raw: String) throws -> String {
        guard raw.utf16.count <= maximumLength, raw.unicodeScalars.allSatisfy(isPrintable) else {
            throw DeviceAPIError(422, "invalid_setting", "Use up to 60 characters without control characters.")
        }
        let scalars = raw.unicodeScalars
        guard let first = scalars.firstIndex(where: { !isTrimSpace($0) }),
              let last = scalars.lastIndex(where: { !isTrimSpace($0) }) else { return "" }
        return String(scalars[first...last])
    }

    public static func effective(override: String, systemName: String, fallback: String) -> String {
        if let custom = try? normalized(override), !custom.isEmpty { return custom }
        let cleaned = String(String.UnicodeScalarView(systemName.unicodeScalars.filter(isPrintable)))
            .trimmingCharacters(in: .whitespacesAndNewlines)
        var prefix = Array(cleaned.utf16.prefix(maximumLength))
        if let last = prefix.last, (0xD800...0xDBFF).contains(last) { prefix.removeLast() }
        return prefix.isEmpty ? fallback : String(decoding: prefix, as: UTF16.self)
    }

    // Kotlin Char.isWhitespace includes Unicode separators, including nonbreaking spaces.
    private static func isTrimSpace(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.properties.generalCategory {
        case .spaceSeparator, .lineSeparator, .paragraphSeparator: true
        default: false
        }
    }

    private static func isPrintable(_ scalar: Unicode.Scalar) -> Bool {
        scalar.value >= 32 && scalar.value != 127
    }
}

public struct HostSettingChange {
    public static let crashReportsField = "crash_reports"
    public static let deviceNameField = "device_name"
    public static let externalCallsPausedField = "external_calls_paused"
    public static let speakerVolumeField = "speaker_volume_ceiling"
    public static let speakerVolumeRange = 10...100
    public static let speakerVolumeDefault = 70
    public static let wifiHistoryPermissionRequest = "wifi_history_permission_request"
    public static let captureNotificationsRequest = "capture_notifications_request"
    public static let disableDeviceCapabilities = "device_capabilities_off"
    public static let deviceCapabilityFields = ["host_camera", "host_microphone", "cloud_appraisals", "lan_discovery_enabled", "callback_allow_internet"] + TransferKind.allCases.map { $0.rawValue + "_enabled" }
    public static let fields = [disableDeviceCapabilities, wifiHistoryPermissionRequest, captureNotificationsRequest, externalCallsPausedField, speakerVolumeField, deviceNameField, "appearance", "devices_tab", "feedback_auto_fix", "callback_allow_internet", "files_enabled", "shares_enabled", "downloads_enabled", "feedback_upload", "host_enabled", "host_camera", "host_microphone", "lan_discovery_enabled", "host_direct_enabled", "host_direct_address", "host_direct_device_lock", "log_visibility", "host_wake", "cloud_appraisals", "speech_enabled", "speech_cloud"] + HostActivityRetentionField.allCases.map(\.rawValue)
    public static func availableFields(crashReporting: Bool) -> [String] {
        fields + (crashReporting ? [crashReportsField] : [])
    }

    public let field: String
    public let value: Any
    public let applet: String?
    public var payload: [String: Any] {
        var value: [String: Any] = ["field": field, "value": self.value]
        if let applet { value["applet"] = applet }
        return value
    }
    public init(_ request: HostRequest, agent: HostAgent, crashReporting: Bool = false) throws {
        let body: [String: Any]
        if request.method == "DELETE" {
            guard request.body.isEmpty, Set(request.query.keys).isSubset(of: ["applet"]) else { throw Self.invalid() }
            let field = String(request.path.split(separator: "/").last ?? "")
            let defaultValue: Any
            switch field {
            case Self.crashReportsField: defaultValue = true
            case Self.deviceNameField: defaultValue = ""
            case "appearance": defaultValue = "System"
            case "devices_tab": defaultValue = "map"
            case Self.speakerVolumeField: defaultValue = Self.speakerVolumeDefault
            case "host_direct_address": defaultValue = NSNull()
            case "log_visibility": defaultValue = HostLogVisibility.all.rawValue
            case "feedback_upload", "feedback_auto_fix", "host_direct_device_lock": defaultValue = true
            default: defaultValue = HostActivityRetentionField(rawValue: field) == nil ? false as Any : 0 as Any
            }
            var reset: [String: Any] = ["field": field, "value": defaultValue]
            if let applet = request.query["applet"] { reset["applet"] = applet }
            body = reset
        } else {
            guard request.method == "POST", request.query.isEmpty, Set(request.body.keys).isSubset(of: ["field", "value", "applet"]) else { throw Self.invalid() }
            body = request.body
        }
        guard let field = body["field"] as? String, Self.availableFields(crashReporting: crashReporting).contains(field), let value = body["value"] else { throw Self.invalid() }
        let scoped = ["speech_enabled", "speech_cloud"].contains(field)
        if scoped {
            guard let applet = body["applet"] as? String, agent.allowsApplet(applet, operation: .configurationWrite) else { throw DeviceAPIError(403, "agent_not_allowed", "This applet is outside the caller's grant.") }
            self.applet = applet
        } else {
            guard body["applet"] == nil else { throw Self.invalid() }; self.applet = nil
        }
        let integerRange = field == Self.speakerVolumeField ? Self.speakerVolumeRange : HostActivityRetentionField(rawValue: field)?.range
        if let integerRange {
            guard let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID(),
                  number.doubleValue == Double(number.intValue), integerRange.contains(number.intValue) else { throw Self.invalid() }
        } else if field == Self.disableDeviceCapabilities || field == Self.captureNotificationsRequest || field == Self.wifiHistoryPermissionRequest {
            guard let number = value as? NSNumber, CFGetTypeID(number) == CFBooleanGetTypeID(), number.boolValue else { throw Self.invalid() }
        } else if field == Self.deviceNameField {
            guard let raw = value as? String else { throw Self.invalid() }
            self.field = field
            self.value = try HostDeviceName.normalized(raw)
            return
        } else if field == "appearance" {
            guard let string = value as? String, ["System", "Light", "Dark"].contains(string) else { throw Self.invalid() }
        } else if field == "devices_tab" {
            guard let string = value as? String, ["map", "list"].contains(string) else { throw Self.invalid() }
        } else if field == "host_direct_address" {
            if !(value is NSNull) {
                guard let string = value as? String, TailnetAddress(string) != nil else { throw Self.invalid() }
            }
        } else if field == "log_visibility" {
            guard let string = value as? String, HostLogVisibility(rawValue: string) != nil else { throw Self.invalid() }
        } else {
            guard let number = value as? NSNumber, CFGetTypeID(number) == CFBooleanGetTypeID() else { throw Self.invalid() }
        }
        self.field = field; self.value = value
    }
    private static func invalid() -> DeviceAPIError { .init(422, "invalid_setting", "Use a declared field, value and applicable applet.") }
    public static var schema: [String: Any] { schema(crashReporting: false) }

    public static func schema(crashReporting: Bool) -> [String: Any] {
        let options = availableFields(crashReporting: crashReporting).map { field -> [String: Any] in
            let scoped = ["speech_enabled", "speech_cloud"].contains(field)
            let value: [String: Any]
            switch field {
            case speakerVolumeField: value = ["type": "integer", "minimum": speakerVolumeRange.lowerBound, "maximum": speakerVolumeRange.upperBound]
            case crashReportsField:
                value = ["type": "boolean", "default": true, "description": "Send crash and hang reports when this build is configured for reporting."]
            case deviceNameField:
                value = ["type": "string", "maxLength": HostDeviceName.maximumLength,
                         "description": "Up to 60 UTF-16 units, with no ASCII controls. Empty clears the Bridgit name override."]
            case disableDeviceCapabilities: value = ["const": true]
            case wifiHistoryPermissionRequest: value = ["const": true, "description": "Initiate foreground OS location consent for Wi-Fi identity. Read wifi_history_authorization afterward; this action never grants permission."]
            case captureNotificationsRequest: value = ["const": true, "description": "Ask the OS to show its capture-notification permission prompt. The owner must grant OS consent; if denied, use system settings."]
            case "appearance": value = ["enum": ["System", "Light", "Dark"]]
            case "devices_tab": value = ["enum": ["map", "list"]]
            case "host_direct_address": value = ["type": ["string", "null"], "description": "An address currently assigned by Tailscale. Null clears the preferred address."]
            case "log_visibility": value = ["enum": [HostLogVisibility.all.rawValue, HostLogVisibility.own.rawValue]]
            default:
                if let field = HostActivityRetentionField(rawValue: field) {
                    value = ["type": "integer", "minimum": field.range.lowerBound, "maximum": field.range.upperBound,
                             "description": "0 keeps this limit unlimited. Diagnostics are exempt."]
                } else { value = ["type": "boolean"] }
            }
            var properties: [String: Any] = ["field": ["const": field], "value": value]
            if scoped { properties["applet"] = ["type": "string", "pattern": "^[a-z][a-z0-9-]{0,63}$"] }
            return ["type": "object", "properties": properties, "required": scoped ? ["field", "value", "applet"] : ["field", "value"], "additionalProperties": false]
        }
        return ["oneOf": options]
    }
}

public enum HostConfigurationRoutes {
    public static func operations(for agent: HostAgent) -> [ProfileOperation] {
        HostAPIContract.allowed(agent).filter { $0.grant.capability == "settings" }.map { .init(method: $0.method, path: $0.path) }
    }
}
