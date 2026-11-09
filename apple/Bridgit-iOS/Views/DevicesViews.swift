import BridgitCore
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
            if let live { DevicesHost(live: live) } else { LocalNetworkDevicesHost() }
        }
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .topBarTrailing) { BuildStamp(foreground: B.onSurfaceVariant) } }
    }
}

private struct LocalNetworkDevicesHost: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var local: LocalAppletModel
    @State private var inventory: PhoneLive?
    var body: some View {
        Group {
            if let inventory { DevicesHost(live: inventory) }
            else { ProgressView() }
        }
        .task {
            if inventory == nil {
                inventory = PhoneLive(device: .init(id: PortalDevice.localID, name: LocalHostModel.deviceName,
                    reportedMetadata: DeviceTypeDescription.local), model: model, localInventory: local)
            }
        }
    }
}

/// The APK's Devices icon (ic_nav_devices, Material "lan": one box over two).
enum DevicesIcon {
    static let symbol = "point.3.connected.trianglepath.dotted"
}

private struct DevicesHost: View {
    @ObservedObject var live: PhoneLive
    /// The last choice is remembered, as on the APK (devices_tab prefs); Map first.
    @AppStorage("devices.tab", store: AppleFixtureWorkspace.defaults) private var tab = "map"
    @State private var replay = 0
    @State private var picked: MapPick?
    @State private var showCoverage = false

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                Image(systemName: DevicesIcon.symbol).font(.title2.weight(.medium)).foregroundStyle(B.primary)
                    .frame(width: 28, height: 28).accessibilityHidden(true)
                Segmented(selection: $tab, onReselect: { if $0 == "map" { replay += 1 } })
                if live.isLocalInventory {
                    Button { showCoverage = true } label: { Image(systemName: "info.circle").frame(minWidth: 44, minHeight: 44) }
                        .accessibilityLabel("Discovery coverage")
                }
            }
            .padding(.horizontal, 16).padding(.top, 10).padding(.bottom, 2)
            .frame(maxWidth: 760)
            Text(live.inventoryDescription).font(.caption).foregroundStyle(B.onSurfaceVariant)
                .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 16).padding(.vertical, 6)
            if let error = live.inventoryError {
                Text((live.inventoryReported ? "Couldn’t refresh network inventory: " : "Network inventory unavailable: ") + error)
                    .font(.callout).foregroundStyle(B.error)
                    .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 16).padding(.bottom, 6)
            }
            ZStack {
                DevicesMapView(live: live, visible: tab == "map", replay: replay) { id in
                    switch id {
                    case "find": live.requestNetworkScan()
                    case "list": tab = "list"
                    default: picked = MapPick(id: id)
                    }
                }
                .opacity(tab == "map" ? 1 : 0).allowsHitTesting(tab == "map").accessibilityHidden(tab != "map")
                DevicesList(live: live) { tab = "map"; live.requestNetworkScan() }
                    .opacity(tab == "list" ? 1 : 0).allowsHitTesting(tab == "list").accessibilityHidden(tab != "list")
            }
        }
        .background((tab == "map" ? B.sceneBackground : B.surface).ignoresSafeArea(edges: .bottom))
        .task(id: live.device.id) { if live.categories.isEmpty || live.device.isLive { await live.refreshDevices() } }
        .onDisappear { live.cancelOwnerScan() }
        .sheet(isPresented: $live.ownerScanConfirmation) {
            NetworkScanReview {
                live.ownerScanConfirmation = false
                live.confirmOwnerScan()
            }
        }
        .sheet(item: $picked) { p in DevicePickSheet(live: live, id: p.id) }
        .sheet(isPresented: $showCoverage) { NetworkCoverageView(load: { try await live.ownerCoverage() }) }
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
                        Text(title).font(.body.weight(.semibold))
                            .foregroundStyle(on ? B.onPrimary : B.onSurfaceVariant)
                            .frame(maxWidth: .infinity).padding(.vertical, 10)
                            .background { if on { Capsule().fill(B.primary).matchedGeometryEffect(id: "pill", in: pill) } }
                            .contentShape(Capsule())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(on ? [.isSelected] : [])
                }
            }
            .padding(4)
            .frame(minHeight: 52)
            .background(Capsule().fill(B.surfaceHigh))
        }
    }
}

private struct MapPick: Identifiable { var id: String }

// MARK: - Map (assets/diagnostics/devices.html)

private struct DevicesMapView: View {
    @ObservedObject var live: PhoneLive
    @AppStorage(DeviceNamePreference.key, store: AppleFixtureWorkspace.defaults) private var nameOverride = ""
    var visible: Bool
    var replay: Int
    var onOpen: (String) -> Void
    var body: some View {
        NetworkMapView(snapshot: .init(name: live.device.id == PortalDevice.localID
            ? LocalHostModel.effectiveDeviceName(override: nameOverride) : live.device.name, categories: live.categories, added: live.added,
                                       scanning: live.scanning, scanFound: live.scanFound, scanNote: live.scanNote,
                                       coreLabel: live.isLocalInventory ? (UIDevice.current.userInterfaceIdiom == .pad ? "This iPad" : "This iPhone") : nil),
                       visible: visible, replay: replay, onOpen: onOpen)
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

    private func visible(_ d: [LanDevice]) -> [LanDevice] { mode.visible(d) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                if !addedCameras.isEmpty {
                    Text("Added to Bridgit").font(.callout.weight(.bold)).foregroundStyle(B.onSurface)
                        .padding(.leading, 4).padding(.vertical, 6)
                    ForEach(addedCameras) { AddedCard(device: $0).padding(.bottom, 8) }
                }
                if all.isEmpty && live.added.isEmpty { hero.padding(.bottom, 8) }
                HStack {
                    Text("On this network").font(.callout.weight(.bold)).foregroundStyle(B.onSurface)
                    Spacer(minLength: 8)
                    if live.canScanNetwork {
                        Button(action: onLevel2) {
                            HStack(spacing: 6) {
                                Text("Level 2 Diagnostic").font(.subheadline.weight(.medium))
                                Image(systemName: "arrow.clockwise").font(.body.weight(.semibold))
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
                    .font(.footnote).foregroundStyle(B.onSurfaceVariant).padding(.horizontal, 2).padding(.vertical, 8)
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
            if mode == .connected && on == 0 || mode == .unconnected && !all.contains(where: \.disconnected) { mode = .all }
        }
    }

    private var hero: some View {
        InfoPanel {
            VStack(alignment: .leading, spacing: 16) {
                HStack(spacing: 14) {
                    Image(systemName: "dot.radiowaves.left.and.right").font(.title2).foregroundStyle(B.primary)
                        .frame(width: 48, height: 48).background(B.surfaceHigh).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                    Text("Discover nearby devices").font(.title2.weight(.bold)).foregroundStyle(B.onSurface)
                }
                Text("Cameras, speakers, TVs and more — discover what’s on your Wi-Fi or Ethernet network. Device types are suggestions. Pairing and agent access stay in your control.")
                    .font(.subheadline).foregroundStyle(B.onSurfaceVariant)
                if !live.canScanNetwork {
                    Text("Pair with the phone for its full network list. The account snapshot only includes cameras.")
                        .font(.subheadline).foregroundStyle(B.onSurfaceVariant)
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
                tile(.unconnected, "\(all.filter(\.disconnected).count)", "Unconnected", B.onSurfaceVariant)
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
                Text(value).font(.title2.weight(.bold)).foregroundStyle(tint).lineLimit(1)
                Text(name).font(.caption2).foregroundStyle(B.onSurfaceVariant).fixedSize(horizontal: false, vertical: true)
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
                    Text(category.title).font(.body.width(.condensed)).foregroundStyle(GroupColors.text)
                        .lineLimit(1).frame(maxWidth: 140, alignment: .leading).fixedSize(horizontal: true, vertical: false)
                    FitRow(spacing: 8) {
                        ForEach(brands, id: \.id) { b in BrandLogo(brand: b, height: 20, maxWidth: 52, minWidth: 20) }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading).frame(height: 22)
                    .padding(.leading, 10).padding(.trailing, 6)
                    .accessibilityHidden(true)
                    Text("\(devices.filter(\.connected).count)/\(devices.count)").font(.footnote.weight(.bold)).foregroundStyle(B.primary)
                        .padding(.horizontal, 6).padding(.trailing, 10)
                    Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(B.onSurfaceVariant)
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
            Text(device.name).font(.body.weight(.bold)).foregroundStyle(B.onPrimaryContainer)
            Text(line).font(.caption).foregroundStyle(B.onPrimaryContainer)
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
                    Image(systemName: st.icon).font(.callout).foregroundStyle(st.accent)
                        .frame(width: 36, height: 36).background(st.fill).clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
                .frame(width: 42, alignment: .leading).frame(maxHeight: .infinity, alignment: .top)
                .padding(.trailing, 10)
                VStack(alignment: .leading, spacing: 0) {
                    Text(device.name).font(.body.weight(.bold)).foregroundStyle(B.onSurface).fixedSize(horizontal: false, vertical: true)
                    Text([device.typeLabel, device.host, device.model].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · "))
                        .font(.caption).foregroundStyle(B.onSurfaceVariant).fixedSize(horizontal: false, vertical: true).padding(.top, 1)
                    if !services.isEmpty { ServicePills(services: services).padding(.top, 5) }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                VStack(alignment: .leading, spacing: 3) {
                    TimelineView(.periodic(from: .now, by: 30)) { _ in
                        status(device.accountSnapshot || !device.connectionReported ? device.connectionDescription : LastSeen.relative(device.lastSeen), "wifi", device.connected ? B.success : B.onSurfaceVariant)
                    }
                    if added { status("Added", "link", device.connected ? B.primary : B.onSurfaceVariant).accessibilityLabel("Added to Bridgit") }
                    else if device.interfaced { status("Bridgit interfaced", "link", device.connected ? B.primary : B.onSurfaceVariant) }
                }
                .fixedSize().padding(.leading, 8)
                Image(systemName: "chevron.right").font(.caption.weight(.semibold)).foregroundStyle(B.outline)
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
            Image(systemName: icon).font(.caption2.weight(.semibold)).frame(width: 13, height: 13)
            Text(text).font(.caption2)
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
            Text("CONNECTION").font(.caption2.weight(.bold)).kerning(0.8).foregroundStyle(B.primary)
            Text(device.connectionDescription).font(.subheadline)
                .foregroundStyle(device.connected ? B.success : B.onSurfaceVariant).padding(.top, 8)
            Text(device.lastSeenLabel + " · " + LastSeen.full(device.lastSeen)).font(.caption).foregroundStyle(B.onSurfaceVariant).padding(.top, 4)
            if device.connectionReported, let checked = device.connectionCheckedAt {
                Text("Checked · " + LastSeen.full(checked)).font(.caption).foregroundStyle(B.onSurfaceVariant).padding(.top, 4)
            }
            Rectangle().fill(B.outlineVariant).frame(height: 1).padding(.vertical, 10)
            if device.interfaced {
                Text("BRIDGIT INTERFACE").font(.caption2.weight(.bold)).kerning(0.8).foregroundStyle(B.primary)
                Text((device.interfaceLabel ?? "Bridgit interface") + " · Setup available").font(.footnote).foregroundStyle(B.onSurface)
                    .padding(.top, 6).padding(.bottom, 12)
            }
            Text("Suggested type: " + device.typeLabel).font(.caption).foregroundStyle(B.onSurfaceVariant)
            if let m = device.model, !m.isEmpty { Text(m).font(.caption).foregroundStyle(B.onSurface).padding(.top, 6) }
            let ids = [device.modelIdentifier, device.identifier].compactMap { $0 }.filter { !$0.isEmpty }
            if !ids.isEmpty { Text(ids.joined(separator: " · ")).font(.caption2.monospaced()).foregroundStyle(B.outline).padding(.top, 6) }
            if let host = device.host {
                Button {
                    UIPasteboard.general.string = host
                    copied = true
                } label: {
                    Label(copied ? "Copied" : "Copy address", systemImage: copied ? "checkmark" : "doc.on.doc")
                        .font(.subheadline.weight(.medium)).frame(maxWidth: .infinity).frame(minHeight: 40)
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
                Text(s).font(.caption2).foregroundStyle(B.onSurfaceVariant)
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
