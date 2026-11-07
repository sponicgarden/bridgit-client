package cc.bridgit.app.platform

import cc.bridgit.app.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Flow steps (docs/applet-flows.md, phase 1): validation, references, conditions, error policy, run log, watch triggers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class FlowStepsTest {
    private val app get()=(RuntimeEnvironment.getApplication() as BridgeApp)
    @Before fun enableOwnerListener() { app.settings.listenerEnabled=true }

    private fun o(vararg p: Pair<String, Any?>) = JSONObject().apply { p.forEach { (k, v) -> put(k, v) } }
    private fun a(vararg v: Any?) = JSONArray().apply { v.forEach { put(it) } }
    private fun flow(vararg steps: JSONObject, trigger: JSONObject = o("type" to "button")) = o("name" to "Dinner check", "trigger" to trigger, "steps" to a(*steps))
    private fun ref(path: String) = o("\$step" to path)
    private fun questions() = o("eating" to o("type" to "choice", "instructions" to "Is a dog eating from a bowl?", "criteria" to o("yes" to "eating", "no" to "not eating")))
    private fun rejects(def: JSONObject, part: String) {
        try { app.platform.workflows.validate(def); fail("Expected rejection: $part") }
        catch (e: ApiError) { assertEquals("invalid_workflow", e.code); assertTrue("'${e.message}' should mention $part", e.message!!.contains(part)) }
    }

    /** An agent with the workflow features switched on and granted, and an active workflow for [def]. */
    private fun activeWorkflow(def: JSONObject, name: String = "FlowTester"): Pair<String, String> {
        val agent = app.policy.addAgent(name).first.id
        val scopes = app.platform.workflows.validate(def).strings("required_scopes")
        scopes.forEach { Capabilities.setEnabled(app, it, true); app.platform.grant(agent, it, System.currentTimeMillis() + 3_600_000) }
        val id = app.platform.workflows.propose(agent, def).getString("id")
        app.platform.workflows.activate(id)
        return agent to id
    }
    private fun runNow(agent: String, id: String) = runBlocking { withTimeout(60_000) { app.platform.workflows.runOnce(agent, id) } }
    private fun steps(result: JSONObject) = result.getJSONArray("steps").let { s -> (0 until s.length()).map { s.getJSONObject(it) } }

    // ---- validation -------------------------------------------------------------------------------------------

    @Test fun glmIsAnAppletFlowModel() {
        val v=app.platform.workflows.validate(flow(o("type" to "ask_model", "model" to "glm", "questions" to questions())))
        assertTrue(v.getString("summary"),v.getString("summary").contains("GLM 5.3 Flash"))
        assertEquals("/v1/glm",cc.bridgit.app.watch.WatchRules.route("glm"))
    }

    @Test fun newStepsDeriveTheirScopesAndASummary() {
        val def = flow(
            o("id" to "shot", "type" to "photo", "camera" to "rear"),
            o("id" to "look", "type" to "ask_model", "model" to "clef", "photo" to ref("shot.photo"), "questions" to questions()),
            o("id" to "check", "type" to "ask_owner", "only_if" to o("step" to "look.answers.eating", "is" to "no"), "title" to "No dinner seen",
                "choices" to a("Alert them", "It's fine"), "wait_s" to 600),
            o("type" to "notice", "only_if" to o("step" to "check.choice", "is" to "Alert them"), "title" to a("Dinner: ", ref("look.answers.eating")), "on_error" to "retry", "retries" to 2))
        val v = app.platform.workflows.validate(def)
        assertEquals(setOf("workflows", "camera.snapshot", "decisions", "interactions"), v.strings("required_scopes").toSet())
        assertEquals(120 + 600 + 40, v.getJSONObject("limits").getInt("max_run_seconds"))
        val summary = v.getString("summary")
        assertTrue(summary, summary.startsWith("When you tap its button: take a photo with the rear camera, ask Clef \"Is a dog eating from a bowl?\""))
        assertTrue(summary, summary.contains("if the answer to \"eating\" is no ask you \"No dinner seen\" (waits up to 10 minutes)"))
        assertTrue(summary, summary.contains("if you choose \"Alert them\" show you \"Dinner: …\" (tries again if this fails)"))
    }

    @Test fun watchTriggerShapes() {
        assertTrue(app.platform.workflows.validate(flow(o("type" to "event", "text" to "x"), trigger = o("type" to "watch", "watch" to "w123"))).getBoolean("valid"))
        assertTrue(app.platform.workflows.validate(flow(o("type" to "event", "text" to "x"), trigger = o("type" to "watch", "watch" to "w123", "on" to "rule", "rule" to "no-meal"))).getBoolean("valid"))
        rejects(flow(o("type" to "event", "text" to "x"), trigger = o("type" to "watch", "watch" to "w123", "on" to "rule")), "trigger.rule")
        rejects(flow(o("type" to "event", "text" to "x"), trigger = o("type" to "watch")), "trigger.watch")
        rejects(flow(o("type" to "event", "text" to "x"), trigger = o("type" to "watch", "watch" to "w1", "on" to "sometimes")), "match or rule")
        assertTrue(FlowSteps.summary(flow(o("type" to "stop"), trigger = o("type" to "watch", "watch" to "w1", "on" to "rule", "rule" to "no-meal"))).startsWith("When its watch's rule \"no-meal\" fires"))
    }

    @Test fun badStepsAreRefusedWithAReason() {
        rejects(flow(o("type" to "notice", "title" to ref("later.value")), o("id" to "later", "type" to "stop")), "names no earlier step")
        rejects(flow(o("type" to "notice", "title" to ref("nobody.value"))), "names no earlier step")
        rejects(flow(o("type" to "shell")), "unknown step type")
        rejects(flow(o("type" to "notice", "title" to "x", "url" to "https://example.com")), "isn't a field of notice")
        rejects(flow(o("id" to "a", "type" to "stop"), o("id" to "a", "type" to "stop")), "used twice")
        rejects(flow(o("type" to "stop", "only_if" to o("step" to "x.y", "is" to 1))), "names no earlier step")
        rejects(flow(o("id" to "x", "type" to "stop"), o("type" to "stop", "only_if" to o("step" to "x.y", "is" to 1, "below" to 2))), "exactly one test")
        rejects(flow(o("type" to "stop", "on_error" to "skip", "retries" to 2)), "retries goes with on_error retry")
        rejects(flow(o("type" to "stop", "on_error" to "retry", "retries" to 9)), "retries must be 1-3")
        rejects(flow(o("id" to "n", "type" to "notice", "title" to "x"), o("type" to "ask_model", "model" to "jev", "questions" to questions(), "photo" to ref("n.photo"))), "earlier photo step")
        rejects(flow(*Array(5) { o("type" to "ask_model", "model" to "jev", "questions" to questions()) }), "at most 4 times")
        rejects(flow(o("type" to "ask_model", "model" to "gpt", "questions" to questions())), "jev, clef or glm")
        rejects(flow(o("type" to "ask_owner", "title" to "Q", "choices" to a("Only one"))), "2-6")
        rejects(flow(o("type" to "wait")), "seconds is required")
        rejects(flow(o("type" to "photo", "camera" to "http://x")), "camera must be")
        rejects(flow(o("type" to "notice", "title" to "x")).put("max_paid_per_day", 10), "max_paid_per_day goes with")
    }

    @Test fun recordStepAndKeptPhotos() {
        val def = flow(
            o("id" to "shot", "type" to "photo", "keep" to true),
            o("id" to "look", "type" to "ask_model", "model" to "jev", "photo" to ref("shot.photo"), "questions" to questions()),
            o("id" to "save", "type" to "record", "table" to "meals",
                "row" to o("activity" to ref("look.answers.eating"), "photo" to ref("shot.photo"), "note" to "auto", "n" to 1, "ok" to true, "gone" to JSONObject.NULL, "msg" to o("\$trigger" to "message"))),
            o("type" to "notice", "only_if" to o("step" to "save.row_id", "exists" to true), "title" to "Saved"))
        val v = app.platform.workflows.validate(def)
        // record needs no scope of its own.
        assertEquals(setOf("workflows", "camera.snapshot", "decisions", "interactions"), v.strings("required_scopes").toSet())
        val summary = v.getString("summary")
        assertTrue(summary, summary.startsWith("When you tap its button: take a photo with the rear camera and keep it, ask Jev"))
        assertTrue(summary, summary.contains("save a row in \"meals\""))
        assertFalse(FlowSteps.summary(flow(o("type" to "photo"))).contains("keep it"))

        rejects(flow(o("type" to "photo", "keep" to "yes")), "keep must be true or false")
        rejects(flow(o("type" to "record", "row" to o("a" to 1))), "table must be a table id")
        rejects(flow(o("type" to "record", "table" to "Meals", "row" to o("a" to 1))), "table must be a table id")
        rejects(flow(o("type" to "record", "table" to "meals")), "row must be an object")
        rejects(flow(o("type" to "record", "table" to "meals", "row" to o())), "1-16 columns")
        rejects(flow(o("type" to "record", "table" to "meals", "row" to JSONObject().apply { repeat(17) { put("c$it", 1) } })), "1-16 columns")
        rejects(flow(o("type" to "record", "table" to "meals", "row" to o("Total" to 1))), "column Total")
        rejects(flow(o("type" to "record", "table" to "meals", "row" to o("a" to a(1, 2)))), "row.a must be text")
        rejects(flow(o("type" to "record", "table" to "meals", "row" to o("a" to "x".repeat(1025)))), "longer than 1024")
        rejects(flow(o("type" to "record", "table" to "meals", "row" to o("a" to ref("later.value"))), o("id" to "later", "type" to "stop")), "names no earlier step")
        rejects(flow(o("type" to "record", "table" to "meals", "row" to o("a" to ref("nobody.value")))), "names no earlier step")
        rejects(flow(o("type" to "record", "table" to "meals", "row" to o("a" to o("\$input" to "x")))), "unknown reference")
        rejects(flow(o("type" to "record", "table" to "meals", "url" to "x", "row" to o("a" to 1))), "isn't a field of record")
    }

    @Test fun recordOutsideAnAppletIsRefused() {
        // Tables and kept photos belong to an installed applet (deleted with it); a plain agent's workflow can't use them.
        try { activeWorkflow(flow(o("id" to "save", "type" to "record", "table" to "meals", "row" to o("a" to 1))), "RecordTester"); org.junit.Assert.fail("proposed a record step outside an applet") }
        catch (e: ApiError) { assertEquals("invalid_workflow", e.code); assertTrue(e.message!!.contains("installed applet")) }
    }

    @Test fun oldWorkflowsStillValidate() {
        val old = o("name" to "Help panel", "trigger" to o("type" to "button"), "steps" to a(o("type" to "notice", "title" to "Help requested", "text" to "Please check the panel")))
        assertEquals(setOf("workflows", "interactions"), app.platform.workflows.validate(old).strings("required_scopes").toSet())
        // The original steps accepted the shared fields on any step and blank text: saved workflows must still pass.
        val loose = o("name" to "Loose", "trigger" to o("type" to "button"), "steps" to a(o("type" to "ring", "seconds" to 10, "text" to "wake"), o("type" to "notice", "title" to "", "seconds" to 3)))
        assertTrue(app.platform.workflows.validate(loose).getBoolean("valid"))
    }

    // ---- pure running helpers -----------------------------------------------------------------------------------

    @Test fun referencesConditionsAndTextParts() {
        val results = mapOf("look" to o("answers" to o("eating" to "no", "pet" to 0.82)), "check" to o("choice" to "Alert them"))
        val trigger = o("message" to "No meal by 6pm", "at" to "2026-11-05T18:00:00Z")
        assertEquals("Seen: no (82%? 0.82) No meal by 6pm", FlowSteps.textOf(a("Seen: ", ref("look.answers.eating"), " (82%? ", ref("look.answers.pet"), ") ", o("\$trigger" to "message")), results, trigger))
        assertEquals("", FlowSteps.textOf(ref("skipped.anything"), results, trigger))
        assertTrue(FlowSteps.holds(o("step" to "look.answers.eating", "is" to "No"), results, trigger)) // text compares without case
        assertTrue(FlowSteps.holds(o("step" to "look.answers.pet", "at_least" to 0.8), results, trigger))
        assertFalse(FlowSteps.holds(o("step" to "look.answers.pet", "below" to 0.5), results, trigger))
        assertTrue(FlowSteps.holds(o("step" to "check.choice", "in" to a("Alert them", "Call")), results, trigger))
        assertFalse(FlowSteps.holds(o("step" to "gone.choice", "is" to "x"), results, trigger)) // a skipped step has no value
        assertFalse(FlowSteps.holds(o("step" to "gone.choice", "is_not" to "x"), results, trigger))
        assertTrue(FlowSteps.holds(o("step" to "gone.choice", "exists" to false), results, trigger))
        assertTrue(FlowSteps.holds(o("trigger" to "message", "exists" to true), results, trigger))
    }

    // ---- running on the phone -----------------------------------------------------------------------------------

    @Test fun resultsFlowBetweenStepsAndOnlyIfSkips() {
        val def = flow(
            o("id" to "first", "type" to "notice", "title" to "Hello"),
            o("type" to "notice", "title" to "Skipped", "only_if" to o("step" to "first.interaction_id", "exists" to false)),
            o("id" to "second", "type" to "notice", "title" to a("After ", ref("first.interaction_id"))),
            o("type" to "stop"),
            o("type" to "notice", "title" to "Never"))
        val (agent, id) = activeWorkflow(def)
        val r = runNow(agent, id)
        assertEquals("completed", r.getString("status"))
        assertEquals(listOf("ok", "skipped", "ok", "ok", "not_run"), steps(r).map { it.getString("status") })
        val firstId = steps(r)[0].getJSONObject("result").getString("interaction_id")
        val titles = app.platform.store.list(agent, "interaction").map { it.optString("title") }
        assertTrue(titles.toString(), "After $firstId" in titles)
        assertFalse("Skipped" in titles || "Never" in titles)
        val runs = app.platform.workflows.runs(agent, id, 10).getJSONArray("runs")
        assertEquals(1, runs.length()); assertEquals("completed", runs.getJSONObject(0).getString("status"))
        assertEquals("button", runs.getJSONObject(0).getJSONObject("trigger").getString("type"))
    }

    @Test fun failedStepFollowsOnErrorAndTheWorkflowStaysActive() {
        // A local spending cap refuses the paid step before dispatch, so its failure is definite.
        fun capped(modelPolicy: String, notice: String, name: String): Pair<String, String> {
            val def = flow(o("type" to "ask_model", "model" to "jev", "questions" to questions(), "on_error" to modelPolicy),
                o("type" to "notice", "title" to notice)).put("max_paid_per_day", 1)
            val (agent, id) = activeWorkflow(def, name)
            val record = app.platform.store.require(id, agent, "workflow")
            app.platform.store.save(agent, "workflow", record.put("paid_day", java.time.LocalDate.now().toString()).put("paid_count", 1))
            return agent to id
        }
        val (a1, w1) = capped("skip", "Carried on", "SkipTester")
        val r1 = runNow(a1, w1)
        assertEquals("completed", r1.getString("status"))
        assertEquals(listOf("failed", "ok"), steps(r1).map { it.getString("status") })
        // Unconfigured builds refuse even earlier with team_unavailable.
        assertTrue(steps(r1)[0].toString(), steps(r1)[0].getString("error") in setOf("paid_cap", "team_unavailable"))

        val (a2, w2) = capped("stop", "Never", "StopTester")
        val r2 = runNow(a2, w2)
        assertEquals("failed", r2.getString("status")); assertEquals(steps(r1)[0].getString("error"), r2.getString("error"))
        assertEquals(listOf("failed", "not_run"), steps(r2).map { it.getString("status") })
        assertEquals("active", app.platform.store.require(w2, a2, "workflow").getString("status")) // a failed run doesn't block
        assertTrue(app.platform.store.events(a2, 0).toString().contains("workflow.failed"))
    }

    @Test fun losingAGrantBlocksTheWorkflow() {
        val def = flow(o("type" to "notice", "title" to "One"), o("type" to "wait", "seconds" to 2), o("type" to "notice", "title" to "Two"))
        val (agent, id) = activeWorkflow(def, "RevokeTester")
        runBlocking {
            val run = async(Dispatchers.IO) { runCatching { app.platform.workflows.runOnce(agent, id) } }
            delay(700); app.platform.store.revoke(agent, "interactions")
            assertTrue(run.await().isFailure)
        }
        val record = app.platform.store.require(id, agent, "workflow")
        assertEquals("blocked", record.getString("status")); assertEquals("agent_not_allowed", record.getString("blocked_reason"))
    }

    @Test fun askOwnerWaitsForTheAnswer() {
        val def = flow(o("id" to "ask", "type" to "ask_owner", "title" to "Alert the family?", "choices" to a("Yes", "No"), "wait_s" to 60),
            o("type" to "notice", "title" to a("You said ", ref("ask.choice")), "only_if" to o("step" to "ask.choice", "is" to "Yes")))
        val (agent, id) = activeWorkflow(def, "AskTester")
        val r = runBlocking {
            val run = async(Dispatchers.IO) { app.platform.workflows.runOnce(agent, id) }
            var q: JSONObject? = null
            while (q == null) { delay(100); q = app.platform.store.list(agent, "interaction").firstOrNull { it.optString("status") == "pending" } }
            app.platform.answer(q.getString("id"), "Yes")
            withTimeout(20_000) { run.await() }
        }
        assertEquals("completed", r.getString("status"))
        assertEquals("Yes", steps(r)[0].getJSONObject("result").getString("choice"))
        assertFalse(steps(r)[0].getJSONObject("result").getBoolean("timed_out")) // so only_if {timed_out is false} holds
        assertTrue(app.platform.store.list(agent, "interaction").any { it.optString("title") == "You said Yes" })
    }

    @Test fun askOwnerClosedWithoutAnAnswerTimesOut() {
        val def = flow(o("id" to "ask", "type" to "ask_owner", "title" to "Still there?", "choices" to a("Yes", "No"), "wait_s" to 60),
            o("type" to "notice", "title" to "No answer", "only_if" to o("step" to "ask.timed_out", "is" to true)))
        val (agent, id) = activeWorkflow(def, "TimeoutTester")
        val r = runBlocking {
            val run = async(Dispatchers.IO) { app.platform.workflows.runOnce(agent, id) }
            var q: JSONObject? = null
            while (q == null) { delay(100); q = app.platform.store.list(agent, "interaction").firstOrNull { it.optString("status") == "pending" } }
            app.platform.store.save(agent, "interaction", q.put("status", "expired"))
            withTimeout(20_000) { run.await() }
        }
        assertTrue(steps(r)[0].getJSONObject("result").getBoolean("timed_out"))
        assertEquals("ok", steps(r)[1].getString("status"))
    }

    @Test fun aWatchMatchRunsItsWorkflowWithTheTriggerData() {
        val agent = app.policy.addAgent("WatchFlowTester").first.id
        val def = flow(o("type" to "notice", "title" to a("Watch said: ", o("\$trigger" to "message"))), trigger = o("type" to "watch", "watch" to "w42", "on" to "rule", "rule" to "no-meal"))
        val scopes = app.platform.workflows.validate(def).strings("required_scopes")
        scopes.forEach { Capabilities.setEnabled(app, it, true); app.platform.grant(agent, it, System.currentTimeMillis() + 3_600_000) }
        // propose() wants a running watch of this agent; the record is what it would save.
        val record = app.platform.store.save(agent, "workflow", JSONObject().put("definition", def).put("digest", PlatformStore.digest(def))
            .put("required_scopes", JSONArray(scopes)).put("status", "awaiting_owner").put("revision", 1))
        app.platform.workflows.activate(record.getString("id"))
        app.platform.workflows.onWatch(agent, "w42", "match", null, JSONObject().put("message", "wrong kind")) // not this trigger
        app.platform.workflows.onWatch(agent, "w42", "rule", "no-meal", JSONObject().put("message", "No meal by 6pm"))
        runBlocking { withTimeout(10_000) { while (app.platform.store.list(agent, "interaction").isEmpty()) delay(100) } }
        assertEquals(listOf("Watch said: No meal by 6pm"), app.platform.store.list(agent, "interaction").map { it.getString("title") })
    }

    @Test fun proposingAWatchTriggerNeedsTheAgentsOwnWatch() {
        val agent = app.policy.addAgent("NoWatchTester").first.id
        try { app.platform.workflows.propose(agent, flow(o("type" to "stop"), trigger = o("type" to "watch", "watch" to "w-nope"))); fail() }
        catch (e: ApiError) { assertTrue(e.message!!.contains("isn't one of your running watches")) }
    }
}
