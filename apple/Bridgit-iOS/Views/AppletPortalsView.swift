import SwiftUI
import UIKit
import WebKit

/// The portal page's own light colours (APK res/values/colors_portal.xml), so the bar above the page matches it.
enum PortalColors {
    static let paper = B.surface
    static let card = B.surfaceLow
    static let ink = B.onSurface
}

/// Applets › Portals (APK AppletPortalsFragment): each applet's web portal on bridgit.cc/myapps, the page the owner
/// sees on a computer, in its compact in-app layout. One portal shows its name; several get a light picker.
struct AppletPortalsView: View {
    @EnvironmentObject var model: AppModel
    var portals: [AppletLists.Portal]
    @Binding var current: String?
    @ObservedObject var web: AppletPageBrowser
    var visible: Bool
    @AppStorage(DeviceWebLinkView.linkedKey, store: AppleFixtureWorkspace.defaults) private var webAccountLinked = false
    @State private var webLink = false
    var showApplets: () -> Void

    var open: AppletLists.Portal? { portals.first { $0.id == current } ?? portals.first }

    var body: some View {
        VStack(spacing: 0) {
        if let open {
            VStack(spacing: 0) {
                if visible { bar(open) }
                ZStack(alignment: .top) {
                    PortalWebView(url: open.url, session: model.mode == .signedIn ? model.cloud.session : nil,
                                  email: model.email, web: web, visible: visible)
                        // A new sign-in (or sign-out) rebuilds the view with the matching start-up script.
                        .id(model.mode == .signedIn ? model.cloud.session ?? "" : "")
                    if visible, let problem = web.problem {
                        ContentUnavailableView("Webpage not available", systemImage: "globe", description: Text(problem))
                            .frame(maxWidth: .infinity, maxHeight: .infinity).background(B.surface)
                    }
                    if visible && web.loading { ProgressView().progressViewStyle(.linear).tint(PortalColors.ink) }
                }
            }
            .background(PortalColors.paper)
        } else if visible {
            empty
        }
        }
    }

    func bar(_ open: AppletLists.Portal) -> some View {
        HStack {
            if portals.count > 1 {
                Menu {
                    ForEach(portals) { p in
                        Button { current = p.id } label: {
                            if p.id == open.id { Label(p.name, systemImage: "checkmark") } else { Text(p.name) }
                        }
                    }
                } label: {
                    HStack {
                        Text(open.name).font(.body).foregroundStyle(PortalColors.ink)
                        Spacer()
                        Image(systemName: "chevron.up.chevron.down").font(.footnote.weight(.semibold)).foregroundStyle(PortalColors.ink)
                    }
                    .padding(.horizontal, 14).frame(minHeight: 44)
                    .background(PortalColors.card, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(PortalColors.ink.opacity(0.25), lineWidth: 1))
                }
                .accessibilityLabel("Page: \(open.name)")
            } else {
                Text(open.name).font(.headline).foregroundStyle(PortalColors.ink)
                    .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
            }
            Button { web.reload() } label: { Image(systemName: "arrow.clockwise").frame(minWidth: 44, minHeight: 44).contentShape(Rectangle()) }
                .accessibilityLabel("Reload page")
            Button { web.openInBrowser(fallback: open.url) } label: { Image(systemName: "arrow.up.right.square").frame(minWidth: 44, minHeight: 44).contentShape(Rectangle()) }
                .accessibilityLabel("Open in browser")
        }
        .padding(.horizontal, 16).padding(.top, 8)
        .background(PortalColors.paper)
    }

    var empty: some View {
        ScrollView {
            EmptyAppletPages(
                webLinkTitle: UIDevice.current.userInterfaceIdiom == .pad ? "Link this iPad to the web" : "Link this phone to the web",
                offerWebLink: !webAccountLinked && model.mode != .sample,
                showApplets: showApplets, showWebLink: { webLink = true }
            )
            .sheet(isPresented: $webLink) { DeviceWebLinkView() }
            .padding(.horizontal, 16)
            .frame(maxWidth: 900, alignment: .leading)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

/// The portal page itself. A persistent website data store (one sign-in covers every portal, like a browser); the
/// user agent gains "BridgitApp/<version>" so the page switches to its in-app layout (the website's myapps/in-app.js).
/// When the app is signed in, a start-up script hands the page the app's session (see AppletLists.signInScript).
/// Pages off bridgit.cc open in Safari.
struct PortalWebView: UIViewRepresentable {
    var url: URL
    var session: String?
    var email: String?
    var web: AppletPageBrowser
    var visible: Bool

    func makeCoordinator() -> Coordinator { Coordinator(web: web) }

    func makeUIView(context: Context) -> UIView {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .default()
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"
        // Keep Safari's "Mobile/…" token (the default application name) so the page stays in its phone layout.
        if AppleFixtureWorkspace.enabled { config.setURLSchemeHandler(OfflineAppletPage(), forURLScheme: OfflineAppletPage.scheme) }
        config.applicationNameForUserAgent = "Mobile/15E148 BridgitApp/\(version)"
        config.userContentController.addUserScript(WKUserScript(source: AppletLists.signInScript(session: session, email: email),
                                                                injectionTime: .atDocumentStart, forMainFrameOnly: true))
        config.preferences.javaScriptCanOpenWindowsAutomatically = false
        let v = WKWebView(frame: .zero, configuration: config)
        v.navigationDelegate = context.coordinator
        v.uiDelegate = context.coordinator
        v.allowsBackForwardNavigationGestures = true
        v.isOpaque = false
        v.backgroundColor = UIColor(B.surface)
        v.scrollView.backgroundColor = v.backgroundColor
        context.coordinator.page = v
        web.view = v
        return UIView()

    }

    func updateUIView(_ container: UIView, context: Context) {
        guard let v = context.coordinator.page else { return }
        guard visible else {
            // WebKit can continue vending its remote accessibility subtree while
            // a hidden web view is attached. Keep the page alive off hierarchy.
            v.removeFromSuperview()
            return
        }
        if v.superview == nil {
            v.frame = container.bounds
            v.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            container.addSubview(v)
        }
        guard context.coordinator.loaded != url else { return }
        context.coordinator.loaded = url
        v.load(OfflineAppletPage.request(for: url))
    }

    static func dismantleUIView(_ container: UIView, coordinator: Coordinator) {
        coordinator.page?.stopLoading()
        coordinator.page = nil
    }

    @MainActor
    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate {
        let web: AppletPageBrowser
        var page: WKWebView?
        var loaded: URL?
        init(web: AppletPageBrowser) { self.web = web }

        func webView(_ w: WKWebView, decidePolicyFor action: WKNavigationAction,
                     decisionHandler: @escaping @MainActor (WKNavigationActionPolicy) -> Void) {
            guard let url = action.request.url else { decisionHandler(.cancel); return }
            // Frames inside the page (sign-in widgets, embeds) follow the page's own rules.
            if let frame = action.targetFrame, !frame.isMainFrame { decisionHandler(.allow); return }
            if AppleFixtureWorkspace.enabled {
                decisionHandler(["about", OfflineAppletPage.scheme].contains(url.scheme ?? "") || OfflineAppletPage.isLocalTestPage(url) ? .allow : .cancel)
                return
            }
            if url.scheme == "about" { decisionHandler(.allow); return }
            if AppletLists.staysInPortal(url) {
                if action.targetFrame == nil { w.load(action.request); decisionHandler(.cancel) } else { decisionHandler(.allow) }
                return
            }
            UIApplication.shared.open(url)
            decisionHandler(.cancel)
        }

        /// window.open and target=_blank: bridgit.cc pages load here, anything else opens in Safari.
        func webView(_ w: WKWebView, createWebViewWith c: WKWebViewConfiguration, for action: WKNavigationAction,
                     windowFeatures: WKWindowFeatures) -> WKWebView? {
            if AppleFixtureWorkspace.enabled {
                if let url = action.request.url, OfflineAppletPage.isLocalTestPage(url) { w.load(action.request) }
                return nil
            }
            if let url = action.request.url {
                if AppletLists.staysInPortal(url) { w.load(action.request) } else { UIApplication.shared.open(url) }
            }
            return nil
        }

        func webView(_ w: WKWebView, didStartProvisionalNavigation n: WKNavigation!) { web.started(w) }
        func webView(_ w: WKWebView, didFinish n: WKNavigation!) { web.finished(w) }
        func webView(_ w: WKWebView, didFail n: WKNavigation!, withError e: Error) { web.failed(w, error: e, fallback: loaded) }
        func webView(_ w: WKWebView, didFailProvisionalNavigation n: WKNavigation!, withError e: Error) { web.failed(w, error: e, fallback: loaded) }
    }
}
