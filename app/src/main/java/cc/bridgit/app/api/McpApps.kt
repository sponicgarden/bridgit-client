package cc.bridgit.app.api

import cc.bridgit.app.interact.CardActions
import cc.bridgit.app.interact.CardRules
import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP Apps (spec 2026-01-26, extension io.modelcontextprotocol/ui): Bridgit cards in chat apps. Pure Kotlin, unit-tested.
 *
 * One resource, [CARD_URI], is a small viewer page (resources/cc/bridgit/app/api/mcp-card-viewer.html). A host renders
 * it for the tools in [UI_TOOLS]; it takes the card's HTML from the tool input or result, replaces itself with the
 * card page built like the phone's ([CardRules.prepare]: the phone's own GUARD and RESPOND_SHIM over a small native
 * object), so the card gets the same window.Bridgit (and window.Bridget) the phone gives it. Bridgit.act becomes a
 * tools/call of [ACT_TOOL], which the host may only call from the card ("app" visibility) and which runs one /v1
 * endpoint as the connected agent through the normal dispatcher, exactly as tools/call of that endpoint would.
 * Bridgit.respond sends the answer to the chat. Card HTML needs no change; there are no keys in the page.
 */
object McpApps {
    const val EXTENSION = "io.modelcontextprotocol/ui"
    const val MIME = "text/html;profile=mcp-app"
    const val CARD_URI = "ui://bridgit/card.html"
    const val ACT_TOOL = "card_act"
    const val SHOW_TOOL = "card_show"

    /** Endpoint tools whose calls a host shows with the card viewer. */
    val UI_TOOLS = setOf("interactions_send")

    /** Where the viewer template takes the phone's card page head. */
    const val PHONE_HEAD_SLOT = "/*BRIDGIT_PHONE_HEAD*/''"

    /**
     * The viewer page. Its template gets the phone's own card page head, [CardRules.prepare] of an empty card
     * (CSP meta, viewport, [CardRules.GUARD], [CardRules.RESPOND_SHIM]), as a JS string, so a card in a chat app
     * runs on exactly the phone's bridge scripts and they can't drift. JSONObject.quote escapes `</` so the
     * head's own script tags don't end the viewer's script.
     */
    val viewer: String by lazy {
        val template = McpApps::class.java.getResourceAsStream("mcp-card-viewer.html")!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
        check(template.indexOf(PHONE_HEAD_SLOT).let { it >= 0 && it == template.lastIndexOf(PHONE_HEAD_SLOT) }) { "viewer template needs one $PHONE_HEAD_SLOT" }
        template.replace(PHONE_HEAD_SLOT, JSONObject.quote(CardRules.prepare("")))
    }

    private fun ui(visibility: List<String>? = null) = JSONObject().put("resourceUri", CARD_URI)
        .apply { visibility?.let { put("visibility", JSONArray(it)) } }

    /** Adds the viewer to an endpoint tool from [McpTools.tool] when it is one of [UI_TOOLS]. */
    fun decorate(tool: JSONObject): JSONObject {
        if (tool.optString("name") in UI_TOOLS) tool.put("_meta", JSONObject().put("ui", ui()))
        return tool
    }

    /** The tools MCP Apps adds to tools/list. */
    fun tools(): List<JSONObject> = listOf(showTool(), actTool())

    fun showTool(): JSONObject = JSONObject()
        .put("name", SHOW_TOOL)
        .put("description", "Show a Bridgit HTML card here in the chat, live: its buttons call the phone as you (Bridgit.act), " +
            "and its answer (Bridgit.respond) comes back to this chat. Nothing is sent to the phone's screen; use interactions_send for that. " +
            "Pass exactly one of html (a card, up to 256 KB, the same rules as kind html), interaction_id (one of your HTML cards) or sample_id (from the samples tool, e.g. html-connection-test).")
        .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("html", JSONObject().put("type", "string").put("description", "The card's HTML, as for POST /v1/interactions kind html"))
            .put("interaction_id", JSONObject().put("type", "integer").put("description", "One of your HTML cards, to show it again here"))
            .put("sample_id", JSONObject().put("type", "string").put("description", "An HTML sample id from the samples tool"))
            .put("title", JSONObject().put("type", "string").put("description", "A short title for the chat")))
            .put("required", JSONArray()))
        .put("annotations", JSONObject().put("title", "Show a Bridgit card in the chat").put("readOnlyHint", true))
        .put("_meta", JSONObject().put("ui", ui(listOf("model", "app"))))

    fun actTool(): JSONObject = JSONObject()
        .put("name", ACT_TOOL)
        .put("description", "Used by a Bridgit card shown in the chat (its Bridgit.act): runs one of your /v1 endpoints on the phone, " +
            "as you, with your grants and the owner's switches. Returns {ok, status, body, error?}.")
        .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("method", JSONObject().put("type", "string").put("enum", JSONArray(listOf("GET", "POST", "PUT", "PATCH", "DELETE"))))
            .put("path", JSONObject().put("type", "string").put("description", "An endpoint path such as /v1/flashlight"))
            .put("query", JSONObject().put("type", "object"))
            .put("body", JSONObject().put("type", "object"))
            .put("interaction_id", JSONObject().put("type", "integer").put("description", "The card's interaction, if it has one (for the log)")))
            .put("required", JSONArray().put("path")))
        .put("annotations", JSONObject().put("title", "Card action").put("readOnlyHint", false).put("destructiveHint", false))
        .put("_meta", JSONObject().put("ui", ui(listOf("app"))))

    /** The resources/list entry. */
    fun resource(): JSONObject = JSONObject().put("uri", CARD_URI).put("name", "bridgit_card")
        .put("title", "Bridgit card").put("description", "Shows a Bridgit HTML card in the chat, with its buttons working").put("mimeType", MIME)

    /** The resources/read result for [uri], or null if it isn't ours. */
    fun read(uri: String): JSONObject? {
        if (uri != CARD_URI) return null
        val content = JSONObject().put("uri", CARD_URI).put("mimeType", MIME).put("text", viewer)
            .put("_meta", JSONObject().put("ui", JSONObject().put("prefersBorder", true)))
        return JSONObject().put("contents", JSONArray().put(content))
    }

    /** A card_act call's arguments as the JSON string [CardActions.parse] takes; interaction_id is only for the log. */
    fun actRequest(args: JSONObject): String {
        val o = JSONObject()
        listOf("method", "path", "query", "body").forEach { k -> if (args.has(k)) o.put(k, args.opt(k)) }
        return o.toString()
    }

    /** A card_act result: [r] ({ok, status, body, error?}) as structuredContent and as text. Never isError, like the phone. */
    fun actResult(r: JSONObject): JSONObject = JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", r.toString())))
        .put("structuredContent", r)

    /** A card_show result: the card for the viewer (structuredContent, which hosts keep out of the model's context). */
    fun showResult(html: String, title: String?, interactionId: Long?, source: String): JSONObject {
        CardRules.checkHtml(html)
        val sc = JSONObject().put("html", html).put("source", source)
        title?.takeIf { it.isNotBlank() }?.let { sc.put("title", it.take(80)) }
        interactionId?.let { sc.put("interaction_id", it) }
        val note = "Showing the card in the chat (${html.toByteArray(Charsets.UTF_8).size / 1024 + 1} KB, from $source). " +
            "Its buttons run on the phone as you; an answer comes back to this chat as a message."
        return JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", note))).put("structuredContent", sc)
    }

    fun error(code: String, message: String): JSONObject = JSONObject().put("isError", true)
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", JSONObject().put("error", code).put("message", message).toString())))

    /** The HTML of sample [id] from the samples file's text, or null if it has none. */
    fun sampleHtml(samplesFile: String, id: String): String? {
        val all = JSONObject(samplesFile).optJSONArray("samples") ?: return null
        for (i in 0 until all.length()) {
            val s = all.getJSONObject(i)
            if (s.optString("id") == id) return s.optJSONObject("request")?.optString("html")?.takeIf { it.isNotEmpty() }
        }
        return null
    }

    /** Refused act request (not even parsed), shaped like the phone's. */
    fun refusedAct(e: CardActions.Refused): JSONObject =
        CardActions.refused(e.code, e.message ?: e.code, if (e.code == "not_found") 404 else 400)
}
