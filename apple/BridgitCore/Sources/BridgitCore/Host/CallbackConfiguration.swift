import Foundation

/// Owner callback destinations and receiver-supplied signing keys share one
/// Keychain record per stable agent ID. Neither is returned by configuration APIs.
public struct CallbackNetworkPolicy: Sendable {
    public let tailnetDomain: String?
    public let allowInternet: Bool
    public init(tailnetDomain: String?, allowInternet: Bool) {
        self.tailnetDomain = tailnetDomain; self.allowInternet = allowInternet
    }
}

// Legacy isolated test fixture; production uses IndexedCallbackConfiguration.
actor CallbackConfiguration {
    struct Record: Codable, Equatable { let url: String; let secret: String }
    private let secrets: SatelliteSecrets
    private var mutating = false
    private var removed = Set<String>()
    private var mutationWaiters: [CheckedContinuation<Void, Never>] = []
    private func finishMutation() {
        mutating = false
        let waiters = mutationWaiters; mutationWaiters.removeAll()
        for waiter in waiters { waiter.resume() }
    }
    public init(secrets: SatelliteSecrets) { self.secrets = secrets }
    private func key(_ id: String) -> String { "callback:" + id }
    func isRemoved(_ id: String) -> Bool { removed.contains(id) }
    private func record(_ id: String) async throws -> Record? {
        guard let encoded = try await secrets.load(key(id)) else { return nil }
        guard let data = encoded.data(using: .utf8), let value = try? JSONDecoder().decode(Record.self, from: data),
              CallbackRules.validSecret(value.secret) else {
            throw DeviceAPIError(503, "callback_storage_invalid", "The callback configuration needs to be set again.")
        }
        return value
    }
    /// Returns presence only; no destination, signing key or credential is disclosed.
    public func list(agentIDs: [String], authorize: () async throws -> Void) async throws -> [[String: Any]] {
        guard !mutating else { throw DeviceAPIError(409, "callback_busy", "Another callback change is in progress.") }
        mutating = true; defer { finishMutation() }
        try await authorize()
        var result: [[String: Any]] = []
        for id in agentIDs { result.append(["agent_id": id, "configured": try await record(id) != nil, "write_only": true]) }
        try await authorize()
        guard agentIDs.allSatisfy({ !removed.contains($0) }) else { throw DeviceAPIError(409, "callback_busy", "Callback configuration changed during the read.") }
        return result
    }
    public func set(agentID: String, url: String, secret: String?, policy: () async -> CallbackNetworkPolicy,
                    agentExists: () async throws -> Bool, authorize: () async throws -> Void) async throws -> [String: Any] {
        guard !mutating else { throw DeviceAPIError(409, "callback_busy", "Another callback change is in progress.") }
        mutating = true; defer { finishMutation() }
        try await authorize()
        guard !removed.contains(agentID), try await agentExists() else { throw DeviceAPIError(404, "not_found", "Unknown agent.") }
        let initialPolicy = await policy()
        _ = try CallbackRules.destination(url, tailnetDomain: initialPolicy.tailnetDomain, allowInternet: initialPolicy.allowInternet)
        let old = try await secrets.load(key(agentID))
        let existing = try await record(agentID)
        guard let value = secret ?? existing?.secret, CallbackRules.validSecret(value) else {
            throw DeviceAPIError(422, "invalid_parameter", "A new callback needs its receiver-provided signing key.")
        }
        let data = try JSONEncoder().encode(Record(url: url.trimmingCharacters(in: .whitespacesAndNewlines), secret: value))
        guard let encoded = String(data: data, encoding: .utf8) else { throw DeviceAPIError(500, "callback_storage_invalid", "Cannot encode callback configuration.") }
        try await authorize(); guard !removed.contains(agentID), try await agentExists() else { throw DeviceAPIError(404, "not_found", "Unknown agent.") }
        let currentPolicy = await policy()
        _ = try CallbackRules.destination(url, tailnetDomain: currentPolicy.tailnetDomain, allowInternet: currentPolicy.allowInternet)
        try await secrets.save(encoded, for: key(agentID))
        do {
            try await authorize(); guard !removed.contains(agentID), try await agentExists() else { throw DeviceAPIError(404, "not_found", "Unknown agent.") }
            let finalPolicy = await policy()
            _ = try CallbackRules.destination(url, tailnetDomain: finalPolicy.tailnetDomain, allowInternet: finalPolicy.allowInternet)
            guard !removed.contains(agentID) else { throw DeviceAPIError(404, "not_found", "Unknown agent.") }
        } catch {
            if !removed.contains(agentID), (try? await agentExists()) == true, let old { try await secrets.save(old, for: key(agentID)) }
            else { try await secrets.remove(key(agentID)) }
            throw error
        }
        return ["agent_id": agentID, "configured": true, "write_only": true]
    }
    struct Delivery: Equatable { let url: String; let secret: String; let destination: CallbackRules.Destination }
    /// Re-evaluate the latest owner network policy and caller authority for every
    /// attempt. A second call is required in the pinned connection authorization.
    func delivery(agentID: String, policy: () async -> CallbackNetworkPolicy,
                  authorize: () async throws -> Void) async throws -> Delivery? {
        guard !mutating else { throw DeviceAPIError(409, "callback_busy", "Another callback change is in progress.") }
        mutating = true; defer { finishMutation() }
        try await authorize()
        guard !removed.contains(agentID), let value = try await record(agentID) else { return nil }
        let current = await policy()
        let destination = try CallbackRules.destination(value.url, tailnetDomain: current.tailnetDomain, allowInternet: current.allowInternet)
        try await authorize()
        guard !removed.contains(agentID) else { throw DeviceAPIError(403, "agent_revoked", "The control agent has been removed.") }
        return Delivery(url: value.url, secret: value.secret, destination: destination)
    }

    /// Called after authority commits agent removal. A tombstone is installed
    /// before waiting, so an in-flight save or rollback cannot win over prune.
    public func pruneRemovedAgent(_ agentID: String) async throws {
        removed.insert(agentID)
        while mutating {
            await withCheckedContinuation { mutationWaiters.append($0) }
        }
        mutating = true; defer { finishMutation() }
        try await secrets.remove(key(agentID))
    }

    public func remove(agentID: String, agentExists: () async throws -> Bool,
                       authorize: () async throws -> Void) async throws -> [String: Any] {
        guard !mutating else { throw DeviceAPIError(409, "callback_busy", "Another callback change is in progress.") }
        mutating = true; defer { finishMutation() }
        try await authorize(); guard !removed.contains(agentID), try await agentExists() else { throw DeviceAPIError(404, "not_found", "Unknown agent.") }
        let old = try await secrets.load(key(agentID))
        try await authorize(); try await secrets.remove(key(agentID))
        do { try await authorize(); guard !removed.contains(agentID), try await agentExists() else { throw DeviceAPIError(404, "not_found", "Unknown agent.") } }
        catch {
            if !removed.contains(agentID), (try? await agentExists()) == true, let old { try await secrets.save(old, for: key(agentID)) }
            throw error
        }
        return ["agent_id": agentID, "configured": false]
    }
}
