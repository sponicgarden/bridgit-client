import Combine
import Foundation

// Computer use on the Mac (A0112, with amendments A0113 and A0114).
//
// An agent sees the screen and acts in any app through the macOS Accessibility API and
// synthesized input. Whether it may is decided in two layers, newest amendment first:
//
// 1. Base default: off on every device. Installing or updating Bridgit never turns it on.
// 2. Superpowers default: for an agent that holds Superpowers (agents.admin), the default is on.
// 3. A specific choice wins: "on" or "off", set by the owner or an agent, stands until someone
//    sets the other one or clears it back to "inherit". A specific off holds even while the
//    caller has Superpowers; a specific on holds even after Superpowers is turned off.
//
// Setting "on" needs Superpowers (or the owner in the app). "off" and "inherit" need only
// settings.write: neither can give an agent more than a Superpowers agent already has.
// The macOS Accessibility and Screen Recording permissions stay the owner's step in System
// Settings: an agent can ask macOS to show the prompt, never grant it.

public enum ComputerUseSetting: String, Codable, CaseIterable, Sendable {
    case inherit, on, off
}

/// The persisted choice and who made it. Agent names are owner-assigned, never secrets.
public struct ComputerUseChoice: Codable, Equatable, Sendable {
    public var setting: ComputerUseSetting
    public var changedAt: Date?
    public var changedBy: String?
    public var changedByAgentID: String?
    /// owner, agent_superpowers or agent.
    public var source: String?
    public init(setting: ComputerUseSetting = .inherit, changedAt: Date? = nil, changedBy: String? = nil,
                changedByAgentID: String? = nil, source: String? = nil) {
        self.setting = setting; self.changedAt = changedAt; self.changedBy = changedBy
        self.changedByAgentID = changedByAgentID; self.source = source
    }
    enum CodingKeys: String, CodingKey {
        case setting, source
        case changedAt = "changed_at", changedBy = "changed_by", changedByAgentID = "changed_by_agent_id"
    }
}

public struct ComputerUseResolution: Equatable, Sendable {
    public let enabled: Bool
    /// default_off, default_from_superpowers, specifically_enabled or specifically_disabled.
    public let reason: String
    public let label: String
}

public struct ComputerUsePermissions: Equatable, Sendable {
    public var accessibility: Bool
    public var screenRecording: Bool
    public init(accessibility: Bool, screenRecording: Bool) {
        self.accessibility = accessibility; self.screenRecording = screenRecording
    }
    public static let none = ComputerUsePermissions(accessibility: false, screenRecording: false)
    public var complete: Bool { accessibility && screenRecording }
    /// The exact owner step left, or nil when both permissions are on.
    public var remainingHumanStep: String? {
        var missing: [String] = []
        if !accessibility { missing.append("Accessibility") }
        if !screenRecording { missing.append("Screen & System Audio Recording") }
        guard !missing.isEmpty else { return nil }
        return "On this Mac, open System Settings › Privacy & Security › " + missing.joined(separator: " and ")
            + ", and switch on Bridgit. macOS may ask to quit and reopen Bridgit."
    }
}

public enum ComputerUsePolicy {
    public static let stateKey = "computer-use:choice"
    public static let superpowersGrant = HostOperation.administrator.rawValue

    public static func hasSuperpowers(_ agent: HostAgent) -> Bool {
        agent.viewer != true && agent.operations.contains(.administrator)
    }

    public static func resolve(_ setting: ComputerUseSetting, superpowers: Bool) -> ComputerUseResolution {
        switch setting {
        case .on: return .init(enabled: true, reason: "specifically_enabled", label: "on (specifically enabled)")
        case .off: return .init(enabled: false, reason: "specifically_disabled", label: "off (specifically disabled)")
        case .inherit:
            return superpowers
                ? .init(enabled: true, reason: "default_from_superpowers", label: "on (default from superpowers)")
                : .init(enabled: false, reason: "default_off", label: "off (default)")
        }
    }

    /// `agent` nil is the owner acting in the app. Grants are checked before this by the host.
    public static func authorize(_ next: ComputerUseSetting, agent: HostAgent?) throws {
        guard let agent else { return }
        guard agent.viewer != true else { throw DeviceAPIError(403, "viewer_read_only", "A viewer link cannot change computer use.") }
        if next == .on, !hasSuperpowers(agent) {
            throw DeviceAPIError(403, "superpowers_required",
                "Turning computer use on needs the owner's Superpowers grant (" + superpowersGrant + ") for this agent, or the owner in the Bridgit app.",
                reason: superpowersGrant)
        }
    }

    static func source(for agent: HostAgent?) -> String {
        guard let agent else { return "owner" }
        return hasSuperpowers(agent) ? "agent_superpowers" : "agent"
    }

    static func message(_ next: ComputerUseSetting, previous: ComputerUseSetting, agent: HostAgent?) -> String {
        let who = agent.map { HostLogNote.scrub($0.name, max: 80) + (hasSuperpowers($0) ? " (Superpowers)" : "") } ?? "You"
        let what: String
        switch next {
        case .on: what = "turned computer use on"
        case .off: what = "turned computer use off"
        case .inherit: what = "set computer use to follow Superpowers"
        }
        return who + " " + what + " (was " + previous.rawValue + ")"
    }
}

/// One validated input action. Coordinates are global display points with the origin at the
/// top-left of the main display, matching the screenshot's point size.
public struct ComputerUseAction: Equatable, Sendable {
    public enum Kind: String, CaseIterable, Sendable {
        case click, doubleClick = "double_click", rightClick = "right_click", move, drag, scroll
        case type, key, press, setValue = "set_value", focus, activate
    }
    public var kind: Kind
    public var x: Double?, y: Double?, toX: Double?, toY: Double?
    public var dx: Int?, dy: Int?
    public var text: String?
    public var key: String?
    public var modifiers: [String]
    public var pid: Int32?
    public var bundleID: String?
    public var element: String?

    public static let modifierNames: Set<String> = ["cmd", "shift", "option", "control", "fn"]
    public static let maximumText = 2000
    static let fields: Set<String> = ["action", "x", "y", "to_x", "to_y", "dx", "dy", "text", "key", "modifiers", "pid", "bundle_id", "element"]

    public init(_ body: [String: Any]) throws {
        guard Set(body.keys).isSubset(of: Self.fields) else {
            throw DeviceAPIError(422, "invalid_parameter", "Unsupported field. Use " + Self.fields.sorted().joined(separator: ", ") + ".")
        }
        guard let raw = body["action"] as? String, let kind = Kind(rawValue: raw) else {
            throw DeviceAPIError(422, "invalid_parameter", "action must be one of " + Kind.allCases.map(\.rawValue).joined(separator: ", ") + ".")
        }
        func number(_ name: String) throws -> Double? {
            guard let value = body[name] else { return nil }
            guard let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID(), number.doubleValue.isFinite,
                  abs(number.doubleValue) <= 100_000 else { throw DeviceAPIError(422, "invalid_parameter", name + " must be a number of points.") }
            return number.doubleValue
        }
        func integer(_ name: String, _ range: ClosedRange<Int>) throws -> Int? {
            guard let value = try number(name) else { return nil }
            guard value == value.rounded(), range.contains(Int(value)) else {
                throw DeviceAPIError(422, "invalid_parameter", name + " must be a whole number from \(range.lowerBound) to \(range.upperBound).")
            }
            return Int(value)
        }
        func string(_ name: String, max: Int) throws -> String? {
            guard let value = body[name] else { return nil }
            guard let text = value as? String, text.utf16.count <= max else { throw DeviceAPIError(422, "invalid_parameter", name + " must be text of at most \(max) characters.") }
            return text
        }
        self.kind = kind
        x = try number("x"); y = try number("y"); toX = try number("to_x"); toY = try number("to_y")
        dx = try integer("dx", -10_000...10_000); dy = try integer("dy", -10_000...10_000)
        text = try string("text", max: Self.maximumText)
        key = try string("key", max: 32)?.lowercased()
        bundleID = try string("bundle_id", max: 255)
        element = try string("element", max: 400)
        if let value = try integer("pid", 1...Int(Int32.max)) { pid = Int32(value) } else { pid = nil }
        if let raw = body["modifiers"] {
            guard let list = raw as? [String], list.count <= 5, Set(list).isSubset(of: Self.modifierNames) else {
                throw DeviceAPIError(422, "invalid_parameter", "modifiers must be a list of " + Self.modifierNames.sorted().joined(separator: ", ") + ".")
            }
            modifiers = list
        } else { modifiers = [] }
        if let element, element.range(of: "^[0-9]{1,4}(\\.[0-9]{1,4}){0,59}$", options: .regularExpression) == nil {
            throw DeviceAPIError(422, "invalid_parameter", "element must be an element id from GET /v1/computer-use/ui, like 0.3.1.")
        }
        switch kind {
        case .click, .doubleClick, .rightClick, .move:
            guard x != nil, y != nil else { throw DeviceAPIError(422, "invalid_parameter", raw + " needs x and y.") }
        case .drag:
            guard x != nil, y != nil, toX != nil, toY != nil else { throw DeviceAPIError(422, "invalid_parameter", "drag needs x, y, to_x and to_y.") }
        case .scroll:
            guard dx != nil || dy != nil else { throw DeviceAPIError(422, "invalid_parameter", "scroll needs dx or dy in pixels.") }
        case .type:
            guard let text, !text.isEmpty else { throw DeviceAPIError(422, "invalid_parameter", "type needs nonempty text.") }
        case .key:
            guard let key, ComputerUseKeys.code(key) != nil else {
                throw DeviceAPIError(422, "invalid_parameter", "key must be a letter, digit or one of " + ComputerUseKeys.named.keys.sorted().joined(separator: ", ") + ".")
            }
        case .press, .focus:
            guard element != nil, pid != nil || bundleID != nil else { throw DeviceAPIError(422, "invalid_parameter", raw + " needs element and pid or bundle_id.") }
        case .setValue:
            guard element != nil, pid != nil || bundleID != nil, text != nil else { throw DeviceAPIError(422, "invalid_parameter", "set_value needs element, text and pid or bundle_id.") }
        case .activate:
            guard pid != nil || bundleID != nil else { throw DeviceAPIError(422, "invalid_parameter", "activate needs pid or bundle_id.") }
        }
    }

    public var elementPath: [Int] { (element ?? "").split(separator: ".").compactMap { Int($0) } }
}

/// macOS ANSI virtual key codes. Letters and digits use their US layout positions.
public enum ComputerUseKeys {
    public static let named: [String: UInt16] = [
        "return": 36, "enter": 76, "tab": 48, "space": 49, "delete": 51, "escape": 53, "forward_delete": 117,
        "left": 123, "right": 124, "down": 125, "up": 126, "home": 115, "end": 119, "page_up": 116, "page_down": 121,
        "f1": 122, "f2": 120, "f3": 99, "f4": 118, "f5": 96, "f6": 97, "f7": 98, "f8": 100, "f9": 101, "f10": 109,
        "f11": 103, "f12": 111, "-": 27, "=": 24, "[": 33, "]": 30, ";": 41, "'": 39, ",": 43, ".": 47, "/": 44, "\\": 42, "`": 50
    ]
    static let characters: [Character: UInt16] = [
        "a": 0, "s": 1, "d": 2, "f": 3, "h": 4, "g": 5, "z": 6, "x": 7, "c": 8, "v": 9, "b": 11, "q": 12, "w": 13, "e": 14,
        "r": 15, "y": 16, "t": 17, "1": 18, "2": 19, "3": 20, "4": 21, "6": 22, "5": 23, "9": 25, "7": 26, "8": 28, "0": 29,
        "o": 31, "u": 32, "i": 34, "p": 35, "l": 37, "j": 38, "k": 40, "n": 45, "m": 46
    ]
    public static func code(_ key: String) -> UInt16? {
        if let code = named[key] { return code }
        guard key.count == 1, let character = key.first else { return nil }
        return characters[character]
    }
}

/// The platform side: screen capture, the Accessibility tree and synthesized input.
public protocol ComputerUseBackend: AnyObject, Sendable {
    func permissions() async -> ComputerUsePermissions
    /// Asks macOS to show its permission prompts. It cannot grant anything itself.
    func requestPermissions() async -> ComputerUsePermissions
    func screenshot(maxEdge: Int, format: String, quality: Int) async throws -> [String: Any]
    func apps() async throws -> [[String: Any]]
    func tree(pid: Int32?, bundleID: String?, maxDepth: Int, maxNodes: Int) async throws -> [String: Any]
    func perform(_ action: ComputerUseAction) async throws -> [String: Any]
}

/// The operations, grants and schemas computer use adds to the Apple host contract.
enum ComputerUseContract {
    static let configurationPath = "/v1/owner/configuration/computer-use"
    static let permissionsPath = configurationPath + "/permissions"
    static let usePath = "/v1/computer-use"

    #if os(macOS)
    static let supported = true
    static let operations: [HostAPIOperation] = [
        .init(name: "configuration_computer_use_read", method: "GET", path: configurationPath, grant: .configurationRead,
              summary: "Read computer use: setting (inherit, on, off), the effective state for you and why (off (default), on (default from superpowers), on (specifically enabled), off (specifically disabled)), who changed it and how, macOS Accessibility and Screen Recording status and the owner's remaining step."),
        .init(name: "configuration_computer_use_set", method: "POST", path: configurationPath, grant: .configurationWrite,
              summary: "Set computer use to on, off or inherit (follow Superpowers: on for agents with Superpowers, off for others). on needs your Superpowers grant (agents.admin); off and inherit need settings.write. A specific off overrides Superpowers until changed. Every change is in Activity."),
        .init(name: "configuration_computer_use_permissions", method: "POST", path: permissionsPath, grant: .configurationWrite,
              summary: "Ask macOS to show its Accessibility and Screen Recording prompts on this Mac. Only the owner can switch them on in System Settings; read remaining_human_step for the exact step."),
        .init(name: "computer_use_status", method: "GET", path: usePath, grant: .screenObserve,
              summary: "Read whether computer use is on for you and why, plus macOS permission readiness. Start here."),
        .init(name: "computer_use_screenshot", method: "GET", path: usePath + "/screenshot", grant: .screenObserve,
              summary: "Capture the main display as base64 JPEG or PNG with its size in points. Click coordinates use these points, origin top-left. Needs Screen Recording."),
        .init(name: "computer_use_apps", method: "GET", path: usePath + "/apps", grant: .screenObserve,
              summary: "List running apps with pid, bundle_id, name and which is frontmost."),
        .init(name: "computer_use_ui", method: "GET", path: usePath + "/ui", grant: .screenObserve,
              summary: "Read an app's Accessibility tree (frontmost app by default): role, title, value, frame in points and actions per element, with element ids for actions. Needs Accessibility."),
        .init(name: "computer_use_action", method: "POST", path: usePath + "/actions", grant: .screenControl,
              summary: "Act on this Mac: click, double_click, right_click, move, drag, scroll, type, key (with modifiers), press, set_value or focus an element id from the ui tree, or activate an app. Needs Accessibility. Every action is in Activity; the owner can turn computer use off at any time.")
    ]
    #else
    static let supported = false
    static let operations: [HostAPIOperation] = []
    #endif

    static let bodyRequired: Set<String> = ["configuration_computer_use_set", "computer_use_action"]

    static func handles(_ request: HostRequest) -> Bool {
        supported && (request.path == configurationPath || request.path == permissionsPath || request.path == usePath || request.path.hasPrefix(usePath + "/"))
    }

    static func grant(method: String, path: String) -> HostOperation? {
        operations.first { $0.method == method && $0.path == path }?.grant
    }

    static func querySchema(_ name: String) -> [String: Any]? {
        switch name {
        case "computer_use_screenshot":
            return ["type": "object", "additionalProperties": false, "properties": [
                "max_edge": ["type": "integer", "minimum": 64, "maximum": 4096, "default": 1600],
                "format": ["enum": ["jpeg", "png"], "default": "jpeg"],
                "quality": ["type": "integer", "minimum": 40, "maximum": 100, "default": 70]]]
        case "computer_use_ui":
            return ["type": "object", "additionalProperties": false, "properties": [
                "pid": ["type": "integer", "minimum": 1], "bundle_id": ["type": "string", "maxLength": 255],
                "max_depth": ["type": "integer", "minimum": 1, "maximum": 40, "default": 15],
                "max_nodes": ["type": "integer", "minimum": 1, "maximum": 3000, "default": 500]]]
        default: return nil
        }
    }

    static func bodySchema(_ name: String) -> [String: Any]? {
        switch name {
        case "configuration_computer_use_set":
            return ["type": "object", "required": ["setting"], "additionalProperties": false,
                    "properties": ["setting": ["enum": ComputerUseSetting.allCases.map(\.rawValue)]]]
        case "configuration_computer_use_permissions":
            return ["type": "object", "maxProperties": 0]
        case "computer_use_action":
            return ["type": "object", "required": ["action"], "additionalProperties": false, "properties": [
                "action": ["enum": ComputerUseAction.Kind.allCases.map(\.rawValue)],
                "x": ["type": "number"], "y": ["type": "number"], "to_x": ["type": "number"], "to_y": ["type": "number"],
                "dx": ["type": "integer", "minimum": -10_000, "maximum": 10_000], "dy": ["type": "integer", "minimum": -10_000, "maximum": 10_000],
                "text": ["type": "string", "maxLength": ComputerUseAction.maximumText],
                "key": ["type": "string", "description": "A letter, digit or " + ComputerUseKeys.named.keys.sorted().joined(separator: ", ")],
                "modifiers": ["type": "array", "maxItems": 5, "items": ["enum": ComputerUseAction.modifierNames.sorted()]],
                "pid": ["type": "integer", "minimum": 1], "bundle_id": ["type": "string", "maxLength": 255],
                "element": ["type": "string", "pattern": "^[0-9]{1,4}(\\.[0-9]{1,4}){0,59}$"]]]
        default: return nil
        }
    }
}

public extension HostOperation {
    #if os(macOS)
    static let computerUseGrants: Set<HostOperation> = [.screenObserve, .screenControl]
    static let computerUseObserve: HostOperation? = .screenObserve
    static let computerUseControl: HostOperation? = .screenControl
    #else
    static let computerUseGrants: Set<HostOperation> = []
    static let computerUseObserve: HostOperation? = nil
    static let computerUseControl: HostOperation? = nil
    #endif
    /// Seeing the screen or acting on it. These never run unless computer use is on for the caller.
    var isComputerUse: Bool {
        #if os(macOS)
        return self == .screenObserve || self == .screenControl
        #else
        return false
        #endif
    }
}

/// Owns the persisted choice and runs computer-use requests. One per host store.
public actor ComputerUseService {
    private let store: AppletStore
    private let backend: ComputerUseBackend?
    private let changed: (@Sendable (ComputerUseChoice) async -> Void)?
    private let acted: (@Sendable () async -> Void)?

    public init(store: AppletStore, backend: ComputerUseBackend?, changed: (@Sendable (ComputerUseChoice) async -> Void)? = nil,
                acted: (@Sendable () async -> Void)? = nil) {
        self.store = store; self.backend = backend; self.changed = changed; self.acted = acted
    }

    public func choice() async throws -> ComputerUseChoice {
        guard let raw = try await store.state(ComputerUsePolicy.stateKey), let data = raw.data(using: .utf8) else { return .init() }
        let decoder = JSONDecoder(); decoder.dateDecodingStrategy = .iso8601
        return (try? decoder.decode(ComputerUseChoice.self, from: data)) ?? .init()
    }

    public func permissions() async -> ComputerUsePermissions { await backend?.permissions() ?? .none }

    public func requestPermissions() async -> ComputerUsePermissions { await backend?.requestPermissions() ?? .none }

    /// `agent` nil is the owner in the app. Saves the choice and its Activity row together.
    @discardableResult
    public func set(_ next: ComputerUseSetting, by agent: HostAgent?) async throws -> ComputerUseChoice {
        try ComputerUsePolicy.authorize(next, agent: agent)
        let previous = try await choice()
        guard previous.setting != next else { return previous }
        let saved = ComputerUseChoice(setting: next, changedAt: Date(), changedBy: agent.map { HostLogNote.scrub($0.name, max: 80) } ?? "Owner",
                                      changedByAgentID: agent?.id, source: ComputerUsePolicy.source(for: agent))
        let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .iso8601; encoder.outputFormatting = [.sortedKeys]
        let value = String(decoding: try encoder.encode(saved), as: UTF8.self)
        try await store.saveComputerUseChoice(key: ComputerUsePolicy.stateKey, value: value,
                                              message: ComputerUsePolicy.message(next, previous: previous.setting, agent: agent))
        await changed?(saved)
        return saved
    }

    public func resolution(for agent: HostAgent) async throws -> ComputerUseResolution {
        ComputerUsePolicy.resolve(try await choice().setting, superpowers: ComputerUsePolicy.hasSuperpowers(agent))
    }

    func state(for agent: HostAgent) async throws -> [String: Any] {
        let saved = try await choice()
        let resolution = ComputerUsePolicy.resolve(saved.setting, superpowers: ComputerUsePolicy.hasSuperpowers(agent))
        let permissions = await permissions()
        let formatter = ISO8601DateFormatter()
        var steps: [String] = []
        if !resolution.enabled {
            steps.append(saved.setting == .off
                ? "Computer use is specifically off. An agent with Superpowers or the owner can set it to on or inherit."
                : "Computer use is off for agents without Superpowers. The owner can grant this agent Superpowers, or set computer use to on in Bridgit › Hardware access.")
        }
        if let step = permissions.remainingHumanStep { steps.append(step) }
        return [
            "supported": ComputerUseContract.supported,
            "setting": saved.setting.rawValue,
            "enabled": resolution.enabled,
            "reason": resolution.reason,
            "state": resolution.label,
            "caller_has_superpowers": ComputerUsePolicy.hasSuperpowers(agent),
            "can_turn_on": ComputerUsePolicy.hasSuperpowers(agent) && agent.allows(.configurationWrite),
            "turn_on_requires": ComputerUsePolicy.superpowersGrant,
            "changed_at": saved.changedAt.map { formatter.string(from: $0) } as Any? ?? NSNull(),
            "changed_by": saved.changedBy as Any? ?? NSNull(),
            "changed_by_agent_id": saved.changedByAgentID as Any? ?? NSNull(),
            "change_source": saved.source as Any? ?? NSNull(),
            "os_permissions": ["accessibility": permissions.accessibility, "screen_recording": permissions.screenRecording],
            "ready": resolution.enabled && permissions.complete,
            "remaining_human_step": steps.isEmpty ? NSNull() as Any : steps.joined(separator: " ") as Any,
            "agent_allowed": ["observe": HostOperation.computerUseObserve.map(agent.allows) ?? false, "control": HostOperation.computerUseControl.map(agent.allows) ?? false],
            "links": ["settings": ComputerUseContract.configurationPath, "permissions": ComputerUseContract.permissionsPath,
                      "status": ComputerUseContract.usePath, "screenshot": ComputerUseContract.usePath + "/screenshot",
                      "apps": ComputerUseContract.usePath + "/apps", "ui": ComputerUseContract.usePath + "/ui",
                      "actions": ComputerUseContract.usePath + "/actions"]
        ]
    }

    /// Requests already passed the host's grant, pause and transport checks.
    func handle(_ request: HostRequest, agent: HostAgent) async throws -> HostResponse {
        let path = request.path
        if path == ComputerUseContract.configurationPath {
            if request.method == "GET" {
                guard request.query.isEmpty, request.body.isEmpty else { throw DeviceAPIError(422, "invalid_parameter", "This read takes no parameters.") }
                return try .json(await state(for: agent))
            }
            guard request.query.isEmpty, Set(request.body.keys) == ["setting"], let raw = request.body["setting"] as? String,
                  let next = ComputerUseSetting(rawValue: raw) else {
                throw DeviceAPIError(422, "invalid_parameter", "Supply only setting: inherit, on or off.")
            }
            try await set(next, by: agent)
            return try .json(await state(for: agent))
        }
        if path == ComputerUseContract.permissionsPath {
            guard request.query.isEmpty, request.body.isEmpty else { throw DeviceAPIError(422, "invalid_parameter", "This request takes no parameters.") }
            _ = await requestPermissions()
            var document = try await state(for: agent)
            document["prompt_requested"] = true
            return try .json(document)
        }
        if path == ComputerUseContract.usePath {
            guard request.query.isEmpty, request.body.isEmpty else { throw DeviceAPIError(422, "invalid_parameter", "This read takes no parameters.") }
            return try .json(await state(for: agent))
        }
        let resolution = try await resolution(for: agent)
        guard resolution.enabled else {
            let detail = resolution.reason == "specifically_disabled"
                ? "Computer use is specifically off on this Mac."
                : "Computer use is off for this agent. It needs the owner's Superpowers grant (" + ComputerUsePolicy.superpowersGrant + "), or the owner or a Superpowers agent setting it to on."
            throw DeviceAPIError(403, "computer_use_off", detail, reason: resolution.reason)
        }
        guard let backend else { throw DeviceAPIError(501, "computer_use_unsupported", "Computer use is available only in the Mac app.") }
        let permissions = await backend.permissions()
        func require(_ granted: Bool, _ name: String) throws {
            guard granted else {
                throw DeviceAPIError(409, "os_permission_required", (permissions.remainingHumanStep ?? "Ask the owner to allow " + name + " for Bridgit.") + " POST " + ComputerUseContract.permissionsPath + " shows the macOS prompt.", reason: name)
            }
        }
        switch path {
        case ComputerUseContract.usePath + "/screenshot":
            try HostQuery.only(request.query, ["max_edge", "format", "quality"])
            try require(permissions.screenRecording, "screen_recording")
            let format = try HostQuery.choice(request.query["format"], ["jpeg", "png"], default: "jpeg")
            let edge = try HostQuery.integer(request.query["max_edge"], default: 1600, range: 64...4096)
            let quality = try HostQuery.integer(request.query["quality"], default: 70, range: 40...100)
            var shot = try await backend.screenshot(maxEdge: edge, format: format, quality: quality)
            shot["coordinates"] = "points, origin top-left of the main display"
            await acted?()
            return try HostResponse.json(shot).withNoStore()
        case ComputerUseContract.usePath + "/apps":
            try HostQuery.only(request.query, [])
            return try .json(["apps": await backend.apps()])
        case ComputerUseContract.usePath + "/ui":
            try HostQuery.only(request.query, ["pid", "bundle_id", "max_depth", "max_nodes"])
            try require(permissions.accessibility, "accessibility")
            let pid = try request.query["pid"].map { Int32(try HostQuery.integer($0, default: 1, range: 1...Int(Int32.max))) }
            if let bundle = request.query["bundle_id"], !(bundle is String) { throw DeviceAPIError(422, "invalid_parameter", "bundle_id must be text.") }
            let tree = try await backend.tree(pid: pid, bundleID: request.query["bundle_id"] as? String,
                                              maxDepth: HostQuery.integer(request.query["max_depth"], default: 15, range: 1...40),
                                              maxNodes: HostQuery.integer(request.query["max_nodes"], default: 500, range: 1...3000))
            await acted?()
            return try HostResponse.json(tree).withNoStore()
        case ComputerUseContract.usePath + "/actions":
            guard request.method == "POST", request.query.isEmpty else { throw DeviceAPIError(422, "invalid_parameter", "Actions take a JSON body and no query.") }
            let action = try ComputerUseAction(request.body)
            if action.kind != .activate { try require(permissions.accessibility, "accessibility") }
            await acted?()
            var result = try await backend.perform(action)
            result["action"] = action.kind.rawValue
            result["done"] = result["done"] ?? true
            return try .json(result)
        default:
            throw DeviceAPIError(404, "not_found", "Unknown computer-use operation.")
        }
    }
}

/// Small query parsers shared by computer-use reads. Values arrive as strings or numbers.
enum HostQuery {
    static func only(_ query: [String: Any], _ allowed: Set<String>) throws {
        guard Set(query.keys).isSubset(of: allowed) else { throw DeviceAPIError(422, "invalid_parameter", "Unsupported query field.") }
    }
    static func integer(_ value: Any?, default fallback: Int, range: ClosedRange<Int>) throws -> Int {
        guard let value else { return fallback }
        let parsed: Int?
        if let text = value as? String { parsed = Int(text) }
        else if let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID(), number.doubleValue == number.doubleValue.rounded() { parsed = number.intValue }
        else { parsed = nil }
        guard let parsed, range.contains(parsed) else {
            throw DeviceAPIError(422, "invalid_parameter", "Use a whole number from \(range.lowerBound) to \(range.upperBound).")
        }
        return parsed
    }
    static func choice(_ value: Any?, _ allowed: [String], default fallback: String) throws -> String {
        guard let value else { return fallback }
        guard let text = value as? String, allowed.contains(text) else { throw DeviceAPIError(422, "invalid_parameter", "Use " + allowed.joined(separator: " or ") + ".") }
        return text
    }
}

extension HostResponse {
    func withNoStore() -> HostResponse {
        var copy = self; copy.headers["Cache-Control"] = "no-store"; return copy
    }
}

/// The app-side view of computer use: the owner's control in the app, the profile's cached
/// state and the "an agent is driving" indicator. Updated by the service.
@MainActor public final class ComputerUseHub: ObservableObject {
    public static let shared = ComputerUseHub()
    @Published public private(set) var choice = ComputerUseChoice()
    @Published public private(set) var permissions = ComputerUsePermissions.none
    @Published public private(set) var lastAgentActivity: Date?
    @Published public var problem: String?
    public private(set) var service: ComputerUseService?

    public init() {}

    public var supported: Bool { ComputerUseContract.supported }

    public func attach(_ service: ComputerUseService) async {
        self.service = service
        await refresh()
    }

    public func refresh() async {
        guard let service else { return }
        do { choice = try await service.choice() } catch { problem = error.localizedDescription }
        permissions = await service.permissions()
    }

    /// The owner's own change in the app.
    public func set(_ setting: ComputerUseSetting) async {
        guard let service else { problem = "Start Bridgit's host first."; return }
        do { choice = try await service.set(setting, by: nil); problem = nil }
        catch { problem = error.localizedDescription }
    }

    public func requestPermissions() async {
        guard let service else { return }
        permissions = await service.requestPermissions()
    }

    public func received(_ choice: ComputerUseChoice) { self.choice = choice }
    public func agentActed() { lastAgentActivity = Date() }

    public func agentActive(at now: Date = Date()) -> Bool {
        guard let lastAgentActivity else { return false }
        return now.timeIntervalSince(lastAgentActivity) < 60
    }

    /// Read live: macOS can change these in System Settings at any time.
    public func currentPermissions() -> ComputerUsePermissions {
        #if os(macOS)
        let live = MacComputerUseBackend.current
        if live != permissions { permissions = live }
        return live
        #else
        return .none
        #endif
    }

    /// The `screen` entry of the caller's device profile, from the cached state.
    public func capability(agent: HostAgent, hostReady: Bool, operations: [ProfileOperation], execution: [String]) -> ProfileCapability {
        let resolution = ComputerUsePolicy.resolve(choice.setting, superpowers: ComputerUsePolicy.hasSuperpowers(agent))
        let allowed = agent.viewer != true && HostOperation.computerUseGrants.contains(where: agent.allows)
        let permissions = currentPermissions(); let ready = hostReady && permissions.complete
        let reason: String? = !allowed ? "agent_not_allowed" : !resolution.enabled ? "capability_disabled"
            : !permissions.complete ? "hardware_or_permission_unavailable" : !hostReady ? "foreground_required" : nil
        return .init(runtimeID: "screen", implemented: supported, supported: supported, enabled: resolution.enabled, ready: ready,
                     agentAllowed: allowed, callable: allowed && resolution.enabled && ready && !operations.isEmpty, blockedReason: reason,
                     constraints: .init(execution: execution,
                        parameters: ["setting": [choice.setting.rawValue], "state": [resolution.label], "reason": [resolution.reason],
                                     "accessibility": [permissions.accessibility ? "allowed" : "not_allowed"],
                                     "screen_recording": [permissions.screenRecording ? "allowed" : "not_allowed"]],
                        limits: ["screenshot_max_edge_px": 4096, "ui_max_nodes": 3000, "type_max_characters": Double(ComputerUseAction.maximumText)]),
                     operations: operations)
    }
}
