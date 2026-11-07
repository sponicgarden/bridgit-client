package cc.bridgit.app.remoteview

import cc.bridgit.app.judgment.Jev
import cc.bridgit.app.watch.WatchTally
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * What an installed package applet's web portal gets from the phone (the kitchen cleanliness monitor and any other
 * package watch with a tally): today's counts per label, the latest answer, which notice rules fired, and the pause
 * state. Only fixed words (labels, choice answers, yes/no) and numbers: never free text from a model. The backend
 * (feedback/src/package-applets.mjs, redactPackage) checks the same shape again. Pure.
 */
object RemoteViewPackage {
    /** The server's applet id for a package: one instance per package per device. */
    fun appletId(packageId: String): String = "pkg.$packageId"
    private val LABEL = Regex("[a-z0-9_-]{1,40}")
    /** At most one summary a minute unless something changed; a photo at most once a minute. */
    const val EVERY_MS = 60_000L
    const val REFRESH_MS = 5 * 60_000L

    /** One answer as {value, confidence 0–100}: a choice and its confidence, or yes/no and how likely yes. */
    fun answer(a: JSONObject?): JSONObject? {
        a ?: return null
        return when (a.optString("type")) {
            "choice" -> {
                val c = a.opt("choice") as? String ?: return null
                val conf = (a.opt("confidence") as? Number)?.toDouble()?.takeIf { it in 0.0..1.0 }
                if (!LABEL.matches(c)) null else JSONObject().put("value", c).put("confidence", conf?.let { (it * 100).roundToInt() } ?: JSONObject.NULL)
            }
            "noul" -> {
                val p = (a.opt("noul") as? Number)?.toDouble()?.takeIf { it in 0.0..1.0 } ?: return null
                JSONObject().put("value", if (p >= 0.5) "yes" else "no").put("confidence", (p * 100).roundToInt())
            }
            else -> null
        }
    }

    /**
     * The summary of [tally] (its spec) given the watch's tally summary ([tallySummary], from WatchTally.summary),
     * its latest check ([last], a Watches check JSON) and the rest of its state.
     */
    fun summary(name: String, tally: WatchTally.Spec, tallySummary: JSONObject?, last: JSONObject?, running: Boolean, paused: Boolean,
                photoOn: Boolean, intervalS: Int, rulesFired: Int, lastCheckAt: Long, appVersion: String): JSONObject {
        val labels = (tally.labels ?: emptyList()).filter { LABEL.matches(it) }.take(12)
        val today = tallySummary?.optJSONObject("today")
        val perLabel = JSONObject()
        today?.optJSONObject("labels")?.let { l -> labels.forEach { k -> l.optJSONObject(k)?.let { v ->
            perLabel.put(k, JSONObject().put("episodes", v.optInt("episodes")).put("minutes", v.optDouble("minutes", 0.0)).put("readings", v.optInt("readings")))
        } } }
        val o = JSONObject().put("name", name.take(60)).put("track", tally.track).put("labels", JSONArray(labels))
            .put("running", running).put("paused", paused).put("photo_on", photoOn).put("interval_s", intervalS)
            .put("rules_fired", rulesFired).put("app_version", appVersion)
            .put("last_check_at", if (lastCheckAt > 0) lastCheckAt else JSONObject.NULL)
            .put("today", JSONObject().put("date", today?.optString("date")?.takeIf { it.isNotEmpty() } ?: JSONObject.NULL)
                .put("checks", today?.optInt("checks") ?: 0).put("watched_minutes", today?.optDouble("watched_minutes", 0.0) ?: 0.0).put("labels", perLabel))
            .put("latest", latest(tally, last) ?: JSONObject.NULL)
        // Rule titles come from the package's own notice ("Kitchen needs a clean-up"); fired times from the tally.
        val fired = HashMap<String, Long>()
        tallySummary?.optJSONArray("rules")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { r ->
            iso(r.opt("last_fired_at"))?.let { fired[r.optString("id")] = it } } }
        o.put("rules", JSONArray(tally.rules.filter { LABEL.matches(it.id) }.take(10).map { r ->
            JSONObject().put("id", r.id).put("title", r.call?.optJSONObject("body")?.optString("title")?.takeIf { it.isNotBlank() }?.take(80) ?: JSONObject.NULL)
                .put("last_fired_at", fired[r.id] ?: JSONObject.NULL)
        }))
        return o
    }

    /** The latest check's answers, the label it counted under (null = not counted) and the time. Null without answers. */
    fun latest(tally: WatchTally.Spec, last: JSONObject?): JSONObject? {
        last ?: return null
        val raw = last.optJSONObject("answers") ?: return null
        val at = iso(last.opt("at")) ?: return null
        val answers = JSONObject()
        raw.keys().asSequence().filter { LABEL.matches(it) }.take(8).forEach { k -> answer(raw.optJSONObject(k))?.let { answers.put(k, it) } }
        val parsed = Jev.Answer(last.optString("model"), raw, 0, 0.0, 0)
        val counted = WatchTally.seenOf(tally, parsed) && WatchTally.labelOf(tally, parsed) != null
        val shown = answers.optJSONObject(tally.track)
        val label = shown?.optString("value")?.takeIf { tally.labels == null || it in tally.labels }
        return JSONObject().put("at", at).put("label", label ?: JSONObject.NULL)
            .put("confidence", shown?.opt("confidence") ?: JSONObject.NULL).put("counted", counted).put("answers", answers)
    }

    /** A check as a state record for the portal timeline: {kind:"state", at, label, confidence, counted, answers}. */
    fun stateRecord(tally: WatchTally.Spec, check: JSONObject): JSONObject? = latest(tally, check)?.put("kind", "state")

    /** A name the portal can count by: "Frying pan" → "frying_pan". Null if nothing is left. */
    fun itemLabel(raw: String): String? = raw.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(40).trimEnd('_').ifEmpty { null }

    /** A card's find result as a left_out record: items with [x0, y0, x1, y1] boxes (fractions) and how sure (0–100). */
    fun leftOut(items: List<Triple<String, List<Double>, Double?>>, at: Long): JSONObject = JSONObject().put("kind", "left_out").put("at", at)
        .put("count", items.size).put("items", JSONArray(items.take(20).mapNotNull { (label, box, score) ->
            val l = itemLabel(label) ?: return@mapNotNull null
            JSONObject().put("label", l).put("box", JSONArray(box.map { Math.round(it.coerceIn(0.0, 1.0) * 1000) / 1000.0 }))
                .apply { score?.let { put("confidence", (it * 100).roundToInt().coerceIn(0, 100)) } }
        }))

    private fun iso(v: Any?): Long? = when (v) {
        is Number -> v.toLong()
        is String -> runCatching { java.time.OffsetDateTime.parse(v).toInstant().toEpochMilli() }.getOrNull()
        else -> null
    }

    /** Whether a new summary goes now: it changed, or the refresh time ran out (both at most once a minute). */
    fun due(digest: String, sentDigest: String?, sentAt: Long, now: Long): Boolean =
        now - sentAt >= EVERY_MS && (digest != sentDigest || now - sentAt >= REFRESH_MS)
}
