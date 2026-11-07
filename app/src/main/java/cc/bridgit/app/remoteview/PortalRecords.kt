package cc.bridgit.app.remoteview

import org.json.JSONArray
import org.json.JSONObject

/**
 * Records an applet's own card sends to its web portal (5 Nov 2026, Exercise check: one record per workout).
 * The package declares the kind it sends (`ui.portal.records.kind`, for example "workout"); the card posts
 * POST /v1/applet/portal/record with only fixed words and numbers, checked here the same way the backend checks
 * them: no free text and no pictures ever leave the phone this way. Pure Kotlin, unit-tested.
 */
object PortalRecords {
    val KIND = Regex("^[a-z][a-z_]{0,23}$")
    private val LABEL = Regex("^[a-z0-9_-]{1,40}$")
    const val MAX_PENDING = 50

    class Bad(message: String) : IllegalArgumentException(message)

    /** The record kind [definition] declares for its portal, or null when it declares none (or a bad one). */
    fun kindOf(definition: JSONObject?): String? =
        definition?.optJSONObject("ui")?.optJSONObject("portal")?.optJSONObject("records")?.optString("kind")?.takeIf { KIND.matches(it) }

    /** `ui.portal.include`: the applet's maker chooses whether the owner turns the portal on (default) or it comes with the applet. */
    val INCLUDE = setOf("optional", "always")

    /** Whether [definition] makes its web portal part of the applet (`ui.portal.include: "always"`). */
    fun always(definition: JSONObject?): Boolean =
        definition?.optJSONObject("ui")?.optJSONObject("portal")?.optString("include") == "always"

    /** Checks a package's `ui.portal` block (AppletPackage.check). Null when fine, else the problem. */
    fun checkDeclaration(ui: JSONObject?): String? {
        val portal = ui?.opt("portal") ?: return null
        if (portal !is JSONObject) return "ui.portal must be an object."
        portal.opt("include")?.let { if (it !in INCLUDE) return "ui.portal.include must be \"optional\" (the owner turns the portal on) or \"always\" (it comes with the applet)." }
        val rec = portal.opt("records") ?: return null
        if (rec !is JSONObject) return "ui.portal.records must be an object."
        if (!KIND.matches(rec.optString("kind"))) return "ui.portal.records.kind must be 1-24 lowercase letters or _ (for example \"workout\")."
        val title = rec.optString("title")
        if (title.isBlank() || title.length > 40) return "ui.portal.records.title is required (40 characters at most)."
        return null
    }

    private fun label(v: Any?, what: String): String = (v as? String)?.takeIf { LABEL.matches(it) }
        ?: throw Bad("$what must be 1-40 lowercase letters, digits, _ or - (no free text).")

    private fun whole(v: Any?, max: Long, what: String): Long? = when (v) {
        null, JSONObject.NULL -> null
        is Int, is Long -> (v as Number).toLong().takeIf { it in 0..max } ?: throw Bad("$what must be a whole number from 0 to $max.")
        is Double -> v.takeIf { it == Math.floor(it) && it in 0.0..max.toDouble() }?.toLong() ?: throw Bad("$what must be a whole number from 0 to $max.")
        else -> throw Bad("$what must be a whole number from 0 to $max.")
    }

    /** The record to queue for [kind] from a card's [body], at [now]. Throws [Bad] with a plain reason. */
    fun record(body: JSONObject, kind: String, now: Long): JSONObject {
        val out = JSONObject().put("kind", kind).put("at", now)
        body.opt("label")?.takeIf { it != JSONObject.NULL }?.let { out.put("label", label(it, "label")) }
        body.opt("metrics")?.takeIf { it != JSONObject.NULL }?.let { m ->
            if (m !is JSONObject) throw Bad("metrics must be an object of numbers.")
            if (m.length() > 16) throw Bad("metrics has at most 16 numbers.")
            val o = JSONObject()
            m.keys().forEach { k ->
                val v = m.get(k) as? Number ?: throw Bad("metrics.$k must be a number.")
                val d = v.toDouble()
                if (!d.isFinite() || Math.abs(d) > 1e6) throw Bad("metrics.$k must be a number between -1,000,000 and 1,000,000.")
                o.put(label(k, "Each metrics key"), Math.round(d * 100) / 100.0)
            }
            if (o.length() > 0) out.put("metrics", o)
        }
        body.opt("items")?.takeIf { it != JSONObject.NULL }?.let { a ->
            if (a !is JSONArray) throw Bad("items must be a list.")
            if (a.length() > 20) throw Bad("items has at most 20 entries.")
            val arr = JSONArray()
            for (i in 0 until a.length()) {
                val it = a.opt(i) as? JSONObject ?: throw Bad("items[$i] must be an object.")
                val x = JSONObject().put("label", label(it.opt("label"), "items[$i].label"))
                whole(it.opt("count"), 10_000, "items[$i].count")?.let { c -> x.put("count", c) }
                whole(it.opt("seconds"), 86_400, "items[$i].seconds")?.let { c -> x.put("seconds", c) }
                arr.put(x)
            }
            out.put("items", arr)
        }
        body.opt("answers")?.takeIf { it != JSONObject.NULL }?.let { a ->
            if (a !is JSONObject) throw Bad("answers must be an object.")
            if (a.length() > 8) throw Bad("answers has at most 8 entries.")
            val o = JSONObject()
            a.keys().forEach { k ->
                val v = a.get(k) as? JSONObject ?: throw Bad("answers.$k must be {value, confidence}.")
                val conf = (v.opt("confidence") as? Number)?.toDouble()?.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0)
                o.put(label(k, "Each answers key"), JSONObject().put("value", label(v.opt("value"), "answers.$k.value")).apply { if (conf != null) put("confidence", Math.round(conf)) })
            }
            if (o.length() > 0) out.put("answers", o)
        }
        if (!out.has("metrics") && !out.has("items") && !out.has("label")) throw Bad("Send at least a label, metrics or items.")
        return out
    }

    /** The push summary for an applet that only sends card records (it has no tally watch). */
    fun summary(name: String, kind: String, paused: Boolean, appVersion: String): JSONObject = JSONObject()
        .put("name", name.take(60)).put("track", kind).put("labels", JSONArray())
        .put("running", !paused).put("paused", paused).put("photo_on", false).put("rules_fired", 0)
        .put("app_version", appVersion).put("last_check_at", JSONObject.NULL)

    /** [pending] plus [record], oldest dropped beyond [MAX_PENDING]. */
    fun queued(pending: JSONArray, record: JSONObject): JSONArray {
        val all = (0 until pending.length()).mapNotNull { pending.optJSONObject(it) } + record
        return JSONArray(all.takeLast(MAX_PENDING))
    }

    /** [pending] without the records that were sent ([sentAts] are their times). */
    fun without(pending: JSONArray, sentAts: Set<Long>): JSONArray =
        JSONArray((0 until pending.length()).mapNotNull { pending.optJSONObject(it) }.filter { it.optLong("at") !in sentAts })
}
