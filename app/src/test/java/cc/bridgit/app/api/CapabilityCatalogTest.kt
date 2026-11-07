package cc.bridgit.app.api

import cc.bridgit.app.platform.Capabilities
import cc.bridgit.app.platform.Commands
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CapabilityCatalogTest {
    private fun ids(query: String) = CapabilityCatalog.search(query, limit = 5).getJSONArray("results").let { a ->
        (0 until a.length()).map { a.getJSONObject(it).getString("id") }
    }
    @Test fun everyPublicFamilyAndOperationHasProvenance() {
        val entries = CapabilityCatalog.entries
        assertEquals("decide", entries.single { it.id == "v1.judgment" }.category)
        assertEquals("act", entries.single { it.id == "v1.flashlight" }.category)
        assertEquals(entries.size, entries.map { it.id }.toSet().size)
        for (e in Endpoints.all) assertTrue(e.fullPath, entries.single { it.id == "v1.${e.capability}" }.operations.any { it.getString("path") == e.fullPath && it.getString("method") == e.method })
        assertEquals(Capabilities.all.map { "v2.${it.id}" }.toSet(), entries.filter { it.id.startsWith("v2.") }.map { it.id }.toSet())
        val tools = entries.filter { it.id.startsWith("v2.") }.flatMap { it.operations }.map { it.getString("tool") }.toSet()
        assertEquals(Commands.reads + Commands.mutations + Commands.immediate, tools)
        entries.filter { it.kind == "recipe" }.forEach { recipe ->
            assertTrue(recipe.id, recipe.capabilityIds.all { id -> entries.any { it.id == id && it.kind == "capability" } })
        }
    }
    @Test fun publicLeavesReuseEveryExactSchemaAndStayCompact() {
        val files = CapabilityCatalog.publicFiles()
        assertTrue(files.getValue("learn/catalog-index.json").toByteArray().size <= 24 * 1024)
        val index = CapabilityCatalog.compactIndex().getJSONArray("entries")
        assertEquals(CapabilityCatalog.entries.size, index.length())
        CapabilityCatalog.entries.filter { it.kind == "capability" }.forEach { e ->
            val path = CapabilityCatalog.publicDetailUrl(e).removePrefix("https://bridgit.cc/")
            assertEquals(e.id, JSONObject(files.getValue(path)).getString("id"))
        }
        val records = Learn.endpoints().getJSONArray("endpoints")
        Endpoints.all.forEachIndexed { i, e ->
            val record = JSONObject(files.getValue(CapabilityCatalog.operationUrl(e).removePrefix("https://bridgit.cc/"))).getJSONObject("operation")
            if (e in McpTools.specs) assertTrue(McpTools.tool(e, false).getJSONObject("inputSchema").similar(record.getJSONObject("mcp_input_schema")))
            record.remove("mcp_input_schema")
            assertTrue(e.fullPath, records.getJSONObject(i).similar(record))
        }
        (Commands.reads + Commands.mutations).forEach { name ->
            val record = JSONObject(files.getValue("learn/operations/v2-$name.json")).getJSONObject("operation")
            assertTrue(cc.bridgit.app.platform.ToolSchemas.forTool(name).similar(record.getJSONObject("mcp_input_schema")))
            assertEquals(name in Commands.mutations, record.getBoolean("idempotency_required"))
            val http = record.getJSONObject("http")
            val args = http.getJSONObject("arguments_schema")
            assertFalse(args.getJSONObject("properties").has("idempotency_key"))
            assertFalse(args.getJSONArray("required").toString().contains("idempotency_key"))
            assertTrue(args.similar(http.getJSONObject("request_schema").getJSONObject("properties").getJSONObject("arguments")))
            assertEquals(name, http.getJSONObject("request_schema").getJSONObject("properties").getJSONObject("tool").getJSONArray("enum").getString(0))
            assertEquals(name in Commands.mutations, http.getJSONObject("headers").has("Idempotency-Key"))
        }
        val interval = cc.bridgit.app.watch.WatchRules.INTERVAL_DESCRIPTION
        assertTrue(Learn.decisions().contains(interval))
        assertTrue(Endpoints.all.single { it.method == "POST" && it.path == "/watches" }.body.single { it.name == "interval_s" }.description.startsWith(interval))
        assertEquals(Endpoints.all.size, Endpoints.all.map { CapabilityCatalog.operationUrl(it) }.toSet().size)
    }
    @Test fun compactIndexHasWholeHandWrittenPurposes() {
        val capabilities = CapabilityCatalog.entries.filter { it.kind == "capability" }.map { it.id }.toSet()
        assertEquals("every capability needs a hand-written purpose and keywords in CapabilityCatalog.blurbs, and no stale ones",
            capabilities, CapabilityCatalog.blurbs.keys)
        val index = CapabilityCatalog.compactIndex().getJSONArray("entries")
        for (i in 0 until index.length()) {
            val e = index.getJSONObject(i)
            val purpose = e.getString("purpose")
            assertTrue(e.getString("id"), purpose.length in 20..90 && purpose != e.getString("title"))
            assertTrue(e.getString("id"), e.getJSONArray("keywords").length() in 3..6)
        }
    }
    @Test fun naturalLanguageIntentFindsUsefulSmallCombinations() {
        assertTrue(ids("help me know whether my dog has eaten").contains("pet-monitor"))
        assertTrue(ids("show an interactive dashboard").any { it in setOf("daily-briefing-panel", "v1.interactions", "v2.interactions") })
        assertTrue(ids("use my external camera").contains("external-device-dashboard"))
        assertTrue(ids("scan receipt").contains("receipt-scanner"))
        assertTrue(ids("zxqv nonexistentzz").isEmpty())
        assertEquals(ids("scan receipt"), ids("scan receipt"))
    }
    @Test fun boundedResponsesAndUnknownReadinessStayHonest() {
        val public = CapabilityCatalog.catalog()
        assertTrue(public.isNull("installed_api_revision"))
        public.getJSONArray("entries").let { a -> (0 until a.length()).forEach { i ->
            val state = a.getJSONObject(i).getJSONObject("state")
            assertTrue(state.isNull("ready")); assertTrue(state.isNull("granted"))
        } }
        val result = CapabilityCatalog.search(id = "receipt-scanner", full = true, installedRevision = ApiChanges.CURRENT,
            states = mapOf("v1.ocr" to JSONObject("{supported:true,enabled:false,granted:true,ready:false,blockers:[feature_disabled]}")))
            .getJSONArray("results").getJSONObject(0)
        assertFalse(result.getJSONObject("state").getBoolean("ready"))
        assertTrue(result.getJSONArray("operations").length() > 0)
        assertTrue(result.getString("examples_url").endsWith("/recipes/receipt-scanner/recipe.json"))
        assertEquals(0, CapabilityCatalog.search(id = "unknown").getJSONArray("results").length())
        assertTrue(CapabilityCatalog.search(limit = 20, full = true).toString().toByteArray().size <= 96 * 1024)
        for (call in listOf<() -> Unit>({ CapabilityCatalog.search("x".repeat(257)) }, { CapabilityCatalog.search(limit = 21) })) {
            try { call(); fail("invalid limits accepted") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun onlyAllowlistedExplicitReportsProduceAnonymousKeys() {
        assertEquals(listOf("discovery:v1:selected:recipe:receipt-scanner"), CapabilityCatalog.reportKeys(JSONObject("{event:selection,id:'receipt-scanner'}")))
        assertEquals(listOf("discovery:v1:reported:outcome:unverified"), CapabilityCatalog.reportKeys(JSONObject("{event:outcome,value:unverified}")))
        for (body in listOf("{event:verified,value:works}", "{event:attempt,id:'v1.camera'}", "{event:selection,id:invented}",
                "{event:relevance,value:helpful,query:'private text'}", "{event:outcome,value:works,id:'pet-monitor'}", "{event:selection,id:17}")) {
            try { CapabilityCatalog.reportKeys(JSONObject(body)); fail(body) } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun supportEvidenceAndBlockersAreBounded() {
        for (reason in listOf("no_hardware", "android_version")) {
            val state = CapabilityCatalog.moduleState(CapInfo("health", "Health", "health", true, false, reason), true)
            assertFalse(state.getBoolean("supported")); assertFalse(state.getBoolean("ready"))
            assertEquals(reason, state.getJSONArray("blockers").getString(0))
        }
        val unknown = CapabilityCatalog.moduleState(CapInfo("camera", "Camera", "camera", true, false, "private inventory detail"), true)
        assertTrue(unknown.isNull("supported")); assertFalse(unknown.toString().contains("private inventory"))
        val denied = CapabilityCatalog.moduleState(CapInfo("camera", "Camera", "camera", true, true, null), false)
        assertTrue(denied.getBoolean("supported")); assertFalse(denied.getBoolean("ready"))
    }
    @Test fun reportAdmissionAndBodyBoundsApplyToBothTransports() {
        var now = 1_000L
        val limiter = RateLimiter({ now })
        repeat(30) { CapabilityCatalog.admit(limiter, "a", true) }
        try { CapabilityCatalog.admit(limiter, "a", true); fail("report limit not enforced") } catch (e: ApiError) { assertEquals("rate_limited", e.code) }
        CapabilityCatalog.admit(limiter, "b", true)
        CapabilityCatalog.admit(limiter, "a", false)
        now += 60_001
        CapabilityCatalog.admit(limiter, "a", true)
        try { CapabilityCatalog.reportKeys(JSONObject().put("event", "selection").put("id", "x".repeat(4096))); fail("oversized MCP report accepted") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun intentAndReportsAreRedactedBeforeHttpAndMcpAudit() {
        assertEquals("[redacted]", Endpoints.DISCOVER.loggedParam("q", "private owner intent"))
        assertEquals("full", Endpoints.DISCOVER.loggedParam("detail", "full"))
        assertEquals("[redacted]", Endpoints.DISCOVERY_REPORT.loggedParam("unexpected_private_field", "private evidence"))
    }
}
