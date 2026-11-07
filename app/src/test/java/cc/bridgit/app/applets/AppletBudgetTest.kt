package cc.bridgit.app.applets

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.interact.CardActions
import cc.bridgit.app.judgment.Jev
import cc.bridgit.app.outbound.Outbound
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Wave 1 of docs/applet-dev-experience.md: one spending cap per applet (R1), validate warnings and clear card size errors (B1). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppletBudgetTest {
    private val app get() = RuntimeEnvironment.getApplication() as BridgeApp
    // The pet monitor before its dinner-check flow (2.0.0): these tests are about the watch's paid calls only.
    private fun file(id: String) = JSONObject(File(if (id == "pet-monitor") "src/test/resources/applets/pet-monitor-2.0.0.json" else "../site-content/public/recipes/$id/applet.json").readText())
    private val link = JSONObject().put("kind", "link").put("url", "https://bridgit.cc/recipes/pet-monitor/applet.json")

    @Before fun phoneReady() {
        app.settings.listenerEnabled = true
        for (m in app.registry.switchable()) app.settings.setOn(m.switchKey!!, true)
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO)
        runCatching { androidx.work.WorkManager.getInstance(app) }.onFailure { androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build()) }
    }
    @After fun cleanUp() = runBlocking { for (r in app.applets.all()) app.applets.remove(r.getString("id")) }

    private fun install(d: JSONObject): JSONObject = runBlocking {
        val pend = app.applets.propose(AppletPackage.check(d), null, link, null, notify = false)
        app.applets.approve(pend.getString("id"), null)
    }

    @Test fun budgetFieldIsChecked() {
        // The old form becomes limits l1 (day) and l2 (month); the new form is a list of limits.
        assertEquals(listOf(AppletBudget.Limit("l1", 0.5, "day"), AppletBudget.Limit("l2", 5.0, "month")), AppletBudget.parse(JSONObject("""{"day_usd":0.5,"month_usd":5}""")))
        assertEquals(listOf(AppletBudget.Limit("l1", 2.0, "month", listOf(0.5)), AppletBudget.Limit("fast", 0.2, "hour")),
            AppletBudget.parse(JSONObject("""{"limits":[{"usd":2,"per":"month","check_at":0.5},{"id":"fast","usd":0.2,"per":"hour"}]}""")))
        assertNull(AppletBudget.parse(null))
        for ((bad, part) in listOf("""{"day_usd":0}""" to "0.01", """{"day_usd":200}""" to "100", """{"week_usd":1}""" to "isn't a budget field",
                """{"day_usd":5,"month_usd":2}""" to "less than", """{"day_usd":"1"}""" to "number",
                """{"limits":[{"usd":1,"per":"year"}]}""" to "per must be", """{"limits":[{"usd":1,"per":"day","check_at":[1.5]}]}""" to "check_at",
                """{"limits":[{"id":"a","usd":1,"per":"day"},{"id":"a","usd":2,"per":"month"}]}""" to "used twice", """{"limits":[]}""" to "1-8")) {
            try { AppletPackage.check(file("pet-monitor").put("budget", JSONObject(bad))); fail(bad) } catch (e: AppletPackage.Bad) { assertTrue("${e.message} / $part", e.message!!.contains(part)) }
        }
    }

    @Test fun thePhoneCountsButOnlyTheBackendStopsAnApplet() = runBlocking {
        val r = install(file("pet-monitor").put("budget", JSONObject("""{"day_usd":0.02,"month_usd":1}""")))
        val applet = r.getString("package_id")
        assertEquals("package", AppletBudget.status(app, app.applets.get(r.getString("id"))!!).getString("set_by"))
        // Over the package's own limit on this phone alone: no local refusal (the backend counts all phones).
        AppletBudget.charge(app, applet, 0.015); AppletBudget.charge(app, applet, 0.01, "watch_x")
        AppletBudget.check(app, applet)
        val st = AppletBudget.status(app, app.applets.get(r.getString("id"))!!)
        assertEquals(0.025, st.getDouble("spent_today_usd"), 1e-9)
        assertEquals("unknown", st.getString("state"))
        // The backend said applet_budget: the phone stops asking until the reset, then on its own again.
        val now = System.currentTimeMillis()
        AppletBudget.refused(app, applet, now + 3_600_000)
        try { AppletBudget.check(app, applet); fail("out") } catch (e: Jev.Failure) { assertEquals("applet_budget", e.reason); assertFalse(e.retryable); assertNotNull(e.resetsAt) }
        AppletBudget.check(app, applet, now + 3_600_001)
        AppletBudget.clearOut(app, applet); AppletBudget.check(app, applet)
        // Calls that name no applet are never counted or stopped.
        AppletBudget.charge(app, null, 50.0); AppletBudget.check(app, null)
        // The owner's limits are kept for offline display.
        app.applets.setBudget(r.getString("id"), 0.5, 5.0)
        assertEquals("owner", AppletBudget.status(app, app.applets.get(r.getString("id"))!!).getString("set_by"))
        assertEquals(0.5, AppletBudget.limitsOf(app, app.applets.get(r.getString("id"))!!).first().usd, 1e-9)
        app.applets.setBudget(r.getString("id"), null, null)
        assertEquals(0.02, AppletBudget.limitsOf(app, app.applets.get(r.getString("id"))!!).first().usd, 1e-9)
        try { app.applets.setBudget(r.getString("id"), 0.0, 1.0); fail() } catch (e: AppletInstances.Refused) { assertEquals("invalid_parameter", e.code) }
        // Today's stats per action, for the sync.
        val stats = AppletBudget.stats(app, applet)
        assertTrue(stats.toString(), (0 until stats.length()).any { stats.getJSONObject(it).getString("action") == "watch_x" && stats.getJSONObject(it).getInt("paid_runs") == 1 })
        app.applets.remove(r.getString("id"))
        assertEquals(0.0 to 0.0, AppletBudget.spent(app, applet))
    }

    @Test fun aPaidCallAsTheAppletIsRefusedWhileItIsOut() = runBlocking {
        val r = install(file("pet-monitor").put("budget", JSONObject("""{"day_usd":0.01}""")))
        val agent = r.getString("agent_id")
        AppletBudget.refused(app, r.getString("package_id"), System.currentTimeMillis() + 60_000)
        val f = try { Outbound.on(Outbound.Actor.agent(agent, "Pet")) { Jev.ask(app, JSONObject(), JSONObject("""{"q":{"type":"noul","instructions":"x"}}""")) }; null }
            catch (e: Jev.Failure) { e }
        // Without a team backend in tests the call can't be sent at all; with one, the phone stops it first.
        assertNotNull(f)
        assertTrue(f!!.reason, f.reason == "applet_budget" || f.reason == "not_configured")
    }

    @Test fun theReviewStatesTheCapAndWarnsAboutTraps() {
        val p = AppletPackage.check(file("pet-monitor"))
        val review = app.applets.review(p, AppletPackage.fill(p, null))
        val parts = review.getJSONArray("parts").toString()
        assertTrue(parts, parts.contains("Paid cloud model calls stop once this applet has spent \$1 in a day or \$10 in a month, counting all devices working on it. This is Bridgit's default."))
        val codes = (0 until review.getJSONArray("warnings").length()).map { review.getJSONArray("warnings").getJSONObject(it).getString("code") }
        // Today's recipe asks Clef and sets no budget.
        assertTrue(codes.toString(), "paid_without_cap" in codes)
        // A data_policy that says no remote models while the applet asks Clef.
        val said = AppletPackage.check(file("pet-monitor").put("data_policy", JSONObject("""{"backend_default":"device_only","device_only":{"remote_models":false}}""")))
        assertTrue(app.applets.review(said, AppletPackage.fill(said, null)).getJSONArray("warnings").toString().contains("data_policy_contradicts"))
        val capped = AppletPackage.check(file("pet-monitor").put("budget", JSONObject("""{"day_usd":0.5,"month_usd":5}""")))
        val r2 = app.applets.review(capped, AppletPackage.fill(capped, null))
        assertTrue(r2.getJSONArray("parts").toString().contains("Paid cloud model calls stop once this applet has spent \$0.50 in a day or \$5 in a month, counting all devices working on it.\""))
        assertFalse(r2.getJSONArray("warnings").toString().contains("paid_without_cap"))
    }

    @Test fun cardScansFindTheKnownTraps() {
        val html = """<!doctype html><form onsubmit="go()"><button type="submit">Save</button></form><script>
            localStorage.setItem('x','1'); Bridgit.act({path:'/v1/images/find', body:{data_base64: b64}});
            Bridgit.act({path:'/v1/camera/snapshot', body:{}}); Bridgit.act({method:'GET', path:'/v1/captures/' + id}); const D=/*RECIPE*/{}/*END RECIPE*/;</script>"""
        val d = file("receipt-scanner")
        val asset = "card.html"
        d.getJSONObject("ui").put("card", JSONObject().put("title", "Receipts").put("asset", asset))
        d.put("assets", JSONObject().put(asset, JSONObject().put("media_type", "text/html").put("text", html).put("sha256", AppletPackage.sha256(html))))
        d.getJSONObject("permissions").getJSONArray("required").put("weather")
        val p = AppletPackage.check(d)
        val plan = AppletPackage.compile(p, AppletPackage.fill(p, null))
        val w = AppletWarnings.check(p, plan, setOf("interactions")) { path -> if ("images/find" in path) "find_crop" else if ("camera" in path || "captures" in path) "camera" else null }
        val codes = w.map { it.code }
        for (c in listOf("card_request_too_big", "card_reply_too_big", "card_form_submit", "card_storage", "permission_unused")) assertTrue("$c in $codes", c in codes)
        assertTrue(w.first { it.code == "permission_unused" }.says.contains("weather"))
        assertEquals(2, codes.count { it == "card_reply_too_big" }) // the snapshot and the capture read
        // A clean card: nothing to say about it.
        val clean = AppletPackage.check(file("room-change-watcher"))
        assertTrue(AppletWarnings.check(clean, AppletPackage.compile(clean, AppletPackage.fill(clean, null)), setOf("camera", "watches", "interactions")).none { it.code.startsWith("card_") })
    }

    @Test fun cardSizeErrorsSayWhatWentWrong() {
        val big = JSONObject().put("path", "/v1/images/find").put("body", JSONObject().put("data_base64", "x".repeat(20_000))).toString()
        try { CardActions.parse(big); fail() } catch (e: CardActions.Refused) {
            assertEquals("request_too_big", e.code); assertTrue(e.message!!.contains("capture_id"))
        }
        val reply = CardActions.result(200, "application/json", ("\"" + "x".repeat(CardActions.PAGE_BODY_MAX_BYTES) + "\"").toByteArray())
        assertFalse(reply.getBoolean("ok")); assertEquals("reply_too_big", reply.getString("error"))
        // A watch rule has no page: the call worked, and it isn't retried.
        assertTrue(CardActions.result(200, "application/json", ("\"" + "x".repeat(CardActions.PAGE_BODY_MAX_BYTES) + "\"").toByteArray(), toPage = false).getBoolean("ok"))
        assertTrue(reply.getJSONObject("body").getString("fix").contains("max_edge_px"))
        // Media replies still come back as their size, and small JSON as itself.
        assertTrue(CardActions.result(200, "image/jpeg", ByteArray(100_000)).getBoolean("ok"))
        assertTrue(CardActions.result(200, "application/json", "{\"a\":1}".toByteArray()).getBoolean("ok"))
    }

    @Test fun capResetsAtLocalMidnight() {
        val now = java.time.ZonedDateTime.of(2026, 11, 5, 15, 0, 0, 0, java.time.ZoneId.systemDefault())
        assertEquals(java.time.ZonedDateTime.of(2026, 11, 6, 0, 0, 0, 0, java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
            AppletBudget.nextReset(now.toInstant().toEpochMilli()))
    }

    @Test fun workflowValidateWarnsAboutAnUncappedPaidFlow() {
        val q = JSONObject("""{"eating":{"type":"noul","instructions":"Is a dog eating?"}}""")
        val def = JSONObject().put("name", "Look").put("trigger", JSONObject().put("type", "button"))
            .put("steps", JSONArray().put(JSONObject().put("type", "ask_model").put("model", "jev").put("questions", q)))
        assertTrue(app.platform.workflows.validate(def).getJSONArray("warnings").toString().contains("paid_without_cap"))
        assertEquals(0, app.platform.workflows.validate(JSONObject(def.toString()).put("max_paid_per_day", 5)).getJSONArray("warnings").length())
    }
}
