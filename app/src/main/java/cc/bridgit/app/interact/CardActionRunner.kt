package cc.bridgit.app.interact

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.api.ApiServer
import cc.bridgit.app.log.LogEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Feature #0007: runs a card's Bridgit.act call on the phone, as the agent that posted the card, through
 * [ApiServer.dispatch] (the in-process path MCP and the relay use). The agent's grants, the owner's switches,
 * each endpoint's own checks and limits and the audit log entry are the HTTP endpoint's own; the log marks it
 * via=card, source card:<interaction_id>. Every call is reported to the agent as an action.executed event,
 * so it works with the agent offline. Design: docs/spec-card-actions.md.
 */
object CardActionRunner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** A non-listening ApiServer with every handler registered, for the running service. */
    @Volatile private var cached: Pair<BridgeService, ApiServer>? = null

    private fun api(): ApiServer? {
        val svc = BridgeService.running ?: return null
        cached?.let { (s, a) -> if (s === svc) return a }
        return synchronized(this) {
            cached?.takeIf { it.first === svc }?.second
                ?: ApiServer(svc, emptyList(), (svc.application as BridgeApp).settings.port).apply { prepareRelay() }.also { cached = svc to it }
        }
    }

    /** Runs [json] for card [interactionId] in the background; [done] gets the page's result (any thread). */
    fun run(app: BridgeApp, interactionId: Long, json: String?, ownerScope: CoroutineScope? = null, done: (JSONObject) -> Unit) {
        (ownerScope ?: scope).launch {
            val r = runCatching { execute(app, interactionId, json) }.getOrElse {
                if (it is kotlinx.coroutines.CancellationException) throw it
                app.log.system("diagnostic", "crash", "Card action failed: ${it.javaClass.simpleName} at ${it.stackTrace.firstOrNull()}", level = "error")
                CardActions.refused("internal_error", "Bridgit hit a bug running this action.", 500)
            }
            done(r)
        }
    }

    private suspend fun execute(app: BridgeApp, interactionId: Long, json: String?): JSONObject {
        val card = app.interactions.get(interactionId) ?: return CardActions.refused("not_found", "This card is gone.", 404)
        val agent = app.agents.get(card.agentId) ?: return CardActions.refused("unauthorized", "The agent that sent this card was removed.", 401)
        var call: CardActions.Call? = null
        val result = try {
            call(app, agent, CardActions.parse(json).also { call = it }, "card:$interactionId", "card",
                JSONObject().put("interaction_id", interactionId), "card action")
        } catch (e: CardActions.Refused) { CardActions.refused(e.code, e.message ?: e.code, if (e.code == "not_found") 404 else 400) }
        app.interactions.event(agent.id, "action.executed", CardActions.event(interactionId, call, result))
        return result
    }

    /**
     * Runs [c] as [agent] in-process (cards, and watch rules since 3 Nov 2026), with the agent's own grants,
     * switches and endpoint limits; the log marks it [via] from [source]. Returns {ok, status, body}. A call
     * refused before any endpoint ran gets its own audit entry, then [CardActions.Refused] is rethrown.
     */
    suspend fun call(app: BridgeApp, agent: cc.bridgit.app.core.Agent, c: CardActions.Call, source: String, via: String, extra: JSONObject, what: String): JSONObject {
        try {
            val (spec, params) = CardActions.match(app.registry.modules.flatMap { it.endpoints }, c)
            val api = api() ?: throw CardActions.Refused("service_start_blocked", "Bridgit is off, so ${what}s can't run.")
            val out = api.dispatch(spec, agent, source, c.query, params, c.body, null, via = via)
            return CardActions.result(out.status, out.contentType, out.bytes, toPage = via != "watch")
        } catch (e: CardActions.Refused) {
            // Refused before any endpoint ran: dispatch wrote no log entry, so this one does.
            app.log.append(LogEntry(ts = System.currentTimeMillis(), channel = "audit", kind = "request", level = "warn",
                agentId = agent.id, agent = agent.name, sourceIp = source, method = c.method, endpoint = c.path,
                result = e.code, message = "${agent.name}'s $what was refused: ${e.message}".take(300),
                data = JSONObject(extra.toString()).put("via", via)))
            throw e
        }
    }
}
