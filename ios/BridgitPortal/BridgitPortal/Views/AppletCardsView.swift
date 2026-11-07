import SwiftUI
import WebKit
import BridgitAppletRuntime

/// Applets › Cards (APK InteractionsFragment's Inbox / Library switch). Library: the card library bundled with the
/// app, each opening as one screen to try. Inbox: the phone's own cards, which only the phone shows.
struct AppletCardsView: View {
    enum Mode: String, CaseIterable, Identifiable { case inbox = "Inbox", library = "Library"; var id: String { rawValue } }
    @State private var mode: Mode = .library
    var phoneName: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                Picker("Cards", selection: $mode) {
                    ForEach(Mode.allCases) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
                .frame(maxWidth: 280)
                .padding(.top, 4)
                switch mode {
                case .inbox: inbox
                case .library: library
                }
            }
            .padding(.horizontal, 16).padding(.bottom, 16)
            .frame(maxWidth: 900, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
    }

    var inbox: some View {
        InfoPanel {
            VStack(alignment: .leading, spacing: 8) {
                Text("The inbox lives on the phone").frostText(.label, weight: .semibold).foregroundStyle(B.onSurface)
                Text("Cards your control agents send, and your answers to them, stay on \(phoneName ?? "the phone"). This app can't read them yet. Open Bridgit on the phone and go to Applets › Cards › Inbox.")
                    .frostText(.supporting).foregroundStyle(B.onSurfaceVariant)
            }
        }
    }

    var library: some View {
        VStack(alignment: .leading, spacing: 8) {
            SectionLabel(text: "Card library")
            Text("Rich cards control agents can show on the phone. Tap one to try it; your answer stays in this app.")
                .frostText(.supporting).foregroundStyle(B.onSurfaceVariant).padding(.bottom, 4)
            let cards = AppletLists.bundledCards
            if cards.isEmpty {
                Text("The card library didn't load.").frostText(.supporting).foregroundStyle(B.onSurfaceVariant)
            }
            ForEach(cards) { c in
                NavigationLink(value: c) {
                    Card {
                        HStack(spacing: 8) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(c.title).frostText(.label, weight: .semibold).foregroundStyle(B.onSurface)
                                Text(c.summary).frostText(.supporting).foregroundStyle(B.onSurfaceVariant).lineLimit(3)
                            }
                            Spacer(minLength: 0)
                            Image(systemName: "chevron.right").foregroundStyle(B.onSurfaceVariant)
                        }
                        .multilineTextAlignment(.leading)
                    }
                }
                .buttonStyle(.plain)
            }
        }
    }
}

/// One library card, full screen (APK CardPreviewFragment). It isn't from an agent: an answer is shown on the card
/// and goes nowhere, and Bridgit.act is refused.
struct CardPreviewView: View {
    var card: AppletLists.Card
    var body: some View {
        CardWebView(html: card.html)
            .background(B.surface)
            .navigationTitle(card.title)
            .navigationBarTitleDisplayMode(.inline)
            .ignoresSafeArea(edges: .bottom)
    }
}

/// The locked-down view a card runs in, after the APK's CardWebView (CAP-34): Bridgit's CSP first in the page,
/// network constructors removed, no storage kept, no navigation, no new windows or dialogs. The page reaches out
/// only through window.Bridgit.respond (once, at most 16 KB of JSON).
struct CardWebView: UIViewRepresentable {
    var html: String
    var installedAction: (@MainActor (JSONValue) async throws -> JSONValue)?

    /// CardRules.CSP on Android. (The APK also sends it as a header with `sandbox allow-scripts`; a page loaded
    /// from a string here can only carry the meta tag.)
    static let csp = "default-src 'none'; img-src data:; style-src 'unsafe-inline'; script-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-src 'none'; object-src 'none'"

    /// The native side of window.Bridgit for a try-out: respond goes to the app, act is always refused.
    static let bridge = "(function(){var r=0;var n={respond:function(s){try{window.webkit.messageHandlers.bridgit.postMessage(String(s))}catch(e){}}," +
        "act:function(){var ref=++r;setTimeout(function(){var x={ok:false,status:400,error:'not_available',body:{error:'not_available'," +
        "message:\"This card can't run actions.\"},ref:ref};try{window.__bridgitActed&&window.__bridgitActed(x)}catch(e){}" +
        "try{window.dispatchEvent(new CustomEvent('bridgit:acted',{detail:x}))}catch(e){}},0);return ref}};window.Bridgit=n;window.Bridget=n;})();"

    /// CardRules.GUARD: removes the network constructors before the page's own script runs.
    static let guardScript = "(function(){var w=window;['RTCPeerConnection','webkitRTCPeerConnection','RTCDataChannel'," +
        "'RTCSessionDescription','RTCIceCandidate','RTCRtpSender','RTCRtpReceiver','WebSocket','WebTransport','EventSource'," +
        "'XMLHttpRequest','fetch','Worker','SharedWorker','BroadcastChannel'].forEach(function(k){try{Object.defineProperty(w,k," +
        "{value:undefined,writable:false,configurable:false})}catch(e){}});" +
        "['HTMLIFrameElement','HTMLFrameElement','HTMLObjectElement','HTMLEmbedElement'].forEach(function(t){var P=w[t]&&w[t].prototype;" +
        "if(!P)return;['contentWindow','contentDocument'].forEach(function(k){try{Object.defineProperty(P,k," +
        "{get:function(){return null},configurable:false})}catch(e){}})});" +
        "try{Object.defineProperty(Navigator.prototype,'geolocation',{value:undefined,configurable:false,writable:false})}catch(e){}" +
        "try{if(navigator.serviceWorker)Object.defineProperty(navigator,'serviceWorker',{value:undefined,configurable:false})}catch(e){}" +
        "try{if(navigator.sendBeacon)Object.defineProperty(Navigator.prototype,'sendBeacon',{value:function(){return false},configurable:false})}catch(e){}" +
        "})();"

    /// CardRules.RESPOND_SHIM: lets a page pass any JSON value to respond, and makes act a Promise.
    static let shim = "(function(){var w=window,n=w.Bridgit||w.Bridget;if(!n||typeof n.respond!=='function')return;" +
        "var p={},bad=function(){return Promise.resolve({ok:false,status:400,error:'invalid_parameter',body:null,ref:0})};" +
        "try{Object.defineProperty(w,'__bridgitActed',{value:function(r){var f=r&&p[r.ref];if(f){delete p[r.ref];f(r)}}," +
        "writable:false,configurable:false})}catch(e){}" +
        "var b={respond:function(v){var s=v;if(typeof v!=='string'){if(v===undefined)return;" +
        "try{s=JSON.stringify(v)}catch(e){return}if(typeof s!=='string')return}n.respond(s)}," +
        "act:function(v){if(typeof n.act!=='function')return bad();var s=v;if(typeof v!=='string'){" +
        "try{s=JSON.stringify(v)}catch(e){return bad()}if(typeof s!=='string')return bad()}" +
        "var ref=n.act(s);return new Promise(function(res){p[ref]=res})}};" +
        "['Bridgit','Bridget'].forEach(function(k){try{Object.defineProperty(w,k,{value:b,writable:false,configurable:false})}" +
        "catch(e){try{w[k]=b}catch(e2){}}})})();"

    /// CardRules.prepare: Bridgit's head in front of the card's HTML; the card's own http-equiv tags are neutralised.
    static func prepare(_ html: String, installed: Bool = false) -> String {
        let installedBridge = """
        (() => {
          const act = async request => {
            try {
              const response = await window.webkit.messageHandlers.appletAction.postMessage(JSON.stringify(request));
              return { ok:true, status:200, body:JSON.parse(response) };
            } catch (error) {
              return { ok:false, status:400, error:"action_failed", body:{message:String(error)} };
            }
          };
          const api = Object.freeze({act});
          for (const name of ["Bridgit","Bridget"]) Object.defineProperty(window,name,{value:api});
        })();
        """
        let scripts = installed ? installedBridge : bridge
        let neutral = html.replacingOccurrences(of: "http-equiv", with: "data-blocked-equiv", options: .caseInsensitive)
        return "<!DOCTYPE html><html><head><meta http-equiv=\"Content-Security-Policy\" content=\"\(csp)\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<script>\(scripts)</script><script>\(guardScript)</script><script>\(installed ? "" : shim)</script></head>" + neutral
    }

    /// CardConfirmation.script: the "answer received" panel over the card.
    static func confirmation(answer: Any, title: String, message: String) -> String {
        func json(_ v: Any) -> String {
            (try? JSONSerialization.data(withJSONObject: v, options: [.fragmentsAllowed])).flatMap { String(data: $0, encoding: .utf8) } ?? "null"
        }
        let payload = json(["answer": answer, "close_after_ms": NSNull()])
        return """
        (function(){
        var event=new CustomEvent('bridgit:answered',{cancelable:true,detail:\(payload)});
        if(!document.dispatchEvent(event))return;
        var body=document.body,style=getComputedStyle(body),panel=document.createElement('section');
        panel.setAttribute('role','status');panel.setAttribute('aria-live','polite');
        panel.style.cssText='position:fixed;inset:0;z-index:2147483647;box-sizing:border-box;display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center;padding:28px;';
        var clear=style.backgroundColor==='rgba(0, 0, 0, 0)';
        panel.style.background=clear?'#f5f2eb':style.backgroundColor;
        panel.style.color=clear?'#1f1b16':style.color;panel.style.fontFamily=style.fontFamily;
        var icon=document.createElement('div');icon.textContent='✓';icon.style.cssText='font-size:44px;margin-bottom:16px;';
        var heading=document.createElement('h2');heading.textContent=\(json(title));
        var note=document.createElement('p');note.textContent=\(json(message));
        panel.append(icon,heading,note);body.appendChild(panel);
        document.querySelectorAll('button,input,select,textarea').forEach(function(e){e.disabled=true;});
        })();
        """
    }

    /// One Bridgit.respond argument: at most 16 KB, strict JSON. Nil when refused.
    static func parseRespond(_ s: String) -> Any? {
        guard s.utf8.count <= 16 * 1024, let d = s.data(using: .utf8) else { return nil }
        return try? JSONSerialization.jsonObject(with: d, options: [.fragmentsAllowed])
    }

    func makeCoordinator() -> Coordinator { Coordinator(action:installedAction) }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .nonPersistent()
        config.preferences.javaScriptCanOpenWindowsAutomatically = false
        config.userContentController.addUserScript(WKUserScript(source:AppletPracticeSource.webUI,injectionTime:.atDocumentEnd,forMainFrameOnly:true))
        config.dataDetectorTypes = []
        config.allowsInlineMediaPlayback = false
        config.mediaTypesRequiringUserActionForPlayback = .all
        if installedAction != nil {
            config.userContentController.addScriptMessageHandler(context.coordinator, contentWorld:.page, name:"appletAction")
        } else {
            config.userContentController.add(WeakHandler(context.coordinator), name:"bridgit")
        }
        let v = WKWebView(frame: .zero, configuration: config)
        v.navigationDelegate = context.coordinator
        v.uiDelegate = context.coordinator
        v.allowsLinkPreview = false
        v.isOpaque = false
        v.backgroundColor = .clear
        context.coordinator.view = v
        v.loadHTMLString(Self.prepare(html, installed:installedAction != nil), baseURL: nil)
        return v
    }

    func updateUIView(_ v: WKWebView, context: Context) {}

    static func dismantleUIView(_ v: WKWebView, coordinator: Coordinator) {
        v.configuration.userContentController.removeScriptMessageHandler(forName: "bridgit")
        v.configuration.userContentController.removeScriptMessageHandler(forName:"appletAction", contentWorld:.page)
        coordinator.action = nil
        v.stopLoading()
    }

    @MainActor
    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler, WKScriptMessageHandlerWithReply {
        var action: (@MainActor (JSONValue) async throws -> JSONValue)?
        private var outstanding = 0

        init(action: (@MainActor (JSONValue) async throws -> JSONValue)?) { self.action = action }

        func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage,
                                   replyHandler: @escaping (Any?, String?) -> Void) {
            guard message.frameInfo.isMainFrame, message.name == "appletAction", message.webView === view,
                  outstanding < 4, let text = message.body as? String, text.utf8.count <= 16384,
                  let request = try? JSONDecoder().decode(JSONValue.self,from:Data(text.utf8)),
                  request.fields != nil else { replyHandler(nil,"Invalid applet request"); return }
            outstanding += 1
            Task { @MainActor in
                defer { outstanding -= 1 }
                guard let action else { replyHandler(nil,"Applet closed"); return }
                do {
                    let response = try await action(request)
                    replyHandler(String(decoding:try JSONEncoder().encode(response),as:UTF8.self),nil)
                } catch { replyHandler(nil,error.localizedDescription) }
            }
        }

        @available(iOS 27.0, macCatalyst 27.0, *)
        func webView(_ webView: WKWebView, requestGeolocationPermissionFor origin: WKSecurityOrigin,
                     initiatedByFrame frame: WKFrameInfo, decisionHandler: @escaping (WKPermissionDecision) -> Void) {
            decisionHandler(.deny)
        }

        func webView(_ webView: WKWebView, requestMediaCapturePermissionFor origin: WKSecurityOrigin,
                     initiatedByFrame frame: WKFrameInfo, type: WKMediaCaptureType,
                     decisionHandler: @escaping (WKPermissionDecision) -> Void) { decisionHandler(.deny) }

        weak var view: WKWebView?
        private var answered = false
        private var started = false

        func userContentController(_ c: WKUserContentController, didReceive message: WKScriptMessage) {
            guard !answered, message.frameInfo.isMainFrame, let s = message.body as? String, let v = CardWebView.parseRespond(s) else { return }
            answered = true
            let answer: [String: Any] = ["button": NSNull(), "values": [String: Any](), "data": v]
            view?.evaluateJavaScript(CardWebView.confirmation(answer: answer, title: "Answer received",
                                                              message: "This was a try-out, so the answer stays in this app."))
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
