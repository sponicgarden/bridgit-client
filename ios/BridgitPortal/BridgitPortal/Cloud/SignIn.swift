import AuthenticationServices
import UIKit

/// Signs in through bridgit.cc/portal-signin/ in a system web sheet. That page uses the same Google
/// sign-in as bridgit.cc/myapps, gets a `bws_` session from the feedback Worker, then hands it back
/// with a redirect to bridgitportal://signin#session=…&email=… . No new Google client is needed.
@MainActor
final class SignIn: NSObject, ASWebAuthenticationPresentationContextProviding {
    static let start = URL(string: "https://bridgit.cc/portal-signin/?app=ios")!
    static let scheme = "bridgitportal"

    struct Result { var session: String; var email: String? }

    private var current: ASWebAuthenticationSession?

    func run() async throws -> Result {
        try await withCheckedThrowingContinuation { cont in
            let s = ASWebAuthenticationSession(url: Self.start, callbackURLScheme: Self.scheme) { url, error in
                if let url, let r = Self.parse(url) { cont.resume(returning: r); return }
                if let e = error as? ASWebAuthenticationSessionError, e.code == .canceledLogin {
                    cont.resume(throwing: CancellationError()); return
                }
                cont.resume(throwing: error ?? APIError(status: 0, code: nil, message: "Sign-in didn't finish. Please try again."))
            }
            s.presentationContextProvider = self
            s.prefersEphemeralWebBrowserSession = false
            current = s
            s.start()
        }
    }

    /// Reads bridgitportal://signin#session=bws_…&email=… (fragment or query).
    nonisolated static func parse(_ url: URL) -> Result? {
        guard url.scheme == scheme else { return nil }
        let c = URLComponents(url: url, resolvingAgainstBaseURL: false)
        var items = c?.queryItems ?? []
        if let f = c?.fragment, let fc = URLComponents(string: "x:?" + f) { items += fc.queryItems ?? [] }
        guard let session = items.first(where: { $0.name == "session" })?.value,
              session.range(of: "^bws_[A-Za-z0-9_-]{30,120}$", options: .regularExpression) != nil else { return nil }
        return Result(session: session, email: items.first(where: { $0.name == "email" })?.value)
    }

    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.keyWindow }.first ?? ASPresentationAnchor()
    }
}
