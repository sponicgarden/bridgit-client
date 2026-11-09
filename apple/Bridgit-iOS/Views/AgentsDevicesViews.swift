import SwiftUI
import UIKit
import BridgitCore

/// Agents, as on the phone: each agent with its avatar, connection and when it last called.
struct AgentsView: View {
    var live: PhoneLive?
    var body: some View {
        Group {
            if let live { AgentsList(live: live) } else { EmptyPhone(title: "Agents") }
        }
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct AgentsList: View {
    @ObservedObject var live: PhoneLive

    /// Bridgit viewers only view the phone: one line names them instead of agent cards (APK AgentsFragment).
    var controlAgents: [AgentRow] { live.agents.filter { !$0.portal } }
    var viewers: [AgentRow] { live.agents.filter(\.portal) }

    private var relaySource: String {
        switch live.source {
        case .live(let at): return "Relay snapshot · read " + Age.text(at)
        case .stale(let at): return "Last relay snapshot · read " + Age.text(at)
        case .sample: return "Sample agents"
        default: return "Relay agent roster not reported"
        }
    }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                // No title row, as on the phone (Agents opens straight onto its cards).
                SectionLabel(text: "Control agents")
                if live.device.cloud != nil {
                    AccountAgentRosterView(roster: AccountAgentRoster(phone: live.device.cloud))
                }
                if case .snapshot = live.source {
                    if live.device.cloud == nil { Text("Current agent roster not reported.").foregroundStyle(B.onSurfaceVariant) }
                } else {
                    Text(relaySource).font(.caption).foregroundStyle(B.onSurfaceVariant)
                    if controlAgents.isEmpty {
                        InfoPanel { Text("No agents reported yet.").foregroundStyle(B.onSurfaceVariant) }
                    }
                    ForEach(controlAgents) { a in AgentCard(agent: a) }
                    if !viewers.isEmpty {
                        HStack(spacing: 10) {
                            Image(systemName: "ipad.and.iphone").foregroundStyle(B.primary)
                            Text("Viewing through Bridgit: " + (viewers.count == 1 ? PhoneLive.client : "iPhone/iPad"))
                                .font(.subheadline).foregroundStyle(B.onSurface)
                        }
                        .padding(.horizontal, 4).padding(.top, 4)
                    }
                }
                Text("Adding, pausing or removing agents is done on the phone.").font(.footnote).foregroundStyle(B.onSurfaceVariant)
                    .padding(.top, 4)
            }
            .padding(16).frame(maxWidth: 760).frame(maxWidth: .infinity)
        }
        .background(B.surface)
        .refreshable { await live.refresh(force: true) }
        .task { if live.agents.isEmpty { await live.refresh() } }
    }
}

/// One agent card, laid out like the APK's (agents_row.xml): avatar, name, connection line, then status and a dot.
private struct AgentCard: View {
    var agent: AgentRow
    var body: some View {
        Card {
            HStack(alignment: .top, spacing: 12) {
                AgentMark(name: agent.name, model: agent.model)
                    .opacity(agent.paused ? 0.5 : 1)
                VStack(alignment: .leading, spacing: 3) {
                    Text(agent.name).font(.headline).foregroundStyle(B.onSurface)
                    if let conn = agent.connectionText, !conn.isEmpty {
                        Text(conn).font(.caption).foregroundStyle(B.onSurfaceVariant)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    TimelineView(.periodic(from: .now, by: 30)) { ctx in
                        HStack(spacing: 6) {
                            Circle().fill(agent.paused ? B.error : agent.isActive ? B.success : B.outline).frame(width: 8, height: 8)
                            Text(agent.statusLine(now: ctx.date)).font(.caption.weight(.medium))
                                .foregroundStyle(agent.paused ? B.error : B.onSurfaceVariant)
                        }
                    }
                    .padding(.top, 2)
                }
                Spacer(minLength: 0)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

/// Activity (APK: More › Activity): the phone's log, newest first, with filters.
struct ActivityView: View {
    @ObservedObject var live: PhoneLive
    var showsNavigationTitle = true
    @State private var agent = ""
    @State private var onlyFailed = false
    @State private var selectedEntry: LogLine?

    var shown: [LogLine] {
        live.logs.filter { (agent.isEmpty || $0.agent == agent) && (!onlyFailed || $0.failed) }
    }

    var body: some View {
        List {
            Section {
                Picker("Agent", selection: $agent) {
                    Text("All agents").tag("")
                    ForEach(Array(Set(live.logs.map(\.agent))).filter { !$0.isEmpty }.sorted(), id: \.self) { Text($0).tag($0) }
                }
                Toggle("Only refused or failed", isOn: $onlyFailed)
            }
            Section {
                if shown.isEmpty { Text("Nothing yet.").foregroundStyle(B.onSurfaceVariant) }
                ForEach(shown) { l in
                    Button { selectedEntry = l } label: {
                    HStack(alignment: .top, spacing: 10) {
                        Circle().fill(l.failed ? B.error : B.success).frame(width: 8, height: 8).padding(.top, 6)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(l.title).font(.subheadline.monospaced()).foregroundStyle(B.onSurface).lineLimit(2)
                            Text([l.agent.isEmpty ? nil : l.agent, l.http > 0 ? "HTTP \(l.http)" : nil,
                                  l.reportedAt?.formatted(date: .omitted, time: .standard) ?? "Time not reported"].compactMap { $0 }.joined(separator: " · "))
                                .font(.caption).foregroundStyle(B.onSurfaceVariant)
                        }
                    }
                    .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                    .contentShape(Rectangle())
                    }.buttonStyle(.plain)
                        .accessibilityIdentifier("linked-activity-entry-" + String(l.id))
                }
                if live.hasOlder && live.device.isLive {
                    Button("Load older") { Task { await live.loadOlderLogs() } }.disabled(live.loadingOlderLogs)
                    if let error = live.olderLogsError { Text(error).font(.caption).foregroundStyle(B.error) }
                }
            } header: {
                Text(sourceText)
            }
        }
        .scrollContentBackground(.hidden)
        .background(B.surface)
        .navigationTitle(showsNavigationTitle ? "Activity" : "")
        .refreshable { await live.refresh(force: true) }
        .task { if live.logs.isEmpty { await live.refresh() } }
        .sheet(item: $selectedEntry) { entry in
            LinkedActivityEntryDetail(entry: entry)
        }
    }

    var sourceText: String {
        switch live.source {
        case .live(let d): "Live from the phone · " + Age.text(d)
        case .stale(let d): "Phone not reachable · last live " + Age.text(d)
        case .snapshot: "From the account's remote view lines"
        case .sample: "Sample data"
        case .none: ""
        }
    }
}

private struct LinkedActivityEntryDetail: View {
    let entry: LogLine
    @Environment(\.dismiss) private var dismiss
    @State private var copied = false
    @State private var copyError: String?

    private var keys: [String] {
        HostActivityDetail.keys + entry.reportedFields.keys.filter { !HostActivityDetail.keys.contains($0) }.sorted()
    }

    var body: some View {
        NavigationStack {
            List {
                ForEach(keys, id: \.self) { key in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(key).font(.caption.monospaced()).foregroundStyle(B.onSurfaceVariant)
                        Text(entry.reportedText(for: key)).font(.body.monospaced())
                            .foregroundStyle(B.onSurface).textSelection(.enabled)
                    }.accessibilityElement(children: .combine)
                        .accessibilityIdentifier("linked-activity-field-" + key)
                }
            }
            .accessibilityIdentifier("linked-activity-detail-fields")
            .scrollContentBackground(.hidden).background(B.surface)
            .safeAreaInset(edge: .bottom) {
                VStack {
                    if let copyError { Text(copyError).foregroundStyle(B.error) }
                    Button(copied ? "Copied" : "Copy as JSON") {
                        do {
                            UIPasteboard.general.string = try entry.reportedJSONString()
                            copied = true; copyError = nil
                        } catch { copyError = error.localizedDescription }
                    }.accessibilityIdentifier("linked-activity-copy")
                        .frame(minHeight: 44)
                }.frame(maxWidth: .infinity).padding(.horizontal).background(B.surface)
            }
            .navigationTitle("Entry details")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }.accessibilityIdentifier("linked-activity-done")
                }
            }
        }
    }
}

struct EmptyPhone: View {
    var title: String
    var body: some View {
        VStack(spacing: 12) {
            ScreenHeader(title: title, lead: "Pair with a Bridgit phone or sign in under More to see this screen.")
        }.padding(16).frame(maxHeight: .infinity, alignment: .top)
    }
}
