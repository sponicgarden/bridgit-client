package cc.bridgit.app.api

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Evaluator scenarios use the actual discovery implementation, not a copied search algorithm. */
class BuilderDiscoveryEvalTest {
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }
    private fun ids(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it).getString("id") }
    private val scenarios = JSONObject(File("../tools/builder-eval-discovery.json").readText()).getJSONArray("scenarios")

    @Test fun rankingAvailabilityAndVersionScenarios() {
        val responses = JSONObject()
        val measurements = JSONArray()
        for (i in 0 until scenarios.length()) {
            val task = scenarios.getJSONObject(i)
            val states = mutableMapOf<String, JSONObject>()
            task.optJSONArray("disabled")?.let { a -> for (n in 0 until a.length()) {
                states[a.getString(n)] = JSONObject().put("supported", true).put("enabled", false)
                    .put("granted", false).put("ready", false).put("blockers", JSONArray(listOf("disabled")))
            } }
            val installed = if (task.has("installed_revision_delta")) ApiChanges.CURRENT + task.getInt("installed_revision_delta") else null
            val started = System.nanoTime()
            val result = CapabilityCatalog.search(task.getString("query"), limit = 5,
                installedRevision = installed, states = states)
            val elapsed = (System.nanoTime() - started) / 1_000_000.0
            val found = result.getJSONArray("results")
            val expected = task.getJSONArray("expected_ids")
            val ranks = JSONObject()
            for (n in 0 until expected.length()) ranks.put(expected.getString(n), ids(found).indexOf(expected.getString(n)) + 1)
            measurements.put(JSONObject().put("task_id", task.getString("id")).put("query", task.getString("query"))
                .put("result_ids", JSONArray(ids(found))).put("expected_ranks", ranks)
                .put("retrieved_bytes", result.toString().toByteArray(Charsets.UTF_8).size)
                .put("search_latency_ms", elapsed).put("status", "measured"))
            task.getJSONArray("expected_ids").let { expected -> for (n in 0 until expected.length()) {
                assertTrue("${task.getString("id")}: top 5 missing ${expected.getString(n)}; got ${ids(found)}",
                    expected.getString(n) in ids(found))
            } }
            if (task.getString("id") == "zero-hit") assertEquals(0, found.length())
            if (task.getString("id") == "ambiguous") assertTrue("ambiguous intent must retain alternatives", found.length() > 1)
            if (task.getString("id") == "stale") {
                assertEquals(ApiChanges.CURRENT - 1, result.getInt("installed_api_revision"))
                assertEquals(ApiChanges.CURRENT, result.getInt("api_revision"))
            }
            if (task.getString("id") == "disabled") {
                val recipe = (0 until found.length()).map(found::getJSONObject).first { it.getString("id") == task.getJSONArray("expected_ids").getString(0) }
                assertEquals(false, recipe.getJSONObject("state").get("ready"))
                assertTrue(recipe.getJSONObject("state").getJSONArray("blockers").toString().contains("disabled"))
            } else if (installed == null) {
                for (n in 0 until found.length()) assertTrue("public source readiness must stay unknown",
                    found.getJSONObject(n).getJSONObject("state").isNull("ready"))
            }
            assertTrue(found.length() <= 5)
            responses.put(task.getString("id"), result)
        }
        val sources = listOf("app/src/main/java/cc/bridgit/app/api/CapabilityCatalog.kt",
            "app/src/main/java/cc/bridgit/app/api/Endpoints.kt", "app/src/main/java/cc/bridgit/app/api/ApiChanges.kt",
            "app/src/main/java/cc/bridgit/app/platform/AgentPlatform.kt", "app/src/main/java/cc/bridgit/app/platform/Capabilities.kt",
            "app/src/main/java/cc/bridgit/app/platform/ToolSchemas.kt", "tools/builder-eval-discovery.json", "site-content/content/builder/recipes.json",
            "app/src/test/java/cc/bridgit/app/api/BuilderDiscoveryEvalTest.kt")
        val hashes = JSONObject(); sources.forEach { hashes.put(it, hash(File("../$it"))) }
        val sha = ProcessBuilder("git", "rev-parse", "HEAD").directory(File("..")).start().inputStream.bufferedReader().readText().trim()
        val corpus = JSONObject().put("suite_version", "builder-discovery-1").put("source_sha", sha)
            .put("api_revision", ApiChanges.CURRENT).put("documentation_revision", CapabilityCatalog.DOCUMENTATION_REVISION)
            .put("source_hashes", hashes).put("responses", responses)
        // Ephemeral evaluator corpus: CI runs this real implementation before agent adapter invocation.
        File("../builder-eval-output").mkdirs()
        File("../builder-eval-output/discovery-responses.json").writeText(corpus.toString(2) + "\n")
        File("../builder-eval-output/ranking-report.json").writeText(JSONObject().put("suite_version", "builder-discovery-1")
            .put("status", "passed").put("kind", "deterministic-real-search")
            .put("source_sha", sha).put("measurements", measurements)
            .put("live_evaluation", "skipped; deterministic search is not a model/device test").toString(2) + "\n")
    }

    @Test fun everyPublicFamilyAndRecipeReferenceExists() {
        val entries = CapabilityCatalog.catalog().getJSONArray("entries")
        val all = (0 until entries.length()).map(entries::getJSONObject).associateBy { it.getString("id") }
        assertEquals(entries.length(), all.size)
        Endpoints.all.forEach { e ->
            val family = all.getValue("v1.${e.capability}").getJSONArray("operations")
            assertTrue("Missing ${e.method} ${e.fullPath}", (0 until family.length()).map(family::getJSONObject)
                .any { it.getString("method") == e.method && it.getString("path") == e.fullPath })
        }
        cc.bridgit.app.platform.Capabilities.all.forEach { assertTrue("Missing v2.${it.id}", all.containsKey("v2.${it.id}")) }
        val tools = mutableSetOf<String>()
        all.values.filter { it.getString("id").startsWith("v2.") }.forEach { entry ->
            val ops = entry.getJSONArray("operations")
            for (n in 0 until ops.length()) {
                val op = ops.getJSONObject(n)
                val tool = op.getString("tool")
                tools += tool
                val required = cc.bridgit.app.platform.ToolSchemas.forTool(tool).getJSONArray("required")
                assertEquals(required.toString(), op.getJSONArray("required_arguments").toString())
            }
        }
        assertEquals(cc.bridgit.app.platform.Commands.all, tools)
        all.values.forEach { entry ->
            val refs = entry.getJSONArray("capability_ids")
            for (n in 0 until refs.length()) assertTrue("Dangling ${refs.getString(n)}", all.containsKey(refs.getString(n)))
        }
    }

    @Test fun ideaGetsOneStartPlanWithLiveOwnerSteps() {
        val plant = CapabilityCatalog.search("watch my plant and tell me when it needs water").getJSONObject("start")
        assertEquals("room-change-watcher", plant.getString("recipe"))
        assertTrue(plant.getJSONArray("first_calls").length() >= 2)
        assertTrue(plant.getJSONArray("done_when").length() >= 2)
        assertTrue(plant.getJSONArray("owner_must").getString(0).contains("Watches, Camera, Interactions"))
        for ((idea, recipe) in listOf("scan my receipts" to "receipt-scanner", "count my dog's meals" to "pet-monitor",
            "remind me every morning" to "daily-briefing-panel", "dictate a shopping list" to "voice-notebook",
            "show my smart plugs" to "external-device-dashboard", "count how often the dog is licking itself" to "situation-counter",
            "tell me when the pot is boiling over on the stove" to "situation-counter"))
            assertEquals(idea, recipe, CapabilityCatalog.search(idea).getJSONObject("start").getString("recipe"))
        val camera = JSONObject().put("ready", false).put("blockers", JSONArray(listOf("feature_disabled")))
        val ok = JSONObject().put("ready", true).put("blockers", JSONArray())
        val paired = CapabilityCatalog.search("is a parcel at the door", states = mapOf("v1.camera" to camera,
            "v1.watches" to ok, "v1.interactions" to ok)).getJSONObject("start")
        assertEquals("[\"Turn on Camera\"]", paired.getJSONArray("owner_must").toString())
        assertTrue(CapabilityCatalog.search("quuxzyxnonexistent").isNull("start"))
        // A detail reply keeps the named recipe's plan (spec-setup-prompt F22).
        val detail = CapabilityCatalog.search(id = "pet-monitor", full = true).getJSONObject("start")
        assertEquals("pet-monitor", detail.getString("recipe")); assertEquals("Exact stable ID", detail.getString("why"))
        assertTrue(CapabilityCatalog.search(id = "v1.camera").isNull("start"))
        CapabilityCatalog.entries.filter { it.kind == "recipe" }.forEach { r ->
            assertEquals(r.id, r.capabilityIds.size, r.start!!.getJSONArray("owner_turns_on").length())
            assertTrue(r.id, CapabilityCatalog.search(r.id).getJSONObject("start").getJSONArray("requests").length() >= 1)
        }
        // The plant plan hands over a complete, valid watch body: a Clef question, a 90-day tally and a notice rule.
        val asks = plant.getJSONArray("requests")
        val ask = (0 until asks.length()).map { asks.getJSONObject(it) }.single { it.getString("example") == "ask" }
            .getJSONObject("request").getJSONObject("body")
        val spec = cc.bridgit.app.watch.WatchRules.parse(ask)
        assertEquals("clef", spec.model)
        assertNotNull(spec.tally)
        val page = CapabilityCatalog.start()
        assertTrue(page.contains("**You decide:**") && page.contains("**Done when:**") && page.contains("## If something isn't ready"))
        CapabilityCatalog.entries.filter { it.kind == "recipe" }.forEach { assertTrue(it.id, page.contains("recipes/${it.id}/recipe.json")) }
    }

    @Test fun overviewIsBoundedAndExactIdDetailRemainsSmall() {
        val start = CapabilityCatalog.start()
        assertTrue("Builder start exceeds 8 KiB; use progressive detail", start.toByteArray(Charsets.UTF_8).size <= 8192)
        assertTrue("Overview should have a compact word count", start.split(Regex("\\s+")).size <= 1200)
        val result = CapabilityCatalog.search(id = "receipt-scanner", full = true)
        assertEquals(listOf("receipt-scanner"), ids(result.getJSONArray("results")))
        assertEquals(0, CapabilityCatalog.search(id = "not-a-real-id").getJSONArray("results").length())
    }
}
