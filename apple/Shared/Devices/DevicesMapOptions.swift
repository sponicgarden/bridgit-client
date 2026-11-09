import Foundation
import SwiftUI
import ImageIO

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

/// What the map page draws, built exactly like DevicesScene.options(): devices added to Bridgit first
/// ("on", or "blocked" while not checked), then remembered devices: "seen", "off", or "blocked" when unprobed.
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
                let state = on ? "seen" : d.connectionReported ? "off" : "blocked"
                let detail = d.accountSnapshot ? d.connectionDescription + (d.lastSeen.map { " · Last connected " + LastSeen.relative($0, now: now) } ?? "") : on ? host : d.connectionReported ? LastSeen.relative(d.lastSeen, now: now) : "Not checked"
                var group = (d.category ?? catId).uppercased()
                if !known.contains(group) { group = "OTHER" }
                var j: [String: Any] = ["id": "seen:" + d.id, "name": d.name,
                                        "sub": (d.interfaced ? "Bridgit · " : "") + detail,
                                        "kind": d.kind ?? "unknown", "state": state, "linked": false, "group": group]
                if let b = d.brand, let logo = catalog.mapLogo(b.id) { j["brand"] = catalog.entry(b.id)?.name ?? b.name; j["logo"] = logo }
                return Entry(json: j, state: state, online: on)
            }
        let entries = addedEntries + observed
        let shown = entries.prefix(maxDevices)
        var out: [String: Any] = [
            "devices": shown.map(\.json),
            "groups": groups.map { ["id": $0.0, "title": $0.1] },
            "more": entries.count - shown.count,
            "counts": ["total": entries.count, "added": addedEntries.count, "unchecked": entries.filter { $0.state == "blocked" }.count,
                       "seen": observed.filter { $0.state == "seen" }.count, "offline": observed.filter { $0.state == "off" }.count,
                       "online": entries.filter(\.online).count],
        ]
        // The name under the mark: the phone being looked at, never "This phone" on the portal.
        if let core, !core.isEmpty { out["core"] = core }
        return out
    }
}


struct NetworkScanReview: View {
    @DialogDismiss private var dismiss
    @Environment(\.dynamicTypeSize) private var textSize
    let allow: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack { Spacer(); BuildStamp(foreground: B.onSurfaceVariant) }
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text("Allow a network scan?").font(.title2.bold()).accessibilityAddTraits(.isHeader)
                    Text("Bridgit will look for advertised devices on your current local network for up to 20 seconds. Device names, addresses and last-seen times are saved privately on this device. Missing devices stay listed as unconnected; refresh to check again. This does not pair devices or give control agents access.").accessibilityIdentifier("network.scan.disclosure")
                }.frame(maxWidth: .infinity, alignment: .leading)
            }
            .accessibilityIdentifier("network.scan.review")
            if textSize.isAccessibilitySize {
                VStack(alignment: .leading, spacing: 8) { allowButton; cancelButton }
            } else {
                HStack { cancelButton; Spacer(); allowButton }
            }
        }
        .padding(24)
        .buildStampSurface()
        #if os(macOS)
        .frame(minWidth: 440, idealWidth: 520, minHeight: 240, idealHeight: 320)
        #endif
    }

    private var cancelButton: some View {
        Button(role: .cancel) { dismiss() } label: {
            Text("Cancel").frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
        }
    }
    private var allowButton: some View {
        Button(action: allow) {
            Text("Allow scan").frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
        }.buttonStyle(.borderedProminent)
    }
}

// MARK: - Brand logos (assets/device-brands, copied into the bundle)

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
            let aspect = CGFloat(img.width) / CGFloat(max(img.height, 1))
            Image(decorative: img, scale: 1).renderingMode(.template).resizable().scaledToFit()
                .foregroundStyle(tint)
                .frame(width: min(maxWidth, max(minWidth, height * aspect)), height: height, alignment: alignment)
                .accessibilityLabel(brand.name)
        } else {
            Text(brand.name).font(.caption2).foregroundStyle(tint).lineLimit(1)
                .frame(maxWidth: maxWidth, alignment: alignment)
        }
    }
}

@MainActor
enum BrandImages {
    private static var cache: [String: CGImage?] = [:]

    /// The logo trimmed to its drawn pixels (the build-time renders sit on a square canvas).
    static func image(for id: String) -> CGImage? {
        if let hit = cache[id] { return hit }
        var img: CGImage?
        if let e = BrandCatalog.shared.entry(id) {
            let url = e.asset.hasSuffix(".svg")
                ? Bundle.main.url(forResource: e.asset, withExtension: "png", subdirectory: "brand-logos")
                : Bundle.main.url(forResource: (e.asset as NSString).deletingPathExtension, withExtension: (e.asset as NSString).pathExtension, subdirectory: "device-brands")
            img = url.flatMap { url -> CGImage? in
                guard let source = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
                return CGImageSourceCreateImageAtIndex(source, 0, nil)
            }.flatMap { trim($0, backdrop: e.asset.hasSuffix(".svg")) }
        }
        cache[id] = img
        return img
    }

    private static func trim(_ image: CGImage, backdrop: Bool) -> CGImage? {
        let cg = image
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
        return cut
    }
}

/// Local bundled catalog only; a linked phone may advertise a different catalog.
struct NetworkCoverage: Decodable {
    struct Service: Decodable { let label: String; let group: String }
    let services: [Service]
    let methods: [String]

    var otherDiscovery: [String] {
        let labels = [("tplink", "TP-Link / Tapo"), ("ssdp", "UPnP / DLNA and router announcements"), ("onvif", "ONVIF cameras")]
        return labels.compactMap { methods.contains($0.0) ? $0.1 : nil }
    }

    var groups: [(title: String, labels: [String])] {
        var result: [(title: String, labels: [String])] = []
        for service in services {
            if let index = result.firstIndex(where: { $0.title == service.group }) {
                if !result[index].labels.contains(service.label) { result[index].labels.append(service.label) }
            } else {
                result.append((title: service.group, labels: [service.label]))
            }
        }
        return result
    }
}

struct NetworkCoverageView: View {
    @DialogDismiss private var dismiss
    @Environment(\.dynamicTypeSize) private var textSize
    let load: () async throws -> Data
    @State private var coverage: NetworkCoverage?
    @State private var problem: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            if textSize.isAccessibilitySize {
                VStack(alignment: .leading, spacing: 8) {
                    HStack { Spacer(); BuildStamp(foreground: B.onSurfaceVariant); done }
                    Text("Discovery coverage").font(.title2.bold()).accessibilityAddTraits(.isHeader)
                }
            } else {
                HStack {
                    Text("Discovery coverage").font(.title2.bold()).accessibilityAddTraits(.isHeader)
                    Spacer()
                    BuildStamp(foreground: B.onSurfaceVariant)
                    done
                }
            }
            if let coverage {
                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        ForEach(coverage.groups.indices, id: \.self) { index in
                            let group = coverage.groups[index]
                            VStack(alignment: .leading, spacing: 4) {
                                Text(group.title).font(.subheadline.bold()).foregroundStyle(B.primary).accessibilityAddTraits(.isHeader)
                                Text(group.labels.joined(separator: " · ")).foregroundStyle(B.onSurfaceVariant)
                            }.frame(maxWidth: .infinity, alignment: .leading)
                        }
                        if !coverage.otherDiscovery.isEmpty {
                            VStack(alignment: .leading, spacing: 4) {
                                Text("Other discovery").font(.subheadline.bold()).foregroundStyle(B.primary).accessibilityAddTraits(.isHeader)
                                Text(coverage.otherDiscovery.joined(separator: " · "))
                                    .foregroundStyle(B.onSurfaceVariant)
                            }
                        }
                    }.frame(maxWidth: .infinity, alignment: .leading)
                }
            } else if let problem {
                Text(problem).foregroundStyle(B.error)
            } else {
                ProgressView()
            }
        }
        .padding(24).background(B.surface).foregroundStyle(B.onSurface).buildStampSurface()
        #if os(macOS)
        .frame(minWidth: 440, idealWidth: 520, minHeight: 320, idealHeight: 520)
        #endif
        .task {
            do { coverage = try JSONDecoder().decode(NetworkCoverage.self, from: await load()) }
            catch { problem = error.localizedDescription }
        }
    }

    private var done: some View {
        Button { dismiss() } label: { Text("Done").frame(minWidth: 44, minHeight: 44).contentShape(Rectangle()) }
            .keyboardShortcut(.cancelAction)
    }
}
