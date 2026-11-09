import Foundation
import Network

public protocol SatelliteHTTPTransport {
    func call(address: TailnetAddress, port: UInt16, request: HostRequest, key: String?) async throws -> HostResponse
}

/// HTTP over the encrypted tailnet, to a validated literal address. No DNS,
/// redirects, app-wide ATS relaxation, cookies, or automatic action retries.
public struct TailnetHTTPTransport: SatelliteHTTPTransport {
    private let fixtureLoopback: Bool
    private let source: TailnetAddress?
    public init(source: TailnetAddress? = nil) { fixtureLoopback = false; self.source = source }
    init(fixtureLoopback: Bool) { self.fixtureLoopback = fixtureLoopback; source = nil }
    public func call(address: TailnetAddress, port: UInt16, request: HostRequest, key: String?) async throws -> HostResponse {
        guard port > 0, ["GET", "POST", "PATCH", "DELETE"].contains(request.method), request.path.hasPrefix("/v1/"), !request.path.contains("%"), !request.path.contains("?"), !request.path.contains("#"), !request.path.contains("//"), !request.path.contains("\r"), !request.path.contains("\n"), request.path.utf8.allSatisfy({ (33...126).contains($0) }) else { throw DeviceAPIError(422, "invalid_parameter", "Use a canonical satellite API request.") }
        if let key { guard SatelliteAuthorityPolicy.validKey(key) else { throw DeviceAPIError(422, "invalid_satellite_key", "Use the actual source-bound key returned by the satellite.") } }
        let body = request.body.isEmpty ? Data() : try JSONSerialization.data(withJSONObject: request.body)
        guard body.count <= TailnetHTTPCodec.bodyLimit else { throw DeviceAPIError(413, "request_too_large", "Satellite requests are limited to 6 MB.") }
        guard request.query.count <= 32 else { throw DeviceAPIError(422, "invalid_parameter", "Use at most 32 satellite query fields.") }
        var components = URLComponents(); components.path = request.path
        components.queryItems = try request.query.sorted(by: { $0.key < $1.key }).map { name, raw in
            guard name.utf8.allSatisfy({ (33...126).contains($0) }), name.count <= 128, let value = raw as? String, value.count <= 4096 else { throw DeviceAPIError(422, "invalid_parameter", "Use bounded string query fields.") }
            return URLQueryItem(name: name, value: value)
        }
        let target = components.percentEncodedPath + (components.percentEncodedQuery.flatMap { $0.isEmpty ? nil : "?" + $0 } ?? "")
        guard target.utf8.count <= 8192 else { throw DeviceAPIError(422, "invalid_parameter", "Use a bounded satellite request path.") }
        let host = fixtureLoopback ? "127.0.0.1" : address.value
        let headerHost = host.contains(":") ? "[" + host + "]" : host
        var header = "\(request.method) \(target) HTTP/1.1\r\nHost: \(headerHost):\(port)\r\nConnection: close\r\nAccept: application/json\r\nContent-Length: \(body.count)\r\n"
        if let key { header += "Authorization: Bearer " + key + "\r\n" }
        if let idempotency = request.idempotencyKey {
            guard !idempotency.isEmpty, idempotency.count <= 128, idempotency.utf8.allSatisfy({ (33...126).contains($0) }) else { throw DeviceAPIError(422, "invalid_parameter", "Use a bounded visible idempotency key.") }
            header += "Idempotency-Key: " + idempotency + "\r\n"
        }
        if !body.isEmpty { header += "Content-Type: application/json\r\n" }
        var bytes = Data((header + "\r\n").utf8); bytes.append(body)
        let parameters = NWParameters(tls: nil, tcp: NWProtocolTCP.Options())
        if !fixtureLoopback {
            let candidates = TailnetAddress.localCandidates().filter { $0.isIPv6 == address.isIPv6 }
            guard let local = source ?? candidates.first, candidates.contains(local) else { throw DeviceAPIError(409, "tailnet_required", "Turn on Tailscale using the local address family used for this pairing.") }
            parameters.requiredLocalEndpoint = .hostPort(host: .init(local.value), port: .any)
        }
        let connection = NWConnection(host: .init(host), port: .init(rawValue: port)!, using: parameters)
        let connectionDeadline = Task { try? await Task.sleep(nanoseconds: 5_000_000_000); if !Task.isCancelled { connection.cancel() } }
        let deadline = Task { try? await Task.sleep(nanoseconds: 65_000_000_000); if !Task.isCancelled { connection.cancel() } }
        defer { connectionDeadline.cancel(); deadline.cancel(); connection.cancel() }
        connection.stateUpdateHandler = { state in if case .ready = state { connectionDeadline.cancel() } }
        connection.start(queue: DispatchQueue(label: "cc.bridgit.satellite-client"))
        do {
            return try await withTaskCancellationHandler(operation: {
                try Task.checkCancellation()
                try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                    connection.send(content: bytes, completion: .contentProcessed { error in
                        if let error { continuation.resume(throwing: error) } else { continuation.resume() }
                    })
                }
                var received = Data()
                while true {
                    let (part, complete): (Data, Bool) = try await withCheckedThrowingContinuation { continuation in
                        connection.receive(minimumIncompleteLength: 1, maximumLength: 65536) { data, _, complete, error in
                            if let error { continuation.resume(throwing: error) }
                            else { continuation.resume(returning: (data ?? Data(), complete)) }
                        }
                    }
                    received.append(part)
                    guard received.count <= 16 * 1024 * 1024 else { throw DeviceAPIError(502, "satellite_reply_too_large", "The satellite reply exceeded its limit.") }
                    if let response = try SatelliteHTTPResponse.parse(received, complete: complete) { return response }
                    guard !complete else { throw DeviceAPIError(502, "invalid_satellite_reply", "The satellite returned an incomplete reply.") }
                }
            }, onCancel: { connection.cancel() })
        } catch is CancellationError { throw CancellationError() }
        catch let error as DeviceAPIError { throw error }
        catch {
            if Task.isCancelled { throw CancellationError() }
            throw DeviceAPIError(503, "satellite_offline", "The satellite did not answer. Check Tailscale and keep Bridgit open there.")
        }
    }
}

enum SatelliteHTTPResponse {
    static let limit = 8 * 1024 * 1024
    static func parse(_ bytes: Data, complete: Bool) throws -> HostResponse? {
        let invalid = DeviceAPIError(502, "invalid_satellite_reply", "The satellite returned an invalid HTTP reply.")
        guard let end = bytes.range(of: Data("\r\n\r\n".utf8)) else { guard bytes.count <= 16 * 1024, !complete else { throw invalid }; return nil }
        guard end.upperBound <= 16 * 1024, let header = String(data: bytes[..<end.lowerBound], encoding: .ascii) else { throw invalid }
        let lines = header.components(separatedBy: "\r\n"), first = (lines.first ?? "").split(separator: " ", maxSplits: 2)
        guard first.count >= 2, ["HTTP/1.0", "HTTP/1.1"].contains(first[0]), let status = Int(first[1]), (200...599).contains(status) else { throw invalid }
        var fields: [String: String] = [:]
        for line in lines.dropFirst() {
            guard let colon = line.firstIndex(of: ":"), !line.hasPrefix(" "), !line.hasPrefix("\t") else { throw invalid }
            let name = String(line[..<colon]).lowercased(), value = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
            guard !name.isEmpty, name.utf8.allSatisfy({ (97...122).contains($0) || (48...57).contains($0) || $0 == 45 }), fields[name] == nil, value.utf8.allSatisfy({ $0 == 9 || (32...126).contains($0) }) else { throw invalid }
            fields[name] = value
        }
        guard fields["content-encoding"] == nil || fields["content-encoding"]?.lowercased() == "identity" else { throw invalid }
        let content = Data(bytes[end.upperBound...]), body: Data
        if let transfer = fields["transfer-encoding"] {
            guard transfer.lowercased() == "chunked", fields["content-length"] == nil else { throw invalid }
            guard let decoded = try chunks(content, complete: complete) else { return nil }; body = decoded
        } else if let raw = fields["content-length"] {
            guard !raw.isEmpty, raw.utf8.allSatisfy({ (48...57).contains($0) }), let count = Int(raw), count <= limit, content.count <= count else { throw invalid }
            guard content.count == count else { if complete { throw invalid }; return nil }; body = content
        } else {
            guard content.count <= limit else { throw invalid }
            guard complete else { return nil }; body = content
        }
        return HostResponse(status: status, contentType: fields["content-type"] ?? "application/json", data: body, headers: fields.filter { !["content-length", "content-type", "connection", "transfer-encoding", "set-cookie", "content-encoding"].contains($0.key) })
    }
    private static func chunks(_ bytes: Data, complete: Bool) throws -> Data? {
        let invalid = DeviceAPIError(502, "invalid_satellite_reply", "The satellite returned invalid chunked data.")
        var position = 0, result = Data()
        while true {
            guard let end = bytes.range(of: Data("\r\n".utf8), in: position..<bytes.count) else { if complete || bytes.count - position > 2048 { throw invalid }; return nil }
            guard end.lowerBound - position <= 2048, let text = String(data: bytes[position..<end.lowerBound], encoding: .ascii), let number = text.split(separator: ";", maxSplits: 1).first,
                  !number.isEmpty, number.count <= 16, number.utf8.allSatisfy({ (48...57).contains($0) || (65...70).contains($0) || (97...102).contains($0) }), let count = Int(number, radix: 16), count <= limit - result.count else { throw invalid }
            position = end.upperBound
            if count == 0 {
                if bytes.count >= position + 2, bytes[position..<position + 2] == Data("\r\n".utf8) { guard bytes.count == position + 2 else { throw invalid }; return result }
                guard let trailerEnd = bytes.range(of: Data("\r\n\r\n".utf8), in: position..<bytes.count), trailerEnd.upperBound - position <= 16 * 1024 else { if complete || bytes.count - position > 16 * 1024 { throw invalid }; return nil }
                guard trailerEnd.upperBound == bytes.count else { throw invalid }; return result
            }
            guard bytes.count >= position + count + 2 else { if complete { throw invalid }; return nil }
            guard bytes[position + count..<position + count + 2] == Data("\r\n".utf8) else { throw invalid }
            result.append(bytes[position..<position + count]); position += count + 2
        }
    }
}

public struct SatelliteClient {
    private let transport: SatelliteHTTPTransport
    public init(transport: SatelliteHTTPTransport = TailnetHTTPTransport()) { self.transport = transport }
    public func call(address: TailnetAddress, port: UInt16, key: String, request: HostRequest) async throws -> HostResponse {
        try await transport.call(address: address, port: port, request: request, key: key)
    }
    public func claim(_ link: SatellitePairingLink, hubName: String) async throws -> (key: String, name: String) {
        let response = try await transport.call(address: link.address, port: link.port, request: .init(method: "POST", path: "/v1/satellite/claim", body: ["token": link.claim, "hub_name": String(hubName.prefix(60))]), key: nil)
        let body = try json(response)
        guard let key = body["key"] as? String, SatelliteAuthorityPolicy.validKey(key) else { throw DeviceAPIError(502, "missing_satellite_key", "The satellite did not return a valid source-bound key.") }
        return (key, String((body["name"] as? String ?? link.name).prefix(60)))
    }
    public func manifest(address: TailnetAddress, port: UInt16, key: String) async throws -> [String: Any] {
        try json(await transport.call(address: address, port: port, request: .init(method: "GET", path: "/v1/satellite/manifest"), key: key))
    }
    public func sendHubInfo(address: TailnetAddress, port: UInt16, key: String, info: [String: Any]) async throws {
        let object = try json(await transport.call(address: address, port: port, request: .init(method: "POST", path: "/v1/satellite/hub-info", body: info), key: key))
        guard object["ok"] as? Bool == true else { throw DeviceAPIError(502, "unconfirmed_hub_info", "The satellite did not confirm the return link.") }
    }
    public func unlink(address: TailnetAddress, port: UInt16, key: String) async throws {
        let object = try json(await transport.call(address: address, port: port, request: .init(method: "POST", path: "/v1/satellite/unlink"), key: key))
        guard object["ok"] as? Bool == true else { throw DeviceAPIError(502, "unconfirmed_unlink", "The satellite did not confirm unlinking.") }
    }
    private func json(_ response: HostResponse) throws -> [String: Any] {
        guard (200...299).contains(response.status) else {
            let parsed = (try? JSONSerialization.jsonObject(with: response.data)) as? [String: Any]
            let raw = parsed?["error"] as? String ?? "satellite_http_error"
            let code = raw.range(of: "^[a-z0-9_]{1,64}$", options: .regularExpression) != nil ? raw : "satellite_http_error"
            throw DeviceAPIError(response.status, code, response.status == 401 ? "The satellite link was removed, expired or already used. Make a new code on that device." : "The satellite returned HTTP \(response.status).")
        }
        guard response.data.count <= 1_048_576, let object = try JSONSerialization.jsonObject(with: response.data) as? [String: Any] else { throw DeviceAPIError(502, "invalid_satellite_reply", "The satellite returned an invalid JSON object.") }
        return object
    }
}
