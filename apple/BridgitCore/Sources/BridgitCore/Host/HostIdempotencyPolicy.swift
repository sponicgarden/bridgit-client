import Foundation

/// Retry protection lasts one day. Cached bodies may be compacted sooner under storage
/// pressure; their tombstones still prevent repeating the action within that window.
public enum HostIdempotencyPolicy {
    public static let retentionSeconds: TimeInterval = 86400
    public static let perCallerLimit = 1024
    public static let deviceLimit = 8192
    public static let cachedBytes = 32 * 1024 * 1024
}
