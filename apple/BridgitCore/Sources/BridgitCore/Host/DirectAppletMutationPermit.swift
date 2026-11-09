import Foundation

/// A short-lived host permit checked inside a synchronous external store effect.
public final class HostMutationPermit: @unchecked Sendable {
    private let fence: HostExecutionFence
    private var open = true
    private let generation: UInt64
    private let invalidatedMessage: String

    init(fence: HostExecutionFence, invalidatedMessage: String) {
        self.fence = fence
        self.invalidatedMessage = invalidatedMessage
        generation = fence.lock.withLock { fence.generation }
    }

    public func commit<T>(_ effect: () throws -> T) throws -> T {
        try fence.lock.withLock {
            try Task.checkCancellation()
            guard open, fence.executionAllowed, generation == fence.generation else {
                throw DeviceAPIError(409, "foreground_required", invalidatedMessage)
            }
            return try effect()
        }
    }

    func close() { fence.lock.withLock { open = false } }
}

public typealias DirectAppletMutationPermit = HostMutationPermit

final class HostExecutionFence: @unchecked Sendable {
    let lock = NSLock()
    fileprivate var executionAllowed = false
    fileprivate var generation: UInt64 = 0

    func update(_ allowed: Bool, bindingChanged: Bool = false) {
        lock.withLock {
            if allowed != executionAllowed || bindingChanged { generation &+= 1 }
            executionAllowed = allowed
        }
    }
}
