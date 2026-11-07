package cc.bridgit.app.platform

import cc.bridgit.app.*
import cc.bridgit.app.applets.AppletPackage
import cc.bridgit.app.schedule.ScheduleRules
import cc.bridgit.app.watch.WatchRules
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
import java.time.ZoneId

/**
 * One action vocabulary (docs/applet-vocabulary.md, phase 3): the one `do` in watch rules and schedule actions, the
 * called trigger and its `with`, single steps, package flows and `$flow`, and starting a flow from a caller.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class VocabularyTest {
    private val app get() = (RuntimeEnvironment.getApplication() as BridgeApp)
    @Before fun enableOwnerListener() { app.settings.listenerEnabled = true }

    private fun o(vararg p: Pair<String, Any?>) = JSONObject().apply { p.forEach { (k, v) -> put(k, v) } }
    private fun a(vararg v: Any?) = JSONArray().apply { v.forEach { put(it) } }
    private fun notice(title: Any = "No meal yet") = o("type" to "notice", "title" to title)
    private fun called(vararg steps: JSONObject, with: JSONObject? = null) =
        o("name" to "Dinner check", "trigger" to o("type" to "called").apply { if (with != null) put("with", with) }, "steps" to a(*steps))
    private fun fails(part: String, block: () -> Unit) {
        try { block(); fail("expected refusal: $part") } catch (e: Exception) { assertTrue("'${e.message}' should mention $part", e.message!!.contains(part)) }
    }

    // ---- flows: called trigger, with, single steps ---------------------------------------------------------------

    @Test fun calledTriggerAndWithAreChecked() {
        val ok = app.platform.workflows.validate(called(o("type" to "notice", "title" to a("Note: ", o("\$trigger" to "with.note"))), with = o("note" to "text")))
        assertTrue(ok.getString("summary").startsWith("When it's started: show you"))
        fails("isn't declared") { app.platform.workflows.validate(called(o("type" to "notice", "title" to o("\$trigger" to "with.note")))) }
        fails("must be one of") { app.platform.workflows.validate(called(notice(), with = o("note" to "date"))) }
        fails("at most 8") { app.platform.workflows.validate(called(notice(), with = JSONObject().apply { for (i in 1..9) put("v$i", "text") })) }
        // with belongs to a called trigger only.
        fails("Unknown field") { app.platform.workflows.validate(o("name" to "x", "trigger" to o("type" to "button", "with" to o("a" to "text")), "steps" to a(notice()))) }
        // Notice priority.
        assertTrue(app.platform.workflows.validate(called(notice().put("priority", "urgent"))).getString("summary").contains("urgent notice"))
        fails("priority must be normal or urgent") { app.platform.workflows.validate(called(notice().put("priority", "loud"))) }
    }

    @Test fun withValuesAreTyped() {
        val decl = o("note" to "text", "total" to "number", "ok" to "boolean")
        val v = FlowSteps.withValues(decl, o("note" to "hi", "total" to 3))
        assertEquals("hi", v.getString("note")); assertEquals(3, v.getInt("total")); assertTrue(v.isNull("ok"))
        fails("isn't a value this flow takes") { FlowSteps.withValues(decl, o("other" to 1)) }
        fails("must be a number") { FlowSteps.withValues(decl, o("total" to "three")) }
        fails("longer than") { FlowSteps.withValues(decl, o("note" to "x".repeat(5000))) }
        try { FlowSteps.withValues(null, o("a" to 1)); fail() } catch (e: ApiError) { assertEquals("bad_with", e.code) }
    }

    @Test fun aSingleStepIsOneOfTheInlineSteps() {
        assertEquals("interactions", FlowSteps.checkInline(notice(a("Seen: ", o("\$trigger" to "message"))).put("priority", "urgent")))
        assertEquals("audio.speak", FlowSteps.checkInline(o("type" to "speak", "text" to "Time to leave.")))
        fails("needs a flow") { FlowSteps.checkInline(o("type" to "photo")) }
        fails("needs a flow") { FlowSteps.checkInline(o("type" to "ask_owner", "title" to "x", "choices" to a("a", "b"))) }
        fails("id doesn't go") { FlowSteps.checkInline(notice().put("id", "n")) }
        fails("only_if doesn't go") { FlowSteps.checkInline(notice().put("only_if", o("trigger" to "message", "exists" to true))) }
        fails("names no earlier step") { FlowSteps.checkInline(notice(o("\$step" to "shot.photo"))) }
        // retry is allowed on a single step.
        FlowSteps.checkInline(notice().put("on_error", "retry").put("retries", 2))
        // The call it makes: text filled from the trigger, title cut to the endpoint's 80.
        val call = FlowSteps.inlineCall(notice(a(o("\$trigger" to "message"), " (check)")).put("priority", "urgent"), o("message" to "No meal by 18:00"))
        assertEquals("/v1/interactions", call.getString("path"))
        assertEquals("No meal by 18:00 (check)", call.getJSONObject("body").getString("title"))
        assertEquals("urgent", call.getJSONObject("body").getString("priority"))
        assertEquals("/v1/speech/speak", FlowSteps.inlineCall(o("type" to "speak", "text" to "Hi"), JSONObject()).getString("path"))
    }

    @Test fun rawCallsGetAStepHintButAreNeverRewritten() {
        val hint = FlowSteps.stepHint(JSONObject("""{"method":"POST","path":"/v1/interactions","body":{"kind":"notice","title":"No meal","text":"{message}"}}"""))!!
        val step = hint.getJSONObject("step")
        assertEquals("notice", step.getString("type")); assertEquals("message", step.getJSONArray("text").getJSONObject(0).getString("\$trigger"))
        assertNotNull(FlowSteps.stepHint(JSONObject("""{"method":"POST","path":"/v1/speaker/say","body":{"text":"hi"}}""")))
        assertNull(FlowSteps.stepHint(JSONObject("""{"method":"POST","path":"/v1/flashlight","body":{"on":true}}""")))
        assertNull(FlowSteps.stepHint(JSONObject("""{"method":"POST","path":"/v1/interactions","body":{"kind":"question","title":"?"}}""")))
    }

    // ---- watch rules -------------------------------------------------------------------------------------------

    private val watchBase = """"data":["camera"],"questions":{"activity":{"type":"choice","instructions":"What is the pet doing?","criteria":{"eating":"e","resting":"r"}}},"tally":{"track":"activity"}"""
    private fun rule(doJson: String) = JSONObject("""{$watchBase,"rules":[{"id":"no-meal","when":{"fewer_than":1,"of":"eating","by":"18:00"},"do":$doJson}]}""")

    @Test fun ruleDoTakesFlowStepOrCall() {
        val flows = mutableListOf<String>()
        val r1 = WatchRules.parse(rule("""{"flow":"wf123","with":{"why":"rule"}}"""), checkFlow = { ref, _ -> flows += ref }).tally!!.rules.single()
        assertEquals("wf123", r1.flow); assertEquals("rule", r1.with!!.getString("why")); assertNull(r1.call); assertEquals(listOf("wf123"), flows)
        val calls = mutableListOf<String>()
        val r2 = WatchRules.parse(rule("""{"step":{"type":"notice","title":{"${'$'}trigger":"message"}}}""")) { c, _ -> calls += c.getString("path") }.tally!!.rules.single()
        assertEquals("notice", r2.step!!.getString("type")); assertEquals(listOf("/v1/interactions"), calls) // the step's call is checked like a raw one
        val r3 = WatchRules.parse(rule("""{"method":"POST","path":"/v1/speaker/say","body":{"text":"{message}"}}""")).tally!!.rules.single()
        assertNotNull(r3.call); assertEquals(1, cc.bridgit.app.watch.WatchTally.hints(WatchRules.parse(rule("""{"method":"POST","path":"/v1/speaker/say","body":{"text":"{message}"}}""")).tally).size)
        fails("exactly one of") { WatchRules.parse(rule("""{"flow":"wf1","method":"POST","path":"/v1/x"}""")) }
        fails("doesn't go with flow") { WatchRules.parse(rule("""{"flow":"wf1","body":{}}""")) }
        fails("needs a flow") { WatchRules.parse(rule("""{"step":{"type":"photo"}}""")) }
        fails("\$flow") { WatchRules.parse(rule("""{"flow":"bad id!"}""")) }
        fails("isn't one of yours") { WatchRules.parse(rule("""{"flow":"wf9"}"""), checkFlow = { _, at -> throw WatchRules.Bad("$at: isn't one of yours") }) }
    }

    // ---- schedules ---------------------------------------------------------------------------------------------

    private val zone: ZoneId = ZoneId.of("Europe/Warsaw")
    private fun manifest(doJson: String, extra: String = "") = JSONObject("""{"actions":[{"id":"a","name":"Morning","when":{"daily":["08:00"]},"do":$doJson$extra}]}""")
    private fun sched(m: JSONObject, flowProblem: (String) -> String? = { null }) = ScheduleRules.parse(m, zone, 0L, { true }, { _, _ -> null }, flowProblem)

    @Test fun scheduleDoTakesTheSameShapes() {
        val call = sched(manifest("""{"call":"POST /v1/camera/snapshot"}""")).actions.single().action as ScheduleRules.Do.Call
        val same = sched(manifest("""{"method":"post","path":"/v1/camera/snapshot"}""")).actions.single().action as ScheduleRules.Do.Call
        assertEquals(call.method, same.method); assertEquals(call.path, same.path)
        val flow = sched(manifest("""{"flow":"morning","with":{"n":1}}""")).actions.single()
        assertEquals(ScheduleRules.Do.Flow("morning", JSONObject("""{"n":1}""")).ref, (flow.action as ScheduleRules.Do.Flow).ref)
        assertTrue(ScheduleRules.describe(flow, zone).contains("start flow morning"))
        val step = sched(manifest("""{"step":{"type":"notice","title":"Daily briefing"}}""")).actions.single()
        assertTrue(ScheduleRules.describe(step, zone), ScheduleRules.describe(step, zone).contains("show you a notice \"Daily briefing\""))
        fun inv(m: JSONObject, part: String, fp: (String) -> String? = { null }) = fails(part) { sched(m, fp) }
        inv(manifest("""{"flow":"x"}""", ""","save_as":"keep""""), "save_as only applies")
        inv(manifest("""{"step":{"type":"notice","title":"t"}}""", ""","send_to":{"url":"https://e.com"}"""), "send_to doesn't go with step")
        inv(manifest("""{"flow":"x"}"""), "not yours") { "not yours" }
        inv(manifest("""{"step":{"type":"wait","seconds":3}}"""), "needs a flow")
        inv(manifest("""{"call":"POST /v1/camera/snapshot","method":"POST"}"""), "exactly one of")
        inv(JSONObject("""{"actions":[{"id":"a","when":{"daily":["08:00"]},"do":{"flow":"x"}},{"id":"b","when":{"after":"a"},"do":{"step":{"type":"speak","text":"hi"}}}]}"""), "starts a flow")
    }

    // ---- starting a flow -----------------------------------------------------------------------------------------

    private fun activeFlow(def: JSONObject, name: String): Pair<String, String> {
        val agent = app.policy.addAgent(name).first.id
        app.platform.workflows.validate(def).strings("required_scopes").forEach { Capabilities.setEnabled(app, it, true); app.platform.grant(agent, it, System.currentTimeMillis() + 3_600_000) }
        val id = app.platform.workflows.propose(agent, def, packageFlow = "dinner").getString("id")
        app.platform.workflows.activate(id)
        return agent to id
    }

    @Test fun aCallerStartsACalledFlowWithItsTriggerData() {
        val (agent, id) = activeFlow(called(o("type" to "notice", "title" to a(o("\$trigger" to "caller"), ": ", o("\$trigger" to "message"), " ", o("\$trigger" to "with.note"))),
            with = o("note" to "text")), "VocabStarter")
        assertNull(app.platform.workflows.startable(agent, id))
        assertNull(app.platform.workflows.startable(agent, "dinner")) // by package flow name too
        assertNotNull(app.platform.workflows.startable(agent, "nope"))
        val started = runBlocking { app.platform.workflows.start(agent, "dinner", o("caller" to "rule", "message" to "No meal by 18:00"), o("note" to "check the bowl")) }
        assertEquals(started.toString(), "started", started.getString("status")); assertEquals(id, started.getString("workflow_id"))
        runBlocking { withTimeout(10_000) { while (app.platform.store.list(agent, "interaction").isEmpty()) delay(100) } }
        assertEquals("rule: No meal by 18:00 check the bowl", app.platform.store.list(agent, "interaction").single().getString("title"))
        val run = app.platform.workflows.runs(agent, id, 1).getJSONArray("runs").getJSONObject(0)
        assertEquals("rule", run.getJSONObject("trigger").getString("caller"))
        // A value of the wrong type starts nothing; another agent can't start it; an unknown flow is not found.
        assertEquals("bad_with", runBlocking { app.platform.workflows.start(agent, id, o("caller" to "agent"), o("note" to 5)) }.getString("status"))
        val other = app.policy.addAgent("VocabOther").first.id
        assertEquals("flow_not_found", runBlocking { app.platform.workflows.start(other, id, JSONObject(), null) }.getString("status"))
        // Paused: not active.
        app.platform.workflows.pause(agent, id)
        assertEquals("flow_not_active", runBlocking { app.platform.workflows.start(agent, id, JSONObject(), null) }.getString("status"))
    }

    @Test fun aRunningFlowDropsTheNextStart() {
        val (agent, id) = activeFlow(called(o("type" to "wait", "seconds" to 3)), "VocabDropper")
        assertEquals("started", runBlocking { app.platform.workflows.start(agent, id, JSONObject(), null) }.getString("status"))
        assertEquals("dropped", runBlocking { app.platform.workflows.start(agent, id, JSONObject(), null) }.getString("status"))
        assertEquals(1, app.platform.store.require(id, agent, "workflow").optInt("dropped_runs"))
    }

    @Test fun onlyCalledOrButtonFlowsCanBeStarted() {
        val (agent, id) = activeFlow(o("name" to "Timer", "trigger" to o("type" to "interval", "seconds" to 900), "steps" to a(notice())), "VocabTimer")
        assertTrue(app.platform.workflows.startable(agent, id)!!.contains("interval trigger"))
        assertEquals("flow_not_active", runBlocking { app.platform.workflows.start(agent, id, JSONObject(), null) }.getString("status"))
    }

    // ---- packages ----------------------------------------------------------------------------------------------

    // The phase 2 (2.0.0) pet monitor, before it had a flow: the base these tests add flows to. 3.0.0 is checked below.
    private fun pet() = JSONObject(File("src/test/resources/applets/pet-monitor-2.0.0.json").readText())
    private fun withFlows(d: JSONObject, ruleDo: JSONObject, vararg flows: JSONObject): JSONObject {
        val p = d.getJSONObject("processing")
        p.put("flows", a(*flows))
        p.getJSONArray("watches").getJSONObject(0).getJSONObject("request").getJSONArray("rules").getJSONObject(0).put("do", ruleDo)
        d.getJSONObject("permissions").put("required", JSONArray(AppletPackage.strings(d.getJSONObject("permissions").getJSONArray("required")) + listOf("workflows")))
        return d
    }
    private val dinner get() = called(o("type" to "notice", "title" to "No meal yet", "text" to a(o("\$trigger" to "message")))).put("id", "dinner")
    private fun bad(d: JSONObject, part: String) = fails(part) { AppletPackage.check(d) }

    @Test fun packageFlowsAndFlowReferences() {
        val d = withFlows(pet(), o("flow" to o("\$flow" to "dinner")), dinner)
        val p = AppletPackage.check(d)
        val plan = AppletPackage.compile(p, AppletPackage.fill(p, null))
        assertEquals(listOf("dinner"), plan.flows.map { it.first }); assertFalse(plan.flows.single().second.has("id"))
        // $flow becomes the package flow's name: bound late, by name, to the applet's own flow.
        val stored = plan.watches.single().second.getJSONArray("rules").getJSONObject(0).getJSONObject("do")
        assertEquals("dinner", stored.getString("flow"))
        assertEquals("dinner", WatchRules.parse(plan.watches.single().second).tally!!.rules.single().flow)
        // The review: the rule says it runs the flow; the flow says what starts it.
        val words = AppletPackage.describe(plan)
        assertTrue(words.joinToString(), words.any { it.contains("If no eating is seen by 18:00, it runs Dinner check.") })
        assertTrue(words.joinToString(), words.any { it.startsWith("Workflow · Dinner check: It runs when the no-meal-by-6pm rule fires. Shows you") })
        // A flow starts nothing on its own scopes' behalf: do.flow adds no permission.
        val derived = AppletPackage.derived(plan, { _, _ -> setOf("camera") }, { c -> if (c == "POST /v1/interactions") "interactions" else null }) { setOf("workflows", "interactions") }
        assertTrue("workflows" in derived)

        bad(withFlows(pet(), o("flow" to "dinner"), dinner), "name the flow as {\"\$flow\": \"dinner\"}")
        bad(withFlows(pet(), o("flow" to o("\$flow" to "lunch")), dinner), "lunch isn't a flow in processing.flows")
        bad(withFlows(pet(), o("flow" to o("\$flow" to "dinner"), "with" to o("x" to 1)), dinner), "doesn't take x")
        bad(withFlows(pet(), o("flow" to o("\$flow" to "dinner")), o("id" to "dinner", "name" to "T", "trigger" to o("type" to "interval"), "steps" to a(notice()))), "has a interval trigger")
        bad(withFlows(pet(), o("flow" to o("\$flow" to "dinner")), dinner, JSONObject(dinner.toString())), "is used twice")
        bad(withFlows(pet(), o("flow" to o("\$flow" to "dinner")), JSONObject(dinner.toString()).put("id", "Dinner!")), "lowercase")
        bad(withFlows(pet(), o("flow" to o("\$flow" to "dinner")), dinner).also { it.getJSONObject("processing").put("workflow", called(notice())) }, "not both")
        bad(withFlows(pet(), o("flow" to o("\$flow" to "dinner")), dinner, dinner.put("id", "b"), JSONObject(dinner.toString()).put("id", "c"), JSONObject(dinner.toString()).put("id", "d"), JSONObject(dinner.toString()).put("id", "e")), "1-4 flows")
        // A $flow outside a do is refused.
        bad(withFlows(pet(), o("flow" to o("\$flow" to "dinner")), JSONObject(dinner.toString()).put("name", o("\$flow" to "dinner"))), "goes in a do")
    }

    @Test fun thePetMonitorRecipeRunsItsDinnerCheckFromTheRuleAndTheCard() {
        val p = AppletPackage.check(JSONObject(File("../site-content/public/recipes/pet-monitor/applet.json").readText()))
        val plan = AppletPackage.compile(p, AppletPackage.fill(p, null))
        assertEquals(listOf("dinner", "review"), plan.flows.map { it.first })
        val review = plan.flows.toMap().getValue("review").getJSONArray("steps")
        assertEquals(listOf("photo","ask_model","record","notice"), (0 until review.length()).map { review.getJSONObject(it).getString("type") })
        assertEquals("clef", review.getJSONObject(1).getString("model"))
        assertEquals(plan.tables.single().id,review.getJSONObject(2).getString("table"))
        assertEquals("dinner", WatchRules.parse(plan.watches.single().second).tally!!.rules.single().flow)
        val words = AppletPackage.describe(plan)
        assertTrue(words.joinToString(), words.any { it.contains("If no eating is seen by 18:00, it runs Dinner check.") })
        assertTrue(words.joinToString(), words.any { it.startsWith("Workflow · Dinner check:") && it.contains("Takes a photo with the rear camera") && it.contains("Can't see the bowl") })
        assertTrue(words.joinToString(), words.any { it.contains("Check bowl now") })
    }

    @Test fun aSingleStepDoDerivesItsEndpointPermission() {
        val d = withFlows(pet(), o("step" to notice(a(o("\$trigger" to "message"))).put("priority", "urgent")), dinner)
        val p = AppletPackage.check(d)
        val plan = AppletPackage.compile(p, AppletPackage.fill(p, null))
        val seen = mutableListOf<String>()
        AppletPackage.derived(plan, { _, _ -> emptySet() }, { c -> seen += c; null }) { emptySet() }
        assertTrue(seen.toString(), "POST /v1/interactions" in seen)
        val words = AppletPackage.describe(plan)
        assertTrue(words.joinToString(), words.any { it.contains("it shows you an urgent notice") })
        assertTrue(words.joinToString(), words.any { it.contains("Nothing in the applet starts it yet") })
    }

    @Test fun oneWorkflowIsStillFlowMain() {
        val d = pet(); d.getJSONObject("processing").put("workflow", o("name" to "Ping", "trigger" to o("type" to "button"), "steps" to a(notice())))
        d.getJSONObject("permissions").put("required", JSONArray(AppletPackage.strings(d.getJSONObject("permissions").getJSONArray("required")) + listOf("workflows")))
        val p = AppletPackage.check(d)
        val plan = AppletPackage.compile(p, AppletPackage.fill(p, null))
        assertEquals(listOf(AppletPackage.MAIN_FLOW), plan.flows.map { it.first })
        assertTrue(AppletPackage.describe(plan).any { it.startsWith("Workflow · Ping: It runs when you tap its button.") })
    }
}
