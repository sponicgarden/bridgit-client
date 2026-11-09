import SwiftUI

struct MacLinkedAgentsView: View {
    @ObservedObject var model: MacModel
    private var account: Phone? {
        guard let install = model.selectedInstallID else { return nil }
        return model.phones.first { $0.phone == install }
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("On " + (model.selectedDeviceName ?? "linked device")).font(.title3.bold())
            if let account { AccountAgentRosterView(roster: AccountAgentRoster(phone: account)) }
            else if model.snapshotAt == nil { Text("Agent roster not read yet. Refresh to read this device.").foregroundStyle(B.onSurfaceVariant) }
            // MacModel clears its relay snapshot synchronously when the selected target changes.
            if model.selectedPairing != nil, let at = model.snapshotAt,
               let rows = model.snapshot["agents"] as? [[String: Any]] ?? (model.snapshot["agents"] as? [String: Any])?["agents"] as? [[String: Any]] {
                Divider()
                Text("Relay snapshot · read " + Age.text(at)).font(.caption).foregroundStyle(B.onSurfaceVariant)
                if rows.isEmpty { Text("No agents in this relay response.").foregroundStyle(B.onSurfaceVariant) }
                ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                    if let name = row["name"] as? String {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(name).font(.headline)
                            Text((row["paused"] as? Bool).map { $0 ? "Paused" : "Not paused" } ?? "Status not reported")
                                .font(.caption).foregroundStyle(B.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
