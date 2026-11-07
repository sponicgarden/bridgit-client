import SwiftUI
import UIKit
import WebKit

/// Lilac frost follows the same light/dark surfaces as the portal canvas and Android chrome.
enum PortalColors {
    static let paper = B.surface
    static let card = B.surfaceContainer
    static let ink = B.onSurface
}

/// The open portal's web view, kept so the Applets ⋮ menu can reload it or open it in Safari.
@MainActor
final class PortalWeb: ObservableObject {
    weak var view: WKWebView?
    @Published var loading = false

    func reload() { view?.reload() }

    func openInBrowser(fallback: URL?) {
        guard let url = view?.url ?? fallback else { return }
        UIApplication.shared.open(url)
    }
}

/// Applets › Portals (APK AppletPortalsFragment): each applet's web portal on bridgit.cc/myapps, the page the owner
/// sees on a computer, in its compact in-app layout. One portal shows its name; several get a light picker.
struct AppletPortalsView: View {
    @EnvironmentObject var model: AppModel
    var portals: [AppletLists.Portal]
    @Binding var current: String?
    @ObservedObject var web: PortalWeb

    var open: AppletLists.Portal? { portals.first { $0.id == current } ?? portals.first }

    var body: some View {
        if let open {
            VStack(spacing: 0) {
                bar(open)
                ZStack(alignment: .top) {
                    PortalWebView(url: open.url, session: model.mode == .signedIn ? model.cloud.session : nil,
                                  email: model.email, web: web)
                        // A new sign-in (or sign-out) rebuilds the view with the matching start-up script.
                        .id(model.mode == .signedIn ? model.cloud.session ?? "" : "")
                    if web.loading { ProgressView().progressViewStyle(.linear).tint(PortalColors.ink) }
                }
            }
            .background(PortalColors.paper)
        } else {
            empty
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
                        Text(open.name).lineLimit(1).truncationMode(.tail).frostText(.body).foregroundStyle(PortalColors.ink)
                        Spacer()
                        Image(systemName: "chevron.up.chevron.down").frostText(.supporting, weight: .semibold).foregroundStyle(PortalColors.ink)
                    }
                    .padding(.horizontal, 14).frame(minHeight: 44)
                    .background(PortalColors.card, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(PortalColors.ink.opacity(0.25), lineWidth: 1))
                }
                .accessibilityLabel("Portal: \(open.name)")
            } else {
                Text(open.name).lineLimit(1).truncationMode(.tail).frostText(.label, weight: .semibold).foregroundStyle(PortalColors.ink)
                    .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
            }
        }
        .padding(.horizontal, 16).padding(.top, 8)
        .background(PortalColors.paper)
    }

    var empty: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                SectionLabel(text: "No portals yet")
                Text("A portal is an applet's web page on bridgit.cc. It shows here once one is turned on. On the phone, set up Pet monitor with a web portal, or link the phone to the web to see your control agents' activity.")
                    .frostText(.supporting).foregroundStyle(B.onSurfaceVariant)
                if model.mode != .signedIn {
                    Text("Phones on your account show their portals here once you sign in under More.")
                        .frostText(.supporting).foregroundStyle(B.onSurfaceVariant)
                }
            }
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
    var web: PortalWeb

    func makeCoordinator() -> Coordinator { Coordinator(web: web) }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .default()
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"
        // Keep Safari's "Mobile/…" token (the default application name) so the page stays in its phone layout.
        config.applicationNameForUserAgent = "Mobile/15E148 BridgitApp/\(version) BridgitPortal/\(version)"
        config.userContentController.addUserScript(WKUserScript(source: AppletLists.signInScript(session: session, email: email),
                                                                injectionTime: .atDocumentStart, forMainFrameOnly: true))
        config.preferences.javaScriptCanOpenWindowsAutomatically = false
        let v = WKWebView(frame: .zero, configuration: config)
        v.navigationDelegate = context.coordinator
        v.uiDelegate = context.coordinator
        v.allowsBackForwardNavigationGestures = true
        v.isOpaque = false
        v.backgroundColor = UIColor(red: 0xFB / 255, green: 0xF5 / 255, blue: 0xEB / 255, alpha: 1)
        v.scrollView.backgroundColor = v.backgroundColor
        web.view = v
        context.coordinator.loaded = url
        v.load(URLRequest(url: url))
        return v
    }

    func updateUIView(_ v: WKWebView, context: Context) {
        web.view = v
        guard context.coordinator.loaded != url else { return }
        context.coordinator.loaded = url
        v.load(URLRequest(url: url))
    }

    static func dismantleUIView(_ v: WKWebView, coordinator: Coordinator) {
        v.stopLoading()
        Task { @MainActor in coordinator.web.loading = false }
    }

    @MainActor
    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate {
        let web: PortalWeb
        var loaded: URL?
        init(web: PortalWeb) { self.web = web }

        func webView(_ w: WKWebView, decidePolicyFor action: WKNavigationAction,
                     decisionHandler: @escaping @MainActor (WKNavigationActionPolicy) -> Void) {
            guard let url = action.request.url else { decisionHandler(.cancel); return }
            // Frames inside the page (sign-in widgets, embeds) follow the page's own rules.
            if let frame = action.targetFrame, !frame.isMainFrame { decisionHandler(.allow); return }
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
            if let url = action.request.url {
                if AppletLists.staysInPortal(url) { w.load(action.request) } else { UIApplication.shared.open(url) }
            }
            return nil
        }

        func webView(_ w: WKWebView, didStartProvisionalNavigation n: WKNavigation!) { web.loading = true }
        func webView(_ w: WKWebView, didFinish n: WKNavigation!) { web.loading = false }
        func webView(_ w: WKWebView, didFail n: WKNavigation!, withError e: Error) { web.loading = false }
        func webView(_ w: WKWebView, didFailProvisionalNavigation n: WKNavigation!, withError e: Error) { web.loading = false }
    }
}
