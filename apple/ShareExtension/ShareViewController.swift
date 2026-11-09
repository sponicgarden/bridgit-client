import BridgitCore
import SwiftUI
import UniformTypeIdentifiers
#if os(macOS)
import AppKit
#else
import UIKit
#endif

private final class ShareCancellation: @unchecked Sendable {
    private let lock = NSLock(); private var value = false
    func cancel() { lock.lock(); value = true; lock.unlock() }
    var cancelled: Bool { lock.lock(); defer { lock.unlock() }; return value }
}
@MainActor private final class ShareExtensionModel: ObservableObject {
    @Published var mode = InboundShareMode.transfer
    @Published var recipientID = ""
    @Published private(set) var recipients: [InboundShareRecipient] = []
    @Published private(set) var busy = false
    @Published private(set) var receipt: InboundShareRequest?
    @Published private(set) var problem: String?
    @Published private(set) var previews: [Int: String] = [:]
    let providers: [NSItemProvider]
    private let context: NSExtensionContext
    private var inbox: InboundShareInbox?
    private var task: Task<Void, Never>?
    private var progresses: [Progress] = []
    private var cancellation = ShareCancellation()
    private var draft: String?
    var eligible: [InboundShareRecipient] { recipients.filter { $0.allows(mode) } }
    var names: [String] { providers.enumerated().map { previews[$0.offset] ?? $0.element.suggestedName ?? "Item \($0.offset + 1)" } }
    var statusLabel: String {
        switch receipt?.status {
        case "delivered": return "Shared"
        case "rejected": return "Not shared"
        case "cancelled": return "Cancelled"
        case "cancel_requested": return "Cancel requested"
        default: return "Queued"
        }
    }
    func refreshReceipt() {
        guard let receipt, let inbox else { return }
        do { self.receipt = try inbox.request(receipt.id) }
        catch { problem = "Could not refresh share status." }
    }
    func preparePreviews() async {
        for (index, provider) in providers.prefix(10).enumerated() {
            guard !Task.isCancelled else { return }
            let types = provider.registeredTypeIdentifiers.compactMap { UTType($0) }
            guard let type = types.first(where: { $0.conforms(to: .plainText) || ($0.conforms(to: .url) && !$0.conforms(to: .fileURL)) }) else { continue }
            guard let value = try? await item(provider, type: type.identifier) else { continue }
            let text = (value as? String) ?? (value as? URL)?.absoluteString ?? (value as? Data).flatMap { String(data: $0, encoding: .utf8) }
            if let text { previews[index] = String(decoding: text.utf16.prefix(500), as: UTF16.self) }
        }
    }
    init(context: NSExtensionContext) {
        self.context = context
        providers = context.inputItems.compactMap { $0 as? NSExtensionItem }.flatMap { item -> [NSItemProvider] in
            if let attachments = item.attachments, !attachments.isEmpty { return attachments }
            if let text = item.attributedContentText?.string, !text.isEmpty { return [NSItemProvider(object: text as NSString)] }
            return []
        }
        do { let inbox = try InboundShareInbox.configured(); self.inbox = inbox; recipients = try inbox.catalog().recipients }
        catch { problem = "System sharing is unavailable in this build." }
    }
    func cancel() {
        cancellation.cancel(); progresses.forEach { $0.cancel() }; task?.cancel()
        if let draft { inbox?.discardDraft(draft) }
        if let receipt, let inbox {
            do {
                let value = try inbox.cancel(receipt.id); self.receipt = value
                if value.status == "cancel_requested" { return }
            } catch { self.receipt = try? inbox.request(receipt.id); problem = error.localizedDescription; return }
        }
        context.cancelRequest(withError: NSError(domain: NSCocoaErrorDomain, code: NSUserCancelledError))
    }
    func done() { context.completeRequest(returningItems: nil) }
    func submit() {
        guard !busy, receipt == nil, let inbox, let recipient = eligible.first(where: { $0.id == recipientID }), !providers.isEmpty, providers.count <= mode.itemLimit else { return }
        busy = true; problem = nil
        let mode = mode, providers = providers, cancelled = cancellation
        task = Task {
            defer { busy = false }
            do {
                let id = try inbox.beginDraft(); draft = id
                var items: [InboundShareItem] = []
                for provider in providers {
                    try Task.checkCancellation(); guard !cancelled.cancelled else { throw CancellationError() }
                    items.append(try await load(provider, into: inbox, draft: id, mode: mode, cancellation: cancelled))
                }
                guard !cancelled.cancelled else { throw CancellationError() }; try Task.checkCancellation()
                receipt = try inbox.enqueue(draft: id, recipient: recipient, mode: mode, items: items); draft = nil
                // This is a staged owner request, not a fabricated agent delivery.
            } catch is CancellationError { if let draft { inbox.discardDraft(draft); self.draft = nil } }
            catch { if let draft { inbox.discardDraft(draft); self.draft = nil }; problem = error.localizedDescription }
        }
    }
    private func load(_ provider: NSItemProvider, into inbox: InboundShareInbox, draft: String, mode: InboundShareMode, cancellation: ShareCancellation) async throws -> InboundShareItem {
        let types = provider.registeredTypeIdentifiers.compactMap { UTType($0) }
        // A real file/image representation wins over its URL or textual alternatives.
        if let type = types.first(where: { $0.conforms(to: .image) || $0.conforms(to: .movie) || $0.conforms(to: .pdf) || $0.conforms(to: .fileURL) || ($0.conforms(to: .data) && !$0.conforms(to: .text) && !$0.conforms(to: .url)) }) {
            if type.conforms(to: .fileURL) {
                let value = try await item(provider, type: type.identifier)
                guard let url = value as? URL, url.isFileURL else { throw unsupported() }
                let scoped = url.startAccessingSecurityScopedResource(); defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                return try inbox.stageFile(url, name: safeName(provider.suggestedName ?? url.lastPathComponent), mime: mime(url: url, type: type), draft: draft, mode: mode, cancelled: { cancellation.cancelled })
            }
            return try await withCheckedThrowingContinuation { continuation in
                let progress = provider.loadFileRepresentation(forTypeIdentifier: type.identifier) { url, error in
                    do {
                        if let error { throw error }; guard let url else { throw self.unsupported() }
                        let item = try inbox.stageFile(url, name: self.safeName(provider.suggestedName ?? url.lastPathComponent), mime: self.mime(url: url, type: type), draft: draft, mode: mode, cancelled: { cancellation.cancelled })
                        continuation.resume(returning: item)
                    } catch { continuation.resume(throwing: error) }
                }
                progresses.append(progress)
            }
        }
        if let type = types.first(where: { $0.conforms(to: .url) }) {
            let value = try await item(provider, type: type.identifier)
            guard let url = value as? URL else { throw unsupported() }
            if url.isFileURL {
                let scoped = url.startAccessingSecurityScopedResource(); defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                return try inbox.stageFile(url, name: safeName(provider.suggestedName ?? url.lastPathComponent), mime: mime(url: url, type: .data), draft: draft, mode: mode, cancelled: { cancellation.cancelled })
            }
            return try inbox.stageText(url.absoluteString, draft: draft, mode: mode, cancelled: { cancellation.cancelled })
        }
        if let type = types.first(where: { $0.conforms(to: .plainText) }) ?? types.first(where: { $0.conforms(to: .text) }) {
            let value = try await item(provider, type: type.identifier)
            let text = (value as? String) ?? (value as? Data).flatMap { String(data: $0, encoding: .utf8) }
            guard let text else { throw unsupported() }
            return try inbox.stageText(text, draft: draft, mode: mode, cancelled: { cancellation.cancelled })
        }
        throw unsupported()
    }
    private func item(_ provider: NSItemProvider, type: String) async throws -> NSSecureCoding {
        try await withCheckedThrowingContinuation { continuation in
            provider.loadItem(forTypeIdentifier: type, options: nil) { value, error in
                if let error { continuation.resume(throwing: error) }
                else if let value { continuation.resume(returning: value) }
                else { continuation.resume(throwing: self.unsupported()) }
            }
        }
    }
    nonisolated private func safeName(_ raw: String) -> String {
        // Provider labels are display metadata; filesystem paths are always generated UUIDs.
        let value = raw.replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "\\", with: "_")
            .components(separatedBy: .controlCharacters).joined()
        return value.isEmpty ? "Shared file" : String(decoding: value.utf16.prefix(120), as: UTF16.self)
    }
    nonisolated private func mime(url: URL, type: UTType) -> String { (try? url.resourceValues(forKeys: [.contentTypeKey]))?.contentType?.preferredMIMEType ?? type.preferredMIMEType ?? "application/octet-stream" }
    nonisolated private func unsupported() -> DeviceAPIError { .init(422, "unsupported_share", "Share text, a URL or a regular file.") }
}

private struct ShareExtensionView: View {
    @ObservedObject var model: ShareExtensionModel
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack { Text("Bridgit").font(.title2.bold()); Spacer(); Button("Cancel") { model.cancel() }.accessibilityIdentifier("inbound-share-cancel").disabled(model.receipt?.status == "delivered") }
            if let receipt = model.receipt {
                Text(model.statusLabel).font(.headline)
                if ["pending", "delivering"].contains(receipt.status) { Text("Open Bridgit to finish delivery.").foregroundStyle(.secondary) }
                if let error = receipt.error { Text(error).foregroundStyle(.red) }
                Button("Done") { model.done() }.buttonStyle(.borderedProminent)
            } else {
                Picker("Send as", selection: $model.mode) {
                    Text("Share transfer").tag(InboundShareMode.transfer)
                    Text("Import into Files").tag(InboundShareMode.fileImport)
                }.pickerStyle(.segmented).disabled(model.busy).accessibilityIdentifier("inbound-share-mode")
                ScrollView { VStack(alignment: .leading, spacing: 6) { ForEach(Array(model.names.enumerated()), id: \.offset) { _, name in Text(name).lineLimit(3) } } }.frame(maxHeight: 120)
                if model.providers.count > model.mode.itemLimit { Text(model.mode == .fileImport ? "File import accepts one item." : "Share up to ten items.") }
                else if model.eligible.isEmpty { Text("No control agent has access. Configure Files or Shares in Bridgit.") }
                else {
                    Picker("Control agent", selection: $model.recipientID) {
                        Text("Choose an agent").tag("")
                        ForEach(model.eligible) { agent in Text(agent.name).tag(agent.id) }
                    }.disabled(model.busy).accessibilityIdentifier("inbound-share-recipient")
                    if model.busy { ProgressView("Preparing…") }
                    Button("Share") { model.submit() }.buttonStyle(.borderedProminent).accessibilityIdentifier("inbound-share-submit")
                        .disabled(model.busy || !model.eligible.contains(where: { $0.id == model.recipientID }) || model.providers.isEmpty || model.providers.count > model.mode.itemLimit)
                }
            }
            if let problem = model.problem { Text(problem).foregroundStyle(.red).textSelection(.enabled) }
            Spacer(minLength: 0)
        }.padding(20).frame(maxWidth: .infinity, maxHeight: .infinity)
            .task {
                await model.preparePreviews()
                while !Task.isCancelled {
                    model.refreshReceipt()
                    do { try await Task.sleep(nanoseconds: 500_000_000) } catch { return }
                }
            }
    }
}

#if os(macOS)
final class ShareViewController: NSViewController {
    override func loadView() { view = NSView(frame: NSRect(x: 0, y: 0, width: 480, height: 420)) }
    override func viewDidLoad() {
        super.viewDidLoad(); guard let context = extensionContext else { return }
        let host = NSHostingController(rootView: ShareExtensionView(model: .init(context: context)))
        addChild(host); host.view.translatesAutoresizingMaskIntoConstraints = false; view.addSubview(host.view)
        NSLayoutConstraint.activate([host.view.leadingAnchor.constraint(equalTo: view.leadingAnchor), host.view.trailingAnchor.constraint(equalTo: view.trailingAnchor), host.view.topAnchor.constraint(equalTo: view.topAnchor), host.view.bottomAnchor.constraint(equalTo: view.bottomAnchor)])
        preferredContentSize = NSSize(width: 480, height: 420)
    }
}
#else
final class ShareViewController: UIViewController {
    override func viewDidLoad() {
        super.viewDidLoad(); guard let context = extensionContext else { return }
        let host = UIHostingController(rootView: ShareExtensionView(model: .init(context: context)))
        addChild(host); host.view.translatesAutoresizingMaskIntoConstraints = false; view.addSubview(host.view); host.didMove(toParent: self)
        NSLayoutConstraint.activate([host.view.leadingAnchor.constraint(equalTo: view.leadingAnchor), host.view.trailingAnchor.constraint(equalTo: view.trailingAnchor), host.view.topAnchor.constraint(equalTo: view.topAnchor), host.view.bottomAnchor.constraint(equalTo: view.bottomAnchor)])
        preferredContentSize = CGSize(width: 400, height: 500)
    }
}
#endif
