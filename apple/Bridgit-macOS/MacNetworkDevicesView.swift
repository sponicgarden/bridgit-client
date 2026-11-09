import BridgitCore
import SwiftUI

/// Local network observations never change the selected linked-device target.
struct MacNetworkDevicesView: View {
    @EnvironmentObject private var local: LocalAppletModel
    @DialogDismiss private var dismiss
    @AppStorage(DeviceNamePreference.key, store: AppleFixtureWorkspace.defaults) private var nameOverride = ""
    @State private var snapshot = NetworkMapSnapshot(name: LocalHostModel.deviceName, coreLabel: "This Mac")
    @AppStorage("devices.tab", store: AppleFixtureWorkspace.defaults) private var tab = "map"
    @State private var replay = 0
    @State private var mode: ListMode = .all
    @State private var expanded: [String: Bool] = [:]
    @State private var error: String?
    @State private var loading = false
    @State private var confirmScan = false
    @State private var showCoverage = false
    @State private var scanTask: Task<Void, Never>?
    @State private var picked: LanDevice?

    private var displaySnapshot: NetworkMapSnapshot {
        var display = snapshot
        display.name = LocalHostModel.effectiveDeviceName(override: nameOverride)
        return display
    }

    var body: some View {
        VStack(spacing: 12) {
            HStack {
                Text(MoreEntry.network.rawValue).font(.headline).accessibilityAddTraits(.isHeader)
                Spacer()
                BuildStamp()
                Button { showCoverage = true } label: { Image(systemName: "info.circle") }
                    .accessibilityLabel("Discovery coverage")
                Button("Done") { dismiss() }.keyboardShortcut(.cancelAction)
            }
            HStack(spacing: 12) {
                Picker("View", selection: $tab) {
                    Text("Map").tag("map")
                    Text("List").tag("list")
                }.pickerStyle(.segmented).frame(maxWidth: 260)
                Spacer()
                Button("Refresh", systemImage: "arrow.clockwise") { Task { await refresh() } }
                    .disabled(loading || snapshot.scanning)
                Button("Level 2", systemImage: "dot.radiowaves.left.and.right") { confirmScan = true }
                    .disabled(local.lan == nil || loading || snapshot.scanning)
            }
            if let error { Text(error).foregroundStyle(B.error).frame(maxWidth: .infinity, alignment: .leading) }
            if let note = snapshot.scanNote { Text(note).foregroundStyle(B.onSurfaceVariant).frame(maxWidth: .infinity, alignment: .leading) }
            if loading || snapshot.scanning { ProgressView(snapshot.scanning ? "Scanning" : "Loading") }
            if tab == "map" {
                NetworkMapView(snapshot: displaySnapshot, visible: true, replay: replay) { id in
                    if id == "find" {
                        if local.lan == nil { error = "Network discovery is unavailable." }
                        else if !loading && !snapshot.scanning { confirmScan = true }
                    }
                    else if id == "list" { tab = "list" }
                    else {
                        let key = id.hasPrefix("seen:") ? String(id.dropFirst(5)) : id
                        picked = NetworkInventory.devices(in: snapshot.categories).first { $0.id == key }
                    }
                }.frame(minHeight: 320)
            } else {
                listFilters
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 12) {
                        ForEach(snapshot.categories) { category in
                            let devices = mode.visible(category.devices)
                            if !devices.isEmpty {
                            DisclosureGroup(isExpanded: Binding(get: { expanded[category.id] ?? (mode != .groups) },
                                                                set: { expanded[category.id] = $0 })) {
                                ForEach(devices) { device in
                                    Button { picked = device } label: {
                                        HStack {
                                            if let brand = device.brand, BrandImages.image(for: brand.id) != nil {
                                                BrandLogo(brand: brand, height: 24, maxWidth: 48, minWidth: 32)
                                            }
                                            VStack(alignment: .leading, spacing: 4) {
                                                Text(device.name).font(.headline)
                                                if let brand = device.brand, BrandImages.image(for: brand.id) == nil {
                                                    Text(brand.name).font(.caption).foregroundStyle(B.onSurfaceVariant)
                                                }
                                                Text([device.typeLabel, device.host, device.model].compactMap { $0 }.joined(separator: " · "))
                                                    .foregroundStyle(B.onSurfaceVariant)
                                            }
                                            Spacer()
                                            Text(device.connectionDescription).foregroundStyle(B.onSurfaceVariant)
                                        }.frame(maxWidth: .infinity, minHeight: 44, alignment: .leading).contentShape(Rectangle())
                                    }.buttonStyle(.plain)
                                }
                            } label: { Text(category.title + " · " + String(devices.count)).font(.headline) }
                            }
                        }
                        if snapshot.categories.isEmpty && !loading && error == nil { Text("No devices found").foregroundStyle(B.onSurfaceVariant) }
                    }
                }
            }
        }
        .padding(20).frame(minWidth: 560, idealWidth: 760, minHeight: 460, idealHeight: 640)
        .background(B.surface).foregroundStyle(B.onSurface).tint(B.primary).buildStampSurface()
        .task { await refresh() }
        .onChange(of: snapshot.categories) { _, _ in
            let devices = NetworkInventory.devices(in: snapshot.categories)
            if (mode == .connected || mode == .unconnected) && mode.visible(devices).isEmpty { mode = .all; expanded = [:] }
        }
        .onDisappear { scanTask?.cancel(); scanTask = nil }
        .bridgitDialog(isPresented: $confirmScan) {
            NetworkScanReview {
                confirmScan = false
                startScan()
            }
        }
        .bridgitDialog(isPresented: $showCoverage) {
            NetworkCoverageView {
                guard let service = local.lan else { throw NSError(domain: "NetworkCoverage", code: 1, userInfo: [NSLocalizedDescriptionKey: "Local discovery coverage is unavailable."]) }
                return try await service.ownerCoverage().data
            }
        }
        .bridgitDialog(item: $picked) { device in
            VStack(alignment: .leading, spacing: 12) {
                HStack { Text(device.name).font(.headline); Spacer(); Button("Done") { picked = nil }.keyboardShortcut(.cancelAction) }
                if let brand = device.brand {
                    HStack {
                        if BrandImages.image(for: brand.id) != nil {
                            BrandLogo(brand: brand, height: 24, maxWidth: 64).accessibilityHidden(true)
                        }
                        Text(brand.name)
                    }
                }
                LabeledContent("Type", value: device.typeLabel)
                if let host = device.host { LabeledContent("Address", value: host) }
                if let model = device.model { LabeledContent("Model", value: model) }
                LabeledContent("Status", value: device.connectionDescription)
                LabeledContent(device.lastSeenLabel, value: LastSeen.full(device.lastSeen))
                if !device.services.isEmpty { Text(device.services.joined(separator: " · ")) }
            }.padding(20).frame(minWidth: 400).textSelection(.enabled)
        }
    }

    private var listFilters: some View {
        let devices = NetworkInventory.devices(in: snapshot.categories)
        return HStack(spacing: 8) {
            filter(.all, title: "Devices", count: devices.count)
            filter(.groups, title: "Groups", count: snapshot.categories.filter { !$0.devices.isEmpty }.count)
            filter(.connected, title: "Connected", count: ListMode.connected.visible(devices).count)
            filter(.unconnected, title: "Unconnected", count: ListMode.unconnected.visible(devices).count)
        }
    }

    private func filter(_ value: ListMode, title: String, count: Int) -> some View {
        Button {
            mode = value
            expanded = [:]
        } label: {
            VStack(alignment: .leading, spacing: 4) {
                Text(String(count)).font(.title2.weight(.bold))
                Text(title).font(.caption)
            }.frame(maxWidth: .infinity, minHeight: 44, alignment: .leading).padding(8)
                .background(mode == value ? B.surfaceHigh : B.surfaceLow)
                .clipShape(RoundedRectangle(cornerRadius: 10))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title + ", " + String(count))
        .accessibilityAddTraits(mode == value ? [.isSelected] : [])
    }

    private func refresh() async {
        guard !loading, !snapshot.scanning else { return }
        guard let service = local.lan else { error = "Network discovery is unavailable."; return }
        loading = true
        defer { loading = false }
        do {
            let response = try await service.ownerInventory()
            try Task.checkCancellation()
            snapshot.categories = NetworkInventory.parseLan(try JSONSerialization.jsonObject(with: response.data))
            error = nil
        } catch is CancellationError {} catch { self.error = error.localizedDescription }
    }

    private func startScan() {
        guard scanTask == nil, !loading, let service = local.lan else { return }
        snapshot.scanning = true; snapshot.scanNote = nil; snapshot.scanFound = nil; error = nil
        scanTask = Task {
            defer { snapshot.scanning = false; scanTask = nil }
            do {
                let response = try await service.ownerScan { try Task.checkCancellation() }
                    try Task.checkCancellation()
                let payload = try JSONSerialization.jsonObject(with: response.data) as? [String: Any] ?? [:]
                snapshot.categories = NetworkInventory.parseLan(payload)
                snapshot.scanFound = NetworkInventory.devices(in: snapshot.categories).filter(\.connected).map(\.id)
                if (payload["methods"] as? [[String: Any]] ?? []).contains(where: { $0["status"] as? String != "completed" }) {
                    snapshot.scanNote = "Some discovery methods were unavailable."
                }
            } catch is CancellationError {} catch { self.error = error.localizedDescription; snapshot.scanNote = error.localizedDescription }
        }
    }
}
