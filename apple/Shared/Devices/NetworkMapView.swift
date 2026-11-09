import SwiftUI
import WebKit
#if os(macOS)
import AppKit
typealias NetworkMapRepresentable = NSViewRepresentable
#else
import UIKit
typealias NetworkMapRepresentable = UIViewRepresentable
#endif

struct NetworkMapSnapshot {
    var name = ""
    var categories: [LanCategory] = []
    var added: [AddedDevice] = []
    var scanning = false
    var scanFound: [String]?
    var scanNote: String?
    /// Explicit adapter label; nil preserves the selected device name.
    var coreLabel: String?
}

/// The APK's Devices map page in a web view, fed like DevicesScene.kt. Only the bundled page and logos load.
struct NetworkMapView: NetworkMapRepresentable {
    var snapshot: NetworkMapSnapshot
    var visible: Bool
    var replay: Int
    var onOpen: (String) -> Void
    @Environment(\.colorScheme) private var scheme

    #if os(macOS)
    func makeNSView(context: Context) -> WKWebView { makeWebView(context: context) }
    func updateNSView(_ web: WKWebView, context: Context) { updateWebView(web, context: context) }
    static func dismantleNSView(_ web: WKWebView, coordinator: Coordinator) { dismantle(web, coordinator: coordinator) }
    #else
    func makeUIView(context: Context) -> WKWebView { makeWebView(context: context) }
    func updateUIView(_ web: WKWebView, context: Context) { updateWebView(web, context: context) }
    static func dismantleUIView(_ web: WKWebView, coordinator: Coordinator) { dismantle(web, coordinator: coordinator) }
    #endif

    func makeCoordinator() -> Coordinator { Coordinator(onOpen: onOpen, replay: replay) }

    private func makeWebView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .nonPersistent()
        config.defaultWebpagePreferences.allowsContentJavaScript = true
        config.preferences.javaScriptCanOpenWindowsAutomatically = false
        let ucc = config.userContentController
        // window.BridgitDevicesApp is the APK's JavascriptInterface; the page reads it when its script starts.
        let shim = """
        window.BridgitDevicesApp = {
          ready: function(){ webkit.messageHandlers.bridgitDevices.postMessage({op:'ready'}); },
          open: function(id){ webkit.messageHandlers.bridgitDevices.postMessage({op:'open', value:String(id)}); },
          view: function(id){ webkit.messageHandlers.bridgitDevices.postMessage({op:'view', value:String(id)}); }
        };
        """
        ucc.addUserScript(WKUserScript(source: shim, injectionTime: .atDocumentStart, forMainFrameOnly: true))
        // WebKit centres an SVG icon (a Material Symbols ligature such as "screenshot_monitor") on the width of its
        // spelled-out name, so the icons sat beside their bubbles. The glyphs are one em wide: start-anchor them
        // and shift back half an em. The "+N" more-node keeps its own centring. devices.html itself is the APK's file.
        let fix = """
        var s = document.createElement('style');
        s.textContent = '#scene .ms { text-anchor: start; transform: translateX(-0.5em); } #scene .node.more .ms { text-anchor: middle; transform: none; }';
        document.head.appendChild(s);
        """
        ucc.addUserScript(WKUserScript(source: fix, injectionTime: .atDocumentEnd, forMainFrameOnly: true))
        ucc.add(context.coordinator, name: "bridgitDevices")

        let web = WKWebView(frame: .zero, configuration: config)
        web.navigationDelegate = context.coordinator
        web.uiDelegate = context.coordinator
        web.allowsLinkPreview = false
        #if !os(macOS)
        web.isOpaque = false
        web.backgroundColor = UIColor(B.sceneBackground)
        web.scrollView.backgroundColor = UIColor(B.sceneBackground)
        web.scrollView.contentInsetAdjustmentBehavior = .never
        web.scrollView.bounces = false
        web.scrollView.isScrollEnabled = false
        web.scrollView.pinchGestureRecognizer?.isEnabled = false
        #endif
        #if os(macOS)
        web.setAccessibilityLabel("Devices map: " + snapshot.name)
        #else
        web.accessibilityLabel = "Devices map: " + snapshot.name
        #endif
        let c = context.coordinator
        c.web = web; c.snapshot = snapshot; c.theme = scheme == .dark ? "dark" : "light"
        // Read access to the whole bundle, so the page's ../device-brands/ logos load next to diagnostics/.
        if let root = Bundle.main.resourceURL, let page = Bundle.main.url(forResource: "devices", withExtension: "html", subdirectory: "diagnostics") {
            var comps = URLComponents(url: page, resolvingAgainstBaseURL: false)!
            comps.queryItems = [URLQueryItem(name: "theme", value: c.theme)]
            web.loadFileURL(comps.url!, allowingReadAccessTo: root)
        }
        return web
    }

    private func updateWebView(_ web: WKWebView, context: Context) {
        let c = context.coordinator
        let theme = scheme == .dark ? "dark" : "light"
        if c.theme != theme {
            c.theme = theme
            c.applyTheme()
            #if !os(macOS)
            web.backgroundColor = UIColor(B.sceneBackground)
            web.scrollView.backgroundColor = UIColor(B.sceneBackground)
            #endif
        }
        c.onOpen = onOpen
        c.snapshot = snapshot
        c.applyCoreLabel()
        c.visible = visible
        c.replay = replay
        c.push()
    }

    private static func dismantle(_ web: WKWebView, coordinator: Coordinator) {
        web.configuration.userContentController.removeScriptMessageHandler(forName: "bridgitDevices")
    }

    @MainActor
    final class Coordinator: NSObject, WKScriptMessageHandler, WKNavigationDelegate, WKUIDelegate {
        /// The opening animation plays the first time the map shows in each app run (LanCamerasFragment.mapPlayedThisRun).
        static var playedThisRun = false
        var onOpen: (String) -> Void
        weak var web: WKWebView?
        var snapshot = NetworkMapSnapshot()
        var theme = ""
        var visible = false
        var replay: Int
        private var lastReplay: Int
        private var ready = false
        private var drawn = false
        private var scanShown = false
        private var lastKey = ""

        init(onOpen: @escaping (String) -> Void, replay: Int) {
            self.onOpen = onOpen; self.replay = replay; self.lastReplay = replay
        }

        nonisolated func userContentController(_ ucc: WKUserContentController, didReceive message: WKScriptMessage) {
            guard let body = message.body as? [String: Any], let op = body["op"] as? String else { return }
            let value = String((body["value"] as? String ?? "").prefix(200))
            Task { @MainActor in
                switch op {
                case "ready": self.pageReady()
                case "open": if !value.isEmpty { self.onOpen(value) }
                default: break // "view": a group opened; the page's own Groups tab closes it again (no system Back on iOS)
                }
            }
        }

        func pageReady() {
            ready = true
            applyTheme()
            applyCoreLabel()
            push()
        }

        /// The page initializes its theme from the URL. A SwiftUI appearance update can arrive
        /// before navigation completes, so replay the latest value once the page reports ready.
        func applyTheme() {
            guard ready else { return }
            js("document.documentElement.dataset.theme = '\(theme)'")
        }

        func applyCoreLabel() {
            guard ready else { return }
            let label = snapshot.coreLabel ?? snapshot.name
            guard !label.isEmpty,
                  let encoded = try? JSONSerialization.data(withJSONObject: [label]),
                  let array = String(data: encoded, encoding: .utf8) else { return }
            js("""
            (function() { const label = \(array)[0];
              const text = document.getElementById('coresub');
              const scene = document.getElementById('scene');
              if (text) text.textContent = label.length > 14 ? label.slice(0, 13) + '…' : label;
              if (scene) scene.setAttribute('aria-label', 'Devices map: ' + label + ' in the middle and its groups of devices around it.');
            })();
            """)
        }

        /// Sends the phone's devices to the page: the opening animation, quiet redraws, or a Level 2 run.
        func push() {
            guard ready, web != nil else { return }
            if snapshot.scanning {
                if !scanShown {
                    // Level 2 started: the map clears to the added devices and the phone starts its sweep.
                    scanShown = true; drawn = true
                    js("BridgitDevices.scanStart(\(json(DevicesMapOptions.build(categories: snapshot.categories, added: snapshot.added, found: [], core: snapshot.coreLabel ?? snapshot.name))))")
                }
                return
            }
            if scanShown {
                // Finished: the devices it saw land one by one, then the map settles on everything remembered.
                scanShown = false
                if let found = snapshot.scanFound, !found.isEmpty {
                    js("BridgitDevices.scanUpdate(\(json(DevicesMapOptions.build(categories: snapshot.categories, added: snapshot.added, found: found, core: snapshot.coreLabel ?? snapshot.name))))")
                }
                var o = DevicesMapOptions.build(categories: snapshot.categories, added: snapshot.added, core: snapshot.coreLabel ?? snapshot.name)
                lastKey = json(o)
                if snapshot.scanFound == nil, let note = snapshot.scanNote { o["note"] = note }
                js("BridgitDevices.scanEnd(\(json(o)))")
                return
            }
            guard visible else { return }
            let o = DevicesMapOptions.build(categories: snapshot.categories, added: snapshot.added, core: snapshot.coreLabel ?? snapshot.name)
            let key = json(o)
            if !Self.playedThisRun || replay != lastReplay {
                Self.playedThisRun = true; drawn = true; lastReplay = replay; lastKey = key
                js("BridgitDevices.setup(\(key))")
            } else if !drawn || key != lastKey {
                drawn = true; lastKey = key
                js("BridgitDevices.apply(\(key))")
            }
        }

        private func js(_ code: String) { web?.evaluateJavaScript(code) }

        private func json(_ v: Any) -> String {
            guard JSONSerialization.isValidJSONObject(v), let d = try? JSONSerialization.data(withJSONObject: v, options: [.sortedKeys]) else { return "null" }
            return String(data: d, encoding: .utf8) ?? "null"
        }

        // Only the bundled files may load; links and new windows are refused, as on Android.
        func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping @MainActor (WKNavigationActionPolicy) -> Void) {
            decisionHandler(action.request.url?.isFileURL == true ? .allow : .cancel)
        }

        func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for action: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? { nil }
    }
}

