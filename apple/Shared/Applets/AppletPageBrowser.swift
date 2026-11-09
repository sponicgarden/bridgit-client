import SwiftUI
import WebKit

@MainActor
final class AppletPageBrowser: ObservableObject {
    #if os(macOS) && DEBUG
    var browserOpener: ((URL) -> Void)?
    #endif
    weak var view: WKWebView?
    @Published var loading = false
    @Published private(set) var problem: String?
    private var retryURL: URL?

    func started(_ page: WKWebView) {
        guard page === view else { return }
        problem = nil
        retryURL = nil
        loading = true
    }

    func finished(_ page: WKWebView) {
        guard page === view else { return }
        loading = false
        problem = nil
        retryURL = nil
    }

    func failed(_ page: WKWebView, error: Error, fallback: URL?) {
        let failure = error as NSError
        guard page === view, !(failure.domain == NSURLErrorDomain && failure.code == NSURLErrorCancelled) else { return }
        let target = failure.userInfo[NSURLErrorFailingURLErrorKey] as? URL
        retryURL = target.flatMap { Self.isAppletPage($0) ? $0 : nil } ?? fallback
        loading = false
        if failure.domain == NSURLErrorDomain && failure.code == NSURLErrorNotConnectedToInternet {
            problem = "Check your internet connection, then reload this page."
        } else { problem = failure.localizedDescription }
    }

    func reload() {
        guard let view else { return }
        if let retryURL {
            view.load(OfflineAppletPage.request(for: retryURL))
        } else { view.reload() }
    }

    private static func isAppletPage(_ url: URL) -> Bool {
        AppletLists.staysInPortal(url) || OfflineAppletPage.isLocalTestPage(url)
    }

    func openInBrowser(fallback: URL) {
        let url = retryURL ?? view?.url.flatMap { Self.isAppletPage($0) ? $0 : nil } ?? fallback
        let destination = OfflineAppletPage.request(for: url).url
        guard let destination, !AppleFixtureWorkspace.enabled || OfflineAppletPage.isLocalTestPage(destination) else { return }
        #if os(iOS)
        UIApplication.shared.open(destination)
        #else
        #if DEBUG
        if let browserOpener { browserOpener(destination); return }
        #endif
        NSWorkspace.shared.open(destination)
        #endif
    }
}

/// WebKit takes its normal failed-navigation path while a simulator fixture stays off the network.
final class OfflineAppletPage: NSObject, WKURLSchemeHandler {
    static let scheme = "bridgit-fixture"

    /// An explicit UI-test port allows WebKit and the system browser to load the same
    /// isolated document. Other fixtures keep their deterministic offline error.
    private static var port: Int? {
        #if DEBUG
        guard AppleFixtureWorkspace.enabled,
              let raw = ProcessInfo.processInfo.environment["BRIDGIT_FIXTURE_APPLET_PAGE_PORT"],
              let port = UInt16(raw), port > 0 else { return nil }
        return Int(port)
        #else
        return nil
        #endif
    }

    static func isLocalTestPage(_ url: URL) -> Bool {
        guard let port else { return false }
        return url.scheme == "http" && url.host == "127.0.0.1" && url.port == port
            && url.user == nil && url.password == nil
    }

    static func request(for url: URL) -> URLRequest {
        guard AppleFixtureWorkspace.enabled, !isLocalTestPage(url) else { return URLRequest(url: url) }
        var local = URLComponents(url: url, resolvingAgainstBaseURL: false) ?? URLComponents()
        local.user = nil
        local.password = nil
        local.scheme = port == nil ? scheme : "http"
        local.host = port == nil ? "offline" : "127.0.0.1"
        local.port = port
        return URLRequest(url: local.url!)
    }

    func webView(_ webView: WKWebView, start urlSchemeTask: WKURLSchemeTask) {
        urlSchemeTask.didFailWithError(URLError(.notConnectedToInternet))
    }
    func webView(_ webView: WKWebView, stop urlSchemeTask: WKURLSchemeTask) {}
}
