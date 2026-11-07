import Foundation
import BridgitAppletRuntime

struct APIError: LocalizedError {
    var status: Int
    var code: String?
    var message: String
    var errorDescription: String? { message }
    var isSignedOut: Bool { status == 401 }
}

/// Talks to the Bridgit feedback Worker that backs bridgit.cc/myapps. A native app sends the
/// `bws_` session as a bearer token, so browser CORS rules don't apply.
@MainActor
final class WatchClient {
    nonisolated static let defaultBase = URL(string: "https://bridgit-feedback.bridgit-app.workers.dev")!

    var base: URL
    var session: String?
    private let makeInstall: (String) throws -> DecisionInstall
    private let http: URLSession

    init(base: URL = WatchClient.defaultBase, session: String? = nil, http: URLSession = .shared, makeInstall: @escaping (String) throws -> DecisionInstall = DecisionInstall.init(owner:)) {
        self.makeInstall = makeInstall
        self.base = base
        self.session = session
        self.http = http
    }

    func phones() async throws -> PhonesResponse { try await get("v1/watch/phones") }

    func events(phone: String, since: Int? = nil, limit: Int = 200) async throws -> EventsResponse {
        var q = [URLQueryItem(name: "phone", value: phone), URLQueryItem(name: "limit", value: String(limit))]
        if let since { q.append(URLQueryItem(name: "since", value: String(since))) }
        return try await get("v1/watch/events", query: q)
    }

    func libraryAccount() async throws -> String {
        struct Reply: Decodable { let library_account: String }
        let reply: Reply = try await get("v1/templates/account")
        return reply.library_account
    }

    func catalog(query: String, after: String, owned: Bool = false) async throws -> CatalogPage {
        let page: CatalogPage = try await get("v1/templates/catalog", query: [
            URLQueryItem(name: "kind", value: "applet"), URLQueryItem(name:"scope",value:owned ? "owned" : "public"),
            URLQueryItem(name: "q", value: query), URLQueryItem(name: "after", value: after),
            URLQueryItem(name: "limit", value: "50")
        ])
        guard page.scope == (owned ? "owned" : "public") || (!owned && page.scope == nil) else {
            throw AppletRuntimeError.invalid("The registry returned a different catalog scope")
        }
        return page
    }

    func catalogRelease(_ entry: CatalogEntry) async throws -> CatalogRelease {
        let data = try await raw(request("GET", "v1/templates/\(entry.key)/\(entry.revision)"))
        return try CatalogRelease(response: data, selection: entry)
    }

    private final class ExactAssetRequest: NSObject, URLSessionTaskDelegate {
        func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                        newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
            completionHandler(nil)
        }
    }

    nonisolated private static func assetBytes(_ request: URLRequest, using http: URLSession, size: Int) async throws -> Data {
        let (stream,response) = try await http.bytes(for:request,delegate:ExactAssetRequest())
        defer { stream.task.cancel() }
        guard (response as? HTTPURLResponse)?.statusCode == 200, response.mimeType == "application/octet-stream",
              response.expectedContentLength == -1 || response.expectedContentLength == Int64(size)
        else { throw AppletRuntimeError.invalid("Registry media unavailable or has the wrong length") }
        var data = Data()
        data.reserveCapacity(size)
        for try await byte in stream {
            guard data.count < size else { throw AppletRuntimeError.invalid("Registry media exceeds the declared length") }
            data.append(byte)
        }
        guard data.count == size else { throw AppletRuntimeError.invalid("Incomplete registry media") }
        return data
    }

    func packageMedia(_ release: RegistryPackage) async throws -> [String:Data] {
        let owner = session
        var files = [String:Data]()
        for (name,descriptor) in release.package.media {
            try Task.checkCancellation()
            guard owner != nil, owner == session else { throw AppletRuntimeError.revoked }
            let pin = release.selection
            guard pin.key.range(of:#"\A[a-zA-Z0-9_-]{1,100}\z"#,options:.regularExpression) != nil,
                  (1...Int(Int32.max)).contains(pin.revision) else { throw AppletRuntimeError.invalid("Invalid media revision") }
            var req = request("GET","v1/templates/\(pin.key)/\(pin.revision)/assets/\(name)")
            req.setValue("application/octet-stream",forHTTPHeaderField:"Accept")
            files[name] = try await Self.assetBytes(req,using:http,size:descriptor.size)
            guard owner == session else { throw AppletRuntimeError.revoked }
        }
        try Task.checkCancellation()
        return files
    }

    func release(_ entry: CatalogEntry) async throws -> RegistryPackage {
        guard case .local(let release) = try await catalogRelease(entry) else {
            throw AppletRuntimeError.unavailable("This edition requires an Android monitoring device.")
        }
        return release
    }

    func reviewUpdate(to destination: RegistryPackage, from installed: LocalAppletLibrary.Edition,
                      in library: LocalAppletLibrary) async throws -> LocalAppletLibrary.Update {
        let pin = destination.selection
        let current = try await library.open(installed)
        var candidate = destination
        if pin.key == installed.id, pin.revision > installed.revision + 1,
           let required = destination.package.definition["migration"]?["from"]?.text,
           required != current.package.version {
            let next = try pin.nextRevision(after:installed.revision)
            do {
                candidate = try await release(next)
            } catch let error as APIError where error.status == 404 {
                throw AppletRuntimeError.unavailable("update through revision " + String(next.revision) + ". Its publisher must make the prerequisite available.")
            }
        }
        return try await library.reviewUpdate(candidate,replacing:installed)
    }

    func applets() async throws -> AppletsResponse { try await get("v1/watch/applets") }

    func applet(_ a: AppletSummary) async throws -> AppletDetail {
        try await get("v1/watch/applet", query: appletQuery(a))
    }

    func gallery(_ a: AppletSummary, before: Double? = nil) async throws -> GalleryResponse {
        var q = appletQuery(a)
        if let before { q.append(URLQueryItem(name: "before", value: String(Int(before)))) }
        return try await get("v1/watch/applet/gallery", query: q)
    }

    func image(_ a: AppletSummary, id: Int) async throws -> Data {
        var q = appletQuery(a)
        q.append(URLQueryItem(name: "id", value: String(id)))
        return try await raw(request("GET", "v1/watch/applet/image", query: q))
    }

    func summarize(_ a: AppletSummary) async throws -> SummaryResult {
        try await send("POST", "v1/watch/applet/summary", query: appletQuery(a), body: [String: String]())
    }

    func linkPreview(code: String) async throws -> LinkPreview {
        try await get("v1/watch/link", query: [URLQueryItem(name: "code", value: code)])
    }

    func link(code: String) async throws {
        let _: Ignored = try await send("POST", "v1/watch/link", body: ["code": code])
    }

    /// Removes a phone from this account (same as Remove on bridgit.cc/myapps). Its stored lines are deleted.
    func unlink(phone: String) async throws {
        let _: Ignored = try await send("POST", "v1/watch/unlink", body: ["phone": phone])
    }

    /// Google-auth access (docs/ios-portal-api.md): ask a phone linked to this account for a live pairing.
    /// The phone's owner must tap Allow on the phone; until then the pairing call answers 202/404.
    func requestPortalAccess(phone: String, deviceName: String) async throws -> PortalRequest {
        try await send("POST", "v1/watch/portal/request", body: ["phone": phone, "device_name": deviceName])
    }

    /// Returns the pairing once after the owner allowed it, nil while still waiting.
    func portalPairing(phone: String, request: String) async throws -> PortalPairingReply? {
        do {
            return try await get("v1/watch/portal/pairing", query: [URLQueryItem(name: "phone", value: phone), URLQueryItem(name: "request", value: request)])
        } catch let e as APIError where e.status == 202 || e.code == "pending" {
            return nil
        }
    }

    func signOut() async {
        _ = try? await raw(request("DELETE", "v1/watch/session"))
    }

    // MARK: plumbing

    private struct Ignored: Decodable {}

    private func appletQuery(_ a: AppletSummary) -> [URLQueryItem] {
        [URLQueryItem(name: "phone", value: a.phone), URLQueryItem(name: "applet", value: a.applet)]
    }

    private func request(_ method: String, _ path: String, query: [URLQueryItem] = [], body: Data? = nil) -> URLRequest {
        var c = URLComponents(url: base.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        if !query.isEmpty { c.queryItems = query }
        var r = URLRequest(url: c.url!)
        r.httpMethod = method
        r.cachePolicy = .reloadIgnoringLocalCacheData
        r.timeoutInterval = 30
        r.setValue("BridgitPortal-iOS/1", forHTTPHeaderField: "User-Agent")
        if let session { r.setValue("Bearer " + session, forHTTPHeaderField: "Authorization") }
        if let body {
            r.httpBody = body
            r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        return r
    }

    private func get<T: Decodable>(_ path: String, query: [URLQueryItem] = []) async throws -> T {
        let data = try await raw(request("GET", path, query: query))
        return try JSONDecoder().decode(T.self, from: data)
    }

    private func send<T: Decodable, B: Encodable>(_ method: String, _ path: String, query: [URLQueryItem] = [], body: B) async throws -> T {
        let data = try await raw(request(method, path, query: query, body: JSONEncoder().encode(body)))
        if T.self == Ignored.self { return Ignored() as! T }
        return try JSONDecoder().decode(T.self, from: data)
    }

    private func raw(_ r: URLRequest) async throws -> Data {
        let (data, response) = try await http.data(for: r)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        if status == 202 { throw APIError(status: 202, code: "pending", message: "Waiting for the phone.") }
        guard (200..<300).contains(status) else {
            let e = try? JSONDecoder().decode(ServerError.self, from: data)
            throw APIError(status: status, code: e?.error, message: e?.message ?? "Request failed (\(status)). Please try again.")
        }
        return data
    }
}

extension WatchClient {
    /// Only entered after the foreground owner's explicit approval for this particular upload.
    func decide(_ decision: LocalAppletLibrary.Decision, reserve: @Sendable () async throws -> Void) async throws -> JSONValue {
        let activeSession = session
        func checkSession() throws {
            try Task.checkCancellation()
            guard activeSession != nil, session == activeSession else { throw AppletRuntimeError.revoked }
        }
        try checkSession()
        struct Features: Decodable { let library_account: String; let decision_services: [String]? }
        let supported: Features = try await get("v1/templates/account")
        guard supported.library_account == decision.owner else { throw AppletRuntimeError.revoked }
        let service = decision.service.rawValue
        let path = "v1/" + service + "/library"
        let capability = [.chef,.appraise].contains(decision.service) ? ".signed-edition-budget.v2" : ".signed-owner-budget.v1"
        guard supported.decision_services?.contains(service + capability) == true else {
            throw AppletRuntimeError.unavailable("account-bound " + service + " decisions on this server")
        }
        let limits = try DecisionInstall.limits(decision.budget)
        let install = try makeInstall(decision.owner)
        let budgetID = decision.packageID

        func post(_ path: String, _ fields: [String:JSONValue], signed: Bool = true) async throws -> JSONValue {
            try checkSession()
            var r = request("POST", path, body:try JSONEncoder().encode(fields))
            r.setValue(nil,forHTTPHeaderField:"Authorization")
            if signed {
                try install.sign(&r,at:Int64(Date().timeIntervalSince1970*1000),nonce:UUID().uuidString)
            }
            if path == "v1/" + service + "/library" {
                if decision.service == .story { r.timeoutInterval = 100 }
                if decision.service == .illustration { r.timeoutInterval = 200 }
                try await reserve()
                try checkSession()
            }
            return try JSONDecoder().decode(JSONValue.self,from:try await raw(r))
        }
        if try await !phones().phones.contains(where: { $0.phone == install.id }) {
            guard let challenge = try await post("v1/install/challenge",[:],signed:false)["challenge"] else {
                throw AppletRuntimeError.invalid("Missing registration challenge")
            }
            let registration = try await post("v1/install/register",["public_key":.string(install.key.publicKey.derRepresentation.base64EncodedString()),
                "challenge":challenge,"attestation_chain":.array([])])
            guard registration["install_id"] == .string(install.id), registration["status"] != .string("blocked") else {
                throw AppletRuntimeError.revoked
            }
            let linkCode = try await post("v1/watch/link-code",["phone_name":.string("Bridgit Apple host"), "device_type":.string("device")])
            guard let code = linkCode["code"]?.text else { throw AppletRuntimeError.invalid("Missing service link code") }
            try checkSession()
            try await link(code:code)
        }

        let applet: JSONValue = .object(["applet":.string(budgetID),"name":.string(decision.name),
                                         "package_budget":.object(["limits":.array(limits)])])
        let synced = try await post("v1/budgets/sync",["library_owner":.string(decision.owner),"timezone":.string(TimeZone.current.identifier),"applets":.array([applet])])
        guard synced["account"] == .string("linked"), case .array(let entries)? = synced["applets"],
              let matched = entries.first(where:{ $0["applet"] == .string(budgetID) }),
              case .array(let enforced)? = matched["limits"], !enforced.isEmpty else {
            throw AppletRuntimeError.invalid("The account applet budget could not be confirmed")
        }
        var body = decision.options
        let image = decision.jpeg.map { JSONValue.string("data:image/jpeg;base64," + $0.base64EncodedString()) }
        if decision.service == .clef {
            body["state"] = .object(["workflow":.string(decision.flow)])
            body["questions"] = .object(decision.questions)
            if let image { body["images"] = .array([image]) }
        } else if decision.service == .appraise || decision.service == .chef {
            guard let image else { throw AppletRuntimeError.invalid("Missing saved photo") }
            body["image"] = image
        }
        var envelope: [String:JSONValue] = ["owner":.string(decision.owner),"applet":.string(budgetID),"action":.string(decision.flow)]
        if [.voice,.speak,.illustration,.chef,.appraise].contains(decision.service) {
            envelope["key"] = .string(decision.libraryKey)
            envelope["revision"] = .number(Double(decision.libraryRevision))
        }
        body["library"] = .object(envelope)
        let response = try await post(path,body)
        if decision.service == .voice || decision.service == .speak {
            guard response["model"]?.text != nil, response["provider_model"]?.text != nil else {
                throw AppletRuntimeError.invalid("Missing voice selection")
            }
            return response
        }
        if decision.service == .illustration {
            guard response["request_id"] == decision.options["request_id"], response["task_key"] == .string("make_fairytale_image"),
                  response["applet_id"] == .string(decision.packageID), response["output_format"] == .string("jpeg"),
                  response["quota"]?.fields != nil else { throw AppletRuntimeError.invalid("Unexpected illustration response") }
            return response
        }
        if decision.service == .story {
            guard let title = response["title"]?.text, !title.isEmpty, title.utf16.count <= 120,
                  let narration = response["narration"]?.text, !narration.isEmpty, narration.utf8.count <= 3200,
                  response["mode"] == decision.options["mode"], response["model"] == .string("z-ai/glm-5.3-flash") else {
                throw AppletRuntimeError.invalid("Incomplete story response")
            }
            return response
        }
        if decision.service == .context {
            guard try JSONEncoder().encode(response).count <= 65536, response["service"] == decision.options["service"], response["data"]?.fields != nil,
                  response["attribution"]?.text != nil, case .number(let cost)? = response["cost_usd"], cost >= 0 else {
                throw AppletRuntimeError.invalid("Incomplete context response")
            }
            return response
        }
        if decision.service == .clef { return try Self.decisionResult(response, questions:decision.questions) }
        if decision.service == .chef, decision.options["mode"] == .string("observe") {
            guard case .bool? = response["cooking"], let action = response["action"]?.text, action.utf16.count <= 160,
                  let tip = response["tip"]?.text, tip.utf16.count <= 400, response["model"] == .string("z-ai/glm-5.3-flash"),
                  case .number(let cost)? = response["cost_usd"], cost >= 0 else { throw AppletRuntimeError.invalid("Incomplete cooking observation") }
            return response
        }
        return try Self.visionResult(response,service:decision.service,maximum:decision.options["max_items"])
    }

    static func visionResult(_ response: JSONValue, service: LocalAppletLibrary.DecisionService, maximum: JSONValue?) throws -> JSONValue {
        guard service == .appraise || service == .chef, case .array(let items)? = response["items"],
              case .number(let maximum) = maximum ?? .number(30), Double(items.count) <= maximum,
              let model = response["model"]?.text, !model.isEmpty,
              case .number(let cost)? = response["cost_usd"], cost >= 0 else {
            throw AppletRuntimeError.invalid("Incomplete vision response")
        }
        for item in items {
            guard item["label"]?.text?.isEmpty == false else { throw AppletRuntimeError.invalid("Missing item label") }
            if service == .appraise {
                let coords = ["x0","y0","x1","y1"].compactMap { key -> Double? in
                    guard case .number(let n)? = item["box"]?[key], (0...1).contains(n) else { return nil }
                    return n
                }
                guard coords.count == 4, coords[0] < coords[2], coords[1] < coords[3] else { throw AppletRuntimeError.invalid("Invalid item box") }
            }
        }
        var result: [String:JSONValue] = ["items":.array(items),"count":.number(Double(items.count)),"model":.string(model),"cost_usd":.number(cost)]
        if let room = response["room"]?.text { result["room"] = .string(room) }
        return .object(result)
    }

    static func decisionResult(_ response: JSONValue, questions: [String:JSONValue]) throws -> JSONValue {
        guard let raw = response["answers"]?.fields, let model = response["model"]?.text,
              case .number(let cost)? = response["cost_usd"], cost >= 0 else {
            throw AppletRuntimeError.invalid("Incomplete Clef response")
        }
        var answers = [String:JSONValue](), confidence = [String:JSONValue]()
        for (key, question) in questions {
            guard let answer = raw[key], let type = question["type"]?.text, answer["type"] == .string(type) else {
                throw AppletRuntimeError.invalid("Missing Clef answer for " + key)
            }
            switch type {
            case "choice":
                guard let choice = answer["choice"]?.text, question["criteria"]?[choice] != nil,
                      case .number(let n)? = answer["confidence"], (0...1).contains(n) else {
                    throw AppletRuntimeError.invalid("Invalid choice for " + key)
                }
                answers[key] = .string(choice); confidence[key] = .number(n)
            case "noul":
                guard case .number(let n)? = answer["noul"], (0...1).contains(n) else { throw AppletRuntimeError.invalid("Invalid probability") }
                answers[key] = .number(n)
            case "score": answers[key] = answer
            default: throw AppletRuntimeError.unavailable(type)
            }
        }
        return .object(["answers":.object(answers),"confidence":.object(confidence),"model":.string(model),"cost_usd":.number(cost)])
    }
}
