import Foundation

/// Relay socket with platform-specific execution policy enforced by the host authority.
/// Start is explicit; signed enrollment and disconnect cancellation are shared. No direct listener.
public actor RelayHostConnection {
    public enum State: String { case stopped, connecting, connected, failed }
    public typealias Observer = (State, String?) async -> Void
    private let base: URL
    private let identity: RelayHostIdentity
    private let authority: HostAuthority
    private let dispatcher: HostDispatcher
    private let transport: RelayHostTransport
    private let observe: Observer
    private var connected = false
    public private(set) var pushProviders = Set<String>()
    public private(set) var pushTopic: String?
    public private(set) var completedJobs = 0
    private var reader: Task<Void, Never>?
    private var heartbeat: Task<Void, Never>?
    private var receiptRefresh: Task<Void, Never>?
    private var lastPong = Date()
    private var jobOwners: [String: String] = [:]
    private var feedbackWaitIDs = Set<String>()
    private var jobs: [String: Task<Void, Never>] = [:]
    private var deadlines: [String: Task<Void, Never>] = [:]
    private var acknowledgements: [String: CheckedContinuation<Void, Error>] = [:]
    private var generation = UUID()
    private var pushRevision: UInt64 = 0
    private var pushTail: (id: UUID, task: Task<Void, Error>)?
    public init(base: URL, identity: RelayHostIdentity, authority: HostAuthority, dispatcher: HostDispatcher, http: URLSession? = nil, transport: RelayHostTransport? = nil, outboundAccess: OutboundAccess = .unrestricted, observe: @escaping Observer = { _, _ in }) throws {
        guard base.scheme == "https", base.host != nil, base.user == nil, base.password == nil, base.query == nil, base.fragment == nil,
              base.path == "/d/" + identity.deviceID else { throw DeviceAPIError(422, "invalid_relay", "Use an HTTPS relay device URL matching this device identity.") }
        self.base = base; self.identity = identity; self.authority = authority; self.dispatcher = dispatcher; self.observe = observe
        self.transport = transport ?? URLSessionHostTransport(http: http, outboundAccess: outboundAccess)
    }
    public func start() async {
        let run = UUID(); generation = run
        await close()
        guard generation == run, !Task.isCancelled else { return }
        await observe(.connecting, nil)
        do {
            try valid(run)
            let nonce = UUID().uuidString.lowercased(), at = Int64(Date().timeIntervalSince1970 * 1000)
            let proof: [String: Any] = ["nonce": nonce, "at": at, "signature": try identity.proof(domain: "bridgit-relay-enroll-v1", nonce: nonce, at: at)]
            let enrolled = try await post("enroll", ["device_id": identity.deviceID, "identity_spki": identity.signer.publicKey.base64EncodedString(), "hpke_public": identity.hpkePublic.base64EncodedString(), "proof": proof], allowedError: "already_enrolled")
            _ = enrolled // Already-enrolled is accepted only if the following signed challenge succeeds.
            try valid(run)
            let challengeNonce = UUID().uuidString.lowercased(), challengeAt = Int64(Date().timeIntervalSince1970 * 1000)
            let challenge = try await post("challenge", ["nonce": challengeNonce, "at": challengeAt, "signature": try identity.proof(domain: "bridgit-relay-challenge-v1", nonce: challengeNonce, at: challengeAt)])
            guard let value = challenge["nonce"] as? String, let until = Self.integer(challenge["until"]), until > challengeAt, until <= challengeAt + 120_000 else { throw DeviceAPIError(502, "invalid_challenge", "The relay returned an invalid challenge.") }
            try valid(run)
            pushTopic = challenge["apns_topic"] as? String
            pushProviders = Set((challenge["push_providers"] as? [String] ?? []).filter { ["fcm", "apns"].contains($0) })
            var parts = URLComponents(url: base.appendingPathComponent("socket"), resolvingAgainstBaseURL: false)!; parts.scheme = "wss"
            var request = URLRequest(url: parts.url!); request.timeoutInterval = 30
            request.setValue(value, forHTTPHeaderField: "X-Relay-Nonce")
            request.setValue(try identity.proof(domain: "bridgit-relay-v1", nonce: value, at: until), forHTTPHeaderField: "X-Relay-Signature")
            try await transport.open(request); try valid(run); connected = true
            await authority.setConnected(true)
            try valid(run)
            try await announce()
            try valid(run)
            lastPong = Date()
            reader = Task { await self.read(run: run) }
            heartbeat = Task {
                while !Task.isCancelled {
                    do {
                        try await Task.sleep(nanoseconds: 20_000_000_000); try self.valid(run)
                        guard Date().timeIntervalSince(self.lastPong) < 75 else { throw DeviceAPIError(504, "heartbeat_timeout", "The relay stopped responding.") }
                        try await self.send(["type": "ping"])
                    } catch { if self.generation == run { await self.stop(); await self.observe(.failed, "The host connection timed out. All active requests stopped.") }; return }
                }
            }
            await observe(.connected, nil)
        } catch {
            guard generation == run else { return }
            await stop(); await observe(.failed, (error as? DeviceAPIError)?.message ?? "The relay connection failed. Start again when this device is online.")
        }
    }
    public func stop() async { generation = UUID(); await close() }
    private func close() async {
        heartbeat?.cancel(); heartbeat = nil; reader?.cancel(); reader = nil
        receiptRefresh?.cancel(); receiptRefresh = nil
        pushTail?.task.cancel(); pushTail = nil
        connected = false; pushProviders.removeAll(); pushTopic = nil
        for task in jobs.values { task.cancel() }; jobs.removeAll(); jobOwners.removeAll(); feedbackWaitIDs.removeAll()
        for task in deadlines.values { task.cancel() }; deadlines.removeAll()
        let pending = acknowledgements; acknowledgements.removeAll()
        for continuation in pending.values { continuation.resume(throwing: CancellationError()) }
        await transport.close()
        await authority.setConnected(false)
        await observe(.stopped, nil)
    }
    /// Ordered registrations with a caller-owned monotonic consent revision. A late
    /// older token cannot overtake Off, including across a reconnect of this object.
    public func publishPush(_ registration: RelayPushRegistration?, revision: UInt64? = nil) async throws {
        guard connected else { throw DeviceAPIError(409, "host_not_connected", "Connect the host before registering for relay wake-ups.") }
        if let registration { guard pushProviders.contains(registration.provider) else { throw DeviceAPIError(409, "push_not_supported", "This relay has not advertised a configured Apple push provider. No device token was sent.") } }
        let requested = revision ?? (pushRevision &+ 1)
        guard requested >= pushRevision else { throw CancellationError() }
        pushRevision = requested
        let preceding = pushTail?.task, id = UUID(), run = generation
        let task = Task {
            if let preceding { _ = try? await preceding.value }
            try self.valid(run)
            guard requested == self.pushRevision else { throw CancellationError() }
            let timeout = Task {
                try? await Task.sleep(nanoseconds: 5_000_000_000)
                if !Task.isCancelled, self.generation == run { await self.stop() }
            }
            defer { timeout.cancel() }
            try await self.send(registration?.frame ?? ["type": "push", "token": NSNull()])
        }
        pushTail = (id, task)
        defer { if pushTail?.id == id { pushTail = nil } }
        try await withTaskCancellationHandler(operation: { try await task.value }, onCancel: { task.cancel() })
    }
    func currentPushRevision() -> UInt64 { pushRevision }
    public func cancelActive() { for task in jobs.values { task.cancel() } }
    public func agentAccessChanged(target: String?) async throws {
        if let target { for (id, owner) in jobOwners where owner == target { jobs[id]?.cancel() } }
        if connected { try await announce() }
    }
    public func permissionsChanged() async throws {
        for task in jobs.values { task.cancel() }
        if connected { try await announce() }
    }
    private func valid(_ run: UUID) throws { guard generation == run, !Task.isCancelled else { throw CancellationError() } }
    private func post(_ path: String, _ object: [String: Any], allowedError: String? = nil) async throws -> [String: Any] {
        var request = URLRequest(url: base.appendingPathComponent(path)); request.httpMethod = "POST"; request.timeoutInterval = 30
        request.setValue("application/json", forHTTPHeaderField: "Content-Type"); request.httpBody = try JSONSerialization.data(withJSONObject: object)
        let (data, status) = try await transport.post(request)
        guard data.count <= 65536,
              let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw DeviceAPIError(502, "invalid_relay_response", "The relay returned an invalid response.") }
        guard (200...299).contains(status) || (allowedError != nil && object["error"] as? String == allowedError) else { throw DeviceAPIError(status, object["error"] as? String ?? "relay_error", "The relay rejected the host connection.") }
        return object
    }
    private func announce() async throws {
        let run = generation, publication = try await authority.relayPublication()
        try valid(run)
        try await send(["type": "agents", "agents": publication.agents])
        guard generation == run else { return }
        receiptRefresh?.cancel(); receiptRefresh = nil
        if let expiry = publication.refreshAt {
            let delay = Int64(max(1, min(135_000, Double(expiry) - Date().timeIntervalSince1970 * 1000)))
            receiptRefresh = Task { [weak self] in
                try? await Task.sleep(for: .milliseconds(delay))
                guard !Task.isCancelled else { return }
                await self?.refreshReceipts(run: run)
            }
        }
    }
    private func refreshReceipts(run: UUID) async {
        guard generation == run, connected else { return }
        do { try await announce() }
        catch {
            guard generation == run else { return }
            await stop(); await observe(.failed, "The relay could not refresh viewer credentials.")
        }
    }
    private func send(_ object: [String: Any]) async throws {
        guard connected else { throw CancellationError() }
        let data = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
        try await transport.send(String(decoding: data, as: UTF8.self))
    }
    private func read(run: UUID) async {
        do {
            while !Task.isCancelled {
                let bytes = try await transport.receive(); try valid(run)
                guard bytes.count <= 2 * 1024 * 1024, let object = try JSONSerialization.jsonObject(with: bytes) as? [String: Any] else { throw DeviceAPIError(400, "invalid_frame", "Invalid relay frame.") }
                switch object["type"] as? String {
                case "pong": lastPong = Date()
                case "ping": try await send(["type": "pong"])
                case "ack":
                    if let id = object["id"] as? String, let seq = Self.integer(object["seq"]), let continuation = acknowledgements.removeValue(forKey: "\(id):\(seq)") {
                        if object["error"] is String { continuation.resume(throwing: DeviceAPIError(502, "relay_ack_failed", "The relay rejected a result chunk.")) } else { continuation.resume() }
                    }
                case "cancel": if let id = object["id"] as? String { jobs[id]?.cancel() }
                case "job":
                    guard let id = object["id"] as? String, id.count <= 128, !id.contains(":"), jobs[id] == nil else { continue }
                    if jobs.count >= 32 { try await send(["type": "failure", "id": id, "error": "device_busy"]); continue }
                    do {
                        // Claim in arrival order before starting concurrent work. A later sequence
                        // must never overtake an earlier job during async decryption/storage.
                        let (job, peer, authenticated) = try await prepare(object)
                        let waiting = job.request.isFeedbackWait
                        let ordinaryCount = jobs.count - feedbackWaitIDs.count
                        // Feedback waits have their own per-agent budget. The third
                        // ordinary slot remains reserved for authenticated cancellation.
                        if !waiting && ordinaryCount >= (job.request.isMCPCancellation ? 3 : 2) {
                            try await send(["type": "failure", "id": id, "error": "device_busy"])
                            continue
                        }
                        do { _ = try await authority.admit(job) }
                        catch {
                            await dispatcher.recordAuthenticatedDenial(error, agent: authenticated, job: job)
                            throw error
                        }
                        try valid(run)
                        if waiting { feedbackWaitIDs.insert(id) }
                        jobOwners[id] = job.agentID
                        jobs[id] = Task { await self.handle(job, peer: peer, run: run) }
                    } catch { try await send(["type": "failure", "id": id, "error": "invalid_job"]) }
                default: break
                }
            }
        } catch {
            guard generation == run else { return }
            await stop(); await observe(.failed, "The host disconnected. All active requests were stopped.")
        }
    }
    public static func integer(_ value: Any?) -> Int64? {
        guard let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID(), number.doubleValue.isFinite,
              number.doubleValue.rounded() == number.doubleValue, abs(number.doubleValue) <= 9_007_199_254_740_991 else { return nil }
        return number.int64Value
    }
    private func prepare(_ frame: [String: Any]) async throws -> (HostJob, Data?, HostAgent) {
        let id = frame["id"] as! String
            guard let outerSequence = Self.integer(frame["seq"]), outerSequence > 0, let agentID = frame["agent_id"] as? String,
                  let epoch = frame["generation"] as? String, let device = frame["device_id"] as? String,
                  let deadline = Self.integer(frame["deadline"]), let outerCreated = Self.integer(frame["created_at"]), let mode = frame["mode"] as? String,
                  var payload = frame["payload"] as? [String: Any], let agent = try await authority.agent(agentID), !agent.paused, agent.usesRelay, mode == agent.mode,
                  agent.generation == epoch || mode == "connector" && agent.acceptsReceiptGeneration(epoch, now: Int64(Date().timeIntervalSince1970 * 1000)),
                  device == identity.deviceID else { throw DeviceAPIError(403, "invalid_job", "This job has no authority on this device.") }
            var seq = outerSequence, created = outerCreated
            var sealedID: String?
            let peer = agent.peer.flatMap { Data(base64Encoded: $0) }
            if let peer {
                guard let requestID = payload["request_id"] as? String, requestID.range(of: "^[A-Za-z0-9_-]{8,128}$", options: .regularExpression) != nil, let authenticatedSequence = Self.integer(payload["seq"]), authenticatedSequence > 0, Self.integer(payload["deadline"]) == deadline else { throw DeviceAPIError(403, "invalid_envelope", "The sealed envelope does not match the relay job.") }
                seq = authenticatedSequence; sealedID = requestID
                let aad = RelaySealed.aad(device: device, agent: agentID, generation: epoch, requestID: requestID, deadline: deadline, sequence: seq, direction: "request")
                let opened = try identity.open(peer: peer, envelope: payload, aad: aad)
                guard let object = try JSONSerialization.jsonObject(with: opened) as? [String: Any], let request = object["request"] as? [String: Any], let authenticatedCreated = Self.integer(object["created_at"]) else { throw DeviceAPIError(400, "invalid_request", "The sealed request is invalid.") }; payload = request; created = authenticatedCreated
            }
            guard let method = payload["method"] as? String, let path = payload["path"] as? String,
                  payload["body"] == nil || payload["body"] is [String: Any], payload["query"] == nil || payload["query"] is [String: Any] else { throw DeviceAPIError(400, "invalid_request", "Use a method, path, and object body and query.") }
            var query = payload["query"] as? [String: Any] ?? [:]
            if let headers = payload["headers"] as? [String: Any] {
                let targets = headers.filter { $0.key.lowercased() == "x-bridgit-target" }
                if !targets.isEmpty {
                    guard targets.count == 1, let target = targets.first?.value as? String,
                          query["target"] == nil || query["target"] as? String == target else { throw DeviceAPIError(422, "unsupported_target", "Conflicting or invalid satellite targets.") }
                    query["target"] = target
                }
            }
            let request = HostRequest(method: method, path: path, query: query, body: payload["body"] as? [String: Any] ?? [:], idempotencyKey: payload["idempotency_key"] as? String)
            let job = HostJob(id: id, sequence: seq, agentID: agentID, generation: epoch, deviceID: device, mode: mode, deadline: deadline, createdAt: created, request: request, sealedRequestID: sealedID)
            return (job, peer, agent)
    }
    private func handle(_ job: HostJob, peer: Data?, run: UUID) async {
        let id = job.id, deadline = job.deadline
        defer { jobOwners[id] = nil; jobs[id] = nil; feedbackWaitIDs.remove(id); deadlines[id]?.cancel(); deadlines[id] = nil }
        do {
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            // Authority already checked the route-specific timeout against the authenticated job.
            guard deadline > now else { throw DeviceAPIError(409, "deadline_expired", "The job expired.") }
            deadlines[id] = Task { try? await Task.sleep(nanoseconds: UInt64(deadline - now) * 1_000_000); if !Task.isCancelled { self.jobs[id]?.cancel() } }
            let response = await dispatcher.dispatch(job, admitted: true)
            try valid(run); try Task.checkCancellation()
            _ = try await authority.validateDelivery(job)
            try await response.beforeDelivery?()
            if job.request.path == HostViewerPairing.claimPath, response.status == 200 {
                // Publish both the replacement and the short receipt alias. Dropping
                // the old generation here would make the worker cancel this claim.
                try await announce()
            }
            let delivery = try Self.encodedDelivery(response, identity: identity, peer: peer, job: job)
            let chunks = try Self.resultChunks(delivery.data)
            for (index, chunk) in chunks.enumerated() {
                try valid(run); try Task.checkCancellation(); _ = try await authority.validateDelivery(job)
                try await response.beforeDelivery?()
                try await chunkAndWait(id: id, sequence: index, chunk: chunk, final: index == chunks.count - 1)
            }
            completedJobs += 1
            // A setting may close this connection or cancel active jobs. Deliver its
            // accepted operation first, remove this job, then apply outside its lifetime.
            jobs[id] = nil; deadlines[id]?.cancel(); deadlines[id] = nil
            if let action = response.afterDelivery { Task { await action() } }
            if delivery.deliveredOriginal, let action = response.afterOriginalDelivery { Task { await action() } }
        } catch {
            guard generation == run else { return }
            try? await send(["type": "failure", "id": id, "error": (error as? DeviceAPIError)?.code ?? "cancelled"])
        }
    }
    /// Serialize the actual result for this job, including encryption, before deciding
    /// whether it fits. An oversized result is delivered as an ordinary 413 response;
    /// the dispatched operation is never run a second time.
    static func encodedResult(_ response: HostResponse, identity: RelayHostIdentity, peer: Data?, job: HostJob) throws -> Data {
        try encodedDelivery(response, identity: identity, peer: peer, job: job).data
    }
    static func encodedDelivery(_ response: HostResponse, identity: RelayHostIdentity, peer: Data?, job: HostJob) throws -> (data: Data, deliveredOriginal: Bool) {
        func encode(_ value: HostResponse) throws -> Data {
            var envelope = value.envelope
            if let peer {
                let requestID = job.sealedRequestID ?? job.id
                let aad = RelaySealed.aad(device: job.deviceID, agent: job.agentID, generation: job.generation, requestID: requestID, deadline: job.deadline, sequence: job.sequence, direction: "response")
                envelope = try identity.seal(peer: peer, plaintext: JSONSerialization.data(withJSONObject: envelope, options: [.withoutEscapingSlashes]), aad: aad)
                envelope["request_id"] = requestID; envelope["deadline"] = job.deadline; envelope["seq"] = job.sequence
            }
            return try JSONSerialization.data(withJSONObject: envelope, options: [.withoutEscapingSlashes])
        }
        let data = try encode(response)
        if data.count <= 8 * 1024 * 1024 { return (data, true) }
        let grant = response.activityOverride?.operation ?? job.request.route?.0
        let message = grant == .feedback || grant == .feedbackFixer
            ? "The report exceeds the relay transport limit. Ask with size=thumb."
            : "The result exceeds the relay transport limit. Read paginated data instead."
        let error = HostResponse.failure(DeviceAPIError(413, "result_too_large", message)).withHostAPIRevision()
        let failure = try encode(error)
        guard failure.count <= 8 * 1024 * 1024 else { throw DeviceAPIError(413, "result_too_large", "The host result exceeds 8 MB.") }
        return (failure, false)
    }
    public static func resultChunks(_ data: Data) throws -> [String] {
        guard data.count <= 8 * 1024 * 1024, String(data: data, encoding: .utf8) != nil else { throw DeviceAPIError(413, "result_too_large", "Use a UTF-8 result no larger than 8 MB.") }
        let bytes = Array(data); var offset = 0; var chunks: [String] = []
        while offset < bytes.count {
            var end = min(bytes.count, offset + 32768)
            while end < bytes.count, bytes[end] & 0xc0 == 0x80 { end -= 1 }
            chunks.append(String(decoding: bytes[offset..<end], as: UTF8.self)); offset = end
        }
        return chunks
    }
    private func chunkAndWait(id: String, sequence: Int, chunk: String, final: Bool) async throws {
        let key = "\(id):\(sequence)"
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                acknowledgements[key] = continuation
                Task {
                    do { try await self.send(["type": "result", "id": id, "seq": sequence, "data": chunk, "final": final]) }
                    catch { self.finishAck(key, error: error) }
                }
                Task { try? await Task.sleep(nanoseconds: 15_000_000_000); self.finishAck(key, error: DeviceAPIError(504, "ack_timeout", "The relay did not acknowledge this result.")) }
            }
        } onCancel: { Task { await self.finishAck(key, error: CancellationError()) } }
    }
    private func finishAck(_ key: String, error: Error) { acknowledgements.removeValue(forKey: key)?.resume(throwing: error) }
}
