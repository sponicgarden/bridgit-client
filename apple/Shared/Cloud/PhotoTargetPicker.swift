import SwiftUI

struct PhotoTargetPicker: View {
    var deviceID: String
    var targets: [PhotoTarget]
    @Binding var chosen: [String]
    @State private var editing = false
    @State private var showConnected = false
    @State private var confirmation = 0

    private var ids: [String] { targets.map(\.id) }
    private var names: [String] { targets.filter { chosen.isEmpty || chosen.contains($0.id) }.map(\.name) }
    private var label: String {
        if targets.isEmpty { return "Photos are not connected to any Bridgit applet. No immediate action will be taken." }
        if chosen.isEmpty && targets.count > 1 { return "Checked by: all applets (\(targets.count))" }
        return "Checked by: " + names.joined(separator: ", ")
    }

    static func selection(deviceID: String, targets: [PhotoTarget], fallback: [String]) -> [String] {
        let saved = AppleFixtureWorkspace.defaults.stringArray(forKey: "camera.targets." + deviceID) ?? fallback
        return targets.map(\.id).filter(saved.contains)
    }

    var body: some View {
        VStack(spacing: 8) {
            if targets.isEmpty { Text(label) }
            else {
                Button(label) {
                    editing = true
                }
                if showConnected {
                    Text("Camera is connected to the following Applets:\n\n" + names.map { "• " + $0 }.joined(separator: "\n"))
                        .font(.footnote)
                }
            }
        }
        .task(id: confirmation) {
            showConnected = true
            do { try await Task.sleep(for: .seconds(10)); showConnected = false } catch { }
        }
        .bridgitDialog(isPresented: $editing) {
            PhotoTargetSelectionSheet(targets: targets, initial: Set(chosen.isEmpty ? ids : chosen)) { selection in
                let picked = ids.filter(selection.contains)
                chosen = picked.count == ids.count ? [] : picked
                AppleFixtureWorkspace.defaults.set(chosen, forKey: "camera.targets." + deviceID)
                confirmation += 1
            }
        }
    }
}

private struct PhotoTargetSelectionSheet: View {
    @DialogDismiss private var dismiss
    var targets: [PhotoTarget]
    var commit: (Set<String>) -> Void
    @State private var draft: Set<String>

    init(targets: [PhotoTarget], initial: Set<String>, commit: @escaping (Set<String>) -> Void) {
        self.targets = targets
        self.commit = commit
        _draft = State(initialValue: initial)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Which applets check these photos?").font(.headline).accessibilityAddTraits(.isHeader)
            ScrollView {
                VStack(spacing: 12) {
                    ForEach(targets) { target in
                        #if os(macOS)
                        Toggle(target.name, isOn: Binding(get: { draft.contains(target.id) }, set: { on in
                            if on { draft.insert(target.id) } else { draft.remove(target.id) }
                        })).toggleStyle(.checkbox)
                        #else
                        Button {
                            if draft.contains(target.id) { draft.remove(target.id) } else { draft.insert(target.id) }
                        } label: {
                            HStack {
                                Text(target.name)
                                Spacer()
                                Image(systemName: draft.contains(target.id) ? "checkmark.circle.fill" : "circle")
                                    .foregroundStyle(B.primary).accessibilityHidden(true)
                            }
                            .frame(minHeight: 48).contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityValue(draft.contains(target.id) ? "Selected" : "Not selected")
                        #endif
                    }
                }
            }
            HStack {
                Spacer()
                Button { dismiss() } label: { Text("Cancel").frame(minWidth: 48, minHeight: 48) }
                    .keyboardShortcut(.cancelAction)
                Button {
                    commit(draft)
                    dismiss()
                } label: { Text("OK").frame(minWidth: 48, minHeight: 48) }
                .keyboardShortcut(.defaultAction)
            }
        }
        .padding(20)
        .foregroundStyle(B.onSurface).tint(B.primary)
        #if os(macOS)
        .frame(minWidth: 360, minHeight: 240)
        #else
        .presentationDetents([.medium, .large])
        #endif
    }
}
