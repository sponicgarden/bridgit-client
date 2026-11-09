import Foundation

/// Host-owned callback lifecycle. The queue is process-local; committed events
/// remain available through the normal pull API after a retry is exhausted.
actor CallbackRuntime {
    private let store: AppletStore
    private let authority: HostAuthority
    private let configuration: IndexedCallbackConfiguration
    private let worker: CallbackWorker
    private let beforeStart: (() async -> Void)?
    private let beforeEnqueue: (() async -> Void)?
    private let beforeRunDue: (() async -> Void)?
    private var eventTask: Task<Void, Never>?
    private var agentTask: Task<Void, Never>?
    private var timerTask: Task<Void, Never>?
    private var stopping = false
    private var starting = false
    private var startWaiters: [CheckedContinuation<Void, Never>] = []
    private var drainingRun: UUID?
    private var cursor: Int64 = 0
    private var generation = UUID()
    init(store: AppletStore, authority: HostAuthority, configuration: IndexedCallbackConfiguration,
         appVersion: String, policy: @escaping CallbackDelivery.Policy, transport: CallbackDelivery.Transport? = nil,
         beforeStart: (() async -> Void)? = nil, beforeEnqueue: (() async -> Void)? = nil, beforeRunDue: (() async -> Void)? = nil) {
        self.store = store; self.authority = authority; self.configuration = configuration
        self.beforeStart = beforeStart; self.beforeEnqueue = beforeEnqueue; self.beforeRunDue = beforeRunDue
        let delivery = CallbackDelivery(authority: authority, store: store, configuration: configuration,
                                        appVersion: appVersion, policy: policy, transport: transport)
        worker = CallbackWorker { agent, revision, epoch, owner, id in
            await delivery.outcome(agentID: agent, grantRevision: revision, configEpoch: epoch, eventOwner: owner, eventID: id)
        }
    }
    func start() async throws {
        guard !stopping, !starting, eventTask == nil, agentTask == nil, timerTask == nil else { return }
        starting = true
        let run = UUID(); generation = run
        defer {
            starting = false
            let waiters = startWaiters; startWaiters.removeAll()
            for waiter in waiters { waiter.resume() }
        }
        await beforeStart?()
        guard generation == run else { return }
        try await configuration.reconcile()
        guard generation == run else { return }
        await authority.setCallbackPruner { [configuration, weak worker] id in
            await worker?.cancel(agentID: id)
            try await configuration.pruneRemovedAgent(id)
        }
        await authority.reconcileCallbackPrunes()
        guard generation == run else { return }
        guard try await store.pendingCallbackPrunes().isEmpty else {
            throw DeviceAPIError(503, "callback_cleanup_pending", "Callback cleanup will be retried.")
        }
        guard generation == run else { return }
        let subscription = try await store.callbackEventSubscription()
        let agentChanges = await store.changes(to: .hostAgents)
        guard generation == run else { return }
        await worker.activate(run)
        guard generation == run else { await worker.deactivate(); return }
        try await reconcileAgents(run: run)
        guard generation == run else { return }
        cursor = subscription.cursor
        eventTask = Task { [weak self] in
            for await _ in subscription.changes {
                guard !Task.isCancelled else { return }
                await self?.drainEvents(run: run)
            }
        }
        agentTask = Task { [weak self] in
            for await _ in agentChanges {
                guard !Task.isCancelled else { return }
                await self?.reconcileAgentsIfCurrent(run: run)
            }
        }
        timerTask = Task { [weak self] in
            while !Task.isCancelled {
                await self?.drainEvents(run: run)
                await self?.runDue(run: run)
                try? await Task.sleep(for: .seconds(1))
            }
        }
    }
    func stop() async {
        guard !stopping else { return }
        stopping = true
        defer { stopping = false }
        generation = UUID(); eventTask?.cancel(); agentTask?.cancel(); timerTask?.cancel()
        eventTask = nil; agentTask = nil; timerTask = nil
        while starting { await withCheckedContinuation { startWaiters.append($0) } }
        await worker.deactivate()
    }
    func cancel(agentID: String) async { await worker.cancel(agentID: agentID) }
    private func reconcileAgents(run: UUID) async throws {
        let agents = try await authority.list()
        var revisions: [String: Int64] = [:]
        for agent in agents { revisions[agent.id] = try await store.callbackGrantEpoch(agent.id) }
        guard generation == run else { return }
        await worker.reconcile(agents, revisions: revisions, run: run)
    }
    private func reconcileAgentsIfCurrent(run: UUID) async {
        guard generation == run else { return }
        try? await reconcileAgents(run: run)
    }
    private func drainEvents(run: UUID) async {
        guard generation == run, drainingRun != run else { return }
        drainingRun = run
        defer { if drainingRun == run { drainingRun = nil } }
        do {
            while generation == run {
                let rows = try await store.callbackEvents(after: cursor)
                guard !rows.isEmpty else { return }
                for row in rows {
                    guard generation == run, let owner = row["owner"] as? String,
                          let event = row["event"] as? [String: Any], let text = event["id"] as? String,
                          let id = Int64(text), id > cursor else { continue }
                    if event["type"] as? String != "callback.test", (row["eligible"] as? NSNumber)?.intValue == 1,
                       let revision = (row["grant_epoch"] as? NSNumber)?.int64Value,
                       let epoch = (row["config_epoch"] as? NSNumber)?.int64Value,
                       let agentID = owner.split(separator: ":", maxSplits: 1).first.map(String.init),
                       let agent = try await authority.agent(agentID) {
                        await beforeEnqueue?()
                        guard generation == run else { return }
                        try await reconcileAgents(run: run)
                        guard generation == run else { return }
                        let dropped = await worker.enqueue(agent: agent, grantRevision: revision, configEpoch: epoch,
                                                           eventOwner: owner, eventID: id, now: Date(), run: run)
                        if dropped > 0 { try? await store.recordCallbackOverflow(agent: agent, count: dropped) }
                    }
                    guard generation == run else { return }
                    cursor = id
                }
                if rows.count < 200 { return }
            }
        } catch { /* Events remain readable; a later change will retry the cursor. */ }
    }
    private func runDue(run: UUID) async {
        guard generation == run else { return }
        while generation == run, let due = await worker.nextDue(), due <= Date() {
            await beforeRunDue?()
            guard generation == run else { return }
            await worker.runOne(now: Date())
        }
    }
}
