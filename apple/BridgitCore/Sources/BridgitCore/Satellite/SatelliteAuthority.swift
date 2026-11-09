import Foundation

struct PendingSatelliteClaim: Codable {
    var hash: String
    var until: Int64
    var name: String
    var operations: Set<HostOperation>
}
public struct SatelliteClaimed {
    public let agent: HostAgent
    public let key: String
    public let name: String
}
enum SatelliteAuthorityPolicy {
    static let claimKey = "host-satellite-claim-v1"
    static let hubOperations: Set<HostOperation> = [.deviceRead, .cameraSnapshot, .speechHear, .speechSpeak, .interactionsRead, .interactionsWrite]
    static func validKey(_ text: String) -> Bool { text.range(of: "^brg_[A-Za-z0-9_-]{43}$", options: .regularExpression) != nil }
    static func equalHash(_ left: String, _ right: String) -> Bool {
        let a = Array(left.utf8), b = Array(right.utf8)
        guard a.count == 64, b.count == 64 else { return false }
        var difference: UInt8 = 0
        for index in a.indices { difference |= a[index] ^ b[index] }
        return difference == 0
    }
    static func hubMayCall(_ request: HostRequest) -> Bool {
        guard request.query["target"] == nil else { return false }
        if request.method == "GET", ["/v1/health", "/v1/capabilities", "/v1/device/profile", "/v1/interactions", "/v1/events"].contains(request.path) { return true }
        if request.path == "/v1/camera/snapshot", request.method == "POST" { return true }
        if request.path == "/v1/interactions", request.method == "POST" { return true }
        let parts = request.path.split(separator: "/")
        return parts.count == 3 && parts[0] == "v1" && parts[1] == "interactions" && UUID(uuidString: String(parts[2])) != nil && ["GET", "DELETE"].contains(request.method)
    }
}
