import BridgitCore
import Foundation

@MainActor final class FeedbackTeamKeys {
    typealias Credential = InstallClient.FeedbackTeamCredential
    typealias Check = () async throws -> Void
    typealias Mint = (HostAgent, @escaping Check) async throws -> Credential

    private struct Scope: Codable, Equatable {
        let generation: String
        let fixer: Bool
        init(_ agent: HostAgent) { generation = agent.generation; fixer = agent.allows(.feedbackFixer) }
    }
    private struct State: Codable {
        var tracked: [String: Scope] = [:]
        var retiring: Set<String> = []
    }
    private let stateURL: URL
    private let read: (String) -> String?
    private let save: (String?, String) -> Bool
    private let mint: Mint
    private let revoke: (String) async throws -> Void
    private var state: State
    private var busy = Set<String>()
    private var retry: Task<Void, Never>?

    init(folder: URL, read: @escaping (String) -> String? = Keychain.get,
         save: @escaping (String?, String) -> Bool = Keychain.set,
         mint: Mint? = nil, revoke: ((String) async throws -> Void)? = nil) {
        stateURL = folder.appendingPathComponent("team-key-state")
        state = (try? Data(contentsOf: stateURL)).flatMap { try? JSONDecoder().decode(State.self, from: $0) } ?? State()
        self.read = read
        self.save = save
        self.mint = mint ?? { agent, check in
            let signer = try Self.signer()
            return try await InstallClient(signer: signer, outboundAccess: DeviceOutboundPolicy.access).mintFeedbackTeamKey(
                agentID: agent.id, name: agent.name, fixer: agent.allows(.feedbackFixer),
                appVersion: AppleBuildDetails.shortVersion ?? AppleBuildDetails.version, authorize: check)
        }
        self.revoke = revoke ?? { id in
            try await InstallClient(signer: Self.signer(), outboundAccess: DeviceOutboundPolicy.access).revokeFeedbackTeamKey(agentID: id)
        }
    }

    private static func signer() throws -> DeviceSigner {
        try DeviceSigner.loadOrCreate(tag: (Bundle.main.bundleIdentifier ?? "Bridgit") + ".install-signing.v1")
    }

    private func account(_ id: String) -> String { "feedback-team:" + id }
    private func cache(_ id: String) -> Credential? {
        read(account(id)).flatMap { try? JSONDecoder().decode(Credential.self, from: Data($0.utf8)) }
    }
    private func persist(_ next: State) throws {
        try JSONEncoder().encode(next).write(to: stateURL, options: .atomic)
        state = next
    }

    func get(_ agent: HostAgent, rotate: Bool, authorize: @escaping Check) async throws -> Credential {
        try await authorize()
        guard busy.insert(agent.id).inserted else {
            throw DeviceAPIError(409, "team_key_busy", "This team key is already being changed.")
        }
        defer { busy.remove(agent.id); scheduleRetirement() }
        let scope = Scope(agent)
        if !rotate, state.tracked[agent.id] == scope, !state.retiring.contains(agent.id), let cached = cache(agent.id) {
            return cached
        }

        if state.tracked[agent.id] != nil && (state.tracked[agent.id] != scope || cache(agent.id) == nil) {
            try markRetiring(agent.id)
        }
        if state.retiring.contains(agent.id) {
            do { try await retire(agent.id) }
            catch { throw Self.teamError(error) }
        }
        try await authorize()
        var next = state
        next.tracked[agent.id] = scope
        try persist(next)
        do {
            let key = try await mint(agent, authorize)
            try await authorize()
            guard !state.retiring.contains(agent.id) else { throw CancellationError() }
            let encoded = try JSONEncoder().encode(key)
            guard save(String(decoding: encoded, as: UTF8.self), account(agent.id)) else {
                throw DeviceAPIError(503, "keychain_failed", "The team key could not be saved securely.")
            }
            return key
        } catch {
            // Mint may have reached the service before the request was cancelled.
            // The next request cannot issue a new key until this retirement settles.
            try? markRetiring(agent.id)
            if error is CancellationError { throw error }
            throw Self.teamError(error)
        }
    }

    func reconcile(_ agents: [HostAgent]) {
        let current = Dictionary(uniqueKeysWithValues: agents.map { ($0.id, $0) })
        for (id, scope) in state.tracked {
            guard let agent = current[id], !agent.paused, agent.allows(.feedback), scope == Scope(agent) else {
                try? markRetiring(id)
                continue
            }
            if !busy.contains(id), cache(id) == nil { try? markRetiring(id) }
        }
        scheduleRetirement()
    }

    private func markRetiring(_ id: String) throws {
        if !state.retiring.contains(id) {
            var next = state
            next.retiring.insert(id)
            try persist(next)
        }
        _ = save(nil, account(id))
    }

    private func retire(_ id: String) async throws {
        do { try await revoke(id) }
        catch let error as DeviceAPIError where (400..<500).contains(error.status) && error.status != 429 {}
        var next = state
        next.retiring.remove(id)
        next.tracked.removeValue(forKey: id)
        try persist(next)
    }

    private func scheduleRetirement() {
        guard retry == nil, state.retiring.contains(where: { !busy.contains($0) }) else { return }
        retry = Task { [weak self] in
            guard let self else { return }
            await self.drainRetirements()
        }
    }

    private func drainRetirements() async {
        defer { retry = nil }
        for attempt in 0..<20 {
            let ids = state.retiring.subtracting(busy)
            guard !ids.isEmpty else { return }
            for id in ids where busy.insert(id).inserted {
                do { try await retire(id) } catch {}
                busy.remove(id)
            }
            if state.retiring.isEmpty { return }
            do { try await Task.sleep(for: .seconds(min(900, 30 * (1 << min(attempt, 5))))) }
            catch { return }
        }
    }

    private static func teamError(_ error: Error) -> DeviceAPIError {
        guard let api = error as? DeviceAPIError else {
            return DeviceAPIError(503, "team_unavailable", "The Bridgit feedback service could not be reached.")
        }
        switch api.status {
        case 429: return DeviceAPIError(429, "rate_limited", "Too many team keys. Try again later.")
        case 403 where api.code == "agent_blocked":
            return DeviceAPIError(403, "agent_blocked", "The Bridgit feedback service blocked this agent.")
        case 403, 409: return api
        default: return DeviceAPIError(503, "team_unavailable", "The Bridgit feedback service could not issue this key.")
        }
    }
}
