import CryptoKit
import Foundation

struct ConnectionDelivery: Codable, Sendable, Identifiable {
    enum State: String, Codable { case queued, sending, accepted, failed, cancelled }
    var id: String
    var destination: String
    var agentID: String
    var generation: String
    var mode: String
    var sourcePeer: TailnetAddress? = nil
    var idempotency: String
    var fingerprint: String
    var text: String
    var payload: Data
    var photo: Data?
    var photoApplet: String?
    var created: TimeInterval
    var next: TimeInterval
    // Absent in older receipts, whose next value was only a retry deadline.
    var finishedAt: TimeInterval?
    var legacyRetentionStartedAt: TimeInterval?
    var state: State = .queued
    var attempts = 0
    var lease: String?
    var leaseUntil: TimeInterval = 0
    var http: Int?
    var writing = false
    var uncertain = false
    var error: String?
    var owner: String { agentID + ":" + generation }
    var pending: Bool { state == .queued || state == .sending }
    var summary: [String: Any] {
        var value: [String: Any] = ["id": id, "to": destination, "state": state.rawValue, "attempts": attempts,
            "created_at": created * 1000, "queued": pending]
        if pending { value["next_attempt_at"] = next * 1000 }
        else if let finishedAt { value["finished_at"] = finishedAt * 1000 }
        if let http { value["status"] = http }
        if uncertain { value["may_have_arrived"] = true }
        if let error { value["error"] = error }
        return value
    }
    static func digest(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
    func checked() throws -> Self {
        guard [id, destination, agentID, generation].allSatisfy({ UUID(uuidString: $0) != nil }),
              (mode == OwnerDirectHostService.mode && sourcePeer != nil
               || ["connector", "sealed"].contains(mode) && sourcePeer == nil),
              (0...6).contains(attempts),
              [created, next, leaseUntil].allSatisfy({ $0.isFinite && $0 >= 0 }),
              [finishedAt, legacyRetentionStartedAt].compactMap { $0 }.allSatisfy({ !pending && $0.isFinite && $0 >= 0 }),
              (!pending || !payload.isEmpty),
              (photo == nil && photoApplet == nil || pending && photo != nil && photoApplet?.isEmpty == false),
              photo.map { !$0.isEmpty && $0.count <= OutboundConnectionService.photoMaxBytes } ?? true,
              photoApplet.map { $0.range(of: "^[a-z][a-z0-9-]{0,63}$", options: .regularExpression) != nil } ?? true,
              (state != .sending || lease != nil && leaseUntil > 0 && attempts > 0),
              (pending || payload.isEmpty && text.isEmpty),
              (state != .accepted || http.map { (200...299).contains($0) } == true && attempts > 0),
              (state != .queued || attempts < 6),
              text.count <= 1000, text.utf8.count <= 16 * 1024, payload.count <= 17 * 1024,
              [idempotency, fingerprint].allSatisfy({ $0.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil }),
              lease == nil || UUID(uuidString: lease!) != nil,
              error == nil || error!.range(of: "^[a-z0-9_]{1,64}$", options: .regularExpression) != nil,
              http == nil || (100...599).contains(http!) else {
            throw DeviceAPIError(500, "connection_queue_invalid", "Saved connection delivery could not be read.")
        }
        return self
    }
    mutating func finish(_ value: State, error: String? = nil, http: Int? = nil, now: Date = Date()) {
        state = value; self.error = error; self.http = http; lease = nil; leaseUntil = 0
        finishedAt = pending ? nil : now.timeIntervalSince1970
        if !pending { next = now.timeIntervalSince1970; payload = Data(); text = ""; photo = nil; photoApplet = nil }
    }
}

extension OutboundConnectionService {
    public typealias QueuePhotoAuthorizer = @Sendable (_ agentID: String, _ generation: String, _ mode: String, _ sourcePeer: TailnetAddress?, _ applet: String) async throws -> Void
    public typealias QueueAuthorizer = @Sendable (_ agentID: String, _ generation: String, _ mode: String, _ sourcePeer: TailnetAddress?) async throws -> Void

    public func enqueue(to id: String, text: String, data: [String: Any], agent: HostAgent, phone: String, idempotencyKey: String, photo: ConnectionSendPhoto? = nil, authorize: @escaping () async throws -> Void) async throws -> [String: Any] {
        try Self.validateMessage(text: text, data: data)
        guard !idempotencyKey.isEmpty, idempotencyKey.count <= 128 else { throw DeviceAPIError(422, "idempotency_key_required", "Use an Idempotency-Key for a queued send.") }
        let key = ConnectionDelivery.digest(Data(idempotencyKey.utf8))
        var intentFields: [String: Any] = ["to": id, "text": text, "data": data]
        if let photo { intentFields["photo"] = ConnectionDelivery.digest(photo.jpeg); intentFields["photo_applet"] = photo.applet }
        let intent = try JSONSerialization.data(withJSONObject: intentFields, options: [.sortedKeys])
        let fingerprint = ConnectionDelivery.digest(intent)
        try await authorize(); try Task.checkCancellation()
        if let prior = try await store.queuedReceipt(owner: agent.eventOwner, key: key) {
            guard prior.fingerprint == fingerprint else { throw DeviceAPIError(409, "idempotency_conflict", "This key queued a different send.") }
            return ["queued": true, "delivery_id": prior.id]
        }
        guard let connection = try await store.ownerConnections().first(where: { $0.id == id && $0.enabled }) else { throw DeviceAPIError(404, "not_found", "There is no enabled connection with that ID.") }
        guard !connection.preset.needsText || !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !data.isEmpty else { throw DeviceAPIError(422, "invalid_parameter", "This service needs message text or data.") }
        let now = Date(), timestamp = ISO8601DateFormatter(); timestamp.timeZone = .current
        var fields = data
        fields["applet"] = agent.name; fields["flow"] = ""; fields["run_id"] = ""
        fields["sent_at"] = timestamp.string(from: now); fields["phone"] = await deviceName?() ?? phone
        let payload = try JSONSerialization.data(withJSONObject: fields, options: [.sortedKeys])
        guard payload.count <= 17 * 1024 else { throw DeviceAPIError(422, "invalid_parameter", "The complete delivery payload is too large.") }
        let row = ConnectionDelivery(id: UUID().uuidString, destination: id, agentID: agent.id, generation: agent.generation,
            mode: agent.mode, sourcePeer: agent.ownerDirectRequestPeer, idempotency: key, fingerprint: fingerprint, text: text, payload: payload, photo: photo?.jpeg, photoApplet: photo?.applet,
            created: now.timeIntervalSince1970, next: now.timeIntervalSince1970)
        // Validate native provider formatting before reserving quota or accepting durable work.
        _ = try Self.request(connection, text: text, data: fields, id: row.id, now: now, photo: row.photo, waitReply: false)
        try await authorize(); try Task.checkCancellation()
        let saved = try await store.enqueueDelivery(row, connection: connection, now: now)
        return ["queued": true, "delivery_id": saved.id]
    }
    public func deliveries(for agent: HostAgent, before: String? = nil) async throws -> [String: Any] {
        let page = try await store.deliveryPage(owner: agent.eventOwner, before: before)
        return ["deliveries": page.rows.map(\.summary), "next": page.next.map { $0 as Any } ?? NSNull()]
    }
    public func delivery(_ id: String, for agent: HostAgent) async throws -> [String: Any] {
        try await store.queueDelivery(id, owner: agent.eventOwner).summary
    }
    public func cancelDelivery(_ id: String, for agent: HostAgent, authorize: @escaping () async throws -> Void) async throws -> [String: Any] {
        try await authorize(); try Task.checkCancellation()
        return try await store.cancelDelivery(id, owner: agent.eventOwner, requestedBy: agent).summary
    }
    public func startQueue(authorize: @escaping QueueAuthorizer, authorizePhoto: QueuePhotoAuthorizer? = nil,
                           eligibleModes: (@Sendable () async -> Set<String>)? = nil) {
        guard queueWorker == nil else { return }
        let epoch = queueEpoch
        queueWorker = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                do {
                    try await self.requireQueueEpoch(epoch)
                    _ = try await self.processQueue(now: Date(), authorize: authorize, authorizePhoto: authorizePhoto,
                                                    eligibleModes: await eligibleModes?())
                } catch is CancellationError { return }
                catch { /* A malformed store never becomes an empty/successful queue. Reads retain the error. */ }
                try? await Task.sleep(for: .seconds(1))
            }
        }
    }
    public func stopQueue() {
        queueEpoch = UUID(); queueWorker?.cancel(); queueWorker = nil
    }
    private func requireQueueEpoch(_ epoch: UUID) throws {
        try Task.checkCancellation()
        guard epoch == queueEpoch else { throw CancellationError() }
    }
    /// One deterministic due attempt; injected authority/transport allow disposable acceptance fixtures.
    @discardableResult public func processQueue(now: Date = Date(), authorize: @escaping QueueAuthorizer,
        authorizePhoto: QueuePhotoAuthorizer? = nil, eligibleModes: Set<String>? = nil) async throws -> Bool {
        guard !drainingQueue, !outboundAccess.isPaused else { return false }; drainingQueue = true
        defer { drainingQueue = false }
        let epoch = queueEpoch, started = ContinuousClock.now
        func attemptDate() -> Date {
            let elapsed = started.duration(to: .now).components
            return now.addingTimeInterval(Double(elapsed.seconds) + Double(elapsed.attoseconds) / 1e18)
        }
        guard let pending = try await store.nextDelivery(now: now, eligibleModes: eligibleModes) else { return false }
        func checkPhoto(_ item: ConnectionDelivery) async throws {
            if let applet = item.photoApplet {
                guard let authorizePhoto else { throw DeviceAPIError(403, "agent_not_allowed", "The queued photo's media authority cannot be checked.") }
                try await authorizePhoto(item.agentID, item.generation, item.mode, item.sourcePeer, applet)
            }
        }
        do { try await authorize(pending.agentID, pending.generation, pending.mode, pending.sourcePeer); try await checkPhoto(pending) }
        catch let error as DeviceAPIError where ["agent_revoked", "agent_not_allowed"].contains(error.code) {
            try await store.finishDelivery(pending, state: .cancelled, error: error.code, next: attemptDate()); return true
        } catch let error as DeviceAPIError where error.code == "foreground_required" { return false }
        try requireQueueEpoch(epoch)
        guard let connection = try await store.ownerConnections().first(where: { $0.id == pending.destination && $0.enabled }) else {
            try await store.finishDelivery(pending, state: .cancelled, error: "destination_disabled", next: attemptDate()); return true
        }
        guard !outboundAccess.isPaused else { return false }
        let lease = UUID().uuidString
        let item = try await store.claimDelivery(pending.id, lease: lease, now: attemptDate())
        do {
            guard let fields = try JSONSerialization.jsonObject(with: item.payload) as? [String: Any], fields.count <= 37,
                  fields.values.allSatisfy({ $0 is String || $0 is NSNumber || $0 is NSNull }) else {
                throw DeviceAPIError(500, "connection_queue_invalid", "Saved delivery payload could not be read.")
            }
            let formatted = try Self.request(connection, text: item.text, data: fields, id: item.id, now: attemptDate(), photo: item.photo, waitReply: false)
            let request = ConnectionHTTPRequest(address: formatted.address, method: formatted.method, allowPrivate: formatted.allowPrivate, headers: formatted.headers, body: formatted.body, timeout: 30)
            let reply = try await transport(request) { [weak self, store] in
                guard let self else { throw CancellationError() }
                try await self.requireQueueEpoch(epoch)
                try await authorize(item.agentID, item.generation, item.mode, item.sourcePeer)
                try await checkPhoto(item)
                try await store.requireCurrentConnection(connection)
                try await store.markDeliveryWriting(item.id, lease: lease)
            }
            try requireQueueEpoch(epoch)
            try await authorize(item.agentID, item.generation, item.mode, item.sourcePeer)
            try await checkPhoto(item)
            guard (200...299).contains(reply.status) else { throw DeviceAPIError(502, "upstream_failed", "The connection did not accept the queued send.") }
            try await store.finishDelivery(item, state: .accepted, http: reply.status, next: attemptDate())
            try? await store.recordConnectionOutcome(connection, failing: false)
        } catch let error as DeviceAPIError where error.code == "external_paused" {
            try await store.deferUnsentDelivery(item)
            return false
        } catch {
            let rawCode = (error as? DeviceAPIError)?.code ?? (error is CancellationError ? "delivery_unconfirmed" : "transport_failed")
            let known: Set<String> = ["agent_revoked", "agent_not_allowed", "foreground_required", "delivery_cancelled", "delivery_unconfirmed", "connection_queue_invalid", "connection_changed", "connection_response_invalid", "connection_timeout", "connection_dns_failed", "connection_address_blocked", "connection_failed", "connection_unavailable", "connection_request_too_large", "connection_secret_invalid", "upstream_failed"]
            let code = known.contains(rawCode) ? rawCode : "transport_failed"
            let cancelled = ["agent_revoked", "agent_not_allowed", "delivery_cancelled"].contains(code)
            let terminal = cancelled || item.attempts >= 6 || code == "connection_queue_invalid"
            let delay: TimeInterval = item.attempts == 1 ? 2 : item.attempts == 2 ? 10 : item.attempts == 3 ? 30 : 300
            let completed = attemptDate()
            try await store.finishDelivery(item, state: cancelled ? .cancelled : terminal ? .failed : .queued,
                error: code, next: terminal ? completed : completed.addingTimeInterval(delay))
            if terminal && !cancelled && !["connection_queue_invalid", "foreground_required", "delivery_unconfirmed"].contains(code) { try? await store.recordConnectionOutcome(connection, failing: true) }
            if error is CancellationError { throw CancellationError() }
        }
        return true
    }
}
