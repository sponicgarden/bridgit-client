import Foundation

/// Shared sandbox and response contract for local card previews on both Apple platforms.
enum CardPreviewRules {
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
    static func prepare(_ html: String) -> String {
        let neutral = html.replacingOccurrences(of: "http-equiv", with: "data-blocked-equiv", options: .caseInsensitive)
        return "<!DOCTYPE html><html><head><meta http-equiv=\"Content-Security-Policy\" content=\"\(csp)\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<script>\(bridge)</script><script>\(guardScript)</script><script>\(shim)</script></head>" + neutral
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

}

// Installed cards share the preview's network sandbox, but actions are dispatched only
// to the installed edition's maintained library principal.
import BridgitAppletRuntime
import SwiftUI
import WebKit

private enum InstalledCardRules {
    static func prepare(_ html: String) -> String {
        let bridge = """
        (() => {
          const api = Object.freeze({act: async request => {
            try {
              const response = await window.webkit.messageHandlers.appletAction.postMessage(JSON.stringify(request));
              return {ok:true,status:200,body:JSON.parse(response)};
            } catch (error) { return {ok:false,status:400,error:'action_failed',body:{message:String(error)}}; }
          }});
          for (const name of ['Bridgit','Bridget']) Object.defineProperty(window,name,{value:api});
        })();
        """
        let safe = html.replacingOccurrences(of: "http-equiv", with: "data-blocked-equiv", options: .caseInsensitive)
        return "<!DOCTYPE html><html><head><meta http-equiv=\"Content-Security-Policy\" content=\"\(CardPreviewRules.csp)\">" +
          "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><script>\(bridge)</script><script>\(CardPreviewRules.guardScript)</script></head>" + safe
    }
}

@MainActor final class InstalledCardCoordinator: NSObject, WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandlerWithReply {
    var action: (@MainActor (JSONValue) async throws -> JSONValue)?
    weak var web: WKWebView?
    private var pending = 0
    private var tasks: [UUID: Task<Void, Never>] = [:]
    private var initial = false
    init(action: @escaping @MainActor (JSONValue) async throws -> JSONValue) { self.action = action }
    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage, replyHandler: @escaping (Any?, String?) -> Void) {
        guard message.frameInfo.isMainFrame, message.name == "appletAction", message.webView === web,
              pending < 4, let text = message.body as? String, text.utf8.count <= 16384,
              let request = try? JSONDecoder().decode(JSONValue.self, from: Data(text.utf8)), request.fields != nil,
              let action else { replyHandler(nil, "Invalid or closed applet request"); return }
        pending += 1
        let id = UUID()
        tasks[id] = Task { @MainActor in
            defer { pending -= 1; tasks.removeValue(forKey: id) }
            do {
                let result = try await action(request)
                guard self.action != nil else { replyHandler(nil, "Applet closed"); return }
                replyHandler(String(decoding: try JSONEncoder().encode(result), as: UTF8.self), nil)
            } catch { replyHandler(nil, error.localizedDescription) }
        }
    }
    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping @MainActor (WKNavigationActionPolicy) -> Void) {
        if !initial, action.targetFrame?.isMainFrame == true, action.request.url?.absoluteString == "about:blank" { initial = true; decisionHandler(.allow) }
        else { decisionHandler(.cancel) }
    }
    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for action: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? { nil }
    func webView(_ webView: WKWebView, requestMediaCapturePermissionFor origin: WKSecurityOrigin, initiatedByFrame frame: WKFrameInfo, type: WKMediaCaptureType, decisionHandler: @escaping (WKPermissionDecision) -> Void) { decisionHandler(.deny) }
    func makeWeb(_ html: String) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .nonPersistent(); config.preferences.javaScriptCanOpenWindowsAutomatically = false
        config.mediaTypesRequiringUserActionForPlayback = .all
        config.userContentController.addScriptMessageHandler(self, contentWorld: .page, name: "appletAction")
        let view = WKWebView(frame: .zero, configuration: config)
        view.navigationDelegate = self; view.uiDelegate = self; web = view
        view.loadHTMLString(InstalledCardRules.prepare(html), baseURL: nil)
        return view
    }
    func close(_ web: WKWebView) {
        action = nil
        for task in tasks.values { task.cancel() }
        web.configuration.userContentController.removeScriptMessageHandler(forName: "appletAction", contentWorld: .page); web.stopLoading()
    }
}

#if os(macOS)
struct InstalledAppletCardWebView: NSViewRepresentable {
    let html: String
    let action: @MainActor (JSONValue) async throws -> JSONValue
    func makeCoordinator() -> InstalledCardCoordinator { InstalledCardCoordinator(action: action) }
    func makeNSView(context: Context) -> WKWebView { context.coordinator.makeWeb(html) }
    func updateNSView(_ web: WKWebView, context: Context) {}
    static func dismantleNSView(_ web: WKWebView, coordinator: InstalledCardCoordinator) { coordinator.close(web) }
}
#else
struct InstalledAppletCardWebView: UIViewRepresentable {
    let html: String
    let action: @MainActor (JSONValue) async throws -> JSONValue
    func makeCoordinator() -> InstalledCardCoordinator { InstalledCardCoordinator(action: action) }
    func makeUIView(context: Context) -> WKWebView { context.coordinator.makeWeb(html) }
    func updateUIView(_ web: WKWebView, context: Context) {}
    static func dismantleUIView(_ web: WKWebView, coordinator: InstalledCardCoordinator) { coordinator.close(web) }
}
#endif
