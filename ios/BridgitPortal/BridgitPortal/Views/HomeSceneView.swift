import SwiftUI
import WebKit

/// The APK's Home "Level 1 Diagnostics" scene: the very same page (app/src/main/assets/diagnostics/,
/// copied into the app bundle at build time), with its animations, fed with the phone's state the way
/// DiagnosticsScene.kt feeds it on Android. The page gets no network and no file access beyond its folder.
struct HomeSceneView: UIViewRepresentable {
    @ObservedObject var live: PhoneLive
    /// Another phone than this device's own is shown: the hexagon becomes the HUB star.
    var otherPhone = false
    var onOpen: (String) -> Void
    @Environment(\.colorScheme) private var scheme

    func makeCoordinator() -> Coordinator { Coordinator(onOpen: onOpen) }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .nonPersistent()
        config.defaultWebpagePreferences.allowsContentJavaScript = true
        config.preferences.javaScriptCanOpenWindowsAutomatically = false
        let ucc = config.userContentController
        // window.BridgitHome is the Android JavascriptInterface; here it posts to the native side.
        let shim = """
        document.documentElement.dataset.theme = \(scheme == .dark ? "'dark'" : "'light'");
        window.BridgitHome = {
          ready: function(){ webkit.messageHandlers.bridgitHome.postMessage({op:'ready'}); },
          setPaused: function(p){ webkit.messageHandlers.bridgitHome.postMessage({op:'setPaused', value:!!p}); },
          open: function(id){ webkit.messageHandlers.bridgitHome.postMessage({op:'open', value:String(id)}); },
          older: function(){ webkit.messageHandlers.bridgitHome.postMessage({op:'older'}); },
          line: function(id, json){ webkit.messageHandlers.bridgitHome.postMessage({op:'line', id:id, value:String(json)}); },
          rerun: function(){ webkit.messageHandlers.bridgitHome.postMessage({op:'rerun'}); }
        };
        """
        ucc.addUserScript(WKUserScript(source: shim, injectionTime: .atDocumentStart, forMainFrameOnly: true))
        // The scene ends above the tab bar, so an iPhone has less height than the phone itself. On short screens
        // the log preview shows 4 lines instead of 6, which keeps the map at its usual phone size and the whole
        // scene on screen. The "Log" side bar (84px tall in scene.html) must shrink with it. iPad is unchanged.
        let fit = """
        var s = document.createElement('style');
        s.textContent = '@media (max-height: 720px) { #logBox { --preview-height: 63px; } #logBar { min-height: 0; } #stage { min-height: 400px; } }';
        document.head.appendChild(s);
        """
        ucc.addUserScript(WKUserScript(source: fit, injectionTime: .atDocumentEnd, forMainFrameOnly: true))
        // WebKit centres a Material Symbols ligature ("photo_camera") on the width of its spelled-out name, so the
        // icons sat beside their bubbles. The glyphs are one em wide: start-anchor them and shift back half an em
        // (the same fix as the Devices map).
        let icons = """
        var s = document.createElement('style');
        s.textContent = '#scene .ms { text-anchor: start; transform: translateX(-0.5em); }';
        document.head.appendChild(s);
        """
        ucc.addUserScript(WKUserScript(source: icons, injectionTime: .atDocumentEnd, forMainFrameOnly: true))
        // Portal-only additions (the viewing device, the phone's name in the hexagon); scene.html itself is the APK's file.
        if let url = Bundle.main.url(forResource: "portal-overlay", withExtension: "js", subdirectory: "diagnostics"), let js = try? String(contentsOf: url, encoding: .utf8) {
            ucc.addUserScript(WKUserScript(source: js, injectionTime: .atDocumentEnd, forMainFrameOnly: true))
        }
        ucc.add(context.coordinator, name: "bridgitHome")

        let web = WKWebView(frame: .zero, configuration: config)
        web.navigationDelegate = context.coordinator
        web.uiDelegate = context.coordinator
        web.allowsLinkPreview = false
        web.isOpaque = false
        web.backgroundColor = UIColor(B.sceneBackground)
        web.scrollView.backgroundColor = UIColor(B.sceneBackground)
        web.accessibilityLabel = "Phone diagnostics: features, control agents and live activity"
        context.coordinator.web = web
        context.coordinator.theme = scheme == .dark ? "dark" : "light"
        context.coordinator.live = live
        if let dir = Bundle.main.url(forResource: "diagnostics", withExtension: nil) {
            var c = URLComponents(url: dir.appendingPathComponent("scene.html"), resolvingAgainstBaseURL: false)!
            c.queryItems = [URLQueryItem(name: "theme", value: scheme == .dark ? "dark" : "light")]
            web.loadFileURL(c.url!, allowingReadAccessTo: dir)
        }
        return web
    }

    func updateUIView(_ web: WKWebView, context: Context) {
        let theme = scheme == .dark ? "dark" : "light"
        if context.coordinator.theme != theme {
            context.coordinator.theme = theme
            web.evaluateJavaScript("document.documentElement.dataset.theme = '\(theme)'")
            web.backgroundColor = UIColor(B.sceneBackground)
        }
        context.coordinator.onOpen = onOpen
        context.coordinator.otherPhone = otherPhone
        context.coordinator.live = live
        context.coordinator.push()
    }

    static func dismantleUIView(_ web: WKWebView, coordinator: Coordinator) {
        web.configuration.userContentController.removeScriptMessageHandler(forName: "bridgitHome")
    }

    @MainActor
    final class Coordinator: NSObject, WKScriptMessageHandler, WKNavigationDelegate, WKUIDelegate {
        var onOpen: (String) -> Void
        weak var web: WKWebView?
        weak var live: PhoneLive?
        var theme = ""
        var otherPhone = false
        private var ready = false
        private var played = false
        private var nodes: [(String, [String])] = []
        private var lastOptions = ""
        private var lastStatus = ""
        private var shownLogIds = Set<Int>()
        private var historyLoaded = false

        init(onOpen: @escaping (String) -> Void) { self.onOpen = onOpen }

        nonisolated func userContentController(_ ucc: WKUserContentController, didReceive message: WKScriptMessage) {
            guard let body = message.body as? [String: Any], let op = body["op"] as? String else { return }
            let value = body["value"] as? String
            Task { @MainActor in
                switch op {
                case "ready": self.onReady()
                case "open": if let value { self.onOpen(value) }
                case "older": await self.live?.loadOlderLogs(); self.pushHistory(append: true)
                case "rerun": await self.live?.refresh(force: true); self.played = false; self.push()
                default: break // setPaused is read-only from the portal; "line" history isn't needed here
                }
            }
        }

        private func onReady() {
            web?.evaluateJavaScript("JSON.stringify(BridgitScene.nodes.map(function(n){return [n.id, n.caps];}))") { raw, _ in
                Task { @MainActor in
                    if let s = raw as? String, let arr = try? JSONSerialization.jsonObject(with: Data(s.utf8)) as? [[Any]] {
                        self.nodes = arr.compactMap { n in (n.first as? String).map { ($0, (n.last as? [String]) ?? []) } }
                    }
                    self.ready = true
                    self.push()
                }
            }
        }

        /// Sends the phone's state to the scene: the full animated run once, then quiet updates.
        func push() {
            guard ready, let live, let web else { return }
            let opts = sceneOptions(live)
            let key = json(opts)
            if !played {
                played = true
                lastOptions = key
                web.evaluateJavaScript("BridgitScene.setup(\(key))")
            } else if key != lastOptions {
                lastOptions = key
                web.evaluateJavaScript("BridgitScene.apply(\(key))")
            }
            web.evaluateJavaScript("BridgitScene.setPaused(\(live.paused))")
            web.evaluateJavaScript("BridgitScene.status(\(json(["listening": live.listening])))")
            if let hs = live.homeStatus {
                let s = json(hs)
                if s != lastStatus { lastStatus = s; web.evaluateJavaScript("BridgitHomeStatus.update(\(s))") }
            }
            pushHistory(append: false)
            var isLive = false
            switch live.source { case .live, .sample: isLive = true; default: break }
            web.evaluateJavaScript("window.BridgitPortal && (BridgitPortal.hub(\(json([live.device.name]))[0], \(otherPhone)), BridgitPortal.viewer(\(json(["kind": UIDevice.current.model, "live": isLive]))))")
        }

        /// The stored log as history once, then new entries as live "packets".
        private func pushHistory(append: Bool) {
            guard ready, let live, let web else { return }
            let fresh = live.logs.filter { !shownLogIds.contains($0.id) }
            guard !fresh.isEmpty || !historyLoaded else { return }
            fresh.forEach { shownLogIds.insert($0.id) }
            let entries = json(fresh.sorted { $0.id < $1.id }.map(\.sceneJSON))
            if !historyLoaded || append {
                historyLoaded = true
                web.evaluateJavaScript("BridgitScene.history(\(entries), \(live.hasOlder))")
            } else {
                web.evaluateJavaScript("BridgitScene.events(\(entries))")
            }
        }

        /// The scene's own option shape (DiagnosticsScene.options() on Android). A phone with the portal API
        /// sends it ready-made; otherwise node states are worked out from capability states like Android does.
        private func sceneOptions(_ live: PhoneLive) -> [String: Any] {
            var o = live.sceneOptions ?? [:]
            if o["nodes"] == nil {
                let caps = o["capStates"] as? [String: String] ?? [:]
                let direct = o["nodeStates"] as? [String: String] ?? [:]
                var states: [String: String] = [:]
                var checks: [String: Any] = [:]
                for (id, list) in nodes {
                    if let st = direct[id] {
                        states[id] = st
                        checks[id] = [["Feature Switch", st == "off" ? "Off" : "On"], ["Access", st == "blocked" ? "Needs access" : "OK"]]
                    } else if id == "tailscale" {
                        states[id] = live.facts.connections["tailscale"] == "connected" ? "on" : "off"
                    } else if id == "clef" {
                        states[id] = caps["watches"] ?? live.facts.services["clef"].map { $0 == "ready" || $0 == "on" ? "on" : "off" } ?? "off"
                    } else {
                        states[id] = list.lazy.compactMap { caps[$0] }.first ?? "off"
                        if let s = states[id] { checks[id] = [["Feature Switch", s == "off" ? "Off" : "On"], ["Access", s == "blocked" ? "Needs access" : "OK"]] }
                    }
                }
                let agents = o["agents"] as? [[String: Any]] ?? []
                o = ["nodes": states, "checks": checks, "agents": agents, "agent": agents.first?["name"] ?? NSNull(),
                     "called": !agents.isEmpty, "externalDevices": [Any](), "emailReminder": false]
            }
            return o
        }

        private func json(_ v: Any) -> String {
            guard JSONSerialization.isValidJSONObject(v), let d = try? JSONSerialization.data(withJSONObject: v) else { return "null" }
            return String(data: d, encoding: .utf8) ?? "null"
        }

        // Only the bundled page itself may load; links and new windows are refused, as on Android.
        func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping @MainActor (WKNavigationActionPolicy) -> Void) {
            decisionHandler(action.request.url?.isFileURL == true ? .allow : .cancel)
        }

        func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for action: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? { nil }
    }
}
