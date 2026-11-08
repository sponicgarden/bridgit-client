package cc.bridgit.app.api

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** API-15: tool generation from Endpoints.all and the JSON-RPC parsing of POST /v1/mcp. */
class McpTest {
    private val names = McpTools.specs.map { McpTools.name(it) }

    @Test fun oversizedMediaUsesAnAuthenticatedHttpLink() {
        val uri = McpTools.downloadUri("http://pixel:8734/v1", Endpoints.GALLERY_PHOTO, mapOf("id" to "123"), mapOf("raw" to "1"))!!
        assertEquals("http://pixel:8734/v1/gallery/photo/123?raw=1", uri)
        val result = McpTools.result(200, "video/mp4", ByteArray(McpTools.MAX_EMBEDDED_BYTES + 1), downloadUri = uri)
        val content = result.getJSONArray("content").getJSONObject(0)
        assertEquals("resource_link", content.getString("type"))
        assertEquals(uri, content.getString("uri"))
        assertFalse(content.has("blob"))
        assertNull(McpTools.downloadUri("http://pixel/v1", Endpoints.SNAPSHOT, emptyMap(), emptyMap()))
    }
    @Test fun largeJsonAttachmentReturnsDownloadLinkInsteadOfDuplicatingTheDataset() {
        val result = McpTools.result(200, "application/json", ByteArray(129 * 1024),
            mapOf("Content-Disposition" to "attachment; filename=applet-data.json"), "http://phone/v1/applets/installed/ai1/data/export")
        assertEquals("resource_link", result.getJSONArray("content").getJSONObject(0).getString("type"))
        assertFalse(result.has("structuredContent"))
    }
    @Test fun binaryResultsAreRetrievableEmbeddedResources() {
        for (mime in listOf("video/mp4", "application/pdf", "application/octet-stream")) {
            val bytes = byteArrayOf(0, 1, 2, -1, -128)
            val result = McpTools.result(200, mime, bytes).getJSONArray("content").getJSONObject(0)
            assertEquals("resource", result.getString("type"))
            val resource = result.getJSONObject("resource")
            assertEquals(mime, resource.getString("mimeType"))
            assertTrue(resource.getString("uri").startsWith("bridgit://result/"))
            org.junit.Assert.assertArrayEquals(bytes, java.util.Base64.getDecoder().decode(resource.getString("blob")))
        }
    }
    @Test fun namesAreUniqueSnakeCaseAndStable() {
        assertEquals(names.toString(), names.size, names.toSet().size)
        names.forEach { assertTrue(it, Regex("^[a-z][a-z0-9_]{0,63}$").matches(it)) }
        assertEquals(Endpoints.SNAPSHOT, McpTools.byName("camera_snapshot"))
        assertEquals(Endpoints.GALLERY_PHOTO, McpTools.byName("gallery_photo"))
        assertEquals(Endpoints.INTERACTION_GET, McpTools.byName("interactions_get"))
        assertEquals(Endpoints.INTERACTION_CANCEL, McpTools.byName("interactions_cancel"))
        assertEquals(Endpoints.SPEAKER_PLAY, McpTools.byName("speaker_play"))
        assertEquals(Endpoints.LOG_NOTE, McpTools.byName("log_note"))
    }

    @Test fun cardReminderIsDiscoverableAndSupportsSafeRetries() {
        val spec = Endpoints.INTERACTION_RENOTIFY
        assertTrue(spec in Endpoints.all)
        assertEquals("interactions", spec.capability)
        assertTrue(spec.auth)
        assertTrue(spec.idempotent)
        assertEquals(spec, McpTools.byName("interactions_renotify"))
        val route = McpTools.route(spec, JSONObject().put("id", 12).put("idempotency_key", "reminder-123"))
        assertEquals("12", route.pathParams["id"])
        assertEquals("reminder-123", route.idempotencyKey)
    }

    @Test fun excludedEndpointsAreNotTools() {
        listOf(Endpoints.MCP, Endpoints.PAIR, Endpoints.HELPER, Endpoints.HELPER_LEGACY, Endpoints.OPENAPI,
            cc.bridgit.app.satellite.SatelliteEndpoints.CLAIM, cc.bridgit.app.satellite.SatelliteEndpoints.MANIFEST, cc.bridgit.app.link.LinkEndpoints.CLAIM).forEach { e ->
            assertFalse(e.fullPath, McpTools.specs.any { it === e })
        }
        assertEquals(Endpoints.all.size - 8, McpTools.specs.size)
    }

    @Test fun schemasHaveEveryParamAndRequired() {
        for (e in McpTools.specs) {
            val s = McpTools.inputSchema(e)
            assertEquals("object", s.getString("type"))
            val props = s.getJSONObject("properties")
            val req = s.getJSONArray("required").let { a -> (0 until a.length()).map { a.getString(it) } }
            McpTools.pathParams(e).forEach { assertTrue("${e.path} $it", props.has(it) && it in req) }
            (e.query + e.body).filter { it.name != "raw" }.forEach { p ->
                assertTrue("${e.path} ${p.name}", props.has(p.name))
                assertEquals("${e.path} ${p.name}", p.required, p.name in req)
                assertEquals(McpTools.jsonType(p.type).toString(), props.getJSONObject(p.name).get("type").toString())
                p.enum?.let { assertEquals(it.size, props.getJSONObject(p.name).getJSONArray("enum").length()) }
            }
            assertFalse(e.path, props.has("raw"))
            assertEquals(e.path, e.idempotent, props.has(McpTools.IDEMPOTENCY_ARG))
            req.forEach { assertTrue("${e.path} required $it", props.has(it)) }
            // A query and a body param with the same name would make one argument ambiguous.
            assertEquals(e.path, (e.query + e.body).map { it.name }.distinct().size, (e.query + e.body).size)
        }
        assertTrue(McpTools.inputSchema(Endpoints.GALLERY_PHOTO).getJSONArray("required").toList().contains("id"))
    }

    @Test fun annotations() {
        assertTrue(McpTools.tool(Endpoints.HEALTH).getJSONObject("annotations").getBoolean("readOnlyHint"))
        assertTrue(McpTools.tool(Endpoints.INTERACTION_CANCEL).getJSONObject("annotations").getBoolean("destructiveHint"))
        assertFalse(McpTools.tool(Endpoints.SNAPSHOT).getJSONObject("annotations").getBoolean("destructiveHint"))
        assertTrue(McpTools.tool(Endpoints.SNAPSHOT, switchedOff = true).getString("description").contains("Switched off"))
    }

    @Test fun routingSplitsArguments() {
        val r = McpTools.route(Endpoints.GALLERY_PHOTO, JSONObject().put("id", 87567).put("max_edge_px", 1024.0).put("size", "thumb"))
        assertEquals(mapOf("id" to "87567"), r.pathParams)
        assertEquals("1024", r.queries["max_edge_px"])
        assertEquals("thumb", r.queries["size"])
        val s = McpTools.route(Endpoints.SNAPSHOT, JSONObject().put("camera", "front").put("zoom", 2).put("idempotency_key", "abcdefgh-1"))
        assertEquals("front", s.body.getString("camera"))
        assertEquals(2, s.body.getInt("zoom"))
        assertEquals("abcdefgh-1", s.idempotencyKey)
        assertTrue(s.queries.isEmpty())
        val loc = McpTools.route(Endpoints.LOCATION, JSONObject().put("fresh", false))
        assertEquals("false", loc.queries["fresh"])
        val play = McpTools.route(Endpoints.SPEAKER_PLAY, JSONObject().put("url", "https://x.test/a.mp3").put("urgent", true))
        assertEquals("true", play.queries["urgent"])
        assertEquals("https://x.test/a.mp3", play.body.getString("url"))
    }

    @Test fun routingRejectsUnknownAndMissing() {
        expectArgError { McpTools.route(Endpoints.GALLERY_PHOTO, JSONObject()) }
        expectArgError { McpTools.route(Endpoints.MIC, JSONObject()) }
        expectArgError { McpTools.route(Endpoints.HEALTH, JSONObject().put("nope", 1)) }
        expectArgError { McpTools.route(Endpoints.GALLERY_PHOTO, JSONObject().put("id", "1").put("raw", 1)) }
        expectArgError { McpTools.route(Endpoints.HEALTH, JSONObject().put("idempotency_key", "abcdefgh")) }
    }

    private fun expectArgError(f: () -> Unit) {
        try { f(); fail("expected ArgError") } catch (_: McpTools.ArgError) {}
    }

    @Test fun parseRequestsNotificationsAndErrors() {
        val req = McpProtocol.parse("""{"jsonrpc":"2.0","id":7,"method":"tools/list"}""")
        assertTrue(req is McpProtocol.Message.Request)
        req as McpProtocol.Message.Request
        assertEquals(7, req.id); assertEquals("tools/list", req.method); assertEquals(0, req.params.length())
        val sid = McpProtocol.parse("""{"jsonrpc":"2.0","id":"a1","method":"ping","params":{}}""") as McpProtocol.Message.Request
        assertEquals("a1", sid.id)
        assertTrue(McpProtocol.parse("""{"jsonrpc":"2.0","method":"notifications/initialized"}""") is McpProtocol.Message.Notification)

        fun code(s: String) = (McpProtocol.parse(s) as McpProtocol.Message.Invalid).code
        assertEquals(McpProtocol.PARSE_ERROR, code("{nope"))
        assertEquals(McpProtocol.INVALID_REQUEST, code("""[{"jsonrpc":"2.0","id":1,"method":"ping"}]"""))
        assertEquals(McpProtocol.INVALID_REQUEST, code("""{"id":1,"method":"ping"}"""))
        assertEquals(McpProtocol.INVALID_REQUEST, code("""{"jsonrpc":"2.0","id":1}"""))
        assertEquals(McpProtocol.INVALID_REQUEST, code("""{"jsonrpc":"2.0","id":null,"method":"ping"}"""))
        assertEquals(McpProtocol.INVALID_PARAMS, code("""{"jsonrpc":"2.0","id":1,"method":"ping","params":[1]}"""))
        assertEquals(1, (McpProtocol.parse("""{"id":1,"method":"ping"}""") as McpProtocol.Message.Invalid).id)
    }

    @Test fun envelopesAndVersion() {
        val e = McpProtocol.error(3, McpProtocol.METHOD_NOT_FOUND, "Method not found: x")
        assertEquals(setOf("jsonrpc", "id", "error"), e.keySet())
        assertEquals(-32601, e.getJSONObject("error").getInt("code"))
        assertEquals(setOf("jsonrpc", "id", "result"), McpProtocol.result(3, JSONObject()).keySet())
        assertEquals("2025-03-26", McpProtocol.negotiate("2025-03-26"))
        assertEquals(McpProtocol.LATEST, McpProtocol.negotiate("1999-01-01"))
        assertEquals(McpProtocol.LATEST, McpProtocol.negotiate(null))
        val init = McpProtocol.initializeResult("2025-06-18", "1.5.2", "Claude")
        assertFalse(init.getJSONObject("capabilities").getJSONObject("tools").getBoolean("listChanged"))
        assertEquals("bridgit", init.getJSONObject("serverInfo").getString("name"))
    }

    @Test fun resultsLiftMediaAndFlagErrors() {
        val jpeg = "/9j/4AAQSkZJRg=="
        val ok = McpTools.result(200, "application/json",
            JSONObject().put("width_px", 10).put("format", "jpeg").put("data_base64", jpeg).toString().toByteArray())
        val c = ok.getJSONArray("content")
        assertEquals("text", c.getJSONObject(0).getString("type"))
        assertFalse(c.getJSONObject(0).getString("text").contains(jpeg))
        assertEquals("image", c.getJSONObject(1).getString("type"))
        assertEquals(jpeg, c.getJSONObject(1).getString("data"))
        assertEquals("image/jpeg", c.getJSONObject(1).getString("mimeType"))
        assertEquals(10, ok.getJSONObject("structuredContent").getInt("width_px"))
        assertFalse(ok.has("isError"))

        val audio = McpTools.result(200, "application/json", JSONObject().put("format", "m4a").put("data_base64", "AAAAGGZ0eXA=").toString().toByteArray())
        assertEquals("audio", audio.getJSONArray("content").getJSONObject(1).getString("type"))
        assertEquals("audio/mp4", audio.getJSONArray("content").getJSONObject(1).getString("mimeType"))

        val err = McpTools.result(403, "application/json", """{"error":"forbidden","message":"no"}""".toByteArray())
        assertTrue(err.getBoolean("isError"))
        assertTrue(err.getJSONArray("content").getJSONObject(0).getString("text").contains("forbidden"))

        val raw = McpTools.result(200, "image/png", byteArrayOf(1, 2, 3), BrandHeaders.withLegacy(mapOf("X-Bridgit-Width" to "3")))
        assertEquals("image", raw.getJSONArray("content").getJSONObject(1).getString("type"))
        // The legacy X-Bridget- copy doesn't leak into the details as a second key.
        val meta = JSONObject(raw.getJSONArray("content").getJSONObject(0).getString("text"))
        assertEquals("3", meta.getString("width")); assertFalse(meta.keys().asSequence().any { it.contains("bridget") })
        val md = McpTools.result(200, "text/markdown; charset=utf-8", "# hi".toByteArray())
        assertEquals("# hi", md.getJSONArray("content").getJSONObject(0).getString("text"))
        assertNull(md.optJSONObject("structuredContent"))
    }

    private fun org.json.JSONArray.toList() = (0 until length()).map { get(it) }
}
