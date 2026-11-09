import BridgitCore
import Combine
import Foundation
#if os(iOS)
import UIKit
#endif

@MainActor final class InboundSharingModel: ObservableObject {
    @Published private(set) var rows: [InboundShareRequest] = []
    @Published private(set) var problem: String?
    let inbox: InboundShareInbox?
    private let store: AppletStore
    private var tasks: [Task<Void, Never>] = []
    private var draining = false
    private var active: Bool
    init(store: AppletStore, inbox: InboundShareInbox? = nil) {
        self.store = store; self.inbox = inbox ?? (try? .configured())
        #if os(iOS)
        active = UIApplication.shared.applicationState == .active
        #else
        active = true
        #endif
        tasks.append(Task { [weak self, store] in
            for await _ in await store.changes(to: .transfers) { guard !Task.isCancelled else { return }; await self?.refresh() }
        })
        tasks.append(Task { [weak self] in
            while !Task.isCancelled { await self?.refresh(); do { try await Task.sleep(nanoseconds: 2_000_000_000) } catch { return } }
        })
    }
    deinit { tasks.forEach { $0.cancel() } }
    func setActive(_ value: Bool) { active = value; if value { Task { await refresh() } } }
    func refresh() async {
        guard let inbox, active, !draining else { return }
        draining = true; defer { draining = false }
        do {
            try inbox.publish(await store.inboundShareCatalog())
            let waiting = try inbox.requests().filter { ["pending", "delivering", "cancel_requested"].contains($0.status) }
            for item in waiting {
                guard active else { break }; try Task.checkCancellation()
                _ = try await store.consumeInboundShare(inbox, id: item.id)
            }
            rows = try inbox.requests(); problem = nil
        } catch is CancellationError { }
        catch { problem = error.localizedDescription }
    }
    func cancel(_ id: String) async throws { guard let inbox else { throw unavailable() }; _ = try inbox.cancel(id); await refresh() }
    func retry(_ id: String) async throws { guard let inbox else { throw unavailable() }; _ = try inbox.retry(id); await refresh() }
    func status() async throws -> [String: Any] {
        guard let inbox else { return ["available": false, "reason": "signed_app_group_unavailable", "requests": []] }
        return ["available": true, "recipients": try await store.inboundShareCatalog().recipients.map { ["id": $0.id, "generation": $0.generation, "name": $0.name, "transfer": $0.shares, "file_import": $0.files] }, "requests": try inbox.requests().map(\.summary), "delivery_context": "iOS foreground; macOS awake"]
    }
    func enqueue(_ body: [String: Any], authorize: @escaping (InboundShareMode) async throws -> Void) async throws -> InboundShareRequest {
        guard let inbox else { throw unavailable() }
        guard Set(body.keys).isSubset(of: ["request_id", "mode", "recipient_id", "generation", "text", "items"]),
              let id = body["request_id"] as? String, UUID(uuidString: id) != nil,
              let rawMode = body["mode"] as? String, let mode = InboundShareMode(rawValue: rawMode),
              let recipientID = body["recipient_id"] as? String, let generation = body["generation"] as? String,
              (body["text"] != nil) != (body["items"] != nil) else { throw DeviceAPIError(422, "invalid_share", "Use a stable request_id, share mode, current recipient/generation and text or items.") }
        try await authorize(mode)
        let catalog = try await store.inboundShareCatalog()
        guard let recipient = catalog.recipients.first(where: { $0.id == recipientID && $0.generation == generation && $0.allows(mode) }) else { throw DeviceAPIError(403, "share_access_revoked", "Choose a currently authorized recipient.") }
        try inbox.publish(catalog)
        let draft = try inbox.beginDraft(); defer { inbox.discardDraft(draft) }
        var items: [InboundShareItem] = []
        if let text = body["text"] as? String { items = [try inbox.stageText(text, draft: draft, mode: mode)] }
        else if let inputs = body["items"] as? [[String: Any]], !inputs.isEmpty, inputs.count <= mode.itemLimit {
            for input in inputs {
                guard Set(input.keys) == ["name", "mime", "data_base64"], let name = input["name"] as? String, let mime = input["mime"] as? String,
                      let encoded = input["data_base64"] as? String, encoded.utf8.count <= (mode.byteLimit + 2) / 3 * 4,
                      let bytes = Data(base64Encoded: encoded), bytes.count <= mode.byteLimit else { throw DeviceAPIError(422, "invalid_share", "Use valid bounded file items.") }
                try await authorize(mode); items.append(try inbox.stageBytes(bytes, name: name, mime: mime, draft: draft, mode: mode))
            }
        } else { throw DeviceAPIError(422, "invalid_share", "Provide text or bounded items for this share mode.") }
        try await authorize(mode); try Task.checkCancellation()
        let request = try inbox.enqueue(draft: draft, requestID: id, recipient: recipient, mode: mode, items: items)
        await refresh(); return (try? inbox.request(request.id)) ?? request
    }
    private func unavailable() -> DeviceAPIError { .init(503, "share_container_unavailable", "System sharing is unavailable for this build's signed container.") }
}
