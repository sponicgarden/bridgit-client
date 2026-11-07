package cc.bridgit.app.platform

import cc.bridgit.app.*
import cc.bridgit.app.applets.AppletPackage
import cc.bridgit.app.applets.AppletTableStore
import cc.bridgit.app.applets.AppletTables
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Modes, the mode trigger and set_mode step, the flow-level only_if, and lookup / count over the applet's table (D1, D2, D4). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModesFlowTest {
    private val app get() = RuntimeEnvironment.getApplication() as BridgeApp
    private val modes get() = app.platform.modes
    private fun o(vararg p: Pair<String, Any?>) = JSONObject().apply { p.forEach { (k, v) -> put(k, v) } }
    private fun a(vararg v: Any?) = JSONArray().apply { v.forEach { put(it) } }
    private val owner = Modes.By("owner", "You")

    @Before fun on() {
        app.settings.listenerEnabled = true
        for (m in app.registry.switchable()) app.settings.setOn(m.switchKey!!, true)
        runCatching { androidx.work.WorkManager.getInstance(app) }.onFailure { androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build()) }
    }
    @After fun cleanUp() { runBlocking { for (r in app.applets.all()) app.applets.remove(r.getString("id")) }; runCatching { modes.set("home", owner) } }

    private fun active(def: JSONObject): Pair<String, String> {
        val agent = app.policy.addAgent("ModeTester" + (System.nanoTime() % 100000)).first.id
        app.platform.workflows.validate(def).strings("required_scopes").forEach { Capabilities.setEnabled(app, it, true); app.platform.grant(agent, it, System.currentTimeMillis() + 3_600_000) }
        val id = app.platform.workflows.propose(agent, def).getString("id"); app.platform.workflows.activate(id)
        return agent to id
    }
    private fun runs(agent: String, id: String) = app.platform.workflows.runs(agent, id, 50).getJSONArray("runs")
    private fun settle(id: String) = runBlocking { delay(50); withTimeout(10_000) { while (app.platform.workflows.busy(id)) delay(20) } }
    private fun record(id: String) = app.platform.store.find(id, kind = "workflow")!!

    @Test fun modesSetLogAndRefuse() {
        assertEquals(listOf("home", "away", "night", "vacation"), modes.all().take(4))
        modes.set("home", owner)
        assertFalse("the same mode changes nothing", modes.set("home", owner))
        assertTrue(modes.set("away", owner))
        assertEquals("away", modes.current())
        assertEquals("home", modes.log().first().getString("from"))
        assertEquals("owner", modes.log().first().getJSONObject("by").getString("type"))
        try { modes.set("party", owner); fail() } catch (e: ApiError) { assertEquals("unknown_mode", e.code) }
        assertEquals("late_shift", modes.add("Late shift"))
        assertTrue(modes.set("late_shift", owner))
        assertFalse("the current mode can't be removed", modes.remove("late_shift"))
        modes.set("home", owner)
        assertTrue(modes.remove("late_shift")); assertFalse(modes.remove("night"))
        val json = modes.json()
        assertEquals("home", json.getString("mode")); assertTrue(json.getJSONArray("log").length() >= 3)
        // A flow or agent can't spin the mode.
        val bot = Modes.By("flow", "Spinner")
        try { repeat(Modes.BURST + 2) { i -> modes.set(if (i % 2 == 0) "away" else "home", bot) }; fail() } catch (e: ApiError) { assertEquals("mode_busy", e.code) }
        assertTrue("the owner is never held back", modes.set(if (modes.current() == "night") "home" else "night", owner))
    }

    @Test fun aModeTriggerStartsOnTheChangeAndSetModeChangesIt() {
        modes.set("home", owner)
        val (agent, id) = active(o("name" to "Going out", "trigger" to o("type" to "mode", "to" to "away"),
            "steps" to a(o("type" to "event", "text" to a("From ", o("\$trigger" to "from"))))))
        val v = app.platform.workflows.validate(record(id).getJSONObject("definition"))
        assertTrue(v.getString("summary"), v.getString("summary").startsWith("When the phone's mode changes to away: "))
        modes.set("night", owner); settle(id)
        assertEquals(0, runs(agent, id).length())
        modes.set("away", owner); settle(id)
        val r = runs(agent, id)
        assertEquals(1, r.length())
        assertEquals("night", r.getJSONObject(0).getJSONObject("trigger").getString("from"))
        modes.set("away", owner); settle(id)
        assertEquals("setting the same mode starts nothing", 1, runs(agent, id).length())

        val (agent2, id2) = active(o("name" to "Bedtime", "trigger" to o("type" to "button"), "steps" to a(o("id" to "m", "type" to "set_mode", "mode" to "night"))))
        assertTrue(record(id2).getJSONArray("required_scopes").toString().contains("modes.set"))
        val run = runBlocking { app.platform.workflows.runNow(agent2, id2, null) }
        assertEquals(run.toString(), "completed", run.getString("status"))
        assertEquals("night", modes.current())
        assertEquals("flow", modes.log().first().getJSONObject("by").getString("type"))
        assertEquals("Bedtime", modes.log().first().getJSONObject("by").getString("name"))
        // An unknown mode fails the step, not the check (the owner may add it later).
        val (agent3, id3) = active(o("name" to "Party", "trigger" to o("type" to "button"), "steps" to a(o("type" to "set_mode", "mode" to "party"))))
        val failed = runBlocking { app.platform.workflows.runNow(agent3, id3, null) }
        assertEquals("unknown_mode", failed.getString("error"))
    }

    @Test fun aFlowLevelOnlyIfSkipsAndCountsTheStart() {
        modes.set("home", owner)
        val (agent, id) = active(o("name" to "Away only", "trigger" to o("type" to "button"), "only_if" to o("mode" to true, "is" to "away"),
            "steps" to a(o("type" to "event", "text" to "hi"))))
        assertTrue(app.platform.workflows.validate(record(id).getJSONObject("definition")).getString("summary").startsWith("When you tap its button, only when the mode is away: "))
        try { runBlocking { app.platform.workflows.runNow(agent, id, null, ownerTap = true) }; fail() } catch (e: ApiError) {
            assertEquals("workflow_skipped", e.code); assertTrue(e.message, e.message!!.contains("when the mode is away"))
        }
        assertEquals(1, record(id).optInt("skipped_runs")); assertEquals(0, runs(agent, id).length())
        modes.set("away", owner)
        assertEquals("completed", runBlocking { app.platform.workflows.runNow(agent, id, null) }.getString("status"))
        // A skipped interval flow waits a whole interval before it tries again.
        val (agentI, idI) = active(o("name" to "Hourly", "trigger" to o("type" to "interval", "seconds" to 3600), "only_if" to o("mode" to true, "is" to "night"), "steps" to a(o("type" to "event", "text" to "x"))))
        synchronized(app.platform.store) { app.platform.store.save(agentI, "workflow", record(idI).put("next_at", 0L)) }
        runBlocking { app.platform.workflows.tick() }; settle(idI)
        assertEquals(1, record(idI).optInt("skipped_runs"))
        assertTrue(record(idI).optLong("next_at") > System.currentTimeMillis() + 3_500_000)
        runBlocking { app.platform.workflows.tick() }; settle(idI)
        assertEquals("not retried on the next tick", 1, record(idI).optInt("skipped_runs"))
        // A step's only_if with the new sources skips just that step.
        val (agent2, id2) = active(o("name" to "Steps", "trigger" to o("type" to "button"), "steps" to a(
            o("id" to "a", "type" to "event", "text" to "x", "only_if" to o("mode" to true, "is" to "night")),
            o("id" to "b", "type" to "event", "text" to "y", "only_if" to o("any" to a(o("mode" to true, "is" to "away"), o("time" to o("days" to a("mon")))))))))
        val steps = runBlocking { app.platform.workflows.runNow(agent2, id2, null) }.getJSONArray("steps")
        assertEquals("skipped", steps.getJSONObject(0).getString("status")); assertEquals("ok", steps.getJSONObject(1).getString("status"))
        // A start from a rule or schedule reports skipped.
        val (agent3, id3) = active(o("name" to "Called", "trigger" to o("type" to "called"), "only_if" to o("mode" to true, "is" to "vacation"), "steps" to a(o("type" to "event", "text" to "x"))))
        assertEquals("skipped", runBlocking { app.platform.workflows.start(agent3, id3, o("caller" to "rule"), null) }.getString("status"))
        // Flow-level only_if can't read a step, and the field is checked like a step's.
        try { app.platform.workflows.validate(o("name" to "x", "trigger" to o("type" to "button"), "only_if" to o("step" to "a.b", "is" to 1), "steps" to a(o("id" to "a", "type" to "event", "text" to "x")))); fail() }
        catch (e: ApiError) { assertTrue(e.message!!.contains("can't read a step")) }
        try { app.platform.workflows.validate(o("name" to "x", "trigger" to o("type" to "mode", "to" to "Away"), "steps" to a(o("type" to "event", "text" to "x")))); fail() }
        catch (e: ApiError) { assertTrue(e.message!!.contains("trigger.to")) }
        // A zone or a sun test without coordinates needs location.
        assertTrue(app.platform.workflows.validate(o("name" to "x", "trigger" to o("type" to "button"), "only_if" to o("sun" to o("after" to "sunset")), "steps" to a(o("type" to "event", "text" to "x"))))
            .getJSONArray("required_scopes").toString().contains("location"))
    }

    // ---- lookup and count over an installed applet's own table -----------------------------------------------

    private val link = o("kind" to "link", "url" to "https://bridgit.cc/recipes/voice-notebook/applet.json")
    private fun doses(): AppletPackage.Package {
        val d = JSONObject(File("../site-content/public/recipes/voice-notebook/applet.json").readText())
        d.put("id", "doses-test").put("name", "Doses test")
        d.put("processing", o(
            "tables" to a(o("id" to "doses", "columns" to o("taken" to o("type" to "boolean"), "mg" to o("type" to "number")))),
            "flows" to a(
                o("id" to "check", "name" to "Missed doses", "trigger" to o("type" to "called"),
                    "only_if" to o("count" to o("table" to "doses", "since" to "this_week", "where" to o("column" to "taken", "is" to false)), "at_least" to 3),
                    "steps" to a(
                        o("id" to "missed", "type" to "lookup", "table" to "doses", "where" to o("column" to "taken", "is" to false), "since" to "this_week", "limit" to 2),
                        o("id" to "tell", "type" to "event", "text" to a("Missed ", o("\$step" to "missed.count")),
                            "only_if" to o("sum" to o("table" to "doses", "column" to "mg", "since" to "today"), "at_least" to 100)))))))
        d.put("permissions", o("required" to a("interactions", "workflows"), "optional" to a(), "remote" to a(), "automatic_grants" to false))
        d.getJSONObject("ui").put("card", o("title" to "Doses", "sources" to o("m" to o("mode" to true)),
            "pieces" to a(o("type" to "header", "title" to "Doses"), o("type" to "metrics", "items" to a(o("label" to "Mode", "value" to o("\$source" to "m.label")))))))
        return AppletPackage.check(d)
    }

    @Test fun lookupAndCountReadTheAppletsOwnTable(): Unit = runBlocking {
        for (sc in listOf("workflows", "interactions")) Capabilities.setEnabled(app, sc, true)
        val r = app.applets.approve(app.applets.propose(doses(), null, link, null, notify = false).getString("id"), null)
        val agent = r.getString("agent_id"); val wf = r.getJSONObject("flows").getJSONObject("check").getString("workflow_id")
        val table = AppletTables.parse(app.applets.forAgent(agent)!!.getJSONObject("definition").getJSONObject("processing")).single()
        val store = AppletTableStore.of(app); val by = o("type" to "card")
        store.add(agent, table, o("taken" to true, "mg" to 50), by)
        store.add(agent, table, o("taken" to false, "mg" to 50), by)
        store.add(agent, table, o("taken" to false, "mg" to 10), by)
        try { app.platform.workflows.runNow(agent, wf, null); fail("two missed doses: skipped") } catch (e: ApiError) { assertEquals("workflow_skipped", e.code) }
        store.add(agent, table, o("taken" to false, "mg" to 5), by)
        val run = app.platform.workflows.runNow(agent, wf, null)
        assertEquals(run.toString(), "completed", run.getString("status"))
        val look = run.getJSONArray("steps").getJSONObject(0).getJSONObject("result")
        assertEquals(3, look.getInt("count")); assertEquals(2, look.getJSONArray("rows").length())
        assertEquals("newest first", 5, look.getJSONArray("rows").getJSONObject(0).getInt("mg"))
        assertEquals(5, look.getJSONObject("row").getInt("mg"))
        assertEquals("115 mg today is at least 100", "ok", run.getJSONArray("steps").getJSONObject(1).getString("status"))
        // Old rows fall out of this_week.
        assertEquals(3, store.scan(agent, "doses", System.currentTimeMillis() - 60_000, o("column" to "taken", "is" to false)).count)
        assertEquals(0, store.scan(agent, "doses", System.currentTimeMillis() + 60_000, null).count)
        assertEquals(115.0, store.scan(agent, "doses", null, null, sumColumn = "mg").sum, 0.001)
    }

    @Test fun aPackageMustNameItsOwnTablesAndColumns() {
        fun bad(edit: (JSONObject) -> Unit, part: String) {
            val d = JSONObject(File("../site-content/public/recipes/voice-notebook/applet.json").readText()).put("id", "bad-test")
            d.put("processing", o("tables" to a(o("id" to "doses", "columns" to o("taken" to o("type" to "boolean"), "note" to o("type" to "text")))),
                "flows" to a(o("id" to "f", "name" to "F", "trigger" to o("type" to "called"), "steps" to a(o("type" to "event", "text" to "x"))))))
            d.put("permissions", o("required" to a("interactions", "workflows"), "optional" to a(), "remote" to a(), "automatic_grants" to false))
            edit(d.getJSONObject("processing").getJSONArray("flows").getJSONObject(0))
            try { AppletPackage.check(d); fail("expected $part") } catch (e: AppletPackage.Bad) { assertTrue(e.message, e.message!!.contains(part)) }
        }
        bad({ it.put("only_if", o("count" to o("table" to "visits"), "below" to 3)) }, "table visits")
        bad({ it.getJSONArray("steps").put(0, o("type" to "lookup", "table" to "doses", "where" to o("column" to "mg", "is" to 1))) }, "where.column mg")
        bad({ it.put("only_if", o("sum" to o("table" to "doses", "column" to "note"), "below" to 3)) }, "number column")
    }
}
