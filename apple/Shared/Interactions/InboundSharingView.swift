import SwiftUI

struct InboundSharingView: View {
    @ObservedObject var model: InboundSharingModel
    @State private var error: String?
    var body: some View {
        List {
            if model.inbox == nil { Text("System sharing unavailable") }
            else if model.rows.isEmpty { Text("No incoming shares") }
            ForEach(model.rows) { row in
                VStack(alignment: .leading, spacing: 8) {
                    Text(row.recipient.name).font(.headline)
                    ForEach(Array(row.items.enumerated()), id: \.offset) { _, item in Text(item.name) }
                    Text(row.status.replacingOccurrences(of: "_", with: " ").capitalized).foregroundStyle(.secondary)
                    if let error = row.error { Text(error).foregroundStyle(.red) }
                    HStack {
                        if ["pending", "delivering", "cancel_requested", "rejected"].contains(row.status) {
                            Button("Cancel", role: .cancel) { Task { do { try await model.cancel(row.id) } catch { self.error = error.localizedDescription } } }
                        }
                        if row.status == "rejected" { Button("Retry") { Task { do { try await model.retry(row.id) } catch { self.error = error.localizedDescription } } } }
                    }
                }
            }
            if let value = error ?? model.problem { Text(value).foregroundStyle(.red) }
        }.navigationTitle("Incoming shares").task { await model.refresh() }
    }
}
