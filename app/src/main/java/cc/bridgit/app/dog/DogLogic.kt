package cc.bridgit.app.dog

import cc.bridgit.app.judgment.Jev
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/**
 * Pet monitor applet (owner, 2 Nov 2026): a photo every 10 s goes to Jev, which says whether a dog is
 * there, what it is doing and its mood. [DogTracker] turns those readings into naps, meals and drinks.
 * Pure (no Android), unit-tested.
 */
object DogJev {
    fun activity(value: String?): String? = if (value == "sleeping") "resting" else value

    val ACTIVITIES = listOf("resting", "eating", "drinking", "playing", "moving", "other")
    val MOODS = listOf("relaxed", "happy", "alert", "anxious", "low", "unclear")

    /** Below this, Jev doesn't think a dog is in the photo. */
    const val DOG_MIN = 0.6
    /** Activity and mood answers below this confidence aren't used. */
    const val CONFIDENCE_MIN = 0.5

    /** The pet's box from the package classifier, or null when it is missing or no pet is likely. */
    fun box(a: Jev.Answer): cc.bridgit.app.lancamera.PetBox? = a.box("pet_box")?.let { cc.bridgit.app.lancamera.PetBox.of(it[0], it[1], it[2], it[3], it[4]) }

    /** Nothing meaningful changed between two answers: same dog or no dog, same activity, same mood. */
    fun sameAnswer(a: Reading, b: Reading): Boolean = a.dog == b.dog && activity(a.activity) == activity(b.activity) && a.mood == b.mood

    /**
     * The hourly check ([timed]: the picture looked unchanged, so the model was asked only because the last
     * answer was an hour old) gave a different answer than [before]: the photo comparison missed a change.
     */
    fun missedChange(timed: Boolean, before: Reading?, now: Reading): Boolean =
        timed && before != null && before.model == now.model && !sameAnswer(before, now)

    /** The two models the monitor can ask. */
    val MODELS = listOf("clef", "jev")

    /** Jev's answer as a [Reading]. A missing or unreadable dog answer counts as no dog. */
    fun reading(at: Long, a: Jev.Answer, model: String = "jev", camera: String = DogCamera.REAR): Reading {
        val p = a.noul("dog_present") ?: 0.0
        val dog = p >= DOG_MIN
        val act = a.choice("activity", ACTIVITIES + "sleeping")?.takeIf { dog && it.second >= CONFIDENCE_MIN }
        val mood = a.choice("mood", MOODS)?.takeIf { dog && it.second >= CONFIDENCE_MIN && it.first != "unclear" }
        return Reading(at, dog, p, activity(act?.first), act?.second ?: 0.0, mood?.first, mood?.second ?: 0.0, model, camera = camera)
    }
}

/** One photo's verdict. [activity] and [mood] are null when there is no dog or the model wasn't sure. [model]: who answered. */
data class Reading(val at: Long, val dog: Boolean, val dogP: Double, val activity: String?, val activityConf: Double,
                   val mood: String?, val moodConf: Double, val model: String = "jev",
                   /** True when the photo looked the same as the last one asked about, so that answer was reused (free). */
                   val reused: Boolean = false,
                   /** Which camera took the photo ([DogCamera.REAR] or [DogCamera.FRONT]). */
                   val camera: String = DogCamera.REAR, val unchangedSince: Long? = null,
                   /** The photo was too dark to see anything, so no model was asked: "can't tell", not "no dog". */
                   val dark: Boolean = false) {
    fun json(): JSONObject = JSONObject().put("at", at).put("dog", dog).put("dog_p", dogP)
        .put("activity", activity ?: JSONObject.NULL).put("activity_conf", activityConf)
        .put("mood", mood ?: JSONObject.NULL).put("mood_conf", moodConf).put("model", model).put("reused", reused).put("camera", camera).put("unchanged_since", unchangedSince ?: JSONObject.NULL).put("dark", dark)

    companion object {
        fun from(o: JSONObject) = Reading(o.getLong("at"), o.getBoolean("dog"), o.optDouble("dog_p", 0.0),
            DogJev.activity(o.optString("activity").takeIf { it.isNotEmpty() && !o.isNull("activity") }), o.optDouble("activity_conf", 0.0),
            o.optString("mood").takeIf { it.isNotEmpty() && !o.isNull("mood") }, o.optDouble("mood_conf", 0.0), o.optString("model", "jev"), o.optBoolean("reused", false),
            o.optString("camera", DogCamera.REAR).takeIf { DogCamera.isCamera(it) } ?: DogCamera.REAR,
            o.optLong("unchanged_since", 0).takeIf { it > 0 && it <= o.getLong("at") }, o.optBoolean("dark", false))
    }
}

/** A nap, a meal or a drink: from the first photo that showed it to the last, plus one photo's worth of time. */
data class Episode(val kind: String, val start: Long, val lastSeen: Long, val frames: Int) {
    fun durationMs(frameMs: Long): Long = lastSeen - start + frameMs
    fun json(): JSONObject = JSONObject().put("kind", kind).put("start", start).put("last_seen", lastSeen).put("frames", frames)

    companion object {
        fun from(o: JSONObject) = Episode(DogJev.activity(o.getString("kind"))!!, o.getLong("start"), o.getLong("last_seen"), o.optInt("frames", 1))
    }
}

/**
 * What the screen shows for today. [views]: photos today by what Jev saw ("sleeping", "eating", ...,
 * "unsure" = a dog but no confident activity, "no_dog", "dark" = too dark to see). [watchedMs]: time the camera was watching today.
 * The medians are over the last [DogTracker.MEDIAN_DAYS] days (null = fewer than 1 gap seen).
 */
data class DogDay(
    val sleepMs: Long, val naps: Int, val asleepSince: Long?,
    val meals: Int, val lastMeal: Long?, val drinks: Int, val lastDrink: Long?,
    val views: Map<String, Int> = emptyMap(), val photos: Int = 0, val watchedMs: Long = 0,
    val medianDrinkGap: Gaps? = null, val medianMealGap: Gaps? = null,
    /** Span of the current nap session; unlike classified totals this may include short gaps. */
    val asleepForMs: Long = 0,
    /** Photos today that looked unchanged, so the last answer was reused and no model was asked (free). */
    val free: Int = 0,
    /** Time estimates from classified checks; gaps and other activities are not counted. */
    val eatingMs: Long = 0, val drinkingMs: Long = 0,
)

/** A median time between episodes and how many gaps it came from. */
data class Gaps(val medianMs: Long, val count: Int)

/** A stretch the camera watched without a break longer than [DogTracker.SPAN_BREAK_MS]. */
data class Span(val start: Long, val end: Long)

/**
 * Turns readings into episodes. A kind ([KINDS]) starts with the first photo that shows it and keeps
 * going while photos keep showing it; a different reading in between (a sleeping dog read once as
 * "resting", or a missed photo) doesn't end it. It ends once no photo has shown it for its gap
 * ([gapMs]): 2 minutes for sleep, 1 minute for eating and drinking. So one long drink is one drink,
 * and a dog that dozes, lifts its head and dozes again is one nap.
 */
class DogTracker(
    val frameMs: Long = DogMonitor.INTERVAL_MS,
    private val gapMs: Map<String, Long> = mapOf("resting" to 120_000L, "eating" to 60_000L, "drinking" to 60_000L),
) {
    companion object {
        val KINDS = listOf("resting", "eating", "drinking")
        const val MAX_EPISODES = 2000
        const val MAX_READINGS = 60
        const val MAX_SPANS = 1000
        /** Readings further apart than this mean the camera wasn't watching in between. */
        const val SPAN_BREAK_MS = 60_000L
        const val MEDIAN_DAYS = 7

        /** The median of [xs], or null if empty. Pure. */
        fun median(xs: List<Long>): Long? {
            if (xs.isEmpty()) return null
            val s = xs.sorted(); val m = s.size / 2
            return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
        }
    }

    private val episodes = ArrayList<Episode>()
    private val readings = ArrayDeque<Reading>()
    private val spans = ArrayList<Span>()
    /** Today's photo tallies: [viewsDay] is the local midnight they belong to. */
    private var viewsDay = 0L
    private val views = HashMap<String, Int>()
    /** Today's free (reused) readings, same day as [views]. */
    private var freeToday = 0

    @Synchronized fun episodes(): List<Episode> = episodes.toList()
    /** Today's sessions, clipped at local midnight; old saved sessions need no migration. */
    @Synchronized fun episodesToday(now: Long, tz: TimeZone = TimeZone.getDefault()): List<Episode> {
        val start = midnight(now, tz)
        return episodes.filter { it.lastSeen >= start }.map { it.copy(start = maxOf(it.start, start)) }
    }
    @Synchronized fun recent(): List<Reading> = readings.toList()
    /** One consistent history/totals snapshot for source-scoped API reads. */
    @Synchronized fun apiSnapshot(now:Long):Pair<List<Reading>,DogDay> = readings.toList() to day(now)
    @Synchronized fun latest(): Reading? = readings.lastOrNull()

    /** The episode of [kind] that is still going at [now], if any. */
    @Synchronized fun open(kind: String, now: Long): Episode? =
        episodes.lastOrNull { it.kind == DogJev.activity(kind) }?.takeIf { now - it.lastSeen <= gap(DogJev.activity(kind)!!) }

    private fun gap(kind: String) = gapMs[kind] ?: 60_000L

    /** Adds [r]; a reading older than the latest one (the phone's clock moved back) is ignored. */
    @Synchronized fun add(r: Reading, tz: TimeZone = TimeZone.getDefault()) {
        if (readings.isNotEmpty() && r.at < readings.last().at) return
        val normalized = r.copy(activity = DogJev.activity(r.activity))
        readings.addLast(normalized)
        while (readings.size > MAX_READINGS) readings.removeFirst()
        val last = spans.lastOrNull()
        if (last != null && r.at >= last.end && r.at - last.end <= SPAN_BREAK_MS) spans[spans.size - 1] = last.copy(end = r.at)
        else { spans.add(Span(r.at, r.at)); while (spans.size > MAX_SPANS) spans.removeAt(0) }
        val day = midnight(r.at, tz)
        if (day != viewsDay) { viewsDay = day; views.clear(); freeToday = 0 }
        if (r.reused) freeToday++
        val seen = when { r.dark -> "dark"; !r.dog -> "no_dog"; r.activity == null -> "unsure"; else -> DogJev.activity(r.activity)!! }
        views[seen] = (views[seen] ?: 0) + 1
        val kind = DogJev.activity(r.activity)?.takeIf { it in KINDS } ?: return
        val i = episodes.indexOfLast { it.kind == kind }
        val prev = episodes.getOrNull(i)
        if (prev != null && r.at >= prev.lastSeen && r.at - prev.lastSeen <= gap(kind)) {
            episodes[i] = prev.copy(lastSeen = r.at, frames = prev.frames + 1)
        } else {
            episodes.add(Episode(kind, r.at, r.at, 1))
            while (episodes.size > MAX_EPISODES) episodes.removeAt(0)
        }
    }

    /** Today's totals, from local midnight of [now] in [tz]. A nap that began before midnight counts from midnight. */
    private fun midnight(t: Long, tz: TimeZone): Long = Calendar.getInstance(tz).apply {
        timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /**
     * Median time from the start of one [kind] episode to the start of the next, over the last
     * [MEDIAN_DAYS] days. A gap counts only if one watched [Span] covers it, so time the monitor was
     * off (or the phone locked) never stretches it; missed single photos are already absorbed by episodes.
     */
    @Synchronized fun medianGap(kind: String, now: Long): Gaps? {
        val since = now - MEDIAN_DAYS * 86_400_000L
        val starts = episodes.filter { it.kind == kind && it.start >= since }.map { it.start }.sorted()
        val gaps = starts.zipWithNext().filter { (a, b) -> b > a && covered(a, b) }.map { (a, b) -> b - a }
        return median(gaps)?.let { Gaps(it, gaps.size) }
    }

    /** One watched span holds both [a] and [b]. Spans are in time order, so a binary search finds it. */
    private fun covered(a: Long, b: Long): Boolean {
        var lo = 0; var hi = spans.size - 1; var hit = -1
        while (lo <= hi) { val m = (lo + hi) / 2; if (spans[m].start <= a) { hit = m; lo = m + 1 } else hi = m - 1 }
        return hit >= 0 && spans[hit].end >= b
    }

    @Synchronized fun day(now: Long, tz: TimeZone = TimeZone.getDefault()): DogDay {
        val midnight = midnight(now, tz)
        val today = episodes.filter { it.lastSeen >= midnight }
        val naps = today.filter { it.kind == "resting" }
        val meals = today.filter { it.kind == "eating" }
        val drinks = today.filter { it.kind == "drinking" }
        val v = if (viewsDay == midnight) views.toMap() else emptyMap()
        fun observed(kind: String) = (v[kind] ?: 0).toLong() * frameMs
        val watched = spans.sumOf { sp -> (sp.end + frameMs - maxOf(sp.start, midnight)).coerceAtLeast(0).takeIf { sp.end >= midnight } ?: 0L }
        val nap = open("resting", now)
        return DogDay(observed("resting"), naps.size, nap?.start,
            meals.size, meals.lastOrNull()?.start, drinks.size, drinks.lastOrNull()?.start,
            v, v.values.sum(), watched, medianGap("drinking", now), medianGap("eating", now), nap?.durationMs(frameMs) ?: 0,
            if (viewsDay == midnight) freeToday else 0, observed("eating"), observed("drinking"))
    }

    @Synchronized fun clear() { episodes.clear(); readings.clear(); spans.clear(); views.clear(); viewsDay = 0; freeToday = 0 }

    /** Saved form: episodes, watched spans, today's tallies and the recent readings. */
    @Synchronized fun toJson(): JSONObject = JSONObject()
        .put("episodes", JSONArray(episodes.map { it.json() }))
        .put("readings", JSONArray(readings.map { it.json() }))
        .put("spans", JSONArray(spans.map { JSONArray().put(it.start).put(it.end) }))
        .put("views_day", viewsDay).put("views", JSONObject(views as Map<*, *>)).put("free", freeToday)

    @Synchronized fun load(o: JSONObject) {
        clear()
        o.optJSONArray("spans")?.let { a -> for (i in 0 until a.length()) runCatching { a.getJSONArray(i).let { spans.add(Span(it.getLong(0), it.getLong(1))) } } }
        viewsDay = o.optLong("views_day", 0)
        freeToday = o.optInt("free", 0)
        o.optJSONObject("views")?.let { v -> v.keys().forEach { k -> val key = DogJev.activity(k)!!; views[key] = (views[key] ?: 0) + v.optInt(k) } }
        o.optJSONArray("episodes")?.let { a -> for (i in 0 until a.length()) runCatching { episodes.add(Episode.from(a.getJSONObject(i))) } }
        o.optJSONArray("readings")?.let { a -> for (i in 0 until a.length()) runCatching { readings.addLast(Reading.from(a.getJSONObject(i))) } }
    }
}

