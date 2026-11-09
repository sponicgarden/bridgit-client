import Foundation

/// One owner host's callback configuration and process-local delivery worker.
/// UI adapters supply owner-controlled network policy and Keychain storage.
public actor CallbackHostService {
    public typealias Policy = () async -> CallbackNetworkPolicy
    private let authority: HostAuthority
    private let store: AppletStore
    private let configuration: IndexedCallbackConfiguration
    private let runtime: CallbackRuntime
    private let delivery: CallbackDelivery
    private let policy: Policy
    public init(authority: HostAuthority, store: AppletStore, secrets: SatelliteSecrets,
                appVersion: String, outboundAccess: OutboundAccess = .unrestricted, policy: @escaping Policy) {
        let config = IndexedCallbackConfiguration(store: store, secrets: secrets)
        self.authority = authority; self.store = store; self.configuration = config; self.policy = policy
        let transport: CallbackDelivery.Transport = { request, authorize in
            try await PinnedConnectionHTTP.send(request, outboundAccess: outboundAccess, authorize: authorize)
        }
        self.delivery = CallbackDelivery(authority: authority, store: store, configuration: config, appVersion: appVersion, policy: policy, transport: transport)
        self.runtime = CallbackRuntime(store: store, authority: authority, configuration: config, appVersion: appVersion, policy: policy, transport: transport)
    }
    /// Test-only transport injection is internal; production always uses pinned networking.
    init(authority: HostAuthority, store: AppletStore, secrets: SatelliteSecrets,
         appVersion: String, policy: @escaping Policy, transport: @escaping CallbackDelivery.Transport,
         beforeRunDue: (() async -> Void)? = nil) {
        let config = IndexedCallbackConfiguration(store: store, secrets: secrets)
        self.authority = authority; self.store = store; self.configuration = config; self.policy = policy
        self.delivery = CallbackDelivery(authority: authority, store: store, configuration: config, appVersion: appVersion, policy: policy, transport: transport)
        self.runtime = CallbackRuntime(store: store, authority: authority, configuration: config, appVersion: appVersion, policy: policy, transport: transport, beforeRunDue: beforeRunDue)
    }
    public func start() async throws { try await runtime.start() }
    public func stop() async { await runtime.stop() }
    private func requireOwnerTarget(_ id: String) async throws {
        guard let target = try await authority.agent(id), target.viewer != true else {
            throw DeviceAPIError(404, "not_found", "Unknown control agent.")
        }
    }
    /// Native owner entry shows only the hostname outside the edit flow.
    public func ownerStatus(agentID: String) async throws -> [String: Any] {
        try await configuration.status(agentID: agentID) { try await self.requireOwnerTarget(agentID) }
    }
    public func ownerURL(agentID: String) async throws -> String? {
        try await configuration.ownerURL(agentID: agentID) { try await self.requireOwnerTarget(agentID) }
    }
    /// Never exposed through host API routes; caller must present this only in the owner's one-time setup.
    public func ownerSetupSecret(agentID: String) async throws -> String? {
        try await configuration.ownerSetupSecret(agentID: agentID) { try await self.requireOwnerTarget(agentID) }
    }
    public func validateOwnerURL(_ url: String) async throws {
        let current = await policy()
        _ = try CallbackRules.destination(url, tailnetDomain: current.tailnetDomain, allowInternet: current.allowInternet)
    }
    /// A native owner creates a fresh receiver key and sees it once after saving.
    public func ownerSet(agentID: String, url: String) async throws -> String {
        var random = SystemRandomNumberGenerator()
        let bytes = (0..<32).map { _ in UInt8.random(in: .min ... .max, using: &random) }
        let secret = "cbs_" + Data(bytes).base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
        _ = try await configuration.set(agentID: agentID, url: url, secret: secret, caller: nil, policy: policy) {
            try await self.requireOwnerTarget(agentID)
        }
        await runtime.cancel(agentID: agentID)
        return secret
    }
    public func ownerRemove(agentID: String) async throws {
        _ = try await configuration.remove(agentID: agentID, caller: nil) { try await self.requireOwnerTarget(agentID) }
        await runtime.cancel(agentID: agentID)
    }
    public func configuration(_ request: HostRequest, agent: HostAgent) async throws -> HostResponse {
        let operation: HostOperation = request.method == "GET" ? .configurationRead : .configurationWrite
        let authorize: () async throws -> Void = { [authority] in try await authority.authorizeOngoing(agent, operation: operation) }
        try await authorize()
        guard request.query.isEmpty else { throw DeviceAPIError(422, "invalid_parameter", "Callback configuration takes no query parameters.") }
        let parts = request.path.split(separator: "/").map(String.init)
        if request.method == "GET", parts.count == 4, request.body.isEmpty {
            let agents = try await authority.list().filter { $0.viewer != true }
            let values = try await configuration.list(agentIDs: agents.map(\.id), authorize: authorize)
            return try .json(["callbacks": values])
        }
        guard parts.count == 5, UUID(uuidString: parts[4]) != nil else { throw DeviceAPIError(404, "not_found", "Unknown callback route.") }
        let id = parts[4].lowercased()
        if request.method == "DELETE", request.body.isEmpty {
            // Android's delegated DELETE removes only configuration. An already
            // queued event consults the current configuration at its next attempt.
            let result = try await configuration.remove(agentID: id, caller: agent, invalidatePending: false, authorize: authorize)
            return try .json(result)
        }
        guard request.method == "POST", Set(request.body.keys).isSubset(of: ["url", "secret"]),
              let url = request.body["url"] as? String, request.body["secret"] == nil || request.body["secret"] is String else {
            throw DeviceAPIError(422, "invalid_parameter", "Supply a callback URL and optional receiver signing key.")
        }
        return try .json(await configuration.set(agentID: id, url: url, secret: request.body["secret"] as? String,
                                                 caller: agent, policy: policy, authorize: authorize))
    }
    func status(agent: HostAgent) async throws -> [String: Any] {
        try await configuration.status(agentID: agent.id) {
            try await self.authority.authorizeOngoing(agent, operation: .deviceRead)
        }
    }
    public func test(agent: HostAgent) async throws -> HostResponse {
        try await authority.authorizeOngoing(agent, operation: .interactionsRead)
        guard let current = try await configuration.testTarget(agentID: agent.id, authorize: {
            try await self.authority.authorizeOngoing(agent, operation: .interactionsRead)
        }) else { throw DeviceAPIError(404, "not_found", "The owner has not set a callback for this agent.") }
        let revision = try await store.callbackGrantEpoch(agent.id)
        let id = try await store.createCallbackTestEvent(owner: agent.eventOwner, requestedBy: agent)
        let host = URLComponents(string: current.url)?.host ?? "unknown"
        let status: Int
        do {
            status = try await delivery.attempt(agentID: agent.id, grantRevision: revision, configEpoch: current.removalEpoch,
                                                eventOwner: agent.eventOwner, eventID: id, allowTest: true)
        } catch is CancellationError { throw CancellationError() }
        catch let error as DeviceAPIError where ["agent_revoked", "agent_not_allowed", "foreground_required"].contains(error.code) { throw error }
        catch {
            let code = (error as? DeviceAPIError)?.code
            let reasons = ["connection_timeout": "timeout", "connection_dns_failed": "dns", "invalid_connection": "not_allowed", "invalid_parameter": "not_allowed", "callback_changed": "changed", "external_paused": "paused"]
            let reason = code.flatMap { reasons[$0] } ?? "connection_failed"
            return try .json(["error": "callback_failed", "message": "The callback did not accept the test event.",
                              "host": host, "delivered": false, "reason": reason], status: 502)
        }
        guard (200...299).contains(status) else {
            return try .json(["error": "callback_failed", "message": "The callback did not accept the test event.",
                              "host": host, "delivered": false, "http_status": status], status: 502)
        }
        return try .json(["delivered": true, "http_status": status, "host": host])
    }
}
