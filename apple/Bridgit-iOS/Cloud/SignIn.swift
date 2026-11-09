import AuthenticationServices
import UIKit
import BridgitCore

/// Signs in through bridgit.cc/app-signin/ in a system web sheet. That page uses the same Google
/// sign-in as bridgit.cc/myapps, gets a `bws_` session from the feedback Worker, then hands it back
/// through an Apple-verified associated-domain callback. No custom scheme carries account credentials.
@MainActor
final class SignIn: NSObject, ASWebAuthenticationPresentationContextProviding {

    typealias Result = SignInAttempt.Result
    private var attempt = SignInAttempt()

    private var current: ASWebAuthenticationSession?
    private var continuation: CheckedContinuation<Result, Error>?
    private var operationID: UUID?

    func run() async throws -> Result {
        try DeviceOutboundPolicy.access.require()
        guard current == nil else { throw APIError(status: 409, code: "sign_in_busy", message: "Sign-in is already open.") }
        let id = UUID(); operationID = id
        let start = attempt.begin(platform: "ios")
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                self.continuation = continuation
                let session = ASWebAuthenticationSession(url: start, callback: .https(host: SignInAttempt.callbackHost, path: SignInAttempt.callbackPath(platform: "ios"))) { [weak self] url, error in
                    Task { @MainActor in
                        guard let self, self.operationID == id else { return }
                        if let url, let result = self.attempt.consume(url) { self.finish(id, result: .success(result)) }
                        else if let error = error as? ASWebAuthenticationSessionError, error.code == .canceledLogin { self.finish(id, result: .failure(CancellationError())) }
                        else { self.finish(id, result: .failure(error ?? APIError(status: 0, code: nil, message: "Sign-in didn't finish. Please try again."))) }
                    }
                }
                session.presentationContextProvider = self
                session.prefersEphemeralWebBrowserSession = false
                current = session
                if !session.start() { finish(id, result: .failure(APIError(status: 409, code: "sign_in_unavailable", message: "The sign-in window could not open."))) }
            }
        } onCancel: { Task { @MainActor [weak self] in if self?.operationID == id { self?.cancel() } } }
    }
    func cancel() {
        let session = current
        if let id = operationID { finish(id, result: .failure(CancellationError())) }
        session?.cancel()
    }
    private func finish(_ id: UUID, result: Swift.Result<Result, Error>) {
        guard operationID == id else { return }
        attempt.cancel()
        let saved = continuation; continuation = nil; operationID = nil; current = nil
        saved?.resume(with: result)
    }

    /// System callback and openURL share the same one-use, initiated sign-in.
    func receive(_ url: URL) -> Bool {
        guard let id = operationID, let result = attempt.consume(url) else { return false }
        let session = current
        finish(id, result: .success(result)); session?.cancel()
        return true
    }
    nonisolated static func parse(_ url: URL) -> Result? { SignInAttempt.parse(url)?.result }

    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.keyWindow }.first ?? ASPresentationAnchor()
    }
}
