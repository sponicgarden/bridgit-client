import CoreFoundation
import CryptoKit
import Foundation

public enum AppletInstallMode { case create, update, upsert }

public struct AppletBatch: Codable, Equatable {
    public var state: String
    public var total: Int
    public var completed: Int
    public var failed: Int
    public var error: String?
    public init(state: String = "idle", total: Int = 0, completed: Int = 0, failed: Int = 0, error: String? = nil) {
        self.state = state; self.total = total; self.completed = completed; self.failed = failed; self.error = error
    }
}

/// Executes declared JSON steps; it never evaluates package code, URLs, SQL or arbitrary expressions.
public actor AppletEngine {
    public static let defaultBatchRowLimit = 50
    public static let maximumRecordedItems = AppletExecutionValidator.maximumRecordedItems
    public static let defaultPaidAttemptsPerDay = AppletExecutionValidator.defaultPaidPerDay
    public typealias Speech = (String, [String: Any], String) async throws -> [String: Any]
    private var speech: Speech?
    public func setSpeechHandler(_ handler: @escaping Speech) { speech = handler }
    public typealias Appraiser = (Data, Int, String) async throws -> [String: Any]
    public let store: AppletStore
    private let checkPrerequisites: ([String]) async throws -> Void
    private let appraiser: Appraiser
    private var active = Set<String>()
    private var runningApplets: [String: Int] = [:]
    private var installing = Set<String>()
    private var tasks: [String: Task<Void, Never>] = [:]
    private var starting = Set<String>()
    private var batches: [String: AppletBatch] = [:]
    private var batchButtons: [String: String] = [:]
    private var executionEpochs: [String: UUID] = [:]
    public init(store: AppletStore, speech: Speech? = nil, checkPrerequisites: @escaping ([String]) async throws -> Void = { _ in }, appraiser: @escaping Appraiser) {
        self.store = store
        self.speech = speech
        self.checkPrerequisites = checkPrerequisites
        self.appraiser = appraiser
    }

    public func validate(_ data: Data) async throws -> [String: Any] {
        guard data.count <= 1_048_576, let package = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw DeviceAPIError(422, "invalid_applet", "Use an applet JSON object no larger than 1 MB.") }
        try AppletExecutionValidator.validate(package)
        if let schema = package["config_schema"] {
            guard let object = schema as? [String: Any], object["type"] as? String == "object",
                  object["properties"] == nil || (object["properties"] as? [String: Any])?.isEmpty == true,
                  object["required"] == nil || (object["required"] as? [String])?.isEmpty == true,
                  object["additionalProperties"] == nil || (object["additionalProperties"] as? Bool) == false else { throw DeviceAPIError(422, "unsupported_configuration", "This runtime does not implement configured package inputs yet.") }
        }
        if let defaults = package["defaults"] { guard (defaults as? [String: Any])?.isEmpty == true else { throw DeviceAPIError(422, "unsupported_configuration", "Nonempty package defaults need a configuration adapter.") } }
        if let inputs = (package["ui"] as? [String: Any])?["inputs"] { guard (inputs as? [String: Any])?.isEmpty == true else { throw DeviceAPIError(422, "unsupported_configuration", "Package input controls are not implemented here yet.") } }
        if let value = package["permissions"] {
            guard let permissions = value as? [String: Any], Set(permissions.keys).isSubset(of: ["required", "optional", "remote", "automatic_grants"]) else { throw DeviceAPIError(422, "invalid_applet", "Permissions must use the declared fields.") }
            for field in ["required", "optional", "remote"] {
                if let values = permissions[field] { guard let strings = values as? [String], strings.count <= 32, Set(strings).count == strings.count else { throw DeviceAPIError(422, "invalid_applet", "Permission lists need distinct strings.") } }
            }
            if let value = permissions["automatic_grants"] { guard let number = value as? NSNumber, CFGetTypeID(number) == CFBooleanGetTypeID() else { throw DeviceAPIError(422, "invalid_applet", "automatic_grants must be a boolean.") } }
            guard (permissions["remote"] as? [String] ?? []).isEmpty else { throw DeviceAPIError(422, "unsupported_capability", "This runtime does not forward applet work to other devices yet.") }
        }
        let required = (package["permissions"] as? [String: Any])?["required"] as? [String] ?? []
        let supported = Set(["camera", "interactions", "workflows", "decisions"] + (speech == nil ? [] : ["mic", "speaker"]))
        guard required.allSatisfy({ supported.contains($0) }) else { throw DeviceAPIError(422, "unsupported_capability", "This applet needs a capability that this Apple build does not implement.") }
        if let automatic = (package["permissions"] as? [String: Any])?["automatic_grants"] as? Bool, automatic { throw DeviceAPIError(422, "automatic_grants_refused", "Applet permissions need the owner's approval.") }
        try await store.validateInstallation(package)
        try await checkPrerequisites(required)
        return package
    }
    public func install(_ data: Data, mode: AppletInstallMode = .upsert, requestedBy: HostAgent? = nil, didInstall: ((String) async -> Void)? = nil) async throws {
        let package = try await validate(data)
        guard let id = package["id"] as? String else { throw DeviceAPIError(422, "invalid_applet", "The applet needs an ID.") }
        guard runningApplets[id, default: 0] == 0, tasks[id] == nil, !starting.contains(id), installing.insert(id).inserted else { throw DeviceAPIError(409, "applet_busy", "Stop this applet's work before updating it.") }
        defer { installing.remove(id) }
        try await store.install(package, mode: mode, requestedBy: requestedBy)
        await didInstall?(id)
    }
    public func addPhoto(applet: String, jpeg: Data, table: String = "photos", column: String = "photo", initial: [String: Any] = ["status": "new"], requestedBy: HostAgent? = nil) async throws -> [String: Any] {
        try await store.addPhotoRow(applet: applet, jpeg: jpeg, table: table, column: column, initial: initial, requestedBy: requestedBy)
    }

    public static func resolve(_ value: Any, results: [String: Any], trigger: [String: Any], item: Any? = nil, depth: Int = 0) throws -> Any {
        guard depth < 32 else { throw DeviceAPIError(422, "invalid_reference", "Applet references are nested too deeply.") }
        func path(_ root: Any?, _ text: String) -> Any {
            if text.isEmpty { return root ?? NSNull() }
            return text.split(separator: ".").reduce(root ?? NSNull()) { current, part in
                if let object = current as? [String: Any] { return object[String(part)] ?? NSNull() }
                if let list = current as? [Any], let index = Int(part), list.indices.contains(index) { return list[index] }
                return NSNull()
            }
        }
        if let object = value as? [String: Any] {
            if let ref = object["$trigger"] as? String { return path(trigger, ref) }
            if let ref = object["$step"] as? String { return path(results, ref) }
            if let ref = object["$item"] as? String { return path(item, ref) }
            return try object.mapValues { try resolve($0, results: results, trigger: trigger, item: item, depth: depth + 1) }
        }
        if let list = value as? [Any] { return try list.map { try resolve($0, results: results, trigger: trigger, item: item, depth: depth + 1) } }
        return value
    }
    public func run(applet: String, flow flowID: String, with values: [String: Any], invocationID: String? = nil, requestedBy: HostAgent? = nil, authorize: (() async throws -> Void)? = nil, authorizeSpeech: ((String) async throws -> Void)? = nil) async throws -> [String: Any] {
        guard !installing.contains(applet) else { throw DeviceAPIError(409, "applet_busy", "This applet is being updated.") }
        if executionEpochs[applet] == nil { executionEpochs[applet] = UUID() }
        let epoch = executionEpochs[applet]!
        runningApplets[applet, default: 0] += 1
        defer { runningApplets[applet, default: 0] -= 1 }
        try await ensureActive(applet: applet, epoch: epoch, authorize: authorize)
        let package = try await store.package(applet)
        try AppletExecutionValidator.validate(package)
        guard let flow = ((package["processing"] as? [String: Any])?["flows"] as? [[String: Any]])?.first(where: { $0["id"] as? String == flowID }),
              let steps = flow["steps"] as? [[String: Any]] else { throw DeviceAPIError(404, "flow_not_found", "The applet has no such flow.") }
        let inputs = try AppletExecutionValidator.inputs(values, trigger: flow["trigger"] as? [String: Any] ?? [:])
        let trigger = ["with": inputs]
        let packageData = try JSONSerialization.data(withJSONObject: package, options: [.sortedKeys])
        let revision = SHA256.hash(data: packageData).map { String(format: "%02x", $0) }.joined()
        var keyObject: [String: Any] = ["applet": applet, "flow": flowID, "package_revision": revision, "trigger": trigger]
        let intentData = try JSONSerialization.data(withJSONObject: keyObject, options: [.sortedKeys])
        let intent = SHA256.hash(data: intentData).map { String(format: "%02x", $0) }.joined()
        if let invocationID { keyObject["invocation_id"] = invocationID }
        let keyData = try JSONSerialization.data(withJSONObject: keyObject, options: [.sortedKeys])
        let key = "flow:" + SHA256.hash(data: keyData).map { String(format: "%02x", $0) }.joined()
        guard active.insert(key).inserted else { throw DeviceAPIError(409, "flow_busy", "This photo is already in a running flow.") }
        defer { active.remove(key) }
        var results: [String: Any] = [:]
        for (index, step) in steps.enumerated() {
            try await ensureActive(applet: applet, epoch: epoch, authorize: authorize)
            guard let id = step["id"] as? String, let type = step["type"] as? String else { throw DeviceAPIError(422, "invalid_step", "A flow step needs an ID and type.") }
            let checkpoint = key + ":\(index):\(id)"
            switch type {
            case "appraise":
                if let cached = try await store.state(checkpoint), let object = try JSONSerialization.jsonObject(with: Data(cached.utf8)) as? [String: Any] { results[id] = object; continue }
                let attempt = "paid-attempt:\(applet):intent:\(intent):\(index):\(id)"
                guard try await store.state(attempt) == nil else { throw DeviceAPIError(409, "appraisal_outcome_unknown", "An interrupted appraisal may already have been charged. Review it before explicitly allowing another attempt.") }
                guard let ref = try Self.resolve(step["photo"] ?? NSNull(), results: results, trigger: trigger) as? String else { throw DeviceAPIError(422, "invalid_photo", "The appraise step needs a kept photo.") }
                let bytes = try await store.photo(applet: applet, ref: ref)
                try await store.beginActionAttempt(applet: applet, flow: flowID, limit: flow["max_paid_per_day"] as? Int ?? AppletExecutionValidator.defaultPaidPerDay, key: attempt, unknownCode: "appraisal_outcome_unknown", requestedBy: requestedBy)
                let reply: [String: Any]
                do {
                    try await ensureActive(applet: applet, epoch: epoch, authorize: authorize)
                    reply = try await appraiser(bytes, step["max_items"] as? Int ?? 20, applet)
                }
                catch {
                    // A definite rejection before work is safe to retry later. Network errors,
                    // cancellation and server failures retain the uncertain-outcome marker.
                    if let rejected = error as? DeviceAPIError, (400...499).contains(rejected.status), rejected.status != 408 {
                        try await store.removeState(attempt)
                    }
                    throw error
                }
                guard let items = reply["items"] as? [[String: Any]], items.count <= (step["max_items"] as? Int ?? 20) else { throw DeviceAPIError(502, "invalid_model_response", "The model did not return a bounded item list.") }
                // Cache the paid answer before honoring cancellation, so a resume never pays twice for it.
                let data = try JSONSerialization.data(withJSONObject: reply, options: [.sortedKeys])
                try await store.setAppletState(applet: applet, key: checkpoint, value: String(decoding: data, as: UTF8.self))
                try await store.removeState(attempt)
                results[id] = reply
            case "hear", "speak":
                guard let speech else { throw DeviceAPIError(501, "not_implemented", "This runtime has no speech adapter.") }
                let required = (package["permissions"] as? [String: Any])?["required"] as? [String] ?? []
                guard required.contains(type == "hear" ? "mic" : "speaker") else { throw DeviceAPIError(403, "applet_permission_required", "The applet must declare its speech capability.") }
                try await authorizeSpeech?(type)
                if let cached = try await store.state(checkpoint), let object = try JSONSerialization.jsonObject(with: Data(cached.utf8)) as? [String: Any] { results[id] = object; continue }
                let attempt = "action-attempt:\(applet):intent:\(intent):\(index):\(id)"
                guard try await store.state(attempt) == nil else { throw DeviceAPIError(409, "action_outcome_unknown", "An interrupted speech action may already have run. Review before explicitly allowing another attempt.") }
                var arguments: [String: Any] = [:]
                for (name, value) in step where !["id", "type"].contains(name) { arguments[name] = try Self.resolve(value, results: results, trigger: trigger) }
                let paidLimit = arguments["allow_cloud"] as? Bool == true ? (flow["max_paid_per_day"] as? Int ?? AppletExecutionValidator.defaultPaidPerDay) : nil
                try await store.beginActionAttempt(applet: applet, flow: flowID, limit: paidLimit, key: attempt, unknownCode: "action_outcome_unknown", requestedBy: requestedBy)
                let reply: [String: Any]
                do {
                    try await ensureActive(applet: applet, epoch: epoch, authorize: authorize)
                    reply = try await speech(type, arguments, applet)
                }
                catch {
                    if let rejected = error as? DeviceAPIError, ["capability_disabled", "permission_denied", "speech_cloud_consent_required", "invalid_parameter", "applet_permission_required", "budget_unconfirmed", "budget_reached", "applet_budget", "paid_cap"].contains(rejected.code) { try await store.removeState(attempt) }
                    throw error
                }
                let data = try JSONSerialization.data(withJSONObject: reply, options: [.sortedKeys])
                try await store.setAppletState(applet: applet, key: checkpoint, value: String(decoding: data, as: UTF8.self)); try await store.removeState(attempt)
                results[id] = reply
                try Task.checkCancellation(); try await authorizeSpeech?(type)
            case "record":
                guard let table = step["table"] as? String, let row = step["row"] as? [String: Any] else { throw DeviceAPIError(422, "invalid_step", "A record step needs a table and row.") }
                let items: [Any]
                if let each = step["for_each"] {
                    guard let list = try Self.resolve(each, results: results, trigger: trigger) as? [Any], list.count <= AppletExecutionValidator.maximumRecordedItems else { throw DeviceAPIError(422, "invalid_reference", "for_each must resolve to a list of at most 50 items.") }; items = list
                } else { items = [NSNull()] }
                let rows = try items.map { item in try row.mapValues { try Self.resolve($0, results: results, trigger: trigger, item: item) } }
                results[id] = try await store.recordOnce(applet: applet, table: table, rows: rows, checkpoint: checkpoint, requestedBy: requestedBy)
            case "update":
                guard let table = step["table"] as? String,
                      let rowID = try Self.resolve(step["row"] ?? NSNull(), results: results, trigger: trigger) as? String,
                      let changes = step["set"] as? [String: Any] else { throw DeviceAPIError(422, "invalid_step", "An update step needs a table, row and changes.") }
                let set = try changes.mapValues { try Self.resolve($0, results: results, trigger: trigger) }
                _ = try await store.updateWorkflowRow(applet: applet, table: table, rowID: rowID, values: set, requestedBy: requestedBy)
                results[id] = ["row_id": rowID]
            default: throw DeviceAPIError(422, "unsupported_step", "This step is not implemented in this build.")
            }
        }
        try await ensureActive(applet: applet, epoch: epoch, authorize: authorize)
        return results
    }
    private func ensureActive(applet: String, epoch: UUID, authorize: (() async throws -> Void)?) async throws {
        try Task.checkCancellation()
        let paused = try await store.paused(applet)
        try await authorize?()
        try Task.checkCancellation()
        guard executionEpochs[applet] == epoch, !paused else { throw DeviceAPIError(409, "applet_paused", "This applet was paused or its execution changed.") }
    }
    public func pause(applet: String, paused: Bool, requestedBy: HostAgent? = nil) async throws {
        if requestedBy?.ownerDirectPeers != nil {
            try await store.setPaused(applet, value: paused, requestedBy: requestedBy)
            executionEpochs[applet] = UUID(); tasks[applet]?.cancel()
        } else {
            executionEpochs[applet] = UUID(); tasks[applet]?.cancel()
            try await store.setPaused(applet, value: paused)
        }
    }
    public func remove(applet: String, requestedBy: HostAgent? = nil, didRemove: ((String) async -> Void)? = nil) async throws -> [String] {
        guard runningApplets[applet, default: 0] == 0, tasks[applet] == nil, !starting.contains(applet), installing.insert(applet).inserted else { throw DeviceAPIError(409, "applet_busy", "Stop this applet's work before removing it.") }
        defer { installing.remove(applet) }
        let retained = try await store.removeApplet(applet, requestedBy: requestedBy)
        executionEpochs[applet] = UUID()
        batches[applet] = nil; batchButtons[applet] = nil
        await didRemove?(applet)
        return retained
    }
    /// Owner-only acknowledgement: callers must explain that another charge is possible.
    public func allowUncertainRetry(applet: String, requestedBy: HostAgent? = nil) async throws {
        guard tasks[applet] == nil, !starting.contains(applet), runningApplets[applet, default: 0] == 0 else { throw DeviceAPIError(409, "batch_busy", "Stop the batch before reviewing interrupted appraisals.") }
        try await store.resetAppletActionAttempts(applet: applet, requestedBy: requestedBy)
    }
    public func batch(applet: String) async -> AppletBatch {
        do {
            let snapshot = try await batchSnapshot(applet: applet)
            return snapshot.last.flatMap { snapshot.states[$0] } ?? .init()
        } catch { return .init(state: "unavailable", error: error.localizedDescription) }
    }
    /// Only declared batch buttons are advertised. Saved progress is retained per
    /// button; an interrupted process cannot fabricate idle/success for running work.
    public func batchStates(applet: String) async throws -> [String: AppletBatch] {
        let package = try await store.package(applet)
        let card = (package["ui"] as? [String: Any])?["card"] as? [String: Any] ?? [:]
        let buttons = (card["pieces"] as? [[String: Any]] ?? []).filter { $0["type"] as? String == "buttons" }.flatMap { $0["items"] as? [[String: Any]] ?? [] }
        let declared = Set(buttons.compactMap { button -> String? in
            guard let action = button["do"] as? [String: Any], action["flow"] != nil, action["for_each"] != nil else { return nil }
            return button["id"] as? String
        })
        let saved = try await batchSnapshot(applet: applet)
        return Dictionary(uniqueKeysWithValues: declared.map { ($0, saved.states[$0] ?? .init()) })
    }
    private func batchSnapshot(applet: String) async throws -> (last: String?, states: [String: AppletBatch]) {
        var last: String?, states: [String: AppletBatch] = [:]
        if let text = try await store.state("applet-batch:" + applet) {
            let value = try JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any] ?? [:]
            last = value["last_button"] as? String
            states = try JSONDecoder().decode([String: AppletBatch].self, from: JSONSerialization.data(withJSONObject: value["states"] as? [String: Any] ?? [:]))
            for id in states.keys where states[id]?.state == "running" { states[id]?.state = "interrupted" }
        }
        if let button = batchButtons[applet], let batch = batches[applet] { last = button; states[button] = batch }
        return (last, states)
    }
    @discardableResult private func persistBatch(applet: String, requestedBy: HostAgent? = nil) async -> Bool {
        do {
            let snapshot = try await batchSnapshot(applet: applet)
            guard let last = snapshot.last else { return true }
            let body = try JSONSerialization.jsonObject(with: JSONEncoder().encode(snapshot.states))
            let bytes = try JSONSerialization.data(withJSONObject: ["last_button": last, "states": body], options: [.sortedKeys])
            try await store.setAppletState(applet: applet, key: "applet-batch:" + applet, value: String(decoding: bytes, as: UTF8.self), requestedBy: requestedBy)
            return true
        } catch { batches[applet]?.state = "blocked"; batches[applet]?.error = error.localizedDescription; return false }
    }
    public func cancelBatch(applet: String) { tasks[applet]?.cancel() }
    public func cancelBatch(applet: String, button: String, requestedBy: HostAgent? = nil) async throws -> AppletBatch {
        let states = try await batchStates(applet: applet)
        guard states[button] != nil else { throw DeviceAPIError(404, "batch_not_found", "This applet has no such batch button.") }
        try await store.requireCurrentWorkflow(applet: applet, requestedBy: requestedBy)
        if batchButtons[applet] == button { tasks[applet]?.cancel(); await tasks[applet]?.value }
        return try await batchStates(applet: applet)[button] ?? .init()
    }
    public func waitForBatch(applet: String) async { await tasks[applet]?.value }
    public func startBatch(applet: String, button buttonID: String = "create", requestedBy: HostAgent? = nil, authorize: (() async throws -> Void)? = nil, authorizeSpeech: ((String) async throws -> Void)? = nil) async throws {
        guard try await !store.paused(applet) else { throw DeviceAPIError(409, "applet_paused", "This applet is paused.") }
        try await authorize?()
        guard tasks[applet] == nil, !installing.contains(applet), starting.insert(applet).inserted else { throw DeviceAPIError(409, "batch_busy", "This applet already has a running batch.") }
        defer { starting.remove(applet) }
        let package = try await store.package(applet)
        try AppletExecutionValidator.validate(package)
        let card = (package["ui"] as? [String: Any])?["card"] as? [String: Any] ?? [:]
        let pieces = card["pieces"] as? [[String: Any]] ?? []
        guard let button = pieces.filter({ $0["type"] as? String == "buttons" }).flatMap({ $0["items"] as? [[String: Any]] ?? [] }).first(where: { $0["id"] as? String == buttonID }),
              let action = button["do"] as? [String: Any], let flow = (action["flow"] as? [String: Any])?["$flow"] as? String,
              let source = (action["for_each"] as? [String: Any])?["$source"] as? String,
              let sourceDef = (card["sources"] as? [String: [String: Any]])?[source], let table = sourceDef["table"] as? String else {
            throw DeviceAPIError(422, "unsupported_action", "This button does not declare a supported flow batch.")
        }
        var rows = try await store.rows(applet: applet, table: table, limit: sourceDef["limit"] as? Int ?? 500)
        if let condition = action["only_if"] as? [String: Any], let column = condition["item"] as? String, let excluded = condition["is_not"] as? String {
            rows = try rows.filter { try Self.resolve(["$item": column], results: [:], trigger: [:], item: $0) as? String != excluded }
        }
        rows = Array(rows.prefix(action["max"] as? Int ?? Self.defaultBatchRowLimit))
        guard let definition = ((package["processing"] as? [String: Any])?["flows"] as? [[String: Any]])?.first(where: { $0["id"] as? String == flow }) else { throw DeviceAPIError(404, "flow_not_found", "The batch names no installed flow.") }
        let mapping = action["with"] as? [String: Any] ?? [:]
        // Validate every element before the first effect, just as Android batches do.
        let selected = try rows.map { row in
            let values = try mapping.mapValues { try Self.resolve($0, results: [:], trigger: [:], item: row) }
            guard let rowID = row["id"] as? String else { throw DeviceAPIError(500, "stored_data_invalid", "The source row has no stable identity.") }
            return (rowID, try AppletExecutionValidator.inputs(values, trigger: definition["trigger"] as? [String: Any] ?? [:]))
        }
        try await authorize?()
        let previous = batches[applet], previousButton = batchButtons[applet]
        batches[applet] = .init(state: "running", total: selected.count); batchButtons[applet] = buttonID
        guard await persistBatch(applet: applet, requestedBy: requestedBy) else {
            if requestedBy?.ownerDirectPeers != nil {
                batches[applet] = previous; batchButtons[applet] = previousButton
                try await store.requireCurrentWorkflow(applet: applet, requestedBy: requestedBy)
            }
            throw DeviceAPIError(503, "storage_unavailable", "Batch state could not be saved.")
        }
        tasks[applet] = Task { await self.performBatch(applet: applet, button: buttonID, flow: flow, inputs: selected,
            requestedBy: requestedBy, authorize: authorize, authorizeSpeech: authorizeSpeech) }
    }
    private func performBatch(applet: String, button: String, flow: String, inputs: [(String, [String: Any])], requestedBy: HostAgent?, authorize: (() async throws -> Void)?, authorizeSpeech: ((String) async throws -> Void)?) async {
        defer { tasks[applet] = nil }
        for (rowID, values) in inputs {
            do {
                try Task.checkCancellation()
                _ = try await run(applet: applet, flow: flow, with: values, invocationID: "batch:" + button + ":" + rowID, requestedBy: requestedBy, authorize: authorize, authorizeSpeech: authorizeSpeech)
                batches[applet]?.completed += 1
            } catch is CancellationError { batches[applet]?.state = "cancelled"; await persistBatch(applet: applet); return }
            catch {
                batches[applet]?.failed += 1; batches[applet]?.error = error.localizedDescription
                if let error = error as? DeviceAPIError, ["budget_unconfirmed", "budget_reached", "applet_budget", "paid_cap", "model_consent_required", "install_not_registered", "appraisal_outcome_unknown", "action_outcome_unknown", "capability_disabled", "permission_denied", "speech_cloud_consent_required", "applet_paused", "agent_revoked", "agent_not_allowed", "wrong_device", "foreground_required"].contains(error.code) {
                    batches[applet]?.state = "blocked"; await persistBatch(applet: applet); return
                }
            }
            guard await persistBatch(applet: applet) else { return }
        }
        batches[applet]?.state = "done"
        await persistBatch(applet: applet)
    }
}
