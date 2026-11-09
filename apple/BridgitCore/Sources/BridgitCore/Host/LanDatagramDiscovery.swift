import Darwin
import Foundation

/// SSDP and ONVIF send one fixed discovery message, then collect bounded hints.
/// Advertised URLs are never fetched and responses never authorize a device.
enum LanDatagramDiscovery {
    enum Method { case ssdp, onvif
        var port: UInt16 { self == .ssdp ? 1900 : 3702 }
        var source: LanDiscoveryObservation.Source { self == .ssdp ? .ssdp : .onvif }
        func query(id: UUID = UUID()) -> Data {
            if self == .ssdp {
                return Data("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: ssdp:all\r\n\r\n".utf8)
            }
            return Data("<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:a=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\" xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\" xmlns:n=\"http://www.onvif.org/ver10/network/wsdl\"><s:Header><a:MessageID>uuid:\(id.uuidString.lowercased())</a:MessageID><a:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</a:To><a:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</a:Action></s:Header><s:Body><d:Probe><d:Types>n:NetworkVideoTransmitter</d:Types></d:Probe></s:Body></s:Envelope>".utf8)
        }
        func service(_ data: Data) -> String? {
            guard data.count <= 8192 else { return nil }
            let response = String(decoding: data, as: UTF8.self)
            if self == .onvif { return response.contains("ProbeMatches") ? "onvif_candidate" : nil }
            return response.components(separatedBy: .newlines).first { $0.lowercased().hasPrefix("st:") }
                .map { String($0.dropFirst(3)).trimmingCharacters(in: .whitespacesAndNewlines) } ?? "ssdp_responder"
        }
    }

    static func scan(_ method: Method, network: LanInterfaceSelection, observations: LanDiscoveryAccumulator,
                     authorize: @escaping @Sendable () async throws -> Void,
                     currentNetwork: @escaping @Sendable () async throws -> LanInterfaceSelection) async throws {
        let check: @Sendable () async throws -> Void = {
            try Task.checkCancellation()
            try await authorize()
            guard try await currentNetwork() == network else {
                throw DeviceAPIError(409, "lan_changed", "The local network changed during discovery.")
            }
        }
        try await check()
        guard let local = LanDiscoveryRules.privateIPv4(network.address), network.index != 0 else {
            throw DeviceAPIError(409, "lan_unavailable", "No physical private LAN is selected.")
        }
        let socket = try LanDatagramSocket(local: local, interface: network.index, multicast: true)
        try await check()
        try socket.send(method.query(), address: 0xeffffffa, port: method.port)
        let deadline = ContinuousClock.now.advanced(by: .seconds(2))
        var packets = 0
        while ContinuousClock.now < deadline && packets < 128 {
            try await check()
            guard let packet = try socket.receive() else {
                try await Task.sleep(nanoseconds: 20_000_000)
                continue
            }
            packets += 1
            guard let service = method.service(packet.data) else { continue }
            try await check()
            await observations.add(host: packet.host, source: method.source, service: service)
        }
        try await check()
    }
}

/// Nonblocking socket owned by one scan. Both the source address and interface
/// are bound; a failed binding never falls back to the default route.
final class LanDatagramSocket {
    struct Packet { let host: String; let port: UInt16; let data: Data }
    private let descriptor: Int32
    init(local: UInt32, interface: UInt32, multicast: Bool, broadcast: Bool = false) throws {
        guard interface != 0 else { throw Self.unavailable() }
        let descriptor = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard descriptor >= 0 else { throw Self.unavailable() }
        do {
            guard fcntl(descriptor, F_SETFD, FD_CLOEXEC) == 0,
                  fcntl(descriptor, F_SETFL, O_NONBLOCK) == 0 else { throw Self.unavailable() }
            var index = interface
            guard setsockopt(descriptor, IPPROTO_IP, IP_BOUND_IF, &index, socklen_t(MemoryLayout.size(ofValue: index))) == 0 else { throw Self.unavailable() }
            var source = Self.address(local, port: 0)
            let bound = withUnsafePointer(to: &source) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.bind(descriptor, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
            }
            guard bound == 0 else { throw Self.unavailable() }
            if broadcast {
                var enabled: Int32 = 1
                guard setsockopt(descriptor, SOL_SOCKET, SO_BROADCAST, &enabled, socklen_t(MemoryLayout<Int32>.size)) == 0 else { throw Self.unavailable() }
            }
            if multicast {
                var sourceAddress = in_addr(s_addr: local.bigEndian), ttl: UInt8 = 1
                guard setsockopt(descriptor, IPPROTO_IP, IP_MULTICAST_IF, &sourceAddress, socklen_t(MemoryLayout<in_addr>.size)) == 0,
                      setsockopt(descriptor, IPPROTO_IP, IP_MULTICAST_TTL, &ttl, 1) == 0 else { throw Self.unavailable() }
            }
        } catch { close(descriptor); throw error }
        self.descriptor = descriptor
    }
    deinit { close(descriptor) }
    func send(_ data: Data, address: UInt32, port: UInt16) throws {
        var target = Self.address(address, port: port)
        let sent = data.withUnsafeBytes { bytes in
            withUnsafePointer(to: &target) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    sendto(descriptor, bytes.baseAddress, bytes.count, 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                }
            }
        }
        guard sent == data.count else { throw Self.unavailable() }
    }
    func receive() throws -> Packet? {
        var source = sockaddr_in(), size = socklen_t(MemoryLayout<sockaddr_in>.size)
        var bytes = [UInt8](repeating: 0, count: 8192)
        let count = bytes.withUnsafeMutableBytes { buffer in
            withUnsafeMutablePointer(to: &source) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { recvfrom(descriptor, buffer.baseAddress, buffer.count, 0, $0, &size) }
            }
        }
        if count < 0 {
            if errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR { return nil }
            throw Self.unavailable()
        }
        guard source.sin_family == AF_INET else { return nil }
        return Packet(host: LanDiscoveryRules.host(UInt32(bigEndian: source.sin_addr.s_addr)),
                      port: UInt16(bigEndian: source.sin_port), data: Data(bytes.prefix(count)))
    }
    func localPort() throws -> UInt16 {
        var address = sockaddr_in(), size = socklen_t(MemoryLayout<sockaddr_in>.size)
        let status = withUnsafeMutablePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(descriptor, $0, &size) }
        }
        guard status == 0 else { throw Self.unavailable() }
        return UInt16(bigEndian: address.sin_port)
    }
    private static func address(_ host: UInt32, port: UInt16) -> sockaddr_in {
        var result = sockaddr_in()
        result.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        result.sin_family = sa_family_t(AF_INET)
        result.sin_addr = in_addr(s_addr: host.bigEndian)
        result.sin_port = port.bigEndian
        return result
    }
    private static func unavailable() -> DeviceAPIError {
        DeviceAPIError(503, "lan_discovery_unavailable", "Local network discovery is unavailable.")
    }
}
