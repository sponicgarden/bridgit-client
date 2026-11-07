import SwiftUI
import UIKit
import WebKit

// The Devices tab, as on the APK (lancamera/LanCamerasFragment.kt): the network icon and a Map | List switch on the
// top line. Map is the APK's own page (assets/diagnostics/devices.html, fed like lancamera/DevicesScene.kt);
// List is the APK's Network devices list (lan/NetworkDevicesFragment.kt, embedded). The portal only views:
// no owner actions, but Level 2 can run when the phone allows this pairing to scan.

/// Network devices (tab: Devices; More › Network devices): Map and List of what the phone found on its network.
struct NetworkDevicesView: View {
    var live: PhoneLive?
    var body: some View {
        Group {
            if let live { DevicesHost(live: live) } else { EmptyPhone(title: "Devices") }
        }
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// The APK's Devices icon (ic_nav_devices, Material "lan": one box over two).
enum DevicesIcon {
    static let symbol = "point.3.connected.trianglepath.dotted"
}

private struct DevicesHost: View {
    @ObservedObject var live: PhoneLive
    /// The last choice is remembered, as on the APK (devices_tab prefs); Map first.
    @AppStorage("devices.tab") private var tab = "map"
    @State private var replay = 0
    @State private var picked: MapPick?

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                Image(systemName: DevicesIcon.symbol).font(.system(size: 22, weight: .medium)).foregroundStyle(B.primary)
                    .frame(width: 28, height: 28).accessibilityHidden(true)
                Segmented(selection: $tab, onReselect: { if $0 == "map" { replay += 1 } })
            }
            .padding(.horizontal, 16).padding(.top, 10).padding(.bottom, 2)
            .frame(maxWidth: 760)
            ZStack {
                DevicesMapView(live: live, visible: tab == "map", replay: replay) { id in
                    switch id {
                    case "find": Task { await live.runLevel2() }
                    case "list": tab = "list"
                    default: picked = MapPick(id: id)
                    }
                }
                .opacity(tab == "map" ? 1 : 0).allowsHitTesting(tab == "map").accessibilityHidden(tab != "map")
                DevicesList(live: live) { tab = "map"; Task { await live.runLevel2() } }
                    .opacity(tab == "list" ? 1 : 0).allowsHitTesting(tab == "list").accessibilityHidden(tab != "list")
            }
        }
        .background((tab == "map" ? B.sceneBackground : B.surface).ignoresSafeArea(edges: .bottom))
        .task(id: live.device.id) { if live.categories.isEmpty || live.device.isLive { await live.refreshDevices() } }
        .sheet(item: $picked) { p in DevicePickSheet(live: live, id: p.id) }
    }

    /// The APK's segmented Map | List switch: a rounded track, the selected half filled with the primary colour.
    private struct Segmented: View {
        @Binding var selection: String
        var onReselect: (String) -> Void
        @Namespace private var pill
        var body: some View {
            HStack(spacing: 0) {
                ForEach([("map", "Map"), ("list", "List")], id: \.0) { id, title in
                    let on = selection == id
                    Button {
                        if on { onReselect(id) } else { withAnimation(.snappy(duration: 0.25)) { selection = id } }
                    } label: {
                        Text(title).font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(on ? B.onPrimary : B.onSurfaceVariant)
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                            .background { if on { Capsule().fill(B.primary).matchedGeometryEffect(id: "pill", in: pill) } }
                            .contentShape(Capsule())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(on ? [.isSelected] : [])
                }
            }
            .padding(4)
            .frame(height: 52)
            .background(Capsule().fill(B.surfaceHigh))
        }
    }
}

private struct MapPick: Identifiable { var id: String }

// MARK: - Brand logos (assets/device-brands, copied into the bundle)

/// The APK's bundled brand catalog (device-brands/catalog.json): logo files and the name patterns it matches.
struct BrandCatalog {
    struct Entry { var id: String; var name: String; var asset: String; var pattern: NSRegularExpression? }
    let entries: [Entry]
    static let shared = BrandCatalog()

    init(bundle: Bundle = .main) {
        guard let url = bundle.url(forResource: "catalog", withExtension: "json", subdirectory: "device-brands"),
              let data = try? Data(contentsOf: url),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { entries = []; return }
        entries = (obj["brands"] as? [[String: Any]] ?? []).compactMap { b in
            guard let id = b["id"] as? String, let asset = b["asset"] as? String else { return nil }
            return Entry(id: id, name: b["name"] as? String ?? id, asset: asset,
                         pattern: (b["pattern"] as? String).flatMap { try? NSRegularExpression(pattern: $0, options: .caseInsensitive) })
        }
    }

    func entry(_ id: String) -> Entry? { entries.first { $0.id == id.lowercased() } }

    /// The map page's path for a brand's logo, relative to diagnostics/ (DevicesScene.logoOf).
    func mapLogo(_ id: String) -> String? { entry(id).map { "../device-brands/" + $0.asset } }

    /// The first brand whose pattern matches the text (DevicesScene.brandOf, for devices added to Bridgit).
    func match(_ text: String) -> Entry? {
        entries.first { $0.pattern?.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) != nil }
    }
}

/// A brand's logo as a silhouette in the theme's ink, like the APK's tinted brand drawables. SVG logos are
/// rendered to PNG at build time (brand-logos/); without one, the brand's name stands in, as on the APK.
struct BrandLogo: View {
    var brand: LanBrand
    var height: CGFloat
    var maxWidth: CGFloat
    var minWidth: CGFloat = 0
    var alignment: Alignment = .center
    var tint: Color = B.onSurfaceVariant

    var body: some View {
        if let img = BrandImages.image(for: brand.id) {
            let aspect = img.size.width / max(img.size.height, 1)
            Image(uiImage: img).renderingMode(.template).resizable().scaledToFit()
                .foregroundStyle(tint)
                .frame(width: min(maxWidth, max(minWidth, height * aspect)), height: height, alignment: alignment)
                .accessibilityLabel(brand.name)
        } else {
            Text(brand.name).font(.system(size: 10)).foregroundStyle(tint).lineLimit(1)
                .frame(maxWidth: maxWidth, alignment: alignment)
        }
    }
}

@MainActor
enum BrandImages {
    private static var cache: [String: UIImage?] = [:]

    /// The logo trimmed to its drawn pixels (the build-time renders sit on a square canvas).
    static func image(for id: String) -> UIImage? {
        if let hit = cache[id] { return hit }
        var img: UIImage?
        if let e = BrandCatalog.shared.entry(id) {
            let url = e.asset.hasSuffix(".svg")
                ? Bundle.main.url(forResource: e.asset, withExtension: "png", subdirectory: "brand-logos")
                : Bundle.main.url(forResource: (e.asset as NSString).deletingPathExtension, withExtension: (e.asset as NSString).pathExtension, subdirectory: "device-brands")
            img = url.flatMap { UIImage(contentsOfFile: $0.path) }.flatMap { trim($0, backdrop: e.asset.hasSuffix(".svg")) }
        }
        cache[id] = img
        return img
    }

    private static func trim(_ image: UIImage, backdrop: Bool) -> UIImage? {
        guard let cg = image.cgImage else { return image }
        let w = cg.width, h = cg.height
        // The context owns its pixels; px reads and writes them in place.
        guard let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4, space: CGColorSpaceCreateDeviceRGB(),
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue), let data = ctx.data else { return image }
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
        let px = UnsafeMutableBufferPointer(start: data.bindMemory(to: UInt8.self, capacity: ctx.bytesPerRow * h), count: ctx.bytesPerRow * h)
        guard ctx.bytesPerRow == w * 4 else { return image }
        // The build renders each SVG on a mid-grey backdrop (Quick Look would otherwise put white logos on white):
        // the backdrop becomes see-through, so the template tint shows the logo's shape and not a filled box.
        if backdrop {
            let (br, bg, bb) = (0x80, 0x80, 0x80)
            for i in stride(from: 0, to: px.count, by: 4) {
                let alpha = Int(px[i + 3])
                guard alpha > 0 else { continue }
                func c(_ k: Int) -> Int { min(255, Int(px[i + k]) * 255 / alpha) } // un-premultiply
                let d = max(abs(c(0) - br), abs(c(1) - bg), abs(c(2) - bb))
                let a = min(255, max(0, (d - 24) * 4)) * alpha / 255
                px[i] = 0; px[i + 1] = 0; px[i + 2] = 0; px[i + 3] = UInt8(a)
            }
        }
        guard let masked = ctx.makeImage() else { return image }
        var minX = w, minY = h, maxX = -1, maxY = -1
        for y in 0..<h { for x in 0..<w where px[(y * w + x) * 4 + 3] > 12 {
            minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
        } }
        guard maxX >= minX, maxY >= minY, let cut = masked.cropping(to: CGRect(x: minX, y: minY, width: maxX - minX + 1, height: maxY - minY + 1)) else { return nil }
        return UIImage(cgImage: cut, scale: image.scale, orientation: .up)
    }
}

// MARK: - Map (assets/diagnostics/devices.html)

/// What the map page draws, built exactly like DevicesScene.options(): devices added to Bridgit first
/// ("on", or "blocked" while not checked), then remembered devices: "seen" when connected, else "off".
enum DevicesMapOptions {
    /// The map's group order (DevicesScene.MAP_ORDER) with the APK's group titles.
    static let groups: [(String, String)] = [("COMPUTERS", "Computers"), ("NETWORK", "Routers & switches"), ("CAMERAS", "Cameras"),
                                             ("AUDIO", "Audio"), ("MEDIA", "TV & video"), ("PRINTERS", "Printers"),
                                             ("SMART", "Smart home"), ("MOBILE", "Phones & tablets"), ("OTHER", "Other devices")]
    static let maxDevices = 200

    /// `found`: during Level 2, the devices this scan saw (each drawn as seen, with its address).
    static func build(categories: [LanCategory], added: [AddedDevice], found: [String]? = nil, core: String? = nil, catalog: BrandCatalog = .shared,
                      now: Date = Date()) -> [String: Any] {
        struct Entry { var json: [String: Any]; var state: String; var online: Bool }
        let known = Set(groups.map(\.0))
        let addedEntries = added.map { a -> Entry in
            let group: String
            switch a.kind { case "camera": group = "CAMERAS"; case "speaker": group = "AUDIO"; case "computer": group = "COMPUTERS"; default: group = "SMART" }
            var j: [String: Any] = ["id": a.id, "name": a.name, "sub": a.host, "kind": a.kind, "state": a.state, "linked": true, "group": group]
            if let b = catalog.match(a.name + " " + (a.adapter ?? "")) { j["brand"] = b.name; j["logo"] = "../device-brands/" + b.asset }
            return Entry(json: j, state: a.state, online: a.state == "on")
        }
        let addedHosts = Set(added.map(\.host))
        var all: [(LanDevice, String)] = []
        var seenIds = Set<String>()
        for c in categories { for d in c.devices where seenIds.insert(d.id).inserted { all.append((d, c.id)) } }
        let scanning = found != nil
        let pool = found.map { ids in let set = Set(ids); return all.filter { set.contains($0.0.id) } } ?? all
        let observed = pool.filter { !addedHosts.contains($0.0.host ?? $0.0.id) }.enumerated()
            .sorted { a, b in
                let ka = (a.element.0.interfaced ? 1 : 2, a.element.0.connected ? 0 : 1), kb = (b.element.0.interfaced ? 1 : 2, b.element.0.connected ? 0 : 1)
                if ka != kb { return ka < kb }
                let la = a.element.0.lastSeen ?? 0, lb = b.element.0.lastSeen ?? 0
                return la != lb ? la > lb : a.offset < b.offset
            }
            .map { pair -> Entry in
                let (d, catId) = pair.element
                let host = d.host ?? d.id
                let on = d.connected || scanning
                var group = (d.category ?? catId).uppercased()
                if !known.contains(group) { group = "OTHER" }
                var j: [String: Any] = ["id": "seen:" + d.id, "name": d.name,
                                        "sub": (d.interfaced ? "Bridgit · " : "") + (on ? host : LastSeen.relative(d.lastSeen, now: now)),
                                        "kind": d.kind ?? "unknown", "state": on ? "seen" : "off", "linked": false, "group": group]
                if let b = d.brand, let logo = catalog.mapLogo(b.id) { j["brand"] = catalog.entry(b.id)?.name ?? b.name; j["logo"] = logo }
                return Entry(json: j, state: on ? "seen" : "off", online: on)
            }
        let entries = addedEntries + observed
        let shown = entries.prefix(maxDevices)
        var out: [String: Any] = [
            "devices": shown.map(\.json),
            "groups": groups.map { ["id": $0.0, "title": $0.1] },
            "more": entries.count - shown.count,
            "counts": ["total": entries.count, "added": addedEntries.count, "unchecked": addedEntries.filter { $0.state == "blocked" }.count,
                       "seen": observed.filter { $0.state == "seen" }.count, "offline": observed.filter { $0.state == "off" }.count,
                       "online": entries.filter(\.online).count],
        ]
        // The name under the mark: the phone being looked at, never "This phone" on the portal.
        if let core, !core.isEmpty { out["core"] = core }
        return out
    }
}

/// The APK's Devices map page in a web view, fed like DevicesScene.kt. Only the bundled page and logos load.
private struct DevicesMapView: UIViewRepresentable {
    @ObservedObject var live: PhoneLive
    var visible: Bool
    var replay: Int
    var onOpen: (String) -> Void
    @Environment(\.colorScheme) private var scheme

    func makeCoordinator() -> Coordinator { Coordinator(onOpen: onOpen, replay: replay) }

    func makeUIView(context: Context) -> WKWebView {
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
        web.isOpaque = false
        web.backgroundColor = UIColor(B.sceneBackground)
        web.scrollView.backgroundColor = UIColor(B.sceneBackground)
        web.scrollView.contentInsetAdjustmentBehavior = .never
        web.scrollView.bounces = false
        web.scrollView.isScrollEnabled = false
        web.scrollView.pinchGestureRecognizer?.isEnabled = false
        web.accessibilityLabel = "Devices map: \(live.device.name) in the middle and its groups of devices around it"
        let c = context.coordinator
        c.web = web; c.live = live; c.theme = scheme == .dark ? "dark" : "light"
        // Read access to the whole bundle, so the page's ../device-brands/ logos load next to diagnostics/.
        if let root = Bundle.main.resourceURL, let page = Bundle.main.url(forResource: "devices", withExtension: "html", subdirectory: "diagnostics") {
            var comps = URLComponents(url: page, resolvingAgainstBaseURL: false)!
            comps.queryItems = [URLQueryItem(name: "theme", value: c.theme)]
            web.loadFileURL(comps.url!, allowingReadAccessTo: root)
        }
        return web
    }

    func updateUIView(_ web: WKWebView, context: Context) {
        let c = context.coordinator
        let theme = scheme == .dark ? "dark" : "light"
        if c.theme != theme {
            c.theme = theme
            web.evaluateJavaScript("document.documentElement.dataset.theme = '\(theme)'")
            web.backgroundColor = UIColor(B.sceneBackground)
            web.scrollView.backgroundColor = UIColor(B.sceneBackground)
        }
        c.onOpen = onOpen
        c.live = live
        c.visible = visible
        c.replay = replay
        c.push()
    }

    static func dismantleUIView(_ web: WKWebView, coordinator: Coordinator) {
        web.configuration.userContentController.removeScriptMessageHandler(forName: "bridgitDevices")
    }

    @MainActor
    final class Coordinator: NSObject, WKScriptMessageHandler, WKNavigationDelegate, WKUIDelegate {
        /// The opening animation plays the first time the map shows in each app run (LanCamerasFragment.mapPlayedThisRun).
        static var playedThisRun = false
        var onOpen: (String) -> Void
        weak var web: WKWebView?
        weak var live: PhoneLive?
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
                case "ready": self.ready = true; self.push()
                case "open": if !value.isEmpty { self.onOpen(value) }
                default: break // "view": a group opened; the page's own Groups tab closes it again (no system Back on iOS)
                }
            }
        }

        /// Sends the phone's devices to the page: the opening animation, quiet redraws, or a Level 2 run.
        func push() {
            guard ready, let live, web != nil else { return }
            if live.scanning {
                if !scanShown {
                    // Level 2 started: the map clears to the added devices and the phone starts its sweep.
                    scanShown = true; drawn = true
                    js("BridgitDevices.scanStart(\(json(DevicesMapOptions.build(categories: live.categories, added: live.added, found: [], core: live.device.name))))")
                }
                return
            }
            if scanShown {
                // Finished: the devices it saw land one by one, then the map settles on everything remembered.
                scanShown = false
                if let found = live.scanFound, !found.isEmpty {
                    js("BridgitDevices.scanUpdate(\(json(DevicesMapOptions.build(categories: live.categories, added: live.added, found: found, core: live.device.name))))")
                }
                var o = DevicesMapOptions.build(categories: live.categories, added: live.added, core: live.device.name)
                lastKey = json(o)
                if live.scanFound == nil, let note = live.scanNote { o["note"] = note }
                js("BridgitDevices.scanEnd(\(json(o)))")
                return
            }
            guard visible else { return }
            let o = DevicesMapOptions.build(categories: live.categories, added: live.added, core: live.device.name)
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

/// A tap on a device on the map: its details, read only.
private struct DevicePickSheet: View {
    @ObservedObject var live: PhoneLive
    var id: String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    let host = id.hasPrefix("seen:") ? String(id.dropFirst(5)) : nil
                    let added = live.added.first { $0.id == id }
                    let device = PhoneLive.devices(in: live.categories).first { $0.id == host || (added != nil && $0.host == added?.host) }
                    if let added { AddedCard(device: added) }
                    if let device {
                        DeviceRow(device: device, shaded: false, added: added != nil, expandedByDefault: true)
                            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    } else if added == nil {
                        Text("This device is no longer in the phone's list.").foregroundStyle(B.onSurfaceVariant)
                    }
                }
                .padding(16)
            }
            .background(B.surface)
            .navigationTitle(title).navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
    }

    var title: String {
        if let a = live.added.first(where: { $0.id == id }) { return a.name }
        let host = id.hasPrefix("seen:") ? String(id.dropFirst(5)) : id
        return PhoneLive.devices(in: live.categories).first { $0.id == host }?.name ?? "Device"
    }
}

// MARK: - List (lan/NetworkDevicesFragment.kt, embedded)

private enum ListMode: String, CaseIterable { case all, groups, connected, unconnected }

private struct DevicesList: View {
    @ObservedObject var live: PhoneLive
    var onLevel2: () -> Void
    @State private var mode: ListMode = .all
    /// Groups opened or closed by hand; the rest follow the tab (Groups folds them all).
    @State private var open: [String: Bool] = [:]

    private var all: [LanDevice] { PhoneLive.devices(in: live.categories) }
    private var addedHosts: Set<String> { Set(live.added.map(\.host)) }
    /// The APK lists only added cameras above the list (LanCamerasFragment.render).
    private var addedCameras: [AddedDevice] { live.added.filter { $0.id.hasPrefix("lan:") || $0.kind == "camera" } }

    private func visible(_ d: [LanDevice]) -> [LanDevice] {
        switch mode {
        case .connected: d.filter(\.connected)
        case .unconnected: d.filter { !$0.connected }
        default: d
        }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                if !addedCameras.isEmpty {
                    Text("Added to Bridgit").font(.system(size: 16, weight: .bold)).foregroundStyle(B.onSurface)
                        .padding(.leading, 4).padding(.vertical, 6)
                    ForEach(addedCameras) { AddedCard(device: $0).padding(.bottom, 8) }
                }
                if all.isEmpty && live.added.isEmpty { hero.padding(.bottom, 8) }
                HStack {
                    Text("On this network").font(.system(size: 16, weight: .bold)).foregroundStyle(B.onSurface)
                    Spacer(minLength: 8)
                    if live.device.isLive || live.source == .sample {
                        Button(action: onLevel2) {
                            HStack(spacing: 6) {
                                Text("Level 2 Diagnostic").font(.system(size: 14, weight: .medium))
                                Image(systemName: "arrow.clockwise").font(.system(size: 15, weight: .semibold))
                            }
                            .foregroundStyle(B.primary)
                            .padding(.leading, 14).padding(.trailing, 12).frame(minHeight: 44)
                            .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(B.primary, lineWidth: 1))
                        }
                        .buttonStyle(.plain).disabled(live.scanning).opacity(live.scanning ? 0.5 : 1)
                        .accessibilityLabel("Run Level 2 Diagnostic: look around this network")
                    }
                }
                .padding(.leading, 4).padding(.vertical, 6)
                if !all.isEmpty { summary.padding(.top, 2).padding(.bottom, 4) }
                if live.scanning || live.scanNote != nil {
                    HStack(spacing: 8) {
                        if live.scanning { ProgressView().controlSize(.small) }
                        Text(live.scanning ? "Looking for devices…" : live.scanNote ?? "")
                    }
                    .font(.system(size: 13)).foregroundStyle(B.onSurfaceVariant).padding(.horizontal, 2).padding(.vertical, 8)
                }
                ForEach(live.categories) { cat in
                    let devs = visible(cat.devices).enumerated().sorted {
                        (addedHosts.contains($0.element.host ?? $0.element.id) ? 0 : 1, $0.offset) < (addedHosts.contains($1.element.host ?? $1.element.id) ? 0 : 1, $1.offset)
                    }.map(\.element)
                    if !devs.isEmpty {
                        let isOpen = open[cat.id] ?? (mode != .groups)
                        VStack(spacing: 0) {
                            GroupHeader(category: cat, devices: devs, open: isOpen) {
                                withAnimation(.easeInOut(duration: 0.22)) { open[cat.id] = !isOpen }
                            }
                            if isOpen {
                                ForEach(Array(devs.enumerated()), id: \.element.id) { i, d in
                                    DeviceRow(device: d, shaded: i % 2 == 1, added: addedHosts.contains(d.host ?? d.id))
                                }
                            }
                        }
                        .padding(.bottom, 4)
                    }
                }
            }
            .padding(.horizontal, 12).padding(.bottom, 12)
            .frame(maxWidth: 760).frame(maxWidth: .infinity)
        }
        .background(B.surface)
        .refreshable { await live.refreshDevices() }
        .onChange(of: live.categories) { _, _ in
            // Like the APK: a tab with nothing left in it falls back to Devices.
            let on = all.filter(\.connected).count
            if mode == .connected && on == 0 || mode == .unconnected && on == all.count { mode = .all }
        }
    }

    private var hero: some View {
        InfoPanel {
            VStack(alignment: .leading, spacing: 16) {
                HStack(spacing: 14) {
                    Image(systemName: "dot.radiowaves.left.and.right").font(.system(size: 22)).foregroundStyle(B.primary)
                        .frame(width: 48, height: 48).background(B.surfaceHigh).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                    Text("Discover nearby devices").font(.system(size: 22, weight: .bold)).foregroundStyle(B.onSurface)
                }
                Text("Cameras, speakers, TVs and more — discover what’s on your Wi-Fi or Ethernet network. Device types are suggestions. Pairing and control agent access stay in your control.")
                    .font(.system(size: 14)).foregroundStyle(B.onSurfaceVariant)
                if !(live.device.isLive || live.source == .sample) {
                    Text("Pair with the phone for its full network list. The account snapshot only includes cameras.")
                        .font(.system(size: 14)).foregroundStyle(B.onSurfaceVariant)
                }
            }
            .padding(6)
        }
    }

    /// The four tiles acting as tabs, and the connected share as a green bar.
    private var summary: some View {
        let total = all.count, on = all.filter(\.connected).count
        let groups = live.categories.filter { !$0.devices.isEmpty }.count
        return VStack(spacing: 0) {
            HStack(spacing: 2) {
                tile(.all, "\(total)", "Devices", B.onSurface)
                tile(.groups, "\(groups)", "Groups", B.onSurface)
                tile(.connected, "\(on)", "Connected", B.success)
                tile(.unconnected, "\(total - on)", "Unconnected", B.onSurfaceVariant)
            }
            GeometryReader { g in
                ZStack(alignment: .leading) {
                    Capsule().fill(B.outlineVariant)
                    Capsule().fill(B.success).frame(width: total == 0 ? 0 : g.size.width * CGFloat(on) / CGFloat(total))
                }
            }
            .frame(height: 4).padding(.top, 8).accessibilityHidden(true)
        }
        .padding(.horizontal, 12).padding(.vertical, 10)
        .background(B.surfaceLow).clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    private func tile(_ m: ListMode, _ value: String, _ name: String, _ tint: Color) -> some View {
        let on = mode == m
        return Button {
            withAnimation(.easeInOut(duration: 0.2)) { mode = m; open = [:] }
        } label: {
            VStack(alignment: .leading, spacing: 0) {
                Text(value).font(.system(size: 22, weight: .bold)).foregroundStyle(tint).lineLimit(1)
                Text(name).font(.system(size: 11)).foregroundStyle(B.onSurfaceVariant).lineLimit(1).minimumScaleFactor(0.7)
                    .frame(height: 16)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.leading, 6).padding(.trailing, 4).padding(.top, 4).padding(.bottom, 6)
            .background {
                if on {
                    RoundedRectangle(cornerRadius: 10, style: .continuous).fill(B.surfaceHigh)
                        .overlay(alignment: .bottom) { RoundedRectangle(cornerRadius: 2).fill(B.primary).frame(height: 3).padding(.horizontal, 10) }
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(name), \(value)")
        .accessibilityAddTraits(on ? [.isSelected] : [])
    }
}

/// The group colours on the APK (res/values/network_colors.xml and values-night).
private enum GroupColors {
    static let start = B.pair(0xE6DAF5, 0x352547)
    static let end = B.pair(0xF2ECFA, 0x241C35)
    static let text = B.pair(0x482A68, 0xE1CAF8)
}

/// A group's header: gradient row, condensed title, the group's shared brand logos, connected/total, one chevron.
private struct GroupHeader: View {
    var category: LanCategory
    var devices: [LanDevice]
    var open: Bool
    var toggle: () -> Void

    /// The makers in this group, most common first (DeviceBrands.common); the phone's list when none are known here.
    private var brands: [LanBrand] {
        var counts: [String: (LanBrand, Int)] = [:]
        for d in devices { if let b = d.brand { counts[b.id] = (b, (counts[b.id]?.1 ?? 0) + 1) } }
        let list = counts.values.sorted { ($1.1, $0.0.name) < ($0.1, $1.0.name) }.map(\.0)
        return list.isEmpty ? category.brands : list
    }

    var body: some View {
        Button(action: toggle) {
            VStack(spacing: 0) {
                HStack(spacing: 0) {
                    Text(category.title).font(.system(size: 15).width(.condensed)).foregroundStyle(GroupColors.text)
                        .lineLimit(1).frame(maxWidth: 140, alignment: .leading).fixedSize(horizontal: true, vertical: false)
                    FitRow(spacing: 8) {
                        ForEach(brands, id: \.id) { b in BrandLogo(brand: b, height: 20, maxWidth: 52, minWidth: 20) }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading).frame(height: 22)
                    .padding(.leading, 10).padding(.trailing, 6)
                    .accessibilityHidden(true)
                    Text("\(devices.filter(\.connected).count)/\(devices.count)").font(.system(size: 13, weight: .bold)).foregroundStyle(B.primary)
                        .padding(.horizontal, 6).padding(.trailing, 10)
                    Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold)).foregroundStyle(B.onSurfaceVariant)
                        .rotationEffect(.degrees(open ? 90 : 0)).frame(width: 20, height: 20)
                }
                .padding(.leading, 4).padding(.trailing, 6).padding(.vertical, 10)
                Rectangle().fill(B.outlineVariant).frame(height: 1)
            }
            .background(LinearGradient(colors: [GroupColors.start, GroupColors.end], startPoint: .leading, endPoint: .trailing))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(category.title), \(devices.count) devices" + (brands.isEmpty ? "" : ", " + brands.map(\.name).joined(separator: ", ")))
        .accessibilityValue(open ? "Expanded" : "Collapsed")
    }
}

/// Lays children out in one row and leaves out whatever doesn't fit (the APK's logo strip).
private struct FitRow: Layout {
    var spacing: CGFloat
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let sizes = subviews.map { $0.sizeThatFits(.unspecified) }
        let width = proposal.width ?? sizes.reduce(0) { $0 + $1.width + spacing }
        return CGSize(width: width, height: sizes.map(\.height).max() ?? 0)
    }
    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX
        for v in subviews {
            let s = v.sizeThatFits(.unspecified)
            if x + s.width <= bounds.maxX {
                v.place(at: CGPoint(x: x, y: bounds.midY), anchor: .leading, proposal: ProposedViewSize(s))
                x += s.width + spacing
            } else {
                x = bounds.maxX + 1
                v.place(at: CGPoint(x: -10_000, y: bounds.midY), anchor: .leading, proposal: .zero)
            }
        }
    }
}

/// A device added to Bridgit, as the APK's compact card above the list (LanCamerasFragment.render), without its owner actions.
struct AddedCard: View {
    var device: AddedDevice
    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(device.name).font(.system(size: 15, weight: .bold)).foregroundStyle(B.onPrimaryContainer)
            Text(line).font(.system(size: 12)).foregroundStyle(B.onPrimaryContainer)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.leading, 12).padding(.trailing, 6).padding(.vertical, 10)
        .background(B.primaryContainer).clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .accessibilityElement(children: .combine)
    }

    var line: String {
        var parts = [device.host, device.applets.isEmpty ? "Not added to any applets" : "Included in: " + device.applets.joined(separator: ", ")]
        if device.kind == "camera" || device.id.hasPrefix("lan:") {
            func c(_ k: String) -> String { device.checks[k] ?? "not_checked" }
            parts.append("Microphone: \(c("audio_in")) · ONVIF speaker: \(c("audio_out")) · Tapo talk: \(c("tapo_talk")). A discovered track does not prove audible pickup or output.")
        }
        return parts.filter { !$0.isEmpty }.joined(separator: " · ")
    }
}

/// One device, compact like the APK's row (NetworkDevicesFragment.deviceRow): brand logo top left and kind badge below it;
/// name, "kind · address · model" and service pills; last seen and the Bridgit link on the right. A tap opens its details.
struct DeviceRow: View {
    var device: LanDevice
    var shaded: Bool
    var added = false
    var expandedByDefault = false
    @State private var expanded: Bool?

    private var isOpen: Bool { expanded ?? expandedByDefault }

    private var style: (icon: String, accent: Color, fill: Color) {
        let k = (device.kind ?? "").isEmpty ? device.typeLabel.lowercased() : device.kind!
        if k.contains("camera") { return ("video.fill", B.success, B.successContainer) }
        if k == "tv" || k == "cast" || k.contains("media") { return ("tv", B.primary, B.primaryContainer) }
        if k.contains("computer") { return ("desktopcomputer", B.primary, B.primaryContainer) }
        if k.contains("mobile") || k.contains("phone") || k.contains("tablet") { return ("iphone", B.primary, B.primaryContainer) }
        if k.contains("speaker") || k.contains("audio") { return ("hifispeaker.fill", B.secondary, B.secondaryContainer) }
        if k.contains("printer") { return ("printer.fill", B.primary, B.primaryContainer) }
        return ("server.rack", B.onSurfaceVariant, B.surfaceHigh)
    }

    private var services: [String] {
        var seen = Set<String>()
        return device.services.filter { seen.insert($0).inserted && $0.caseInsensitiveCompare(device.typeLabel) != .orderedSame }
    }

    var body: some View {
        let st = style
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .top, spacing: 0) {
                VStack(alignment: .leading, spacing: 0) {
                    if let b = device.brand {
                        BrandLogo(brand: b, height: 14, maxWidth: 42, alignment: .leading).padding(.top, 3).padding(.bottom, 4)
                    }
                    Spacer(minLength: 0)
                    Image(systemName: st.icon).font(.system(size: 16)).foregroundStyle(st.accent)
                        .frame(width: 36, height: 36).background(st.fill).clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
                .frame(width: 42, alignment: .leading).frame(maxHeight: .infinity, alignment: .top)
                .padding(.trailing, 10)
                VStack(alignment: .leading, spacing: 0) {
                    Text(device.name).font(.system(size: 15, weight: .bold)).foregroundStyle(B.onSurface).lineLimit(1)
                    Text([device.typeLabel, device.host, device.model].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · "))
                        .font(.system(size: 12)).foregroundStyle(B.onSurfaceVariant).lineLimit(1).padding(.top, 1)
                    if !services.isEmpty { ServicePills(services: services).padding(.top, 5) }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                VStack(alignment: .leading, spacing: 3) {
                    TimelineView(.periodic(from: .now, by: 30)) { _ in
                        status(LastSeen.relative(device.lastSeen), "wifi", device.connected ? B.success : B.onSurfaceVariant)
                    }
                    if added { status("Added", "link", device.connected ? B.primary : B.onSurfaceVariant).accessibilityLabel("Added to Bridgit") }
                    else if device.interfaced { status("Bridgit interfaced", "link", device.connected ? B.primary : B.onSurfaceVariant) }
                }
                .fixedSize().padding(.leading, 8)
                Image(systemName: "chevron.right").font(.system(size: 12, weight: .semibold)).foregroundStyle(B.outline)
                    .rotationEffect(.degrees(isOpen ? 90 : 0)).frame(width: 18, height: 18).padding(.leading, 4)
                    .frame(maxHeight: .infinity, alignment: .center)
            }
            .padding(.leading, 8).padding(.trailing, 6).padding(.vertical, 8)
            if isOpen {
                DeviceDetails(device: device).padding(.leading, 60).padding(.trailing, 6).padding(.bottom, 10)
                    .transition(.opacity.combined(with: .move(edge: .top)))
            }
            Rectangle().fill(B.outlineVariant).frame(height: 1)
        }
        .background {
            if added {
                B.primaryContainer.overlay(alignment: .leading) { B.primary.frame(width: 3) }
            } else {
                shaded ? B.surface : B.surfaceLow
            }
        }
        .contentShape(Rectangle())
        .onTapGesture { withAnimation(.spring(response: 0.3, dampingFraction: 0.88)) { expanded = !isOpen } }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
        .accessibilityValue(isOpen ? "Expanded" : "Collapsed")
    }

    private func status(_ text: String, _ icon: String, _ tint: Color) -> some View {
        HStack(spacing: 4) {
            Image(systemName: icon).font(.system(size: 10, weight: .semibold)).frame(width: 13, height: 13)
            Text(text).font(.system(size: 10))
        }
        .foregroundStyle(tint)
    }
}

/// A device's details, as the APK shows them under its row (deviceDetails) without the owner's actions.
private struct DeviceDetails: View {
    var device: LanDevice
    @State private var copied = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("CONNECTION").font(.system(size: 10, weight: .bold)).kerning(0.8).foregroundStyle(B.primary)
            Text("Last observed · " + LastSeen.relative(device.lastSeen)).font(.system(size: 14))
                .foregroundStyle(device.connected ? B.success : B.onSurfaceVariant).padding(.top, 8)
            Text("Last seen · " + LastSeen.full(device.lastSeen)).font(.system(size: 12)).foregroundStyle(B.onSurfaceVariant).padding(.top, 4)
            Rectangle().fill(B.outlineVariant).frame(height: 1).padding(.vertical, 10)
            if device.interfaced {
                Text("BRIDGIT INTERFACE").font(.system(size: 10, weight: .bold)).kerning(0.8).foregroundStyle(B.primary)
                Text((device.interfaceLabel ?? "Bridgit interface") + " · Setup available").font(.system(size: 13)).foregroundStyle(B.onSurface)
                    .padding(.top, 6).padding(.bottom, 12)
            }
            Text("Suggested type: " + device.typeLabel).font(.system(size: 12)).foregroundStyle(B.onSurfaceVariant)
            if let m = device.model, !m.isEmpty { Text(m).font(.system(size: 12)).foregroundStyle(B.onSurface).padding(.top, 6) }
            let ids = [device.modelIdentifier, device.identifier].compactMap { $0 }.filter { !$0.isEmpty }
            if !ids.isEmpty { Text(ids.joined(separator: " · ")).font(.system(size: 10).monospaced()).foregroundStyle(B.outline).padding(.top, 6) }
            if let host = device.host {
                Button {
                    UIPasteboard.general.string = host
                    copied = true
                } label: {
                    Label(copied ? "Copied" : "Copy address", systemImage: copied ? "checkmark" : "doc.on.doc")
                        .font(.system(size: 14, weight: .medium)).frame(maxWidth: .infinity).frame(minHeight: 40)
                }
                .buttonStyle(.bordered).buttonBorderShape(.roundedRectangle(radius: 14)).tint(B.primary).padding(.top, 8)
            }
        }
        .padding(.horizontal, 14).padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(LinearGradient(colors: [B.surfaceHigh, B.surfaceLow], startPoint: .leading, endPoint: .trailing))
    }
}

/// Wrapped service pills, as in the APK.
private struct ServicePills: View {
    var services: [String]
    var body: some View {
        FlowLayout(spacing: 4) {
            ForEach(services, id: \.self) { s in
                Text(s).font(.system(size: 10)).foregroundStyle(B.onSurfaceVariant)
                    .padding(.horizontal, 7).padding(.top, 1).padding(.bottom, 2)
                    .background(B.surfaceHigh).clipShape(Capsule()).overlay(Capsule().stroke(B.outlineVariant, lineWidth: 1))
            }
        }
    }
}

/// Wraps children onto as many rows as they need.
struct FlowLayout: Layout {
    var spacing: CGFloat
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, row: CGFloat = 0
        for v in subviews {
            let s = v.sizeThatFits(.unspecified)
            if x > 0 && x + s.width > width { x = 0; y += row + spacing; row = 0 }
            x += s.width + spacing; row = max(row, s.height)
        }
        return CGSize(width: width.isFinite ? width : x, height: y + row)
    }
    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, row: CGFloat = 0
        for v in subviews {
            let s = v.sizeThatFits(.unspecified)
            if x > bounds.minX && x + s.width > bounds.maxX { x = bounds.minX; y += row + spacing; row = 0 }
            v.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(s))
            x += s.width + spacing; row = max(row, s.height)
        }
    }
}
