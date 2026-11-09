import Foundation

public enum HostFeedbackStatus: String, CaseIterable, Sendable {
    case new, seen, inProgress = "in_progress", fixed, wontFix = "wont_fix", duplicate, confirmed, reopened

    public var fixerDestinations: [Self] {
        switch self {
        case .new, .seen, .reopened: [.inProgress, .wontFix, .duplicate]
        case .inProgress: [.fixed, .wontFix]
        default: []
        }
    }

    public var reporterOutcomes: [String] {
        allows(.confirmed, actor: .reporter) ? ["works", "still_broken"] : []
    }

    public static func outcome(_ value: String) -> Self? {
        switch value {
        case "works": .confirmed
        case "still_broken": .reopened
        default: nil
        }
    }

    public func allows(_ destination: Self, actor: HostFeedbackActor) -> Bool {
        switch actor {
        case .auto: (self == .new || self == .reopened) && destination == .seen
        case .developer: fixerDestinations.contains(destination)
        case .owner, .reporter:
            [.fixed, .wontFix, .duplicate].contains(self) && [.confirmed, .reopened].contains(destination)
        case .team:
            self != .confirmed && self != destination &&
                (destination == .seen ? self == .new || self == .reopened
                                      : [.inProgress, .fixed, .wontFix, .duplicate].contains(destination))
        }
    }
}

public enum HostFeedbackActor: String, Codable, Sendable {
    case auto, developer, owner, team, reporter
}

/// The wait budget includes initial team synchronization and ends before the
/// host's longer transport deadline. The caller can poll earlier for new rows.
public struct HostFeedbackWaitBudget: Sendable {
    private let deadline: ContinuousClock.Instant

    public init(seconds: Int, started: ContinuousClock.Instant = .now) {
        deadline = started.advanced(by: .seconds(seconds))
    }

    public func remaining(at now: ContinuousClock.Instant = .now) -> Duration {
        max(.zero, now.duration(to: deadline))
    }
}
