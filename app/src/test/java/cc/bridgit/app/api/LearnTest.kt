package cc.bridgit.app.api

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The server copy of the learning material (bridgit.cc/learn/, bridgit.cc/llms.txt) matches the app's catalogs.
 * A stale committed copy fails here and is rewritten in place; tools/refresh-learn.sh rewrites it without failing.
 */
class LearnTest {
    private val web = File("../site-content/public")
    private val samplesFile = File(web, "samples/samples.json").readText()
    private val helperSource = File("../tools/bridgit").readText()
    private val files = Learn.files(File(web, "demo/SKILL.md").readText(), samplesFile, helperSource)

    private fun same(path: String, a: String, b: String) =
        if (path.endsWith(".json")) JSONObject(a).similar(JSONObject(b)) else a == b

    @Test fun committedCopyIsCurrent() {
        val stale = files.filter { (path, text) -> File(web, path).let { !it.exists() || !same(path, it.readText(), text) } }
        stale.forEach { (path, text) -> File(web, path).apply { parentFile.mkdirs() }.writeText(text) }
        if (System.getProperty("bridgit.learn.refresh") == "1") return
        assertEquals("site-content/public/learn and llms.txt were stale; they are rewritten now, commit them (or run tools/refresh-learn.sh)",
            emptyList<String>(), stale.keys.toList())
    }

    @Test fun discoveryRevisionTracksCatalogChanges() {
        val catalog = files.getValue("learn/catalog.json")
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(catalog.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val target = File(web, "learn/catalog-contract.json")
        val prior = if (target.exists()) JSONObject(target.readText()) else null
        if (prior != null && prior.optString("sha256") != hash) {
            assertTrue("Catalog changed: bump CapabilityCatalog.DOCUMENTATION_REVISION before refreshing",
                CapabilityCatalog.DOCUMENTATION_REVISION > prior.getInt("documentation_revision"))
        }
        val next = JSONObject().put("documentation_revision", CapabilityCatalog.DOCUMENTATION_REVISION).put("sha256", hash)
        if (System.getProperty("bridgit.learn.refresh") == "1") target.writeText(next.toString(1) + "\n")
        else assertTrue("Run tools/refresh-learn.sh to publish the discovery contract fingerprint", prior?.similar(next) == true)
    }

    @Test fun publicPairingExamplesUseTheActualRenderer() {
        for ((path, connection) in listOf("learn/pairing-direct.txt" to cc.bridgit.app.core.Connection.DIRECT,
                "learn/pairing-userspace.txt" to cc.bridgit.app.core.Connection.USERSPACE)) {
            val actual = SetupPrompt.build("example-agent", "<KEY_OMITTED>", connection,
                Address("phone.example.invalid", null, 8734), "example Android device",
                listOf(CapInfo("camera", "Camera", "camera", true, true, null),
                    CapInfo("gallery", "Gallery", "gallery", true, true, null)),
                helperSource, "<CALLBACK_SECRET_OMITTED>", device = SetupPrompt.EXAMPLE_DEVICE)
            assertEquals(actual, files.getValue(path))
            assertTrue(files.getValue("learn/introduction.txt").contains(files.getValue("learn/pairing-direct.txt")))
        }
    }

    @Test fun promptPublicationHasCurrentInputsAndOutputs() {
        // Website builds verify these hashes without needing an Android toolchain. Any renderer,
        // helper, fixture, or exported-text change requires running this real-renderer test again.
        fun hash(text: String) = java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val sources = listOf("app/build.gradle.kts", "app/src/main/java/cc/bridgit/app/api/SelfDescription.kt",
            "app/src/main/java/cc/bridgit/app/api/Learn.kt", "app/src/main/java/cc/bridgit/app/api/Playbook.kt",
            "app/src/main/java/cc/bridgit/app/adb/AdbSetupHelp.kt", "tools/bridgit",
            "app/src/test/java/cc/bridgit/app/api/LearnTest.kt")
        val inputs = JSONObject(); sources.forEach { inputs.put(it, hash(File("../$it").readText())) }
        val outputs = JSONObject(); files.filterKeys { it.startsWith("learn/pairing-") || it in listOf("learn/introduction.txt", "learn/evaluate.md") }
            .forEach { (path, text) -> outputs.put(path, hash(text)) }
        val expected = JSONObject().put("generator", "SetupPrompt.build (private distribution)").put("inputs", inputs).put("outputs", outputs)
        val target = File(web, "learn/prompt-manifest.json")
        val current = target.exists() && JSONObject(target.readText()).similar(expected)
        if (!current) target.writeText(expected.toString(1) + "\n")
        if (System.getProperty("bridgit.learn.refresh") != "1") assertTrue("Prompt inputs/outputs changed: run tools/refresh-learn.sh and commit the generated files", current)
    }

    @Test fun indexMatchesTheCatalogs() {
        val index = JSONObject(files.getValue("learn/index.json"))
        assertEquals(ApiChanges.CURRENT, index.getInt("api_revision"))
        assertEquals(Endpoints.all.size, index.getInt("endpoint_count"))
        assertEquals(ErrorCatalog.all.size, index.getInt("error_codes"))
        val samples = JSONObject(samplesFile).getJSONArray("samples").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        val kinds = index.getJSONArray("sample_kinds").let { a -> (0 until a.length()).map { a.getString(it) } }
        assertEquals(samples.map { it.getString("kind") }.distinct(), kinds)
        assertEquals(setOf("notice", "question", "html", "text", "pieces"), kinds.toSet())
        assertEquals(samples.size, index.getJSONArray("sample_ids").length())
        assertTrue(index.getString("file_a_bug_without_a_phone").contains("${Learn.FEEDBACK_BACKEND}/v1/agents/register"))
    }

    @Test fun everyEndpointErrorAndRevisionIsServed() {
        val guide = files.getValue("learn/guide.md")
        val openapi = JSONObject(files.getValue("learn/openapi.json")).getJSONObject("paths")
        val list = JSONObject(files.getValue("learn/endpoints.json"))
        assertEquals(Endpoints.all.size, list.getInt("count"))
        assertEquals(Endpoints.all.size, list.getJSONArray("endpoints").length())
        for (e in Endpoints.all) {
            assertTrue(e.fullPath, guide.contains("### `${e.method} ${e.fullPath}`"))
            assertTrue(e.fullPath, openapi.getJSONObject(e.fullPath).has(e.method.lowercase()))
        }
        val errors = JSONObject(files.getValue("learn/errors.json")).getJSONArray("errors")
        assertEquals(ErrorCatalog.all.map { it.code }, (0 until errors.length()).map { errors.getJSONObject(it).getString("code") })
        val changes = JSONObject(files.getValue("learn/changes.json")).getJSONArray("changes")
        assertEquals(ApiChanges.revisions.map { it.revision }, (0 until changes.length()).map { changes.getJSONObject(it).getInt("revision") })
        assertTrue(guide.contains("API revision ${ApiChanges.CURRENT}"))
        assertTrue(files.getValue("learn/mcp.md").contains(McpTools.name(Endpoints.ISSUES_POST)))
        assertTrue(files.getValue("learn/bugs.md").contains("${Learn.FEEDBACK_BACKEND}/v1/agents/register"))
        McpTools.specs.forEach { assertTrue(it.fullPath, files.getValue("learn/mcp.md").contains("`${McpTools.name(it)}`")) }
        Demos.all.forEach { assertTrue(it.title, files.getValue("learn/demo.md").contains("### ${it.title}\n")) }
    }

    @Test fun agentReportingGoesDirectlyToServers() {
        for (path in listOf("learn/introduction.txt", "learn/pairing-direct.txt", "learn/pairing-userspace.txt", "learn/bugs.md", "learn/guide.md")) {
            val text = files.getValue(path)
            assertTrue(path, text.contains("directly"))
            assertFalse(path, text.contains("Phone down?"))
            if (path != "learn/guide.md") assertFalse(path, text.contains("POST /v1/issues {") || text.contains("POST /v1/feedback {"))
        }
        assertTrue(files.getValue("learn/bugs.md").contains("${Learn.FEEDBACK_BACKEND}/v1/agent/issues"))
    }

    @Test fun noPhoneStateOrSecrets() {
        for ((path, text) in files) {
            assertFalse(path, Regex("""brg_[A-Za-z0-9_-]{16,}""").containsMatchIn(text))
            assertFalse(path, Regex("""100\.\d+\.\d+\.\d+""").containsMatchIn(text))
            assertFalse(path, text.contains("phone-not-on-tailscale"))
            assertFalse(path, text.contains("not available:"))
            assertFalse(path, text.contains("switched off**"))
        }
    }

    @Test fun llmsTxtLinksEveryFile() {
        val llms = files.getValue("llms.txt")
        assertTrue(llms.contains("[Modifying applets and templates](${cc.bridgit.app.applets.AppletEvolution.GUIDE})"))
        files.keys.filter { it != "llms.txt" && !it.startsWith("learn/capabilities/") && !it.startsWith("learn/operations/") }
            .forEach { assertTrue(it, llms.contains("${Learn.SITE}/$it")) }
        val index = files.getValue("learn/catalog-index.json")
        files.keys.filter { it.startsWith("learn/capabilities/") }.forEach { assertTrue(it, index.contains("${Learn.SITE}/$it")) }
        val details = files.filterKeys { it.startsWith("learn/capabilities/") }.values.joinToString("\n")
        files.keys.filter { it.startsWith("learn/operations/") }.forEach { assertTrue(it, details.contains("${Learn.SITE}/$it")) }
        assertTrue(llms.contains(Samples.URL))
        assertTrue(llms.contains(Demos.SKILL_URL))
    }

    @Test fun phoneRepliesPointAtTheServer() {
        assertEquals(Learn.INDEX_URL, ApiChanges.links().getString("learn"))
        assertTrue(McpProtocol.instructions("Grok").contains(Learn.LLMS_URL))
        val phoneGuide = Guide.build("Grok", "1.7.0", Address("p.ts.net", null, 8734), emptyList(), "own", false)
        assertTrue(phoneGuide.contains(Learn.INDEX_URL))
        assertTrue(phoneGuide.contains("# Bridgit guide for Grok"))
        assertTrue(phoneGuide.contains("http://p.ts.net:8734/v1"))
    }

    /** /v2/openapi.json must describe every route PlatformApi installs (except the MCP endpoint itself). */
    @Test fun controlOpenApiHasEveryInstalledRoute() {
        val source = File("src/main/java/cc/bridgit/app/platform/PlatformApi.kt").readText()
        val installed = Regex("""\b(get|post)\("(/[^"]+)"\)""").findAll(source)
            .map { it.groupValues[1] to it.groupValues[2] }.filter { it.second != "/mcp" }.toList()
        assertTrue(installed.size >= 12) // profile routes are now in the v1 EndpointSpec catalog (and MCP)
        val paths = cc.bridgit.app.platform.PlatformApi.openApi().getJSONObject("paths")
        val missing = installed.filter { (m, p) -> paths.optJSONObject(p)?.has(m) != true }
        assertEquals("routes missing from /v2/openapi.json: add them to PlatformApi.ROUTES", emptyList<Pair<String, String>>(), missing)
        assertEquals(installed.size, cc.bridgit.app.platform.PlatformApi.ROUTES.size)
        val call = paths.getJSONObject("/v2/call").getJSONObject("post")
        assertTrue(call.getJSONArray("parameters").toString().contains("Idempotency-Key"))
        assertTrue(paths.getJSONObject("/v2/operations/{id}").getJSONObject("get").getJSONArray("parameters").toString().contains("\"in\":\"path\""))
        val control = files.getValue("learn/control.md")
        for (heading in listOf("## Rules for every tool", "## Using other apps on the screen", "## Workflows", "## Help alerts", "## Camera monitoring streams", "## Files", "## Notifications", "## Peripherals"))
            assertTrue("control.md lacks $heading", control.contains(heading))
    }

    /** Routes registered outside the endpoint catalog (the control layer) must still be on the public control page. */
    @Test fun everyRouteOutsideTheCatalogIsDocumented() {
        val control = files.getValue("learn/control.md")
        val catalog = Endpoints.all.map { "${it.method} ${it.fullPath}" }.toSet()
        val route = Regex("""\b(get|post|put|patch|delete)\("(/[^"]+)"\)""")
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }
        val missing = sources.flatMap { f -> route.findAll(f.readText()).map { "${it.groupValues[1].uppercase()} ${it.groupValues[2]}" to f.name } }
            .filter { (r, _) -> r !in catalog && !r.startsWith("GET /mcp") && !r.startsWith("DELETE /") && !r.startsWith("GET /v1/mcp") }
            .filter { (r, _) -> r != "POST /mcp" && !control.contains("`$r`") }
            .toList()
        assertEquals("routes with no public docs: add them to Endpoints or to Learn.control()", emptyList<Pair<String, String>>(), missing)
        assertTrue(control.contains("POST /mcp"))
        for (i in 0 until cc.bridgit.app.platform.PlatformApi.schema().length())
            assertTrue(control.contains("`" + cc.bridgit.app.platform.PlatformApi.schema().getJSONObject(i).getString("name") + "`"))
        val health = File("src/main/java/cc/bridgit/app/capability/Modules.kt").readText()
        assertTrue("health must name both MCP servers", health.contains(".put(\"mcp\",\"/v1/mcp\")") && health.contains(".put(\"mcp\",\"/mcp\")"))
        assertTrue(files.getValue("llms.txt").contains(Learn.CONTROL_URL))
        assertTrue(files.getValue("learn/mcp.md").contains(Learn.CONTROL_URL))
    }

    /** The playbook may only name endpoints and control tools that exist (owner request 2 Nov 2026: no invented calls). */
    @Test fun playbookNamesOnlyRealCallsAndTools() {
        val text = files.getValue("learn/playbook.md")
        val catalog = Endpoints.all.map { "${it.method} ${it.fullPath}" }.toSet()
        val v2 = setOf("GET /v2/capabilities", "GET /v2/tools", "GET /v2/guide", "POST /v2/call", "GET /v2/events", "GET /v2/operations/{id}")
        val calls = Regex("""\b(GET|POST|DELETE|PUT|PATCH) (/v[12]/[A-Za-z0-9_./{}-]+)""").findAll(text)
            .map { "${it.groupValues[1]} ${it.groupValues[2].trimEnd('.', ',')}" }.toSet()
        assertEquals("playbook names calls that don't exist", emptySet<String>(), calls - catalog - v2)
        val v1Tools = McpTools.specs.map { McpTools.name(it) }.toSet()
        val schema = cc.bridgit.app.platform.PlatformApi.schema()
        val v2Tools = (0 until schema.length()).map { schema.getJSONObject(it).getString("name") }.toSet()
        val tools = Regex("""`(bridgit_[a-z_]+)`""").findAll(text).map { it.groupValues[1] }.toSet()
        assertEquals("playbook names control tools that don't exist", emptySet<String>(), tools - v2Tools)
        assertTrue("playbook should name control tools", tools.isNotEmpty())
        val mcp = Regex("""MCP `((?!bridgit_)[a-z_]+)`""").findAll(text).map { it.groupValues[1] }.toSet()
        assertEquals("playbook names MCP tools that don't exist", emptySet<String>(), mcp - v1Tools)
        val cases = Regex("""^### \d+\. """, RegexOption.MULTILINE).findAll(text).count()
        assertTrue("use cases: $cases < ${Playbook.USE_CASES}", cases >= Playbook.USE_CASES)
        assertTrue(calls.size > 30)
    }

    @Test fun interactionExampleCountIsCurrent() {
        val index = JSONObject(File("../site-content/content/interactions/index.json").readText())
        assertEquals(index.getJSONArray("interactions").length(), Learn.INTERACTION_EXAMPLES)
    }
}
