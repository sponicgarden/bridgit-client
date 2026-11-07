package cc.bridgit.app.ui.interactions

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.applets.AppletTableStore
import cc.bridgit.app.applets.AppletTables
import cc.bridgit.app.interact.CardActionRunner
import cc.bridgit.app.interact.CardActions
import cc.bridgit.app.interact.Interaction
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads a pieces card's data sources (docs/applet-cards.md) as the agent that posted it: watch tallies and
 * status and read-only calls through the same in-process dispatch as `Bridgit.act` (its grants, switches and
 * limits apply), and the applet's own workflow runs, tables and kept photos straight from the phone. No source
 * is read while the card isn't on screen.
 */
class PieceSources(private val app: BridgeApp, private val card: Interaction) {
    val json: JSONObject = runCatching { JSONObject(card.html ?: "{}") }.getOrDefault(JSONObject())
    val sources: JSONObject = json.optJSONObject("sources") ?: JSONObject()
    val agentId: String get() = card.agentId
    /** The installed applet this card belongs to, if any. */
    fun instance(): JSONObject? = app.applets.forAgent(agentId)
    fun paused(): Boolean = instance()?.optString("status") == "paused"
    fun workflowId(): String? = instance()?.optString("workflow_id")?.takeIf { it.isNotEmpty() }
        ?: runCatching { app.platform.store.list(agentId, "workflow", 10).firstOrNull()?.getString("id") }.getOrNull()
    fun tables(): List<AppletTables.Table> = instance()?.optJSONObject("definition")?.optJSONObject("processing")
        ?.let { runCatching { AppletTables.parse(it) }.getOrNull() } ?: emptyList()

    /** Button replies (`into`), kept while the card is open. */
    val replies = java.util.concurrent.ConcurrentHashMap<String, Any>()

    class ReadError(message: String) : Exception(message)

    /** Reads source [name] now. Throws [ReadError] with words for the card's error strip. */
    suspend fun read(name: String): Any? {
        val s = sources.optJSONObject(name) ?: return null
        return when {
            s.has("watch") -> call("GET", "/v1/watches/" + s.optString("watch") + (if (s.optString("read") == "tally") "/tally" else ""), JSONObject())
            s.has("call") -> s.getJSONObject("call").let { c -> call("GET", c.optString("path"), c.optJSONObject("query") ?: JSONObject()) }
            s.has("table") -> {
                val store = AppletTableStore.of(app)
                val t = s.getString("table")
                JSONObject().put("items", JSONArray(store.rows(agentId, t, s.optInt("limit", 20), null, true))).put("count", store.count(agentId, t))
            }
            s.has("photos") -> JSONObject().put("items", JSONArray(AppletTableStore.of(app).photos(agentId, s.getString("photos"), s.optInt("limit", 12))))
            s.has("runs") -> {
                val wf = workflowId() ?: return JSONObject().put("items", JSONArray())
                val runs = app.platform.workflows.runs(agentId, wf, if (s.has("step")) 20 else s.optInt("limit", 10)).optJSONArray("runs") ?: JSONArray()
                if (s.has("step")) {
                    val step = s.getString("step")
                    (0 until runs.length()).asSequence().mapNotNull { i -> runs.optJSONObject(i)?.optJSONArray("steps") }
                        .mapNotNull { steps -> (0 until steps.length()).mapNotNull { steps.optJSONObject(it) }.firstOrNull { it.optString("id") == step && it.optString("status") == "ok" } }
                        .firstOrNull()?.optJSONObject("result")
                } else JSONObject().put("items", runs).put("count", runs.length())
            }
            s.has("reply") -> replies[s.getString("reply")]
            s.has("batch") -> app.platform.batches.summary(agentId, s.getString("batch"))
            // The phone's mode (docs/applet-reach.md D2): reading is free for applets.
            s.has("mode") -> app.platform.modes.json(withLog = false).put("label", cc.bridgit.app.platform.Modes.label(app.platform.modes.current()))
            else -> null
        }
    }

    private suspend fun call(method: String, path: String, query: JSONObject): Any? {
        val agent = app.agents.get(agentId) ?: throw ReadError("The control agent that sent this card was removed.")
        val r = try {
            val c = CardActions.parse(JSONObject().put("method", method).put("path", path).put("query", query).toString())
            CardActionRunner.call(app, agent, c, "card:${card.id}", "card",
                JSONObject().put("interaction_id", card.id), "card read")
        } catch (e: CardActions.Refused) { throw ReadError(e.message ?: e.code) }
        if (!r.optBoolean("ok")) throw ReadError((r.opt("body") as? JSONObject)?.optString("message")?.ifEmpty { null } ?: r.optString("error").ifEmpty { "This couldn't be read." })
        return r.opt("body")
    }

    /** Sources that change on their own (watch counts, runs) are read again this often while the card is open. */
    fun liveEveryMs(name: String): Long? {
        val s = sources.optJSONObject(name) ?: return null
        return when {
            s.has("watch") || s.has("runs") || s.has("mode") -> 10_000L
            s.has("call") -> s.optInt("every_s", 0).takeIf { it > 0 }?.times(1000L)
            else -> null // tables, kept photos and batches redraw when they change; replies when their button runs
        }
    }
}
