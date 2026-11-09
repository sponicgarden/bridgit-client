import CryptoKit
import Foundation
import Security

public enum HostOperation: String, Codable, CaseIterable {
    case administrator = "agents.admin"
    case files, shares, downloads
    case appletsManage = "applets.install"
    case appletsMediaRead = "applets.media.read", appletsMediaWrite = "applets.media.write"
    case configurationRead = "settings.read", configurationWrite = "settings.write"
    case interactionsRead = "interactions.read", interactionsWrite = "interactions.write"
    case speechHear = "speech.hear", speechSpeak = "speech.speak"
    case microphoneRecord = "mic.record"
    case cameraSnapshot = "camera.snapshot"
    case lanDiscovery = "lan_discovery"
    case connections, log
    case feedback, feedbackFixer = "feedback.fixer"
    case deviceRead = "device.read", appletsRead = "applets.read", appletsWrite = "applets.write", workflowsRun = "workflows.run"
    #if os(macOS)
    /// Computer use (A0112): see the screen and act in any app. Names match Android's grants.
    case screenObserve = "screen.observe", screenControl = "screen.control"
    #endif
    public var capability: String { switch self {
        #if os(macOS)
        case .screenObserve, .screenControl: return "screen"
        #endif
        case .files, .shares, .downloads, .connections, .log, .lanDiscovery: return rawValue; case .feedback, .feedbackFixer: return "feedback"; case .administrator, .configurationRead, .configurationWrite: return "settings"; case .interactionsRead, .interactionsWrite: return "interactions"; case .speechHear, .microphoneRecord: return "mic"; case .speechSpeak: return "speaker"; case .cameraSnapshot: return "camera"; case .deviceRead: return "device"; case .appletsManage, .appletsRead, .appletsWrite, .appletsMediaRead, .appletsMediaWrite: return "applets"; case .workflowsRun: return "workflows" } }
}
enum RetiredHostCredential: String {
    case rotated = "key_rotated", removed = "agent_removed"
    static let lifetime: TimeInterval = 7 * 24 * 60 * 60

    var rejection: DeviceAPIError {
        switch self {
        case .rotated:
            .init(401, "unauthorized", "This key was rotated. Ask the owner for the new setup.", reason: rawValue)
        case .removed:
            .init(401, "unauthorized", "This control agent was removed. Ask the owner to add it again.", reason: rawValue)
        }
    }
}

struct ViewerRelayReceipt: Codable, Equatable {
    var generation: String
    var tokenHash: String
    var until: Int64
}
public struct OwnerDirectPeerAlert: Codable, Equatable, Identifiable {
    public let address: TailnetAddress
    public let at: Date
    public let refused: Bool
    public var id: TailnetAddress { address }
}
public struct HostAgent: Codable, Equatable, Identifiable {
    public var id: String
    public var name: String
    public var notes: String? = nil
    public var generation: String
    public var tokenHash: String
    public var operations: Set<HostOperation>
    public var applets: Set<String>
    public var paused: Bool
    public var ownerDirectPeers: [TailnetAddress]? = nil
    public var ownerDirectPeerAlerts: [OwnerDirectPeerAlert]? = nil
    var ownerDirectRequestPeer: TailnetAddress? = nil
    public var tailnetPeer: String? = nil
    public var satelliteRole: String? = nil
    public var viewer: Bool? = nil
    public var viewerClaimUntil: Int64? = nil
    var viewerRelayReceipt: ViewerRelayReceipt? = nil
    public var peer: String?
    public var eventOwner: String { id + ":" + generation }
    public var mode: String {
        if ownerDirectPeers != nil { return OwnerDirectHostService.mode }
        return tailnetPeer != nil ? "direct" : peer == nil ? "connector" : "sealed"
    }
    var usesRelay: Bool { mode == "connector" || mode == "sealed" }
    /// Explicit owner-issued management authority, never inferred from a view link
    /// or a package's own permissions. Mirrors Android applet_install control keys.
    public func allows(_ operation: HostOperation) -> Bool {
        if operation == .feedback || operation == .feedbackFixer {
            return viewer != true && (usesRelay || ownerDirectPeers != nil) &&
                (operation == .feedback || operations.contains(.feedbackFixer))
        }
        return operations.contains(.administrator) || operations.contains(operation) || (operations.contains(.appletsManage) && [.appletsRead, .appletsWrite, .appletsMediaRead, .appletsMediaWrite].contains(operation))
    }
    public func hasGlobalAppletScope(for operation: HostOperation) -> Bool {
        operations.contains(.administrator) || operations.contains(.appletsManage) && [.appletsManage, .appletsRead, .appletsWrite, .appletsMediaRead, .appletsMediaWrite].contains(operation)
    }
    public func allowsApplet(_ id: String, operation: HostOperation) -> Bool { hasGlobalAppletScope(for: operation) || applets.contains(id) }

    enum CodingKeys: String, CodingKey { case id, name, notes, generation, operations, applets, paused, peer, viewer; case ownerDirectPeers = "owner_direct_peers", ownerDirectPeerAlerts = "owner_direct_peer_alerts", tailnetPeer = "tailnet_peer", satelliteRole = "satellite_role"; case viewerClaimUntil = "viewer_claim_until", viewerRelayReceipt = "viewer_relay_receipt"; case tokenHash = "token_hash" }
    public var announcement: [String: Any] {
        var frame: [String: Any] = ["id": id, "generation": generation, "mode": mode, "token_hash": tokenHash]
        if viewer == true { frame["portal"] = true }
        return frame
    }
    func acceptsReceiptGeneration(_ generation: String, now: Int64) -> Bool {
        viewer == true && viewerRelayReceipt?.generation == generation && (viewerRelayReceipt?.until ?? 0) > now
    }
}
public struct HostRequest {
    public var method: String
    public var path: String
    public var query: [String: Any]
    public var body: [String: Any]
    public var idempotencyKey: String?
    var bodyBytesMeasured: Int?
    var ownerDirectBaseURL: URL?
    public init(method: String, path: String, query: [String: Any] = [:], body: [String: Any] = [:], idempotencyKey: String? = nil) {
        self.method = method; self.path = path; self.query = query; self.body = body; self.idempotencyKey = idempotencyKey
    }
    var isMCPCancellation: Bool { method == "POST" && path == "/v1/mcp" && body["jsonrpc"] as? String == "2.0" && body["method"] as? String == "notifications/cancelled" && body["id"] == nil }
    var isFeedbackFiling: Bool {
        guard method == "POST" else { return false }
        if path == "/v1/feedback" { return true }
        guard path == "/v1/mcp", body["jsonrpc"] as? String == "2.0",
              body["method"] as? String == "tools/call", body["id"] != nil,
              let params = body["params"] as? [String: Any], params["name"] as? String == "feedback_create",
              let arguments = params["arguments"] as? [String: Any], arguments["body"] is [String: Any] else { return false }
        return true
    }

    var isFeedbackWait: Bool {
        let fields: [String: Any]
        if method == "GET", path == "/v1/feedback" { fields = query }
        else if method == "POST", path == "/v1/mcp", body["jsonrpc"] as? String == "2.0",
                body["method"] as? String == "tools/call", body["id"] != nil,
                let params = body["params"] as? [String: Any], params["name"] as? String == "feedback_list",
                let arguments = params["arguments"] as? [String: Any], let nested = arguments["query"] as? [String: Any] {
            fields = nested
        } else { return false }
        guard let since = fields["since"] as? String, !since.isEmpty else { return false }
        if let value = fields["wait"] as? String, let seconds = Int(value) { return (1...55).contains(seconds) }
        guard let value = fields["wait"] as? NSNumber, CFGetTypeID(value) != CFBooleanGetTypeID(),
              value.doubleValue == Double(value.intValue) else { return false }
        return (1...55).contains(value.intValue)
    }
    public var route: (HostOperation, String?)? {
        guard ["GET", "POST", "PATCH", "DELETE"].contains(method), path.hasPrefix("/v1/"), !path.contains("%"), !path.contains("?"), !path.contains("#"), !path.contains("//") else { return nil }
        if let target = query["target"] as? String, SatelliteForwardPolicy.validTarget(target), let operation = SatelliteForwardPolicy.operation(method: method, path: path) { return (operation, nil) }
        if let operation = HostAPIContract.operation(matching: self), operation.grant == .lanDiscovery { return (.lanDiscovery, nil) }
        if path == "/v1/satellites", method == "GET" { return (.deviceRead, nil) }
        if let grant = ComputerUseContract.grant(method: method, path: path) { return (grant, nil) }
        if HostViewerPairing.permits(self) { return (.deviceRead, nil) }
        let p = path.split(separator: "/").map(String.init)
        guard !p.contains("."), !p.contains("..") else { return nil }
        if Array(p.prefix(4)) == ["v1", "owner", "configuration", "callbacks"] {
            if p.count == 4, method == "GET" { return (.configurationRead, nil) }
            if p.count == 5, UUID(uuidString: p[4]) != nil, ["POST", "DELETE"].contains(method) { return (.configurationWrite, nil) }
            return nil
        }
        if path == "/v1/callback/test", method == "POST" { return (.interactionsRead, nil) }
        if Array(p.prefix(4)) == ["v1", "owner", "configuration", "inbound-shares"] {
            if p.count == 4, ["GET", "POST"].contains(method) { return (method == "GET" ? .configurationRead : .configurationWrite, nil) }
            if p.count == 5, UUID(uuidString: p[4]) != nil, ["GET", "DELETE"].contains(method) { return (method == "GET" ? .configurationRead : .configurationWrite, nil) }
            if p.count == 6, UUID(uuidString: p[4]) != nil, p[5] == "retry", method == "POST" { return (.configurationWrite, nil) }
            return nil
        }
        if (2...3).contains(p.count), let kind = TransferKind(rawValue: p[1]) {
            let collection = p.count == 2
            guard collection || UUID(uuidString: p[2]) != nil else { return nil }
            if method == "GET" || (!collection && method == "DELETE") || (collection && kind == .files && method == "POST") {
                return (kind.grant, nil)
            }
            return nil
        }
        if Array(p.prefix(4)) == ["v1", "owner", "configuration", "connections"] {
            if p.count == 4, method == "GET" { return (.configurationRead, nil) }
            if p.count == 4, method == "POST" { return (.configurationWrite, nil) }
            if p.count == 5, UUID(uuidString: p[4]) != nil, ["PATCH", "DELETE"].contains(method) { return (.configurationWrite, nil) }
            if p.count == 6, UUID(uuidString: p[4]) != nil, p[5] == "rotate", method == "POST" { return (.configurationWrite, nil) }
            return nil
        }
        if path == DeviceDomainName.operationPath {
            if method == "GET" { return (.configurationRead, nil) }
            if ["POST", "DELETE"].contains(method) { return (.configurationWrite, nil) }
            return nil
        }
        if path == "/v1/agents/me", method == "GET" { return (.configurationRead, nil) }
        if path == "/v1/agents/me", method == "PATCH" { return (.configurationWrite, nil) }
        if path == "/v1/agents", ["GET", "POST"].contains(method) { return (.administrator, nil) }
        if p.count == 3, p[1] == "agents", UUID(uuidString: p[2]) != nil, ["PATCH", "DELETE"].contains(method) { return (.administrator, nil) }
        if p.count == 4, p[1] == "agents", UUID(uuidString: p[2]) != nil, p[3] == "rotate", method == "POST" {
            return (.administrator, nil)
        }
        if ["/v1/account", "/v1/linked-devices"].contains(path), method == "GET" { return (.configurationRead, nil) }
        if path == "/v1/account/phones", method == "GET" { return (.configurationRead, nil) }
        if path == "/v1/account/phones", method == "POST" { return (.configurationWrite, nil) }
        if p.count == 4, p[1] == "account", p[2] == "phones", ViewerConfigurationAction.validPhoneID(p[3]), method == "DELETE" { return (.configurationWrite, nil) }
        if path == "/v1/account", method == "DELETE" { return (.configurationWrite, nil) }
        if path == "/v1/account/sign-in", ["POST", "DELETE"].contains(method) { return (.configurationWrite, nil) }
        if path == "/v1/linked-devices", method == "POST" { return (.configurationWrite, nil) }
        if ["/v1/linked-devices/attachment", "/v1/linked-devices/pause"].contains(path), method == "POST" { return (.configurationWrite, nil) }
        if p.count == 4, p[1] == "linked-devices", p[2] == "pairings", Pairing.validDevice(p[3]), ["PATCH", "DELETE"].contains(method) { return (.configurationWrite, nil) }
        if path == "/v1/settings", method == "GET" { return (.configurationRead, nil) }
        if path == "/v1/settings", method == "POST" { return (.configurationWrite, nil) }
        if p.count == 4, p[1] == "settings", p[2] == "requests", UUID(uuidString: p[3]) != nil {
            if method == "GET" { return (.configurationRead, nil) }
            if method == "DELETE" { return (.configurationWrite, nil) }
        }
        if p.count == 3, p[1] == "settings", method == "DELETE" { return (.configurationWrite, nil) }
        if method == "GET", ["/v1/health", "/v1/capabilities", "/v1/device/profile", "/v1/discover", "/v1/samples"].contains(path) || HostAPIPaths.selfDescription.contains(path) { return (.deviceRead, nil) }
        if ["GET", "POST"].contains(method), path == "/v1/mcp" { return (.deviceRead, nil) }
        if method == "GET", ["/v1/interactions", "/v1/events"].contains(path) { return (.interactionsRead, nil) }
        if method == "POST", path == "/v1/interactions" { return (.interactionsWrite, nil) }
        if p.count == 3, p[1] == "interactions", UUID(uuidString: p[2]) != nil {
            if method == "GET" { return (.interactionsRead, nil) }
            if method == "DELETE" { return (.interactionsWrite, nil) }
        }
        if path == "/v1/feedback", ["GET", "POST"].contains(method) { return (.feedback, nil) }
        if path == "/v1/feedback/team-key", method == "GET" { return (.feedback, nil) }
        if p.count >= 3, p[1] == "feedback", p[2] != "team-key" {
            if p.count == 3, method == "GET" { return (.feedback, nil) }
            if p.count == 4, method == "POST" {
                if p[3] == "status" { return (.feedbackFixer, nil) }
                if p[3] == "followup" { return (.feedback, nil) }
            }
        }
        if method == "POST", path == "/v1/log" { return (.log, nil) }
        if method == "GET", p.count == 2 || p.count == 3, p[1] == "log" { return (.log, nil) }
        if method == "POST", path == "/v1/mic/record" { return (.microphoneRecord, nil) }
        if method == "POST", path == "/v1/camera/snapshot" { return (.cameraSnapshot, nil) }
        if method == "GET", path == "/v1/connections" { return (.connections, nil) }
        if method == "POST", path == "/v1/connections/send" { return (.connections, nil) }
        if method == "GET", path == "/v1/connections/deliveries" { return (.connections, nil) }
        if p.count == 4, p[1] == "connections", p[2] == "deliveries", UUID(uuidString: p[3]) != nil, ["GET", "DELETE"].contains(method) { return (.connections, nil) }
        if method == "POST", ["/v1/applets/validate", "/v1/applets/install"].contains(path) { return (.appletsManage, nil) }
        if method == "GET", path == "/v1/applets/installed" { return (.appletsRead, nil) }
        guard p.count >= 4, p[1] == "applets", p[2] == "installed", p[3].range(of: "^[a-z][a-z0-9-]{0,63}$", options: .regularExpression) != nil else { return nil }
        if p.count == 4, method == "DELETE" { return (.appletsManage, p[3]) }
        if p.count == 5, ["update", "pause"].contains(p[4]), method == "POST" { return (.appletsManage, p[3]) }
        if p.count == 5, p[4] == "batches", method == "GET" { return (.appletsRead, p[3]) }
        if p.count == 7, p[4] == "batches", p[6] == "start", method == "POST" { return (.workflowsRun, p[3]) }
        if p.count == 6, p[4] == "batches", method == "DELETE" { return (.workflowsRun, p[3]) }
        if p.count == 5, p[4] == "retry", method == "POST" { return (.workflowsRun, p[3]) }
        if p.count == 5, p[4] == "data", method == "GET" { return (.appletsRead, p[3]) }
        if p.count == 6, p[4] == "data", p[5] == "export", method == "GET" { return (.appletsRead, p[3]) }
        if p.count == 5, p[4] == "photos", method == "POST" { return (.appletsMediaWrite, p[3]) }
        if p.count == 6, p[4] == "photos", p[5].range(of: "^[a-z0-9]{1,40}$", options: .regularExpression) != nil, method == "GET" { return (.appletsMediaRead, p[3]) }
        if p.count == 4, method == "GET" { return (.appletsRead, p[3]) }
        if p.count == 7, p[4] == "tables", p[6] == "rows", method == "GET" { return (.appletsRead, p[3]) }
        if p.count == 7, p[4] == "tables", p[6] == "rows", ["POST", "DELETE"].contains(method) { return (.appletsWrite, p[3]) }
        if p.count == 8, p[4] == "tables", p[6] == "rows", ["PATCH", "DELETE"].contains(method) { return (.appletsWrite, p[3]) }
        if p.count == 7, p[4] == "flows", p[6] == "run", method == "POST" { return (.workflowsRun, p[3]) }
        return nil
    }
}
public struct HostJob {
    public var id: String
    public var tailnetSource: String?
    public var sealedRequestID: String?
    public var sequence: Int64
    public var agentID: String
    public var generation: String
    public var deviceID: String
    public var mode: String
    public var deadline: Int64
    public var createdAt: Int64
    public var request: HostRequest
    public init(id: String, sequence: Int64, agentID: String, generation: String, deviceID: String, mode: String = "connector", deadline: Int64, createdAt: Int64, request: HostRequest, sealedRequestID: String? = nil, tailnetSource: String? = nil) {
        self.tailnetSource = tailnetSource; self.sealedRequestID = sealedRequestID; self.id = id; self.sequence = sequence; self.agentID = agentID; self.generation = generation; self.deviceID = deviceID; self.mode = mode; self.deadline = deadline; self.createdAt = createdAt; self.request = request
    }
}
/// The only authority store for Apple host callers. Saved view pairings are never imported here.
/// Constructing it does not enroll, listen, connect, or grant anything.
public actor HostAuthority {
    private let store: AppletStore
    private var callbackPruner: ((String) async throws -> Void)?
    public let deviceID: String
    private var agents: [String: HostAgent] = [:]
    private var loaded = false
    private var loading: Task<[HostAgent], Error>?
    private var mutating = false
    private let allowsBackgroundAwake: Bool
    public nonisolated var requiresForeground: Bool { !allowsBackgroundAwake }
    private var directEnabled = false
    private var ownerDirectEnabled = false
    private var callbackExecutionEnabled = false
    private var ownerDirectBindingRevision: UInt64 = 0
    private let directAppletExecution = HostExecutionFence()
    private let relayFeedbackExecution = HostExecutionFence()
    private var enabled = false
    private var awake = true
    private var foreground = true
    private var backgroundReadUntil: Int64?
    private var rates: [String: (Int64, Int)] = [:]
    private var epoch: UInt64 = 0
    public init(store: AppletStore, deviceID: String, allowsBackgroundAwake: Bool = false) { self.store = store; self.deviceID = deviceID; self.allowsBackgroundAwake = allowsBackgroundAwake }
    public func setCallbackPruner(_ pruner: @escaping (String) async throws -> Void) { callbackPruner = pruner }
    public func reconcileCallbackPrunes() async {
        guard let callbackPruner, let ids = try? await store.pendingCallbackPrunes() else { return }
        for id in ids {
            do { try await callbackPruner(id); try await store.finishCallbackPrune(id) }
            catch { /* Keep the durable marker for a later retry. */ }
        }
    }
    private func load() async throws {
        guard !loaded else { return }
        if loading == nil { let store = self.store; loading = Task {
            guard let text = try await store.state("host-agents-v1") else { return [] }
            return try JSONDecoder().decode([HostAgent].self, from: Data(text.utf8))
        } }
        let values: [HostAgent]
        do { values = try await loading!.value } catch { loading = nil; throw error }
        guard !loaded else { return }
        guard Set(values.map(\.id)).count == values.count else { throw DeviceAPIError(500, "invalid_agent_store", "Agent storage needs repair.") }
        agents = Dictionary(uniqueKeysWithValues: values.map { ($0.id, $0) })
        loaded = true
    }
    private func save(_ values: [String: HostAgent], removingTransfersFor owner: String? = nil, consumingSatelliteClaim expected: String? = nil,
                      permit: HostMutationPermit? = nil) async throws {
        let text = String(decoding: try JSONEncoder().encode(Array(values.values)), as: UTF8.self)
        try await store.saveHostAgents(text, removingTransfersFor: owner, consumingSatelliteClaim: expected, permit: permit)
    }
    public func list() async throws -> [HostAgent] { try await load(); return agents.values.sorted { $0.name < $1.name } }
    public func buildInstructionRecipients(now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) async throws -> [HostAgent] {
        try await load()
        let active = try await store.recentAgentOwners(now: now)
        guard enabled, awake, foreground || allowsBackgroundAwake else { return [] }
        return active.compactMap { owner in
            agents.values.first { $0.eventOwner == owner && !$0.paused && $0.allows(.interactionsRead) }
        }
    }

    public func queueBuildInstructions(for agent: HostAgent, prompt: String, instructions: URL) async throws {
        let recipients = try await buildInstructionRecipients()
        guard recipients.contains(where: { $0.eventOwner == agent.eventOwner }),
              enabled, awake, foreground || allowsBackgroundAwake,
              let current = agents[agent.id], current == agent, !current.paused else {
            throw DeviceAPIError(409, "agent_unavailable", "No active agent available")
        }
        try await store.queueBuildInstructions(owner: agent.eventOwner, prompt: prompt, instructions: instructions)
    }

    private func relaySlots(at now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> Int {
        agents.values.filter(\.usesRelay).reduce(0) { count, agent in
            count + 1 + (agent.viewer == true && ((agent.viewerClaimUntil ?? 0) > now || (agent.viewerRelayReceipt?.until ?? 0) > now) ? 1 : 0)
        }
    }
    private func hasTokenHash(_ hash: String, except id: String? = nil) -> Bool {
        agents.values.contains { $0.id != id && ($0.tokenHash == hash || $0.viewerRelayReceipt?.tokenHash == hash) }
    }
    func relayPublication(now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) async throws -> (agents: [[String: Any]], refreshAt: Int64?) {
        let active = try await list().filter { !$0.paused && $0.usesRelay }
        var wire = active.map(\.announcement), refreshAt: Int64?
        for agent in active {
            guard agent.viewer == true, let receipt = agent.viewerRelayReceipt, receipt.until > now else { continue }
            // Keep only the exact old id/generation available for the relay's claim
            // result polling. Host admission refuses all data reads with this alias.
            wire.append(["id": agent.id, "generation": receipt.generation, "mode": "connector", "token_hash": receipt.tokenHash, "portal": true])
            refreshAt = min(refreshAt ?? receipt.until, receipt.until)
        }
        guard wire.count <= 16 else { throw DeviceAPIError(409, "relay_slot_limit", "The relay supports at most 16 credential slots.") }
        return (wire, refreshAt)
    }
    private func validateGrants(_ name: String, _ operations: Set<HostOperation>, _ applets: Set<String>, _ peer: String?) throws {
        guard !operations.isEmpty, !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, name.count <= 80,
              applets.allSatisfy({ $0.range(of: "^[a-z][a-z0-9-]{0,63}$", options: .regularExpression) != nil }),
              (operations.contains(.administrator) || operations.contains(.appletsManage) || operations.isDisjoint(with: [.appletsRead, .appletsWrite, .appletsMediaRead, .appletsMediaWrite]) || !applets.isEmpty),
              (operations.contains(.administrator) || !operations.contains(.workflowsRun) || !applets.isEmpty) else { throw DeviceAPIError(422, "explicit_grants_required", "Choose named operations and applets for this agent.") }
        if let peer { guard let bytes = Data(base64Encoded: peer), (try? P256.KeyAgreement.PublicKey(x963Representation: bytes)) != nil else { throw DeviceAPIError(422, "invalid_peer", "Use the agent’s P-256 public key.") } }
    }

    private func checkedControlAgentRename(_ raw: String, current: HostAgent) throws -> String {
        if raw == current.name { return current.name }
        let name = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if name == current.name { return current.name }
        guard name.range(of: "^[A-Za-z0-9][A-Za-z0-9 _-]{0,23}$", options: .regularExpression) != nil else {
            throw DeviceAPIError(422, "invalid_agent_name", "Use letters, numbers, spaces, hyphens or underscores (1–24). Start with a letter or number.")
        }
        let folded = name.lowercased()
        guard !agents.values.contains(where: {
            $0.id != current.id && $0.viewer != true && $0.tailnetPeer == nil &&
                $0.name.utf8.allSatisfy { $0 < 128 } &&
                $0.name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() == folded
        }) else {
            throw DeviceAPIError(409, "agent_name_in_use", "A control agent already uses that name.")
        }
        return name
    }

    private func checkedOwnerNotes(_ raw: String) throws -> String? {
        guard raw.utf16.count <= 200 else {
            throw DeviceAPIError(422, "invalid_agent_notes", "Agent notes can contain up to 200 UTF-16 code units.")
        }
        return raw.isEmpty ? nil : raw
    }

    /// Update the owner-editable fields while retaining the live transport and credential.
    public func editControlAgent(_ original: HostAgent, name: String, notes: String,
                                 operations: Set<HostOperation>, applets: Set<String>) async throws -> HostAgent {
        guard !mutating else { throw DeviceAPIError(409, "agent_store_busy", "Another agent change is being saved.") }
        mutating = true
        defer { mutating = false }
        try await load()
        guard let current = agents[original.id] else { throw DeviceAPIError(404, "agent_not_found", "This agent has been removed.") }
        guard current.viewer != true, current.tailnetPeer == nil else {
            throw DeviceAPIError(403, "viewer_read_only", "Change viewer links through owner pairing controls.")
        }
        guard current.generation == original.generation, current.name == original.name, current.notes == original.notes,
              current.operations == original.operations, current.applets == original.applets else {
            throw DeviceAPIError(409, "agent_changed", "This agent changed while you were editing. Reopen its details and try again.")
        }
        let title = try checkedControlAgentRename(name, current: current)
        try validateGrants(title, operations, applets, current.peer)
        guard !operations.contains(.feedback),
              current.ownerDirectPeers == nil || operations.isSubset(of: OwnerDirectHostService.supportedGrants) else {
            throw DeviceAPIError(422, "invalid_agent_grants", "Choose grants supported by this agent’s connection.")
        }
        let ownerNotes = try checkedOwnerNotes(notes)
        var updated = current
        updated.name = title
        updated.notes = ownerNotes
        updated.operations = operations
        updated.applets = applets
        guard updated != current else { return current }
        try Task.checkCancellation()
        var next = agents
        next[current.id] = updated
        if current.operations != operations || current.applets != applets {
            agents[current.id]?.paused = true
            epoch &+= 1
        }
        do { try await save(next) }
        catch { agents[current.id] = current; epoch &+= 1; throw error }
        agents = next
        epoch &+= 1
        return updated
    }

    /// Serialize authorization and mutation with local owner edits. Neither credential
    /// material nor a reusable bearer enters the remote result or idempotency cache.
    public func administer(_ request: HostRequest, caller: HostAgent) async throws -> HostResponse {
        try await administer(request, caller: caller, beforeCredentialCommit: nil)
    }

    func administer(_ request: HostRequest, caller: HostAgent,
                    beforeCredentialCommit: (@Sendable () async throws -> Void)?) async throws -> HostResponse {
        guard !mutating else { throw DeviceAPIError(409, "agent_store_busy", "Another agent change is being saved.") }
        mutating = true
        defer { mutating = false }
        let ownProfile = request.path == "/v1/agents/me"
        guard let operation = request.route?.0, operation == .administrator || ownProfile else { throw DeviceAPIError(403, "agent_not_allowed", "Agent administration requires superpowers.") }
        try await authorizeOngoing(caller, operation: operation)
        guard request.query.isEmpty else { throw DeviceAPIError(422, "invalid_parameter", "Use an agent management route without query parameters.") }
        func describe(_ agent: HostAgent) -> [String: Any] {
            var value: [String: Any] = ["id": agent.id, "name": agent.name, "generation": agent.generation, "mode": agent.mode,
                "operations": agent.operations.map(\.rawValue).sorted(), "applets": agent.applets.sorted(), "paused": agent.paused]
            if request.method == "GET", !ownProfile { value["notes"] = agent.notes ?? "" }
            if let peers = agent.ownerDirectPeers {
                value["owner_direct_peers"] = peers.map(\.value)
                value["owner_direct_peer_alerts"] = (agent.ownerDirectPeerAlerts ?? []).map {
                    ["address": $0.address.value, "at": ISO8601DateFormatter().string(from: $0.at), "refused": $0.refused]
                }
            }
            return value
        }
        let body = request.body
        let invalid = DeviceAPIError(422, "invalid_parameter", "Supply the documented agent fields and explicit grants.")
        if request.method == "GET" {
            guard body.isEmpty else { throw invalid }
            if ownProfile, let current = agents[caller.id] { return try .json(describe(current)) }
            return try .json(["agents": agents.values.sorted { $0.id < $1.id }.map(describe)])
        }
        let path = request.path.split(separator: "/")
        if path.count == 4, path[3] == "rotate" {
            let id = path[2].lowercased()
            guard id != caller.id else {
                throw DeviceAPIError(403, "agent_not_allowed", "Rotate a different paired control agent.")
            }
            guard Set(body.keys) == ["token_hash"], let hash = body["token_hash"] as? String else { throw invalid }
            let rotated = try await replaceCredential(id, hash: hash, requester: caller, beforeCommit: beforeCredentialCommit)
            return try .json(describe(rotated))
        }
        let creating = request.method == "POST"
        var next = agents
        let id = ownProfile ? caller.id : creating ? UUID().uuidString.lowercased() : String(request.path.split(separator: "/").last!).lowercased()
        let previous = agents[id]
        guard creating || previous != nil else { throw DeviceAPIError(404, "agent_not_found", "This agent has been removed.") }
        var response: HostResponse
        if request.method == "DELETE" {
            guard body.isEmpty, id != caller.id else { throw invalid }
            next.removeValue(forKey: id)
            response = try .json(["removed": true])
        } else {
            var fields: Set<String> = ["name", "operations", "applets", "paused"]
            if !creating && !ownProfile && id != caller.id { fields.insert("notes") }
            if previous?.ownerDirectPeers != nil && !ownProfile {
                fields.formUnion(["owner_direct_peers", "dismiss_owner_direct_peer_alert"])
            }
            guard !body.isEmpty, Set(body.keys).isSubset(of: creating ? fields.union(["token_hash", "peer"]) : fields) else { throw invalid }
            var agent: HostAgent
            if let previous { agent = previous } else {
                guard agents.count < 16, relaySlots() < 16, let hash = body["token_hash"] as? String, hash.utf8.count == 64,
                      hash.allSatisfy({ "0123456789abcdef".contains($0) }), !hasTokenHash(hash),
                      try await store.retiredHostCredential(hash) == nil,
                      body["peer"] == nil || body["peer"] is String else { throw invalid }
                agent = HostAgent(id: id, name: "", generation: UUID().uuidString.lowercased(), tokenHash: hash,
                                  operations: [], applets: [], paused: false, peer: body["peer"] as? String)
            }
            if previous?.viewer == true || previous?.tailnetPeer != nil { throw DeviceAPIError(403, "viewer_read_only", "Change viewer links through owner pairing controls.") }
            if let value = body["name"] {
                guard let name = value as? String else { throw invalid }
                if let previous { agent.name = try checkedControlAgentRename(name, current: previous) }
                else { agent.name = name }
            }
            if let value = body["notes"] {
                guard let notes = value as? String else { throw invalid }
                agent.notes = try checkedOwnerNotes(notes)
            }
            if let value = body["operations"] {
                guard let names = value as? [String] else { throw invalid }
                agent.operations = Set(try names.map { guard let op = HostOperation(rawValue: $0), op != .feedback else { throw invalid }; return op })
            }
            if let value = body["applets"] { guard let names = value as? [String] else { throw invalid }; agent.applets = Set(names) }
            if let value = body["owner_direct_peers"] {
                guard let raw = value as? [String], raw.count <= Self.maximumOwnerDirectPeers else { throw invalid }
                let peers = raw.compactMap(TailnetAddress.init)
                guard peers.count == raw.count, Set(peers).count == peers.count else { throw invalid }
                agent.ownerDirectPeers = peers
                agent.ownerDirectPeerAlerts?.removeAll { peers.contains($0.address) }
            }
            if let value = body["dismiss_owner_direct_peer_alert"] {
                guard let raw = value as? String, let address = TailnetAddress(raw) else { throw invalid }
                agent.ownerDirectPeerAlerts?.removeAll { $0.address == address }
            }
            if let value = body["paused"] {
                guard let number = value as? NSNumber, CFGetTypeID(number) == CFBooleanGetTypeID() else { throw invalid }
                agent.paused = number.boolValue
            }
            try validateGrants(agent.name, agent.operations, agent.applets, agent.peer)
            if agent.operations.contains(.feedbackFixer), previous?.operations.contains(.feedbackFixer) != true,
               creating || id == caller.id {
                throw DeviceAPIError(403, "agent_not_allowed", "Fixer Mode can be delegated to a different paired control agent.")
            }
            if ownProfile, let previous {
                guard agent.operations.isSubset(of: previous.operations), agent.applets.isSubset(of: previous.applets) else {
                    throw DeviceAPIError(403, "agent_not_allowed", "Only an administrator can expand grants.")
                }
            }
            guard id != caller.id || (!agent.paused && agent.allows(operation)) else { throw invalid }
            next[id] = agent
            response = try .json(describe(agent), status: creating ? 201 : 200)
        }
        let notesPermit: HostMutationPermit?
        if body["notes"] != nil {
            try requireOngoing(caller, operation: operation)
            let fence = caller.usesRelay ? relayFeedbackExecution : directAppletExecution
            notesPermit = HostMutationPermit(fence: fence, invalidatedMessage: "The host changed before the agent could be edited.")
        } else { notesPermit = nil }
        defer { notesPermit?.close() }
        // Deny in-flight work against the old grants while persisting their replacement.
        if previous != nil && id != caller.id { agents[id]?.paused = true; epoch &+= 1 }
        do { try await save(next, removingTransfersFor: request.method == "DELETE" ? previous?.eventOwner : nil, permit: notesPermit) }
        catch { agents[id] = previous; epoch &+= 1; throw error }
        agents = next
        epoch &+= 1
        if request.method == "DELETE", previous != nil { Task { await self.reconcileCallbackPrunes() } }
        return response
    }

    public func create(name: String, operations: Set<HostOperation>, applets: Set<String>, peer: String? = nil) async throws -> (HostAgent, String) {
        try await create(name: name, operations: operations, applets: applets, peer: peer, viewerUntil: nil)
    }
    public func createViewer(name: String, now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) async throws -> (HostAgent, String) {
        try await create(name: name, operations: [.deviceRead], applets: [], peer: nil, viewerUntil: now + 86_400_000)
    }
    private func create(name: String, operations: Set<HostOperation>, applets: Set<String>, peer: String?, viewerUntil: Int64?, directPeer: TailnetAddress? = nil, ownerDirect: Bool = false) async throws -> (HostAgent, String) {
        guard !mutating else { throw DeviceAPIError(409, "agent_store_busy", "Another agent change is being saved.") }
        mutating = true; defer { mutating = false }
        try await load()
        guard agents.count < 16 else { throw DeviceAPIError(409, "agent_limit", "At most 16 agents may be configured.") }
        if directPeer == nil && !ownerDirect {
            guard relaySlots() + (viewerUntil == nil ? 1 : 2) <= 16 else { throw DeviceAPIError(409, "relay_slot_limit", "No relay credential slots are available. Remove an unused link or agent first.") }
        }
        try validateGrants(name, operations, applets, peer)
        let token = try Self.makeToken(relay: directPeer == nil && !ownerDirect)
        let hash = PairingClaim.tokenHash(token)
        var agent = HostAgent(id: UUID().uuidString.lowercased(), name: name, generation: UUID().uuidString.lowercased(), tokenHash: hash, operations: operations, applets: applets, paused: false, peer: peer)
        if let viewerUntil { agent.viewer = true; agent.viewerClaimUntil = viewerUntil }
        if ownerDirect { agent.ownerDirectPeers = [] }
        if let directPeer { agent.tailnetPeer = directPeer.value; agent.satelliteRole = "return" }
        var next = agents; next[agent.id] = agent
        try await save(next); agents = next
        epoch &+= 1
        return (agent, token)
    }
    private static func makeToken(relay: Bool) throws -> String {
        var random = Data(count: 32)
        guard random.withUnsafeMutableBytes({ SecRandomCopyBytes(kSecRandomDefault, 32, $0.baseAddress!) }) == errSecSuccess else {
            throw DeviceAPIError(503, "random_unavailable", "Could not create an agent token.")
        }
        return (relay ? "rly_" : "brg_") + random.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }

    public func rotateControlKey(_ id: String) async throws -> (HostAgent, String) {
        guard !mutating else { throw DeviceAPIError(409, "agent_store_busy", "Another agent change is being saved.") }
        mutating = true; defer { mutating = false }
        try await load()
        guard let current = agents[id] else { throw DeviceAPIError(404, "agent_not_found", "This agent has been removed.") }
        let token = try Self.makeToken(relay: current.usesRelay)
        return (try await replaceCredential(id, hash: PairingClaim.tokenHash(token)), token)
    }

    private func replaceCredential(_ id: String, hash: String, requester: HostAgent? = nil,
                                   beforeCommit: (@Sendable () async throws -> Void)? = nil) async throws -> HostAgent {
        guard let before = agents[id] else { throw DeviceAPIError(404, "agent_not_found", "This agent has been removed.") }
        guard before.viewer != true, before.tailnetPeer == nil else {
            throw DeviceAPIError(403, "agent_not_allowed", "Rotate control keys from the Control agents list.")
        }
        guard hash.utf8.count == 64, hash.allSatisfy({ "0123456789abcdef".contains($0) }), !hasTokenHash(hash),
              try await store.retiredHostCredential(hash) == nil else {
            throw DeviceAPIError(422, "invalid_token_hash", "Supply the hash of a fresh agent key.")
        }
        let permit: HostMutationPermit?
        if let requester {
            try requireOngoing(requester, operation: .administrator)
            let fence = requester.usesRelay ? relayFeedbackExecution : directAppletExecution
            permit = HostMutationPermit(fence: fence, invalidatedMessage: "The host changed before the key could be rotated.")
        } else { permit = nil }
        defer { permit?.close() }
        try Task.checkCancellation()
        var rotated = before
        rotated.tokenHash = hash
        rotated.generation = UUID().uuidString.lowercased()
        if rotated.ownerDirectPeers != nil { rotated.ownerDirectPeers = []; rotated.ownerDirectPeerAlerts = [] }
        var next = agents; next[id] = rotated
        agents[id]?.paused = true
        epoch &+= 1
        do {
            try await beforeCommit?()
            try await save(next, permit: permit)
        } catch { agents[id] = before; epoch &+= 1; throw error }
        agents = next
        epoch &+= 1
        return rotated
    }

    /// Local owner control also works before connecting the host to the relay.
    public func setAdministrator(_ id: String, enabled: Bool) async throws {
        try await setOwnerGrant(.administrator, agentID: id, enabled: enabled)
    }

    public func setFeedbackFixer(_ id: String, enabled: Bool) async throws {
        try await setOwnerGrant(.feedbackFixer, agentID: id, enabled: enabled)
    }

    private func setOwnerGrant(_ grant: HostOperation, agentID id: String, enabled: Bool) async throws {
        guard !mutating else { throw DeviceAPIError(409, "agent_store_busy", "Another agent change is being saved.") }
        mutating = true
        defer { mutating = false }
        try await load()
        guard let previous = agents[id] else { throw DeviceAPIError(404, "agent_not_found", "This agent has been removed.") }
        guard previous.viewer != true, previous.tailnetPeer == nil else { throw DeviceAPIError(403, "viewer_read_only", "A viewer link cannot become a control agent.") }
        var next = agents
        if enabled { next[id]?.operations.insert(grant) }
        else {
            next[id]?.operations.remove(grant)
            agents[id]?.operations.remove(grant)
            epoch &+= 1
        }
        do { try await save(next) } catch { agents[id] = previous; epoch &+= 1; throw error }
        agents = next
        epoch &+= 1
    }

    public func pause(_ id: String, paused: Bool) async throws {
        guard !mutating else { throw DeviceAPIError(409, "agent_store_busy", "Another agent change is being saved.") }
        mutating = true; defer { mutating = false }
        try await load(); guard let before = agents[id] else { throw DeviceAPIError(404, "agent_not_found", "This agent has been removed.") }
        var next = agents; next[id]?.paused = paused
        if paused { agents[id]?.paused = true; epoch &+= 1 }
        do { try await save(next); agents = next } catch { agents[id] = before; epoch &+= 1; throw error }; epoch &+= 1
    }
    public func remove(_ id: String) async throws {
        guard !mutating else { throw DeviceAPIError(409, "agent_store_busy", "Another agent change is being saved.") }
        mutating = true; defer { mutating = false }
        try await load(); let before = agents.removeValue(forKey: id)
        epoch &+= 1
        do { try await save(agents, removingTransfersFor: before?.eventOwner) } catch { agents[id] = before; epoch &+= 1; throw error }
        if before != nil { Task { await self.reconcileCallbackPrunes() } }
    }
    /// Connection changes cannot override platform foreground/sleep facts.
    public func setConnected(_ enabled: Bool) {
        self.enabled = enabled
        refreshRelayFeedbackExecution()
        epoch &+= 1
    }
    public func setBackgroundReadLease(until: Int64?, now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) throws {
        guard until == nil || (until! > now && until! - now <= 20_000) else { throw DeviceAPIError(422, "invalid_lease", "Background read leases last at most twenty seconds.") }
        backgroundReadUntil = until; epoch &+= 1
    }
    public func setState(enabled: Bool, awake: Bool, foreground: Bool) {
        self.enabled = enabled; self.awake = awake; self.foreground = foreground
        refreshRelayFeedbackExecution()
        refreshDirectAppletExecution()
        epoch &+= 1
    }
    private func refreshRelayFeedbackExecution() {
        relayFeedbackExecution.update(enabled && awake && (foreground || allowsBackgroundAwake))
    }
    public func agent(_ id: String) async throws -> HostAgent? { try await load(); return agents[id] }
    public func currentEpoch() -> UInt64 { epoch }
    public func validate(_ job: HostJob, now: Int64 = Int64(Date().timeIntervalSince1970 * 1000), requireFreshCapture: Bool = false) async throws -> HostAgent {
        try await load()
        let ownerDirect = job.mode == OwnerDirectHostService.mode
        let relay = job.mode == "connector" || job.mode == "sealed"
        let transportEnabled = ownerDirect ? ownerDirectEnabled : relay ? enabled : directEnabled
        guard transportEnabled, awake, foreground || allowsBackgroundAwake || relay && (backgroundReadUntil ?? 0) > now else { throw DeviceAPIError(409, "foreground_required", "Keep this Bridgit host open and awake.") }
        if !foreground, !allowsBackgroundAwake {
            guard let operation = job.request.route?.0, [.deviceRead, .appletsRead, .appletsMediaRead, .interactionsRead, .configurationRead].contains(operation) else { throw DeviceAPIError(409, "foreground_required", "Background wake-ups only permit bounded reads. Open Bridgit for this action.") }
        }
        guard job.deviceID == deviceID, let agent = agents[job.agentID], job.mode == agent.mode, !agent.paused,
              agent.generation == job.generation || agent.acceptsReceiptGeneration(job.generation, now: now) && job.request.method == "POST" && job.request.path == HostViewerPairing.claimPath else { throw DeviceAPIError(403, "agent_revoked", "This job no longer has host authority.") }
        if ownerDirect {
            try verifyOwnerDirectSource(job, agent: agent)
        } else if job.mode == "direct" {
            guard let source = job.tailnetSource.flatMap(TailnetAddress.init), source.value == agent.tailnetPeer, agent.satelliteRole == "hub", SatelliteAuthorityPolicy.hubMayCall(job.request) else {
                throw DeviceAPIError(403, "satellite_scope", "This key is limited to its Tailscale peer and selected satellite services.")
            }
        } else if job.tailnetSource != nil { throw DeviceAPIError(403, "invalid_transport", "A relay job cannot assert a direct peer.") }
        let timeout = job.timeoutMilliseconds(for: agent)
        guard job.deadline > now, job.deadline - now <= timeout, job.createdAt <= now + 1000, job.createdAt > now - timeout else { throw DeviceAPIError(409, "deadline_expired", "This job has expired or has an invalid timestamp.") }
        guard let (operation, applet) = job.request.route else { throw DeviceAPIError(501, "unsupported_operation", "This Apple build does not implement this operation.") }
        if requireFreshCapture && ([.cameraSnapshot, .microphoneRecord].contains(operation) || operation.isComputerUse) {
            guard job.createdAt >= now - 5000 else { throw DeviceAPIError(409, "capture_stale", "A capture request must be no more than five seconds old.") }
        }
        if agent.viewer == true {
            guard HostViewerPairing.permits(job.request) else { throw DeviceAPIError(403, "viewer_read_only", "This viewer link cannot control the host.") }
            if agent.viewerClaimUntil != nil {
                guard job.request.path == HostViewerPairing.claimPath else { throw DeviceAPIError(403, "claim_required", "Claim this one-time viewer link before reading.") }
            }
        }
        guard agent.allows(operation), applet == nil || agent.allowsApplet(applet!, operation: operation) else { throw DeviceAPIError(403, "agent_not_allowed", "The owner has not granted this operation on this applet.") }
        var authorized = agent
        if ownerDirect { authorized.ownerDirectRequestPeer = job.tailnetSource.flatMap(TailnetAddress.init) }
        return authorized
    }
    /// Accepted async batches outlive their RPC deadline, never their current grant
    /// or the platform execution context. Applet principals cannot create this grant.
    public func authorizeOngoing(_ expected: HostAgent, operation: HostOperation, applet: String? = nil) async throws {
        try await load()
        try requireOngoing(expected, operation: operation, applet: applet)
    }
    private func requireOngoing(_ expected: HostAgent, operation: HostOperation, applet: String? = nil) throws {
        let direct = expected.mode == OwnerDirectHostService.mode
        guard (direct ? ownerDirectEnabled : enabled), awake, foreground || allowsBackgroundAwake else { throw DeviceAPIError(409, "foreground_required", "Open and wake Bridgit for this work.") }
        guard let current = agents[expected.id], current.generation == expected.generation, current.mode == expected.mode,
              !current.paused else { throw DeviceAPIError(403, "agent_revoked", "This caller no longer has host access.") }
        if direct {
            guard let peer = expected.ownerDirectRequestPeer, current.ownerDirectPeers?.contains(peer) == true else { throw Self.wrongOwnerDirectPeer }
        }
        guard current.allows(operation), applet == nil || current.allowsApplet(applet!, operation: operation) else { throw DeviceAPIError(403, "agent_not_allowed", "This caller no longer has this operation on this applet.") }
    }
    /// Outbound callbacks follow owner host intent, independently of inbound relay/listener connectivity.
    public func setCallbackExecutionEnabled(_ enabled: Bool) { callbackExecutionEnabled = enabled }
    public func callbackExecutionAvailable() -> Bool { callbackExecutionEnabled && awake && (foreground || allowsBackgroundAwake) }
    func authorizeCallback(agentID: String, operation: HostOperation) async throws {
        try await load()
        guard let current = agents[agentID], current.viewer != true, !current.paused else {
            throw DeviceAPIError(403, "agent_revoked", "This control agent no longer has host access.")
        }
        guard current.allows(operation) else {
            throw DeviceAPIError(403, "agent_not_allowed", "This control agent no longer has this operation.")
        }
        guard callbackExecutionAvailable() else {
            throw DeviceAPIError(409, "foreground_required", "Callback delivery is temporarily unavailable.")
        }
    }
    /// Durable connection attempts outlive an RPC, never the current owner grant or host lifecycle.
    public func connectionDeliveryModes() -> Set<String> {
        guard awake, foreground || allowsBackgroundAwake else { return [] }
        var modes: Set<String> = enabled ? ["connector", "sealed"] : []
        if ownerDirectEnabled { modes.insert(OwnerDirectHostService.mode) }
        return modes
    }
    public func authorizeConnectionDelivery(agentID: String, generation: String, mode: String, sourcePeer: TailnetAddress? = nil) async throws {
        try await load()
        guard connectionDeliveryModes().contains(mode) else { throw DeviceAPIError(409, "foreground_required", "Open and wake Bridgit for this work.") }
        guard let current = agents[agentID], current.generation == generation, current.mode == mode, !current.paused else { throw DeviceAPIError(403, "agent_revoked", "This sender no longer has host access.") }
        if mode == OwnerDirectHostService.mode {
            guard let sourcePeer, current.ownerDirectPeers?.contains(sourcePeer) == true else {
                throw DeviceAPIError(403, "agent_revoked", "This direct sender’s original Tailscale device no longer has access.")
            }
        } else if sourcePeer != nil {
            throw DeviceAPIError(403, "agent_revoked", "This sender cannot assert a Tailscale device.")
        }
        guard current.allows(.connections) else { throw DeviceAPIError(403, "agent_not_allowed", "This sender no longer has connection delivery authority.") }
    }
    public func authorizeConnectionPhoto(agentID: String, generation: String, mode: String, sourcePeer: TailnetAddress? = nil, applet: String) async throws {
        try await authorizeConnectionDelivery(agentID: agentID, generation: generation, mode: mode, sourcePeer: sourcePeer)
        guard let current = agents[agentID], current.allows(.appletsMediaRead), current.allowsApplet(applet, operation: .appletsMediaRead) else {
            throw DeviceAPIError(403, "agent_not_allowed", "This sender no longer has media access to the queued photo's applet.")
        }
        guard (try? await store.package(applet)) != nil else { throw DeviceAPIError(403, "agent_not_allowed", "The queued photo's applet was removed.") }
    }
    /// Replay state is committed before dispatch. The transport also caps concurrent jobs at two.
    public func admit(_ job: HostJob, now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) async throws -> HostAgent {
        let admittedEpoch = epoch
        _ = try await validate(job, now: now, requireFreshCapture: true)
        let minute = now / 60_000, previous = rates[job.agentID]
        let count = previous?.0 == minute ? previous!.1 : 0
        guard count < 300 else { throw DeviceAPIError(429, "rate_limited", "At most 300 host requests per minute per agent.") }
        rates[job.agentID] = (minute, count + 1)
        // Direct HTTP receives a fresh server-owned UUID; action retries use the same
        // persisted idempotency contract. Only signed relay jobs carry caller sequences.
        let fresh: Bool
        if job.mode == "direct" || job.mode == OwnerDirectHostService.mode { fresh = UUID(uuidString: job.id) != nil }
        else { fresh = try await store.claimSequence(agent: job.agentID, generation: job.generation, sequence: job.sequence, now: now) }
        guard fresh else { throw DeviceAPIError(409, "replay", "This relay sequence was already used.") }
        guard admittedEpoch == epoch else { throw DeviceAPIError(403, "authority_changed", "Host authority changed while this job was being admitted.") }
        return try await validate(job, now: now)
    }
    /// Rotate the executable generation and token atomically. A bounded old generation
    /// keeps the actual relay claim receipt reachable but never authorizes device reads.
    public func claimViewer(_ job: HostJob, now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) async throws {
        guard !mutating else { throw DeviceAPIError(409, "agent_store_busy", "Another agent change is being saved.") }
        mutating = true; defer { mutating = false }
        let current = try await validate(job, now: now)
        guard current.viewer == true else { throw DeviceAPIError(403, "viewer_required", "Only a viewer pairing can be claimed.") }
        guard current.generation == job.generation, let until = current.viewerClaimUntil else { throw DeviceAPIError(409, "already_claimed", "This link was already claimed.") }
        guard until > now else { throw DeviceAPIError(410, "link_expired", "This viewer pairing link expired.") }
        guard relaySlots(at: now) <= 16 else { throw DeviceAPIError(409, "relay_slot_limit", "Remove an unused relay link before claiming this code.") }
        guard job.request.method == "POST", job.request.path == HostViewerPairing.claimPath,
              job.request.query.isEmpty, Set(job.request.body.keys) == ["token_hash"],
              let hash = job.request.body["token_hash"] as? String, hash.count == 64,
              hash.allSatisfy({ "0123456789abcdef".contains($0) }), hash != current.tokenHash,
              !hasTokenHash(hash, except: current.id) else {
            throw DeviceAPIError(400, "invalid_token_hash", "Supply a new SHA-256 token hash.")
        }
        var next = agents, claimed = current
        claimed.viewerRelayReceipt = ViewerRelayReceipt(generation: current.generation, tokenHash: current.tokenHash, until: job.deadline + 5000)
        claimed.tokenHash = hash; claimed.viewerClaimUntil = nil; claimed.generation = UUID().uuidString.lowercased()
        next[current.id] = claimed
        try await save(next)
        agents = next; epoch &+= 1
    }
    public func validateDelivery(_ job: HostJob, now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) async throws -> HostAgent {
        try await validate(job, now: now)
    }
    func pauseFromSatellite(_ caller: HostAgent, target: String, paused: Bool) async throws {
        guard !mutating else { throw DeviceAPIError(409, "agent_store_busy", "Another agent change is being saved.") }
        mutating = true; defer { mutating = false }
        try await load()
        guard directEnabled, awake, foreground || allowsBackgroundAwake,
              let current = agents[caller.id], current.satelliteRole == "return", current.generation == caller.generation, !current.paused,
              current.tailnetPeer == caller.tailnetPeer,
              let previous = agents[target], previous.viewer != true, previous.tailnetPeer == nil else { throw DeviceAPIError(403, "satellite_not_allowed", "This return link cannot manage that agent.") }
        var next = agents; next[target]?.paused = paused
        if paused { agents[target]?.paused = true; epoch &+= 1 }
        do { try await save(next); agents = next }
        catch { agents[target] = previous; epoch &+= 1; throw error }
        epoch &+= 1
    }
    func pauseSatelliteReturn(_ id: String, paused: Bool, records: String) async throws {
        guard !mutating else { throw DeviceAPIError(409, "agent_store_busy", "Another agent change is being saved.") }
        mutating = true; defer { mutating = false }
        try await load()
        guard let previous = agents[id], previous.satelliteRole == "return" else { throw DeviceAPIError(404, "satellite_return_missing", "This satellite return link was removed.") }
        var next = agents; next[id]?.paused = paused
        if paused { agents[id]?.paused = true; epoch &+= 1 }
        let encoded = String(decoding: try JSONEncoder().encode(Array(next.values)), as: UTF8.self)
        do { try await store.saveHostAgents(encoded, removingTransfersFor: nil, satelliteRecords: records); agents = next }
        catch { agents[id] = previous; epoch &+= 1; throw error }
        epoch &+= 1
    }
    public func createSatelliteReturn(name: String, peer: TailnetAddress) async throws -> (HostAgent, String) {
        guard directEnabled, awake, foreground || allowsBackgroundAwake else { throw DeviceAPIError(409, "foreground_required", "Start this hub’s Tailscale listener before creating a return link.") }
        return try await create(name: name, operations: [.deviceRead], applets: [], peer: nil, viewerUntil: nil, directPeer: peer)
    }
    public func checkOwnerExecution() throws {
        guard awake, foreground || allowsBackgroundAwake else { throw DeviceAPIError(409, "foreground_required", "Keep this Apple host open and awake for satellite calls.") }
    }
    public func setDirectEnabled(_ value: Bool) { directEnabled = value; epoch &+= 1 }
    public func beginSatellitePairing(address: TailnetAddress, port: UInt16, name: String, operations: Set<HostOperation>, now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) async throws -> SatellitePairingLink {
        guard !mutating else { throw DeviceAPIError(409, "agent_store_busy", "Another owner change is being saved.") }
        mutating = true; defer { mutating = false }
        guard directEnabled, awake, foreground || allowsBackgroundAwake else { throw DeviceAPIError(409, "foreground_required", "Start this device’s Tailscale listener and keep Bridgit open.") }
        guard operations.contains(.deviceRead), operations.isSubset(of: SatelliteAuthorityPolicy.hubOperations) else { throw DeviceAPIError(422, "satellite_grants", "Choose only implemented satellite services; control administration is never included.") }
        let claim = "sct_" + String(PairingClaim.newToken().dropFirst(4))
        let link = try SatellitePairingLink(address: address, port: port, claim: claim, name: name)
        let pending = PendingSatelliteClaim(hash: PairingClaim.tokenHash(claim), until: now + 900_000, name: link.name, operations: operations)
        try await store.setState(SatelliteAuthorityPolicy.claimKey, value: String(decoding: JSONEncoder().encode(pending), as: UTF8.self))
        return link
    }
    public func satelliteName() async throws -> String? { try await store.state("host-satellite-name-v1") }
    public func cancelSatellitePairing(claim: String? = nil) async throws {
        guard !mutating else { throw DeviceAPIError(409, "agent_store_busy", "Another owner change is being saved.") }
        mutating = true; defer { mutating = false }
        if let claim {
            guard let saved = try await store.state(SatelliteAuthorityPolicy.claimKey), let pending = try? JSONDecoder().decode(PendingSatelliteClaim.self, from: Data(saved.utf8)),
                  SatelliteAuthorityPolicy.equalHash(pending.hash, PairingClaim.tokenHash(claim)) else { return }
        }
        try await store.removeState(SatelliteAuthorityPolicy.claimKey)
    }
    public func claimSatellite(token: String, source: String, hubName: String?, now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) async throws -> SatelliteClaimed {
        guard !mutating else { throw DeviceAPIError(409, "agent_store_busy", "Another owner change is being saved.") }
        mutating = true; defer { mutating = false }
        try await load()
        guard directEnabled, awake, foreground || allowsBackgroundAwake else { throw DeviceAPIError(409, "foreground_required", "Keep the satellite open and awake.") }
        guard let address = TailnetAddress(source), SatellitePairingLink.validClaim(token),
              let saved = try await store.state(SatelliteAuthorityPolicy.claimKey), let pending = try? JSONDecoder().decode(PendingSatelliteClaim.self, from: Data(saved.utf8)), pending.until > now,
              SatelliteAuthorityPolicy.equalHash(pending.hash, PairingClaim.tokenHash(token)) else { throw DeviceAPIError(401, "unauthorized", "Wrong, expired or already used satellite code.") }
        guard agents.count < 16 else { throw DeviceAPIError(409, "agent_limit", "At most 16 host links and control agents may be configured.") }
        let safeName = (hubName ?? "Hub").unicodeScalars.filter { !CharacterSet.controlCharacters.contains($0) }.map(String.init).joined().trimmingCharacters(in: .whitespacesAndNewlines)
        let key = "brg_" + String(PairingClaim.newToken().dropFirst(4))
        var agent = HostAgent(id: UUID().uuidString.lowercased(), name: "Hub: " + String((safeName.isEmpty ? "Hub" : safeName).prefix(60)), generation: UUID().uuidString.lowercased(), tokenHash: PairingClaim.tokenHash(key), operations: pending.operations, applets: [], paused: false, peer: nil)
        agent.tailnetPeer = address.value; agent.satelliteRole = "hub"
        var next = agents; next[agent.id] = agent
        try await save(next, consumingSatelliteClaim: saved)
        agents = next; epoch &+= 1
        return SatelliteClaimed(agent: agent, key: key, name: pending.name)
    }
    func setOwnerDirectEnabled(_ value: Bool) {
        ownerDirectEnabled = value
        refreshDirectAppletExecution()
        epoch &+= 1
    }

    private func refreshDirectAppletExecution(bindingChanged: Bool = false) {
        directAppletExecution.update(ownerDirectEnabled && awake && (foreground || allowsBackgroundAwake),
                                     bindingChanged: bindingChanged)
    }

    /// Serialize only a prepared local maintained-store mutation against owner
    /// agent/peer changes. The permit checks execution again on the store actor.
    public func withDirectAppletMutation<T: Sendable>(_ requester: HostAgent, operation: HostOperation, applet: String,
        effect: (DirectAppletMutationPermit) async throws -> T) async throws -> T {
        guard requester.ownerDirectPeers != nil, [.appletsWrite, .appletsManage].contains(operation) else {
            throw DeviceAPIError(403, "agent_not_allowed", "This action needs a current direct applet grant.")
        }
        guard !mutating else { throw DeviceAPIError(409, "agent_store_busy", "Another agent change is being saved.") }
        mutating = true; defer { mutating = false }
        try await load()
        try requireOngoing(requester, operation: operation, applet: applet)
        let permit = DirectAppletMutationPermit(fence: directAppletExecution, invalidatedMessage: "The direct host changed before this applet change.")
        defer { permit.close() }
        return try await effect(permit)
    }

    public func withFeedbackMutation<T: Sendable>(_ requester: HostAgent, requiresFixer: Bool,
        effect: (HostAgent, HostMutationPermit) async throws -> T) async throws -> T {
        guard !mutating else { throw DeviceAPIError(409, "agent_store_busy", "Another agent change is being saved.") }
        mutating = true; defer { mutating = false }
        try await load()
        try requireOngoing(requester, operation: requiresFixer ? .feedbackFixer : .feedback)
        guard let current = agents[requester.id] else { throw CancellationError() }
        let fence = current.ownerDirectPeers == nil ? relayFeedbackExecution : directAppletExecution
        let permit = HostMutationPermit(fence: fence, invalidatedMessage: "The host changed before this report could be saved.")
        defer { permit.close() }
        return try await effect(current, permit)
    }

    func commitOwnerDirectBinding(revision: UInt64, enabled: Bool, requester: HostAgent? = nil) async throws {
        if let requester {
            try await load()
            try requireOngoing(requester, operation: .configurationWrite)
        }
        guard revision > ownerDirectBindingRevision else { throw CancellationError() }
        ownerDirectBindingRevision = revision
        if ownerDirectEnabled != enabled { setOwnerDirectEnabled(enabled) }
        else if enabled { refreshDirectAppletExecution(bindingChanged: true) }
    }

    public func createOwnerDirect(name: String, operations: Set<HostOperation>, applets: Set<String>) async throws -> (HostAgent, String) {
        try requireOwnerDirectExecution()
        return try await create(name: name, operations: operations, applets: applets, peer: nil, viewerUntil: nil, ownerDirect: true)
    }

    #if DEBUG
    /// Seed an already enrolled agent in an isolated native fixture with no active listener.
    public func createOwnerDirectFixture(name: String, operations: Set<HostOperation>,
                                         applets: Set<String>) async throws -> (HostAgent, String) {
        try await create(name: name, operations: operations, applets: applets, peer: nil, viewerUntil: nil, ownerDirect: true)
    }
    #endif

    private func requireOwnerDirectExecution() throws {
        guard ownerDirectEnabled, awake, foreground || allowsBackgroundAwake else {
            throw DeviceAPIError(409, "foreground_required", "Keep this direct Bridgit host open and awake.")
        }
    }

    private static let maximumOwnerDirectPeers = 4

    public func setOwnerDirectPeers(_ id: String, allow: TailnetAddress? = nil, unlock: Bool = false,
                                    dismiss: TailnetAddress? = nil) async throws {
        guard !mutating else { throw DeviceAPIError(409, "agent_store_busy", "Another agent change is being saved.") }
        mutating = true; defer { mutating = false }
        try await load()
        guard var agent = agents[id], var peers = agent.ownerDirectPeers else {
            throw DeviceAPIError(404, "agent_not_found", "This direct agent has been removed.")
        }
        if unlock { peers.removeAll() }
        if let allow {
            peers.removeAll { $0 == allow }
            peers.append(allow)
            peers = Array(peers.suffix(Self.maximumOwnerDirectPeers))
            agent.ownerDirectPeerAlerts?.removeAll { $0.address == allow }
        }
        if let dismiss { agent.ownerDirectPeerAlerts?.removeAll { $0.address == dismiss } }
        agent.ownerDirectPeers = peers
        var next = agents; next[id] = agent
        try await save(next)
        agents = next; epoch &+= 1
    }

    func authenticateOwnerDirect(key: String, source: String, now: Date = Date()) async throws -> HostAgent {
        let unauthorized = DeviceAPIError(401, "unauthorized", "Send this direct control agent’s bearer key.")
        guard let address = TailnetAddress(source), SatelliteAuthorityPolicy.validKey(key) else { throw unauthorized }
        try await load()
        try requireOwnerDirectExecution()
        let hash = PairingClaim.tokenHash(key)
        var authenticated: HostAgent?
        for agent in agents.values where agent.ownerDirectPeers != nil {
            if SatelliteAuthorityPolicy.equalHash(agent.tokenHash, hash) { authenticated = agent }
        }
        guard var agent = authenticated, let peers = agent.ownerDirectPeers else {
            if let retired = try await store.retiredHostCredential(hash, mode: OwnerDirectHostService.mode, at: now) { throw retired.rejection }
            throw unauthorized
        }
        guard !agent.paused else { throw DeviceAPIError(403, "agent_revoked", "The owner paused this control agent.") }
        if peers.contains(address) { return agent }
        guard !mutating else { throw DeviceAPIError(409, "agent_store_busy", "Try the direct connection again.") }
        mutating = true; defer { mutating = false }
        let allowed = peers.isEmpty ? true : try await !store.ownerDirectDeviceLock()
        if allowed { agent.ownerDirectPeers = Array((peers + [address]).suffix(Self.maximumOwnerDirectPeers)) }
        if !peers.isEmpty {
            let previous = agent.ownerDirectPeerAlerts ?? []
            if !previous.contains(where: { $0.address == address && $0.refused == !allowed }) {
                let alert = OwnerDirectPeerAlert(address: address, at: Date(), refused: !allowed)
                let updated = previous.filter { $0.address != address } + [alert]
                agent.ownerDirectPeerAlerts = Array(updated.suffix(Self.maximumOwnerDirectPeers))
            }
        }
        if agent != authenticated {
            var next = agents; next[agent.id] = agent
            try await save(next)
            agents = next; epoch &+= 1
        }
        try requireOwnerDirectExecution()
        guard allowed else { throw Self.wrongOwnerDirectPeer }
        return agent
    }

    private static var wrongOwnerDirectPeer: DeviceAPIError {
        .init(403, "wrong_device", "This key is locked to a different Tailscale device. Ask the owner to allow this device.")
    }

    private func verifyOwnerDirectSource(_ job: HostJob, agent: HostAgent) throws {
        guard let source = job.tailnetSource.flatMap(TailnetAddress.init), agent.ownerDirectPeers?.contains(source) == true else {
            throw Self.wrongOwnerDirectPeer
        }
        guard OwnerDirectHostService.permits(job.request) else {
            throw DeviceAPIError(501, "unsupported_operation", "This operation is not available on the direct host.")
        }
    }

    func validateOwnerDirectStream(_ job: HostJob) async throws -> HostAgent {
        try await load()
        try Task.checkCancellation()
        try requireOwnerDirectExecution()
        guard job.mode == OwnerDirectHostService.mode, job.deviceID == deviceID,
              let agent = agents[job.agentID], agent.generation == job.generation, !agent.paused,
              agent.allows(.log) else { throw DeviceAPIError(403, "agent_revoked", "This caller no longer has Activity access.") }
        try verifyOwnerDirectSource(job, agent: agent)
        return agent
    }

    public func authenticateDirect(key: String, source: String) async throws -> HostAgent {
        try await load()
        guard directEnabled, awake, foreground || allowsBackgroundAwake else { throw DeviceAPIError(409, "foreground_required", "Keep this satellite open and awake.") }
        guard let address = TailnetAddress(source), SatelliteAuthorityPolicy.validKey(key) else { throw DeviceAPIError(401, "unauthorized", "This Tailscale peer has no matching satellite key.") }
        let hash = PairingClaim.tokenHash(key)
        guard let agent = agents.values.first(where: { $0.tailnetPeer == address.value && ["hub", "return"].contains($0.satelliteRole ?? "") && SatelliteAuthorityPolicy.equalHash($0.tokenHash, hash) }) else { throw DeviceAPIError(401, "unauthorized", "This Tailscale peer has no matching satellite key.") }
        guard !agent.paused else { throw DeviceAPIError(403, "link_paused", "The owner paused this satellite link.") }
        return agent
    }

}
