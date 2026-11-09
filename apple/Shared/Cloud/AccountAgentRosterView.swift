import SwiftUI

/// Account evidence stays separate from relay responses and is always labelled as a snapshot.
struct AccountAgentRosterView: View {
    var roster: AccountAgentRoster
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(roster.sourceText).font(.caption).foregroundStyle(B.onSurfaceVariant)
            if !roster.reported && !roster.current.isEmpty {
                Text("Partial roster · only detailed agents were reported.").font(.caption).foregroundStyle(B.onSurfaceVariant)
            }
            if roster.current.isEmpty { Text(roster.emptyText).foregroundStyle(B.onSurfaceVariant) }
            ForEach(roster.current) { entry in
                HStack(alignment: .top, spacing: 12) {
                    AgentMark(name: entry.name)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(entry.name).font(.headline)
                        Text(entry.state).font(.caption).foregroundStyle(B.onSurfaceVariant)
                        if let used = entry.lastUsed, used > 0 {
                            Text("Last recorded use · " + Age.text(ms: used)).font(.caption).foregroundStyle(B.onSurfaceVariant)
                        }
                    }
                    Spacer(minLength: 0)
                }.padding(12).background(B.surfaceLow, in: RoundedRectangle(cornerRadius: 12))
            }
            if !roster.history.isEmpty {
                Text("Previously seen · current membership unknown").font(.subheadline.weight(.medium))
                ForEach(roster.history) { entry in
                    Text(entry.name).font(.subheadline).foregroundStyle(B.onSurfaceVariant)
                }
            }
        }
    }
}
