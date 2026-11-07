package cc.bridgit.app.portal

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * What this phone, as a Bridgit Portal viewer, last read from each paired phone (in memory, for the phone chooser
 * and Home's status line). Nothing here is saved.
 */
object PortalViewer {
    data class Seen(val home: JSONObject, val at: Long)

    private val last = ConcurrentHashMap<String, Seen>()

    fun remember(device: String, home: JSONObject, now: Long = System.currentTimeMillis()) { last[device] = Seen(home, now) }
    fun seen(device: String): Seen? = last[device]

    /** "Galaxy Tab A9 · Android 15 · Bridgit 1.8.1786 · 82%" from a /v1/portal/home reply; empty parts are left out. */
    fun summary(home: JSONObject?): String {
        val d = home?.optJSONObject("device") ?: return ""
        val b = home.optJSONObject("battery")
        return listOfNotNull(
            d.optString("model").takeIf { it.isNotEmpty() },
            d.optString("android").takeIf { it.isNotEmpty() }?.let { "Android $it" },
            d.optString("app_version").takeIf { it.isNotEmpty() }?.let { "Bridgit $it" },
            b?.takeIf { it.has("percent") && !it.isNull("percent") }?.let { "${it.optInt("percent")}%" + if (it.optBoolean("charging")) " charging" else "" },
        ).joinToString(" · ")
    }

    /** "Galaxy Tab A9 · Android 15 · Bridgit 1.8.1786": [summary] without the battery, for the phone chooser's cards. */
    fun details(home: JSONObject?): String {
        val d = home?.optJSONObject("device") ?: return ""
        return listOfNotNull(
            d.optString("model").takeIf { it.isNotEmpty() },
            d.optString("android").takeIf { it.isNotEmpty() }?.let { "Android $it" },
            d.optString("app_version").takeIf { it.isNotEmpty() }?.let { "Bridgit $it" },
        ).joinToString(" · ")
    }

    /** Battery percent and whether it is charging, or null when the reply has none. */
    fun battery(home: JSONObject?): Pair<Int, Boolean>? {
        val b = home?.optJSONObject("battery") ?: return null
        if (!b.has("percent") || b.isNull("percent")) return null
        return b.optInt("percent") to b.optBoolean("charging")
    }

    /** "just now", "3 min ago", "2 h ago", or "not reached yet". */
    fun freshness(at: Long?, now: Long = System.currentTimeMillis()): String {
        if (at == null || at <= 0) return "not reached yet"
        val s = (now - at) / 1000
        return when {
            s < 60 -> "just now"
            s < 3600 -> "${s / 60} min ago"
            s < 86_400 -> "${s / 3600} h ago"
            else -> "${s / 86_400} d ago"
        }
    }
}
