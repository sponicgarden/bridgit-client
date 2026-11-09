package cc.bridgit.app.api

import cc.bridgit.app.core.Connection
import cc.bridgit.app.core.Keys
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Ways the pure API logic can fail:
 * Rate limiter: hits never expire; the limit is off by one; a refused attempt still counts.
 * Idempotency: two concurrent repeats both run; a repeat after success runs again; one agent's key
 *   replays another agent's result; media bodies kept longer than 15 min (SEC-13); 5xx results replayed.
 * Sizing: upscaling; wrong aspect ratio; presets ignored; explicit values not overriding presets; bad values accepted.
 * Error catalog: a code used by the app missing from the spec (or the other way round); HTTP status mismatch.
 * Setup prompt: key missing or present twice; wrong port/host; userspace prompt without the helper; direct
 *   prompt over the 3,000-character budget (AGT-10); capture calls listed while switched off (AGT-07).
 * Guide / openapi: an endpoint missing; invalid structure; a secret leaking in.
 * Demo script: a demo names an endpoint that doesn't exist; a switched-off or ungranted demo offered as ready;
 *   the setup prompt or guide not pointing at it.
 * Wait gate: more than 4 waits per agent; counts never released.
 */
class ApiLogicTest {
    private var now = 0L

    @Test fun rateLimiterWindow() {
        val r = RateLimiter(clock = { now })
        repeat(5) { assertTrue(r.tryAcquire("ip", 5)) }
        assertFalse(r.tryAcquire("ip", 5))
        assertEquals(5, r.count("ip"))
        assertTrue(r.retryAfterS("ip") in 1..60)
        assertTrue("another key is separate", r.tryAcquire("other", 5))
        now += 60_001
        assertEquals(0, r.count("ip"))
        assertTrue(r.tryAcquire("ip", 5))
    }

    @Test fun waitGate() {
        val g = WaitGate(perAgent = 4, total = 5)
        repeat(4) { assertTrue(g.enter("a")) }
        assertFalse(g.enter("a"))
        assertTrue(g.enter("b"))
        assertFalse("total cap", g.enter("c"))
        g.exit("a")
        assertTrue(g.enter("c"))
    }

    @Test fun idempotency() {
        val c = IdempotencyCache(clock = { now })
        assertEquals(IdempotencyCache.Claim.New, c.claim("a1", "k1"))
        assertEquals(IdempotencyCache.Claim.Running, c.claim("a1", "k1"))
        assertEquals("other agent, same key", IdempotencyCache.Claim.New, c.claim("a2", "k1"))
        // hasResult (used to skip pre-body admission for replays): only once finished, only for that agent.
        assertFalse("running isn't a result", c.hasResult("a1", "k1"))
        c.finish("a1", "k1", 200, "application/json", "{\"x\":1}".toByteArray(), emptyMap(), isMedia = false)
        assertTrue(c.hasResult("a1", "k1")); assertFalse(c.hasResult("a2", "k1"))
        val d = c.claim("a1", "k1") as IdempotencyCache.Claim.Done
        assertEquals("{\"x\":1}", String(d.stored.body!!))
        now += 23 * 3600_000L
        assertTrue(c.claim("a1", "k1") is IdempotencyCache.Claim.Done)
        now += 2 * 3600_000L
        assertEquals("expired after 24 h", IdempotencyCache.Claim.New, c.claim("a1", "k1"))
    }

    @Test fun idempotencyKeyBelongsToItsEndpoint() {
        val c = IdempotencyCache(clock = { now })
        assertEquals(IdempotencyCache.Claim.New, c.claim("a", "k1", "POST /camera/snapshot"))
        assertEquals("running elsewhere", IdempotencyCache.Claim.Mismatch, c.claim("a", "k1", "POST /interactions"))
        c.finish("a", "k1", 200, "image/jpeg", byteArrayOf(1, 2), emptyMap(), isMedia = true)
        assertFalse("no replay shortcut for another endpoint", c.hasResult("a", "k1", "POST /interactions"))
        assertTrue(c.hasResult("a", "k1", "POST /camera/snapshot"))
        assertEquals("never replays the photo to another endpoint", IdempotencyCache.Claim.Mismatch, c.claim("a", "k1", "POST /interactions"))
        assertTrue(c.claim("a", "k1", "POST /camera/snapshot") is IdempotencyCache.Claim.Done)
        now += 16 * 60_000L
        assertEquals("still bound after the media body is dropped", IdempotencyCache.Claim.Mismatch, c.claim("a", "k1", "POST /interactions"))
    }

    @Test fun idempotencyMediaAndErrors() {
        val c = IdempotencyCache(clock = { now })
        c.claim("a", "m"); c.finish("a", "m", 200, "image/jpeg", ByteArray(10), emptyMap(), isMedia = true)
        assertTrue(c.claim("a", "m") is IdempotencyCache.Claim.Done)
        now += 16 * 60_000L
        assertEquals("media body dropped after 15 min", IdempotencyCache.Claim.Expired, c.claim("a", "m"))
        c.claim("a", "e"); c.finish("a", "e", 500, "application/json", ByteArray(1), emptyMap(), isMedia = false)
        assertEquals("5xx is not remembered", IdempotencyCache.Claim.New, c.claim("a", "e"))
        c.finish("a", "e", 422, "application/json", ByteArray(1), emptyMap(), isMedia = false)
        assertEquals("4xx is not remembered (nothing was done)", IdempotencyCache.Claim.New, c.claim("a", "e"))
        c.abandon("a", "e")
        // Byte budget: a body that doesn't fit leaves only the done marker, so no second capture.
        val small = IdempotencyCache(clock = { now }, maxBodyBytes = 5)
        small.claim("a", "big"); small.finish("a", "big", 200, "image/jpeg", ByteArray(10), emptyMap(), isMedia = true)
        assertEquals(IdempotencyCache.Claim.Expired, small.claim("a", "big"))
        // Full: new keys are refused, existing markers are never evicted.
        val tiny = IdempotencyCache(clock = { now }, maxEntries = 1)
        tiny.claim("a", "k1"); tiny.finish("a", "k1", 200, "application/json", ByteArray(1), emptyMap(), isMedia = false)
        assertEquals(IdempotencyCache.Claim.Full, tiny.claim("a", "k2"))
        assertTrue(tiny.claim("a", "k1") is IdempotencyCache.Claim.Done)
        assertTrue(IdempotencyCache.KEY_RE.matches("123e4567-e89b-12d3-a456-426614174000"))
        assertFalse(IdempotencyCache.KEY_RE.matches("bad key!"))
    }

    @Test fun sizingTargets() {
        val agent = Sizing.PRESETS.getValue("agent")
        assertEquals(1568 to 1176, Sizing.target(4000, 3000, agent))
        assertEquals(1176 to 1568, Sizing.target(3000, 4000, agent))
        assertEquals("never upscales", 800 to 600, Sizing.target(800, 600, agent))
        assertEquals(512 to 384, Sizing.target(4000, 3000, Sizing.PRESETS.getValue("thumb")))
        assertEquals(4000 to 3000, Sizing.target(4000, 3000, Sizing.PRESETS.getValue("original")))
        assertEquals(1024 to 768, Sizing.target(4000, 3000, Sizing.Spec(null, 1024, 90)))
        assertEquals(1, Sizing.sampleSize(4000, 3000))
        assertEquals(2, Sizing.sampleSize(4000, 1568))
        assertEquals(4, Sizing.sampleSize(8000, 1568))
    }

    @Test fun sizingResolve() {
        fun r(vararg kv: Pair<String, Any?>) = Sizing.resolve({ k -> kv.toMap()[k] }, "agent")
        assertEquals(Sizing.PRESETS["agent"], r())
        assertEquals(Sizing.PRESETS["thumb"], r("size" to "thumb"))
        assertEquals(800, r("max_edge_px" to "800").maxEdge)
        assertEquals(60, r("size" to "large", "quality" to 60).quality)
        assertEquals(2560, r("size" to "large", "quality" to 60).maxEdge)
        for (bad in listOf("size" to "huge", "max_edge_px" to "10", "quality" to "101", "max_width_px" to "abc"))
            try { r(bad); fail("accepted $bad") } catch (e: ApiError) { assertEquals("invalid_parameter", e.code) }
    }

    @Test fun errorCatalogMatchesSpecAppendixC() {
        val spec = File("../docs/bridgit-spec-v2.md").takeIf { it.exists() } ?: File("docs/bridgit-spec-v2.md")
        val text = spec.readText()
        val appendix = text.substringAfter("## Appendix C. Error catalog").substringBefore("## Appendix D")
        val rows = Regex("^\\| (\\d{3}) \\| (?:\\(pending[^|]*|`([a-z_]+)`)", RegexOption.MULTILINE).findAll(appendix)
            .mapNotNull { m -> m.groupValues[2].takeIf { it.isNotEmpty() }?.let { it to m.groupValues[1].toInt() } }.toMap()
        val code = ErrorCatalog.all.associate { it.code to it.http }
        assertEquals("codes in the app but not in the spec", emptySet<String>(), code.keys - rows.keys)
        assertEquals("codes in the spec but not in the app", emptySet<String>(), rows.keys - code.keys)
        for ((c, h) in code) assertEquals("HTTP for $c", rows[c], h)
        for (e in Endpoints.all) e.errors.forEach { ErrorCatalog.http(it) }
    }

    private val addr = Address("my-phone.tailnet-name.ts.net", "100.101.102.103", 8735)
    private fun caps(vararg on: String) = listOf("camera", "mic", "gallery").map {
        CapInfo(it, it, it, it in on, true, null)
    }

    private val sections = listOf("IDENTITY", "CONNECTION", "RULES", "FIRST CALLS, IN ORDER", "GOTCHAS", "AFTER YOU'RE CONNECTED", "POINTERS")

    @Test fun setupPromptDirect() {
        val key = Keys.generate()
        val p = SetupPrompt.build("Muse", key, Connection.DIRECT, addr, "Pixel 10 Pro XL", caps("gallery", "camera"), null)
        assertEquals("key exactly once", 1, Regex(Regex.escape(key)).findAll(p).count())
        assertTrue(p.contains("http://my-phone.tailnet-name.ts.net:8735/v1"))
        assertTrue(p.contains("IP fallback: http://100.101.102.103:8735/v1"))
        assertTrue(p.contains("- Agent name: Muse\n"))
        assertTrue(p.contains("Store the key only in \"~/.config/bridgit/agents/Muse/header\" (mode 600)"))
        assertTrue(p.contains("- Connected test: curl -sS -m 60 -H @\"\$HOME/.config/bridgit/agents/Muse/header\" http://my-phone.tailnet-name.ts.net:8735/v1/health\n"))
        assertTrue("connected sentence", p.contains("means you're connected; nothing else proves it."))
        assertTrue("timeout fallback", p.contains("run the connection ladder first") && p.contains("More → Relay"))
        assertTrue(p.contains("/v1/camera/snapshot") && p.contains("/v1/image/last"))
        assertTrue(p.contains("GET /v1/guide is the full reference: use it only when needed"))
        assertTrue(p.contains("GET /v1/demo"))
        assertTrue("points bug reports at the servers", p.contains("Report every bug") && p.contains(Learn.BUGS_URL))
        assertTrue("states the job", p.contains("Your job: build and improve applets"))
        assertTrue("points at the playbook", p.contains(Learn.PLAYBOOK_URL))
        assertTrue("both MCP servers", p.contains("http://my-phone.tailnet-name.ts.net:8735/v1/mcp") && p.contains("http://my-phone.tailnet-name.ts.net:8735/mcp "))
        assertTrue("no blanket idempotency promise", !p.contains("on POSTs (reuse"))
        assertTrue("24-hour email rule", p.contains("owner_email.status") && p.contains("POST /v1/owner/email") && p.contains("24 hours"))
        assertTrue(p.contains("GET /v1/limits"))
        println("setup prompt length: ${p.length}")
        assertTrue("AGT-10 budget: ${p.length}", p.length < 6000)
        // AGT-09 order, and the first calls in their order
        val order = sections.map { p.indexOf(it) }
        assertTrue(order.none { it < 0 }); assertEquals(order.sorted(), order)
        val calls = listOf("1. GET /v1/health:", "2. POST /v1/me", "3. GET /v1/capabilities:", "4. GET /v1/limits", "5. Read https://bridgit.cc/learn/start.md").map { p.indexOf(it) }
        assertTrue(calls.toString(), calls.none { it < 0 }); assertEquals(calls.sorted(), calls)
    }

    private val named = DeviceIdentity("kitchen-tab", "Kitchen Tab", "Google Pixel 10 Pro XL", "o***@example.com (verified)")

    @Test fun setupPromptOpensWithTheIdentityBlockAndTheDomainName() {
        val key = Keys.generate()
        for (connection in listOf(Connection.DIRECT, Connection.USERSPACE)) {
            val p = SetupPrompt.build("Muse", key, connection, addr, "Google Pixel 10 Pro XL", caps("camera"), "helper", device = named)
            assertEquals("A0213: identity first, fixed fields in a fixed order", listOf("IDENTITY",
                "- Device domain name: kitchen-tab",
                "- Friendly name: Kitchen Tab (display only)",
                "- Model: Google Pixel 10 Pro XL",
                "- Agent name: Muse",
                "- Owner: o***@example.com (verified)"), p.lines().take(6))
            assertTrue("connected test checks the domain name", p.contains("bridgit_name.name kitchen-tab means you're connected; nothing else proves it"))
            val store = if (connection == Connection.USERSPACE) "env" else "header"
            assertTrue("rotation rule replaces the one stored key", p.contains("key_rotated") && p.contains("ask the owner for a new setup prompt, then REPLACE the key in \"~/.config/bridgit/agents/Muse/$store\"; never keep two"))
            assertTrue("one canonical store, mode 600", p.contains("Store the key only in \"~/.config/bridgit/agents/Muse/$store\" (mode 600)"))
            assertTrue("says which MCP when", p.contains("/v1/mcp for the API") && p.contains("/mcp only for private-build control tools"))
            assertTrue("A0213 order: rules before the bootstrap", p.indexOf("RULES") in p.indexOf("CONNECTION") until p.indexOf("FIRST CALLS, IN ORDER"))
            val first = p.lineSequence().first { it.startsWith("1. GET /v1/health:") }
            assertTrue(first, first.contains("This device's domain name is kitchen-tab") && first.contains("never register another name"))
            assertFalse("a named device is never told to register", first.contains("register it") || first.contains("register a name"))
            assertTrue("A0213 budget: ${p.length}", p.length < 6000)
        }
    }

    @Test fun setupPromptOnlyRegistersANameWhenTheDeviceHasNoneAndTheOwnerChoseIt() {
        val p = SetupPrompt.build("Muse", Keys.generate(), Connection.DIRECT, addr, "Pixel", caps("camera"), null)
        assertTrue(p.startsWith("IDENTITY\n- Device domain name: none yet (see FIRST CALLS 1)\n"))
        assertTrue("friendly name falls back to the model", p.contains("- Friendly name: Pixel (display only"))
        assertTrue(p.contains("- Owner: email not set yet"))
        val first = SetupPrompt.firstCalls().lineSequence().first { it.startsWith("1. GET /v1/health:") }
        val registered = first.indexOf("if bridgit_name.status is \"registered\"")
        val unregistered = first.indexOf("Only if it is \"unregistered\": ask the owner which name they want")
        assertTrue(first, registered >= 0 && unregistered > registered && first.indexOf("then register it") > unregistered)
        assertTrue(first, first.contains("never register another"))
        assertTrue(first, first.contains(Learn.NAMES_URL)); assertEquals("https://bridgit.cc/agents/names/", Learn.NAMES_URL)
        assertTrue(first, first.indexOf("bridgit_name") < first.indexOf("owner_email"))
        assertFalse("the old unconditional step is gone", first.contains("first register a name"))
    }

    @Test fun setupPromptSamplesAreAsciiSafe() {
        val p = SetupPrompt.build("Muse", Keys.generate(), Connection.DIRECT, addr, "Pixel", caps("gallery", "camera", "mic"), null, device = named)
        val smart = setOf('\u2018', '\u2019', '\u201C', '\u201D')
        assertTrue("A0213: no smart quotes anywhere", p.none { it in smart })
        p.lineSequence().filter { it.contains('{') }.forEach { line -> assertTrue("JSON sample not ASCII: $line", line.substring(line.indexOf('{')).all { it.code < 128 }) }
    }

    @Test fun startPageServedFromThePhone() {
        val e = Endpoints.all.single { it.fullPath == "/v1/start" }
        assertEquals("GET", e.method); assertFalse("no key, like the public page", e.auth)
        assertEquals("/v1/start", ApiChanges.links().getString("start"))
        assertTrue("start.md says so", CapabilityCatalog.start().contains("GET /v1/start"))
        val p = SetupPrompt.build("Muse", Keys.generate(), Connection.DIRECT, addr, "Pixel", caps("camera"), null)
        assertTrue("prompt names the fallback", p.lineSequence().any { it.startsWith("5. Read ${CapabilityCatalog.START_URL}") && it.contains("GET /v1/start") })
    }

    @Test fun firstCallsAreOneSourceForPromptAndRelayText() {
        val first = SetupPrompt.firstCalls()
        assertTrue(first.startsWith("FIRST CALLS, IN ORDER\n1. GET /v1/health"))
        val p = SetupPrompt.build("Muse", Keys.generate(), Connection.DIRECT, addr, "Pixel", caps("camera"), null)
        assertTrue("prompt embeds the block verbatim", p.contains(first))
        val relay = java.io.File("src/main/res/values/strings_relay.xml").readText()
        val text = Regex("<string name=\"relay_prompt\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL).find(relay)!!.groupValues[1]
        assertFalse("no longer sends agents to the whole guide first", text.contains("Start with GET /v1/guide"))
        assertTrue("takes the first-calls block (%3) before the details (%4)", text.indexOf("%3\$s") in 0 until text.indexOf("%4\$s"))
        assertTrue(text.contains("request.path"))
    }

    @Test fun setupPromptIdempotencyListMatchesEndpoints() {
        val p = SetupPrompt.build("Muse", Keys.generate(), Connection.DIRECT, addr, "Pixel", caps("camera"), null)
        val line = p.lineSequence().first { it.startsWith("- Idempotency-Key:") }
        val named = listOf("camera/snapshot", "mic/record", "interactions", "speaker/play", "speaker/say", "screen/capture", "applets/install")
        named.forEach { assertTrue(it, line.contains(it)) }
        val all = Endpoints.all
        named.forEach { path -> assertTrue("$path accepts Idempotency-Key", all.single { it.method == "POST" && it.fullPath == "/v1/$path" }.idempotent) }
        assertTrue("watches don't take it", all.none { it.method == "POST" && it.fullPath == "/v1/watches" && it.idempotent })
    }

    @Test fun shortPromptCarriesOnlyWhatComesBeforeTheGuide() {
        val key = Keys.generate()
        val unnamed = DeviceIdentity(null, "A9 Cream Tab", "Samsung SM-X210", null)
        val p = SetupPrompt.short("Grok Bot 2", key, addr, unnamed)
        assertEquals("key exactly once", 1, Regex(Regex.escape(key)).findAll(p).count())
        assertTrue(p.contains("http://my-phone.tailnet-name.ts.net:8735/v1") && p.contains("IP fallback: http://100.101.102.103:8735/v1"))
        assertTrue(p.contains("only in \"~/.config/bridgit/agents/Grok Bot 2/header\" (mode 600)"))
        assertTrue("first reply is the naming question", p.contains("needs a friendly name to register your device. Can we call it '<name>'?"))
        assertTrue("points at the guide", p.contains("The Bridgit Interface Guide is here:") && p.contains("http://my-phone.tailnet-name.ts.net:8735/v1/welcome") && p.contains(SetupPrompt.WELCOME_URL))
        assertTrue("a few lines", p.lines().size <= 4 && p.length < 1000)
        val named = SetupPrompt.short("Grok Bot 2", key, addr, unnamed.copy(domainName = "cremeo-ow"))
        assertFalse("a named device is never asked to name it", named.contains("Can we call it"))
        assertTrue(Endpoints.all.single { it.fullPath == "/v1/welcome" }.auth)
    }

    @Test fun setupPromptAgentNameWithSpaces() {
        val key = Keys.generate()
        val p = SetupPrompt.build("Claude dev", key, Connection.DIRECT, addr, "Pixel", caps("camera"), null)
        assertTrue(p.contains("only in \"~/.config/bridgit/agents/Claude dev/header\""))
        assertTrue(p.contains("-H @\"\$HOME/.config/bridgit/agents/Claude dev/header\""))
        assertFalse("no unquoted path", Regex("""[^"]~/\.config/bridgit/agents/Claude dev""").containsMatchIn(p))
        val u = SetupPrompt.build("Claude dev", key, Connection.USERSPACE, addr, "Pixel", caps("camera"), "helper")
        assertTrue(u.contains("only in \"~/.config/bridgit/agents/Claude dev/env\""))
        assertTrue(u.contains("BRIDGIT_ENV=\"\$HOME/.config/bridgit/agents/Claude dev/env\""))
    }

    @Test fun setupPromptWithCallback() {
        val key = Keys.generate()
        val secret = cc.bridgit.app.core.CallbackRules.newSecret()
        val p = SetupPrompt.build("Grok", key, Connection.DIRECT, addr, "Pixel 10 Pro XL", caps("gallery", "camera"), null,
            secret)
        assertEquals("secret exactly once", 1, Regex(Regex.escape(secret)).findAll(p).count())
        assertTrue(p.contains("CALLBACK SECRET\n- $secret\n- Keep it like your key"))
        assertTrue("AGT-10 budget: ${p.length}", p.length < 6000)
        assertTrue(p.indexOf("CALLBACK SECRET") in p.indexOf("RULES")..p.indexOf("AFTER YOU'RE CONNECTED"))
        // Worst case (AGT-10): longest name, MagicDNS + IP fallback, every feature on, callback secret, direct and relay.
        for (connection in listOf(Connection.DIRECT, Connection.RELAY)) {
            val worst = SetupPrompt.build("A".repeat(24), key, connection, addr, "Samsung SM-X238U",
                caps("gallery", "camera", "mic"), null, secret)
            println("setup prompt worst case ($connection, private=${cc.bridgit.app.BuildConfig.PRIVATE_CONTROL}): ${worst.length}")
            assertTrue("AGT-10 worst case $connection: ${worst.length}", worst.length < 6000)
        }
        assertFalse(SetupPrompt.build("Grok", key, Connection.DIRECT, addr, "Pixel", caps(), null).contains("CALLBACK"))
    }

    @Test fun setupPromptNothingOnAndUserspace() {
        val key = Keys.generate()
        val p = SetupPrompt.build("Grok", key, Connection.DIRECT, addr, "Pixel", caps(), null)
        assertTrue(p.contains("No optional features ready and allowed"))
        assertFalse(p.contains("/v1/camera/snapshot"))
        val helper = "#!/usr/bin/env python3\nprint('helper')\n"
        val u = SetupPrompt.build("Grok", key, Connection.USERSPACE, addr, "Pixel", caps("camera"), helper)
        assertTrue(u.contains("BRIDGIT_HOST=100.101.102.103") && u.contains("BRIDGIT_PORT=8735"))
        assertTrue(u.contains("BRIDGIT_ENV=\"\$HOME/.config/bridgit/agents/Grok/env\""))
        assertTrue(u.contains("----- BEGIN ~/bin/bridgit -----\n$helper".trimEnd()))
        assertEquals(1, Regex(Regex.escape(key)).findAll(u).count())
        val qr = JSONObject(SetupPrompt.qrPayload("Muse", key, addr))
        assertEquals(1, qr.getInt("bridgit")); assertEquals(1, qr.getInt("bridget")); assertEquals(key, qr.getString("key"))
        assertTrue(SetupPrompt.qrPayload("Muse", key, addr).length < 200)
    }

    @Test fun setupSuggestionsRequireReadinessAndAgentPermission() {
        val enabled = caps("camera", "mic", "gallery").map {
            if (it.id == "camera") it.copy(ready = false, blockedReason = "open_app_for_camera") else it
        }
        val p = SetupPrompt.build("Ct", Keys.generate(), Connection.DIRECT, addr, "Tablet", enabled, null) { it != "gallery" }
        assertFalse(p.contains("POST /v1/camera/snapshot"))
        assertFalse(p.contains("GET /v1/image/last"))
        assertTrue(p.contains("POST /v1/mic/record"))
        val denied = SetupPrompt.build("Ct", Keys.generate(), Connection.DIRECT, addr, "Tablet", enabled, null) { false }
        assertTrue(denied.contains("No optional features ready and allowed"))
        assertFalse(denied.contains("POST /v1/mic/record"))
    }

    @Test fun setupExplainsBootstrapAndWaitingWithoutStartingOnboarding() {
        val p = SetupPrompt.build("Ct", Keys.generate(), Connection.DIRECT, addr, "Tablet", caps(), null)
        assertTrue(p.indexOf("GET /v1/health:") < p.indexOf("GET /v1/capabilities:"))
        assertTrue(p.contains("GET /v2/capabilities"))
        assertTrue(p.contains("\"seen\" is not an answer"))
        assertTrue(p.contains("keep each new cursor"))
        assertTrue(p.contains("retry_after_s"))
        assertTrue(p.contains("curl -m 60") && p.contains("duration + 90 s with transcribe=true"))
        // Paste-only owners get the demo question straight away; it still runs only on a yes, and is asked once.
        assertTrue(p.contains("\"Want a 5-minute demo on your phone?\""))
        assertTrue(p.contains("If they gave you a task, do it first, then ask."))
        assertTrue(p.contains("On yes, follow GET /v1/demo"))
        assertTrue(p.contains("Ask once."))
        assertTrue(p.contains("If they want more"))
        // Inline examples must be valid JSON, not shorthand that looks like a request body.
        for (path in listOf("me")) {
            val body = p.lineSequence().first { it.contains("POST /v1/$path ") }
                .substringAfter("POST /v1/$path ").substringBefore('}') + "}"
            JSONObject(body)
        }
    }

    @Test fun guideAndOpenApi() {
        val g = Guide.build("Grok", "1.3.0", addr, caps("camera"), "own", superPowers = false)
        for (e in Endpoints.all) assertTrue(e.fullPath, g.contains(e.fullPath))
        assertTrue(g.contains("only your own entries"))
        assertTrue(g.contains("**switched off**"))
        assertNull(Keys.PATTERN.find(g))
        val o = OpenApi.build("1.3.0", addr)
        assertEquals("3.1.0", o.getString("openapi"))
        val paths = o.getJSONObject("paths")
        for (e in Endpoints.all) assertTrue(e.fullPath, paths.getJSONObject(e.fullPath).has(e.method.lowercase()))
        assertEquals("id", paths.getJSONObject("/v1/log/{id}").getJSONObject("get").getJSONArray("parameters").getJSONObject(0).getString("name"))
        assertEquals(0, paths.getJSONObject("/v1/tools/bridgit").getJSONObject("get").getJSONArray("security").length())
        assertEquals(0, paths.getJSONObject("/v1/tools/bridget").getJSONObject("get").getJSONArray("security").length())
    }

    /** The guide leads with the short path and manners, and marks what this agent may not use. */
    @Test fun guideStartsShortAndIsPerAgent() {
        val g = Guide.build("Grok", "1.8.0", addr, caps("camera", "mic"), "own", superPowers = false) { it != "mic" }
        assertTrue(g.indexOf("## Start here") in 0 until g.indexOf("## Endpoints"))
        assertTrue(g.indexOf("## Good phone manners") in 0 until g.indexOf("## Endpoints"))
        // New feature sections go under "## Feature notes", never above "## Start here" (they drifted there before).
        val headings = Regex("(?m)^## (.+)$").findAll(g).map { it.groupValues[1] }.toList()
        assertEquals(listOf("New in revision ${ApiChanges.CURRENT}", "Start here"), headings.take(2))
        assertTrue(g.indexOf("## Start here") < g.indexOf("## Feature notes"))
        assertTrue(g.indexOf("## Start here") < 6000)
        assertTrue(g.contains("`agent_allowed`, `enabled` and `ready`"))
        assertTrue(g.contains("| mic (`mic`) | on | yes | no | agent_not_allowed |"))
        assertTrue(g.contains("/v1/mic/record` — **not allowed for you**"))
        assertFalse(g.contains("Bridgit never calls out"))
        assertTrue(Endpoints.CAPABILITIES.returns.contains("agent_allowed"))
        assertTrue(McpProtocol.instructions("Grok").contains(McpTools.name(Endpoints.CAPABILITIES)))
    }

    @Test fun demoScript() {
        val keys = Endpoints.all.map { "${it.method} ${it.fullPath}" }.toSet()
        for (d in Demos.all) d.calls.forEach { assertTrue("${d.title}: no endpoint $it", it in keys) }
        val caps = listOf(CapInfo("interactions", "Questions", "interactions", true, true, null),
            CapInfo("camera", "Camera", "camera", true, true, null), CapInfo("mic", "Microphone", "mic", false, true, null),
            CapInfo("phone", "Phone status", "phone", true, true, null))
        val m = Demos.build("Grok", caps) { it != "phone" }
        val ready = m.lines().first { it.startsWith("Ready now:") }
        assertTrue(ready.contains("Camera control") && ready.contains("Ask me with buttons"))
        assertFalse(ready.contains("Listen and transcribe") || ready.contains("Phone at a glance"))
        assertTrue(m.contains("### Listen and transcribe (not available: Microphone is switched off"))
        assertTrue(m.contains("### Phone at a glance (not available: the owner hasn't allowed you"))
        // With cards on, the menu's questions go on one card; without, they're asked in chat.
        assertTrue(m.contains(Demos.QUESTIONS_CARD) && !m.contains("Cards are off"))
        val noCards = Demos.build("Grok", caps.drop(1)) { true }
        assertTrue(noCards.contains("Cards are off") && !noCards.contains(Demos.QUESTIONS_CARD))
        // The first-run tour comes first, without its frontmatter, then the menu.
        val both = Demos.build("Grok", caps, "---\nname: bridgit-demo\ndescription: x\n---\n\n# Bridgit first-run demo\nItems.\n") { true }
        assertFalse(both.contains("name: bridgit-demo"))
        assertTrue(both.indexOf("# Bridgit first-run demo") in 0 until both.indexOf("# Part 2: the menu"))
        assertTrue(Demos.build("Grok", caps) { true }.contains(Demos.SKILL_URL))
        assertTrue(Guide.build("Grok", "1.6.0", addr, caps, "own", superPowers = false).contains("`GET /v1/demo`"))
        val g = Guide.build("Grok", "1.6.0", addr, caps, "own", superPowers = false)
        assertTrue(g.contains("## Issues Tracking: report bugs, ask for features"))
        assertEquals("/v1/issues", ApiChanges.links().getString("issues"))
        assertEquals("/v1/feedback", ApiChanges.links().getString("feedback"))
        assertEquals("/v1/samples", ApiChanges.links().getString("samples"))
        assertTrue(McpProtocol.instructions("Grok").contains(McpTools.name(Endpoints.SAMPLES)))
    }

    /** FBK-15: the guide asks for every report and explains the team key and the verify loop. */
    @Test fun guideExplainsReportingAndTheVerifyLoop() {
        val g = Guide.build("Grok", "1.6.0", addr, caps("camera"), "own", superPowers = false)
        assertTrue(g.contains("## Issues Tracking: report bugs, ask for features"))
        assertTrue(g.contains("every bug and every feature request"))
        assertTrue(g.contains("`GET /v1/issues/team-key`"))
        assertTrue(g.contains("waiting_for_retest") && g.contains("/verify"))
        assertTrue(g.contains("`unverified` means you couldn't test it"))
    }

    /** Every call the first-run tour (SKILL.md) and the menu's steps name is a real endpoint, so the scripts can't drift from the API. */
    @Test fun demoScriptsNameRealEndpoints() {
        val keys = Endpoints.all.map { "${it.method} ${it.fullPath.replace(Regex("\\{[^}]+}"), "{}")}" }.toSet()
        val call = Regex("\\b(GET|POST|DELETE) (/v1)?(/[a-z][a-z0-9_/{}.-]*)")
        fun check(where: String, text: String): Int = call.findAll(text).count { m ->
            val path = "/v1" + m.groupValues[3].trimEnd('/', '.').replace(Regex("\\{[^}]+}"), "{}")
            assertTrue("$where: no endpoint ${m.groupValues[1]} $path", "${m.groupValues[1]} $path" in keys); true
        }
        val skill = java.io.File("../site-content/public/demo/SKILL.md").readText()
        assertTrue("SKILL.md names too few calls", check("SKILL.md", skill) > 20)
        for (d in Demos.all) d.steps.forEach { check(d.title, it) }
    }

    @Test fun pendingSchema() {
        val p = Pending.body("job_queued", "j1", "/v1/jobs/j1?wait=55", "r1")
        assertTrue(p.getBoolean("pending")); assertEquals(1, p.getInt("retry_after_s"))
        try { Pending.body("nope", "x", "/", "r"); fail() } catch (_: IllegalArgumentException) {}
    }
}

/**
 * Ways API revisions (API-14) can fail: a release adds an endpoint but no revision entry
 * (agents never hear of it), an entry names an endpoint that doesn't exist, revisions out
 * of order, or whats_new still nags an agent that is up to date.
 */
class ApiChangesTest {
    @Test fun revisionsAreOrderedAndNameRealEndpoints() {
        val revs = ApiChanges.revisions.map { it.revision }
        assertEquals((1..revs.size).toList(), revs)
        val paths = Endpoints.all.map { it.fullPath }.toSet() +
            cc.bridgit.app.platform.PlatformApi.openApi().getJSONObject("paths").keys().asSequence().toSet()
        for (r in ApiChanges.revisions) for (i in r.items) i.endpoint?.let { assertTrue("unknown endpoint $it", it in paths) }
        // every endpoint is announced in some revision (directly or as part of a family)
        for (e in Endpoints.all) assertTrue("${e.fullPath} not announced", ApiChanges.revisions.flatMap { it.items }
            .any { it.endpoint != null && (e.fullPath == it.endpoint || e.fullPath.startsWith(it.endpoint.substringBefore("/{")) || it.endpoint.startsWith(e.fullPath)) }
            || e.fullPath in setOf("/v1/capabilities", "/v1/guide", "/v1/openapi.json", "/v1/pair", "/v1/audit"))
    }

    @Test fun whatsNew() {
        assertEquals(org.json.JSONObject.NULL, ApiChanges.whatsNew(ApiChanges.CURRENT))
        val w = ApiChanges.whatsNew(0) as org.json.JSONObject
        assertEquals(ApiChanges.CURRENT, w.getInt("new_revisions"))
        assertEquals("/v1/changes?since=0", w.getString("url"))
        assertEquals(1, ApiChanges.json(ApiChanges.CURRENT - 1, "x").getJSONArray("changes").length())
    }
}
