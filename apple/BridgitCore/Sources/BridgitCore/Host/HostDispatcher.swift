import CoreFoundation
import CryptoKit
import Foundation
import OSLog

public struct HostResponse {
    public var status: Int
    public var contentType: String
    public var data: Data
    public var headers: [String: String]
    /// Transport calls this only after every result chunk has been acknowledged. Never serialized.
    public var afterDelivery: (() async -> Void)?
    /// Runs only when the original response, not a transport replacement, was delivered.
    var afterOriginalDelivery: (() async -> Void)?
    var streamCompletion: ((Int, Error?) async -> Void)?
    var stream: (((Data) async throws -> Void) async throws -> Void)?
    var beforeDelivery: (() async throws -> Void)?
    // Private idempotency metadata, never included in a transport envelope.
    var lanNetworkScope: String?
    var acknowledgesAPIChanges = false
    typealias Activity = (operation: HostOperation, status: Int, facts: HostActivityRequestFacts)
    var activityOverride: Activity?
    public init(status: Int = 200, contentType: String = "application/json", data: Data, headers: [String: String] = [:]) { self.status = status; self.contentType = contentType; self.data = data; self.headers = headers }
    public static func json(_ object: [String: Any], status: Int = 200) throws -> HostResponse {
        guard JSONSerialization.isValidJSONObject(object) else {
            throw DeviceAPIError(500, "invalid_response", "The device could not encode this response.")
        }
        return .init(status: status, data: try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]))
    }
    public static func failure(_ error: Error) -> HostResponse {
        let api = error as? DeviceAPIError ?? DeviceAPIError(500, "device_error", "The device could not complete this request.")
        var body: [String: Any] = ["error": api.code, "message": api.message]
        if let reason = api.reason { body["reason"] = reason }
        return (try? .json(body, status: api.status)) ?? .init(status: 500, data: Data())
    }
    /// Applied only to the Apple owner host, never satellite-only replies.
    func withHostAPIRevision() -> Self {
        var response = self
        response.headers["X-Bridgit-API-Revision"] = String(HostAPIChanges.current)
        response.headers["X-Bridget-API-Revision"] = String(HostAPIChanges.current)
        return response
    }
    public func requiringDelivery(_ check: @escaping () async throws -> Void) -> Self {
        var result = self
        let previous = beforeDelivery
        result.beforeDelivery = {
            try await previous?()
            try await check()
        }
        return result
    }
    public var envelope: [String: Any] { ["status": status, "content_type": contentType, "body_base64": data.base64EncodedString(), "headers": headers] }
}
/// Shared by transports. Authorization is checked at admission and after every completed request;
/// data scope always comes from the current agent, never a caller-supplied owner flag.
public actor HostDispatcher {
    public typealias Profile = (HostAgent) async throws -> DeviceProfile
    private let authority: HostAuthority
    private let store: AppletStore
    private let engine: AppletEngine
    private let profile: Profile
    private let captureNotifications: (() async -> Bool)?
    public typealias Feedback = (HostRequest, HostAgent) async throws -> (response: HostResponse, requiresFixer: Bool)
    private let feedback: Feedback?
    private let callbacks: CallbackHostService?
    public typealias Sensor = (HostRequest, HostAgent) async throws -> HostResponse
    public typealias SatelliteForward = (HostRequest, HostAgent, @escaping (Bool) async throws -> Void) async throws -> HostResponse
    public typealias SatelliteList = (HostAgent) async throws -> [[String: Any]]
    private let satelliteForward: SatelliteForward?
    private let satelliteList: SatelliteList?
    public typealias AdditionalApplets = (HostAgent) async throws -> [[String: Any]]
    public typealias AdditionalAppletAction = (HostRequest, HostAgent) async throws -> HostResponse?
    private let additionalViewerApplets: AdditionalApplets?
    private let additionalApplets: AdditionalApplets?
    private let additionalAppletAction: AdditionalAppletAction?
    private let sensor: Sensor?
    private let samples: ((String?) throws -> [String: Any])?
    private let configuration: HostConfigurationService?
    private let connections: OutboundConnectionService?
    private let lan: HostLanService?
    private let appletChanged: ((String, String) async -> Void)?
    private var running = Set<String>()
    private var feedbackWaits: [String: String] = [:]
    private var activeKeys = Set<String>()
    private var mcpInitialized = Set<String>()
    private var mcpTasks: [String: Task<HostResponse, Never>] = [:]
    public init(authority: HostAuthority, store: AppletStore, engine: AppletEngine, appletChanged: ((String, String) async -> Void)? = nil, profile: @escaping Profile, sensor: Sensor? = nil, samples: ((String?) throws -> [String: Any])? = nil, configuration: HostConfigurationService? = nil, connections: OutboundConnectionService? = nil, additionalApplets: AdditionalApplets? = nil, additionalAppletAction: AdditionalAppletAction? = nil, additionalViewerApplets: AdditionalApplets? = nil, satelliteForward: SatelliteForward? = nil, satelliteList: SatelliteList? = nil, feedback: Feedback? = nil, lan: HostLanService? = nil, callbacks: CallbackHostService? = nil, captureNotifications: (() async -> Bool)? = nil) { self.captureNotifications = captureNotifications; self.lan = lan; self.callbacks = callbacks; self.feedback = feedback; self.satelliteForward = satelliteForward; self.satelliteList = satelliteList; self.additionalViewerApplets = additionalViewerApplets; self.additionalApplets = additionalApplets; self.additionalAppletAction = additionalAppletAction; self.connections = connections; self.appletChanged = appletChanged; self.configuration = configuration; self.samples = samples; self.sensor = sensor; self.authority = authority; self.store = store; self.engine = engine; self.profile = profile }
    private var computerUse: ComputerUseService?
    /// Mac computer use (A0112–A0114). Without it these routes answer computer_use_unsupported.
    public func attachComputerUse(_ service: ComputerUseService?) { computerUse = service }
    private func computerUseResponse(_ request: HostRequest, agent: HostAgent) async throws -> HostResponse {
        guard let computerUse else { throw DeviceAPIError(501, "computer_use_unsupported", "Computer use is available only in the Mac app.") }
        return try await computerUse.handle(request, agent: agent)
    }
    public func dispatch(_ job: HostJob) async -> HostResponse { await dispatch(job, admitted: false) }
    /// The Device Domain Name hint (bridgit.cc/names) from the cache; no network call.
    private func bridgitName(_ agent: HostAgent) async -> [String: Any]? {
        let canConfigure = HostAPIContract.allowed(agent).contains { $0.name == "configuration_name_set" }
        return await configuration?.bridgitNameHint(canConfigure: canConfigure)
    }
    func dispatch(_ job: HostJob, admitted: Bool, authenticated: HostAgent? = nil) async -> HostResponse {
        let started = ContinuousClock.now
        let at = Date()
        var caller: HostAgent?
        var executed: HostResponse.Activity?
        var outcome: HostResponse
        var knownAtAdmission: Int?
        do {
            let waiting = job.request.isFeedbackWait
            var reservedWait = false
            if !waiting {
                let cancellation = job.request.isMCPCancellation
                guard feedbackWaits[job.id] == nil, running.count < (cancellation ? 3 : 2), running.insert(job.id).inserted else {
                    throw DeviceAPIError(429, "device_busy", "At most two host jobs may run at once.")
                }
            }
            defer {
                if reservedWait { feedbackWaits.removeValue(forKey: job.id) }
                if !waiting { running.remove(job.id) }
            }
            let agent = try await (admitted ? authority.validate(job) : authority.admit(job))
            caller = agent
            try Task.checkCancellation()
            if waiting {
                guard agent.allows(.feedback) else { throw DeviceAPIError(403, "agent_not_allowed", "This caller has no feedback access.") }
                guard !running.contains(job.id), feedbackWaits[job.id] == nil,
                      feedbackWaits.values.lazy.filter({ $0 == agent.id }).prefix(4).count < 4 else {
                    throw DeviceAPIError(429, "too_many_waits", "At most four feedback waits may run per agent.")
                }
                feedbackWaits[job.id] = agent.id
                reservedWait = true
            }
            let key = HostAPIChanges.key(agent)
            let known = Int(try await store.state(key) ?? "0") ?? 0
            knownAtAdmission = known
            var response = try await execute(job.request, agent: agent, job: job, allowsStreaming: job.mode == OwnerDirectHostService.mode)
            executed = response.activityOverride
            _ = try await authority.validateDelivery(job)
            try await response.beforeDelivery?()
            try Task.checkCancellation()
            if response.status < 400, response.acknowledgesAPIChanges ||
                (job.request.method == "GET" && HostAPIPaths.selfDescription.contains(job.request.path)) {
                let previous = response.afterOriginalDelivery
                response.afterOriginalDelivery = { [authority, store] in
                    await previous?()
                    do {
                        _ = try await authority.validateDelivery(job)
                        try await store.setState(key, value: String(HostAPIChanges.current))
                    } catch {
                        Logger(subsystem: "cc.bridgit.host", category: "discovery").error("Could not acknowledge API changes after delivery.")
                    }
                }
            }
            outcome = response
        } catch is CancellationError { outcome = .failure(DeviceAPIError(409, "cancelled", "This request was cancelled.")) }
        catch {
            if caller == nil, let api = error as? DeviceAPIError,
               Self.isAuthenticatedDenial(api), let authenticated,
               authenticated.id == job.agentID, authenticated.generation == job.generation,
               authenticated.mode == job.mode, job.deviceID == authority.deviceID {
                caller = authenticated
            }
            outcome = .failure(error)
        }
        outcome = outcome.withHostAPIRevision()
        if let caller {
            let known: Int
            if let knownAtAdmission { known = knownAtAdmission }
            else { known = Int((try? await store.state(HostAPIChanges.key(caller))) ?? "0") ?? 0 }
            if known < HostAPIChanges.current {
                let link = "/v1/changes?since=" + String(max(0, known))
                outcome.headers["X-Bridgit-Whats-New"] = link
                outcome.headers["X-Bridget-Whats-New"] = link
            }
        }
        if let caller, let operation = executed?.operation ?? job.request.route?.0 {
            if outcome.stream != nil {
                var streaming = outcome
                let type = outcome.contentType
                streaming.streamCompletion = { [store] bytes, error in
                    let result: HostResponse
                    if let error {
                        let api = error as? DeviceAPIError ?? DeviceAPIError(409, "cancelled", "Export delivery stopped.")
                        result = .failure(api)
                    } else { result = .init(contentType: type, data: Data()) }
                    let facts = HostActivityRequestFacts(job: job, response: result, started: started, streamedBytes: bytes)
                    try? await store.recordHostActivity(agent: caller, operation: operation, status: result.status, at: at, facts: facts)
                }
                return streaming
            }
            let facts = outcome.activityOverride?.facts ?? executed?.facts.replacingResponse(outcome)
                ?? HostActivityRequestFacts(job: job, response: outcome, started: started)
            do { try await store.recordHostActivity(agent: caller, operation: operation,
                status: outcome.activityOverride?.status ?? outcome.status, at: at, facts: facts) }
            catch { Logger(subsystem: "cc.bridgit.host", category: "activity").error("Could not save request activity.") }
        }
        return outcome
    }
    // Only transports may supply an agent after validating its credential or sealed frame.
    static func isAuthenticatedDenial(_ error: DeviceAPIError) -> Bool {
        error.status == 403 && ["agent_not_allowed", "satellite_scope", "viewer_read_only", "claim_required"].contains(error.code)
    }
    func recordAuthenticatedDenial(_ error: Error, agent: HostAgent, job: HostJob) async {
        guard let api = error as? DeviceAPIError, Self.isAuthenticatedDenial(api),
              agent.id == job.agentID, agent.generation == job.generation, agent.mode == job.mode,
              job.deviceID == authority.deviceID, let operation = job.request.route?.0 else { return }
        let facts = HostActivityRequestFacts(job: job, response: .failure(api), resultCode: api.code)
        do { try await store.recordHostActivity(agent: agent, operation: operation, status: api.status, facts: facts) }
        catch { Logger(subsystem: "cc.bridgit.host", category: "activity").error("Could not save request activity.") }
    }
    static func directAuditRoute(method: String, target: String) -> (endpoint: String, grant: HostOperation?)? {
        let path = String(target.prefix { $0 != "?" })
        let request = HostRequest(method: method, path: path)
        if let operation = HostAPIContract.operation(matching: request) {
            return (operation.path, request.route?.0 ?? operation.grant)
        }
        if path == "/v1/mcp", ["GET", "POST"].contains(method) { return (path, .deviceRead) }
        if path == "/v1/satellites", method == "GET" { return (path, .deviceRead) }
        if request.route != nil { return ("(known route)", request.route?.0) }
        return nil
    }

    func recordDirectHTTPRejection(_ error: DeviceAPIError, method: String, target: String, source: TailnetAddress?,
                                   bytesIn: Int?, at: Date, started: ContinuousClock.Instant,
                                   verifiedAgent: HostAgent? = nil) async {
        let route = Self.directAuditRoute(method: method, target: target)
        let facts = HostActivityRequestFacts(parsedMethod: method, canonicalEndpoint: route?.endpoint ?? "(unknown route)",
            resultCode: error.code, source: source, bytesIn: bytesIn, requestID: UUID().uuidString, started: started, denialReason: error.reason)
        do {
            if let agent = verifiedAgent, let operation = route?.grant {
                try await store.recordHostActivity(agent: agent, operation: operation, status: error.status, at: at, facts: facts)
            } else {
                try await store.recordAnonymousHostDenial(status: error.status, at: at, facts: facts)
            }
        } catch { Logger(subsystem: "cc.bridgit.host", category: "activity").error("Could not save direct request rejection activity.") }
    }

    public func satelliteReturnView(_ request: HostRequest, agent: HostAgent) async throws -> HostResponse {
        guard request.method == "GET", ["/v1/portal/home", "/v1/portal/logs"].contains(request.path),
              agent.satelliteRole == "return", let current = try await authority.agent(agent.id), current.satelliteRole == "return", !current.paused,
              current.generation == agent.generation, current.tailnetPeer == agent.tailnetPeer else { throw DeviceAPIError(403, "satellite_not_allowed", "This return link has no shared Home access.") }
        return try await readViewer(request, agent: agent)
    }
    private func readViewer(_ request: HostRequest, agent: HostAgent) async throws -> HostResponse {
        guard request.body.isEmpty else { throw DeviceAPIError(422, "invalid_parameter", "Viewer reads take no body.") }
        guard HostViewerPairing.reads.contains(request.path) else { throw DeviceAPIError(501, "unsupported_operation", "This viewer operation is unavailable.") }
        try Self.queryFields(request.query, allowed: ["logs", "limit", "since", "until", "client", "client_type", "client_platform", "client_device"])
        let facts = try await profile(agent)
        let device: [String: Any] = ["name": facts.device.name, "platform": facts.device.platform, "device_type": facts.device.deviceType, "app_version": facts.device.appVersion, "os_version": facts.device.osVersion]
        let agents = try await authority.list().map { value -> [String: Any] in
            ["id": value.id, "name": value.name, "paused": value.paused, "transport": value.usesRelay ? "relay" : "direct", "mode": value.mode, "portal": value.viewer == true]
        }
        var applets = try await store.packages().map { package -> [String: Any] in
            ["id": package["id"] as? String ?? "", "name": package["name"] as? String ?? package["id"] as? String ?? ""]
        }
        // Viewer consent exposes installed names, never controller data or permissions.
        // Keep the projection explicit even if a provider returns richer metadata.
        if let additionalViewerApplets {
            var ids = Set(applets.compactMap { $0["id"] as? String })
            for value in try await additionalViewerApplets(agent) {
                guard let id = value["id"] as? String, !id.isEmpty,
                      let name = value["name"] as? String, ids.insert(id).inserted else {
                    throw DeviceAPIError(409, "applet_identity_conflict", "Installed applet identities conflict.")
                }
                applets.append(["id": id, "name": name])
            }
        }
        let limit = try Self.queryInteger(request.query["logs"] ?? request.query["limit"], default: 50, range: 1...100)
        func cursor(_ key: String) throws -> Int64? {
            guard let raw = request.query[key] else { return nil }
            let text: String
            if let value = raw as? String { text = value }
            else if let value = raw as? NSNumber, CFGetTypeID(value) != CFBooleanGetTypeID() { text = value.stringValue }
            else { throw DeviceAPIError(422, "invalid_parameter", "Use an activity cursor.") }
            guard let value = Int64(text), value >= 0 else { throw DeviceAPIError(422, "invalid_parameter", "Use an activity cursor.") }
            return value
        }
        let since = try cursor("since"), until = try cursor("until")
        let records = try await store.hostActivity(limit: limit + 1, before: until)
        let page = Array(records.prefix(limit)).filter { since == nil || $0.id > since! }
        let logs: [String: Any] = ["entries": page.map { HostActivityDetail($0).fields }, "has_older": records.count > limit, "next_since": page.first?.id as Any? ?? NSNull()]
        let features = facts.capabilities.sorted { $0.key < $1.key }.map { id, cap -> [String: Any] in
            ["id": id, "name": id.capitalized, "enabled": cap.enabled, "ready": cap.ready, "implemented": cap.implemented, "blocked_reason": cap.blockedReason as Any? ?? NSNull()]
        }
        let home: [String: Any] = ["device": device, "features": features, "agents": agents, "applets": applets]
        switch request.path {
        case "/v1/portal/home": return try .json(home)
        case "/v1/portal/agents": return try .json(["agents": agents])
        case "/v1/portal/applets": return try .json(["applets": applets])
        case "/v1/portal/logs": return try .json(logs)
        case "/v1/portal/theme": return try .json(["source": "native", "theme": NSNull()])
        default: return try .json(["home": home, "agents": ["agents": agents], "applets": ["applets": applets], "logs": logs])
        }
    }
    private func execute(_ request: HostRequest, agent: HostAgent, job: HostJob, allowsStreaming: Bool = false) async throws -> HostResponse {
        var request = request
        if let raw = request.query["target"] {
            guard let target = raw as? String else { throw DeviceAPIError(422, "unsupported_target", "Choose a valid satellite target.") }
            if target.isEmpty || target == "hub" && satelliteForward != nil { request.query.removeValue(forKey: "target") }
            else {
                guard SatelliteForwardPolicy.validTarget(target), let satelliteForward else { throw DeviceAPIError(422, "unsupported_target", "This satellite target is unavailable. Omit target to act on this device.") }
                return try await satelliteForward(request, agent) { [authority] requireFreshCapture in
                    _ = try await authority.validate(job, requireFreshCapture: requireFreshCapture)
                }
            }
        }
        if request.method == "GET", request.path == "/v1/satellites" {
            guard request.query.isEmpty, request.body.isEmpty else { throw DeviceAPIError(422, "invalid_parameter", "Satellite listing takes no parameters.") }
            guard let satelliteList else { throw DeviceAPIError(501, "satellite_unavailable", "Satellite listing is not configured on this host.") }
            return try .json(["satellites": await satelliteList(agent), "how_to_call": "Use target=<satellite id> for a listed endpoint. Omit it to use this device."])
        }
        if request.path == HostViewerPairing.claimPath {
            try await authority.claimViewer(job)
            return try .json(["claimed": true])
        }
        if request.path.hasPrefix("/v1/portal/") {
            guard agent.viewer == true, request.method == "GET" else { throw DeviceAPIError(403, "viewer_required", "This route requires an owner-issued viewer link.") }
            return try await readViewer(request, agent: agent)
        }
        guard let route = request.route else { throw DeviceAPIError(501, "unsupported_operation", "This operation is unavailable.") }
        if route.0 == .lanDiscovery {
            guard let lan else { throw DeviceAPIError(503, "lan_unavailable", "Network discovery is unavailable.") }
            guard await lan.isEnabled() else { throw DeviceAPIError(403, "lan_disabled", "Network discovery is off.") }
        }
        if let kind = TransferKind(rawValue: route.0.rawValue) { try await store.requireTransferEnabled(kind) }
        let directFeedback = job.mode == OwnerDirectHostService.mode && agent.allows(.feedback) && request.isFeedbackFiling
        let limit = (directFeedback ? 2 : 1) * 1_024 * 1_024
        let bodySize: Int
        if directFeedback, let measured = request.bodyBytesMeasured {
            bodySize = measured
        } else {
            let encoding: JSONSerialization.WritingOptions = directFeedback ? [.withoutEscapingSlashes] : [.sortedKeys]
            bodySize = try JSONSerialization.data(withJSONObject: request.body, options: encoding).count
        }
        guard bodySize <= limit else {
            throw DeviceAPIError(413, "request_too_large", "Use a request body no larger than \(directFeedback ? 2 : 1) MiB.")
        }
        if request.path == "/v1/mcp" {
            if request.method == "GET" { return .init(status: 405, data: Data(), headers: ["Allow": "POST"]) }
            return try await mcp(request.body, agent: agent, job: job)
        }
        if request.method == "GET", ComputerUseContract.handles(request) {
            return try await computerUseResponse(request, agent: agent)
        }
        if request.method == "GET" {
            if route.0 == .lanDiscovery {
                guard let lan else { throw DeviceAPIError(503, "lan_unavailable", "Network discovery is unavailable.") }
                return try await lan.execute(request, authorize: { [authority] in _ = try await authority.validate(job) })
            }
            if route.0 == .feedback { return try await feedbackResponse(request, agent: agent) }
            if route.0 == .log {
                guard request.body.isEmpty else { throw HostLogQuery.invalid("Activity reads take no body.") }
                return try await HostActivityReader(store: store, authorize: { [authority] in
                    try await authority.validate(job)
                }).response(to: request, streamAuthorization: allowsStreaming ? { [authority] in
                    try await authority.validateOwnerDirectStream(job)
                } : nil)
            }
            let p = request.path.split(separator: "/").map(String.init)
            if request.path == "/v1/events" { try Self.queryFields(request.query, allowed: ["since", "wait", "types", "limit"]) }
            else if request.path == "/v1/interactions" { try Self.queryFields(request.query, allowed: ["status", "limit"]) }
            else if p.count == 3 && p[1] == "interactions" { try Self.queryFields(request.query, allowed: ["wait"]) }
            else if ["/v1/health", "/v1/capabilities", "/v1/device/profile", HostAPIPaths.openAPI, HostAPIPaths.guide, "/v1/connections"].contains(request.path) { try Self.queryFields(request.query, allowed: []) }
            return try await read(request, agent: agent)
        }
        if route.0 == .appletsWrite && request.method == "DELETE" {
            guard request.body.isEmpty else { throw DeviceAPIError(422, "invalid_parameter", "Row deletion takes no body.") }
        } else if route.0 == .appletsWrite || (route.0 == .workflowsRun && request.path.split(separator: "/").dropFirst(4).first == "flows") {
            let field = route.0 == .workflowsRun ? "with" : request.method == "POST" ? "row" : "set"
            guard Set(request.body.keys).isSubset(of: [field]), request.body[field] == nil && field == "with" || request.body[field] is [String: Any] else { throw DeviceAPIError(422, "invalid_parameter", "Supply only the declared " + field + " object.") }
        }
        let note = route.0 == .log ? try HostLogNote(request) : nil
        if let note, request.idempotencyKey == nil {
            let current = try await authority.validate(job)
            return try await store.recordHostNote(note, agent: current)
        }
        if note != nil, request.idempotencyKey?.range(of: HostLogNote.keyPattern, options: .regularExpression) == nil {
            throw DeviceAPIError(422, "invalid_parameter", "Idempotency-Key must contain 8–128 letters, digits, hyphens or underscores.")
        }
        guard let key = request.idempotencyKey, !key.isEmpty, key.count <= 128 else { throw DeviceAPIError(400, "idempotency_key_required", "Use a new Idempotency-Key for an action and reuse it only to retry that same action.") }
        let prefix = "host-idempotency:" + agent.id + ":" + agent.generation + ":"
        let digest = SHA256.hash(data: Data(key.utf8)).map { String(format: "%02x", $0) }.joined()
        let record = prefix + digest
        guard activeKeys.insert(record).inserted else { throw DeviceAPIError(409, "action_in_progress", "This action is already running.") }
        defer { activeKeys.remove(record) }
        let intentBody = note == nil ? request.body : request.body.filter { HostLogNote.fields.contains($0.key) }
        let intentQuery = note == nil ? request.query : [:]
        let intent = try JSONSerialization.data(withJSONObject: ["body": intentBody, "query": intentQuery], options: [.sortedKeys])
        let fingerprint = SHA256.hash(data: Data((request.method + "\n" + request.path + "\n").utf8) + intent).map { String(format: "%02x", $0) }.joined()
        if let saved = try await store.reserveHostAction(record, ownerPrefix: prefix, fingerprint: fingerprint), let object = try JSONSerialization.jsonObject(with: Data(saved.utf8)) as? [String: Any] {
            guard object["fingerprint"] as? String == fingerprint else { throw DeviceAPIError(409, "idempotency_conflict", "This key was already used for a different action.") }
            if object["result_expired"] as? Bool == true { throw DeviceAPIError(409, "idempotency_result_expired", "The action result is no longer retained. Inspect device state before choosing a new action key.") }
            guard let body = object["body_base64"] as? String, let data = Data(base64Encoded: body), let status = object["status"] as? Int else { throw DeviceAPIError(409, "outcome_unknown", "The previous action was interrupted. Inspect its data before choosing a new action.") }
            let response = HostResponse(status: status, contentType: object["content_type"] as? String ?? "application/json", data: data, headers: object["headers"] as? [String: String] ?? [:])
            if route.0 == .configurationWrite, let configuration { return await configuration.attachDelivery(to: response, agent: agent) }
            if route.0 == .lanDiscovery, let lan {
                // Cached inventory is tied to the observed network, including when
                // the replacement LAN reuses its private address range.
                let scope = object["lan_network_scope"] as? String
                if response.status < 400 { try lan.requireNetworkScope(scope) }
                // Cached JSON cannot preserve the original response's live policy closure.
                return response.requiringDelivery { [authority] in
                    _ = try await authority.validate(job)
                    guard await lan.isEnabled() else { throw DeviceAPIError(403, "lan_disabled", "Network discovery is off.") }
                    if response.status < 400 { try lan.requireNetworkScope(scope) }
                }
            }
            return response
        }
        // Reserve before an await so duplicate keys cannot run concurrently. A crash preserves
        // an unknown-outcome marker rather than repeating an action with side effects.
        let p = request.path.split(separator: "/").map(String.init)
        let applet = route.1 ?? ""
        let current = try await authority.validate(job, requireFreshCapture: true)
        try Task.checkCancellation()
        if let note { return try await store.recordHostNote(note, agent: current, reservation: (record, fingerprint)) }
        let result: HostResponse
        let additional: HostResponse?
        do { additional = ComputerUseContract.handles(request) ? nil : try await additionalAppletAction?(request, agent) }
        catch let error as DeviceAPIError where (400...499).contains(error.status) {
            additional = .failure(error)
        }
        if let additional { result = additional }
        else if ComputerUseContract.handles(request) {
            // Recheck the current grant and the computer-use setting just before acting.
            do { result = try await computerUseResponse(request, agent: current) }
            catch let error as DeviceAPIError where (400...499).contains(error.status) && error.status != 408 { result = .failure(error) }
        }
        else {
        switch route.0 {
        case .lanDiscovery:
            do {
                guard let lan else { throw DeviceAPIError(503, "lan_unavailable", "Network discovery is unavailable.") }
                result = try await lan.execute(request, authorize: { [authority] in _ = try await authority.validate(job) })
            } catch { result = .failure(error) }
        case .connections:
            do {
                guard let connections else { throw DeviceAPIError(503, "connections_unavailable", "Connection delivery is unavailable.") }
                if request.method == "DELETE" {
                    guard request.body.isEmpty, request.query.isEmpty, let id = request.path.split(separator: "/").last else { throw DeviceAPIError(422, "invalid_parameter", "Cancel a delivery without a body or query.") }
                    result = try .json(await connections.cancelDelivery(String(id), for: agent, authorize: { [authority] in _ = try await authority.validate(job) }))
                    break
                }
                guard request.query.isEmpty, Set(request.body.keys).isSubset(of: ["to", "text", "data", "photo", "wait_reply"]),
                      let id = request.body["to"] as? String, UUID(uuidString: id) != nil,
                      request.body["text"] == nil || request.body["text"] is String,
                      request.body["data"] == nil || request.body["data"] is NSNull || request.body["data"] is [String: Any] else {
                    throw DeviceAPIError(422, "invalid_parameter", "Use to, optional text/data/kept photo and a boolean wait_reply.")
                }
                var wait = false
                if let raw = request.body["wait_reply"] {
                    guard let value = raw as? NSNumber, CFGetTypeID(value) == CFBooleanGetTypeID() else { throw DeviceAPIError(422, "invalid_parameter", "wait_reply must be boolean.") }
                    wait = value.boolValue
                }
                let text = request.body["text"] as? String ?? "", data = request.body["data"] as? [String: Any] ?? [:]
                var photo: ConnectionSendPhoto?
                if let raw = request.body["photo"], !(raw is NSNull) {
                    guard let ref = raw as? String else { throw DeviceAPIError(422, "invalid_parameter", "photo must be a kept photo reference.") }
                    if !ref.isEmpty { photo = try await store.connectionPhoto(ref: ref, agent: agent) }
                }
                let photoApplet = photo?.applet
                let authorize: () async throws -> Void = { [authority] in
                    _ = try await authority.validate(job)
                    if let photoApplet { try await authority.authorizeConnectionPhoto(agentID: agent.id, generation: agent.generation, mode: agent.mode, sourcePeer: agent.ownerDirectRequestPeer, applet: photoApplet) }
                }
                if wait {
                    result = try .json(await connections.send(to: id, text: text, data: data, sender: agent.eventOwner, phone: job.deviceID, appletName: agent.name, photo: photo, authorize: authorize))
                } else {
                    result = try .json(await connections.enqueue(to: id, text: text, data: data, agent: agent, phone: job.deviceID, idempotencyKey: key, photo: photo, authorize: authorize))
                }
            } catch is CancellationError { throw CancellationError() }
            catch {
                // Android ConnectionsApi.mapped exposes queue pressure as rate_limited.
                // Adapt only this public send route, before caching its response for retries.
                if request.method == "POST", request.path == "/v1/connections/send",
                   let api = error as? DeviceAPIError, api.status == 429,
                   let retryAfter = api.code == "queue_full" ? 60 : (api.code == "rate_limited" ? api.connectionSendRetryAfterSeconds : nil) {
                    result = try .json(["error": "rate_limited", "message": api.message,
                                        "retry_after_s": retryAfter], status: 429)
                } else if request.method == "POST", request.path == "/v1/connections/send", let api = error as? DeviceAPIError,
                          ["photo_not_supported", "invalid_photo", "photo_too_large"].contains(api.code) {
                    result = .failure(DeviceAPIError(422, "invalid_parameter", api.message))
                } else if request.method == "POST", request.path == "/v1/connections/send", let api = error as? DeviceAPIError, api.code == "photo_not_found" {
                    result = .failure(DeviceAPIError(404, "not_found", "No kept photo is available to this sender."))
                } else { result = .failure(error) }
            }
        case .files, .downloads, .shares:
            do {
                let kind = TransferKind(rawValue: route.0.rawValue)!
                guard request.query.isEmpty else { throw DeviceAPIError(422, "invalid_parameter", "Transfer actions take no query.") }
                if request.method == "DELETE" {
                    guard request.body.isEmpty else { throw DeviceAPIError(422, "invalid_parameter", "Transfer deletion takes no body.") }
                    try await store.deleteTransfer(id: p[2], for: agent, kind: kind, requestedBy: current)
                    result = try .json(["deleted": true])
                } else {
                    let body = request.body
                    guard Set(body.keys).isSubset(of: ["name", "mime", "data_base64"]), let name = body["name"] as? String,
                          body["mime"] == nil || body["mime"] is String, let encoded = body["data_base64"] as? String,
                          let bytes = Data(base64Encoded: encoded) else { throw DeviceAPIError(422, "invalid_parameter", "Supply a filename, optional MIME type and base64 file data.") }
                    result = try .json(await store.saveTransfer(for: agent, kind: .files, name: name,
                                                                mime: body["mime"] as? String ?? "application/octet-stream", bytes: bytes, requestedBy: current), status: 201)
                }
            } catch let error as DeviceAPIError where (400...499).contains(error.status) { result = .failure(error) }
        case .appletsManage:
            do {
                guard request.query.isEmpty else { throw DeviceAPIError(422, "invalid_parameter", "Applet management takes no query.") }
                if request.method == "DELETE" {
                    guard request.body.isEmpty else { throw DeviceAPIError(422, "invalid_parameter", "Applet removal takes no body.") }
                    let retained = try await engine.remove(applet: applet, requestedBy: current, didRemove: { [appletChanged] id in await appletChanged?(id, "remove") })
                    result = try .json(["removed": true, "id": applet, "left_behind": retained])
                } else if p.last == "pause" {
                    guard Set(request.body.keys) == ["paused"], let value = request.body["paused"] as? NSNumber, CFGetTypeID(value) == CFBooleanGetTypeID() else { throw DeviceAPIError(422, "invalid_parameter", "Supply only a paused boolean.") }
                    try await engine.pause(applet: applet, paused: value.boolValue, requestedBy: current)
                    await appletChanged?(applet, "pause")
                    result = try .json(Self.installed(try await store.package(applet), paused: value.boolValue))
                } else {
                    guard Set(request.body.keys).isSubset(of: ["package", "config"]), let package = request.body["package"] as? [String: Any], request.body["config"] == nil || (request.body["config"] as? [String: Any])?.isEmpty == true else { throw DeviceAPIError(422, "unsupported_package_input", "Supply an inline package and optional empty config. URL import and configured packages are not supported yet.") }
                    let bytes = try JSONSerialization.data(withJSONObject: package)
                    let checked = try await engine.validate(bytes), id = checked["id"] as! String
                    if p.last == "validate" {
                        result = try .json(["valid": true, "package_id": id, "execution": "called_flows", "installed": false, "grants_created": false])
                    } else {
                        let update = p.last == "update"
                        if update { guard id == applet else { throw DeviceAPIError(422, "package_id_mismatch", "Update the same package ID.") } }
                        try await engine.install(bytes, mode: update ? .update : .create, requestedBy: current, didInstall: { [appletChanged] id in await appletChanged?(id, update ? "update" : "install") })
                        result = try .json(Self.installed(checked, paused: try await store.paused(id)), status: update ? 200 : 201)
                    }
                }
            } catch let error as DeviceAPIError where (400...499).contains(error.status) && error.status != 408 { result = .failure(error) }
        case .feedback, .feedbackFixer:
            do { result = try await feedbackResponse(request, agent: current) }
            catch let error as DeviceAPIError where (400...499).contains(error.status) && error.status != 408 {
                result = .failure(error)
            }
        case .administrator, .configurationWrite:
            do {
                if request.path.hasPrefix("/v1/owner/configuration/callbacks") {
                    guard let callbacks else { throw DeviceAPIError(503, "callbacks_unavailable", "Callbacks are unavailable.") }
                    result = try await callbacks.configuration(request, agent: agent)
                } else {
                    guard let configuration else { throw DeviceAPIError(503, "settings_unavailable", "Configuration is unavailable.") }
                    result = try await configuration.configuration(request, agent: agent)
                }
            } catch let error as DeviceAPIError where (400...499).contains(error.status) && error.status != 408 { result = .failure(error) }
        case .interactionsRead:
            guard request.path == "/v1/callback/test", request.query.isEmpty, request.body.isEmpty, let callbacks else {
                throw DeviceAPIError(503, "callbacks_unavailable", "Callbacks are unavailable.")
            }
            do { result = try await callbacks.test(agent: current) }
            catch let error as DeviceAPIError where (400...499).contains(error.status) && error.status != 408 { result = .failure(error) }
        case .interactionsWrite:
            do {
                if request.method == "POST" { result = try .json(await store.createInteraction(owner: agent.eventOwner, input: request.body, senderName: agent.name, requestedBy: current), status: 201) }
                else { result = try .json(await store.cancelInteraction(owner: agent.eventOwner, id: p[2], requestedBy: current)) }
            } catch let error as DeviceAPIError where (400...499).contains(error.status) && error.status != 408 {
                // Interaction mutations are one SQLite transaction. A definite rejection
                // rolls it back, so cache that failure rather than leaving outcome_unknown.
                result = .failure(error)
            }
        case .appletsMediaWrite:
            guard let sensor else { throw DeviceAPIError(501, "not_implemented", "This device has no photo importer.") }
            do { result = try await sensor(request, agent) }
            catch let error as DeviceAPIError where (400...499).contains(error.status) && error.status != 408 { result = .failure(error) }
        case .microphoneRecord:
            guard let sensor else { throw DeviceAPIError(501, "not_implemented", "This device has no microphone handler.") }
            do {
                let options = try MicrophoneClipRequest(request)
                let remaining = Double(job.deadline) / 1000 - Date().timeIntervalSince1970
                guard remaining >= Double(options.minimumJobSeconds) else {
                    throw DeviceAPIError(408, "deadline_expired", "The remaining request deadline is too short to record this clip.")
                }
                result = try await sensor(request, agent)
            } catch let error as DeviceAPIError where (400...499).contains(error.status) {
                result = .failure(error)
            }
        case .cameraSnapshot:
            guard let sensor else { throw DeviceAPIError(501, "not_implemented", "This device has no camera handler.") }
            result = try await sensor(request, agent)
        case .appletsWrite:
            do {
                if request.method == "DELETE" {
                    if p.count == 8 {
                        try await store.delete(applet: applet, table: p[5], rowID: p[7], requestedBy: current)
                        result = try .json(["deleted": true, "row_id": p[7]])
                    } else {
                        result = .failure(DeviceAPIError(501, "unsupported_operation", "Bulk deletion is unavailable for this applet store."))
                    }
                } else if request.method == "POST", let row = request.body["row"] as? [String: Any] {
                    let saved = try await store.addRows(applet: applet, table: p[5], rows: [row], by: "agent", requestedBy: current)
                    result = try .json(saved[0], status: 201)
                } else if request.method == "PATCH", let values = request.body["set"] as? [String: Any] {
                    result = try .json(await store.update(applet: applet, table: p[5], rowID: p[7], values: values, requestedBy: current))
                } else { throw DeviceAPIError(422, "invalid_row", "Supply the declared row or set object.") }
            } catch let error as DeviceAPIError where (400...499).contains(error.status) && error.status != 408 { result = .failure(error) }
        case .workflowsRun:
            do {
                if p.count >= 5, p[4] == "batches" {
                    guard request.query.isEmpty, request.body.isEmpty else { throw DeviceAPIError(422, "invalid_parameter", "Batch actions take no parameters.") }
                    if request.method == "DELETE" {
                        let state = try await engine.cancelBatch(applet: applet, button: p[5], requestedBy: current)
                        result = .init(data: try JSONEncoder().encode(state))
                    } else {
                        try await engine.startBatch(applet: applet, button: p[5], requestedBy: current, authorize: { [authority] in try await authority.authorizeOngoing(agent, operation: .workflowsRun, applet: applet) }, authorizeSpeech: { [authority] operation in try await authority.authorizeOngoing(agent, operation: operation == "hear" ? .speechHear : .speechSpeak, applet: applet) })
                        let state = await engine.batch(applet: applet)
                        result = .init(status: 202, data: try JSONEncoder().encode(state))
                    }
                    await appletChanged?(applet, "batch")
                } else if p.count == 5, p[4] == "retry" {
                    guard request.query.isEmpty, Set(request.body.keys) == ["acknowledge_possible_charge"], let value = request.body["acknowledge_possible_charge"] as? NSNumber, CFGetTypeID(value) == CFBooleanGetTypeID(), value.boolValue else { throw DeviceAPIError(422, "explicit_retry_required", "Set acknowledge_possible_charge=true to allow another attempt.") }
                    try await engine.allowUncertainRetry(applet: applet, requestedBy: current)
                    result = try .json(["retry_allowed": true, "work_started": false])
                } else {
                    result = try .json(await engine.run(applet: applet, flow: p[5], with: request.body["with"] as? [String: Any] ?? [:], invocationID: agent.id + ":" + agent.generation + ":" + (request.idempotencyKey ?? ""), requestedBy: current, authorize: { [authority] in _ = try await authority.validate(job) }, authorizeSpeech: { [authority] operation in
                        let current = try await authority.validate(job)
                        let required: HostOperation = operation == "hear" ? .speechHear : .speechSpeak
                        guard current.allows(required) else { throw DeviceAPIError(403, "agent_not_allowed", "This caller needs a separate speech grant.") }
                    }))
                    await appletChanged?(applet, "data")
                }
            } catch let error as DeviceAPIError where ["invalid_parameter", "bad_with", "explicit_retry_required", "applet_paused", "batch_not_found", "batch_busy", "unsupported_action"].contains(error.code) { result = .failure(error) }
        default: throw DeviceAPIError(501, "unsupported_operation", "This operation is unavailable.")
        }
        }
        if route.0 == .appletsWrite, result.status < 400 { await appletChanged?(applet, "data") }
        var delivered = result
        delivered.headers["X-Bridgit-Idempotency-Retention"] = String(Int(HostIdempotencyPolicy.retentionSeconds))
        var completed = delivered.envelope; completed["fingerprint"] = fingerprint
        if let scope = delivered.lanNetworkScope { completed["lan_network_scope"] = scope }
        if route.0 == .interactionsWrite, let body = try? JSONSerialization.jsonObject(with: result.data) as? [String: Any], let id = body["interaction_id"] as? String { completed["interaction_id"] = id }
        if route.0 == .microphoneRecord, result.status < 400 {
            // A clip or transcript is sensitive. Keep the retry marker without retaining either.
            completed = ["fingerprint": fingerprint, "result_expired": true]
        }
        try await store.completeHostAction(record, result: completed)
        return delivered
    }
    private func read(_ request: HostRequest, agent: HostAgent) async throws -> HostResponse {
        if request.path != "/v1/applets/installed", let response = try await additionalAppletAction?(request, agent) {
            return response
        }
        if request.path == "/v1/connections" {
            guard request.body.isEmpty, request.query.isEmpty else { throw DeviceAPIError(422, "invalid_parameter", "Listing connections takes no body or query.") }
            guard let connections else { throw DeviceAPIError(503, "connections_unavailable", "Connection delivery is unavailable.") }
            return try .json(["connections": await connections.list()])
        }
        let parts = request.path.split(separator: "/").map(String.init)
        if parts.count >= 3, parts[1] == "connections", parts[2] == "deliveries" {
            guard request.body.isEmpty else { throw DeviceAPIError(422, "invalid_parameter", "Delivery reads take no body.") }
            guard let connections else { throw DeviceAPIError(503, "connections_unavailable", "Connection delivery is unavailable.") }
            if parts.count == 3 {
                try Self.queryFields(request.query, allowed: ["before"])
                let before = request.query["before"] as? String
                guard request.query["before"] == nil || before.flatMap(UUID.init(uuidString:)) != nil else { throw DeviceAPIError(422, "invalid_parameter", "before must be a delivery ID from the previous page.") }
                return try .json(await connections.deliveries(for: agent, before: before))
            }
            try Self.queryFields(request.query, allowed: [])
            return try .json(await connections.delivery(parts[3], for: agent))
        }
        if parts.count >= 2, let kind = TransferKind(rawValue: parts[1]) {
            if parts.count == 2 {
                try Self.queryFields(request.query, allowed: [])
                return try .json(["items": await store.transfers(for: agent, kind: kind)])
            }
            try Self.queryFields(request.query, allowed: ["offset", "length"])
            let file = try await store.transferBytes(id: parts[2], for: agent, kind: kind)
            var headers = ["Cache-Control": "no-store", "Content-Disposition": "attachment"]
            if !request.query.isEmpty {
                let offset = try Self.queryInteger(request.query["offset"], default: 0, range: 0...Int.max)
                let length = try Self.queryInteger(request.query["length"], default: 2 * 1024 * 1024, range: 1...(2 * 1024 * 1024))
                guard offset < file.data.count else { throw DeviceAPIError(416, "invalid_range", "Offset is outside this file.") }
                let end = min(offset + length, file.data.count)
                headers["Content-Range"] = "bytes \(offset)-\(end - 1)/\(file.data.count)"
                return .init(status: 206, contentType: file.mime, data: file.data.subdata(in: offset..<end), headers: headers)
            }
            guard file.data.count <= 5 * 1024 * 1024 else { throw DeviceAPIError(413, "file_requires_chunks", "Read this file with offset and length chunks.") }
            return .init(contentType: file.mime, data: file.data, headers: headers)
        }
        if request.route?.0 == .configurationRead || request.route?.0 == .administrator {
            if request.path.hasPrefix("/v1/owner/configuration/callbacks") {
                guard let callbacks else { throw DeviceAPIError(503, "callbacks_unavailable", "Callbacks are unavailable.") }
                return try await callbacks.configuration(request, agent: agent)
            }
            guard let configuration else { throw DeviceAPIError(503, "settings_unavailable", "Configuration is unavailable.") }
            return try await configuration.configuration(request, agent: agent)
        }
        switch request.path {
        case "/v1/interactions":
            let limit = try Self.queryInteger(request.query["limit"], default: 50, range: 1...100)
            if let status = request.query["status"], !(status is String && ["pending", "seen", "answered", "dismissed", "expired", "cancelled"].contains(status as! String)) { throw DeviceAPIError(422, "invalid_parameter", "Invalid interaction status.") }
            return try .json(["interactions": await store.interactions(owner: agent.eventOwner, status: request.query["status"] as? String, limit: limit)])
        case "/v1/events":
            let after: Int64? = try request.query["since"].map { Int64(try Self.queryInteger($0, default: 0, range: 0...Int.max)) }
            let wait = try Self.queryInteger(request.query["wait"], default: 0, range: 0...55)
            guard wait == 0 || after != nil else { throw DeviceAPIError(422, "invalid_parameter", "Waiting for events requires since.") }
            let limit = try Self.queryInteger(request.query["limit"], default: after == nil ? 50 : 100, range: 1...100)
            if let types = request.query["types"], !(types is String) { throw DeviceAPIError(422, "invalid_parameter", "Event types must be a comma-separated string.") }
            let types = (request.query["types"] as? String)?.split(separator: ",").map(String.init) ?? []
            let until = Date().addingTimeInterval(TimeInterval(wait))
            while true {
                let value = try await store.interactionEvents(owner: agent.eventOwner, after: after, limit: limit, types: types)
                if !(value["events"] as? [[String: Any]] ?? []).isEmpty || Date() >= until { return try .json(value) }
                try await Task.sleep(nanoseconds: 250_000_000)
            }
        case "/v1/samples":
            try Self.queryFields(request.query, allowed: ["id"])
            guard request.query["id"] == nil || request.query["id"] is String else { throw DeviceAPIError(422, "invalid_parameter", "Sample ID must be a string.") }
            guard let samples else { throw DeviceAPIError(503, "samples_unavailable", "Samples are not bundled in this host.") }
            return try .json(samples(request.query["id"] as? String))
        case "/v1/discover":
            try Self.queryFields(request.query, allowed: ["id"])
            if let id = request.query["id"], !(id is String) { throw DeviceAPIError(422, "invalid_parameter", "Capability ID must be a string.") }
            return try .json(HostAPIContract.discovery(agent, id: request.query["id"] as? String, lanAvailable: await lan?.isEnabled() ?? false, callbacksAvailable: callbacks != nil))
        case HostAPIPaths.changes:
            try Self.queryFields(request.query, allowed: ["since"])
            let since = try Self.queryInteger(request.query["since"], default: 0, range: 0...Int.max)
            let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "local"
            return try .json(HostAPIChanges.document(since: since, appVersion: version))
        case HostAPIPaths.openAPI: return try .json(HostAPIContract.openAPI(agent, lanAvailable: await lan?.isEnabled() ?? false, callbacksAvailable: callbacks != nil, crashReporting: await configuration?.supportsCrashReporting() ?? false))
        case HostAPIPaths.guide:
            let text = HostAPIContract.allowed(agent, lanAvailable: await lan?.isEnabled() ?? false, callbacksAvailable: callbacks != nil).map { "- \($0.method) \($0.path): \($0.summary)" }.joined(separator: "\n")
            return .init(contentType: "text/markdown", data: Data(("# Bridgit Apple host\n\n" + text + "\n\nInspect /v1/device/profile before calling. Applet package format: " + HostAPIPaths.appletPackageFormat + ". Actions require an Idempotency-Key unless the operation documents it as optional. Protection lasts 86400 seconds. Compacted replies return idempotency_result_expired without repeating work.\n").utf8))
        case "/v1/health":
            var health: [String: Any] = ["ok": true, "status": "ok", "app": "Bridgit", "api_revision": HostAPIChanges.current,
                "device_id": authority.deviceID, "name": "Bridgit", "transport": agent.usesRelay ? "relay" : "tailscale",
                "foreground_required": authority.requiresForeground]
            if agent.ownerDirectPeers != nil, let base = request.ownerDirectBaseURL { health["base_url"] = base.absoluteString }
            let known = max(0, Int(try await store.state(HostAPIChanges.key(agent)) ?? "0") ?? 0)
            health["whats_new"] = known >= HostAPIChanges.current ? NSNull() : ["since": known, "new_revisions": HostAPIChanges.current - known, "url": "/v1/changes?since=" + String(known), "summary": "Apple host API changes"] as [String: Any]
            if let name = await bridgitName(agent) { health["bridgit_name"] = name }
            return try .json(health)
        case "/v1/device/profile": return .init(data: try await profile(agent).encoded())
        case "/v1/capabilities":
            let facts = try await profile(agent)
            var entries: [String: Any] = [:]
            for (key, capability) in facts.capabilities {
                entries[key] = ["title": key.capitalized, "enabled": capability.enabled, "ready": capability.ready, "agent_allowed": capability.agentAllowed,
                    "blocked_reason": capability.blockedReason as Any? ?? NSNull(), "details": ["implemented": capability.implemented, "supported": capability.supported, "callable": capability.callable, "execution": capability.constraints.execution, "parameters": capability.constraints.parameters as Any? ?? NSNull(), "limits": capability.constraints.limits as Any? ?? NSNull()]]
            }
            let callbackStatus = try await callbacks?.status(agent: agent) ?? ["set": false, "host": NSNull()]
            var document: [String: Any] = ["device": ["name": facts.device.name, "platform": facts.device.platform, "device_type": facts.device.deviceType], "capabilities": entries, "profile_url": "/v1/device/profile", "notifications": await captureNotifications?() ?? false, "callback": callbackStatus, "listener": ["camera_ready": facts.capabilities["camera"]?.ready ?? false, "mic_ready": facts.capabilities["mic"]?.ready ?? false]]
            if let name = await bridgitName(agent) { document["bridgit_name"] = name }
            return try .json(document)
        case "/v1/applets/installed":
            let packages = try await store.packages().filter { agent.allowsApplet($0["id"] as? String ?? "", operation: .appletsRead) }
            var installed: [[String: Any]] = []
            for package in packages { installed.append(Self.installed(package, paused: try await store.paused(package["id"] as! String))) }
            installed.append(contentsOf: try await additionalApplets?(agent) ?? [])
            let ids = installed.compactMap { $0["id"] as? String }
            guard ids.count == installed.count, Set(ids).count == ids.count else {
                throw DeviceAPIError(409, "applet_identity_conflict", "Two applet stores reported the same installation identity.")
            }
            return try .json(["installed": installed, "pending": []])
        default:
            let p = request.path.split(separator: "/").map(String.init)
            if p.count == 3, p[1] == "interactions" {
                let wait = try Self.queryInteger(request.query["wait"], default: 0, range: 0...55)
                let until = Date().addingTimeInterval(TimeInterval(wait))
                while true {
                    let value = try await store.interaction(owner: agent.eventOwner, id: p[2])
                    if !["pending", "seen"].contains(value["status"] as? String ?? "") || Date() >= until { return try .json(value) }
                    try await Task.sleep(nanoseconds: 250_000_000)
                }
            }
            if p.count >= 5, p[4] == "photos" {
                try Self.queryFields(request.query, allowed: ["offset", "length"])
                let photo = try await store.photo(applet: p[3], ref: "kept:" + p[5])
                let offset = try Self.queryInteger(request.query["offset"], default: 0, range: 0...Int.max)
                let chunked = request.query["offset"] != nil || request.query["length"] != nil
                guard offset < photo.count else { throw DeviceAPIError(416, "invalid_range", "Offset is outside this photo.") }
                if chunked {
                    let length = try Self.queryInteger(request.query["length"], default: 2 * 1024 * 1024, range: 1...(2 * 1024 * 1024))
                    let end = min(photo.count, offset + length)
                    return .init(status: 206, contentType: "application/octet-stream", data: photo.subdata(in: offset..<end), headers: ["Content-Range": "bytes \(offset)-\(end - 1)/\(photo.count)", "Cache-Control": "no-store"])
                }
                guard photo.count <= 5 * 1024 * 1024 else { throw DeviceAPIError(413, "photo_requires_chunks", "Read this photo with offset and length chunks.") }
                return .init(contentType: "image/jpeg", data: photo, headers: ["Cache-Control": "no-store"])
            }
            if p.count == 5, p[4] == "batches" {
                guard request.query.isEmpty else { throw DeviceAPIError(422, "invalid_parameter", "Batch reads take no query.") }
                return .init(data: try JSONEncoder().encode(try await engine.batchStates(applet: p[3])))
            }
            if p.count >= 5, p[4] == "data" {
                try Self.queryFields(request.query, allowed: ["format", "table"])
                if let format = request.query["format"] as? String, format != "json" {
                    guard format == "csv", let sensor else { throw DeviceAPIError(422, "unsupported_export", "Use json or csv. Create other document formats from the saved-data API.") }
                    return try await sensor(request, agent)
                }
                guard request.query["format"] == nil || request.query["format"] as? String == "json", request.query["table"] == nil else { throw DeviceAPIError(422, "invalid_parameter", "JSON discovery exports all declared tables.") }
                let snapshot = try await store.appletDataSnapshot(p[3])
                let batch = try await engine.batchStates(applet: p[3])
                let batchJSON = try JSONSerialization.jsonObject(with: JSONEncoder().encode(batch)) as? [String: Any] ?? [:]
                return try .json(AppletDataContract.make(snapshot: snapshot, applet: p[3], includeRows: p.count == 6, mediaAllowed: agent.allows(.appletsMediaRead), batches: batchJSON))
            }
            if p.count == 4 { return try .json(Self.installed(try await store.package(p[3]), paused: try await store.paused(p[3]))) }
            try Self.queryFields(request.query, allowed: ["limit", "since"])
            guard request.query["since"] == nil || request.query["since"] is String else { throw DeviceAPIError(422, "invalid_cursor", "since must be the previous page's next string.") }
            return try .json(await store.rowPage(applet: p[3], table: p[5], limit: Self.queryInteger(request.query["limit"], default: 100, range: 1...200), since: request.query["since"] as? String))
        }
    }
    private func feedbackResponse(_ request: HostRequest, agent: HostAgent) async throws -> HostResponse {
        guard let feedback else { throw DeviceAPIError(503, "feedback_unavailable", "The local reports store is unavailable.") }
        let reply = try await feedback(request, agent)
        var response = reply.response
        response.headers["Cache-Control"] = "no-store"
        let grant: HostOperation = reply.requiresFixer ? .feedbackFixer : .feedback
        return response.requiringDelivery { [authority] in
            try Task.checkCancellation()
            try await authority.authorizeOngoing(agent, operation: grant)
        }
    }

    /// JSON-RPC tools over the authenticated relay transport. The handshake contract is
    /// https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle
    private func mcp(_ body: [String: Any], agent: HostAgent, job: HostJob) async throws -> HostResponse {
        let id = body["id"] ?? NSNull(), owner = agent.eventOwner
        func failure(_ code: Int, _ message: String) throws -> HostResponse { try .json(["jsonrpc": "2.0", "id": id, "error": ["code": code, "message": message]]) }
        guard body["jsonrpc"] as? String == "2.0", let method = body["method"] as? String,
              body["params"] == nil || body["params"] is [String: Any] else { return try failure(-32600, "Invalid JSON-RPC request") }
        if body["id"] == nil {
            if method == "notifications/cancelled", let params = body["params"] as? [String: Any], let target = Self.rpcID(params["requestId"]) { mcpTasks[owner + ":" + target]?.cancel() }
            return .init(status: 202, data: Data())
        }
        guard let requestID = Self.rpcID(id) else { return try failure(-32600, "Invalid request ID") }
        if method != "initialize" && method != "ping" && !mcpInitialized.contains(owner) { return try failure(-32002, "Initialize first") }
        let result: [String: Any]
        var afterDelivery: (() async -> Void)?
        // Private idempotency metadata, never included in a transport envelope.
    var lanNetworkScope: String?
    var acknowledgesAPIChanges = false
        var beforeDelivery: (() async throws -> Void)?
        var activityOverride: HostResponse.Activity?
        switch method {
        case "initialize":
            guard let params = body["params"] as? [String: Any], let requested = params["protocolVersion"] as? String,
                  params["capabilities"] is [String: Any], let client = params["clientInfo"] as? [String: Any], client["name"] is String, client["version"] is String else { return try failure(-32602, "Invalid initialize parameters") }
            let supported = ["2025-03-26", "2025-06-18", "2025-11-25"]
            let version = supported.contains(requested) ? requested : "2025-11-25"
            mcpInitialized.insert(owner)
            result = ["protocolVersion": version, "capabilities": ["tools": ["listChanged": false]], "serverInfo": ["name": "Bridgit Apple host", "version": "local-m3"]]
        case "ping": result = [:]
        case "tools/list":
            if let params = body["params"] as? [String: Any], !params.isEmpty { return try failure(-32602, "Tool pagination is not available") }
            result = ["tools": HostAPIContract.tools(agent, lanAvailable: await lan?.isEnabled() ?? false, callbacksAvailable: callbacks != nil, crashReporting: await configuration?.supportsCrashReporting() ?? false)]
        case "tools/call":
            guard let params = body["params"] as? [String: Any], let name = params["name"] as? String,
                  params["arguments"] == nil || params["arguments"] is [String: Any],
                  let operation = HostAPIContract.allowed(agent, lanAvailable: await lan?.isEnabled() ?? false, callbacksAvailable: callbacks != nil).first(where: { $0.name == name }) else { return try failure(-32602, "Unknown or ungranted tool") }
            var request: HostRequest
            do { request = try operation.request(params["arguments"] as? [String: Any] ?? [:]) }
            catch { return try failure(-32602, "Invalid tool arguments") }
            request.ownerDirectBaseURL = job.request.ownerDirectBaseURL
            var nested = job; nested.request = request
            let taskID = owner + ":" + requestID
            guard mcpTasks[taskID] == nil else { return try failure(-32600, "Request ID is already running") }
            let started = ContinuousClock.now
            let task = Task { () -> HostResponse in
                do { _ = try await self.authority.validate(nested, requireFreshCapture: true); return try await self.execute(request, agent: agent, job: nested) }
                catch is CancellationError { return .failure(DeviceAPIError(409, "cancelled", "Request cancelled")) }
                catch { return .failure(error) }
            }
            mcpTasks[taskID] = task
            defer { mcpTasks.removeValue(forKey: taskID) }
            let response = await withTaskCancellationHandler(operation: { await task.value }, onCancel: { task.cancel() })
            let measured = task.isCancelled ? HostResponse.failure(DeviceAPIError(409, "cancelled", "Request cancelled")) : response
            activityOverride = (operation.grant, measured.status,
                                HostActivityRequestFacts(job: job, response: measured, started: started, request: request))
            if task.isCancelled {
                var cancelled = try failure(-32800, "Request cancelled")
                cancelled.activityOverride = activityOverride
                return cancelled
            }
            afterDelivery = response.afterDelivery
            beforeDelivery = response.beforeDelivery
            acknowledgesAPIChanges = response.status < 400 && request.method == "GET" &&
                HostAPIPaths.selfDescription.contains(request.path)
            var content: [[String: Any]] = []
            if response.contentType == "image/jpeg" { content.append(["type": "image", "mimeType": "image/jpeg", "data": response.data.base64EncodedString()]) }
            else if response.contentType.hasPrefix("text/") || ["application/json", "application/x-ndjson"].contains(response.contentType) { content.append(["type": "text", "text": String(decoding: response.data, as: UTF8.self)]) }
            else { content.append(["type": "resource", "resource": ["uri": "bridgit://result/" + UUID().uuidString.lowercased(), "mimeType": response.contentType, "blob": response.data.base64EncodedString()]]) }
            result = ["content": content, "isError": response.status >= 400]
        default: return try failure(-32601, "Method not found")
        }
        var response = try HostResponse.json(["jsonrpc": "2.0", "id": id, "result": result])
        response.afterDelivery = afterDelivery
        response.beforeDelivery = beforeDelivery
        response.acknowledgesAPIChanges = acknowledgesAPIChanges
        if let activityOverride {
            response.activityOverride = activityOverride
            if [.microphoneRecord, .log, .feedback, .feedbackFixer].contains(activityOverride.operation) {
                response.headers["Cache-Control"] = "no-store"
            }
            if [.feedback, .feedbackFixer].contains(activityOverride.operation), response.data.count > 8 * 1_024 * 1_024 {
                var limited = try failure(-32000, "The report exceeds the MCP result limit. Ask with size=thumb.")
                limited.headers["Cache-Control"] = "no-store"
                limited.beforeDelivery = beforeDelivery
                limited.activityOverride = activityOverride
                return limited
            }
        }
        return response
    }
    func runningMCPRequests() -> Int { mcpTasks.count }
    private static func rpcID(_ value: Any?) -> String? {
        if let text = value as? String, text.utf8.count <= 128 { return "s:" + text }
        if let value, let number = RelayHostConnection.integer(value) { return "n:" + String(number) }
        return nil
    }
    private static func queryFields(_ query: [String: Any], allowed: Set<String>) throws {
        guard Set(query.keys).isSubset(of: allowed) else { throw DeviceAPIError(422, "invalid_parameter", "Unsupported query field.") }
    }
    private static func queryInteger(_ value: Any?, default fallback: Int, range: ClosedRange<Int>) throws -> Int {
        guard let value else { return fallback }
        let number = (value as? String).flatMap(Int.init) ?? RelayHostConnection.integer(value).flatMap(Int.init(exactly:))
        guard let number, range.contains(number) else { throw DeviceAPIError(422, "invalid_parameter", "Query value is outside its supported range.") }
        return number
    }
    private static func installed(_ package: [String: Any], paused: Bool = false) -> [String: Any] {
        ["id": package["id"] ?? "", "package_id": package["id"] ?? "", "name": package["name"] ?? "", "version": package["version"] ?? "", "status": paused ? "paused" : "active", "local": true, "data_url": "/v1/applets/installed/" + (package["id"] as? String ?? "") + "/data"]
    }
}
