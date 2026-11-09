import AppKit
import SwiftUI
import WebKit

struct MacCardPreview: NSViewRepresentable {
    var html: String
    var onRespond: (@MainActor (Any) async throws -> Void)? = nil
    var onContentHeight: ((CGFloat) -> Void)?
    func makeCoordinator() -> Coordinator {
        let coordinator = Coordinator()
        coordinator.onRespond = onRespond
        coordinator.onContentHeight = onContentHeight
        return coordinator
    }
    func makeNSView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .nonPersistent()
        config.preferences.javaScriptCanOpenWindowsAutomatically = false
        config.mediaTypesRequiringUserActionForPlayback = .all
        config.userContentController.add(WeakHandler(context.coordinator), name: "bridgit")
        let web = PreviewWebView(frame: .zero, configuration: config)
        web.onResize = { [weak coordinator = context.coordinator] view in
            coordinator?.measureContent(view)
        }
        web.navigationDelegate = context.coordinator; web.uiDelegate = context.coordinator
        context.coordinator.view = web
        web.loadHTMLString(CardPreviewRules.prepare(html), baseURL: nil)
        return web
    }
    func updateNSView(_ web: WKWebView, context: Context) { }
    static func dismantleNSView(_ web: WKWebView, coordinator: Coordinator) {
        web.configuration.userContentController.removeScriptMessageHandler(forName: "bridgit"); web.stopLoading()
    }
    @MainActor final class Coordinator: NSObject, WKScriptMessageHandler, WKNavigationDelegate, WKUIDelegate {
        weak var view: WKWebView?
        var onRespond: (@MainActor (Any) async throws -> Void)?
        var onContentHeight: ((CGFloat) -> Void)?
        private var loaded = false
        private var measurement = 0
        func webView(_ web: WKWebView, didFinish navigation: WKNavigation!) {
            loaded = true
            measureContent(web)
        }
        func measureContent(_ web: WKWebView) {
            guard loaded, let onContentHeight else { return }
            measurement += 1
            let revision = measurement
            // A card may size its photo in viewport units, so the sheet's resize
            // can change the document height again even after the image decodes.
            let measure = """
                await Promise.allSettled(Array.from(document.images, image => image.decode()));
                await document.fonts.ready;
                return document.documentElement.scrollHeight;
                """
            web.callAsyncJavaScript(measure, arguments: [:], in: nil, in: .defaultClient) { [weak self] result in
                guard self?.measurement == revision, case .success(let value) = result,
                      let height = value as? NSNumber, height.doubleValue.isFinite, height.doubleValue > 0 else { return }
                onContentHeight(CGFloat(height.doubleValue))
            }
        }
        private var answered = false
        private var started = false
        func userContentController(_ c: WKUserContentController, didReceive message: WKScriptMessage) {
            guard !answered, message.frameInfo.isMainFrame, let s = message.body as? String, let value = CardPreviewRules.parseRespond(s) else { return }
            answered = true
            let answer: [String: Any] = ["button": NSNull(), "values": [String: Any](), "data": value]
            if let onRespond {
                Task { @MainActor in
                    do {
                        try await onRespond(value)
                        _ = try? await view?.evaluateJavaScript(CardPreviewRules.confirmation(answer: answer, title: "Answer saved", message: ""))
                    } catch { answered = false }
                }
            } else {
                view?.evaluateJavaScript(CardPreviewRules.confirmation(answer: answer, title: "Answer received", message: "This was a try-out, so the answer stays on this Mac."))
            }
        }
        func webView(_ web: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping @MainActor (WKNavigationActionPolicy) -> Void) {
            if !started, action.targetFrame?.isMainFrame == true, action.request.url?.absoluteString == "about:blank" { started = true; decisionHandler(.allow) }
            else { decisionHandler(.cancel) }
        }
        func webView(_ web: WKWebView, createWebViewWith c: WKWebViewConfiguration, for a: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? { nil }
        func webView(_ w: WKWebView, runJavaScriptAlertPanelWithMessage m: String, initiatedByFrame f: WKFrameInfo, completionHandler: @escaping @MainActor () -> Void) { completionHandler() }
        func webView(_ w: WKWebView, runJavaScriptConfirmPanelWithMessage m: String, initiatedByFrame f: WKFrameInfo, completionHandler: @escaping @MainActor (Bool) -> Void) { completionHandler(false) }
        func webView(_ w: WKWebView, runJavaScriptTextInputPanelWithPrompt p: String, defaultText: String?, initiatedByFrame f: WKFrameInfo, completionHandler: @escaping @MainActor (String?) -> Void) { completionHandler(nil) }
    }
    private final class PreviewWebView: WKWebView {
        var onResize: ((WKWebView) -> Void)?
        override func setFrameSize(_ size: NSSize) {
            let previous = frame.size
            super.setFrameSize(size)
            if previous != size { onResize?(self) }
        }
    }
    final class WeakHandler: NSObject, WKScriptMessageHandler {
        weak var target: (any WKScriptMessageHandler)?
        init(_ target: any WKScriptMessageHandler) { self.target = target }
        func userContentController(_ c: WKUserContentController, didReceive message: WKScriptMessage) { target?.userContentController(c, didReceive: message) }
    }
}

/// Isolated to the current account. Card previews use a separate, network-blocked WebKit configuration.
struct MacPortalPage: NSViewRepresentable {
    var url: URL
    var session: String?
    var email: String?
    var browser: AppletPageBrowser
    func makeCoordinator() -> Coordinator { Coordinator(browser: browser) }
    func makeNSView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration(); config.websiteDataStore = .nonPersistent()
        config.preferences.javaScriptCanOpenWindowsAutomatically = false
        if AppleFixtureWorkspace.enabled { config.setURLSchemeHandler(OfflineAppletPage(), forURLScheme: OfflineAppletPage.scheme) }
        config.applicationNameForUserAgent = "BridgitApp/local"
        config.userContentController.addUserScript(WKUserScript(source: AppletLists.signInScript(session: session, email: email), injectionTime: .atDocumentStart, forMainFrameOnly: true))
        let web = WKWebView(frame: .zero, configuration: config)
        web.navigationDelegate = context.coordinator; web.uiDelegate = context.coordinator
        browser.view = web
        return web
    }
    func updateNSView(_ web: WKWebView, context: Context) {
        guard context.coordinator.requestedURL != url, AppletLists.staysInPortal(url) else { return }
        context.coordinator.requestedURL = url
        web.load(OfflineAppletPage.request(for: url))
    }
    static func dismantleNSView(_ web: WKWebView, coordinator: Coordinator) { web.stopLoading() }
    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate {
        let browser: AppletPageBrowser
        init(browser: AppletPageBrowser) { self.browser = browser }
        var requestedURL: URL?
        func webView(_ page: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) { browser.started(page) }
        func webView(_ page: WKWebView, didFinish navigation: WKNavigation!) { browser.finished(page) }
        func webView(_ page: WKWebView, didFail navigation: WKNavigation!, withError error: Error) { browser.failed(page, error: error, fallback: requestedURL) }
        func webView(_ page: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) { browser.failed(page, error: error, fallback: requestedURL) }

        func webView(_ web: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping @MainActor (WKNavigationActionPolicy) -> Void) {
            guard let url = action.request.url else { decisionHandler(.cancel); return }
            if AppleFixtureWorkspace.enabled {
                decisionHandler(["about", OfflineAppletPage.scheme].contains(url.scheme ?? "") || OfflineAppletPage.isLocalTestPage(url) ? .allow : .cancel)
                return
            }
            if url.scheme == "about" || AppletLists.staysInPortal(url) { decisionHandler(.allow) }
            else { if action.navigationType == .linkActivated { NSWorkspace.shared.open(url) }; decisionHandler(.cancel) }
        }
        func webView(_ web: WKWebView, createWebViewWith c: WKWebViewConfiguration, for action: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
            if AppleFixtureWorkspace.enabled {
                if let url = action.request.url, OfflineAppletPage.isLocalTestPage(url) { web.load(action.request) }
                return nil
            }
            if let url = action.request.url { if AppletLists.staysInPortal(url) { web.load(action.request) } else { NSWorkspace.shared.open(url) } }
            return nil
        }
    }
}
