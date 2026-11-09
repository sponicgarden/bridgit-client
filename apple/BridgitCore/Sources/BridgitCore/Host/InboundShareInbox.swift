import CryptoKit
import Darwin
import Foundation

public enum InboundShareMode: String, Codable, CaseIterable, Sendable {
    case transfer, fileImport = "file_import"
    public var kind: TransferKind { self == .transfer ? .shares : .files }
    public var itemLimit: Int { self == .transfer ? 10 : 1 }
    public var byteLimit: Int { self == .transfer ? 25 * 1024 * 1024 : 512 * 1024 }
    public func accepts(mime: String) -> Bool {
        self == .transfer || ["image/jpeg", "image/png", "image/webp", "audio/mp4", "audio/mpeg", "audio/ogg", "application/pdf", "text/plain"].contains(mime.lowercased())
    }
}
public struct InboundShareRecipient: Codable, Identifiable, Sendable {
    public let id: String
    public let generation: String
    public let mode: String
    public let name: String
    public let shares: Bool
    public let files: Bool
    public init(id: String, generation: String, mode: String, name: String, shares: Bool, files: Bool) {
        self.id = id; self.generation = generation; self.mode = mode; self.name = name; self.shares = shares; self.files = files
    }
    public func allows(_ mode: InboundShareMode) -> Bool { mode == .transfer ? shares : files }
}
public struct InboundShareCatalog: Codable, Sendable {
    public var recipients: [InboundShareRecipient]
    public var updatedAt: Date
    public init(recipients: [InboundShareRecipient], updatedAt: Date = Date()) { self.recipients = recipients; self.updatedAt = updatedAt }
}
public struct InboundShareItem: Codable, Sendable {
    public let name: String
    public let mime: String
    public let file: String
    public let size: Int
    public let sha256: String
    public var origin: String = "file"
}
public struct InboundShareRequest: Codable, Identifiable, Sendable {
    public var id: String
    public var recipient: InboundShareRecipient
    public var mode: InboundShareMode
    public var items: [InboundShareItem]
    public var createdAt: Date
    public var status: String = "pending"
    public var error: String?
    public var transferIDs: [String] = []
    public var fingerprint: String {
        let value = Fingerprint(recipient: recipient.id, generation: recipient.generation, transport: recipient.mode, mode: mode, items: items.map { FingerprintItem(name: $0.name, mime: $0.mime, size: $0.size, sha256: $0.sha256, origin: $0.origin) })
        return InboundShareInbox.digest((try? JSONEncoder.sorted.encode(value)) ?? Data())
    }
    private struct Fingerprint: Codable { let recipient: String; let generation: String; let transport: String; let mode: InboundShareMode; let items: [FingerprintItem] }
    private struct FingerprintItem: Codable { let name: String; let mime: String; let size: Int; let sha256: String; let origin: String }
    public var summary: [String: Any] {
        ["id": id, "recipient_id": recipient.id, "generation": recipient.generation, "mode": mode.rawValue,
         "status": status, "error": error as Any? ?? NSNull(), "created_at": Int64(createdAt.timeIntervalSince1970 * 1000),
         "items": items.map { ["name": $0.name, "mime": $0.mime, "size_bytes": $0.size] as [String: Any] }, "transfer_ids": transferIDs]
    }
}
private extension JSONEncoder {
    static var sorted: JSONEncoder { let value = JSONEncoder(); value.outputFormatting = [.sortedKeys]; return value }
}

/// Only an inbound recipient catalog and newly staged payloads enter this container.
/// No host tokens, existing agent files, accounts, applets or view credentials are shared.
public final class InboundShareInbox: @unchecked Sendable {
    public let root: URL
    private struct Tombstone: Codable {
        let fingerprint: String; let status: String; let transferIDs: [String]; let createdAt: Date
    }
    private static let maximumBytes = 100 * 1024 * 1024
    public init(root: URL) throws {
        self.root = root
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        try FileManager.default.createDirectory(at: root.appendingPathComponent("requests"), withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        try FileManager.default.createDirectory(at: root.appendingPathComponent("drafts"), withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        try FileManager.default.createDirectory(at: root.appendingPathComponent("receipts"), withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
    }
    public static func configured(bundle: Bundle = .main) throws -> InboundShareInbox {
        guard let group = bundle.object(forInfoDictionaryKey: "BridgitInboundShareGroup") as? String, !group.isEmpty,
              let url = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group) else { throw DeviceAPIError(503, "share_container_unavailable", "System sharing is unavailable for this build's signed container.") }
        return try .init(root: url.appendingPathComponent("InboundShares", isDirectory: true))
    }
    private func locked<T>(_ body: () throws -> T) throws -> T {
        let path = root.appendingPathComponent("inbox.lock").path
        let fd = Darwin.open(path, O_CREAT | O_RDWR | O_NOFOLLOW | O_CLOEXEC, 0o600)
        guard fd >= 0 else { throw DeviceAPIError(503, "share_storage_unavailable", "Shared storage is unavailable.") }
        defer { _ = Darwin.close(fd) }
        guard flock(fd, LOCK_EX) == 0 else { throw DeviceAPIError(503, "share_storage_busy", "Shared storage is busy.") }
        defer { _ = flock(fd, LOCK_UN) }
        return try body()
    }
    public func publish(_ catalog: InboundShareCatalog) throws { try locked { try write(catalog, to: root.appendingPathComponent("recipients.json")) } }
    public func catalog() throws -> InboundShareCatalog {
        try locked {
            let file = root.appendingPathComponent("recipients.json")
            guard FileManager.default.fileExists(atPath: file.path) else { return .init(recipients: []) }
            return try read(InboundShareCatalog.self, file: file, limit: 131072)
        }
    }
    public func beginDraft(id: String = UUID().uuidString.lowercased()) throws -> String {
        try locked {
            guard let id = UUID(uuidString: id)?.uuidString.lowercased(), !FileManager.default.fileExists(atPath: requestFolder(id).path) else { throw invalid() }
            let drafts = root.appendingPathComponent("drafts")
            let folders = try FileManager.default.contentsOfDirectory(at: drafts, includingPropertiesForKeys: [.contentModificationDateKey])
            // Incomplete provider copies have no owner-visible receipt. Reap only
            // abandoned drafts after a day; active and queued requests are retained.
            for old in folders where UUID(uuidString: old.lastPathComponent) != nil {
                if let changed = try old.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate,
                   changed < Date().addingTimeInterval(-86400) { try FileManager.default.removeItem(at: old) }
            }
            guard try FileManager.default.contentsOfDirectory(atPath: drafts.path).count < 100 else { throw DeviceAPIError(429, "share_inbox_full", "Finish existing incoming shares first.") }
            let folder = draftFolder(id)
            guard !FileManager.default.fileExists(atPath: folder.path) else { throw DeviceAPIError(409, "share_exists", "This share is already being prepared.") }
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
            return id
        }
    }
    public func discardDraft(_ id: String) { guard UUID(uuidString: id) != nil else { return }; try? locked { try? FileManager.default.removeItem(at: draftFolder(id)) } }
    public func stageText(_ text: String, draft: String, mode: InboundShareMode, cancelled: () -> Bool = { false }) throws -> InboundShareItem {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw invalid() }
        let clipped: String
        if mode == .transfer {
            guard text.utf16.count <= 200_000 else { throw DeviceAPIError(413, "share_text_too_large", "Share up to 200,000 text characters.") }; clipped = text
        } else { clipped = String(decoding: text.utf16.prefix(32768), as: UTF16.self) }
        var item = try stageBytes(Data(clipped.utf8), name: mode == .transfer ? "shared.txt" : "Shared text.txt", mime: "text/plain", draft: draft, mode: mode, cancelled: cancelled)
        item.origin = "text"; return item
    }
    public func stageBytes(_ bytes: Data, name: String, mime: String, draft: String, mode: InboundShareMode, cancelled: () -> Bool = { false }) throws -> InboundShareItem {
        try locked {
            try validate(name: name, mime: mime)
            guard mode.accepts(mime: mime) else { throw DeviceAPIError(422, "unsupported_file", "Import images, PDF, plain text or supported audio.") }
            guard bytes.count <= mode.byteLimit else { throw DeviceAPIError(413, "share_item_too_large", "The shared item exceeds this mode's limit.") }
            guard !cancelled(), UUID(uuidString: draft) != nil else { throw invalid() }
            guard try stagingBytes() + bytes.count <= Self.maximumBytes else { throw DeviceAPIError(429, "share_inbox_full", "Finish or cancel existing incoming shares first.") }
            let file = UUID().uuidString.lowercased() + ".bin", url = draftFolder(draft).appendingPathComponent(file)
            try bytes.write(to: url, options: [.atomic]); try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
            guard !cancelled() else { try? FileManager.default.removeItem(at: url); throw CancellationError() }
            return .init(name: name, mime: mime, file: file, size: bytes.count, sha256: Self.digest(bytes))
        }
    }
    /// Copy inside NSItemProvider's temporary-file callback; never retain a provider URL.
    public func stageFile(_ source: URL, name: String, mime: String, draft: String, mode: InboundShareMode, cancelled: () -> Bool = { false }) throws -> InboundShareItem {
        try locked {
            try validate(name: name, mime: mime)
            guard mode.accepts(mime: mime) else { throw DeviceAPIError(422, "unsupported_file", "Import images, PDF, plain text or supported audio.") }
            guard source.isFileURL, UUID(uuidString: draft) != nil, !cancelled() else { throw invalid() }
            let values = try source.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey])
            guard values.isRegularFile == true, values.isSymbolicLink != true, values.fileSize ?? 0 <= mode.byteLimit else { throw DeviceAPIError(413, "unsupported_share", "Choose a regular file within this share mode's limit.") }
            let fd = Darwin.open(source.path, O_RDONLY | O_NOFOLLOW | O_CLOEXEC)
            guard fd >= 0 else { throw invalid() }; let input = FileHandle(fileDescriptor: fd, closeOnDealloc: true)
            let file = UUID().uuidString.lowercased() + ".bin", destination = draftFolder(draft).appendingPathComponent(file)
            guard FileManager.default.createFile(atPath: destination.path, contents: nil, attributes: [.posixPermissions: 0o600]) else { throw invalid() }
            let output = try FileHandle(forWritingTo: destination)
            var succeeded = false; defer { try? input.close(); try? output.close(); if !succeeded { try? FileManager.default.removeItem(at: destination) } }
            let existingBytes = try stagingBytes()
            var total = 0, hash = SHA256()
            while let part = try input.read(upToCount: 65536), !part.isEmpty {
                guard !cancelled() else { throw CancellationError() }; total += part.count
                guard total <= mode.byteLimit else { throw DeviceAPIError(413, "share_item_too_large", "The shared file exceeds this mode's limit.") }
                guard existingBytes + total <= Self.maximumBytes else { throw DeviceAPIError(429, "share_inbox_full", "Finish or cancel existing incoming shares first.") }
                try output.write(contentsOf: part); hash.update(data: part)
            }
            try output.synchronize(); guard !cancelled() else { throw CancellationError() }; succeeded = true
            return .init(name: name, mime: mime, file: file, size: total, sha256: hash.finalize().map { String(format: "%02x", $0) }.joined())
        }
    }
    public func enqueue(draft: String, requestID: String? = nil, recipient: InboundShareRecipient, mode: InboundShareMode, items: [InboundShareItem]) throws -> InboundShareRequest {
        try locked {
            guard UUID(uuidString: draft) != nil, !items.isEmpty, items.count <= mode.itemLimit,
                  items.allSatisfy({ $0.size >= 0 && $0.size <= mode.byteLimit }), Set(items.map(\.file)).count == items.count else { throw invalid() }
            let id = (requestID ?? draft).lowercased()
            guard UUID(uuidString: id) != nil else { throw invalid() }
            var request = InboundShareRequest(id: id, recipient: recipient, mode: mode, items: items, createdAt: Date())
            let receiptFile = root.appendingPathComponent("receipts").appendingPathComponent(id + ".json")
            if FileManager.default.fileExists(atPath: receiptFile.path) {
                let previous = try read(Tombstone.self, file: receiptFile, limit: 8192)
                guard previous.fingerprint == request.fingerprint else { throw DeviceAPIError(409, "share_replay_mismatch", "This share ID already belongs to different content or another recipient.") }
                request.status = previous.status; request.transferIDs = previous.transferIDs; request.createdAt = previous.createdAt
                try? FileManager.default.removeItem(at: draftFolder(draft)); return request
            }
            if FileManager.default.fileExists(atPath: requestFolder(id).path) {
                let previous = try load(id)
                guard previous.fingerprint == request.fingerprint else { throw DeviceAPIError(409, "share_replay_mismatch", "This share ID already belongs to different content or another recipient.") }
                try? FileManager.default.removeItem(at: draftFolder(draft)); return previous
            }
            let existing = try requestsUnlocked()
            let terminal = existing.filter { ["delivered", "cancelled"].contains($0.status) }
            for (index, old) in terminal.enumerated() where index >= 200 || old.createdAt < Date().addingTimeInterval(-7 * 86400) {
                try? FileManager.default.removeItem(at: requestFolder(old.id))
            }
            guard existing.filter({ !["delivered", "cancelled"].contains($0.status) }).count < 100, existing.filter({ !["delivered", "cancelled"].contains($0.status) }).flatMap(\.items).reduce(0, { $0 + $1.size }) + items.reduce(0, { $0 + $1.size }) <= Self.maximumBytes else { throw DeviceAPIError(429, "share_inbox_full", "Finish or cancel existing incoming shares first.") }
            let catalogFile = root.appendingPathComponent("recipients.json")
            let catalog = try read(InboundShareCatalog.self, file: catalogFile, limit: 131072)
            guard catalog.recipients.contains(where: { $0.id == recipient.id && $0.generation == recipient.generation && $0.mode == recipient.mode && $0.allows(mode) }) else { throw DeviceAPIError(403, "share_recipient_unavailable", "This agent is no longer available for the selected share mode.") }
            try write(request, to: draftFolder(draft).appendingPathComponent("request.json"))
            try FileManager.default.moveItem(at: draftFolder(draft), to: requestFolder(id))
            return request
        }
    }
    public func requests() throws -> [InboundShareRequest] { try locked { try requestsUnlocked() } }
    public func request(_ id: String) throws -> InboundShareRequest { try locked { try load(id) } }
    public func cancel(_ id: String) throws -> InboundShareRequest {
        try locked {
            var value = try load(id)
            guard value.status != "delivered" else { throw DeviceAPIError(409, "share_already_delivered", "This share was already delivered.") }
            value.status = value.status == "delivering" || value.status == "cancel_requested" ? "cancel_requested" : "cancelled"
            value.error = nil; try save(value)
            if value.status == "cancelled" { removePayload(value) }; return value
        }
    }
    public func retry(_ id: String) throws -> InboundShareRequest {
        try locked {
            var value = try load(id)
            guard value.status == "rejected" else { throw DeviceAPIError(409, "share_not_retryable", "Only a rejected share can be explicitly retried.") }
            value.status = "pending"; value.error = nil; try save(value); return value
        }
    }
    /// Lock spans the host's atomic authority/import transaction. A process crash leaves
    /// delivering/cancel_requested recoverable using the private durable id+digest receipt.
    public func deliver(_ id: String, body: (InboundShareRequest, (InboundShareItem) throws -> Data) throws -> [String]?) throws -> InboundShareRequest {
        try locked {
            var value = try load(id)
            guard ["pending", "delivering", "cancel_requested"].contains(value.status) else { return value }
            let wasCancelRequested = value.status == "cancel_requested"
            if !wasCancelRequested { value.status = "delivering"; try save(value) }
            let ids: [String]?
            do {
                ids = try body(value) { item in
                    guard item.file.range(of: "^[a-f0-9-]{36}\\.bin$", options: .regularExpression) != nil, item.size >= 0, item.size <= value.mode.byteLimit else { throw self.invalid() }
                    let bytes = try self.readBytes(self.requestFolder(id).appendingPathComponent(item.file), limit: value.mode.byteLimit)
                    guard bytes.count == item.size, Self.digest(bytes) == item.sha256 else { throw self.invalid() }; return bytes
                }
            } catch is CancellationError {
                value.status = wasCancelRequested ? "cancel_requested" : "pending"; try? save(value); throw CancellationError()
            } catch {
                value.status = "rejected"; value.error = (error as? DeviceAPIError)?.message ?? "Could not deliver this share."
                try save(value); return value
            }
            // A receipt-write failure after the private DB commit remains delivering,
            // never a false rejection/cancellation. The next pass recovers by ID.
            value.status = ids == nil ? "cancelled" : "delivered"; value.transferIDs = ids ?? []; value.error = nil
            try save(value); removePayload(value); return value
        }
    }
    private func stagingBytes() throws -> Int {
        var total = 0
        for directory in ["drafts", "requests"] {
            guard let files = FileManager.default.enumerator(at: root.appendingPathComponent(directory), includingPropertiesForKeys: [.fileSizeKey, .isRegularFileKey, .isSymbolicLinkKey]) else { throw invalid() }
            for case let file as URL in files where file.pathExtension == "bin" {
                let info = try file.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey, .isSymbolicLinkKey])
                guard info.isRegularFile == true, info.isSymbolicLink != true else { throw invalid() }
                total += info.fileSize ?? 0
                if total > Self.maximumBytes { return total }
            }
        }
        return total
    }
    private func validate(name: String, mime: String) throws {
        guard !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, name.utf16.count <= 120, name != ".", name != "..", !name.contains("/"), !name.contains("\\"), name.rangeOfCharacter(from: .controlCharacters) == nil,
              mime.count <= 100, mime.range(of: #"\A[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+\z"#, options: .regularExpression) != nil else { throw invalid() }
    }
    private func requestsUnlocked() throws -> [InboundShareRequest] {
        let names = try FileManager.default.contentsOfDirectory(atPath: root.appendingPathComponent("requests").path)
        guard names.count <= 1000 else { throw invalid() }
        return try names.filter { UUID(uuidString: $0) != nil }.map(load).sorted { $0.createdAt > $1.createdAt }
    }
    private func draftFolder(_ id: String) -> URL { root.appendingPathComponent("drafts").appendingPathComponent(id.lowercased(), isDirectory: true) }
    private func requestFolder(_ id: String) -> URL { root.appendingPathComponent("requests").appendingPathComponent(id.lowercased(), isDirectory: true) }
    private func load(_ id: String) throws -> InboundShareRequest { guard let id = UUID(uuidString: id)?.uuidString.lowercased() else { throw invalid() }; let value = try read(InboundShareRequest.self, file: requestFolder(id).appendingPathComponent("request.json"), limit: 131072); guard value.id == id else { throw invalid() }; return value }
    private func save(_ value: InboundShareRequest) throws {
        try write(value, to: requestFolder(value.id).appendingPathComponent("request.json"))
        if ["delivered", "cancelled"].contains(value.status) {
            let receipt = Tombstone(fingerprint: value.fingerprint, status: value.status, transferIDs: value.transferIDs, createdAt: value.createdAt)
            try write(receipt, to: root.appendingPathComponent("receipts").appendingPathComponent(value.id + ".json"))
        }
    }
    private func removePayload(_ value: InboundShareRequest) { for item in value.items where item.file.range(of: "^[a-f0-9-]{36}\\.bin$", options: .regularExpression) != nil { try? FileManager.default.removeItem(at: requestFolder(value.id).appendingPathComponent(item.file)) } }
    private func write<T: Encodable>(_ value: T, to file: URL) throws { try JSONEncoder.sorted.encode(value).write(to: file, options: [.atomic]); try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: file.path) }
    private func read<T: Decodable>(_ type: T.Type, file: URL, limit: Int) throws -> T { try JSONDecoder().decode(type, from: readBytes(file, limit: limit)) }
    private func readBytes(_ file: URL, limit: Int) throws -> Data {
        let fd = Darwin.open(file.path, O_RDONLY | O_NOFOLLOW | O_CLOEXEC); guard fd >= 0 else { throw invalid() }
        let input = FileHandle(fileDescriptor: fd, closeOnDealloc: true); defer { try? input.close() }
        let data = try input.read(upToCount: limit + 1) ?? Data(); guard data.count <= limit else { throw invalid() }; return data
    }
    public static func digest(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
    private func invalid() -> DeviceAPIError { .init(422, "invalid_inbound_share", "The shared item is unsupported or invalid.") }
}
