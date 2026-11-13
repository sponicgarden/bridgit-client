package cc.bridgit.app.core.authority

import android.os.SystemClock

/** The two clocks the authority waits read. Behind an interface so tests can move them. */
interface AuthorityTime {
    /** The device's wall clock. A person, the network or an attacker can move it either way. */
    fun wallMs(): Long

    /** Milliseconds since boot, sleep included (SystemClock.elapsedRealtime). Starts again near zero on a restart. */
    fun elapsedMs(): Long

    object Device : AuthorityTime {
        override fun wallMs() = System.currentTimeMillis()
        override fun elapsedMs() = SystemClock.elapsedRealtime()
    }
}

/** Where the counted running time is kept: the count, the time-since-boot reading it was counted up to, and which process took that reading. */
interface ClockCheckpoints {
    data class State(val countedMs: Long, val elapsedMs: Long, val session: Long)

    fun read(): State
    fun write(state: State)
}

/**
 * Counted running time (architecture §4.7 "Clocks"): one number that only rises, and only while a
 * process is alive to see the time-since-boot clock move. The wall clock is never read, so moving it
 * shortens nothing.
 *
 * The clock holds nothing in memory. The count and the reading it was counted up to live in storage, and
 * [checkpoint] reads, advances and writes them. Run inside the store's database transaction, that makes
 * every store instance on the same file advance the same counter: none can hand out a count lower than
 * one another instance already stored, and no stretch of time is counted twice.
 *
 * A reading taken by another process ([session] differs) is never counted from: a new process starts
 * from the stored count at the moment it first checkpoints. So time powered off, and time between the
 * last checkpoint and a stop, is not counted. That can only make a wait longer, never shorter.
 */
class RunningClock(private val time: AuthorityTime, private val checkpoints: ClockCheckpoints, private val session: Long = PROCESS) {
    /** Counts up to now from the stored reading and stores the result. Returns the stored count. A backward step of the clock counts as nothing. */
    fun checkpoint(): Long {
        val now = time.elapsedMs()
        val was = checkpoints.read()
        val delta = if (was.session == session) (now - was.elapsedMs).coerceAtLeast(0) else 0
        val next = ClockCheckpoints.State(was.countedMs.coerceAtLeast(0) + delta, now, session)
        if (next != was) checkpoints.write(next)
        return next.countedMs
    }

    companion object {
        /** One value per app process. Never 0, which is what an empty store holds. */
        val PROCESS: Long = java.security.SecureRandom().nextLong() or 1L
    }

    /**
     * One wait. It is over only when the running time counted has reached [countedEndMs] AND the wall clock
     * is past [wallEndMs]. Both ends are fixed when the wait begins.
     */
    data class Wait(val countedEndMs: Long, val wallEndMs: Long) {
        fun over(countedMs: Long, wallMs: Long) = countedMs >= countedEndMs && wallMs >= wallEndMs

        companion object {
            /** No wait at all (first set-up, D16c). */
            val NONE = Wait(0, 0)

            fun starting(countedMs: Long, wallMs: Long, durationMs: Long) = Wait(countedMs + durationMs, wallMs + durationMs)
        }
    }
}
