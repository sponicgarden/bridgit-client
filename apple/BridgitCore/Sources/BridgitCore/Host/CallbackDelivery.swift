import Foundation

/// One attempt. The event remains in the pull store on failure; scheduling and
/// lifecycle are separate and must not imply delivery while the host is stopped.
actor CallbackDelivery {
    typealias Policy = () async -> CallbackNetworkPolicy
    typealias Transport = (ConnectionHTTPRequest, @escaping () async throws -> Void) async throws -> ConnectionHTTPResponse
    private let authority: HostAuthority
    private let store: AppletStore
    private let configuration: IndexedCallbackConfiguration
    private let policy: Policy
    private let transport: Transport
    private let appVersion: String
    private let beforeFinalEligibility: (() async throws -> Void)?
    init(authority: HostAuthority, store: AppletStore, configuration: IndexedCallbackConfiguration,
         appVersion: String, policy: @escaping Policy, transport: Transport? = nil,
         beforeFinalEligibility: (() async throws -> Void)? = nil) {
        self.authority = authority; self.store = store; self.configuration = configuration
        self.appVersion = appVersion; self.policy = policy; self.beforeFinalEligibility = beforeFinalEligibility
        self.transport = transport ?? { request, authorize in try await PinnedConnectionHTTP.send(request, authorize: authorize) }
    }

    func outcome(agentID: String, grantRevision: Int64, configEpoch: Int64, eventOwner: String, eventID: Int64) async -> CallbackAttemptOutcome {
        do {
            let status = try await attempt(agentID: agentID, grantRevision: grantRevision, configEpoch: configEpoch, eventOwner: eventOwner, eventID: eventID)
            return (200...299).contains(status) ? .delivered : .failed
        } catch is CancellationError { return .dropped }
        catch let error as DeviceAPIError where ["foreground_required", "external_paused"].contains(error.code) { return .deferred }
        catch let error as DeviceAPIError where ["not_found", "agent_revoked", "agent_not_allowed"].contains(error.code) { return .dropped }
        catch { return .failed }
    }

    func attempt(agentID: String, grantRevision: Int64, configEpoch: Int64, eventOwner: String, eventID: Int64, now: Date = Date(), allowTest: Bool = false) async throws -> Int {
        guard eventOwner.hasPrefix(agentID + ":"), let agent = try await authority.agent(agentID), !agent.paused,
              let saved = try await store.callbackEventRecord(owner: eventOwner, id: eventID),
              (saved["eligible"] as? NSNumber)?.intValue == 1,
              (saved["grant_epoch"] as? NSNumber)?.int64Value == grantRevision,
              (saved["config_epoch"] as? NSNumber)?.int64Value == configEpoch,
              let event = saved["event"] as? [String: Any], let type = event["type"] as? String else { throw DeviceAPIError(404, "not_found", "The event or control agent is no longer available.") }
        let required: HostOperation?
        switch type.split(separator: ".").first.map(String.init) {
        case "interaction", "applet": required = nil
        case "callback" where allowTest && type == "callback.test": required = nil
        case "file": required = .files
        case "share": required = .shares
        default: throw DeviceAPIError(403, "agent_not_allowed", "This event is not available for callbacks.")
        }
        let check: () async throws -> Void = { [authority, store] in
            try Task.checkCancellation()
            guard try await store.callbackGrantEpoch(agentID) == grantRevision else {
                throw DeviceAPIError(403, "agent_revoked", "Control agent grants changed.")
            }
            guard let retained = try await store.callbackEventRecord(owner: eventOwner, id: eventID),
                  (retained["eligible"] as? NSNumber)?.intValue == 1,
                  (retained["grant_epoch"] as? NSNumber)?.int64Value == grantRevision,
                  (retained["config_epoch"] as? NSNumber)?.int64Value == configEpoch else {
                throw DeviceAPIError(404, "not_found", "The event is no longer available.")
            }
            try await authority.authorizeCallback(agentID: agentID, operation: .interactionsRead)
            if let required { try await authority.authorizeCallback(agentID: agentID, operation: required) }
        }
        try await check()
        guard let target = try await configuration.testTarget(agentID: agentID, authorize: check) else {
            throw DeviceAPIError(404, "not_found", "No callback is configured for this agent.")
        }
        let destination: IndexedCallbackConfiguration.Delivery
        do {
            guard let current = try await configuration.current(agentID: agentID, policy: policy, authorize: check) else {
                throw DeviceAPIError(404, "not_found", "No callback is configured for this agent.")
            }
            destination = current
        } catch let error as DeviceAPIError where error.code == "invalid_parameter" || error.code == "invalid_connection" {
            let safeHost = URLComponents(string: target.url)?.host ?? "unknown"
            try? await store.recordCallbackAttempt(agent: agent, host: safeHost, eventID: eventID, eventType: type, status: nil)
            throw error
        }
        guard destination.removalEpoch == configEpoch else { throw DeviceAPIError(403, "agent_revoked", "The callback was removed after this event.") }
        let payload: [String: Any] = ["event": event, "agent": agent.name]
        guard JSONSerialization.isValidJSONObject(payload) else { throw DeviceAPIError(500, "invalid_response", "Cannot encode callback event.") }
        let body = try JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys])
        let request = try CallbackRules.request(address: destination.url, destination: destination.destination,
            secret: destination.secret, timestamp: Int64(now.timeIntervalSince1970), eventID: String(eventID), body: body, appVersion: appVersion)
        let host = URLComponents(string: destination.url)?.host ?? "unknown"
        do {
            let response = try await transport(request) { [configuration, policy] in
                try await check()
                guard let latest = try await configuration.current(agentID: agentID, policy: policy, authorize: check), latest == destination else {
                    throw DeviceAPIError(409, "callback_changed", "The callback configuration changed.")
                }
                try await self.beforeFinalEligibility?()
                try await self.store.authorizeCallbackDelivery(agentID: agentID, grantEpoch: grantRevision,
                                                          configEpoch: configEpoch, owner: eventOwner,
                                                          eventID: eventID, additional: required)
            }
            try? await store.recordCallbackAttempt(agent: agent, host: host, eventID: eventID, eventType: type, status: response.status)
            return response.status
        } catch {
            if !(error is CancellationError), (error as? DeviceAPIError)?.code != "foreground_required" {
                try? await store.recordCallbackAttempt(agent: agent, host: host, eventID: eventID, eventType: type, status: nil)
            }
            throw error
        }
    }
}
