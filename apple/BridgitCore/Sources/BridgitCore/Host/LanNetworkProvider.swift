import CryptoKit
import Foundation
import Network
#if os(macOS)
import CoreWLAN
#elseif os(iOS)
import NetworkExtension
#endif

/// A network observation plus an opaque lifetime token. IP/subnet equality must
/// never join inventory from two different networks with reused private ranges.
struct LanNetworkSnapshot: Equatable {
    let network: LanInterfaceSelection
    /// Per-path token for in-flight work and idempotent response delivery.
    let scope: String
    /// History may survive reconnects only with a specific authorized identity.
    let historyScope: String
}

/// Input from an OS-authorized Wi-Fi adapter, never from a client request.
/// A nil gateway observation means identity is incomplete, not an empty route list.
struct LanHistoryIdentity {
    let bssid: String
    let gateways: [String]?

    func scope(for network: LanInterfaceSelection) -> String? {
        let value = bssid.lowercased()
        let octets = value.split(separator: ":", omittingEmptySubsequences: false)
        guard octets.count == 6,
              octets.allSatisfy({ $0.utf8.count == 2 && $0.utf8.allSatisfy { (48...57).contains($0) || (97...102).contains($0) } }),
              value != "02:00:00:00:00:00", value != "00:00:00:00:00:00",
              value != "ff:ff:ff:ff:ff:ff", let gateways,
              (1...30).contains(network.prefix),
              gateways.allSatisfy({ LanDiscoveryRules.onLink($0, local: network.address, prefix: network.prefix) }) else { return nil }
        let digest = SHA256.hash(data: Data(value.utf8)).map { String(format: "%02x", $0) }.joined()
        return "wifi:\(digest):\(network.prefix):\(Set(gateways).sorted().joined(separator: ","))"
    }
}

/// Observes OS network changes without initiating discovery or requesting access.
/// Reads already-authorized Wi-Fi identity without prompting or scanning.
/// Missing/redacted identity uses process/path lifetime only.
final class LanNetworkProvider: @unchecked Sendable {
    typealias IdentityReader = @Sendable (LanInterfaceSelection) async -> LanHistoryIdentity?
    private let readIdentity: IdentityReader?
    private let lock = NSLock()
    private var state: Result<LanNetworkSnapshot, Error> = .failure(DeviceAPIError(409, "lan_unavailable", "The local network is not ready."))
    private var monitor: NWPathMonitor?
    init() {
        readIdentity = Self.authorizedIdentity
        let monitor = NWPathMonitor()
        self.monitor = monitor
        monitor.pathUpdateHandler = { [weak self] path in
            self?.accept(Result { try LanInterfaceSelection.capture(path: path) })
        }
        monitor.start(queue: DispatchQueue(label: "cc.bridgit.lan.network"))
    }
    /// Deterministic network-event injection for tests; no monitor or sockets.
    init(testing: Void, readIdentity: IdentityReader? = nil) { self.readIdentity = readIdentity }
    deinit { monitor?.cancel() }

    func snapshot() throws -> LanNetworkSnapshot {
        lock.lock(); defer { lock.unlock() }
        return try state.get()
    }
    /// Refresh before inventory reads/scans so revoked OS access does not keep
    /// selecting durable history merely because the network path stayed up.
    func refreshIdentity() async throws {
        guard let readIdentity else { return }
        let expected = try snapshot()
        let identity = await readIdentity(expected.network)
        apply(identity, to: expected)
    }

    private func apply(_ identity: LanHistoryIdentity?, to expected: LanNetworkSnapshot) {
        lock.lock(); defer { lock.unlock() }
        guard let current = try? state.get(), current == expected else { return }
        let durable = identity?.scope(for: current.network)
        let proposed = durable ?? current.scope
        guard proposed != current.historyScope else { return }
        // An identity change also invalidates pending/cached responses. A stable
        // history key must never serve as a connection-generation token.
        let generation = "session:" + UUID().uuidString.lowercased()
        state = .success(.init(network: current.network, scope: generation,
                               historyScope: durable ?? generation))
    }

    private static func authorizedIdentity(_ network: LanInterfaceSelection) async -> LanHistoryIdentity? {
        guard network.isWiFi else { return nil }
        let bssid: String?
        #if os(macOS)
        bssid = CWWiFiClient.shared().interface(withName: network.name)?.bssid()
        #elseif os(iOS)
        bssid = await NEHotspotNetwork.fetchCurrent()?.bssid
        #else
        bssid = nil
        #endif
        guard let bssid else { return nil }
        return LanHistoryIdentity(bssid: bssid, gateways: network.gateways)
    }

    func require(_ expected: LanNetworkSnapshot) throws -> LanInterfaceSelection {
        let current = try snapshot()
        guard current == expected else { throw DeviceAPIError(409, "lan_changed", "The local network changed during discovery.") }
        return current.network
    }
    /// NWPath has no public durable network handle equivalent. Rotate on each
    /// delivered path change, even if the replacement reuses the same address.
    /// This conservative fallback is documented as incomplete history parity.
    func accept(_ selection: Result<LanInterfaceSelection, Error>, identity: LanHistoryIdentity? = nil) {
        lock.lock(); defer { lock.unlock() }
        state = selection.map { network in
            let generation = "session:" + UUID().uuidString.lowercased()
            return .init(network: network, scope: generation,
                         historyScope: identity?.scope(for: network) ?? generation)
        }
    }
}
