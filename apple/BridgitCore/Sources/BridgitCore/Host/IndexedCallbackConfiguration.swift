import Foundation

/// Opaque version IDs are staged in SQLite before their Keychain write. The
/// active pointer moves only under a fresh transactional authority check.
actor IndexedCallbackConfiguration {
    private struct Record: Codable { let url: String; let secret: String }
    struct Delivery: Equatable { let url: String; let secret: String; let destination: CallbackRules.Destination; let removalEpoch: Int64 }
    private let store: AppletStore
    private let secrets: SatelliteSecrets
    private var busy = false
    private var removed = Set<String>()
    private var waiters: [CheckedContinuation<Void, Never>] = []
    private func finish() {
        busy = false
        let pending = waiters; waiters.removeAll()
        for waiter in pending { waiter.resume() }
    }
    func isRemoved(_ id: String) -> Bool { removed.contains(id) }
    init(store: AppletStore, secrets: SatelliteSecrets) { self.store = store; self.secrets = secrets }
    private func key(_ id: String, _ version: String) -> String { "callback:" + id + ":" + version }
    private func load(_ id: String, version: String) async throws -> Record {
        guard let text = try await secrets.load(key(id, version)), let data = text.data(using: .utf8),
              let record = try? JSONDecoder().decode(Record.self, from: data), CallbackRules.validSecret(record.secret) else {
            throw DeviceAPIError(503, "callback_storage_invalid", "The callback needs to be set again.")
        }
        return record
    }
    func set(agentID: String, url: String, secret: String?, caller: HostAgent?,
             policy: () async -> CallbackNetworkPolicy,
             authorize: () async throws -> Void) async throws -> [String: Any] {
        guard !busy else { throw DeviceAPIError(409, "callback_busy", "Another callback change is in progress.") }
        busy = true; defer { finish() }
        let lease = try await store.callbackMutationLease(); defer { lease.release() }
        try await authorize()
        guard !removed.contains(agentID) else { throw DeviceAPIError(404, "not_found", "Unknown agent.") }
        let initial = await policy()
        _ = try CallbackRules.destination(url, tailnetDomain: initial.tailnetDomain, allowInternet: initial.allowInternet)
        let index = try await store.callbackIndex(agentID)
        let previous: Record?
        if let active = index.active { previous = try await load(agentID, version: active) } else { previous = nil }
        guard let signingKey = secret ?? previous?.secret, CallbackRules.validSecret(signingKey) else {
            throw DeviceAPIError(422, "invalid_parameter", "A new callback needs its receiver-provided signing key.")
        }
        let version = UUID().uuidString.lowercased()
        try await store.stageCallbackKey(version, agentID: agentID, caller: caller)
        do {
            let bytes = try JSONEncoder().encode(Record(url: url.trimmingCharacters(in: .whitespacesAndNewlines), secret: signingKey))
            try await secrets.save(String(decoding: bytes, as: UTF8.self), for: key(agentID, version))
            try await authorize()
            let current = await policy()
            _ = try CallbackRules.destination(url, tailnetDomain: current.tailnetDomain, allowInternet: current.allowInternet)
            try await store.commitCallbackKey(version, agentID: agentID, caller: caller)
        } catch {
            try? await store.abandonCallbackKey(version, agentID: agentID)
            try? await cleanup(agentID: agentID)
            throw error
        }
        try? await cleanup(agentID: agentID)
        return ["agent_id": agentID, "configured": true, "write_only": true]
    }
    func remove(agentID: String, caller: HostAgent?, invalidatePending: Bool = true, authorize: () async throws -> Void) async throws -> [String: Any] {
        guard !busy else { throw DeviceAPIError(409, "callback_busy", "Another callback change is in progress.") }
        busy = true; defer { finish() }
        let lease = try await store.callbackMutationLease(); defer { lease.release() }
        try await authorize(); try await store.removeCallbackKey(agentID: agentID, caller: caller, invalidatePending: invalidatePending)
        try? await cleanup(agentID: agentID)
        return ["agent_id": agentID, "configured": false]
    }
    func list(agentIDs: [String], authorize: () async throws -> Void) async throws -> [[String: Any]] {
        try await authorize()
        var result: [[String: Any]] = []
        for id in agentIDs { result.append(["agent_id": id, "configured": try await store.callbackIndex(id).active != nil, "write_only": true]) }
        try await authorize(); return result
    }
    /// Status exposes only the configured hostname, never the URL or signing key.
    func status(agentID: String, authorize: () async throws -> Void) async throws -> [String: Any] {
        try await authorize()
        let index = try await store.callbackIndex(agentID)
        guard !removed.contains(agentID), let active = index.active else {
            return ["set": false, "host": NSNull()]
        }
        let record = try await load(agentID, version: active)
        let host = URLComponents(string: record.url)?.host
        try await authorize()
        guard !removed.contains(agentID), try await store.callbackIndex(agentID) == index else {
            throw DeviceAPIError(409, "callback_changed", "The callback configuration changed.")
        }
        return ["set": true, "host": host as Any? ?? NSNull()]
    }
    func ownerURL(agentID: String, authorize: () async throws -> Void) async throws -> String? {
        try await authorize()
        let index = try await store.callbackIndex(agentID)
        guard !removed.contains(agentID), let active = index.active else { return nil }
        let record = try await load(agentID, version: active)
        try await authorize()
        guard !removed.contains(agentID), try await store.callbackIndex(agentID) == index else {
            throw DeviceAPIError(409, "callback_changed", "The callback configuration changed.")
        }
        return record.url
    }
    /// Used only for the local owner's one-time control-agent setup after rotation.
    func ownerSetupSecret(agentID: String, authorize: () async throws -> Void) async throws -> String? {
        try await authorize()
        let index = try await store.callbackIndex(agentID)
        guard !removed.contains(agentID), let active = index.active else { return nil }
        let record = try await load(agentID, version: active)
        try await authorize()
        guard !removed.contains(agentID), try await store.callbackIndex(agentID) == index else {
            throw DeviceAPIError(409, "callback_changed", "The callback configuration changed.")
        }
        return record.secret
    }
    struct TestTarget { let url: String; let removalEpoch: Int64 }
    func testTarget(agentID: String, authorize: () async throws -> Void) async throws -> TestTarget? {
        try await authorize()
        let index = try await store.callbackIndex(agentID)
        guard !removed.contains(agentID), let active = index.active else { return nil }
        let record = try await load(agentID, version: active)
        try await authorize()
        guard !removed.contains(agentID), try await store.callbackIndex(agentID) == index else {
            throw DeviceAPIError(409, "callback_changed", "The callback configuration changed.")
        }
        return .init(url: record.url, removalEpoch: index.removalEpoch)
    }
    func current(agentID: String, policy: () async -> CallbackNetworkPolicy,
                 authorize: () async throws -> Void) async throws -> Delivery? {
        try await authorize()
        guard !removed.contains(agentID) else { return nil }
        let index = try await store.callbackIndex(agentID)
        guard let active = index.active else { return nil }
        let record = try await load(agentID, version: active)
        let currentPolicy = await policy()
        let destination = try CallbackRules.destination(record.url, tailnetDomain: currentPolicy.tailnetDomain, allowInternet: currentPolicy.allowInternet)
        try await authorize()
        guard !removed.contains(agentID), try await store.callbackIndex(agentID) == index else { throw DeviceAPIError(409, "callback_changed", "The callback configuration changed.") }
        return .init(url: record.url, secret: record.secret, destination: destination, removalEpoch: index.removalEpoch)
    }
    private func cleanup(agentID: String) async throws {
        for version in try await store.retiredCallbackKeys(agentID) {
            try await secrets.remove(key(agentID, version))
            try await store.finishRetiredCallbackKey(version, agentID: agentID)
        }
    }
    /// Called only after the agent-removal transaction retires active/staged IDs.
    /// Tombstone before waiting so a suspended writer cannot publish a credential.
    func pruneRemovedAgent(_ id: String) async throws {
        removed.insert(id)
        while busy { await withCheckedContinuation { waiters.append($0) } }
        busy = true; defer { finish() }
        let lease = try await store.callbackMutationLease(); defer { lease.release() }
        try await cleanup(agentID: id)
    }

    /// Must finish before the event worker starts, to clean interrupted staging.
    func reconcile() async throws {
        guard !busy else { throw DeviceAPIError(409, "callback_busy", "Another callback change is in progress.") }
        busy = true; defer { finish() }
        let lease = try await store.callbackMutationLease(); defer { lease.release() }
        for staged in try await store.stagedCallbacks() { try await store.abandonCallbackKey(staged.key, agentID: staged.agentID) }
        for id in try await store.retiredCallbackAgents() { try await cleanup(agentID: id) }
    }
}
