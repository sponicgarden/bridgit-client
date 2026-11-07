package cc.bridgit.app.power

import kotlin.math.roundToLong

/** Passive, session-local discharge trend. Monotonic time avoids clock corrections and DST.
 * Never wakes the device; Android battery broadcasts and existing reads feed the estimator.
 * Charging, missing data, a level increase or a long sampling gap starts a fresh history.
 */
class BatteryRuntime {
    data class Estimate(val status: String, val seconds: Long? = null, val lowerSeconds: Long? = null,
        val upperSeconds: Long? = null, val observedSeconds: Long = 0, val percentPerHour: Double? = null)
    private data class Sample(val time: Long, val percent: Int)
    private val samples = ArrayDeque<Sample>()

    @Synchronized fun reset() { samples.clear() }

    @Synchronized fun observe(now: Long, percent: Int?, plugged: Boolean): Estimate {
        if (plugged || percent == null || percent !in 0..100) {
            samples.clear()
            return Estimate(if (plugged) "plugged_in" else "unavailable")
        }
        val last = samples.lastOrNull()
        if (last != null && (now < last.time || now - last.time > MAX_GAP_MS || percent > last.percent)) samples.clear()
        // Keep bounded history; preserve an unchanged level's observation time for plateau accuracy.
        while (samples.isNotEmpty() && now - samples.first().time > WINDOW_MS) samples.removeFirst()
        val previous = samples.lastOrNull()
        if (previous == null || now - previous.time >= MIN_SAMPLE_MS || percent != previous.percent) {
            samples.addLast(Sample(now, percent))
        }
        val first = samples.first()
        val span = now - first.time
        val drop = first.percent - percent
        if (span < MIN_WINDOW_MS || drop < 2) return Estimate("learning", observedSeconds = span / 1000)
        val secondsPerPercent = span / 1000.0 / drop
        return Estimate("available", (percent * secondsPerPercent).roundToLong(),
            (percent * span / 1000.0 / (drop + 1)).roundToLong(),
            (percent * span / 1000.0 / (drop - 1)).roundToLong(), span / 1000,
            drop * 3_600_000.0 / span)
    }

    companion object {
        const val MIN_WINDOW_MS = 10 * 60_000L
        const val WINDOW_MS = 2 * 60 * 60_000L
        const val MAX_GAP_MS = 30 * 60_000L
        const val MIN_SAMPLE_MS = 60_000L
    }
}
