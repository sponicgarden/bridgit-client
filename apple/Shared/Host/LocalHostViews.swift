import BridgitCore
import SwiftUI
import UniformTypeIdentifiers

struct ActivityExportDocument: FileDocument {
    static let jsonlType = UTType(filenameExtension: "jsonl") ?? UTType(exportedAs: "cc.bridgit.jsonl", conformingTo: .plainText)
    static let readableContentTypes = [UTType.commaSeparatedText, jsonlType]
    static let writableContentTypes = readableContentTypes
    var data: Data
    init(data: Data = Data()) { self.data = data }
    init(configuration: ReadConfiguration) throws { data = configuration.file.regularFileContents ?? Data() }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: data) }
}

private struct ActivityReportPresentation: Identifiable {
    let entry: HostActivityEntry
    let recent: [HostActivityEntry]
    var screen: Data?
    var id: Int64 { entry.id }
}

/// Persistent request history and the separate in-memory host lifecycle events.
struct LocalActivityView: View {
    @EnvironmentObject private var local: LocalAppletModel
    @ObservedObject var host: LocalHostModel
    var showsNavigationTitle = true
    @State private var query = ""
    @State private var records: [HostActivityEntry] = []
    @State private var agents: [HostActivityAgent] = []
    @State private var systemOnly = false
    @State private var hasMore = false
    @State private var loading = false
    @State private var generation = UUID()
    @State private var totals: (requests: Int, photos: Int, clips: Int, errors: Int)?
    @State private var agent: HostActivityAgentFilter?
    @State private var types = Set<String>()
    @State private var typeDraft = Set<String>()
    @State private var choosingTypes = false
    private let activityTypes = ["Requests", "Settings", "Agents", "Diagnostics", "Notes"]
    @State private var errorsOnly = false
    @State private var live = true
    var feedbackReports: FeedbackReports?
    @State private var pendingReport: ActivityReportPresentation?
    @State private var report: ActivityReportPresentation?
    @State private var copied = false
    @State private var selected: HostActivityEntry?
    @State private var problem: String?
    @State private var exportSnapshot: HostActivityExport?
    @State private var pendingExportSnapshot: HostActivityExport?
    @State private var exportFormat: HostActivityExport.Format = .jsonl
    @State private var choosingExport = false
    @State private var preparingExport = false
    @State private var launchExport = false
    @State private var exporting = false
    @State private var exportDocument = ActivityExportDocument()
    @State private var exportStatus: String?
    @State private var exportFilename = ""
    private var exportType: UTType { exportFormat == .csv ? .commaSeparatedText : ActivityExportDocument.jsonlType }
    private let pageSize = 100

    private var typeLabel: String {
        if types.count == 1, let type = types.first { return type }
        return types.isEmpty ? "Type" : "Types (\(types.count))"
    }

    private func agentLabel(_ name: String) -> String {
        name == "Bridgit (system)" ? "Control agent: " + name : name
    }

    private func agentOptionLabel(_ value: HostActivityAgent) -> String {
        let name = agentLabel(value.name)
        switch value.id {
        case .legacyName: return name + " · Earlier activity"
        case .identified(let id):
            let sameName = agents.filter { $0.name == value.name }
            guard sameName.count > 1 else { return name }
            let short = String(id.prefix(8))
            let collision = sameName.contains { other in
                guard other.id != value.id, case .identified(let otherID) = other.id else { return false }
                return otherID.hasPrefix(short)
            }
            return name + " · " + (collision ? id : short)
        }
    }

    private var selectedKinds: Set<HostActivityKind>? {
        guard !types.isEmpty else { return nil }
        var result = Set<HostActivityKind>()
        if types.contains("Requests") { result.insert(.request) }
        if types.contains("Settings") { result.insert(.setting) }
        if types.contains("Agents") { result.formUnion([.agent, .pairing]) }
        if types.contains("Diagnostics") { result.formUnion([.service, .crash]) }
        if types.contains("Notes") { result.insert(.note) }
        return result
    }

    private var lifecycle: [String] {
        let term = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !systemOnly, agent == nil, !errorsOnly, types.isEmpty else { return [] }
        return term.isEmpty ? host.activity : host.activity.filter { $0.localizedCaseInsensitiveContains(term) }
    }
    var body: some View {
        activityContent
        #if os(iOS)
        .preference(key: ActivityToolbarStampPreference.self, value: true)
        #endif
        .background(B.surface)
        .navigationTitle(showsNavigationTitle ? "Activity" : "")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Menu {
                    Button("Export") { Task { await prepareExport() } }
                        .disabled(preparingExport).accessibilityIdentifier("activity-export-open")
                } label: { Label("More", systemImage: "ellipsis") }
                    .accessibilityIdentifier("activity-overflow")
            }
        }
        .bridgitDialog(isPresented: $choosingExport, onDismiss: {
            if launchExport { launchExport = false; exporting = true }
        }) {
            VStack(alignment: .leading, spacing: 20) {
                Text("Export activity").font(.headline)
                Text("\(exportSnapshot?.count ?? 0) entries matching your filters")
                    .accessibilityIdentifier("activity-export-count")
                Text("Saved activity on this device. Current filters are applied to all saved entries.")
                    .font(.callout).foregroundStyle(B.onSurfaceVariant)
                Picker("Format", selection: $exportFormat) {
                    Text("JSONL").tag(HostActivityExport.Format.jsonl)
                    Text("CSV").tag(HostActivityExport.Format.csv)
                }.pickerStyle(.segmented).accessibilityIdentifier("activity-export-format")
                HStack {
                    Button("Cancel") { choosingExport = false }
                        .accessibilityIdentifier("activity-export-cancel")
                    Spacer()
                    Button("Export") {
                        do {
                            guard let exportSnapshot else { return }
                            exportDocument = ActivityExportDocument(data: try exportSnapshot.data(format: exportFormat))
                            pendingExportSnapshot = exportSnapshot
                            exportFilename = HostActivityExport.suggestedFilename(format: exportFormat)
                            launchExport = true; choosingExport = false
                        } catch { problem = error.localizedDescription }
                    }.accessibilityIdentifier("activity-export-save")
                }
            }.padding(24).frame(minWidth: 280)
            #if os(iOS)
            .presentationDetents([.medium])
            #endif
        }
        .fileExporter(isPresented: $exporting, document: exportDocument, contentType: exportType,
                      defaultFilename: exportFilename) { result in
            let completed = pendingExportSnapshot
            pendingExportSnapshot = nil
            switch result {
            case .success:
                guard let completed else { problem = "The exported activity count could not be read."; return }
                exportStatus = "Exported \(completed.count) entries"
                guard let store = local.store else { problem = "The file was exported, but its activity entry could not be saved."; return }
                Task {
                    do { try await completed.recordCompletion(result, in: store) }
                    catch { problem = "The file was exported, but its activity entry could not be saved. " + error.localizedDescription }
                }
            case .failure(let error): problem = error.localizedDescription
            }
        }
        .task(id: "\(live)|\(query)|\(String(describing: agent))|\(errorsOnly)|\(systemOnly)|\(types.sorted())") {
            await refresh()
            guard live, let store = local.store else { return }
            for await _ in await store.changes(to: .activity) {
                guard !Task.isCancelled else { return }
                await refresh()
            }
        }
        .bridgitDialog(isPresented: $choosingTypes) {
            VStack(alignment: .leading, spacing: 8) {
                Text("Choose types").font(.headline)
                ScrollView {
                    VStack(spacing: 0) {
                        ForEach(activityTypes, id: \.self) { value in
                            Button {
                                if typeDraft.contains(value) { typeDraft.remove(value) }
                                else { typeDraft.insert(value) }
                            } label: {
                                HStack {
                                    Image(systemName: typeDraft.contains(value) ? "checkmark.square.fill" : "square")
                                    Text(value)
                                    Spacer()
                                }.frame(minHeight: 48).contentShape(Rectangle())
                            }.buttonStyle(.plain)
                                .accessibilityIdentifier("activity-choice-" + value)
                                .accessibilityAddTraits(typeDraft.contains(value) ? .isSelected : [])
                        }
                    }
                }
                HStack {
                    Button { types = []; choosingTypes = false } label: {
                        Text("Clear").frame(minWidth: 48, minHeight: 48).contentShape(Rectangle())
                    }
                    Spacer()
                    Button { types = typeDraft; choosingTypes = false } label: {
                        Text("Done").frame(minWidth: 48, minHeight: 48).contentShape(Rectangle())
                    }
                }.buttonStyle(.plain).foregroundStyle(B.primary)
            }.padding().frame(minWidth: 280, minHeight: 300)
            #if os(iOS)
            .presentationDetents([.medium, .large])
            #endif
        }
        .feedbackComposer(item: $report) { report in
            FeedbackForm(kind: .bug, store: feedbackReports ?? .shared, lastScreen: report.screen,
                         sourceScreen: "Activity", activityEntry: report.entry, activityLog: report.recent)
        }
        .bridgitDialog(item: $selected, onDismiss: {
            if var next = pendingReport {
                pendingReport = nil
                next.screen = FeedbackCapture.lastScreen()
                report = next
            }
        }) { item in
            VStack(alignment: .leading, spacing: 16) {
                let detail = HostActivityDetail(item)
                Group {
                    Text(item.operation).font(.headline)
                    Text("Entry details").font(.subheadline)
                    ScrollView {
                        VStack(alignment: .leading, spacing: 6) {
                            ForEach(HostActivityDetail.keys, id: \.self) { key in
                                LabeledContent {
                                    Text(detail.text(for: key)).foregroundStyle(B.onSurface)
                                } label: { Text(key).foregroundStyle(B.onSurfaceVariant) }
                                    .font(.system(.caption, design: .monospaced))
                                    .textSelection(.enabled)
                                    .accessibilityIdentifier("activity-detail-field-" + key)
                            }
                        }
                    }.accessibilityIdentifier("activity-detail-fields")
                    Button {
                        do {
                            let json = try detail.json()
                            #if os(iOS)
                            UIPasteboard.general.string = json
                            copied = true
                            #else
                            NSPasteboard.general.clearContents()
                            copied = NSPasteboard.general.setString(json, forType: .string)
                            #endif
                        } catch { problem = error.localizedDescription }
                    } label: {
                        Text(copied ? "Copied" : "Copy as JSON").frame(minWidth: 48, minHeight: 48).contentShape(Rectangle())
                    }
                }
                Button {
                    systemOnly = ![HostActivityKind.request, .agent, .pairing, .note].contains(item.kind)
                    agent = systemOnly ? nil : (item.agentID.map(HostActivityAgentFilter.identified) ?? .legacyName(item.agent))
                    selected = nil
                } label: {
                    Text((item.kind == .request || item.kind == .agent || item.kind == .pairing || item.kind == .note ? agentLabel(item.displayAgentName) : "Bridgit") + "'s activity")
                        .frame(minWidth: 48, minHeight: 48).contentShape(Rectangle())
                }
                Button {
                    Task {
                        do {
                            guard let store = local.store else { return }
                            let recent = try await store.hostActivity(limit: 50)
                            guard selected?.id == item.id else { return }
                            pendingReport = ActivityReportPresentation(entry: item, recent: recent)
                            selected = nil
                        } catch { problem = error.localizedDescription }
                    }
                } label: {
                    Text("Report a bug about this").frame(minHeight: 48).contentShape(Rectangle())
                }
                Button { selected = nil } label: {
                    Text("Done").frame(minWidth: 48, minHeight: 48).contentShape(Rectangle())
                }
            }.padding(24).frame(minWidth: 320)
        }
    }
    @ViewBuilder private var activityContent: some View {
        #if os(macOS)
        content
        #else
        ScrollView { content }
        #endif
    }
    @ViewBuilder private var filterControls: some View {
        filterAgentAndType
        filterStateControls
    }
    @ViewBuilder private var filterAgentAndType: some View {
        let agentTitle = systemOnly ? "Bridgit (system)" : agents.first(where: { $0.id == agent }).map(agentOptionLabel) ?? "Agent"
        Menu {
            Button("All agents") { agent = nil; systemOnly = false }
            ForEach(agents) { value in Button(agentOptionLabel(value)) { agent = value.id; systemOnly = false } }
            Button("Bridgit (system)") { agent = nil; systemOnly = true }
        } label: {
            HStack(spacing: 6) {
                Text(agentTitle)
                #if !os(macOS)
                Image(systemName: "chevron.down").font(.caption).accessibilityHidden(true)
                #endif
            }.frame(minHeight: 44)
        }.buttonStyle(.bordered).accessibilityLabel(agentTitle).accessibilityIdentifier("activity-agent")
        Button {
            typeDraft = types
            choosingTypes = true
        } label: {
            HStack(spacing: 6) { Text(typeLabel); Image(systemName: "chevron.down").font(.caption).accessibilityHidden(true) }
                .frame(minHeight: 44)
        }.buttonStyle(.bordered).accessibilityLabel(typeLabel).accessibilityIdentifier("activity-type")
    }
    @ViewBuilder private var filterStateControls: some View {
        filterToggle("Errors only", value: $errorsOnly, id: "activity-errors")
        filterToggle("Live", value: $live, id: "activity-live")
        if !live {
            Button { Task { await refresh() } } label: { Text("Refresh").frame(minHeight: 44) }
                .buttonStyle(.bordered).accessibilityIdentifier("activity-refresh")
        }
    }
    @ViewBuilder private func filterToggle(_ title: String, value: Binding<Bool>, id: String) -> some View {
        if value.wrappedValue {
            Toggle(isOn: value) {
                HStack(spacing: 6) { Image(systemName: "checkmark").accessibilityHidden(true); Text(title) }
                    .frame(minHeight: 44)
                    #if os(iOS)
                    .foregroundStyle(B.onPrimary)
                    #endif
            }.toggleStyle(.button)
                .buttonStyle(.borderedProminent).tint(B.primary)
                .overlay(selectedFilterOutline)
                .accessibilityIdentifier(id)
        } else {
            Toggle(isOn: value) { Text(title).frame(minHeight: 44) }.toggleStyle(.button)
                .buttonStyle(.bordered).accessibilityIdentifier(id)
        }
    }
    @ViewBuilder private var selectedFilterOutline: some View {
        #if os(macOS)
        RoundedRectangle(cornerRadius: 8).stroke(B.primary, lineWidth: 2)
        #else
        Capsule().stroke(B.primary, lineWidth: 2)
        #endif
    }
    private var content: some View {
            VStack(alignment: .leading, spacing: 16) {
                HStack(spacing: 12) {
                    Image(systemName: "magnifyingglass")
                        .foregroundStyle(B.onSurfaceVariant).accessibilityHidden(true)
                    TextField("Search activity", text: $query,
                              prompt: Text("Search activity").foregroundStyle(B.onSurfaceVariant))
                        .textFieldStyle(.plain).foregroundStyle(B.onSurface)
                        .accessibilityLabel("Search activity").accessibilityIdentifier("activity-search")
                }
                .padding(12).frame(minHeight: 44)
                .background(B.surfaceContainer, in: Capsule())
                if let totals {
                    InfoPanel {
                        (Text("Last 24 hours").bold() + Text(" · ^[\(totals.requests) request](inflect: true) · ^[\(totals.photos) photo](inflect: true) · ^[\(totals.clips) clip](inflect: true) · ^[\(totals.errors) error](inflect: true)"))
                            .font(.subheadline).foregroundStyle(B.onSurfaceVariant)
                            .fixedSize(horizontal: false, vertical: true)
                    }.accessibilityIdentifier("activity-summary")
                }
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 8) { filterControls }.fixedSize(horizontal: true, vertical: false)
                    VStack(alignment: .leading, spacing: 8) {
                        HStack(spacing: 8) { filterAgentAndType }.fixedSize(horizontal: true, vertical: false)
                        HStack(spacing: 8) { filterStateControls }.fixedSize(horizontal: true, vertical: false)
                    }.fixedSize(horizontal: true, vertical: false)
                    VStack(alignment: .leading, spacing: 8) { filterControls }
                }
                if let problem { Text(problem).foregroundStyle(B.error) }
                if let exportStatus { Text(exportStatus).accessibilityIdentifier("activity-export-result") }
                if records.isEmpty && lifecycle.isEmpty {
                    InfoPanel {
                        VStack(spacing: 12) {
                            Image(systemName: "chart.line.uptrend.xyaxis")
                                .font(.largeTitle).foregroundStyle(B.onSurfaceVariant)
                                .accessibilityHidden(true)
                            Text(query.isEmpty && !systemOnly && agent == nil && types.isEmpty && !errorsOnly ? "Nothing yet." : "No entries match")
                                .font(.headline).accessibilityAddTraits(.isHeader)
                            if !query.isEmpty || systemOnly || agent != nil || !types.isEmpty || errorsOnly {
                                Button("Clear filters") { query = ""; agent = nil; systemOnly = false; types = []; errorsOnly = false }
                            }
                            if query.isEmpty && !systemOnly && agent == nil && types.isEmpty && !errorsOnly && problem == nil {
                                Text("When a control agent uses Bridgit, it shows up here.").foregroundStyle(B.onSurfaceVariant)
                            }
                        }
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical)
                    }.accessibilityIdentifier("activity-empty-panel")
                } else {
                    ForEach(records, id: \.id) { item in
                        Button { copied = false; selected = item } label: {
                            HStack {
                                VStack(alignment: .leading) {
                                    Text(item.operation).font(.headline)
                                    Text((item.kind == .request ? agentLabel(item.displayAgentName) : item.displayAgentName) + " · " + item.at.formatted()).font(.caption).foregroundStyle(B.onSurfaceVariant)
                                }
                                Spacer()
                                if item.kind == .request { Text(String(item.status)) }
                                Image(systemName: "chevron.right").accessibilityHidden(true)
                            }.contentShape(Rectangle())
                        }.buttonStyle(.plain).accessibilityIdentifier("activity-\(item.kind.rawValue)-\(item.id)")
                        Divider()
                    }
                }
                if hasMore {
                    Button("Load older") { Task { await loadOlder() } }.disabled(loading)
                        .accessibilityIdentifier("activity-load-older")
                }
                if !lifecycle.isEmpty {
                    Text("Host activity on this device").font(.headline).accessibilityAddTraits(.isHeader)
                    Text("Host connection and access changes during this session.").font(.callout).foregroundStyle(B.onSurfaceVariant)
                    ForEach(Array(lifecycle.enumerated()), id: \.offset) { _, event in
                        Text(event).frame(maxWidth: .infinity, alignment: .leading)
                        Divider()
                    }
                }
            }.padding(16).frame(maxWidth: 760).frame(maxWidth: .infinity, alignment: .top)
    }
    @MainActor private func prepareExport() async {
        guard !preparingExport else { return }
        preparingExport = true
        defer { preparingExport = false }
        guard let store = local.store else { problem = "Activity storage is unavailable."; return }
        let query = query, agent = agent, errorsOnly = errorsOnly
        let includeRequests = !systemOnly && (types.isEmpty || types.contains("Requests"))
        let includeSettings = types.isEmpty || types.contains("Settings")
        do {
            exportSnapshot = try await store.hostActivityExport(query: query, agentFilter: agent, errorsOnly: errorsOnly, kinds: selectedKinds, systemOnly: systemOnly,
                includeRequests: includeRequests, includeSettings: includeSettings)
            exportStatus = nil; launchExport = false; choosingExport = true
        } catch { problem = error.localizedDescription }
    }
    @MainActor private func refresh() async {
        let token = UUID()
        generation = token
        loading = true
        guard let store = local.store else { loading = false; problem = "Activity storage is unavailable."; return }
        do {
            let page = try await store.hostActivity(limit: pageSize + 1, query: query, agentFilter: agent, errorsOnly: errorsOnly, kinds: selectedKinds, systemOnly: systemOnly,
                includeRequests: !systemOnly && (types.isEmpty || types.contains("Requests")),
                includeSettings: types.isEmpty || types.contains("Settings"))
            let allAgents = try await store.hostActivityAgentIdentities()
            let configured = try await store.transferAgents().map { HostActivityAgent(id: .identified($0.id), name: $0.name) }
            let summary = try await store.hostActivityTotals(since: Date().addingTimeInterval(-24 * 60 * 60))
            guard generation == token, !Task.isCancelled else { return }
            records = Array(page.prefix(pageSize)); hasMore = page.count > pageSize
            var byID = Dictionary(allAgents.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
            for value in configured { byID[value.id] = value }
            agents = byID.values.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }; totals = summary; problem = nil; loading = false
        } catch {
            guard generation == token, !Task.isCancelled else { return }
            totals = nil; loading = false; problem = error.localizedDescription
        }
    }
    @MainActor private func loadOlder() async {
        guard !loading, hasMore, let cursor = records.last?.id, let store = local.store else { return }
        let token = generation
        loading = true
        do {
            let page = try await store.hostActivity(limit: pageSize + 1, before: cursor, query: query, agentFilter: agent, errorsOnly: errorsOnly, kinds: selectedKinds, systemOnly: systemOnly,
                includeRequests: !systemOnly && (types.isEmpty || types.contains("Requests")),
                includeSettings: types.isEmpty || types.contains("Settings"))
            guard generation == token, !Task.isCancelled else { return }
            records.append(contentsOf: page.prefix(pageSize)); hasMore = page.count > pageSize
            loading = false; problem = nil
        } catch {
            guard generation == token, !Task.isCancelled else { return }
            loading = false; problem = error.localizedDescription
        }
    }

}

struct LocalHostSection: View {
    @EnvironmentObject private var local: LocalAppletModel
    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            if let host = local.host { LocalHostControls(host: host, configuration: local.configuration, packages: local.packages) }
            else { Text(local.problem ?? "Control agent storage is unavailable.").foregroundStyle(B.error) }
            OwnerConfigurationView(model: local.configuration)
        }
    }
}
struct OwnerDirectConnectionControls: View {
    @ObservedObject var host: LocalHostModel
    @State private var address: TailnetAddress?
    @State private var candidates: [TailnetAddress] = []
    @State private var problem: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Direct Tailscale hosting").font(.headline).accessibilityAddTraits(.isHeader)
            if let bound = host.ownerDirectAddress, let port = host.ownerDirectPort {
                Label("Listening on " + bound.value + " · port " + String(port), systemImage: "network")
                Button("Stop direct listener") { Task { await host.stopOwnerDirect() } }
            } else {
                Text("Use a Tailscale address assigned to this device. The listener accepts only Tailscale peers with an approved direct key.")
                    .font(.callout).foregroundStyle(B.onSurfaceVariant)
                if candidates.isEmpty {
                    Text("No Tailscale address found. Connect Tailscale, then check again.")
                        .font(.callout).foregroundStyle(B.onSurfaceVariant)
                } else {
                    Picker("Tailscale address", selection: $address) {
                        ForEach(candidates, id: \.self) { candidate in Text(candidate.value).tag(Optional(candidate)) }
                    }
                    Button(host.ownerDirectStarting ? "Starting direct listener…" : "Start direct listener") {
                        guard let address else { return }
                        Task {
                            problem = nil
                            do { try await host.startOwnerDirect(address) }
                            catch is CancellationError {}
                            catch { problem = error.localizedDescription }
                        }
                    }.disabled(address == nil || host.ownerDirectStarting)
                }
                Button(action: refresh) {
                    Text("Check for Tailscale addresses")
                        .frame(minHeight: 44, alignment: .leading)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.bordered)
                .accessibilityIdentifier("owner-direct.refresh")
                .disabled(host.ownerDirectStarting)
            }
            if let message = problem ?? host.ownerDirectProblem { Text(message).foregroundStyle(B.error) }
        }
        .onAppear(perform: refresh)
    }

    private func refresh() {
        candidates = host.availableTailnetAddresses
        if let address, candidates.contains(address) { return }
        address = host.ownerDirectPreferredAddress.flatMap { candidates.contains($0) ? $0 : nil } ?? candidates.first
    }
}

private struct LocalHostControls: View {
    #if os(iOS)
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    #endif
    @ObservedObject var host: LocalHostModel
    @ObservedObject var configuration: OwnerConfigurationModel
    var packages: [[String: Any]]
    @State private var confirmStart = false
    private enum AgentSetupSheet: Identifiable {
        case add, directReplacement, relayReplacement, capabilitiesOff
        case detail(HostAgent)

        var id: String {
            switch self {
            case .add: "add"
            case .capabilitiesOff: "capabilities-off"
            case .directReplacement: "direct-replacement"
            case .relayReplacement: "relay-replacement"
            case .detail(let agent): "detail-" + agent.id
            }
        }
    }
    @State private var agentSetupSheet: AgentSetupSheet?
    @State private var removing: HostAgent?
    @State private var unlocking: HostAgent?
    @State private var rotating: HostAgent?
    private var showingAgentSetup: Bool { agentSetupSheet != nil }
    private var addAgentButton: some View {
        Button("Add control agent", systemImage: "plus") { host.problem = nil; agentSetupSheet = .add }
            .buttonStyle(.borderedProminent)
            .disabled(host.busy || host.creatingAgent)
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            ViewThatFits(in: .horizontal) {
                HStack {
                    Text("Control agents").font(.title3.bold()).accessibilityAddTraits(.isHeader).fixedSize()
                    Spacer()
                    addAgentButton.fixedSize()
                }
                VStack(alignment: .leading, spacing: 12) {
                    Text("Control agents").font(.title3.bold()).accessibilityAddTraits(.isHeader)
                    addAgentButton
                }
            }
            ForEach(host.agents) { agent in
                VStack(alignment: .leading, spacing: 8) {
                    if agent.viewer != true && agent.tailnetPeer == nil {
                        Button {
                            host.problem = nil
                            agentSetupSheet = .detail(agent)
                        } label: {
                            agentHeading(agent, opensDetail: true)
                                .frame(minHeight: 44)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Details for " + agent.name)
                        .accessibilityIdentifier("host.agent.detail." + agent.id)
                    } else {
                        agentHeading(agent, opensDetail: false)
                    }
                    Text(agent.operations.map(\.ownerLabel).sorted().joined(separator: " · ")).font(.caption).foregroundStyle(B.onSurfaceVariant)
                    if !agent.applets.isEmpty { Text("Applets: " + agent.applets.sorted().joined(separator: ", ")).font(.caption) }
                    if let peers = agent.ownerDirectPeers {
                        Text(peers.isEmpty ? "Waiting for the first Tailscale connection." :
                             "Allowed Tailscale devices: " + peers.map(\.value).joined(separator: ", ")).font(.caption)
                        if !peers.isEmpty { Button("Unlock Tailscale device") { unlocking = agent } }
                        ForEach(agent.ownerDirectPeerAlerts ?? []) { alert in
                            let peer = alert.address
                            VStack(alignment: .leading, spacing: 6) {
                                Text((alert.refused ? "Blocked new Tailscale device " : "Allowed new Tailscale device ") + peer.value)
                                    .font(.callout)
                                Text(alert.at.formatted()).font(.caption).foregroundStyle(B.onSurfaceVariant)
                                HStack {
                                    if !peers.contains(peer) {
                                        Button("Allow device") { Task { await host.changeOwnerDirectPeers(agent, allow: peer) } }
                                            .accessibilityLabel("Allow Tailscale device " + peer.value)
                                    }
                                    Button("Dismiss") { Task { await host.changeOwnerDirectPeers(agent, dismiss: peer) } }
                                        .accessibilityLabel("Dismiss Tailscale device " + peer.value)
                                }
                            }
                        }
                    }
                    if agent.viewer != true && agent.tailnetPeer == nil {
                        Toggle("Superpowers", isOn: Binding(get: { agent.operations.contains(.administrator) }, set: { enabled in
                            Task { await host.setAdministrator(agent, enabled: enabled) }
                        })).accessibilityLabel("Superpowers for " + agent.name)
                            .accessibilityHint("Control all Bridgit settings and other agents on this device.")
                        Toggle("Fixer Mode", isOn: Binding(get: { agent.allows(.feedbackFixer) }, set: { enabled in
                            Task { await host.setFeedbackFixer(agent, enabled: enabled) }
                        })).accessibilityLabel("Fixer Mode for " + agent.name)
                            .accessibilityHint("Read and triage all reports, including attached screenshots and Activity.")
                        Button {
                            rotating = agent
                        } label: {
                            Label("Rotate key", systemImage: "key")
                                .frame(minHeight: 44)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.bordered)
                        .disabled(host.creatingAgent)
                        .accessibilityLabel("Rotate key for " + agent.name)
                        .accessibilityIdentifier("host.agent.rotate." + agent.id)
                    }
                    HStack { Button(agent.paused ? "Resume" : "Pause") { Task { await host.pause(agent) } }.accessibilityLabel((agent.paused ? "Resume " : "Pause ") + agent.name); Button("Remove", role: .destructive) { removing = agent }.accessibilityLabel("Remove " + agent.name) }
                }.padding(14).background(.quaternary.opacity(0.3), in: RoundedRectangle(cornerRadius: 12))
            }
            if host.agents.isEmpty { Text("No agent has access to this device.").foregroundStyle(B.onSurfaceVariant) }
            Text(LocalHostModel.allowsBackgroundAwake ? "Runs while this Mac is awake." : "Keep Bridgit open to host.").font(.callout).foregroundStyle(B.onSurfaceVariant)
            Label(host.state.rawValue.capitalized, systemImage: host.state == .connected ? "network" : "pause.circle")
            HStack {
                if host.state == .connected || host.state == .connecting { Button("Stop host", role: .destructive) { Task { await host.stop() } } }
                else { Button("Start host") { confirmStart = true }.buttonStyle(.bordered).disabled(host.busy || host.creatingAgent) }
            }
            OwnerDirectConnectionControls(host: host)
            #if os(iOS)
            Toggle("Allow relay wake-ups", isOn: Binding(get: { host.wakeEnabled }, set: { enabled in Task { await host.setWakeEnabled(enabled) } }))
                .disabled(!host.wakeEnabled && host.state != .connected)
            if host.wakeEnabled { Text(host.wakeStatus.replacingOccurrences(of: "_", with: " ").capitalized).font(.caption).foregroundStyle(B.onSurfaceVariant) }
            #endif
            Toggle("Allow Internet callback destinations", isOn: Binding(get: { host.callbackInternetAllowed }, set: { enabled in
                Task { do { try await host.setCallbackInternetAllowed(enabled) } catch { host.problem = error.localizedDescription } }
            }))
            Toggle("Allow host camera requests", isOn: Binding(get: { host.cameraEnabled }, set: { enabled in Task { await host.setCameraEnabled(enabled) } }))
            Text("Each agent also needs Camera snapshots access.").font(.caption).foregroundStyle(B.onSurfaceVariant)
            Toggle("Allow host microphone clips", isOn: Binding(get: { host.microphoneEnabled }, set: { enabled in
                Task {
                    host.problem = nil
                    do { try await host.setMicrophoneEnabled(enabled) }
                    catch is CancellationError { }
                    catch { host.problem = error.localizedDescription }
                }
            }))
            Text("Only agents with microphone recording access can request clips. Audio stays on this device until it is returned to the requesting agent.").font(.caption).foregroundStyle(B.onSurfaceVariant)
            if host.microphoneEnabled && !host.microphoneTranscriptionAllowed {
                Button("Allow on-device clip transcription") { Task { await host.requestMicrophoneTranscription() } }
            }
            Button(role: .destructive) { agentSetupSheet = .capabilitiesOff } label: {
                Text("Turn off device capabilities")
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.bordered)
            .disabled(configuration.turningOffCapabilities)
            if let problem = host.problem { Text(problem).foregroundStyle(B.error).textSelection(.enabled) }
        }
        .confirmationDialog("Start this device as a Bridgit host?", isPresented: $confirmStart, titleVisibility: .visible) {
            Button("Start host") { Task { await host.start() } }; Button("Cancel", role: .cancel) { }
        } message: { Text("This registers this device's public relay identity and connects it to Bridgit. Only the host agents you explicitly add can send requests. Starting does not allow cloud models or grant camera or microphone access.") }
        .accessibilityHidden(showingAgentSetup)
        .allowsHitTesting(!showingAgentSetup)
        .coveringBuildStampSurface(showingAgentSetup)
        .bridgitDialog(item: $agentSetupSheet, onDismiss: { host.oneTimeSetup = nil }) { sheet in
            switch sheet {
            case .add:
                ControlAgentSetup(host: host, packages: packages)
            case .detail(let agent):
                #if os(iOS)
                if #available(iOS 18, *), horizontalSizeClass == .regular, dynamicTypeSize.isAccessibilitySize {
                    OwnerControlAgentDetail(host: host, agent: agent, packages: packages)
                        .presentationSizing(.page)
                } else {
                    OwnerControlAgentDetail(host: host, agent: agent, packages: packages)
                }
                #else
                OwnerControlAgentDetail(host: host, agent: agent, packages: packages)
                #endif
            case .capabilitiesOff:
                OwnerCapabilityReductionSheet(configuration: configuration)
            case .directReplacement, .relayReplacement:
                let connection: ControlAgentConnection = if case .directReplacement = sheet { .direct } else { .relay }
                HostSetupSecret(host: host, connection: connection)
                    .safeAreaInset(edge: .top, spacing: 0) {
                        HStack {
                            Spacer()
                            BuildStamp(foreground: B.onSurfaceVariant)
                        }
                        .padding(.horizontal, 16)
                        .padding(.top, 10)
                    }
                    .buildStampSurface()
                    .onChange(of: host.oneTimeSetup == nil) { _, cleared in
                        if cleared { agentSetupSheet = nil }
                    }
            }
        }
        .confirmationDialog("Rotate this control agent’s key?", isPresented: Binding(get: { rotating != nil }, set: { if !$0 { rotating = nil } }), titleVisibility: .visible) {
            if let agent = rotating {
                Button("Rotate key", role: .destructive) {
                    rotating = nil
                    Task {
                        if await host.rotate(agent) { agentSetupSheet = agent.ownerDirectPeers == nil ? .relayReplacement : .directReplacement }
                    }
                }
            }
            Button("Cancel", role: .cancel) { rotating = nil }
        } message: {
            Text(rotating?.ownerDirectPeers != nil
                 ? "The old key stops working. Access stays the same. The next Tailscale device to use the new key becomes its allowed device."
                 : "The old key stops working. The agent keeps its access and needs the new setup.")
        }
        .task { await host.observeSavedAgents() }
        .confirmationDialog("Unlock this Tailscale agent?", isPresented: Binding(get: { unlocking != nil }, set: { if !$0 { unlocking = nil } }), titleVisibility: .visible) {
            if let agent = unlocking {
                Button("Unlock device", role: .destructive) { Task { await host.changeOwnerDirectPeers(agent, unlock: true); unlocking = nil } }
            }
            Button("Cancel", role: .cancel) { unlocking = nil }
        } message: { Text("The next Tailscale device that uses this key becomes its allowed device.") }
        .confirmationDialog("Remove this host agent?", isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }), titleVisibility: .visible) {
            if let agent = removing { Button("Remove agent", role: .destructive) { Task { await host.remove(agent); removing = nil } } }; Button("Cancel", role: .cancel) { removing = nil }
        } message: { Text("Its token will stop working and active requests will be cancelled. Local applet data is kept.") }
    }

    private func agentHeading(_ agent: HostAgent, opensDetail: Bool) -> some View {
        HStack {
            Text(agent.name).font(.headline)
            Spacer()
            Text(agent.paused ? "Paused" : agent.ownerDirectPeers != nil ? "Tailscale" : agent.mode.capitalized).font(.caption)
            if opensDetail { Image(systemName: "chevron.forward").font(.caption).accessibilityHidden(true) }
        }
    }

}
private struct OwnerCapabilityReductionSheet: View {
    @DialogDismiss private var dismiss
    @Environment(\.dynamicTypeSize) private var textSize
    @ObservedObject var configuration: OwnerConfigurationModel
    @State private var problem: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                Spacer()
                BuildStamp(foreground: B.onSurfaceVariant)
            }
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text("Turn off device capabilities?")
                        .font(.title2.bold())
                        .accessibilityAddTraits(.isHeader)
                    Text("Host camera and microphone clips, files, shares, downloads, cloud appraisals, agent network discovery and Internet callback destinations will be turned off.")
                        .accessibilityIdentifier("host.capabilities.off.scope")
                    if configuration.crashReportingEnabled != nil { Text("Crash reports will also be turned off.") }
                    if let problem { Text(problem).foregroundStyle(B.error) }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 2)
            }
            .accessibilityIdentifier("host.capabilities.off.review")
            if textSize.isAccessibilitySize {
                VStack(alignment: .leading, spacing: 8) { turnOff; cancel }
            } else {
                HStack { cancel; Spacer(); turnOff }
            }
        }
        .padding(24)
        .buildStampSurface()
        .interactiveDismissDisabled(configuration.turningOffCapabilities)
        #if os(macOS)
        .frame(minWidth: 440, idealWidth: 520, minHeight: 240, idealHeight: 280)
        #endif
    }

    private var cancel: some View {
        Button(role: .cancel) { dismiss() } label: {
            Text("Cancel").frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
        }
        .disabled(configuration.turningOffCapabilities)
    }

    private var turnOff: some View {
        Button(role: .destructive) {
            Task {
                do { try await configuration.turnOffDeviceCapabilities(); dismiss() }
                catch { problem = error.localizedDescription }
            }
        } label: {
            Text("Turn off").frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
        }
        .buttonStyle(.borderedProminent)
        .disabled(configuration.turningOffCapabilities)
    }
}

private struct OwnerControlAgentDetail: View {
    @DialogDismiss private var dismiss
    @Environment(\.dynamicTypeSize) private var textSize
    @ScaledMetric(relativeTo: .body) private var notesMinimumHeight: CGFloat = 100
    @EnvironmentObject private var local: LocalAppletModel
    @ObservedObject var host: LocalHostModel
    let agent: HostAgent
    let packages: [[String: Any]]
    @State private var name: String
    @State private var notes: String
    @State private var operations: Set<HostOperation>
    @State private var applets: Set<String>
    @State private var maintained: [[String: Any]] = []
    @State private var loadProblem: String?
    @State private var problem: String?
    @State private var saving = false
    @State private var confirmMaximum = false
    @State private var editingCallback = false
    @State private var callbackCopyDiagnostic: String?
    @State private var callbackCopied = false

    init(host: LocalHostModel, agent: HostAgent, packages: [[String: Any]]) {
        self.host = host
        self.agent = agent
        self.packages = packages
        _name = State(initialValue: agent.name)
        _notes = State(initialValue: agent.notes ?? "")
        _operations = State(initialValue: agent.operations)
        _applets = State(initialValue: agent.applets)
    }

    private var grantChoices: [HostOperation] {
        HostOperation.allCases.filter {
            $0 != .feedback && (agent.ownerDirectPeers == nil || OwnerDirectHostService.supportedGrants.contains($0))
        }
    }
    private var hasMaximumGrants: Bool { Set(grantChoices).isSubset(of: operations) }
    private var title: String {
        name == agent.name ? agent.name : name.trimmingCharacters(in: .whitespacesAndNewlines)
    }
    private var changed: Bool {
        title != agent.name || notes != (agent.notes ?? "") || operations != agent.operations || applets != agent.applets
    }
    private var valid: Bool {
        let validName = title == agent.name ||
            title.range(of: "^[A-Za-z0-9][A-Za-z0-9 _-]{0,23}$", options: .regularExpression) != nil
        return validName && notes.utf16.count <= 200 && !operations.isEmpty
    }
    private var appletChoices: [(id: String, name: String)] {
        let saved = agent.applets.sorted().map { ["id": $0, "name": $0] }
        return hostAgentAppletChoices(packages + maintained + saved)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top) {
                if !textSize.isAccessibilitySize { detailHeading }
                Spacer(minLength: 8)
                BuildStamp(foreground: B.onSurfaceVariant)
            }
            #if os(macOS)
            ScrollView { editForm }
                .frame(maxHeight: 440)
            #else
            editForm
                .scrollDismissesKeyboard(.immediately)
                .accessibilityIdentifier("host.agent.detail.form")
            #endif

            HStack {
                Button { dismiss() } label: {
                    Text("Cancel").frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
                }.disabled(saving)
                Spacer()
                Button { Task { await save() } } label: {
                    Text(saving ? "Saving…" : "Save").frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
                }
                    .buttonStyle(.borderedProminent)
                    .disabled(saving || !valid || !changed)
                    .accessibilityIdentifier("host.agent.detail.save")
            }
        }
        .padding(24)
        .interactiveDismissDisabled(saving)
        .coveringBuildStampSurface(editingCallback)
        .bridgitDialog(isPresented: $editingCallback) {
            OwnerCallbackEditor(host: host, agent: agent, onCopied: { callbackCopied = true }) { callbackCopyDiagnostic = $0 }
        }
        .buildStampSurface()
        .alert("Maximum grants?", isPresented: $confirmMaximum) {
            Button("Grant", role: .destructive) { operations = Set(grantChoices) }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Superpowers and Fixer Mode.")
        }
        .task {
            guard let viewer = local.configuration.viewer, viewer.configuredSignedIn else { return }
            do {
                let controller = PortableLibraryController(cloud: viewer.configuredCloud)
                maintained = try await MaintainedAppletHost.viewerNames(controller)
            } catch { loadProblem = "Installed library applets could not be loaded. " + error.localizedDescription }
        }
        #if os(macOS)
        .frame(minWidth: 480, idealWidth: 620, minHeight: 420, idealHeight: 600, maxHeight: 600)
        #endif
    }

    private var editForm: some View {
        Form {
            if textSize.isAccessibilitySize {
                Section { detailHeading }
                    .listRowBackground(Color.clear)
            }
            Section("Agent") {
                TextField("Agent name", text: $name).accessibilityIdentifier("host.agent.detail.name")
                if title != agent.name {
                    Text("\(title.count)/24").font(.caption).accessibilityLabel("Name length \(title.count) of 24")
                }
                LabeledContent("Connection", value: agent.ownerDirectPeers != nil ? "Direct Tailscale" : agent.mode == "sealed" ? "Encrypted relay" : "Relay connector")
                Text("Owner notes").font(.headline)
                TextEditor(text: $notes)
                    .frame(minHeight: notesMinimumHeight)
                    .accessibilityLabel("Owner notes")
                    .accessibilityIdentifier("host.agent.detail.notes")
                Text("\(notes.utf16.count)/200")
                    .font(.caption)
                    .foregroundStyle(notes.utf16.count > 200 ? B.error : B.onSurfaceVariant)
                    .accessibilityLabel("Notes length \(notes.utf16.count) of 200")
            }
            OwnerCallbackSection(host: host, agent: agent, editing: $editingCallback, copied: $callbackCopied, copyDiagnostic: callbackCopyDiagnostic)
            Section("Powers") {
                Text("All supported grants, including Superpowers and Fixer Mode. Device settings and system permissions still apply.")
                    .font(.callout)
                    .foregroundStyle(B.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                Text(hasMaximumGrants ? "Maximum agent grants" : "Limited agent grants")
                    .accessibilityIdentifier("host.agent.detail.powers")
                Button("Grant maximum powers") { confirmMaximum = true }
                    .disabled(hasMaximumGrants)
                    .accessibilityLabel("Review maximum powers")
                    .accessibilityIdentifier("host.agent.detail.maximum")
                if operations.contains(.administrator) {
                    Button("Use limited grants") { operations.remove(.administrator) }
                }
            }
            Section("Permissions") {
                ForEach(grantChoices, id: \.self) { operation in
                    grantToggle(operation.ownerLabel, isOn: Binding(
                        get: { operations.contains(operation) },
                        set: { if $0 { operations.insert(operation) } else { operations.remove(operation) } }
                    ))
                    .accessibilityIdentifier("host.agent.detail.grant." + operation.rawValue)
                }
            }
            Section("Applets") {
                if appletChoices.isEmpty { Text("No installed applets are available.") }
                ForEach(appletChoices, id: \.id) { item in
                    grantToggle(item.name, isOn: Binding(
                        get: { applets.contains(item.id) },
                        set: { if $0 { applets.insert(item.id) } else { applets.remove(item.id) } }
                    ))
                    .accessibilityIdentifier("host.agent.detail.applet." + item.id)
                }
                if let loadProblem { Text(loadProblem).foregroundStyle(B.error) }
            }
            if let problem { Text(problem).foregroundStyle(B.error) }
        }
        .disabled(saving)
    }

    private var detailHeading: some View {
        Text("Control agent details")
            .font(.title2.bold())
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityAddTraits(.isHeader)
    }

    private func grantToggle(_ label: String, isOn: Binding<Bool>) -> some View {
        Toggle(isOn: isOn) {
            #if os(iOS)
            Text(label)
                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                .contentShape(Rectangle())
                .onTapGesture { isOn.wrappedValue.toggle() }
                .accessibilityHidden(true)
            #else
            Text(label)
            #endif
        }
        .accessibilityLabel(label)
    }

    private func save() async {
        saving = true
        problem = nil
        defer { saving = false }
        do {
            try await host.editControlAgent(agent, name: name, notes: notes, operations: operations, applets: applets)
            dismiss()
        } catch { problem = error.localizedDescription }
    }
}

private struct OwnerCallbackSection: View {
    @ObservedObject var host: LocalHostModel
    let agent: HostAgent
    @State private var configured = false
    @State private var hostname: String?
    @Binding var editing: Bool
    @Binding var copied: Bool
    let copyDiagnostic: String?
    @State private var removing = false
    @State private var problem: String?
    var body: some View {
        Section("Callback URL") {
            Text(configured ? "Set to " + (hostname ?? "unknown host") : "Not set")
                .accessibilityIdentifier("host.callback.status")
            Button(configured ? "Change callback" : "Set callback") { copied = false; editing = true }
                .buttonStyle(.borderless)
                .accessibilityIdentifier("host.callback.edit")
            if configured {
                Button("Remove callback", role: .destructive) { removing = true }
                    .buttonStyle(.borderless)
                    .accessibilityIdentifier("host.callback.remove")
            }
            if copied { Text("Signing key copied.").font(.caption).foregroundStyle(B.onSurfaceVariant) }
            if let problem { Text(problem).foregroundStyle(B.error) }
            #if DEBUG
            if let copyDiagnostic, CommandLine.arguments.contains("-fixtureHideOneTimeSecrets") {
                Text(copyDiagnostic).accessibilityIdentifier("host.callback.fixture-copy-diagnostic")
            }
            #endif
        }
        .task { await refresh() }
        .onChange(of: editing) { _, shown in if !shown { Task { await refresh() } } }
        .alert("Remove callback?", isPresented: $removing) {
            Button("Remove", role: .destructive) {
                Task { do { try await host.removeOwnerCallback(agent.id); await refresh() } catch { problem = error.localizedDescription } }
            }
            Button("Cancel", role: .cancel) { }
        } message: { Text("Pending callback deliveries will be cancelled.") }
    }
    private func refresh() async {
        do {
            let status = try await host.callbackOwnerStatus(agent.id)
            configured = status["set"] as? Bool == true; hostname = status["host"] as? String; problem = nil
        } catch { problem = error.localizedDescription }
    }
}

private struct OwnerCallbackEditor: View {
    @DialogDismiss private var dismiss
    @ObservedObject var host: LocalHostModel
    let agent: HostAgent
    let onCopied: () -> Void
    let onFixtureCopyVerified: (String) -> Void
    @State private var url = ""
    @State private var secret: String?
    @State private var problem: String?
    @State private var confirming = false
    @State private var busy = false
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack { Text("Callback URL").font(.title2.bold()); Spacer(); BuildStamp(foreground: B.onSurfaceVariant) }
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    if let secret {
                        Text("Signing key · shown once").font(.headline)
                        Text("Give this key only to the callback receiver. It will not be shown again.")
                            .fixedSize(horizontal: false, vertical: true)
                        Text(displayedSecret(secret)).font(.system(.body, design: .monospaced)).textSelection(.enabled).privacySensitive()
                            .accessibilityIdentifier("host.callback.secret")
                    } else {
                        Text("Bridgit sends this agent’s events to this address.")
                            .fixedSize(horizontal: false, vertical: true)
                        TextField("Callback URL", text: $url).textFieldStyle(.roundedBorder)
                            .frame(minHeight: 44).accessibilityIdentifier("host.callback.url")
                        if let problem { Text(problem).foregroundStyle(B.error) }
                    }
                }.frame(maxWidth: .infinity, alignment: .leading)
            }
            HStack {
                if let secret {
                    Button { copySecret(secret); onCopied(); self.secret = nil; dismiss() } label: {
                        Text("Copy").frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
                    }.accessibilityIdentifier("host.callback.secret.copy")
                    Spacer()
                    Button { self.secret = nil; dismiss() } label: {
                        Text("Done").frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
                    }.accessibilityIdentifier("host.callback.secret.done")
                } else {
                    Button { dismiss() } label: {
                        Text("Cancel").frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
                    }.accessibilityIdentifier("host.callback.cancel")
                    Spacer()
                    Button { Task { await validate() } } label: {
                        Text("Next").frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
                    }.disabled(busy || url.isEmpty).accessibilityIdentifier("host.callback.next")
                }
            }
        }
        .padding(24).buildStampSurface()
        .alert("Save callback for " + agent.name + "?", isPresented: $confirming) {
            Button("Save") { Task { await save() } }
            Button("Cancel", role: .cancel) { }
        } message: { Text("Bridgit will send " + agent.name + "’s events to:\n\n" + url + "\n\nA new signing key replaces the old key.").privacySensitive() }
        .task { do { url = try await host.callbackOwnerURL(agent.id) ?? "" } catch { problem = error.localizedDescription } }
        #if os(macOS)
        .frame(minWidth: 480, minHeight: 300)
        #endif
    }
    private func copySecret(_ value: String) {
        #if os(iOS)
        UIPasteboard.general.setItems([["public.utf8-plain-text": value]], options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(60)])
        #if DEBUG
        if CommandLine.arguments.contains("-fixtureHideOneTimeSecrets") {
            let board = UIPasteboard.general
            let readback = board.string
            let types = board.items.flatMap { $0.keys }.sorted().joined(separator: ",")
            onFixtureCopyVerified(readback == value ? "Fixture copy verified" : "Fixture copy mismatch; length \(readback?.utf8.count ?? -1); types \(types)")
        }
        #endif
        #else
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        pasteboard.setString(value, forType: .string)
        pasteboard.setData(Data(), forType: NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType"))
        let count = pasteboard.changeCount
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(60))
            if pasteboard.changeCount == count { pasteboard.clearContents() }
        }
        #endif
    }
    private func displayedSecret(_ secret: String) -> String {
        #if DEBUG
        if CommandLine.arguments.contains("-fixtureHideOneTimeSecrets") { return "Signing key hidden for fixture" }
        #endif
        return secret
    }
    private func validate() async {
        busy = true; defer { busy = false }
        do { try await host.validateCallbackURL(url); problem = nil; confirming = true }
        catch { problem = error.localizedDescription }
    }
    private func save() async {
        busy = true; defer { busy = false }
        do { secret = try await host.setOwnerCallback(agent.id, url: url); url = ""; problem = nil }
        catch { problem = error.localizedDescription }
    }
}

private func hostAgentAppletChoices(_ packages: [[String: Any]]) -> [(id: String, name: String)] {
    var seen = Set<String>()
    return packages.compactMap { item in
        guard let id = item["id"] as? String, seen.insert(id).inserted else { return nil }
        return (id, item["name"] as? String ?? id)
    }
}

struct HostSetupSecret: View {
    @ObservedObject var host: LocalHostModel
    var connection: ControlAgentConnection? = nil
    @State private var copied: String?
    @State private var firstDirectCall = false
    @State private var confirmStart = false
    private var setup: [String: Any] {
        guard let text = host.oneTimeSetup else { return [:] }
        return (try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any]) ?? [:]
    }
    /// A0239: the guided setup prompt (as on Android), not the bare record; the record ends the prompt.
    private var setupText: String { host.presentedControlSetupPrompt(direct: connection == .direct) ?? "" }
    private var setupRecord: String { host.presentedControlSetup(direct: connection == .direct) ?? "" }
    private var displayedSetupText: String {
        #if DEBUG
        if CommandLine.arguments.contains("-fixtureHideOneTimeSecrets") { return "Setup secrets hidden for fixture" }
        #endif
        return setupText
    }
    private var fixtureHasCallbackKey: Bool {
        #if DEBUG
        guard CommandLine.arguments.contains("-fixtureHideOneTimeSecrets"),
              let object = try? JSONSerialization.jsonObject(with: Data(setupRecord.utf8)) as? [String: Any] else { return false }
        return object["callback_secret"] is String
        #else
        return false
        #endif
    }
    private var mcpURL: URL? {
        guard let base = setup["base"] as? String, let url = URL(string: base), url.scheme == "https" else { return nil }
        return url.appendingPathComponent("mcp")
    }
    var body: some View {
            VStack(alignment: .leading, spacing: 16) {
                ScrollView {
                    VStack(alignment: .leading, spacing: 14) {
                        Text("Agent setup · shown once")
                            .font(.title2.bold())
                            .accessibilityAddTraits(.isHeader)
                            .fixedSize(horizontal: false, vertical: true)
                        Text("Share this secret key only with this agent. Bridgit stores its hash. Rotate if exposed.")
                            .font(.callout)
                            .fixedSize(horizontal: false, vertical: true)
                            .accessibilityIdentifier("host.setup.warning")
                        if connection == .direct {
                            if host.ownerDirectAddress == nil {
                                OwnerDirectConnectionControls(host: host)
                            } else {
                                Label(firstDirectCall ? "First agent request received" : "Waiting for the first agent request",
                                      systemImage: firstDirectCall ? "checkmark.circle" : "network")
                            }
                        } else if host.state != .connected && host.state != .connecting {
                            Text("This device is not accepting requests yet. Start host when you are ready to make this connection available.")
                                .font(.callout)
                                .foregroundStyle(B.onSurfaceVariant)
                            Button("Start host") { confirmStart = true }.buttonStyle(.bordered).disabled(host.busy)
                        } else {
                            Label(host.state == .connected ? "Host connected" : "Connecting host…", systemImage: "network")
                        }
                        if let problem = host.problem { Text(problem).foregroundStyle(B.error) }
                        if let connection, connection.hostedClient, let url = mcpURL {
                            Text("Add Bridgit to " + connection.clientName).font(.headline)
                            Text("Add a custom connector or MCP server using this address and OAuth. On the Bridgit authorization page, paste the relay key and choose Allow. The connection receives only the permissions you selected.")
                                .font(.callout)
                            Text(url.absoluteString).font(.system(.callout, design: .monospaced)).textSelection(.enabled)
                            Button("Copy connector URL") { copy(url.absoluteString, secret: false); copied = "Connector URL copied" }
                            if let token = setup["token"] as? String {
                                Button("Copy relay key") { copy(token, secret: true); copied = "Relay key copied" }
                            }
                            if connection == .chatGPT { Link("Open ChatGPT plugins", destination: URL(string: "https://chatgpt.com/plugins")!) }
                            DisclosureGroup("Full setup for other clients") {
                                Text(displayedSetupText).font(.system(.caption, design: .monospaced)).textSelection(.enabled).privacySensitive()
                            }
                        } else {
                            Text(displayedSetupText).font(.system(.caption, design: .monospaced)).textSelection(.enabled).privacySensitive()
                        }
                        if fixtureHasCallbackKey { Text("Callback key included").accessibilityIdentifier("host.setup.callback-key-present") }
                        if let copied { Text(copied).font(.callout).foregroundStyle(B.onSurfaceVariant) }
                    }.frame(maxWidth: .infinity, alignment: .leading)
                }
                .accessibilityIdentifier("host.setup.content")
                HStack {
                    ShareLink(item: displayedSetupText) {
                        Label("Share setup", systemImage: "square.and.arrow.up")
                            .frame(minHeight: 44)
                            .contentShape(Rectangle())
                    }
                    Spacer()
                    Button { host.oneTimeSetup = nil } label: {
                        Text("Done").frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
                    }
                    .accessibilityIdentifier("host.setup.done")
                }
            }.padding(24)
            .task(id: setup["generation"] as? String) {
                guard connection == .direct, let id = setup["agent_id"] as? String,
                      let generation = setup["generation"] as? String else { return }
                firstDirectCall = false
                await host.observeOwnerDirectFirstCall(agentID: id, generation: generation) { firstDirectCall = true }
            }
            #if os(macOS)
            .frame(minWidth: 440, idealWidth: 650, minHeight: 360, idealHeight: 560)
            #endif
            .confirmationDialog("Start this device as a Bridgit host?", isPresented: $confirmStart, titleVisibility: .visible) {
                Button("Start host") { Task { await host.start() } }
                Button("Cancel", role: .cancel) { }
            } message: {
                Text("This registers this device's public relay identity and connects it to Bridgit. Only the agents and permissions you explicitly approved can accept requests. Camera, microphone and paid cloud services keep their separate controls.")
            }
    }
    private func copy(_ value: String, secret: Bool) {
        #if os(iOS)
        if secret {
            UIPasteboard.general.setItems([["public.utf8-plain-text": value]], options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(60)])
        } else { UIPasteboard.general.string = value }
        #else
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(value, forType: .string)
        if secret { NSPasteboard.general.setData(Data(), forType: NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType")) }
        #endif
    }
}

struct HostAgentPermissionDraft {
    /// Every grant starts on; the owner unticks what the agent should not have.
    var operations: Set<HostOperation> = Set(HostOperation.allCases).subtracting([.feedback])
    var applets = Set<String>()
    /// A0227: relay agents start as connector agents, as on Android. Encrypted requests need the
    /// agent's own public key, which the owner rarely has, and left Create agent greyed out.
    var sealed = false
    var peer = ""
}

/// A long form must scroll on macOS so the buttons under it stay on screen; iOS forms already scroll.
struct MacFormScroll<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        #if os(macOS)
        ScrollView { content.formStyle(.columns) }
        #else
        content
        #endif
    }
}

struct HostAgentForm: View {
    @DialogDismiss private var dismiss
    @EnvironmentObject private var local: LocalAppletModel
    @ObservedObject var host: LocalHostModel
    var packages: [[String: Any]]
    @State private var name: String
    var connection: ControlAgentConnection?
    var onBack: (() -> Void)?
    var onCreated: (() -> Void)?
    var onDraftChange: ((HostAgentPermissionDraft) -> Void)?
    init(host: LocalHostModel, packages: [[String: Any]], name: String = "", connection: ControlAgentConnection? = nil, draft: HostAgentPermissionDraft = .init(), onBack: (() -> Void)? = nil, onDraftChange: ((HostAgentPermissionDraft) -> Void)? = nil, onCreated: (() -> Void)? = nil) {
        self.host = host
        self.packages = packages
        _name = State(initialValue: name)
        self.connection = connection
        _operations = State(initialValue: draft.operations)
        _applets = State(initialValue: draft.applets)
        _peer = State(initialValue: draft.peer)
        _sealed = State(initialValue: connection?.hostedClient == true ? false : draft.sealed)
        self.onBack = onBack
        self.onDraftChange = onDraftChange
        self.onCreated = onCreated
    }
    private var isDirect: Bool { connection == .direct }
    private var grantChoices: [HostOperation] {
        HostOperation.allCases.filter { $0 != .feedback && (!isDirect || OwnerDirectHostService.supportedGrants.contains($0)) }
    }
    private var selectedGrants: Set<HostOperation> { operations.intersection(grantChoices) }
    @State private var operations: Set<HostOperation> = [.deviceRead]
    @State private var applets = Set<String>()
    @State private var sealed = false
    @State private var peer = ""
    @State private var maintainedChoices: [[String: Any]] = []
    @State private var choicesProblem: String?
    private var appletChoices: [(id: String, name: String)] {
        hostAgentAppletChoices(packages + maintainedChoices)
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Control agent permissions").font(.title2.bold()).accessibilityAddTraits(.isHeader)
            Text(isDirect ? "Only share the direct key with the intended agent on your Tailscale network." :
                 "The agent gets only the permissions you select. Create agent also starts the host so the agent can reach this device through the Bridgit relay.")
                .font(.callout).foregroundStyle(B.onSurfaceVariant)
            MacFormScroll {
            Form {
                if onCreated == nil { TextField("Agent name", text: $name) }
                else { LabeledContent("Agent name", value: name) }
                if let connection { LabeledContent("Connection", value: connection.title) }
                if isDirect {
                    Text("The direct key works on Tailscale. It is kept separate from relay and satellite keys.")
                        .font(.callout).foregroundStyle(B.onSurfaceVariant)
                } else {
                    if connection?.hostedClient != true { Toggle("Encrypted requests", isOn: $sealed) }
                    if sealed { TextField("Agent P-256 public key (base64)", text: $peer).font(.system(.caption, design: .monospaced)) }
                    else { Text("Connector access uses a bearer token and the relay can read requests. Grant only the access you need.").font(.callout).foregroundStyle(B.onSurfaceVariant) }
                }
                // Columns, not one long list, so the whole set fits on screen without scrolling.
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 250), alignment: .leading)], alignment: .leading, spacing: 8) {
                    ForEach(grantChoices, id: \.self) { operation in
                        Toggle(operation.ownerLabel, isOn: Binding(get: { operations.contains(operation) }, set: { if $0 { operations.insert(operation) } else { operations.remove(operation) } }))
                    }
                }
                if !selectedGrants.isDisjoint(with: [.workflowsRun, .speechHear, .speechSpeak, .configurationRead, .configurationWrite, .appletsManage, .appletsRead, .appletsWrite, .appletsMediaRead, .appletsMediaWrite]) {
                    ForEach(appletChoices, id: \.id) { item in
                        Toggle(item.name, isOn: Binding(get: { applets.contains(item.id) }, set: { if $0 { applets.insert(item.id) } else { applets.remove(item.id) } }))
                            .accessibilityIdentifier("host-agent-applet-" + item.id)
                    }
                    if operations.contains(.appletsManage), !operations.contains(.administrator) {
                        Text("Select the applets this agent can pause or resume.").font(.callout).foregroundStyle(B.onSurfaceVariant)
                    }
                    if let choicesProblem { Text(choicesProblem).foregroundStyle(B.error) }
                    Text("Flow access can send an applet's photos to cloud models when you have separately enabled appraisals. Existing view links keep their original permissions.").font(.callout).foregroundStyle(B.onSurfaceVariant)
                }
            }
            }
            if let problem = host.problem { Text(problem).foregroundStyle(B.error) }
            HStack {
                if let onBack {
                    Button("Back") {
                        onDraftChange?(.init(operations: operations, applets: applets, sealed: sealed, peer: peer))
                        onBack()
                    }.disabled(host.creatingAgent)
                }
                Button("Cancel") { dismiss() }.disabled(host.creatingAgent)
                Spacer(); Button(host.creatingAgent ? "Creating agent…" : "Create agent") {
                Task {
                    host.problem = nil
                    await host.create(name: name, operations: selectedGrants, applets: applets,
                                      peer: sealed && !isDirect ? peer.trimmingCharacters(in: .whitespacesAndNewlines) : nil,
                                      ownerDirect: isDirect)
                    if host.oneTimeSetup != nil {
                        // A0227: a relay agent can reach this device only while the host runs. As on
                        // Android, creating it turns the host on instead of leaving a second step.
                        if !isDirect, host.state != .connected, host.state != .connecting { Task { await host.start() } }
                        if let onCreated { onCreated() } else { dismiss() }
                    }
                }
            }.buttonStyle(.borderedProminent).disabled(host.creatingAgent || host.busy || name.isEmpty || selectedGrants.isEmpty ||
                (!isDirect && sealed && peer.isEmpty) || (!operations.contains(.administrator) && (operations.contains(.workflowsRun) || (!operations.contains(.appletsManage) && !operations.isDisjoint(with: [.appletsRead, .appletsWrite, .appletsMediaRead, .appletsMediaWrite]))) && applets.isEmpty)) }
        }.padding(24)
        .task {
            if isDirect, host.ownerDirectAddress == nil {
                do { try await host.enableConfiguredOwnerDirect() }
                catch is CancellationError {}
                catch { host.problem = error.localizedDescription }
            }
        }
        .task {
            guard let viewer = local.configuration.viewer, viewer.configuredSignedIn else { return }
            do {
                let controller = PortableLibraryController(cloud: viewer.configuredCloud)
                maintainedChoices = try await MaintainedAppletHost.viewerNames(controller)
                choicesProblem = nil
            } catch { choicesProblem = "Installed library applets could not be loaded. " + error.localizedDescription }
        }
        #if os(macOS)
        .frame(minWidth: 480, idealWidth: 900, minHeight: 420, idealHeight: 560)
        #endif
    }
}

private extension HostOperation {
    var ownerLabel: String {
        switch self {
        case .files: return "Send and manage private files"
        case .downloads: return "Read and delete files you choose"
        case .shares: return "Read and delete files shared with this agent"
        case .appletsManage: return "Install and manage applets"
        case .appletsMediaRead: return "Read applet photos"
        case .appletsMediaWrite: return "Import applet photos"
        case .configurationRead: return "Read configuration"
        case .administrator: return "Superpowers · manage Bridgit settings and agents"
        case .configurationWrite: return "Change configuration and destinations"
        case .interactionsRead: return "Read card answers"
        case .interactionsWrite: return "Send cards"
        case .deviceRead: return "Read device status"
        case .appletsRead: return "Read applet tables"
        case .appletsWrite: return "Write applet tables"
        case .workflowsRun: return "Run applet flows"
        case .cameraSnapshot: return "Take camera snapshots"
        case .lanDiscovery: return "Network discovery"
        #if os(macOS)
        case .screenObserve: return "See the screen and app interfaces"
        case .screenControl: return "Control this Mac"
        #endif
        case .connections: return "Send to approved connections"
        case .log: return "Read Activity and write notes"
        case .feedback: return "File and follow up on your reports"
        case .feedbackFixer: return "Fixer Mode · read and triage all reports"
        case .microphoneRecord: return "Record microphone clips"
        case .speechHear: return "Hear speech"
        case .speechSpeak: return "Speak aloud"
        }
    }
}
