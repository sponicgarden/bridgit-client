import Foundation
import CoreFoundation

public struct LinkedSatellite: Codable, Equatable, Identifiable {
    public var id: String
    public var name: String
    public var address: TailnetAddress
    public var sourceAddress: TailnetAddress
    public var port: UInt16
    public var allowedAgents: Set<String>
    public var returnAgentID: String
    public var allowAgentPause: Bool
    public var paused: Bool
    public var manifest: SatelliteManifest
    public var checkedAt: Date
    public var lastSeenAt: Date?
    public var lastError: String?
    public func state(at now: Date = Date()) -> String {
        if paused { return "paused" }
        if lastError == "unlinked" { return "unlinked" }
        if lastError == "offline" { return "offline" }
        if lastError != nil { return "blocked" }
        guard let lastSeenAt else { return "not_checked" }
        return now >= lastSeenAt && now.timeIntervalSince(lastSeenAt) <= 60 ? "online" : "last_known"
    }
}

/// Owner-selected peer records and credentials remain separate from account and
/// relay pairings. Adding requires a real claim + manifest + acknowledged return
/// exchange; any persistence failure revokes the grants created by that attempt.
public actor SatelliteHubService {
    public typealias Endpoint = () async throws -> (TailnetAddress, UInt16)
    public typealias SharedView = (HostRequest, HostAgent) async throws -> HostResponse
    private let store: AppletStore
    private let authority: HostAuthority
    private let secrets: SatelliteSecrets
    private let configuredClient: SatelliteClient?
    private let endpoint: Endpoint
    private let localName: () async -> String
    private let localType: () async -> String?
    private let sharedView: SharedView
    private var values: [String: LinkedSatellite] = [:]
    private var pending: [String: LinkedSatellite] = [:]
    private var loaded = false
    private var mutating = false
    private var saving = false
    private var calls: [UUID: (String, String, Task<HostResponse, Error>)] = [:]
    private static let stateKey = "host-satellites-v1"
    public init(store: AppletStore, authority: HostAuthority, secrets: SatelliteSecrets, client: SatelliteClient? = nil, endpoint: @escaping Endpoint, localName: @escaping () async -> String, localType: @escaping () async -> String?, sharedView: @escaping SharedView) {
        self.store = store; self.authority = authority; self.secrets = secrets; configuredClient = client; self.endpoint = endpoint; self.localName = localName; self.localType = localType; self.sharedView = sharedView
    }
    private func load() async throws {
        guard !loaded else { return }
        let text = try await store.state(Self.stateKey) ?? "[]"
        let records = try JSONDecoder().decode([LinkedSatellite].self, from: Data(text.utf8))
        guard records.count <= 16, Set(records.map(\.id)).count == records.count, records.allSatisfy({ SatelliteForwardPolicy.validTarget($0.id) && $0.port > 0 }) else { throw DeviceAPIError(500, "invalid_satellite_store", "Satellite storage needs repair.") }
        if !loaded { values = Dictionary(uniqueKeysWithValues: records.map { ($0.id, $0) }); loaded = true }
    }
    private func save(_ records: [String: LinkedSatellite]) async throws {
        guard !saving else { throw DeviceAPIError(409, "satellite_store_busy", "Another satellite change is being saved.") }
        saving = true; defer { saving = false }
        let encoded = try JSONEncoder().encode(records.values.sorted { $0.id < $1.id })
        try await store.setState(Self.stateKey, value: String(decoding: encoded, as: UTF8.self))
        values = records
    }
    private func freshTargetID(_ name: String) -> String {
        let slug = SatelliteForwardPolicy.newID(name: name, existing: []).dropFirst(4).prefix(18)
        while true {
            let suffix = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased().prefix(16)
            let candidate = "sat:" + slug + "-" + suffix
            if values[candidate] == nil { return candidate }
        }
    }
    private func client(_ source: TailnetAddress) -> SatelliteClient { configuredClient ?? .init(transport: TailnetHTTPTransport(source: source)) }
    private func keyID(_ id: String) -> String { "satellite-outgoing:" + id }
    public func list() async throws -> [LinkedSatellite] { try await load(); return values.values.sorted { $0.name < $1.name } }
    private func validateAgents(_ selected: Set<String>) async throws {
        let eligible = Set(try await authority.list().filter { $0.viewer != true && $0.tailnetPeer == nil }.map(\.id))
        guard selected.isSubset(of: eligible) else { throw DeviceAPIError(422, "invalid_satellite_agents", "Choose existing control agents; paired devices are separate.") }
    }
    public func add(_ link: SatellitePairingLink, allowedAgents: Set<String>, allowAgentPause: Bool = false) async throws -> LinkedSatellite {
        guard !mutating, !saving else { throw DeviceAPIError(409, "satellite_store_busy", "Another satellite change is being saved.") }
        mutating = true; defer { mutating = false }
        try await load(); try await validateAgents(allowedAgents)
        guard values.count < 16, !values.values.contains(where: { $0.address == link.address && $0.port == link.port }) else { throw DeviceAPIError(409, "satellite_already_linked", "This satellite is already linked, or the link limit was reached.") }
        let (localAddress, localPort) = try await endpoint()
        guard localAddress.isIPv6 == link.address.isIPv6 else { throw DeviceAPIError(422, "tailnet_family_mismatch", "The listener and satellite must use the same Tailscale address family.") }
        let client = self.client(localAddress)
        var remote: String?, returned: HostAgent?, identifier: String?
        do {
            try Task.checkCancellation()
            let claim = try await client.claim(link, hubName: localName())
            remote = claim.key
            try Task.checkCancellation()
            let report = try SatelliteManifest(await client.manifest(address: link.address, port: link.port, key: claim.key), fallbackName: claim.name)
            try Task.checkCancellation()
            let id = freshTargetID(claim.name); identifier = id
            let (agent, returnKey) = try await authority.createSatelliteReturn(name: "Satellite: " + String(claim.name.prefix(60)), peer: link.address)
            returned = agent
            let now = Date()
            let record = LinkedSatellite(id: id, name: claim.name, address: link.address, sourceAddress: localAddress, port: link.port, allowedAgents: allowedAgents, returnAgentID: agent.id, allowAgentPause: allowAgentPause, paused: false, manifest: report, checkedAt: now, lastSeenAt: now, lastError: nil)
            pending[agent.id] = record
            defer { pending[agent.id] = nil }
            var info: [String: Any] = ["name": await localName(), "port": Int(localPort), "key": returnKey, "targets": [], "portals": []]
            if let type = await localType() { info["device_type"] = type }
            try await client.sendHubInfo(address: link.address, port: link.port, key: claim.key, info: info)
            try Task.checkCancellation()
            try await secrets.save(claim.key, for: keyID(id))
            try Task.checkCancellation()
            try await validateAgents(allowedAgents)
            var next = values; next[id] = record
            try await save(next)
            return record
        } catch {
            if let returned { try? await authority.remove(returned.id); pending[returned.id] = nil }
            if let identifier { try? await secrets.remove(keyID(identifier)) }
            if let remote {
                let acknowledged = await Task.detached { () -> Bool in
                    do { try await client.unlink(address: link.address, port: link.port, key: remote); return true } catch { return false }
                }.value
                if !acknowledged { throw DeviceAPIError(503, "satellite_rollback_unconfirmed", "Pairing was not saved. Remote unlinking could not be confirmed; remove this hub link on the satellite before making a new code.") }
            }
            throw error
        }
    }
    public func setAllowedAgents(_ id: String, agents: Set<String>) async throws {
        guard !mutating, !saving else { throw DeviceAPIError(409, "satellite_store_busy", "Another satellite change is being saved.") }
        mutating = true; defer { mutating = false }
        try await load(); try await validateAgents(agents)
        guard var record = values[id] else { throw DeviceAPIError(404, "satellite_not_found", "This satellite was removed.") }
        record.allowedAgents = agents; var next = values; next[id] = record
        try await save(next)
        for (target, caller, task) in calls.values where target == id && !agents.contains(caller) { task.cancel() }
    }
    public func setPaused(_ id: String, paused: Bool) async throws {
        guard !mutating, !saving else { throw DeviceAPIError(409, "satellite_store_busy", "Another satellite change is being saved.") }
        mutating = true; defer { mutating = false }
        try await load()
        guard var record = values[id] else { throw DeviceAPIError(404, "satellite_not_found", "This satellite was removed.") }
        record.paused = paused; var next = values; next[id] = record
        let encoded = String(decoding: try JSONEncoder().encode(next.values.sorted { $0.id < $1.id }), as: UTF8.self)
        try await authority.pauseSatelliteReturn(record.returnAgentID, paused: paused, records: encoded)
        values = next
        if paused { for (target, _, task) in calls.values where target == id { task.cancel() } }
    }
    @discardableResult public func remove(_ id: String) async throws -> Bool {
        guard !mutating, !saving else { throw DeviceAPIError(409, "satellite_store_busy", "Another satellite change is being saved.") }
        mutating = true; defer { mutating = false }
        try await load()
        guard let record = values[id] else { return true }
        for (target, _, task) in calls.values where target == id { task.cancel() }
        try await authority.remove(record.returnAgentID)
        var acknowledged = false
        if let key = try await secrets.load(keyID(id)) {
            do { try await client(record.sourceAddress).unlink(address: record.address, port: record.port, key: key); acknowledged = true } catch { }
        }
        var next = values; next[id] = nil; try await save(next)
        try await secrets.remove(keyID(id))
        return acknowledged
    }
    @discardableResult public func refresh(_ id: String) async throws -> LinkedSatellite {
        try await load(); try await authority.checkOwnerExecution()
        guard !mutating, !saving, let original = values[id], !original.paused, let key = try await secrets.load(keyID(id)) else { throw DeviceAPIError(409, "satellite_unavailable", "Resume this satellite link before refreshing it.") }
        do {
            let report = try SatelliteManifest(await client(original.sourceAddress).manifest(address: original.address, port: original.port, key: key), fallbackName: original.name)
            try Task.checkCancellation(); try await authority.checkOwnerExecution()
            guard !mutating, !saving, var current = values[id], !current.paused, current.returnAgentID == original.returnAgentID else { throw DeviceAPIError(409, "satellite_changed", "This satellite link changed while refreshing.") }
            current.manifest = report; current.checkedAt = Date(); current.lastSeenAt = current.checkedAt; current.lastError = nil
            var next = values; next[id] = current; try await save(next); return current
        } catch {
            if !Task.isCancelled, !mutating, !saving, var current = values[id], current.returnAgentID == original.returnAgentID {
                current.checkedAt = Date()
                let api = error as? DeviceAPIError
                current.lastError = api?.status == 401 ? "unlinked" : api?.code == "satellite_offline" ? "offline" : "blocked"
                var next = values; next[id] = current; try? await save(next)
            }
            throw error
        }
    }
    public func forward(_ request: HostRequest, agent: HostAgent, authorize: @escaping (Bool) async throws -> Void) async throws -> HostResponse {
        try await load()
        guard !mutating, !saving else { throw DeviceAPIError(409, "satellite_store_busy", "This satellite is being configured.") }
        guard let target = request.query["target"] as? String, SatelliteForwardPolicy.validTarget(target), let operation = SatelliteForwardPolicy.operation(method: request.method, path: request.path), agent.allows(operation),
              let initial = values[target], !initial.paused, initial.allowedAgents.contains(agent.id) else { throw DeviceAPIError(403, "satellite_not_allowed", "This control agent has no grant for that satellite.") }
        try await authority.authorizeOngoing(agent, operation: operation); try await authorize(false)
        if initial.checkedAt > Date() || Date().timeIntervalSince(initial.checkedAt) > 60 { _ = try await refresh(target) }
        guard let record = values[target], !record.paused, record.allowedAgents.contains(agent.id), record.manifest.permits(request),
              let key = try await secrets.load(keyID(target)) else { throw DeviceAPIError(403, "satellite_not_allowed", "This control agent has no current grant for that satellite endpoint.") }
        try await authority.authorizeOngoing(agent, operation: operation)
        try await authorize(true)
        guard !mutating, !saving, let current = values[target], current.returnAgentID == record.returnAgentID, !current.paused, current.allowedAgents.contains(agent.id) else { throw DeviceAPIError(403, "satellite_not_allowed", "The satellite grant changed before sending.") }
        var outgoing = request; outgoing.query.removeValue(forKey: "target")
        if request.method != "GET" {
            guard let key = request.idempotencyKey, !key.isEmpty, key.count <= 128 else { throw DeviceAPIError(400, "idempotency_key_required", "Use an idempotency key for a satellite action.") }
            outgoing.idempotencyKey = SatelliteForwardPolicy.forwardedIdempotencyKey(agent: agent, key: key)
        }
        let id = UUID(), client = self.client(record.sourceAddress)
        let task = Task { try await client.call(address: record.address, port: record.port, key: key, request: outgoing) }
        calls[id] = (target, agent.id, task); defer { calls[id] = nil }
        let response: HostResponse
        do {
            response = try await withTaskCancellationHandler(operation: { try await task.value }, onCancel: { task.cancel() })
        } catch {
            if !Task.isCancelled, !mutating, !saving, var current = values[target], current.address == record.address, current.port == record.port {
                current.checkedAt = Date(); current.lastError = (error as? DeviceAPIError)?.code == "satellite_offline" ? "offline" : "blocked"; var next = values; next[target] = current; try? await save(next)
            }
            throw error
        }
        try Task.checkCancellation(); try await authority.authorizeOngoing(agent, operation: operation); try await authorize(false)
        guard !mutating, !saving, var current = values[target], !current.paused, current.allowedAgents.contains(agent.id), current.returnAgentID == record.returnAgentID else { throw DeviceAPIError(403, "satellite_not_allowed", "The owner changed this satellite link while the call was running.") }
        current.checkedAt = Date(); current.lastSeenAt = current.checkedAt; current.lastError = response.status == 401 ? "unlinked" : nil
        var next = values; next[target] = current; try await save(next)
        var delivered = response
        delivered.headers = delivered.headers.filter { !["content-length", "transfer-encoding", "connection", "set-cookie"].contains($0.key.lowercased()) }
        delivered.headers["X-Bridgit-Ran-On"] = target
        return delivered
    }
    public func publicSatellites(for agent: HostAgent) async throws -> [[String: Any]] {
        try await authority.authorizeOngoing(agent, operation: .deviceRead)
        try await load()
        return values.values.filter { $0.allowedAgents.contains(agent.id) }.sorted { $0.name < $1.name }.map { record in
            ["id": record.id, "name": record.name, "kind": "satellite", "status": record.state(),
             "last_seen": record.lastSeenAt.map { Int64($0.timeIntervalSince1970 * 1000) } as Any? ?? NSNull(),
             "last_checked": Int64(record.checkedAt.timeIntervalSince1970 * 1000),
             "device_type": record.manifest.deviceType as Any? ?? NSNull(), "platform": record.manifest.platform as Any? ?? NSNull(),
             "model": record.manifest.model as Any? ?? NSNull(), "app_version": record.manifest.appVersion as Any? ?? NSNull(),
             "cameras": record.manifest.cameras.map { ["id": record.id + "/" + $0, "target_camera": $0] },
             "capabilities": record.manifest.capabilities.map { ["id": $0.id, "enabled": $0.enabled as Any? ?? NSNull(), "ready": $0.ready as Any? ?? NSNull(), "blocked_reason": $0.blockedReason as Any? ?? NSNull()] },
             "endpoints": record.manifest.endpoints.filter { endpoint in
                 let sample = endpoint.path.replacingOccurrences(of: "{id}", with: "fixture_id")
                 return SatelliteForwardPolicy.operation(method: endpoint.method, path: sample).map(agent.allows) ?? false
             }.map { ["method": $0.method, "path": $0.path, "capability": $0.capability] }] as [String: Any]
        }
    }
    public func returnCall(_ request: HostRequest, agent: HostAgent) async throws -> HostResponse {
        try await load()
        guard !mutating, !saving else { throw DeviceAPIError(409, "satellite_store_busy", "This link is being configured.") }
        guard agent.satelliteRole == "return", let record = values.values.first(where: { $0.returnAgentID == agent.id }) ?? pending[agent.id], !record.paused,
              agent.tailnetPeer == record.address.value else { throw DeviceAPIError(403, "satellite_not_allowed", "This return link is not active.") }
        if request.method == "GET", ["/v1/portal/home", "/v1/portal/logs"].contains(request.path) { return try await sharedView(request, agent) }
        if request.method == "GET", request.path == "/v1/satellite/hub" {
            guard request.query.isEmpty, request.body.isEmpty else { throw DeviceAPIError(422, "invalid_parameter", "Hub status takes no parameters.") }
            var object: [String: Any] = ["name": await localName(), "targets": [], "portals": []]
            if let type = await localType() { object["device_type"] = type }
            return try .json(object)
        }
        if request.method == "POST", request.path == "/v1/satellite/hub/unlink" {
            guard request.query.isEmpty, request.body.isEmpty else { throw DeviceAPIError(422, "invalid_parameter", "Unlink takes no parameters.") }
            var response = try HostResponse.json(["ok": true]); response.afterDelivery = { [weak self] in _ = try? await self?.remove(record.id) }; return response
        }
        if request.method == "GET", request.path == "/v1/satellite/hub/agents" {
            guard record.allowAgentPause, request.query.isEmpty, request.body.isEmpty else { throw DeviceAPIError(403, "agent_management_not_shared", "The hub owner has not shared control-agent management.") }
            let agents = try await authority.list().filter { $0.tailnetPeer == nil && $0.viewer != true }.map { ["id": $0.id, "name": $0.name, "paused": $0.paused] as [String: Any] }
            return try .json(["agents": agents])
        }
        let parts = request.path.split(separator: "/")
        if request.method == "POST", parts.count == 6, Array(parts.prefix(4)) == ["v1", "satellite", "hub", "agents"], parts[5] == "pause" {
            guard record.allowAgentPause, request.query.isEmpty, Set(request.body.keys) == ["paused"], let flag = request.body["paused"] as? NSNumber, CFGetTypeID(flag) == CFBooleanGetTypeID(),
                  let target = try await authority.agent(String(parts[4])), target.tailnetPeer == nil, target.viewer != true else { throw DeviceAPIError(403, "agent_management_not_shared", "This agent is not shared for management.") }
            try await authority.pauseFromSatellite(agent, target: target.id, paused: flag.boolValue)
            return try .json(["id": target.id, "name": target.name, "paused": flag.boolValue])
        }
        throw DeviceAPIError(501, "unsupported_satellite_return", "This return-link operation is unavailable on this Apple hub.")
    }
}
