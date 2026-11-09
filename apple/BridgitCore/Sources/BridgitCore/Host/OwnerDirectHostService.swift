import Foundation

/// An independent, opt-in owner listener. Its credentials and lifecycle do not
/// enable the satellite listener or publish an agent to the relay.
public actor OwnerDirectHostService {
    static let mode = "owner_direct"
    public static let defaultPort: UInt16 = 8734

    private let authority: HostAuthority
    private let dispatcher: HostDispatcher
    private let unavailable: (() async -> Void)?
    private var listener: TailnetHTTPListener?
    private var staged: (id: UUID, listener: TailnetHTTPListener)?
    private var starting: UUID?
    private var run = UUID()
    private var bindingRevision: UInt64 = 0
    private var localAddress: TailnetAddress?
    private var localPort: UInt16?
    public var boundEndpoint: (TailnetAddress, UInt16)? {
        guard let localAddress, let localPort else { return nil }
        return (localAddress, localPort)
    }
    private var failures: [TailnetAddress: (minute: Int64, count: Int)] = [:]

    public init(authority: HostAuthority, dispatcher: HostDispatcher, unavailable: (() async -> Void)? = nil) {
        self.authority = authority
        self.dispatcher = dispatcher
        self.unavailable = unavailable
    }

    public func start(address: TailnetAddress, port: UInt16 = OwnerDirectHostService.defaultPort,
                      requester: HostAgent? = nil) async throws -> UInt16 {
        guard TailnetAddress.localCandidates().contains(address) else {
            throw DeviceAPIError(409, "tailnet_required", "Connect Tailscale and choose an address assigned to this device.")
        }
        return try await bind(address: address, port: port, requester: requester, authorize: nil, fixture: false)
    }

    /// Creates a real ephemeral TCP listener for bind-order tests. The fixture
    /// listener never serves direct control routes.
    func bindLoopbackForTesting(address: TailnetAddress, requester: HostAgent? = nil, authorize: (() async throws -> Void)? = nil) async throws -> UInt16 {
        try await bind(address: address, port: 0, requester: requester, authorize: authorize, fixture: true)
    }

    private func bind(address: TailnetAddress, port: UInt16, requester: HostAgent?,
                      authorize: (() async throws -> Void)?, fixture: Bool) async throws -> UInt16 {
        guard starting == nil else { throw DeviceAPIError(409, "listener_busy", "Wait for the direct listener to finish starting.") }
        let next = UUID()
        starting = next
        defer { if starting == next { starting = nil } }
        if let requester { try await authority.authorizeOngoing(requester, operation: .configurationWrite) }
        try await authorize?()
        guard starting == next, !Task.isCancelled else { throw CancellationError() }
        if let bound = boundEndpoint, bound.0 == address, bound.1 == port { return port }
        let incoming: TailnetHTTPListener
        if fixture {
            incoming = TailnetHTTPListener(fixtureLoopbackHandler: { _, _ in .failure(CancellationError()) })
        } else {
            incoming = TailnetHTTPListener(handler: { [weak self] request, source in
                guard let self else { return .failure(CancellationError()) }
                return await self.handleActive(request, source: source, run: next)
            }, rejection: { [weak self] observation, error, source, at, started in
                await self?.recordActiveParsedRejection(observation, error: error, source: source, at: at, started: started, run: next)
            }, decorateFailure: { $0.withHostAPIRevision() }, unavailable: { [weak self] in await self?.listenerUnavailable(next) },
               responseTimeoutSeconds: MicrophoneClipRequest.longJobTimeoutSeconds + 5, cancelOnPeerEOF: true)
        }
        staged = (next, incoming)
        do {
            let bound = try await incoming.start(address: fixture ? "127.0.0.1" : address.value, port: port)
            // Retain the old listener and principal readiness through this check.
            // The staged listener cannot dispatch until it becomes the active run.
            try await authorize?()
            guard starting == next, staged?.id == next, !Task.isCancelled else { throw CancellationError() }
            bindingRevision &+= 1
            try await authority.commitOwnerDirectBinding(revision: bindingRevision, enabled: true, requester: requester)
            guard starting == next, staged?.id == next else { throw CancellationError() }
            let previous = listener
            listener = incoming
            staged = nil
            localAddress = address
            localPort = bound
            run = next
            await previous?.stop()
            guard run == next else { throw CancellationError() }
            return bound
        } catch {
            if staged?.id == next { staged = nil }
            await incoming.stop()
            throw error
        }
    }

    public func stop() async {
        run = UUID()
        starting = nil
        localAddress = nil
        localPort = nil
        let old = listener
        let pending = staged?.listener
        listener = nil
        staged = nil
        bindingRevision &+= 1
        try? await authority.commitOwnerDirectBinding(revision: bindingRevision, enabled: false)
        await old?.stop()
        await pending?.stop()
    }

    private func handleActive(_ request: TailnetHTTPRequest, source: String, run: UUID) async -> HostResponse {
        guard run == self.run else { return HostResponse.failure(DeviceAPIError(503, "listener_starting", "Wait for the direct listener to finish starting.")).withHostAPIRevision() }
        return await handle(request, source: source)
    }

    private func listenerUnavailable(_ current: UUID) async {
        guard current == run else { return }
        await stop()
        guard listener == nil, starting == nil else { return }
        await unavailable?()
    }

    /// Only exercised by a bound listener in production. Tests use a loopback
    /// wire harness with an explicit synthetic source to test the same checks.
    func handle(_ wire: TailnetHTTPRequest, source: String) async -> HostResponse {
        let at = Date(), started = ContinuousClock.now
        let address = TailnetAddress(source)
        var verifiedAgent: HostAgent?
        do {
            if let localAddress, !TailnetAddress.localCandidates().contains(localAddress) {
                await listenerUnavailable(run)
                throw DeviceAPIError(409, "tailnet_required", "The selected Tailscale address is no longer available.")
            }
            guard let source = address else {
                throw DeviceAPIError(403, "forbidden_source", "Only Tailscale peers can use this listener.")
            }
            let path = wire.target.prefix { $0 != "?" }
            if !path.contains("%"), HostDispatcher.directAuditRoute(method: wire.method, target: wire.target) == nil {
                throw DeviceAPIError(404, "not_found", "No such endpoint. GET /v1/guide lists available operations.")
            }
            let header = wire.headers["authorization"] ?? ""
            let key = header.hasPrefix("Bearer ") ? String(header.dropFirst(7)) : ""
            guard SatelliteAuthorityPolicy.validKey(key) else {
                try recordFailedAuthentication(from: source)
                throw DeviceAPIError(401, "unauthorized", "Send this direct control agent’s bearer key.")
            }
            // Valid credentials remain usable even if another client on the same
            // peer generated bad guesses. Lookup is a bounded in-memory SHA-256 check.
            let agent: HostAgent
            do { agent = try await authority.authenticateOwnerDirect(key: key, source: source.value) }
            catch let error as DeviceAPIError where error.status == 401 {
                try recordFailedAuthentication(from: source)
                throw error
            }
            verifiedAgent = agent
            var request: HostRequest
            do { request = try wire.hostRequest }
            catch let error as DeviceAPIError { throw error }
            catch { throw DeviceAPIError(400, "invalid_json", "Use a JSON object.") }
            guard Self.permits(request) else {
                throw DeviceAPIError(501, "unsupported_operation", "This operation is not available on the direct host.")
            }
            if let bound = boundEndpoint { request.ownerDirectBaseURL = Self.baseURL(address: bound.0, port: bound.1) }
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            var job = HostJob(id: UUID().uuidString, sequence: 0, agentID: agent.id, generation: agent.generation,
                              deviceID: authority.deviceID, mode: Self.mode, deadline: now + 65_000,
                              createdAt: now, request: request, tailnetSource: source.value)
            if let clip = job.microphoneClipOptions(for: agent) {
                job.deadline = now + min(job.timeoutMilliseconds(for: agent), Int64(max(65, clip.minimumJobSeconds + 5)) * 1_000)
            }
            var response = await dispatch(job, agent: agent)
            if response.status < 400 && response.beforeDelivery == nil {
                response.beforeDelivery = { [authority] in _ = try await authority.validateDelivery(job) }
            }
            return response
        } catch {
            if let api = error as? DeviceAPIError, [400, 401, 403, 404, 409, 422, 429, 501].contains(api.status) {
                await dispatcher.recordDirectHTTPRejection(api, method: wire.method, target: wire.target,
                    source: address, bytesIn: wire.body.count, at: at, started: started, verifiedAgent: verifiedAgent)
            }
            return HostResponse.failure(error).withHostAPIRevision()
        }
    }

    private func recordActiveParsedRejection(_ observation: TailnetHTTPObservation, error: DeviceAPIError, source: String,
                                             at: Date, started: ContinuousClock.Instant, run: UUID) async {
        guard run == self.run else { return }
        await recordParsedRejection(observation, error: error, source: source, at: at, started: started)
    }

    func recordParsedRejection(_ observation: TailnetHTTPObservation, error: DeviceAPIError, source: String,
                               at: Date, started: ContinuousClock.Instant) async {
        await dispatcher.recordDirectHTTPRejection(error, method: observation.method, target: observation.target,
            source: TailnetAddress(source), bytesIn: nil, at: at, started: started)
    }

    public static func baseURL(address: TailnetAddress, port: UInt16) -> URL? {
        let host = address.isIPv6 ? "[" + address.value + "]" : address.value
        return URL(string: "http://" + host + ":" + String(port) + "/v1")
    }

    private func dispatch(_ job: HostJob, agent: HostAgent) async -> HostResponse {
        let work = Task { await dispatcher.dispatch(job, admitted: false, authenticated: agent) }
        let watch = Task {
            while !Task.isCancelled {
                do {
                    try await Task.sleep(for: .milliseconds(250))
                    _ = try await authority.validateDelivery(job)
                } catch is CancellationError { return }
                catch {
                    work.cancel()
                    return
                }
            }
        }
        defer { watch.cancel() }
        return await withTaskCancellationHandler(operation: { await work.value }, onCancel: { work.cancel() })
    }

    private func recordFailedAuthentication(from address: TailnetAddress) throws {
        let minute = Int64(Date().timeIntervalSince1970 / 60)
        failures = failures.filter { $0.value.minute == minute }
        let count = failures[address]?.count ?? 0
        guard count < 5, count > 0 || failures.count < 128 else {
            throw DeviceAPIError(429, "rate_limited", "Too many failed direct sign-ins. Wait a minute.")
        }
        failures[address] = (minute, count + 1)
    }

    public static let supportedGrants: Set<HostOperation> = Set<HostOperation>([
        .deviceRead, .log, .feedback, .feedbackFixer, .cameraSnapshot, .microphoneRecord,
        .configurationRead, .configurationWrite, .administrator,
        .interactionsRead, .interactionsWrite, .files, .shares, .downloads, .connections,
        .appletsManage, .appletsRead, .appletsWrite, .appletsMediaRead, .appletsMediaWrite,
        .speechHear, .speechSpeak, .workflowsRun, .lanDiscovery
    ]).union(HostOperation.computerUseGrants)

    static func permits(_ incoming: HostRequest) -> Bool {
        var request = incoming
        if let rawTarget = request.query["target"] {
            guard let target = rawTarget as? String else { return false }
            if target.isEmpty || target == "hub" { request.query.removeValue(forKey: "target") }
            else {
                guard SatelliteForwardPolicy.validTarget(target),
                      let grant = SatelliteForwardPolicy.operation(method: request.method, path: request.path) else { return false }
                return supportedGrants.contains(grant)
            }
        }
        guard let (_, applet) = request.route else { return false }
        if request.path == "/v1/mcp" { return true }
        guard let operation = HostAPIContract.operation(matching: request), permitsOperation(operation) else { return false }
        return applet.map { supportsApplet($0, operation: operation) } ?? true
    }

    static func supportsApplet(_ applet: String, operation: HostAPIOperation) -> Bool {
        guard applet.hasPrefix("library-") else { return operation.name != "applets_rows_delete_all" }
        switch operation.grant {
        case .appletsManage: return ["applets_pause", "applets_remove"].contains(operation.name)
        case .appletsMediaWrite, .workflowsRun: return false
        default: return true
        }
    }

    static func permitsOperation(_ operation: HostAPIOperation) -> Bool {
        guard supportedGrants.contains(operation.grant) else { return false }
        switch operation.grant {
        case .configurationRead, .configurationWrite:
            return operation.path == "/v1/settings" || operation.path.hasPrefix("/v1/settings/")
                || operation.path == "/v1/agents/me" || operation.path == DeviceDomainName.operationPath
                || operation.path.hasPrefix("/v1/owner/configuration/connections") || operation.path.hasPrefix("/v1/owner/configuration/callbacks")
        default:
            return true
        }
    }
}
