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
        if (home?.optJSONObject("device") == null) return ""
        val model = cc.bridgit.app.core.session.ViewerModel.from(home)
        val b = cc.bridgit.app.core.session.ViewerModel.battery(model)
        return listOfNotNull(cc.bridgit.app.core.session.ViewerModel.details(model).takeIf { it.isNotEmpty() },
            b?.let { (p, charging) -> "$p%" + if (charging) " charging" else "" }).joinToString(" · ")
    }

    /** "Galaxy Tab A9 · Android 15 · Bridgit 1.8.1786": [summary] without the battery, for the phone chooser's cards. */
    fun details(home: JSONObject?): String =
        if (home?.optJSONObject("device") == null) "" else cc.bridgit.app.core.session.ViewerModel.details(cc.bridgit.app.core.session.ViewerModel.from(home))

    /** Battery percent and whether it is charging, or null when the reply has none. */
    fun battery(home: JSONObject?): Pair<Int, Boolean>? =
        home?.let { cc.bridgit.app.core.session.ViewerModel.battery(cc.bridgit.app.core.session.ViewerModel.from(it)) }

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
