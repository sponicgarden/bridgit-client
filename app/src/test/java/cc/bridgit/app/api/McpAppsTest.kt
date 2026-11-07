package cc.bridgit.app.api

import cc.bridgit.app.interact.CardRules
import cc.bridgit.app.interact.CardActions
import cc.bridgit.app.interact.InteractionError
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** MCP Apps: Bridgit cards in chat apps through the card viewer resource and the card_show / card_act tools. */
class McpAppsTest {
    private fun list(o: JSONObject, k: String) = o.getJSONArray(k).let { a -> (0 until a.length()).map { a.getString(it) } }

    @Test fun viewerIsAnMcpAppResourceThatProvidesBothBridgeNames() {
        val read = McpApps.read(McpApps.CARD_URI)!!.getJSONArray("contents").getJSONObject(0)
        assertEquals(McpApps.CARD_URI, read.getString("uri"))
        assertEquals("text/html;profile=mcp-app", read.getString("mimeType"))
        val html = read.getString("text")
        assertTrue(html.startsWith("<!DOCTYPE html>"))
        listOf("w.Bridgit=n;w.Bridget=n", "ui/initialize", "ui/notifications/initialized", "ui/notifications/tool-result",
            "ui/notifications/size-changed", "ui/message", "ui/update-model-context", "bridgit:answered", "bridgit:acted",
            "name: 'card_act'", "data-blocked-equiv").forEach { assertTrue(it, it in html) }
        // No key or address of any phone in the page.
        listOf("Bearer", "Authorization", "http://", "https://").forEach { assertFalse(it, it in html) }
        assertNull(McpApps.read("ui://bridgit/other"))
        assertEquals(McpApps.CARD_URI, McpApps.resource().getString("uri"))
        assertEquals(McpApps.MIME, McpApps.resource().getString("mimeType"))
    }

    @Test fun viewerRunsThePhonesOwnBridgeScripts() {
        val html = McpApps.viewer
        // The scripts themselves, character for character (they need no escaping in a JS string).
        listOf(CardRules.GUARD, CardRules.RESPOND_SHIM, CardRules.CSP).forEach { assertTrue(it.take(40), it in html) }
        assertTrue(html.indexOf(CardRules.GUARD) < html.indexOf(CardRules.RESPOND_SHIM))
        // The whole head the card page gets is CardRules.prepare's, decoded back from the viewer's JS string.
        val start = html.indexOf("var PHONE_HEAD = ")
        assertTrue(start > 0)
        val head = JSONTokener(html.substring(start + "var PHONE_HEAD = ".length)).nextValue()
        assertEquals(CardRules.prepare(""), head)
        assertFalse(McpApps.PHONE_HEAD_SLOT in html)
        // Only the viewer's own script ends a script block; the head's tags stay inside the string.
        assertEquals(html.indexOf("</script>"), html.lastIndexOf("</script>"))
        // The native object goes in before GUARD, as the phone's Kotlin bridge does, and is a plain value the shim can replace.
        assertTrue("PHONE_HEAD.indexOf('<script>')" in html)
        assertFalse("configurable: false" in html)
    }

    @Test fun onlyTheCardToolsCarryTheViewer() {
        val send = McpApps.decorate(McpTools.tool(Endpoints.all.first { McpTools.name(it) == "interactions_send" }))
        assertEquals(McpApps.CARD_URI, send.getJSONObject("_meta").getJSONObject("ui").getString("resourceUri"))
        val other = McpApps.decorate(McpTools.tool(Endpoints.SNAPSHOT))
        assertFalse(other.has("_meta"))
        McpApps.UI_TOOLS.forEach { assertNotNull(it, McpTools.byName(it)) }
    }

    @Test fun cardActIsForTheCardOnlyAndCardShowForBoth() {
        val act = McpApps.actTool().getJSONObject("_meta").getJSONObject("ui")
        assertEquals(listOf("app"), list(act, "visibility"))
        val show = McpApps.showTool().getJSONObject("_meta").getJSONObject("ui")
        assertEquals(listOf("model", "app"), list(show, "visibility"))
        assertEquals(McpApps.CARD_URI, show.getString("resourceUri"))
        val endpointNames = McpTools.specs.map { McpTools.name(it) }.toSet()
        McpApps.tools().forEach { assertFalse(it.getString("name"), it.getString("name") in endpointNames) }
    }

    @Test fun actArgumentsBecomeThePhonesActRequest() {
        val args = JSONObject().put("method", "post").put("path", "/v1/watches/w1/pause").put("body", JSONObject().put("paused", true))
            .put("interaction_id", 12).put("extra", "dropped")
        val call = CardActions.parse(McpApps.actRequest(args))
        assertEquals("POST", call.method)
        assertEquals("/v1/watches/w1/pause", call.path)
        assertEquals(true, call.body.getBoolean("paused"))
        assertFalse(JSONObject(McpApps.actRequest(args)).has("interaction_id"))
        try { CardActions.parse(McpApps.actRequest(JSONObject())); fail() } catch (e: CardActions.Refused) {
            val r = McpApps.actResult(McpApps.refusedAct(e))
            assertFalse(r.has("isError"))
            assertEquals(false, r.getJSONObject("structuredContent").getBoolean("ok"))
            assertEquals("invalid_parameter", r.getJSONObject("structuredContent").getString("error"))
        }
    }

    @Test fun cardShowChecksTheHtmlLikeThePhone() {
        val r = McpApps.showResult("<p>Hi</p>", "Hello", 7L, "html")
        val sc = r.getJSONObject("structuredContent")
        assertEquals("<p>Hi</p>", sc.getString("html")); assertEquals(7L, sc.getLong("interaction_id")); assertEquals("Hello", sc.getString("title"))
        // The card goes to the viewer only: the text the model sees doesn't repeat it.
        assertFalse("<p>Hi</p>" in r.getJSONArray("content").getJSONObject(0).getString("text"))
        try { McpApps.showResult("<iframe src=x></iframe>", null, null, "html"); fail() } catch (e: InteractionError) { assertEquals("invalid_parameter", e.code) }
        try { McpApps.showResult("x".repeat(256 * 1024 + 1), null, null, "html"); fail() } catch (e: InteractionError) { assertEquals("payload_too_large", e.code) }
    }

    @Test fun samplesGiveTheirCardHtml() {
        val file = File("../site-content/public/samples/samples.json").readText()
        val html = McpApps.sampleHtml(file, "html-connection-test")
        assertNotNull(html); assertTrue(html!!.contains("Bridgit"))
        assertNull(McpApps.sampleHtml(file, "no-such-sample"))
    }

    @Test fun initializeAdvertisesResourcesAndTheUiExtension() {
        val caps = McpProtocol.initializeResult("2025-06-18", "1.0", "a").getJSONObject("capabilities")
        assertTrue(caps.has("tools")); assertTrue(caps.has("resources"))
        assertEquals(McpApps.MIME, caps.getJSONObject("extensions").getJSONObject(McpApps.EXTENSION).getJSONArray("mimeTypes").getString(0))
    }
}
