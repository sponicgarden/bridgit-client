import Foundation

/// Multiple library controllers can open the same account database. Cancellation
/// must reach their runs too; the persisted generation remains the authority.
final class ExecutionCancellation: @unchecked Sendable {
    static let shared = ExecutionCancellation()
    private let lock = NSLock()
    private var entries: [UUID: (String, @Sendable () -> Void)] = [:]
    func register(_ id: UUID, scope: String, cancel: @escaping @Sendable () -> Void) {
        lock.lock(); defer { lock.unlock() }
        entries[id] = (scope, cancel)
    }
    func remove(_ id: UUID) { lock.lock(); defer { lock.unlock() }; entries[id] = nil }
    func cancel(scope: String) {
        lock.lock()
        let actions = entries.values.filter { $0.0 == scope }.map { $0.1 }
        lock.unlock()
        for action in actions { action() }
    }
}
