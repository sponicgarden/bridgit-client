import Foundation

/// A local introduction, not an authorization or capability grant.
public struct FirstRunProgress {
    public enum Step: Int, CaseIterable { case introduction, capabilities, privacy, warning }
    public enum Action: String { case diagnostic, addControlAgent }
    public private(set) var step: Step
    public private(set) var returningInstall: Bool
    public private(set) var completedAction: Action?
    private let defaults: UserDefaults
    private let prefix: String

    public init(defaults: UserDefaults, prefix: String = "firstRun.v1", hasOwnerData: Bool) {
        self.defaults = defaults; self.prefix = prefix
        let initialized = defaults.bool(forKey: prefix + ".initialized")
        returningInstall = initialized ? defaults.bool(forKey: prefix + ".returning") : hasOwnerData
        completedAction = defaults.string(forKey: prefix + ".action").flatMap(Action.init(rawValue:))
        step = Step(rawValue: defaults.integer(forKey: prefix + ".step")) ?? .introduction
        if !initialized {
            // Capture before the host creates its first empty database. Incomplete introductions
            // must remain incomplete on the next launch, even though that database now exists.
            defaults.set(returningInstall, forKey: prefix + ".returning")
            defaults.set(true, forKey: prefix + ".initialized")
        }
    }
    /// Apple framework restoration is not evidence of an owner preference. In particular,
    /// regular-width split navigation writes this metadata during otherwise isolated UI runs.
    public static func hasOwnerPreferences(_ keys: [String]) -> Bool {
        let metadataPrefixes = ["firstRun.", "NS", "Apple", "com.apple."]
        return keys.contains { key in !metadataPrefixes.contains(where: { key.hasPrefix($0) }) }
    }
    public var isRequired: Bool { !returningInstall && completedAction == nil }
    /// Matches the first-run gate when an owner already created a local control agent.
    /// This is migration/navigation history, never completion or a new grant.
    public mutating func recognizeSavedAgent() {
        guard isRequired else { return }
        returningInstall = true
        defaults.set(true, forKey: prefix + ".returning")
    }
    public mutating func next() { if step != .warning { move(to: Step(rawValue: step.rawValue + 1)!) } }
    public mutating func back() { if step != .introduction { move(to: Step(rawValue: step.rawValue - 1)!) } }
    public mutating func skip() { move(to: .warning) }
    private mutating func move(to step: Step) { self.step = step; defaults.set(step.rawValue, forKey: prefix + ".step") }
    /// Only either explicit final action completes the introduction. Navigation and links do not.
    @discardableResult public mutating func finish(_ action: Action) -> Bool {
        guard isRequired, step == .warning else { return false }
        completedAction = action; defaults.set(action.rawValue, forKey: prefix + ".action")
        return true
    }
}
