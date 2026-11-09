import Foundation

/// Viewer credentials are separate from control agents, with a fixed native read surface.
/// Additional Android portal actions remain unavailable unless implemented under their own consent.
public enum HostViewerPairing {
    public enum WireFormat: String, CaseIterable {
        case current, androidAPK3347
        public var label: String { self == .current ? "Current Bridgit" : "Android APK 3347" }
    }
    public static let claimPath = "/v1/portal/claim"
    public static let reads: Set<String> = ["/v1/portal/snapshot", "/v1/portal/home", "/v1/portal/agents", "/v1/portal/applets", "/v1/portal/theme", "/v1/portal/logs", "/v1/device/profile"]
    static func permits(_ request: HostRequest) -> Bool {
        request.query["target"] == nil && (request.method == "GET" && reads.contains(request.path) || request.method == "POST" && request.path == claimPath)
    }
    public static func link(base: URL, device: String, token: String, name: String, install: String? = nil, format: WireFormat = .current) throws -> URL {
        guard Pairing.validToken(token), let normalized = Pairing.makeBase(relay: base.absoluteString, device: device), normalized == base else {
            throw DeviceAPIError(422, "invalid_pairing", "Use this host's HTTPS relay identity.")
        }
        var parts = URLComponents(); parts.scheme = format == .androidAPK3347 ? "bridgitportal" : "bridgit"; parts.host = "pair"
        var fields = [URLQueryItem(name: "v", value: "1"), .init(name: "relay", value: base.absoluteString), .init(name: "device", value: device), .init(name: "token", value: token), .init(name: "name", value: String(name.prefix(60))), .init(name: "portal", value: "1"), .init(name: "claim", value: "1")]
        if let install { fields.append(.init(name: "install", value: install)) }
        var fragment = URLComponents(); fragment.queryItems = fields
        parts.percentEncodedFragment = fragment.percentEncodedQuery
        return parts.url!
    }
}
