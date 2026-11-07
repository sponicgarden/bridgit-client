package cc.bridgit.app.power

import android.content.Context
import kotlin.math.*

/**
 * Power triggers (docs/applet-reach.md part G). Pure Kotlin except [PowerState.load]/[PowerState.save].
 *
 * The phone keeps one power state: plugged or not, since when, and when the state before it began. Every flow with a
 * `power` trigger decides from that state alone, so a reboot during a power cut still reports the outage once power
 * is back, and a flow fires at most once per plug or unplug (its marker is the `since` it fired for).
 */
object PowerRules {
    val ONS = listOf("unplugged", "plugged", "restored")
    const val MAX_FOR_S = 3600

    /** [plugged] since [since] (wall clock ms); [before] is when the previous state began (null when unknown). */
    data class State(val plugged: Boolean, val since: Long, val before: Long?)

    /**
     * The state after a reading of [plugged] at [now]: unchanged, or a new state that began now. The very first reading
     * is no plug or unplug: its start is unknown (since 0), so nothing fires for it and no outage is measured from it.
     */
    fun observe(prev: State?, plugged: Boolean, now: Long): State =
        if (prev == null) State(plugged, 0L, null) else if (prev.plugged == plugged) prev else State(plugged, now, prev.since.takeIf { it > 0 })

    /** What a power trigger fires with: the marker to save and the `$trigger` fields. */
    data class Fire(val marker: Long, val data: Map<String, Any>)

    /**
     * Whether a `power` trigger [on] with [forS] fires now. Only a plug or unplug at or after [armedAt] (when the flow
     * was activated) counts, so a flow activated while the phone is already charging does not fire at once.
     * [fired] is the marker of its last run; the same plug or unplug never fires twice.
     */
    fun due(on: String, forS: Int, s: State?, armedAt: Long, fired: Long?, now: Long): Fire? {
        if (s == null || s.since <= 0 || s.since < armedAt || fired == s.since) return null
        val held = now - s.since
        return when (on) {
            "unplugged" -> if (!s.plugged && held >= forS * 1000L) Fire(s.since, mapOf("off_at" to s.since, "off_minutes" to (held / 60_000L).toInt())) else null
            "plugged" -> if (s.plugged && held >= forS * 1000L) Fire(s.since, mapOf("on_at" to s.since)) else null
            "restored" -> {
                val off = s.before ?: return null
                val lasted = s.since - off
                if (s.plugged && lasted >= forS * 1000L) Fire(s.since, mapOf("off_at" to off, "on_at" to s.since, "off_minutes" to ((lasted + 30_000L) / 60_000L).toInt())) else null
            }
            else -> null
        }
    }

    /** The owner's sentence for a power trigger. */
    fun words(on: String, forS: Int): String {
        val span = minutes(forS)
        return when (on) {
            "unplugged" -> if (forS == 0) "When the phone comes off charge" else "When the phone has been off charge for $span (a power cut)"
            "restored" -> if (forS == 0) "When charging comes back after the phone was off charge" else "When power comes back after at least $span off charge"
            else -> if (forS == 0) "When the phone starts charging" else "When the phone has been charging for $span"
        }
    }
    private fun minutes(s: Int) = when {
        s < 60 -> "$s seconds"
        s % 60 == 0 && s / 60 == 1 -> "1 minute"
        s % 60 == 0 -> "${s / 60} minutes"
        else -> "${(s + 30) / 60} minutes"
    }
}

/** The saved power state ([PowerRules.State]) in the app's preferences, so it outlives a restart or reboot. */
object PowerState {
    private const val PREFS = "power_state"
    fun load(app: Context): PowerRules.State? {
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.contains("since")) return null
        return PowerRules.State(p.getBoolean("plugged", false), p.getLong("since", 0L), if (p.contains("before")) p.getLong("before", 0L) else null)
    }
    /** Records a reading of [plugged] at [now]; returns the state, saved if it changed. */
    @Synchronized fun observe(app: Context, plugged: Boolean, now: Long = System.currentTimeMillis()): PowerRules.State {
        val prev = load(app)
        val next = PowerRules.observe(prev, plugged, now)
        if (next != prev) app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putBoolean("plugged", next.plugged); putLong("since", next.since)
            if (next.before != null) putLong("before", next.before) else remove("before")
        }.commit()
        return next
    }
}

/** Sunrise and sunset on the phone, from a saved coarse position (NOAA's simplified solar equations, within a few minutes). */
object SunTimes {
    val ONS = listOf("sunrise", "sunset")
    const val MAX_OFFSET_MIN = 180

    /** Sunrise ([rise]) or sunset on UTC date [epochDay] at [lat], [lon], as wall clock ms; null in polar day or night. */
    fun event(epochDay: Long, lat: Double, lon: Double, rise: Boolean): Long? {
        val n = epochDay + 2440588.0 - 2451545.0 + 0.0008
        val jStar = n - lon / 360.0
        val m = norm(357.5291 + 0.98560028 * jStar)
        val mr = Math.toRadians(m)
        val c = 1.9148 * sin(mr) + 0.0200 * sin(2 * mr) + 0.0003 * sin(3 * mr)
        val lambda = Math.toRadians(norm(m + c + 180.0 + 102.9372))
        val transit = 2451545.0 + jStar + 0.0053 * sin(mr) - 0.0069 * sin(2 * lambda)
        val decl = asin(sin(lambda) * sin(Math.toRadians(23.4397)))
        val phi = Math.toRadians(lat)
        val cosW = (sin(Math.toRadians(-0.833)) - sin(phi) * sin(decl)) / (cos(phi) * cos(decl))
        if (cosW < -1 || cosW > 1) return null
        val w = Math.toDegrees(acos(cosW))
        val j = if (rise) transit - w / 360.0 else transit + w / 360.0
        return ((j - 2440587.5) * 86_400_000.0).roundToLong()
    }
    private fun norm(d: Double) = ((d % 360.0) + 360.0) % 360.0

    /** The first sunrise or sunset (with [offsetMin]) after [after]; null if none in the next few days (polar). */
    fun next(on: String, offsetMin: Int, lat: Double, lon: Double, after: Long): Long? {
        val day = Math.floorDiv(after, 86_400_000L)
        return (day - 1..day + 3).mapNotNull { d -> event(d, lat, lon, on == "sunrise")?.plus(offsetMin * 60_000L) }.filter { it > after }.minOrNull()
    }

    fun words(on: String, offsetMin: Int): String {
        val what = if (on == "sunrise") "sunrise" else "sunset"
        return when {
            offsetMin == 0 -> "At $what"
            offsetMin < 0 -> "${-offsetMin} minutes before $what"
            else -> "$offsetMin minutes after $what"
        }
    }
}
