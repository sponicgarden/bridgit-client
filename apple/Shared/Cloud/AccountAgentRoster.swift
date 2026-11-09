import Foundation

/// RemoteView.snapshot reports every current name, but at most 20 detailed statuses.
/// History never establishes current membership; an explicitly empty roster is authoritative.
struct AccountAgentRoster {
    struct Entry: Identifiable, Equatable {
        var name: String
        var paused: Bool?
        var lastUsed: Double?
        var id: String { name }
        var state: String { paused.map { $0 ? "Paused" : "Not paused" } ?? "Status not reported" }
    }
    var current: [Entry]
    var history: [Entry]
    var reported: Bool
    var reportedAt: Double?

    init(phone: Phone?) {
        reportedAt = phone?.status_at
        let status = phone?.status
        // Detail-only snapshots can establish a partial roster, never a complete empty one.
        reported = status?.agents != nil
        let names = status?.agents ?? status?.agent_status?.map(\.name) ?? []
        var seen = Set<String>()
        current = names.filter { seen.insert($0).inserted }.map { name in
            let detail = status?.agent_status?.first { $0.name == name }
            return Entry(name: name, paused: detail?.paused, lastUsed: detail?.last_used_at)
        }
        let currentNames = Set(current.map(\.name))
        var historical = Set<String>()
        history = (phone?.agent_history ?? []).filter { !currentNames.contains($0.name) && historical.insert($0.name).inserted }
            .map { Entry(name: $0.name, paused: nil, lastUsed: $0.last_used_at) }
    }

    var sourceText: String {
        "Account snapshot · " + (reportedAt.map { "reported " + Age.text(ms: $0) } ?? "report time unavailable")
    }
    var emptyText: String { reported ? "No current agents reported." : "Current agent roster not reported." }
}
