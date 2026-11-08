package cc.bridgit.app.outbound

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneOffset

class OutboundLogicTest {
    @After fun reset() { Outbound.detach() }

    private fun build(url: String, method: String = "POST", headers: Map<String, String> = emptyMap(), body: String? = null,
                      type: String? = "application/json", status: Int = 200, received: String? = null,
                      actor: Outbound.Actor = Outbound.Actor.PHONE, hint: Outbound.Hint? = null) =
        Outbound.build(url, method, "https", headers, body?.toByteArray(), body?.length?.toLong() ?: 0, type, status,
            received?.length?.toLong() ?: -1, received?.toByteArray(), "application/json", null, 1_000, 5, actor, hint)

    @Test fun speechContentNeverAppearsInEitherOutboundPreview() {
        val e = build("https://bridgit-feedback.bridgit-app.workers.dev/v1/speech/hear",
            body = "{\"audio_base64\":\"private-audio\",\"text\":\"private-text\"}", received = "{\"transcript\":\"private-transcript\"}")
        assertNull(e.requestPreview); assertNull(e.responsePreview)
        assertFalse(e.toJson({ "t" }).toString().contains("private-"))
    }

    // ---- redaction --------------------------------------------------------------------------------

    @Test fun secretHeadersAreNeverStored() {
        val e = build("https://bridgit-feedback.bridgit-app.workers.dev/v1/jev", headers = mapOf(
            "Authorization" to "Bearer abcdefghijklmnop", "X-Bridgit-Signature" to "t=1,n=2,s=xyz", "X-Relay-Signature" to "sig",
            "X-Relay-Nonce" to "n1", "Cookie" to "a=b", "User-Agent" to "Bridgit/1.8 (Android 14)", "X-Bridgit-Install" to "inst-1"), body = "{}")
        val h = e.headers!!
        for (k in listOf("Authorization", "X-Bridgit-Signature", "X-Relay-Signature", "X-Relay-Nonce", "Cookie")) assertEquals(k, Redact.REDACTED, h.getString(k))
        assertEquals("Bridgit/1.8 (Android 14)", h.getString("User-Agent"))
        assertEquals("inst-1", h.getString("X-Bridgit-Install"))
        assertFalse(e.toJson({ "t" }).toString().contains("abcdefghijklmnop"))
    }

    @Test fun secretFieldsAndTokensInBodiesAreRedacted() {
        val body = JSONObject().put("token", "fcm-secret-123").put("api_key", "k").put("password", "p")
            .put("nested", JSONObject().put("signature", "s").put("note", "my key is brg_abcdefghijklmnopqrstuvwx"))
            .put("text", "Bearer abcdefghijklmnopqrstu and bgk_abcdefghijk12345").put("state", "fine").toString()
        val p = build("https://example.com/in", body = body).requestPreview!!
        val o = JSONObject(p)
        assertEquals(Redact.REDACTED, o.getString("token"))
        assertEquals(Redact.REDACTED, o.getString("api_key"))
        assertEquals(Redact.REDACTED, o.getString("password"))
        assertEquals(Redact.REDACTED, o.getJSONObject("nested").getString("signature"))
        assertFalse(p.contains("abcdefghijklmnopqrstuvwx")); assertFalse(p.contains("abcdefghijklmnopqrstu ")); assertFalse(p.contains("bgk_abcdefghijk12345"))
        assertEquals("fine", o.getString("state"))
    }

    @Test fun secretQueryParametersAreRedacted() {
        val e = build("https://example.com/hook?token=abc123&id=7&sig=1&auth=x&apikey=y&hmac=z&pwd=p", method = "GET")
        for (k in listOf("token", "sig", "auth", "apikey", "hmac", "pwd")) assertEquals(k, Redact.REDACTED, e.query!!.getString(k))
        assertEquals("7", e.query!!.getString("id"))
    }

    @Test fun secretPathSegmentsAreHidden() {
        val e = build("https://hooks.example.com/services/T0123ABCD/B0456EFGH/abcdEFGHijklMNOP1234qrst")
        assertEquals("/services/T0123ABCD/B0456EFGH/[token]", e.path)
        assertEquals("/v1/issues/42/history", build("https://bridgit-feedback.bridgit-app.workers.dev/v1/issues/42/history").path)
        assertEquals("/weatherapi/locationforecast/2.0/compact", build("https://api.met.no/weatherapi/locationforecast/2.0/compact").path)
    }

    @Test fun idempotencyKeyAndPublicKeyAreNotTreatedAsSecrets() {
        assertFalse(Redact.isSecretName("idempotency_key")); assertFalse(Redact.isSecretName("public_key"))
        assertTrue(Redact.isSecretName("install_key")); assertTrue(Redact.isSecretName("X-Bridgit-Signature"))
    }

    @Test fun relayPushFramesKeepTheTokenOut() {
        val e = build("wss://relay.example/d/1/socket", method = "SEND", body = JSONObject().put("type", "push").put("token", "FCM-TOKEN-VALUE").toString(),
            hint = Outbound.Hint("Bridgit relay", "push", listOf(Categories.PUSH_TOKEN)))
        assertFalse(e.requestPreview!!.contains("FCM-TOKEN-VALUE"))
        assertTrue(Categories.PUSH_TOKEN in e.categories)
        assertEquals(64, e.requestDigest!!.length)
    }

    // ---- size caps --------------------------------------------------------------------------------

    @Test fun previewsAreCappedAndMediaSummarised() {
        val big = JSONObject().put("images", JSONArray().put(JSONObject().put("data_base64", "A".repeat(200_000))))
            .put("description", "x".repeat(5000)).put("rows", JSONArray((1..500).map { "row $it ".repeat(20) })).toString()
        val e = build("https://bridgit-feedback.bridgit-app.workers.dev/v1/issues", body = big)
        assertTrue(e.requestPreview!!.length <= Redact.MAX_REQUEST_PREVIEW)
        assertTrue(e.requestPreview!!.contains("[200000 chars base64]"))
        assertEquals(big.length.toLong(), e.bytesSent)
        val r = build("https://example.com/", received = "y".repeat(10_000))
        assertTrue(r.responsePreview!!.length <= Redact.MAX_RESPONSE_PREVIEW)
    }

    @Test fun peoplePushesKeepNoBodyPreviewAndVectorsCollapse() {
        val emb = (1..512).joinToString(",", "[", "]") { "0.0$it" }
        val body = """{"applet":"pkg.k","people":{"sightings":[{"id":"8f3c-uuid","burst":"b-uuid","embedding":$emb,"crop":"Zm9v"}]}}"""
        val hidden = build("https://bridgit-feedback.bridgit-app.workers.dev/v1/watch/applet/push", body = body,
            hint = Outbound.Hint(categories = listOf(Categories.IMAGE), noBodyPreview = true))
        assertEquals(null, hidden.requestPreview)
        assertEquals(body.length.toLong(), hidden.bytesSent); assertTrue(hidden.requestDigest != null)
        assertTrue(Categories.IMAGE in hidden.categories)
        // Without the flag, a long list of numbers is only counted.
        val shown = build("https://example.com/x", body = """{"v":$emb,"short":[1,2,3]}""")
        assertFalse(shown.requestPreview!!.contains("0.01"))
        assertTrue(shown.requestPreview!!.contains("[512 numbers]"))
        assertTrue(shown.requestPreview!!.contains("[1,2,3]"))
    }

    @Test fun binaryBodiesAreDescribedNotStored() {
        val e = Outbound.build("https://example.com/up", "POST", "https", emptyMap(), ByteArray(3000) { 0 }, 3000, "image/jpeg", 200, 0, null, null, null, 0, 1, Outbound.Actor.PHONE, null)
        assertEquals("[3000 bytes image/jpeg]", e.requestPreview)
        assertTrue(Categories.IMAGE in e.categories)
    }

    // ---- categories ------------------------------------------------------------------------------

    @Test fun weatherCallsAreLocationRoundedToOneKm() {
        val e = build("https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=52.23&lon=21.01", method = "GET", body = null,
            headers = mapOf("User-Agent" to "Bridgit/1.8 (Android; https://bridgit.cc)"))
        assertEquals("MET Norway weather", e.service)
        assertTrue(Categories.LOCATION_1KM in e.categories)
        assertTrue(Categories.APP_VERSION in e.categories)
        assertFalse(Categories.LOCATION_EXACT in e.categories)
        val exact = build("https://api.met.no/x?lat=52.23123&lon=21.01123", method = "GET")
        assertTrue(Categories.LOCATION_EXACT in exact.categories)
    }

    @Test fun jevAndReportsGetTheirCategories() {
        val jev = build("https://bridgit-feedback.bridgit-app.workers.dev/v1/jev", headers = mapOf("X-Bridgit-Install" to "i"),
            body = JSONObject().put("state", JSONObject().put("battery_percent", 12)).put("questions", JSONObject()).toString(),
            actor = Outbound.Actor.agent("a1", "Claude"))
        assertTrue(jev.service.contains("Jev"))
        for (c in listOf(Categories.AGENT_STATE, Categories.QUESTIONS, Categories.INSTALL_ID, Categories.BATTERY)) assertTrue(c, c in jev.categories)
        assertEquals("Claude", jev.agent); assertEquals("a1", jev.agentId)
        val rep = build("https://bridgit-feedback.bridgit-app.workers.dev/v1/issues",
            body = JSONObject().put("description", "broken").put("log", JSONArray()).put("images", JSONArray()).toString())
        for (c in listOf(Categories.ISSUE_TEXT, Categories.LOG_ENTRIES, Categories.IMAGE)) assertTrue(c, c in rep.categories)
    }

    @Test fun installRegistrationIsLabelled() {
        assertTrue(build("https://bridgit-feedback.bridgit-app.workers.dev/v1/install/register").purpose.startsWith("Register this install"))
        assertTrue(build("https://bridgit-feedback.bridgit-app.workers.dev/v1/install/challenge").purpose.contains("challenge"))
    }

    @Test fun unknownDestinationsStillGetDetectedCategories() {
        val e = build("https://new-service.example/api", body = JSONObject().put("latitude", 1.234567).put("longitude", 2.5).put("email", "a@b.c").toString())
        assertEquals("new-service.example", e.service)
        assertTrue(Categories.LOCATION_EXACT in e.categories); assertTrue(Categories.EMAIL in e.categories)
    }

    @Test fun hintsOverrideServiceAndPurpose() {
        val e = build("https://hooks.example/x", hint = Outbound.Hint("Agent's callback URL", "Interaction callback", listOf(Categories.OWNER_ANSWER)))
        assertEquals("Agent's callback URL", e.service); assertEquals("Interaction callback", e.purpose)
        assertTrue(Categories.OWNER_ANSWER in e.categories)
    }

    // ---- actor attribution and the sink -------------------------------------------------------------

    @Test fun actorIsTakenFromTheThreadAndRestored() {
        val got = ArrayList<OutboundEntry>()
        Outbound.attach { got += it }
        Outbound.on(Outbound.Actor.agent("a9", "Grok")) { Outbound.record("https://api.met.no/x", "GET", status = 200, startedAt = 0) }
        Outbound.record("https://api.met.no/y", "GET", status = 200, startedAt = 0)
        assertEquals("Grok", got[0].agent); assertEquals("phone", got[1].agent)
    }

    @Test fun earlyEntriesWaitForTheStoreAndCallWrapperRecordsErrors() {
        Outbound.record("https://api.met.no/x", "GET", status = 200, startedAt = 0)
        val got = ArrayList<OutboundEntry>()
        Outbound.attach { got += it }
        assertEquals(1, got.size)
        runCatching { Outbound.call("https://alphacephei.com/m.zip") { m -> m.status = 503; throw IllegalStateException("boom") } }
        assertEquals(503, got[1].status); assertTrue(got[1].error!!.contains("boom")); assertFalse(got[1].ok)
    }

    @Test fun handedOffEntriesCountAsOk() {
        val e = build("https://sentry.example/api/", status = OutboundEntry.HANDED_OFF)
        assertTrue(e.ok)
        val j = e.toJson({ "t" })
        assertEquals(JSONObject.NULL, j.get("status")); assertEquals("handed_to_sdk", j.getString("delivery"))
    }

    // ---- query and summary --------------------------------------------------------------------------

    @Test fun queryFiltersBecomeBoundSql() {
        val q = OutboundQuery.parse(mapOf("agent" to "Claude,phone", "destination" to "met.no", "category" to "location_1km,image",
            "result" to "error", "since" to "12", "method" to "get", "limit" to "50"), { if (it == "Claude") "a1" else null })
        val (where, args) = q.where()
        assertTrue(where.contains("agent_id IN (?)")); assertTrue(where.contains("agent_id IS NULL AND agent IN (?)"))
        assertTrue(where.contains("host LIKE ?")); assertTrue(where.contains("categories LIKE ?")); assertTrue(where.contains("id > ?"))
        assertTrue(args.contains("a1")); assertTrue(args.contains("phone")); assertTrue(args.contains("%,location\\_1km,%")); assertTrue(args.contains("GET"))
        assertEquals(50, q.limit)
    }

    @Test fun badFiltersAreRefused() {
        for (p in listOf(mapOf("limit" to "0"), mapOf("result" to "maybe"), mapOf("since" to "yesterday"), mapOf("agent" to "nobody"), mapOf("method" to "G E T")))
            assertTrue(p.toString(), runCatching { OutboundQuery.parse(p, { null }) }.exceptionOrNull() is cc.bridgit.app.log.QueryError)
    }

    @Test fun scopeLimitsAgentsToTheirOwnCalls() {
        val mine = OutboundQuery.parse(mapOf("agent" to "phone"), { null }).scopedTo(listOf("a1"))
        assertEquals(emptyList<String>(), mine.agentIds); assertEquals(emptyList<String>(), mine.actors)
        assertEquals("0", mine.where().first.substringBefore(" ")) // matches nothing: phone calls aren't the agent's
        val all = OutboundQuery().scopedTo(listOf("a1"))
        assertEquals(listOf("a1"), all.agentIds)
        assertNull(OutboundQuery().scopedTo(null).agentIds)
    }

    @Test fun summaryTotalsByDestinationCategoryAgentAndDay() {
        val day = 1_762_041_600_000L // 2025-11-02T00:00Z
        val es = listOf(
            OutboundEntry(1, day + 1000, "api.met.no", "MET Norway weather", "GET", "/", "Weather", "a1", "Claude", listOf("location_1km"), 100, 2000, 200, null, 5, "https"),
            OutboundEntry(2, day + 2000, "api.met.no", "MET Norway weather", "GET", "/", "Weather", null, "phone", listOf("location_1km"), 100, -1, 0, "TIMEOUT", 5, "https"),
            OutboundEntry(3, day + 86_400_000L, "x.workers.dev", "Jev", "POST", "/v1/jev", "Jev", "a1", "Claude", listOf("agent_state", "install_id"), 900, 300, 200, null, 5, "https"),
        )
        val s = OutboundSummary.of(es, ZoneOffset.UTC)
        val t = s.getJSONObject("totals")
        assertEquals(3, t.getInt("calls")); assertEquals(1, t.getInt("failed")); assertEquals(1100, t.getLong("bytes_sent")); assertEquals(2300, t.getLong("bytes_received"))
        val dest = s.getJSONArray("by_destination")
        assertEquals("Jev", dest.getJSONObject(0).getString("service")) // most bytes sent first
        assertEquals(2, dest.getJSONObject(1).getInt("calls"))
        val agents = s.getJSONArray("by_agent")
        assertEquals("Claude", agents.getJSONObject(0).getString("agent")); assertEquals(1000, agents.getJSONObject(0).getLong("bytes_sent"))
        assertEquals(2, s.getJSONArray("by_day").length()); assertEquals("2025-11-02", s.getJSONArray("by_day").getJSONObject(0).getString("day"))
        assertEquals(2, (0 until s.getJSONArray("by_category").length()).map { s.getJSONArray("by_category").getJSONObject(it) }.first { it.getString("category") == "location_1km" }.getInt("calls"))
    }

    @Test fun summaryAnswersWhatLeftTodayAndToWhom() {
        val start = 1_762_041_600_000L // 2025-11-02T00:00Z
        val h = 3_600_000L
        val es = listOf(
            OutboundEntry(1, start + 1 * h, "api.met.no", "MET Norway weather", "GET", "/", "Weather", "a1", "Claude", listOf("location_1km"), 100, 2000, 200, null, 5, "https"),
            OutboundEntry(2, start + 2 * h, "x.workers.dev", "Jev", "POST", "/v1/jev", "Jev decision", "a1", "Claude", listOf("agent_state"), 5000, 300, 200, null, 5, "https"),
            OutboundEntry(3, start + 2 * h + 60_000, "hooks.example", "Agent's callback URL", "POST", "/in", "Callback", "a2", "Grok", listOf("owner_answer"), 300, -1, 0, "TIMEOUT: Took too long.", 5, "https"),
        )
        // met.no was seen before the window; the other two hosts are new today.
        val first = mapOf("api.met.no" to start - 5 * 86_400_000L, "x.workers.dev" to start + 2 * h, "hooks.example" to start + 2 * h + 60_000)
        val s = OutboundSummary.of(es, ZoneOffset.UTC, first, windowStart = start, windowEnd = start + 3 * h)
        val headline = s.getString("headline")
        assertTrue(headline, headline.startsWith("Since 00:00 today: 3 calls sent 5.3 KB to 3 destinations: Jev (4.9 KB, 1 call)"))
        assertTrue(headline, headline.contains("1 failed")); assertTrue(headline, headline.contains("New destinations: x.workers.dev, hooks.example"))
        assertEquals("hour", s.getJSONObject("window").getString("bucket"))
        assertEquals(2, s.getJSONArray("by_time").length()) // 01:00 and 02:00
        assertEquals(2, s.getJSONArray("by_time").getJSONObject(1).getInt("calls"))
        val biggest = s.getJSONObject("biggest")
        assertEquals("Jev", biggest.getJSONArray("destinations").getJSONObject(0).getString("service"))
        assertEquals("Claude", biggest.getJSONArray("agents").getJSONObject(0).getString("agent"))
        assertEquals(2L, biggest.getJSONArray("calls").getJSONObject(0).getLong("id"))
        val news = s.getJSONArray("new_destinations")
        assertEquals(listOf("x.workers.dev", "hooks.example"), (0 until news.length()).map { news.getJSONObject(it).getString("host") })
        val f = s.getJSONObject("failures")
        assertEquals(1, f.getInt("count")); assertEquals("TIMEOUT", f.getJSONArray("by_error").getJSONObject(0).getString("error"))
        assertEquals("Grok", f.getJSONArray("recent").getJSONObject(0).getString("agent"))
        val met = (0 until s.getJSONArray("by_destination").length()).map { s.getJSONArray("by_destination").getJSONObject(it) }.first { it.getString("host") == "api.met.no" }
        assertFalse(met.getBoolean("new")); assertEquals("location_1km", met.getJSONArray("categories").getString(0))
        assertEquals("Claude", met.getJSONArray("agents").getJSONObject(0).getString("agent"))
        val cat = (0 until s.getJSONArray("by_category").length()).map { s.getJSONArray("by_category").getJSONObject(it) }.first { it.getString("category") == "agent_state" }
        assertEquals("Jev", cat.getJSONArray("destinations").getJSONObject(0).getString("service"))
        assertEquals("Jev", s.getJSONArray("by_agent").getJSONObject(0).getJSONArray("destinations").getJSONObject(0).getString("service"))
        assertEquals(3, s.getJSONObject("totals").getInt("destinations"))
    }

    @Test fun summaryOfNothingSaysSoAndLongWindowsUseDays() {
        val s = OutboundSummary.of(emptyList(), ZoneOffset.UTC, windowStart = 0, windowEnd = 10L * 86_400_000L)
        assertTrue(s.getString("headline").endsWith("nothing was sent off the phone."))
        assertEquals("day", s.getJSONObject("window").getString("bucket"))
    }

    @Test fun nothingIsRecordedWhileTheLogIsOff() {
        val got = ArrayList<OutboundEntry>()
        Outbound.attach { got += it }
        Outbound.recording = { false }
        try {
            Outbound.record("https://api.met.no/x", "GET", status = 200, startedAt = 0)
            assertEquals(0, got.size)
        } finally { Outbound.recording = { true } }
        Outbound.record("https://api.met.no/x", "GET", status = 200, startedAt = 0)
        assertEquals(1, got.size)
    }

    // ---- nothing missed silently ---------------------------------------------------------------------

    /**
     * Every way the app opens a connection must be one the outbound log covers. A new OkHttp client needs
     * the interceptor; a new raw connection needs an Outbound.call/record next to it (then add the file here).
     */
    @Test fun everyNetworkPrimitiveIsRecorded() {
        val root = File("src/main/java/cc/bridgit/app")
        val recordedRaw = setOf("ssh/SshClient.kt", "lancamera/CameraRtsp.kt", "lancamera/TapoTalk.kt", "lan/LanDiscovery.kt", "core/SafeHttp.kt", "update/UpdateInstaller.kt", "update/TailscaleInstaller.kt", "listen/WakeModel.kt", "platform/DeliveryManager.kt", "vision/FaceEmbedder.kt", "vision/OnDeviceModels.kt", "satellite/HubLinks.kt")
        // Never leave the phone: the add-agent check connects to the phone's own listener; api/engine is the server; adb/ talks to this device.
        val notOffPhone = setOf("ui/addagent/AddAgentViewModel.kt")
        val problems = ArrayList<String>()
        root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.forEach { f ->
            val rel = f.relativeTo(root).path
            val src = f.readText()
            Regex("OkHttpClient\\.Builder\\(\\)(?!\\.addInterceptor\\(cc\\.bridgit\\.app\\.outbound\\.OutboundInterceptor\\))").findAll(src)
                .forEach { problems += "$rel: OkHttpClient without OutboundInterceptor" }
            if (Regex("openConnection\\(|\\bSocket\\(\\)|HttpsURLConnection\\b|DatagramSocket\\(").containsMatchIn(src) && rel !in recordedRaw && rel !in notOffPhone && !rel.startsWith("api/engine/") && !rel.startsWith("adb/"))
                problems += "$rel: raw connection not covered by the outbound log"
            if (rel in recordedRaw && !src.contains("Outbound.")) problems += "$rel: listed as recorded but has no Outbound call"
        }
        assertEquals(problems.joinToString("\n"), 0, problems.size)
    }
}
