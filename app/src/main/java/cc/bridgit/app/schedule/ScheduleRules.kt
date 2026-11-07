package cc.bridgit.app.schedule

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Action manifests (CAP-57): an agent hands the phone a list of actions, each with its own schedule,
 * once. The phone then runs them by itself, so the agent doesn't have to wake up (and spend tokens)
 * for every run. Pure rules only: parsing, validation, next-run maths and plain-language summaries.
 */
object ScheduleRules {
    const val MAX_MANIFEST_BYTES = 64 * 1024
    const val MAX_ACTIONS = 100
    const val MAX_TIMES = 200
    const val MAX_ASSET_BYTES = 25L * 1024 * 1024
    const val MAX_AGENT_ASSET_BYTES = 100L * 1024 * 1024
    const val MAX_AGENT_ASSETS = 100
    const val MIN_POLL_MINUTES = 15
    const val DEFAULT_GRACE_MINUTES = 10
    private val ID = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,39}")
    private val HEADER = Regex("[A-Za-z0-9-]{1,64}")
    private val BLOCKED_HEADERS = setOf("host", "content-length", "transfer-encoding", "connection", "content-type")
    private val CALL = Regex("(GET|POST|PUT|PATCH|DELETE) (/v1/[A-Za-z0-9/_{}.-]+)")
    val LOCAL_VERBS = listOf("download", "play", "show", "send", "delete")

    class Invalid(message: String) : Exception(message)

    sealed class When {
        abstract val grace: Long
        data class At(val at: Long, override val grace: Long) : When()
        data class Times(val times: List<Long>, override val grace: Long) : When()
        data class Every(val start: Long, val everyMs: Long, val count: Int?, val until: Long?, override val grace: Long) : When()
        data class Daily(val times: List<LocalTime>, val days: Set<DayOfWeek>, val start: Long, val until: Long?, val count: Int?, val zone: ZoneId, override val grace: Long) : When()
        data class After(val action: String, override val grace: Long) : When()
    }

    sealed class Do {
        data class Call(val method: String, val path: String, val query: Map<String, String>, val body: JSONObject) : Do()
        data class Download(val url: String, val sha256: String?, val mime: String?, val maxBytes: Long) : Do()
        data class Play(val asset: String, val caption: String?) : Do()
        data class Show(val asset: String, val title: String, val text: String?) : Do()
        data class Send(val asset: String) : Do()
        data class Delete(val asset: String) : Do()
        /** Start a called or button flow (docs/applet-vocabulary.md): a workflow id, or an installed applet's flow name. */
        data class Flow(val ref: String, val with: JSONObject?) : Do()
        /** One step from the flow menu, run on its own (notice, speak): made as the endpoint call that does it. */
        data class Step(val step: JSONObject) : Do()
    }

    data class SendTo(val url: String, val headers: Map<String, String>)
    data class Action(val id: String, val name: String?, val whenSpec: When, val action: Do, val saveAs: String?, val sendTo: SendTo?, val json: JSONObject) {
        /** Changing an action's own JSON resets its run history; untouched actions keep counting. */
        val digest: String get() = digest(json)
    }
    data class Manifest(val zone: ZoneId, val actions: List<Action>, val digest: String)

    // ---- parsing ---------------------------------------------------------------------------------------

    /**
     * Validates [raw]. [routeExists] says whether "METHOD /v1/path" is an endpoint this agent may schedule;
     * it returns null when fine, or the reason it isn't. [now] fills a missing recurrence start.
     */
    fun parse(raw: JSONObject, phoneZone: ZoneId, now: Long, urlAllowed: (String) -> Boolean, routeExists: (String, String) -> String?,
              flowProblem: (String) -> String? = { null }): Manifest {
        if (raw.toString().toByteArray().size > MAX_MANIFEST_BYTES) throw Invalid("The manifest is limited to 64 KB.")
        only(raw, "timezone", "actions", "version")
        if (raw.has("version") && raw.opt("version") != 1) throw Invalid("version must be 1.")
        val zone = raw.optString("timezone", "").let { z ->
            if (z.isEmpty()) phoneZone else runCatching { ZoneId.of(z) }.getOrElse { throw Invalid("timezone must be an IANA name such as Europe/Warsaw.") }
        }
        val list = raw.optJSONArray("actions") ?: throw Invalid("actions is required: a list of {id, when, do}.")
        if (list.length() !in 1..MAX_ACTIONS) throw Invalid("Use 1 to $MAX_ACTIONS actions.")
        val actions = (0 until list.length()).map { i ->
            val o = list.optJSONObject(i) ?: throw Invalid("actions[$i] must be an object.")
            try { action(o, zone, now, urlAllowed, routeExists, flowProblem) } catch (e: Invalid) { throw Invalid("actions[$i] (${o.optString("id", "?")}): ${e.message}") }
        }
        val ids = actions.map { it.id }
        ids.groupBy { it }.filter { it.value.size > 1 }.keys.firstOrNull()?.let { throw Invalid("Two actions are called \"$it\"; ids must be unique.") }
        actions.forEach { a ->
            val w = a.whenSpec
            if (w is When.After && w.action !in ids) throw Invalid("${a.id} runs after \"${w.action}\", which isn't in the manifest.")
            // A flow may wait for the owner for an hour: nothing waits for it to end.
            if (w is When.After && actions.first { it.id == w.action }.action is Do.Flow) throw Invalid("${a.id} can't run after \"${w.action}\": that action starts a flow, and nothing waits for a flow to end. Put the next step in the flow.")
        }
        // "after" chains must end in a timed action, never loop.
        actions.forEach { a ->
            val seen = mutableSetOf(a.id)
            var w = a.whenSpec
            while (w is When.After) {
                if (!seen.add(w.action)) throw Invalid("${a.id} is part of an \"after\" loop.")
                w = actions.first { it.id == w.let { x -> (x as When.After).action } }.whenSpec
            }
        }
        return Manifest(zone, actions, digest(raw))
    }

    private fun action(o: JSONObject, zone: ZoneId, now: Long, urlAllowed: (String) -> Boolean, routeExists: (String, String) -> String?, flowProblem: (String) -> String?): Action {
        only(o, "id", "name", "when", "do", "save_as", "send_to")
        val id = o.optString("id", "")
        if (!ID.matches(id)) throw Invalid("id must be 1-40 letters, digits, - or _.")
        val name = o.optString("name", "").trim().takeIf { it.isNotEmpty() }?.also { if (it.length > 120) throw Invalid("name is limited to 120 characters.") }
        val w = whenOf(o.optJSONObject("when") ?: throw Invalid("when is required."), zone, now)
        val d = doOf(o.optJSONObject("do") ?: throw Invalid("do is required."), urlAllowed, routeExists, flowProblem)
        val saveAs = o.optString("save_as", "").takeIf { it.isNotEmpty() }?.also { if (!ID.matches(it)) throw Invalid("save_as must be 1-40 letters, digits, - or _.") }
            ?: (if (d is Do.Download) id else null)
        if (saveAs != null && d !is Do.Call && d !is Do.Download) throw Invalid("save_as only applies to call and download.")
        val sendTo = o.optJSONObject("send_to")?.let { s ->
            only(s, "url", "headers")
            val url = s.optString("url", "")
            if (!urlAllowed(url)) throw Invalid("send_to.url must be a public https:// address.")
            val headers = mutableMapOf<String, String>()
            s.optJSONObject("headers")?.let { h ->
                if (h.length() > 10) throw Invalid("send_to.headers: at most 10.")
                h.keys().forEach { k ->
                    if (!HEADER.matches(k) || k.lowercase() in BLOCKED_HEADERS) throw Invalid("send_to.headers: \"$k\" isn't allowed.")
                    val v = h.opt(k) as? String ?: throw Invalid("send_to.headers.$k must be text.")
                    if (v.length > 2048 || v.any { it == '\r' || it == '\n' }) throw Invalid("send_to.headers.$k is too long or has a line break.")
                    headers[k] = v
                }
            }
            SendTo(url, headers)
        }
        if (d is Do.Send && sendTo == null) throw Invalid("send needs send_to.")
        if (d is Do.Delete && sendTo != null) throw Invalid("delete can't send_to.")
        if ((d is Do.Flow || d is Do.Step) && sendTo != null) throw Invalid("send_to doesn't go with ${if (d is Do.Flow) "flow" else "step"}.")
        return Action(id, name, w, d, saveAs, sendTo, o)
    }

    private fun whenOf(w: JSONObject, zone: ZoneId, now: Long): When {
        only(w, "at", "times", "start", "every_minutes", "count", "until", "daily", "days", "after", "grace_minutes")
        val grace = w.optInt("grace_minutes", DEFAULT_GRACE_MINUTES).also { if (it !in 0..1440 || (w.has("grace_minutes") && w.opt("grace_minutes") !is Int)) throw Invalid("grace_minutes must be 0 to 1440.") } * 60_000L
        val shapes = listOf("at", "times", "every_minutes", "daily", "after").filter { w.has(it) }
        if (shapes.size != 1) throw Invalid("when needs exactly one of at, times, every_minutes, daily or after.")
        fun count() = if (!w.has("count")) null else (w.opt("count") as? Int)?.takeIf { it in 1..100_000 } ?: throw Invalid("count must be 1 to 100000.")
        fun until() = if (!w.has("until")) null else time(w.opt("until"), zone, "until")
        fun start() = if (!w.has("start")) now else time(w.opt("start"), zone, "start")
        val extras = setOf("start", "count", "until", "days").filter { w.has(it) }
        return when (shapes.single()) {
            "at" -> { if (extras.isNotEmpty()) throw Invalid("at takes no ${extras.first()}."); When.At(time(w.opt("at"), zone, "at"), grace) }
            "times" -> {
                if (extras.isNotEmpty()) throw Invalid("times takes no ${extras.first()}.")
                val a = w.optJSONArray("times") ?: throw Invalid("times must be a list.")
                if (a.length() !in 1..MAX_TIMES) throw Invalid("times: 1 to $MAX_TIMES entries.")
                When.Times((0 until a.length()).map { time(a.opt(it), zone, "times[$it]") }.distinct().sorted(), grace)
            }
            "every_minutes" -> {
                if (w.has("days")) throw Invalid("every_minutes takes no days.")
                val m = (w.opt("every_minutes") as? Int)?.takeIf { it in 1..10_080 } ?: throw Invalid("every_minutes must be a whole number from 1 to 10080.")
                val e = When.Every(start(), m * 60_000L, count(), until(), grace)
                if (e.until != null && e.until < e.start) throw Invalid("until is before start.")
                e
            }
            "daily" -> {
                val a = w.optJSONArray("daily") ?: throw Invalid("daily must be a list of \"HH:MM\".")
                if (a.length() !in 1..48) throw Invalid("daily: 1 to 48 times.")
                val times = (0 until a.length()).map { i ->
                    val s = a.opt(i) as? String ?: throw Invalid("daily[$i] must be \"HH:MM\".")
                    runCatching { LocalTime.parse(s, DateTimeFormatter.ofPattern("HH:mm")) }.getOrElse { throw Invalid("daily[$i] must be \"HH:MM\" (24-hour).") }
                }.distinct().sorted()
                val days = w.optJSONArray("days")?.let { d ->
                    (0 until d.length()).map { i -> DAYS[(d.opt(i) as? String)?.lowercase()] ?: throw Invalid("days use mon, tue, wed, thu, fri, sat, sun.") }.toSet()
                } ?: DayOfWeek.values().toSet()
                if (days.isEmpty()) throw Invalid("days can't be empty.")
                When.Daily(times, days, start(), until(), count(), zone, grace)
            }
            else -> {
                if (extras.isNotEmpty()) throw Invalid("after takes no ${extras.first()}.")
                When.After(w.optString("after").also { if (!ID.matches(it)) throw Invalid("after must name another action's id.") }, grace)
            }
        }
    }

    private val FLOW_REF = Regex("[A-Za-z0-9_-]{1,64}")

    private fun doOf(d: JSONObject, urlAllowed: (String) -> Boolean, routeExists: (String, String) -> String?, flowProblem: (String) -> String?): Do {
        val verbs = (listOf("call", "flow", "step") + LOCAL_VERBS).filter { d.has(it) } + (if (d.has("method") || d.has("path")) listOf("method") else emptyList())
        if (verbs.size != 1) throw Invalid("do needs exactly one of flow, step, an endpoint call {method, path} (or call \"POST /v1/…\"), ${LOCAL_VERBS.joinToString()}.")
        fun asset(k: String) = (d.opt(k) as? String)?.takeIf { ID.matches(it) } ?: throw Invalid("$k must name a saved asset (1-40 letters, digits, - or _).")
        return when (verbs.single()) {
            "call" -> {
                only(d, "call", "query", "body")
                val m = CALL.matchEntire(d.optString("call")) ?: throw Invalid("call must look like \"POST /v1/camera/snapshot\".")
                val (method, path) = m.destructured
                routeExists(method, path)?.let { throw Invalid(it) }
                val query = mutableMapOf<String, String>()
                d.optJSONObject("query")?.let { q -> q.keys().forEach { k -> query[k] = q.get(k).toString() } }
                Do.Call(method, path, query, d.optJSONObject("body") ?: JSONObject())
            }
            // The same call spelled as watch rules and card buttons spell it.
            "method" -> {
                only(d, "method", "path", "query", "body")
                val method = d.optString("method", "").uppercase()
                val path = d.optString("path", "").let { if (it.startsWith("/v1/")) it else if (it.startsWith("/")) "/v1$it" else "/v1/$it" }
                if (!CALL.matches("$method $path")) throw Invalid("method must be GET, POST, PUT, PATCH or DELETE and path a /v1/ endpoint path, like {\"method\":\"POST\",\"path\":\"/v1/camera/snapshot\"}.")
                routeExists(method, path)?.let { throw Invalid(it) }
                val query = mutableMapOf<String, String>()
                d.optJSONObject("query")?.let { q -> q.keys().forEach { k -> query[k] = q.get(k).toString() } }
                Do.Call(method, path, query, d.optJSONObject("body") ?: JSONObject())
            }
            "flow" -> {
                only(d, "flow", "with")
                val ref = d.optString("flow").takeIf { FLOW_REF.matches(it) } ?: throw Invalid("flow must be the id of one of your workflows (in an applet package: {\"\$flow\": \"<flow id>\"}).")
                flowProblem(ref)?.let { throw Invalid(it) }
                val with = d.opt("with")?.let { w -> (w as? JSONObject)?.takeIf { it.length() <= cc.bridgit.app.platform.FlowSteps.MAX_WITH && it.toString().length <= 4096 }
                    ?: throw Invalid("with must be an object of at most ${cc.bridgit.app.platform.FlowSteps.MAX_WITH} values (4 KB).") }
                Do.Flow(ref, with)
            }
            "step" -> {
                only(d, "step")
                val step = d.opt("step") as? JSONObject
                try { cc.bridgit.app.platform.FlowSteps.checkInline(step) } catch (e: cc.bridgit.app.api.ApiError) { throw Invalid(e.message ?: "step isn't valid.") }
                val call = cc.bridgit.app.platform.FlowSteps.inlineCall(step!!, JSONObject())
                routeExists(call.getString("method"), call.getString("path"))?.let { throw Invalid(it) }
                Do.Step(step)
            }
            "download" -> {
                only(d, "download", "sha256", "mime", "max_mb")
                val url = d.optString("download")
                if (!urlAllowed(url)) throw Invalid("download must be a public https:// address.")
                val sha = d.optString("sha256", "").lowercase().takeIf { it.isNotEmpty() }?.also { if (!it.matches(Regex("[0-9a-f]{64}"))) throw Invalid("sha256 must be 64 hex digits.") }
                val mime = d.optString("mime", "").takeIf { it.isNotEmpty() }?.also { if (!it.matches(Regex("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+"))) throw Invalid("mime must be a MIME type.") }
                val mb = if (d.has("max_mb")) (d.opt("max_mb") as? Int)?.takeIf { it in 1..25 } ?: throw Invalid("max_mb must be 1 to 25.") else 25
                Do.Download(url, sha, mime, mb * 1024L * 1024)
            }
            "play" -> { only(d, "play", "caption"); Do.Play(asset("play"), d.optString("caption", "").take(500).ifEmpty { null }) }
            "show" -> {
                only(d, "show", "title", "text")
                val title = d.optString("title", "").trim().ifEmpty { throw Invalid("show needs a title.") }
                if (title.length > 120) throw Invalid("title is limited to 120 characters.")
                Do.Show(asset("show"), title, d.optString("text", "").take(4000).ifEmpty { null })
            }
            "send" -> { only(d, "send"); Do.Send(asset("send")) }
            else -> { only(d, "delete"); Do.Delete(asset("delete")) }
        }
    }

    private val DAYS = mapOf("mon" to DayOfWeek.MONDAY, "tue" to DayOfWeek.TUESDAY, "wed" to DayOfWeek.WEDNESDAY,
        "thu" to DayOfWeek.THURSDAY, "fri" to DayOfWeek.FRIDAY, "sat" to DayOfWeek.SATURDAY, "sun" to DayOfWeek.SUNDAY)

    /** ISO-8601 with an offset, or a local "2026-11-02T16:34" read in the manifest's timezone. */
    fun time(v: Any?, zone: ZoneId, field: String): Long {
        val s = v as? String ?: throw Invalid("$field must be an ISO-8601 time such as 2026-11-02T16:34:00+01:00.")
        runCatching { return OffsetDateTime.parse(s).toInstant().toEpochMilli() }
        runCatching { return Instant.parse(s).toEpochMilli() }
        runCatching { return LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli() }
        throw Invalid("$field must be an ISO-8601 time such as 2026-11-02T16:34:00+01:00.")
    }

    private fun only(o: JSONObject, vararg allowed: String) {
        o.keys().asSequence().firstOrNull { it !in allowed }?.let { throw Invalid("Unknown field \"$it\". Allowed here: ${allowed.joinToString()}.") }
    }

    // ---- timing ------------------------------------------------------------------------------------------

    /**
     * The first slot after [last] (or the first slot of all when null), honouring count via [consumed]
     * (slots already run or missed). Null when the schedule is finished. "after" actions have no slots.
     */
    fun nextSlot(w: When, last: Long?, consumed: Int): Long? = when (w) {
        is When.At -> if (last == null && consumed == 0) w.at else null
        is When.Times -> w.times.firstOrNull { last == null || it > last }
        is When.Every -> {
            val k = if (last == null) 0L else Math.floorDiv(last - w.start, w.everyMs) + 1
            val slot = w.start + maxOf(0L, k) * w.everyMs
            val index = (slot - w.start) / w.everyMs
            when {
                w.count != null && index >= w.count -> null
                w.until != null && slot > w.until -> null
                else -> slot
            }
        }
        is When.Daily -> {
            if (w.count != null && consumed >= w.count) null else {
                val from = maxOf(w.start - 1, last ?: Long.MIN_VALUE)
                var day = Instant.ofEpochMilli(from).atZone(w.zone).toLocalDate()
                var found: Long? = null
                for (i in 0..8) {
                    if (day.dayOfWeek in w.days) {
                        found = w.times.asSequence().map { day.atTime(it).atZone(w.zone).toInstant().toEpochMilli() }.firstOrNull { it > from }
                        if (found != null) break
                    }
                    day = day.plusDays(1)
                }
                found?.takeIf { w.until == null || it <= w.until }
            }
        }
        is When.After -> null
    }

    /** What to do at [now]: the latest due slot to run (null if none or it is past its grace), slots skipped, and the next slot. */
    data class Due(val run: Long?, val skipped: Int, val lastConsumed: Long?, val consumedAfter: Int, val next: Long?)

    fun due(w: When, last: Long?, consumed: Int, now: Long): Due {
        var slot = nextSlot(w, last, consumed)
        if (slot == null || slot > now) return Due(null, 0, last, consumed, slot)
        var latest = slot
        var count = consumed + 1
        var dueSlots = 1
        // Every: jump straight to the latest due slot instead of walking a long gap one by one.
        if (w is When.Every) {
            var k = Math.floorDiv(now - w.start, w.everyMs)
            w.count?.let { k = minOf(k, it - 1L) }
            w.until?.let { u -> k = minOf(k, Math.floorDiv(u - w.start, w.everyMs)) }
            val firstK = (slot - w.start) / w.everyMs
            latest = w.start + k * w.everyMs
            dueSlots = (k - firstK + 1).toInt()
            count = consumed + dueSlots
        } else {
            var guard = 0
            while (guard++ < 20_000) {
                val n = nextSlot(w, latest, count) ?: break
                if (n > now) break
                latest = n; count++; dueSlots++
            }
        }
        val runIt = now - latest <= w.grace
        val next = nextSlot(w, latest, count)
        return Due(if (runIt) latest else null, if (runIt) dueSlots - 1 else dueSlots, latest, count, next)
    }

    // ---- results -----------------------------------------------------------------------------------------

    /** The bytes worth keeping or sending from an endpoint reply: decoded data_base64 media, else the reply itself. */
    data class Payload(val bytes: ByteArray, val mime: String)

    fun payload(contentType: String, body: ByteArray, decode: (String) -> ByteArray): Payload {
        val ct = contentType.substringBefore(';').trim().ifEmpty { "application/octet-stream" }
        if (ct == "application/json") runCatching {
            val o = JSONObject(String(body, Charsets.UTF_8))
            val b64 = o.optString("data_base64", "")
            if (b64.isNotEmpty()) {
                val mime = o.optString("mime", "").ifEmpty {
                    when (o.optString("format").lowercase()) { "jpeg", "jpg" -> "image/jpeg"; "png" -> "image/png"; "webp" -> "image/webp"; "wav" -> "audio/wav"
                        "m4a", "aac" -> "audio/mp4"; "mp3" -> "audio/mpeg"; "ogg" -> "audio/ogg"; "mp4" -> "video/mp4"; else -> "application/octet-stream" }
                }
                return Payload(decode(b64), mime)
            }
        }
        return Payload(body, ct)
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun digest(o: JSONObject): String = sha256(canonical(o).toByteArray())
    fun canonical(v: Any?): String = when (v) {
        is JSONObject -> v.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(v.get(it)) }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canonical(v.get(it)) }
        null, JSONObject.NULL -> "null"
        is String -> JSONObject.quote(v)
        else -> v.toString()
    }

    // ---- words for the owner -----------------------------------------------------------------------------

    fun describe(a: Action, zone: ZoneId): String {
        val what = when (val d = a.action) {
            is Do.Call -> "call ${d.method} ${d.path}" + if (d.query.isEmpty()) "" else " (" + d.query.entries.joinToString { "${it.key}=${it.value}" } + ")"
            is Do.Download -> "download ${host(d.url)}"
            is Do.Play -> "play ${d.asset}"
            is Do.Show -> "show ${d.asset} on screen"
            is Do.Send -> "send ${d.asset}"
            is Do.Delete -> "delete ${d.asset}"
            is Do.Flow -> "start flow ${d.ref}"
            is Do.Step -> cc.bridgit.app.platform.FlowSteps.inlineWords(d.step).replaceFirst("shows you", "show you").replaceFirst("says", "say")
        }
        val keep = a.saveAs?.takeIf { a.action is Do.Call }?.let { ", keep the result as $it" } ?: ""
        val send = a.sendTo?.let { ", then send it to ${host(it.url)}" } ?: ""
        return "${whenText(a.whenSpec, zone)}: $what$keep$send"
    }

    fun whenText(w: When, zone: ZoneId): String {
        fun t(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).format(DateTimeFormatter.ofPattern("EEE d MMM HH:mm"))
        return when (w) {
            is When.At -> "At ${t(w.at)}"
            is When.Times -> if (w.times.size == 1) "At ${t(w.times[0])}" else "At ${w.times.size} set times from ${t(w.times.first())} to ${t(w.times.last())}"
            is When.Every -> buildString {
                append("Every ${minutes(w.everyMs / 60_000)} from ${t(w.start)}")
                w.count?.let { append(", $it time" + if (it == 1) "" else "s") }
                w.until?.let { append(", until ${t(it)}") }
            }
            is When.Daily -> buildString {
                append(if (w.days.size == 7) "Every day" else "Every " + w.days.sorted().joinToString { it.name.take(3).lowercase().replaceFirstChar(Char::uppercase) })
                append(" at " + w.times.joinToString { it.toString() })
                w.count?.let { append(", $it time" + if (it == 1) "" else "s") }
                w.until?.let { append(", until ${t(it)}") }
            }
            is When.After -> "After ${w.action} succeeds"
        }
    }

    private fun minutes(m: Long) = when {
        m == 1L -> "minute"
        m % 1440 == 0L -> if (m == 1440L) "day" else "${m / 1440} days"
        m % 60 == 0L -> if (m == 60L) "hour" else "${m / 60} hours"
        else -> "$m minutes"
    }
    fun host(url: String) = runCatching { java.net.URI(url).host }.getOrNull() ?: "a web address"
    fun iso(ms: Long, zone: ZoneId): String = ZonedDateTime.ofInstant(Instant.ofEpochMilli(ms), zone).toOffsetDateTime().toString()
}
