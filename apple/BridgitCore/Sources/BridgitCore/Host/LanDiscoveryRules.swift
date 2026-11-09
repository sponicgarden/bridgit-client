import Foundation

/// Target rules from Android 3347's LanRules. This does not perform discovery or
/// imply that a local interface, OS permission, or agent grant is available.
enum LanDiscoveryRules {
    static func privateIPv4(_ host: String) -> UInt32? {
        let parts = host.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 4 else { return nil }
        var octets: [UInt32] = []
        for part in parts {
            let bytes = Array(part.utf8)
            guard (1...3).contains(bytes.count), bytes.allSatisfy({ (48...57).contains($0) }),
                  bytes.count == 1 || bytes[0] != 48,
                  let value = UInt32(part), value <= 255 else { return nil }
            octets.append(value)
        }
        guard octets[0] == 10 || (octets[0] == 172 && (16...31).contains(octets[1])) ||
                (octets[0] == 192 && octets[1] == 168) else { return nil }
        return octets.reduce(0) { ($0 << 8) | $1 }
    }

    static func host(_ address: UInt32) -> String {
        [24, 16, 8, 0].map { String((address >> $0) & 255) }.joined(separator: ".")
    }

    static func onLink(_ host: String, local: String, prefix: Int) -> Bool {
        guard (1...30).contains(prefix), let target = privateIPv4(host),
              let own = privateIPv4(local), target != own else { return false }
        let mask = UInt32.max << (32 - prefix)
        let base = own & mask
        let end = base | ~mask
        return target > base && target < end
    }

    static func boundedHosts(local: String, prefix: Int) throws -> [String] {
        guard (24...30).contains(prefix), let own = privateIPv4(local) else {
            throw DeviceAPIError(422, "invalid_parameter", "Subnet inventory requires a private IPv4 address and /24 through /30. Supply explicit hosts on larger networks.")
        }
        let mask = UInt32.max << (32 - prefix)
        let base = own & mask
        let end = base | ~mask
        return ((base + 1)..<end).filter { $0 != own }.map(host)
    }

    static func targets(hosts: [String], subnet: Bool, local: String, prefix: Int) throws -> [String] {
        // Android validates the caller's list even when subnet mode supplies targets.
        guard hosts.count <= 254 else {
            throw DeviceAPIError(422, "invalid_parameter", "At most 254 explicit hosts are allowed.")
        }
        let candidates = try subnet ? boundedHosts(local: local, prefix: prefix) : hosts
        guard candidates.allSatisfy({ onLink($0, local: local, prefix: prefix) }) else {
            throw DeviceAPIError(422, "invalid_parameter", "Targets must be private IPv4 hosts on the current direct LAN.")
        }
        return candidates
    }
}
