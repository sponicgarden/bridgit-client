import AVFoundation
import BridgitCore
import SwiftUI

struct InboxCard: Identifiable {
    var body: [String: Any]
    var id: String { body["interaction_id"] as? String ?? "" }
    var status: String { body["status"] as? String ?? "pending" }
    var kind: String { body["kind"] as? String ?? "notice" }
    var isQuestion: Bool { kind == "question" || kind == "html" }
    var waiting: Bool { status == "pending" || status == "seen" || (isQuestion && status == "expired") }
    var needsAttention: Bool { waiting && !(kind == "notice" && status == "seen") }
    var sender: String { (body["sender"] as? [String: Any])?["name"] as? String ?? "Your control agent" }

    var statusLine: String {
        switch status {
        case "answered":
            let answer = body["answer"] as? [String: Any]
            let controls = body["controls"] as? [[String: Any]] ?? []
            let button = answer?["button"] as? String
            let label = controls.first { button != nil && $0["id"] as? String == button }?["label"] as? String
            let values = answer?["values"] as? [String: String] ?? [:]
            let first = controls.compactMap { ($0["id"] as? String).flatMap { values[$0] } }.first
            let cardAnswer = (answer?["data"] as? [String: Any])?["button"] as? String
            return (label ?? button ?? cardAnswer ?? first).map { "✓ You: " + $0 } ?? "✓ You answered"
        case "expired": return isQuestion ? "Expired, you can still answer" : "Missed"
        case "dismissed": return "Closed"
        case "cancelled": return "Cancelled"
        default:
            if kind == "question" { return "Waiting for your answer" }
            if kind == "html" { return "Waiting for your response" }
            return status == "seen" ? "Seen" : "New notice"
        }
    }

    var metadata: String {
        guard let timestamp = body["created_at"] as? String,
              let date = ISO8601DateFormatter().date(from: timestamp) else { return sender }
        let calendar = Calendar.current
        let time: String
        if calendar.isDateInToday(date) { time = "Today, " + date.formatted(date: .omitted, time: .shortened) }
        else if calendar.isDateInYesterday(date) { time = "Yesterday" }
        else { time = date.formatted(.dateTime.day().month(.abbreviated)) }
        return sender + " · " + time
    }

}
struct LocalInteractionInbox: View {
    var title: String?
    var browseLibrary: (() -> Void)?
    @Binding var filter: Filter

    init(title: String? = nil, browseLibrary: (() -> Void)? = nil, filter: Binding<Filter> = .constant(.all)) {
        self.title = title
        self.browseLibrary = browseLibrary
        self._filter = filter
    }

    enum Filter: String, CaseIterable {
        case all = "All", questions = "Questions", notices = "Notices", open = "Open"

        var emptyMessage: String {
            switch self {
            case .all: return ""
            case .questions: return "No questions waiting."
            case .notices: return "No notices."
            case .open: return "Nothing open."
            }
        }

        fileprivate func includes(_ card: InboxCard) -> Bool {
            switch self {
            case .all: return true
            case .questions: return card.isQuestion
            case .notices: return !card.isQuestion
            case .open: return card.waiting
            }
        }
    }
    @EnvironmentObject private var local: LocalAppletModel
    @State private var cards: [InboxCard] = []
    @State private var loaded = false
    @State private var selected: InboxCard?
    @State private var problem: String?
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            if let title { Text(title).font(.headline).accessibilityAddTraits(.isHeader) }
            if browseLibrary != nil {
                HStack(spacing: 8) {
                    filterLabel.fixedSize().accessibilityHidden(true)
                    ScrollViewReader { reader in
                        ScrollView(.horizontal) {
                            HStack(spacing: 8) { filterButtons }
                        }
                        .scrollIndicators(.hidden)
                        .onChange(of: filter, initial: true) { _, selected in
                            reader.scrollTo(selected, anchor: .center)
                        }
                    }
                }
            }
            let visible = cards.filter(filter.includes)
            if loaded && visible.isEmpty {
                if let browseLibrary {
                    emptyPanel(browseLibrary: browseLibrary)
                } else {
                    Text("No cards yet").font(.headline)
                    Text("When a control agent sends you a question, card or notice, it shows up here.")
                        .foregroundStyle(B.onSurfaceVariant)
                }
            }
            let waiting = visible.filter(\.needsAttention)
            inboxSection("Needs your attention · " + String(waiting.count), rows: waiting)
            inboxSection("Completed", rows: visible.filter { !$0.needsAttention })
            if let problem { Text(problem).foregroundStyle(B.error) }
        }
        .task {
            while !Task.isCancelled {
                do {
                    if let store = local.store { cards = try await store.interactions().map { InboxCard(body: $0) }; loaded = true; problem = nil }
                    try await Task.sleep(nanoseconds: 1_000_000_000)
                } catch is CancellationError { return }
                catch { problem = error.localizedDescription; try? await Task.sleep(nanoseconds: 1_000_000_000) }
            }
        }
        .bridgitDialog(item: $selected) { card in
            InteractionCardView(card: card).environmentObject(local)
            #if os(macOS)
                .frame(minWidth: 650, idealWidth: 760)
                .presentationSizing(.fitted)
            #endif
        }
    }

    private var filterLabel: some View {
        Text("Filter:").font(.subheadline).foregroundStyle(B.onSurfaceVariant)
    }

    private var filterButtons: some View {
        ForEach(Filter.allCases, id: \.self) { option in
            let selected = option == filter
            Button { filter = option } label: {
                HStack(spacing: 4) {
                    if selected { Image(systemName: "checkmark").accessibilityHidden(true) }
                    Text(option.rawValue)
                }
                .font(.subheadline.weight(.medium))
                .padding(.horizontal, 7).padding(.vertical, 5)
                .background(selected ? B.primaryContainer : B.surface, in: RoundedRectangle(cornerRadius: 8))
                .overlay { RoundedRectangle(cornerRadius: 8).stroke(B.outlineVariant) }
                .frame(minWidth: 44, minHeight: 44)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .id(option)
            .accessibilityAddTraits(selected ? .isSelected : [])
        }
    }

    private func emptyPanel(browseLibrary: @escaping () -> Void) -> some View {
        ZStack {
            Color.clear.containerRelativeFrame(.vertical) { height, _ in height * 0.72 }
            VStack(spacing: 12) {
                Image(systemName: "questionmark.bubble")
                    .font(.largeTitle).foregroundStyle(B.onSurfaceVariant).accessibilityHidden(true)
                Text(cards.isEmpty ? "No cards yet" : "No matching cards")
                    .font(.headline).accessibilityAddTraits(.isHeader)
                Text(cards.isEmpty
                     ? "When a control agent sends you a question, card or notice, it shows up here."
                     : filter.emptyMessage)
                    .foregroundStyle(B.onSurfaceVariant)
                Button {
                    if cards.isEmpty { browseLibrary() } else { filter = .all }
                } label: {
                    Text(cards.isEmpty ? "Browse library" : "Clear filter").foregroundStyle(B.onPrimary)
                }.buttonStyle(.borderedProminent).tint(B.primary)
                #if os(iOS)
                .controlSize(.large)
                #endif
            }
            .multilineTextAlignment(.center)
            .padding()
            .frame(maxWidth: .infinity)
        }
    }

    @ViewBuilder private func inboxSection(_ heading: String, rows: [InboxCard]) -> some View {
        if let first = rows.first {
            VStack(alignment: .leading, spacing: 0) {
                Text(heading.uppercased()).font(.footnote.weight(.medium)).tracking(1.5)
                    .foregroundStyle(first.needsAttention ? B.primary : B.onSurfaceVariant)
                    .accessibilityAddTraits(.isHeader)
                ForEach(rows) { card in
                    Button { selected = card } label: {
                        HStack(spacing: 12) {
                            AgentMark(name: card.sender)
                            VStack(alignment: .leading, spacing: 4) {
                                Text(card.body["title"] as? String ?? "Card").font(.headline).foregroundStyle(B.onSurface)
                                Text(card.statusLine).font(.subheadline)
                                    .foregroundStyle(card.status == "answered" ? B.success : B.onSurfaceVariant)
                                Text(card.metadata).font(.caption).foregroundStyle(B.onSurfaceVariant)
                            }
                            Spacer(); Image(systemName: "chevron.right").foregroundStyle(B.onSurfaceVariant)
                        }.padding(.vertical, 12).contentShape(Rectangle())
                    }.buttonStyle(.plain)
                    .accessibilityActions {
                        if card.waiting {
                            Button("Close") {
                                Task {
                                    do {
                                        guard let store = local.store else { return }
                                        try await store.dismissInteraction(id: card.id)
                                        AccessibilityNotification.Announcement("Closed. " + card.sender + " was told.").post()
                                    }
                                    catch { problem = error.localizedDescription }
                                }
                            }
                        }
                    }
                    if card.id != rows.last?.id { Divider().padding(.leading, 52) }
                }
            }
        }
    }

}
private struct InteractionCardView: View {
    @DialogDismiss private var dismiss
    @EnvironmentObject private var local: LocalAppletModel
    var card: InboxCard
    @State private var values: [String: String] = [:]
    @State private var problem: String?
    @State private var busy = false
    @Environment(\.scenePhase) private var scenePhase
    @State private var reader: AVSpeechSynthesizer?
    @State private var closedHere = false
    @State private var outcome: String?
    @State private var proposal: HTMLAnswerProposal?
    private var saved: Bool { outcome != nil }
    private var archivedHTML: Bool {
        card.kind == "html" && (card.status == "dismissed" || card.status == "cancelled")
    }
    private var showsConfirmation: Bool { saved && !archivedHTML }
    init(card: InboxCard) {
        self.card = card
        _outcome = State(initialValue: card.waiting ? nil : card.status)
    }
    @AccessibilityFocusState private var confirmationFocused: Bool
    private var controls: [[String: Any]] { card.body["controls"] as? [[String: Any]] ?? [] }
    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack {
                VStack(alignment: .leading, spacing: 3) {
                    if card.kind == "html" {
                        Text("From \(card.sender) · custom card").font(.headline).accessibilityAddTraits(.isHeader)
                        Text(card.body["title"] as? String ?? "Card").font(.subheadline).foregroundStyle(B.onSurfaceVariant)
                    } else {
                        Text(card.body["title"] as? String ?? "Card").font(.headline).accessibilityAddTraits(.isHeader)
                        Text("From \(card.sender)").font(.subheadline).foregroundStyle(B.onSurfaceVariant)
                    }
                }
                Spacer()
                Button("Play aloud", systemImage: "speaker.wave.2") { playAloud() }
                    .labelStyle(.iconOnly)
                if showsConfirmation { Button("Done") { dismiss() }.buttonStyle(.borderedProminent).keyboardShortcut(.defaultAction) }
                else { Button("Done") { dismiss() }.keyboardShortcut(.cancelAction) }
            }
            if showsConfirmation {
                VStack(spacing: 12) {
                    Image(systemName: outcome == "answered" ? "checkmark.circle.fill" : "clock").font(.largeTitle).foregroundStyle(outcome == "answered" ? B.success : B.onSurfaceVariant).accessibilityHidden(true)
                    Text(outcome == "answered" ? "Answer saved" : outcome == "dismissed" ? "Closed" : (outcome ?? "Closed").capitalized).font(.title2.weight(.semibold)).accessibilityFocused($confirmationFocused)
                    if outcome == "dismissed" { Text("\(card.sender) was told.") }
                }.frame(maxWidth: .infinity).padding(.vertical, 24)
            }
            else if card.body["kind"] as? String == "html", let html = card.body["html"] as? String {
                Group {
                    #if os(macOS)
                    MacCardPreview(html: html, onRespond: respond)
                    #else
                    CardWebView(html: html, onRespond: respond)
                    #endif
                }
                .id(card.id)
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .overlay { RoundedRectangle(cornerRadius: 12).strokeBorder(B.outlineVariant).allowsHitTesting(false) }
                if let proposal {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Review answer").font(.headline).accessibilityAddTraits(.isHeader)
                        ScrollView { Text(proposal.preview).font(.callout).textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading) }.frame(maxHeight: 140)
                        HStack {
                            Button("Send answer") { confirmHTMLAnswer() }.buttonStyle(.borderedProminent).disabled(busy)
                            Button("Cancel", role: .cancel) { proposal.finish(.failure(CancellationError())); self.proposal = nil }
                        }
                    }
                }
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        if let text = card.body["text"] as? String { Text(text).foregroundStyle(B.onSurface) }
                        ForEach(Array(controls.enumerated()), id: \.offset) { _, control in
                            nativeControl(control).disabled(busy)
                        }
                        if controls.contains(where: { ["text", "choice"].contains($0["type"] as? String ?? "") }) {
                            Button("Send answer") { send(button: nil) }.buttonStyle(.borderedProminent).disabled(busy)
                        }
                    }.frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            if archivedHTML {
                VStack(alignment: .leading, spacing: 4) {
                    if card.status == "dismissed" { Text("You closed this. \(card.sender) was told.") }
                    else { Text("\(card.sender) cancelled this.") }
                    Text(card.status == "dismissed" ? "Closed" : "Cancelled")
                        .font(.caption.weight(.medium))
                        .padding(.horizontal, 8).padding(.vertical, 2)
                        .background(B.surfaceHigh, in: Capsule())
                }
                .font(.footnote).foregroundStyle(B.onSurfaceVariant)
                .padding(8).frame(maxWidth: .infinity, alignment: .leading)
                .background(B.surfaceContainer, in: RoundedRectangle(cornerRadius: 8))
            }
            if let problem { Text(problem).foregroundStyle(B.error) }
            if !saved {
                Spacer(minLength: 0)
                HStack {
                    Label(card.status == "pending" ? "Waiting" : card.status.capitalized, systemImage: "clock")
                        .font(.caption.weight(.medium))
                        .padding(.horizontal, 8).padding(.vertical, 3)
                        .foregroundStyle(B.onPrimaryContainer)
                        .background(B.primaryContainer, in: Capsule())
                    Spacer()
                    Button("Dismiss", systemImage: "xmark") {
                        guard !busy, let store = local.store else { return }
                        busy = true
                        Task {
                            defer { busy = false }
                            do {
                                try await store.dismissInteraction(id: card.id)
                                outcome = "dismissed"
                                closedHere = true
                            } catch { problem = error.localizedDescription }
                        }
                    }.buttonStyle(.bordered).buttonBorderShape(.capsule)
                        .overlay { Capsule().strokeBorder(B.outlineVariant).allowsHitTesting(false) }
                        .tint(B.primary).disabled(busy)
                }
            }
        }
        .onDisappear {
            reader?.stopSpeaking(at: .immediate)
            proposal?.finish(.failure(CancellationError()))
            proposal = nil
        }
        .onChange(of: scenePhase) { _, phase in
            if phase != .active { reader?.stopSpeaking(at: .immediate) }
        }
        .padding(.horizontal, card.kind == "html" && !showsConfirmation ? 10 : 24)
        .padding(.vertical, 24)
        #if os(macOS)
        .frame(minHeight: showsConfirmation ? 0 : 620)
        .fixedSize(horizontal: false, vertical: showsConfirmation)
        .onExitCommand { dismiss() }
        #else
        .presentationDetents(showsConfirmation ? [.medium] : [.large])
        .presentationDragIndicator(.visible)
        #endif
        .presentationBackground(B.surface)
        .task(id: closedHere) {
            guard closedHere, card.kind == "html" else { return }
            do { try await Task.sleep(for: .milliseconds(2500)) }
            catch { return }
            dismiss()
        }
        .onChange(of: saved) { _, saved in if saved { confirmationFocused = true } }
        .task {
            guard !card.isQuestion, card.status == "pending" else { return }
            do { try await local.store?.markInteractionSeen(id: card.id) }
            catch { problem = error.localizedDescription }
        }
    }
    private func playAloud() {
        let title = (card.body["title"] as? String ?? "Card").trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedTitle = title.replacingOccurrences(of: "[.!?]+$", with: "", options: .regularExpression)
        let body = (card.body["text"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let introduction = card.sender + " says: " + (trimmedTitle.isEmpty ? title : trimmedTitle) + "."
        let utterance = AVSpeechUtterance(string: body.isEmpty ? introduction : introduction + " " + body.prefix(500))
        let synthesizer = reader ?? AVSpeechSynthesizer()
        #if os(iOS)
        synthesizer.usesApplicationAudioSession = false
        #endif
        reader = synthesizer
        synthesizer.stopSpeaking(at: .immediate)
        synthesizer.speak(utterance)
    }

    @ViewBuilder private func nativeControl(_ control: [String: Any]) -> some View {
        let id = control["id"] as? String ?? "", label = control["label"] as? String ?? ""
        switch control["type"] as? String {
        case "button": Button(label) { send(button: id) }.buttonStyle(.bordered).disabled(busy)
        case "text": TextField(label, text: Binding(get: { values[id] ?? "" }, set: { values[id] = String($0.prefix(2000)) })).textFieldStyle(.roundedBorder)
        case "choice":
            Picker(label, selection: Binding(get: { values[id] ?? "" }, set: { values[id] = $0 })) {
                Text("Choose").tag("")
                ForEach(control["options"] as? [String] ?? [], id: \.self) { Text($0).tag($0) }
            }
        case "link": if let text = control["url"] as? String, let url = URL(string: text) { Link(label, destination: url) }
        default: EmptyView()
        }
    }
    private func send(button: String?) {
        Task {
            do { try await save(["button": button as Any? ?? NSNull(), "values": values, "data": NSNull()]) }
            catch { problem = error.localizedDescription }
        }
    }
    @MainActor private func respond(_ value: Any) async throws {
        guard proposal == nil, !saved else { throw DeviceAPIError(409, "answer_pending", "Review the pending answer first.") }
        try await withCheckedThrowingContinuation { continuation in
            proposal = HTMLAnswerProposal(value: value, continuation: continuation)
        }
    }
    private func confirmHTMLAnswer() {
        guard let proposal else { return }
        Task {
            do {
                try await save(["button": NSNull(), "values": [String: Any](), "data": proposal.value])
                proposal.finish(.success(())); self.proposal = nil
            } catch {
                problem = error.localizedDescription
                proposal.finish(.failure(error)); self.proposal = nil
            }
        }
    }
    @MainActor private func save(_ answer: [String: Any]) async throws {
        guard !busy, let store = local.store else { throw DeviceAPIError(409, "inbox_busy", "Try again.") }
        busy = true; defer { busy = false }
        _ = try await store.answerInteraction(id: card.id, answer: answer)
        outcome = "answered"
    }
}

/// Untrusted HTML may propose data; only the native card's explicit action saves it.
@MainActor final class HTMLAnswerProposal {
    let value: Any
    let preview: String
    private var continuation: CheckedContinuation<Void, Error>?
    init(value: Any, continuation: CheckedContinuation<Void, Error>) {
        self.value = value; self.continuation = continuation
        let data = try? JSONSerialization.data(withJSONObject: value, options: [.prettyPrinted, .sortedKeys, .fragmentsAllowed])
        preview = data.flatMap { String(data: $0, encoding: .utf8) } ?? String(describing: value)
    }
    func finish(_ result: Result<Void, Error>) {
        let saved = continuation; continuation = nil
        saved?.resume(with: result)
    }
}
