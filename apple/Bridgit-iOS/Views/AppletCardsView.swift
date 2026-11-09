import SwiftUI
import WebKit

/// Applets › Cards (APK InteractionsFragment's Inbox / Library switch). Library: the card library bundled with the
/// app, each opening as one screen to try. Inbox: the phone's own cards, which only the phone shows.
struct AppletCardsView: View {
    enum Mode: String, CaseIterable, Identifiable { case inbox = "Inbox", library = "Library"; var id: String { rawValue } }
    @Binding var inboxFilter: LocalInteractionInbox.Filter
    @Binding var mode: Mode

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                Picker("Cards", selection: $mode) {
                    ForEach(Mode.allCases) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
                .frame(maxWidth: 600)
                .frame(maxWidth: .infinity)
                .padding(.top, 4)
                switch mode {
                case .inbox: LocalInteractionInbox(browseLibrary: { mode = .library }, filter: $inboxFilter)
                case .library: library
                }
            }
            .padding(.horizontal, 16).padding(.bottom, 16)
            .frame(maxWidth: 900, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
    }

    var library: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(UIDevice.current.userInterfaceIdiom == .pad
                 ? "Rich cards control agents can show on this iPad. Tap one to try it; your answer stays here."
                 : "Rich cards control agents can show on this phone. Tap one to try it; your answer stays here.")
                .font(.subheadline).foregroundStyle(B.onSurfaceVariant).padding(.bottom, 4)
            let cards = AppletLists.bundledCards
            if cards.isEmpty {
                Text("The card library didn't load.").font(.subheadline).foregroundStyle(B.onSurfaceVariant)
            }
            ForEach(cards) { c in
                NavigationLink(value: c) {
                    CardLibraryRow(card: c)
                }
                .buttonStyle(.plain)
                if c.id != cards.last?.id { Divider() }
            }
        }
    }
}

/// One library card, full screen (APK CardPreviewFragment). It isn't from an agent: an answer is shown on the card
/// and goes nowhere, and Bridgit.act is refused.
struct CardPreviewView: View {
    var card: AppletLists.Card
    @State private var visitID = UUID()
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(UIDevice.current.userInterfaceIdiom == .pad
                 ? "Try it out: your answer stays on this iPad."
                 : "Try it out: your answer stays on this phone.")
                .font(.footnote).foregroundStyle(B.onSurfaceVariant)
                .padding(.horizontal, 16).padding(.bottom, 8)
            CardWebView(html: card.previewHTML).id(visitID)
        }
        .background(B.surface)
        // SwiftUI can retain a popped destination. Each Library visit starts a fresh try-out.
        .onDisappear { visitID = UUID() }
        .navigationTitle(card.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .tabBar)
        .preference(key: LocalDestinationPreference.self, value: true)
    }
}

/// The locked-down view a card runs in, after the APK's CardWebView (CAP-34): Bridgit's CSP first in the page,
/// network constructors removed, no storage kept, no navigation, no new windows or dialogs. The page reaches out
/// only through window.Bridgit.respond (once, at most 16 KB of JSON).
struct CardWebView: UIViewRepresentable {
    var html: String
    var onRespond: (@MainActor (Any) async throws -> Void)? = nil

    static let csp = CardPreviewRules.csp
    static let bridge = CardPreviewRules.bridge
    static let guardScript = CardPreviewRules.guardScript
    static let shim = CardPreviewRules.shim
    static func prepare(_ html: String) -> String { CardPreviewRules.prepare(html) }
    static func parseRespond(_ text: String) -> Any? { CardPreviewRules.parseRespond(text) }
    static func confirmation(answer: Any, title: String, message: String) -> String {
        CardPreviewRules.confirmation(answer: answer, title: title, message: message)
    }

    func makeCoordinator() -> Coordinator { let coordinator = Coordinator(); coordinator.onRespond = onRespond; return coordinator }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .nonPersistent()
        config.preferences.javaScriptCanOpenWindowsAutomatically = false
        config.dataDetectorTypes = []
        config.allowsInlineMediaPlayback = false
        config.mediaTypesRequiringUserActionForPlayback = .all
        config.userContentController.add(WeakHandler(context.coordinator), name: "bridgit")
        let v = WKWebView(frame: .zero, configuration: config)
        v.navigationDelegate = context.coordinator
        v.uiDelegate = context.coordinator
        v.allowsLinkPreview = false
        v.isOpaque = false
        v.backgroundColor = .clear
        context.coordinator.view = v
        v.loadHTMLString(Self.prepare(html), baseURL: nil)
        return v
    }

    func updateUIView(_ v: WKWebView, context: Context) {}

    static func dismantleUIView(_ v: WKWebView, coordinator: Coordinator) {
        v.configuration.userContentController.removeScriptMessageHandler(forName: "bridgit")
        v.stopLoading()
    }

    @MainActor
    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler {
        weak var view: WKWebView?
        var onRespond: (@MainActor (Any) async throws -> Void)?
        private var answered = false
        private var started = false

        func userContentController(_ c: WKUserContentController, didReceive message: WKScriptMessage) {
            guard !answered, message.frameInfo.isMainFrame, let s = message.body as? String, let v = CardWebView.parseRespond(s) else { return }
            answered = true
            let answer: [String: Any] = ["button": NSNull(), "values": [String: Any](), "data": v]
            if let onRespond {
                Task { @MainActor in
                    do {
                        try await onRespond(v)
                        _ = try? await view?.evaluateJavaScript(CardWebView.confirmation(answer: answer, title: "Answer saved", message: ""))
                    } catch { answered = false }
                }
            } else {
                view?.evaluateJavaScript(CardWebView.confirmation(answer: answer, title: "Answer received", message: UIDevice.current.userInterfaceIdiom == .pad ? "This was a try-out, so the answer stays on this iPad." : "This was a try-out, so the answer stays on this phone."))
            }
        }

        /// The card's own load is the only navigation it ever gets.
        func webView(_ w: WKWebView, decidePolicyFor action: WKNavigationAction,
                     decisionHandler: @escaping @MainActor (WKNavigationActionPolicy) -> Void) {
            if !started, action.targetFrame?.isMainFrame == true, action.request.url?.absoluteString == "about:blank" {
                started = true
                decisionHandler(.allow)
            } else {
                decisionHandler(.cancel)
            }
        }

        func webView(_ w: WKWebView, createWebViewWith c: WKWebViewConfiguration, for a: WKNavigationAction,
                     windowFeatures: WKWindowFeatures) -> WKWebView? { nil }
        func webView(_ w: WKWebView, runJavaScriptAlertPanelWithMessage m: String, initiatedByFrame f: WKFrameInfo,
                     completionHandler: @escaping @MainActor () -> Void) { completionHandler() }
        func webView(_ w: WKWebView, runJavaScriptConfirmPanelWithMessage m: String, initiatedByFrame f: WKFrameInfo,
                     completionHandler: @escaping @MainActor (Bool) -> Void) { completionHandler(false) }
        func webView(_ w: WKWebView, runJavaScriptTextInputPanelWithPrompt p: String, defaultText: String?, initiatedByFrame f: WKFrameInfo,
                     completionHandler: @escaping @MainActor (String?) -> Void) { completionHandler(nil) }
    }

    /// The content controller keeps its handler alive; this keeps it from keeping the coordinator alive.
    final class WeakHandler: NSObject, WKScriptMessageHandler {
        weak var target: (any WKScriptMessageHandler)?
        init(_ t: any WKScriptMessageHandler) { target = t }
        func userContentController(_ c: WKUserContentController, didReceive m: WKScriptMessage) {
            target?.userContentController(c, didReceive: m)
        }
    }
}
