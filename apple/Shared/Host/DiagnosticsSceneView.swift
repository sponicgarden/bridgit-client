import BridgitCore
import SwiftUI
import WebKit
#if os(macOS)
import AppKit
typealias DiagnosticsRepresentable = NSViewRepresentable
#else
import UIKit
typealias DiagnosticsRepresentable = UIViewRepresentable
#endif

/// The APK's Home "Level 1 Diagnostics" scene: the very same page (app/src/main/assets/diagnostics/,
/// copied into the app bundle at build time), with its animations, fed with the phone's state the way
/// DiagnosticsScene.kt feeds it on Android. The page gets no network and no file access beyond its folder.
struct DiagnosticsSnapshot {
    var name: String
    var metadata = ReportedDeviceMetadata()
    var stateAvailable = true
    var missingState: ReportedFeatureState = .unreported
    var deviceID: String?
    var animated = true
    var sourceLabel: String?
    var options: [String: Any] = [:]
    var status: [String: Any] = [:]
    var logs: [[String: Any]] = []
    var hasOlder = false
    var activityReported = false
    var paused = false
    var listening: [String] = []
    var otherDevice = false
    var viewers: [[String: Any]] = []
}

/// Do not animate a made-up successful check before the target reports its state.
struct DiagnosticsSceneView: View {
    var snapshot: DiagnosticsSnapshot
    var onOpen: (String) -> Void
    var onRerun: () async -> Void
    var onOlder: () async -> Void = {}
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var phase
    @State private var nativeMotion = NativeMotionPreference.current
    @ScaledMetric(relativeTo: .body) private var textScale: CGFloat = 1
    var body: some View {
        Group {
            if snapshot.stateAvailable {
                DiagnosticsWebView(snapshot: snapshot, onOpen: onOpen, onRerun: onRerun, onOlder: onOlder, reduceMotion: reduceMotion || nativeMotion, textScale: Double(textScale))
                    .id(snapshot.deviceID)
            } else {
                ContentUnavailableView {
                    Label("Device state is not available", systemImage: "rectangle.dashed")
                } description: {
                    Text("The selected device has not reported its diagnostics yet.")
                } actions: {
                    Button("Retry") { Task { await onRerun() } }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(B.sceneBackground)
            }
        }
        .onChange(of: phase, initial: true) { _, value in if value == .active { nativeMotion = NativeMotionPreference.current } }
        .onReceive(NativeMotionPreference.center.publisher(for: NativeMotionPreference.changed).receive(on: RunLoop.main)) { _ in nativeMotion = NativeMotionPreference.current }
    }
}

private enum NativeMotionPreference {
    static var current: Bool {
#if os(macOS)
        NSWorkspace.shared.accessibilityDisplayShouldReduceMotion
#else
        UIAccessibility.isReduceMotionEnabled
#endif
    }
    static var center: NotificationCenter {
#if os(macOS)
        NSWorkspace.shared.notificationCenter
#else
        .default
#endif
    }
    static var changed: Notification.Name {
#if os(macOS)
        NSWorkspace.accessibilityDisplayOptionsDidChangeNotification
#else
        UIAccessibility.reduceMotionStatusDidChangeNotification
#endif
    }
}

/// One renderer for iPhone, iPad and Mac; its HTML and layout are the APK's own files.
private struct DiagnosticsWebView: DiagnosticsRepresentable {
    var snapshot: DiagnosticsSnapshot
    var onOpen: (String) -> Void
    var onRerun: () async -> Void
    var onOlder: () async -> Void = {}
    @Environment(\.colorScheme) private var scheme
    var reduceMotion: Bool
    var textScale: Double

    func makeCoordinator() -> Coordinator { Coordinator(onOpen: onOpen) }

    private func makeWeb(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .nonPersistent()
        config.defaultWebpagePreferences.allowsContentJavaScript = true
        config.preferences.javaScriptCanOpenWindowsAutomatically = false
        let ucc = config.userContentController
        // window.BridgitHome is the Android JavascriptInterface; here it posts to the native side.
        let shim = """
        window.BridgitReportedState = true;
        document.documentElement.dataset.bridgitReportedState = "true";
        window.BridgitTextScale = \(textScale);
        document.documentElement.style.setProperty('--bridgit-text-scale', String(window.BridgitTextScale));
        window.BridgitNativeMotion = \(reduceMotion ? "true" : "false");
        document.documentElement.dataset.bridgitReducedMotion = \(reduceMotion ? "'true'" : "'false'");
        var motionStyle = document.createElement('style');
        motionStyle.textContent = 'html[data-bridgit-reduced-motion=true] *, html[data-bridgit-reduced-motion=true] *::before, html[data-bridgit-reduced-motion=true] *::after { animation: none !important; transition: none !important; scroll-behavior: auto !important; }';
        document.documentElement.appendChild(motionStyle);
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
        // WebKit centres a Material Symbols ligature ("photo_camera") on the width of its spelled-out name, so the
        // icons sat beside their bubbles. The glyphs are one em wide: start-anchor them and shift back half an em
        // (the same fix as the Devices map).
        let icons = """
        // WebKit otherwise exposes the entire SVG as one image and hides its device chooser.
        var scene = document.getElementById('scene');
        if (scene) scene.setAttribute('role', 'group');
        var s = document.createElement('style');
        s.textContent = '#scene .ms { text-anchor: start; transform: translateX(-0.5em); } @media (max-height: 660px) { #logBox { --preview-height: 63px; } #logBar { min-height: 0; } #stage { min-height: 400px; } }';
        document.head.appendChild(s);
        """
        ucc.addUserScript(WKUserScript(source: icons, injectionTime: .atDocumentEnd, forMainFrameOnly: true))
#if os(macOS)
        // The desktop map owns the full right pane; activity must not take its height.
        let desktop = """
        var sidebar = document.createElement('aside'); sidebar.id = 'desktopActivity';
        sidebar.setAttribute('aria-label', 'Applets and logs');
        var map = document.createElement('main'); map.id = 'desktopMap';
        var header = document.querySelector('.hdrline');
        var agentRow = header.querySelector('.left'); agentRow.id = 'desktopAgentRow';
        document.getElementById('addAgent').textContent = 'Add Control Agent';
        var titleRow = document.createElement('div'); titleRow.id = 'desktopDiagnosticsHeading';
        var title = document.createElement('span'); title.id = 'desktopDiagnosticsTitle';
        title.textContent = 'Level 1 Diagnostics'; title.setAttribute('role', 'heading'); title.setAttribute('aria-level', '1');
        titleRow.appendChild(title); titleRow.appendChild(document.getElementById('rerun'));
        titleRow.appendChild(document.getElementById('hdrCount'));
        header.prepend(titleRow);
        var runStatus = document.getElementById('hdr');
        function updateDesktopStatus() {
          var diagnosticStatus = runStatus.textContent.indexOf('Level 1 Diagnostics') === 0;
          title.textContent = diagnosticStatus ? runStatus.textContent : 'Level 1 Diagnostics';
          runStatus.hidden = diagnosticStatus || !runStatus.textContent;
        }
        new MutationObserver(updateDesktopStatus).observe(runStatus, {childList: true, characterData: true, subtree: true});
        updateDesktopStatus();
        var hexagon = document.getElementById('core'); hexagon.setAttribute('tabindex', '0');
        hexagon.addEventListener('keydown', function(e) {
          if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); hexagon.dispatchEvent(new MouseEvent('click', {bubbles: true})); }
        });
        sidebar.appendChild(header);
        ['homeStatus', 'logBox', 'externalDevices', 'status'].forEach(function(id) {
          sidebar.appendChild(document.getElementById(id));
        });
        ['stage', 'featureDetail', 'compactSummary'].forEach(function(id) {
          map.appendChild(document.getElementById(id));
        });
        map.appendChild(document.querySelector('.legendrow'));
        document.body.appendChild(sidebar); document.body.appendChild(map);
        document.getElementById('logBar').click();
        var desktopStyle = document.createElement('style');
        desktopStyle.textContent = `
          body { display: grid; grid-template-columns: clamp(300px, 28vw, 360px) minmax(0, 1fr); overflow: hidden; }
          #desktopActivity { min-width: 0; min-height: 0; display: flex; flex-direction: column; gap: 12px; padding: 12px; overflow-y: auto; border-right: 1px solid var(--line); }
          #desktopActivity .hdrline { position: static; flex-wrap: wrap; padding: 0; gap: 6px; white-space: normal; }
          #desktopActivity .hdrline .left { flex-wrap: wrap; gap: 8px; width: 100%; }
          #desktopDiagnosticsHeading { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; width: 100%; }
          #desktopDiagnosticsTitle { font: 500 11px/1.4 system-ui, sans-serif; }
          #desktopActivity #hdr[hidden] { display: none; }
          #desktopActivity .hdrline .right:empty { display: none; }
          #desktopActivity #homeStatus, #desktopActivity #logBox, #desktopActivity #externalDevices { margin: 0; }
          #desktopActivity #homeStatus { flex: 0 0 auto; }
          #desktopActivity #appletStatus { flex-direction: column; overflow-x: hidden; }
          #desktopActivity .applet-item { white-space: normal; }
          #desktopActivity #logBox { flex: 1 0 220px; height: auto; min-height: 220px; max-height: none; overflow: hidden; }
          #desktopActivity #logBox:not(.open) { flex: 0 0 auto; height: calc(var(--preview-height) + 14px); min-height: calc(var(--preview-height) + 14px); max-height: calc(var(--preview-height) + 14px); }
          #desktopActivity #logArea { display: flex; flex-direction: column; min-height: 0; }
          #desktopActivity #log { flex: 1; height: auto; min-height: 0; max-height: none; overflow-y: auto; white-space: pre-wrap; overflow-wrap: anywhere; }
          #desktopActivity #log > div { height: auto; overflow: visible; }
          #desktopActivity #emailReminder { position: static; flex: 0 0 28px; }
          #desktopActivity #status { padding: 0; }
          #desktopMap { display: flex; flex-direction: column; min-width: 0; min-height: 0; overflow: auto; }
          #desktopMap #stage { flex: 1 1 0 !important; min-height: 423px; position: relative; }
          #desktopMap #scene { position: absolute; inset: 0; width: 100% !important; height: 100%; }
          #desktopMap .legendrow { flex: 0 0 auto; }
        `;
        document.head.appendChild(desktopStyle);
        """
        ucc.addUserScript(WKUserScript(source: desktop, injectionTime: .atDocumentEnd, forMainFrameOnly: true))
#endif
        // Portal-only additions (the viewing device, the phone's name in the hexagon); scene.html itself is the APK's file.
        if let url = Bundle.main.url(forResource: "portal-overlay", withExtension: "js", subdirectory: "diagnostics"), let js = try? String(contentsOf: url, encoding: .utf8) {
            ucc.addUserScript(WKUserScript(source: js, injectionTime: .atDocumentEnd, forMainFrameOnly: true))
        }
        ucc.add(context.coordinator, name: "bridgitHome")

        let web = WKWebView(frame: .zero, configuration: config)
        web.navigationDelegate = context.coordinator
        web.uiDelegate = context.coordinator
        web.allowsLinkPreview = false
        #if os(macOS)
        web.setValue(false, forKey: "drawsBackground")
        #else
        web.isOpaque = false
        web.backgroundColor = UIColor(B.sceneBackground)
        web.scrollView.backgroundColor = UIColor(B.sceneBackground)
        #endif
        #if os(macOS)
        web.setAccessibilityLabel("Device diagnostics")
        #else
        web.accessibilityLabel = "Device diagnostics"
        #endif
        context.coordinator.web = web
        context.coordinator.reducedMotion = reduceMotion
        context.coordinator.textScale = textScale
        context.coordinator.theme = scheme == .dark ? "dark" : "light"
        context.coordinator.setSnapshot(snapshot)
        context.coordinator.onRerun = onRerun
        context.coordinator.onOlder = onOlder
        if let dir = Bundle.main.url(forResource: "diagnostics", withExtension: nil) {
            var c = URLComponents(url: dir.appendingPathComponent("scene.html"), resolvingAgainstBaseURL: false)!
            c.queryItems = [URLQueryItem(name: "theme", value: scheme == .dark ? "dark" : "light")]
            web.loadFileURL(c.url!, allowingReadAccessTo: dir)
        }
        return web
    }

    private func updateWeb(_ web: WKWebView, context: Context) {
        let theme = scheme == .dark ? "dark" : "light"
        if context.coordinator.theme != theme {
            context.coordinator.theme = theme
            web.evaluateJavaScript("document.documentElement.dataset.theme = '\(theme)'")
            #if !os(macOS)
            web.backgroundColor = UIColor(B.sceneBackground)
            #endif
        }
        context.coordinator.setReducedMotion(reduceMotion)
        context.coordinator.setTextScale(textScale)
        context.coordinator.onOpen = onOpen
        context.coordinator.setSnapshot(snapshot)
        context.coordinator.onRerun = onRerun
        context.coordinator.onOlder = onOlder
        context.coordinator.push()
    }

    #if os(macOS)
    func makeNSView(context: Context) -> WKWebView { makeWeb(context: context) }
    func updateNSView(_ web: WKWebView, context: Context) { updateWeb(web, context: context) }
    static func dismantleNSView(_ web: WKWebView, coordinator: Coordinator) { coordinator.invalidate() }
    #else
    func makeUIView(context: Context) -> WKWebView { makeWeb(context: context) }
    func updateUIView(_ web: WKWebView, context: Context) { updateWeb(web, context: context) }
    static func dismantleUIView(_ web: WKWebView, coordinator: Coordinator) { coordinator.invalidate() }
    #endif

    @MainActor
    final class Coordinator: NSObject, WKScriptMessageHandler, WKNavigationDelegate, WKUIDelegate {
        var onOpen: (String) -> Void
        weak var web: WKWebView?
        var snapshot = DiagnosticsSnapshot(name: "")
        var onRerun: () async -> Void = {}
        var onOlder: () async -> Void = {}
        var theme = ""
        var reducedMotion = false
        var textScale = 1.0
        private var active = true
        private var work: [UUID: Task<Void, Never>] = [:]
        private var ready = false
        private var played = false
        private var nodes: [(String, [String])] = []
        private var lastOptions = ""
        private var lastStatus = ""
        private var shownLogIds = Set<Int>()
        private var historyLoaded = false
        private var oldestLogID: Int?
        private var lastHasOlder = false

        init(onOpen: @escaping (String) -> Void) { self.onOpen = onOpen }

        nonisolated func userContentController(_ ucc: WKUserContentController, didReceive message: WKScriptMessage) {
            guard message.frameInfo.isMainFrame, let body = message.body as? [String: Any], let op = body["op"] as? String else { return }
            let value = body["value"] as? String
            Task { @MainActor in
                guard self.active else { return }
                switch op {
                case "ready": self.onReady()
                case "open": if let value { self.onOpen(value) }
                case "older": self.command { [weak self] in
                    guard let self else { return }; await self.onOlder()
                    guard self.active, !Task.isCancelled else { return }; self.pushHistory(append: true)
                }
                case "rerun": self.command { [weak self] in
                    guard let self else { return }; await self.onRerun()
                    guard self.active, !Task.isCancelled else { return }
                    self.played = false; self.push()
                }
                default: break // setPaused is read-only from the portal; "line" history isn't needed here
                }
            }
        }

        func setSnapshot(_ value: DiagnosticsSnapshot) {
            if snapshot.animated != value.animated {
                played = false
                if active && ready { web?.evaluateJavaScript("BridgitScene.skip()") }
            }
            snapshot = value
        }
        func invalidate() {
            active = false; ready = false
            work.values.forEach { $0.cancel() }; work.removeAll()
            web?.configuration.userContentController.removeScriptMessageHandler(forName: "bridgitHome")
            web?.navigationDelegate = nil; web?.uiDelegate = nil
            web?.stopLoading(); web?.loadHTMLString("", baseURL: nil); web = nil
        }
        private func command(_ action: @escaping @MainActor () async -> Void) {
            let id = UUID()
            work[id] = Task { [weak self] in
                guard let self, self.active else { return }; await action(); self.work[id] = nil
            }
        }
        func setReducedMotion(_ value: Bool) {
            guard value != reducedMotion else { return }
            reducedMotion = value
            guard active, ready else { return }
            web?.evaluateJavaScript("BridgitScene.setReducedMotion(\(value ? "true" : "false"))")
        }
        func setTextScale(_ value: Double) {
            guard value != textScale else { return }
            textScale = value
            guard active, ready else { return }
            web?.evaluateJavaScript("BridgitScene.setTextScale(\(value))")
        }
        private func onReady() {
            guard active, !ready, let web else { return }
            web.evaluateJavaScript("JSON.stringify(BridgitScene.nodes.map(function(n){return [n.id, n.caps];}))") { raw, _ in
                Task { @MainActor in
                    guard self.active, self.web === web else { return }
                    if let s = raw as? String, let arr = try? JSONSerialization.jsonObject(with: Data(s.utf8)) as? [[Any]] {
                        self.nodes = arr.compactMap { n in (n.first as? String).map { ($0, (n.last as? [String]) ?? []) } }
                    }
                    self.ready = true
                    web.evaluateJavaScript("BridgitScene.setTextScale(\(self.textScale))")
                    web.evaluateJavaScript("BridgitScene.setReducedMotion(\(self.reducedMotion ? "true" : "false"))")
                    self.push()
                }
            }
        }

        /// Sends the phone's state to the scene: the full animated run once, then quiet updates.
        func push() {
            guard active, ready, let web else { return }
            let opts = sceneOptions()
            let key = json(opts)
            if !played {
                played = true
                lastOptions = key
                web.evaluateJavaScript(snapshot.animated && !reducedMotion ? "BridgitScene.setup(\(key))" : "BridgitScene.apply(\(key))")
            } else if key != lastOptions {
                lastOptions = key
                web.evaluateJavaScript("BridgitScene.skip(); BridgitScene.apply(\(key))")
            }
            web.evaluateJavaScript("BridgitScene.setPaused(\(snapshot.paused))")
            web.evaluateJavaScript("BridgitScene.status(\(json(["listening": snapshot.listening])))")
            do {
                var hs = snapshot.status
                if hs["applets"] == nil { hs["applets"] = [Any](); hs["emptyApplets"] = "Applet status not reported" }
                let s = json(hs)
                if s != lastStatus { lastStatus = s; web.evaluateJavaScript("BridgitHomeStatus.update(\(s))") }
            }
            web.evaluateJavaScript("BridgitScene.setActivityReported(\(snapshot.activityReported || !snapshot.logs.isEmpty ? "true" : "false"))")
            pushHistory(append: false)
            web.evaluateJavaScript("window.BridgitPortal && (BridgitPortal.hub(\(json([snapshot.name]))[0], \(snapshot.otherDevice), \(json([DeviceTypeDescription.label(snapshot.metadata)]))[0], \(json([snapshot.sourceLabel ?? ""]))[0]), BridgitPortal.viewers(\(json(snapshot.viewers))))")
        }

        /// The stored log as history once, then new entries as live "packets".
        private func pushHistory(append: Bool) {
            guard active, ready, let web else { return }
            let fresh = snapshot.logs.filter { !shownLogIds.contains(($0["id"] as? Int) ?? 0) }
            guard append || !fresh.isEmpty || !historyLoaded || snapshot.hasOlder != lastHasOlder else { return }
            fresh.forEach { shownLogIds.insert(($0["id"] as? Int) ?? 0) }
            let entries = json(fresh.sorted { ($0["id"] as? Int ?? 0) < ($1["id"] as? Int ?? 0) })
            let nextOldest = fresh.compactMap { $0["id"] as? Int }.min()
            let olderPage = nextOldest.map { id in oldestLogID.map { id < $0 } ?? false } ?? false
            lastHasOlder = snapshot.hasOlder
            if !historyLoaded || append || olderPage || fresh.isEmpty || !snapshot.animated {
                historyLoaded = true
                web.evaluateJavaScript("BridgitScene.history(\(entries), \(snapshot.hasOlder))")
            } else {
                web.evaluateJavaScript("BridgitScene.events(\(entries))")
            }
            if let nextOldest { oldestLogID = min(oldestLogID ?? nextOldest, nextOldest) }
        }

        /// The scene's own option shape (DiagnosticsScene.options() on Android). A phone with the portal API
        /// sends it ready-made; otherwise node states are worked out from capability states like Android does.
        private func sceneOptions() -> [String: Any] {
            var o = snapshot.options
            let caps = o["capStates"] as? [String: Any] ?? [:]
            let direct = o["nodes"] as? [String: Any] ?? o["nodeStates"] as? [String: Any] ?? [:]
            let suppliedChecks = o["checks"] as? [String: Any] ?? [:]
            var states: [String: String] = [:]
            var checks: [String: Any] = [:]
            for (id, list) in nodes {
                let raw = direct[id] ?? (id == "clef" ? caps["watches"] : nil) ?? list.lazy.compactMap { caps[$0] }.first
                let state = raw.map { ReportedFeatureState(reported: $0) } ?? snapshot.missingState
                states[id] = state.rawValue
                switch state {
                case .unreported: checks[id] = [["Status", "Not reported"]]
                case .unavailable: checks[id] = [["Availability", raw == nil && snapshot.missingState == .unavailable ? "Unavailable in this build" : "Unavailable"]]
                case .on, .off, .blocked:
                    checks[id] = suppliedChecks[id] ?? [["Reported state", state == .on ? "On" : state == .off ? "Off" : "Needs access"]]
                }
            }
            o["agentsReported"] = o["agents"] != nil || o["agent"] != nil
            let agents = o["agents"] as? [[String: Any]] ?? []
            o["nodes"] = states; o["checks"] = checks; o["reportedOnly"] = true
            if o["agents"] == nil { o["agents"] = agents }
            if o["agent"] == nil { o["agent"] = agents.first?["name"] ?? NSNull() }
            if o["called"] == nil, let called = agents.first?["called"] as? Bool { o["called"] = called }
            if o["externalDevices"] == nil { o["externalDevices"] = [Any]() }
            if o["emailReminder"] == nil { o["emailReminder"] = false }
            return o
        }

        private func json(_ v: Any) -> String {
            guard JSONSerialization.isValidJSONObject(v), let d = try? JSONSerialization.data(withJSONObject: v, options: [.sortedKeys]) else { return "null" }
            return String(data: d, encoding: .utf8) ?? "null"
        }

        // Only the bundled page itself may load; links and new windows are refused, as on Android.
        func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping @MainActor (WKNavigationActionPolicy) -> Void) {
            decisionHandler(action.request.url?.isFileURL == true ? .allow : .cancel)
        }

        func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for action: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? { nil }
    }
}
