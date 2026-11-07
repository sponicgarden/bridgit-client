import Foundation

/// A team backend error, with the backend's own code (`name_taken`, `already_named`, `code_invalid`, …).
struct BackendError: LocalizedError, Equatable {
    var status: Int
    var code: String
    var message: String
    var errorDescription: String? { message }
}

/// Signed calls to the Bridgit team backend as this device (DeviceIdentity). Registers the install the
/// first time, and once more if the backend has forgotten it (`unknown_install`).
struct TeamBackend: Sendable {
    var base: URL = WatchClient.defaultBase
    var identity: DeviceIdentity
    var http: URLSession = .shared

    func call(_ method: String, _ path: String, body: [String: Any]? = nil) async throws -> (Int, [String: Any]) {
        try await ensureRegistered()
        let (status, json) = try await send(method, path, body: body, signed: true)
        if status == 401, json["error"] as? String == "unknown_install" {
            identity.forgetRegistration()
            try await ensureRegistered()
            return try await send(method, path, body: body, signed: true)
        }
        return (status, json)
    }

    func ensureRegistered() async throws {
        if identity.isRegistered { return }
        let (cs, challenge) = try await send("POST", "/v1/install/challenge", body: [:], signed: false)
        guard cs == 200, let c = challenge["challenge"] as? String else { throw Self.failure(cs, challenge) }
        let (rs, reg) = try await send("POST", "/v1/install/register", body: [
            "public_key": identity.publicKeyB64, "challenge": c, "attestation_chain": [String](), "app_version": AppVersion.short,
        ], signed: true)
        guard rs == 200 || rs == 201, reg["install_id"] as? String == identity.id else { throw Self.failure(rs, reg) }
        if reg["status"] as? String == "blocked" { throw BackendError(status: 403, code: "install_blocked", message: "The Bridgit team blocked this install.") }
        identity.markRegistered(trust: reg["trust"] as? String ?? "unverified")
    }

    private func send(_ method: String, _ path: String, body: [String: Any]?, signed: Bool) async throws -> (Int, [String: Any]) {
        var r = URLRequest(url: URL(string: base.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + path)!)
        r.httpMethod = method
        r.timeoutInterval = 15
        r.cachePolicy = .reloadIgnoringLocalCacheData
        r.setValue("BridgitPortal-iOS/1", forHTTPHeaderField: "User-Agent")
        if let body {
            r.httpBody = try JSONSerialization.data(withJSONObject: body)
            r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        if signed { try identity.sign(&r) }
        let (data, response) = try await http.data(for: r)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        return (status, (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:])
    }

    static func failure(_ status: Int, _ json: [String: Any]) -> BackendError {
        BackendError(status: status, code: json["error"] as? String ?? "upstream_failed",
                     message: json["message"] as? String ?? "The Bridgit backend answered \(status).")
    }
}

/// This device's Device Domain Name (bridgit.cc/names), like Android's DeviceNames: POST /v1/names to
/// register, GET/PATCH /v1/names/me, and a heartbeat every `heartbeat_every_s` while the app is open
/// and the name is visible. The local copy only caches the backend's answer.
@MainActor
final class DeviceNameStore: ObservableObject {
    struct Own: Codable, Equatable {
        var name: String
        var label: String?
        var visible: Bool
        var heartbeatEvery: Int

        init(name: String, label: String?, visible: Bool, heartbeatEvery: Int) {
            self.name = name; self.label = label; self.visible = visible; self.heartbeatEvery = heartbeatEvery
        }

        init?(_ json: [String: Any]) {
            guard let n = json["name"] as? String else { return nil }
            self.init(name: n, label: json["label"] as? String, visible: json["visible"] as? Bool ?? false,
                      heartbeatEvery: json["heartbeat_every_s"] as? Int ?? 300)
        }
    }

    static let advice = "Pick a Device Domain Name for this Bridgit: about 10 characters, as cute and friendly to a person as you can make it, like kitchen-tab or porch-cam. " +
        "3 to 30 lowercase letters, digits and hyphens; no slashes. First come, first served, like a domain: if it's taken, pick another."
    private static let cacheKey = "bridgit-name"

    @Published private(set) var own: Own?
    @Published var busy = false
    @Published var error: String?
    /// Sample data: a made-up name; nothing reaches the backend or the cache.
    private(set) var isSample = false

    private let backend: () throws -> TeamBackend
    private let defaults: UserDefaults
    private var lastHeartbeat = Date.distantPast
    private var heartbeatTask: Task<Void, Never>?

    init(defaults: UserDefaults = .standard, backend: @escaping () throws -> TeamBackend = { TeamBackend(identity: try DeviceIdentity.load()) }) {
        self.defaults = defaults
        self.backend = backend
        if let d = defaults.data(forKey: Self.cacheKey) { own = try? JSONDecoder().decode(Own.self, from: d) }
    }

    var name: String? { own?.name }

    /// A Device Domain Name as the backend accepts it, lower-cased; nil when invalid. Same rule as the backend.
    nonisolated static func parse(_ text: String) -> String? {
        let n = text.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard (3...30).contains(n.count), n.range(of: #"^[a-z0-9]+(-[a-z0-9]+)*$"#, options: .regularExpression) != nil else { return nil }
        return n
    }

    /// A first suggestion from the device's own name ("Rahul's iPad" → "rahuls-ipad").
    nonisolated static func suggestion(from deviceName: String) -> String {
        let folded = deviceName.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: nil).lowercased()
        var out = "", dash = false
        for ch in folded {
            if ch.isASCII, ch.isLetter || ch.isNumber { out.append(ch); dash = false }
            else if ch == "'" || ch == "’" { continue }
            else if !out.isEmpty, !dash { out.append("-"); dash = true }
        }
        while out.hasSuffix("-") { out.removeLast() }
        let short = String(out.prefix(30))
        return parse(short) ?? "my-bridgit"
    }

    private func save(_ o: Own?) {
        guard !isSample else { return }
        own = o
        if let o, let d = try? JSONEncoder().encode(o) { defaults.set(d, forKey: Self.cacheKey) } else { defaults.removeObject(forKey: Self.cacheKey) }
    }

    /// Registers [text] as this device's name. Throws `name_taken` with the backend's message.
    func register(_ text: String) async throws {
        guard !isSample else { return }
        guard let n = Self.parse(text) else { throw BackendError(status: 400, code: "invalid_parameter", message: Self.advice) }
        busy = true; defer { busy = false }
        let (status, json) = try await backend().call("POST", "/v1/names", body: ["name": n])
        if status == 409, json["error"] as? String == "already_named" { try await refresh(); return }
        guard (200..<300).contains(status), let o = Own(json) else { throw TeamBackend.failure(status, json) }
        save(o)
        lastHeartbeat = Date()
        error = nil
    }

    /// GET /v1/names/me: the backend's answer, cached; nil when the backend has none.
    func refresh() async throws {
        guard !isSample else { return }
        let (status, json) = try await backend().call("GET", "/v1/names/me")
        if status == 404, json["error"] as? String == "no_name" { save(nil); return }
        guard status == 200, let o = Own(json) else { throw TeamBackend.failure(status, json) }
        save(o)
    }

    /// PATCH /v1/names/me {label?, visible?}.
    func update(label: String?? = nil, visible: Bool? = nil) async throws {
        if isSample { if let visible { own?.visible = visible }; return }
        var body: [String: Any] = [:]
        if let label { body["label"] = label ?? NSNull() }
        if let visible { body["visible"] = visible }
        guard !body.isEmpty else { return }
        let (status, json) = try await backend().call("PATCH", "/v1/names/me", body: body)
        guard status == 200, let o = Own(json) else { throw TeamBackend.failure(status, json) }
        save(o)
    }

    /// "I'm on", every heartbeat_every_s while visible.
    func heartbeatIfDue(now: Date = Date()) async {
        guard !isSample, let o = own, o.visible, now.timeIntervalSince(lastHeartbeat) >= Double(o.heartbeatEvery) - 15 else { return }
        lastHeartbeat = now
        guard let (status, json) = try? await backend().call("POST", "/v1/names/heartbeat", body: [:]) else { return }
        if status == 200, let fresh = Own(json) { save(fresh) }
        else if status == 404, json["error"] as? String == "no_name" { save(nil) }
    }

    /// While the app is open: check the cached name once, then heartbeat on schedule.
    func startHeartbeat() {
        guard heartbeatTask == nil else { return }
        heartbeatTask = Task { [weak self] in
            if self?.own != nil { try? await self?.refresh() }
            while !Task.isCancelled {
                await self?.heartbeatIfDue()
                try? await Task.sleep(nanoseconds: 30_000_000_000)
            }
        }
    }

    func stopHeartbeat() {
        heartbeatTask?.cancel()
        heartbeatTask = nil
    }

    func setSampleName(_ name: String) {
        stopHeartbeat()
        isSample = true
        own = Own(name: name, label: nil, visible: true, heartbeatEvery: 300)
    }

    func reloadCached() {
        isSample = false
        own = defaults.data(forKey: Self.cacheKey).flatMap { try? JSONDecoder().decode(Own.self, from: $0) }
    }
}
