package cc.bridgit.app.remoteview

import cc.bridgit.app.dog.DogDay
import cc.bridgit.app.dog.DogStatus
import cc.bridgit.app.dog.Reading
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * The Pet monitor panel on bridgit.cc/watch (owner, 2 Nov 2026): what the phone sends about the Pet monitor while
 * remote view is linked. Only numbers, times and fixed words (activity, mood, model, an error code): never the
 * screen's error text. The latest photo goes with it only while the owner's photo switch is on, at most every
 * [PHOTO_EVERY_MS]. The backend (bridgit-cloud/feedback/src/watch.mjs, redactDog) checks the same shape again. Pure.
 */
object RemoteViewDog {
    /** A command belongs to a consent generation; retries and reordered replies never replay it. */
    fun newControl(generation: String, revision: Long, previousGeneration: String?, previousRevision: Long): Boolean =
        generation.isNotEmpty() && revision >= 0 && (generation != previousGeneration || revision > previousRevision)

    /** The portal's sampling interval change, carried in dog_control (see [samplingCommand]). */
    data class SamplingCommand(val revision: Long, val allS: Int?, val cameras: Map<String, Int?>)

    /**
     * Reads dog_control.sampling: {revision, all_s: 10..3600 or null (default), cameras?: {rear|front|lan:<id>: 10..3600
     * or null (follow all_s)}}. Null when it is missing or anything in it is out of bounds: a bad command changes nothing.
     */
    fun samplingCommand(o: JSONObject?): SamplingCommand? {
        if (o == null || !o.has("revision") || !o.has("all_s")) return null
        val revision = (o.opt("revision") as? Number)?.toLong()?.takeIf { it >= 0 } ?: return null
        fun seconds(v: Any?): Int? = (v as? Int)?.takeIf { cc.bridgit.app.appdevices.SensorSearchDelays.validSeconds(it) }
        val all = if (o.isNull("all_s")) null else seconds(o.opt("all_s")) ?: return null
        val cams = LinkedHashMap<String, Int?>()
        if (o.has("cameras") && !o.isNull("cameras")) {
            val c = o.optJSONObject("cameras") ?: return null
            if (c.length() > 20) return null
            for (id in c.keys()) {
                if (!cc.bridgit.app.dog.DogCamera.isCamera(id)) return null
                cams[id] = if (c.isNull(id)) null else seconds(c.opt(id)) ?: return null
            }
        }
        return SamplingCommand(revision, all, cams)
    }

    /** At most one summary per 30 s while the monitor runs; Start and Stop go at once. */
    const val EVERY_MS = 30_000L
    const val PHOTO_EVERY_MS = 30_000L

    /** Length of a clip the web portal's listen button records. */
    const val LISTEN_SECONDS = 6

    /** The device's own microphone as the portal shows it: off (owner's switch), no_permission, or available. */
    fun builtinMic(switchOn: Boolean, permitted: Boolean): String = when {
        !switchOn -> "off"
        !permitted -> "no_permission"
        else -> "available"
    }

    /** A failed listen as one of the portal's plain error codes (the backend words them). Never the raw message. */
    fun listenError(message: String?, permissionMissing: Boolean): String = when {
        permissionMissing -> "no_permission"
        message == "Open Bridgit before microphone capture" -> "open_app"
        message == "Microphone access changed" -> "mic_off"
        message == "No supported microphone track" -> "no_track"
        message in setOf("Microphone busy", "Camera microphone busy", "Another app is using the microphone") ||
            message?.startsWith("Microphone unavailable") == true -> "busy"
        else -> "record"
    }
    const val PHOTO_BYTES = 100 * 1024
    const val PHOTO_EDGE = 640
    const val RECENT = 15
    /** When this app process started: a restart or an update shows up on the web portal as a new time. */
    val APP_STARTED_AT: Long by lazy {
        runCatching { System.currentTimeMillis() - (android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()) }
            .getOrDefault(System.currentTimeMillis())
    }

    fun reading(r: Reading): JSONObject = JSONObject().put("at", r.at).put("dog", r.dog)
        .put("dog_pct", (r.dogP * 100).roundToInt().coerceIn(0, 100))
        .put("activity", r.activity ?: JSONObject.NULL).put("mood", r.mood ?: JSONObject.NULL)
        .put("model", r.model).put("reused", r.reused)
        .put("unchanged_since", r.unchangedSince ?: JSONObject.NULL)

    /**
     * The last Auto check of every camera, so the portal can show it even when it changed nothing (a scan that
     * keeps the same camera is otherwise invisible). Scores are dog_present as 0–100; null = no photo from that camera.
     */
    fun scan(c: cc.bridgit.app.dog.DogCamera.Choice): JSONObject = JSONObject().put("at", c.at).put("pet_seen", c.why != cc.bridgit.app.dog.DogCamera.Why.NO_DOG)
        .put("cameras", JSONArray(c.cameras.take(MAX_SCAN_CAMERAS)))
        .put("scores", JSONObject().apply { c.scores.entries.take(MAX_SCAN_CAMERAS).forEach { (k, v) -> put(k, v?.let { (it * 100).roundToInt().coerceIn(0, 100) } ?: JSONObject.NULL) } })

    const val MAX_SCAN_CAMERAS = 8

    fun summary(s: DogStatus, day: DogDay?, recent: List<Reading>, photoOn: Boolean, sampling: JSONObject? = null): JSONObject {
        val o = JSONObject().put("running", s.running).put("searching",s.searching).put("model", s.model)
            .put("started_at", if (s.startedAt > 0) s.startedAt else JSONObject.NULL)
            .put("error", s.errorCode ?: JSONObject.NULL).put("photo_on", photoOn)
            .put("stopped_at", if (!s.running && s.stoppedAt > 0) s.stoppedAt else JSONObject.NULL)
            .put("stop_reason", if (s.running) JSONObject.NULL else s.stopCode ?: JSONObject.NULL)
            .put("resumed_at", if (s.running && s.resumedAt > 0) s.resumedAt else JSONObject.NULL)
            .put("app_started_at", APP_STARTED_AT).put("app_version", cc.bridgit.app.BuildConfig.VERSION_NAME)
            .put("last_scan", s.choice?.let(::scan) ?: JSONObject.NULL)
            .put("asked", s.asked).put("free", s.unchanged)
            .put("latest", recent.lastOrNull()?.let(::reading) ?: JSONObject.NULL)
            .put("recent", JSONArray(recent.takeLast(RECENT).map(::reading)))
            .put("barking_now", s.running && s.bark.now)
        // The sampling interval (default 20 s): {all_s, default_s, min_s, max_s, cameras, overrides, photo_interval_s}.
        if (sampling != null) o.put("sampling", sampling)
        if (day != null) {
            // The portal knows no "dark" tally; there, a too-dark photo counts as no dog in view (as its reading says).
            val views = JSONObject(); day.views.forEach { (k, v) -> val key = if (k == "dark") "no_dog" else k; views.put(key, views.optInt(key) + v) }
            o.put("today", JSONObject()
                .put("sleep_ms", day.sleepMs).put("naps", day.naps)
                .put("asleep_since", day.asleepSince ?: JSONObject.NULL).put("asleep_for_ms", day.asleepForMs)
                .put("eating_ms", day.eatingMs).put("drinking_ms", day.drinkingMs)
                .put("meals", day.meals).put("last_meal", day.lastMeal ?: JSONObject.NULL)
                .put("drinks", day.drinks).put("last_drink", day.lastDrink ?: JSONObject.NULL)
                .put("photos", day.photos).put("free", day.free).put("watched_ms", day.watchedMs).put("views", views)
                .put("median_drink_ms", day.medianDrinkGap?.medianMs ?: JSONObject.NULL).put("median_drink_gaps", day.medianDrinkGap?.count ?: 0)
                .put("median_meal_ms", day.medianMealGap?.medianMs ?: JSONObject.NULL).put("median_meal_gaps", day.medianMealGap?.count ?: 0)
                .put("barks", s.bark.episodes).put("barking_ms", s.bark.hitMs).put("last_bark", s.bark.lastHit ?: JSONObject.NULL))
        }
        return o
    }

    /** Capture and classification emit separately. Do not consume the send throttle between them. */
    fun awaitingReading(s: DogStatus, recent: List<Reading>, since: Long, photoOn: Boolean): Boolean =
        s.running && s.error == null && photoOn && s.frame != null && s.frameAt >= since && recent.none { it.at == s.frameAt }

    /** What changed enough to send now: a start or stop, or the throttle ran out. */
    fun due(running: Boolean, lastRunning: Boolean?, lastAt: Long, now: Long): Boolean =
        lastRunning == null || running != lastRunning || now - lastAt >= EVERY_MS
}
