package cc.bridgit.app.core

/**
 * When to ask Tailscale to reconnect while the phone has no Tailscale address (A0091: a Play update
 * stopped Tailscale's VPN and nothing brought it back, so the API was unreachable all day).
 * The first try is immediate, then the gap doubles from [minGapMs] up to [maxGapMs]; [reset] once
 * the phone is back on the tailnet. Plain Kotlin, unit-tested.
 */
class TailscaleReconnect(private val minGapMs: Long = 60_000L, private val maxGapMs: Long = 15 * 60_000L) {
    private var nextAtMs = 0L
    private var gapMs = minGapMs
    var attempts = 0
        private set

    /** True if a reconnect should be sent now; books the next one. */
    fun due(nowMs: Long): Boolean {
        if (nowMs < nextAtMs) return false
        nextAtMs = nowMs + gapMs
        gapMs = minOf(gapMs * 2, maxGapMs)
        attempts++
        return true
    }

    /** How long until the next try is due (0 if it is due now). */
    fun waitMs(nowMs: Long): Long = (nextAtMs - nowMs).coerceAtLeast(0L)

    fun reset() { nextAtMs = 0L; gapMs = minGapMs; attempts = 0 }
}
