import BridgitCore
import CoreFoundation
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers
import ImageIO

/// Match package card semantics: primary is filled; plain or omitted is outlined.
private struct AppletDeclaredActionStyle: ViewModifier {
    let style: String?
    @ViewBuilder func body(content: Content) -> some View {
        if style == "primary" { content.buttonStyle(.borderedProminent) }
        else { content.buttonStyle(.bordered) }
    }
}

struct LocalAppletReference: Identifiable { var id: String; var name: String; var paused = false }

/// These screens always name their device; account applets are rendered by separate views.
struct LocalAppletsSection: View {
    @EnvironmentObject private var local: LocalAppletModel
    @State private var open: LocalAppletReference?
    @State private var review: BundledApplet?
    @State private var removing: LocalAppletReference?
    var showInstall = true
    var title = "Installed"
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Text(title).font(.subheadline.weight(.medium)).foregroundStyle(B.primary).accessibilityAddTraits(.isHeader)
                Spacer()
                if showInstall, let host = local.host {
                    BuildInstructionDelivery(host: host, createApplet: true)
                }
            }
            ForEach(local.packages.compactMap { p -> LocalAppletReference? in guard let id = p["id"] as? String else { return nil }; return .init(id: id, name: p["name"] as? String ?? id, paused: p["installation_status"] as? String == "paused") }) { applet in
                Button { open = applet } label: {
                    HStack { Label(applet.name, systemImage: "square.grid.2x2").font(.headline); Spacer(); Text(applet.paused ? "Paused" : "Local").font(.caption); Image(systemName: "chevron.right") }
                        .padding(14).frame(maxWidth: .infinity, alignment: .leading)
                }.buttonStyle(.bordered)
                .contextMenu {
                    Button(applet.paused ? "Resume" : "Pause", systemImage: applet.paused ? "play" : "pause") { Task { await local.pause(applet: applet.id, paused: !applet.paused) } }
                    Button("Remove", systemImage: "trash", role: .destructive) { removing = applet }
                }
                .accessibilityAction(named: Text(applet.paused ? "Resume applet" : "Pause applet")) { Task { await local.pause(applet: applet.id, paused: !applet.paused) } }
                .accessibilityAction(named: Text("Remove applet")) { removing = applet }
            }
            if local.packages.isEmpty { Text("No installed applets").font(.callout).foregroundStyle(B.onSurfaceVariant) }
            if showInstall {
                Text("Available").font(.subheadline.weight(.medium)).foregroundStyle(B.primary).accessibilityAddTraits(.isHeader)
                ForEach(BundledApplet.catalog.filter { entry in
                    !local.packages.contains { $0["id"] as? String == entry.id }
                }) { entry in
                    Button { review = entry } label: {
                        AppletCatalogRow(name: entry.name, summary: entry.subtitle, icon: Image(entry.icon))
                    }.buttonStyle(.plain)
                    .accessibilityLabel("Review \(entry.name). \(entry.subtitle)")
                    Divider().padding(.leading, 52)
                }
            }
            if let problem = local.problem { Text(problem).font(.callout).foregroundStyle(B.error).textSelection(.enabled) }
        }
        .bridgitDialog(item: $review) { entry in BundledAppletReview(entry: entry) }
        .confirmationDialog("Remove applet?", isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }), titleVisibility: .visible) {
            if let applet = removing { Button("Remove " + applet.name, role: .destructive) { removing = nil; Task { await local.remove(applet: applet.id) } } }
            Button("Cancel", role: .cancel) { removing = nil }
        }
        .bridgitDialog(item: $open) { applet in
            VStack(spacing: 0) {
                HStack { Text(applet.name).font(.headline); Spacer(); Button("Done") { open = nil } }.padding()
                LocalAppletCardView(applet: applet.id)
            }
            #if os(macOS)
            .frame(minWidth: 620, idealWidth: 850, minHeight: 420, idealHeight: 600)
            #endif
        }
    }
}

private struct AppletCatalogIcon: View {
    let image: Image
    var body: some View {
        image.renderingMode(.template).resizable().scaledToFit().frame(width: 24, height: 24)
            .foregroundStyle(B.primary).padding(8)
            .background(B.surfaceHigh, in: RoundedRectangle(cornerRadius: 9))
            .accessibilityHidden(true)
    }
}

struct AppletCatalogRow: View {
    let name: String
    let summary: String
    let icon: Image

    var body: some View {
        HStack(spacing: 12) {
            AppletCatalogIcon(image: icon)
            VStack(alignment: .leading, spacing: 4) {
                Text(name).font(.body.weight(.medium)).foregroundStyle(B.onSurface)
                Text(summary).font(.subheadline).foregroundStyle(B.onSurfaceVariant)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Image(systemName: "chevron.right").foregroundStyle(B.onSurfaceVariant).accessibilityHidden(true)
        }
        .frame(minHeight: 48)
        .contentShape(Rectangle())
    }
}

struct BuildInstructionsRow: View {
    var body: some View {
        HStack(spacing: 12) {
            AppletCatalogIcon(image: Image("applet_tools"))
            Text("Build instructions").font(.body.weight(.medium))
            Spacer()
            Image(systemName: "chevron.right").foregroundStyle(B.primary).accessibilityHidden(true)
        }
        .frame(minHeight: 48)
        .contentShape(Rectangle())
    }
}

struct BundledApplet: Identifiable {
    let id: String
    let name: String
    let subtitle: String
    let icon: String

    static let catalog = [
        Self(id: "a-sorta-fairytale-about-me", name: "A Sorta Fairytale About Me", subtitle: "Your surroundings become a spoken story.", icon: "applet_chef"),
        Self(id: "chef-helper", name: "Chef’s Helper", subtitle: "Spoken tips while you cook.", icon: "applet_chef"),
        Self(id: "insurance-inventory", name: "Insurance inventory", subtitle: "Photo inventory for your insurer.", icon: "applet_shield"),
        Self(id: "exercise-check", name: "Exercise check", subtitle: "Camera-based rep counting.", icon: "applet_fitness")
    ]
}

/// Display the package's own declarations even when validation refuses installation.
struct AppletReviewDetails: Decodable {
    struct Permissions: Decodable { let required: [String] }
    struct Table: Decodable, Identifiable {
        let id: String
        let label: String?
        let keep_days: Int?
        let max_rows: Int?
        var retention: String {
            "Keeps up to " + String(max_rows ?? 1000) + " rows on this device for " + String(keep_days ?? 90) + " days."
        }
    }
    struct Step: Decodable {
        let type: String
        let table: String?
        let allow_cloud: Bool?
        let recordsEachItem: Bool

        private enum CodingKeys: String, CodingKey { case type, table, allow_cloud, for_each }

        init(from decoder: Decoder) throws {
            let fields = try decoder.container(keyedBy: CodingKeys.self)
            type = try fields.decode(String.self, forKey: .type)
            table = try fields.decodeIfPresent(String.self, forKey: .table)
            allow_cloud = try fields.decodeIfPresent(Bool.self, forKey: .allow_cloud)
            recordsEachItem = fields.contains(.for_each)
        }

        var canSpend: Bool {
            ["appraise", "ask_model", "kitchen", "service"].contains(type) || (["hear", "speak"].contains(type) && allow_cloud == true)
        }
        func description(tables: [Table]) -> String {
            let target = tables.first { $0.id == table }?.label ?? table ?? "this applet’s records"
            switch type {
            case "appraise": return "Sends the kept photo off this device to a paid model, which lists the things in it with prices."
            case "record" where recordsEachItem:
                return "Saves a row in " + target + " for each item (at most " + String(AppletEngine.maximumRecordedItems) + ")."
            case "record": return "Saves a row in " + target + "."
            case "update": return "Updates a row in " + target + "."
            case "hear": return "Uses the microphone to listen for speech."
            case "speak": return "Speaks aloud."
            default: return "Requests the " + type + " operation."
            }
        }
    }
    struct Flow: Decodable, Identifiable {
        let id: String
        let name: String?
        let steps: [Step]?
        let max_paid_per_day: Int?
    }
    struct Processing: Decodable {
        let tables: [Table]?
        let flows: [Flow]?
        private enum CodingKeys: String, CodingKey { case tables, flows, workflow }
        private struct Workflow: Decodable {
            let name: String?
            let steps: [Step]?
            let max_paid_per_day: Int?
        }
        init(from decoder: Decoder) throws {
            let fields = try decoder.container(keyedBy: CodingKeys.self)
            tables = try fields.decodeIfPresent([Table].self, forKey: .tables)
            if let single = try fields.decodeIfPresent(Workflow.self, forKey: .workflow) {
                flows = [Flow(id: "main", name: single.name, steps: single.steps, max_paid_per_day: single.max_paid_per_day)]
            } else {
                flows = try fields.decodeIfPresent([Flow].self, forKey: .flows)
            }
        }
    }
    let summary: String?
    let version: String?
    let permissions: Permissions?
    let processing: Processing?

    var hasPaidFlow: Bool { processing?.flows?.contains { $0.steps?.contains(where: \.canSpend) == true } == true }

    static func spendingSummary(for package: [String: Any], paidFlow: Bool) throws -> String? {
        let permissions = package["permissions"] as? [String: Any]
        let required = Set(permissions?["required"] as? [String] ?? [])
        guard package["budget"] != nil || paidFlow || !required.isDisjoint(with: ["judgment", "find_crop"]) else {
            return nil
        }
        let limits = try AppletSpendingLimit.limits(for: package)
        let amounts = limits.map { limit in
            let amount = limit.usd.formatted(.currency(code: "USD").locale(Locale(identifier: "en_US")).precision(.fractionLength(0...4)))
            return amount + " per " + limit.per
        }.joined(separator: " or ")
        let source = package["budget"] == nil ? "Bridgit’s default" : "Package limit"
        return source + ": " + amounts + ". Linked devices on the same account share this applet’s budget."
    }

    func buttonTriggers(in package: [String: Any]) -> [String: [String]] {
        let card = (package["ui"] as? [String: Any])?["card"] as? [String: Any]
        let sources = card?["sources"] as? [String: [String: Any]] ?? [:]
        let pieces = card?["pieces"] as? [[String: Any]] ?? []
        let tables = processing?.tables ?? []
        var triggers: [String: [String]] = [:]
        for piece in pieces where piece["type"] as? String == "buttons" {
            for button in piece["items"] as? [[String: Any]] ?? [] {
                guard let label = button["label"] as? String, let action = button["do"] as? [String: Any],
                      let flow = (action["flow"] as? [String: String])?["$flow"] else { continue }
                var text = "It runs when you tap " + label
                if let source = (action["for_each"] as? [String: String])?["$source"],
                   let table = sources[source]?["table"] as? String {
                    let name = tables.first(where: { $0.id == table })?.label ?? table
                    let maximum = action["max"] as? Int ?? AppletEngine.defaultBatchRowLimit
                    text += ", once for each matching entry in " + name + " (at most " + String(maximum) + " per run)"
                }
                triggers[flow, default: []].append(text + ".")
            }
        }
        return triggers
    }

}

struct BundledAppletReview: View {
    let entry: BundledApplet
    @EnvironmentObject private var local: LocalAppletModel
    @DialogDismiss private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @State private var needsCameraPermission = false
    @State private var spendingDisclosure: String?
    @State private var reviewed: Data?
    @State private var flowTriggers: [String: [String]] = [:]
    @State private var details: AppletReviewDetails?
    @State private var problem: String?
    @State private var installing = false

    private var permissionDetails: [String: (String, String)] { AppletReviewPermissionDetails.all }

    var body: some View {
        VStack(spacing: 0) {
            Text("Install applet").font(.headline).padding()
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text(entry.name).font(.title).accessibilityAddTraits(.isHeader)
                    Text(details?.summary ?? entry.subtitle)
                    if let version = details?.version { Text("Version " + version).font(.subheadline) }
                    Text("Comes with Bridgit").font(.caption).foregroundStyle(B.onSurfaceVariant)
                    if let problem {
                        heading("Before you can install")
                        Text(problem).foregroundStyle(B.error)
                        if needsCameraPermission {
                            if AppleCamera.canRequestPermission {
                                Button("Allow camera access") { Task { _ = await AppleCamera.requestPermission(); await prepare() } }
                            } else {
                                Text("Open the system privacy settings for Camera and allow Bridgit.")
                            }
                        }
                    }
                    let tables = details?.processing?.tables ?? []
                    let workflows = details?.processing?.flows ?? []
                    if !tables.isEmpty || !workflows.isEmpty {
                        heading("What it does")
                        ForEach(workflows) { flow in
                            Text("Workflow · " + (flow.name ?? flow.id)).font(.subheadline.bold())
                            ForEach(Array((flowTriggers[flow.id] ?? []).enumerated()), id: \.offset) { _, trigger in
                                Text(trigger)
                            }
                            ForEach(Array((flow.steps ?? []).enumerated()), id: \.offset) { _, step in
                                Text(step.description(tables: tables))
                            }
                        }
                        ForEach(tables) { table in
                            Text("Saved data · " + (table.label ?? table.id)).font(.subheadline.bold())
                            Text(table.retention)
                        }
                    }
                    if let spendingDisclosure {
                        heading("Spending limit")
                        Text(spendingDisclosure)
                        ForEach(workflows.filter { $0.steps?.contains(where: \.canSpend) == true }) { flow in
                            let attempts = flow.max_paid_per_day ?? AppletEngine.defaultPaidAttemptsPerDay
                            Text((flow.name ?? flow.id) + " · Up to " + String(attempts) + " attempts on this device per UTC day.")
                        }
                    }
                    if let required = details?.permissions?.required, !required.isEmpty {
                        heading("What it can use")
                        ForEach(required.sorted(), id: \.self) { permission in
                            if let (title, detail) = permissionDetails[permission] {
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(title).font(.subheadline.bold())
                                    Text(detail).foregroundStyle(B.onSurfaceVariant)
                                }
                                .accessibilityElement(children: .combine)
                            } else {
                                Text(permission)
                            }
                        }
                    }
                }.padding().frame(maxWidth: .infinity, alignment: .leading)
            }
            Divider()
            HStack {
                Button { dismiss() } label: {
                    Text("Decline")
                    #if os(iOS)
                        .frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
                    #endif
                }.disabled(installing)
                Spacer()
                Button("Approve") { Task { await install() } }
                    .disabled(reviewed == nil || installing).buttonStyle(.borderedProminent)
                    #if os(iOS)
                    .controlSize(.large)
                    #endif
            }.padding()
        }.task(id: scenePhase) { if scenePhase == .active { await prepare() } }
        #if os(macOS)
        .frame(minWidth: 420, idealWidth: 560, minHeight: 320, idealHeight: 580)
        #endif
    }

    private func heading(_ title: String) -> some View {
        Text(title).font(.subheadline.weight(.medium)).foregroundStyle(B.primary).padding(.top, 6).accessibilityAddTraits(.isHeader)
    }

    private func prepare() async {
        reviewed = nil
        spendingDisclosure = nil
        needsCameraPermission = false
        problem = nil
        do {
            guard let url = Bundle.main.url(forResource: entry.id, withExtension: "json", subdirectory: "Applets") else {
                throw DeviceAPIError(503, "package_unavailable", "This package could not be loaded.")
            }
            let data = try Data(contentsOf: url)
            details = try JSONDecoder().decode(AppletReviewDetails.self, from: data)
            let package = try JSONSerialization.jsonObject(with: data) as? [String: Any] ?? [:]
            flowTriggers = details?.buttonTriggers(in: package) ?? [:]
            spendingDisclosure = try AppletReviewDetails.spendingSummary(for: package, paidFlow: details?.hasPaidFlow == true)
            guard let engine = local.engine else {
                throw DeviceAPIError(503, "package_unavailable", "The local applet store is unavailable.")
            }
            _ = try await engine.validate(data)
            reviewed = data
        } catch {
            needsCameraPermission = (error as? DeviceAPIError)?.code == "camera_permission_required"
            problem = error.localizedDescription
        }
    }

    private func install() async {
        guard let engine = local.engine, let reviewed, !installing else { return }
        installing = true
        defer { installing = false }
        do {
            try await engine.install(reviewed, mode: .create, didInstall: { id in local.speech.disable(applet: id) })
            await local.reload()
            dismiss()
        } catch { problem = error.localizedDescription }
    }
}

struct LocalAppletCardView: View {
    @EnvironmentObject private var local: LocalAppletModel
    let applet: String
    @State private var package: [String: Any] = [:]
    @State private var loading = true
    @State private var tables: [String: [[String: Any]]] = [:]
    @State private var selectedPhotos: [PhotosPickerItem] = []
    @State private var importFile = false
    @State private var camera = false
    @State private var cameraAction: [String: Any] = [:]
    @State private var modelConsent = false
    @State private var retryConsent = false
    @State private var editing: EditingRow?
    @State private var deleting: EditingRow?
    @State private var exportURL: URL?
    @State private var error: String?
    private var card: [String: Any] { (package["ui"] as? [String: Any])?["card"] as? [String: Any] ?? [:] }
    private var batch: AppletBatch { local.batches[applet] ?? .init() }
    private var paused: Bool { local.packages.first { $0["id"] as? String == applet }?["installation_status"] as? String == "paused" }
    private var stateWords: String? {
        let custom = card["states"] as? [String: String] ?? [:]
        if paused { return custom["paused"] ?? "Paused" }
        if error != nil || local.problem != nil { return custom["error"] }
        if loading { return custom["loading"] ?? "Loading…" }
        let sources = card["sources"] as? [String: [String: Any]] ?? [:]
        let tableNames = sources.values.compactMap { $0["table"] as? String }
        if !tableNames.isEmpty && tableNames.allSatisfy({ tables[$0]?.isEmpty != false }) { return custom["empty"] }
        return nil
    }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                if local.fixtureMode { Text("FIXTURE MODE — these results do not describe your photos.").font(.headline).foregroundStyle(B.error) }
                if LocalAppletModel.requiresAppraisal(package: package) { HStack {
                    if local.modelEnabled {
                        Label(local.fixtureMode ? "Fixture appraisals enabled" : "Cloud appraisals enabled", systemImage: "checkmark.shield")
                        Button("Disable") { local.disableModels() }
                    } else {
                        Button(local.registering ? "Registering this device…" : local.fixtureMode ? "Enable fixture appraisals" : "Enable cloud appraisals") { modelConsent = true }
                            .buttonStyle(.bordered).disabled(local.registering)
                    }
                } }
                if let permissions = (package["permissions"] as? [String: Any])?["required"] as? [String], permissions.contains("mic") || permissions.contains("speaker") {
                    SpeechAppletControls(speech: local.speech, applet: applet, hearing: permissions.contains("mic"))
                }
                if batch.state == "running" {
                    ProgressView("Reading \(batch.completed + batch.failed) of \(batch.total)", value: Double(batch.completed + batch.failed), total: Double(max(1, batch.total)))
                    Button("Stop reading", role: .cancel) { local.cancel(applet: applet) }
                } else if batch.total > 0 {
                    Text("\(batch.completed) photos read · \(batch.failed) failed · \(batch.state)").font(.callout)
                }
                if batch.state == "blocked" { Button("Review interrupted appraisals") { retryConsent = true } }
                if let failure = batch.error { Text(failure).font(.callout).foregroundStyle(B.error) }
                if let error = error ?? local.problem { Text(error).font(.callout).foregroundStyle(B.error).textSelection(.enabled) }
                if let stateWords { Text(stateWords).font(.callout).foregroundStyle(B.onSurfaceVariant) }
                ForEach(Array((card["pieces"] as? [[String: Any]] ?? []).enumerated()), id: \.offset) { _, piece in render(piece) }
                if let exportURL { ShareLink(item: exportURL) { Label("Share \(exportURL.pathExtension.uppercased())", systemImage: "square.and.arrow.up") }.buttonStyle(.borderedProminent) }
            }.padding(20).frame(maxWidth: 1000, alignment: .leading).frame(maxWidth: .infinity)
        }
        .task(id: local.revision) { await load() }
        .onChange(of: selectedPhotos) { _, picked in
            Task { for photo in picked { if let data = try? await photo.loadTransferable(type: Data.self) { await local.addPhoto(applet: applet, data: data, action: cameraAction) } }; selectedPhotos = [] }
        }
        .fileImporter(isPresented: $importFile, allowedContentTypes: [.image], allowsMultipleSelection: true) { result in
            Task {
                do { for url in try result.get() { let access = url.startAccessingSecurityScopedResource(); defer { if access { url.stopAccessingSecurityScopedResource() } }; await local.addPhoto(applet: applet, data: try Data(contentsOf: url), action: cameraAction) } }
                catch let failure { self.error = failure.localizedDescription }
            }
        }
        .bridgitDialog(isPresented: $camera) { DeviceCameraView { data in Task { await local.addPhoto(applet: applet, data: data, action: cameraAction) } } }
        .bridgitDialog(item: $editing) { row in LocalRowEditor(package: package, table: row.table, row: row.row) { values in try await local.update(applet: applet, table: row.table, row: row.id, values: values) } }
        .confirmationDialog("Delete this item?", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), titleVisibility: .visible) {
            if let row = deleting { Button("Delete item", role: .destructive) { Task { await local.delete(applet: applet, table: row.table, row: row.id); deleting = nil } } }
            Button("Cancel", role: .cancel) { deleting = nil }
        }
        .confirmationDialog("Allow another attempt for interrupted appraisals?", isPresented: $retryConsent, titleVisibility: .visible) {
            Button("Allow another attempt") { Task { do { try await local.engine?.allowUncertainRetry(applet: applet) } catch { self.error = error.localizedDescription } } }
            Button("Cancel", role: .cancel) { }
        } message: { Text("A lost reply can mean the cloud already processed and charged for a photo. Another attempt may charge again. This allows a retry; run the applet’s action again to start it.") }
        .confirmationDialog(local.fixtureMode ? "Enable the local fixture model?" : "Allow cloud appraisals?", isPresented: $modelConsent, titleVisibility: .visible) {
            Button(local.fixtureMode ? "Enable fixture model" : "Allow cloud appraisals") { Task { await local.enableModels() } }
            Button("Cancel", role: .cancel) { }
        } message: { Text(local.fixtureMode ? "No image leaves this device. Results are fixed test data and are not a real appraisal." : "Running an appraisal sends selected photos to Bridgit’s cloud model and may incur charges within your account and applet limits. You can stop it or disable cloud appraisals here.") }
    }
    @ViewBuilder private func render(_ piece: [String: Any]) -> some View {
        switch piece["type"] as? String {
        case "header": VStack(alignment: .leading, spacing: 6) { if let eyebrow = piece["eyebrow"] as? String { Text(eyebrow.uppercased()).font(.caption).foregroundStyle(B.onSurfaceVariant) }; Text(piece["text"] as? String ?? "").font(.title2.bold()) }
        case "note": Text(piece["text"] as? String ?? "").font(.callout).foregroundStyle(B.onSurfaceVariant)
        case "buttons":
            ForEach(Array((piece["items"] as? [[String: Any]] ?? []).enumerated()), id: \.offset) { _, button in
                let action = button["do"] as? [String: Any] ?? [:]
                if action["camera"] != nil {
                    ViewThatFits(in: .horizontal) {
                      HStack {
                        Button(button["label"] as? String ?? "Take photos", systemImage: "camera") { cameraAction = action["camera"] as? [String: Any] ?? [:]; camera = true }.modifier(AppletDeclaredActionStyle(style: button["style"] as? String))
                        PhotosPicker(selection: $selectedPhotos, maxSelectionCount: 30, matching: .images) { Label("Photo library", systemImage: "photo.on.rectangle") }.buttonStyle(.bordered).simultaneousGesture(TapGesture().onEnded { cameraAction = action["camera"] as? [String: Any] ?? [:] })
                        Button("Import images", systemImage: "folder") { cameraAction = action["camera"] as? [String: Any] ?? [:]; importFile = true }.buttonStyle(.bordered)
                      }
                      VStack(alignment: .leading, spacing: 10) {
                        Button(button["label"] as? String ?? "Take photos", systemImage: "camera") { cameraAction = action["camera"] as? [String: Any] ?? [:]; camera = true }.modifier(AppletDeclaredActionStyle(style: button["style"] as? String))
                        PhotosPicker(selection: $selectedPhotos, maxSelectionCount: 30, matching: .images) { Label("Photo library", systemImage: "photo.on.rectangle") }.buttonStyle(.bordered).simultaneousGesture(TapGesture().onEnded { cameraAction = action["camera"] as? [String: Any] ?? [:] })
                        Button("Import images", systemImage: "folder") { cameraAction = action["camera"] as? [String: Any] ?? [:]; importFile = true }.buttonStyle(.bordered)
                      }
                    }.disabled(paused)
                } else if action["flow"] != nil {
                    Button(button["label"] as? String ?? "Run", systemImage: "sparkles") { Task { await local.startBatch(applet: applet, button: button["id"] as? String ?? "create") } }
                        .modifier(AppletDeclaredActionStyle(style: button["style"] as? String)).disabled(!canStartBatch(action))
                } else if let step = action["step"] as? [String: Any], step["type"] as? String == "share", step["as"] as? String == "csv", let table = step["table"] as? String {
                    Button(button["label"] as? String ?? "Export", systemImage: "square.and.arrow.up") {
                        Task { do { exportURL = try await local.export(applet: applet, table: table, declaration: step) } catch { self.error = error.localizedDescription } }
                    }.modifier(AppletDeclaredActionStyle(style: button["style"] as? String))
                } else { Text("This action is not available in this build.").foregroundStyle(B.onSurfaceVariant) }
            }
        case "gallery": gallery(piece)
        case "table": table(piece)
        default: Text("This card piece is not implemented.").foregroundStyle(B.onSurfaceVariant)
        }
    }
    private func source(_ ref: Any?) -> (String, [[String: Any]]) {
        guard let id = (ref as? [String: Any])?["$source"] as? String,
              let definition = (card["sources"] as? [String: [String: Any]])?[id], let table = definition["table"] as? String else { return ("", []) }
        return (table, Array((tables[table] ?? []).prefix(definition["limit"] as? Int ?? 500)))
    }
    private func canStartBatch(_ action: [String: Any]) -> Bool {
        guard batch.state != "running", !paused else { return false }
        let flow = (action["flow"] as? [String: Any])?["$flow"] as? String
        guard !LocalAppletModel.requiresAppraisal(package: package, flow: flow) || local.modelEnabled else { return false }
        let (_, rows) = source(action["for_each"])
        guard let condition = action["only_if"] as? [String: Any], let column = condition["item"] as? String, let excluded = condition["is_not"] as? String else { return !rows.isEmpty }
        return rows.contains { (try? AppletEngine.resolve(["$item": column], results: [:], trigger: [:], item: $0)) as? String != excluded }
    }
    @ViewBuilder private func gallery(_ piece: [String: Any]) -> some View {
        let (_, rows) = source(piece["photos"])
        if rows.isEmpty { Text(piece["empty"] as? String ?? "No photos yet.").foregroundStyle(B.onSurfaceVariant) }
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 140))], spacing: 12) {
            ForEach(Array(rows.prefix(piece["limit"] as? Int ?? 24).enumerated()), id: \.offset) { _, row in
                VStack(alignment: .leading) {
                    if let ref = row[piece["photo"] as? String ?? "photo"] as? String { LocalKeptPhoto(applet: applet, ref: ref).frame(height: 130).clipShape(RoundedRectangle(cornerRadius: 12)) }
                    let status = row[piece["caption"] as? String ?? "status"] as? String ?? ""
                    Text((piece["words"] as? [String: String])?[status] ?? status).font(.callout)
                    if let count = row[piece["caption_count"] as? String ?? "items"] as? Int { Text("\(count) items").font(.caption).foregroundStyle(B.onSurfaceVariant) }
                }
            }
        }
    }
    private func columns(_ piece: [String: Any], table id: String) -> [AppletPresentation.Column] {
        let definitions = (try? AppletStore.tableDefinitions(package))?.first { $0["id"] as? String == id }?["columns"] as? [String: [String: Any]] ?? [:]
        return AppletPresentation.columns(piece["columns"], definitions: definitions)
    }
    @ViewBuilder private func table(_ piece: [String: Any]) -> some View {
        let (id, sourceRows) = source(piece["rows"])
        let rows = Array(sourceRows.prefix(piece["limit"] as? Int ?? 500))
        let columns = columns(piece, table: id)
        let totals = piece["totals"] as? [String] ?? []
        if rows.isEmpty { Text(piece["empty"] as? String ?? "No rows yet.").foregroundStyle(B.onSurfaceVariant) }
        ForEach(AppletPresentation.groups(rows, by: piece["group_by"] as? String), id: \.0) { group, items in
            VStack(alignment: .leading, spacing: 12) {
                if !group.isEmpty { Text(group).font(.headline) }
                ForEach(Array(items.enumerated()), id: \.offset) { _, row in
                    VStack(alignment: .leading, spacing: 12) {
                        HStack(alignment: .top, spacing: 14) {
                            if let photo = piece["photo"] as? [String: Any], let key = photo["column"] as? String, let ref = row[key] as? String {
                                LocalKeptPhoto(applet: applet, ref: ref, box: (photo["box"] as? String).flatMap { row[$0] as? String }).frame(width: 72, height: 72).clipShape(RoundedRectangle(cornerRadius: 9))
                            }
                            VStack(alignment: .leading, spacing: 5) {
                                ForEach(columns) { column in
                                    LabeledContent(column.label, value: column.text(row[column.key]))
                                }
                                ForEach((piece["marks"] as? [String: [String: String]] ?? [:]).keys.sorted(), id: \.self) { key in
                                    let marks = piece["marks"] as? [String: [String: String]] ?? [:]
                                    let value = (row[key] as? Bool).map { $0 ? "true" : "false" } ?? (row[key] as? String ?? "")
                                    if let label = marks[key]?[value] { Text(label).font(.caption).foregroundStyle(B.onSurfaceVariant) }
                                }
                            }
                        }
                        HStack(spacing: 20) {
                            if piece["owner_edit"] as? Bool == true { Button("Edit") { editing = .init(table: id, row: row) }.buttonStyle(.borderless).accessibilityLabel("Edit row " + (columns.first.map { $0.text(row[$0.key]) } ?? "")) }
                            if piece["owner_delete"] as? Bool == true { Button("Delete", role: .destructive) { deleting = .init(table: id, row: row) }.buttonStyle(.borderless).accessibilityLabel("Delete row " + (columns.first.map { $0.text(row[$0.key]) } ?? "")) }
                        }
                    }.padding(12).background(B.surfaceLow, in: RoundedRectangle(cornerRadius: 12))
                }
                if !totals.isEmpty { Text(AppletPresentation.totals(items, keys: totals, columns: columns)).font(.callout.bold()) }
            }.padding(.vertical, 8)
        }
        if !rows.isEmpty && !totals.isEmpty { LabeledContent("Grand total", value: AppletPresentation.totals(rows, keys: totals, columns: columns)).font(.headline) }
    }
    private func load() async {
        loading = true; defer { loading = false }
        guard let store = local.store else { return }
        do {
            let package = try await store.package(applet)
            var snapshot: [String: [[String: Any]]] = [:]
            for table in try AppletStore.tableDefinitions(package) { if let id = table["id"] as? String { snapshot[id] = try await store.rows(applet: applet, table: id) } }
            guard !Task.isCancelled else { return }; self.package = package; tables = snapshot
        } catch { self.error = error.localizedDescription }
    }
    private struct EditingRow: Identifiable {
        var table: String; var row: [String: Any]; var id: String { row["id"] as? String ?? "" }
    }
}

struct LocalKeptPhoto: View {
    @EnvironmentObject private var local: LocalAppletModel
    var applet: String
    var ref: String
    var box: String? = nil
    @State private var image: CGImage?
    var body: some View {
        Group { if let image { Image(decorative: image, scale: 1).resizable().scaledToFit() } else { Image(systemName: "photo").foregroundStyle(B.onSurfaceVariant) } }
            .task(id: ref + (box ?? "")) {
                guard let bytes = try? await local.store?.photo(applet: applet, ref: ref), let source = CGImageSourceCreateWithData(bytes as CFData, nil),
                      var decoded = CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceCreateThumbnailWithTransform: true, kCGImageSourceThumbnailMaxPixelSize: 512] as CFDictionary) else { return }
                if let box, let raw = try? JSONSerialization.jsonObject(with: Data(box.utf8)) as? [String: Double],
                   let x0 = raw["x0"], let y0 = raw["y0"], let x1 = raw["x1"], let y1 = raw["y1"],
                   [x0,y0,x1,y1].allSatisfy({ $0.isFinite && (0...1).contains($0) }), x1 > x0, y1 > y0 {
                    decoded = decoded.cropping(to: CGRect(x: x0 * Double(decoded.width), y: y0 * Double(decoded.height), width: (x1-x0) * Double(decoded.width), height: (y1-y0) * Double(decoded.height))) ?? decoded
                }
                guard !Task.isCancelled else { return }; image = decoded
            }
    }
}

private struct LocalRowEditor: View {
    @DialogDismiss private var dismiss
    let package: [String: Any]
    let table: String
    let row: [String: Any]
    var save: ([String: Any]) async throws -> Void
    @State private var saving = false
    @State private var text: [String: String] = [:]
    @State private var flags: [String: Bool] = [:]
    @State private var error: String?
    private var columns: [String: [String: Any]] { ((try? AppletStore.tableDefinitions(package))?.first { $0["id"] as? String == table })?["columns"] as? [String: [String: Any]] ?? [:] }
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack { Text("Edit item").font(.headline); Spacer(); Button("Cancel") { dismiss() }; Button("Save") { commit() }.buttonStyle(.borderedProminent).disabled(saving) }.padding(.horizontal)
            Form {
                ForEach(columns.keys.sorted(), id: \.self) { key in
                    let column = columns[key]!, label = column["label"] as? String ?? key, type = column["type"] as? String ?? "text"
                    if type == "boolean" { Toggle(label, isOn: Binding(get: { flags[key] ?? false }, set: { flags[key] = $0 })) }
                    else if type != "photo" && key != "box" { TextField(label, text: Binding(get: { text[key] ?? "" }, set: { text[key] = $0 })) }
                }
                if let error { Text(error).foregroundStyle(B.error) }
            }
        }.padding(16)
        #if os(macOS)
        .frame(minWidth: 440, idealWidth: 550, minHeight: 360, idealHeight: 560)
        #endif
        .onAppear { for (key, column) in columns { if column["type"] as? String == "boolean" { flags[key] = row[key] as? Bool ?? false } else { text[key] = row[key] is NSNull ? "" : row[key].map { String(describing: $0) } ?? "" } } }
    }
    private func commit() {
        var values: [String: Any] = [:]
        for (key, column) in columns {
            let type = column["type"] as? String ?? "text"
            if type == "photo" || key == "box" { continue }
            if type == "boolean" { values[key] = flags[key] ?? false }
            else if type == "number" || type == "integer" {
                let source = text[key] ?? ""
                if source.isEmpty { values[key] = NSNull(); continue }
                guard let number = Double(source), number.isFinite, type != "integer" || number.rounded() == number else { error = "Enter a valid number for \(column["label"] as? String ?? key)."; return }
                values[key] = number
            } else { values[key] = text[key] ?? "" }
        }
        saving = true; error = nil
        Task {
            do { try await save(values); dismiss() }
            catch { self.error = error.localizedDescription }
            saving = false
        }
    }
}


/// Shared presentation of reported remote applets; it contains no local installation or owner tools.
struct ReportedLinkedApplet: Identifiable {
    let id: String
    let name: String
    let kind: String
    var summary: String?
    var running: Bool?
    var lastAt: Double?
    var sensors: [String] = []
    var icon: String { switch kind { case "dog": "🐕"; case "tour": "🗺️"; case "watch": "👁️"; default: "◈" } }
    init(id: String, name: String, kind: String, summary: String? = nil, running: Bool? = nil, lastAt: Double? = nil, sensors: [String] = []) {
        self.id = id; self.name = name; self.kind = kind; self.summary = summary; self.running = running
        self.lastAt = lastAt; self.sensors = sensors
    }
    init?(raw: [String: Any]) {
        guard let id = raw["id"] as? String, !id.isEmpty else { return nil }
        let value = raw["running"] as? NSNumber
        self.init(id: id, name: raw["name"] as? String ?? raw["kind"] as? String ?? id, kind: raw["kind"] as? String ?? "",
            summary: raw["summary"] as? String,
            running: value.flatMap { CFGetTypeID($0) == CFBooleanGetTypeID() ? $0.boolValue : nil },
            lastAt: (raw["last_at"] as? Double).flatMap { $0.isFinite && $0 > 0 ? $0 : nil },
            sensors: raw["sensors"] as? [String] ?? [])
    }
}
/// Why a linked device's applet list is missing, and the next step (A0244: never a bare "not reported").
struct LinkedAppletGap: Equatable {
    enum Reason: Equatable {
        /// No read has finished yet.
        case checking
        /// This viewer's device views are paused.
        case paused
        /// The last read failed.
        case unreachable
        /// The device answered, but its report has no applet list (an older Bridgit).
        case notInReport
        /// Only the account copy is available and it has no applet list; the date is the device's last report.
        case noAccountReport(Date?)
    }
    var device: String
    var reason: Reason
    var title: String {
        switch reason {
        case .checking: "Reading applets from \(device)…"
        case .paused: "Device views are paused"
        case .unreachable: "Couldn’t reach \(device)"
        case .notInReport: "\(device) didn’t report its applets"
        case .noAccountReport: "\(device) hasn’t reported its applets"
        }
    }
    var detail: String? {
        switch reason {
        case .checking: nil
        case .paused: "Resume device views to read its applets."
        case .unreachable: "Check that \(device) is on and online, then try again."
        case .notInReport: "Update Bridgit on \(device), then try again."
        case .noAccountReport(let at):
            (at.map { "Last report \(Age.text($0)). " } ?? "") + "Open Bridgit on \(device) while signed in to this account, then try again."
        }
    }
    var canRetry: Bool { reason != .checking && reason != .paused }
}

struct LinkedAppletReadList: View {
    var rows: [ReportedLinkedApplet]
    var reported: Bool
    var gap: LinkedAppletGap? = nil
    var retry: (() async -> Void)? = nil
    @State private var retrying = false
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if rows.isEmpty {
                if reported { ContentUnavailableView("No applets reported", systemImage: "square.grid.2x2") }
                else if let gap { missing(gap) }
                else { ContentUnavailableView("Applet status not reported", systemImage: "square.grid.2x2") }
            } else { ForEach(rows) { LinkedAppletReadRow(applet: $0) } }
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
    private func missing(_ gap: LinkedAppletGap) -> some View {
        ContentUnavailableView {
            if gap.reason == .checking { ProgressView(); Text(gap.title) }
            else { Label(gap.title, systemImage: gap.reason == .paused ? "pause.circle" : "square.grid.2x2") }
        } description: {
            if let detail = gap.detail { Text(detail) }
        } actions: {
            if gap.canRetry, let retry {
                Button("Try again") { retrying = true; Task { await retry(); retrying = false } }.disabled(retrying)
            }
        }
    }
}
struct LinkedAppletReadRow: View {
    var applet: ReportedLinkedApplet
    var body: some View {
        Card {
            HStack(spacing: 12) {
                Text(applet.icon).font(.title2).frame(width: 40)
                VStack(alignment: .leading, spacing: 3) {
                    Text(applet.name).font(.headline).foregroundStyle(B.onSurface)
                    let detail = [applet.lastAt.map { "Last active " + Age.text(ms: $0) },
                        applet.sensors.isEmpty ? nil : "Uses " + applet.sensors.joined(separator: ", ")].compactMap { $0 }
                    if !detail.isEmpty { Text(detail.joined(separator: " · ")).font(.caption).foregroundStyle(B.onSurfaceVariant) }
                }
                Spacer(minLength: 0)
                StatePill(text: applet.summary ?? applet.running.map { $0 ? "Running" : "Paused" } ?? "Not reported", tone: applet.running == true ? .good : .idle)
            }
        }
    }
}


enum AppletReviewPermissionDetails {
    static let all = [
        "watches": ("Continuous watches", "Runs the package’s declared ongoing checks."),
        "sounds": ("Sound monitoring", "Uses the package’s declared sound monitoring."),
        "applet_data": ("Its own records", "Keeps what it saves on this device; removing the applet deletes them."),
        "audio.speak": ("Spoken responses", "Reads a response aloud."),
        "camera": ("Camera", "Takes photos for this applet."),
        "camera.snapshot": ("Camera snapshots", "Captures a still image from the camera."),
        "captures": ("Kept captures", "Uses photos, screenshots, recordings and clips a control agent chose to keep."),
        "decisions": ("Jev and Clef", "Sends questions, and the photos they look at, off this device for paid answers."),
        "interactions": ("Cards", "Shows its card and notices on this device."),
        "judgment": ("Decisions (Jev)", "Requests yes/no or multiple-choice answers with probabilities and can act on the result."),
        "location": ("Location", "Knows where this device is."),
        "mic": ("Microphone", "Uses the microphone to listen for speech."),
        "speaker": ("Speaker", "Plays audio and speaks aloud."),
        "video": ("Short video clips", "Records front or rear camera video for 1–30 seconds. Audio needs microphone access."),
        "workflows": ("Workflows", "Runs its approved steps.")
    ]
}
