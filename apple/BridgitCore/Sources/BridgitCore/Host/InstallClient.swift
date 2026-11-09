import Foundation

/// Refuse redirects so signed headers never reach another origin.
public final class NoRedirects: NSObject, URLSessionTaskDelegate {
    public func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                           newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}

public struct FeedbackUpdates: Decodable {
    public struct Change: Decodable {
        public let phone_report_id: Int64
        public let status: String
        public let team_note: String?
    }
    public let updates: [Change]
    public let cursor: Int64
    public let cursor_id: Int64?
    public let more: Bool
}

public actor InstallClient {
    public static let productionBase = URL(string: "https://bridgit-feedback.bridgit-app.workers.dev")!
    private let signer: DeviceSigner
    private let base: URL
    private let http: URLSession
    private let outboundAccess: OutboundAccess
    private var registered = false
    public init(signer: DeviceSigner, base: URL = InstallClient.productionBase, http: URLSession? = nil, outboundAccess: OutboundAccess = .unrestricted) {
        self.signer = signer; self.base = base; self.outboundAccess = outboundAccess
        self.http = http ?? URLSession(configuration: .ephemeral, delegate: NoRedirects(), delegateQueue: nil)
    }
    public var installID: String { signer.installID }
    /// Explicit owner action calls this. Constructing a client does not enroll or send anything.
    public func register(appVersion: String) async throws -> [String: Any] {
        var challenge = URLRequest(url: base.appendingPathComponent("v1/install/challenge"))
        challenge.httpMethod = "POST"; challenge.httpBody = Data("{}".utf8); challenge.setValue("application/json", forHTTPHeaderField: "Content-Type")
        challenge.timeoutInterval = 30
        let c = try await json(challenge)
        guard let value = c["challenge"] as? String else { throw DeviceAPIError(502, "invalid_response", "The server did not return an install challenge.") }
        let payload: [String: Any] = ["public_key": signer.publicKey.base64EncodedString(), "attestation_chain": [String](), "challenge": value, "app_version": appVersion]
        let body = try JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys])
        let request = try signer.signedRequest(url: base.appendingPathComponent("v1/install/register"), method: "POST", body: body)
        let reply = try await json(request)
        guard reply["install_id"] as? String == signer.installID else { throw DeviceAPIError(502, "identity_mismatch", "The server returned a different installation identity.") }
        registered = true; return reply
    }
    public struct WebLinkCode: Sendable {
        public let code: String
        public let expires: Date
        public var url: URL { URL(string: "https://bridgit.cc/myapps?code=" + code)! }
    }

    /// Check the account association with an empty event list. Keep device activity and inventory private.
    public func webAccountLinked(appVersion: String) async throws -> Bool {
        if !registered { _ = try await register(appVersion: appVersion) }
        let request = try signer.signedRequest(url: base.appendingPathComponent("v1/watch/events"),
                                               method: "POST", body: Data(#"{"events":[]}"#.utf8))
        let reply = try await json(request)
        guard let linked = reply["linked"] as? Bool else {
            throw DeviceAPIError(502, "invalid_response", "Bridgit could not confirm whether this device is linked.")
        }
        return linked
    }

    /// Returns true while the service is still removing this installation's web data.
    public func turnOffWebView() async throws -> Bool {
        let request = try signer.signedRequest(url: base.appendingPathComponent("v1/watch/unlink"),
                                               method: "POST", body: Data("{}".utf8))
        let reply = try await json(request)
        guard reply["linked"] as? Bool == false else {
            throw DeviceAPIError(502, "invalid_response", "Bridgit could not confirm that web view is off.")
        }
        return reply["deletion_pending"] as? Bool == true
    }

    public func issueWebLinkCode(name: String, deviceType: String, platform: String? = nil, appVersion: String) async throws -> WebLinkCode {
        let metadata = ReportedDeviceMetadata(platform: platform, deviceType: deviceType)
        guard let type = metadata.deviceType, !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw DeviceAPIError(400, "invalid_device", "A device name and type are required.")
        }
        if !registered { _ = try await register(appVersion: appVersion) }
        var fields = ["phone_name": String(name.prefix(60)), "device_type": type]
        if let platform = metadata.platform { fields["platform"] = platform }
        let body = try JSONEncoder().encode(fields)
        let request = try signer.signedRequest(url: base.appendingPathComponent("v1/watch/link-code"),
                                               method: "POST", body: body)
        let reply = try await json(request)
        guard let code = reply["code"] as? String, code.utf8.count == 8,
              code.allSatisfy({ "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".contains($0) }),
              let ms = reply["expires_at"] as? Int64, Double(ms) > Date().timeIntervalSince1970 * 1000 else {
            throw DeviceAPIError(502, "invalid_response", "Bridgit could not issue a valid web link code.")
        }
        return WebLinkCode(code: code, expires: Date(timeIntervalSince1970: Double(ms) / 1000))
    }

    public func submitFeedback(reportID: Int64, kind: String, description: String, noAutofix: Bool,
                               createdAt: Int64, appVersion: String, versionCode: Int64? = nil, buildNumber: String? = nil,
                               registrationAppVersion: String? = nil, deviceModel: String? = nil, osVersion: String? = nil,
                               buildRevision: String? = nil, buildConfiguration: String? = nil,
                               images: [Data] = [], log: [[String: String]] = [],
                               sourceScreen: String? = nil, logEntryID: Int64? = nil, attachedActivity: [HostActivityEntry] = [],
                               filingAgentContext: Data? = nil, authorize: (() async throws -> Void)? = nil) async throws -> Int64 {
        guard reportID > 0, ["bug", "feature"].contains(kind),
              !description.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              description.utf16.count <= 10_000 else {
            throw DeviceAPIError(400, "invalid_report", "Enter a description of up to 10,000 characters.")
        }
        guard images.count <= 5, images.allSatisfy({ !$0.isEmpty && $0.count <= 1_000_000 }) else {
            throw DeviceAPIError(413, "invalid_images", "Attach up to five screenshots of at most 1 MB each.")
        }
        try await authorize?()
        if !registered {
            let runningVersion = registrationAppVersion ?? (Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "")
            _ = try await register(appVersion: runningVersion)
        }
        var context: [String: Any] = ["app_version": appVersion]
        // Receiver indexes these canonical keys; Apple OS remains in context_json.
        if let versionCode, (0...9_007_199_254_740_991).contains(versionCode) { context["version_code"] = versionCode }
        context["app_build"] = buildNumber
        context["device_model"] = deviceModel
        context["os_version"] = osVersion
        context["git_commit"] = buildRevision
        context["build_type"] = buildConfiguration
        context["screen"] = sourceScreen
        context["log_entry_id"] = logEntryID
        var filingAgent: [String: Any]?
        if let filingAgentContext {
            let agent = try JSONSerialization.jsonObject(with: filingAgentContext)
            guard let fields = agent as? [String: Any], fields["id"] is String else {
                throw DeviceAPIError(422, "invalid_report", "The filing agent context is unavailable.")
            }
            filingAgent = fields
        }
        let entries: [[String: Any]] = log + attachedActivity.map { HostActivityDetail($0).fields }
        var payload: [String: Any] = [
            "phone_report_id": reportID, "kind": kind, "description": description,
            "no_autofix": noAutofix, "status": "new", "created_at": createdAt,
            "capture_source": filingAgentContext == nil ? "form" : "agent", "log": entries, "context": context,
            "images": images.map { ["data_base64": $0.base64EncodedString(), "mime": "image/jpeg"] }
        ]
        payload["agent"] = filingAgent
        let data = try JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys])
        try await authorize?()
        let request = try signer.signedRequest(url: base.appendingPathComponent("v1/reports"), method: "POST", body: data)
        let reply = try await json(request)
        guard let id = reply["id"] as? Int64, id > 0 else {
            throw DeviceAPIError(502, "invalid_response", "The feedback service did not confirm the report.")
        }
        return id
    }
    public func sendFeedbackResolution(reportID: Int64, historyID: Int64, status: String, at: Date) async throws {
        guard ["confirmed", "reopened"].contains(status) else {
            throw DeviceAPIError(400, "invalid_resolution", "Choose a valid report and owner response.")
        }
        try await sendFeedbackHistory(reportID: reportID, historyID: historyID, status: status,
                                      at: at, kind: "status", actor: .owner, author: "owner", note: nil)
    }

    public func sendFeedbackHistory(reportID: Int64, historyID: Int64, status: String, at: Date,
                                    kind: String, actor: HostFeedbackActor, author: String, note: String?,
                                    authorize: (() async throws -> Void)? = nil) async throws {
        guard reportID > 0, historyID > 0, HostFeedbackStatus(rawValue: status) != nil,
              ["status", "comment"].contains(kind), actor != .team, (note?.utf16.count ?? 0) <= 500,
              kind != "comment" || note?.isEmpty == false else {
            throw DeviceAPIError(400, "invalid_resolution", "The report history entry is invalid.")
        }
        let payload: [String: Any] = [
            "phone_history_id": historyID, "at": Int64(at.timeIntervalSince1970 * 1000),
            "kind": kind, "status": status, "actor": actor.rawValue, "by": author, "note": note as Any? ?? NSNull()
        ]
        let body = try JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys])
        let url = base.appendingPathComponent("v1/reports/" + String(reportID) + "/history")
        try await authorize?()
        _ = try await json(signer.signedRequest(url: url, method: "POST", body: body))
    }

    public struct FeedbackTeamCredential: Codable, Sendable {
        public let key: String
        public let agentID: String
        public let role: String
        public let baseURL: String
        public let docs: String

        public var response: [String: Any] {
            ["key": key, "agent_id": agentID, "role": role, "base_url": baseURL, "docs": docs,
             "how_to": ["Send Authorization: Bearer <key>. Keep it out of logs and command lines.",
                        "File reports at POST " + baseURL + "/v1/agent/reports.",
                        "Read " + docs + " for follow-up and verification."]]
        }
    }

    public func mintFeedbackTeamKey(agentID: String, name: String, fixer: Bool, appVersion: String,
                                    authorize: () async throws -> Void) async throws -> FeedbackTeamCredential {
        try await authorize()
        if !registered { _ = try await register(appVersion: appVersion) }
        let body = try JSONSerialization.data(withJSONObject: ["phone_agent_id": agentID, "name": name, "developer": fixer], options: [.sortedKeys])
        try await authorize()
        let endpoint = base.appendingPathComponent("v1/agent-keys")
        let reply = try await json(signer.signedRequest(url: endpoint, method: "POST", body: body))
        guard let key = reply["key"] as? String, key.range(of: "^bgk_[A-Za-z0-9_-]{43}$", options: .regularExpression) != nil,
              let teamAgent = reply["agent_id"] as? String, !teamAgent.isEmpty,
              let baseURL = reply["base_url"] as? String, let url = URL(string: baseURL),
              url.scheme == "https", url.host == base.host, url.port == base.port,
              url.user == nil, url.password == nil, url.path.isEmpty, url.query == nil, url.fragment == nil else {
            throw DeviceAPIError(502, "invalid_response", "The feedback service returned an invalid team credential.")
        }
        return FeedbackTeamCredential(key: key, agentID: teamAgent, role: reply["role"] as? String ?? "reporter",
                                      baseURL: baseURL, docs: baseURL + "/v1/agent")
    }

    public func revokeFeedbackTeamKey(agentID: String) async throws {
        let body = try JSONSerialization.data(withJSONObject: ["phone_agent_id": agentID], options: [.sortedKeys])
        let endpoint = base.appendingPathComponent("v1/agent-keys/revoke")
        _ = try await json(signer.signedRequest(url: endpoint, method: "POST", body: body))
    }

    public func feedbackUpdates(since: Int64, sinceID: Int64?) async throws -> FeedbackUpdates {
        var parts = URLComponents(url: base.appendingPathComponent("v1/reports/updates"), resolvingAgainstBaseURL: false)!
        var query = [URLQueryItem(name: "since", value: String(since))]
        if let sinceID { query.append(URLQueryItem(name: "since_id", value: String(sinceID))) }
        parts.queryItems = query
        var request = try signer.signedRequest(url: parts.url!, method: "GET")
        request.httpBody = nil
        request.timeoutInterval = 4
        let reply = try await json(request)
        let page = try JSONDecoder().decode(FeedbackUpdates.self, from: JSONSerialization.data(withJSONObject: reply))
        guard page.cursor >= since, page.updates.count <= 200,
              !page.more || page.cursor > since || (page.cursor_id ?? -1) > (sinceID ?? -1) else {
            throw DeviceAPIError(502, "invalid_response", "The feedback service returned an invalid update cursor.")
        }
        return page
    }

    private struct BudgetReceipt: Decodable {
        struct Row: Decodable {
            struct Limit: Decodable {
                let id: String
                let per: String
                let usd: Double
            }
            let applet: String
            let source: String
            let state: String
            let limits: [Limit]

            func confirms(_ expected: [AppletSpendingLimit]) -> Bool {
                guard ["ok", "warning", "out"].contains(state),
                      (1...8).contains(limits.count), Set(limits.map(\.id)).count == limits.count,
                      limits.allSatisfy({ !$0.id.isEmpty && (0.01...100).contains($0.usd) && ["hour", "day", "week", "month"].contains($0.per) }) else { return false }
                if source == "owner" || source == "agent" { return true }
                // The service rounds package defaults to four decimal places.
                return source == "package" && limits.count == expected.count && expected.allSatisfy { declared in
                    limits.contains { $0.id == declared.id && $0.per == declared.per && abs($0.usd - declared.usd) <= 0.00005000001 }
                }
            }
        }
        let applets: [Row]
    }

    /// Register the package defaults using the same installation that will make the paid call.
    /// Sync preserves owner/agent overrides. A successful empty response provides no protection.
    private func confirmBudget(package: [String: Any], applet: String) async throws {
        guard registered, package["id"] as? String == applet else {
            throw DeviceAPIError(409, "budget_unconfirmed", "The installed applet could not be verified for paid work.")
        }
        let limits = try AppletSpendingLimit.limits(for: package)
        let encoded = try JSONSerialization.jsonObject(with: JSONEncoder().encode(limits))
        let body = try JSONSerialization.data(withJSONObject: [
            "timezone": TimeZone.current.identifier,
            "applets": [["applet": applet, "name": package["name"] as? String ?? applet,
                         "package_budget": ["limits": encoded]]]
        ], options: [.sortedKeys])
        let request = try signer.signedRequest(url: base.appendingPathComponent("v1/budgets/sync"), method: "POST", body: body)
        let reply: [String: Any]
        do { reply = try await json(request) }
        catch { throw DeviceAPIError(409, "budget_unconfirmed", "The spending limit could not be checked. Try again before starting paid work.") }
        guard let receipt = try? JSONDecoder().decode(BudgetReceipt.self, from: JSONSerialization.data(withJSONObject: reply)),
              receipt.applets.count == 1, let row = receipt.applets.first,
              row.applet == applet, row.confirms(limits) else {
            throw DeviceAPIError(409, "budget_unconfirmed", "The service did not confirm this applet’s spending limit.")
        }
        if row.state == "out" { throw DeviceAPIError(409, "applet_budget", "This applet has reached its spending limit.") }
    }

    public func appraise(jpeg: Data, room: String? = nil, maxItems: Int = 30, applet: String, action: String = "main", package: [String: Any], authorize: () async throws -> Void = {}) async throws -> [String: Any] {
        guard registered else { throw DeviceAPIError(409, "install_not_registered", "Allow model calls and register this device before paid work.") }
        guard !jpeg.isEmpty, jpeg.count <= 8 * 1024 * 1024, (1...30).contains(maxItems), room?.count ?? 0 <= 60 else {
            throw DeviceAPIError(400, "invalid_parameter", "Use a JPEG, at most 30 items, and a room name of at most 60 characters.")
        }
        var payload: [String: Any] = ["image": "data:image/jpeg;base64," + jpeg.base64EncodedString(), "max_items": maxItems]
        if let room { payload["room"] = room }
        let body = try JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys])
        try await confirmBudget(package: package, applet: applet)
        try await authorize()
        var request = try signer.signedRequest(url: base.appendingPathComponent("v1/appraise"), method: "POST", body: body)
        request.setValue(applet, forHTTPHeaderField: "X-Bridgit-Applet"); request.setValue(action, forHTTPHeaderField: "X-Bridgit-Action")
        // Paid requests are never retried implicitly. The caller must inspect durable batch state first.
        return try await json(request)
    }
    /// Speech shares the signed identity and applet budgets. No model call retries implicitly.
    public func speech(operation: String, payload: [String: Any], applet: String, package: [String: Any], authorize: () async throws -> Void = {}) async throws -> [String: Any] {
        guard registered else { throw DeviceAPIError(409, "install_not_registered", "Enable speech and register this device first.") }
        guard ["resolve", "hear", "speak"].contains(operation) else { throw DeviceAPIError(400, "invalid_parameter", "Invalid speech operation.") }
        if operation != "resolve", payload["allow_cloud"] as? Bool != true { throw DeviceAPIError(403, "speech_cloud_consent_required", "Cloud speech needs explicit permission.") }
        let body = try JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys])
        guard body.count <= 1_950_000 else { throw DeviceAPIError(413, "request_too_large", "Speech input is too large.") }
        if operation != "resolve" { try await confirmBudget(package: package, applet: applet) }
        try await authorize()
        var request = try signer.signedRequest(url: base.appendingPathComponent("v1/speech/" + operation), method: "POST", body: body)
        request.timeoutInterval = 25
        request.setValue(applet, forHTTPHeaderField: "X-Bridgit-Applet")
        request.setValue("main", forHTTPHeaderField: "X-Bridgit-Action")
        return try await json(request)
    }
    /// A signed call for Bridgit names (/v1/names…). Registers this install first, and once more when the
    /// backend has forgotten it. Returns the status and body; non-2xx answers are the caller's to read.
    public func teamCall(method: String, path: String, body: [String: Any]?, appVersion: String) async throws -> (Int, [String: Any]) {
        guard path.hasPrefix("/v1/names") else { throw DeviceAPIError(400, "invalid_parameter", "Only Bridgit names calls are allowed here.") }
        if !registered { _ = try await register(appVersion: appVersion) }
        var (status, reply) = try await signedCall(method: method, path: path, body: body)
        if status == 401, reply["error"] as? String == "unknown_install" {
            registered = false
            _ = try await register(appVersion: appVersion)
            (status, reply) = try await signedCall(method: method, path: path, body: body)
        }
        return (status, reply)
    }

    private func signedCall(method: String, path: String, body: [String: Any]?) async throws -> (Int, [String: Any]) {
        try Task.checkCancellation()
        try outboundAccess.require()
        let data = try body.map { try JSONSerialization.data(withJSONObject: $0, options: [.sortedKeys]) } ?? Data()
        guard let url = URL(string: base.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + path) else {
            throw DeviceAPIError(400, "invalid_parameter", "Invalid names path.")
        }
        var request = try signer.signedRequest(url: url, method: method, body: data)
        if method == "GET" { request.httpBody = nil }
        request.timeoutInterval = 15
        let (bytes, response) = try await http.data(for: request)
        guard bytes.count <= 64 * 1024 else { throw DeviceAPIError(502, "response_too_large", "The names service response is too large.") }
        return ((response as? HTTPURLResponse)?.statusCode ?? 0, (try? JSONSerialization.jsonObject(with: bytes)) as? [String: Any] ?? [:])
    }

    private func json(_ request: URLRequest) async throws -> [String: Any] {
        try Task.checkCancellation()
        try outboundAccess.require()
        let (data, response) = try await http.data(for: request)
        guard data.count <= 8 * 1024 * 1024 else { throw DeviceAPIError(502, "response_too_large", "The model response is too large.") }
        let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            throw DeviceAPIError(status, object["error"] as? String ?? "request_failed", object["message"] as? String ?? "The model service refused this request (\(status)).")
        }
        return object
    }
}
