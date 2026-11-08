package cc.bridgit.app.ui.common

import cc.bridgit.app.log.LogEntry
import org.json.JSONObject

/**
 * Activity rows in the owner's terms (bug reports #0003, #0004). Pure Kotlin so it is unit-tested.
 *
 * - Endpoints show the real id ("/v1/interactions/10"), not the route template.
 * - Long-polls (GET with `wait`) are the agent waiting, not a slow call: repeated polls for the same
 *   thing collapse into one row ("Waiting for your answer to question 10 · 4 checks") and the wait
 *   window is shown as a wait, never as a request duration.
 *
 * Only the agent page collapses rows; the Activity log keeps one row per call with full detail.
 */
object ActivityRows {
    private val TEMPLATE = Regex("\\{([A-Za-z_]+)\\}")

    /** What the agent page needs to know about an interaction, looked up by the caller. */
    data class InteractionInfo(val title: String?, val kind: String?, val status: String?) {
        val open get() = status == "pending" || status == "seen"
    }

    enum class Kind { WAIT, EVENTS, INTERACTION, CAMERA, SCREEN, MIC, IMAGE, GALLERY, HEALTH, GUIDE, LOG, PAIR, NOTE, SPEAKER, LIGHT, FEEDBACK, OTHER }

    enum class Tone { NEUTRAL, PRIMARY, WAITING, SUCCESS, ERROR }

    enum class ChipStyle { GET, POST, DELETE, OTHER_METHOD, OK, ERROR, WAITING, PLAIN }

    data class Chip(val text: String, val style: ChipStyle)

    /** One rendered row: [title] in owner words, [subtitle] for context, [chips] for technical detail. */
    data class Row(
        val title: String, val subtitle: String?, val chips: List<Chip>, val kind: Kind, val tone: Tone,
        val ts: Long, val isError: Boolean, val count: Int = 1,
    )

    sealed class Item {
        abstract val newest: LogEntry
        data class Single(val entry: LogEntry) : Item() { override val newest get() = entry }
        /** Repeated long-polls of one endpoint, newest first. */
        data class Polls(val entries: List<LogEntry>) : Item() {
            override val newest get() = entries.first()
            val oldest get() = entries.last()
        }
    }

    // ---- Parsing ----

    /** The endpoint with path parameters filled in from the logged params; the template stays when a value is missing. */
    fun endpoint(e: LogEntry): String? {
        val ep = e.endpoint ?: return null
        val p = e.params ?: return ep
        return TEMPLATE.replace(ep) { m ->
            p.opt(m.groupValues[1])?.takeIf { it != JSONObject.NULL }?.toString()?.takeIf { it.isNotBlank() } ?: m.value
        }
    }

    /** The long-poll window the agent asked for, in seconds, or null if this call didn't wait. */
    fun waitSeconds(e: LogEntry): Int? = e.params?.opt("wait")?.toString()?.toIntOrNull()?.takeIf { it > 0 }

    /** A successful GET that held open waiting for something (`?wait=`). Failed polls stay separate rows. */
    fun isPoll(e: LogEntry): Boolean =
        e.kind == "request" && e.method == "GET" && waitSeconds(e) != null && !e.isError

    /** The interaction a call is about: the {id} path parameter, or the id the create call logged. */
    fun interactionId(e: LogEntry): Long? {
        val ep = e.endpoint ?: return null
        if (!ep.startsWith("/v1/interactions") && !ep.startsWith("/v2/interactions")) return null
        return e.params?.opt("id")?.toString()?.toLongOrNull()
            ?: e.data?.opt("interaction_id")?.toString()?.toLongOrNull()
    }

    fun kind(e: LogEntry): Kind {
        val ep = e.endpoint ?: ""
        return when {
            e.kind == "note" -> Kind.NOTE
            ep.contains("/interactions/") && isPoll(e) -> Kind.WAIT
            ep.endsWith("/events") -> Kind.EVENTS
            ep.contains("/interactions") || ep.contains("/text/stream") -> Kind.INTERACTION
            ep.contains("/camera") -> Kind.CAMERA
            ep.contains("/screen") -> Kind.SCREEN
            ep.contains("/mic") || ep.contains("/listen") || ep.contains("/sounds") -> Kind.MIC
            ep.contains("/image") -> Kind.IMAGE
            ep.contains("/gallery") -> Kind.GALLERY
            ep.endsWith("/health") || ep.endsWith("/me") -> Kind.HEALTH
            ep.endsWith("/guide") || ep.endsWith("/capabilities") || ep.contains("/changes") -> Kind.GUIDE
            ep.startsWith("/v1/log") || ep.endsWith("/audit") -> Kind.LOG
            ep.endsWith("/pair") || e.kind == "pairing" -> Kind.PAIR
            ep.contains("/speaker") || ep.contains("/ring") -> Kind.SPEAKER
            ep.contains("/flashlight") || ep.contains("/torch") -> Kind.LIGHT
            ep.contains("/feedback") || ep.startsWith("/v1/issues") -> Kind.FEEDBACK
            isPoll(e) -> Kind.WAIT
            else -> Kind.OTHER
        }
    }

    // ---- Collapsing ----

    /**
     * Groups repeated long-polls (same method and resolved endpoint) into one item placed where the newest
     * poll is. Polls of different things may interleave (an interaction wait and an events wait) and still
     * group; any other call ends the open groups, so the order of real actions is kept. [entries] are newest first.
     */
    fun collapse(entries: List<LogEntry>): List<Item> {
        val out = mutableListOf<Any>() // LogEntry or MutableList<LogEntry>
        val open = HashMap<String, MutableList<LogEntry>>()
        for (e in entries) {
            if (isPoll(e)) {
                val key = "${e.agentId}|${e.method}|${endpoint(e)}"
                val g = open[key]
                if (g != null) g.add(e) else mutableListOf(e).also { open[key] = it; out.add(it) }
            } else {
                open.clear()
                out.add(e)
            }
        }
        @Suppress("UNCHECKED_CAST")
        return out.map { if (it is LogEntry) Item.Single(it) else Item.Polls(it as List<LogEntry>) }
    }

    // ---- Rendering ----

    private fun noun(info: InteractionInfo?) = when (info?.kind) {
        "html" -> "card"; "notice" -> "notice"; "text" -> "text panel"; else -> "question"
    }

    private fun methodStyle(m: String?) = when (m) {
        "GET" -> ChipStyle.GET; "POST" -> ChipStyle.POST; "DELETE" -> ChipStyle.DELETE; else -> ChipStyle.OTHER_METHOD
    }

    private fun quoted(title: String?) = title?.trim()?.takeIf { it.isNotEmpty() }?.let { "“${it.take(60)}”" }

    /**
     * The row for [item]. [agentName] is the agent's current name (dropped from sentences: the agent page is
     * about one agent). [lookup] finds an interaction by id; it may return null (deleted or unknown).
     */
    fun row(item: Item, agentName: String?, lookup: (Long) -> InteractionInfo? = { null }): Row = when (item) {
        is Item.Single -> single(item.entry, agentName, lookup)
        is Item.Polls -> polls(item, lookup)
    }

    private fun single(e: LogEntry, agentName: String?, lookup: (Long) -> InteractionInfo?): Row {
        if (isPoll(e)) return polls(Item.Polls(listOf(e)), lookup)
        val name = agentName ?: e.agent
        var sentence = Sentences.of(e, name)
        if (name != null && sentence.startsWith("$name ")) sentence = sentence.removePrefix("$name ").replaceFirstChar { it.uppercase() }
        val chips = mutableListOf<Chip>()
        endpoint(e)?.let { chips.add(Chip("${e.method ?: ""} $it".trim(), methodStyle(e.method))) }
        e.http?.let { chips.add(if (it >= 400) Chip("$it ${e.result ?: ""}".trim(), ChipStyle.ERROR) else Chip("$it", ChipStyle.OK)) }
        e.bytesOut?.takeIf { it > 0 }?.let { chips.add(Chip(Fmt.bytes(it), ChipStyle.PLAIN)) }
        e.durationMs?.let { chips.add(Chip(Fmt.duration(it), ChipStyle.PLAIN)) }
        val k = kind(e)
        val tone = when {
            e.isError -> Tone.ERROR
            k == Kind.INTERACTION && e.method == "POST" -> Tone.PRIMARY
            k == Kind.CAMERA || k == Kind.SCREEN || k == Kind.MIC || k == Kind.SPEAKER || k == Kind.LIGHT -> Tone.SUCCESS
            else -> Tone.NEUTRAL
        }
        return Row(sentence, Sentences.detail(e), chips, k, tone, e.ts, e.isError)
    }

    private fun polls(g: Item.Polls, lookup: (Long) -> InteractionInfo?): Row {
        val e = g.newest
        val n = g.entries.size
        val wait = waitSeconds(e) ?: 0
        val iid = interactionId(e)
        val info = iid?.let(lookup)
        val checks = if (n == 1) "1 check" else "$n checks"
        val since = if (n > 1) " since ${Fmt.time(g.oldest.ts)}" else " at ${Fmt.time(e.ts)}"
        val chips = mutableListOf<Chip>()
        endpoint(e)?.let { chips.add(Chip("GET $it", ChipStyle.GET)) }
        val title: String
        val subtitle: String
        val tone: Tone
        val kind: Kind
        if (iid != null) {
            val what = "${noun(info)} $iid"
            // A notice's wait ends when the owner sees it; a question's when they answer or close it.
            val notice = info?.kind == "notice"
            // Without the interaction, a poll that ended well before its window means it got what it waited for.
            val stillWaiting = if (notice) info?.status == "pending" else info?.open ?: ((e.durationMs ?: 0) >= wait * 1000L - 1500)
            if (stillWaiting) {
                title = if (notice) "Waiting for you to see $what" else "Waiting for your answer to $what"
                chips.add(Chip("waiting", ChipStyle.WAITING))
                tone = Tone.WAITING
            } else {
                title = if (notice) "Waited for you to see $what" else "Waited for your answer to $what"
                val done = info?.status == "answered" || (notice && info?.status == "seen")
                info?.status?.let { chips.add(Chip(it, if (done) ChipStyle.OK else ChipStyle.PLAIN)) }
                tone = if (done) Tone.SUCCESS else Tone.NEUTRAL
            }
            subtitle = listOfNotNull(quoted(info?.title), "$checks$since").joinToString(" · ")
            kind = Kind.WAIT
        } else if ((e.endpoint ?: "").endsWith("/events")) {
            title = "Listened for your answers and other events"
            subtitle = "$checks$since"
            tone = Tone.NEUTRAL
            kind = Kind.EVENTS
        } else {
            title = "Waited on ${endpoint(e)}"
            subtitle = "$checks$since"
            tone = Tone.NEUTRAL
            kind = Kind.WAIT
        }
        chips.add(Chip("waits up to $wait s", ChipStyle.PLAIN))
        return Row(title, subtitle, chips, kind, tone, e.ts, false, n)
    }

    /** One short line for the Home agent row: what the agent is doing now, else the last endpoint without /v1/. */
    fun homeLine(e: LogEntry, lookup: (Long) -> InteractionInfo? = { null }): String? {
        if (isPoll(e)) {
            val iid = interactionId(e)
            if (iid != null) {
                val info = lookup(iid)
                if (info?.kind == "notice" && info.status == "pending") return "waiting for you to see notice $iid"
                return if (info == null || (info.open && info.kind != "notice")) "waiting for your answer · ${noun(info)} $iid"
                    else "${noun(info)} $iid ${info.status ?: "closed"}"
            }
            if ((e.endpoint ?: "").endsWith("/events")) return "listening for events"
        }
        return endpoint(e)?.removePrefix("/v1/")
    }
}
