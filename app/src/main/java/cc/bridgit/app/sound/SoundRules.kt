package cc.bridgit.app.sound

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * Sound watching, the shared core (owner, 4 Nov 2026). The phone's loudness detector ([LoudSounds], free, on the
 * phone) hears a loud sound; only then may the clip go to Clef ([SoundCheck]). Every user of it — a sound watch an
 * agent or applet starts (POST /v1/watches with data ["sound"]), or a native applet such as the Pet monitor's
 * barking — makes the same three decisions here:
 *
 *  - [Decision.COUNT]: the last clip Clef answered was at most `reuseMs` ago and this one sounds like it
 *    ([SoundPrint.similar]), so its answer is reused without a paid check (like the camera's free change check).
 *  - [Decision.SKIP]: the hourly paid-check cap is reached, or checks are paused after a budget answer.
 *  - [Decision.ASK]: send the clip to Clef.
 *
 * Pure and unit-tested.
 */
object SoundRules {
    /** Paid checks an hour a sound user gets unless it asks for another number. */
    const val DEFAULT_CHECKS_PER_HOUR = 6
    /** A paid answer this recent is reused for a loud sound that sounds like it (same episode). */
    const val DEFAULT_REUSE_MS = 5 * 60_000L
    const val MAX_REUSE_MS = 30 * 60_000L
    /** After spend_cap or clef_quota, no checks for this long (other features keep what is left). */
    const val BUDGET_PAUSE_MS = 60 * 60_000L
    /** The loudness detector's own gap between two sounds (WakeRules.SOUND_COOLDOWN_MS): at most this often. */
    const val MIN_GAP_S = 30
    val SENSITIVITIES = listOf("quiet", "normal", "loud")

    enum class Decision { COUNT, ASK, SKIP }

    /** [lastAt]: when the reusable answer's sound (or the last sound that reused it) was heard; 0 = none. */
    fun decide(now: Long, lastAt: Long, reuseMs: Long, similar: Boolean, paidLastHour: Int, maxPerHour: Int, pausedUntil: Long): Decision = when {
        reuseMs > 0 && lastAt > 0 && abs(now - lastAt) <= reuseMs && similar -> Decision.COUNT
        now < pausedUntil || paidLastHour >= maxPerHour -> Decision.SKIP
        else -> Decision.ASK
    }

    fun budgetReason(reason: String?) = reason == "spend_cap" || reason == "clef_quota"
}

/** One sound user's day: episodes of a hit, time, last hit, whether an episode is going now, skipped sounds. */
data class SoundDay(val episodes: Int = 0, val hitMs: Long = 0, val lastHit: Long? = null, val now: Boolean = false,
                    val skipped: Int = 0, val checks: Int = 0, val pausedUntil: Long = 0)

/**
 * A native sound user's counter: [hits] are the answers that count (for the Pet monitor, barking and growling).
 * An episode is hits no more than [reuseMs] apart. Day totals roll over at local midnight ([dayOf]); an episode
 * going on at midnight counts once on the new day too.
 */
class SoundEpisodes(private val hits: Set<String>, private val dayOf: (Long) -> String,
                    private val reuseMs: Long = SoundRules.DEFAULT_REUSE_MS, private val maxPerHour: Int = SoundRules.DEFAULT_CHECKS_PER_HOUR) {
    private var day = ""
    private var episodes = 0
    private var hitMs = 0L
    private var lastHit: Long? = null
    private var skipped = 0
    private var checksToday = 0
    private var lastHitAt = 0L
    private val paid = ArrayDeque<Long>()
    private var pausedUntil = 0L
    /** The last answered clip: its fingerprint, answer and time (extended by every sound that reused it). */
    private var lastPrint: SoundPrint? = null
    private var lastAnswer: String? = null
    private var lastAnswerAt = 0L

    private fun roll(now: Long) {
        val d = dayOf(now)
        if (d == day) return
        val carry = day.isNotEmpty() && ongoing(now)
        day = d; episodes = if (carry) 1 else 0; hitMs = 0; lastHit = if (carry) lastHitAt else null; skipped = 0; checksToday = 0
    }
    private fun paidLastHour(now: Long): Int { while (paid.isNotEmpty() && paid.first() <= now - 3_600_000L) paid.removeFirst(); return paid.size }
    private fun ongoing(now: Long) = lastHitAt > 0 && abs(now - lastHitAt) <= reuseMs

    /**
     * A loud sound from the phone's microphone. [COUNT][SoundRules.Decision.COUNT] returns the reused answer;
     * [ASK][SoundRules.Decision.ASK] reserves a paid check (call [onAnswer] with Clef's answer).
     */
    @Synchronized fun onLoud(now: Long, durationMs: Long, print: SoundPrint?): Pair<SoundRules.Decision, String?> {
        roll(now)
        val similar = print != null && print.similar(lastPrint)
        return when (val d = SoundRules.decide(now, lastAnswerAt, reuseMs, similar, paidLastHour(now), maxPerHour, pausedUntil)) {
            SoundRules.Decision.COUNT -> { lastAnswerAt = maxOf(lastAnswerAt, now); if (lastAnswer in hits) hit(now, durationMs); d to lastAnswer }
            SoundRules.Decision.SKIP -> { skipped++; d to null }
            SoundRules.Decision.ASK -> { paid.addLast(now); checksToday++; d to null }
        }
    }

    /** A sample taken on a timer (no loudness check): true when it may go to Clef now. [exempt] skips the hourly cap. */
    @Synchronized fun reserve(now: Long, exempt: Boolean = false): Boolean {
        roll(now)
        if (now < pausedUntil || (!exempt && paidLastHour(now) >= maxPerHour)) return false
        paid.addLast(now); checksToday++
        return true
    }

    /** Clef's answer for a sound heard at [at]. True when it starts a new episode (worth one alert). */
    @Synchronized fun onAnswer(at: Long, durationMs: Long, answer: String, print: SoundPrint? = null): Boolean {
        roll(at)
        if (print != null && at >= lastAnswerAt) { lastPrint = print; lastAnswer = answer; lastAnswerAt = at }
        if (answer !in hits) return false
        val fresh = !ongoing(at)
        hit(at, durationMs)
        if (fresh) episodes++
        return fresh
    }

    private fun hit(at: Long, durationMs: Long) { lastHitAt = maxOf(lastHitAt, at); hitMs += durationMs; lastHit = maxOf(lastHit ?: 0, at) }

    @Synchronized fun pause(now: Long) { pausedUntil = now + SoundRules.BUDGET_PAUSE_MS }

    @Synchronized fun day(now: Long): SoundDay {
        roll(now)
        return SoundDay(episodes, hitMs, lastHit, ongoing(now), skipped, checksToday, pausedUntil.takeIf { it > now } ?: 0)
    }

    /** Saved on the phone, so a restart keeps the day, the hourly cap, a budget pause and an episode in progress. */
    @Synchronized fun toJson(): JSONObject = JSONObject().put("day", day).put("episodes", episodes).put("hit_ms", hitMs)
        .put("last_hit", lastHit ?: 0).put("skipped", skipped).put("checks", checksToday)
        .put("last_hit_at", lastHitAt).put("paused_until", pausedUntil).put("paid", JSONArray(paid.toList()))
    @Synchronized fun load(o: JSONObject) {
        day = o.optString("day"); episodes = o.optInt("episodes"); hitMs = o.optLong("hit_ms")
        lastHit = o.optLong("last_hit").takeIf { it > 0 }; skipped = o.optInt("skipped"); checksToday = o.optInt("checks")
        lastHitAt = o.optLong("last_hit_at"); pausedUntil = o.optLong("paused_until")
        paid.clear(); o.optJSONArray("paid")?.let { a -> for (i in 0 until a.length()) paid.addLast(a.optLong(i)) }
    }
}
