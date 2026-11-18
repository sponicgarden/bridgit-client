package cc.bridgit.app.watch

import cc.bridgit.app.capability.isoAt
import cc.bridgit.app.judgment.Jev
import cc.bridgit.app.vision.FrameDiff
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlin.math.sqrt

/**
 * The calibration loop, phase 1 (A0047, docs/plans/calibration-loop.md): every stage that decides something
 * in a watch (the free change check, the sound match, the paid model, the paid cap) writes one small decision
 * record per reading, with no media. A sample of the readings the free stage answered is asked of the paid
 * model anyway (the audit sample); the platform keeps that sample at or above [MIN_AUDIT_SHARE] so an agent
 * cutting cost can't switch its own check off. [report] turns the records into the measures an agent tunes
 * with: volume and cost per stage, reuse share, audited miss rate with its interval, agreement per confidence
 * band, stage health, and the same broken down by camera, hour and darkness.
 *
 * Not in this phase: owner labels (spot checks, "Wrong?"), raw records for agents, replay, examples and fleet
 * pooling, which wait on the owner's decisions listed in the plan. Pure Kotlin except [Log]'s file.
 */
object Calibration {
    /** 1 in 50 readings the free stage answered is asked of the paid model anyway. */
    const val DEFAULT_AUDIT_SHARE = 0.02
    /** The floor: never fewer than 1 in 200. */
    const val MIN_AUDIT_SHARE = 0.005
    const val MAX_AUDIT_SHARE = 0.5
    const val KEEP_DAYS = 30
    const val MAX_RECORDS = 20_000
    const val MAX_WINDOW_DAYS = KEEP_DAYS
    const val DEFAULT_WINDOW_DAYS = 7

    /** Stages. */
    const val CHANGE_GATE = "change_gate"
    const val SOUND_MATCH = "sound_match"
    const val MODEL = "model"
    const val PAID_CAP = "paid_cap"
    const val CAMERA = "camera"

    const val AUDIT_SOURCE = "audit_sample"

    class Bad(message: String) : Exception(message)

    /** An agent's audit share: a number in [MIN_AUDIT_SHARE]..[MAX_AUDIT_SHARE]; null (JSON null) clears it. */
    fun parseAuditShare(raw: Any?): Double? {
        if (raw == null || raw == JSONObject.NULL) return null
        val v = (raw as? Number)?.toDouble() ?: throw Bad("audit_share must be a number from $MIN_AUDIT_SHARE to $MAX_AUDIT_SHARE, or null for the default.")
        if (v.isNaN() || v < MIN_AUDIT_SHARE) throw Bad("audit_share can't be below $MIN_AUDIT_SHARE (1 in ${(1 / MIN_AUDIT_SHARE).toInt()}): the audit sample is how the watch's misses are measured.")
        if (v > MAX_AUDIT_SHARE) throw Bad("audit_share can be at most $MAX_AUDIT_SHARE.")
        return v
    }

    fun auditDue(share: Double?, roll: Double): Boolean = roll < (share ?: DEFAULT_AUDIT_SHARE).coerceIn(MIN_AUDIT_SHARE, MAX_AUDIT_SHARE)

    fun settings(share: Double?): JSONObject = JSONObject()
        .put("audit_share", share ?: DEFAULT_AUDIT_SHARE).put("audit_share_set", share != null)
        .put("audit_share_default", DEFAULT_AUDIT_SHARE).put("audit_share_min", MIN_AUDIT_SHARE).put("audit_share_max", MAX_AUDIT_SHARE)
        .put("keep_days", KEEP_DAYS).put("max_records", MAX_RECORDS)

    /** The question a watch's records are about: the tally's track, else its first question. */
    fun task(spec: WatchRules.Spec): String? = spec.tally?.track ?: spec.questions.keys().asSequence().firstOrNull()

    /** [answer]'s value for [task]: the choice and its confidence, or "yes"/"no" and the noul probability. */
    fun primary(answer: Jev.Answer?, task: String?): Pair<String?, Double?> {
        if (answer == null || task == null) return null to null
        answer.choice(task)?.let { return it.first to it.second }
        answer.noul(task)?.let { return (if (it >= 0.5) "yes" else "no") to it }
        return null to null
    }

    fun conditions(grid: IntArray?, at: Long, zone: ZoneId): JSONObject = JSONObject()
        .put("hour", Instant.ofEpochMilli(at).atZone(zone).hour)
        .apply { if (grid != null && grid.isNotEmpty()) put("dark", FrameDiff.dark(grid)).put("brightness", Math.round(grid.average() / 255.0 * 100) / 100.0) }

    /** One decision record (about 300-400 bytes). [result] holds answer, confidence, decided and reason. */
    fun record(
        id: String, readingId: String, at: Long, watch: String, task: String?, stage: String, service: String?,
        camera: String?, conditions: JSONObject, costUsd: Double, latencyMs: Long?, result: JSONObject, final: Boolean,
    ): JSONObject = JSONObject().put("id", id).put("reading_id", readingId).put("at", at).put("watch", watch)
        .put("task", task ?: JSONObject.NULL).put("stage", stage).put("service", service ?: JSONObject.NULL)
        .put("camera", camera ?: JSONObject.NULL).put("conditions", conditions)
        .put("cost_usd", costUsd).put("latency_ms", latencyMs ?: JSONObject.NULL).put("result", result).put("final", final)

    fun result(answer: String?, confidence: Double?, decided: Boolean, reason: String): JSONObject = JSONObject()
        .put("answer", answer ?: JSONObject.NULL).put("confidence", confidence ?: JSONObject.NULL).put("decided", decided).put("reason", reason)

    /** The audit label on a free stage's record: what the paid model said when asked anyway. */
    fun audit(answer: String?, confidence: Double?, agreed: Boolean, at: Long, service: String?): JSONObject = JSONObject()
        .put("answer", answer ?: JSONObject.NULL).put("confidence", confidence ?: JSONObject.NULL).put("agreed", agreed)
        .put("source", AUDIT_SOURCE).put("trust", "medium").put("by", service ?: JSONObject.NULL).put("at", at)

    /** The 95% Wilson interval of k in n, or null when n is 0. */
    fun wilson(k: Int, n: Int): JSONArray? {
        if (n <= 0) return null
        val z = 1.96; val p = k.toDouble() / n; val z2 = z * z
        val centre = (p + z2 / (2 * n)) / (1 + z2 / n)
        val half = z * sqrt(p * (1 - p) / n + z2 / (4.0 * n * n)) / (1 + z2 / n)
        fun r(x: Double) = Math.round(x.coerceIn(0.0, 1.0) * 1000) / 1000.0
        return JSONArray().put(r(centre - half)).put(r(centre + half))
    }

    private fun share(k: Int, n: Int): Any = if (n == 0) JSONObject.NULL else Math.round(k * 1000.0 / n) / 1000.0
    private fun usd(x: Double) = Math.round(x * 1e7) / 1e7

    private class Group { var decisions = 0; var audited = 0; var misses = 0
        fun json() = JSONObject().put("decisions", decisions).put("audited", audited).put("misses", misses).put("miss_rate", share(misses, audited)) }

    /** The measures over the records of the last [windowDays] days. */
    fun report(records: List<JSONObject>, now: Long, windowDays: Int, auditShare: Double?, zone: ZoneId = ZoneId.systemDefault()): JSONObject {
        val since = now - windowDays * 86_400_000L
        val inWindow = records.filter { it.optLong("at") >= since }
        val readings = inWindow.map { it.optString("reading_id") }.toSet().size
        class Stage { var count = 0; var decided = 0; var cost = 0.0; val latencies = ArrayList<Long>() }
        val stages = LinkedHashMap<Pair<String, String>, Stage>()
        val byCamera = sortedMapOf<String, Group>(); val byHour = sortedMapOf<Int, Group>(); val byDark = sortedMapOf<String, Group>()
        val bands = Array(5) { IntArray(3) } // per band 0.5-0.6 ... 0.9-1.0: [cheap decisions, audited, agreed]
        val health = JSONObject(); val errors = sortedMapOf<String, Int>()
        var cheap = 0; var cheapDecided = 0; var due = 0; var done = 0; var skipped = 0; var agreed = 0; var dark = 0
        val skipReasons = sortedMapOf<String, Int>()
        for (r in inWindow) {
            val stage = r.optString("stage"); val service = r.optString("service").ifEmpty { stage }
            val res = r.optJSONObject("result") ?: JSONObject()
            val s = stages.getOrPut(stage to service) { Stage() }
            s.count++; if (res.optBoolean("decided")) s.decided++; s.cost += r.optDouble("cost_usd", 0.0)
            (r.opt("latency_ms") as? Number)?.toLong()?.let { s.latencies.add(it) }
            if (r.optJSONObject("conditions")?.optBoolean("dark") == true) dark++
            when (stage) {
                CAMERA -> errors.merge("no_photo", 1, Int::plus)
                PAID_CAP -> errors.merge("over_paid_cap", 1, Int::plus)
                MODEL -> if (res.has("error")) errors.merge(res.optString("error"), 1, Int::plus)
            }
            if (stage != CHANGE_GATE && stage != SOUND_MATCH) continue
            cheap++
            if (!res.optBoolean("decided")) continue
            cheapDecided++
            if (r.optBoolean("audit_due")) due++
            r.optString("audit_skipped").ifEmpty { null }?.let { skipped++; skipReasons.merge(it, 1, Int::plus) }
            val audit = r.optJSONObject("audit")
            val hit = audit?.optBoolean("agreed")
            if (audit != null) { done++; if (hit == true) agreed++ }
            fun add(g: Group) { g.decisions++; if (audit != null) { g.audited++; if (hit != true) g.misses++ } }
            add(byCamera.getOrPut(r.optString("camera").ifEmpty { "none" }) { Group() })
            add(byHour.getOrPut(r.optJSONObject("conditions")?.optInt("hour", -1) ?: -1) { Group() })
            r.optJSONObject("conditions")?.takeIf { it.has("dark") }?.let { add(byDark.getOrPut(if (it.optBoolean("dark")) "dark" else "lit") { Group() }) }
            (res.opt("confidence") as? Number)?.toDouble()?.let { c ->
                // A noul's probability counts by its distance from 0.5 (0.1 and 0.9 are equally sure).
                val sure = if (res.optString("answer") in setOf("yes", "no")) maxOf(c, 1 - c) else c
                if (sure >= 0.5) { val b = bands[((sure - 0.5) * 10).toInt().coerceIn(0, 4)]; b[0]++; if (audit != null) { b[1]++; if (hit == true) b[2]++ } }
            }
        }
        val misses = done - agreed
        errors.forEach { (k, v) -> health.put(k, v) }
        return JSONObject()
            .put("window_days", windowDays).put("since", isoAt(since)).put("records", inWindow.size).put("readings", readings)
            .put("settings", settings(auditShare))
            .put("stages", JSONArray(stages.map { (k, s) ->
                val l = s.latencies.sorted()
                JSONObject().put("stage", k.first).put("service", k.second).put("count", s.count).put("decided", s.decided)
                    .put("cost_usd", usd(s.cost)).put("latency_ms_p50", if (l.isEmpty()) JSONObject.NULL else l[l.size / 2])
            }))
            .put("reuse", JSONObject().put("free_checks", cheap).put("answered_free", cheapDecided).put("share", share(cheapDecided, cheap)))
            .put("audit", JSONObject().put("due", due).put("done", done).put("skipped", skipped)
                .put("skipped_because", JSONObject(skipReasons as Map<*, *>))
                .put("agreed", agreed).put("misses", misses).put("miss_rate", share(misses, done)).put("miss_rate_interval_95", wilson(misses, done) ?: JSONObject.NULL))
            .put("calibration", JSONArray(bands.mapIndexed { i, b ->
                JSONObject().put("band", "%.1f-%.1f".format(java.util.Locale.ROOT, 0.5 + i / 10.0, 0.6 + i / 10.0)).put("free_answers", b[0])
                    .put("audited", b[1]).put("agreed", b[2]).put("agreement", share(b[2], b[1]))
            }))
            .put("health", health.put("dark_records", dark))
            .put("by", JSONObject()
                .put("camera", JSONObject().apply { byCamera.forEach { (k, g) -> put(k, g.json()) } })
                .put("hour", JSONObject().apply { byHour.forEach { (k, g) -> put(k.toString(), g.json()) } })
                .put("light", JSONObject().apply { byDark.forEach { (k, g) -> put(k, g.json()) } }))
            .put("labels", JSONObject().put("sources", JSONArray().put(AUDIT_SOURCE))
                .put("about", "Audits are the paid model's answer, so they show where the free stage disagrees with it, never where the paid model is wrong. Owner labels come in a later phase."))
            .put("notes", JSONArray(notes(cheapDecided, done, misses, wilson(misses, done))))
    }

    /** Plain hints with the numbers they rest on; the agent decides what to change. */
    private fun notes(answeredFree: Int, audited: Int, misses: Int, interval: JSONArray?): List<String> = buildList {
        if (answeredFree == 0) add("The free stage answered nothing in this window, so there is nothing to audit.")
        else if (audited < 30) add("Only $audited audited readings: too few to tune on. Wait, or raise audit_share for a while.")
        else if (interval != null && interval.getDouble(1) < 0.02) add("$misses of $audited audited free answers disagreed with the paid model (95% interval up to ${interval.getDouble(1)}): there is room to reuse answers for longer (local.max_reuse_s) or use a lower local.sensitivity.")
        else if (interval != null && interval.getDouble(0) > 0.1) add("$misses of $audited audited free answers disagreed with the paid model (95% interval from ${interval.getDouble(0)}): the free stage reuses answers too often; try a higher local.sensitivity or a shorter local.max_reuse_s.")
    }

    /**
     * One watch's records: kept in memory and in an append-only file of JSON lines, trimmed to [KEEP_DAYS] and
     * [MAX_RECORDS] (oldest first). A record changes once more when its audit arrives ([attach]); the file is
     * rewritten when it has grown by a quarter past the cap and when the watch starts.
     */
    class Log(private val file: File?, private val keepMs: Long = KEEP_DAYS * 86_400_000L, private val max: Int = MAX_RECORDS) {
        private val records = ArrayDeque<JSONObject>()
        private var lines = 0

        init { load() }

        @Synchronized private fun load() {
            val f = file ?: return
            if (!f.exists()) return
            val now = System.currentTimeMillis()
            val byId = LinkedHashMap<String, JSONObject>()
            runCatching { f.forEachLine { line -> runCatching { JSONObject(line) }.getOrNull()?.let { r -> byId[r.optString("id")] = r } } }
            byId.values.filter { now - it.optLong("at") < keepMs }.sortedBy { it.optLong("at") }.takeLast(max).forEach { records.addLast(it) }
            rewrite()
        }

        @Synchronized fun add(r: JSONObject) {
            records.addLast(r)
            trim(r.optLong("at"))
            append(r)
        }

        /** Adds [fields] to the record [id] (an audit arriving). */
        @Synchronized fun attach(id: String, fields: JSONObject) {
            val r = records.lastOrNull { it.optString("id") == id } ?: return
            fields.keys().forEach { r.put(it, fields.get(it)) }
            append(r)
        }

        @Synchronized fun all(): List<JSONObject> = records.toList()

        private fun trim(now: Long) {
            while (records.isNotEmpty() && (records.size > max || now - records.first().optLong("at") >= keepMs)) records.removeFirst()
        }

        private fun append(r: JSONObject) {
            val f = file ?: return
            runCatching { f.parentFile?.mkdirs(); f.appendText(r.toString() + "\n"); lines++ }
            if (lines > max + max / 4 || lines > records.size * 2 + 100) rewrite()
        }

        private fun rewrite() {
            val f = file ?: return
            runCatching {
                f.parentFile?.mkdirs()
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.bufferedWriter().use { out -> records.forEach { out.write(it.toString()); out.write("\n") } }
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
                lines = records.size
            }
        }

        @Synchronized fun delete() { records.clear(); file?.delete() }
    }
}
