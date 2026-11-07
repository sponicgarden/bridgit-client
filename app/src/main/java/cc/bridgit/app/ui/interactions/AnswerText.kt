package cc.bridgit.app.ui.interactions

import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns what an HTML card sent (CAP-34, any JSON) into short lines a person can read,
 * instead of showing the raw JSON (owner, 2 Nov 2026).
 * {"button":"yes","values":{"note":"blue cup"},"step":2} -> button "yes", lines ["Note: blue cup", "Step: 2"].
 */
object AnswerText {

    class Result(val button: String?, val lines: List<String>)

    private const val MAX_LINES = 8
    private const val MAX_VALUE = 120

    fun of(data: Any?): Result {
        if (data !is JSONObject) return Result(null, listOfNotNull(value(data)?.let(::clip)))
        val button = data.optString("button").takeIf { data.has("button") && !data.isNull("button") && it.isNotBlank() }
        val lines = ArrayList<String>()
        flatten(data, null, lines, skip = setOf("button"))
        return Result(button, lines.take(MAX_LINES))
    }

    private fun flatten(o: JSONObject, prefix: String?, out: MutableList<String>, skip: Set<String> = emptySet()) {
        for (k in o.keys()) {
            if (k in skip) continue
            val label = if (prefix == null) label(k) else "$prefix, ${label(k).lowercase()}"
            when (val v = o.opt(k)) {
                // "values" only groups the card's fields, so its keys stand on their own.
                is JSONObject -> flatten(v, if (prefix == null && k == "values") null else label, out)
                else -> value(v)?.let { out.add("$label: ${clip(it)}") }
            }
        }
    }

    /** Readable text for one value; null for empty values, which are left out. */
    private fun value(v: Any?): String? = when (v) {
        null, JSONObject.NULL -> null
        is Boolean -> if (v) "yes" else "no"
        is JSONArray -> (0 until v.length()).mapNotNull { value(v.opt(it)) }.joinToString(", ").ifBlank { null }
        is JSONObject -> v.keys().asSequence().mapNotNull { k -> value(v.opt(k))?.let { "${label(k).lowercase()} $it" } }
            .joinToString(", ").ifBlank { null }
        else -> v.toString().trim().ifBlank { null }
    }

    /** "favouriteColor" / "favourite_color" -> "Favourite color". */
    fun label(key: String): String {
        val words = key.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").replace('_', ' ').replace('-', ' ').trim().lowercase()
        return words.replaceFirstChar { it.uppercase() }.ifBlank { key }
    }

    private fun clip(s: String) = if (s.length <= MAX_VALUE) s else s.take(MAX_VALUE - 1) + "…"
}
