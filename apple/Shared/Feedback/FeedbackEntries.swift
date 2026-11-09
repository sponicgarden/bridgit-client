import BridgitCore
import CoreFoundation
import SwiftUI
import UniformTypeIdentifiers
import ImageIO
#if os(macOS)
import AppKit
#else
import UIKit
import PhotosUI
#endif

private extension HostActivityEntry {
    var feedbackPreview: [String: String] {
        var fields = ["time": at.ISO8601Format(), "message": operation]
        if !isSetting { fields["agent"] = agent }
        return fields
    }
}

enum FeedbackKind: String, Codable, CaseIterable, Identifiable {
    case bug, feature
    var id: Self { self }
    var title: String { self == .bug ? "Report a bug" : "Request a feature" }
    var iconAsset: String { self == .bug ? "more_bug" : "more_feature" }
}

struct FeedbackMetadata {
    var version: String
    var appVersion: String? = nil
    var buildNumber: String? = nil
    var system: String
    var model: String?
    var revision: String?
    var configuration: String?
    @MainActor static var current: Self {
        #if os(iOS)
        let system = UIDevice.current.systemName + " " + UIDevice.current.systemVersion
        #else
        let system = "macOS " + ProcessInfo.processInfo.operatingSystemVersionString
        #endif
        return .init(version: AppleBuildDetails.version, appVersion: AppleBuildDetails.shortVersion,
                     buildNumber: AppleBuildDetails.buildNumber, system: system, model: AppleBuildDetails.deviceModel,
                     revision: AppleBuildDetails.revision, configuration: AppleBuildDetails.configuration)
    }
}

struct FeedbackReport: Codable, Identifiable {
    var id: Int64
    var kind: FeedbackKind
    var description: String
    var noAutofix: Bool
    var createdAt: Int64
    var localOnly: Bool?
    var remoteID: Int64?
    var uploadFailed: Bool?
    var images: [Data]?
    var log: [[String: String]]?
    var version: String?
    var appVersion: String?
    var buildNumber: String?
    var deviceModel: String?
    var buildRevision: String?
    var buildConfiguration: String?
    // Older records with no version remain unknown; retries must not borrow the current app's version.
    var submissionVersion: String {
        if let appVersion { return appVersion }
        // Prior records stored only a short version; the earlier full-display-only
        // shape does not prove a canonical version or raw build separately.
        guard let version, !version.contains(" (") else { return "" }
        return version
    }
    var submissionVersionCode: Int64? {
        guard let buildNumber, !buildNumber.isEmpty,
              buildNumber.utf8.allSatisfy({ (48...57).contains($0) }),
              let value = Int64(buildNumber), value <= 9_007_199_254_740_991 else { return nil }
        return value
    }
    var logEntryID: Int64?
    var attachedActivity: [HostActivityEntry]?
    var sourceScreen: String?
    var filingAgentID: String?
    var filingAgentName: String?
    var filingAgentContext: Data?
    var captureSource: String { filingAgentID == nil ? "form" : "agent" }
    var filedBy: String { filingAgentName ?? "owner" }
    var updatedDate: Date { moves?.last?.at ?? filedDate }
    var typedStatus: HostFeedbackStatus { HostFeedbackStatus(rawValue: status) ?? .new }
    var teamNote: String? {
        moves?.last { $0.note != nil && ($0.actor == .team || $0.actor == nil && $0.by == "Bridgit team") }?.note
    }

    var filedDate: Date { Date(timeIntervalSince1970: Double(createdAt) / 1000) }
    var teamReference: String? {
        remoteID.flatMap { $0 > 0 ? String(format: (filingAgentID == nil ? "M" : "A") + "%04lld", $0) : nil }
    }
    var reference: String { teamReference ?? (localOnly == true ? "Saved on this device" : "Not sent yet") }
    var saveAcknowledgement: String {
        if let teamReference { return "Thanks. It’s report " + teamReference + ", and the Bridgit team has it." }
        if localOnly == true { return "Saved on this device. Sending reports to the Bridgit team was turned off, so it has no report number." }
        return "Saved on this device and waiting to send. Check delivery in My reports."
    }
    var system: String?
    struct Move: Codable {
        let status: String
        let at: Date
        let note: String?
        var pendingID: Int64?
        var actor: HostFeedbackActor?
        var author: String?
        var kind: String?
        var by: String { author ?? (["confirmed", "reopened"].contains(status) ? "Owner" : "Bridgit team") }
        var apiHistory: [String: Any] {
            ["at": at.feedbackTimestamp, "kind": kind ?? "status", "status": status, "by": by, "note": note as Any? ?? NSNull()]
        }
    }
    var moves: [Move]?
    var status: String { moves?.last?.status ?? "new" }
    var awaitsOwner: Bool { typedStatus.allows(.confirmed, actor: .owner) }
    var countsAsFixed: Bool {
        if status == "confirmed" { return moves?.dropLast().last?.status == "fixed" }
        return status == "fixed"
    }
    static func label(_ status: String) -> String {
        switch status {
        case "seen": return "Seen by agent"
        case "in_progress": return "In progress"
        case "fixed": return "Fixed — check it"
        case "wont_fix": return "Won’t fix"
        case "duplicate": return "Duplicate"
        case "confirmed": return "Closed"
        case "reopened": return "Reopened"
        default: return "New"
        }
    }
    mutating func apply(_ change: FeedbackUpdates.Change) -> Bool {
        guard let destination = HostFeedbackStatus(rawValue: change.status), typedStatus.allows(destination, actor: .team) else { return false }
        var history = moves ?? []
        history.append(Move(status: change.status, at: Date(), note: change.team_note, actor: .team))
        moves = history
        return true
    }
}

@MainActor final class FeedbackReports: ObservableObject {
    static let shared: FeedbackReports = {
        #if os(macOS) && DEBUG
        if AppleFixtureWorkspace.enabled {
            let defaults = AppleFixtureWorkspace.defaults
            defaults.set(true, forKey: "feedback.uploadOff")
            let folder = FileManager.default.temporaryDirectory
                .appendingPathComponent("BridgitFeedbackFixtures")
                .appendingPathComponent(UUID().uuidString)
            return FeedbackReports(folder: folder, defaults: defaults)
        }
        #endif
        return FeedbackReports()
    }()
    var sendingEnabled: Bool { !defaults.bool(forKey: "feedback.uploadOff") }
    @Published private(set) var reports: [FeedbackReport] = []
    @Published private(set) var sending: Set<Int64> = []
    @Published var problem: String?
    private let defaults: UserDefaults
    private let folder: URL
    private let captureMetadata: @MainActor () -> FeedbackMetadata
    private let clientFactory: (@MainActor () throws -> InstallClient)?
    private lazy var teamKeys = FeedbackTeamKeys(folder: folder)
    func reconcileTeamKeys(_ agents: [HostAgent]) { teamKeys.reconcile(agents) }
    init(folder: URL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        .appendingPathComponent(Bundle.main.bundleIdentifier ?? "Bridgit").appendingPathComponent("Feedback"), defaults: UserDefaults = .standard,
        captureMetadata: (@MainActor () -> FeedbackMetadata)? = nil, clientFactory: (@MainActor () throws -> InstallClient)? = nil) {
        self.clientFactory = clientFactory
        self.captureMetadata = captureMetadata ?? { .current }
        self.defaults = defaults
        self.folder = folder
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            for file in try FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil)
            where file.pathExtension == "json" {
                do { reports.append(try JSONDecoder().decode(FeedbackReport.self, from: Data(contentsOf: file))) }
                catch { problem = "A saved report could not be read." }
            }
            reports.sort { $0.createdAt > $1.createdAt }
        } catch { problem = error.localizedDescription }
    }

    @Published private(set) var statusProblem: String?
    private var refreshing = false
    private var lastRefresh: Date?
    private struct Cursor: Codable { let time: Int64; let id: Int64? }
    private func client() throws -> InstallClient {
        if let clientFactory { return try clientFactory() }
        let signer = try DeviceSigner.loadOrCreate(tag: (Bundle.main.bundleIdentifier ?? "Bridgit") + ".install-signing.v1")
        return InstallClient(signer: signer, outboundAccess: DeviceOutboundPolicy.access)
    }

    func refresh(fetch: ((Int64, Int64?) async throws -> FeedbackUpdates)? = nil) async {
        guard !refreshing, reports.contains(where: { $0.remoteID != nil && $0.localOnly != true }),
              !defaults.bool(forKey: "feedback.uploadOff"),
              lastRefresh.map({ Date().timeIntervalSince($0) >= 60 }) ?? true else { return }
        refreshing = true
        defer { refreshing = false }
        let cursorFile = folder.appendingPathComponent("team.cursor")
        do {
            var cursor = FileManager.default.fileExists(atPath: cursorFile.path)
                ? try JSONDecoder().decode(Cursor.self, from: Data(contentsOf: cursorFile)) : Cursor(time: 0, id: nil)
            for _ in 0..<5 {
                let page: FeedbackUpdates
                if let fetch { page = try await fetch(cursor.time, cursor.id) }
                else { page = try await client().feedbackUpdates(since: cursor.time, sinceID: cursor.id) }
                guard !Task.isCancelled, !defaults.bool(forKey: "feedback.uploadOff") else { return }
                for change in page.updates {
                    guard let index = reports.firstIndex(where: { $0.id == change.phone_report_id && $0.localOnly != true && $0.remoteID != nil }) else { continue }
                    var report = reports[index]
                    if report.apply(change) {
                        try persist(report)
                        reports[index] = report
                    }
                }
                cursor = Cursor(time: page.cursor, id: page.cursor_id)
                try JSONEncoder().encode(cursor).write(to: cursorFile, options: .atomic)
                if !page.more { break }
            }
            statusProblem = nil
            lastRefresh = Date()
        } catch {
            if !Task.isCancelled { statusProblem = "Couldn’t refresh report status. " + error.localizedDescription }
        }
    }

    func resolve(_ id: Int64, confirmed: Bool) throws {
        guard let index = reports.firstIndex(where: { $0.id == id }), reports[index].awaitsOwner else {
            throw DeviceAPIError(409, "report_changed", "This report is no longer awaiting your response.")
        }
        var report = reports[index]
        let queue = report.localOnly != true && !defaults.bool(forKey: "feedback.uploadOff")
        let move = FeedbackReport.Move(status: confirmed ? "confirmed" : "reopened", at: Date(), note: nil,
                                       pendingID: queue ? Int64.random(in: 1...9_007_199_254_740_991) : nil, actor: .owner)
        report.moves = (report.moves ?? []) + [move]
        try persist(report)
        reports[index] = report
    }

    @Published private(set) var resolutionProblem: String?
    private var draining = false
    func sendPendingResolutions(send: ((Int64, Int64, String, Date) async throws -> Void)? = nil) async {
        guard !draining, !defaults.bool(forKey: "feedback.uploadOff") else { return }
        draining = true
        defer { draining = false }
        var deferredReports = Set<Int64>()
        resolutionProblem = nil
        do {
            while !Task.isCancelled && !defaults.bool(forKey: "feedback.uploadOff"),
                  let index = reports.firstIndex(where: { report in
                      !deferredReports.contains(report.id) && report.remoteID != nil && report.localOnly != true && report.moves?.contains { $0.pendingID != nil } == true
                  }), let remote = reports[index].remoteID,
                  let move = reports[index].moves?.first(where: { $0.pendingID != nil }), let historyID = move.pendingID {
                let reportID = reports[index].id
                do {
                    if let send { try await send(remote, historyID, move.status, move.at) }
                    else {
                        try await client().sendFeedbackHistory(
                            reportID: remote, historyID: historyID, status: move.status, at: move.at,
                            kind: move.kind ?? "status", actor: move.actor ?? .owner,
                            author: move.author ?? "owner", note: move.note,
                            authorize: { try await self.requireTeamUpload(reportID) })
                    }
                } catch {
                    if Task.isCancelled { return }
                    resolutionProblem = "Your response is saved. Couldn’t send it to the Bridgit team. " + error.localizedDescription
                    deferredReports.insert(reportID)
                    continue
                }
                // A new report can change the index while the request is in flight.
                guard let current = reports.firstIndex(where: { $0.id == reportID }),
                      let offset = reports[current].moves?.firstIndex(where: { $0.pendingID == historyID }) else { continue }
                var updated = reports[current]
                updated.moves?[offset].pendingID = nil
                try persist(updated)
                reports[current] = updated
            }
        } catch {
            if !Task.isCancelled { resolutionProblem = "Couldn’t save the delivery receipt. " + error.localizedDescription }
        }
    }

    private func persist(_ report: FeedbackReport) throws {
        try JSONEncoder().encode(report).write(to: folder.appendingPathComponent("\(report.id).json"), options: .atomic)
    }

    func save(kind: FeedbackKind, text: String, autoFix: Bool, images: [Data], log: [[String: String]], localOnly: Bool, sourceScreen: String, logEntryID: Int64? = nil, attachedActivity: [HostActivityEntry]? = nil, filingAgent: HostAgent? = nil, agentContext: Data? = nil) throws -> FeedbackReport {
        var id: Int64
        repeat { id = .random(in: 1...9_007_199_254_740_991) } while reports.contains { $0.id == id }
        let metadata = captureMetadata()
        let report = FeedbackReport(id: id, kind: kind, description: text,
                                    noAutofix: filingAgent == nil ? kind == .bug && !autoFix : kind == .feature || !autoFix,
                                    createdAt: Int64(Date().timeIntervalSince1970 * 1000), localOnly: localOnly, images: images, log: log,
                                    version: metadata.version, appVersion: metadata.appVersion, buildNumber: metadata.buildNumber, deviceModel: metadata.model, buildRevision: metadata.revision,
                                    buildConfiguration: metadata.configuration, logEntryID: logEntryID, attachedActivity: attachedActivity, sourceScreen: sourceScreen,
                                    filingAgentID: filingAgent?.id, filingAgentName: filingAgent?.name, filingAgentContext: agentContext, system: metadata.system)
        try persist(report)
        reports.insert(report, at: 0)
        return report
    }

    func setSendingEnabled(_ enabled: Bool) throws {
        if !enabled { defaults.set(true, forKey: "feedback.uploadOff") }
        // Finish any interrupted conversion before allowing retries, including after restart.
        if defaults.bool(forKey: "feedback.uploadOff") {
            for index in reports.indices where reports[index].remoteID == nil && reports[index].localOnly != true {
                var report = reports[index]
                report.localOnly = true
                try persist(report)
                reports[index] = report
            }
        }
        defaults.set(!enabled, forKey: "feedback.uploadOff")
    }

    private func upload(_ report: FeedbackReport) async throws -> Int64 {
        return try await client().submitFeedback(
            reportID: report.id, kind: report.kind.rawValue, description: report.description,
            noAutofix: report.noAutofix, createdAt: report.createdAt, appVersion: report.submissionVersion,
            versionCode: report.submissionVersionCode, buildNumber: report.buildNumber,
            registrationAppVersion: AppleBuildDetails.shortVersion,
            deviceModel: report.deviceModel, osVersion: report.system, buildRevision: report.buildRevision,
            buildConfiguration: report.buildConfiguration, images: report.images ?? [], log: report.log ?? [],
            sourceScreen: report.sourceScreen, logEntryID: report.logEntryID, attachedActivity: report.attachedActivity ?? [],
            filingAgentContext: report.filingAgentContext, authorize: { try await self.requireTeamUpload(report.id) })
    }

    private func requireTeamUpload(_ reportID: Int64) throws {
        try Task.checkCancellation()
        guard sendingEnabled, reports.contains(where: { $0.id == reportID && $0.localOnly != true }) else {
            throw DeviceAPIError(403, "feedback_upload_disabled", "Sending reports to the Bridgit team is off.")
        }
    }

    func send(_ report: FeedbackReport, submit: ((FeedbackReport) async throws -> Int64)? = nil) async {
        guard !defaults.bool(forKey: "feedback.uploadOff"),
              reports.contains(where: { $0.id == report.id && $0.remoteID == nil && $0.localOnly != true }),
              sending.insert(report.id).inserted else { return }
        defer { sending.remove(report.id) }
        do {
            let remoteID: Int64
            if let submit { remoteID = try await submit(report) }
            else { remoteID = try await upload(report) }
            if let index = reports.firstIndex(where: { $0.id == report.id }) {
                var sent = reports[index]
                sent.remoteID = remoteID
                sent.uploadFailed = nil
                try persist(sent)
                reports[index] = sent
            }
            problem = nil
            await sendPendingResolutions()
        } catch {
            if !Task.isCancelled, let index = reports.firstIndex(where: { $0.id == report.id && $0.localOnly != true }) {
                var failed = reports[index]
                failed.uploadFailed = true
                if (try? persist(failed)) != nil { reports[index] = failed }
            }
            problem = "Saved on this device. " + error.localizedDescription
        }
    }
}

extension FeedbackReports {
    func agentRequest(_ request: HostRequest, agent: HostAgent, authority: HostAuthority, activity: AppletStore) async throws
        -> (response: HostResponse, requiresFixer: Bool) {
        try Task.checkCancellation()
        let path = request.path.split(separator: "/")
        if request.method == "GET", request.path == "/v1/feedback/team-key" {
            return try await agentTeamKey(request, agent: agent, authority: authority)
        }
        if path.count == 2, request.method == "GET" {
            return try await agentList(request, agent: agent, authority: authority)
        }
        if path.count == 2, request.method == "POST" {
            return try await agentFile(request, agent: agent, authority: authority, activity: activity)
        }
        guard path.count >= 3, let id = Int64(path[2]), id > 0 else { throw Self.invalid("Use a positive report number.") }
        if path.count == 3, request.method == "GET" {
            return try await agentDetail(id, request: request, agent: agent, authority: authority)
        }
        guard path.count == 4, request.method == "POST" else { throw Self.invalid("Unknown report action.") }
        let fixer = path[3] == "status"
        guard fixer || path[3] == "followup" else { throw Self.invalid("Unknown report action.") }
        let report = try await authority.withFeedbackMutation(agent, requiresFixer: fixer) { current, permit in
            try permit.commit { try self.agentMove(id, body: request.body, current: current, fixer: fixer) }
        }
        if report.moves?.contains(where: { $0.pendingID != nil }) == true { Task { await sendPendingResolutions() } }
        var reply = report.apiSummary
        if !fixer { reply["links"] = Self.agentLinks(id) }
        return (try .json(reply), fixer)
    }

    private func agentTeamKey(_ request: HostRequest, agent: HostAgent, authority: HostAuthority) async throws
        -> (response: HostResponse, requiresFixer: Bool) {
        let raw = request.query["rotate"]
        let rotate = raw as? Bool == true || ["1", "true"].contains(raw as? String ?? "")
        let fixer = agent.allows(.feedbackFixer)
        func authorize() async throws {
            try Task.checkCancellation()
            guard sendingEnabled else {
                throw DeviceAPIError(403, "capability_disabled", "Sending bug reports is off. You can register directly with the Bridgit feedback service at /v1/agents/register.")
            }
            try await authority.authorizeOngoing(agent, operation: fixer ? .feedbackFixer : .feedback)
        }
        let key = try await teamKeys.get(agent, rotate: rotate, authorize: authorize)
        return (try HostResponse.json(key.response).requiringDelivery(authorize), fixer)
    }

    private func agentList(_ request: HostRequest, agent: HostAgent, authority: HostAuthority) async throws
        -> (response: HostResponse, requiresFixer: Bool) {
        let status = try Self.parse(request.query["status"], key: "status", make: HostFeedbackStatus.init(rawValue:))
        let kind = try Self.parse(request.query["kind"], key: "kind", make: FeedbackKind.init(rawValue:))
        let since = try Self.parse(request.query["since"], key: "since", make: Self.isoDate)
        let limit = try Self.integer(request.query["limit"], key: "limit", range: 1...100) ?? 50
        let wait = try Self.integer(request.query["wait"], key: "wait", range: 0...55) ?? 0
        guard wait == 0 || since != nil else { throw Self.invalid("wait needs since (an ISO time).") }
        let mine = request.query["mine"] as? Bool == true || ["1", "true"].contains((request.query["mine"] as? String)?.lowercased() ?? "")
        let all = !mine && agent.allows(.feedbackFixer)
        let grant: HostOperation = all ? .feedbackFixer : .feedback
        let budget = HostFeedbackWaitBudget(seconds: wait)
        try await authority.authorizeOngoing(agent, operation: grant)
        await refresh()
        while true {
            try Task.checkCancellation()
            try await authority.authorizeOngoing(agent, operation: grant)
            let matches = reports.lazy.filter { report in
                (all || report.filingAgentID == agent.id) &&
                    (status == nil || report.typedStatus == status) && (kind == nil || report.kind == kind) &&
                    (since.map { report.updatedDate > $0 } ?? true)
            }.prefix(limit)
            let remaining = budget.remaining()
            if !matches.isEmpty || remaining == .zero {
                return (try .json(["reports": Array(matches.map(\.apiSummary))]), all)
            }
            try await Task.sleep(for: min(.milliseconds(200), remaining))
        }
    }

    private func agentDetail(_ id: Int64, request: HostRequest, agent: HostAgent, authority: HostAuthority) async throws
        -> (response: HostResponse, requiresFixer: Bool) {
        let fixer = agent.allows(.feedbackFixer)
        guard let existing = reports.first(where: { $0.id == id }), fixer || existing.filingAgentID == agent.id else { throw Self.missing(id) }
        await refresh()
        let report: FeedbackReport
        if fixer {
            report = try await authority.withFeedbackMutation(agent, requiresFixer: true) { current, permit in
                try permit.commit {
                    guard let index = self.reports.firstIndex(where: { $0.id == id }) else { throw Self.missing(id) }
                    if self.reports[index].typedStatus.allows(.seen, actor: .auto) {
                        return try self.appendMove(at: index, to: .seen, actor: .auto, name: current.name, note: nil)
                    }
                    return self.reports[index]
                }
            }
        } else {
            try await authority.authorizeOngoing(agent, operation: .feedback)
            guard let own = reports.first(where: { $0.id == id && $0.filingAgentID == agent.id }) else { throw Self.missing(id) }
            report = own
        }
        var reply = report.apiSummary
        reply["description"] = report.description
        reply["team_note"] = report.teamNote as Any? ?? NSNull()
        let initial = FeedbackReport.Move(status: "new", at: report.filedDate, note: nil, author: report.filedBy)
        reply["history"] = ([initial] + (report.moves ?? [])).map(\.apiHistory)
        reply["followup_outcomes"] = report.filingAgentID == agent.id ? report.typedStatus.reporterOutcomes : []
        reply["links"] = Self.agentLinks(id)
        if fixer {
            reply["allowed_next"] = report.typedStatus.fixerDestinations.map(\.rawValue)
            reply["context"] = report.apiContext
            reply["images"] = try Self.agentImages(report.images ?? [], query: request.query)
            let activity: [[String: Any]] = try (report.attachedActivity ?? []).map {
                try JSONSerialization.jsonObject(with: Data(HostActivityDetail($0).json().utf8)) as? [String: Any] ?? [:]
            }
            reply["log"] = (report.log ?? []).map { $0 as [String: Any] } + activity
        }
        if fixer && report.moves?.contains(where: { $0.pendingID != nil }) == true { Task { await sendPendingResolutions() } }
        return (try HostResponse.json(reply), fixer)
    }

    private func agentFile(_ request: HostRequest, agent: HostAgent, authority: HostAuthority, activity: AppletStore) async throws
        -> (response: HostResponse, requiresFixer: Bool) {
        guard let kind = (request.body["kind"] as? String).flatMap(FeedbackKind.init(rawValue:)),
              let text = (request.body["description"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
              !text.isEmpty, text.utf16.count <= 10_000 else { throw Self.invalid("Use bug or feature and a description of 1–10,000 characters.") }
        let inputs = request.body["images"] as? [[String: Any]] ?? []
        guard request.body["images"] == nil || request.body["images"] is [[String: Any]], inputs.count <= 5 else { throw Self.invalid("Attach up to five images.") }
        let fixer = !inputs.isEmpty
        try await authority.authorizeOngoing(agent, operation: fixer ? .feedbackFixer : .feedback)
        let images: [Data] = try inputs.map { item in
            guard let encoded = item["data_base64"] as? String, let bytes = Data(base64Encoded: encoded), !bytes.isEmpty else { throw Self.invalid("Image data must be base64.") }
            let jpg = try LocalAppletModel.jpeg(bytes, maxEdge: 1600)
            guard jpg.count <= 1_000_000 else { throw DeviceAPIError(413, "image_too_large", "Use a smaller image.") }
            return jpg
        }
        let log = try await activity.hostActivity(limit: 50, kinds: Set(HostActivityKind.allCases.filter { $0 != .note }))
        let noAutofix = request.body["no_autofix"] as? Bool == true
        let report = try await authority.withFeedbackMutation(agent, requiresFixer: fixer) { current, permit in
            try permit.commit {
                let cutoff = Date().addingTimeInterval(-3600)
                guard self.reports.lazy.filter({ $0.filingAgentID == current.id && $0.filedDate > cutoff }).prefix(50).count < 50 else {
                    throw DeviceAPIError(429, "rate_limited", "At most 50 reports per hour per agent.")
                }
                let context = try Self.agentContext(current, reported: request.body["agent_info"] as? [String: Any])
                return try self.save(kind: kind, text: text, autoFix: !noAutofix, images: images, log: [],
                                     localOnly: !self.sendingEnabled, sourceScreen: "agent", attachedActivity: log,
                                     filingAgent: current, agentContext: context)
            }
        }
        if report.localOnly != true { Task { await self.send(report) } }
        let links = Self.agentLinks(report.id)
        return (try .json(["id": report.id, "ref": NSNull(), "number": NSNull(), "status": report.status,
                           "no_autofix": report.noAutofix, "sent_to_team": report.uploadState,
                           "url": links["url"] ?? "", "followup_url": links["followup_url"] ?? "",
                           "check_back": "Use GET /v1/feedback/\(report.id) to follow this report.",
                           "team_api": ["base_url": InstallClient.productionBase.absoluteString,
                                        "key_endpoint": "/v1/feedback/team-key",
                                        "docs": InstallClient.productionBase.absoluteString + "/v1/agent"]], status: 201), fixer)
    }

    private func agentMove(_ id: Int64, body: [String: Any], current: HostAgent, fixer: Bool) throws -> FeedbackReport {
        guard let index = reports.firstIndex(where: { $0.id == id }) else { throw Self.missing(id) }
        let report = reports[index]
        if !fixer, report.filingAgentID != current.id {
            if current.allows(.feedbackFixer) { throw DeviceAPIError(403, "not_your_report", "Only the filing agent can follow up.") }
            throw Self.missing(id)
        }
        let note = (body["note"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
        let comment = note.flatMap { $0.isEmpty ? nil : $0 }
        if fixer {
            guard let value = body["status"] as? String, let destination = HostFeedbackStatus(rawValue: value),
                  [.inProgress, .fixed, .wontFix, .duplicate].contains(destination) else { throw Self.invalid("Use in_progress, fixed, wont_fix or duplicate.") }
            if destination == report.typedStatus { return report }
            guard report.typedStatus.allows(destination, actor: .developer) else { throw Self.invalid("This status transition is unavailable.") }
            return try appendMove(at: index, to: destination, actor: .developer, name: current.name,
                                  note: comment.map { Self.utf16Prefix($0, maximum: 500) })
        }
        guard (comment?.utf16.count ?? 0) <= 500 else { throw Self.invalid("note is limited to 500 characters.") }
        let outcome = (body["outcome"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        let destination = outcome.flatMap(HostFeedbackStatus.outcome)
        guard outcome == nil || destination != nil else { throw Self.invalid("outcome must be works or still_broken.") }
        guard comment != nil || destination != nil else { throw Self.invalid("Send a note, an outcome, or both.") }
        let cutoff = Date().addingTimeInterval(-3600)
        guard (report.moves ?? []).lazy.filter({ $0.actor == .reporter && $0.at > cutoff }).prefix(20).count < 20 else {
            throw DeviceAPIError(429, "rate_limited", "At most 20 follow-ups per hour on one report.")
        }
        if let destination, !report.typedStatus.allows(destination, actor: .reporter) { throw Self.invalid("This report is not ready for that outcome.") }
        return try appendMove(at: index, to: destination ?? report.typedStatus, actor: .reporter,
                              name: current.name, note: comment, kind: destination == nil ? "comment" : "status")
    }

    private func appendMove(at index: Int, to status: HostFeedbackStatus, actor: HostFeedbackActor, name: String,
                            note: String?, kind: String = "status") throws -> FeedbackReport {
        var report = reports[index]
        var pendingID: Int64?
        if report.localOnly != true && sendingEnabled {
            repeat { pendingID = Int64.random(in: 1...9_007_199_254_740_991) }
            while report.moves?.contains(where: { $0.pendingID == pendingID }) == true
        }
        report.moves = (report.moves ?? []) + [.init(status: status.rawValue, at: Date(), note: note,
            pendingID: pendingID, actor: actor, author: name, kind: kind)]
        try persist(report)
        reports[index] = report
        return report
    }

    private static func agentLinks(_ id: Int64) -> [String: String] {
        ["url": "/v1/feedback/\(id)", "followup_url": "/v1/feedback/\(id)/followup"]
    }

    private static func missing(_ id: Int64) -> DeviceAPIError { .init(404, "not_found", "No report \(id).") }
    private static func invalid(_ message: String) -> DeviceAPIError { .init(422, "invalid_parameter", message) }
    private static func isoDate(_ value: String) -> Date? {
        let parser = ISO8601DateFormatter()
        parser.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = parser.date(from: value) { return date }
        parser.formatOptions = [.withInternetDateTime]
        return parser.date(from: value)
    }

    private static func parse<T>(_ raw: Any?, key: String, make: (String) -> T?) throws -> T? {
        guard let raw else { return nil }
        guard let text = raw as? String, let value = make(text) else { throw invalid("Invalid \(key).") }
        return value
    }

    private static func integer(_ raw: Any?, key: String, range: ClosedRange<Int>) throws -> Int? {
        guard let raw else { return nil }
        let integer: Int?
        if let number = raw as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID(), number.doubleValue == Double(number.intValue) {
            integer = number.intValue
        } else { integer = (raw as? String).flatMap { Int($0) } }
        guard let integer, range.contains(integer) else { throw invalid("\(key) must be \(range.lowerBound) to \(range.upperBound).") }
        return integer
    }

    nonisolated fileprivate static func utf16Prefix(_ value: String, maximum: Int) -> String {
        var prefix = Array(value.utf16.prefix(maximum))
        if let last = prefix.last, (0xD800...0xDBFF).contains(last) { prefix.removeLast() }
        return String(decoding: prefix, as: UTF16.self)
    }

    private static func agentContext(_ agent: HostAgent, reported: [String: Any]?) throws -> Data {
        var context: [String: Any] = ["id": agent.id, "name": agent.name, "connection": agent.mode,
            "developer": agent.allows(.feedbackFixer), "fixer_mode": agent.allows(.feedbackFixer),
            "super_powers": agent.allows(.administrator)]
        if let reported {
            var safe: [String: Any] = [:]
            for key in reported.keys.sorted().prefix(20) where key.utf16.count <= 40 {
                if let text = reported[key] as? String { safe[key] = utf16Prefix(text, maximum: 200) }
                else if let number = reported[key] as? NSNumber { safe[key] = number }
            }
            context["reported"] = safe
            for name in ["model", "harness"] {
                if let value = safe[name] as? String, !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    context[name] = value
                }
            }
        }
        return try JSONSerialization.data(withJSONObject: context, options: [.sortedKeys])
    }

    private static func agentImages(_ saved: [Data], query: [String: Any]) throws -> [[String: Any]] {
        let size = query["size"] as? String ?? "agent"
        let preset: (Int?, Double)
        switch size {
        case "agent": preset = (1568, 0.85)
        case "thumb": preset = (512, 0.75)
        case "large": preset = (2560, 0.90)
        case "original": preset = (nil, 0.90)
        default: throw invalid("size must be agent, thumb, large or original.")
        }
        let edge = try integer(query["max_edge_px"], key: "max_edge_px", range: 64...20_000)
        let width = try integer(query["max_width_px"], key: "max_width_px", range: 64...20_000)
        let quality = try integer(query["quality"], key: "quality", range: 40...100)
        func dimensions(_ bytes: Data) throws -> (Int, Int) {
            guard let source = CGImageSourceCreateWithData(bytes as CFData, nil),
                  let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
                  let w = properties[kCGImagePropertyPixelWidth] as? Int, let h = properties[kCGImagePropertyPixelHeight] as? Int,
                  w > 0, h > 0 else { throw DeviceAPIError(500, "invalid_photo", "A saved report image could not be read.") }
            return (w, h)
        }
        return try saved.enumerated().map { index, data in
            let (w, h) = try dimensions(data)
            let sourceEdge = max(w, h)
            let widthEdge = width.map { Int(Double(sourceEdge) * min(1, Double($0) / Double(w))) } ?? sourceEdge
            let bound = min(widthEdge, edge ?? (width == nil ? preset.0 : nil) ?? sourceEdge)
            let targetWidth = max(1, Int((Double(w) * Double(bound) / Double(sourceEdge)).rounded()))
            let bytes = targetWidth < w
                ? try LocalAppletModel.jpeg(data, maxEdge: max(1, bound), quality: quality.map { Double($0) / 100 } ?? preset.1) : data
            let (outputWidth, outputHeight) = try dimensions(bytes)
            return ["index": index + 1, "width_px": outputWidth, "height_px": outputHeight,
                    "size_bytes": bytes.count, "data_base64": bytes.base64EncodedString()]
        }
    }
}

private extension Date {
    var feedbackTimestamp: String {
        ISO8601Format(.iso8601.time(includingFractionalSeconds: true))
    }
}

private extension FeedbackReport {
    var uploadState: String {
        if remoteID != nil { return "sent" }
        if localOnly == true { return "off" }
        return uploadFailed == true ? "failed" : "pending"
    }
    var apiSummary: [String: Any] {
        let first = description.split(whereSeparator: \.isNewline)
            .first { !$0.trimmingCharacters(in: .whitespaces).isEmpty }?
            .trimmingCharacters(in: .whitespaces) ?? ""
        return ["id": id, "ref": teamReference as Any? ?? NSNull(), "number": teamReference as Any? ?? NSNull(),
         "team_id": remoteID as Any? ?? NSNull(), "kind": kind.rawValue, "status": status,
         "first_line": FeedbackReports.utf16Prefix(first, maximum: 120),
         "created_at": filedDate.feedbackTimestamp, "updated_at": updatedDate.feedbackTimestamp, "no_autofix": noAutofix,
         "capture_source": captureSource, "filed_by": filedBy, "image_count": images?.count ?? 0,
         "last_updated_by": moves?.last?.by ?? filedBy, "sent_to_team": uploadState]
    }

    var apiContext: [String: Any] {
        var context: [String: Any] = [:]
        context["app_version"] = appVersion
        context["app_build"] = buildNumber
        context["device_model"] = deviceModel
        context["os_version"] = system
        context["git_commit"] = buildRevision
        context["build_type"] = buildConfiguration
        context["screen"] = sourceScreen
        context["log_entry_id"] = logEntryID
        if let filingAgentContext { context["agent"] = try? JSONSerialization.jsonObject(with: filingAgentContext) }
        return context
    }
}

@MainActor enum FeedbackCapture {
    static func lastScreen() -> Data? {
        #if os(macOS)
        guard let view = (NSApp.keyWindow ?? NSApp.mainWindow)?.contentView, !view.bounds.isEmpty,
              let bitmap = view.bitmapImageRepForCachingDisplay(in: view.bounds) else { return nil }
        view.cacheDisplay(in: view.bounds, to: bitmap)
        let source = bitmap.representation(using: .jpeg, properties: [.compressionFactor: 0.8])
        #else
        let windows = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            .filter { $0.activationState == .foregroundActive }.compactMap(\.keyWindow)
        guard windows.count == 1, let window = windows.first, !window.bounds.isEmpty else { return nil }
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let source: Data? = UIGraphicsImageRenderer(bounds: window.bounds, format: format).jpegData(withCompressionQuality: 0.8) { context in
            if !window.drawHierarchy(in: window.bounds, afterScreenUpdates: false) { window.layer.render(in: context.cgContext) }
        }
        #endif
        guard let data = try? LocalAppletModel.jpeg(source ?? Data(), maxEdge: 1280, quality: 0.75),
              data.count <= 1_000_000 else { return nil }
        return data
    }
}

extension View {
    func feedbackComposer<Item: Identifiable, Editor: View>(item: Binding<Item?>, @ViewBuilder editor: @escaping (Item) -> Editor) -> some View {
        feedbackSurface(item: item, editor: editor)
    }
    fileprivate func feedbackSurface<Item: Identifiable, Editor: View>(item: Binding<Item?>, @ViewBuilder editor: @escaping (Item) -> Editor) -> some View {
        modifier(FeedbackSurfacePresentation(item: item, editor: editor))
    }
}

private struct FeedbackSurfacePresentation<Item: Identifiable, Editor: View>: ViewModifier {
    @Binding var item: Item?
    let editor: (Item) -> Editor
    #if os(iOS)
    @Environment(\.horizontalSizeClass) private var width
    @Environment(\.dynamicTypeSize) private var textSize
    #endif

    init(item: Binding<Item?>, editor: @escaping (Item) -> Editor) {
        _item = item
        self.editor = editor
    }

    @ViewBuilder func body(content: Content) -> some View {
        #if os(iOS)
        if width == .compact && textSize.isAccessibilitySize {
            content.accessibilityHidden(item != nil).allowsHitTesting(item == nil).coveringBuildStampSurface(item != nil).fullScreenCover(item: $item) { editor($0).buildStampSurface() }
        } else {
            content.accessibilityHidden(item != nil).allowsHitTesting(item == nil).coveringBuildStampSurface(item != nil).bridgitDialog(item: $item) { editor($0).buildStampSurface() }
        }
        #else
        content.accessibilityHidden(item != nil).allowsHitTesting(item == nil).coveringBuildStampSurface(item != nil).bridgitDialog(item: $item) { editor($0).buildStampSurface() }
        #endif
    }
}

private struct FeedbackPresentation: Identifiable {
    let id = UUID()
    let kind: FeedbackKind
    let lastScreen: Data?
}

struct FeedbackEntries: View {
    @ObservedObject private var store: FeedbackReports
    init(store: FeedbackReports? = nil) { self.store = store ?? .shared }
    var body: some View {
        VStack(alignment: .leading) {
            Text("Feedback").font(.subheadline.weight(.semibold)).foregroundStyle(B.primary).accessibilityAddTraits(.isHeader)
            MoreRowDivider()
            ForEach(FeedbackKind.allCases) { FeedbackEntryRow(kind: $0, showsHistory: true, store: store) }
            if let problem = store.problem ?? store.resolutionProblem ?? store.statusProblem { Text(problem).foregroundStyle(B.error) }
        }.task { await store.sendPendingResolutions(); await store.refresh() }
    }
}

private struct FeedbackRowPresentation<Item: Identifiable, Editor: View>: ViewModifier {
    @Binding var item: Item?
    let editor: (Item) -> Editor
    init(item: Binding<Item?>, @ViewBuilder editor: @escaping (Item) -> Editor) {
        _item = item
        self.editor = editor
    }
    #if os(iOS)
    @Environment(\.horizontalSizeClass) private var width
    @Environment(\.dynamicTypeSize) private var textSize
    #endif
    @ViewBuilder func body(content: Content) -> some View {
        #if os(iOS)
        if width == .compact && textSize.isAccessibilitySize {
            content.coveringBuildStampSurface(item != nil).fullScreenCover(item: $item) { editor($0).buildStampSurface() }
        } else {
            content.coveringBuildStampSurface(item != nil).bridgitDialog(item: $item) { editor($0).buildStampSurface() }
        }
        #else
        content.coveringBuildStampSurface(item != nil).bridgitDialog(item: $item) { editor($0).buildStampSurface() }
        #endif
    }
}

private enum FeedbackRowDestination: Identifiable {
    case history(FeedbackPresentation)
    case compose(FeedbackPresentation)
    var id: UUID {
        switch self { case .history(let item), .compose(let item): return item.id }
    }
}

struct FeedbackEntryRow: View {
    @EnvironmentObject private var local: LocalAppletModel
    let kind: FeedbackKind
    var showsHistory = false
    @ObservedObject private var store: FeedbackReports
    init(kind: FeedbackKind, showsHistory: Bool = false, store: FeedbackReports? = nil) {
        self.kind = kind
        self.showsHistory = showsHistory
        self.store = store ?? .shared
    }
    @State private var destination: FeedbackRowDestination?
    @Environment(\.dynamicTypeSize) private var textSize
    @ViewBuilder private var composeButton: some View {
        Button(action: compose) {
            Label { Text(kind.title).fixedSize(horizontal: false, vertical: true) } icon: {
                Image(decorative: kind.iconAsset).foregroundStyle(B.onSurfaceVariant)
            }
            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
            .contentShape(Rectangle())
        }.buttonStyle(.plain)
    }
    @ViewBuilder private var historyButton: some View {
        if showsHistory {
            let filed = store.reports.filter { $0.kind == kind }
            let summary = String(filed.count) + " filed · " + String(filed.filter(\.countsAsFixed).count) + " fixed"
            Button {
                destination = .history(FeedbackPresentation(kind: kind, lastScreen: nil))
            } label: {
                Text(summary).font(.caption).foregroundStyle(B.primary).fixedSize(horizontal: false, vertical: true)
                    .frame(minHeight: 44).contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(kind == .bug ? "My bug reports" : "My feature requests")
            .accessibilityValue(summary)
        }
    }
    private var composeChevron: some View {
        Image(systemName: "chevron.right").foregroundStyle(B.onSurfaceVariant)
            .frame(minHeight: 44).contentShape(Rectangle())
            .onTapGesture(perform: compose).accessibilityHidden(true)
    }
    var body: some View {
        Group {
            if textSize.isAccessibilitySize && showsHistory {
                VStack(alignment: .leading, spacing: 8) {
                    HStack(spacing: 8) { composeButton; composeChevron }
                    historyButton.frame(maxWidth: .infinity, alignment: .leading)
                }
            } else {
                HStack(spacing: 8) { composeButton; historyButton; composeChevron }
            }
        }
        .modifier(FeedbackRowPresentation(item: $destination) { destination in
            switch destination {
            case .history(let item): FeedbackHistory(initial: item.kind, store: store)
            case .compose(let item): FeedbackForm(kind: item.kind, store: store, lastScreen: item.lastScreen, activityStore: local.store)
            }
        })
        if kind != .feature { MoreRowDivider() }
    }

    private func compose() {
        destination = .compose(FeedbackPresentation(kind: kind, lastScreen: FeedbackCapture.lastScreen()))
    }
}

struct OwnerSettingsRow: View {
    @EnvironmentObject private var local: LocalAppletModel
    @AppStorage("appearance", store: AppleFixtureWorkspace.defaults) private var appearance = BridgitAppearance.system
    var title = "Settings"
    @State private var settingError: String?
    @AppStorage("feedback.uploadOff") private var uploadOff = false
    @State private var presented = false
    var body: some View {
        Button { presented = true } label: {
            HStack { Label(title, systemImage: "gearshape"); Spacer(); Image(systemName: "chevron.right") }
                .contentShape(Rectangle()).padding(.vertical, 12)
        }.buttonStyle(.plain)
        .bridgitDialog(isPresented: $presented) {
            VStack(alignment: .leading, spacing: 0) {
                HStack { Text("Settings").font(.title); Spacer(); Button("Done") { presented = false }.foregroundStyle(B.primary) }
                    .padding()
                ScrollView {
                    VStack(alignment: .leading, spacing: 20) {
                        Text("Privacy").font(.headline).accessibilityAddTraits(.isHeader)
                        Toggle("Send bug reports to the Bridgit team", isOn: Binding(get: { !uploadOff }, set: { enabled in
                            do { try FeedbackReports.shared.setSendingEnabled(enabled); settingError = nil }
                            catch { settingError = error.localizedDescription }
                        }))
                        Text("Reports include your description, app version, and any screenshots or recent log you choose to send. Turn this off to keep new reports on this device.")
                            .font(.subheadline).foregroundStyle(B.onSurfaceVariant)
                        if let settingError { Text(settingError).foregroundStyle(B.error) }
                        CrashReportingToggle(model: local.configuration)
                        Text("Appearance").font(.headline).accessibilityAddTraits(.isHeader)
                        LabeledContent("Theme") {
                            Picker("Theme", selection: $appearance) {
                                ForEach(BridgitAppearance.allCases, id: \.self) { Text($0.rawValue).tag($0) }
                            }
                            .pickerStyle(.menu).labelsHidden()
                            .accessibilityIdentifier("settings-theme")
                        }
                        Text("About").font(.headline).accessibilityAddTraits(.isHeader)
                        LabeledContent("Version", value: AppleBuildDetails.version)
                        #if os(iOS)
                        TestFlightUpdateButton { Text("Check for updates") }
                        #else
                        MacUpdateButton { Text("Check for updates") }
                        #endif
                        if let revision = AppleBuildDetails.revision {
                            LabeledContent("Commit", value: revision)
                        }
                        LabeledContent("Spec", value: "docs/bridgit-spec-v2.md")
                        legalLink("Privacy policy", destination: AppleBuildDetails.privacy)
                        legalLink("Terms of use", destination: AppleBuildDetails.terms)
                    }
                    .padding()
                    .frame(maxWidth: 600, alignment: .leading)
                    .frame(maxWidth: .infinity)
                }
            }
            #if os(macOS)
            .frame(minWidth: 420, minHeight: 360, idealHeight: 560)
            #else
            .frame(maxHeight: .infinity, alignment: .top)
            #endif
            .background(B.surface)
            #if os(macOS)
            .preferredColorScheme(appearance.colorScheme)
            #endif
        }
    }
    private struct CrashReportingToggle: View {
        @ObservedObject var model: OwnerConfigurationModel
        @State private var changing = false
        @State private var problem: String?

        var body: some View {
            if let enabled = model.crashReportingEnabled {
                VStack(alignment: .leading, spacing: 8) {
                    Toggle("Send crash reports", isOn: Binding(get: { enabled }, set: { value in
                        changing = true
                        Task {
                            defer { changing = false }
                            do { try await model.setCrashReporting(value); problem = nil }
                            catch { problem = error.localizedDescription }
                        }
                    }))
                    .disabled(changing)
                    .accessibilityIdentifier("settings-crash-reports")
                    Text("Sends crash and hang diagnostics through Sentry.")
                        .font(.subheadline).foregroundStyle(B.onSurfaceVariant)
                    if let problem { Text(problem).foregroundStyle(B.error) }
                }
            }
        }
    }

    private func legalLink(_ title: String, destination: URL) -> some View {
        Link(destination: destination) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                Text(destination.absoluteString).font(.subheadline).foregroundStyle(B.onSurfaceVariant)
            }
            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
            .contentShape(Rectangle())
        }
        .accessibilityLabel(title)
        .accessibilityValue(destination.absoluteString)
    }

}

private struct ReportStatusBadge: View {
    let status: String
    private var colors: (background: Color, foreground: Color) {
        switch status {
        case "seen": (B.primaryContainer, B.onPrimaryContainer)
        case "in_progress": (B.secondaryContainer, B.onSecondaryContainer)
        case "fixed": (B.successContainer, B.onSuccessContainer)
        case "reopened": (B.errorContainer, B.onErrorContainer)
        case "confirmed": (.clear, B.onSurfaceVariant)
        default: (B.surfaceHigh, B.onSurfaceVariant)
        }
    }
    var body: some View {
        Text(FeedbackReport.label(status)).font(.subheadline.weight(.semibold))
            .padding(.horizontal, 10).padding(.vertical, 4)
            .foregroundStyle(colors.foreground)
            .background(colors.background, in: RoundedRectangle(cornerRadius: 8))
            .overlay { if status == "confirmed" { RoundedRectangle(cornerRadius: 8).stroke(B.outline) } }
            .accessibilityLabel("Status: " + FeedbackReport.label(status))
    }
}

private struct ReportSection<Content: View>: View {
    let title: String
    @ViewBuilder let content: () -> Content
    var body: some View {
        Card {
            VStack(alignment: .leading, spacing: 8) {
                Text(title).textCase(.uppercase).font(.caption.weight(.bold)).tracking(1)
                    .foregroundStyle(B.primary).accessibilityAddTraits(.isHeader)
                content()
            }
        }
    }
}

private struct ReportImages: View {
    let images: [Data]
    private struct Preview: Identifiable { let id: Int; let image: CGImage }
    @State private var preview: Preview?
    @State private var failedAttempt: UUID?
    private let unavailableMessage = "This picture could not be opened."

    private func thumbnail(_ data: Data, maxSize: Int) -> CGImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let options: [CFString: Any] = [kCGImageSourceCreateThumbnailFromImageAlways: true,
                                        kCGImageSourceCreateThumbnailWithTransform: true,
                                        kCGImageSourceThumbnailMaxPixelSize: maxSize]
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
    }

    var body: some View {
        ReportSection(title: "Screenshots") {
            ScrollView(.horizontal) {
                HStack {
                    ForEach(images.indices, id: \.self) { i in
                        Button {
                            if let image = thumbnail(images[i], maxSize: 1600) { preview = Preview(id: i, image: image) }
                            else {
                                failedAttempt = UUID()
                                AccessibilityNotification.Announcement(unavailableMessage).post()
                            }
                        } label: {
                            Group {
                                if let image = thumbnail(images[i], maxSize: 256) {
                                    Image(decorative: image, scale: 1).resizable().scaledToFill()
                                } else { Image(systemName: "photo") }
                            }.frame(width: 96, height: 96).clipped().contentShape(Rectangle())
                        }.buttonStyle(.plain)
                            .accessibilityLabel("Screenshot " + String(i + 1) + " of " + String(images.count))
                            .accessibilityHint("View a larger image")
                    }
                }
            }
            if failedAttempt != nil { Text(unavailableMessage).foregroundStyle(B.error) }
        }
        .task(id: failedAttempt) {
            guard failedAttempt != nil else { return }
            do {
                try await Task.sleep(for: .seconds(4))
                failedAttempt = nil
            } catch {}
        }.feedbackSurface(item: $preview) { item in
            VStack(spacing: 0) {
                FeedbackSurfaceBar(actionTitle: "Close") { preview = nil }
                Image(decorative: item.image, scale: 1).resizable().scaledToFit()
                    .accessibilityHidden(false).accessibilityLabel("Screenshot " + String(item.id + 1))
                    .frame(maxWidth: .infinity, maxHeight: .infinity).padding(16)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .background(B.surface.ignoresSafeArea())
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("feedback.screenshotViewer")
            #if os(macOS)
            .frame(minWidth: 360, idealWidth: 600, minHeight: 320, idealHeight: 500)
            #endif
        }
    }
}

/// Presentation only: retain the saved payload and never replace unknown legacy times.
struct FeedbackLogDisplay {
    let message: String
    let agent: String?
    let rawTime: String?
    let date: Date?

    init(entry: [String: String]) {
        message = entry["message"].flatMap { $0.isEmpty ? nil : $0 } ?? "Message not recorded"
        agent = entry["agent"].flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : $0 }
        rawTime = entry["time"]
        let parser = ISO8601DateFormatter()
        parser.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let fractional = rawTime.flatMap(parser.date(from:))
        parser.formatOptions = [.withInternetDateTime]
        date = fractional ?? rawTime.flatMap(parser.date(from:))
    }

    func shortTime(locale: Locale = .current, timeZone: TimeZone = .current) -> String {
        formattedTime(dateStyle: .none, timeStyle: .short, locale: locale, timeZone: timeZone)
    }

    func fullTime(locale: Locale = .current, timeZone: TimeZone = .current) -> String {
        formattedTime(dateStyle: .full, timeStyle: .long, locale: locale, timeZone: timeZone)
    }

    func accessibilityLabel(locale: Locale = .current, timeZone: TimeZone = .current) -> String {
        [agent ?? "Bridgit", message, fullTime(locale: locale, timeZone: timeZone)].joined(separator: ", ")
    }

    private func formattedTime(dateStyle: DateFormatter.Style, timeStyle: DateFormatter.Style, locale: Locale, timeZone: TimeZone) -> String {
        guard let date else { return rawTime.flatMap { $0.isEmpty ? nil : $0 } ?? "Time not recorded" }
        let formatter = DateFormatter()
        formatter.locale = locale
        formatter.timeZone = timeZone
        formatter.dateStyle = dateStyle
        formatter.timeStyle = timeStyle
        return formatter.string(from: date)
    }
}

/// The saved report and its pre-submit preview use the same native activity rows.
private struct FeedbackLogRow: View {
    let entry: [String: String]
    @Environment(\.dynamicTypeSize) private var textSize
    var body: some View {
        let display = FeedbackLogDisplay(entry: entry)
        Card {
            HStack(alignment: .top, spacing: 12) {
                if let agent = display.agent {
                    AgentMark(name: agent)
                } else {
                    Image("Mark").resizable().scaledToFit().frame(width: 40, height: 40)
                        .clipShape(RoundedRectangle(cornerRadius: 9)).accessibilityHidden(true)
                }
                if textSize.isAccessibilitySize {
                    VStack(alignment: .leading, spacing: 6) {
                        message(display)
                        time(display)
                    }.frame(maxWidth: .infinity, alignment: .leading)
                } else {
                    HStack(alignment: .firstTextBaseline, spacing: 12) {
                        message(display).frame(maxWidth: .infinity, alignment: .leading)
                        time(display).fixedSize(horizontal: true, vertical: false)
                    }
                }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(display.accessibilityLabel())
    }
    private func message(_ display: FeedbackLogDisplay) -> some View {
        Text(display.message).font(.body).foregroundStyle(B.onSurface).textSelection(.enabled)
    }
    private func time(_ display: FeedbackLogDisplay) -> some View {
        Text(display.shortTime()).font(.caption.monospacedDigit()).foregroundStyle(B.onSurfaceVariant)
    }
}

private struct ReportLog: View {
    let entries: [[String: String]]
    @State private var expanded = false
    var body: some View {
        ReportSection(title: "Attached log") {
            if entries.isEmpty { Text("No log was attached.") }
            else {
                DisclosureGroup(isExpanded: $expanded) {
                    VStack(alignment: .leading, spacing: 12) {
                        ForEach(entries.indices, id: \.self) { i in
                            FeedbackLogRow(entry: entries[i])
                        }
                    }.padding(.top, 8)
                } label: {
                    Text(String(entries.count) + (entries.count == 1 ? " entry · " : " entries · ") + (expanded ? "Hide" : "Show"))
                }
            }
        }
    }
}

private struct ReportHistoryRow: View {
    let status: String
    let by: String
    let at: Date
    let note: String?
    let last: Bool
    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            VStack(spacing: 0) {
                Circle().fill(last ? B.primary : B.outline).frame(width: 9, height: 9)
                if !last { Rectangle().fill(B.outlineVariant).frame(width: 1) }
            }.padding(.top, 5).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(FeedbackReport.label(status)).font(.subheadline.weight(.semibold))
                Text(by + " · " + at.formatted()).font(.caption).foregroundStyle(B.onSurfaceVariant)
                if let note, !note.isEmpty { Text(note) }
            }.padding(.bottom, last ? 0 : 10)
        }.accessibilityElement(children: .combine)
    }
}

/// Keep actions reachable while long, accessible titles scroll with their content.
private struct FeedbackSurfaceBar: View {
    let actionTitle: String
    var actionIdentifier = ""
    let action: () -> Void
    var body: some View {
        HStack {
            BuildStamp()
            Spacer(minLength: 12)
            Button(action: action) {
                Text(actionTitle).frame(minWidth: 48, minHeight: 48).contentShape(Rectangle())
            }.accessibilityIdentifier(actionIdentifier)
        }.padding(.horizontal, 16).padding(.vertical, 4)
            .background(B.surface.ignoresSafeArea(edges: .top))
    }
}

private struct FeedbackHistory: View {
    @EnvironmentObject private var local: LocalAppletModel
    @ObservedObject var store: FeedbackReports
    @DialogDismiss private var dismiss
    @Environment(\.dynamicTypeSize) private var textSize
    @State private var filter: String
    @State private var detail: FeedbackReport?
    @State private var compose: FeedbackPresentation?
    @AppStorage("feedback.uploadOff") private var uploadOff = false

    init(initial: FeedbackKind, store: FeedbackReports) {
        self.store = store
        _filter = State(initialValue: initial == .bug ? "Bugs" : "Features")
    }

    private var deviceNoun: String {
        #if os(macOS)
        "Mac"
        #else
        UIDevice.current.userInterfaceIdiom == .pad ? "tablet" : "phone"
        #endif
    }

    private func resolve(_ id: Int64, confirmed: Bool) {
        do {
            try store.resolve(id, confirmed: confirmed)
            Task { await store.sendPendingResolutions() }
        } catch { store.problem = error.localizedDescription }
    }

    private var visible: [FeedbackReport] {
        store.reports.filter { report in
            switch filter {
            case "Bugs": return report.kind == .bug
            case "Features": return report.kind == .feature
            case "Open": return report.status != "confirmed"
            default: return true
            }
        }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("My reports").font(.title).accessibilityAddTraits(.isHeader)
                Picker("Filter", selection: $filter) {
                    ForEach(["All", "Bugs", "Features", "Open"], id: \.self) { Text($0) }
                }.pickerStyle(.segmented)
                let newKind: FeedbackKind = filter == "Features" ? .feature : .bug
                Button {
                    compose = FeedbackPresentation(kind: newKind, lastScreen: FeedbackCapture.lastScreen())
                } label: {
                    if textSize.isAccessibilitySize {
                        HStack(alignment: .top, spacing: 12) {
                            Image(newKind.iconAsset).resizable().scaledToFit().frame(width: 24, height: 24).accessibilityHidden(true)
                            Text(newKind.title).fixedSize(horizontal: false, vertical: true)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }.frame(minHeight: 44).contentShape(Rectangle())
                    } else { Label(newKind.title, image: newKind.iconAsset) }
                }
                if visible.isEmpty { Text(store.reports.isEmpty ? "No reports yet." : "No reports match this filter.") }
                ForEach(visible) { report in
                    HStack {
                        Button {
                            detail = report
                        } label: {
                            VStack(alignment: .leading) {
                                Text(report.description).lineLimit(2)
                                Text(FeedbackReport.label(report.status) + " · " + report.reference + " · " + report.filedDate.formatted(.dateTime.day().month(.abbreviated))).font(.caption)
                            }.frame(maxWidth: .infinity, alignment: .leading)
                        }.buttonStyle(.plain)
                        if report.remoteID == nil && report.localOnly != true && !uploadOff {
                            Button("Retry") { Task { await store.send(report) } }.disabled(store.sending.contains(report.id))
                        }
                    }.padding(.vertical, 8)
                    Divider()
                }
            }
        }.padding()
        .safeAreaInset(edge: .top, spacing: 0) { FeedbackSurfaceBar(actionTitle: "Done") { dismiss() } }
        .coveringBuildStampSurface(detail != nil || compose != nil)
        .task { await store.sendPendingResolutions(); await store.refresh() }
        .feedbackComposer(item: $compose) { item in
            FeedbackForm(kind: item.kind, store: store, lastScreen: item.lastScreen, sourceScreen: "My reports", activityStore: local.store)
        }
        .feedbackSurface(item: $detail) { selected in
            let report = store.reports.first { $0.id == selected.id } ?? selected
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text((report.kind == .bug ? "Bug" : "Feature request") + " · " + report.reference)
                        .font(.title).accessibilityAddTraits(.isHeader)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .fixedSize(horizontal: false, vertical: true)
                    HStack {
                        ReportStatusBadge(status: report.status)
                        if report.kind == .bug && report.noAutofix {
                            Text("Don’t auto-fix").font(.subheadline).padding(.horizontal, 8).padding(.vertical, 4)
                                .background(B.surfaceHigh, in: RoundedRectangle(cornerRadius: 8))
                        }
                    }
                    Text("Filed " + report.filedDate.formatted() + " by owner")
                    if report.remoteID != nil { Text("Sent to the Bridgit team") }
                    else if report.localOnly == true { Text("Kept on this " + deviceNoun + " (sending to the Bridgit team was off)") }
                    else { Text("Waiting to send to the Bridgit team") }
                    if let problem = store.problem ?? store.resolutionProblem { Text(problem).foregroundStyle(B.error) }
                    ReportSection(title: "Description") { Text(report.description).textSelection(.enabled) }
                    if let images = report.images, !images.isEmpty { ReportImages(images: images) }
                    ReportLog(entries: (report.attachedActivity?.map(\.feedbackPreview) ?? []) + (report.log ?? []))
                    ReportSection(title: "Context") {
                        Text("Filed from").font(.subheadline.bold())
                        Text("The form on this " + deviceNoun)
                        if let version = report.version { Text("Version " + version) }
                        if let model = report.deviceModel { Text("Model · " + model).accessibilityIdentifier("report-saved-model") }
                        if let system = report.system { Text(system) }
                        if let revision = report.buildRevision { Text("Commit · " + revision) }
                        if let configuration = report.buildConfiguration { Text("Configuration · " + configuration) }
                        if let screen = report.sourceScreen { Text("Screen · " + screen) }
                    }
                    ReportSection(title: "Status history") {
                        let moves = report.moves ?? []
                        VStack(alignment: .leading, spacing: 0) {
                            ReportHistoryRow(status: "new", by: "owner", at: report.filedDate, note: nil, last: moves.isEmpty)
                            ForEach(Array(moves.enumerated()), id: \.offset) { i, move in
                                ReportHistoryRow(status: move.status, by: move.by, at: move.at, note: move.note, last: i == moves.count - 1)
                            }
                        }
                    }
                }.padding()
            }
            .background(B.surface)
            .safeAreaInset(edge: .top, spacing: 0) {
                FeedbackSurfaceBar(actionTitle: "Done", actionIdentifier: "feedback.savedReport.done") { detail = nil }
            }
            .safeAreaInset(edge: .bottom) {
                if report.awaitsOwner {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("The Bridgit team marked this " + (report.status == "fixed" ? "fixed" : FeedbackReport.label(report.status).lowercased()) + ". Is it sorted?")
                        HStack {
                            Button("Still broken") { resolve(report.id, confirmed: false) }.buttonStyle(.bordered)
                            Spacer()
                            Button("Confirm") { resolve(report.id, confirmed: true) }.buttonStyle(.borderedProminent)
                        }
                    }.padding().frame(maxWidth: .infinity, alignment: .leading).background(.bar)
                }
            }
            #if os(macOS)
            .frame(minWidth: 400, minHeight: 320)
            #endif
        }
        #if os(macOS)
        .frame(minWidth: 460, minHeight: 360)
        #endif
    }
}

struct FeedbackForm: View {
    @State var kind: FeedbackKind
    @ObservedObject var store: FeedbackReports
    var lastScreen: Data?
    var sourceScreen = "More"
    var activityEntry: HostActivityEntry?
    var activityLog: [HostActivityEntry] = []
    @State private var includeScreen = true
    @State private var includeLog = true
    @State private var previewLog = false
    @State private var savedReport: FeedbackReport?
    @State private var confirmCancel = false
    @State private var largeDraftChoice = false
    @Environment(\.dynamicTypeSize) private var textSize
    @Environment(\.buildStampVisible) private var activeSurface
    var activityStore: AppletStore?
    @State private var regularLog: [HostActivityEntry]?
    @State private var logProblem: String?
    private var isLoadingLog: Bool { activityEntry == nil && regularLog == nil }

    private var activityAttachment: [HostActivityEntry] {
        activityEntry?.reportLog(includingRecent: activityLog) ?? regularLog ?? []
    }
    private var recentLog: [[String: String]] {
        activityAttachment.map(\.feedbackPreview)
    }
    private var logSummary: String {
        let count = activityEntry == nil ? (regularLog?.count ?? 0) : activityLog.count
        let summary = String(count) + (count == 1 ? " log entry" : " log entries")
        return activityEntry == nil ? summary : summary + ", plus the entry you picked"
    }
    @DialogDismiss private var dismiss
    @AppStorage("feedback.bug.draft") private var bug = ""
    @AppStorage("feedback.feature.draft") private var feature = ""
    @AppStorage("feedback.uploadOff") private var uploadOff = false
    @AppStorage("feedback.bug.autoFix") private var autoFix = true
    @State private var error: String?
    #if os(iOS)
    @State private var pickedPhoto: PhotosPickerItem?
    @State private var loadingPhoto = false
    #endif
    @State private var choosingImage = false
    @State private var images: [Data] = []
    private var draft: Binding<String> { kind == .bug ? $bug : $feature }
    private var descriptionHint: String { kind == .bug ? "What you expected, what you saw…" : "What would you like Bridgit to do?" }
    private var attachments: [Data] { (includeScreen ? lastScreen.map { [$0] } ?? [] : []) + images }
    var body: some View {
        Group {
            // A queued report can receive its team number while the confirmation is open.
            // Keep its live content, and allow large accessibility text to scroll.
            if let savedReport, textSize.isAccessibilitySize || savedReport.localOnly != true {
                let current = store.reports.first { $0.id == savedReport.id } ?? savedReport
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        Text("Report saved").font(.title2.bold()).accessibilityAddTraits(.isHeader)
                        Text(current.saveAcknowledgement)
                    }.frame(maxWidth: 480, alignment: .leading).padding()
                }
                .safeAreaInset(edge: .top, spacing: 0) { FeedbackSurfaceBar(actionTitle: "OK") { dismiss() } }
                .interactiveDismissDisabled()
                #if os(macOS)
                .frame(minWidth: 400, minHeight: 220)
                #endif
            } else {
                editor
                    .task {
                        guard isLoadingLog else { return }
                        do {
                            guard let activityStore else { throw DeviceAPIError(503, "storage_unavailable", "Activity storage is unavailable.") }
                            regularLog = try await activityStore.hostActivity(limit: 50)
                        } catch {
                            logProblem = error.localizedDescription
                            includeLog = false
                            regularLog = []
                        }
                    }
                    .interactiveDismissDisabled(hasDraft || savedReport != nil)
                    .alert("Report saved", isPresented: Binding(
                        get: { savedReport?.localOnly == true && !textSize.isAccessibilitySize },
                        set: { if !$0 { savedReport = nil } }
                    ), presenting: savedReport) { _ in
                        Button("OK") { dismiss() }
                    } message: { saved in
                        let current = store.reports.first { $0.id == saved.id } ?? saved
                        Text(current.saveAcknowledgement)
                    }
            }
        }.coveringBuildStampSurface(previewLog || largeDraftChoice)
    }

    private var editor: some View {
        ScrollView {
        VStack(alignment: .leading, spacing: 16) {
            formTitle
            Text(kind == .bug ? "Tell your agent to file a bug or a feature request. Or describe what went wrong." : "Tell your agent to file a bug or a feature request. Or describe what you’d like.")
                .font(.callout).foregroundStyle(B.onSurfaceVariant)
            if textSize.isAccessibilitySize {
                VStack(spacing: 8) {
                    ForEach(FeedbackKind.allCases) { choice in
                        kindChoice(choice)
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                            .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(B.outline, lineWidth: 1).allowsHitTesting(false))
                    }
                }
            } else {
                HStack(spacing: 0) {
                    ForEach(FeedbackKind.allCases) { choice in kindChoice(choice) }
                }
                .clipShape(Capsule())
                .overlay(Capsule().strokeBorder(B.outline, lineWidth: 1).allowsHitTesting(false))
            }
            descriptionPanel
            attachmentsPanel
            if let error { Text(error).foregroundStyle(B.error) }

        }.padding()
        }
        .scrollDismissesKeyboard(.immediately)
        .safeAreaInset(edge: .top, spacing: 0) {
            Group {
                if textSize.isAccessibilitySize {
                    VStack(alignment: .trailing, spacing: 6) {
                        BuildStamp()
                        ViewThatFits(in: .horizontal) {
                            HStack(spacing: 8) {
                                cancelButton.fixedSize(horizontal: true, vertical: false)
                                Spacer(minLength: 8)
                                submitButton.fixedSize(horizontal: true, vertical: false)
                            }
                            VStack(alignment: .trailing, spacing: 6) {
                                cancelButton
                                submitButton
                            }.frame(maxWidth: .infinity, alignment: .trailing)
                        }
                    }
                } else {
                    HStack { cancelButton; Spacer(); BuildStamp(); Spacer(); submitButton }
                }
            }.padding().background(B.surface)
                .opacity(activeSurface ? 1 : 0).accessibilityHidden(!activeSurface).allowsHitTesting(activeSurface)
        }
        .bridgitDialog(isPresented: $previewLog) {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text("Recent log").font(.headline).accessibilityAddTraits(.isHeader)
                    let entries = recentLog
                    if entries.isEmpty { Text("No log entries yet.") }
                    ForEach(entries.indices, id: \.self) { index in
                        FeedbackLogRow(entry: entries[index])
                    }
                }.frame(maxWidth: .infinity, alignment: .leading).padding()
            }.frame(minWidth: 280, minHeight: 240)
                .safeAreaInset(edge: .top, spacing: 0) { FeedbackSurfaceBar(actionTitle: "Done") { previewLog = false } }
                .buildStampSurface()
            #if os(iOS)
            .presentationDetents([.medium, .large])
            #endif
        }
        .alert("Keep this draft?", isPresented: $confirmCancel) {
            Button("Discard", role: .destructive, action: discardDraft)
            Button("Keep") { dismiss() }
        }
        .bridgitDialog(isPresented: $largeDraftChoice) {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text("Keep this draft?").font(.headline).accessibilityAddTraits(.isHeader)
                    Button("Discard", role: .destructive, action: discardDraft)
                    Button("Keep") { dismiss() }
                }.buttonStyle(.bordered)
                    .frame(maxWidth: .infinity, alignment: .leading).padding()
            }
            .safeAreaInset(edge: .top, spacing: 0) { FeedbackSurfaceBar(actionTitle: "Cancel") { largeDraftChoice = false } }
            .buildStampSurface()
        }
        .fileImporter(isPresented: $choosingImage, allowedContentTypes: [.image]) { result in
            do {
                let url = try result.get()
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                guard attachments.count < 5, let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize,
                      size <= 30 * 1024 * 1024 else {
                    throw DeviceAPIError(413, "image_too_large", "Choose an image smaller than 30 MB.")
                }
                try appendImage(Data(contentsOf: url))
            } catch let failure as CocoaError where failure.code == .userCancelled { }
            catch { self.error = error.localizedDescription }
        }
        #if os(macOS)
        .frame(minWidth: 450, minHeight: 320)
        #endif
    }

    private var descriptionPanel: some View {
        Card {
            VStack(alignment: .leading, spacing: 10) {
                Text(kind == .bug ? "What happened?" : "What would you like to see?").font(.headline)
                if kind == .bug {
                    Toggle("Attempt Auto-fix", isOn: $autoFix)
                    if !autoFix {
                        Text("Auto-fix is off: this is left for a person to look at. Nothing gets fixed automatically.")
                            .font(.callout).foregroundStyle(B.onSurfaceVariant)
                    }
                }
                Text("Description").font(.caption).foregroundStyle(B.onSurfaceVariant)
                ZStack(alignment: .topLeading) {
                    TextEditor(text: draft)
                        .accessibilityLabel(draft.wrappedValue.isEmpty ? "Description, " + descriptionHint : "Description")
                        .scrollContentBackground(.hidden)
                        .frame(minHeight: 100)
                    if draft.wrappedValue.isEmpty {
                        Text(descriptionHint)
                            .padding(6).foregroundStyle(.secondary).allowsHitTesting(false).accessibilityHidden(true)
                    }
                }
                .padding(4)
                .overlay(RoundedRectangle(cornerRadius: 6).stroke(B.outline, lineWidth: 1))
            }
        }
    }

    private func kindChoice(_ choice: FeedbackKind) -> some View {
        Button { kind = choice } label: {
            HStack(spacing: 8) {
                Image(decorative: choice.iconAsset).renderingMode(.template)
                    .resizable().scaledToFit().frame(width: 18, height: 18)
                Text(choice == .bug ? "Bug" : "Feature").fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, minHeight: 48)
            .foregroundStyle(kind == choice ? B.onSecondaryContainer : B.onSurface)
            .background(kind == choice ? B.secondaryContainer : B.surface)
            .contentShape(Rectangle())
        }.buttonStyle(.plain).accessibilityAddTraits(kind == choice ? .isSelected : [])
    }

    private var screenshotPicker: some View {
        #if os(iOS)
        PhotosPicker(selection: $pickedPhoto, matching: .images) {
            if textSize.isAccessibilitySize {
                HStack(alignment: .top, spacing: 12) {
                    Image(systemName: "plus").resizable().scaledToFit().frame(width: 24, height: 24).accessibilityHidden(true)
                    Text(images.isEmpty ? "Add screenshot" : "Add another")
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(12).frame(maxWidth: .infinity, minHeight: 48).contentShape(Rectangle())
                .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(B.outline, lineWidth: 1))
            } else {
                Label(images.isEmpty ? "Add screenshot" : "Add another", systemImage: "plus")
                    .padding(.horizontal, 12).frame(minHeight: 48).contentShape(Rectangle())
                    .overlay(Capsule().strokeBorder(B.outline, lineWidth: 1))
            }
        }.disabled(attachments.count >= 5 || loadingPhoto)
        .onChange(of: pickedPhoto) { _, item in
            guard let item else { return }
            loadingPhoto = true
            Task {
                defer { loadingPhoto = false; pickedPhoto = nil }
                do {
                    guard let data = try await item.loadTransferable(type: Data.self) else {
                        throw DeviceAPIError(422, "invalid_image", "Choose a screenshot that can be read.")
                    }
                    try appendImage(data)
                } catch { self.error = error.localizedDescription }
            }
        }
        #else
        Button(images.isEmpty ? "Add screenshot" : "Add another", systemImage: "photo") { choosingImage = true }.disabled(attachments.count >= 5)
        #endif
    }

    private var attachmentsPanel: some View {
        Card {
            VStack(alignment: .leading, spacing: 10) {
                if let lastScreen {
                    Toggle(isOn: $includeScreen) {
                        HStack(alignment: .top, spacing: 12) {
                            if let source = CGImageSourceCreateWithData(lastScreen as CFData, nil),
                               let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                                kCGImageSourceCreateThumbnailFromImageAlways: true,
                                kCGImageSourceThumbnailMaxPixelSize: 180
                               ] as CFDictionary) {
                                Image(decorative: image, scale: 1).resizable().scaledToFit()
                                    .frame(width: 40, height: 60).accessibilityHidden(true)
                            }
                            VStack(alignment: .leading, spacing: 4) {
                                Text("Include last screen").fixedSize(horizontal: false, vertical: true)
                                Text(sourceScreen + ", as you left it").font(.callout)
                                    .foregroundStyle(B.onSurfaceVariant).fixedSize(horizontal: false, vertical: true)
                            }
                        }
                    }
                    .accessibilityLabel("Include last screen, " + sourceScreen + ", as you left it")
                    .disabled(!includeScreen && images.count >= 5)
                }
                screenshotPicker
                if !images.isEmpty {
                    ScrollView(.horizontal) {
                        HStack {
                            ForEach(images.indices, id: \.self) { index in
                                if let source = CGImageSourceCreateWithData(images[index] as CFData, nil),
                                   let thumbnail = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                                    kCGImageSourceCreateThumbnailFromImageAlways: true,
                                    kCGImageSourceThumbnailMaxPixelSize: 150
                                   ] as CFDictionary) {
                                    VStack {
                                        Image(decorative: thumbnail, scale: 1).resizable().scaledToFit().frame(height: 60)
                                        Button("Remove") { images.remove(at: index) }
                                            .accessibilityLabel("Remove screenshot " + String(index + 1))
                                    }
                                }
                            }
                        }
                    }
                }
                if let logProblem { Text(logProblem).foregroundStyle(B.error) }
                Toggle("Include recent log", isOn: $includeLog)
                    .disabled(isLoadingLog || logProblem != nil)
                HStack {
                    Text(isLoadingLog ? "Loading activity…" : logSummary)
                    Button { previewLog = true } label: {
                        Text("Preview").frame(minWidth: 48, minHeight: 48).contentShape(Rectangle())
                    }
                }.font(.callout).disabled(isLoadingLog || logProblem != nil)
            }
        }
    }

    private var formTitle: some View { Text(kind.title).font(.headline).accessibilityAddTraits(.isHeader) }
    private var hasDraft: Bool { !draft.wrappedValue.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    private var cancelButton: some View {
        Button {
            if !hasDraft { dismiss() }
            else if textSize.isAccessibilitySize { largeDraftChoice = true }
            else { confirmCancel = true }
        } label: { Text("Cancel").frame(minWidth: 48, minHeight: 48).contentShape(Rectangle()) }
    }
    private func discardDraft() {
        draft.wrappedValue = ""
        if kind == .bug { autoFix = true }
        dismiss()
    }
    private var canSubmit: Bool {
        #if os(iOS)
        if loadingPhoto { return false }
        #endif
        return !isLoadingLog && hasDraft && draft.wrappedValue.utf16.count <= 10_000 && savedReport == nil
    }

    private var submitButton: some View {
        Button {
            guard savedReport == nil else { return }
            do {
                let report = try store.save(kind: kind, text: draft.wrappedValue.trimmingCharacters(in: .whitespacesAndNewlines), autoFix: autoFix, images: attachments, log: [], localOnly: uploadOff, sourceScreen: sourceScreen,
                    logEntryID: activityEntry?.id, attachedActivity: includeLog ? activityAttachment : nil)
                savedReport = report
                draft.wrappedValue = ""
                if kind == .bug { autoFix = true }
                if !uploadOff { Task { await store.send(report) } }
            } catch { self.error = error.localizedDescription }
        } label: {
            Text("Submit")
                .padding(.horizontal, 14).frame(minHeight: 48)
                .foregroundStyle(canSubmit ? B.onPrimary : B.onSurfaceVariant)
                .background(canSubmit ? B.primary : B.surfaceHigh, in: Capsule())
        }.buttonStyle(.plain).disabled(!canSubmit)
    }

    private func appendImage(_ data: Data) throws {
        guard attachments.count < 5 else { throw DeviceAPIError(422, "too_many_images", "Attach up to five screenshots.") }
        let jpeg = try LocalAppletModel.jpeg(data, maxEdge: 1280, quality: 0.75)
        guard jpeg.count <= 1_000_000 else { throw DeviceAPIError(413, "image_too_large", "Choose a smaller screenshot.") }
        images.append(jpeg)
        error = nil
    }

}
