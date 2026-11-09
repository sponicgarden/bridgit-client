import BridgitCore
import SwiftUI

struct OwnerConfigurationView: View {
    @ObservedObject var model: OwnerConfigurationModel
    private enum Editor: String, Identifiable {
        case deviceName, bridgitName, retention
        var id: Self { self }
    }
    @State private var editor: Editor?
    #if os(iOS)
    @Environment(\.horizontalSizeClass) private var width
    @Environment(\.dynamicTypeSize) private var textSize
    #endif

    private var controls: some View {
        VStack(alignment: .leading, spacing: 12) {
            if model.canConfigureExternalCalls {
                Button {
                    editor = .deviceName
                } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Device name")
                        Text(LocalHostModel.effectiveDeviceName(override: model.deviceNameOverride))
                            .foregroundStyle(B.onSurfaceVariant)
                    }
                    .frame(minHeight: 44, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .accessibilityIdentifier("host.device-name.edit")
                if model.bridgitNames != nil {
                    Button {
                        editor = .bridgitName
                    } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Bridgit name")
                            Text(model.bridgitName?.name ?? "Not set")
                                .foregroundStyle(B.onSurfaceVariant)
                        }
                        .frame(minHeight: 44, alignment: .leading)
                        .contentShape(Rectangle())
                    }
                    .accessibilityIdentifier("host.bridgit-name.edit")
                }
                Toggle("External calls", isOn: Binding(get: { !model.externalCallsPaused },
                    set: { enabled in Task { await model.setExternalCallsPaused(!enabled) } }))
                    .accessibilityIdentifier("host.external-calls")
            }
            if let visibility = model.logVisibility {
                Picker("Agents with Activity access can read", selection: Binding(
                    get: { model.logVisibility ?? visibility },
                    set: { value in Task { await model.setLogVisibility(value) } }
                )) {
                    Text("All Activity").tag(HostLogVisibility.all)
                    Text("Their own Activity").tag(HostLogVisibility.own)
                }
            }
            if model.activityRetention != nil {
                Button {
                    editor = .retention
                } label: {
                    Text("Activity retention").frame(minHeight: 44).contentShape(Rectangle())
                }
            }
            if let speech = model.speech { SpeakerVolumeControl(speech: speech) }
            if let locked = model.directDeviceLock {
                Toggle("Lock direct agents to their Tailscale devices", isOn: Binding(
                    get: { model.directDeviceLock ?? locked },
                    set: { value in Task { await model.setDirectDeviceLock(value) } }
                ))
                Text("The first connection sets the device. Review new Tailscale devices in the agent’s controls.")
                    .font(.caption).foregroundStyle(B.onSurfaceVariant)
            }
            if !model.requests.isEmpty {
                Text("Configuration changes").font(.headline).accessibilityAddTraits(.isHeader)
                ForEach(model.requests.compactMap(ConfigurationOperation.init), id: \.id) { operation in
                    HStack {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(operation.summary)
                            Text(operation.sender).font(.caption).foregroundStyle(B.onSurfaceVariant)
                        }
                        Spacer()
                        ProgressView().accessibilityLabel(operation.committing ? "Finishing configuration" : "Applying configuration")
                        if !operation.committing {
                            Button("Cancel") { Task { await model.cancel(operation.id) } }
                                .accessibilityLabel("Cancel " + operation.summary)
                        }
                    }
                }
            }
            if let problem = model.problem { Text(problem).foregroundStyle(B.error) }
        }
        .task {
            while !Task.isCancelled {
                await model.reload()
                do { try await Task.sleep(nanoseconds: 1_000_000_000) } catch { break }
            }
        }
    }

    @ViewBuilder var body: some View {
        #if os(iOS)
        if width == .compact && textSize.isAccessibilitySize {
            controls.coveringBuildStampSurface(editor != nil).fullScreenCover(item: $editor) { presentedEditor($0) }
        } else {
            controls.coveringBuildStampSurface(editor != nil).bridgitDialog(item: $editor) { presentedEditor($0) }
        }
        #else
        controls.coveringBuildStampSurface(editor != nil).bridgitDialog(item: $editor) { presentedEditor($0) }
        #endif
    }

    private func presentedEditor(_ selection: Editor) -> some View {
        Group {
            switch selection {
            case .deviceName: DeviceNameEditor(model: model, name: model.deviceNameOverride)
            case .bridgitName: BridgitNameEditor(model: model)
            case .retention:
                if let limits = model.activityRetention { ActivityRetentionEditor(model: model, limits: limits) }
            }
        }
        .background(B.surface.ignoresSafeArea())
        .buildStampSurface()
    }
}
private struct DeviceNameEditor: View {
    @DialogDismiss private var dismiss
    @Environment(\.dynamicTypeSize) private var textSize
    let model: OwnerConfigurationModel
    @State private var name: String
    @State private var saving = false
    @State private var problem: String?

    init(model: OwnerConfigurationModel, name: String) {
        self.model = model
        _name = State(initialValue: name)
    }

    private var normalized: String? { try? HostDeviceName.normalized(name) }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack { Spacer(); BuildStamp(foreground: B.onSurfaceVariant) }
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text("Device name").font(.title2.bold()).accessibilityAddTraits(.isHeader)
                    Text("Shown in Bridgit. Leave blank to use the system name.")
                    TextField("Device name", text: $name).textFieldStyle(.roundedBorder)
                        .accessibilityIdentifier("host.device-name.value")
                    if normalized == nil {
                        Text("Use up to 60 characters without control characters.").foregroundStyle(B.error)
                    }
                    if let problem { Text(problem).foregroundStyle(B.error) }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .accessibilityIdentifier("host.device-name.form")
            let actions = textSize.isAccessibilitySize
                ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8))
                : AnyLayout(HStackLayout(spacing: 12))
            actions {
                Button { dismiss() } label: {
                    Text("Cancel").frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
                }
                .keyboardShortcut(.cancelAction)
                .disabled(saving)
                Button { Task { await save() } } label: {
                    Text("Save").frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
                }
                .buttonStyle(.borderedProminent)
                .disabled(normalized == nil || normalized == model.deviceNameOverride || saving)
            }
        }
        .padding(24)
        .interactiveDismissDisabled(saving)
        #if os(macOS)
        .frame(minWidth: 420, minHeight: 240)
        #endif
    }

    private func save() async {
        saving = true
        defer { saving = false }
        do {
            try await model.setDeviceName(name)
            dismiss()
        } catch { problem = error.localizedDescription }
    }
}

private struct ActivityRetentionEditor: View {
    @DialogDismiss private var dismiss
    @Environment(\.dynamicTypeSize) private var textSize
    @ObservedObject var model: OwnerConfigurationModel
    @State private var limits: [HostActivityRetentionField: Int]
    @State private var entries: String
    @State private var days: String
    @State private var saving = false
    @State private var problem: String?

    init(model: OwnerConfigurationModel, limits: [HostActivityRetentionField: Int]) {
        self.model = model
        _limits = State(initialValue: limits)
        _entries = State(initialValue: String(limits[.entries] ?? 0))
        _days = State(initialValue: String(limits[.days] ?? 0))
    }

    private func limit(_ text: String, field: HostActivityRetentionField) -> Int? {
        guard let number = Int(text.trimmingCharacters(in: .whitespacesAndNewlines)), field.range.contains(number) else { return nil }
        return number
    }

    private var draft: [HostActivityRetentionField: Int]? {
        guard let entries = limit(entries, field: .entries), let days = limit(days, field: .days) else { return nil }
        return [.entries: entries, .days: days]
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack { Spacer(); BuildStamp(foreground: B.onSurfaceVariant) }
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text("Activity retention").font(.title2.bold()).accessibilityAddTraits(.isHeader)
                    Text("Use 0 for no limit. Older Activity may be deleted. Diagnostics are kept.")
                    limitField("Maximum entries", field: .entries, text: $entries, invalid: "Enter 0–100,000 entries.")
                    limitField("Maximum days", field: .days, text: $days, invalid: "Enter 0–365 days.")
                    if let problem { Text(problem).foregroundStyle(B.error) }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .accessibilityIdentifier("activity.retention.form")
            if textSize.isAccessibilitySize {
                VStack(alignment: .leading, spacing: 8) { saveButton; cancelButton }
            } else {
                HStack { cancelButton; Spacer(); saveButton }
            }
        }
        .padding(24)
        .interactiveDismissDisabled(saving)
        #if os(macOS)
        .frame(minWidth: 420, idealWidth: 500, minHeight: 310, idealHeight: 360)
        #endif
    }

    private var cancelButton: some View {
        Button { dismiss() } label: {
            Text("Cancel").frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
        }
        .keyboardShortcut(.cancelAction)
        .disabled(saving)
    }

    private var saveButton: some View {
        Button { Task { await save() } } label: {
            Text("Save").frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
        }
        .buttonStyle(.borderedProminent)
        .disabled(draft == nil || draft == limits || saving)
    }

    private func limitField(_ title: String, field: HostActivityRetentionField, text: Binding<String>, invalid: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).accessibilityHidden(true)
            TextField(title, text: text)
                .textFieldStyle(.roundedBorder)
                .accessibilityIdentifier(field.rawValue)
                #if os(iOS)
                .keyboardType(.numberPad)
                #endif
            if limit(text.wrappedValue, field: field) == nil {
                Text(invalid).foregroundStyle(B.error)
            }
        }
    }

    private func save() async {
        guard let draft else { return }
        saving = true
        defer { saving = false }
        do {
            try await model.setActivityRetention(draft.filter { limits[$0.key] != $0.value })
            dismiss()
        } catch { problem = error.localizedDescription }
    }
}

private struct SpeakerVolumeControl: View {
    @ObservedObject var speech: LocalSpeechModel
    var body: some View {
        Stepper(value: Binding(get: { speech.speakerVolumeCeiling }, set: speech.setSpeakerVolumeCeiling),
                in: HostSettingChange.speakerVolumeRange) {
            Text("Agent speech volume limit")
        }
        .accessibilityValue(Text("\(speech.speakerVolumeCeiling)%"))
        Text("\(speech.speakerVolumeCeiling)%").font(.caption).foregroundStyle(B.onSurfaceVariant)
            .accessibilityHidden(true)
    }
}

private struct ConfigurationOperation: Identifiable {
    let id: String, summary: String, sender: String
    let committing: Bool
    init?(_ value: [String: Any]) {
        guard let id = value["request_id"] as? String, let summary = value["summary"] as? String, let sender = value["sender"] as? String else { return nil }
        self.id = id; self.summary = summary; self.sender = sender
        committing = value["status"] as? String == "committing"
    }
}

/// Device Domain Name (bridgit.cc/names): register one, then change its label and visibility.
private struct BridgitNameEditor: View {
    @DialogDismiss private var dismiss
    @Environment(\.dynamicTypeSize) private var textSize
    @ObservedObject var model: OwnerConfigurationModel
    @State private var name: String
    @State private var label: String
    @State private var visible: Bool
    @State private var saving = false
    @State private var problem: String?

    init(model: OwnerConfigurationModel) {
        self.model = model
        let own = model.bridgitName
        _name = State(initialValue: own?.name ?? DeviceDomainName.suggestion(from: LocalHostModel.effectiveDeviceName(override: model.deviceNameOverride)))
        _label = State(initialValue: own?.label ?? "")
        _visible = State(initialValue: own?.visible ?? false)
    }

    private var registered: DeviceDomainNameRecord? { model.bridgitName }
    private var parsed: String? { DeviceDomainName.parse(name) }
    private var changed: Bool {
        guard let own = registered else { return parsed != nil }
        let trimmed = label.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed != (own.label ?? "") || visible != own.visible
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack { Spacer(); BuildStamp(foreground: B.onSurfaceVariant) }
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text("Bridgit name").font(.title2.bold()).accessibilityAddTraits(.isHeader)
                    if let own = registered {
                        Text(own.name).font(.body.monospaced()).textSelection(.enabled)
                            .accessibilityIdentifier("host.bridgit-name.value")
                    } else {
                        TextField("Bridgit name", text: $name).textFieldStyle(.roundedBorder)
                            .autocorrectionDisabled()
                            #if os(iOS)
                            .textInputAutocapitalization(.never)
                            #endif
                            .accessibilityIdentifier("host.bridgit-name.value")
                        if parsed == nil {
                            Text("3 to 30 lowercase letters, digits and hyphens.").foregroundStyle(B.error)
                        }
                    }
                    TextField("Label", text: $label).textFieldStyle(.roundedBorder)
                        .accessibilityIdentifier("host.bridgit-name.label")
                    Toggle("Visible", isOn: $visible).accessibilityIdentifier("host.bridgit-name.visible")
                    if let problem { Text(problem).foregroundStyle(B.error) }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .accessibilityIdentifier("host.bridgit-name.form")
            let actions = textSize.isAccessibilitySize
                ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8))
                : AnyLayout(HStackLayout(spacing: 12))
            actions {
                Button { dismiss() } label: {
                    Text("Cancel").frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
                }
                .keyboardShortcut(.cancelAction)
                .disabled(saving)
                Button { Task { await save() } } label: {
                    Text(registered == nil ? "Register" : "Save").frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
                }
                .buttonStyle(.borderedProminent)
                .disabled(!changed || saving)
                .accessibilityIdentifier("host.bridgit-name.save")
            }
        }
        .padding(24)
        .interactiveDismissDisabled(saving)
        .task { await model.refreshBridgitName() }
        .onChange(of: model.bridgitName) { _, own in
            guard let own, !saving else { return }
            name = own.name; label = own.label ?? ""; visible = own.visible
        }
        #if os(macOS)
        .frame(minWidth: 420, minHeight: 300)
        #endif
    }

    private func save() async {
        saving = true
        defer { saving = false }
        problem = nil
        let trimmed = label.trimmingCharacters(in: .whitespacesAndNewlines)
        do {
            if let own = registered {
                try await model.setBridgitName(label: trimmed == (own.label ?? "") ? nil : .some(trimmed.isEmpty ? nil : trimmed),
                                               visible: visible == own.visible ? nil : visible)
            } else {
                try await model.setBridgitName(name: parsed, label: trimmed.isEmpty ? nil : .some(trimmed), visible: visible)
            }
            dismiss()
        } catch { problem = error.localizedDescription }
    }
}
