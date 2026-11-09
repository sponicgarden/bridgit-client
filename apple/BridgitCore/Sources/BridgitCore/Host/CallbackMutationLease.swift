import Darwin
import Foundation

/// A process-independent, nonblocking lease for staging and erasing callback
/// Keychain versions. A crashed writer releases the OS lock automatically.
final class CallbackMutationLease {
    private var descriptor: Int32
    init(directory: URL) throws {
        let path = directory.appendingPathComponent("callback-mutation.lock").path
        descriptor = open(path, O_CREAT | O_RDWR | O_NOFOLLOW | O_CLOEXEC, mode_t(0o600))
        guard descriptor >= 0 else { throw DeviceAPIError(503, "callback_storage_invalid", "Cannot open callback coordination.") }
        guard flock(descriptor, LOCK_EX | LOCK_NB) == 0 else {
            close(descriptor); descriptor = -1
            throw DeviceAPIError(409, "callback_busy", "Another callback change is in progress.")
        }
    }
    func release() {
        guard descriptor >= 0 else { return }
        _ = flock(descriptor, LOCK_UN); close(descriptor); descriptor = -1
    }
    deinit { release() }
}
