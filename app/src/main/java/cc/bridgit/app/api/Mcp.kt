package cc.bridgit.app.api

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import cc.bridgit.app.capability.appVersion
import cc.bridgit.app.capability.address
import cc.bridgit.app.interact.CardActions
import org.json.JSONArray
import org.json.JSONObject

/**
 * API-15: `POST /v1/mcp`, MCP over Streamable HTTP with JSON replies (no SSE, stateless: no
 * Mcp-Session-Id). The agent's normal key authenticates the POST through the normal pipeline; each
 * tools/call then runs the endpoint's own handler in-process through [ApiServer.dispatch], which applies
 * the per-endpoint checks and writes the tool's own log entry. No loopback listener is opened.
 *
 * tools/list: every endpoint except MCP, pairing, the helper and openapi.json; Fixer Mode / Super Powers ones only
 * for agents that have them. Endpoints whose switch is off are listed with a "switched off" note (the list is
 * advertised as not changing, so clients that cache it still see a tool once the owner turns it on).
 */
class McpDispatch(private val api: ApiServer) {

    suspend fun handle(r: Req): Reply {
        // A browser page must never drive Bridgit's tools (DNS rebinding and the like); MCP clients send no Origin.
        r.header("Origin")?.takeIf { it.isNotBlank() }?.let {
            throw ErrorCatalog.error("forbidden", "Browser pages may not call /v1/mcp.", mapOf("reason" to "origin"))
        }
        val agent = r.agent!!
        return when (val m = McpProtocol.parse(r.body)) {
            is McpProtocol.Message.Invalid -> { r.params.put("rpc", "invalid"); rpc(McpProtocol.error(m.id, m.code, m.message)) }
            is McpProtocol.Message.Notification -> {
                r.params.put("rpc", m.method.take(60))
                r.message = "${agent.name}: MCP ${m.method.take(60)}"
                Reply.Text("", "application/json", 202)
            }
            is McpProtocol.Message.Request -> {
                r.params.put("rpc", m.method.take(60))
                r.message = "${agent.name}: MCP ${m.method.take(60)}"
                rpc(request(r, m))
            }
        }
    }

    private suspend fun request(r: Req, m: McpProtocol.Message.Request): JSONObject {
        val agent = api.app.agents.get(r.agent!!.id) ?: r.agent
        return when (m.method) {
            "initialize" -> McpProtocol.result(m.id, McpProtocol.initializeResult(m.params.optString("protocolVersion").ifEmpty { null },
                api.app.appVersion(), agent.name))
            "ping" -> McpProtocol.result(m.id, JSONObject())
            "tools/list" -> {
                val tools = JSONArray()
                visible(agent).filter { agent.allows(it.capability) }.forEach { e ->
                    val off = api.app.registry.byId(e.capability)?.enabled == false
                    tools.put(McpApps.decorate(McpTools.tool(e, off)))
                }
                McpApps.tools().forEach { tools.put(it) }
                McpProtocol.result(m.id, JSONObject().put("tools", tools))
            }
            "resources/list" -> McpProtocol.result(m.id, JSONObject().put("resources", JSONArray().put(McpApps.resource())))
            "resources/templates/list" -> McpProtocol.result(m.id, JSONObject().put("resourceTemplates", JSONArray()))
            "resources/read" -> McpApps.read(m.params.optString("uri"))?.let { McpProtocol.result(m.id, it) }
                ?: McpProtocol.error(m.id, McpProtocol.INVALID_PARAMS, "Unknown resource \"${m.params.optString("uri").take(80)}\".")
            "tools/call" -> {
                val name = m.params.optString("name")
                if (name == McpApps.SHOW_TOOL || name == McpApps.ACT_TOOL) {
                    r.params.put("tool", name)
                    r.message = "${agent.name}: MCP tools/call $name"
                    val a = m.params.opt("arguments") as? JSONObject ?: JSONObject()
                    return McpProtocol.result(m.id, if (name == McpApps.ACT_TOOL) cardAct(r, agent, a) else cardShow(agent, a))
                }
                val spec = McpTools.byName(name)?.takeIf { it in visible(agent) }
                    ?: return McpProtocol.error(m.id, McpProtocol.INVALID_PARAMS, "Unknown tool \"${name.take(60)}\". Call tools/list.")
                r.params.put("tool", name)
                r.message = "${agent.name}: MCP tools/call $name"
                val args = when (val a = m.params.opt("arguments")) {
                    null, JSONObject.NULL -> JSONObject()
                    is JSONObject -> a
                    else -> return McpProtocol.error(m.id, McpProtocol.INVALID_PARAMS, "arguments must be an object.")
                }
                val routed = try { McpTools.route(spec, args) } catch (e: McpTools.ArgError) {
                    val err = ErrorCatalog.error("invalid_parameter", e.message ?: "Bad arguments.")
                    return McpProtocol.result(m.id, McpTools.result(err.http, "application/json", err.toJson(r.requestId).toString().toByteArray()))
                }
                val out = api.dispatch(spec, agent, r.source, routed.queries, routed.pathParams, routed.body, routed.idempotencyKey)
                McpProtocol.result(m.id, McpTools.result(out.status, out.contentType, out.bytes, out.headers,
                    McpTools.downloadUri(api.app.address().baseUrl, spec, routed.pathParams, routed.queries)))
            }
            else -> McpProtocol.error(m.id, McpProtocol.METHOD_NOT_FOUND, "Method not found: ${m.method.take(60)}")
        }
    }

    /** MCP Apps: a card's Bridgit.act in a chat app, run as [agent] exactly like tools/call of that endpoint. */
    private suspend fun cardAct(r: Req, agent: cc.bridgit.app.core.Agent, args: JSONObject): JSONObject {
        val call = try { CardActions.parse(McpApps.actRequest(args)) } catch (e: CardActions.Refused) { return McpApps.actResult(McpApps.refusedAct(e)) }
        val (spec, params) = try { CardActions.match(api.app.registry.modules.flatMap { it.endpoints }, call) }
            catch (e: CardActions.Refused) { return McpApps.actResult(McpApps.refusedAct(e)) }
        // The card's id goes in the log only when the card is this agent's own.
        val card = args.optLong("interaction_id", 0L).takeIf { it > 0 && api.app.interactions.get(it)?.agentId == agent.id }
        val out = api.dispatch(spec, agent, card?.let { "${r.source} card:$it" } ?: r.source, call.query, params, call.body, null, via = "mcp_app")
        return McpApps.actResult(CardActions.result(out.status, out.contentType, out.bytes))
    }

    /** MCP Apps: card_show, one card for the chat's viewer from html, one of the agent's own HTML cards, or a sample. */
    private fun cardShow(agent: cc.bridgit.app.core.Agent, args: JSONObject): JSONObject {
        val html = args.optString("html").takeIf { args.has("html") && it.isNotEmpty() }
        val iid = args.optLong("interaction_id", 0L).takeIf { args.has("interaction_id") }
        val sample = args.optString("sample_id").takeIf { args.has("sample_id") && it.isNotEmpty() }
        if (listOfNotNull(html, iid, sample).size != 1) return McpApps.error("invalid_parameter", "Pass exactly one of html, interaction_id or sample_id.")
        val title = args.optString("title").ifEmpty { null }
        return try {
            when {
                html != null -> McpApps.showResult(html, title, null, "html")
                iid != null -> {
                    val card = api.app.interactions.get(iid)?.takeIf { it.agentId == agent.id && it.html != null && it.kind == cc.bridgit.app.interact.IKind.HTML }
                        ?: return McpApps.error("not_found", "No HTML card $iid of yours.")
                    McpApps.showResult(card.html!!, title ?: card.title, card.id, "interaction $iid")
                }
                else -> {
                    val file = runCatching { api.app.assets.open(Samples.ASSET).bufferedReader().use { it.readText() } }.getOrNull()
                        ?: return McpApps.error("not_available", "This build has no samples.")
                    val h = McpApps.sampleHtml(file, sample!!) ?: return McpApps.error("not_found", "No HTML sample \"${sample.take(60)}\". The samples tool lists them.")
                    McpApps.showResult(h, title, null, "sample $sample")
                }
            }
        } catch (e: cc.bridgit.app.interact.InteractionError) { McpApps.error(e.code, e.message ?: e.code) }
    }

    private fun visible(agent: cc.bridgit.app.core.Agent) = McpTools.specs.filter { e -> e.power?.let { agent.has(it) } ?: true }

    /** The JSON-RPC envelope as is: Reply.Json would add request_id to it, which strict clients reject. */
    private fun rpc(o: JSONObject) = Reply.Text(o.toString(), "application/json")

    /** GET (an SSE stream) and DELETE (end a session) aren't offered: 405, which MCP clients expect. */
    fun Route.installExtras() {
        listOf(HttpMethod.Get, HttpMethod.Delete).forEach { m ->
            route(Endpoints.MCP.path, m) { handle { call.response.header("Allow", "POST"); call.respond(HttpStatusCode.MethodNotAllowed) } }
        }
    }
}
