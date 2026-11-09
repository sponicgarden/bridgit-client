import Foundation
import Network

struct TailnetHTTPRequest {
    var method: String
    var target: String
    var headers: [String: String]
    var body: Data
    var version = "HTTP/1.1"
    var hostRequest: HostRequest {
        get throws {
            guard let components = URLComponents(string: "http://tailnet.invalid" + target), components.percentEncodedPath == components.path else { throw DeviceAPIError(400, "invalid_request", "Use a canonical API path.") }
            var query: [String: Any] = [:]
            for item in components.queryItems ?? [] {
                guard query[item.name] == nil else { throw DeviceAPIError(400, "invalid_request", "Repeated query fields are not accepted.") }
                query[item.name] = item.value ?? ""
            }
            if let target = headers["x-bridgit-target"] {
                guard query["target"] == nil || query["target"] as? String == target else { throw DeviceAPIError(422, "unsupported_target", "Conflicting satellite targets.") }
                query["target"] = target
            }
            let object: [String: Any]
            if body.isEmpty { object = [:] }
            else {
                guard let value = try JSONSerialization.jsonObject(with: body) as? [String: Any] else { throw DeviceAPIError(400, "invalid_json", "Use a JSON object.") }
                object = value
            }
            var request = HostRequest(method: method, path: components.path, query: query, body: object,
                                      idempotencyKey: headers["idempotency-key"])
            request.bodyBytesMeasured = body.count
            return request
        }
    }
}

struct TailnetHTTPObservation {
    let method: String
    let target: String
}

/// One bounded HTTP request per connection; rejects ambiguous framing and pipelining.
enum TailnetHTTPCodec {
    static let headerLimit = 16 * 1024
    static let bodyLimit = 6 * 1024 * 1024
    static func parse(_ bytes: Data) throws -> TailnetHTTPRequest? {
        var observation: TailnetHTTPObservation?
        return try parse(bytes, observation: &observation)
    }

    static func parse(_ bytes: Data, observation: inout TailnetHTTPObservation?) throws -> TailnetHTTPRequest? {
        guard let end = bytes.range(of: Data("\r\n\r\n".utf8)) else {
            guard bytes.count <= headerLimit else { throw invalid(431) }; return nil
        }
        guard end.upperBound <= headerLimit else { throw invalid(431) }
        let headerBytes = bytes[..<end.lowerBound]
        guard headerBytes.allSatisfy({ $0 == 9 || $0 == 10 || $0 == 13 || (32...126).contains($0) }), let header = String(data: headerBytes, encoding: .ascii) else { throw invalid() }
        let lines = header.components(separatedBy: "\r\n")
        let first = (lines.first ?? "").split(separator: " ", omittingEmptySubsequences: false)
        guard first.count == 3, !first[0].isEmpty, ["HTTP/1.1", "HTTP/1.0"].contains(first[2]),
              first[1].hasPrefix("/"), !first[1].hasPrefix("//"), !first[1].contains("#"), !first[1].contains("\\") else { throw invalid() }
        let method = String(first[0])
        let supported = ["GET", "POST", "PATCH", "DELETE"].contains(method)
        let standard = ["HEAD", "OPTIONS", "PUT", "CONNECT", "TRACE"].contains(method)
        observation = .init(method: supported || standard ? method : "(unsupported method)", target: String(first[1]))
        guard supported else { throw invalid() }
        var fields: [String: String] = [:]
        for line in lines.dropFirst() {
            guard let colon = line.firstIndex(of: ":"), !line.hasPrefix(" "), !line.hasPrefix("\t") else { throw invalid() }
            let name = String(line[..<colon]).lowercased()
            guard !name.isEmpty, name.utf8.allSatisfy({ (97...122).contains($0) || (48...57).contains($0) || $0 == 45 }), fields[name] == nil else { throw invalid() }
            let value = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
            guard !value.contains("\r"), !value.contains("\n") else { throw invalid() }
            fields[name] = value
        }
        guard first[2] != "HTTP/1.1" || fields["host"]?.isEmpty == false,
              fields["transfer-encoding"] == nil, fields["expect"] == nil else { throw invalid() }
        let length: Int
        if let raw = fields["content-length"] {
            guard !raw.isEmpty, raw.utf8.allSatisfy({ (48...57).contains($0) }), let value = Int(raw), value <= bodyLimit else { throw invalid(413) }
            length = value
        } else { length = 0 }
        guard length == 0 || fields["content-type"]?.lowercased().split(separator: ";").first?.trimmingCharacters(in: .whitespaces) == "application/json" else { throw invalid(415) }
        let remaining = bytes.count - end.upperBound
        guard remaining <= length else { throw invalid() }
        guard remaining == length else { return nil }
        return TailnetHTTPRequest(method: String(first[0]), target: String(first[1]), headers: fields, body: Data(bytes[end.upperBound...]), version: String(first[2]))
    }
    static func encode(_ response: HostResponse, streaming: Bool = false) -> Data {
        let framing = streaming ? "Transfer-Encoding: chunked" : "Content-Length: " + String(response.data.count)
        var result = Data("HTTP/1.1 \(response.status) Bridgit\r\nContent-Type: \(response.contentType)\r\n\(framing)\r\nConnection: close\r\nCache-Control: no-store\r\n".utf8)
        let excluded: Set<String> = ["content-type", "content-length", "connection", "transfer-encoding", "cache-control"]
        for (key, value) in response.headers.sorted(by: { $0.key < $1.key }) where !excluded.contains(key.lowercased()) {
            if !key.contains("\r"), !key.contains("\n"), !value.contains("\r"), !value.contains("\n") { result.append(Data((key + ": " + value + "\r\n").utf8)) }
        }
        result.append(Data("\r\n".utf8)); if !streaming { result.append(response.data) }
        return result
    }
    static func invalid(_ status: Int = 400) -> DeviceAPIError { .init(status, "invalid_request", "Use one bounded JSON API request with unambiguous HTTP framing.") }
}

/// An explicit, address-bound listener. Public construction accepts numeric Tailscale
/// local and peer addresses only; it never listens on wildcard/Wi-Fi interfaces.
actor TailnetHTTPListener {
    typealias Handler = (TailnetHTTPRequest, String) async -> HostResponse
    typealias Rejection = (TailnetHTTPObservation, DeviceAPIError, String, Date, ContinuousClock.Instant) async -> Void
    private let handler: Handler
    private let rejection: Rejection?
    private let decorateFailure: (HostResponse) -> HostResponse
    private let fixtureLoopback: Bool
    private let unavailable: (() async -> Void)?
    private let responseTimeoutSeconds: Int
    private let cancelOnPeerEOF: Bool
    private(set) var isReady = false
    private let queue = DispatchQueue(label: "cc.bridgit.tailnet-http")
    private var listener: NWListener?
    private var ready: CheckedContinuation<UInt16, Error>?
    private var generation = UUID()
    private var clients: [UUID: (NWConnection, Task<Void, Never>)] = [:]
    init(handler: @escaping Handler, rejection: Rejection? = nil, decorateFailure: @escaping (HostResponse) -> HostResponse = { $0 }, unavailable: (() async -> Void)? = nil, responseTimeoutSeconds: Int = 65, cancelOnPeerEOF: Bool = false) {
        self.handler = handler; self.rejection = rejection; self.decorateFailure = decorateFailure; self.unavailable = unavailable; self.responseTimeoutSeconds = responseTimeoutSeconds
        self.cancelOnPeerEOF = cancelOnPeerEOF
        fixtureLoopback = false
    }
    /// An internal transport fixture, never selected by app preferences or runtime detection.
    init(fixtureLoopbackHandler: @escaping Handler, rejection: Rejection? = nil, decorateFailure: @escaping (HostResponse) -> HostResponse = { $0 }, cancelOnPeerEOF: Bool = false) {
        handler = fixtureLoopbackHandler; self.rejection = rejection; self.decorateFailure = decorateFailure; unavailable = nil; responseTimeoutSeconds = 65; fixtureLoopback = true
        self.cancelOnPeerEOF = cancelOnPeerEOF
    }
    private func permitted(_ address: String) -> Bool { fixtureLoopback ? address == "127.0.0.1" : TailnetAddress(address) != nil }
    func start(address: String, port: UInt16 = 18471) async throws -> UInt16 {
        guard permitted(address), port > 0 || fixtureLoopback else { throw DeviceAPIError(422, "tailnet_required", "Choose this device’s numeric Tailscale address and a valid port.") }
        try Task.checkCancellation()
        stop()
        let run = UUID(); generation = run
        let parameters = NWParameters(tls: nil, tcp: NWProtocolTCP.Options())
        parameters.requiredLocalEndpoint = .hostPort(host: .init(address), port: .init(rawValue: port)!)
        parameters.allowLocalEndpointReuse = false
        // The required local endpoint already carries the port. Passing it again with
        // `on:` makes Network.framework reject a non-zero port with POSIX EINVAL (A0129).
        let listener: NWListener
        do { listener = try NWListener(using: parameters) }
        catch { throw DeviceAPIError(503, "listener_unavailable", "Could not listen on " + address + ". Check Tailscale and try again.") }
        self.listener = listener
        listener.stateUpdateHandler = { [weak self] state in Task { await self?.changed(state, run: run) } }
        listener.newConnectionHandler = { [weak self] connection in Task { await self?.accept(connection, run: run) } }
        let startupDeadline = Task {
            try? await Task.sleep(for: .seconds(12))
            if !Task.isCancelled, generation == run, ready != nil {
                fail(DeviceAPIError(503, "listener_timeout", "Tailscale listening did not become ready. Check local network access and try again."))
            }
        }
        defer { startupDeadline.cancel() }
        return try await withTaskCancellationHandler(operation: {
            try await withCheckedThrowingContinuation { continuation in
                ready = continuation; listener.start(queue: queue)
            }
        }, onCancel: { Task { await self.stop() } })
    }
    private func changed(_ state: NWListener.State, run: UUID) {
        guard generation == run else { return }
        switch state {
        case .ready:
            guard let port = listener?.port?.rawValue else { fail(DeviceAPIError(503, "listener_unavailable", "No bound port was returned.")); return }
            isReady = true; ready?.resume(returning: port); ready = nil
        case .waiting where isReady, .failed:
            fail(DeviceAPIError(503, "listener_unavailable", "Could not keep the selected Tailscale address bound. Check Tailscale and the port."))
        case .cancelled: fail(CancellationError())
        default: break
        }
    }
    private func fail(_ error: Error) {
        let wasReady = isReady
        ready?.resume(throwing: error); ready = nil; stop()
        if wasReady, let unavailable { Task { await unavailable() } }
    }
    func stop() {
        generation = UUID(); isReady = false; listener?.cancel(); listener = nil
        ready?.resume(throwing: CancellationError()); ready = nil
        for (connection, task) in clients.values { task.cancel(); connection.cancel() }
        clients.removeAll()
    }
    private func accept(_ connection: NWConnection, run: UUID) {
        guard generation == run, clients.count < 8, case .hostPort(let host, _) = connection.endpoint, permitted(String(describing: host)) else { connection.cancel(); return }
        let peer = TailnetAddress(String(describing: host))?.value ?? String(describing: host)
        let id = UUID()
        let task = Task { await self.serve(connection, peer: peer, id: id, run: run) }
        clients[id] = (connection, task); connection.start(queue: queue)
    }
    private func serve(_ connection: NWConnection, peer: String, id: UUID, run: UUID) async {
        defer { clients[id] = nil; connection.cancel() }
        let timeout = Task { try? await Task.sleep(nanoseconds: 5_000_000_000); if !Task.isCancelled { connection.cancel() } }
        defer { timeout.cancel() }
        let at = Date(), started = ContinuousClock.now
        var observation: TailnetHTTPObservation?
        do {
            var bytes = Data(), parsed: TailnetHTTPRequest?
            repeat {
                let part = try await receive(connection)
                guard !part.isEmpty else { throw CancellationError() }
                bytes.append(part)
                guard bytes.count <= TailnetHTTPCodec.headerLimit + TailnetHTTPCodec.bodyLimit else { throw TailnetHTTPCodec.invalid(413) }
                parsed = try TailnetHTTPCodec.parse(bytes, observation: &observation)
            } while parsed == nil
            observation = nil
            timeout.cancel()
            // Owner requests are one HTTP request per connection. Once framed,
            // further input or a closed peer cancels any pending owner action.
            let peerWatch = cancelOnPeerEOF ? Task {
                _ = try? await receive(connection)
                guard !Task.isCancelled else { return }
                clients[id]?.1.cancel()
            } : nil
            defer { peerWatch?.cancel() }
            let responseTimeout = Task { try? await Task.sleep(for: .seconds(responseTimeoutSeconds)); if !Task.isCancelled { connection.cancel() } }
            defer { responseTimeout.cancel() }
            let response = await handler(parsed!, peer)
            guard generation == run, !Task.isCancelled else {
                await response.streamCompletion?(0, CancellationError())
                return
            }
            if let stream = response.stream {
                if parsed?.version != "HTTP/1.1" {
                    let error = DeviceAPIError(505, "http_version", "Streaming export requires HTTP/1.1.")
                    await response.streamCompletion?(0, error)
                    try await send(connection, data: TailnetHTTPCodec.encode(decorateFailure(.failure(error))))
                    return
                }
                do { try await response.beforeDelivery?() }
                catch {
                    await response.streamCompletion?(0, error)
                    try await send(connection, data: TailnetHTTPCodec.encode(decorateFailure(.failure(error))))
                    return
                }
                responseTimeout.cancel()
                var sent = 0
                do {
                    try await sendStreamFrame(connection, data: TailnetHTTPCodec.encode(response, streaming: true))
                    try await stream { chunk in
                        try Task.checkCancellation()
                        var frame = Data((String(chunk.count, radix: 16) + "\r\n").utf8)
                        frame.append(chunk)
                        frame.append(Data("\r\n".utf8))
                        try await self.sendStreamFrame(connection, data: frame)
                        sent += chunk.count
                    }
                    try await response.beforeDelivery?()
                    try await sendStreamFrame(connection, data: Data("0\r\n\r\n".utf8))
                    await response.streamCompletion?(sent, nil)
                } catch {
                    await response.streamCompletion?(sent, error)
                    return
                }
            } else {
                try await response.beforeDelivery?()
                try await send(connection, data: TailnetHTTPCodec.encode(response))
            }
            if let action = response.afterOriginalDelivery { await action() }
            if let action = response.afterDelivery {
                if cancelOnPeerEOF {
                    // The acknowledged owner setting may close or rebind this
                    // exact listener. Its authority is checked by the action.
                    Task { await action() }
                } else { await action() }
            }
        } catch {
            if generation == run, !Task.isCancelled {
                if let observation, let api = error as? DeviceAPIError {
                    await rejection?(observation, api, peer, at, started)
                }
                try? await send(connection, data: TailnetHTTPCodec.encode(decorateFailure(.failure(error))))
            }
        }
    }
    private func receive(_ connection: NWConnection) async throws -> Data {
        try await withTaskCancellationHandler(operation: {
            try await withCheckedThrowingContinuation { continuation in
                connection.receive(minimumIncompleteLength: 1, maximumLength: 65536) { data, _, complete, error in
                    if let error { continuation.resume(throwing: error) }
                    else if let data, !data.isEmpty { continuation.resume(returning: data) }
                    else if complete { continuation.resume(returning: Data()) }
                    else { continuation.resume(throwing: URLError(.networkConnectionLost)) }
                }
            }
        }, onCancel: { connection.cancel() })
    }
    private func sendStreamFrame(_ connection: NWConnection, data: Data) async throws {
        let idle = Task {
            try? await Task.sleep(for: .seconds(65))
            if !Task.isCancelled { connection.cancel() }
        }
        defer { idle.cancel() }
        try await send(connection, data: data)
    }
    private func send(_ connection: NWConnection, data: Data) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            connection.send(content: data, completion: .contentProcessed { error in
                if let error { continuation.resume(throwing: error) } else { continuation.resume() }
            })
        }
    }
}
