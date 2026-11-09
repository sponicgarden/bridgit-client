import BridgitCore
import SwiftUI

struct ConnectionsEntry: View {
    @State private var presented = false
    var body: some View {
        Button { presented = true } label: {
            HStack(spacing: 12) {
                Image(systemName: "arrow.up.right.circle").font(.title2).foregroundStyle(B.primary).frame(width: 40)
                VStack(alignment: .leading, spacing: 3) {
                    Text("Connections").font(.body.weight(.medium))
                    Text("Messages and services").font(.caption).foregroundStyle(B.onSurfaceVariant)
                }
                Spacer()
                Image(systemName: "chevron.right").foregroundStyle(B.primary).accessibilityHidden(true)
            }.frame(minHeight: 48).contentShape(Rectangle())
        }.buttonStyle(.plain)
        .bridgitDialog(isPresented: $presented) { OwnerConnectionsView() }
    }
}

@MainActor
struct OwnerConnectionsView: View {
    @EnvironmentObject private var local: LocalAppletModel
    @DialogDismiss private var dismiss
    @State private var rows: [OwnerConnection] = []
    @State private var loading = true
    @State private var adding = false
    @State private var editing: OwnerConnection?
    @State private var removing: OwnerConnection?
    @State private var rotating: OwnerConnection?
    @State private var secret: OwnerConnection?
    @State private var message: String?
    @State private var problem: String?
    @State private var busy = false
    @State private var sending = false
    @State private var pending: Task<Void, Never>?

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                Text("Connections").font(.title2.bold()).accessibilityAddTraits(.isHeader)
                Spacer()
                Button("Done") { dismiss() }
            }
            Text("Saved on " + LocalHostModel.deviceName).font(.callout).foregroundStyle(B.onSurfaceVariant)
            Text("Agents with Connections access can send to these destinations. Configuration agents can provision receiver credentials under a separate owner-issued grant. Existing addresses, headers and secrets are never returned to agents.")
                .font(.callout)
            Button("Add a connection", systemImage: "plus") { adding = true }.buttonStyle(.borderedProminent)
                .disabled(busy || loading || rows.count >= 25)
            if rows.count >= 25 { Text("At most 25 connections can be saved.").font(.caption) }
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    if loading { ProgressView("Loading connections…") }
                    else if rows.isEmpty { Text("No connections yet.").foregroundStyle(B.onSurfaceVariant) }
                    ForEach(rows) { row in connectionRow(row) }
                }.frame(maxWidth: .infinity, alignment: .leading)
            }
            if sending {
                HStack {
                    ProgressView().controlSize(.small)
                    Text("Sending…").font(.callout)
                    Button("Cancel send") { pending?.cancel() }
                }
            }
            if let message { Text(message).font(.callout).foregroundStyle(B.onSurfaceVariant).accessibilityIdentifier("connection-result") }
            if let problem { Text(problem).font(.callout).foregroundStyle(B.error).textSelection(.enabled) }
        }.padding(24)
        #if os(macOS)
        .frame(minWidth: 480, idealWidth: 620, minHeight: 480, idealHeight: 660)
        #endif
        .task { await reload() }
        .onDisappear { pending?.cancel() }
        .bridgitDialog(isPresented: $adding, onDismiss: { Task { await reload() } }) { AddConnectionForm() }
        .bridgitDialog(item: $editing, onDismiss: { Task { await reload() } }) { row in AddConnectionForm(existing: row) }
        .bridgitDialog(item: $secret) { row in ConnectionSecretView(row: row) }
        .confirmationDialog("Revoke this connection?", isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }), titleVisibility: .visible) {
            if let row = removing {
                Button("Revoke " + row.name, role: .destructive) {
                    removing = nil
                    perform {
                        guard let store = local.store else { throw unavailable() }
                        try await store.revokeOwnerConnection(row.id)
                        return "Connection revoked. A send that already reached the service cannot be recalled."
                    }
                }
            }
            Button("Cancel", role: .cancel) { removing = nil }
        }
        .confirmationDialog("New signing secret?", isPresented: Binding(get: { rotating != nil }, set: { if !$0 { rotating = nil } }), titleVisibility: .visible) {
            if let row = rotating {
                Button("Make a new one") {
                    rotating = nil
                    perform {
                        guard let store = local.store else { throw unavailable() }
                        try await store.rotateOwnerConnectionSecret(row.id)
                        return "New secret saved. Open View signing secret to update your receiving service. Both secrets sign sends for the next 24 hours."
                    }
                }
            }
            Button("Cancel", role: .cancel) { rotating = nil }
        } message: { Text("For 24 hours sends are signed with the old and new secrets, so you can update the receiving service.") }
    }
    private func connectionRow(_ row: OwnerConnection) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(row.name).font(.headline)
            Text(row.preset.name + (row.allowPrivate ? " · Private network approved" : ""))
                .font(.callout).foregroundStyle(B.onSurfaceVariant)
            Text(row.maskedAddress).font(.system(.caption, design: .monospaced)).privacySensitive()
            if row.failing { Label("The last send was not confirmed. Check the service before sending again.", systemImage: "exclamationmark.triangle").font(.callout).foregroundStyle(B.error) }
            Toggle("Enabled", isOn: Binding(get: { row.enabled }, set: { enabled in
                perform {
                    guard let store = local.store else { throw unavailable() }
                    try await store.setOwnerConnection(row.id, enabled: enabled)
                    return enabled ? "Connection enabled." : "Connection disabled."
                }
            })).accessibilityLabel("Enable " + row.name)
            HStack {
                Button("Send a test") {
                    perform(sending: true) {
                        guard let service = local.connections else { throw unavailable() }
                        let reply = try await service.send(to: row.id, text: "Test from Bridgit: this connection works.", data: ["test": true], sender: "owner", phone: LocalHostModel.deviceName) {
                            #if os(iOS)
                            guard UIApplication.shared.applicationState == .active else { throw DeviceAPIError(409, "foreground_required", "Keep Bridgit open to send.") }
                            #endif
                        }
                        return row.name + " answered HTTP " + String(reply["status"] as? Int ?? 0) + "."
                    }
                }.disabled(!row.enabled)
                Menu("Manage") {
                    Button("Edit") { editing = row }
                    if row.preset.signs {
                        Button("View signing secret") { secret = row }
                        Button("New signing secret") { rotating = row }
                    }
                    Button("Revoke", role: .destructive) { removing = row }
                }
            }
        }.padding(14).frame(maxWidth: .infinity, alignment: .leading)
            .background(B.surfaceLow, in: RoundedRectangle(cornerRadius: 12)).disabled(busy)
    }
    private func unavailable() -> DeviceAPIError { .init(503, "connections_unavailable", "Connection storage is unavailable.") }
    private func reload() async {
        defer { loading = false }
        do {
            guard let store = local.store else { throw unavailable() }
            rows = try await store.ownerConnections()
        } catch { problem = error.localizedDescription }
    }
    private func perform(sending: Bool = false, _ action: @escaping @MainActor () async throws -> String) {
        guard !busy else { return }
        busy = true; self.sending = sending; message = nil; problem = nil
        pending = Task {
            defer { busy = false; self.sending = false; pending = nil }
            do { message = try await action() }
            catch is CancellationError { problem = "Send cancelled. It may have reached the service; do not retry automatically." }
            catch { problem = error.localizedDescription }
            await reload()
        }
    }
}

@MainActor
private struct AddConnectionForm: View {
    let existing: OwnerConnection?
    init(existing: OwnerConnection? = nil) {
        self.existing = existing
        _name = State(initialValue: existing?.name ?? "")
        _address = State(initialValue: existing?.address ?? "")
        _presetID = State(initialValue: existing?.presetID ?? "json")
        _allowPrivate = State(initialValue: existing?.allowPrivate ?? false)
        _chatID = State(initialValue: existing?.extra["chat_id"] ?? "")
        _user = State(initialValue: existing?.extra["user"] ?? "")
        _token = State(initialValue: existing?.extra["token"] ?? "")
        _headers = State(initialValue: existing?.headers.sorted(by: { $0.key < $1.key }).map { $0.key + ": " + $0.value }.joined(separator: "\n") ?? "")
    }
    @EnvironmentObject private var local: LocalAppletModel
    @DialogDismiss private var dismiss
    @State private var name = ""
    @State private var address = ""
    @State private var presetID = "json"
    @State private var allowPrivate = false
    @State private var chatID = ""
    @State private var user = ""
    @State private var token = ""
    @State private var headers = ""
    @State private var saving = false
    @State private var problem: String?
    private var preset: ConnectionPreset { ConnectionPreset.named(presetID)! }
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(existing == nil ? "Add a connection" : "Edit connection").font(.title2.bold()).accessibilityAddTraits(.isHeader)
            Form {
                TextField("Webhook URL", text: $address).privacySensitive().autocorrectionDisabled()
                    .accessibilityIdentifier("connection-address")
                    #if os(iOS)
                    .textInputAutocapitalization(.never)
                    #endif
                TextField("Name, like Home Assistant", text: $name).accessibilityIdentifier("connection-name")
                Picker("Service", selection: $presetID) {
                    ForEach(ConnectionPreset.all) { choice in Text(choice.name + (choice.paid ? " (paid)" : "")).tag(choice.id) }
                }.accessibilityIdentifier("connection-service")
                Text(preset.note).font(.callout).foregroundStyle(B.onSurfaceVariant)
                Toggle("On my private network", isOn: $allowPrivate).accessibilityIdentifier("connection-private-network")
                Text("Allows private addresses and nonstandard HTTPS ports. HTTP requires a private address. Loopback, link-local, multicast and reserved addresses stay blocked.")
                    .font(.caption).foregroundStyle(B.onSurfaceVariant)
                if preset.extra.contains("chat_id") { TextField("Telegram chat ID", text: $chatID) }
                if preset.extra.contains("user") { SecureField("Pushover user key", text: $user) }
                if preset.extra.contains("token") { SecureField("Pushover app API token", text: $token) }
                if preset.customHeaders {
                    Text("Fixed headers, one per line: Name: value").font(.caption)
                    TextEditor(text: $headers).font(.system(.caption, design: .monospaced)).frame(minHeight: 80).privacySensitive()
                }
                Text("Approving saves this destination on this device. Only agents with separate Connections access can send to it. Nothing is sent when you approve.")
                    .font(.callout)
            }
            if let problem { Text(problem).foregroundStyle(B.error).font(.callout) }
            HStack {
                Button("Cancel") { dismiss() }.disabled(saving)
                Spacer()
                Button(saving ? "Saving…" : "Approve") { save() }.buttonStyle(.borderedProminent).disabled(saving)
            }
        }.padding(24)
        #if os(macOS)
        .frame(minWidth: 480, idealWidth: 620, minHeight: 500, idealHeight: 680)
        #endif
        .interactiveDismissDisabled(saving)
        .onChange(of: address) { _, value in
            let recognized = ConnectionPreset.recognize(value)
            if name.trimmingCharacters(in: .whitespaces).isEmpty || ConnectionPreset.all.contains(where: { $0.name == name }) {
                name = recognized == "json" ? "" : (ConnectionPreset.named(recognized)?.name ?? "")
            }
            presetID = recognized
            if value.hasPrefix("http://") { allowPrivate = true }
        }
    }
    private func save() {
        guard !saving else { return }; saving = true; problem = nil
        Task {
            defer { saving = false }
            do {
                var fields: [String: String] = [:], extra: [String: String] = [:]
                if preset.customHeaders {
                    var names = Set<String>()
                    for line in headers.split(separator: "\n").map({ $0.trimmingCharacters(in: .whitespacesAndNewlines) }).filter({ !$0.isEmpty }) {
                        guard let colon = line.firstIndex(of: ":") else { throw DeviceAPIError(422, "invalid_connection", "Write each header as Name: value.") }
                        let key = line[..<colon].trimmingCharacters(in: .whitespaces)
                        guard names.insert(key.lowercased()).inserted else { throw DeviceAPIError(422, "invalid_connection", "Use each header name only once.") }
                        fields[key] = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
                    }
                }
                if preset.extra.contains("chat_id") { extra["chat_id"] = chatID.trimmingCharacters(in: .whitespacesAndNewlines) }
                if preset.extra.contains("user") { extra["user"] = user.trimmingCharacters(in: .whitespacesAndNewlines) }
                if preset.extra.contains("token") { extra["token"] = token.trimmingCharacters(in: .whitespacesAndNewlines) }
                var row = try existing ?? OwnerConnection(name: name, address: address, presetID: presetID, allowPrivate: allowPrivate, headers: fields, extra: extra)
                row.name = name.trimmingCharacters(in: .whitespacesAndNewlines); row.address = address.trimmingCharacters(in: .whitespacesAndNewlines)
                row.presetID = presetID; row.allowPrivate = allowPrivate; row.headers = fields; row.extra = extra
                try row.validate()
                guard let store = local.store else { throw DeviceAPIError(503, "connections_unavailable", "Connection storage is unavailable.") }
                if let existing { try await store.updateOwnerConnection(row, expectedRevision: existing.revision) }
                else { try await store.addOwnerConnection(row) }
                dismiss()
            } catch { problem = error.localizedDescription }
        }
    }
}

private struct ConnectionSecretView: View {
    let row: OwnerConnection
    @DialogDismiss private var dismiss
    @State private var copied = false
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Signing secret for " + row.name).font(.headline)
            Text("Use this only in your receiving service to verify Standard Webhooks signatures. Agents never receive it.").font(.callout)
            Text(row.signingSecret).font(.system(.callout, design: .monospaced)).textSelection(.enabled).privacySensitive()
            HStack {
                Button(copied ? "Copied" : "Copy signing secret") {
                    #if os(iOS)
                    UIPasteboard.general.setItems([["public.utf8-plain-text": row.signingSecret]], options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(60)])
                    #else
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(row.signingSecret, forType: .string)
                    NSPasteboard.general.setData(Data(), forType: NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType"))
                    #endif
                    copied = true
                }
                Spacer()
                Button("Done") { dismiss() }
            }
        }.padding(24)
        #if os(macOS)
        .frame(minWidth: 440, idealWidth: 580)
        #endif
    }
}
