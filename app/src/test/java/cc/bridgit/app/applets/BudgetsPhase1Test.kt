package cc.bridgit.app.applets

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.api.ApiServer
import cc.bridgit.app.core.Connection
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Budgets phase 1 on the phone (docs/plans/budgets-phase1-contract.md §9, App). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BudgetsPhase1Test {
    private val app get() = RuntimeEnvironment.getApplication() as BridgeApp
    private fun pet() = JSONObject(File("src/test/resources/applets/pet-monitor-2.0.0.json").readText())
    private val link = JSONObject().put("kind", "link").put("url", "https://bridgit.cc/recipes/pet-monitor/applet.json")
    /** The sync's requests, and what the fake backend answers next. */
    private val sent = mutableListOf<Triple<String, String, JSONObject?>>()
    private var answer: (String, String, JSONObject?) -> Pair<Int, JSONObject> = { _, _, _ -> 404 to JSONObject() }

    @Before fun phoneReady() {
        app.settings.listenerEnabled = true
        for (m in app.registry.switchable()) app.settings.setOn(m.switchKey!!, true)
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO)
        runCatching { androidx.work.WorkManager.getInstance(app) }.onFailure { androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build()) }
        app.budgetSync.transport = { m, p, b -> sent += Triple(m, p, b); answer(m, p, b) }
    }

    @After fun cleanUp() = runBlocking {
        app.budgetSync.transport = null
        for (r in app.applets.all()) app.applets.remove(r.getString("id"))
        app.applets.pending().forEach { app.applets.pendingDrop(it.getString("id")) }
    }

    private fun install(d: JSONObject, by: cc.bridgit.app.core.Agent? = null): JSONObject = runBlocking {
        // An agent's proposal carries the practice review record (AppletPractices); the owner's link does not need one.
        if (by != null) d.getJSONObject("ui").put("practices", JSONObject("""{"contract":"bridgit-practices/1","catalog":"1.0.0","review":{"decision":"no-new-practice","reason":"Test budgets with the unchanged Pet monitor package.","practice_ids":["GOV-001"]}}"""))
        val pend = app.applets.propose(AppletPackage.check(d), null, link, by, notify = false)
        app.applets.approve(pend.getString("id"), null)
    }

    private fun reply(applet: String, state: String, extra: JSONObject.() -> Unit = {}) = JSONObject().put("account", "phone").put("timezone", "Europe/Warsaw")
        .put("applets", JSONArray().put(JSONObject().put("applet", applet).put("state", state).put("revision", 1)
            .put("limits", JSONArray().put(JSONObject().put("id", "l2").put("usd", 2).put("per", "month").put("used_usd", if (state == "out") 2.0 else 0.4)
                .put("top_up_usd", 0).put("resets_at", "2030-12-01T00:00:00+01:00").put("forecast_usd", 0.9)))
            .put("source", "package").put("agents_may_change", true).apply(extra)))

    @Test fun libraryBudgetConfirmationFollowsTheReviewedOwnerAndEdition() {
        val instance = JSONObject().put("id","ai123").put("name","Chef")
        val pin = JSONObject().put("owner","a".repeat(64)).put("key","private-chef").put("revision",2).put("applet","chef")
        answer = { _,_,_ -> 200 to reply("chef","ok") }
        assertThrows(BudgetSync.Refused::class.java) { app.budgetSync.confirmEditionBudget(instance,pin) }
        answer = { _,_,_ -> 200 to reply("chef","ok").put("account","linked") }
        app.budgetSync.confirmEditionBudget(instance,pin)
        val calls = sent.size
        app.budgetSync.confirmEditionBudget(instance,pin)
        assertEquals(calls,sent.size)
        assertEquals("a".repeat(64),sent.last().third!!.getString("library_owner"))
        pin.put("owner","b".repeat(64))
        answer = { _,_,_ -> 403 to JSONObject().put("error","library_unlinked") }
        assertThrows(BudgetSync.Refused::class.java) { app.budgetSync.confirmEditionBudget(instance,pin) }
        assertEquals(calls+1,sent.size)
    }

    @Test fun paidCallsNameTheAppletAndAction() {
        assertEquals(mapOf("X-Bridgit-Applet" to "kitchen-cleanliness", "X-Bridgit-Action" to "main"), Jev.budgetHeaders("kitchen-cleanliness", null))
        assertEquals("analyse_photo", Jev.budgetHeaders("kitchen-cleanliness", "analyse_photo")["X-Bridgit-Action"])
        assertTrue(Jev.budgetHeaders(null, "x").isEmpty())
        // The Pet monitor's calls say applet dog; an applet's own agent gives its package id; anyone else none.
        assertEquals("dog", Jev.appletFor(app, "dog", null))
        val r = install(pet())
        assertEquals("pet-monitor", Jev.appletFor(app, null, r.getString("agent_id")))
        val other = app.agents.add("BudgetsOther", Connection.DIRECT).first
        assertNull(Jev.appletFor(app, null, other.id))
        assertNull(Jev.appletFor(app, null, null))
        // A watch's calls carry its action (the package watch id when the package declares no actions).
        val w = app.watches.list(r.getString("agent_id")).single()
        assertEquals("pet-monitor" to "main", app.watches.budgetKey!!(w))
    }

    @Test fun anAppletBudgetRefusalIsReadable() {
        val body = """{"error":"applet_budget","message":"Kitchen cleanliness used its ${'$'}2.00 monthly budget (all phones).","applet":"kitchen-cleanliness",
            "limit":{"id":"l2","usd":2,"per":"month"},"resets_at":"2026-12-01T00:00:00+01:00"}"""
        val f = try { Jev.parse(429, body); null } catch (e: Jev.Failure) { e }!!
        assertEquals("applet_budget", f.reason); assertFalse(f.retryable); assertEquals(429, f.http)
        assertEquals("kitchen-cleanliness", f.applet)
        assertEquals(java.time.OffsetDateTime.parse("2026-12-01T00:00:00+01:00").toInstant().toEpochMilli(), f.resetsAt)
        assertEquals("l2", f.extra!!.getJSONObject("limit").getString("id"))
        assertFalse("applet_budget" in cc.bridgit.app.judgment.OwnKeys.FALLBACK_REASONS)
    }

    @Test fun outPausesTheAppletsWatchesUntilAnOkSync() = runBlocking {
        val r = install(pet())
        val agent = r.getString("agent_id")
        val w = app.watches.list(agent).single()
        answer = { _, _, _ -> 200 to reply("pet-monitor", "out") }
        assertNotNull(app.budgetSync.syncNow())
        assertEquals(cc.bridgit.app.applets.BudgetSync.SYNC_PATH, sent.last().second)
        assertEquals("pet-monitor", sent.last().third!!.getJSONArray("applets").getJSONObject(0).getString("applet"))
        assertTrue(w.budgetPaused())
        assertEquals("applet_budget", w.json(false).getString("paused"))
        assertEquals(java.time.OffsetDateTime.parse("2030-12-01T00:00:00+01:00").toInstant().toEpochMilli(), w.budgetPausedUntil)
        // Saved, so it survives a restart.
        assertTrue(app.getSharedPreferences("watches", android.content.Context.MODE_PRIVATE).getString(w.id, "")!!.contains("budget_paused_until"))
        val paused = app.interactions.eventsFor(agent, null, setOf("watch.paused"))
        assertEquals(1, paused.size); assertEquals("applet_budget", paused[0].data.getString("reason"))
        // Locally refused while out.
        try { AppletBudget.check(app, "pet-monitor"); fail() } catch (e: Jev.Failure) { assertEquals("applet_budget", e.reason) }
        // A second "out" sync doesn't repeat the event.
        app.budgetSync.syncNow()
        assertEquals(1, app.interactions.eventsFor(agent, null, setOf("watch.paused")).size)
        // ok: running again at once.
        answer = { _, _, _ -> 200 to reply("pet-monitor", "ok") }
        app.budgetSync.syncNow()
        assertFalse(w.budgetPaused()); assertTrue(w.json(false).isNull("paused"))
        assertEquals(1, app.interactions.eventsFor(agent, null, setOf("watch.resumed")).size)
        AppletBudget.check(app, "pet-monitor")
    }

    @Test fun anAppletBudgetRefusalPausesEveryWatchUntilResetsAt() {
        val r = install(pet())
        val agent = r.getString("agent_id")
        val w = app.watches.list(agent).single()
        val f = Jev.Failure("applet_budget", "Pet monitor used its budget.", http = 429,
            extra = JSONObject().put("error", "applet_budget").put("applet", "pet-monitor").put("resets_at", "2030-12-01T00:00:00+01:00"))
        app.watches.failed(w, f, JSONObject(), System.currentTimeMillis(), System.currentTimeMillis())
        assertEquals(java.time.OffsetDateTime.parse("2030-12-01T00:00:00+01:00").toInstant().toEpochMilli(), w.budgetPausedUntil)
        assertEquals("applet_budget", w.json(false).getString("paused"))
        val ev = app.interactions.eventsFor(agent, null, setOf("watch.paused"))
        assertEquals(1, ev.size); assertEquals("applet_budget", ev[0].data.getString("reason"))
        // Not ended, and an interval below the watch rules' floor is clamped to it.
        assertNotNull(app.watches.byId(w.id))
        app.watches.setInterval(w.id, 5)
        assertTrue(w.intervalS >= cc.bridgit.app.watch.WatchRules.MIN_INTERVAL_TALLY_S)
    }

    @Test fun anUnknownBackendRefusesNothing() {
        val r = install(pet())
        answer = { _, _, _ -> 404 to JSONObject() }
        assertNull(app.budgetSync.syncNow())
        answer = { _, _, _ -> 503 to JSONObject() }
        assertNull(app.budgetSync.syncNow())
        AppletBudget.check(app, "pet-monitor")
        assertFalse(app.watches.list(r.getString("agent_id")).single().budgetPaused())
    }

    @Test fun anIntervalChangesInPlaceAndKeepsTheTally() {
        val r = install(pet())
        val w = app.watches.list(r.getString("agent_id")).single()
        val tally = w.tally
        val own = w.spec.intervalS
        w.checks = 7
        assertTrue(app.watches.setInterval(w.id, 1200))
        assertEquals(1200, w.intervalS); assertEquals(1200, w.json(false).getInt("interval_s")); assertEquals(own, w.json(false).getInt("package_interval_s"))
        assertSame(tally, w.tally); assertEquals(7, w.checks)
        assertTrue(app.getSharedPreferences("watches", android.content.Context.MODE_PRIVATE).getString(w.id, "")!!.contains("\"interval_s_override\":1200"))
        // The sync's actions set it too (the package's own interval clears the override).
        answer = { _, _, _ -> 200 to reply("pet-monitor", "ok") { put("actions", JSONArray().put(JSONObject().put("action", "main").put("every_s", own))) } }
        app.budgetSync.syncNow()
        assertEquals(own, w.intervalS); assertNull(w.intervalOverrideS)
        answer = { _, _, _ -> 200 to reply("pet-monitor", "ok") { put("actions", JSONArray().put(JSONObject().put("action", "main").put("every_s", 900))) } }
        app.budgetSync.syncNow()
        assertEquals(900, w.intervalS); assertSame(tally, w.tally)
    }

    @Test fun actionsAreParsedAndDerived() {
        // No actions: one per watch, from its interval.
        val p = AppletPackage.check(pet())
        val derived = AppletActions.of(p.definition, AppletPackage.compile(p, AppletPackage.fill(p, null)))
        assertEquals(listOf("main"), derived.map { it.id }); assertFalse(derived[0].declared); assertEquals("camera", derived[0].per)
        assertTrue(derived[0].minS <= derived[0].everyS && derived[0].everyS <= derived[0].maxS)
        // Declared actions, named by the watch.
        val d = pet().put("actions", JSONObject("""{"analyse_photo":{"every":600,"min":300,"max":3600,"per":"camera","priority":2},"value_items":{"at_most_every":21600,"max":86400}}"""))
        d.getJSONObject("processing").getJSONArray("watches").getJSONObject(0).put("action", "analyse_photo")
        val q = AppletPackage.check(d)
        val actions = AppletActions.of(q.definition, AppletPackage.compile(q, AppletPackage.fill(q, null)))
        assertEquals(listOf("analyse_photo", "value_items"), actions.map { it.id })
        assertEquals(listOf("main"), actions[0].watches); assertEquals(2, actions[0].priority); assertEquals(21600, actions[1].everyS)
        assertEquals("analyse_photo", AppletActions.actionOfWatch(q.definition, "main"))
        assertEquals(JSONObject("""{"action":"analyse_photo","every_s":600,"min_s":300,"max_s":3600,"per":"camera","priority":2}""").toString(), actions[0].json().toString())
        for ((bad, part) in listOf(
            pet().put("actions", JSONObject("""{"a":{"every":5}}""")) to "whole seconds",
            pet().put("actions", JSONObject("""{"a":{"every":600,"min":900}}""")) to "min ≤ every",
            pet().put("actions", JSONObject("""{"a":{"every":600,"per":"room"}}""")) to "phone or camera",
            pet().also { it.getJSONObject("processing").getJSONArray("watches").getJSONObject(0).put("action", "nope") } to "no actions")) {
            try { AppletPackage.check(bad); fail(part) } catch (e: AppletPackage.Bad) { assertTrue("${e.message} / $part", e.message!!.contains(part)) }
        }
    }

    @Test fun syncEventsReachAgentsOnce() {
        val asker = app.agents.add("BudgetsAsker", Connection.DIRECT).first
        val r = install(pet(), app.agents.get(asker.id))
        val ev = JSONArray().put(JSONObject().put("id", 18).put("type", "budget_forecast").put("at", "2026-11-05T10:00:00+01:00").put("limit_id", "l2")
            .put("message", "Pet monitor: at this rate it runs out on 15 Nov.").put("fixes", JSONArray()))
        answer = { _, _, _ -> 200 to reply("pet-monitor", "warning") { put("events", ev) } }
        app.budgetSync.syncNow(); app.budgetSync.syncNow()
        val got = app.interactions.eventsFor(asker.id, null, setOf("budget_forecast"))
        assertEquals(1, got.size)
        assertEquals("pet-monitor", got[0].data.getString("applet")); assertEquals(r.getString("id"), got[0].data.getString("instance_id"))
        // The applet's own agent hears it too; the next request says what was seen.
        assertEquals(1, app.interactions.eventsFor(r.getString("agent_id"), null, setOf("budget_forecast")).size)
        assertEquals(18, sent.last().third!!.getJSONArray("applets").getJSONObject(0).getLong("seen_event_id"))
    }

    @Test fun theBudgetsPermissionGatesAgentChanges() = runBlocking {
        val asker = app.agents.add("BudgetsInstaller", Connection.DIRECT).first
        app.agents.update(asker.id) { it.copy(caps = setOf("applet_install")) }
        val r = install(pet(), app.agents.get(asker.id))
        // Turned on for the agent that installed it; others start without it.
        assertTrue(app.agents.get(asker.id)!!.allows(BudgetModule.ID))
        val other = app.agents.add("BudgetsStranger", Connection.DIRECT).first
        app.agents.update(other.id) { it.copy(caps = setOf("applet_install")) }
        assertFalse(app.agents.get(other.id)!!.allows(BudgetModule.ID))
        val controller = Robolectric.buildService(BridgeService::class.java).create()
        val api = ApiServer(controller.get(), emptyList(), 0); api.prepareRelay()
        val body = JSONObject().put("limits", JSONArray().put(JSONObject().put("id", "l1").put("usd", 4).put("per", "month")))
        suspend fun put(agentId: String) = api.dispatch(BudgetEndpoints.SET, app.agents.get(agentId)!!, "100.64.0.2", emptyMap(), mapOf("id" to r.getString("id")), body, null)
        val refused = put(other.id)
        assertEquals(403, refused.status); assertEquals("agent_not_allowed", JSONObject(String(refused.bytes)).getString("error"))
        answer = { m, p, _ -> if (m == "PUT") 200 to JSONObject().put("ok", true) else 200 to reply("pet-monitor", "ok") }
        val ok = put(asker.id)
        assertEquals(String(ok.bytes), 200, ok.status)
        val change = sent.first { it.first == "PUT" }
        assertEquals("/v1/budgets/pet-monitor/limits", change.second)
        assertEquals("agent", change.third!!.getJSONObject("by").getString("kind")); assertEquals(asker.id, change.third!!.getJSONObject("by").getString("id"))
        // The backend's refusals pass through.
        answer = { _, _, _ -> 403 to JSONObject().put("error", "over_ceiling").put("message", "Over the owner's ceiling of \$3.").put("ceiling", JSONObject().put("l1", 3)) }
        val over = put(asker.id)
        assertEquals(403, over.status); assertEquals("over_ceiling", JSONObject(String(over.bytes)).getString("error"))
        // The master switch (on by default) off: capability_disabled.
        app.settings.setOn(BudgetModule.ID, false)
        assertEquals("capability_disabled", JSONObject(String(put(asker.id).bytes)).getString("error"))
        app.settings.setOn(BudgetModule.ID, true)
        // Reading needs no Budgets permission for the asker, and the action route checks the action exists.
        val read = api.dispatch(AppletEndpoints.BUDGET, app.agents.get(asker.id)!!, "100.64.0.2", emptyMap(), mapOf("id" to r.getString("id")), JSONObject(), null)
        assertEquals(200, read.status); assertEquals("pet-monitor", JSONObject(String(read.bytes)).getString("applet"))
        val noAction = api.dispatch(BudgetEndpoints.ACTION_SET, app.agents.get(asker.id)!!, "100.64.0.2", emptyMap(), mapOf("id" to r.getString("id"), "action" to "nope"), JSONObject().put("every_s", 600), null)
        assertEquals(404, noAction.status)
    }

    @Test fun budgetsIsOnByDefault() {
        val p = app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        p.edit().remove("switch_budgets").remove("switch_camera").commit()
        assertTrue(app.settings.isOn(BudgetModule.ID))
        assertFalse(app.settings.isOn("camera"))
    }

    @Test fun thePetMonitorCallsCarryAppletDog() {
        // Its calls pass applet = "dog"; while dog is out the phone refuses them before they leave.
        AppletBudget.refused(app, AppletBudget.DOG, System.currentTimeMillis() + 60_000)
        try { AppletBudget.check(app, Jev.appletFor(app, "dog", null)); fail() } catch (e: Jev.Failure) { assertEquals("applet_budget", e.reason) }
        AppletBudget.clearOut(app, AppletBudget.DOG)
        assertEquals(AppletBudget.DOG_LIMITS, listOf(AppletBudget.Limit("l1", 3.0, "month", listOf(0.5))))
    }
}
