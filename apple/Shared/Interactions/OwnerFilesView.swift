import BridgitCore
import QuickLook
import SwiftUI
import UniformTypeIdentifiers

enum OwnerFilesDestination: String, CaseIterable, Identifiable {
    case files = "Files", shared = "Shared"
    var id: Self { self }
    var kind: TransferKind { self == .files ? .files : .shares }
}

struct OwnerFilesMenu: View {
    @Binding var selection: OwnerFilesDestination?

    var body: some View {
        Menu {
            ForEach(OwnerFilesDestination.allCases) { item in
                Button(item.rawValue) { selection = item }
            }
        } label: {
            Image(systemName: "ellipsis.circle").frame(minWidth: 44, minHeight: 44)
        }.accessibilityLabel("More options")
    }
}

struct TransferFeatureSettings: View {
    @EnvironmentObject private var local: LocalAppletModel
    @State private var enabled: Set<TransferKind> = []
    @State private var ready = false
    @State private var saving = false
    @State private var problem: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Toggle("Network discovery", isOn: Binding(get: { local.lanDiscoveryEnabled }, set: { local.setLanDiscoveryEnabled($0) }))
                .disabled(local.lan == nil)
                .frame(minHeight: 44)
                .accessibilityIdentifier("lan-discovery-switch")
            Text("Files").font(.headline).accessibilityAddTraits(.isHeader)
            ForEach(TransferKind.allCases, id: \.self) { kind in
                Toggle(isOn: Binding(get: { enabled.contains(kind) }, set: { value in
                    guard let store = local.store else { return }
                    saving = true
                    Task {
                        defer { saving = false }
                        do {
                            try await store.setTransferEnabled(kind, enabled: value)
                            enabled = try await store.enabledTransfers()
                            problem = nil
                        } catch { problem = error.localizedDescription }
                    }
                })) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(kind.settingTitle)
                        Text(detail(kind)).font(.caption).foregroundStyle(B.onSurfaceVariant)
                    }
                }.disabled(!ready || saving)
                    .accessibilityIdentifier("transfer-switch-" + kind.rawValue)
            }
            if let problem { Text(problem).foregroundStyle(B.error) }
        }
        .task {
            guard let store = local.store else { problem = "File storage is unavailable."; return }
            for await _ in await store.changes(to: .transfers) {
                guard !Task.isCancelled else { return }
                do { enabled = try await store.enabledTransfers(); ready = true }
                catch { ready = false; problem = error.localizedDescription }
            }
        }
    }

    private func detail(_ kind: TransferKind) -> String {
        switch kind {
        case .files: return "Files stay in app storage for 30 days. Open Files to review, save or delete them."
        case .downloads: return "Choose individual files for a control agent. The agent can read only the copies you select."
        case .shares: return "Allow each control agent to read the items shared with it."
        }
    }


}

private struct TransferDocument: FileDocument {
    static let readableContentTypes = [UTType.data]
    var data: Data
    init(data: Data) { self.data = data }
    init(configuration: ReadConfiguration) throws { data = configuration.file.regularFileContents ?? Data() }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: data) }
}

struct OwnerFilesView: View {
    let kind: TransferKind
    @EnvironmentObject private var local: LocalAppletModel
    @State private var rows: [[String: Any]] = []
    @State private var agents: [HostAgent] = []
    @State private var downloadsEnabled = false
    @State private var picking = false
    @State private var picked: URL?
    @State private var choosingRecipient = false
    @State private var preview: URL?
    @State private var previewDirectory: URL?
    @State private var export: TransferDocument?
    @State private var exportName = ""
    @State private var exporting = false
    @State private var problem: String?
    @State private var loaded = false
    @State private var receipt: String?
    @State private var incoming = false
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("Files").font(.headline).accessibilityAddTraits(.isHeader)
                Button("Incoming shares") { incoming = true }
                Text("Private storage, kept 30 days. Control agents see only their own files.")
                Button("Choose a file") {
                    receipt = nil
                    if downloadsEnabled { picking = true }
                    else { problem = "Enable Selected Downloads in Feature enablement first." }
                }
                Text("Makes a private copy for one control agent").font(.callout).foregroundStyle(B.onSurfaceVariant)
                if loaded && rows.isEmpty { Text("No files yet.") }
                ForEach(rows.compactMap(TransferRow.init)) { row in
                    Divider()
                    Text(row.name).font(.headline)
                    Text([ByteCountFormatter.string(fromByteCount: row.size, countStyle: .file),
                          agents.first { $0.id == row.agent }?.name ?? "Removed control agent", row.kind.rawValue].joined(separator: " · "))
                        .font(.callout).foregroundStyle(B.onSurfaceVariant)
                    HStack {
                        Button("Open") { Task { await open(row, saving: false) } }
                        Button("Save a copy") { Task { await open(row, saving: true) } }
                        Button("Delete", role: .destructive) {
                            Task {
                                do { try await local.store?.deleteTransfer(id: row.id, for: nil, kind: row.kind); await refresh() }
                                catch { problem = error.localizedDescription }
                            }
                        }
                    }
                }
                Text("Up to 25 MB a file and 100 MB in all. Control agent uploads: 25 files or 25 MB per control agent, 50 files or 50 MB together.")
                    .font(.callout).foregroundStyle(B.onSurfaceVariant)
                if let receipt { Text(receipt) }
                if let problem { Text(problem).foregroundStyle(B.error) }
            }.padding().frame(maxWidth: 680, alignment: .leading).frame(maxWidth: .infinity)
        }
        .task {
            guard let store = local.store else { await refresh(); return }
            for await _ in await store.changes(to: .transfers) {
                guard !Task.isCancelled else { return }
                await refresh()
            }
        }
        .refreshable { await refresh() }
        .onChange(of: scenePhase) { _, phase in if phase == .active { Task { await refresh() } } }
        .fileImporter(isPresented: $picking, allowedContentTypes: [.item]) { result in
            do {
                picked = try result.get()
                Task {
                    await refresh()
                    if recipients.isEmpty { picked = nil; problem = "Add a control agent with selected-file access first." }
                    else { choosingRecipient = true }
                }
            } catch { if (error as NSError).code != NSUserCancelledError { problem = error.localizedDescription } }
        }
        .confirmationDialog("Send selected file to…", isPresented: $choosingRecipient, titleVisibility: .visible) {
            ForEach(recipients) { agent in
                Button(agent.name) {
                    guard let url = picked else { return }
                    picked = nil
                    Task { await send(url, to: agent) }
                }
            }
            Button("Cancel", role: .cancel) { picked = nil }
        }
        .bridgitDialog(isPresented: $incoming) {
            if let model = local.inboundSharing { NavigationStack { InboundSharingView(model: model).toolbar { Button("Done") { incoming = false } } } }
        }
        .fileExporter(isPresented: $exporting, document: export, contentType: .data, defaultFilename: exportName) { result in
            export = nil
            if case .failure(let error) = result { problem = error.localizedDescription }
        }
        .quickLookPreview($preview)
        .onChange(of: preview) { _, url in if url == nil { clearPreview() } }
        .onDisappear { clearPreview() }
    }

    private var recipients: [HostAgent] { agents.filter { !$0.paused && $0.allows(.downloads) } }

    private func refresh() async {
        do {
            guard let store = local.store else { throw DeviceAPIError(503, "storage_unavailable", "File storage is unavailable.") }
            downloadsEnabled = try await store.enabledTransfers().contains(.downloads)
            agents = try await store.transferAgents()
            rows = try await store.transfers(kind: kind)
            loaded = true
            problem = nil
        } catch { problem = error.localizedDescription }
    }

    private func send(_ url: URL, to agent: HostAgent) async {
        do {
            guard let store = local.store else { throw DeviceAPIError(503, "storage_unavailable", "File storage is unavailable.") }
            let item = try await Task.detached {
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                let info = try url.resourceValues(forKeys: [.isRegularFileKey, .contentTypeKey])
                guard info.isRegularFile == true else { throw DeviceAPIError(422, "invalid_parameter", "Choose a regular file.") }
                let handle = try FileHandle(forReadingFrom: url)
                defer { try? handle.close() }
                var data = Data()
                while let part = try handle.read(upToCount: 64 * 1024), !part.isEmpty {
                    guard data.count + part.count <= 25 * 1024 * 1024 else { throw DeviceAPIError(413, "payload_too_large", "Choose a file up to 25 MB.") }
                    data.append(part)
                }
                return (data, info.contentType?.preferredMIMEType ?? "application/octet-stream")
            }.value
            _ = try await store.saveTransfer(for: agent, kind: .downloads, name: url.lastPathComponent, mime: item.1, bytes: item.0)
            await refresh()
            receipt = "Copied \(url.lastPathComponent) for \(agent.name)."
        } catch { problem = error.localizedDescription }
    }

    private func open(_ row: TransferRow, saving: Bool) async {
        do {
            guard let store = local.store else { throw DeviceAPIError(503, "storage_unavailable", "File storage is unavailable.") }
            let file = try await store.transferBytes(id: row.id, for: nil, kind: row.kind)
            if saving {
                export = TransferDocument(data: file.data); exportName = row.name; exporting = true
            } else {
                guard !TransferKind.isInstallable(name: row.name, mime: file.mime) else {
                    throw DeviceAPIError(422, "invalid_parameter", "App packages cannot be opened here.")
                }
                clearPreview()
                let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
                try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
                previewDirectory = dir
                let url = dir.appendingPathComponent(row.name)
                try file.data.write(to: url, options: .atomic)
                preview = url
            }
        } catch { clearPreview(); problem = error.localizedDescription }
    }

    private func clearPreview() {
        if let dir = previewDirectory { try? FileManager.default.removeItem(at: dir); previewDirectory = nil }
    }

    private struct TransferRow: Identifiable {
        let id: String, name: String, agent: String, kind: TransferKind, size: Int64
        init?(_ body: [String: Any]) {
            guard let id = body["id"] as? String, let name = body["name"] as? String, let agent = body["agent_id"] as? String,
                  let kind = TransferKind(rawValue: body["kind"] as? String ?? ""), let size = body["size_bytes"] as? NSNumber else { return nil }
            self.id = id; self.name = name; self.agent = agent; self.kind = kind; self.size = size.int64Value
        }
    }
}
