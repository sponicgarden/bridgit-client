package cc.bridgit.app.watch

import cc.bridgit.app.judgment.Jev
import cc.bridgit.app.vision.ChangeSettings
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Watches (POST /v1/watches): the phone takes readings on an interval, keeps the last few, and asks a
 * decision model (Jev) the agent's question about them. When the answer meets the agent's condition,
 * the agent gets a callback. Pure: no Android, unit-tested.
 */
object WatchRules {
    /** Data an agent can ask for, and the capability grant (and switch) each needs; null = none. */
    val ELEMENTS: Map<String, String?> = linkedMapOf(
        "location" to "location",
        "motion" to "sensors",
        "steps" to "sensors",
        "light" to "sensors",
        "pressure" to "sensors",
        "proximity" to "sensors",
        "battery" to null,
        "device" to null,
        "camera" to "camera",
        // Loud sounds (cc.bridgit.app.sound): the watch asks Clef about each loud clip, no interval. Needs the Sounds grant and switch.
        "sound" to "sounds",
    )
    val DEFAULT_DATA = listOf("location", "motion")
    /** Paid models (through the Bridgit team backend), and "none": local only, the free photo change check calls back. */
    val MODELS = listOf("jev", "clef", "glm", "none")
    val PAID_MODELS = listOf("jev", "clef", "glm")
    /** Without a photo the default stays Jev (as before); with one, Clef (cheaper per photo here and better at images). */
    fun defaultModel(data: List<String>): String = if ("camera" in data || "sound" in data) "clef" else "jev"
    val CAMERAS = listOf("rear", "front")
    /** Rough cost of one paid check, for planning (each check's real cost_usd is in the watch's status). */
    val COST_PER_CHECK_USD = mapOf("jev_text" to 0.000008, "jev_photo" to 0.000065, "clef_text" to 0.00003, "clef_photo" to 0.000105)
    /** Same picture, same answer: two choice answers with the same pick, two probabilities this close. */
    const val SAME_NOUL = 0.15

    /** The team backend route for [model]. */
    fun route(model: String): String = when (model) { "clef" -> Jev.CLEF_ROUTE; "glm" -> Jev.GLM_ROUTE; else -> Jev.JEV_ROUTE }

    /** Shared wording for exact endpoint records and progressive decision documentation. */
    val INTERVAL_DESCRIPTION get() = "Seconds between readings: $MIN_INTERVAL_S-$MAX_INTERVAL_S (default $DEFAULT_INTERVAL_S); down to $MIN_INTERVAL_TALLY_S only for a camera tally with local.change_check enabled. Set max_paid_per_hour to bound optional model calls."
    const val MIN_INTERVAL_S = 30
    /** A tally watch on the camera with the free change check on can read this often (the pet monitor's pace). */
    const val MIN_INTERVAL_TALLY_S = 10
    const val MAX_INTERVAL_S = 3600
    const val DEFAULT_INTERVAL_S = 60
    const val MAX_WINDOW = 20
    const val DEFAULT_WINDOW = 5
    const val DEFAULT_EXPIRES_S = 2 * 3600
    const val MAX_EXPIRES_S = 24 * 3600
    /** A watch with a tally keeps counting for weeks. */
    const val MAX_EXPIRES_TALLY_S = 90 * 86400
    const val DEFAULT_EXPIRES_TALLY_S = 30 * 86400
    const val MIN_COOLDOWN_S = 60
    const val DEFAULT_COOLDOWN_S = 600
    /** Choice conditions: Jev's confidence in the picked option (not its probability: 0.67 likely can be 0.34 confident). */
    const val DEFAULT_MIN_CONFIDENCE = 0.8
    /** The simple form: Jev's probability that the answer is yes. */
    const val DEFAULT_MIN_PROBABILITY = 0.8
    const val MAX_PER_AGENT = 5
    /** Checks an hour for the whole phone: every watch signs as this install, which Jev allows 300 calls an hour. */
    const val MAX_CHECKS_PER_HOUR = 240
    /** The same for an install the backend couldn't attest (debug builds, unlocked phones): Jev allows those 60, enough for one 30 s watch. */
    const val MAX_CHECKS_PER_HOUR_UNVERIFIED = 60
    /** How long a watch stops asking after Jev says the phone is over its hourly limit. */
    const val RATE_LIMIT_PAUSE_MS = 10 * 60_000L

    /** The phone-wide check budget for an install with this backend [trust] ("attested" or "unverified"). */
    fun maxChecksPerHour(trust: String?): Int = if (trust == "attested") MAX_CHECKS_PER_HOUR else MAX_CHECKS_PER_HOUR_UNVERIFIED
    const val MAX_QUESTIONS = 10
    const val MAX_CONDITIONS = 5
    const val MAX_QUESTION_CHARS = 1000
    const val MAX_CONTEXT_CHARS = 2000
    const val MAX_LABEL_CHARS = 80
    /** Consecutive failed checks before a watch gives up. */
    const val MAX_FAILURES = 5
    /** Checks kept for GET /v1/watches/{id}. */
    const val RECENT_CHECKS = 10
    /** Length of the accelerometer burst in each motion reading. */
    const val MOTION_SECONDS = 2.0
    /** The question id the simple `question` form uses. */
    const val SIMPLE_ID = "match"

    private val ID = Regex("^[A-Za-z0-9_-]{1,40}$")

    class Bad(message: String) : Exception(message)

    /**
     * A sound watch (data ["sound"]): woken by each loud sound the phone hears, not by an interval. [reuseS]: a sound
     * this soon after one that matched (or counted in the tally) reuses that answer for free, the same episode.
     */
    data class SoundOptions(val sensitivity: String = "normal", val reuseS: Int = (cc.bridgit.app.sound.SoundRules.DEFAULT_REUSE_MS / 1000).toInt(),
        val sources: List<String> = listOf("builtin")) {
        val reuseMs get() = reuseS * 1000L
        fun json(): JSONObject = JSONObject().put("sensitivity", sensitivity).put("reuse_s", reuseS)
            .put("sources", JSONArray(sources)).put("min_gap_s", cc.bridgit.app.sound.SoundRules.MIN_GAP_S)
    }

    /** "This answer holds": a picked option at [minConfidence] or more, or a probability between [min] and [max]. */
    data class Condition(val answer: String, val choice: String?, val minConfidence: Double, val min: Double?, val max: Double?) {
        fun json(): JSONObject = JSONObject().put("answer", answer).apply {
            if (choice != null) put("choice", choice).put("min_confidence", minConfidence)
            min?.let { put("min", it) }; max?.let { put("max", it) }
        }
    }

    data class Spec(
        val label: String,
        val question: String?,
        val questions: JSONObject,
        val conditions: List<Condition>,
        val data: List<String>,
        val intervalS: Int,
        val window: Int,
        val minReadings: Int,
        val expiresS: Int,
        val repeat: Boolean,
        val cooldownS: Int,
        val callbackUrl: String?,
        val context: String?,
        val model: String,
        /** "rear" or "front" when [data] has camera, else null. */
        val camera: String? = null,
        /** The free photo change check; only used when [data] has camera. */
        val local: ChangeSettings = ChangeSettings(enabled = false),
        /** At most this many paid model calls an hour for this watch; null = only the phone-wide limit. */
        val maxPaidPerHour: Int? = null,
        /** Counts, episodes and day totals kept on the phone, and rules on them; null = a plain watch. */
        val tally: WatchTally.Spec? = null,
        /** Set for a sound watch (data ["sound"]). */
        val sound: SoundOptions? = null,
        /** The [Situations] id the watch was made from ("situation" for own words); null = its own question. */
        val situation: String? = null,
        /** Faces seen when the picture changes (model none only); null = off. See [PeopleRules]. */
        val people: PeopleRules.Options? = null,
        val startPaused: Boolean = false,
        val additionalCameras: List<String> = emptyList(),
    ) {
        val cameraSources = listOfNotNull(camera) + additionalCameras
        val checksPerHour get() = checksPerHour(intervalS)
        val paid: Boolean get() = model in PAID_MODELS
        /** Paid calls an hour at most: what this watch takes from the phone-wide limit. */
        val paidPerHour: Int get() = if (!paid) 0 else minOf(checksPerHour, maxPaidPerHour ?: Int.MAX_VALUE)

        fun json(): JSONObject = JSONObject().put("label", label)
            .apply { if (startPaused) put("start_paused", true) }
            .put("question", question ?: JSONObject.NULL).put("questions", questions)
            .put("callback_when", JSONArray(conditions.map { it.json() }))
            .put("data", JSONArray(data)).put("interval_s", intervalS).put("window", window)
            .put("min_readings", minReadings).put("expires_in_s", expiresS).put("repeat", repeat)
            .put("cooldown_s", if (repeat) cooldownS else JSONObject.NULL)
            .put("callback_url", callbackUrl ?: JSONObject.NULL).put("context", context ?: JSONObject.NULL)
            .put("model", model)
            .put("camera", camera ?: JSONObject.NULL)
            .apply { if (additionalCameras.isNotEmpty()) put("additional_cameras", JSONArray(additionalCameras)) }
            .put("local", if (camera != null) local.json() else JSONObject.NULL)
            .put("max_paid_per_hour", maxPaidPerHour ?: JSONObject.NULL)
            .put("paid_calls_per_hour_at_most", paidPerHour)
            .put("tally", tally?.json() ?: JSONObject.NULL)
            .apply { sound?.let { put("sound", it.json()) }; situation?.let { put("situation", it) }; people?.let { put("people", it.json()) } }
    }

    fun checksPerHour(intervalS: Int): Int = (3600 + intervalS - 1) / intervalS

    /** The capability grants [data] needs. */
    fun grantsFor(data: List<String>, camera:String?=null): Set<String> = data.mapNotNull { if(it=="camera" && camera?.startsWith("lan:")==true) "lan_camera" else ELEMENTS[it] }.toSet()

    /** Reads and checks a POST /v1/watches body. Throws [Bad] with words an agent can act on. */
    fun parse(request: JSONObject, checkFlow: (String, String) -> Unit = { _, _ -> }, checkCall: (JSONObject, String) -> Unit = { _, _ -> }): Spec {
        // A situation ("pot_boiling_over", or own words) fills in the camera, questions and tally first.
        val expanded = Situations.expand(request)
        val b = expanded.body
        val known = setOf("label", "question", "questions", "callback_when", "min_probability", "data", "interval_s",
            "window", "min_readings", "expires_in_s", "repeat", "cooldown_s", "callback_url", "context", "model", "camera", "local", "max_paid_per_hour",
            "tally", "rules", "sound", "situation", "people", "start_paused", "additional_cameras")
        b.keys().asSequence().firstOrNull { it !in known }?.let { throw Bad("$it isn't a watch field. Fields: ${known.sorted().joinToString()}.") }

        val data = when (val d = b.opt("data")) {
            null -> DEFAULT_DATA
            is JSONArray -> (0 until d.length()).map { d.opt(it) as? String ?: throw Bad("data must be a list of names.") }
            else -> throw Bad("data must be a list, for example [\"location\", \"motion\"].")
        }.distinct()
        if (data.isEmpty()) throw Bad("data must name at least one of ${ELEMENTS.keys.joinToString()}.")
        data.firstOrNull { it !in ELEMENTS }?.let { throw Bad("$it isn't something a watch can read. Choose from ${ELEMENTS.keys.joinToString()}.") }
        val hasCamera = "camera" in data
        val hasSound = "sound" in data
        if (hasSound && data.size > 1) throw Bad("sound can't be combined with other data: a sound watch is woken by loud sounds, not on an interval. Use data [\"sound\"] alone.")
        if (!hasSound && b.has("sound")) throw Bad("sound options go with data [\"sound\"].")
        if (hasSound && b.has("interval_s")) throw Bad("A sound watch has no interval_s: it checks each loud sound the phone hears (at most one every ${cc.bridgit.app.sound.SoundRules.MIN_GAP_S} s).")
        val model = when (val m = b.opt("model")) { null -> defaultModel(data); is String -> m.trim().lowercase(); else -> throw Bad("model must be one of ${MODELS.joinToString()}.") }
        if (model !in MODELS) throw Bad("model must be one of ${MODELS.joinToString()}.")
        if (hasSound && model != "clef") throw Bad("A sound watch needs model clef: only Clef listens to audio.")

        val simple = b.opt("question")
        val advanced = b.opt("questions")
        val hasTally = b.has("tally")
        if (hasTally && model == "none") throw Bad("tally counts the model's answers; model none gives none. Pick clef or jev.")
        if (model == "none") {
            if (!hasCamera) throw Bad("model none (local only) needs camera in data: without a paid model only the free photo change check can call you back.")
            if (simple != null || advanced != null || b.has("callback_when") || b.has("min_probability"))
                throw Bad("model none asks no model, so it takes no question: you're called back when the picture changes. Pick clef or jev to ask a question.")
        } else if ((simple == null) == (advanced == null)) throw Bad("Send question (a yes/no question in words), questions (typed questions with callback_when) or situation (a common picture situation, GET /v1/situations), just one.")

        val question: String?
        val questions: JSONObject
        val conditions: List<Condition>
        if (model == "none") {
            question = null; questions = JSONObject(); conditions = emptyList()
        } else if (simple != null) {
            question = (simple as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: throw Bad("question must be text, for example \"Is the phone on a bicycle?\".")
            if (question.length > MAX_QUESTION_CHARS) throw Bad("question is over $MAX_QUESTION_CHARS characters.")
            if (b.has("callback_when")) throw Bad("callback_when goes with questions. With question, use min_probability (default $DEFAULT_MIN_PROBABILITY).")
            val minProb = prob(b, "min_probability", DEFAULT_MIN_PROBABILITY)
            questions = simpleQuestions(question)
            // With a tally the phone counts; it only calls back on each yes if min_probability asks for that.
            conditions = if (hasTally && !b.has("min_probability")) emptyList() else listOf(Condition(SIMPLE_ID, null, 0.0, minProb, null))
        } else {
            question = null
            if (b.has("min_probability")) throw Bad("min_probability goes with question. With questions, put min (or min_confidence) in callback_when.")
            questions = advanced as? JSONObject ?: throw Bad("questions must be an object: {id: {type, instructions, criteria?}}.")
            if (questions.length() == 0 || questions.length() > MAX_QUESTIONS) throw Bad("questions must hold 1-$MAX_QUESTIONS questions.")
            for (id in questions.keys()) {
                if (!ID.matches(id)) throw Bad("Question id $id must be 1-40 letters, digits, - or _.")
                val q = questions.optJSONObject(id) ?: throw Bad("questions.$id must be an object {type, instructions, criteria?}.")
                if (q.optString("type").isBlank()) throw Bad("questions.$id needs a type (choice, noul, ...).")
                val instr = q.opt("instructions") as? String
                if (instr.isNullOrBlank()) throw Bad("questions.$id needs instructions.")
                if (instr.length > MAX_QUESTION_CHARS) throw Bad("questions.$id.instructions is over $MAX_QUESTION_CHARS characters.")
            }
            conditions = b.opt("callback_when")?.let { conditions(it, questions) }
                ?: if (hasTally) emptyList() else throw Bad("questions needs callback_when: the answer that should call you back (or a tally, to count answers on the phone).")
        }

        val fastOk = hasTally && hasCamera && (b.optJSONObject("local")?.opt("change_check") as? Boolean ?: true)
        val interval = if (hasTally && hasCamera && !fastOk && (b.opt("interval_s") as? Number)?.toInt()?.let { it < MIN_INTERVAL_S } == true)
            throw Bad("interval_s under $MIN_INTERVAL_S needs the free change check on (local.change_check true).")
            else if (hasSound) cc.bridgit.app.sound.SoundRules.MIN_GAP_S
            else int(b, "interval_s", DEFAULT_INTERVAL_S, if (fastOk) MIN_INTERVAL_TALLY_S else MIN_INTERVAL_S, MAX_INTERVAL_S)
        // Only the newest photo is sent, so a camera-only watch needs no history by default.
        val photoOnly = data == listOf("camera") || hasSound
        val window = int(b, "window", if (photoOnly) 1 else DEFAULT_WINDOW, 1, MAX_WINDOW)
        val minReadings = int(b, "min_readings", if (photoOnly) 1 else min(3, window), 1, window)
        val expires = if (hasTally) int(b, "expires_in_s", DEFAULT_EXPIRES_TALLY_S, interval, MAX_EXPIRES_TALLY_S)
            else int(b, "expires_in_s", DEFAULT_EXPIRES_S, interval, MAX_EXPIRES_S)
        val hasPeople = b.has("people") && !b.isNull("people")
        if (hasPeople && (model != "none" || !hasCamera)) throw Bad("people works on a model none camera watch: it looks for faces when the free change check sees the picture change. Use data [\"camera\"] and model none.")
        val repeat = when (val r = b.opt("repeat")) { null -> hasTally || hasPeople; is Boolean -> r; else -> throw Bad("repeat must be true or false.") }
        if (hasPeople && !repeat) throw Bad("A people watch keeps looking each time the picture changes, so it can't end on its first change: leave repeat out (or true).")
        if (hasTally && !repeat && conditions.isNotEmpty()) throw Bad("A tally watch keeps counting, so it can't end on its first match: leave repeat out (or true).")
        if (!repeat && b.has("cooldown_s")) throw Bad("cooldown_s goes with repeat: true.")
        val cooldown = int(b, "cooldown_s", max(DEFAULT_COOLDOWN_S, interval), max(MIN_COOLDOWN_S, interval), MAX_EXPIRES_S)
        val callbackUrl = when (val u = b.opt("callback_url")) { null -> null; is String -> u.trim().ifEmpty { null }; else -> throw Bad("callback_url must be a URL.") }
        val context = when (val c = b.opt("context")) { null -> null; is String -> c.trim().ifEmpty { null }; else -> throw Bad("context must be text.") }
        if ((context?.length ?: 0) > MAX_CONTEXT_CHARS) throw Bad("context is over $MAX_CONTEXT_CHARS characters.")
        fun cameraId(value: Any?): String {
            val id = (value as? String)?.trim() ?: throw Bad("A camera id must be text.")
            if (id.lowercase() in CAMERAS) return id.lowercase()
            if (id.matches(Regex("lan:[a-zA-Z0-9-]{1,60}"))) return id
            throw Bad("Choose rear or front, or an enrolled lan:<id>.")
        }
        val camera = if (b.has("camera")) cameraId(b.get("camera")) else if (hasCamera) "rear" else null
        if (camera != null && !hasCamera) throw Bad("camera picks which camera; add camera to data to take a photo each reading.")
        val additional = if (b.has("additional_cameras")) {
            val ids = b.optJSONArray("additional_cameras") ?: throw Bad("additional_cameras must be a list of camera ids.")
            if (!hasCamera || hasPeople || ids.length() > 7) throw Bad("Use additional_cameras with a camera watch, up to seven additional cameras. People watches use one camera.")
            (0 until ids.length()).map { cameraId(ids.get(it)) }.also {
                if (camera in it || it.distinct().size != it.size) throw Bad("List each camera only once.")
            }
        } else emptyList()
        val local = local(b.opt("local"), hasCamera, model)
        val maxPaid = if (!b.has("max_paid_per_hour")) (if (hasSound) cc.bridgit.app.sound.SoundRules.DEFAULT_CHECKS_PER_HOUR else null) else {
            if (model == "none") throw Bad("max_paid_per_hour caps paid calls; model none makes none.")
            int(b, "max_paid_per_hour", 0, 1, MAX_CHECKS_PER_HOUR)
        }
        val label = when (val l = b.opt("label")) {
            null -> if (model == "none") "Picture changes" else (question ?: questions.optJSONObject(questions.keys().next())?.optString("instructions").orEmpty())
            is String -> l.trim()
            else -> throw Bad("label must be text.")
        }.let { if (it.length > MAX_LABEL_CHARS) it.take(MAX_LABEL_CHARS - 1).trimEnd() + "…" else it }.ifBlank { "Watch" }

        val sound = if (hasSound) soundOptions(b.opt("sound")) else null
        val tally = WatchTally.parse(b.opt("tally"), b.opt("rules"), questions, interval, checkCall,
            defaultGapS = sound?.reuseS?.coerceAtLeast(interval) ?: maxOf(3 * interval, 60), checkFlow = checkFlow)
        val startPaused = when (val raw = b.opt("start_paused")) {
            null -> false
            is Boolean -> raw
            else -> throw Bad("start_paused must be true or false.")
        }
        return Spec(label, question, questions, conditions, data, interval, window, minReadings, expires, repeat, cooldown, callbackUrl, context, model,
            camera, local, maxPaid, tally, sound, expanded.id, if (hasPeople) PeopleRules.parse(b.opt("people")) else null, startPaused, additional)
    }

    private fun soundOptions(raw: Any?): SoundOptions {
        if (raw == null) return SoundOptions()
        val o = raw as? JSONObject ?: throw Bad("sound must be an object, e.g. {\"sensitivity\": \"normal\", \"reuse_s\": 300}.")
        o.keys().asSequence().firstOrNull { it !in setOf("sensitivity", "reuse_s", "sources") }?.let { throw Bad("sound.$it isn't a sound option (sensitivity, reuse_s).") }
        val sens = when (val v = o.opt("sensitivity")) {
            null -> "normal"
            is String -> v.trim().lowercase().takeIf { it in cc.bridgit.app.sound.SoundRules.SENSITIVITIES } ?: throw Bad("sound.sensitivity must be quiet, normal or loud.")
            else -> throw Bad("sound.sensitivity must be quiet, normal or loud.")
        }
        val reuse = int(o, "reuse_s", SoundOptions().reuseS, 0, (cc.bridgit.app.sound.SoundRules.MAX_REUSE_MS / 1000).toInt(), "sound.")
        val sources = soundSources(o)
        return SoundOptions(sens, reuse, sources)
    }

    fun soundSources(options: JSONObject?): List<String> {
        if (options?.has("sources") != true) return listOf("builtin")
        val ids=options.optJSONArray("sources") ?: throw Bad("sound.sources must be a list of microphone ids")
        if (ids.length()>5) throw Bad("Choose at most four camera microphones and the built-in microphone.")
        val sources=(0 until ids.length()).map { i ->
            val id=ids.opt(i) as? String ?: throw Bad("Microphone ids must be strings.")
            if (id!="builtin" && !Regex("lan:[a-zA-Z0-9-]{1,60}").matches(id)) throw Bad("Use builtin or an enrolled LAN camera id.")
            id
        }
        if (sources.distinct()!=sources || sources.count { it!="builtin" }>4) throw Bad("Choose up to four different LAN microphones.")
        return sources
    }

    /**
     * The `local` options: the free photo change check before any paid call. Only with camera in data; on by
     * default there. With model none the change check is the whole watch, so it can't be turned off.
     */
    private fun local(raw: Any?, hasCamera: Boolean, model: String): ChangeSettings {
        if (raw == null) return ChangeSettings(enabled = hasCamera)
        val o = raw as? JSONObject ?: throw Bad("local must be an object, e.g. {\"change_check\": true, \"sensitivity\": \"normal\"}.")
        if (!hasCamera) throw Bad("local options are about photos: add camera to data, or leave local out.")
        val keys = setOf("change_check", "sensitivity", "learn_noise", "max_reuse_s")
        o.keys().asSequence().firstOrNull { it !in keys }?.let { throw Bad("local.$it isn't a local option (${keys.joinToString()}).") }
        fun bool(k: String, d: Boolean) = when (val v = o.opt(k)) { null -> d; is Boolean -> v; else -> throw Bad("local.$k must be true or false.") }
        val enabled = bool("change_check", true)
        if (!enabled && model == "none") throw Bad("model none is the change check alone, so local.change_check can't be false.")
        val sens = when (val v = o.opt("sensitivity")) {
            null -> ChangeSettings.DEFAULT_SENSITIVITY
            is String -> v.trim().lowercase().takeIf { it in ChangeSettings.PRESETS } ?: throw Bad("local.sensitivity must be ${ChangeSettings.PRESETS.keys.joinToString(", ")}.")
            else -> throw Bad("local.sensitivity must be ${ChangeSettings.PRESETS.keys.joinToString(", ")}.")
        }
        val reuse = int(o, "max_reuse_s", (ChangeSettings().maxReuseMs / 1000).toInt(), ChangeSettings.MIN_REUSE_S, ChangeSettings.MAX_REUSE_S, "local.")
        return ChangeSettings(enabled, sens, bool("learn_noise", true), reuse * 1000L)
    }

    /** Two answers mean the same (so a paid call for a changed picture was wasted): same picks, probabilities within [SAME_NOUL]. */
    fun sameAnswer(a: Jev.Answer, b: Jev.Answer): Boolean {
        val ids = a.answers.keys().asSequence().toSet()
        if (ids != b.answers.keys().asSequence().toSet()) return false
        return ids.all { id ->
            val ca = a.choice(id); val cb = b.choice(id)
            val na = a.noul(id); val nb = b.noul(id)
            when {
                ca != null || cb != null -> ca?.first == cb?.first
                na != null && nb != null -> kotlin.math.abs(na - nb) <= SAME_NOUL
                else -> a.answers.opt(id)?.toString() == b.answers.opt(id)?.toString()
            }
        }
    }

    /** The simple form: a yes/no probability (noul) question about the readings. */
    fun simpleQuestions(question: String): JSONObject = JSONObject().put(SIMPLE_ID, JSONObject()
        .put("type", "noul")
        .put("instructions", "$question Judge from the phone readings in the state (oldest first, newest last) and the photo, if there is one: how likely is it that this is true now?"))

    internal fun conditions(raw: Any, questions: JSONObject, field: String = "callback_when"): List<Condition> {
        val single = field != "callback_when"
        val list = when (raw) {
            is JSONObject -> listOf(raw)
            is JSONArray -> if (single) throw Bad("$field must be one condition object.") else (0 until raw.length()).map { raw.optJSONObject(it) ?: throw Bad("$field[$it] must be an object.") }
            else -> throw Bad(if (single) "$field must be a condition {answer, min?, max?} or {answer, choice, min_confidence?}." else "$field must be a condition or a list of 1-$MAX_CONDITIONS (all must hold).")
        }
        if (list.isEmpty() || list.size > MAX_CONDITIONS) throw Bad("$field must be 1-$MAX_CONDITIONS conditions (all must hold).")
        return list.mapIndexed { i, c ->
            val at = if (single) field else "$field[$i]"
            c.keys().asSequence().firstOrNull { it !in setOf("answer", "choice", "min_confidence", "min", "max") }
                ?.let { throw Bad("$at.$it isn't a condition field (answer, choice, min_confidence, min, max).") }
            val answer = c.opt("answer") as? String ?: throw Bad("$at.answer must name one of your questions.")
            val q = questions.optJSONObject(answer) ?: throw Bad("$at.answer must name one of your questions.")
            if (c.has("choice")) {
                val choice = (c.opt("choice") as? String)?.takeIf { it.isNotEmpty() } ?: throw Bad("$at.choice must be text.")
                if (c.has("min") || c.has("max")) throw Bad("$at: use min_confidence with choice; min and max are for probability (noul) answers.")
                q.optJSONObject("criteria")?.let { cr -> if (!cr.has(choice)) throw Bad("$at.choice $choice isn't one of $answer's options (${cr.keys().asSequence().joinToString()}).") }
                Condition(answer, choice, prob(c, "min_confidence", DEFAULT_MIN_CONFIDENCE, at), null, null)
            } else {
                if (c.has("min_confidence")) throw Bad("$at.min_confidence needs a choice.")
                if (!c.has("min") && !c.has("max")) throw Bad("$at needs choice (with min_confidence) or min / max for a probability.")
                val lo = if (c.has("min")) prob(c, "min", 0.0, at) else null
                val hi = if (c.has("max")) prob(c, "max", 1.0, at) else null
                if (lo != null && hi != null && lo > hi) throw Bad("$at: min is above max.")
                Condition(answer, null, 0.0, lo, hi)
            }
        }
    }

    private fun prob(o: JSONObject, key: String, default: Double, at: String? = null): Double {
        if (!o.has(key)) return default
        val v = (o.opt(key) as? Number)?.toDouble()
        if (v == null || v.isNaN() || v < 0.0 || v > 1.0) throw Bad("${at?.let { "$it." } ?: ""}$key must be a number from 0 to 1.")
        return v
    }

    private fun int(o: JSONObject, key: String, default: Int, lo: Int, hi: Int, at: String = ""): Int {
        if (!o.has(key)) return default
        val n = o.opt(key) as? Number
        val v = n?.toDouble()
        if (v == null || v != Math.floor(v) || v < lo || v > hi) throw Bad("$at$key must be a whole number from $lo to $hi.")
        return v.toInt()
    }

    /** True when every condition holds on [answer]. No answer for a question means it doesn't hold. */
    fun matches(conditions: List<Condition>, answer: Jev.Answer): Boolean = conditions.isNotEmpty() && conditions.all { c ->
        if (c.choice != null) answer.choice(c.answer)?.let { (pick, conf) -> pick == c.choice && conf >= c.minConfidence } == true
        else answer.noul(c.answer)?.let { p -> (c.min == null || p >= c.min) && (c.max == null || p <= c.max) } == true
    }

    /** Whether a check is due: enough readings, and for a repeating watch the cooldown has passed. */
    fun shouldAsk(spec: Spec, readings: Int, lastMatchAt: Long, now: Long, pausedUntil: Long = 0L): Boolean =
        readings >= spec.minReadings && now >= pausedUntil &&
            // A tally counts every answer: its cooldown only spaces out the callbacks (see [callbackDue]).
            (spec.tally != null || lastMatchAt <= 0 || now - lastMatchAt >= spec.cooldownS * 1000L)

    /** Whether a match should call back now: always for a plain watch (it only asks after the cooldown), after cooldown_s for a tally. */
    fun callbackDue(spec: Spec, lastMatchAt: Long, now: Long): Boolean =
        spec.tally == null || lastMatchAt <= 0 || now - lastMatchAt >= spec.cooldownS * 1000L

    /** Metres between two points (haversine). */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(min(1.0, sqrt(a)))
    }

    private fun round(v: Double, places: Int): Double { val f = 10.0.pow(places); return Math.round(v * f) / f }

    /**
     * Adds `moved_m` and `speed_kmh` to each reading's location: GPS speed when the fix has one, otherwise the
     * distance from the previous fix over the time between them. Returns new objects; pure.
     */
    fun withMovement(readings: List<JSONObject>): List<JSONObject> {
        var prev: Triple<Double, Double, Long>? = null
        return readings.map { r0 ->
            val r = JSONObject(r0.toString())
            val loc = r.optJSONObject("location")
            if (loc != null && loc.has("latitude") && loc.has("longitude")) {
                val lat = loc.getDouble("latitude"); val lon = loc.getDouble("longitude")
                val t = r.optLong("at_ms", 0L) - loc.optLong("fix_age_s", 0L) * 1000
                val p = prev
                val moved = p?.let { distanceM(it.first, it.second, lat, lon) }
                moved?.let { loc.put("moved_m", it.roundToInt()) }
                val gps = (loc.opt("speed_mps") as? Number)?.toDouble()
                val kmh = when {
                    gps != null -> gps * 3.6
                    p != null && moved != null && t - p.third >= 1000 -> moved / ((t - p.third) / 1000.0) * 3.6
                    else -> null
                }
                kmh?.let { loc.put("speed_kmh", round(it, 1)).put("speed_source", if (gps != null) "gps" else "between_fixes") }
                prev = Triple(lat, lon, t)
            }
            r
        }
    }

    /** Mean, spread and peak of acceleration (m/s², gravity removed) over a short burst; null if no samples. */
    fun motionStats(magnitudes: List<Double>, seconds: Double): JSONObject? {
        if (magnitudes.isEmpty()) return null
        val mean = magnitudes.average()
        val sd = sqrt(magnitudes.sumOf { (it - mean).pow(2) } / magnitudes.size)
        return JSONObject().put("accel_mean", round(mean, 2)).put("accel_sd", round(sd, 2))
            .put("accel_max", round(magnitudes.max(), 2)).put("samples", magnitudes.size).put("seconds", round(seconds, 1))
    }

    /**
     * The state sent to the model: what the readings are, the agent's context, the local time, and the readings
     * oldest first with their age. Readings carry `at_ms` internally; it becomes `at` (local ISO) and `seconds_ago`.
     */
    fun state(spec: Spec, readings: List<JSONObject>, now: Long, zone: ZoneId, photo: String? = null): JSONObject {
        val iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        val nowZ = Instant.ofEpochMilli(now).atZone(zone)
        val out = JSONArray()
        val moved = withMovement(readings)
        for ((i, r) in moved.withIndex()) {
            val at = r.optLong("at_ms", now)
            val o = JSONObject().put("at", iso.format(Instant.ofEpochMilli(at).atZone(zone))).put("seconds_ago", ((now - at) / 1000).coerceAtLeast(0))
            for (k in r.keys()) if (k != "at_ms") o.put(k, r.get(k))
            // Only the newest photo is sent; older camera readings say so.
            o.optJSONObject("camera")?.let { c -> if (c.optBoolean("taken")) c.put("photo", if (i == moved.lastIndex && photo != null) photo else "not sent (only the newest photo is)") }
            out.put(o)
        }
        return JSONObject()
            .put("about", "Readings from the owner's Android phone, taken about every ${spec.intervalS} s. Oldest first; the last one is the newest. " +
                "Speeds are km/h; acceleration is m/s² with gravity removed, over a ${"%.0f".format(Locale.ROOT, MOTION_SECONDS)} s burst." +
                    (if (photo != null) " The newest reading's camera.photo is the selected camera's photo." else ""))
            .apply { spec.context?.let { put("context", it) } }
            .put("now", iso.format(nowZ)).put("weekday", nowZ.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH))
            .put("interval_s", spec.intervalS).put("data", JSONArray(spec.data)).put("readings", out)
    }
}
