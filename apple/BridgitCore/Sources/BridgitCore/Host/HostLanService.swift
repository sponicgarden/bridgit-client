import Foundation

/// Shared execution service for the host's native owner UI and authenticated API.
/// The caller supplies current authorization; this service never creates grants.
public actor HostLanService {
    public typealias Authorization = @Sendable () async throws -> Void
    private let network: LanNetworkProvider
    private let history: LanDiscoveryHistoryStore
    private let session: LanDiscoverySession
    private let catalog: LanDeviceCatalog
    private var inventoryBusy = false
    private let outboundAccess: OutboundAccess
    private let enabled: @Sendable () async -> Bool

    public init(directory: URL, outboundAccess: OutboundAccess = .unrestricted, enabled: @escaping @Sendable () async -> Bool) throws {
        self.outboundAccess = outboundAccess
        self.network = LanNetworkProvider()
        self.history = try LanDiscoveryHistoryStore(directory: directory)
        self.session = LanDiscoverySession()
        self.catalog = try LanDeviceCatalog()
        self.enabled = enabled
    }
    init(network: LanNetworkProvider, history: LanDiscoveryHistoryStore,
         session: LanDiscoverySession, outboundAccess: OutboundAccess = .unrestricted, enabled: @escaping @Sendable () async -> Bool) throws {
        self.outboundAccess = outboundAccess
        self.network = network; self.history = history; self.session = session
        self.catalog = try LanDeviceCatalog(); self.enabled = enabled
    }
    /// This checks the selected direct network only, not OS local-network consent.
    public nonisolated var networkAvailable: Bool { (try? network.snapshot()) != nil }

    public static func capability(agent: HostAgent, serviceAvailable: Bool, enabled: Bool,
                                  networkAvailable: Bool, hostReady: Bool, contextAllowed: Bool,
                                  execution: [String], outboundPaused: Bool = false) -> ProfileCapability {
        let allowed = agent.viewer != true && agent.allows(.lanDiscovery)
        let operations = HostAPIRoutes.operations(for: agent, capability: "lan_discovery", lanAvailable: serviceAvailable && enabled)
            .filter { !outboundPaused || $0.method == "GET" }
        let ready = serviceAvailable && networkAvailable && hostReady && contextAllowed
        let reason: String? = !allowed ? "agent_not_allowed" : !serviceAvailable ? "operation_unavailable" :
            !enabled ? "capability_disabled" : !contextAllowed || !hostReady ? "foreground_required" :
            !networkAvailable ? "lan_unavailable" : nil
        return .init(runtimeID: "lan_discovery", implemented: true, supported: serviceAvailable,
                     enabled: enabled, ready: ready, agentAllowed: allowed,
                     callable: ready && enabled && allowed && !operations.isEmpty, blockedReason: reason,
                     constraints: .init(execution: execution,
                        parameters: ["network": ["direct_wifi_or_ethernet_private_ipv4"],
                                     "os_local_network_permission": ["checked_when_requested"],
                                     "observations": ["unverified", "not_enrollment"],
                                     "active_scan": [outboundPaused ? "paused" : "allowed"]],
                        limits: ["hosts": 254, "timeout_seconds": 25]), operations: operations)
    }

    public func isEnabled() async -> Bool { await enabled() }

    nonisolated func requireNetworkScope(_ scope: String?) throws {
        guard let scope, try network.snapshot().scope == scope else {
            throw DeviceAPIError(409, "lan_changed", "The local network changed. Start a new scan with a new action key.")
        }
    }

    /// Native owner browsing is independent of permission for agents to scan.
    /// This entry point cannot choose a request, network scope, or active scan.
    public func ownerInventory() async throws -> HostResponse {
        try await response(.init(method: "GET", path: "/v1/lan/discovered"), requireEnabled: false, authorize: {})
    }
    /// Read the local bundled discovery catalog without starting discovery or granting an agent access.
    public func ownerCoverage() async throws -> HostResponse {
        try await response(.init(method: "GET", path: "/v1/lan/catalog"), requireEnabled: false, authorize: {})
    }
    /// Called only by the native owner's confirmed scan action. No custom targets.
    public func ownerScan(authorize: @escaping Authorization) async throws -> HostResponse {
        try await response(.init(method: "POST", path: "/v1/lan/discover"), requireEnabled: false, authorize: authorize)
    }
    public func ownerLogo(id: String, raw: Bool = true) async throws -> HostResponse {
        try await response(.init(method: "GET", path: "/v1/lan/brands/" + id + "/logo", query: ["raw": raw]),
                           requireEnabled: false, authorize: {})
    }
    public func execute(_ request: HostRequest, authorize: @escaping Authorization) async throws -> HostResponse {
        try await response(request, requireEnabled: true, authorize: authorize)
    }
    private func response(_ request: HostRequest, requireEnabled: Bool, authorize: @escaping Authorization) async throws -> HostResponse {
        let enabled = enabled
        let check: Authorization = {
            try Task.checkCancellation()
            try await authorize()
            let policyAllows = requireEnabled ? await enabled() : true
            guard policyAllows else { throw DeviceAPIError(403, "lan_disabled", "Network discovery is off.") }
        }
        try await check()
        let input = try HostLanRequest(request)
        let report = LanDiscoveryReport(catalog: catalog)
        switch input {
        case .catalog:
            return try HostResponse.json(report.coverage).requiringDelivery(check)
        case let .logo(id, raw):
            guard let brand = catalog.brands.first(where: { $0.id == id }), let bytes = catalog.logo(id: id) else {
                throw DeviceAPIError(404, "not_found", "The brand logo was not found.")
            }
            if raw { return HostResponse(contentType: brand.mime, data: bytes, headers: ["Cache-Control": "private, max-age=86400", "X-Content-Type-Options": "nosniff"]).requiringDelivery(check) }
            var value = brand.metadata; value["data_base64"] = bytes.base64EncodedString()
            return try HostResponse.json(value).requiringDelivery(check)
        case .remembered, .discover:
            guard !inventoryBusy else { throw DeviceAPIError(409, "lan_busy", "A local network operation is already running.") }
            inventoryBusy = true
            defer { inventoryBusy = false }
            let network = network
            let outboundAccess = outboundAccess
            let allowProbe: Authorization = {
                try await check()
                try outboundAccess.require()
            }
            if case .discover = input { try await allowProbe() }
            try await network.refreshIdentity()
            let snapshot = try network.snapshot()
            let current: @Sendable () async throws -> LanInterfaceSelection = { try network.require(snapshot) }
            let devices: [LanDiscoveredDevice]
            var payload: [String: Any] = ["complete": false, "scan_performed": false, "observations": [],
                "network": ["address": snapshot.network.address, "prefix_length": snapshot.network.prefix,
                            "gateways": snapshot.network.gateways, "scope": "direct_wifi_or_ethernet_ipv4",
                            "history_persistence": snapshot.historyScope == snapshot.scope ? "connection" : "authorized_network"]]
            if case let .discover(hosts, subnet) = input {
                let result = try await session.scan(hosts: hosts, subnet: subnet, timeout: requireEnabled ? .seconds(25) : .seconds(20), authorize: allowProbe, currentNetwork: current)
                try await check()
                _ = try network.require(snapshot)
                devices = try await history.update(scope: snapshot.historyScope,
                    observed: LanDiscoveredDevice.devices(result.observations), reliable: result.reliable)
                payload["scan_performed"] = true
                payload["observations"] = try JSONSerialization.jsonObject(with: JSONEncoder().encode(result.observations))
                payload["methods"] = result.methods.map { ["method": $0.method, "status": $0.status] }
            } else {
                devices = try await history.read(scope: snapshot.historyScope)
            }
            // A network change during an awaited history operation must not
            // return the previous network's inventory to the new network view.
            try await check()
            _ = try network.require(snapshot)
            var result = try HostResponse.json(report.enrich(payload, devices: devices)).requiringDelivery {
                try await check()
                _ = try network.require(snapshot)
            }
            result.lanNetworkScope = snapshot.scope
            return result
        }
    }
}
