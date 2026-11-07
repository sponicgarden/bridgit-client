package cc.bridgit.app.interact

import cc.bridgit.app.api.Endpoints
import cc.bridgit.app.planned.PlannedEndpoints
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Feature #0007: Bridgit.act parsing, endpoint matching, results and the in-flight gate. */
class CardActionsTest {
    private val specs = Endpoints.all

    private fun refusal(json: String?): String = try { CardActions.parse(json); fail("accepted $json"); "" } catch (e: CardActions.Refused) { e.code }
    private fun matchCode(json: String): String = try { CardActions.match(specs, CardActions.parse(json)); fail("matched $json"); "" } catch (e: CardActions.Refused) { e.code }

    @Test fun flashlightRequestParsesAndMatchesTheHttpEndpoint() {
        val c = CardActions.parse("""{"path":"/v1/flashlight","body":{"on":true,"auto_off_seconds":1}}""")
        assertEquals("POST", c.method) // a body means POST
        assertEquals("/v1/flashlight", c.path)
        assertEquals(1, c.body.getInt("auto_off_seconds"))
        val (spec, params) = CardActions.match(specs, c)
        assertEquals(PlannedEndpoints.TORCH, spec)
        assertTrue(params.isEmpty())
    }

    @Test fun pathForms() {
        for (p in listOf("/v1/phone/ring", "v1/phone/ring", "/phone/ring", "phone/ring", "/v1/phone/ring/"))
            assertEquals(p, "/v1/phone/ring", CardActions.parse("""{"method":"post","path":"$p"}""").path)
        // No body and no method: GET.
        assertEquals("GET", CardActions.parse("""{"path":"/v1/flashlight"}""").method)
    }

    @Test fun pathParamsAndQuery() {
        val c = CardActions.parse("""{"method":"GET","path":"/v1/interactions/12","query":{"wait":0}}""")
        assertEquals(mapOf("wait" to "0"), c.query)
        val (spec, params) = CardActions.match(specs, c)
        assertEquals(Endpoints.INTERACTION_GET, spec)
        assertEquals(mapOf("id" to "12"), params)
    }

    @Test fun badRequestsAreRefused() {
        assertEquals("invalid_parameter", refusal(null))
        assertEquals("invalid_parameter", refusal("not json"))
        assertEquals("invalid_parameter", refusal("\"/v1/flashlight\"")) // a bare string is not a request
        assertEquals("invalid_parameter", refusal("{}"))
        assertEquals("invalid_parameter", refusal("""{"path":"/v1/flashlight?on=1"}"""))
        assertEquals("invalid_parameter", refusal("""{"path":"/v1/../v1/flashlight"}"""))
        assertEquals("invalid_parameter", refusal("""{"path":"/v1/flashlight","body":[1]}"""))
        assertEquals("invalid_parameter", refusal("""{"path":"/v1/flashlight","method":"TRACE"}"""))
        assertEquals("invalid_parameter", refusal("""{"path":"/v1/flashlight","query":"on=1"}"""))
        // Too big says so, with the fix (docs/applet-dev-experience.md B1).
        assertEquals("request_too_big", refusal("{\"path\":\"" + "a".repeat(CardRules.RESPOND_MAX_BYTES) + "\"}"))
    }

    @Test fun unknownAndKeylessEndpointsAreRefused() {
        assertEquals("not_found", matchCode("""{"path":"/v1/nope"}"""))
        assertEquals("not_found", matchCode("""{"method":"DELETE","path":"/v1/flashlight"}"""))
        // No key (pairing) or MCP: nothing a card should call.
        assertEquals("not_available", matchCode("""{"path":"/v1/pair","body":{"code":"123456"}}"""))
        assertEquals("not_available", matchCode("""{"path":"/v1/mcp","body":{}}"""))
        // Health takes a key, so a card may call it.
        assertEquals(Endpoints.HEALTH, CardActions.match(specs, CardActions.parse("""{"path":"/v1/health"}""")).first)
    }

    @Test fun anyGrantedEndpointMatches() {
        // No allowlist: whatever the agent may call over HTTP, its card may call (the grant check runs in dispatch).
        assertEquals(Endpoints.SNAPSHOT, CardActions.match(specs, CardActions.parse("""{"path":"/v1/camera/snapshot","body":{}}""")).first)
        assertEquals(Endpoints.INTERACTION_CREATE, CardActions.match(specs, CardActions.parse("""{"path":"/v1/interactions","body":{"kind":"notice","title":"Hi"}}""")).first)
    }

    @Test fun resultShapes() {
        val ok = CardActions.result(200, "application/json", """{"on":true,"request_id":"r1"}""".toByteArray())
        assertTrue(ok.getBoolean("ok"))
        assertEquals(200, ok.getInt("status"))
        assertTrue(ok.getJSONObject("body").getBoolean("on"))
        assertFalse(ok.has("error"))

        val denied = CardActions.result(403, "application/json; charset=utf-8",
            """{"error":"agent_not_allowed","message":"no","request_id":"r2"}""".toByteArray())
        assertFalse(denied.getBoolean("ok"))
        assertEquals("agent_not_allowed", denied.getString("error"))
        // A failure without an error body still says what went wrong.
        assertEquals("http_502", CardActions.result(502, "text/plain", ByteArray(0)).getString("error"))

        val photo = CardActions.result(200, "image/jpeg", ByteArray(1234))
        assertEquals(1234, photo.getJSONObject("body").getInt("bytes"))
        assertEquals("image/jpeg", photo.getJSONObject("body").getString("content_type"))

        val big = CardActions.result(200, "application/json", ("\"" + "x".repeat(CardActions.PAGE_BODY_MAX_BYTES) + "\"").toByteArray())
        assertTrue(big.getJSONObject("body").has("bytes"))

        val refused = CardActions.refused("not_found", "No endpoint", 404)
        assertEquals("not_found", refused.getString("error"))
        assertEquals("not_found", refused.getJSONObject("body").getString("error"))
    }

    @Test fun eventForTheAgent() {
        val c = CardActions.parse("""{"path":"/v1/flashlight","body":{"on":true}}""")
        val e = CardActions.event(12, c, CardActions.result(200, "application/json", "{}".toByteArray()))
        assertEquals(12L, e.getLong("interaction_id"))
        assertEquals("POST", e.getString("method"))
        assertEquals("/v1/flashlight", e.getString("path"))
        assertTrue(e.getBoolean("ok"))
        assertTrue(e.isNull("error"))
        val bad = CardActions.event(12, null, CardActions.refused("invalid_parameter", "x"))
        assertTrue(bad.isNull("path"))
        assertEquals("invalid_parameter", bad.getString("error"))
        // Event type is visible to any agent with interactions access (no extra grant).
        assertTrue(EventAccess.allowed(cc.bridgit.app.core.Agent(id = "a", name = "A", keyHash = "h", keySalt = "s", keyPrefix = "p",
            createdAt = 0, caps = setOf("interactions")), "action.executed"))
    }

    @Test fun deliverScriptCarriesRefAndEscapesTheBody() {
        val s = CardActions.deliverScript(7, CardActions.result(200, "application/json", """{"t":"</script><b>'\"x"}""".toByteArray()))
        assertTrue("\"ref\":7" in s)
        assertTrue("__bridgitActed" in s && "bridgit:acted" in s)
        // The body is a JSON literal: quotes stay escaped, so it can't break out of the script.
        val literal = s.substringAfter("var r=").substringBefore(";try{")
        assertEquals("</script><b>'\"x", JSONObject(literal).getJSONObject("body").getString("t"))
    }

    @Test fun gateCapsCallsInFlight() {
        val g = ActGate(max = 2)
        assertEquals(1, g.nextRef()); assertEquals(2, g.nextRef())
        assertTrue(g.start()); assertTrue(g.start())
        assertFalse(g.start())
        g.finish()
        assertTrue(g.start())
        assertEquals(2, g.inFlight)
    }

    @Test fun shimExposesAct() {
        val shim = CardRules.RESPOND_SHIM
        assertTrue("act:function" in shim && "n.act(s)" in shim && "new Promise" in shim)
        assertTrue("__bridgitActed" in shim)
        assertFalse("</script" in shim.lowercase())
    }
}
