import Darwin
import Foundation

/// Android's bounded inventory probe: no payload, banner reads, arbitrary ports,
/// routed targets or fallback to a different interface.
enum LanTCPInventory {
    static let ports: [UInt16] = [80, 443, 554, 2020]
    typealias Probe = @Sendable (LanInterfaceSelection, String, UInt16) async throws -> Bool

    static func scan(network: LanInterfaceSelection, hosts: [String], observations: LanDiscoveryAccumulator,
                     authorize: @escaping @Sendable () async throws -> Void,
                     currentNetwork: @escaping @Sendable () async throws -> LanInterfaceSelection,
                     probe: @escaping Probe = { network, host, port in
                         try await connect(network: network, host: host, port: port)
                     }) async throws {
        _ = try LanDiscoveryRules.targets(hosts: hosts, subnet: false, local: network.address, prefix: network.prefix)
        guard try await currentNetwork() == network else { throw changedNetwork() }
        var seen = Set<String>()
        let unique = hosts.filter { seen.insert($0).inserted }
        for start in stride(from: 0, to: unique.count, by: 16) {
            try Task.checkCancellation()
            try await authorize()
            try await withThrowingTaskGroup(of: Void.self) { group in
                for host in unique[start..<min(start + 16, unique.count)] {
                    group.addTask {
                        for port in ports {
                            try Task.checkCancellation()
                            try await authorize()
                            guard try await currentNetwork() == network else { throw changedNetwork() }
                            let open = try await probe(network, host, port)
                            try Task.checkCancellation()
                            try await authorize()
                            guard try await currentNetwork() == network else { throw changedNetwork() }
                            if open { await observations.add(host: host, source: .tcp, service: "tcp_open", port: Int(port)) }
                        }
                    }
                }
                try await group.waitForAll()
            }
        }
        try await authorize()
        guard try await currentNetwork() == network else { throw changedNetwork() }
    }

    private static func changedNetwork() -> DeviceAPIError {
        DeviceAPIError(409, "lan_changed", "The local network changed during discovery.")
    }

    static func connect(network: LanInterfaceSelection, host: String, port: UInt16) async throws -> Bool {
        guard ports.contains(port), LanDiscoveryRules.onLink(host, local: network.address, prefix: network.prefix) else {
            throw DeviceAPIError(422, "invalid_parameter", "Invalid local inventory target.")
        }
        try Task.checkCancellation()
        // The nonblocking socket is polled on a worker queue for at most 180ms.
        // Cancellation is checked again before any result is admitted or next probe starts.
        let cancellation = ProbeCancellation()
        let result: Bool = try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                DispatchQueue.global(qos: .utility).async {
                    do {
                        try cancellation.check()
                        continuation.resume(returning: try connectBound(network: network, host: host, port: port))
                    } catch { continuation.resume(throwing: error) }
                }
            }
        } onCancel: { cancellation.cancel() }
        try Task.checkCancellation()
        return result
    }

    private static func connectBound(network: LanInterfaceSelection, host: String, port: UInt16) throws -> Bool {
        guard let local = LanDiscoveryRules.privateIPv4(network.address), let target = LanDiscoveryRules.privateIPv4(host),
              network.index != 0 else { throw changedNetwork() }
        return try connectSocket(local: local, target: target, interfaceIndex: network.index, port: port)
    }

    /// Internal socket primitive. The discovery entry point validates LAN scope
    /// and fixed ports before calling it. Kept separate for loopback wire tests.
    static func connectSocket(local: UInt32, target: UInt32, interfaceIndex: UInt32, port: UInt16) throws -> Bool {
        guard interfaceIndex != 0 else { throw unavailable() }
        let descriptor = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP)
        guard descriptor >= 0 else { throw unavailable() }
        defer { close(descriptor) }
        guard fcntl(descriptor, F_SETFD, FD_CLOEXEC) == 0,
              fcntl(descriptor, F_SETFL, O_NONBLOCK) == 0 else { throw unavailable() }
        var interface = interfaceIndex
        guard setsockopt(descriptor, IPPROTO_IP, IP_BOUND_IF, &interface, socklen_t(MemoryLayout.size(ofValue: interface))) == 0 else {
            throw unavailable()
        }
        var source = address(local, port: 0)
        let bound = withUnsafePointer(to: &source) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.bind(descriptor, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
        }
        guard bound == 0 else { throw changedNetwork() }
        var destination = address(target, port: port)
        let status = withUnsafePointer(to: &destination) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.connect(descriptor, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
        }
        if status == 0 { return true }
        guard errno == EINPROGRESS || errno == EWOULDBLOCK else { return try outcome(errno) }
        var event = pollfd(fd: descriptor, events: Int16(POLLOUT), revents: 0)
        let ready = poll(&event, 1, 180)
        if ready == 0 { return false }
        guard ready > 0 else { throw unavailable() }
        var error: Int32 = 0
        var length = socklen_t(MemoryLayout.size(ofValue: error))
        guard getsockopt(descriptor, SOL_SOCKET, SO_ERROR, &error, &length) == 0 else { throw unavailable() }
        return try outcome(error)
    }

    private static func outcome(_ error: Int32) throws -> Bool {
        if error == 0 { return true }
        if [ECONNREFUSED, ETIMEDOUT, EHOSTUNREACH].contains(error) { return false }
        throw unavailable()
    }

    private static func unavailable() -> DeviceAPIError {
        DeviceAPIError(503, "lan_probe_unavailable", "Local network inventory is unavailable.")
    }

    private static func address(_ value: UInt32, port: UInt16) -> sockaddr_in {
        var result = sockaddr_in()
        result.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        result.sin_family = sa_family_t(AF_INET)
        result.sin_port = port.bigEndian
        result.sin_addr = in_addr(s_addr: value.bigEndian)
        return result
    }

    private final class ProbeCancellation: @unchecked Sendable {
        private let lock = NSLock()
        private var cancelled = false
        func cancel() { lock.withLock { cancelled = true } }
        func check() throws {
            if lock.withLock({ cancelled }) { throw CancellationError() }
        }
    }
}
