package cc.bridgit.app.applets

import cc.bridgit.app.BridgeApp
import org.json.JSONObject

/** Readable names for an installed applet's actions and limits, shared by its card's Schedule and Recent changes. */
object AppletLabels {
    /** The package watch's own label from the definition (its request's label, else its question), or null. */
    fun definitionWatchLabel(definition: JSONObject?, pkgWatchId: String): String? {
        val ws = definition?.optJSONObject("processing")?.optJSONArray("watches") ?: return null
        val w = (0 until ws.length()).mapNotNull { ws.optJSONObject(it) }.firstOrNull { it.optString("id") == pkgWatchId } ?: return null
        val req = w.optJSONObject("request") ?: w
        return (req.opt("label") as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: (req.opt("question") as? String)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** A package watch's label: the running watch's, else the definition's. */
    fun watchLabel(app: BridgeApp, r: JSONObject, pkgWatchId: String): String? {
        val wid = r.optJSONObject("watches")?.optJSONObject(pkgWatchId)?.optString("watch_id")?.takeIf { it.isNotEmpty() }
        val running = wid?.let { app.watches.get(r.optString("agent_id"), it) }?.spec?.label?.trim()?.takeIf { it.isNotEmpty() }
        return running ?: definitionWatchLabel(r.optJSONObject("definition"), pkgWatchId)
    }

    /** Action id → its watches' labels, for every action the device runs for [r]. */
    fun watchLabelsByAction(app: BridgeApp, r: JSONObject): Map<String, List<String>> =
        runCatching { app.budgetSync.actionsOf(r) }.getOrDefault(emptyList()).associate { a -> a.id to a.watches.mapNotNull { watchLabel(app, r, it) } }

    /** Action id → readable label, for [ids] (the sync's actions, else the device's) in their order. */
    fun actionLabels(app: BridgeApp, r: JSONObject, text: AppletCardText, ids: List<String>): Map<String, String> =
        text.actionLabels(ids, watchLabelsByAction(app, r), r.optString("name"))
}
