package cc.bridgit.app.core.camera

import cc.bridgit.app.core.camera.CameraLeases.Lease
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * The only owner of the phone's camera (A0455 phase 2). It replaces the old `registry.cameraLock` mutex: every
 * camera user (an agent's photo, an arm, body tracking, monitoring, a watch's photo, the Pet monitor, the
 * flashlight, a video and the owner's camera screens) asks for a lease and gives it back with the [Ticket] it got.
 *
 * Phase 2 has no standing stream yet, so a one-off and the owner's screen exclude each other exactly as the mutex
 * did: [tryAcquire] is `tryLock` (a busy camera answers at once), [acquire] is `lock` (it waits, first come first
 * served, and a waiter that is cancelled never keeps the camera), and [acquireWithin] is a bounded wait.
 * A release names its ticket's generation, so a late or repeated release never frees a newer lease.
 */
class CameraBroker {
    /** A granted lease; give it back with [release]. */
    class Ticket internal constructor(internal val held: CameraLeases.Held<Unit>) {
        val lease: Lease get() = held.lease
        val lens: String get() = held.lens
        val generation: Int get() = held.generation
        override fun toString() = "Ticket($lease, $lens, #$generation)"
    }

    private class Waiter(val lease: Lease, val lens: String, val cont: CancellableContinuation<Ticket>)

    private val leases = CameraLeases<Unit>()
    private val waiters = ArrayDeque<Waiter>()

    /** Who holds the camera now (the owner's screen or a one-off first), or null when it is free. */
    @Synchronized fun holder(): Lease? = (leases.exclusive() ?: leases.oneOff() ?: leases.standing())?.lease
    @Synchronized fun isFree(): Boolean = leases.isFree()
    /** How many callers wait in [acquire]. */
    @Synchronized fun waiting(): Int = waiters.size

    /**
     * The lease now, or null when the camera is busy. Like `Mutex.tryLock`, it never jumps ahead of callers already
     * waiting in [acquire].
     */
    @Synchronized fun tryAcquire(lease: Lease, lens: String = ANY): Ticket? {
        if (waiters.isNotEmpty() && lease !is Lease.Standing) return null
        return grant(lease, lens)
    }

    /** Waits for the lease (like `Mutex.lock`). Cancelling the caller while it waits leaves the camera as it was. */
    suspend fun acquire(lease: Lease, lens: String = ANY): Ticket {
        tryAcquire(lease, lens)?.let { return it }
        return suspendCancellableCoroutine { c ->
            val w = Waiter(lease, lens, c)
            val now = synchronized(this) { tryAcquire(lease, lens) ?: null.also { waiters.addLast(w) } }
            if (now != null) c.resume(now) { _, _, _ -> release(now) }
            // A cancelled waiter leaves the queue at once, like a cancelled Mutex.lock, so it is never handed the camera.
            else c.invokeOnCancellation { synchronized(this) { waiters.remove(w) } }
        }
    }

    /** Waits at most [ms] for the lease; null when the camera stayed busy. A lease won at the deadline is kept. */
    suspend fun acquireWithin(ms: Long, lease: Lease, lens: String = ANY): Ticket? {
        if (ms <= 0) return tryAcquire(lease, lens)
        var won: Ticket? = null
        try { withTimeout(ms) { won = acquire(lease, lens) } } catch (_: TimeoutCancellationException) {}
        return won
    }

    /** Gives [ticket]'s lease back. False when it was already given back (or is stale): then nothing changes. */
    fun release(ticket: Ticket?): Boolean {
        if (ticket == null) return false
        synchronized(this) {
            leases.release(ticket.generation) ?: return false
            // Hand the camera to the waiters in order, as far as their leases fit.
            while (waiters.isNotEmpty()) {
                val next = waiters.first()
                val t = grant(next.lease, next.lens) ?: break
                waiters.removeFirst()
                // Cancelled in between: the camera goes straight back (and on to the next waiter).
                next.cont.resume(t) { _, _, _ -> release(t) }
            }
            return true
        }
    }

    private fun grant(lease: Lease, lens: String): Ticket? =
        when (val g = leases.acquire(lease, lens, Unit)) {
            is CameraLeases.Grant.Granted -> Ticket(g.held)
            is CameraLeases.Grant.Busy -> null
        }

    companion object {
        /** A lease that does not name a lens (the owner's screen, which can switch, or the flashlight). */
        const val ANY = "any"
        fun lens(rear: Boolean) = if (rear) "rear" else "front"
    }
}
