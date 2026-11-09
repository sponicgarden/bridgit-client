import Foundation

/// One in-memory native sign-in. State is consumed once and discarded on cancellation.
public struct SignInAttempt {
    public struct Result: Equatable, Sendable {
        public var session: String
        public var email: String?
        public init(session: String, email: String?) { self.session = session; self.email = email }
    }
    public static let callbackHost = "bridgit.cc"
    public static func callbackPath(platform: String) -> String { "/app-signin/complete/" + platform }
    public private(set) var state: String?
    private var expectedPath: String?
    public init() {}
    public mutating func begin(platform: String) -> URL {
        let nonce = UUID().uuidString.replacingOccurrences(of: "-", with: "") + UUID().uuidString.replacingOccurrences(of: "-", with: "")
        state = nonce; expectedPath = Self.callbackPath(platform: platform)
        var url = URLComponents(string: "https://bridgit.cc/app-signin/")!
        url.queryItems = [.init(name: "app", value: platform), .init(name: "state", value: nonce)]
        return url.url!
    }
    public mutating func cancel() { state = nil; expectedPath = nil }
    public mutating func consume(_ url: URL) -> Result? {
        guard let expected = state, let parsed = Self.parse(url), parsed.state == expected, url.path == expectedPath else { return nil }
        cancel()
        return parsed.result
    }
    public static func parse(_ url: URL) -> (state: String, result: Result)? {
        guard url.scheme == "https", url.host == callbackHost, [callbackPath(platform: "ios"), callbackPath(platform: "macos")].contains(url.path),
              url.user == nil, url.password == nil, url.port == nil,
              let parts = URLComponents(url: url, resolvingAgainstBaseURL: false), parts.query == nil,
              let fragment = parts.fragment, let items = URLComponents(string: "x:?" + fragment)?.queryItems,
              items.allSatisfy({ ["session", "email", "state"].contains($0.name) }),
              Set(items.map(\.name)).count == items.count,
              let state = items.first(where: { $0.name == "state" })?.value,
              state.range(of: "^[A-Fa-f0-9]{64}$", options: .regularExpression) != nil,
              let token = items.first(where: { $0.name == "session" })?.value,
              token.range(of: "^bws_[A-Za-z0-9_-]{30,120}$", options: .regularExpression) != nil else { return nil }
        let email = items.first(where: { $0.name == "email" })?.value
        guard (email?.utf8.count ?? 0) <= 320 else { return nil }
        return (state, .init(session: token, email: email))
    }
}
