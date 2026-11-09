import BridgitCore
import BridgitAppletRuntime
import Foundation

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
    private let http: URLSession
    private let outboundAccess: OutboundAccess

    init(base: URL = WatchClient.defaultBase, session: String? = nil, http: URLSession = .shared, outboundAccess: OutboundAccess = DeviceOutboundPolicy.access) {
        self.base = base
        self.session = session
        self.http = http
        self.outboundAccess = outboundAccess
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

    nonisolated private static func assetBytes(_ request: URLRequest, using http: URLSession, size: Int, outboundAccess: OutboundAccess) async throws -> Data {
        try outboundAccess.require()
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
            files[name] = try await Self.assetBytes(req, using: http, size: descriptor.size, outboundAccess: outboundAccess)
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
        let reply: LinkReply = try await send("POST", "v1/watch/link", body: ["code": code])
        guard reply.linked else { throw APIError(status: 502, code: "link_unconfirmed", message: "Bridgit could not confirm that the phone was linked.") }
    }

    /// Removes a phone from this account (same as Remove on bridgit.cc/myapps). Its stored lines are deleted.
    func unlink(phone: String) async throws -> Bool? {
        let reply: UnlinkReply = try await send("POST", "v1/watch/unlink", body: ["phone": phone])
        guard reply.unlinked else { throw APIError(status: 502, code: "unlink_unconfirmed", message: "Bridgit could not confirm removal of the phone.") }
        return reply.deletion_pending
    }

    /// Google-auth access (docs/apple-api.md): ask a phone linked to this account for a live pairing.
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

    func signOut(session previous: String? = nil) async {
        var r = request("DELETE", "v1/watch/session")
        if let previous { r.setValue("Bearer " + previous, forHTTPHeaderField: "Authorization") }
        _ = try? await raw(r)
    }

    // MARK: plumbing

    private struct LinkReply: Decodable { let linked: Bool }
    private struct UnlinkReply: Decodable {
        let unlinked: Bool
        let deletion_pending: Bool?
    }

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
        r.setValue("Bridgit-Apple/1", forHTTPHeaderField: "User-Agent")
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
        return try JSONDecoder().decode(T.self, from: data)
    }

    private func raw(_ r: URLRequest) async throws -> Data {
        try outboundAccess.require()
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
