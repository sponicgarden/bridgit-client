package cc.bridgit.app.context

import org.json.JSONObject
import java.time.ZonedDateTime

/** Facts are evidence, never instructions or authority to act. Expired values are not returned as current. */
object ContextFacts {
    const val VERSION = 1
    const val LOCATION_TTL = 15 * 60_000L
    val PLACES = listOf("home", "work", "retail", "vacation_spot", "other", "unknown")
    fun fact(value: Any, source: String, confidence: Double, now: Long, expires: Long? = null) = JSONObject()
        .put("value", value).put("source", source).put("confidence", confidence)
        .put("observed_at", now).put("expires_at", expires ?: JSONObject.NULL)
        .put("status", if (source == "owner" || source == "device") "known" else "inferred")

    fun visible(fact: JSONObject?, now: Long): JSONObject = when {
        fact == null -> JSONObject().put("value", JSONObject.NULL).put("status", "unknown")
        fact.optLong("expires_at", Long.MAX_VALUE) <= now -> JSONObject(fact.toString())
            .put("value", JSONObject.NULL).put("status", "expired")
        else -> JSONObject(fact.toString())
    }

    /** Conservative clock fallback when there is no usable position for sunrise/sunset. */
    fun clockNight(now: ZonedDateTime): Boolean = now.hour < 7 || now.hour >= 22

    fun choice(choice: Pair<String, Double>?): Pair<String, Double> =
        choice?.takeIf { it.first in PLACES && it.second.isFinite() && it.second >= .8 && it.second <= 1.0 }
            ?: ("unknown" to 0.0)

    fun keyValid(key: String) = Regex("^[a-z][a-z0-9_]{0,39}$").matches(key)
}
