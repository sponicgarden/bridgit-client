import CoreFoundation
import Foundation

/// Strict host-side input contract; network identity/scope are never supplied by
/// the caller. Transport authentication and discovery grants precede execution.
enum HostLanRequest: Equatable {
    case catalog, remembered, logo(id: String, raw: Bool), discover(hosts: [String], subnet: Bool)
    init(_ request: HostRequest) throws {
        if request.method == "GET", request.path == "/v1/lan/catalog", request.query.isEmpty, request.body.isEmpty { self = .catalog; return }
        if request.method == "GET", request.path == "/v1/lan/discovered", request.query.isEmpty, request.body.isEmpty { self = .remembered; return }
        let parts = request.path.split(separator: "/", omittingEmptySubsequences: false)
        if request.method == "GET", parts.count == 6, parts[0].isEmpty, parts[1] == "v1", parts[2] == "lan", parts[3] == "brands", parts[5] == "logo",
           request.body.isEmpty, Set(request.query.keys).isSubset(of: ["raw"]),
           parts[4].range(of: "^[a-z0-9-]{1,64}$", options: .regularExpression) != nil {
            let raw: Bool
            if let value = request.query["raw"] {
                if let text = value as? String, ["0", "1", "false", "true"].contains(text) { raw = text == "1" || text == "true" }
                else if let number = value as? NSNumber, [0,1].contains(number.doubleValue) { raw = number.boolValue }
                else { throw Self.invalid() }
            } else { raw = false }
            self = .logo(id: String(parts[4]), raw: raw); return
        }
        guard request.method == "POST", request.path == "/v1/lan/discover", request.query.isEmpty,
              Set(request.body.keys).isSubset(of: ["hosts", "subnet"]) else { throw Self.invalid() }
        let hosts: [String]
        if let input = request.body["hosts"] {
            guard let values = input as? [String], values.count <= 254,
                  values.allSatisfy({ LanDiscoveryRules.privateIPv4($0) != nil }) else { throw Self.invalid() }
            hosts = values
        } else { hosts = [] }
        let subnet: Bool
        if let value = request.body["subnet"] {
            guard let number = value as? NSNumber, CFGetTypeID(number) == CFBooleanGetTypeID() else { throw Self.invalid() }
            subnet = number.boolValue
        } else { subnet = false }
        self = .discover(hosts: hosts, subnet: subnet)
    }
    static let discoverSchema: [String: Any] = [
        "type": "object", "additionalProperties": false,
        "properties": [
            "hosts": ["type": "array", "maxItems": 254, "items": ["type": "string", "description": "Literal private IPv4 host on the selected direct LAN."]],
            "subnet": ["type": "boolean", "default": false, "description": "Probe the direct /24 through /30 subnet on four fixed TCP ports."]
        ]
    ]
    private static func invalid() -> DeviceAPIError { .init(422, "invalid_parameter", "Use the declared local network request fields.") }
}
