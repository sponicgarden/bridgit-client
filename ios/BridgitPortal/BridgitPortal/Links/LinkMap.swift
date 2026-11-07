import AVFoundation
import SwiftUI

/// LNK-39..40 (design pass: docs/design/linked-devices-apple.md): this device in the middle, each linked
/// Bridgit on a ring, devices seen through one of them on an outer ring. Arrows point at the device being
/// controlled. Rings grow in 24 pt steps until no node box (circle + name) collides. Pure, so tests check it.
struct LinkMapLayout: Equatable {
    enum Style: Equatable { case solid, dashed, faint, indirect }

    struct Node: Equatable, Identifiable {
        var id: String
        var name: String
        /// Centre of the node's circle.
        var point: CGPoint
        var ring: Int
        var linkId: String?
        /// For an outer node: the linked Bridgit it is seen through.
        var via: String?
        var symbol: String = "app.connected.to.app.below.fill"

        var diameter: CGFloat { ring == 0 ? 52 : ring == 1 ? 44 : 40 }
    }

    struct Edge: Equatable {
        var from: String
        var to: String
        var arrowAtFrom: Bool
        var arrowAtTo: Bool
        var style: Style
        var label: String?
        /// Sideways shift, for two directions in different states drawn as parallel lanes.
        var lane: CGFloat = 0
    }

    var nodes: [Node]
    var edges: [Edge]
    var size: CGSize
    /// True when the map is larger than its viewport and scrolls.
    var scrolls: Bool

    static let center = "self"
    static let labelWidth: CGFloat = 104
    static let labelGap: CGFloat = 6
    static let clearance: CGFloat = 12
    static let margin: CGFloat = 16

    /// Estimated height of a name under a node: one or two 13 pt lines.
    static func labelHeight(_ name: String, scale: CGFloat = 1) -> CGFloat {
        (CGFloat(name.count) * 7.6 * scale > labelWidth ? 34 : 17) * scale
    }

    /// A node's box (circle + name), relative to the circle's centre.
    static func box(_ n: Node, scale: CGFloat = 1) -> CGRect {
        let w = max(n.diameter, labelWidth * scale)
        return CGRect(x: -w / 2, y: -n.diameter / 2, width: w, height: n.diameter + labelGap + labelHeight(n.name, scale: scale))
    }

    static func make(me: String, links: [LinkedBridgit], gone: Set<String> = [], viewport: CGSize, scale: CGFloat = 1) -> LinkMapLayout {
        let sorted = links.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
        let direct = Set(sorted.map(\.name))
        let n = max(sorted.count, 1)
        let sector = 2 * Double.pi / Double(n)
        let outer: [[LinkPeer]] = sorted.map { l in l.peers.filter { !direct.contains($0.name) && $0.name != me } }

        func place(_ r1: CGFloat, _ gap: CGFloat, _ turn: Double) -> [Node] {
            let r2 = r1 + gap
            var nodes = [Node(id: center, name: me, point: .zero, ring: 0, symbol: DeviceKind.thisDeviceSymbol)]
            for (i, l) in sorted.enumerated() {
                let a = -Double.pi / 2 + turn + Double(i) * sector
                nodes.append(Node(id: "link:" + l.id, name: l.name, point: CGPoint(x: r1 * cos(a), y: r1 * sin(a)), ring: 1,
                                  linkId: l.id, symbol: DeviceKind.symbol(l.deviceType)))
                let peers = outer[i]
                let step = min(sector * 0.8 / Double(max(peers.count, 1)), 0.55)
                for (j, peer) in peers.enumerated() {
                    let b = a + (Double(j) - Double(peers.count - 1) / 2) * step
                    nodes.append(Node(id: "via:" + l.id + ":" + peer.name, name: peer.name, point: CGPoint(x: r2 * cos(b), y: r2 * sin(b)),
                                      ring: 2, linkId: l.id, via: l.name))
                }
            }
            return nodes
        }

        /// The first overlapping pair, if any: whether it involves an outer-ring node.
        func collision(_ nodes: [Node]) -> (outer: Bool, Void)? {
            let boxes = nodes.map { box($0, scale: scale).offsetBy(dx: $0.point.x, dy: $0.point.y).insetBy(dx: -clearance / 2, dy: -clearance / 2) }
            for i in boxes.indices {
                for j in boxes.indices where j > i && boxes[i].intersects(boxes[j]) { return (nodes[i].ring == 2 || nodes[j].ring == 2, ()) }
            }
            return nil
        }

        // Grow the ring that is crowded, 24 pt at a time: the first ring, or the gap to the outer ring.
        func settle(_ turn: Double) -> [Node] {
            var r1: CGFloat = 112, gap: CGFloat = 112
            var nodes = place(r1, gap, turn)
            var tries = 0
            while let c = collision(nodes), tries < 40 {
                if c.outer { gap += 24 } else { r1 += 24 }
                nodes = place(r1, gap, turn)
                tries += 1
            }
            return nodes
        }
        func extent(_ nodes: [Node]) -> CGRect { nodes.map { box($0, scale: scale).offsetBy(dx: $0.point.x, dy: $0.point.y) }.reduce(CGRect.null) { $0.union($1) } }
        func overflow(_ nodes: [Node]) -> CGFloat {
            let b = extent(nodes)
            return max(0, b.width + 2 * margin - viewport.width) * 4 + max(0, b.height + 2 * margin - viewport.height)
        }
        // First from twelve o'clock; when that overflows a narrow viewport, the ring turned by a quarter
        // sector at a time (same clockwise order) that overflows least.
        var nodes = settle(0)
        if overflow(nodes) > 0 {
            for k in 1..<4 {
                let other = settle(sector * Double(k) / 4)
                if overflow(other) < overflow(nodes) { nodes = other }
            }
        }

        // Fit: canvas = bounds + margin, at least the viewport. Content centred when it fits; when it scrolls,
        // this device is kept as central as the bounds allow.
        let bounds = extent(nodes)
        let need = CGSize(width: bounds.width + 2 * margin, height: bounds.height + 2 * margin)
        let remote = outer.reduce(0) { $0 + $1.count }
        let scrolls = need.width > viewport.width || need.height > viewport.height || sorted.count > 4 || remote > 6
        let size = CGSize(width: max(viewport.width, need.width), height: max(viewport.height, need.height))
        var origin = scrolls ? CGPoint(x: size.width / 2, y: size.height / 2) : CGPoint(x: size.width / 2 - bounds.midX, y: size.height / 2 - bounds.midY)
        origin.x = min(max(origin.x, margin - bounds.minX), size.width - margin - bounds.maxX)
        origin.y = min(max(origin.y, margin - bounds.minY), size.height - margin - bounds.maxY)
        nodes = nodes.map { var m = $0; m.point = CGPoint(x: $0.point.x + origin.x, y: $0.point.y + origin.y); return m }

        var edges: [Edge] = []
        for l in sorted {
            let id = "link:" + l.id
            let cut = gone.contains(l.id)
            func style(_ s: DirectionState) -> Style { cut || s == .off ? .faint : s == .paused ? .dashed : s.isWaiting ? .faint : .solid }
            let out = l.out.state, inn = l.inbound.state
            if out != .off && inn != .off && style(out) != style(inn) {
                edges.append(Edge(from: center, to: id, arrowAtFrom: false, arrowAtTo: true, style: style(out), label: out.isWaiting ? "waiting" : nil, lane: -3))
                edges.append(Edge(from: center, to: id, arrowAtFrom: true, arrowAtTo: false, style: style(inn), label: inn.isWaiting ? "waiting" : nil, lane: 3))
            } else {
                let s = [out, inn].first { $0 != .off } ?? .off
                edges.append(Edge(from: center, to: id, arrowAtFrom: inn != .off, arrowAtTo: out != .off, style: style(s),
                                  label: [out, inn].contains(where: \.isWaiting) && !cut ? "waiting" : nil))
            }
            for peer in l.peers where !direct.contains(peer.name) && peer.name != me {
                edges.append(Edge(from: id, to: "via:" + l.id + ":" + peer.name, arrowAtFrom: peer.usedBy, arrowAtTo: peer.uses,
                                  style: .indirect, label: "via " + l.name))
            }
        }
        return LinkMapLayout(nodes: nodes, edges: edges, size: size, scrolls: scrolls)
    }

    /// Where a line leaving [node] toward [toward] starts (or ends): 6 pt outside the circle, and past the
    /// node's name when the line would cross it.
    static func exit(from node: Node, toward: CGPoint, scale: CGFloat = 1) -> CGPoint {
        let dx = toward.x - node.point.x, dy = toward.y - node.point.y
        let d = hypot(dx, dy)
        guard d > 0 else { return node.point }
        var t = (node.diameter / 2 + 6) / d
        // The name's rectangle (relative to the circle's centre), 6 pt larger all round.
        let label = CGRect(x: -labelWidth * scale / 2, y: node.diameter / 2 + labelGap, width: labelWidth * scale,
                           height: labelHeight(node.name, scale: scale)).insetBy(dx: -6, dy: -6)
        if let leave = rayExit(dx: dx, dy: dy, rect: label) { t = max(t, leave) }
        t = min(t, 0.5)
        return CGPoint(x: node.point.x + dx * t, y: node.point.y + dy * t)
    }

    /// Slab test for the ray (0,0) + s·(dx,dy), s ≥ 0: the s where it leaves [rect], if it passes through it.
    static func rayExit(dx: CGFloat, dy: CGFloat, rect: CGRect) -> CGFloat? {
        var lo: CGFloat = 0, hi = CGFloat.greatestFiniteMagnitude
        for (o, lmin, lmax) in [(dx, rect.minX, rect.maxX), (dy, rect.minY, rect.maxY)] {
            if abs(o) < 1e-9 {
                if 0 < lmin || 0 > lmax { return nil }
            } else {
                let a = lmin / o, b = lmax / o
                lo = max(lo, min(a, b)); hi = min(hi, max(a, b))
            }
        }
        return lo <= hi && hi < .greatestFiniteMagnitude ? hi : nil
    }
}

struct LinkMapView: View {
    var me: String
    var links: [LinkedBridgit]
    var gone: Set<String>
    var onOpen: (LinkMapLayout.Node) -> Void
    @Environment(\.horizontalSizeClass) private var sizeClass
    @ScaledMetric(relativeTo: .caption) private var scale: CGFloat = 1

    private var hasIndirect: Bool {
        let direct = Set(links.map(\.name))
        return links.contains { $0.peers.contains { !direct.contains($0.name) && $0.name != me } }
    }

    /// Viewport height (design pass): iPhone 360/520, iPad 440/560, Mac 400/520 (direct only / with indirect).
    private var height: CGFloat {
        #if targetEnvironment(macCatalyst)
        return hasIndirect ? 520 : 400
        #else
        if sizeClass == .regular { return hasIndirect ? 560 : 440 }
        return hasIndirect ? 520 : 360
        #endif
    }

    var body: some View {
        GeometryReader { geo in
            let layout = LinkMapLayout.make(me: me, links: links, gone: gone, viewport: geo.size, scale: scale)
            ScrollViewReader { proxy in
                ScrollView(layout.scrolls ? [.horizontal, .vertical] : [], showsIndicators: layout.scrolls) {
                    canvas(layout).frame(width: layout.size.width, height: layout.size.height)
                }
                .scrollDisabled(!layout.scrolls)
                .onAppear { if layout.scrolls { proxy.scrollTo(LinkMapLayout.center, anchor: .center) } }
            }
        }
        .frame(height: height)
        .background(B.surfaceLow, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
    }

    private func canvas(_ layout: LinkMapLayout) -> some View {
        ZStack(alignment: .topLeading) {
            Canvas { ctx, _ in for e in layout.edges { draw(e, layout, &ctx) } }
                .accessibilityHidden(true)
            ForEach(layout.edges.indices, id: \.self) { i in
                if let label = layout.edges[i].label, let (p, _) = segment(layout.edges[i], layout) {
                    Text(label).font(.system(size: 12 * scale, weight: .medium)).foregroundStyle(B.onSurfaceVariant)
                        .padding(.horizontal, 4).padding(.vertical, 2)
                        .background(B.surfaceLow, in: RoundedRectangle(cornerRadius: 4))
                        .position(p)
                        .accessibilityHidden(true)
                }
            }
            ForEach(layout.edges.indices, id: \.self) { i in
                if let (_, (s, t)) = segment(layout.edges[i], layout) {
                    Color.clear.frame(width: 24, height: 24).position(x: (s.x + t.x) / 2, y: (s.y + t.y) / 2)
                        .accessibilityElement().accessibilityLabel(edgeLabel(layout.edges[i], layout))
                        .accessibilityHint(layout.edges[i].style == .indirect ? "Show path" : "")
                }
            }
            ForEach(layout.nodes) { node in
                NodeBubble(node: node, faint: node.ring == 2, scale: scale)
                    .position(x: node.point.x, y: node.point.y + LinkMapLayout.box(node, scale: scale).midY)
                    .id(node.id)
                    .onTapGesture { onOpen(node) }
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(nodeLabel(node))
                    .accessibilityAddTraits(.isButton)
            }
        }
    }

    // MARK: drawing

    private func node(_ id: String, _ l: LinkMapLayout) -> LinkMapLayout.Node? { l.nodes.first { $0.id == id } }

    /// The label's position beside the shaft's midpoint, and the shaft's ends.
    private func segment(_ e: LinkMapLayout.Edge, _ l: LinkMapLayout) -> (CGPoint, (CGPoint, CGPoint))? {
        guard let a = node(e.from, l), let b = node(e.to, l) else { return nil }
        var s = LinkMapLayout.exit(from: a, toward: b.point, scale: scale)
        var t = LinkMapLayout.exit(from: b, toward: a.point, scale: scale)
        let d = hypot(t.x - s.x, t.y - s.y)
        guard d >= 16 else { return nil }
        let ux = (t.x - s.x) / d, uy = (t.y - s.y) / d
        s = CGPoint(x: s.x - uy * e.lane, y: s.y + ux * e.lane)
        t = CGPoint(x: t.x - uy * e.lane, y: t.y + ux * e.lane)
        let mid = CGPoint(x: (s.x + t.x) / 2, y: (s.y + t.y) / 2)
        // 6 pt from the shaft, on the side away from this device.
        let side: CGFloat = ux >= 0 ? 1 : -1
        let label = CGPoint(x: mid.x - uy * 14 * side, y: mid.y + ux * 14 * side)
        return (label, (s, t))
    }

    private func draw(_ e: LinkMapLayout.Edge, _ l: LinkMapLayout, _ ctx: inout GraphicsContext) {
        guard let (_, (s, t)) = segment(e, l) else { return }
        let d = hypot(t.x - s.x, t.y - s.y)
        let ux = (t.x - s.x) / d, uy = (t.y - s.y) / d
        var path = Path()
        path.move(to: s)
        path.addLine(to: t)
        let color: Color
        let width: CGFloat
        switch e.style {
        case .indirect: color = B.outline.opacity(0.7); width = 1.5
        case .faint: color = B.primary.opacity(0.4); width = 2
        default: color = B.primary; width = 2
        }
        ctx.stroke(path, with: .color(color), style: StrokeStyle(lineWidth: width, lineCap: .round, dash: e.style == .dashed ? [6, 4] : []))
        let (len, wide): (CGFloat, CGFloat) = e.style == .indirect ? (8, 6) : (10, 8)
        if e.arrowAtTo { ctx.fill(arrow(at: t, ux: ux, uy: uy, len: len, wide: wide), with: .color(color)) }
        if e.arrowAtFrom { ctx.fill(arrow(at: s, ux: -ux, uy: -uy, len: len, wide: wide), with: .color(color)) }
    }

    private func arrow(at tip: CGPoint, ux: CGFloat, uy: CGFloat, len: CGFloat, wide: CGFloat) -> Path {
        let base = CGPoint(x: tip.x - ux * len, y: tip.y - uy * len)
        var p = Path()
        p.move(to: tip)
        p.addLine(to: CGPoint(x: base.x - uy * wide / 2, y: base.y + ux * wide / 2))
        p.addLine(to: CGPoint(x: base.x + uy * wide / 2, y: base.y - ux * wide / 2))
        p.closeSubpath()
        return p
    }

    // MARK: accessibility (exact templates from the design pass)

    private func stateWord(_ s: DirectionState) -> String {
        switch s { case .on: "On"; case .paused: "Paused"; case .off: "Off"; default: "Waiting" }
    }

    private func nodeLabel(_ n: LinkMapLayout.Node) -> String {
        if n.ring == 0 { return n.name + ", this device" }
        if let via = n.via { return "\(n.name), via \(via). \(via) uses \(n.name)." }
        guard let l = links.first(where: { $0.id == n.linkId }) else { return n.name }
        let out = "This device can use \(LinkService.list(l.out.services)). \(stateWord(l.out.state))."
        let inn = "Can use this device: \(LinkService.list(l.inbound.services)). \(stateWord(l.inbound.state))."
        if l.out.state != .off && l.inbound.state != .off {
            return "\(n.name). \(out) \(n.name) can use this device: \(LinkService.list(l.inbound.services)). \(stateWord(l.inbound.state))."
        }
        return "\(n.name). " + (l.inbound.state != .off ? inn : out)
    }

    private func edgeLabel(_ e: LinkMapLayout.Edge, _ l: LinkMapLayout) -> String {
        guard let a = node(e.from, l), let b = node(e.to, l) else { return "" }
        if e.style == .indirect { return "\(a.name) uses \(b.name), via \(a.name)." }
        let link = links.first { $0.id == b.linkId }
        if e.arrowAtFrom && e.arrowAtTo { return "\(a.name) and \(b.name) use each other. \(stateWord(link?.out.state ?? .off))." }
        if e.arrowAtFrom { return "\(b.name) uses \(a.name). \(stateWord(link?.inbound.state ?? .off))." }
        return "\(a.name) uses \(b.name). \(stateWord(link?.out.state ?? .off))."
    }
}

private struct NodeBubble: View {
    var node: LinkMapLayout.Node
    var faint: Bool
    var scale: CGFloat

    var body: some View {
        let d = node.diameter
        VStack(spacing: LinkMapLayout.labelGap) {
            Image(systemName: node.symbol)
                .font(.system(size: node.ring == 0 ? 24 : node.ring == 1 ? 20 : 18, weight: .medium))
                .foregroundStyle(node.ring == 0 ? B.onPrimary : faint ? B.onSurfaceVariant : B.onPrimaryContainer)
                .frame(width: d, height: d)
                .background(node.ring == 0 ? B.primary : faint ? B.surfaceHigh : B.primaryContainer, in: Circle())
                .overlay(Circle().stroke(faint ? B.outline : .clear, lineWidth: 1))
            Text(node.name).font(.system(size: 13 * scale, weight: node.ring == 0 ? .semibold : .medium))
                .foregroundStyle(faint ? B.onSurfaceVariant : B.onSurface)
                .multilineTextAlignment(.center).lineLimit(2)
                .frame(width: LinkMapLayout.labelWidth * scale)
        }
        .frame(minWidth: 44, minHeight: 44)
        .contentShape(Rectangle())
    }
}

/// SF Symbols for this Apple device and linked devices.
enum DeviceKind {
    static var thisDeviceSymbol: String {
        #if targetEnvironment(macCatalyst)
        return "laptopcomputer"
        #else
        return UIDevice.current.userInterfaceIdiom == .pad ? "ipad" : "iphone"
        #endif
    }

    static func symbol(_ deviceType: String) -> String {
        switch deviceType {
        case "tablet": "ipad.landscape"
        case "computer", "mac": "laptopcomputer"
        case "camera": "video.fill"
        default: "iphone"
        }
    }

    /// LinkSelf.platform and device type for this install.
    static var platform: (platform: String, deviceType: String) {
        #if targetEnvironment(macCatalyst)
        return ("macos", "computer")
        #else
        return UIDevice.current.userInterfaceIdiom == .pad ? ("ipados", "tablet") : ("ios", "phone")
        #endif
    }

    /// Whether this device can scan a QR code (a Mac may have no camera).
    static var hasCamera: Bool { AVCaptureDevice.default(for: .video) != nil }
}
