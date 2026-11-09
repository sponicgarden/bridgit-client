import Foundation
import CoreFoundation

/// A peer report, never a grant. Missing booleans and metadata remain unknown.
public struct SatelliteManifest: Codable, Equatable {
    public var name: String
    public var model: String?
    public var deviceType: String?
    public var platform: String?
    public var appVersion: String?
    public var cameras: [String]
    public var capabilities: [Capability]
    public var endpoints: [Endpoint]
    public struct Capability: Codable, Equatable {
        public var id: String
        public var enabled: Bool?
        public var ready: Bool?
        public var blockedReason: String?
    }
    public struct Endpoint: Codable, Equatable {
        public var method: String
        public var path: String
        public var capability: String
        func matches(_ request: HostRequest) -> Bool {
            guard method == request.method else { return false }
            let expected = path.split(separator: "/"), actual = request.path.split(separator: "/")
            return expected.count == actual.count && zip(expected, actual).allSatisfy { template, value in
                template == value || template == "{id}" && SatelliteForwardPolicy.validResourceID(String(value))
            }
        }
    }
    public init(_ object: [String: Any], fallbackName: String) throws {
        let invalid = DeviceAPIError(502, "invalid_satellite_manifest", "The satellite returned an invalid capability report.")
        func text(_ key: String, limit: Int) -> String? {
            guard let raw = object[key] as? String else { return nil }
            let value = raw.unicodeScalars.filter { !CharacterSet.controlCharacters.contains($0) }.map(String.init).joined().trimmingCharacters(in: .whitespacesAndNewlines)
            return value.isEmpty ? nil : String(value.prefix(limit))
        }
        func flag(_ value: Any?) -> Bool? {
            guard let number = value as? NSNumber, CFGetTypeID(number) == CFBooleanGetTypeID() else { return nil }
            return number.boolValue
        }
        name = text("name", limit: 60) ?? String(fallbackName.prefix(60))
        model = text("model", limit: 160); appVersion = text("app_version", limit: 80)
        let metadata = ReportedDeviceMetadata(platform: text("platform", limit: 40), deviceType: text("device_type", limit: 40))
        deviceType = metadata.deviceType; platform = metadata.platform
        if let raw = object["cameras"] {
            guard let values = raw as? [String], values.count <= 16, values.allSatisfy({ SatelliteForwardPolicy.validResourceID($0) }) else { throw invalid }
            cameras = values
        } else { cameras = [] }
        if let raw = object["capabilities"] {
            guard let values = raw as? [[String: Any]], values.count <= 64 else { throw invalid }
            capabilities = try values.map { value in
                guard let id = value["id"] as? String, SatelliteForwardPolicy.validResourceID(id) else { throw invalid }
                return Capability(id: id, enabled: flag(value["enabled"]), ready: flag(value["ready"]), blockedReason: (value["blocked_reason"] as? String).map { String($0.prefix(160)) })
            }
            guard Set(capabilities.map(\.id)).count == capabilities.count else { throw invalid }
        } else { capabilities = [] }
        guard let values = object["endpoints"] as? [[String: Any]], values.count <= 200 else { throw invalid }
        endpoints = try values.compactMap { value in
            guard let method = value["method"] as? String, let path = value["path"] as? String, let capability = value["capability"] as? String else { throw invalid }
            let canonical = path.replacingOccurrences(of: "{id}", with: "fixture_id")
            guard let operation = SatelliteForwardPolicy.operation(method: method, path: canonical) else { return nil }
            let expected = operation == .deviceRead ? "status" : operation.capability
            guard capability == expected else { throw invalid }
            return Endpoint(method: method, path: path, capability: expected)
        }
        guard Set(endpoints.map { $0.method + " " + $0.path }).count == endpoints.count else { throw invalid }
    }
    public func permits(_ request: HostRequest) -> Bool {
        guard let endpoint = endpoints.first(where: { $0.matches(request) }) else { return false }
        if endpoint.capability == "status" { return true }
        guard let capability = capabilities.first(where: { $0.id == endpoint.capability }) else { return false }
        return capability.enabled == true && capability.ready == true && capability.blockedReason == nil
    }
}

public enum SatelliteForwardPolicy {
    public static func validTarget(_ value: String) -> Bool { value.range(of: "^sat:[a-z0-9-]{1,40}$", options: .regularExpression) != nil }
    static func validResourceID(_ value: String) -> Bool { value.range(of: "^[A-Za-z0-9_-]{1,128}$", options: .regularExpression) != nil }
    public static func operation(method: String, path: String) -> HostOperation? {
        guard path.hasPrefix("/v1/"), !path.contains("%"), !path.contains("?"), !path.contains("#"), !path.contains("//"), !path.split(separator: "/").contains("..") else { return nil }
        if method == "GET", ["/v1/health", "/v1/capabilities", "/v1/device/profile"].contains(path) { return .deviceRead }
        if method == "POST", path == "/v1/camera/snapshot" { return .cameraSnapshot }
        if method == "POST", ["/v1/mic/record", "/v1/speech/hear"].contains(path) { return .speechHear }
        if method == "POST" && ["/v1/speaker/play", "/v1/speaker/say", "/v1/speech/speak"].contains(path) || ["GET", "DELETE"].contains(method) && path == "/v1/speaker" { return .speechSpeak }
        if path == "/v1/interactions", ["GET", "POST"].contains(method) { return method == "GET" ? .interactionsRead : .interactionsWrite }
        if path == "/v1/events", method == "GET" { return .interactionsRead }
        let parts = path.split(separator: "/")
        if parts.count == 3, parts[0] == "v1", parts[1] == "interactions", validResourceID(String(parts[2])), ["GET", "DELETE"].contains(method) { return method == "GET" ? .interactionsRead : .interactionsWrite }
        return nil
    }
    public static func newID(name: String, existing: Set<String>) -> String {
        let base = name.lowercased().replacingOccurrences(of: "[^a-z0-9]+", with: "-", options: .regularExpression).trimmingCharacters(in: CharacterSet(charactersIn: "-"))
        let slug = String((base.isEmpty ? "device" : base).prefix(36)).trimmingCharacters(in: CharacterSet(charactersIn: "-"))
        var suffix = 1
        while true {
            let ending = suffix == 1 ? "" : "-" + String(suffix)
            let candidate = "sat:" + slug.prefix(max(1, 40 - ending.count)) + ending
            if !existing.contains(candidate), validTarget(candidate) { return candidate }
            suffix += 1
        }
    }
    public static func forwardedIdempotencyKey(agent: HostAgent, key: String) -> String {
        "h" + PairingClaim.tokenHash(agent.eventOwner).prefix(32) + "-" + PairingClaim.tokenHash(key)
    }
}
