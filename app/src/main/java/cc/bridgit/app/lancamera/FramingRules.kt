package cc.bridgit.app.lancamera

import cc.bridgit.app.judgment.Jev
import org.json.JSONObject

/** Pure policies. A model supplies observations, never arbitrary motor commands. */
object FramingRules {
    data class Limits(val moves: Int = 6, val checks: Int = 12, val timeoutS: Int = 90,
        val settleMs: Long = 1500, val step: Double = .03, val travel: Double = .30,
        val confidence: Double = .80, val similarity: Double = .75) {
        init {
            require(moves in 0..24 && checks in 2..48 && timeoutS in 15..300)
            require(settleMs in 1000..5000 && step.isFinite() && step in .005.. .05)
            require(travel.isFinite() && travel in .01.. .60)
            require(confidence.isFinite() && confidence in .6.. .99 && similarity.isFinite() && similarity in .6.. .99)
        }
        fun json() = JSONObject().put("max_moves", moves).put("max_checks", checks).put("timeout_s", timeoutS)
            .put("settle_ms", settleMs).put("max_step", step).put("max_travel", travel)
            .put("min_confidence", confidence).put("min_similarity", similarity)
        companion object {
            fun parse(o: JSONObject, defaults: Limits = Limits()): Limits {
                fun n(k: String, d: Double): Double = if (!o.has(k)) d else (o.opt(k) as? Number)?.toDouble()?.takeIf { it.isFinite() }
                    ?: throw IllegalArgumentException("$k must be a finite number")
                fun i(k: String, d: Int): Int { val v = n(k, d.toDouble()); require(v == v.toInt().toDouble()) { "$k must be an integer" }; return v.toInt() }
                return Limits(i("max_moves",defaults.moves), i("max_checks",defaults.checks), i("timeout_s",defaults.timeoutS),
                    i("settle_ms",defaults.settleMs.toInt()).toLong(), n("max_step",defaults.step), n("max_travel",defaults.travel),
                    n("min_confidence",defaults.confidence), n("min_similarity",defaults.similarity))
            }
        }
    }
    data class Observation(val similarity: Double?, val identity: Double?, val visibility: Pair<String,Double>?,
        val horizontal: Pair<String,Double>?, val vertical: Pair<String,Double>?, val size: Pair<String,Double>?,
        val panAmount: Pair<String,Double>? = null, val tiltAmount: Pair<String,Double>? = null) {
        fun json() = JSONObject().put("similarity", similarity ?: JSONObject.NULL).put("identity", identity ?: JSONObject.NULL)
            .put("identity_verified", false).apply {
                mapOf("visibility" to visibility,"horizontal" to horizontal,"vertical" to vertical,"size" to size,"pan_amount" to panAmount,"tilt_amount" to tiltAmount).forEach { (k,v) ->
                    put(k, v?.let { JSONObject().put("choice",it.first).put("confidence",it.second) } ?: JSONObject.NULL)
                }
            }
    }
    fun observation(a: Jev.Answer) = Observation(a.noul("similarity"), a.noul("identity"), a.choice("visibility",VISIBILITY),
        a.choice("horizontal",HORIZONTAL),a.choice("vertical",VERTICAL),a.choice("size",SIZE),a.choice("pan_amount",AMOUNT),a.choice("tilt_amount",AMOUNT))
    val VISIBILITY = listOf("whole","cropped","occluded","absent","multiple","unclear")
    val HORIZONTAL = listOf("left","center","right","unclear")
    val VERTICAL = listOf("up","center","down","unclear")
    val AMOUNT = listOf("small","medium","large","unclear")
    val SIZE = listOf("small","suitable","large","unclear")
    fun questions(context: Boolean) = JSONObject().apply {
        put("similarity", JSONObject().put("type","noul").put("instructions","Probability a target visually similar to reference_photo is visible in observed_photo. Appearance only; an absent target is zero."))
        put("identity", JSONObject().put("type","noul").put("instructions","Probability the visible target is the same individual as reference_photo. Be uncertain when markings or face are hidden. This is not identity verification."))
        fun q(id: String, instructions: String, criteria: Map<String,String>) { put(id,JSONObject().put("type","choice").put("instructions",instructions).put("criteria",JSONObject(criteria))) }
        q("visibility","Describe the reference-like target in observed_photo. If multiple plausible targets exist, answer multiple.",mapOf("whole" to "Whole target is visible, including its extremities.","cropped" to "Target extends outside frame.","occluded" to "Furniture or another object hides part of target.","absent" to "No similar target.","multiple" to "More than one plausible target.","unclear" to "Cannot tell."))
        q("horizontal","Where is the center of the visible target body in observed_photo? Use the full image, not a crop.",mapOf("left" to "Left of 35% of image width.","center" to "Within 35% through 65% of image width; this broad band is intentional.","right" to "Right of 65% of image width.","unclear" to "Cannot reliably locate one target."))
        q("vertical","Where is the center of the visible target body in observed_photo? Ignore reference_photo position.",mapOf("up" to "Above 35% of image height.","center" to "Within 35% through 65% of image height.","down" to "Below 65% of image height.","unclear" to "Cannot reliably locate one target."))
        for(axis in listOf("pan","tilt")) q("${axis}_amount","How much $axis correction does observed_photo need to bring the target into the broad center band? Estimate image error, never motor units.",
            mapOf("small" to "Less than 10 percent of the image dimension beyond the center band, or already centered.","medium" to "10 through 25 percent beyond the center band.","large" to "More than 25 percent beyond the center band.","unclear" to "Cannot estimate safely."))
        q("size",if(context) "Does observed_photo show the whole target with nearby activity context?" else "Does observed_photo frame the whole target with little extra surrounding space?",mapOf("small" to "Too much surrounding space; target is small.","suitable" to "Whole target at appropriate scale.","large" to "Target is too large or cropped.","unclear" to "Cannot judge scale."))
    }
    /** Null means no safe directional answer. Zero means centered, not a missing observation. */
    fun direction(value: Pair<String,Double>?, negative: String, positive: String, confidence: Double): Int? {
        if (value == null || !value.second.isFinite() || value.second < confidence) return null
        return when(value.first) { negative -> -1; positive -> 1; "center" -> 0; else -> null }
    }
    fun similar(o: Observation, l: Limits) = o.similarity?.let { it.isFinite() && it >= l.similarity } == true &&
        o.visibility?.let { it.second >= l.confidence && it.first in listOf("whole","cropped") } == true
    fun error(o: Observation, l: Limits): Int? {
        val x = direction(o.horizontal,"left","right",l.confidence) ?: return null
        val y = direction(o.vertical,"up","down",l.confidence) ?: return null
        return kotlin.math.abs(x) + kotlin.math.abs(y)
    }
}

/** All PTZ entry points share this process-wide motion lease. Stop always remains available. */
object CameraMotion {
    private val leases = mutableMapOf<String,String>()
    private val cancellations = mutableMapOf<String,()->Unit>()
    @Synchronized fun owns(camera: String, token: String) = leases[camera] == token
    fun cancel(camera: String) { val cancel=synchronized(this) { leases[camera]?.let(cancellations::get) }; cancel?.invoke() }
    @Synchronized fun acquire(cameras: List<String>, token: String, cancel: (() -> Unit)? = null): Boolean {
        if (cameras.any { it in leases }) return false
        cameras.forEach { leases[it] = token }; if(cancel!=null)cancellations[token]=cancel; return true
    }
    @Synchronized fun release(token: String) { if (cc.bridgit.app.applets.AppletDeviceStops.retained(token)) return; leases.entries.removeAll { it.value == token }; cancellations.remove(token) }
    @Synchronized fun <T> command(camera: String, token: String?, block: () -> T): T { check(camera,token); return block() }
    @Synchronized fun check(camera: String, token: String?) {
        if (leases[camera]?.let { it != token } == true) throw CameraFailure("Camera has an active framing operation. Cancel it before manual movement.")
    }
}
