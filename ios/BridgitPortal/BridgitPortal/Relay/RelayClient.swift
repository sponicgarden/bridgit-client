import Foundation

enum RelayError: LocalizedError {
    case offline
    case quota
    case revoked
    case unknownPhone
    case notAllowed(String)
    case timeout
    case failed(Int, String)

    var errorDescription: String? {
        switch self {
        case .offline: "The phone isn't connected to the relay right now."
        case .quota: "Live view paused: the relay's limit for this pairing was reached. It resets later; the stored snapshot is shown."
        case .revoked: "The phone removed this pairing. Pair again from the phone."
        case .unknownPhone: "The relay doesn't know this phone, or it was switched off there. Turn on Relay on the phone and pair again."
        case .notAllowed(let what): "The phone doesn't allow this pairing to read \(what)."
        case .timeout: "The phone didn't answer in time."
        case .failed(let s, let m): "Relay error \(s): \(m)"
        }
    }
}

struct RelayResponse {
    var status: Int
    var body: Data
}

/// Sends one request to the phone through the Bridgit relay as a connector agent
/// (bridgit-cloud/relay/README.md "Wire contracts"): POST <base>/jobs?wait=N, then GET <base>/jobs/<id> while pending.
/// Calls for one phone are serialized so the app never holds more than one relay job per phone.
actor RelayClient {
    let pairing: Pairing
    private let http: URLSession
    private var chain: Task<Void, Never>?

    init(pairing: Pairing, http: URLSession = .shared) {
        self.pairing = pairing
        self.http = http
    }

    func get(_ path: String, query: [String: String] = [:], wait: Int = 25) async throws -> RelayResponse {
        try await call(method: "GET", path: path, query: query, body: nil, wait: wait)
    }

    func getJSON(_ path: String, query: [String: String] = [:], wait: Int = 25) async throws -> Any {
        let r = try await get(path, query: query, wait: wait)
        return try JSONSerialization.jsonObject(with: r.body.isEmpty ? Data("{}".utf8) : r.body)
    }

    /// `deadline` (epoch ms) overrides the default of wait + 60 s.
    func call(method: String, path: String, query: [String: String], body: Any?, wait: Int, deadline: Int? = nil) async throws -> RelayResponse {
        // Serialize: wait for the previous call on this phone to finish first.
        let previous = chain
        let task = Task<RelayResponse, Error> {
            _ = await previous?.value
            return try await self.send(method: method, path: path, query: query, body: body, wait: wait, deadline: deadline)
        }
        chain = Task { _ = try? await task.value }
        return try await task.value
    }

    private func send(method: String, path: String, query: [String: String], body: Any?, wait: Int, deadline fixed: Int?) async throws -> RelayResponse {
        let w = max(0, min(wait, 55))
        var request: [String: Any] = ["method": method, "path": path]
        if !query.isEmpty { request["query"] = query }
        if let body { request["body"] = body }
        let deadline = fixed ?? Int(Date().timeIntervalSince1970 * 1000) + (w + 60) * 1000
        let payload = try JSONSerialization.data(withJSONObject: ["deadline": deadline, "request": request])

        var c = URLComponents(url: pairing.base.appendingPathComponent("jobs"), resolvingAgainstBaseURL: false)!
        c.queryItems = [URLQueryItem(name: "wait", value: String(w))]
        var r = URLRequest(url: c.url!)
        r.httpMethod = "POST"
        r.httpBody = payload
        r.timeoutInterval = TimeInterval(w + 20)
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.setValue("Bearer " + pairing.token, forHTTPHeaderField: "Authorization")
        r.setValue("BridgitPortal-iOS/1", forHTTPHeaderField: "User-Agent")

        let started = Date()
        var (data, status) = try await fetch(r)
        // 202: accepted, not finished. Poll the job until it completes or the deadline passes.
        while status == 202 {
            guard let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any], let id = obj["job_id"] as? String else {
                throw RelayError.failed(status, "unreadable job")
            }
            if Date().timeIntervalSince(started) > TimeInterval(w + 50) { throw RelayError.timeout }
            try await Task.sleep(nanoseconds: 1_500_000_000)
            var g = URLRequest(url: pairing.base.appendingPathComponent("jobs").appendingPathComponent(id))
            g.setValue("Bearer " + pairing.token, forHTTPHeaderField: "Authorization")
            g.timeoutInterval = 30
            (data, status) = try await fetch(g)
        }
        return try Self.decodeCompletion(status: status, data: data)
    }

    private func fetch(_ r: URLRequest) async throws -> (Data, Int) {
        let (data, response) = try await http.data(for: r)
        return (data, (response as? HTTPURLResponse)?.statusCode ?? 0)
    }

    /// Maps the relay's job completion (or error) to the phone's own HTTP response.
    static func decodeCompletion(status: Int, data: Data) throws -> RelayResponse {
        let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        let code = obj["error"] as? String ?? ""
        if code == "agent_revoked" { throw RelayError.revoked }
        if code == "device_disabled" || code == "unknown_device" || code == "device_not_found" { throw RelayError.unknownPhone }
        if code == "relay_route_excluded" || code == "route_not_allowed" || code == "forbidden_route" { throw RelayError.notAllowed("this") }
        switch status {
        case 200: break
        case 401: throw RelayError.revoked
        case 429: throw RelayError.quota
        case 503, 504: throw code == "phone_unreachable" || code.isEmpty ? RelayError.offline : RelayError.failed(status, code)
        default: throw RelayError.failed(status, code.isEmpty ? "unexpected reply" : code)
        }
        guard let resp = obj["response"] as? [String: Any] else { throw RelayError.failed(status, "no response") }
        let phoneStatus = resp["status"] as? Int ?? 0
        var body = Data()
        if let b64 = resp["body_base64"] as? String {
            guard let d = Data(base64Encoded: b64) else { throw RelayError.failed(status, "unreadable reply from the phone") }
            body = d
        } else if let b = resp["body"] {
            if let s = b as? String { body = Data(s.utf8) }
            else if JSONSerialization.isValidJSONObject(b) { body = (try? JSONSerialization.data(withJSONObject: b)) ?? Data() }
        }
        if phoneStatus == 401 { throw RelayError.revoked }
        if phoneStatus == 403 {
            let inner = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any]
            throw RelayError.notAllowed((inner?["message"] as? String) ?? "this screen")
        }
        if phoneStatus == 429 { throw RelayError.quota }
        guard (200..<300).contains(phoneStatus) else {
            let inner = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any]
            throw RelayError.failed(phoneStatus, (inner?["message"] as? String) ?? (inner?["error"] as? String) ?? "phone refused")
        }
        return RelayResponse(status: phoneStatus, body: body)
    }
}
