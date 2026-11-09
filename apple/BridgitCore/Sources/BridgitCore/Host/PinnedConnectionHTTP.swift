import Darwin
import Foundation
import Network
import Security

public enum ConnectionAddress {
    public enum Scope: Sendable { case blocked, ownerNetwork, internet }
    public static func scope(_ address: String) -> Scope {
        let literal = address.trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
        if let v4 = IPv4Address(literal) { return scopeV4(Array(v4.rawValue)) }
        guard let v6 = IPv6Address(literal) else { return .blocked }
        let b = Array(v6.rawValue)
        if b.prefix(10).allSatisfy({ $0 == 0 }), b[10] == 255, b[11] == 255 { return scopeV4(Array(b.suffix(4))) }
        if b[0] & 0xfe == 0xfc { return .ownerNetwork }
        if b[0] == 0x00, b[1] == 0x64, b[2] == 0xff, b[3] == 0x9b, b[4..<12].allSatisfy({ $0 == 0 }) {
            return scopeV4(Array(b.suffix(4)))
        }
        if b[0] == 0x20, b[1] == 0x02 { return .blocked }
        // IANA special protocol/benchmark/ORCHID assignments and documentation ranges.
        // Conservative: protocol anycast/transition endpoints are not webhook destinations.
        if b[0] == 0x20, b[1] == 0x01, (b[2] & 0xfe == 0 || b[2] == 0x0d && b[3] == 0xb8) { return .blocked }
        if b[0] == 0x3f, b[1] == 0xff, b[2] & 0xf0 == 0 { return .blocked }
        return b[0] & 0xe0 == 0x20 ? .internet : .blocked
    }
    private static func scopeV4(_ b: [UInt8]) -> Scope {
        let x = b[0], y = b[1], z = b[2]
        if x == 10 || x == 172 && y & 0xf0 == 16 || x == 192 && y == 168 || x == 100 && y & 0xc0 == 64 { return .ownerNetwork }
        if x == 0 || x == 127 || x == 169 && y == 254 || x >= 224 ||
            x == 192 && y == 0 && (z == 0 || z == 2) || x == 192 && y == 88 && z == 99 || x == 198 && y & 0xfe == 18 ||
            x == 198 && y == 51 && z == 100 || x == 203 && y == 0 && z == 113 { return .blocked }
        return .internet
    }
    public static func privateName(_ host: String) -> Bool {
        let host = host.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
        return scope(host) == .ownerNetwork || [".local", ".lan", ".home.arpa", ".ts.net"].contains(where: host.hasSuffix)
    }
    public static func check(_ address: String, allowPrivate: Bool) throws -> URLComponents {
        guard address.count <= 2048, !address.unicodeScalars.contains(where: { $0.value < 32 || $0.value == 127 }),
              let parts = URLComponents(string: address), let host = parts.host, !host.isEmpty,
              host.range(of: "^[A-Za-z0-9._:\\[\\]-]+$", options: .regularExpression) != nil,
              parts.user == nil, parts.password == nil, parts.fragment == nil,
              ["https", "http"].contains(parts.scheme?.lowercased() ?? ""),
              parts.port == nil || (1...65535).contains(parts.port!) else {
            throw DeviceAPIError(422, "invalid_connection", "Use a web address without a username, password or #fragment.")
        }
        if parts.scheme?.lowercased() == "http" {
            guard allowPrivate, privateName(host) else { throw DeviceAPIError(422, "invalid_connection", "Plain HTTP needs an approved private-network address or name.") }
        } else if !allowPrivate, let port = parts.port, port != 443 {
            throw DeviceAPIError(422, "invalid_connection", "Use HTTPS port 443, or approve your private network for another port.")
        }
        return parts
    }
}

/// Resolve once, validate every answer, and connect to that numeric address.
/// TLS still validates the original hostname. No redirect or proxy can move the send.
enum PinnedConnectionHTTP {
    static func send(_ request: ConnectionHTTPRequest, outboundAccess: OutboundAccess = .unrestricted,
                     authorize: @escaping () async throws -> Void) async throws -> ConnectionHTTPResponse {
        let check = { if request.callbackDestination != .tailnet { try outboundAccess.require() } }
        try check()
        guard request.timeout.isFinite, (1...30).contains(request.timeout) else { throw DeviceAPIError(422, "invalid_parameter", "The connection deadline is invalid.") }
        guard ["POST", "PUT"].contains(request.method), request.body.count <= 1_400_000 else { throw DeviceAPIError(422, "connection_request_too_large", "The connection request is too large or uses an unsupported method.") }
        let parts = try ConnectionAddress.check(request.address, allowPrivate: request.allowPrivate)
        if let destination = request.callbackDestination { try CallbackRules.validateSendURL(request.address, destination: destination) }
        let operation = ConnectionHTTPTransaction(request: request, parts: parts, checkOutbound: check, authorize: authorize)
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { operation.start($0) }
        } onCancel: { operation.cancel() }
    }
}

private final class ConnectionHTTPTransaction: @unchecked Sendable {
    private let request: ConnectionHTTPRequest
    private let parts: URLComponents
    private let checkOutbound: () throws -> Void
    private let authorize: () async throws -> Void
    private let queue = DispatchQueue(label: "cc.bridgit.connection.http")
    private var continuation: CheckedContinuation<ConnectionHTTPResponse, Error>?
    private var finished = false
    private var connection: NWConnection?
    private var timeout: DispatchWorkItem?
    private var authorization: Task<Void, Never>?
    private var parser = ConnectionHTTPReplyParser()
    init(request: ConnectionHTTPRequest, parts: URLComponents, checkOutbound: @escaping () throws -> Void, authorize: @escaping () async throws -> Void) {
        self.request = request; self.parts = parts; self.checkOutbound = checkOutbound; self.authorize = authorize
    }
    func start(_ continuation: CheckedContinuation<ConnectionHTTPResponse, Error>) {
        queue.async {
            guard !self.finished else { continuation.resume(throwing: CancellationError()); return }
            self.continuation = continuation
            do { try self.checkOutbound() } catch { self.finish(.failure(error)); return }
            let seconds = Int(self.request.timeout)
            let timeout = DispatchWorkItem { [weak self] in self?.finish(.failure(DeviceAPIError(504, "connection_timeout", "The connection did not answer within \(seconds) seconds. It may have received the send; do not retry automatically."))) }
            self.timeout = timeout; self.queue.asyncAfter(deadline: .now() + self.request.timeout, execute: timeout)
            DispatchQueue.global(qos: .utility).async {
                let result = Result { try Self.resolve(self.parts.host!, port: self.parts.port ?? (self.parts.scheme?.lowercased() == "http" ? 80 : 443)) }
                self.queue.async { self.resolved(result) }
            }
        }
    }
    func cancel() { queue.async { self.finish(.failure(CancellationError())) } }
    private func finish(_ result: Result<ConnectionHTTPResponse, Error>) {
        guard !finished else { return }; finished = true
        timeout?.cancel(); authorization?.cancel(); connection?.stateUpdateHandler = nil; connection?.cancel()
        let callback = continuation; continuation = nil; callback?.resume(with: result)
    }
    private static func resolve(_ host: String, port: Int) throws -> [String] {
        var hints = addrinfo(); hints.ai_family = AF_UNSPEC; hints.ai_socktype = SOCK_STREAM; hints.ai_protocol = IPPROTO_TCP
        var first: UnsafeMutablePointer<addrinfo>?
        let bareHost = host.trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
        guard getaddrinfo(bareHost, String(port), &hints, &first) == 0, let first else { throw DeviceAPIError(502, "connection_dns_failed", "The connection address could not be resolved.") }
        defer { freeaddrinfo(first) }
        var result: [String] = [], current: UnsafeMutablePointer<addrinfo>? = first
        while let value = current {
            var name = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if getnameinfo(value.pointee.ai_addr, value.pointee.ai_addrlen, &name, socklen_t(name.count), nil, 0, NI_NUMERICHOST) == 0 { result.append(String(cString: name)) }
            current = value.pointee.ai_next
        }
        guard !result.isEmpty else { throw DeviceAPIError(502, "connection_dns_failed", "The connection address could not be resolved.") }
        return result
    }
    private func resolved(_ result: Result<[String], Error>) {
        guard !finished else { return }
        do {
            let addresses = try result.get(), plain = parts.scheme?.lowercased() == "http"
            guard addresses.allSatisfy({ value in
                if let destination = request.callbackDestination {
                    return CallbackRules.allowsResolvedAddress(value, destination: destination)
                }
                let scope = ConnectionAddress.scope(value)
                return plain ? request.allowPrivate && scope == .ownerNetwork : scope == .internet || request.allowPrivate && scope == .ownerNetwork
            }) else { throw DeviceAPIError(403, "connection_address_blocked", "The connection resolves to an unapproved or unsafe network address.") }
            let tcp = NWProtocolTCP.Options()
            let parameters: NWParameters
            if plain { parameters = NWParameters(tls: nil, tcp: tcp) }
            else {
                let tls = NWProtocolTLS.Options(), host = parts.host!.trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
                if IPv4Address(host) == nil && IPv6Address(host) == nil {
                    sec_protocol_options_set_tls_server_name(tls.securityProtocolOptions, host)
                }
                sec_protocol_options_set_verify_block(tls.securityProtocolOptions, { _, trust, complete in
                    let value = sec_trust_copy_ref(trust).takeRetainedValue()
                    let configured = SecTrustSetPolicies(value, SecPolicyCreateSSL(true, host as CFString)) == errSecSuccess
                    complete(configured && SecTrustEvaluateWithError(value, nil))
                }, queue)
                parameters = NWParameters(tls: tls, tcp: tcp)
            }
            let port = NWEndpoint.Port(rawValue: UInt16(parts.port ?? (plain ? 80 : 443)))!
            let connection = NWConnection(host: NWEndpoint.Host(addresses[0]), port: port, using: parameters)
            self.connection = connection
            connection.stateUpdateHandler = { [weak self] state in
                guard let self, !self.finished else { return }
                switch state {
                case .ready: self.ready(connection)
                case .failed: self.finish(.failure(DeviceAPIError(502, "connection_failed", "The connection failed. If sending had started, it may have arrived; do not retry automatically.")))
                case .waiting: self.finish(.failure(DeviceAPIError(502, "connection_unavailable", "The connection is unavailable. Check its network and service.")))
                default: break
                }
            }
            try checkOutbound()
            connection.start(queue: queue)
        } catch { finish(.failure(error)) }
    }
    private func ready(_ connection: NWConnection) {
        guard authorization == nil else { return }
        authorization = Task {
            do {
                try await authorize(); try Task.checkCancellation()
                queue.async { [self] in
                    guard !finished else { return }
                    do { try checkOutbound() } catch { finish(.failure(error)); return }
                    var target = parts.percentEncodedPath.isEmpty ? "/" : parts.percentEncodedPath
                    if let query = parts.percentEncodedQuery { target += "?" + query }
                    let port = parts.port, plain = parts.scheme?.lowercased() == "http"
                    let rawHost = parts.host!
                    let hostName = rawHost.contains(":") && !rawHost.hasPrefix("[") ? "[" + rawHost + "]" : rawHost
                    let host = hostName + (port != nil && port != (plain ? 80 : 443) ? ":\(port!)" : "")
                    var lines = ["\(request.method) \(target) HTTP/1.1", "Host: \(host)", "Connection: close", "Content-Length: \(request.body.count)"]
                    for key in request.headers.keys.sorted() { lines.append(key + ": " + request.headers[key]!) }
                    let head = Data((lines.joined(separator: "\r\n") + "\r\n\r\n").utf8)
                    guard head.count <= 64 * 1024 else { finish(.failure(DeviceAPIError(422, "connection_request_too_large", "The connection request is too large."))); return }
                    connection.send(content: head + request.body, completion: .contentProcessed { [weak self] error in
                        guard let self else { return }
                        if error != nil { self.finish(.failure(DeviceAPIError(502, "connection_failed", "The send could not be confirmed. It may have arrived; do not retry automatically."))) }
                        else { self.receive(connection) }
                    })
                }
            } catch { queue.async { self.finish(.failure(error)) } }
        }
    }
    private func receive(_ connection: NWConnection) {
        guard !finished else { return }
        connection.receive(minimumIncompleteLength: 1, maximumLength: 4096) { [weak self] data, _, complete, error in
            guard let self, !self.finished else { return }
            do {
                if let response = try self.parser.append(data ?? Data(), complete: complete && error == nil) {
                    self.finish(.success(response)); return
                }
                if error != nil { throw DeviceAPIError(502, "connection_response_invalid", "The receiver reply was interrupted. The send may have arrived; do not retry automatically.") }
                self.receive(connection)
            } catch { self.finish(.failure(error)) }
        }
    }
}
