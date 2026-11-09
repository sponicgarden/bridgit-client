import Foundation

/// Process-local queue, like Android: events remain in the pull store, while
/// pending callback retries are not restored after a process restart.
enum CallbackAttemptOutcome { case delivered, failed, dropped, deferred }

actor CallbackWorker {
    struct GrantSnapshot: Equatable {
        let operations: Set<HostOperation>
        let applets: Set<String>
        let revision: Int64
        init(_ agent: HostAgent, revision: Int64) { operations = agent.operations; applets = agent.applets; self.revision = revision }
    }
    struct Pending {
        let eventOwner: String
        let eventID: Int64
        let configEpoch: Int64
        let grants: GrantSnapshot
    }
    typealias Attempt = (String, Int64, Int64, String, Int64) async -> CallbackAttemptOutcome
    private var queue = CallbackQueue<Pending>()
    private var epochs: [String: Int] = [:]
    private var grants: [String: GrantSnapshot] = [:]
    private var runtimeGeneration: UUID?
    private var processing = false
    private let attempt: Attempt
    init(attempt: @escaping Attempt) { self.attempt = attempt }

    func activate(_ run: UUID) { runtimeGeneration = run; queue = CallbackQueue(); grants.removeAll(); epochs.removeAll() }
    func deactivate() { runtimeGeneration = nil; queue = CallbackQueue(); grants.removeAll(); epochs.removeAll() }
    func reconcile(_ agents: [HostAgent], revisions: [String: Int64], run: UUID) {
        guard runtimeGeneration == run else { return }
        reconcile(agents, revisions: revisions)
    }
    func enqueue(agent: HostAgent, grantRevision: Int64, configEpoch: Int64, eventOwner: String, eventID: Int64, now: Date, run: UUID) -> Int {
        guard runtimeGeneration == run else { return 0 }
        return enqueue(agent: agent, grantRevision: grantRevision, configEpoch: configEpoch, eventOwner: eventOwner, eventID: eventID, now: now)
    }
    func reconcile(_ agents: [HostAgent], revisions: [String: Int64]) {
        let next = Dictionary(uniqueKeysWithValues: agents.map { ($0.id, GrantSnapshot($0, revision: revisions[$0.id] ?? 0)) })
        for id in Set(grants.keys).union(next.keys) where grants[id] != next[id] {
            queue.remove(agentID: id); epochs[id, default: 0] += 1
        }
        grants = next
    }
    @discardableResult func enqueue(agent: HostAgent, grantRevision: Int64, configEpoch: Int64, eventOwner: String, eventID: Int64, now: Date) -> Int {
        guard grants[agent.id] == GrantSnapshot(agent, revision: grantRevision), eventOwner.hasPrefix(agent.id + ":"), eventID > 0 else { return 0 }
        return queue.add(.init(agentID: agent.id, value: .init(eventOwner: eventOwner, eventID: eventID, configEpoch: configEpoch, grants: GrantSnapshot(agent, revision: grantRevision)), attempt: 1, due: now))
    }
    func cancel(agentID: String) { queue.remove(agentID: agentID); epochs[agentID, default: 0] += 1 }
    func pendingCount(agentID: String) -> Int { queue.items.filter { $0.agentID == agentID }.count }
    func nextDue() -> Date? { queue.items.map(\.due).min() }
    func runOne(now: Date, completedAt: () -> Date = Date.init) async {
        guard !processing, let item = queue.takeDue(now: now), grants[item.agentID] == item.value.grants else { return }
        processing = true; defer { processing = false }
        let epoch = epochs[item.agentID, default: 0]
        let run = runtimeGeneration
        let result = await attempt(item.agentID, item.value.grants.revision, item.value.configEpoch, item.value.eventOwner, item.value.eventID)
        guard runtimeGeneration == run else { return }
        if result == .deferred, epochs[item.agentID, default: 0] == epoch, grants[item.agentID] == item.value.grants {
            _ = queue.add(.init(agentID: item.agentID, value: item.value, attempt: item.attempt, due: completedAt().addingTimeInterval(1)))
            return
        }
        guard result == .failed, epochs[item.agentID, default: 0] == epoch, grants[item.agentID] == item.value.grants,
              let delay = CallbackRules.nextDelay(after: item.attempt) else { return }
        _ = queue.add(.init(agentID: item.agentID, value: item.value, attempt: item.attempt + 1, due: completedAt().addingTimeInterval(delay)))
    }
}
