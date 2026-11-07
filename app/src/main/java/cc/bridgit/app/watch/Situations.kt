package cc.bridgit.app.watch

import org.json.JSONArray
import org.json.JSONObject

/**
 * Common picture situations ("dog licking itself", "sunlight in the room", "pot boiling over"): a watch body with
 * `situation` instead of a question. Each one is a tuned yes/no photo question plus counting defaults, expanded
 * into ordinary watch fields (camera data, questions, tally, an owner notice for urgent ones) before the watch
 * rules check it, so the stored request is re-expanded the same way after a restart or an update. The model stays
 * the watch's own (Clef by default for photos, or Jev). Anything not in the library can be described in words.
 * Pure: no Android, unit-tested.
 */
object Situations {
    class Situation(
        val id: String,
        val title: String,
        /** What the photo must show, as a yes/no question. */
        val question: String,
        /** How likely yes must be to count, 0-1. */
        val min: Double,
        val intervalS: Int,
        /** Sightings this far apart are one episode. */
        val gapS: Int,
        /** Tell the owner with a notice when an episode starts. */
        val alert: Boolean,
        /** Count only while this is visible ("Is a dog visible?"), so watched time means time it was in view. */
        val subject: String? = null,
        val examples: String = "",
    ) {
        fun json(): JSONObject = JSONObject().put("id", id).put("title", title).put("question", question)
            .put("min", min).put("interval_s", intervalS).put("gap_s", gapS).put("alert", alert)
            .put("counts_only_while", subject ?: JSONObject.NULL)
    }

    const val CUSTOM_ID = "situation"
    const val SUBJECT_ID = "subject_visible"
    const val DEFAULT_INTERVAL_S = 20
    const val DEFAULT_MIN = 0.7
    const val MAX_DESCRIBE_CHARS = 300
    private val ID_LIKE = Regex("^[a-z0-9_]{1,40}$")

    val LIBRARY: List<Situation> = listOf(
        Situation("dog_licking_itself", "Dog licking itself",
            "Is the dog licking its own genitals or groin (not just grooming a paw or its fur)?", 0.7, 20, 60, false,
            subject = "Is a dog visible?"),
        Situation("dog_scratching", "Dog scratching",
            "Is the dog scratching itself with a paw, or rubbing itself against something to scratch?", 0.7, 20, 60, false,
            subject = "Is a dog visible?"),
        Situation("dog_on_couch", "Dog on the couch",
            "Is a dog on the couch, sofa or bed (on the furniture, not on the floor next to it)?", 0.75, 60, 600, false),
        Situation("cat_on_counter", "Cat on the counter",
            "Is a cat on a kitchen counter, table or stove top?", 0.75, 30, 300, false),
        Situation("pot_boiling_over", "Pot boiling over",
            "Is a pot or pan on the stove boiling over, with liquid or foam spilling over its rim?", 0.7, 20, 180, true),
        Situation("smoke_in_room", "Smoke in the room",
            "Is there visible smoke or haze in the room, or smoke rising from the stove or an appliance?", 0.7, 20, 300, true),
        Situation("water_on_floor", "Water on the floor",
            "Is there a puddle, spill or standing water on the floor?", 0.75, 60, 900, true),
        Situation("sunlight_in_room", "Sunlight in the room",
            "Is direct sunlight shining into the room, making bright sun patches on the floor, walls or furniture?", 0.7, 300, 1800, false),
        Situation("lights_on", "Lights on",
            "Are the room's lights switched on (lamps or ceiling lights lit)?", 0.75, 300, 1800, false),
        Situation("person_in_view", "Person in view",
            "Is a person visible in the picture?", 0.75, 30, 300, false),
    )

    fun byId(id: String): Situation? = LIBRARY.firstOrNull { it.id == id }

    /** GET /v1/situations and the watch options: the library and how to use it. */
    fun catalog(): JSONObject = JSONObject()
        .put("about", "Send situation instead of question in POST /v1/watches: a library id below, or your own words " +
            "(\"the cat is on the kitchen counter\"). The phone turns it into a camera watch that counts each episode on the phone " +
            "(GET /v1/watches/{id}/tally) and, for alert situations, sends the owner a notice when one starts. Pick the model as for " +
            "any watch (clef by default with a photo, or jev); the free change check skips unchanged photos.")
        .put("situations", JSONArray(LIBRARY.map { it.json() }))
        .put("custom", JSONObject().put("example", "{\"situation\": {\"describe\": \"the cat is on the kitchen counter\", \"alert\": true}}")
            .put("defaults", JSONObject().put("min", DEFAULT_MIN).put("interval_s", DEFAULT_INTERVAL_S).put("alert", false)))

    /** Fields a situation fills in, so the agent can't also send them. */
    private val FILLED = listOf("question", "questions", "min_probability", "tally")

    /**
     * Turns a body with `situation` into an ordinary watch body (a copy); a body without one comes back as is.
     * `situation`: a library id, own words, or {id | describe, alert?, min?, count_only_while?}. [Expanded.id] is the
     * library id, or "situation" for own words; null without a situation.
     */
    class Expanded(val body: JSONObject, val id: String?, val track: String?)

    fun expand(b: JSONObject): Expanded {
        val raw = b.opt("situation") ?: return Expanded(b, null, null)
        FILLED.firstOrNull { b.has(it) }?.let {
            throw WatchRules.Bad("situation already sets the question and the tally, so leave $it out (or send question without situation for your own).")
        }
        val o: JSONObject = when (raw) {
            is String -> raw.trim().let { s ->
                if (s.isEmpty()) throw WatchRules.Bad("situation must be a library id or words, e.g. \"pot_boiling_over\".")
                if (byId(s) != null || ID_LIKE.matches(s)) JSONObject().put("id", s) else JSONObject().put("describe", s)
            }
            is JSONObject -> raw
            else -> throw WatchRules.Bad("situation must be a library id, your own words, or {id | describe, alert?, min?}.")
        }
        o.keys().asSequence().firstOrNull { it !in setOf("id", "describe", "alert", "min", "count_only_while") }
            ?.let { throw WatchRules.Bad("situation.$it isn't a situation option (id, describe, alert, min, count_only_while).") }
        if (o.has("id") == o.has("describe")) throw WatchRules.Bad("situation needs id (one of ${LIBRARY.joinToString { it.id }}) or describe (your own words), not both.")

        val s: Situation = if (o.has("id")) {
            val id = (o.opt("id") as? String)?.trim().orEmpty()
            byId(id) ?: throw WatchRules.Bad("$id isn't a situation in the library: ${LIBRARY.joinToString { it.id }}. Or describe it in words: {\"describe\": \"...\"}.")
        } else {
            val words = (o.opt("describe") as? String)?.trim()?.trimEnd('.', '?', '!')?.takeIf { it.isNotEmpty() }
                ?: throw WatchRules.Bad("situation.describe must be words, e.g. \"the cat is on the kitchen counter\".")
            if (words.length > MAX_DESCRIBE_CHARS) throw WatchRules.Bad("situation.describe is over $MAX_DESCRIBE_CHARS characters.")
            Situation(CUSTOM_ID, words.replaceFirstChar { it.uppercase() }.take(WatchRules.MAX_LABEL_CHARS),
                "Does the photo show this: $words?", DEFAULT_MIN, DEFAULT_INTERVAL_S, 120, false)
        }
        val alert = when (val a = o.opt("alert")) { null -> s.alert; is Boolean -> a; else -> throw WatchRules.Bad("situation.alert must be true or false.") }
        val min = when (val m = o.opt("min")) {
            null -> s.min
            is Number -> m.toDouble().takeIf { it in 0.0..1.0 } ?: throw WatchRules.Bad("situation.min must be 0-1.")
            else -> throw WatchRules.Bad("situation.min must be a number 0-1.")
        }
        val subject = when (val c = o.opt("count_only_while")) {
            null -> s.subject
            is String -> c.trim().ifEmpty { null }?.also { if (it.length > MAX_DESCRIBE_CHARS) throw WatchRules.Bad("situation.count_only_while is over $MAX_DESCRIBE_CHARS characters.") }
            else -> throw WatchRules.Bad("situation.count_only_while must be a question in words, e.g. \"Is a dog visible?\".")
        }

        val out = JSONObject(b.toString()).apply { remove("situation") }
        when (val d = out.opt("data")) {
            null -> out.put("data", JSONArray().put("camera"))
            is JSONArray -> if (d.length() != 1 || d.opt(0) != "camera") throw WatchRules.Bad("A situation is seen on a camera: leave data out (or [\"camera\"]).")
            else -> throw WatchRules.Bad("A situation is seen on a camera: leave data out (or [\"camera\"]).")
        }
        if (!out.has("interval_s")) out.put("interval_s", s.intervalS)
        if (!out.has("label")) out.put("label", s.title)
        val track = if (s.id.length <= 40) s.id else CUSTOM_ID
        val questions = JSONObject().put(track, JSONObject().put("type", "noul")
            .put("instructions", "${s.question} Judge from the newest photo: how likely is it that this is happening in it now?"))
        if (subject != null) questions.put(SUBJECT_ID, JSONObject().put("type", "noul")
            .put("instructions", "$subject Judge from the newest photo: how likely is it?"))
        out.put("questions", questions)
        out.put("tally", JSONObject().put("track", track).put("min", min).put("gap_s", maxOf(s.gapS, 3 * out.optInt("interval_s", s.intervalS)).coerceAtMost(WatchTally.MAX_GAP_S))
            .apply { if (subject != null) put("seen", JSONObject().put("answer", SUBJECT_ID).put("min", 0.6)) })
        if (alert && !out.has("rules")) out.put("rules", JSONArray().put(JSONObject().put("id", "alert")
            .put("when", JSONObject().put("at_least", 1).put("of", track).put("within_s", 120))
            .put("cooldown_s", maxOf(s.gapS, 300))
            .put("do", JSONObject().put("method", "POST").put("path", "/v1/interactions")
                .put("body", JSONObject().put("kind", "notice").put("title", s.title).put("text", "Seen on the camera just now.").put("priority", "urgent")))))
        return Expanded(out, s.id, track)
    }
}
