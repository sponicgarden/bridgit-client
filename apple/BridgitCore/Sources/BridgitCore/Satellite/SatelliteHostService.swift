import Foundation
import CoreFoundation

/// Native adapters persist outgoing peer credentials in Keychain; fixtures use an
/// isolated in-memory implementation. Inbound agent stores contain hashes only.
public protocol SatelliteSecrets {
    func load(_ id: String) async throws -> String?
    func save(_ value: String, for id: String) async throws
    func remove(_ id: String) async throws
}

public struct SatelliteHubInfo: Codable, Equatable {
    public var name: String
    public var address: TailnetAddress
    public var port: UInt16
    public var localAddress: TailnetAddress? = nil
    public var deviceType: String?
    public var targets: [Item]
    public var portals: [Item]
    public struct Item: Codable, Equatable { public var id: String; public var name: String; public var url: String? }
}

public struct SatelliteHubHome {
    public let name: String
    public let observedAt: Date
    public let agents: [String]?
    public let applets: [String]?
}

/// Real direct receiver for an explicitly started satellite. The socket supplies
/// the source address; no JSON/header can choose its authenticated peer.
public actor SatelliteHostService {
    private let authority: HostAuthority
    private let dispatcher: HostDispatcher
    private let store: AppletStore
    private let profile: HostDispatcher.Profile
    private let secrets: SatelliteSecrets
    private let unavailable: (() async -> Void)?
    public typealias ReturnHandler = (HostRequest, HostAgent) async throws -> HostResponse
    private var returnHandler: ReturnHandler?
    private var listener: TailnetHTTPListener?
    private var address: TailnetAddress?
    private var port: UInt16?
    private var listenerRun = UUID()
    private var failures: [String: (minute: Int64, count: Int)] = [:]
    public init(authority: HostAuthority, dispatcher: HostDispatcher, store: AppletStore, secrets: SatelliteSecrets, unavailable: (() async -> Void)? = nil, profile: @escaping HostDispatcher.Profile) {
        self.authority = authority; self.dispatcher = dispatcher; self.store = store; self.secrets = secrets; self.unavailable = unavailable; self.profile = profile
    }
    public func start(address: TailnetAddress, port: UInt16 = 18471) async throws -> UInt16 {
        guard TailnetAddress.localCandidates().contains(address) else { throw DeviceAPIError(409, "tailnet_required", "Turn on Tailscale and choose an address assigned to this device’s tunnel.") }
        await stop()
        let run = UUID(); listenerRun = run
        let listener = TailnetHTTPListener(handler: { [weak self] request, source in
            guard let self else { return .failure(CancellationError()) }
            return await self.handle(request, source: source)
        }, unavailable: { [weak self] in await self?.listenerUnavailable(run: run) })
        self.listener = listener
        do {
            let bound = try await listener.start(address: address.value, port: port)
            try Task.checkCancellation()
            guard listenerRun == run else { throw CancellationError() }
            await authority.setDirectEnabled(true)
            try Task.checkCancellation()
            guard listenerRun == run else { throw CancellationError() }
            self.address = address; self.port = bound
            return bound
        } catch {
            await listener.stop()
            if listenerRun == run {
                self.listener = nil; self.address = nil; self.port = nil
                await authority.setDirectEnabled(false)
            }
            throw error
        }
    }
    public func stop() async {
        listenerRun = UUID()
        let previous = listener; listener = nil; address = nil; port = nil
        await authority.setDirectEnabled(false)
        await previous?.stop()
    }
    private func listenerUnavailable(run: UUID) async {
        guard listenerRun == run else { return }
        let stopped = UUID(); listenerRun = stopped
        let previous = listener; listener = nil; address = nil; port = nil
        await authority.setDirectEnabled(false); await previous?.stop()
        if listenerRun == stopped { await unavailable?() }
    }
    public var boundEndpoint: (TailnetAddress, UInt16)? {
        get async {
            guard let address, let port, let listener, await listener.isReady,
                  self.listener === listener, TailnetAddress.localCandidates().contains(address) else { return nil }
            return (address, port)
        }
    }
    public var isListening: Bool { get async { await boundEndpoint != nil } }
    public func makeLink(name: String, operations: Set<HostOperation>) async throws -> SatellitePairingLink {
        guard let (address, port) = await boundEndpoint else { throw DeviceAPIError(409, "listener_required", "Start this device’s Tailscale listener before making a satellite code.") }
        return try await authority.beginSatellitePairing(address: address, port: port, name: name, operations: operations)
    }
    public func setReturnHandler(_ handler: ReturnHandler?) { returnHandler = handler }
    private func infoKey(_ id: String) -> String { "satellite-hub-info:" + id }
    private func secretKey(_ id: String) -> String { "satellite-return:" + id }
    public func hubInfo(_ id: String) async throws -> SatelliteHubInfo? {
        guard let agent = try await authority.agent(id), agent.satelliteRole == "hub", agent.tailnetPeer != nil else { return nil }
        guard let saved = try await store.state(infoKey(id)) else { return nil }
        return try JSONDecoder().decode(SatelliteHubInfo.self, from: Data(saved.utf8))
    }
    public func readHubHome(_ id: String) async throws -> SatelliteHubHome {
        try await authority.checkOwnerExecution()
        guard let agent = try await authority.agent(id), !agent.paused, let info = try await hubInfo(id), let localAddress = info.localAddress,
              let key = try await secrets.load(secretKey(id)) else { throw DeviceAPIError(409, "hub_return_unavailable", "This hub’s return link is not ready.") }
        let client = SatelliteClient(transport: TailnetHTTPTransport(source: localAddress))
        let response = try await client.call(address: info.address, port: info.port, key: key, request: .init(method: "GET", path: "/v1/portal/home"))
        guard (200...299).contains(response.status), response.data.count <= 1_048_576,
              let object = try JSONSerialization.jsonObject(with: response.data) as? [String: Any] else { throw DeviceAPIError(response.status == 401 ? 401 : 502, "hub_home_unavailable", "The hub did not return its Home report.") }
        try Task.checkCancellation(); try await authority.checkOwnerExecution()
        guard let current = try await authority.agent(id), current.generation == agent.generation, !current.paused,
              try await hubInfo(id) == info, try await secrets.load(secretKey(id)) == key else { throw DeviceAPIError(403, "hub_return_changed", "This hub link changed while reading it.") }
        func names(_ value: Any?) -> [String]? { (value as? [[String: Any]]).map { $0.prefix(100).compactMap { ($0["name"] as? String).map { String($0.prefix(120)) } } } }
        let device = object["device"] as? [String: Any]
        return SatelliteHubHome(name: String((device?["name"] as? String ?? info.name).prefix(120)), observedAt: Date(), agents: names(object["agents"]), applets: names(object["applets"]))
    }
    private func checked(_ original: HostAgent, key: String, source: String) async throws {
        let current = try await authority.authenticateDirect(key: key, source: source)
        guard current.id == original.id, current.generation == original.generation else { throw DeviceAPIError(401, "unauthorized", "This satellite link changed.") }
    }
    func handle(_ wire: TailnetHTTPRequest, source: String) async -> HostResponse {
        do {
            if let address, !TailnetAddress.localCandidates().contains(address) {
                await listenerUnavailable(run: listenerRun)
                throw DeviceAPIError(409, "tailnet_required", "The local Tailscale address is no longer available.")
            }
            guard TailnetAddress(source) != nil else { throw DeviceAPIError(403, "tailnet_required", "Satellite calls must come from a Tailscale peer.") }
            let request = try wire.hostRequest
            if request.path == "/v1/satellite/claim", request.method == "POST" {
                let minute = Int64(Date().timeIntervalSince1970) / 60
                failures = failures.filter { $0.value.minute == minute }
                guard failures.count < 128 || failures[source] != nil, (failures[source]?.count ?? 0) < 20 else { throw DeviceAPIError(429, "rate_limited", "Too many satellite claim attempts. Wait a minute.") }
                failures[source] = (minute, (failures[source]?.count ?? 0) + 1)
                guard request.query.isEmpty, Set(request.body.keys).isSubset(of: ["token", "hub_name"]), let token = request.body["token"] as? String,
                      request.body["hub_name"] == nil || request.body["hub_name"] is String else { throw DeviceAPIError(422, "invalid_parameter", "Supply the one-time token and optional hub_name.") }
                let claimed = try await authority.claimSatellite(token: token, source: source, hubName: request.body["hub_name"] as? String)
                return try .json(["key": claimed.key, "name": claimed.name, "agent": claimed.agent.name])
            }
            guard let authorization = wire.headers["authorization"], authorization.hasPrefix("Bearer ") else { throw DeviceAPIError(401, "unauthorized", "A source-bound satellite key is required.") }
            let key = String(authorization.dropFirst(7))
            let agent = try await authority.authenticateDirect(key: key, source: source)
            if agent.satelliteRole == "return" {
                guard let returnHandler else { throw DeviceAPIError(501, "return_link_unavailable", "This hub has no return-link receiver configured.") }
                let response = try await returnHandler(request, agent)
                try await checked(agent, key: key, source: source)
                return response
            }
            switch (request.method, request.path) {
            case ("GET", "/v1/satellite/manifest"):
                guard request.query.isEmpty, request.body.isEmpty else { throw DeviceAPIError(422, "invalid_parameter", "Manifest takes no parameters.") }
                let response = try await manifest(agent)
                try await checked(agent, key: key, source: source)
                return response
            case ("POST", "/v1/satellite/hub-info"):
                guard request.query.isEmpty else { throw DeviceAPIError(422, "invalid_parameter", "Hub info takes no query.") }
                var (info, returnKey) = try Self.parseHubInfo(request.body, source: source)
                info.localAddress = address
                let identifier = secretKey(agent.id), previous = try await secrets.load(identifier)
                try await secrets.save(returnKey, for: identifier)
                do {
                    try await checked(agent, key: key, source: source)
                    try await store.setState(infoKey(agent.id), value: String(decoding: JSONEncoder().encode(info), as: UTF8.self))
                    try await checked(agent, key: key, source: source)
                } catch {
                    if let previous { try? await secrets.save(previous, for: identifier) } else { try? await secrets.remove(identifier) }
                    throw error
                }
                return try .json(["ok": true])
            case ("POST", "/v1/satellite/unlink"):
                guard request.query.isEmpty, request.body.isEmpty else { throw DeviceAPIError(422, "invalid_parameter", "Unlink takes no parameters.") }
                var response = try HostResponse.json(["ok": true])
                response.afterDelivery = { [weak self] in try? await self?.removeHub(agent.id, generation: agent.generation) }
                return response
            default:
                let now = Int64(Date().timeIntervalSince1970 * 1000)
                let job = HostJob(id: UUID().uuidString, sequence: 0, agentID: agent.id, generation: agent.generation, deviceID: authority.deviceID, mode: "direct", deadline: now + 65_000, createdAt: now, request: request, tailnetSource: source)
                return await dispatcher.dispatch(job, admitted: false, authenticated: agent)
            }
        } catch { return .failure(error) }
    }
    public func removeHub(_ id: String, generation: String? = nil) async throws {
        guard let current = try await authority.agent(id), current.satelliteRole == "hub", generation == nil || current.generation == generation else { return }
        try await authority.remove(id)
        try await store.removeState(infoKey(id)); try await secrets.remove(secretKey(id))
    }
    private func manifest(_ agent: HostAgent) async throws -> HostResponse {
        let facts = try await profile(agent)
        let capabilities: [[String: Any]] = facts.capabilities.filter { ["device", "camera", "mic", "speaker", "interactions"].contains($0.key) }.sorted { $0.key < $1.key }.map { id, cap in
            ["id": id == "device" ? "status" : id, "enabled": cap.enabled && cap.agentAllowed, "ready": cap.ready,
             "blocked_reason": cap.blockedReason as Any? ?? NSNull(), "implemented": cap.implemented, "supported": cap.supported]
        }
        let endpoints: [[String: Any]] = HostAPIContract.allowed(agent).filter { operation in
            let canonical = operation.path.replacingOccurrences(of: "{id}", with: "00000000-0000-0000-0000-000000000001")
            let capability = facts.capabilities[operation.grant.capability]
            return SatelliteAuthorityPolicy.hubMayCall(.init(method: operation.method, path: canonical)) && (operation.grant == .deviceRead || capability?.enabled == true && capability?.implemented == true && capability?.supported == true)
        }.map { operation in
            ["method": operation.method, "path": operation.path, "capability": operation.grant == .deviceRead ? "status" : operation.grant.capability]
        }
        let cameras = facts.capabilities["camera"].flatMap { $0.supported ? $0.constraints.parameters?["camera"] : nil } ?? []
        let name = try await authority.satelliteName() ?? facts.device.name
        return try .json(["name": name, "model": NSNull(), "platform": facts.device.platform,
                          "device_type": facts.device.deviceType, "app_version": facts.device.appVersion, "api_revision": facts.device.apiRevision,
                          "install": facts.device.installID as Any? ?? NSNull(), "battery": NSNull(), "cameras": cameras,
                          "capabilities": capabilities, "endpoints": endpoints])
    }
    private static func parseHubInfo(_ body: [String: Any], source: String) throws -> (SatelliteHubInfo, String) {
        let invalid = DeviceAPIError(422, "invalid_parameter", "Supply the hub’s port, source-bound return key and bounded targets/portals.")
        guard Set(body.keys).isSubset(of: ["name", "device_type", "port", "key", "targets", "portals"]), let address = TailnetAddress(source),
              let key = body["key"] as? String, SatelliteAuthorityPolicy.validKey(key), let number = body["port"] as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID(),
              let port = UInt16(number.stringValue), port > 0, body["name"] == nil || body["name"] is String else { throw invalid }
        func items(_ key: String, portals: Bool) throws -> [SatelliteHubInfo.Item] {
            guard let value = body[key] else { return [] }
            guard let entries = value as? [[String: Any]], entries.count <= 100 else { throw invalid }
            return try entries.map { entry in
                guard let id = entry["id"] as? String, !id.isEmpty, id.count <= 128, let name = entry["name"] as? String, name.count <= 120 else { throw invalid }
                let url = entry["url"] as? String
                if portals, let url { guard let parsed = URLComponents(string: url), parsed.scheme == "https", parsed.host != nil, parsed.user == nil, parsed.password == nil, url.count <= 4096 else { throw invalid } }
                return .init(id: id, name: name, url: portals ? url : nil)
            }
        }
        let rawType = (body["device_type"] as? String)?.lowercased()
        let type = rawType.flatMap { ["phone", "tablet", "computer", "tv", "watch", "camera"].contains($0) ? $0 : nil }
        return (SatelliteHubInfo(name: String((body["name"] as? String ?? "Hub").prefix(60)), address: address, port: port, deviceType: type, targets: try items("targets", portals: false), portals: try items("portals", portals: true)), key)
    }
}
