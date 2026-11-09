import CryptoKit
import Foundation
import Network

/// Callback rules shared by owner configuration and delivery. DNS answers must
/// also be checked immediately before connecting; this is URL validation only.
enum CallbackRules {
    enum Destination: Equatable { case tailnet, internet }
    static let maxPendingPerAgent = 500
    static let retryDelays: [TimeInterval] = [10, 60, 300]
    static let timeout: TimeInterval = 10

    static func destination(_ input: String, tailnetDomain: String?, allowInternet: Bool) throws -> Destination {
        let url = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !url.isEmpty, url.count <= 2000, !url.unicodeScalars.contains(where: { $0.value < 33 || $0.value == 127 }),
              let parts = URLComponents(string: url), let scheme = parts.scheme?.lowercased(), ["http", "https"].contains(scheme),
              let rawHost = parts.host, !rawHost.isEmpty, parts.user == nil, parts.password == nil, parts.fragment == nil,
              parts.port == nil || (1...65535).contains(parts.port!) else {
            throw DeviceAPIError(422, "invalid_parameter", "Use a callback URL without credentials or a fragment.")
        }
        _ = try ConnectionAddress.check(url, allowPrivate: true)
        let host = rawHost.trimmingCharacters(in: CharacterSet(charactersIn: "[]")).lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "."))
        if TailnetAddress(host) != nil { return .tailnet }
        let literal = IPv4Address(host) != nil || IPv6Address(host) != nil
        if !literal, let domain = tailnetDomain?.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: ".")),
           domain.hasSuffix(".ts.net"), host.hasSuffix("." + domain) { return .tailnet }
        guard allowInternet, scheme == "https", !host.hasSuffix(".ts.net"), host != "localhost", literal || host.contains("."),
              !literal || ConnectionAddress.scope(host) == .internet else {
            throw DeviceAPIError(422, "invalid_parameter", "The callback address does not match the current network policy.")
        }
        return .internet
    }

    /// Every resolved address must remain in the policy selected for the URL.
    static func allowsResolvedAddress(_ address: String, destination: Destination) -> Bool {
        switch destination {
        case .tailnet: return TailnetAddress(address) != nil
        case .internet: return ConnectionAddress.scope(address) == .internet
        }
    }

    static func validateSendURL(_ address: String, destination: Destination) throws {
        let parts = try ConnectionAddress.check(address, allowPrivate: true)
        if destination == .internet {
            guard parts.scheme?.lowercased() == "https", let rawHost = parts.host?.lowercased() else {
                throw DeviceAPIError(422, "invalid_parameter", "Internet callbacks require public HTTPS.")
            }
            let host = rawHost.trimmingCharacters(in: CharacterSet(charactersIn: "."))
            guard !host.hasSuffix(".ts.net") else {
                throw DeviceAPIError(422, "invalid_parameter", "Internet callbacks require public HTTPS.")
            }
        }
    }

    static func validSecret(_ value: String) -> Bool {
        let bytes = Array(value.utf8)
        guard bytes.count == 47, bytes.starts(with: Array("cbs_".utf8)) else { return false }
        return bytes.dropFirst(4).allSatisfy { (65...90).contains($0) || (97...122).contains($0) || (48...57).contains($0) || $0 == 45 || $0 == 95 }
    }
    static func signature(secret: String, timestamp: Int64, body: Data) -> String {
        var message = Data("\(timestamp).".utf8); message.append(body)
        let mac = HMAC<SHA256>.authenticationCode(for: message, using: SymmetricKey(data: Data(secret.utf8)))
        return "sha256=" + mac.map { String(format: "%02x", $0) }.joined()
    }
    static func request(address: String, destination: Destination, secret: String, timestamp: Int64,
                        eventID: String, body: Data, appVersion: String) throws -> ConnectionHTTPRequest {
        guard validSecret(secret), !eventID.isEmpty, eventID.utf8.allSatisfy({ (48...57).contains($0) }),
              !appVersion.unicodeScalars.contains(where: { $0.value < 32 || $0.value == 127 }) else {
            throw DeviceAPIError(422, "invalid_parameter", "Invalid callback signing inputs.")
        }
        try validateSendURL(address, destination: destination)
        let signature = signature(secret: secret, timestamp: timestamp, body: body)
        let headers = ["Content-Type": "application/json", "User-Agent": "Bridgit/" + appVersion,
            "X-Bridgit-Timestamp": String(timestamp), "X-Bridget-Timestamp": String(timestamp),
            "X-Bridgit-Event-Id": eventID, "X-Bridget-Event-Id": eventID,
            "X-Bridgit-Signature": signature, "X-Bridget-Signature": signature]
        return .init(callbackAddress: address, destination: destination, headers: headers, body: body)
    }

    static func nextDelay(after attempt: Int) -> TimeInterval? {
        guard attempt > 0, attempt <= retryDelays.count else { return nil }
        return retryDelays[attempt - 1]
    }
}

struct CallbackQueue<Value> {
    struct Item { let agentID: String; let value: Value; let attempt: Int; let due: Date }
    private(set) var items: [Item] = []
    mutating func add(_ item: Item) -> Int {
        var dropped = 0
        while items.lazy.filter({ $0.agentID == item.agentID }).count >= CallbackRules.maxPendingPerAgent {
            guard let index = items.firstIndex(where: { $0.agentID == item.agentID }) else { break }
            items.remove(at: index); dropped += 1
        }
        items.append(item); return dropped
    }
    mutating func remove(agentID: String) { items.removeAll { $0.agentID == agentID } }
    mutating func takeDue(now: Date) -> Item? {
        guard let index = items.indices.min(by: { items[$0].due < items[$1].due }), items[index].due <= now else { return nil }
        return items.remove(at: index)
    }
}
