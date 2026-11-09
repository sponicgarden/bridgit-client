import SwiftUI

/// Agents, as on the phone: each agent with its avatar, connection and when it last called.
struct AgentsView: View {
    var live: PhoneLive?
    var body: some View {
        Group {
            if let live { AgentsList(live: live) } else { EmptyPhone(title: "Control agents") }
        }
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct AgentsList: View {
    @ObservedObject var live: PhoneLive

    /// Bridgit Portal viewers only view the phone: one line names them instead of agent cards (APK AgentsFragment).
    var controlAgents: [AgentRow] { live.agents.filter { !$0.portal } }
    var viewers: [AgentRow] { live.agents.filter(\.portal) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                // No title row, as on the phone (Agents opens straight onto its cards).
                SectionLabel(text: "Control agents")
                if controlAgents.isEmpty {
                    InfoPanel { Text("No control agents reported yet.").foregroundStyle(B.onSurfaceVariant) }
                }
                ForEach(controlAgents) { a in AgentCard(agent: a) }
                if !viewers.isEmpty {
                    HStack(spacing: 10) {
                        Image(systemName: "ipad.and.iphone").foregroundStyle(B.primary)
                        Text("Viewing through Bridgit: " + (viewers.count == 1 ? PhoneLive.client : "iPhone/iPad"))
                            .frostText(.supporting).foregroundStyle(B.onSurface)
                    }
                    .padding(.horizontal, 4).padding(.top, 4)
                }
                Text("Adding, pausing or removing control agents is done on the phone.").frostText(.supporting).foregroundStyle(B.onSurfaceVariant)
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
        let c = B.avatar(agent.name)
        Card {
            HStack(alignment: .top, spacing: 12) {
                Text(String(agent.name.prefix(1)).uppercased()).frostText(.label, weight: .semibold).foregroundStyle(c.fg)
                    .frame(width: 42, height: 42).background(c.bg).clipShape(Circle())
                    .opacity(agent.paused ? 0.5 : 1)
                VStack(alignment: .leading, spacing: 3) {
                    Text(agent.name).frostText(.label, weight: .semibold).foregroundStyle(B.onSurface)
                    if let conn = agent.connectionText, !conn.isEmpty {
                        Text(conn).frostText(.supporting).foregroundStyle(B.onSurfaceVariant).lineLimit(2)
                    }
                    TimelineView(.periodic(from: .now, by: 30)) { ctx in
                        HStack(spacing: 6) {
                            Circle().fill(agent.paused ? B.error : agent.isActive ? B.success : B.outline).frame(width: 8, height: 8)
                            Text(agent.statusLine(now: ctx.date)).frostText(.supporting, weight: .medium)
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
    @State private var agent = ""
    @State private var onlyFailed = false

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
                    HStack(alignment: .top, spacing: 10) {
                        Circle().fill(l.failed ? B.error : B.success).frame(width: 8, height: 8).padding(.top, 6)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(l.title).font(.subheadline.monospaced()).foregroundStyle(B.onSurface).lineLimit(2)
                            Text([l.agent.isEmpty ? nil : l.agent, l.http > 0 ? "HTTP \(l.http)" : nil,
                                  Date(timeIntervalSince1970: l.ts / 1000).formatted(date: .omitted, time: .standard)].compactMap { $0 }.joined(separator: " · "))
                                .frostText(.supporting).foregroundStyle(B.onSurfaceVariant)
                        }
                    }
                }
                if live.hasOlder && live.device.isLive {
                    Button("Load older") { Task { await live.loadOlderLogs() } }
                }
            } header: {
                Text(sourceText)
            }
        }
        .scrollContentBackground(.hidden)
        .background(B.surface)
        .navigationTitle("Activity")
        .refreshable { await live.refresh(force: true) }
        .task { if live.logs.isEmpty { await live.refresh() } }
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

struct EmptyPhone: View {
    var title: String
    var body: some View {
        VStack(spacing: 12) {
            ScreenHeader(title: title, lead: "Pair with a Bridgit phone or sign in under More to see this screen.")
        }.padding(16).frame(maxHeight: .infinity, alignment: .top)
    }
}
