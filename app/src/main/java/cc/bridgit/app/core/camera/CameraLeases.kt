package cc.bridgit.app.core.camera

/**
 * Who holds the camera (A0455 phase 1): the hand-off rules [CameraBroker] uses instead of the old
 * `registry.cameraLock`. Pure and synchronized, generic over the camera binding [B], following [cc.bridgit.app.capability.ArmSlot]:
 * every grant has a generation, and a release names it, so a late timer or a stale caller can never free a newer lease.
 *
 * - [Lease.Standing]: the stream for registered watches. At most one; it can be paused, never taken away silently.
 * - [Lease.OneOff]: a snapshot, an arm or a person-started capture. On the standing stream's lens it shares the
 *   stream's binding; on another lens it pauses the stream until it is released.
 * - [Lease.Exclusive]: the owner's viewfinder or camera screen. It beats the standing stream (which pauses) and is
 *   refused only while a one-off capture is mid-way.
 */
class CameraLeases<B> {
    sealed class Lease {
        data class Standing(val watchSet: Set<String>) : Lease()
        data class OneOff(val agentId: String, val kind: String) : Lease()
        data class Exclusive(val owner: String) : Lease()
    }

    /** A granted lease. [binding] is the camera it uses: a shared one-off gets the standing stream's. */
    data class Held<B>(val lease: Lease, val lens: String, val binding: B, val generation: Int, val shared: Boolean = false)

    sealed class Grant<out B> {
        data class Granted<B>(val held: Held<B>, val pausedStanding: Held<B>? = null) : Grant<B>()
        data class Busy(val holder: Lease) : Grant<Nothing>()
    }

    /**
     * What a release freed, and the standing stream to start again when nothing pauses it any more. [handedTo] is a
     * one-off that was sharing the released stream's binding and now owns it: the caller must not close that binding.
     */
    data class Released<B>(val held: Held<B>, val resumedStanding: Held<B>?, val handedTo: Held<B>? = null)

    private var standing: Held<B>? = null
    private var standingPaused = false
    private var oneOff: Held<B>? = null
    private var exclusive: Held<B>? = null
    private var generation = 0

    @Synchronized fun standing(): Held<B>? = standing
    @Synchronized fun isStandingPaused(): Boolean = standing != null && standingPaused
    @Synchronized fun oneOff(): Held<B>? = oneOff
    @Synchronized fun exclusive(): Held<B>? = exclusive
    @Synchronized fun isFree(): Boolean = standing == null && oneOff == null && exclusive == null

    /** Asks for [lease] on [lens] with [binding] (the camera the caller would open; unused when shared). */
    @Synchronized fun acquire(lease: Lease, lens: String, binding: B): Grant<B> = when (lease) {
        is Lease.Standing -> {
            val holder = standing?.lease
            if (holder != null) Grant.Busy(holder) else {
                val h = Held(lease, lens, binding, ++generation)
                standing = h
                // Starts paused behind a capture or the owner's screen that already holds the camera.
                standingPaused = exclusive != null || oneOff != null
                Grant.Granted(h)
            }
        }
        is Lease.OneOff -> {
            val holder = exclusive?.lease ?: oneOff?.lease
            if (holder != null) Grant.Busy(holder) else {
                val s = standing
                if (s != null && !standingPaused && s.lens == lens) {
                    val h = Held<B>(lease, lens, s.binding, ++generation, shared = true)
                    oneOff = h
                    Grant.Granted(h)
                } else {
                    val h = Held(lease, lens, binding, ++generation)
                    oneOff = h
                    Grant.Granted(h, pausedStanding = pauseStandingFor(h))
                }
            }
        }
        is Lease.Exclusive -> {
            val holder = exclusive?.lease ?: oneOff?.lease
            if (holder != null) Grant.Busy(holder) else {
                val h = Held(lease, lens, binding, ++generation)
                exclusive = h
                Grant.Granted(h, pausedStanding = pauseStandingFor(h))
            }
        }
    }

    /**
     * Pauses the standing stream for [other] (a one-off on another lens, or the owner's screen). Returns the stream it
     * paused, or null when there is none, it was already paused or it shares [other]'s binding.
     */
    @Synchronized fun pauseStandingFor(other: Held<B>): Held<B>? {
        val s = standing ?: return null
        if (standingPaused || other.shared) return null
        standingPaused = true
        return s
    }

    /** Lets the standing stream run again when nothing that pauses it holds the camera. Returns it when it resumed. */
    @Synchronized fun resumeStanding(): Held<B>? {
        val s = standing ?: return null
        if (!standingPaused || exclusive != null || oneOff?.shared == false) return null
        standingPaused = false
        return s
    }

    /** Releases the lease of [generation]. A stale or unknown generation is ignored (null). */
    @Synchronized fun release(generation: Int): Released<B>? {
        standing?.takeIf { it.generation == generation }?.let { s ->
            standing = null; standingPaused = false
            // A one-off riding on the stream's binding keeps it: the binding is handed over, and its release closes it.
            val handed = oneOff?.takeIf { it.shared }?.copy(shared = false)
            if (handed != null) oneOff = handed
            return Released(s, null, handed)
        }
        oneOff?.takeIf { it.generation == generation }?.let { o -> oneOff = null; return Released(o, resumeStanding()) }
        exclusive?.takeIf { it.generation == generation }?.let { e -> exclusive = null; return Released(e, resumeStanding()) }
        return null
    }

    /** Replaces the standing stream's watch set (registrations changed). False when [generation] is stale. */
    @Synchronized fun renegotiate(generation: Int, watchSet: Set<String>): Boolean {
        val s = standing ?: return false
        if (s.generation != generation) return false
        standing = s.copy(lease = Lease.Standing(watchSet))
        return true
    }
}
