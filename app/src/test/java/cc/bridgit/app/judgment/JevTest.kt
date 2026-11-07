package cc.bridgit.app.judgment

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The phone side of POST /v1/jev; the backend side is bridgit-cloud/feedback/test/jev.test.mjs. */
class JevTest {
    private val q = JSONObject().put("safe", JSONObject().put("type", "choice").put("instructions", "Safe?"))

    @Test fun bodyHoldsStateAndQuestionsOnly() {
        val b = JSONObject(String(Jev.requestBody(JSONObject().put("battery", 12), q)))
        assertEquals(setOf("state", "questions"), b.keys().asSequence().toSet())
        assertEquals(12, b.getJSONObject("state").getInt("battery"))
        assertEquals("Safe?", b.getJSONObject("questions").getJSONObject("safe").getString("instructions"))
        assertEquals("plain", JSONObject(String(Jev.requestBody("plain", q))).getString("state"))
        Jev.requestBody(JSONArray().put(1), q)
    }

    @Test(expected = IllegalArgumentException::class) fun noQuestionsIsRefused() { Jev.requestBody("x", JSONObject()) }

    @Test(expected = IllegalArgumentException::class) fun numberStateIsRefused() { Jev.requestBody(5, q) }

    @Test fun parsesTypedAnswers() {
        val a = Jev.parse(200, """{"model":"jev-1.14.0","answers":{"safe":{"type":"choice","choice":"yes","confidence":0.93},
            "p":{"type":"noul","noul":0.2},"bad":{"type":"choice","choice":"maybe","confidence":1.5}},
            "usage":{"input_tokens":1000,"output_tokens":7},"cost_usd":0.000042,"elapsed_ms":380}""")
        assertEquals("jev-1.14.0", a.model)
        assertEquals(1000L, a.inputTokens)
        assertEquals("yes" to 0.93, a.choice("safe", listOf("yes", "no")))
        assertNull(a.choice("safe", listOf("no")))
        assertNull(a.choice("bad"))
        assertNull(a.choice("p"))
        assertEquals(0.2, a.noul("p")!!, 1e-9)
        assertNull(a.noul("missing"))
    }

    private fun failure(code: Int, body: String): Jev.Failure =
        try { Jev.parse(code, body); fail("expected a failure"); throw AssertionError() } catch (e: Jev.Failure) { e }

    @Test fun mapsBackendErrors() {
        failure(429, """{"error":"spend_cap","message":"budget"}""").let { assertEquals("spend_cap", it.reason); assertFalse(it.retryable) }
        failure(429, """{"error":"rate_limited","message":"slow down"}""").let { assertEquals("rate_limited", it.reason); assertTrue(it.retryable) }
        failure(503, """{"error":"jev_busy","message":"busy"}""").let { assertTrue(it.retryable) }
        failure(503, """{"error":"not_configured","message":"off"}""").let { assertEquals("not_configured", it.reason); assertFalse(it.retryable) }
        failure(502, "<html>").let { assertEquals("team_unreachable", it.reason); assertTrue(it.retryable) }
        failure(422, """{"error":"jev_rejected","message":"Jev refused the request: bad type"}""").let {
            assertEquals("jev_rejected", it.reason); assertEquals("Jev refused the request: bad type", it.message); assertFalse(it.retryable)
        }
        failure(200, "{}").let { assertEquals("malformed", it.reason) }
    }

    @Test fun requestBodyCarriesActionsOnlyWhenGiven() {
        val rules = JSONArray().put(JSONObject().put("id", "a"))
        assertEquals(1, JSONObject(String(Jev.requestBody("x", q, rules))).getJSONArray("actions").length())
        assertFalse(JSONObject(String(Jev.requestBody("x", q, JSONArray()))).has("actions"))
    }

    @Test fun parsesFiredActionsAndSkipped() {
        val a = Jev.parse(200, """{"model":"jev-1.14.0","answers":{},"actions_schema":"bridgit-actions/1",
            "actions":[{"id":"plug_in","type":"notice","title":"Plug in","reason":"charge = yes at 0.93 ≥ 0.80"}],
            "skipped":[{"id":"fine","reason":"charge = yes, not no"}]}""")
        assertEquals("bridgit-actions/1", a.actionsSchema)
        assertEquals("plug_in", a.actions.getJSONObject(0).getString("id"))
        assertEquals("fine", a.skipped.getJSONObject(0).getString("id"))
        assertEquals(0, Jev.parse(200, """{"answers":{}}""").actions.length())
    }

    private fun steps(vararg actions: JSONObject, schema: String = JevActions.SCHEMA) =
        JevActions.plan(schema, JSONArray().apply { actions.forEach { put(it) } })

    @Test fun knownActionsArePlanned() {
        val s = steps(JSONObject().put("id", "n").put("type", "notice").put("title", "Plug in").put("text", "Low").put("reason", "r"),
            JSONObject().put("id", "s").put("type", "speak").put("text", "Plug in"),
            JSONObject().put("id", "l").put("type", "log").put("message", "fine"))
        assertTrue(s.all { it.refused == null })
        assertEquals(mapOf("title" to "Plug in", "text" to "Low"), s[0].fields)
        assertEquals("r", s[0].reason)
    }

    @Test fun anythingOutsideTheClosedListIsIgnored() {
        val refused = steps(
            JSONObject().put("id", "a").put("type", "open_url").put("url", "https://x"),
            JSONObject().put("id", "b").put("type", "notice").put("title", "Tap https://evil.example"),
            JSONObject().put("id", "c").put("type", "notice").put("title", "ok").put("html", "<script>"),
            JSONObject().put("id", "d").put("type", "notice"),
            JSONObject().put("id", "e").put("type", "log").put("message", "x".repeat(501)),
            JSONObject().put("id", "f").put("type", "speak").put("text", "javascript:alert(1)"),
        ).map { it.refused }
        assertEquals(listOf("unknown action type open_url", "holds a link or script", "unexpected field html", "missing title", "message too long", "holds a link or script"), refused)
        assertEquals("unknown schema bridgit-actions/2",
            steps(JSONObject().put("id", "a").put("type", "log").put("message", "m"), schema = "bridgit-actions/2")[0].refused)
        assertEquals(emptyList<JevActions.Step>(), JevActions.plan(JevActions.SCHEMA, JSONArray()))
    }
    @Test fun retainedAudioIsSentOnlyWhenExplicitlyProvided() {
        val q=JSONObject("{sound:{type:'choice',instructions:'bark or other'}}")
        val plain=JSONObject(String(Jev.requestBody("context",q)))
        assertFalse(plain.has("audio"))
        val withAudio=JSONObject(String(Jev.requestBody("context",q,audio=listOf("data:audio/wav;base64,AAAA"))))
        assertEquals("data:audio/wav;base64,AAAA",withAudio.getJSONArray("audio").getString(0))
        assertFalse(withAudio.has("images"))
    }

}
