package cc.bridgit.app.watch

import cc.bridgit.app.judgment.Jev
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Watches that remember (plan: docs/plans/phone-apps): a watch's `tally` turns each answer into counts,
 * episodes (back-to-back readings with the same answer) and day totals kept on the phone for weeks, and its
 * `rules` fire on those totals with the agent offline ("no drinking for 4 h while the dog was in view").
 * Pure: no Android, unit-tested. [State] is mutable and saved as JSON by [Watches].
 */
object WatchTally {
    const val MAX_KEEP_DAYS = 30
    const val DEFAULT_KEEP_DAYS = 30
    const val MAX_RULES = 5
    const val MAX_GAP_S = 6 * 3600
    const val DEFAULT_MIN_CONFIDENCE = 0.5
    const val DEFAULT_MIN_PROBABILITY = 0.5
    /** Days in the summary and the typical-gap median. */
    const val SUMMARY_DAYS = 7
    /** Episodes returned by GET /v1/watches/{id}/tally at most. */
    const val MAX_EPISODES_OUT = 500
    /** Episodes kept at most (oldest dropped first), whatever keep_days says. */
    const val MAX_EPISODES_KEPT = 20_000
    private val RULE_ID = Regex("^[A-Za-z0-9_-]{1,40}$")
    private val HHMM = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$")

    class Spec(
        /** The question whose answer is counted. */
        val track: String,
        /** "choice" (each pick is a label) or "noul" (the question id is the label when likely enough). */
        val kind: String,
        /** Labels counted; null = every option. */
        val labels: List<String>?,
        val minConfidence: Double,
        val minProbability: Double,
        /** When set, a reading only counts (and only adds to watched time) while this holds, e.g. "a dog is visible". */
        val seen: WatchRules.Condition?,
        /** Readings this far apart still belong to the same episode. */
        val gapS: Int,
        /** Shorter episodes (fewer readings) are dropped as noise. */
        val minEpisodeReadings: Int,
        val keepDays: Int,
        val rules: List<Rule>,
        val labelGapS: Map<String, Int> = emptyMap(),
        val continuousGaps: Boolean = false,
    ) {
        fun json(): JSONObject = JSONObject().put("track", track).put("kind", kind)
            .put("labels", labels?.let { JSONArray(it) } ?: JSONObject.NULL)
            .put(if (kind == "choice") "min_confidence" else "min", if (kind == "choice") minConfidence else minProbability)
            .put("seen", seen?.json() ?: JSONObject.NULL).put("gap_s", gapS).put("min_episode_readings", minEpisodeReadings)
            .put("keep_days", keepDays).put("rules", JSONArray(rules.map { it.json() }))
            .apply { if (continuousGaps) put("typical_gap_basis","continuous_seen") }
            .apply { if (labelGapS.isNotEmpty()) put("label_gap_s", JSONObject(labelGapS)) }
    }

    sealed class When {
        abstract val label: String
        /** No episode of [label] for [seconds] (counting only watched time when [whileSeen]). */
        data class NoneFor(override val label: String, val seconds: Int, val whileSeen: Boolean) : When()
        /** Fewer than [n] episodes of [label] today by [by] local time; once a day. */
        data class FewerThanBy(override val label: String, val n: Int, val by: LocalTime) : When()
        /** At least [n] episodes of [label] started in the last [seconds]. */
        data class AtLeastWithin(override val label: String, val n: Int, val seconds: Int) : When()
    }

    /**
     * A rule. What it does when it fires (docs/applet-vocabulary.md) is at most one of: [call], one raw endpoint call
     * ({message} and {label} filled in); [flow], a called or button flow to start (a workflow id, or an installed
     * applet's package flow name) with [with]; or [step], one step from the flow menu run on its own.
     */
    class Rule(val id: String, val `when`: When, val cooldownS: Int, val call: JSONObject?, val raw: JSONObject,
               val flow: String? = null, val with: JSONObject? = null, val step: JSONObject? = null) {
        fun json(): JSONObject = JSONObject(raw.toString())
    }

    /** For rules that make a raw call a step could make: the do.step that does the same, as a hint (never applied). */
    fun hints(spec: Spec?): List<JSONObject> = spec?.rules.orEmpty().mapNotNull { r ->
        r.call?.let { cc.bridgit.app.platform.FlowSteps.stepHint(it) }?.let { JSONObject().put("rule", r.id).put("do", it)
            .put("why", "One vocabulary: rules, schedules and flows share the flow step menu. The raw call keeps working.") }
    }

    /** Reads `tally` and `rules` of a watch body. [questions] are the watch's typed questions. */
    fun parse(rawTally: Any?, rawRules: Any?, questions: JSONObject, intervalS: Int, checkCall: (JSONObject, String) -> Unit,
              defaultGapS: Int = maxOf(3 * intervalS, 60), checkFlow: (String, String) -> Unit = { _, _ -> }): Spec? {
        if (rawTally == null) {
            if (rawRules != null) throw WatchRules.Bad("rules work on a tally: add tally {track: <question id>} so the phone has something to count.")
            return null
        }
        val t = rawTally as? JSONObject ?: throw WatchRules.Bad("tally must be an object, e.g. {\"track\": \"activity\"}.")
        val known = setOf("track", "labels", "min_confidence", "min", "seen", "gap_s", "label_gap_s", "typical_gap_basis", "min_episode_readings", "keep_days")
        t.keys().asSequence().firstOrNull { it !in known }?.let { throw WatchRules.Bad("tally.$it isn't a tally field (${known.joinToString()}).") }
        val track = (t.opt("track") as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: (if (questions.length() == 1) questions.keys().next() else null)
            ?: throw WatchRules.Bad("tally.track must name the question to count (one of ${questions.keys().asSequence().joinToString()}).")
        val q = questions.optJSONObject(track) ?: throw WatchRules.Bad("tally.track $track isn't one of your questions (${questions.keys().asSequence().joinToString()}).")
        val kind = when (q.optString("type")) {
            "choice" -> "choice"
            "noul" -> "noul"
            else -> throw WatchRules.Bad("tally.track must be a choice or noul question; $track is ${q.optString("type")}.")
        }
        val options = q.optJSONObject("criteria")?.keys()?.asSequence()?.toList()
        val labels = when (val l = t.opt("labels")) {
            null -> null
            is JSONArray -> {
                if (kind == "noul") throw WatchRules.Bad("tally.labels picks options of a choice question; a noul question counts one thing, $track.")
                (0 until l.length()).map { i -> (l.opt(i) as? String)?.takeIf { it.isNotEmpty() } ?: throw WatchRules.Bad("tally.labels must be a list of option names.") }
                    .distinct().also { list ->
                        if (list.isEmpty()) throw WatchRules.Bad("tally.labels is empty; leave it out to count every option.")
                        if (options != null) list.firstOrNull { it !in options }?.let { throw WatchRules.Bad("tally.labels: $it isn't one of $track's options (${options.joinToString()}).") }
                    }
            }
            else -> throw WatchRules.Bad("tally.labels must be a list of option names.")
        }
        val allLabels: List<String>? = if (kind == "noul") listOf(track) else labels ?: options
        val minConf = prob(t, "min_confidence", DEFAULT_MIN_CONFIDENCE)
        val minProb = prob(t, "min", DEFAULT_MIN_PROBABILITY)
        if (kind == "choice" && t.has("min")) throw WatchRules.Bad("tally.min is for a noul question; with a choice use tally.min_confidence.")
        if (kind == "noul" && t.has("min_confidence")) throw WatchRules.Bad("tally.min_confidence is for a choice question; with a noul use tally.min.")
        val seen = t.opt("seen")?.let { WatchRules.conditions(it, questions, "tally.seen").single() }
        val gap = int(t, "gap_s", defaultGapS, intervalS, MAX_GAP_S, "tally.")
        val labelGaps = if (t.has("label_gap_s")) {
            val overrides = t.optJSONObject("label_gap_s") ?: throw WatchRules.Bad("tally.label_gap_s must map tracked labels to seconds.")
            overrides.keys().asSequence().associateWith { label ->
                if (allLabels == null || label !in allLabels) throw WatchRules.Bad("tally.label_gap_s.$label must name a tracked label.")
                int(overrides, label, gap, 1, MAX_GAP_S, "tally.label_gap_s.")
            }
        } else emptyMap()
        val minEp = int(t, "min_episode_readings", 1, 1, 20, "tally.")
        val keep = int(t, "keep_days", DEFAULT_KEEP_DAYS, 1, MAX_KEEP_DAYS, "tally.")
        val rules = rules(rawRules, allLabels, checkCall, checkFlow)
        val continuous = when (t.opt("typical_gap_basis")) {
            null, "elapsed" -> false
            "continuous_seen" -> true
            else -> throw WatchRules.Bad("tally.typical_gap_basis must be elapsed or continuous_seen.")
        }
        return Spec(track, kind, labels, minConf, minProb, seen, gap, minEp, keep, rules, labelGaps, continuous)
    }

    private fun rules(raw: Any?, labels: List<String>?, checkCall: (JSONObject, String) -> Unit, checkFlow: (String, String) -> Unit): List<Rule> {
        val list = when (raw) {
            null -> return emptyList()
            is JSONArray -> (0 until raw.length()).map { raw.optJSONObject(it) ?: throw WatchRules.Bad("rules[$it] must be an object.") }
            else -> throw WatchRules.Bad("rules must be a list of 1-$MAX_RULES rules.")
        }
        if (list.size > MAX_RULES) throw WatchRules.Bad("rules: at most $MAX_RULES.")
        val ids = HashSet<String>()
        return list.mapIndexed { i, r ->
            val at = "rules[$i]"
            r.keys().asSequence().firstOrNull { it !in setOf("id", "when", "do", "cooldown_s") }?.let { throw WatchRules.Bad("$at.$it isn't a rule field (id, when, do, cooldown_s).") }
            val id = (r.opt("id") as? String)?.trim() ?: "rule${i + 1}"
            if (!RULE_ID.matches(id)) throw WatchRules.Bad("$at.id must be 1-40 letters, digits, - or _.")
            if (!ids.add(id)) throw WatchRules.Bad("$at.id $id is used twice.")
            val w = r.optJSONObject("when") ?: throw WatchRules.Bad("$at.when is required: {none_for_s, of} or {fewer_than, of, by} or {at_least, of, within_s}.")
            val of = (w.opt("of") as? String)?.takeIf { it.isNotEmpty() } ?: throw WatchRules.Bad("$at.when.of must name a counted label${labels?.let { " (${it.joinToString()})" } ?: ""}.")
            if (labels != null && of !in labels) throw WatchRules.Bad("$at.when.of $of isn't counted; counted: ${labels.joinToString()}.")
            val kinds = listOf("none_for_s", "fewer_than", "at_least").filter { w.has(it) }
            if (kinds.size != 1) throw WatchRules.Bad("$at.when needs exactly one of none_for_s, fewer_than, at_least.")
            val whenKeys = when (kinds[0]) { "none_for_s" -> setOf("none_for_s", "of", "while_seen"); "fewer_than" -> setOf("fewer_than", "of", "by"); else -> setOf("at_least", "of", "within_s") }
            w.keys().asSequence().firstOrNull { it !in whenKeys }?.let { throw WatchRules.Bad("$at.when.$it doesn't go with ${kinds[0]} (${whenKeys.joinToString()}).") }
            val cond: When = when (kinds[0]) {
                "none_for_s" -> When.NoneFor(of, int(w, "none_for_s", 0, 60, 7 * 86400, "$at.when."),
                    when (val s = w.opt("while_seen")) { null -> true; is Boolean -> s; else -> throw WatchRules.Bad("$at.when.while_seen must be true or false.") })
                "fewer_than" -> {
                    val by = (w.opt("by") as? String)?.trim()?.let { HHMM.matchEntire(it) }
                        ?: throw WatchRules.Bad("$at.when.by must be a local time like \"10:00\".")
                    When.FewerThanBy(of, int(w, "fewer_than", 0, 1, 1000, "$at.when."), LocalTime.of(by.groupValues[1].toInt(), by.groupValues[2].toInt()))
                }
                else -> When.AtLeastWithin(of, int(w, "at_least", 0, 1, 1000, "$at.when."), int(w, "within_s", 0, 60, 86400, "$at.when."))
            }
            val defaultCooldown = when (cond) { is When.NoneFor -> cond.seconds; is When.AtLeastWithin -> cond.seconds; is When.FewerThanBy -> 86400 }
            val cooldown = if (cond is When.FewerThanBy) {
                if (r.has("cooldown_s")) throw WatchRules.Bad("$at.cooldown_s: a fewer_than rule fires at most once a day already.")
                86400
            } else int(r, "cooldown_s", defaultCooldown, 60, 7 * 86400, "$at.")
            val d = r.opt("do")
            if (d != null && d !is JSONObject) throw WatchRules.Bad("$at.do must be {\"flow\": …}, {\"step\": {…}} or an endpoint call {method, path, body?}.")
            val act = (d as? JSONObject)?.let { action(it, "$at.do", checkCall, checkFlow) }
            Rule(id, cond, cooldown, act?.call, r, act?.flow, act?.with, act?.step)
        }
    }

    /** A checked `do`: exactly one of an endpoint [call], a [flow] (with [with]) or a single [step]. */
    class Action(val call: JSONObject? = null, val flow: String? = null, val with: JSONObject? = null, val step: JSONObject? = null)

    private val FLOW_REF = Regex("^[A-Za-z0-9_-]{1,64}$")

    /**
     * The one `do` (docs/applet-vocabulary.md), as watch rules write it. [checkCall] checks an endpoint call (a raw
     * call, or the call a single step makes); [checkFlow] checks that a flow reference can be started.
     */
    fun action(d: JSONObject, at: String, checkCall: (JSONObject, String) -> Unit, checkFlow: (String, String) -> Unit): Action {
        val forms = listOfNotNull("flow".takeIf { d.has("flow") }, "step".takeIf { d.has("step") }, "call".takeIf { d.has("method") || d.has("path") })
        if (forms.size != 1) throw WatchRules.Bad("$at needs exactly one of: {\"flow\": <flow id>}, {\"step\": {one step}} or an endpoint call {method, path, body?}.")
        return when (forms[0]) {
            "flow" -> {
                d.keys().asSequence().firstOrNull { it != "flow" && it != "with" }?.let { throw WatchRules.Bad("$at.$it doesn't go with flow (flow, with).") }
                val ref = (d.opt("flow") as? String)?.takeIf { FLOW_REF.matches(it) }
                    ?: throw WatchRules.Bad("$at.flow must be the id of one of your workflows (in an applet package: {\"\$flow\": \"<flow id>\"}).")
                val with = d.opt("with")?.let { w ->
                    val o = w as? JSONObject ?: throw WatchRules.Bad("$at.with must be an object of values for the flow.")
                    if (o.length() > cc.bridgit.app.platform.FlowSteps.MAX_WITH || o.toString().length > 4096) throw WatchRules.Bad("$at.with: at most ${cc.bridgit.app.platform.FlowSteps.MAX_WITH} values, 4 KB.")
                    o
                }
                checkFlow(ref, "$at.flow")
                Action(flow = ref, with = with)
            }
            "step" -> {
                if (d.length() != 1) throw WatchRules.Bad("$at: step goes alone: {\"step\": {…}}.")
                val step = d.opt("step") as? JSONObject
                try { cc.bridgit.app.platform.FlowSteps.checkInline(step) } catch (e: cc.bridgit.app.api.ApiError) { throw WatchRules.Bad("$at.${e.message}") }
                checkCall(cc.bridgit.app.platform.FlowSteps.inlineCall(step!!, JSONObject()), "$at.step")
                Action(step = step)
            }
            else -> { checkCall(d, at); Action(call = d) }
        }
    }

    // ---- state -----------------------------------------------------------------------------------------

    /**
     * [before]: when it opened, the time of the last reading of each other label (e.g. the last "clean" reading
     * before a "messy" episode, which a still-open clean episode's `last` can't tell); null in episodes saved before.
     */
    class Episode(val label: String, val start: Long, var last: Long, var readings: Int, val before: Map<String, Long>? = null, var intervalS: Int? = null)

    class State(var startedAt: Long) {
        /** Episodes still going, by label. */
        val open = LinkedHashMap<String, Episode>()
        /** Finished episodes, oldest first. */
        val episodes = ArrayList<Episode>()
        /** Watched time: [start, end] spans where readings counted (seen), oldest first. */
        val spans = ArrayList<LongArray>()
        /** Per local day: readings per label, plus "_seen" and "_checks". */
        val days = LinkedHashMap<String, LinkedHashMap<String, Int>>()
        val ruleFiredAt = LinkedHashMap<String, Long>()
        /** fewer_than rules: the day they last fired (or were settled). */
        val ruleDay = LinkedHashMap<String, String>()
        var interrupted = false
        var lastSeenAt = 0L
        /** The last reading of each label. */
        val lastAt = LinkedHashMap<String, Long>()

        fun json(): JSONObject = JSONObject().put("started_at", startedAt).put("last_seen_at", lastSeenAt).put("interrupted", interrupted)
            .put("last_at", JSONObject(lastAt as Map<*, *>))
            .put("open", JSONArray(open.values.map { ep(it) })).put("episodes", JSONArray(episodes.map { ep(it) }))
            .put("spans", JSONArray(spans.map { JSONArray(it.toList()) }))
            .put("days", JSONObject().apply { days.forEach { (d, m) -> put(d, JSONObject(m as Map<*, *>)) } })
            .put("rule_fired_at", JSONObject(ruleFiredAt as Map<*, *>)).put("rule_day", JSONObject(ruleDay as Map<*, *>))

        private fun ep(e: Episode) = JSONArray().put(e.label).put(e.start).put(e.last).put(e.readings)
            .apply { e.before?.let { put(JSONObject(it as Map<*, *>)) }; e.intervalS?.let { put(5,it) } }

        companion object {
            fun from(o: JSONObject): State = State(o.optLong("started_at")).apply {
                lastSeenAt = o.optLong("last_seen_at")
                interrupted = o.optBoolean("interrupted")
                fun eps(a: JSONArray?) = (0 until (a?.length() ?: 0)).mapNotNull { i -> a!!.optJSONArray(i)?.let {
                    Episode(it.getString(0), it.getLong(1), it.getLong(2), it.getInt(3), it.optJSONObject(4)?.let { b -> b.keys().asSequence().associateWith { k -> b.getLong(k) } }, it.optInt(5).takeIf { n -> n > 0 })
                } }
                o.optJSONObject("last_at")?.let { r -> r.keys().forEach { lastAt[it] = r.getLong(it) } }
                eps(o.optJSONArray("open")).forEach { open[it.label] = it }
                episodes.addAll(eps(o.optJSONArray("episodes")))
                o.optJSONArray("spans")?.let { a -> for (i in 0 until a.length()) a.optJSONArray(i)?.let { spans.add(if (it.length() >= 3) longArrayOf(it.getLong(0),it.getLong(1),it.getLong(2)) else longArrayOf(it.getLong(0),it.getLong(1))) } }
                o.optJSONObject("days")?.let { d -> d.keys().forEach { k -> val m = LinkedHashMap<String, Int>(); d.getJSONObject(k).let { x -> x.keys().forEach { m[it] = x.getInt(it) } }; days[k] = m } }
                o.optJSONObject("rule_fired_at")?.let { r -> r.keys().forEach { ruleFiredAt[it] = r.getLong(it) } }
                o.optJSONObject("rule_day")?.let { r -> r.keys().forEach { ruleDay[it] = r.getString(it) } }
            }
        }
    }

    /** Pin legacy frame lengths before changing cadence so a new interval cannot rewrite old totals. */
    fun preserveInterval(state: State, intervalS: Int) {
        for (episode in state.episodes + state.open.values) if (episode.intervalS == null) episode.intervalS = intervalS
        for ((index,span) in state.spans.withIndex()) {
            if ((span.getOrNull(2) ?: 0) <= 0) state.spans[index] = longArrayOf(span[0],span[1],intervalS.toLong())
        }
    }

    /** The label [answer] gives for [spec], or null (unsure, not counted, or missing). */
    fun labelOf(spec: Spec, answer: Jev.Answer): String? = when (spec.kind) {
        "choice" -> answer.choice(spec.track)?.let { (pick, conf) -> pick.takeIf { conf >= spec.minConfidence && (spec.labels == null || it in spec.labels) } }
        else -> answer.noul(spec.track)?.let { p -> spec.track.takeIf { p >= spec.minProbability } }
    }

    /** Whether [answer] counts as watched time ("the dog is in view"); always true without `seen`. */
    fun seenOf(spec: Spec, answer: Jev.Answer): Boolean = spec.seen?.let { WatchRules.matches(listOf(it), answer) } ?: true

    fun day(at: Long, zone: ZoneId): String = Instant.ofEpochMilli(at).atZone(zone).toLocalDate().toString()

    /**
     * One check. [answer] null = no usable answer (no photo, model failed): nothing is counted and the
     * watched time has a gap. Returns the episodes that closed.
     */
    fun record(spec: Spec, st: State, at: Long, answer: Jev.Answer?, zone: ZoneId, intervalS: Int? = null): List<Episode> {
        val d = st.days.getOrPut(day(at, zone)) { LinkedHashMap() }
        d["_checks"] = (d["_checks"] ?: 0) + 1
        val seen = answer != null && seenOf(spec, answer)
        val label = if (seen) labelOf(spec, answer!!) else null
        if (seen) {
            d["_seen"] = (d["_seen"] ?: 0) + 1
            val lastSpan = st.spans.lastOrNull()
            val joins = lastSpan != null && !(spec.continuousGaps && st.interrupted) && at - lastSpan[1] in 0..spec.gapS * 1000L
            val span = longArrayOf(if (joins) lastSpan!![0] else at, at, intervalS?.toLong() ?: 0)
            if (joins) st.spans[st.spans.lastIndex] = span else st.spans.add(span)
            st.lastSeenAt = at
        }
        st.interrupted = !seen
        val closed = ArrayList<Episode>()
        // An episode ends once its answer hasn't come up for gap_s; a different answer in between doesn't end it
        // (one "drinking" reading in the middle of a meal shouldn't split the meal in two).
        for (e in st.open.values.toList()) {
            if (at - e.last > (spec.labelGapS[e.label] ?: spec.gapS) * 1000L) { st.open.remove(e.label); if (keep(spec, e)) { st.episodes.add(e); closed.add(e) } }
        }
        if (label != null) {
            d[label] = (d[label] ?: 0) + 1
            val e = st.open[label]
            if (e != null) { e.last = at; e.readings++; e.intervalS = intervalS } else st.open[label] = Episode(label, at, at, 1, st.lastAt.filterKeys { it != label },intervalS)
            st.lastAt[label] = at
        }
        prune(spec, st, at, zone)
        return closed
    }

    private fun keep(spec: Spec, e: Episode) = e.readings >= spec.minEpisodeReadings

    private fun prune(spec: Spec, st: State, now: Long, zone: ZoneId) {
        val cutoff = now - spec.keepDays * 86_400_000L
        st.episodes.removeAll { it.last < cutoff }
        while (st.episodes.size > MAX_EPISODES_KEPT) st.episodes.removeAt(0)
        st.spans.removeAll { it[1] < cutoff }
        val firstDay = day(cutoff, zone)
        st.days.keys.filter { it < firstDay }.forEach { st.days.remove(it) }
    }

    /** Finished and still-going episodes of [label] (still-going ones only once they pass min_episode_readings). */
    fun episodesOf(spec: Spec, st: State, label: String? = null): List<Episode> =
        (st.episodes + st.open.values.filter { keep(spec, it) }).filter { label == null || it.label == label }.sortedBy { it.start }

    /** An episode's length: first to last reading, plus one interval for the reading itself. */
    fun lengthMs(e: Episode, intervalS: Int): Long = e.last - e.start + (e.intervalS ?: intervalS) * 1000L

    /** Watched milliseconds between [from] and [to]. */
    fun seenMs(st: State, from: Long, to: Long, intervalS: Int): Long {
        var coveredUntil = from
        var total = 0L
        for (span in st.spans) {
            val frameSeconds = span.getOrNull(2)?.takeIf { it > 0 } ?: intervalS.toLong()
            val start = maxOf(span[0], coveredUntil)
            val end = minOf(span[1] + frameSeconds * 1000L, to)
            if (end > start) {
                total += end - start
                coveredUntil = end
            }
        }
        return total
    }

    // ---- rules -----------------------------------------------------------------------------------------

    class Fired(val rule: Rule, val message: String)

    /** Rules that fire now; marks them fired in [st]. */
    fun due(spec: Spec, st: State, now: Long, zone: ZoneId, intervalS: Int): List<Fired> {
        val out = ArrayList<Fired>()
        val today = day(now, zone)
        for (r in spec.rules) {
            val last = st.ruleFiredAt[r.id]
            val cooled = last == null || now - last >= r.cooldownS * 1000L
            val msg: String? = when (val w = r.`when`) {
                is When.NoneFor -> {
                    if (st.open[w.label]?.let { keep(spec, it) } == true) null
                    else {
                        val lastEp = episodesOf(spec, st, w.label).lastOrNull()?.last
                        // Since the last episode, the watch start, or the last time this rule fired, whichever is latest.
                        val since = maxOf(lastEp ?: 0L, st.startedAt, last ?: 0L)
                        val gone = if (w.whileSeen) seenMs(st, since, now, intervalS) else now - since
                        if (gone >= w.seconds * 1000L)
                            "No ${w.label} for ${words(gone)}${if (w.whileSeen) " of watched time" else ""}" +
                                (lastEp?.let { " (last one ended ${words(now - it)} ago)." } ?: " since the watch started.")
                        else null
                    }
                }
                is When.FewerThanBy -> {
                    val byToday = LocalDate.parse(today).atTime(w.by).atZone(zone).toInstant().toEpochMilli()
                    when {
                        st.ruleDay[r.id] == today || now < byToday -> null
                        // Started after the deadline, or no successful check today: say nothing for today.
                        st.startedAt > byToday || (st.days[today]?.get("_checks") ?: 0) == 0 -> { st.ruleDay[r.id] = today; null }
                        else -> {
                            st.ruleDay[r.id] = today
                            val n = episodesOf(spec, st, w.label).count { day(it.start, zone) == today }
                            if (n < w.n) "Only $n ${w.label} by ${w.by} today (wanted at least ${w.n})." else null
                        }
                    }
                }
                is When.AtLeastWithin -> {
                    val from = maxOf(now - w.seconds * 1000L, last ?: 0L)
                    val n = episodesOf(spec, st, w.label).count { it.start >= from }
                    if (n >= w.n) "$n ${w.label} in the last ${words(w.seconds * 1000L)}." else null
                }
            }
            if (msg != null && (cooled || r.`when` is When.FewerThanBy)) { st.ruleFiredAt[r.id] = now; out.add(Fired(r, msg)) }
        }
        return out
    }

    /** A rule's `do` call with {message} and {label} in its text values filled in. Returns a copy. */
    fun fill(call: JSONObject, message: String, label: String): JSONObject {
        fun walk(v: Any?): Any? = when (v) {
            is String -> v.replace("{message}", message).replace("{label}", label)
            is JSONObject -> JSONObject().also { o -> v.keys().forEach { k -> o.put(k, walk(v.get(k))) } }
            is JSONArray -> JSONArray().also { a -> for (i in 0 until v.length()) a.put(walk(v.get(i))) }
            else -> v
        }
        return walk(JSONObject(call.toString())) as JSONObject
    }

    fun words(ms: Long): String {
        val m = ms / 60_000
        return when {
            m < 1 -> "${ms / 1000} s"
            m < 60 -> "$m min"
            m % 60 == 0L -> "${m / 60} h"
            else -> "${m / 60} h ${m % 60} min"
        }
    }

    // ---- summary ---------------------------------------------------------------------------------------

    private val ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    private fun iso(at: Long, zone: ZoneId) = ISO.format(Instant.ofEpochMilli(at).atZone(zone))

    /** Median minutes between the starts of consecutive [label] episodes over the last [SUMMARY_DAYS] days. Continuous coverage needs one covered pair; elapsed mode needs three episodes. */
    fun typicalGapMin(spec: Spec, st: State, label: String, now: Long): Double? {
        val starts = episodesOf(spec, st, label).map { it.start }.filter { it >= now - SUMMARY_DAYS * 86_400_000L }
        if (starts.size < (if (spec.continuousGaps) 2 else 3)) return null
        // Both sequences are chronological. Walk the spans once, even for thousands of episodes.
        var span = 0
        val gaps = starts.zipWithNext().mapNotNull { (a,b) ->
            if (spec.continuousGaps) {
                while (span < st.spans.size && st.spans[span][1] < b) span++
                if (span == st.spans.size || st.spans[span][0] > a || b <= a) return@mapNotNull null
            }
            (b - a) / 60_000.0
        }.sorted()
        if (gaps.isEmpty()) return null
        val mid = gaps.size / 2
        val m = if (gaps.size % 2 == 1) gaps[mid] else (gaps[mid - 1] + gaps[mid]) / 2
        return Math.round(m * 10) / 10.0
    }

    private fun labelsSeen(spec: Spec, st: State): List<String> =
        ((if (spec.kind == "noul") listOf(spec.track) else spec.labels ?: emptyList()) +
            episodesOf(spec, st).map { it.label } + st.days.values.flatMap { it.keys }.filter { !it.startsWith("_") }).distinct()

    private fun dayJson(spec: Spec, st: State, date: String, zone: ZoneId, intervalS: Int, labels: List<String>): JSONObject {
        val start = LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = LocalDate.parse(date).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val counts = st.days[date]
        val per = JSONObject()
        for (l in labels) {
            val eps = episodesOf(spec, st, l).filter { it.start in start until end }
            per.put(l, JSONObject().put("episodes", eps.size)
                .put("minutes", Math.round(eps.sumOf { lengthMs(it, intervalS) } / 6_000.0) / 10.0)
                .put("readings", counts?.get(l) ?: 0))
        }
        return JSONObject().put("date", date).put("checks", counts?.get("_checks") ?: 0)
            .put("watched_minutes", Math.round(seenMs(st, start, end, intervalS) / 6_000.0) / 10.0).put("labels", per)
    }

    /** What the agent, a card and the owner see: today, the last few days, what's going on, typical gaps, rules. */
    fun summary(spec: Spec, st: State, now: Long, zone: ZoneId, intervalS: Int): JSONObject {
        val labels = labelsSeen(spec, st)
        val today = LocalDate.parse(day(now, zone))
        val days = JSONArray()
        for (i in 0 until SUMMARY_DAYS) {
            val d = today.minusDays(i.toLong())
            if (d.atStartOfDay(zone).plusDays(1).toInstant().toEpochMilli() <= st.startedAt) break
            days.put(dayJson(spec, st, d.toString(), zone, intervalS, labels))
        }
        val last = JSONObject(); val ongoing = JSONObject(); val gaps = JSONObject()
        for (l in labels) {
            episodesOf(spec, st, l).lastOrNull()?.let { last.put(l, iso(it.last, zone)) }
            st.open[l]?.takeIf { keep(spec, it) }?.let { ongoing.put(l, JSONObject().put("since", iso(it.start, zone)).put("minutes", Math.round(lengthMs(it, intervalS) / 6_000.0) / 10.0)) }
            typicalGapMin(spec, st, l, now)?.let { gaps.put(l, it) }
        }
        return JSONObject().put("track", spec.track).put("labels", JSONArray(labels))
            .put("watching_since", iso(st.startedAt, zone))
            .put("last_seen_at", if (st.lastSeenAt > 0) iso(st.lastSeenAt, zone) else JSONObject.NULL)
            .put("today", days.optJSONObject(0) ?: dayJson(spec, st, today.toString(), zone, intervalS, labels))
            .put("days", days).put("ongoing", ongoing).put("last", last).put("typical_gap_minutes", gaps)
            .put("rules", JSONArray(spec.rules.map { r -> JSONObject().put("id", r.id)
                .put("last_fired_at", st.ruleFiredAt[r.id]?.let { iso(it, zone) } ?: JSONObject.NULL) }))
    }

    /** One line for the owner's list: "eating 3 times (12 min), drinking 5 times today". */
    /** [names]: words to show instead of a label id (a situation's title). */
    fun todayLine(spec: Spec, st: State, now: Long, zone: ZoneId, intervalS: Int, names: Map<String, String> = emptyMap()): String {
        val sum = summary(spec, st, now, zone, intervalS)
        val t = sum.getJSONObject("today").getJSONObject("labels")
        val order = sum.getJSONArray("labels").let { a -> (0 until a.length()).map { a.getString(it) } }
        val parts = order.asSequence().filter { t.has(it) }.mapNotNull { l ->
            val o = t.getJSONObject(l); val n = o.getInt("episodes")
            if (n == 0) null else "${names[l] ?: l} ${if (n == 1) "once" else "$n times"} (${o.getDouble("minutes").let { if (it >= 1) "${it.toInt()} min" else "<1 min" }})"
        }.toList()
        return if (parts.isEmpty()) "Nothing counted yet today." else parts.joinToString(", ") + " today."
    }

    /** Episodes for GET /v1/watches/{id}/tally, newest last, from the last [days] days. */
    fun episodesJson(spec: Spec, st: State, now: Long, zone: ZoneId, intervalS: Int, days: Int, label: String?): JSONArray {
        val from = now - days * 86_400_000L
        val list = episodesOf(spec, st, label).filter { it.last >= from }.takeLast(MAX_EPISODES_OUT)
        return JSONArray(list.map { e -> JSONObject().put("label", e.label).put("start", iso(e.start, zone)).put("end", iso(e.last, zone))
            .put("minutes", Math.round(lengthMs(e, intervalS) / 6_000.0) / 10.0).put("readings", e.readings)
            .put("ongoing", st.open[e.label] === e) })
    }

    private fun prob(o: JSONObject, key: String, default: Double): Double {
        if (!o.has(key)) return default
        val v = (o.opt(key) as? Number)?.toDouble()
        if (v == null || v.isNaN() || v < 0.0 || v > 1.0) throw WatchRules.Bad("tally.$key must be a number from 0 to 1.")
        return v
    }

    private fun int(o: JSONObject, key: String, default: Int, lo: Int, hi: Int, at: String): Int {
        if (!o.has(key)) return default
        val v = (o.opt(key) as? Number)?.toDouble()
        if (v == null || v != Math.floor(v) || v < lo || v > hi) throw WatchRules.Bad("$at$key must be a whole number from $lo to $hi.")
        return v.toInt()
    }
}
