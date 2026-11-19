package cc.bridgit.app.satellite

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

/** Agent calls forwarded to a linked Bridgit over the relay (A0335) and who they are for (A0304). Pure. */
class LinkRelayForwardTest {
    private val P = SatellitePolicy

    @Test fun forwardedJobCarriesWhoItIsForAndNeverATarget() {
        val headers = listOf(
            P.ON_BEHALF_HEADER to P.onBehalfOf("agt_muse01", "Muse"),
            P.ON_BEHALF_RUNTIME_HEADER to P.onBehalfRuntime("Meta Muse", "Muse Wonder")!!,
            "Accept" to "application/json",
            P.TARGET_HEADER to "pixel",
            "Authorization" to "Bearer brg_secret",
            "X-Bridgit-Hub-Name" to "Pixel")
        val req = LinkRelayClient.forwardRequest("POST", "/v1/interactions", listOf("target" to "iphone", "wait" to "5"), headers,
            JSONObject().put("text", "hi"), P.forwardedIdempotencyKey("agt_muse01", "key-12345678"))
        assertEquals("POST", req.getString("method"))
        assertEquals("/v1/interactions", req.getString("path"))
        assertEquals("5", req.getJSONObject("query").getString("wait"))
        assertFalse(req.getJSONObject("query").has("target"))
        val h = req.getJSONObject("headers")
        assertEquals(setOf(P.ON_BEHALF_HEADER, P.ON_BEHALF_RUNTIME_HEADER, "Accept"), h.keys().asSequence().toSet())
        assertEquals("agt_muse01,Muse", h.getString(P.ON_BEHALF_HEADER))
        assertFalse(req.toString().contains("brg_secret"))
        assertEquals("hi", req.getJSONObject("body").getString("text"))
        assertEquals("hagtmuse01-key-12345678", req.getString("idempotency_key"))
    }

    @Test fun bodylessCallHasNoBodyOrQuery() {
        val req = LinkRelayClient.forwardRequest("GET", "/v1/health", listOf("target" to "iphone"), emptyList(), null, null)
        assertFalse(req.has("body")); assertFalse(req.has("query")); assertFalse(req.has("headers")); assertFalse(req.has("idempotency_key"))
    }

    @Test fun answerComesBackUnchanged() {
        val photo = byteArrayOf(1, 2, 3, 0, -1)
        val text = JSONObject().put("response", JSONObject().put("status", 201).put("content_type", "image/jpeg")
            .put("body_base64", Base64.getEncoder().encodeToString(photo)).put("headers", JSONObject().put("X-Request-Id", "req_1"))).toString()
        val a = LinkRelayClient.decodeAnswer(200, text)
        assertEquals(201, a.status); assertEquals("image/jpeg", a.contentType)
        assertTrue(photo.contentEquals(a.bytes)); assertEquals("req_1", a.headers["X-Request-Id"])
    }

    @Test fun targetErrorsPassThroughAndRelayRefusalsThrow() {
        val err = JSONObject().put("error", "link_revoked").put("scope", "direction").toString()
        val a = LinkRelayClient.decodeAnswer(200, JSONObject().put("response", JSONObject().put("status", 403)
            .put("body_base64", Base64.getEncoder().encodeToString(err.toByteArray()))).toString())
        assertEquals(403, a.status); assertEquals("link_revoked", JSONObject(String(a.bytes)).getString("error"))
        for ((code, want) in listOf(503 to "satellite_offline", 401 to "unauthorized", 429 to "rate_limited")) {
            try { LinkRelayClient.decodeAnswer(code, JSONObject().put("error", "x").toString()); fail("$code") }
            catch (e: SatelliteClient.Failure) { assertEquals(want, e.code) }
        }
    }

    @Test fun runtimeHeaderRoundTripsAndDropsBadValues() {
        val v = P.onBehalfRuntime("Meta Muse; v2, beta", "Muse Wonder")!!
        assertEquals(SatellitePolicy.Runtime("Meta Muse; v2, beta", "Muse Wonder"), P.parseOnBehalfRuntime(v))
        assertEquals(SatellitePolicy.Runtime("Meta Muse", null), P.parseOnBehalfRuntime(P.onBehalfRuntime("Meta Muse", null)))
        assertNull(P.onBehalfRuntime(null, "Muse Wonder"))
        assertNull(P.onBehalfRuntime("x".repeat(61), null))
        assertNull(P.parseOnBehalfRuntime("harness=Muse"))
        assertNull(P.parseOnBehalfRuntime("model=%0Aevil"))
        assertNull(P.parseOnBehalfRuntime("garbage"))
        assertEquals("Meta Muse", P.parseOnBehalfRuntime("model=Meta+Muse;harness=" + "h".repeat(41))!!.label)
        // The id/name header is untouched by the new one.
        assertEquals("agt_1" to "Muse", P.parseOnBehalfOf(P.onBehalfOf("agt_1", "Muse")))
    }

    @Test fun attributionSentenceNamesAgentModelAndPath() {
        assertEquals("Muse (Meta Muse, Muse Wonder) via Pixel", P.attributed("Muse", SatellitePolicy.Runtime("Meta Muse", "Muse Wonder"), "Pixel"))
        assertEquals("Muse via Pixel", P.attributed("Muse", null, "Pixel"))
    }

    @Test fun runtimeHeaderIsNeverForwardedFromTheCaller() {
        assertTrue("x-bridgit-on-behalf-runtime" in P.DROPPED_REQUEST_HEADERS)
    }
}
