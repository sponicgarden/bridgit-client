import Darwin
import Foundation
import Network

/// A scan must bind to this physical interface and recheck it before storing results.
/// Address equality alone is not a persistent network identity.
struct LanInterfaceSelection: Equatable {
    let name: String
    let index: UInt32
    let address: String
    let prefix: Int
    let gateways: [String]
    let isWiFi: Bool

    init(name: String, index: UInt32, address: String, prefix: Int, gateways: [String] = [], isWiFi: Bool = false) {
        self.name = name; self.index = index; self.address = address; self.prefix = prefix
        self.gateways = gateways; self.isWiFi = isWiFi
    }

    /// Use only literal, on-link IPv4 gateways. This never resolves hostnames or
    /// probes the router; VPN/public/off-link endpoints are not LAN metadata.
    func withGateways(_ endpoints: [NWEndpoint]) -> Self {
        let addresses = endpoints.compactMap { endpoint -> String? in
            guard case let .hostPort(host, _) = endpoint,
                  case let .ipv4(ip) = host else { return nil }
            let value = ip.debugDescription
            return LanDiscoveryRules.onLink(value, local: address, prefix: prefix) ? value : nil
        }
        return Self(name: name, index: index, address: address, prefix: prefix,
                    gateways: Set(addresses).sorted(), isWiFi: isWiFi)
    }

    struct Candidate {
        let name: String
        let index: UInt32
        let address: String
        let netmask: UInt32
        let physical: Bool
        let up: Bool
    }

    static func select(_ candidates: [Candidate]) throws -> Self {
        var interfaces: [UInt32: Self] = [:]
        for candidate in candidates where candidate.physical && candidate.up && candidate.index != 0 {
            guard LanDiscoveryRules.privateIPv4(candidate.address) != nil else { continue }
            let prefix = candidate.netmask.nonzeroBitCount
            guard prefix > 0, candidate.netmask == UInt32.max << (32 - prefix) else { continue }
            // Like Android HostLan, select the first private IPv4 address per network.
            if interfaces[candidate.index] == nil {
                interfaces[candidate.index] = Self(name: candidate.name, index: candidate.index,
                                                  address: candidate.address, prefix: prefix)
            }
        }
        guard interfaces.count == 1, let selected = interfaces.values.first else {
            throw DeviceAPIError(409, "lan_unavailable", "Connect exactly one private Wi-Fi or Ethernet LAN.")
        }
        return selected
    }

    /// NWPath supplies interface type; getifaddrs supplies its current address and
    /// netmask. Do not infer physical transport from a name such as en0 or utun0.
    static func capture(path: NWPath) throws -> Self {
        let physical = Set(path.availableInterfaces.filter { $0.type == .wifi || $0.type == .wiredEthernet }.map { UInt32($0.index) })
        var first: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&first) == 0 else {
            throw DeviceAPIError(503, "lan_unavailable", "Network interfaces could not be read.")
        }
        defer { if let first { freeifaddrs(first) } }
        var current = first
        var candidates: [Candidate] = []
        while let entry = current {
            defer { current = entry.pointee.ifa_next }
            guard let address = entry.pointee.ifa_addr, address.pointee.sa_family == AF_INET,
                  let mask = entry.pointee.ifa_netmask, mask.pointee.sa_family == AF_INET,
                  let namePointer = entry.pointee.ifa_name else { continue }
            let name = String(cString: namePointer)
            let index = if_nametoindex(namePointer)
            let flags = entry.pointee.ifa_flags
            let up = flags & UInt32(IFF_UP) != 0 && flags & UInt32(IFF_RUNNING) != 0 &&
                flags & UInt32(IFF_LOOPBACK | IFF_POINTOPOINT) == 0
            let number = address.withMemoryRebound(to: sockaddr_in.self, capacity: 1) {
                UInt32(bigEndian: $0.pointee.sin_addr.s_addr)
            }
            let netmask = mask.withMemoryRebound(to: sockaddr_in.self, capacity: 1) {
                UInt32(bigEndian: $0.pointee.sin_addr.s_addr)
            }
            candidates.append(Candidate(name: name, index: index, address: LanDiscoveryRules.host(number),
                                        netmask: netmask, physical: physical.contains(index), up: up))
        }
        let selected = try select(candidates).withGateways(path.gateways)
        return Self(name: selected.name, index: selected.index, address: selected.address,
                    prefix: selected.prefix, gateways: selected.gateways,
                    isWiFi: path.availableInterfaces.contains { UInt32($0.index) == selected.index && $0.type == .wifi })
    }
}
