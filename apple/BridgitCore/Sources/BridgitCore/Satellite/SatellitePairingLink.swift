import Darwin
import Foundation

/// Numeric Tailscale addresses only. No DNS names, zones, ports, or arbitrary LAN hosts.
public struct TailnetAddress: Equatable, Codable, Hashable, Sendable {
    public let value: String
    public init?(_ text: String) {
        guard !text.isEmpty, text == text.trimmingCharacters(in: .whitespacesAndNewlines), !text.contains("%") else { return nil }
        var v4 = in_addr()
        if text.withCString({ inet_pton(AF_INET, $0, &v4) }) == 1 {
            let bytes = withUnsafeBytes(of: v4) { Array($0) }
            guard bytes[0] == 100, (64...127).contains(bytes[1]) else { return nil }
            value = Self.render(v4); return
        }
        var v6 = in6_addr()
        guard text.withCString({ inet_pton(AF_INET6, $0, &v6) }) == 1 else { return nil }
        let bytes = withUnsafeBytes(of: v6) { Array($0) }
        // Accept mapped IPv4 from socket peers only when the underlying address is tailnet.
        if Array(bytes.prefix(12)) == Array(repeating: UInt8(0), count: 10) + [255, 255] {
            guard bytes[12] == 100, (64...127).contains(bytes[13]) else { return nil }
            value = bytes.suffix(4).map(String.init).joined(separator: "."); return
        }
        guard Array(bytes.prefix(6)) == [0xfd, 0x7a, 0x11, 0x5c, 0xa1, 0xe0] else { return nil }
        var buffer = [CChar](repeating: 0, count: Int(INET6_ADDRSTRLEN))
        guard inet_ntop(AF_INET6, &v6, &buffer, socklen_t(buffer.count)) != nil else { return nil }
        value = String(cString: buffer)
    }
    public var isIPv6: Bool { value.contains(":") }
    /// Read assigned tunnel addresses only. Carrier-grade IPv4 on ordinary Wi-Fi
    /// or cellular interfaces is not a local Tailscale endpoint.
    public static func localCandidates() -> [TailnetAddress] {
        var list: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&list) == 0, let list else { return [] }
        defer { freeifaddrs(list) }
        var result = Set<TailnetAddress>(), current: UnsafeMutablePointer<ifaddrs>? = list
        while let node = current {
            let info = node.pointee; current = info.ifa_next
            guard let name = info.ifa_name, let address = info.ifa_addr, info.ifa_flags & UInt32(IFF_UP) != 0 else { continue }
            let interface = String(cString: name)
            guard interface.hasPrefix("utun") || interface.hasPrefix("tailscale"), [AF_INET, AF_INET6].contains(Int32(address.pointee.sa_family)) else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            guard getnameinfo(address, socklen_t(address.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 else { continue }
            let value = String(cString: host).components(separatedBy: "%").first ?? ""
            if let tailnet = TailnetAddress(value) { result.insert(tailnet) }
        }
        return result.sorted { ($0.isIPv6 ? 1 : 0, $0.value) < ($1.isIPv6 ? 1 : 0, $1.value) }
    }
    private static func render(_ address: in_addr) -> String {
        var address = address, buffer = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
        _ = inet_ntop(AF_INET, &address, &buffer, socklen_t(buffer.count))
        return String(cString: buffer)
    }
    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer(), text = try container.decode(String.self)
        guard let value = Self(text) else { throw DecodingError.dataCorruptedError(in: container, debugDescription: "Expected a numeric Tailscale address") }
        self = value
    }
    public func encode(to encoder: Encoder) throws { var container = encoder.singleValueContainer(); try container.encode(value) }
}

public struct SatellitePairingLink: Equatable, Sendable {
    public let address: TailnetAddress
    public let port: UInt16
    public let claim: String
    public let name: String
    public let install: String?
    public init(address: TailnetAddress, port: UInt16, claim: String, name: String, install: String? = nil) throws {
        guard port > 0, Self.validClaim(claim) else { throw Self.invalid }
        self.address = address; self.port = port; self.claim = claim
        let safe = name.unicodeScalars.filter { !CharacterSet.controlCharacters.contains($0) }.map(String.init).joined().trimmingCharacters(in: .whitespacesAndNewlines)
        self.name = String((safe.isEmpty ? "Satellite" : safe).prefix(60))
        self.install = install.flatMap { $0.range(of: "^bgi_[a-z2-7]{24}$", options: .regularExpression) != nil ? $0 : nil }
    }
    private static var invalid: DeviceAPIError { .init(422, "invalid_satellite_link", "Use the one-time satellite link made on the other device while Tailscale is on.") }
    public static func validClaim(_ value: String) -> Bool { value.range(of: "^sct_[A-Za-z0-9_-]{40,64}$", options: .regularExpression) != nil }
    public static func parse(_ text: String) throws -> Self {
        guard let c = URLComponents(string: text.trimmingCharacters(in: .whitespacesAndNewlines)), c.scheme == "bridgit", c.host == "satellite", c.path.isEmpty, c.query == nil, c.user == nil, c.password == nil, c.port == nil,
              let fragment = c.percentEncodedFragment, let values = URLComponents(string: "x:?" + fragment)?.queryItems else { throw invalid }
        for key in ["v", "host", "port", "claim", "name", "install"] { guard values.filter({ $0.name == key }).count <= 1 else { throw invalid } }
        func field(_ key: String) -> String? { values.first { $0.name == key }?.value }
        guard field("v") == "1", let host = field("host"), let address = TailnetAddress(host), let raw = field("port"), let port = UInt16(raw), let claim = field("claim") else { throw invalid }
        return try Self(address: address, port: port, claim: claim, name: field("name") ?? "Satellite", install: field("install"))
    }
    public var url: URL {
        var c = URLComponents(); c.scheme = "bridgit"; c.host = "satellite"
        var fields = [URLQueryItem(name: "v", value: "1"), .init(name: "host", value: address.value), .init(name: "port", value: String(port)), .init(name: "claim", value: claim), .init(name: "name", value: name)]
        if let install { fields.append(.init(name: "install", value: install)) }
        var fragment = URLComponents(); fragment.queryItems = fields; c.percentEncodedFragment = fragment.percentEncodedQuery
        return c.url!
    }
}
